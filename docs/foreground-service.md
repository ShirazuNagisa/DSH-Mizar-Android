# 后台保活与待办通知

App 退到后台时，一条常驻通知（渠道 `mizar-connection`）让进程不被回收，WebSocket 因此保持连接；agent 需要你回答时，另一条通知（渠道 `mizar-alerts`）会弹出。两者是不同渠道：前者静默、不可清除，后者会响并出横幅。

## 你必须在 ColorOS 上手动开的两项

前台服务、AOSP 电池白名单（`dumpsys deviceidle whitelist`）、`standby bucket = EXEMPTED` **都挡不住 OPPO 的 freezer**。2026-10-18 实测：白名单加满之后，进程仍然 `isFrozen=false`、`cgroup.freeze=0`、`curProcState=4`（前台服务）—— 系统不承认冻结，但**整进程一点 CPU 都不给**：连一条普通的 `ScheduledExecutorService` 线程都停摆。需要：

1. **设置 → 电池 → 应用耗电管理 → DSH-Mizar → 允许后台活动 / 不限制**
2. **设置 → 应用 → 应用自启动管理 → DSH-Mizar → 允许**

另外建议在最近任务里给 DSH-Mizar 上锁。（「应用速冻」名单在部分 ColorOS 版本里已合并进第 1 项，找不到就不必单独设。）

### 怎么确认真的生效了

用一条**不依赖协程**的线程当探针，比看日志形态可靠：

```bash
adb logcat -s mizar-notify     # 前台服务起的时候应每 60 秒有一行 heartbeat
```

改设置前后各看两分钟：改之前，退到后台约 15 秒后所有定时器一起停（`heartbeat` 不再出现）；改之后 `heartbeat: connected=true silent=0 foreground=false` 会一直续下去。

## 完成通知的三层原因（0.8.18 的修复）

一次「一轮结束了但手机没反应」其实是三个独立缺陷叠在一起，缺任何一层的修复都看不出来：

1. **重连是有限的**：`beginReconnect` 原来 `repeat(6)`，约 30 秒后永久置 `FAILED` 不再拨号。手机在口袋里断一次线就再也不连了。现在用 `Backoff.persistent`（0.5→30 秒，最后一步封顶）无限重试，只有凭证被拒才停。
2. **半死的连接察觉不到**：读超时 60 秒，轮询失败被 `?: return` 吞掉，既不报警也不重连。现在 10 秒一次的 `interactions.pending` 就是心跳（5 秒超时），连续两次无应答即判定死链并重连；后台的会话列表刷新也限制在 20 秒，免得它把心跳堵住。
3. **系统冻结进程**：见上一节，只能靠手机设置解决。

三处都修好后，实测（0.8.18，OnePlus 12 / ColorOS）：

```
10:57:23.493  event turn.settled
10:57:23.760  settled session-f779…: posted=true failed=false body=… · 3 min 9 s · …
```

事件到达后 267 ms 就把通知发出去了；同一次轮询随后读到同一轮，被 `notifiedFinished` 正确去重（`already announced`）。

## 实现上的五个坑（都踩过，别再踩回去）

1. **不要在 `onStop` 里停服务**：按 Home 就会触发 `onStop`，服务被杀等于没有保活。只在 `onDestroy` 且 `isFinishing` 时停。
2. **`foreground` 默认必须是 `false`**：进程被系统重启后只剩服务、没有 Activity；默认 `true` 会把「操作员不在」误判成「正在看屏幕」，从而**压制**该发的通知。
3. **待办通知的 id 不能是负数**：`sessionId.hashCode()` 可能为负，`1000 + hash` 得到负 id 时系统**静默丢弃**。现在用 `1000 + abs(hash) % 900`，与前台服务的 `id = 1` 也不冲突。
4. **不要用 `%1$s` 拼正文**：资源占位符 + `runCatching` 会让 `IllegalFormatException` 被吞掉，通知就「消失且无日志」。正文用 Kotlin 拼接，失败走 `.onFailure { Log.w(...) }`。
5. **watcher 不要跑在主线程调度器上**：事件流和轮询都是网络工作，放 `Dispatchers.Default`；主线程一停它们就全停，而这恰好是没人看着的时候。

服务不拥有连接：连接在 App 的 ViewModel 里，服务只负责让系统不回收进程；任务被划掉时 ViewModel 清空、socket 关闭，服务随之停止。
