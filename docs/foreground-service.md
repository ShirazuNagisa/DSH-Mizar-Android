# 后台保活与待办通知

App 退到后台时，一条常驻通知（渠道 `mizar-connection`）让进程不被回收，WebSocket 因此保持连接；agent 需要你回答时，另一条通知（渠道 `mizar-alerts`）会弹出。两者是不同渠道：前者静默、不可清除，后者会响并出横幅。

## 你必须在 ColorOS 上手动开的两项

前台服务与 AOSP 电池白名单**都挡不住 OPPO 的 freezer**（实测：`OplusHansManager` 冻结进程后，10 秒轮询直接停摆）。需要：

1. **设置 → 电池 → 应用耗电管理 → DSH-Mizar → 允许后台活动 / 不限制**
2. **设置 → 应用 → 应用自启动管理 → DSH-Mizar → 允许**

另外建议在最近任务里给 DSH-Mizar 上锁。

## 实现上的四个坑（都踩过，别再踩回去）

1. **不要在 `onStop` 里停服务**：按 Home 就会触发 `onStop`，服务被杀等于没有保活。只在 `onDestroy` 且 `isFinishing` 时停。
2. **`foreground` 默认必须是 `false`**：进程被系统重启后只剩服务、没有 Activity；默认 `true` 会把「操作员不在」误判成「正在看屏幕」，从而**压制**该发的通知。
3. **待办通知的 id 不能是负数**：`sessionId.hashCode()` 可能为负，`1000 + hash` 得到负 id 时系统**静默丢弃**。现在用 `1000 + abs(hash) % 900`，与前台服务的 `id = 1` 也不冲突。
4. **不要用 `%1$s` 拼正文**：资源占位符 + `runCatching` 会让 `IllegalFormatException` 被吞掉，通知就「消失且无日志」。正文用 Kotlin 拼接，失败走 `.onFailure { Log.w(...) }`。

服务不拥有连接：连接在 App 的 ViewModel 里，服务只负责让系统不回收进程；任务被划掉时 ViewModel 清空、socket 关闭，服务随之停止。
