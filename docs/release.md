# 发版（本地签名 + tag 触发 CI 发布）

release APK 可以在本地用 `keystore/release.jks` 构建并直接分发；同时推送 `v*` tag 仍会触发 GitHub Actions 出包并创建 Release（要求 tag 与 `versionName` 一致）。本地构建与 CI 发布并存。

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
| 0.9.1 | 39 | `dist/dsh-mizar-0.9.1.apk` | CI 出包（tag `v0.9.1`） |
| 0.9.2 | 40 | — | 早期本地构建（未发布） |
| 0.10.0 | 41 | `dist/dsh-mizar-0.10.0.apk` | GUI 重构内部版本 |
| 0.10.1 | 42 | `dist/dsh-mizar-0.10.1.apk` | Next pill 触区修复内部版本 |
| **0.9.2** | **43** | `dist/dsh-mizar-0.9.2.apk` | **对外发布版本**：GUI 重构 + Next 触区修复；按要求复用 0.9.2 版本名，versionCode 43 高于内部版本 |

### 0.9.2 / 0.10.x 版本沿革（2026-10）

重构期间版本号走了一段弯路，记录如下，避免以后误判新旧：

- `0.9.2 (40)`：重构前最后一个本地签名构建，未发布、没有 tag；后因发布命名被复用。
- `0.10.0 (41)`：GUI 重构的内部验收构建，对应 tag `v0.10.0`（提交 `e6077d1`）。
- `0.10.1 (42)`：重构后的 Next pill 触区修复版，对应 tag `v0.10.1`（提交 `6b2fe25`）。
- `0.9.2 (43)`：最终对外发布版，对应 tag `v0.9.2`（提交 `1cde59b`），包含完整重构 + 触区修复，也是唯一建议分发的安装包。

判断新旧**只看 versionCode**（43 > 42 > 41 > 40），不要用 versionName 比较：版本名在这里被复用了一次，`0.10.1` 的字典序大于 `0.9.2`，但它的 versionCode 更小、时间也更早。GitHub 上 `v0.10.0` / `v0.10.1` 仅作内部快照，正式版本以 `v0.9.2` 为准。

从下一个版本起恢复单向递增（建议 `0.9.3 (44)`，具体版本名待定）：不再复用版本名；只在版本 bump 提交上打 tag；tag 必须与 `versionName` 完全一致（CI 会校验）。
