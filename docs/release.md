# 发版（本地签名，无 CI）

0.9.2 起不再依赖 CI 出包：release APK 在这台机器上用 `keystore/release.jks` 签名，构建脚本不变，产物直接分发。

## 出包

```powershell
$env:GRADLE_USER_HOME = 'E:\gradle-home2'
$env:JAVA_HOME = '<jdk-17>'
.\gradlew.bat :mizarkit:test :app:assembleRelease

# 产物：app/build/outputs/apk/release/app-release.apk
Copy-Item app\build\outputs\apk\release\app-release.apk dist\dsh-mizar-<版本>.apk
```

版本号在 `app/build.gradle.kts`：`versionCode` 每次发版 +1，`versionName` 与发布版本一致。
本地发版不强制打 tag（0.9.2 起如此），tag 只用于需要 CI 出包的场合。

## 校验

```powershell
# 版本名/版本号
& "$env:ANDROID_HOME\build-tools\<ver>\aapt2.exe" dump badging dist\dsh-mizar-0.9.2.apk | Select-String 'versionName|versionCode'

# 签名（应当报 CN=DSH-Mizar）
& "$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat" verify --print-certs dist\dsh-mizar-0.9.2.apk
```

## 安装到手机

release 包和 debug 包**签名不同**，覆盖安装会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`：

```powershell
# 正式安装（会清掉配对，重新扫码配对一次）
adb uninstall me.shirazu.dshmizar
adb install dist\dsh-mizar-0.9.2.apk

# 只是想在开发机上继续验证（保留配对与设置），装 debug 包即可：
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 记录

| 版本 | versionCode | 产物 | 备注 |
|---|---|---|---|
| 0.9.0 | 38 | `dist/dsh-mizar-0.9.0.apk` | CI 出包（tag `v0.9.0`） |
| 0.9.1 | 39 | — | 仅本地/调试包 |
| 0.9.2 | 40 | `dist/dsh-mizar-0.9.2.apk` | 首个本地签名的正式包，不再走 CI |
| 0.10.0 | 41 | `dist/dsh-mizar-0.10.0.apk` | GUI 重构：continuous corners、springs、glass、T1/T2/T3/T5 |
| 0.10.1 | 42 | `dist/dsh-mizar-0.10.1.apk` | 真机回归修复：Next pill 的有效触区从 37dp 修正到 48dp（0.10.0 的 header 布局把 48dp 目标裁掉了） |
