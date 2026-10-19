#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Drive the Android emulator this project debugs against, without a USB cable.

.DESCRIPTION
    One command per step of the loop: create the AVD, boot it, wait for it, install
    the APK, pair it to the host on this machine, take a screenshot, drive a gesture,
    read the log, stop it.

    The pairing is the interesting part: the emulator reaches the host's Mizar
    listener through 10.0.2.2 (the emulator's alias for the host's loopback), so the
    link is direct and the public relay is not involved at all. A pairing code is
    still needed -- it comes from the DSH-Mizar panel, once per AVD.

.EXAMPLE
    ./emu.ps1 create
    ./emu.ps1 boot                # blocking; run it as a background job under DSH
    ./emu.ps1 wait
    ./emu.ps1 install
    ./emu.ps1 pair ZH8M-NVT0
    ./emu.ps1 shot after-pair
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('create', 'boot', 'start', 'wait', 'install', 'pair', 'shot', 'swipe', 'tap', 'logcat', 'grant', 'locale', 'stop', 'status')]
    [string] $Command,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Rest
)

$ErrorActionPreference = 'Stop'

$Sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       else { 'E:\Projects\Environment\android-sdk' }

$Adb = Join-Path $Sdk 'platform-tools\adb.exe'
$Emulator = Join-Path $Sdk 'emulator\emulator.exe'
$AvdManager = Join-Path $Sdk 'cmdline-tools\latest\bin\avdmanager.bat'

$AvdName = 'mizar35'
$Image = 'system-images;android-35;google_apis;x86_64'
$Port = 5556
$Serial = "emulator-$Port"

# The host's Mizar listener binds 127.0.0.1; 10.0.2.2 is how the emulator reaches it.
$HostAuthority = '10.0.2.2:19390'

$RepoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$Apk = Join-Path $RepoRoot 'app\build\outputs\apk\release\app-release.apk'
$Shots = Join-Path $RepoRoot '.devstate'
$AvdDir = Join-Path $env:USERPROFILE ".android\avd\$AvdName.avd"

function Fail([string] $message) { Write-Error $message; exit 1 }
function Note([string] $message) { Write-Host "emu: $message" }

function Invoke-Adb {
    param([string[]] $Arguments, [int] $TimeoutSeconds = 30)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Adb
    $psi.Arguments = (@('-s', $Serial) + $Arguments) -join ' '
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $process = [System.Diagnostics.Process]::Start($psi)
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        try { $process.Kill() } catch { }
        return $null
    }
    return $process.StandardOutput.ReadToEnd().Trim()
}

function Test-Booted {
    $value = Invoke-Adb -Arguments @('shell', 'getprop', 'sys.boot_completed') -TimeoutSeconds 20
    return $value -eq '1'
}

if (-not (Test-Path $Adb)) { Fail "adb not found at $Adb (set ANDROID_SDK_ROOT)" }

switch ($Command) {
    'create' {
        if (-not $env:JAVA_HOME) { Fail 'JAVA_HOME must be set for avdmanager' }
        if (Test-Path $AvdDir) {
            Note "removing existing AVD $AvdName"
            Remove-Item -Recurse -Force $AvdDir
            Remove-Item -Force (Join-Path $env:USERPROFILE ".android\avd\$AvdName.ini") -ErrorAction SilentlyContinue
        }
        Note "creating $AvdName from $Image"
        cmd /c "echo no| `"$AvdManager`" create avd -n $AvdName -k `"$Image`" -d pixel_6 --force"
        if ($LASTEXITCODE -ne 0) { Fail 'avdmanager failed' }

        # swiftshader_indirect hangs under WHPX with this image; `host` renders through
        # the real GPU and is the configuration that has booted here. The temp data
        # partition is removed so pairing and the installed APK survive reboots.
        $config = Join-Path $AvdDir 'config.ini'
        $lines = Get-Content $config
        $patched = foreach ($line in $lines) {
            switch -Regex ($line) {
                '^disk\.dataPartition\.path\s*=' { continue }
                '^hw\.gpu\.enabled\s*=' { 'hw.gpu.enabled = yes'; continue }
                '^hw\.gpu\.mode\s*=' { 'hw.gpu.mode = host'; continue }
                '^hw\.ramSize\s*=' { 'hw.ramSize = 4096'; continue }
                default { $line }
            }
        }
        Set-Content -Path $config -Value $patched -Encoding utf8
        Note 'AVD ready'
    }

    'boot' {
        # Blocking on purpose: under the harness this must run as a background job so
        # the process tree outlives the tool call that started it.
        & $Emulator -avd $AvdName -no-window -no-audio -no-boot-anim `
            -gpu host -memory 4096 -port $Port -no-snapshot -no-metrics
    }

    'start' {
        $existing = Get-Process -Name 'qemu-system-x86_64-headless' -ErrorAction SilentlyContinue
        if ($existing) { Note 'emulator already running'; exit 0 }
        Start-Process -FilePath $Emulator -ArgumentList @(
            '-avd', $AvdName, '-no-window', '-no-audio', '-no-boot-anim',
            '-gpu', 'host', '-memory', '4096', '-port', $Port, '-no-snapshot', '-no-metrics'
        ) -WindowStyle Hidden
        Note 'emulator launched (detached)'
    }

    'wait' {
        $deadline = (Get-Date).AddMinutes(5)
        while ((Get-Date) -lt $deadline) {
            if (Test-Booted) { Note 'booted'; exit 0 }
            Start-Sleep -Seconds 5
        }
        Fail 'emulator did not finish booting within 5 minutes (try ./emu.ps1 stop, clear locks, boot again)'
    }

    'install' {
        if (-not (Test-Path $Apk)) { Fail "no release APK at $Apk -- run :app:assembleRelease first" }
        & $Adb -s $Serial install -r $Apk
        if ($LASTEXITCODE -ne 0) { Fail 'install failed' }
        Note 'installed'
    }

    'pair' {
        if ($Rest.Count -lt 1) { Fail 'usage: ./emu.ps1 pair <code>' }
        $code = $Rest[0]
        $uri = "mizar://pair?h=$HostAuthority&c=$code&n=Acer-emu&v=1"
        & $Adb -s $Serial shell "am start -a android.intent.action.VIEW -d '$uri'"
        Start-Sleep -Seconds 4
        $log = & $Adb -s $Serial logcat -d -s mizar-notify:* 2>&1 | Select-Object -Last 5
        $log | ForEach-Object { Write-Host $_ }
    }

    'shot' {
        $name = if ($Rest.Count -ge 1) { $Rest[0] } else { 'shot' }
        New-Item -ItemType Directory -Force -Path $Shots | Out-Null
        $target = Join-Path $Shots "emu-$name.png"
        & $Adb -s $Serial shell screencap -p /sdcard/emu-shot.png
        & $Adb -s $Serial pull /sdcard/emu-shot.png $target | Out-Null
        Note "saved $target"
    }

    'swipe' {
        if ($Rest.Count -lt 4) { Fail 'usage: ./emu.ps1 swipe <x1> <y1> <x2> <y2> [ms]' }
        $duration = if ($Rest.Count -ge 5) { $Rest[4] } else { '400' }
        & $Adb -s $Serial shell input swipe $Rest[0] $Rest[1] $Rest[2] $Rest[3] $duration
    }

    'tap' {
        if ($Rest.Count -lt 2) { Fail 'usage: ./emu.ps1 tap <x> <y>' }
        & $Adb -s $Serial shell input tap $Rest[0] $Rest[1]
    }

    'logcat' {
        $tag = if ($Rest.Count -ge 1) { $Rest[0] } else { 'mizar-notify' }
        & $Adb -s $Serial logcat -d -s "${tag}:*" 2>&1 | Select-Object -Last 40
    }

    'grant' {
        & $Adb -s $Serial shell pm grant me.shirazu.dshmizar android.permission.POST_NOTIFICATIONS
        Note 'notification permission granted (userdebug image: shell can grant, unlike the phone)'
    }

    'locale' {
        # The app ships both languages; this makes screenshots match the phone.
        & $Adb -s $Serial shell 'setprop persist.sys.locale zh-CN; setprop persist.sys.language zh; setprop persist.sys.country CN'
        & $Adb -s $Serial shell 'setprop ctl.restart zygote'
        Note 'locale set to zh-CN; the framework restarts in about a minute'
    }

    'stop' {
        & $Adb -s $Serial emu kill 2>&1 | Out-Null
        Start-Sleep -Seconds 3
        Get-Process -Name 'qemu-system-x86_64-headless', 'emulator' -ErrorAction SilentlyContinue |
            Stop-Process -Force
        foreach ($lock in @('hardware-qemu.ini.lock', 'multiinstance.lock')) {
            $path = Join-Path $AvdDir $lock
            if (Test-Path $path) { Remove-Item -Force $path -ErrorAction SilentlyContinue }
        }
        Note 'stopped'
    }

    'status' {
        & $Adb devices
        if (Test-Booted) {
            Note 'booted'
            $version = Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', 'me.shirazu.dshmizar') -TimeoutSeconds 30
            $version | Select-String -Pattern 'versionName|versionCode' | Select-Object -First 2 | ForEach-Object { $_.Line.Trim() }
        } else {
            Note 'not booted'
        }
    }
}
