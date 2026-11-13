# 本地 Agent 接入设计：把 `LocalKernelEngine` 当宿主（Transport 版）

状态：设计稿，2026-11-13。范围：**不接主 UI、不改 `AppViewModel`**，交付可接线的最小闭环。
关联：PoC 可行性报告 `docs/android/local-agent-poc-2026-11-13.md`；iOS 参考
`MizarKit/Sources/MizarKit/Kernel/LocalKernelTransport.swift`、`LocalKernel/docs/kernel-api.md`。

## 1. 目标

用户在平台内选「本地 Agent（本机）」后，App 的连接层指向进程内内核，走同一套 Mizar v1 帧
（hello/welcome/session/prompt/approval），不依赖电脑也能跑一轮对话。成功标准：连接建立、
会话列表/创建、prompt 产生 `turn/start…turn/end`、失败可降级回远程。

## 2. 为什么几乎不用改 kit

`MizarConnection(configuration, traffic, makeTransport)` 的 transport 是注入的
（`makeTransport: () -> WebSocketTransport`），而内核 bundle 本身就是**同一份协议实现**
（`kernel-api.md` §13 明确要求 Android 宿主只实现 transport + PlatformHost）。所以：

- **不需要新协议**：hello/welcome、关联请求、event 帧、错误码全部复用；
- **不需要改 `mizarkit`**：新代码只在 `localagent/` 模块 + 少量 UI 入口；
- **不能复用桌面假设**：内核只推「交互一族」事件（`interactions.changed`/`approval.request`
  等），**没有** `turn.settled`/`session.changed`/状态通道；轮次结束靠轮询
  `session.messages`（App 现有 watcher 已经是轮询架构，这正是能低成本接上的原因）。

## 3. 数据流

```
AppViewModel ── MizarConnection ── LocalKernelTransport (WebSocketTransport)
                                        │  text frames（JSON）
                                        ▼
                              LocalKernelEngine（JS 线程）
                                        │  __mizarOpenConnection / __mizarSendOn
                                        ▼
                              QuickJS-NG：MizarKernel → KernelConnection
                                        │  host.fs/kv/http/log/timer（回调式）
                                        ▼
                              localagent-host.js → JNI（__mizarNativeCall）
                                        ▼
                     HostDispatcher → MizarHostSandbox / HostHttpTransport / 帧回投
```

## 4. 传输语义（LocalKernelTransport）

- `connect(url, headers)`：忽略 url/headers（本地无 socket），在引擎线程打开一条
  `kernel.openConnection()`，把该连接的帧投进 `Channel`；已连接时抛 `SOCKET_CLOSED`。
- `send(text)`：`__mizarSendOn(connectionId, text)`；连接不存在 → `SOCKET_CLOSED`（retryable）。
- `receiveMessage()`：产出一条 `Incoming.Text`；关闭后返回 `null`（MizarConnection 的读循环据此结束）。
  内核不发二进制帧，`Incoming.Binary` 保留但不会出现。
- `close()`：`__mizarCloseConnection(id)` + 关闭 channel；幂等。
- 每个 `MizarConnection` 一条 JS 连接；重连＝新建 transport（`makeTransport` 每次调用新实例）。
- 引擎线程外调用会阻塞：transport 内部一律 `Dispatchers.IO`，不占 UI 线程。

## 5. 身份 / 配对绕过

- `Configuration(authority = "local:19390", token = "local", appVersion, deviceModel, headers = emptyMap())`。
  authority 只用于 `PairingUri` 生成一个合法但**永不 dial** 的 URL；token 任意（内核接受任何 token）。
- 不写 `CredentialStore`、不改配对记录、不参与 relay/LAN 切换（endpoint 是 Direct，`relayed=false`，
  `AdoptionPlan.looksForLan=false`）。
- hostId/deviceId 来自内核 welcome（持久化在 kv，重启稳定），UI 可用它做本地会话缓存键。

## 6. 会话存储

- 内核 root：`filesDir/LocalAgent`（`LocalKernelEngine.rootDir`）；内核自管
  `.mizar/sessions/*` 与工作区文件夹（默认「默认工作区」，可在内核里新建工作区）。
- App 的会话索引/正文缓存按 hostId 分键，不会污染远程数据。
- 「清除本地会话」= 删 rootDir（`LocalAgentHost.clear()`），设置页已有回调入口。

## 7. 生命周期（仅前台）

- 进程内单引擎（`LocalAgentHost` 单例由 AppViewModel 持有）；boot ≈150 ms，放 IO。
- 进后台/离开本地模式：`close()` 引擎（内核把未闭合回合补 `turn/end stopped`，重启自愈）。
- 进程被杀：下次进入重新 boot；历史仍在磁盘。**不做后台运行**（与 iOS 产品承诺一致）。

## 8. 轮次与事件

- prompt 后：内核写 `turn/start`，每步写 `step/start`/`assistant/attempt`/`assistant/message`，
  结束写唯一的 `turn/end`（`completed|stopped|error`）。App 用现有 `session.messages(afterSeq)`
  轮询投影，现有会话页/折叠逻辑可直接复用。
- 事件流只有交互：`interactions.changed`（只带计数）、`approval.request/resolved`、
  `question.request/resolved`。收到 `interactions.changed` 后重拉 `interactions.pending`。
- 没有状态通道：`liveTurn` 的「运行中状态/过程行」在本地模式下来自轮询投影（比桌面延迟高，
  可接受；后续可在内核侧加事件，但那需要改 iOS kernel，不在本轮）。

## 9. 审批（本轮策略）

- 内核 fail-closed：最后一个客户端断开时 pending 审批结算为 `unavailable`，所以**连接要保持**。
- 最小闭环：`LocalInteractionResponder` 自动回答——审批 `rejected`（默认）/可选
  `allowed-once`，提问选每项第一个选项；策略是纯函数（可测），本轮不弹审批卡片。
- 正式接线后把 responder 换成现有审批 dock（`answerApproval` 已存在），只需不启动 auto 策略。

## 10. 宿主能力面（host API）

- 已有（PoC）：`fs`、`kv`、`log/uuid/now`、JS 侧定时器泵。
- **本轮新增**：`http.request` 流式（OkHttp；行粒度 `onChunk`、`onDone(status)`、`onError`、
  `abort`）。做法：JS→Kotlin `http.start` 拿 handle；OkHttp 线程把 chunk/done/error 投进
  `HostEventQueue`，post 回引擎线程执行 `__mizarDrainHostEvents()`；内核的 SSE 解析拿到整行。
- 凭据：`kernel.apiKey.*` 走 `KernelKeyValueStore`（PoC 明文 SharedPreferences；接正式 UI 前换
  `EncryptedSharedPreferences`）。
- 不支持：附件上传（`NOT_IMPLEMENTED`）、`file.stat/read`、`usage.overview`、`relay.set`，kit 已回退 null。

## 11. 错误与降级

| 情况 | 表现 | 处理 |
|---|---|---|
| QuickJS `.so` 缺失/加载失败 | `LocalAgentException` | UI 显示「本地 Agent 不可用」并留在远程 |
| 内核 boot 失败 | `BootReport.ready=false` | 同上；报告里给 bootError |
| 没有 API key | 回合 `turn/end error MISSING_CREDENTIAL` | 现有会话页按「以 error 结束」渲染，引导去设置 |
| 方法 NOT_IMPLEMENTED | kit 返回 null | 现有 UI 隐藏对应能力（图片/预览/用量） |
| 引擎线程崩/卡 | transport send 抛 `SOCKET_CLOSED` | App 现有 reconnect 逻辑会尝试重连；本地模式应改为「重建引擎」而非无限重试 |

## 12. UI 入口与接线点（Lead 负责接线）

- **Agent 切换器**：新增末行「本地 Agent（实验）」，数据来自 `LocalAgentStatus`；
  选中 → 断开远程 socket → 本地 `MizarConnection` → 走现有 `adoptConnection`/`becomeLive`。
  入口 composable 本轮交付：`ui/localagent/LocalAgentEntry.kt`（无状态，Lead 挂到
  `AgentSwitcher` 的 popover 尾部；或侧栏加一行）。
- **设置页**：已有 `LocalAgentSettingsPage(SettingsLocalAgent)`；Lead 把
  `LocalAgentStatus` 映射为 `SettingsLocalAgent(available, configured, storedBytes)`，
  `onOpenLocalAgentModels/onClearLocalAgentData` 接 `LocalAgentHost`。
- **onboarding**：`OnboardingModel.localAgentAvailable/Configured` 两个字段已留好，填上即可。
- **AppViewModel 接线（约 40 行）**：持有 `LocalAgentHost`；新增 `useLocalAgent()/leaveLocalAgent()`；
  `dial` 处 `makeTransport = { host.openTransport() }`；`UiState` 加 `localAgent: SettingsLocalAgent`。
  本次不改 `AppViewModel`，只给 API 与接线清单。

## 13. 测试策略

1. **JVM**：`LocalKernelTransportTest`（fake `LocalKernelLink`）——connect/send/receive/close 的帧往返、
   关闭后 receive=null、重复 connect 抛错；`LocalInteractionAnswerTest`——审批/提问的自动回答 payload。
2. **instrumentation**：`LocalKernelLinkInstrumentedTest`——真引擎 boot → `MizarConnection.connect()`
   welcome 1 → `session.create` → `session.prompt` → 轮询 `session.messages` 断言
   `turn/start` 与 `turn/end`（无 API key 时为 error MISSING_CREDENTIAL，正好覆盖失败降级）；
   `session.list` 出现该会话；`interactions.pending` 为空。
3. **降级**：boot 失败路径用不存在的 root 权限/缺 asset 模拟；方法 NOT_IMPLEMENTED 断言 null。
4. **协议一致性**：同一份内核 bundle（字节一致）已由 PoC 证明；本轮补「帧形状」断言
   （welcome 字段、ok/error 关联、event 名称），与 `kernel-api.md` 黄金帧对齐。

## 14. 已知限制 / 下一步

- 审批/提问自动回答（无 UI）；图片/文件预览/附件/用量在本地模式不可用（内核未实现）。
- 轮询延迟：turn 过程行晚 1 个 poll 周期；后续可让内核推 `session.changed`（需改 iOS kernel）。
- 单引擎单进程；同时只应存在一条本地连接（App 的 `ownership` 已保证）。
- 16 KB 页真机/arm64 未实测（PoC 遗留）；包体 +0.9 MB/ABI。
- 下一步：AppViewModel 接线 → 本地设置页数据 → 审批卡片替换 auto 策略 → 真机验证。
