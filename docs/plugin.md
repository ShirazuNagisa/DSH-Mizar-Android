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
| `lib/` | 由 `src/` 编译产生，与源码一同提交 | 宿主直接加载 `lib/`，不跑构建 |

除此之外与上游完全一致。`session.refresh` 不再携带：客户端在宿主不实现时自动退回三次普通读取。

## 同步上游

上游更新后：

```sh
# 1. 更新镜像源（保持干净）
git -C E:\Projects\DSH-Mizar fetch origin && git -C E:\Projects\DSH-Mizar reset --hard origin/main

# 2. 用上游的树覆盖 plugin/（会丢掉增量）
git -C E:\Projects\DSH-Mizar archive --format=zip -o %TEMP%\plugin.zip HEAD
# 解开到 plugin/，然后重新落一次下面的增量

# 3. 重建并跑测试
cd plugin && node node_modules\typescript\bin\tsc -p tsconfig.json && node client\build.mjs
node node_modules\vitest\vitest.mjs run

# 4. 装到宿主：DSH 的 profile 把 dsh-mizar 链接到本目录（见下），重启 DSH 后生效
```

增量本身很小（`src/index.ts` 里约 10 行），冲突时按上表的语义重写即可，不要机械合并。

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
