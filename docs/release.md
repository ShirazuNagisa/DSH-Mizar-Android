# 发版（本地签名 + tag 触发 CI 发布）

release APK 可以在本地用 `keystore/release.jks` 构建并直接分发；同时推送 `v*` tag 仍会触发 GitHub Actions 出包并创建 Release（要求 tag 与 `versionName` 一致）。本地构建与 CI 发布并存。

## 出包

```powershell
$env:GRADLE_USER_HOME = 'E:\gradle-home2'
$env:JAVA_HOME = '<jdk-17>'
# --rerun-tasks 不是可选项：up-to-date 的测试任务一条测试都不会跑，
# `BUILD SUCCESSFUL` 不代表测试通过。0.10.3 就是这样把两条从没执行过的
# 测试带进了 CI（见 docs/android-testing.md 的「A green build is not a green test run」）。
.\gradlew.bat :mizarkit:test :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:assembleRelease

# 产物：app/build/outputs/apk/release/app-release.apk
Copy-Item app\build\outputs\apk\release\app-release.apk dist\dsh-mizar-<版本>.apk
```

版本号在 `app/build.gradle.kts`：`versionCode` 每次发版 +1，`versionName` 与发布版本一致。
本地发版不强制打 tag（0.9.2 起如此），tag 只用于需要 CI 出包的场合。

## 校验

```powershell
# 版本名/版本号
& "$env:ANDROID_HOME\build-tools\<ver>\aapt2.exe" dump badging dist\dsh-mizar-0.10.1.apk | Select-String 'versionName|versionCode'

# 签名（应当报 CN=DSH-Mizar）
& "$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat" verify --print-certs dist\dsh-mizar-0.10.1.apk
```

## 安装到手机

release 包和 debug 包**签名不同**，覆盖安装会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`：

```powershell
# 正式安装（会清掉配对，重新扫码配对一次）
adb uninstall me.shirazu.dshmizar
adb install dist\dsh-mizar-0.10.1.apk

# 只是想在开发机上继续验证（保留配对与设置），装 debug 包即可：
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 记录

| 版本 | versionCode | 产物 | 备注 |
|---|---|---|---|
| 0.9.0 | 38 | `dist/dsh-mizar-0.9.0.apk` | CI 出包（tag `v0.9.0`） |
| 0.9.1 | 39 | `dist/dsh-mizar-0.9.1.apk` | CI 出包（tag `v0.9.1`） |
| 0.9.2 | 40 | — | 重构前的早期本地构建（未发布；名称后来被 0.10.1 取代） |
| 0.10.0 | 41 | `dist/dsh-mizar-0.10.0.apk` | GUI 重构内部版本（tag `v0.10.0`） |
| 0.10.1 | 42 | — | Next pill 触区修复的内部构建；tag `v0.10.1` 已移到 43 版提交，42 的资产被替换 |
| **0.10.1** | **43** | `dist/dsh-mizar-0.10.1.apk` | **对外发布版本**：GUI 重构 + Next 触区修复（原 0.9.2 命名已撤回） |
| 0.10.2 | 44 | `dist/dsh-mizar-0.10.2.apk` | 缺口清单 P1/P2：实时活动、配对进度、按块揭示、路由失败说明、诊断包 |
| **0.10.3** | **45** | `dist/dsh-mizar-0.10.3.apk` | 跟插件 0.8.9：子代理页、逐页上下文、中继档位；附件入口进 `+`；中断轮不再显示「已完成」。首次 CI 因 `ProtocolLogTest` 的顺序问题失败，修复（`a3e4b76`）后 tag 移到该提交 |

### 0.9.2 / 0.10.x 版本沿革（2026-10）

重构期间版本号走了一段弯路，记录如下，避免以后误判新旧：

- `0.9.2 (40)`：重构前的本地签名构建，未发布；只有提交，没有 tag。
- `0.10.0 (41)`：GUI 重构的内部验收构建，对应 tag `v0.10.0`（提交 `e6077d1`）。
- `0.10.1 (42)`：Next pill 触区修复的内部构建，原 tag `v0.10.1`（提交 `6b2fe25`）已移到 43 版。
- `0.10.1 (43)`：最终对外发布版，tag `v0.10.1`（新提交），包含完整重构 + 触区修复，也是唯一建议分发的安装包。
- `0.9.2 (43)` 的临时命名已撤回：`v0.9.2` tag 与 release 已删除；相关提交仍留在 git 历史里。

判断新旧**只看 versionCode**（43 > 42 > 41 > 40），不要用 versionName 比较。GitHub 上只有 `v0.10.0` 作为内部快照保留，正式版本以 `v0.10.1`（43）为准。

从下一个版本起（建议 `0.10.2 (44)`）恢复单向递增：不再复用版本名；只在版本 bump 提交上打 tag；tag 必须与 `versionName` 完全一致（CI 会校验）。
