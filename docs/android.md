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

`local.properties` 里的 `sdk.dir` 是本机路径，不提交；`GRADLE_USER_HOME` 可以指向
`<checkout>/.gradle-home`（已 gitignore），让 Gradle 缓存留在项目里。

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
