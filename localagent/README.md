# localagent — Android 本地 Agent（PoC）

iOS 的「本地 Agent」用 JavaScriptCore 跑同一份 TypeScript 内核
（`iOS/LocalKernel/kernel`）。这个模块把它搬到 Android：**同一份内核产物**
装进 assets，用 **QuickJS-NG** 执行，Kotlin 只实现内核要的 `PlatformHost`
（文件、KV、日志、时间、定时器、HTTP 占位）和 Mizar 协议 v1 的客户端帧。

本模块只做到「PoC 跑通」：没有接主 UI、没有网络请求、没有 turn 循环。
可行性结论、实测数字、工作量与风险见
[`docs/android/local-agent-poc-2026-11-13.md`](../../../docs/android/local-agent-poc-2026-11-13.md)。

## 结构

```
localagent/
  build.gradle.kts                  library 模块 + syncKernelBundle/checkKernelBundle
  scripts/build-quickjs.ps1         重编 libmizarquickjs.so（NDK r28c + ndk-build）
  src/main/assets/mizar-kernel.js   与 iOS dist 逐字节相同的内核产物（提交入库）
  src/main/assets/localagent-host.js  PlatformHost 的 JS 侧（回调式 → 单 JNI 函数）
  src/main/cpp/quickjs/             QuickJS-NG 0.10.1 vendored（MIT）
  src/main/cpp/jni_bridge.c         JNI：context、evaluate+Promise job 排空、单回调
  src/main/cpp/Android.mk / Application.mk
  src/main/jniLibs/{x86_64,arm64-v8a}/libmizarquickjs.so   prebuilt（16 KB 页对齐）
  src/main/kotlin/me/shirazu/dshmizar/localagent/
    LocalKernelEngine.kt            引擎线程、boot、多条连接、host op 路由、kernel.* 调用
    LocalKernelLink.kt              引擎给 transport 的窄接口（+ 连接的帧通道）
    LocalKernelTransport.kt         进程内 WebSocketTransport：把内核当宿主 dial
    LocalAgentHost.kt               引擎生命周期 / transport 工厂 / 配置 / 状态 / 清数据
    LocalInteractions.kt            审批/提问的自动回答（AUTO_DENY / AUTO_ALLOW）
    HostHttpTransport.kt            host.http：OkHttp 流式（行粒度 chunk/done/error/abort）
    HostEventQueue.kt               引擎线程外完成的 host 事件（http chunk）队列
    QuickJsNative.kt                JNI 方法声明（4 个）
    HostDispatcher.kt               JS → Kotlin 的唯一方法 call(op, first, second)
    MizarHostSandbox.kt             沙箱 fs、KV、日志、uuid/now
    Envelopes.kt                    {ok,value} / {ok,error} 信封
    KernelKeyValueStore.kt          与 iOS 同名接口；PoC 用 SharedPreferences
    KernelProtocol.kt               client frame：hello / 请求 / 应答解析
  src/test/...                      JVM：transport 帧规则、交互回答 payload
  src/androidTest/...               设备：PoC 自测 + 真 MizarConnection 一轮 turn
  evidence/                         上一轮 PoC 实测证据（png / logcat / json / txt）
```

## 引擎

**`app.cash.quickjs:quickjs-android` 不可用**（本轮实测）：它的 native
`evaluate` 不排空 QuickJS 的 Promise job 队列，而内核从 `createKernel` 起就是
async，于是 boot 停在第一次 `await`（logcat：`ready=false
microtasksDrained=false`）；另外 2021 年编译的 4 个 ABI 的 `libquickjs.so` 都是
4 KB 页对齐，Android 15+ 的 16 KB 页设备无法加载，也过不了 Google Play 的
16 KB 要求。同年代的 quickjs-android fork（`io.github.taoweiji.quickjs:1.4.6`）
同样 4 KB 对齐，Amplitude 的 `com.amplitude:quickjs-android` 在 Maven 上只有空
POM，J2V8 的 Android 产物停在 2015 年。详见报告的候选对比表。

因此本模块 **vendored QuickJS-NG 0.10.1**（`src/main/cpp/quickjs/`，MIT）并自带
一个很薄的 JNI bridge（`src/main/cpp/jni_bridge.c`，约 470 行）：

- `create()`：`JSRuntime` + `JSContext`，内存上限 256 MB，注册
  `__mizarNativeCall(op, a, b)`；
- `evaluate(handle, script, fileName)`：`JS_Eval` 之后**循环
  `JS_ExecutePendingJob` 直到队列为空**，再把结果转成 Java 字符串 —— 这一步是
  内核能 boot 的前提；
- `setDispatcher()`：保存 Kotlin `HostDispatcher` 的全局引用并缓存 `call`
  方法；JS 侧的 host 函数都经它同步回 Kotlin（只传/回字符串）；
- `close()`。

**与 impl-plan 的偏差**：引擎没有写进 `gradle/libs.versions.toml`，因为它不是
Maven 依赖（源码 vendored），版本固定在 vendored 目录 +
`LocalKernelEngine.ENGINE_VERSION`；构建产物是提交的 prebuilt `.so`，正常
`:app:assembleDebug` 不需要 NDK。

重新编译原生库（改了 `src/main/cpp/**` 才需要；NDK r28c 已验证）：

```powershell
cd Android
pwsh localagent\scripts\build-quickjs.ps1
# 输出：src/main/jniLibs/{x86_64,arm64-v8a}/libmizarquickjs.so，并校验 16 KB 对齐
```

| ABI | libmizarquickjs.so | ELF LOAD 对齐 | 用途 |
|---|---|---|---|
| x86_64 | 965 224 B | `0x4000`（16 KB） | 模拟器（mizar36） |
| arm64-v8a | 894 176 B | `0x4000`（16 KB） | 真机 |

`minSdk 26`、`targetSdk 36`（library 的 targetSdk 必须显式写 36，否则测试 APK
会弹「为旧版 Android 构建」的对话框）。32 位 ABI 刻意不构建：minSdk 26 与 Play
的 64 位要求下没有实际设备收益，只翻倍构建与包体。

## 内核产物同步

产物只有一个来源：`iOS/LocalKernel/kernel/dist/mizar-kernel.js`（
`npm run build` 生成，同一脚本生成 iOS 内嵌的 `KernelBundle.swift`）。
`localagent/src/main/assets/mizar-kernel.js` 是它的副本，**提交入库**，这样
Android 仓库单独 checkout 也能构建。

```powershell
cd Android
$env:JAVA_HOME='C:\Users\84494\AppData\Local\Programs\AndroidDev\jdk-17'
$env:GRADLE_USER_HOME='D:\Env\gradle-home-android'

.\gradlew.bat :localagent:syncKernelBundle    # iOS dist -> assets（需要 ../iOS 存在）
.\gradlew.bat :localagent:checkKernelBundle   # SHA-256 比对，不一致时失败
```

当前副本：56 505 字节，SHA-256
`0ec1242a2900440da4c3be8249b024622a97dff09a1c69a68a893bb20ee5495b`，与
`KernelBundle.swift` 解出来的字节完全相同（同一份 esbuild IIFE，`target es2020`，
全局名 `MizarKernel`）。同步任务刻意**不挂到 preBuild**：跨仓库取文件必须是显式、
可评审的一步。

## 桥接方式（为什么长这样）

内核的 `PlatformHost`（`iOS/LocalKernel/kernel/src/host/host.ts`）是回调式：

```ts
fs.readText(path, cb)   // cb.ok(text | null) / cb.fail({message})
http.request(req, handlers)
setTimeout(fn, ms)
```

JNI 能传任意对象，但让 JS 回调对象过桥意味着 Kotlin 要管理 JS 对象生命周期并在
正确线程回调。PoC 采用更薄的设计：

1. JS 侧（`assets/localagent-host.js`）实现完整的回调式 host；每个方法同步调用
   桥函数 `__mizarNativeCall(op, a, b)`（3 个字符串），拿到 JSON 信封
   `{"ok":true,"value":…}` / `{"ok":false,"error":…}`，然后在 **JS 里**调用
   `cb.ok()` / `cb.fail()`。回调从不跨桥。
2. Kotlin 侧（`MizarHostSandbox`）按 op 字符串路由：路径按内核约定解析并拒绝
   逃逸沙箱（与 iOS `KernelRuntime.resolve` 同一套组件算术）；文件写用临时文件
   + rename；`kv` 走 `KernelKeyValueStore`（PoC 是 SharedPreferences，正式接线换
   `EncryptedSharedPreferences`）。
3. 定时器：QuickJS 没有事件循环，`setTimeout` 只在 JS 里登记，Kotlin 在每次请求
   前后调 `__mizarPump()` 执行到期回调。PoC 的 host 全是同步完成，这套泵已够用；
   真正的 turn 循环需要「后台结果 → 回到 JS 线程」的通道（生产化工作项）。
4. 协议：`KernelProtocol` 拼 `hello` 与请求帧；内核把 `welcome`/`ok`/`error`
   帧通过 `connection.onFrame` 推回，JS 里落地成 `__mizarNativeCall("frame", …)`，
   Kotlin 按 `id` 关联。连接、帧、方法表全部来自内核自己 —— 客户端只补了信封。

线程模型与 iOS 相同：**所有** JS 上下文访问都在一个 `HandlerThread`
（`mizar-localagent-engine`）上，`boot()`/`call()`/`evaluate()` 阻塞调用方并把
工作投到该线程；`Listener.onFrame` 也在该线程触发，UI 需自行切线程。

## 跑 PoC

测试是 library 模块的 self-instrumenting androidTest APK，不需要装 app：

```powershell
cd Android
$env:JAVA_HOME='C:\Users\84494\AppData\Local\Programs\AndroidDev\jdk-17'
$env:GRADLE_USER_HOME='D:\Env\gradle-home-android'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"

.\gradlew.bat :localagent:assembleDebug :localagent:assembleDebugAndroidTest

adb -s emulator-5558 install -r -t localagent\build\outputs\apk\androidTest\debug\localagent-debug-androidTest.apk
adb -s emulator-5558 logcat -c
adb -s emulator-5558 shell am instrument -w `
  -e class me.shirazu.dshmizar.localagent.LocalKernelPoCInstrumentedTest `
  me.shirazu.dshmizar.localagent.test/androidx.test.runner.AndroidJUnitRunner

# 证据（txt/json/png）在设备外部目录，pull 回来即可
adb -s emulator-5558 pull /sdcard/Android/data/me.shirazu.dshmizar.localagent.test/files/localagent-poc .
adb -s emulator-5558 logcat -d -s LocalAgentPoC:V LocalAgentJni:V
```

测试里 `boot()` 完成后断言：`microtasksDrainedOnEvaluate == true`、
`welcome.deviceId` 非空、`kernel.status.protocolVersion == 1`、
`kernel.config.get.config.providers` 存在；并采样 50 次 `kernel.status` 延迟、
记录 native/java heap 与 PSS、设备 `PAGE_SIZE`，最后把摘要贴到
`PoCResultsActivity` 上截图。测试包名固定是
`me.shirazu.dshmizar.localagent.test`（library 模块的自测 APK）。

## 接入 App：传输适配（本轮新增）

App 侧不需要新协议，也不需要改 `mizarkit`：`MizarConnection` 的 transport 是注入了工厂的，
本地模式只是换一个 `WebSocketTransport`，hello/welcome、关联请求、事件流、重连逻辑全部复用。
设计文档见 `Android/docs/local-agent-link.md`。
接线示意（Lead 在 `AppViewModel` 里接，不在本模块）：

```kotlin
private val localAgent = LocalAgentHost(getApplication(), BuildConfig.VERSION_NAME, Build.MODEL)

private suspend fun connectLocal() {
    stopWatchers()
    ownership.current?.disconnect()
    ownership.release()

    val socket = MizarConnection(
        configuration = localAgent.configuration(),       // authority = "local:19390"，永不 dial
        makeTransport = { localAgent.openTransport() },   // 进程内帧通道
    )
    val welcome = socket.connect()                        // hello → welcome，同一套帧
    // 之后和远程完全一样：adoptConnection/becomeLive/startWatchers/refreshSessions。
    // 本地用一个合成 PairedHost 占位（hostId/hostName/deviceId 都来自 welcome）。
    LocalInteractions.launch(viewModelScope, socket, LocalInteractionPolicy.AUTO_DENY)
}
```

- 状态：`suspend fun LocalAgentHost.status(): LocalAgentStatus`（available / configured /
  storedBytes / provider / model / bootError）；`toSettingsLocalAgent()` 转成设置页已有的
  `SettingsLocalAgent`，onboarding 的 `localAgentAvailable/localAgentConfigured` 直接填。
- 入口：`ui/localagent/LocalAgentEntry.kt` 的 `LocalAgentSwitcherRow(status, current, onPick)`，
  挂在 `AgentSwitcher` popover 末尾即可（不改 `AgentCatalog`：本地是另一个宿主，不是第五个 agent）。
- 退出/清理：`localAgent.close()`、`localAgent.clearData()`（只删沙箱，不动模型配置和 KV）。
- 审批：本轮用 `LocalInteractions` 自动回答（默认拒绝）；接现有审批 dock 时不要启动它即可。

## 已知限制（本轮有意为之）

- **轮次不是事件驱动**：内核只推交互一族事件（`interactions.changed`/`approval.*`/`question.*`），
  没有桌面的状态通道和 `turn.settled`；运行中/结束靠 App 现有 `session.messages` 轮询，
  过程行与结束提示会有最多一个 poll 周期的延迟。
- **审批/提问自动回答**：没有卡片；`AUTO_DENY` 拒绝所有审批，提问选第一个选项。真实审批 dock
  接线后停用 responder 即可。
- **桌面独有方法返回 NOT_IMPLEMENTED**（kit 回退 null）：`usage.overview`、`session.sync`、
  `session.stream`、`file.stat`/`file.read`、`relay.set`；因此本地模式下图片、交付文件预览、
  用量页、附件按现有 UI 规则隐藏/提示。附件上传内核本身未实现（`upload.*` NOT_IMPLEMENTED）。
- **http 本轮已实现**（OkHttp 流式，行粒度 chunk / done / error / abort）：模型流式回答可用；
  但 `host.http` 的网络错误只会上报给内核，内核以 `turn/end error` 收尾。
- **fs/kv 仍是同步阻塞**：在内核线程上直接做 IO，大文件读写会卡住定时器与帧投递。
  下一步按 iOS 的做法异步化（后台执行 + 回引擎线程回调）。
- **只有前台可用**：与 iOS 相同；进程被冻结/杀死后内核停止，下次 boot 由内核补 `turn/end`。
- **PoC 默认连接常驻**：boot 为了自测保留一条默认连接（`call()`/状态读取用它），所以 App 的
  transport 断开时内核不会因为“最后一个客户端走了”而 fail-closed 结算 pending 审批；进程或引擎
  销毁时无影响。正式接线可把默认连接改成按需（`call()` 首次使用时再 handshake）。
- **凭据存储**：`SharedPreferencesKernelKeyValueStore` 是明文；正式接线注入
  `EncryptedSharedPreferences`（app 已依赖 `androidx.security-crypto`）。
- **16 KB 真机未实测**：`.so` 已按 16 KB 对齐构建，目前只在 4 KB 页的 `mizar36` AVD 上加载过。
