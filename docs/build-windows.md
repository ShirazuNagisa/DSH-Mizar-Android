# Windows 上构建 Android 客户端

## 缓存目录必须独立（重要）

**不要与插件仓库共用 `GRADLE_USER_HOME`。** 本机实测：共用 `E:\Projects\DSH-Mizar\.gradle-home` 时，
`:app:processDebugResources` 会稳定失败在一个与代码无关的错误上：

```
AAPT: …\app\build\intermediates\stable_resource_ids_file\debug\processDebugResources\stableIds.txt:
      error: failed to open: 无效的句柄 (13)
```

同一份源码、同一套版本（AGP 8.7.3 / Kotlin 2.1.0 / Gradle 8.11.1 / buildTools 35.0.0）换到干净的
缓存目录后立刻构建成功（41.33 MB）。已排除：项目配置差异、路径与 ACL、残留 build 产物、
Gradle 构建缓存开关、守护进程、`TEMP`/`TMP`、预建目标目录/文件、aapt2 产物缓存、
AGP 版本降级（8.6.1 同样失败）。**结论：共享缓存里有坏条目，让 AAPT2 拿到无效句柄。**

## 推荐的一次性准备

```powershell
# JDK 17
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.12.7-hotspot'

# 独立的 Gradle 用户目录（只需建一次，之后一直用它）
$env:GRADLE_USER_HOME = 'E:\gradle-home2'

# SDK 路径（本地文件，已在 .gitignore 里）
'sdk.dir=E\:\\Projects\\Environment\\android-sdk' | Set-Content local.properties -Encoding ASCII
```

`gradle-home2` 里建议放两样东西，避免每次都重新下载：

- `init.d\mirrors.init.gradle` —— 国内镜像（阿里云 public / google / gradle-plugin）
- `wrapper\dists\` —— 预置的 Gradle 8.11.1 发行版（`services.gradle.org` 在这台机器上极慢）

## 构建与安装

```powershell
$env:GRADLE_USER_HOME = 'E:\gradle-home2'
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.12.7-hotspot'
.\gradlew.bat :app:assembleDebug          # → app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

协议核心的测试（不需要 Android SDK，秒级）：

```powershell
.\gradlew.bat :mizarkit:test
```

## 如果又出现「无效的句柄」

按顺序做，前两步通常就够了：

1. 确认 `GRADLE_USER_HOME` 不是插件仓库那一份；
2. `.\gradlew.bat --stop`，删掉 `app\build` 与 `mizarkit\build` 再构建；
3. 仍失败就**换一个全新的空目录**当 `GRADLE_USER_HOME`（把上面那两样东西带过去）——
   这就是当初定位到「缓存坏条目」的办法。

## 与本仓库对应的主机端

客户端连的是 **DSH-Mizar**（插件）仓库；中继是 **DSH-Mizar-Relay**。三个仓库各自独立，
不要把源码混在一起。
