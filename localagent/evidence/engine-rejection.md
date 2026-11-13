# 引擎选型证据：为什么不用 `app.cash.quickjs:quickjs-android`

> 本文件是 2026-11-13 本轮 PoC 的复核记录：前半是 Maven 产物事实（可随时重验），
> 后半是当时在 AVD `emulator-5558`（API 36 / x86_64 / 4 KB 页）上的实测日志摘录。

## 1. Maven 事实（2026-11-13 复核）

`app.cash.quickjs:quickjs-android:0.9.2`（Maven Central，最后一次发布 2021-08-04）：

- AAR 内 `.so`：`arm64-v8a` 854 224 B、`armeabi-v7a` 538 328 B、`x86` 878 304 B、
  `x86_64` 928 224 B；
- ELF LOAD 段对齐（`p_align`，全部 2 个 LOAD 段）：

| ABI | `p_align` | 16 KB 页兼容 |
|---|---|---|
| arm64-v8a | `0x1000`（4 KB） | ✗ |
| armeabi-v7a | `0x1000`（4 KB） | ✗ |
| x86 | `0x1000`（4 KB） | ✗ |
| x86_64 | `0x1000`（4 KB） | ✗ |

- AAR `uses-sdk`：`minSdkVersion 18`；Java API 只有 `QuickJs.evaluate/compile/
  execute/get/set`（`set` 只支持原语参数）。
- `QuickJs.java` 的 native `evaluate` 路径没有 `JS_ExecutePendingJob`（可通过
  §2 的行为实测反推；同库的 `QuickJs` 没有暴露任何 job/loop API）。

同年代备选复核：

- `io.github.taoweiji.quickjs:quickjs-android:1.4.6`（2021-06-19）：`.so`
  同样是 `p_align=0x1000`（含 `libquickjs.so` 与 `libquickjs-android.so` 两个库）；
  有 `EventQueue`/plugin，但未再验证 promise 行为（同年代、无 16 KB，已无收益）。
- `com.amplitude:quickjs-android:1.0.1`（2025-05-29）：Maven 上只有
  `packaging=pom` 的占位 POM（`All rights reserved`），无 AAR/JAR，不可用。
- J2V8：桌面 `com.eclipsesource.j2v8:j2v8` 6.3.4（2025-11-14 的 metadata），
  但 Android 产物 `j2v8_android` 停在 3.0.5（2015-10-27）；跨 ABIs / ES2020
  支持 / 体积均不适合，未实测。

## 2. 设备行为实测（同一份内核 bundle、同一套 Kotlin 桥）

用 `app.cash.quickjs:quickjs-android:0.9.2` 时的 logcat（AVD mizar36，API 36）：

```
I LocalAgentJni: quickjs context created
I LocalAgentPoC: boot: ready=false microtasksDrained=false deviceId=null
  bundle=56505B eval=151ms total=376ms root=/data/user/0/.../files/LocalAgent
```

对照：同一模块、同一份 `assets/mizar-kernel.js`，换成 vendored QuickJS-NG 0.10.1
+ 自写 JNI（每次 `evaluate` 后 `JS_ExecutePendingJob` 排空）后：

```
I LocalAgentPoC: boot: ready=true microtasksDrained=true deviceId=b2c847b6-...
  bundle=56505B eval=12ms total=147ms
I LocalAgentPoC: call kernel.status -> {"v":1,"type":"ok",...,"protocolVersion":1,...} (2ms)
I LocalAgentPoC: call kernel.config.get -> {"v":1,"type":"ok",...,"config":{...}} (2ms)
```

结论：0.9.2 的失败不是配置/参数问题，而是 binding 的 embedder 循环缺了 Promise
job 排空；内核 `createKernel` 的第一次 `await` 之后再也无法推进。16 KB 对齐是
第二个独立的硬阻塞。
