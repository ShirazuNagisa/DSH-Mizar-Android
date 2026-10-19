# App GUI 重构对接文档

**给谁**：重构 `app/` 界面层的同学。
**给谁看**：重构完成后接手验证与修 bug 的人。

一句话的分工：**协议层、连接层、通知层一行不动；界面层可以整个重写。**

---

## 一、硬约束（这几处改了会坏，且坏得不明显）

| 层 | 位置 | 为什么不能动 |
|---|---|---|
| 协议核心 | `mizarkit/` | 纯 Kotlin/JVM，帧编解码、连接状态机、会话投影、Markdown。它自带测试，是唯一能在没有手机的情况下验证协议的地方。要加能力先加测试 |
| 状态与动作 | `AppViewModel` 的公开函数 + `UiState` 字段 | 界面和它之间的唯一契约。**可以新增字段/函数，不要删改语义** |
| 通知规则 | `AppViewModel.announceFinishedTurns` / `onTurnSettled` / `CompletionAnnouncer` | 判定顺序是踩出来的，见 [foreground-service.md](foreground-service.md) 的六个坑。界面层任何"顺手改一下"都会让通知静默消失 |
| 生命周期接线 | `MainActivity`：`onStart → setPresence(true) + ConnectionService.start`；`onStop → setPresence(false)`；`onDestroy(isFinishing) → stop` | `foreground` 决定"要不要弹通知"，误判一次就等于把人正看着的通知发出去、或把该发的压掉 |
| 通知 id 与频道 | `push/Push.kt` | 完成通知 base 2000、待办 base 1000、`abs(hash) % 900`；频道 `mizar-alerts`（会响）与 `mizar-connection`（静默常驻）。负 id 会被系统**静默丢弃** |
| 插件镜像 | `plugin/`、`scripts/sync-plugin.mjs` | 宿主侧代码，与本仓库的界面无关。**不要在这里改任何东西** |
| 宿主仓库 | `E:\Projects\DSH-Mizar` | iOS 与 Android 共用，保持上游原样 |

重构允许的：布局、组件、导航、字体/配色、动画、无障碍、字符串组织、把大文件拆开。

---

## 二、现在的界面结构（可重写）

```
app/src/main/kotlin/me/shirazu/dshmizar/
├── MainActivity.kt            4KB   Compose 宿主 + 生命周期接线（勿改）
├── AppViewModel.kt           66KB   状态与动作（对外契约，勿改语义）
├── ui/
│   ├── Screens.kt            29KB   RootScreen / HomeScreen / UnpairedScreen / FailedScreen
│   │                                SessionList、设置弹窗、主机选择、新建会话、扫码页
│   ├── ConversationScreens.kt 57KB  SessionScreen：对话列表、轮次分区、消息、审批/提问卡、输入框
│   ├── Theme.kt               4KB   主题（`ThemeMode`），中性配色
│   ├── Format.kt              2KB   时间/耗时格式化
│   └── Scanner.kt             4KB   二维码扫描
├── push/                     12KB   前台服务、通知、FCM 占位（勿改）
└── store/                     8KB   ActivityStore / SessionIndexStore（勿改）
```

**入口只有一个**：`RootScreen(model, incomingPairUri, onPairUriHandled)`。它按 `state.phase` 分派到首页 / 未配对 / 失败页；进会话是 `SessionScreen(model, state)`。

拆文件时保留这两个入口的签名即可，内部组件随便重组。

---

## 三、状态与动作（对接表）

界面**只做两件事**：读 `model.state`，调 `model.xxx()`。不要自己开协程去碰 socket。

### 状态（`UiState`，节选常用）

| 字段 | 含义 |
|---|---|
| `phase` | `LAUNCHING / UNPAIRED / CONNECTING / READY / FAILED`，决定显示哪个页 |
| `host`、`hosts`、`welcome` | 当前/已配对主机、握手信息 |
| `sessions`、`groups`、`titles`、`workspacePaths` | 会话列表、按工作区分组、标题、工作区集合 |
| `selectedSessionId`、`conversation`、`turns` | 打开的会话、已合并的消息、按轮次的分组 |
| `stats`、`statsError`、`conversationError`、`problem` | 各类错误/统计，用于状态行与错误提示 |
| `pending` | 待审批 + 待回答（卡片的数据源） |
| `modelCatalog`、`permissionCatalog`、`modelChoice` | 模型/权限选择器 |
| `reconnecting`、`disconnectReason` | 断线横幅（延迟 2 秒出现，见下） |
| `needsPairing`、`themeMode`、`seenAt` | 重新配对引导、主题、已读时间 |
| `loadingConversation`、`busy`、`refreshing` | 加载态 |
| `oldestSeq`、`hasMore`、`exhausted`、`newestSeq` | 翻页与增量位置 |

### 动作（`AppViewModel` 公开函数）

```kotlin
start()                                  // 启动即调用（MainActivity 已接）
pair(target, deviceName)                 // 配对
retry() / dismissProblem()               // 失败重试、清错误
openHost(hostId) / forget(hostId)        // 切换/删除主机
startPairing()                           // 生成配对信息
refreshSessions()                        // 下拉刷新会话列表
loadTitles(sessionIds)                   // 列表可见时按需取标题
selectSession(sessionId?)                // 进入/退出会话
createSession(cwd)                       // 新建会话
reload(tail, afterSeq?)                  // 重新加载会话（suspend，慎用在 UI 线程）
loadOlder()                              // 向上翻页
sendPrompt(text) / cancelTurn()          // 发送、中断
refreshConversation()                    // 下拉刷新对话
refreshInteractions()                    // 手动刷待办
answerApproval(requestId, outcome)       // 审批
answerQuestions(callId, answers)         // 提问作答
loadCatalogs() / setModel(selection) / setPermission(preset)
registerPush() / setTheme(mode) / setPresence(foreground)
```

---

## 四、必须保留的交互行为（回归清单）

这些都是有原因的，重构时容易"优化"掉：

1. **冷启动直接停在最新消息**，不能看到滚动过程。做法是定位后再绘制。
2. **待办卡片出现/消失会改变视口高度**：如果原本跟随底部，必须重新锚定，否则最新消息被切掉一半。
3. **下拉刷新**（会话列表与对话页都有）+ 对话页的「回到最新」按钮。
4. **审批/提问卡片**：可选、可提交；提交后卡片消失、状态回到「正在思考…」。
5. **状态行**：运行中 / 空闲 / 耗时。
6. **断线横幅延迟 2 秒**才出现。1 秒的抖动不该闪"正在连接"（实测过一次网络切换闪 9 次）。
7. **六个页面各就各位**：未配对、失败、首页、会话、扫码、各种弹窗。
8. **代码块复制**、消息长按菜单等已有交互不要丢。

---

## 五、构建 / 测试 / 发布

```powershell
$env:GRADLE_USER_HOME='E:\gradle-home2'                      # 必须；与插件仓库的缓存分开，否则 AAPT2 会互相踩
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.12.7-hotspot'

.\gradlew.bat :mizarkit:test            # 纯 JVM，秒级
.\gradlew.bat :app:assembleRelease      # 签名 APK → app/build/outputs/apk/release/
```

- **必须用 release 包**：debug 包签名不同，装不上现有 release 签名（会提示覆盖冲突）。
- 版本号在 `app/build.gradle.kts`：`versionCode` 递增、`versionName` 与 git tag 一致。
- 发布：`git tag vX.Y.Z && git push origin main vX.Y.Z`，CI 自动构建签名 APK 并发 release（见 `.github/workflows/release.yml`）。**不要手工上传**。

---

## 六、真机环境与验证方法

| 项 | 值 |
|---|---|
| 手机 | OnePlus 12（PJD110），adb 序列号 `8711ae53` |
| adb | `E:\Projects\Environment\android-sdk\platform-tools\adb.exe` |
| 宿主 | DSH 桌面端，插件监听 `127.0.0.1:19390`，手机通过 `adb reverse tcp:19390 tcp:19390` 或公网中继连入 |
| App 日志 | `adb logcat -s mizar-notify`（连接、事件、通知判定都在这个 tag 下） |

三条最常用的验证命令：

```bash
adb shell dumpsys notification --noredact | grep 'pkg=me.shirazu.dshmizar'   # 通知是否真的发出（id 1 = 前台服务，2000+ = 完成通知）
adb logcat -d -s mizar-notify                                                # 连接/事件/判定过程
adb shell dumpsys activity activities | grep topResumedActivity              # App 是否在前台（决定通不通知）
```

---

## 七、已知限制（不要在 UI 层"修"）

1. **手机作答后桌面卡片不关** —— 宿主 waterfall 的固有行为，agent 已正确继续。详见 [plugin.md](plugin.md)「已知限制」。UI 层不要试图用轮询或伪造事件去关它。
2. **ColorOS 会冻结后台进程** —— 需要手机设置里允许后台活动 + 自启动。UI 层无法绕过。
3. **断线期间结束的轮次**由插件补发（连接后 2 秒），UI 层不需要做任何事。
4. **`session.refresh` 宿主不实现** —— 客户端首次被拒后自动退回三次普通读取，别在 UI 层重复实现。

---

## 八、重构完成的验收标准

界面怎么变都行，但下面这些必须成立：

```bash
.\gradlew.bat :mizarkit:test :app:assembleRelease    # 全绿
adb install -r app/build/outputs/apk/release/app-release.apk
adb reverse tcp:19390 tcp:19390
```

手工走一遍（每步都要过）：

1. 冷启动 → 会话列表按工作区分组显示，标题正确
2. 进入会话 → 直接停在最新消息，无滚动动画
3. 发一句话 → 运行中状态出现 → 回答流式出现
4. **App 退到后台**，让这一轮结束 → **收到「会话完成」通知**；日志有 `settled … posted=true`
5. 前台时结束一轮 → **不**弹通知；日志有 `on screen, nothing to announce`
6. 断网 10 秒再恢复 → 横幅延迟 2 秒出现、自动重连；日志有 `reconnect attempt` / `reconnected`
7. 触发一次审批/提问 → 手机卡片可作答 → 卡片消失、agent 继续
8. 杀掉 DSH 再启动 → 手机自动重连，列表与对话不空白

任何一条不过，先看日志再改代码。

---

## 九、协作约定

- **一个提交一件事**，提交信息说清"为什么"，不只是"改了什么"。
- 碰到协议层疑问（帧字段、事件时序）先问，不要在前端猜；`mizarkit` 的测试是唯一权威。
- 不要改 `plugin/`、`scripts/`、`.github/`，也不要动 `E:\Projects\DSH-Mizar`。
- 重构期间如果必须改 `UiState` 或 ViewModel 的函数签名，**先在这个文档里记一笔**，否则接手排查的人会照着旧契约去读。

---

## 十、重构前基线（2026-10-18，0.8.21 / versionCode 36）

重构完成后逐条对比，行为一致才算通过：

| 场景 | 基线表现 |
|---|---|
| 冷启动 | **直接恢复上次打开的会话**（不是先显示列表），状态行显示「正在思考…」如果那一轮还在跑 |
| 返回列表 | 顶部 `Acer · N 个会话`，按工作区分组（bouncing-balls 1 / python 数据分析 1 / DSH-Mizar 2 / project01 1），右上刷新 + 设置，列表下方是「+ 新建会话」 |
| 后台期间完成一轮 | 通知栏出现完成通知（id 2000–2899），正文是「会话标题 · 耗时 · 回答首行」 |
| 前台期间完成一轮 | 不弹通知，日志 `settled …: on screen, nothing to announce` |
| 轮询与事件重复读到同一轮 | 第二次判定 `already announced`，不重复弹 |
| 连接稳定 | `heartbeat: connected=true silent=0` 每 61 秒一次，10 分钟无中断 |
| 通知栏常驻 | 只有 id=1（前台服务）；完成通知随点即消 |

网络路径基线：手机先走公网中继（`connected to Relay(...)`），USB 插上并 `adb reverse` 后不会自动切换 —— 这是正常的，App 的 endpoint 就是中继。

---

## 十一、契约增补记录

重构期间对 `UiState` / `AppViewModel` 的改动都记在这里，方便后来的人按新契约阅读。

| 增补 | 说明 |
|---|---|
| `UiState.quietHours` + `setQuietHours(on)` | 静音时段开关，设置页持有；通知判定直接读存储，不读这个字段 |
| `createSessionWithPrompt(cwd, prompt)` | 新建会话并立刻发出第一条消息（首页空状态用），等价于 `createSession` + `sendPrompt` |

以上都是**新增**，没有删除或改变既有语义。

## 十二、通知文案（两语言）

| 项 | 英文 | 中文 |
|---|---|---|
| 常驻连接 | Connected to the computer — this notice keeps the connection alive in the background. | 已连接到电脑 · 这条常驻通知让 App 在后台保持连接 |
| 常驻渠道 | Background connection | 后台连接 |
| 告警渠道 | Agent alerts | Agent 提醒 |
| 待办标题 | The agent is waiting | Agent 正在等你回复 |
| 待办正文 | — open DSH-Mizar to answer. | — 打开 DSH-Mizar 回答。 |
| 失败诊断 | `diagnosis_*` 七条 | 同名七条 |

两个 `ensureChannel()` 现在每次启动都会写一遍渠道名与描述。**不要改回"存在就 return"**：那样渠道名会永远停在第一次创建时的语言，装过旧版本的手机改不过来（Android 允许更新名字/描述，只拒绝提高重要性）。

## 十三、iOS 0.9.1 移植（进行中）

参照 iOS `2751d17`（0.9.1 / build 23）。进度按"已移植 / 待移植"分开记，接手的人不必重新读一遍 Swift。

### 已移植

| iOS | Android | 说明 |
|---|---|---|
| `ShellState` 0..1 → **-1..1** | `ShellState.progress` | -1 轮次索引、0 对话、1 侧边栏。一个数管两个抽屉，互斥且不会"关一个推进另一个" |
| 手势按点数而非宽度 | `drawerDrag` | 两个抽屉不等宽，位移按各自宽度换算；方向在每次手势开始时由 `settledAt` 决定 |
| 卡片朝开的那一侧偏移 | `ConversationCard` | `translationX` 按 `progress` 的符号取宽度 |
| 右侧轮次索引 | `ui/shell/TurnIndex.kt` | 一行一轮：序号、提示首行、耗时、状态点 |
| 点行 → 收起 + 滚到该轮 + 闪一次 | `MizarShell` + `ConversationList` | 列表 state 提到 shell，按 session 记忆；高亮是 600ms 渐隐的洗色 |
| 按时长画的下划线 | `TurnIndexRow` | 宽度 = 该轮耗时 / 屏幕上最长的一轮 |
| 只看未完成 | `TurnIndex` 的 `FilterChip` | 筛选只影响显示，点击仍映射回原始下标 |
| 标题点击打开索引 | `SessionScreen.onOpenIndex` | 菜单按钮开侧边栏，标题开索引 |
| 索引里标出屏幕上的轮次 | `onScreen: Set<Int>` | 从 `LazyListState.layoutInfo` 读，两个视图不会各说各的 |

### 待移植 / 替代方案

| iOS | 计划 |
|---|---|
| **所有弹层 → 锚定 popover**（`Popover.swift`） | Compose 没有 SwiftUI 的 popover 定位，替代方案是用 `DropdownMenu`/`Popup` 挂在触发它的 chip 的 Box 里，而不是整屏 sheet。涉及 `Sheets.kt` 的四个 chip 菜单 + 侧边栏三个对话框；改动是"把 sheet 内容搬进锚点"，不动数据 |
| iPad 独立列 + 刻度轨 | 手机上没有对应形态；宽屏（≥600dp）时索引可作为常驻列，暂缓 |
| 连接面板 `ConnectionPanel.swift` | 侧边栏底部现在只有连接状态一行；面板要展示地址/中继/延迟，属于新面板，未做 |
| 弹出命名提示 | 未做 |

**注意**：`TurnIndex` 的等宽假设与 `Space` 尺度都来自 0.9.0 的设计语言，改它时要连着 `MizarShell` 的抽屉宽度一起看（`min(320.dp, maxWidth * 0.84f)`）。

## 十四、0.9.0 真机验收（2026-10-19，OnePlus 12 / PJD110）

版本：`versionName 0.9.0` / `versionCode 38`；发布 tag `v0.9.0`，CI `release` 成功并上传 `dsh-mizar-0.9.0.apk`。

| 类别 | 项 | 结果 |
|---|---|---|
| GUI | B1 搜索框「搜索」垂直居中 | ✅ |
| GUI | B2/B3 侧边栏滑入、右滑开 / 左滑关 | ✅（修了手势只被调用一次、位移只算单步两个 bug） |
| GUI | B4/B5/B6/B8 轮次索引：行、时长下划线、只看未完成、点行滚动+高亮 | ✅ |
| GUI | B7 屏幕上的轮次加底色 | ✅（像素采样确认：237.5 vs 243 的背景差） |
| GUI | B9 左滑开索引 / 右滑收起 | ✅ |
| GUI | B10 连接面板（状态/地址/路径/延迟） | ✅ |
| GUI | B11/B12 列表分组、未读点、会话页 | ✅ |
| 行为 | C1 冷启动恢复会话 | ✅ |
| 行为 | C2 后台完成通知 | ✅ `id=2530`、中文、`posted=true` |
| 行为 | C3 前台抑制 | ✅ 前台轮次结束不出通知，通知栏只剩 id=1 |
| 行为 | C4 断线横幅 + 自动重连 | ✅（修了横幅压状态栏） |
| 行为 | C5 通知权限闸门 | ⚠️ 本机不可达：关权限后系统杀进程，见 `docs/foreground-service.md` |

**抽屉手势的最终参数**（`ShellState.endDrag`，实测换算 `(像素 − 74) / 907`）：

| 方向 | 位移阈值 | 约等于手指距离 |
|---|---|---|
| 打开 | 0.20 | ~255 px |
| 关闭 | 0.15 | ~210 px |

不对称是有意的：侧边栏开着时对话是更窄的卡片，回程本来就省力，打开阈值略高才能让两个方向手感一致；整体阈值下移的同时也减少了与系统全面屏返回手势的抢事件。
