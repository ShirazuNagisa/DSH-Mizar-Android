# 附件上传与会话设置功能分支

本分支基于上游 `427c713e3d6e577b461458894991fa6647b6c661`（Android 0.8.21），是非官方源码扩展，不是作者的正式发布。保留原版功能和主机通知补丁；不在本分支发布APK或npm包。

## 功能

### 图片与文件

- Android Photo Picker / OpenDocument，无全盘存储权限。
- 单件20 MiB、每条消息最多5件，支持纯附件消息。
- PNG/JPEG/WebP/GIF作为DSH图片内容；普通文件原字节进入DSH的 `fileUploads.uploadStream`，仅使用主机生成的receipt。
- 已认证WebSocket内64 KiB分块，校验字节数/SHA256、设备与会话归属；重试幂等、暂停/删除、图片草稿缩略图、历史附件信息卡。
- 私有暂存目录、资源配额、30分钟TTL。主机成功接收消息后释放临时附件；失败保留供显式重试。
- 草稿不跨App进程死亡持久化；HEIC等需要先转换。图片最长边8192px、最多3200万像素；模型仍须支持图片。

协议：[ATTACHMENT-PROTOCOL.md](../ATTACHMENT-PROTOCOL.md)。

### 模型思考强度和会话访问权限

- 输入框上方固定显示 **模型 / 思考强度 / 访问权限**，显示当前值。
- 档位来自对应provider/model的实时目录，不硬编码low/medium/high。
- 动态权限目录，当前档位勾选；`custom`仅展示，`auto`仅宿主提供时出现；完全访问需要二次确认。
- 加载失败/重试/保存状态可见，模型用主机确认返回值，权限读回后才报告成功。
- 主机、设备、会话、连接及请求代次隔离，避免迟到结果覆盖另一条会话。新的投影优先，不让手机乐观缓存永久覆盖桌面状态。
- 权限仅作用于当前会话。DSH 0.2.1-rc.2 的模型选择接口会同时保存未来新会话的模型默认，界面注明这一宿主行为。

## 版本与构建

- Android：`0.8.21-attachments.2`，versionCode38，包名 `me.shirazu.dshmizar.attachments`，最低Android8；与官方原版并存。
- 主机插件：`dsh-mizar@0.8.3-attachments.1`，匹配DSH `0.2.1-rc.2`。不要同时加载原插件和本插件两个实例。
- Android需要JDK17、SDK35、build-tools35.0.0；Gradle wrapper已包含。

```sh
./gradlew :mizarkit:test :app:assembleDebug :app:lintRelease
# Release需要配置自己的签名；没有keystore配置时不能把unsigned包当成可安装发布包
./gradlew :app:assembleRelease

cd plugin
npm ci
npm run build
npm run typecheck
npm test -- --maxWorkers=2
npm pack --ignore-scripts
```

用插件管理器将生成的tgz安装到需要的DSH profile，然后手动重启。已有Mizar状态不需要删除。Android的新包名需自行配对。Firebase配置可选，不包含任何官方推送凭据。

Release签名使用本机忽略的 `keystore.properties` 或既有构建脚本支持的环境变量；**不要提交密钥、密码、配对码或token**。未修改上游tag发布workflow，本功能分支不打tag、不自动发布Release。

`plugin/lib/`遵循上游约定提交构建产物，以支持插件直接安装；APK/AAB/tgz、缓存、测试profile、原始日志和本机辅助脚本均排除。

## 验证摘要

2026-10-18，本地Windows验证记录：

| 检查 | 结果 |
|---|---|
| 插件build/typecheck | 通过 |
| 插件Vitest | 587/587，29个测试文件，新增84项 |
| Kotlin/JVM | 232/232，含附件与会话设置测试 |
| Android Release / APK验签 | 通过，独立包名/自有签名 |
| Android lint | 0错误，53警告 |
| Kotlin → 隔离真实DSH | 思考强度设置读回、无强度模型、三档权限读回、图片/文件、纯附件prompt、重复请求通过 |
| 本地原版中继 → 真实DSH | 五附件、20MiB文件、落盘哈希、幂等通过；使用离线确定性模型验证图片引用进入模型请求 |
| Android真机UI | 本地自动化环境没有接入设备；选择器、触控、厂商后台策略仍需设备验收 |

Kotlin真实连接检查需自行启动隔离宿主并提供一次性配对码，入口限制为loopback19491，避免误改正式会话：

```sh
./gradlew :mizarkit:attachmentHostCheck -Pbase=http://127.0.0.1:19491 -Pcode=<TEST_CODE> -Pcwd=<TEST_WORKSPACE>
```

该检查要求隔离模型目录提供支持图片且至少两档思考强度的模型，以及一个无思考档位模型。不要对生产profile执行；测试会创建会话、设置权限并发送生成的测试附件。详细插件覆盖见 [host测试记录](../plugin/docs/attachment-test-results.md)。

## 中继、安全和已知边界

- 公开Relay源码本地实测：900 KiB单帧通过，900 KiB+1字节以1009拒绝；64 KiB分块能传输20MiB文件，无需修改中继源码。
- 公共中继线上测试在建立测试连接阶段返回HTTP429，**没有验证线上单帧/总文件/带宽上限**。没有绕过限流或继续施压。不能把本地测试结果说成公共服务器已经验证通过。
- HTTPS/WSS不等于端到端加密：中继运营者能读取经过的内容。敏感文件应使用可信VPN或自建中继；本分支不实现E2EE。
- 普通文件上传不自动执行，也不保证任意文件类型都能被模型直接理解。
- 强制结束主机进程可能遗留私有临时目录；正常退出和TTL会清理，详情见 [host安全说明](../plugin/docs/attachments-local.md)。

## 上游与授权

上游项目：[DSH-Mizar-Android](https://github.com/ShirazuNagisa/DSH-Mizar-Android)、[电脑插件](https://github.com/ShirazuNagisa/DSH-Mizar)、[Relay](https://github.com/ShirazuNagisa/DSH-Mizar-Relay)。

保留作者的全部既有版权/许可证声明。主机插件MIT不自动覆盖Android App及mizarkit；本检出没有明确的Android根许可证。本源码分支不赋予额外授权，也不声明整个项目为MIT。对外发布二进制、商用或其他再分发前请确认作者授权。
