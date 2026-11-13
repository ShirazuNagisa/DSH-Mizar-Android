#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Rebuild libmizarquickjs.so — QuickJS-NG 0.10.1 plus the JNI bridge — for the
    local agent.

.DESCRIPTION
    The module ships prebuilt native libraries under src/main/jniLibs/<abi>/, so
    a normal `gradlew :app:assembleDebug` never needs the NDK. This script is the
    explicit rebuild step: it runs `ndk-build` over src/main/cpp (vendored
    QuickJS-NG + jni_bridge.c) and writes both ABIs back into jniLibs.

    QuickJS-NG is MIT-licensed; the vendored copy is src/main/cpp/quickjs.

    Requirements: an Android NDK r27+ (r28 verified) and PowerShell 7. The
    NDK is looked up in this order: -Ndk, $env:ANDROID_NDK_HOME,
    $env:ANDROID_NDK_ROOT, the highest version under <sdk>/ndk.

.PARAMETER Abi
    Rebuild only one ABI (x86_64 or arm64-v8a). Default: both.

.PARAMETER Ndk
    Explicit NDK directory.

.EXAMPLE
    ./scripts/build-quickjs.ps1
    ./scripts/build-quickjs.ps1 -Abi x86_64
#>
[CmdletBinding()]
param(
    [string] $Abi = '',
    [string] $Ndk = ''
)

$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path $PSScriptRoot -Parent

function Resolve-Sdk {
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    $localProperties = Join-Path (Split-Path $moduleRoot -Parent) 'local.properties'
    if (Test-Path $localProperties) {
        $line = Get-Content $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) { return ($line -replace '^sdk\.dir=', '') -replace '\\:', ':' }
    }
    throw 'ANDROID_SDK_ROOT / ANDROID_HOME is not set and local.properties has no sdk.dir'
}

if (-not $Ndk) {
    if ($env:ANDROID_NDK_HOME) { $Ndk = $env:ANDROID_NDK_HOME }
    elseif ($env:ANDROID_NDK_ROOT) { $Ndk = $env:ANDROID_NDK_ROOT }
    else {
        $ndkRoot = Join-Path (Resolve-Sdk) 'ndk'
        $candidates = if (Test-Path $ndkRoot) {
            Get-ChildItem $ndkRoot -Directory |
                Where-Object { Test-Path (Join-Path $_.FullName 'ndk-build.cmd') } |
                Sort-Object Name -Descending
        }
        if (-not $candidates) { throw "No NDK found under $ndkRoot. Install one with: sdkmanager `"ndk;28.2.13676358`"" }
        $Ndk = $candidates[0].FullName
    }
}

$ndkBuild = Join-Path $Ndk 'ndk-build.cmd'
if (-not (Test-Path $ndkBuild)) { throw "ndk-build.cmd not found under $Ndk" }

$cpp = Join-Path $moduleRoot 'src\main\cpp'
$arguments = @(
    '-C', $cpp,
    "NDK_PROJECT_PATH=$cpp",
    'APP_BUILD_SCRIPT=Android.mk',
    'NDK_APPLICATION_MK=Application.mk',
    "NDK_OUT=$(Join-Path $moduleRoot 'build\ndk-obj')",
    "NDK_LIBS_OUT=$(Join-Path $moduleRoot 'src\main\jniLibs')"
)
if ($Abi) { $arguments += "APP_ABI=$Abi" }

Write-Host "build-quickjs: ndk-build via $Ndk"
& $ndkBuild @arguments
if ($LASTEXITCODE -ne 0) { throw "ndk-build failed with exit code $LASTEXITCODE" }

Write-Host 'build-quickjs: outputs'
Get-ChildItem (Join-Path $moduleRoot 'src\main\jniLibs') -Recurse -Filter *.so |
    Select-Object FullName, Length |
    Format-Table -AutoSize

# The whole reason this script exists for Android 15+: every LOAD segment must
# be 16 KB aligned, or the library cannot load on a 16 KB page-size device.
$python = Get-Command python -ErrorAction SilentlyContinue
if ($python) {
    $check = @'
import struct, os, sys
def load_align(path):
    with open(path, "rb") as f: data = f.read()
    is64 = data[4] == 2
    phoff = struct.unpack_from("<Q" if is64 else "<I", data, 0x20 if is64 else 0x1c)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36 if is64 else 0x2a)
    aligns = []
    for i in range(phnum):
        off = phoff + i * phentsize
        if struct.unpack_from("<I", data, off)[0] != 1: continue
        aligns.append(struct.unpack_from("<QQQQQQ", data, off + 8)[5] if is64 else struct.unpack_from("<IIIIIII", data, off + 4)[6])
    return min(aligns)
base = sys.argv[1]
for abi in sorted(os.listdir(base)):
    path = os.path.join(base, abi, "libmizarquickjs.so")
    if not os.path.isfile(path): continue
    align = load_align(path)
    print(("OK  " if align >= 16384 else "FAIL") + " " + abi + " min_align=0x%x" % align)
'@
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) 'check_mizarquickjs_align.py'
    Set-Content -Path $temp -Value $check -Encoding utf8
    & $python.Source $temp (Join-Path $moduleRoot 'src\main\jniLibs')
}
