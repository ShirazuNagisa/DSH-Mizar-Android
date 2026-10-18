# 配套的主机插件（`plugin/`）

`plugin/` 是一份 `dsh-mizar` 的镜像：`DSH-Mizar` 仓库某个提交的完整源码树，加上 Android 需要的少量增量。

## 为什么不直接改 DSH-Mizar

`DSH-Mizar` 同时是 iOS 客户端的宿主插件。为 Android 加一个事件，会让 iOS 那侧多一条它不认识的
广播——协议允许（未知事件被忽略），但"为一边改共用的东西"是错的维护方式：那边的作者不知道这个事件的
存在，重构时会把它删掉，而删除在这里表现为"手机不再收到完成通知"，没有任何报错。

所以规则是：

- **`DSH-Mizar` 保持上游原样**，不提交、不推送本地改动。
- **本仓库的 `plugin/` 是 Android 这一侧的口径**：上游 + 我们需要的增量。
- 宿主加载的是 `plugin/`，不是 `DSH-Mizar`。

## 增量清单

| 文件 | 改动 | 为什么 |
|---|---|---|
| `src/index.ts` | `turn/end` 时除 `notifier.turnEnded` 外，再向所有客户端广播 `turn.settled { sessionId, reason }` | 没有 FCM 的手机收不到推送；完成通知只能靠这个事件，否则退化成轮询 |
| `src/push/missed.ts` + `src/index.ts` | 广播时**没有客户端**的完成事件先记下来，下一个连上来的客户端补发（限 20 条 / 12 小时，新的一轮会作废旧的） | 断线期间"开始并结束"的那一轮，事件丢了、轮询也没见过它 running，否则永远补不回来 |
| `lib/` | 由 `src/` 编译产生，与源码一同提交 | 宿主直接加载 `lib/`，不跑构建 |

除此之外与上游完全一致。`session.refresh` 不再携带：客户端在宿主不实现时自动退回三次普通读取。

## 同步上游

上游更新后：

```sh
# 1. 更新本地检出（它就是镜像源，保持干净、不提交任何东西）
git -C E:\Projects\DSH-Mizar fetch origin
git -C E:\Projects\DSH-Mizar reset --hard origin/main

# 2. 一条命令重建 plugin/：取 HEAD 的树 → 落增量 → 编译 → 跑 503 项测试
node scripts/sync-plugin.mjs

# 3. 看一眼被 staged 的 diff，然后提交
git diff --cached --stat
```

`scripts/sync-plugin.mjs` 做的事就是上面四步，外加两个保护：检出里有未提交的改动会**警告但仍按 HEAD 镜像**
（镜像必须可复现），补丁打不上会**在写盘前失败**（`--check` 先跑），并把结果 stage 好供审阅。
它从不修改本地检出。

```sh
node scripts/sync-plugin.mjs                 # 默认源 ../DSH-Mizar
node scripts/sync-plugin.mjs D:\other\clone  # 指定别的检出
node scripts/sync-plugin.mjs --no-test       # 只编译不跑测试
node scripts/sync-plugin.mjs --client        # 连浏览器半边的 bundle 一起重建
```

`--client` 平时不要加：浏览器半边的产物由上游提交，重建只会改写一行内嵌了 `node_modules` 绝对路径的注释，
让仓库无谓地变脏。

增量本身很小，作为**有序补丁系列**存在 `scripts/patches/`（按文件名顺序应用，编号就是依赖顺序），
冲突时按上表的语义重写对应补丁，不要机械合并；补丁打不上时脚本会停下并告诉你哪一段对不上。

| 补丁 | 内容 |
|---|---|
| `01-turn-settled.patch` | `turn/end` 时向客户端广播 `turn.settled` |
| `02-missed-completions.patch` | 没有客户端时把完成事件记下来，下一个连上来的客户端补发（`push/missed.ts` + 单测） |

## 宿主的加载方式

`~/.dsh/profiles/desktop/package.json`：

```json
{ "dependencies": { "dsh-mizar": "link:E:/Projects/DSH-Mizar-Android/plugin" } }
```

插件在 DSH 启动时载入，改完代码要重启 DSH。判定加载成功：

```sh
curl -s localhost:19390   # 或看插件面板上是否在监听
grep -c "turn.settled" plugin/lib/index.js   # 应为 1
```

`node_modules/` 不提交；在 `plugin/` 下按 `package-lock.json` 装一次即可（`npm ci`，或本机有的 pnpm）。

## 已知限制：手机作答后桌面卡片不关（2026-10-18 决定不处理）

宿主 `UserQuestionService.ask()` 是一次 Cordis waterfall：它取**最先返回**的那个答案，**不会通知或取消其他分支**；
请求里带的 `signal` 是"调用方取消"（会话中断）用的，不是"别人已经答了"。

插件为了让桌面和手机都能答，`prepend` 之后主动调用 `next()` 把 GUI 也拉进竞争。手机先答时，插件把答案交回宿主、
agent 正常继续，但 GUI 那条分支的 promise 永远悬着，它的卡片就留在界面上，直到手动关掉。

抓到的真实请求与结果（一次实测）：

```json
{"event":"request","keys":["questions","agent","signal"],"wait":null,
 "agentId":"session-40f87284…","callId":"mizar-3a2108e2…"}
{"event":"settled","from":"phone","answerCount":1}
```

**不是 bug，也不用修**：agent 已经拿到答案（`from: "phone"`）。插件侧没有 API 能关闭别人的卡 ——
`userQuestions.answer(agent, callId, answer)` 需要宿主的 `callId`，而真实请求里 `wait` 为空，拿不到。
审批卡是同一套 waterfall 结构，行为相同。

如果哪天想彻底解决，正确的方向是给 DSH 上游提：waterfall 被认领后应当通知/取消其它 answerer，
或在请求上提供一个结算时 abort 的共享信号。插件这边单独做不到。

