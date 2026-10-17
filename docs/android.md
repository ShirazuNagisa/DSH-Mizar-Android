# Android 客户端

同一套 Mizar 协议的第二个客户端：手机看 DSH agent 干活、发消息、回答审批。协议本身由主机插件
实现，见 [DSH-Mizar 的 protocol-v1.md](https://github.com/ShirazuNagisa/DSH-Mizar/blob/main/docs/protocol-v1.md)；
中继见 [DSH-Mizar-Relay 的 deployment.md](https://github.com/ShirazuNagisa/DSH-Mizar-Relay/blob/main/docs/deployment.md)。

## 目录

```text
mizarkit/   纯 Kotlin/JVM：帧、配对、连接状态机、会话投影、缓存接口、诊断
app/        Compose 界面、Firebase Messaging、通知、加密存储
protocol/   与主机插件共用的黄金帧（fixtures），测试直接读这些文件
```

`mizarkit` 不带任何 Android 依赖，理由和 iOS 侧把 MizarKit 做成 Swift Package 一样：
协议测试要能在 JVM 上秒级跑完，而不是每次都过一遍模拟器。

## 构建环境

| 项 | 版本 |
|---|---|
| JDK | 17（AGP 8.7 不接受更新的 JDK） |
| Android SDK | platform 35 + build-tools 35.0.0 |
| Gradle | 8.11.1（wrapper 已提交，`./gradlew` 即可） |

`local.properties` 里的 `sdk.dir` 是本机路径，不提交。

> **Windows 上必须用独立的 `GRADLE_USER_HOME`**（例如 `E:\gradle-home2`）。与插件仓库共用缓存时，
> `:app:processDebugResources` 会稳定失败在 `stableIds.txt: failed to open`。完整原因、`GRADLE_USER_HOME`+`GRADLE_USER_HOME` 的写法与排错步骤见 [build-windows.md](build-windows.md)。

```sh
# 测试（纯 JVM，秒级）
./gradlew :mizarkit:test

# 打 debug 包
./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
```

## 与真实宿主对跑

```sh
# 1. 起一个 dev 宿主（主机插件仓库里的 scripts/dev.mjs），日志里会打印配对码和监听地址
# 2. 用日志里的地址和配对码跑客户端
./gradlew :mizarkit:hostCheck "-Pbase=http://<host>:19390" "-Pcode=ABCD-EFGH"
```

它做的事和主机插件仓库的 `scripts/smoke-listener.mjs` 同层：hello → pair → 握手 → `session.list` →
`workspace.list` → `session.messages` → `session.create` → `session.prompt`（带幂等键） →
`interactions.pending` → `model.catalog` → `permissions.catalog` → `session.cancel`。

**地址要选对**：插件默认 `bind.mode: lan`，只绑非内部 IPv4 地址，**不含 `127.0.0.1`**。
要它日志里 `listening on …` 列出的地址之一。

## FCM 推送

`app/google-services.json` 是可选的，且已 gitignore：

- **有它**：google-services 插件被应用，App 拿到 FCM token，连上宿主后发
  `push.register {token, platform:"android", locale}`，并在 `onNewToken` 时更新存储。
- **没有它**：插件不应用，App 照常构建运行，只是 `PushTokens.current()` 返回 null，
  推送不激活。这是刻意的：一个必须先在 Firebase 建项目才能编译的 App 是谁都试不了的 App。

中继侧要配 `fcm` 服务账号才真正发得出去，见
[中继部署文档第 10 节](https://github.com/ShirazuNagisa/DSH-Mizar-Relay/blob/main/docs/deployment.md#103-serving-android-phones-fcm)。
iOS 与 Android 在同一间房里各走各的通道：`push.register` 的 `platform` 字段决定这一点，缺省是
`ios`，所以现有 iOS 客户端与已部署的中继不需要任何改动。

## 平台决定

- **明文 `ws://`**：宿主地址是配对时才知道的，Android 无法预先声明例外，所以
  `usesCleartextTraffic="true"`——和 iOS 的 `NSAllowsArbitraryLoads` 是同一个决定、同一个理由。
  真正的解法是监听器上 TLS，那还是宿主侧的待办。
- **targetSdk 35**：Android 16 的局域网访问保护（`ACCESS_LOCAL_NETWORK`）在目标 36 时才需要
  显式申请，这里先避开并记一笔。
- **没有前台服务**：iOS 客户端也没有后台模式，Android 侧不做「保活」这种不对等的功能。
  息屏久了长连接会被系统收走，重新打开 App 即可；推送是后台唯一的通道。
- **Doze 与厂商省电**：FCM 的到达时间与长连接的存活都受它们影响。App 在每次回到前台时重连并
  重新拉取会话，所以「回来就有」而不是「回来等一会」。
- **状态目录**：凭据用 Keystore 支撑的加密存储（一份 blob、先更新后新增），
  会话列表索引是一台宿主一个 JSON 文件。**不做内存降级**——静默退回内存会让配对看起来成功、
  下次启动消失。

## 与 iOS 客户端的差异（刻意的）

| 位置 | iOS | Android |
|---|---|---|
| 连接失败的说法 | `ConnectionDiagnosis` 直接给出英文句子 | 给出**结构化的判定**，由 App 映射到本地化文案；未本地化时才回落到英文 |
| `RESPONSE_TOO_LARGE` | 未建模 | 在错误码表里 |
| 令牌失效 | `requiresRepairing` 从未被使用 | 清除本地凭据并回到配对 |
| 工作区标题 | 由路径推导 | 仍由路径推导（`workspace.list` 的 title 留作后续改进） |
| Markdown | `AttributedString(markdown:)` 两段回退 | 自研小解析器 + 未闭合围栏规则，`plain` 作为最后回退 |

**两处已知的协议解析差异**（`PairingUri` 逐字节对齐的是**面板**那一侧，即 TypeScript）：

1. 查询串用 `application/x-www-form-urlencoded` 编码，空格是 `+`；Swift 的 `URLComponents`
   给的是 `%20`。两端解析自己那侧写出的 URI 都没问题，互相解析会差一个字符。
2. `v=`（存在但为空）在 TypeScript/Kotlin 侧解析为 `0`，Swift 侧是 `nil`。面板永远写合法整数，
   所以这条不可达，但它是**被测试钉住的决定**而不是意外。

## 黄金帧

`protocol/fixtures/` 里的文件与主机插件仓库的同名文件一致，来自真实宿主。测试从仓库里直接读，
不复制进测试源码，这样同一份字节既被 TypeScript 侧校验、也被 Kotlin 侧校验。

## 界面行为（0.8.1）

- **手动刷新**：会话标题栏右侧的 ↻ 按钮重新读取该会话的对话、统计与待办卡片。用于「电脑上发了消息、手机上没跟上」的场景（进程被冻结、宿主重启、网络抖动）。
- **状态行**：标题下方常驻一行 —— `正在思考…`（绿色转圈）/ `等待你的回答`（橙色，有审批或提问卡）/ `上一轮失败`（红色）/ `空闲`（灰）。
- **回到最新**：向上翻历史时右下角浮出按钮；有新内容到达时显示 `N 条新消息`。翻历史不再被自动滚动打断（此前只要对话变长就强制跳到底）。
- **未读标记**：会话列表行尾的小圆点表示「上一轮在你没看的时候结束了」；正在跑的行仍用左侧转圈，不重复标记。
- **复制整条消息**：每个气泡右上角有一个复制按钮，写剪贴板并提示「已复制」；文本选区仍保留，用于局部复制。
- **记住会话**：冷启动会直接打开上次阅读的会话（每个进程只恢复一次；主动返回列表后不会被拽回去）。
- **主题**：中性灰调色板（此前只设了 `primary`，其余角色是 Material 默认的蓝紫基色，因此输入框显深蓝、选中文字不清）。文本选中色为深色 `#3A4A66` / 浅色 `#B9CFFB`。

### 构建包的两条规则

- 覆盖安装必须用 **release 签名包**（`./gradlew :app:assembleRelease` 或 `scripts/release.sh`）。debug 包与 release 包签名不同，`adb install -r` 会静默失败，看到的界面还是旧的。
- 构建用独立的 `GRADLE_USER_HOME`（如 `E:\gradle-home2`），见 [build-windows.md](build-windows.md)。

## 验证记录（0.8.4 / 0.8.5，2026-10-17）

| 项 | 结论 |
|---|---|
| 计划审批卡 | 有界（≤42% 屏高）、正文折叠 6 行 + 「N 字 · 展开全文」、**批准按钮可见** ✅ |
| 手机端完成审批 | 在手机上点选项后，卡片消失、状态回到「正在思考…」，会话继续 ✅ |
| 进入对话位置 | 冷启动直接停在最新消息，无滚动过程（`scrollToItem` + 定位前不绘制）✅ |
| 待办卡消失后的锚定 | 修复：卡片出现/消失会改变视口高度，若原本跟随底部则重新锚定（`following` + `pendingCardCount` effect）✅ |
| 状态行 / 刷新 / 复制 / 回到最新 | 见上节，均已在真机确认 ✅ |

一条经验：`Scaffold` 的 `bottomBar` 会随待办卡增减改变高度，任何「锚定在底部」的实现都必须把这件事当成视口变化来处理，否则审批完成的瞬间最新消息会被切掉一半。
