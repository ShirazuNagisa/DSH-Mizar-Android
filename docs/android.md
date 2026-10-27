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
| Android SDK | platform 36 + build-tools 36.0.0（`targetSdk`/`compileSdk` 都是 36） |
| Gradle | 8.11.1（wrapper 已提交，`./gradlew` 即可） |

`local.properties` 里的 `sdk.dir` 是本机路径，不提交。

> **Windows 上必须用独立的 `GRADLE_USER_HOME`**（例如 `E:\gradle-home2`）。与插件仓库共用缓存时，
> `:app:processDebugResources` 会稳定失败在 `stableIds.txt: failed to open`。完整原因、`GRADLE_USER_HOME`+`GRADLE_USER_HOME` 的写法与排错步骤见 [build-windows.md](build-windows.md)。

```sh
# 测试（纯 JVM，秒级）
./gradlew :mizarkit:test          # 协议、配对、投影
./gradlew :app:testDebugUnitTest  # 纯 App 侧逻辑（Markdown 揭示）

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

## 配对

插件 0.8.5+ 的二维码同时带直连地址（`h`）和中继地址（`u`）。配对按**直连 6 秒 → 中继 20 秒**逐条尝试：

- 只有「这条路上什么都没应答」才换下一条；`PAIRING_CODE_*` 这类明确拒绝是终局，不换门重试。
- 应答的那条路由会交给第一次连接，不再先花另一条路的超时。
- 重连本来就会遍历两条路由；现在配对与它一致。
- 失败时 `PairingFailure` 带着逐条尝试，`activity.log` 里一条路由一行；屏幕上只显示真正该看的那条
  错误（宿主明确拒绝时是它，否则是最后一条路的传输错误）。
- 中继那条请求带 `x-mizar-client-id`（见下节）；直连那条不带。

首次配对会顺带读一遍会话标题（最多 80 条、最近的优先），连接页显示 `已读取 N / M 个会话`、
进度条与预计剩余时间。**只有首次配对会等它**：重连直接用上次落盘的索引，列表照旧按需懒加载。

## 连接：并行拨号与失败归因（0.10.5 起）

一台电脑通常有两个以上入口（局域网直连 + 两条中继）。以前是**串行**试：一个入口挂了要等满
20 秒握手超时，才轮到下一个——手机只想连上，却先付了最慢的顺序。

现在启动即**同时拨所有入口**，先答者胜，其余取消（不等它们退出：输的那条可能还在自己的握手里，
取消会关掉它的 socket）。成功后的状态更新、watcher、首页、推送注册抽成了 `adoptConnection()`，
竞速只负责决定「哪一条」。

失败时显示哪一条错误，也不再是「最后到的那条」（那是计时器的偶然）。`Diagnoses.weight()` 按
**证明了什么**排序：

| 权重 | 诊断 | 证明了什么 |
|---|---|---|
| 100 | `RequiresRepairing` | 对面答了，且拒绝了本机令牌——只能重新配对 |
| 80 | `RelayRoomEmpty` | 中继答了 404/410：服务器在，电脑没连上 |
| 40/30 | `OutsideTailnet` / `TailnetPeerSilent` | 地址是 tailnet，但本机不在/对端没答 |
| 20 | `Silent` | 什么都没有回答 |
| 10 | `Offline` | 本机没有网络 |

`RelayRoomEmpty` 来自 OkHttp 的 `onFailure(…, response)`：非 101 的响应现在带着状态码抛出
（`UpgradeRefused`），而不是被拍平成一句 `ProtocolException`。直连路由没有中间服务器，同样的
404 不算「房间空」。

## 中继身份头

`mizarkit/account/AccountIdentity`：`x-mizar-client-id` 是安装 id（`[A-Za-z0-9._-]{8,64}`，
存在加密存储里），`x-mizar-account` 是账户令牌的 **SHA-256**，`x-mizar-session` 是令牌本身、
只发给拥有账户库的那个中继源。

- **直连不发**：本机电脑不计流量，也没有账户库可核对。
- **配对的中继请求也发**：那是新安装的第一条请求，也是中继给这台设备建流量身份的那条；
  不带的话，配对流量的归属只有一个匿名桶。
- 三个头都会被中继剥离，电脑端看不到。

## 中继档位（插件 0.8.9）

付费账户的隧道应当走付费中继（`dshmizar-1`），免费账户走免费中继（`dshmizar`）。host 没有账户身份，
只有手机知道套餐，所以由手机调 `relay.set`：

- 连上中继后读 `host.info.relay`，与 `RelayTier.baseUrl(paid)` 比对，**不同才**请求移动。
- host 先回 `ok` 再重拨（约 250 ms）。手机把 `PairedHost.baseUrl` 换成返回的 `baseURL`，700 ms 后按新
  room 重连——token 没变，room 没变，所以这是重拨不是重配。
- 另一台设备触发时，本机会收到 `relay.changed`，同样更新并重连。
- 老 host 没有 `relay` 字段、`relay.set` 回 `NOT_IMPLEMENTED`：什么都不做。自建中继不移动。
- 账户套餐变化（付费↔免费、登录/登出）会重新同步一次；免费账户被付费中继的 gate 拒绝是预期行为。
- 配对里存的是公共中继时，重连的候选里多一条「**另一个**公共中继 + 同一个 room」：手机离线期间
  host 搬走的话，room 还在，另一条路能把它找回来。

## 协议要点（插件 0.8.9）

- **子代理的页要带父会话。** `session.messages` 的 `parentSessionId` 只在读**委派子会话**时传：host 拒绝
  按 session 寻址子代理的日志（`session/agent-busy`，在碰日志之前就回），必须由父地址重建。fork 有
  `parentSessionId` 但**不是**子代理——它是操作者自己的对话，按普通会话读。规则在
  `mizarkit/SessionLineage`，四个读取点（会话页、上翻页、完成通知、子代理标题）都走同一个 `pageAddress()`。
- **每一页都带 `context`。** 占用环以前只从 `session.stats` 来，而跑着 turn 时真正在跑的是增量刷新，
  它从不读 stats——数字就冻在上一次全量读取。现在每页都带（同一份投影基线算出来的，不额外花一次读），
  缺省表示 host 没有这份投影，读作**未知**而不是 0。

## 实时活动

Android 16 的 Live Updates 是 iOS Live Activity 的对位：`push/LiveTurn.kt` 用
`Notification.ProgressStyle` 发一条常驻通知，系统把它提成状态栏胶囊与锁屏卡片。

- **进度是不确定的**：一轮 turn 还剩多少无法预知，宿主只报已经发生的步骤。一条走到 90% 就停住的
  进度条，比一条说「还在跑」的更糟。
- **计时用系统计时器**（`setUsesChronometer` + `setWhen`）：App 被挂起时它照样走，手机不必醒来重绘。
- **停止按钮**是广播（`LiveTurnReceiver`），经 `LiveTurnCommands` 找到当前连接；进程已经不在时
  退回「打开 App 并定位到该会话」——那时候 socket 也没了，没有别的诚实答案。
- **收起**由 `turn.settled` 触发，会话列表里没有 running 行时也收；不依赖用户手动划掉。
- **API 36 以下什么都不发**：没有这个界面，而连接服务自己的常驻通知已经在说「已连接」。

## 账户（0.10.2）

账户是可选的：邮箱/用户名 + 密码，或 GitHub。登录后中继连接带身份头，付费账户在中继下也能用附件与
运行细节；不登录时这两项仍然由局域网解锁。令牌存 EncryptedSharedPreferences，登录/登出会重连一次。

完整说明、服务端配置与 AVD 测法见 [account.md](account.md)。

## 交付文件预览（0.10.5 起）

模型用 `present` 交付的文件，宿主在 `turn/end` 上以 `mizarDeliverables` 带下来（它从该轮的工具调用里
读出来，因为摘要页本来不带工具流量）。手机在回答下面画成卡片，点开走 `file.read` 取字节：

- **一次只取一片**（256 KiB，宿主的上限）。文本超出时说「只显示前 256 KB，共 X」；图片超出时直接说
  「预览不了」——半张图比没有更糟。
- **能画的**：Markdown/纯文本/代码（等宽）、PNG/JPEG/WebP/GIF/BMP（按原比例铺满宽度）、PDF
  （系统 `PdfRenderer`，不引第三方库；白底渲染，最多前 5 页并说明「共 N 页」）。其余显示
  「这种文件手机预览不了（大小）」，文件仍在电脑上。
- **PDF 的取舍**：渲染需要文件描述符，所以字节先落到 `cacheDir`，画完立刻删除；超过一片（256 KB）的
  PDF **不渲染**——半个 PDF 不是它的预览。
- **门槛**：与附件、完整记录同一个 `advancedFeaturesUnlocked`。
- **位置**：右侧边栏的第三页，与「轮次 / 子代理」同一行分段（`SidePlane`）。宽屏的立式侧栏与手机的
  抽屉共用这一个实现，点开卡片会直接把抽屉切到「预览」页。子代理原来是一个弹窗，现在也归到这一行里。

**没做的**：正文里指向本地路径的 Markdown 链接还不能点开预览（iOS 可以）。`Markdown` 解析器只记
样式、不记链接目标，要做就得改解析器并把回调穿进打字机那条路径——收益不如交付卡片直接，暂时留着。

**一个坑**：`ConversationTurn` 在 `merging()` / `absorbing()` 里是用具名参数**重建**的，新增字段不在
那里列一遍就会被静默清空——交付卡片第一次就是这样在刷新后消失的。`ConversationTest` 里有一条回归测试
钉住它。

## 兑换码（0.10.4 起）

付费时长在安卓上只有一条来源：**兑换码**。设置页账户区里有一条「兑换码」，账户页的
「兑换邀请码」按钮打开的是同一个弹窗——一处实现，两个入口，免得两份表单各自漂移。

- 兑换走 `invite/redeem`，服务端把天数**累加**到现有到期时间上，所以连着兑两个会叠加。
- 弹窗必须说出**新的到期日**（「兑换成功，付费至 2026-11-23」）。只让上面某一行变化，用户会以为没成功，
  下一步就是再兑一次。
- 未登录时弹窗不显示输入框，只说「兑换码是给账户加时长的，请先登录」——没有账户就没有可加的对象。

## 余额（0.10.4 起）

设置页账户行下面多了「余额」：显示**所连电脑**的 DeepSeek 官方钱包，不是这台手机的中继额度。
走宿主的 `account.balance`（能力 `deepseek.balance.v1`），只有宿主有凭据，手机只是画。

- 四种结果分开说，因为操作者的下一步不同：`ready`（钱包列表 + 充值跳转）、`signed-out`（那台电脑没登录
  DeepSeek）、`unsupported`（插件版本太老，没有这个方法）、`unavailable`（查询失败）。
- 金额是宿主校验过的十进制**字符串**，客户端不转 `Double`——转一次就是在给别人的钱做四舍五入。
- 打开即读一次，头部刷新按钮再读；不跟会话轮询绑在一起。

## 一轮的完整记录（0.10.4 起）

会话页每一轮下面有一行总结，右侧的「完整记录」把**这一轮**的原始日志拉下来：旁白、每次工具调用和它的
结果。摘要页本来只带每轮最后一条回答（一屏一轮），所以这些内容平时根本到不了手机。

- **门槛与附件相同**：付费账户，或者同一局域网。同一个 `advancedFeaturesUnlocked`，同一个理由——一次
  按轮全量可能就顶一整页摘要的体积。
- **按轮取，不整体取**：`session.messages { full: true, turn: N }`。整段会话全量在一个七轮的会话上实测
  是 4.36 MB，而摘要页 199 KB；点开哪轮才付哪轮的钱。
- **宿主丢掉的仍然丢掉**：`request/header`、`request/context`（系统提示词与工具表）不进这一页；回答里的
  token `stream` 也不进——两个客户端都只画 `message.content`，而它通常是记录里最大的字段（实测一条
  210 KB 的回答里 210 KB 都是 stream）。
- **工具行**：`tool/call` 与 `tool/result` 按宿主的 `callId` 配对后画成一行，点一下展开参数和结果。没有配对
  上的结果不画（半个配对没有行可落）。

## 平台决定

- **明文 `ws://`**：宿主地址是配对时才知道的，Android 无法预先声明例外，所以
  `usesCleartextTraffic="true"`——和 iOS 的 `NSAllowsArbitraryLoads` 是同一个决定、同一个理由。
  真正的解法是监听器上 TLS，那还是宿主侧的待办。
- **targetSdk 36**：升到 36 是为了 Android 16 的 Live Updates（`Notification.ProgressStyle`），
  `compileSdk` 同步到 36、build-tools 36.0.0。曾经记在这里的「局域网访问保护需要
  `ACCESS_LOCAL_NETWORK`」是**后续版本**才强制的运行时权限，API 36 的 `android.jar` 里没有这个
  常量，所以这次升级不引入新的权限请求；等它出现时再补，届时直连与中继两条路的关系（配对回退）
  正好是降级路径。
- **前台服务**：`push/ConnectionService.kt` 以 `dataSync` 类型常驻，通知按连接状态说三种话；
  息屏久了系统仍可能收走长连接，重新打开 App 即可。细节见 [foreground-service.md](foreground-service.md)。
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
| 工作区标题 | 由路径推导 | 与 iOS 一致：用 `workspace.list` 的 `title`，没有会话的工作区也能选到 |
| Markdown | `AttributedString(markdown:)` 两段回退 | 自研小解析器 + 未闭合围栏规则，`plain` 作为最后回退 |
| 打字机 | 按块揭示已解析的 Markdown | 同上，`MarkdownReveal` 逐块切；纯文本走按字符的便宜路径 |
| 实时活动 | Live Activity + Widget Extension | Live Updates（`Notification.ProgressStyle`，API 36+）；进度不确定，结束由 `turn.settled` 收起 |
| 配对进度 | 全屏进度 + 预计剩余时间 | 连接页内的进度条 + `已读取 N / M` + ETA；只覆盖标题预热（本客户端不预下载对话） |
| 账户 | Apple 登录 + App Store 订阅 | 邮箱密码 + GitHub 登录；订阅（Play Billing）尚未接入，见 [account.md](account.md) |

**两处已知的协议解析差异**（`PairingUri` 逐字节对齐的是**面板**那一侧，即 TypeScript）：

1. 查询串用 `application/x-www-form-urlencoded` 编码，空格是 `+`；Swift 的 `URLComponents`
   给的是 `%20`。两端解析自己那侧写出的 URI 都没问题，互相解析会差一个字符。
2. `v=`（存在但为空）在 TypeScript/Kotlin 侧解析为 `0`，Swift 侧是 `nil`。面板永远写合法整数，
   所以这条不可达，但它是**被测试钉住的决定**而不是意外。

## 黄金帧

`protocol/fixtures/` 里的文件与主机插件仓库的同名文件一致，来自真实宿主。测试从仓库里直接读，
不复制进测试源码，这样同一份字节既被 TypeScript 侧校验、也被 Kotlin 侧校验。

## 界面行为

这一节原来停在 0.8.1，只写当时的三件事；下面按 0.10.x 的实际界面重写，旧条目保留在末尾。

- **手动刷新**：会话标题栏右侧的 ↻ 按钮重新读取该会话的对话、统计与待办卡片。用于「电脑上发了消息、手机上没跟上」的场景（进程被冻结、宿主重启、网络抖动）。
- **状态行**：标题下方常驻一行 —— `正在思考…`（绿色转圈）/ `等待你的回答`（橙色，有审批或提问卡）/ `上一轮失败`（红色）/ `空闲`（灰）。
- **回到最新**：向上翻历史时右下角浮出按钮；有新内容到达时显示 `N 条新消息`。翻历史不再被自动滚动打断（此前只要对话变长就强制跳到底）。
- **未读标记**：会话列表行尾的小圆点表示「上一轮在你没看的时候结束了」；正在跑的行仍用左侧转圈，不重复标记。
- **复制整条消息**：每个气泡右上角有一个复制按钮，写剪贴板并提示「已复制」；文本选区仍保留，用于局部复制。
- **记住会话**：冷启动会直接打开上次阅读的会话（每个进程只恢复一次；主动返回列表后不会被拽回去）。
- **主题**：中性灰调色板（此前只设了 `primary`，其余角色是 Material 默认的蓝紫基色，因此输入框显深蓝、选中文字不清）。文本选中色为深色 `#3A4A66` / 浅色 `#B9CFFB`。
- **打字机按块揭示**：带格式的回答不再是「整段直接出现」。解析结果按块切，逐块出现、当前块部分显示，
  所以 `##`、`**`、围栏标记不会被逐字符爬过屏幕；纯文本仍走按字符的便宜路径。慢速诊断开关照旧。
- **局域网优先**：连上中继后主动探测宿主自己的地址，命中就切到直连（`host.info` 的 `addresses`，
  用 `probeHostId` 校验主机 id 才切）；设置页可关。
- **附件与运行细节**：付费账户 **或** 局域网解锁。入口在作曲栏左下角的 `+` 里：窗口顶部一排两个大格子
  （添加照片 / 添加文件，比下面的命令行高），已选文件仍以 chip 形式显示在输入框上方；离开同一 Wi-Fi 且
  没有付费账户时，这两个格子与附件栏一起隐藏。
- **归档**：会话长按菜单里归档，调 `session.archive`，该行从本机列表移除。
- **离线浏览**：宿主连不上时可以直接进缓存过的列表与会话，顶部一条横幅说明现在看的是旧数据。
- **日志导出**：设置页一键打包（见 [诊断包](#诊断包)）。
- **宽屏三页侧栏**：轮次 / 子代理 / 预览三页，平板与折叠屏上是常驻列而不是抽屉。

## 会话设置：宿主说了算

模型、思考强度、权限，三样都只认宿主报回来的值：

- **模型**：点一下先显示选择（`modelChoice`），`session.model.set` 一答就把这条缓存删掉，改用宿主返回的
  `selected` + 一次 `session.stats` 回读。所以桌面端改了模型，手机下次读 stats 就跟上；手机被拒绝时，
  药丸回到真实值而不是停在"我以为"。
- **权限**：`session.permission.set` 的应答只代表"请求收到了"，不代表"改了"。所以改完立刻读
  `session.stats`，`permissionPreset` 对得上才算生效，对不上就报一句。
- **`custom`** 宿主会报，但手机不能设，所以不出现在选择列表里；当前生效的那一项打勾。
- **迟到的应答**：每个设置请求带 `SettingsScope(host, device, session, connectionEpoch)` 和自增
  `revision`，`mayApply` 不成立就整条丢弃——换会话、换主机、重连之后旧回复不会覆盖新状态。
- **stats 是增量**：一个只带上下文压力的帧不能把模型、标题、权限清空，所以统一走
  `SessionSettings.mergeStats`。

验证（AVD + `scripts/ki2-host.mjs`）：选 DeepSeek Lite → 宿主日志 `session.model.set` → 药丸变；
用 `POST /ctl/model` 在"桌面端"改回 V4/Low → 手机下拉刷新后跟着变；权限选 Read only → 宿主日志
`session.permission.set` → 重开面板勾选已移动。

## 附件：线上契约

手机把图片/文件交给电脑，走的是**已认证那条 WebSocket** 上的四个方法，名字和字段都以宿主为准：

| 方法 | payload | 返回 |
|---|---|---|
| `upload.capabilities` | `{}` | `{chunkBytes, imageBytes, fileBytes, promptBytes, attachments, images, expiresSeconds, imageMediaTypes, sha256}` |
| `upload.begin` | `sessionId, kind, name, mediaType, byteSize, sha256?` | `{uploadId, chunkBytes}` |
| `upload.chunk` | `uploadId, index, data`（规范 base64） | `{received: index + 1}` |
| `upload.finish` | `uploadId` | `{attachmentId, kind, name, mediaType, byteSize}` |
| `upload.abort` | `uploadId` | `{aborted: true}` |

prompt 里带的是 **`attachmentIds`**，不是 `attachments`。

三条容易踩的：

- **块大小是宿主的偏移约定。** 每块正好 `chunkBytes`（默认 128 KiB，除最后一块），宿主按
  `index × chunkBytes` 落盘。客户端按自己的想象切块，第一块之后全写到错位置。
- **`mediaType` 是必填**，普通文件也一样（用 `application/octet-stream`）。
- **名字写错就是静默丢文件。** 宿主对未知方法回 `NOT_IMPLEMENTED`，对读不懂的 prompt 字段当没传；
  这正是 0.10.5 的状态——UI 早就接好了，链路却因为方法名对不上从来没通过。

照片在手机上**先转成 JPEG**（`AttachmentStaging`，最长边受限、质量 86）再上传，所以 20 MB 的
HEIC 原图不会原样塞进 prompt。哈希按 64 KiB 流式计算，不把整份文件读进内存。

验证：`scripts/ki2-host.mjs` 里按插件规则实现了这四个方法（含重放比对与"除最后一块必须整块"），
`./gradlew :mizarkit:attachmentHostCheck -Pbase=http://127.0.0.1:19491 -Pcode=XXXX` 用真实
`AttachmentUploader` 跑通配对 → 上传（355 字节图片 + 150001 字节文件）→ 重复 finish → 纯附件 prompt →
同 requestId 重放。AVD 上再走一遍 UI：选图 → `upload.begin/finish` → `prompt recorded (…, 1 attachment(s))`
→ 历史里显示附件 chip。

## 诊断包

设置页的导出按钮生成一个 zip，全部是纯文本，且**不含**设备令牌、配对码、提问与回答：

| 文件 | 内容 |
|---|---|
| `environment.json` | 构建、系统、设备、运行时（含是否模拟器、ABI、堆上限） |
| `device.txt` | 上面那些的可读版本 |
| `state.txt` | 连接阶段、主机身份、最后一次失败、同步进度 |
| `settings.txt` | 操作者的开关 |
| `sessions.txt` | 会话列表（不含正文） |
| `activity.log` | App 自己的事件，新的在后 |
| `requests.jsonl` | 协议往返：method、方向、耗时、结果码、字节数（不含 payload） |

`requests.jsonl` 是排查「帧没发出去 / 发出去被拒」的那份：一条 `out` 后面没有对应的 `in`，就是
发出去了没人应答；`error:CODE` 是宿主按名字拒绝的。环形缓冲 400 条，由 `MizarConnection` 写入。

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

## 传输优化（0.8.7 + 插件 49875cf）

弱服务器 / 隧道上的主要成本是**每次刷新重传整个尾部**。现在客户端把「我读到哪」带上：

- 客户端在 `session.messages` 里加 `afterSeq`（本屏已读到的日志位置 `asOfSeq`）；
- 插件在**摘要之后、预算之前**按 `afterSeq` 过滤，只回新记录；没有 seq 的记录保留（无法证明是旧的）；
- 老插件会忽略该参数，客户端照常合并 —— **向后兼容**。

实测（dev 宿主，一个 5 条记录的会话）：
```
尾部一页   5 条 / 1596 B
增量       1 条 /  304 B     省 81%
```
会话越长、消息越长，省的越多（增量只带新文本）。

**注意**：插件改动要重启 DSH 才会加载（`lib/` 已重建）。
