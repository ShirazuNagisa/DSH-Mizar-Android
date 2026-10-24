# 账户

账户是可选的。不登录时，配对、会话、对话、审批、附件（局域网）、运行细节（局域网）、推送全部照常；
登录只做三件事：把付费状态带到这台手机上、让附件与运行细节在中继下也能用、让中继的流量归到这个安装。

## 支持的方式

| 方式 | 客户端 | 服务端 |
|---|---|---|
| 用户名或邮箱 + 密码 | 注册（可能要先收验证码）、登录 | `/account/v1/register`、`/login` |
| 忘记密码 | 两步：邮箱 → 验证码 + 新密码，成功后直接登录 | `/account/v1/password/reset/start`、`/password/reset/complete` |
| GitHub | 浏览器授权，回跳 `mizar://auth` | `/account/v1/github/*` |

没有 Google 登录、没有邮箱验证码登录（`/login/code/*` 还没做）。一台手机只保留一个账户会话。

### 注册的两条路（0.10.4 起）

服务端配了邮件（`MIZAR_MAIL_ROLE` 不是 `off`）时，`/register` 无码会返回 **428
`EMAIL_VERIFICATION_REQUIRED`**；没配邮件时它会直接建号。客户端不知道对面是哪一种，也不该知道——
所以做法是**先单步试一次**：

- 成功 → 结束（无邮件的服务器，一次往返）。
- 428 → 调 `/register/start` 拿验证码，界面换成只剩一个输入框，提交时把 `code` 一起发回去。
- `/register/start` 自己回 503 `MAIL_UNAVAILABLE` → 说明服务器说"要码"却又发不出码，按错误报给用户。

这样同一个包在两种部署上都能注册。iOS 只走两步，因此在没配邮件的服务器上注册会 503——这一点安卓比 iOS 宽。

重置的第一步对**不存在的地址也返回同样的成功**，所以界面写的是「如果这个地址有账户，验证码已经在路上」——
这不是措辞选择，是服务端防止枚举账户的决定，客户端不能把它说成确认。`scripts/account-mock.mjs` 里
验证码固定为 `123456`，方便在 AVD 上把整个流程走完。

## 服务端

账户服务只在中继的**主力1**上开启（`accounts.enabled=true`），GitHub 还要求 `accounts.github.enabled=true`。
完整配置与 OAuth App 创建步骤见中继仓库的 `docs/deployment.md` 第 11.5.1 节。

客户端把地址编译进 `BuildConfig.ACCOUNT_BASE_URL`，默认生产：

```sh
# 指向 AVD 里的 mock
./gradlew :app:assembleRelease -PmizarAccountBase=http://10.0.2.2:8787/account/v1
```

## 邮箱登录

- 注册：用户名 2–32（不含空白与 `@`）、邮箱、密码 8–128。
- 登录：`identifier` 是用户名或邮箱。
- 服务端对纯密码账户限 2 台设备，第三台会踢掉最早的一台，并回一个 `DEVICE_LIMIT` notice；
  客户端在账户页把它写成「X 已退出登录，为这台手机腾出位置」。
- 绑定 GitHub 之后不再受 2 台上限（与 Apple 账户一致）。

## GitHub 登录

GitHub OAuth App 的回调只能是 http(s)，所以 secret 与回调都留在中继上：

```
手机  POST /github/start {device}         → {authorizeUrl, state}
手机  用浏览器打开 authorizeUrl
GitHub → GET /github/callback?code&state   → 中继换 token、读身份
中继  303 /github/done?handoff=MZ-…
用户  点「Open Mizar」→ mizar://auth?handoff=…
手机  POST /github/exchange {handoff, device} → {token, user}
```

- handoff 5 分钟、单次、绑定发起登录的 `device.id`；错 5 次作废。
- 浏览器里不出现账户令牌，只有这张一次性的 handoff。
- 落地页给三个出口：`intent://`、`mizar://`、手输代码。App 的账户页有「有登录代码？」入口，
  对应第三种。
- 已登录时点「绑定 GitHub」，`/github/start` 带 Bearer，回调把 GitHub 绑到当前账户而不是新建。

### 邮箱冲突

注册不验证邮箱，所以 GitHub 的已验证邮箱**不会**静默合并进一个「有密码但邮箱未验证」的账户
（那等于让抢注邮箱的人接管）。这种情况返回 `409 GITHUB_EMAIL_CONFLICT`，客户端提示：
先用密码登录，再在账户页绑定 GitHub。

## 客户端结构

| 层 | 位置 |
|---|---|
| 协议模型 / 客户端 / 错误映射 | `mizarkit/.../kit/account/` |
| 令牌存储（EncryptedSharedPreferences） | `app/.../store/AccountStores.kt` |
| 状态机 | `AppViewModel` 的 `AccountUiState` |
| 账户页 / 设置行 | `app/.../ui/account/` |
| 深链 | `MainActivity.readIntent`（按 host 分流 `pair` / `auth`） |

状态机四态：`IDLE → LOADING → READY`，网络失败进 `UNREACHABLE`（保留资料，不清会话）；
只有 401 / `SESSION_REVOKED` / `ACCOUNT_DISABLED` 才清本地并回到未登录。

登录/登出/删号之后会重连一次：中继的身份头只在 WebSocket 升级时读，活着的 socket 改不了。

## 身份头

| 头 | 何时带 | 值 |
|---|---|---|
| `x-mizar-client-id` | 所有中继连接 | 安装 id（36 位小写 UUID，跨登出稳定） |
| `x-mizar-account` | 已登录的中继连接 | `sha256(token)`，64 位小写 hex |
| `x-mizar-session` | 已登录，且中继 origin == 账户 origin | 令牌本身（付费门禁用） |

直连（局域网）连接不带任何身份头。

## 在 AVD 上测

```sh
# 1. mock 账户服务（零依赖）
node scripts/account-mock.mjs --host 0.0.0.0 --port 8787 --public http://10.0.2.2:8787

# 2. 指向 mock 的包
GRADLE_USER_HOME=E:\gradle-home2 ./gradlew :app:assembleRelease -PmizarAccountBase=http://10.0.2.2:8787/account/v1

# 3. 模拟器
pwsh -File scripts/emulator/emu.ps1 boot   # 工具内必须后台运行
pwsh -File scripts/emulator/emu.ps1 wait
pwsh -File scripts/emulator/emu.ps1 install

# 4. 模拟 GitHub 回调（不经过浏览器）
adb -s emulator-5556 shell am start -a android.intent.action.VIEW -d 'mizar://auth?handoff=MZ-XXXX-XXXX'

# 没有配对电脑时，也可以直接打开账户页
adb -s emulator-5556 shell am start -a android.intent.action.VIEW -d 'mizar://account'
```

固定数据：`shirazu / mizar-test-password`，邀请码 `MOCK-INVITE`（+30 天）。
`GET /account/v1/me?fail=revoked` 用来验 401 清会话；`POST /account/v1/reset` 复位。

模拟器的 Chrome 首次启动可能卡在「正在核对信息」，所以 GitHub 的浏览器环节常常走不通。
mock 为此留了一个测试端点：`GET /account/v1/debug/flows` 返回 App 刚创建的 `state`，
把它直接喂给 `/account/v1/github/callback`，拿到 handoff 后再 `am start` 那条深链即可。
这条路径覆盖的是 App 侧的 exchange 与绑定逻辑；浏览器与回调页属于中继的测试范围。

### 手工验收

1. 未登录 + 中继：附件栏与运行细节不可见。
2. 登录 + 兑换 `MOCK-INVITE` → 付费：中继下两者出现。
3. 飞行模式重开账户页：仍显示用户名 +「暂时无法连接」，不是未登录。
4. 杀进程重开：仍登录；登出后重开：未登录。
5. 设置里「忘记这台电脑」后：账户仍登录。
6. `adb logcat` 里没有完整的 `mza_` 令牌。

## 错误码

`INVALID_INPUT`、`UNAUTHENTICATED`、`SESSION_REVOKED`、`ACCOUNT_DISABLED`、`USERNAME_TAKEN`、
`EMAIL_TAKEN`、`RATE_LIMITED`、`INVITE_INVALID/EXPIRED/USED_UP`、`NETWORK`、`MALFORMED_RESPONSE`、
`GITHUB_NOT_CONFIGURED`、`GITHUB_STATE_INVALID/EXPIRED`、`GITHUB_DENIED`、`GITHUB_EXCHANGE_FAILED`、
`GITHUB_UNAVAILABLE`、`GITHUB_HANDOFF_INVALID/EXPIRED`、`GITHUB_DEVICE_MISMATCH`、
`GITHUB_EMAIL_CONFLICT`、`GITHUB_ACCOUNT_CONFLICT`、`GITHUB_ALREADY_LINKED`。

客户端优先用响应体里的 `error` 字段分支，状态码只是兜底；`404` 与 `204` 按不可读响应处理。
