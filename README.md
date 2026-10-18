# DSH-Mizar Android

DSH-Mizar 的 Android 客户端：在手机上查看 DSH agent 的会话、读对话、发消息、回答审批与提问。
推送走 FCM（可选），通知经中继转给 Firebase；没有 FCM 配置时 App 照常可用，只是没有后台通知。

- `mizarkit/` —— 纯 Kotlin/JVM 的协议核心：帧、配对、连接状态机、会话投影、缓存接口、诊断。
  不带任何 Android 依赖，测试在 JVM 上秒级跑完。
- `app/` —— Compose 界面、扫码配对、通知、Keystore 支撑的加密存储。
- `plugin/` —— 与这个客户端配套的**主机插件**（`dsh-mizar` 的一份镜像）。上游
  [DSH-Mizar](https://github.com/ShirazuNagisa/DSH-Mizar) 同时服务 iOS 和 Android，不能只为一边改；
  Android 需要的增量（`turn.settled` 广播 + 断线补发）落在这一份里。同步方法见
  [docs/plugin.md](docs/plugin.md)。

构建环境、对真实宿主跑通链路、与 iOS 客户端的差异，见 [docs/android.md](docs/android.md)。
界面重构（换人重写 `app/` 的 Compose 层）的边界、契约与验收清单，见
[docs/gui-refactor-handoff.md](docs/gui-refactor-handoff.md)。

## 构建

需要 JDK 17 和 Android SDK（platform 35 + build-tools 35.0.0）。`app/google-services.json`
是可选的，且不提交（见 docs/android.md 的 FCM 一节）。

```sh
./gradlew :mizarkit:test            # 纯 JVM 协议测试
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
```

对一台运行中的宿主跑一遍真实链路（宿主日志里有配对码和监听地址）：

```sh
./gradlew :mizarkit:hostCheck "-Pbase=http://<host>:19390" "-Pcode=<CODE>"
```

协议本身由主机插件实现，见 [DSH-Mizar](https://github.com/ShirazuNagisa/DSH-Mizar)；
中继见 [DSH-Mizar-Relay](https://github.com/ShirazuNagisa/DSH-Mizar-Relay)；iOS 客户端见
[DSH-Mizar-iOS](https://github.com/ShirazuNagisa/DSH-Mizar-iOS)。
