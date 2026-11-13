package me.shirazu.dshmizar.localagent

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** A failure of the local kernel runtime itself, as opposed to a kernel answer. */
class LocalAgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * One booted local kernel, living on one thread.
 *
 * The shape mirrors iOS `KernelRuntime` (`MizarKit/Sources/MizarKit/Kernel/
 * KernelRuntime.swift`): one JavaScript context, one serial queue, host
 * callbacks delivered onto that queue, and a `boot()` + `openConnection()` pair
 * above it. The difference is the engine — Jeff's QuickJS-NG bridge here,
 * JavaScriptCore there.
 *
 * Threading: every touch of the JS context happens on [engineThread]. The class
 * is safe to call from any thread; [boot], [call], [evaluate] block the caller
 * and execute on the engine thread. Frames the kernel pushes arrive through the
 * JS `onFrame` sink, are recorded in order, and are also handed to
 * [Listener.onFrame] on the engine thread — a UI must hop to its own thread.
 *
 * The [rootDir] is the kernel's sandbox: everything it persists lives under
 * here, and the kernel only ever names paths relative to it.
 */
class LocalKernelEngine(
    context: Context,
    val rootDir: File = File(context.applicationContext.filesDir, "LocalAgent"),
    private val hostName: String = defaultHostName(),
    private val defaultWorkspaceName: String = "默认工作区",
    private val kvStore: KernelKeyValueStore = SharedPreferencesKernelKeyValueStore(context),
    private val listener: Listener = Listener.NONE,
) : AutoCloseable {

    /** The sinks a host (and a test) wants besides logcat. */
    interface Listener {
        /** One structured kernel log line — the host API's `log(level, message)`. */
        fun onLog(level: String, message: String) = Unit

        /** One protocol frame the kernel pushed, already encoded as JSON text. */
        fun onFrame(text: String) = Unit

        companion object {
            val NONE: Listener = object : Listener {}
        }
    }

    /**
     * What the boot produced, in one value.
     *
     * [microtasksDrainedOnEvaluate] is the engine-behaviour probe: the kernel is
     * promise-based (its host API is callback-style and wrapped in exactly one
     * `fromCallback`), so an engine that does not run promise jobs while an
     * `evaluate` returns would need a different driving loop. The vendored
     * bridge drains them; the probe records that fact rather than assuming it.
     */
    data class BootReport(
        val ready: Boolean,
        val bootError: String?,
        val microtasksDrainedOnEvaluate: Boolean,
        val welcomeFrame: String?,
        val deviceId: String?,
        val hostId: String?,
        val durationMs: Long,
        val bundleEvalMs: Long,
        val engine: String,
        val kernelBundleBytes: Int,
        val hostName: String,
        val rootPath: String,
    )

    /** One request/answer pair, as the protocol delivered it. */
    data class KernelCall(
        val method: String,
        val requestId: String,
        /** The raw `ok`/`error` frame, or `null` when the kernel stayed silent. */
        val frame: String?,
        /** `CODE: message` for an `error` frame, or the transport failure. */
        val error: String?,
        val durationMs: Long,
    ) {
        val ok: Boolean get() = frame != null && KernelProtocol.typeOf(frame) == "ok"

        /** The `result` of an `ok` frame as JSON text, or `null`. */
        val resultJson: String? get() = frame?.let(KernelProtocol::resultJson)
    }

    private val appContext = context.applicationContext
    private val engineThread = HandlerThread("mizar-localagent-engine").apply { start() }
    private val engineHandler = Handler(engineThread.looper)
    private val nextRequestId = AtomicInteger(0)

    // Only touched on the engine thread, except where noted.
    private var handle: Long = 0L
    private var bootReport: BootReport? = null
    private val frames = ArrayList<String>()
    private val framesLock = Any()

    private val sandbox = MizarHostSandbox(
        root = rootDir,
        kvStore = kvStore,
        listener = listener,
        onFrame = ::recordFrame,
    )
    private val dispatcher = HostDispatcher(sandbox)

    /** Whether [boot] has produced a ready kernel. */
    @Volatile
    var isBooted: Boolean = false
        private set

    // MARK: - Boot

    /**
     * Create the engine, evaluate the kernel bundle and shake hands as a client.
     *
     * Idempotent: calling it twice returns the first report. Throws
     * [LocalAgentException] only when the runtime cannot start at all (missing
     * `.so` for this ABI, unreadable asset); a kernel that refuses to boot comes
     * back as a report with `ready = false` and [BootReport.bootError].
     */
    fun boot(timeoutMs: Long = 60_000): BootReport =
        onEngineThread(timeoutMs) { bootInternal() }

    private fun bootInternal(): BootReport {
        bootReport?.let { return it }

        val startedAt = SystemClock.elapsedRealtime()
        val created = try {
            QuickJsNative.create()
        } catch (error: Throwable) {
            throw LocalAgentException(
                "the QuickJS-NG library could not be loaded — is libmizarquickjs.so present for " +
                    "${Build.SUPPORTED_ABIS.firstOrNull() ?: "this ABI"}? ${error.message}",
                error,
            )
        }
        if (created == 0L) {
            throw LocalAgentException("QuickJS-NG could not create a runtime/context")
        }
        handle = created
        QuickJsNative.setDispatcher(handle, dispatcher)

        val hostSource = readAsset(HOST_ASSET)
        val kernelSource = readAsset(KERNEL_ASSET)

        val hostConfig = JSONObject()
            .put("hostName", hostName)
            .put("home", rootDir.absolutePath)
            .put("defaultWorkspaceName", defaultWorkspaceName)
            .put("platformVersion", ENGINE_VERSION)
        // The JSON object goes in as an object literal, not as a string: the JS
        // host reads `config.defaultWorkspaceName` and friends directly.
        evalJs("globalThis.__mizarHostConfig = ${hostConfig}; 0", "host-config")
        evalJs(hostSource, HOST_ASSET)

        val bundleStartedAt = SystemClock.elapsedRealtime()
        evalJs(kernelSource, KERNEL_ASSET)
        val bundleEvalMs = SystemClock.elapsedRealtime() - bundleStartedAt

        val microtasksDrained = microtaskProbe()

        evalJs("__mizarBootKernel(); 0", "boot")
        val bootError = evalJs(
            "typeof globalThis.__mizarBootError === 'string' ? globalThis.__mizarBootError : ''",
            "boot-probe",
        )?.takeIf { it.isNotEmpty() }
        val ready = bootError == null &&
            intOf(evalJs("typeof globalThis.__mizarKernel === 'object' ? 1 : 0", "boot-probe")) == 1

        var welcomeFrame: String? = null
        if (ready) {
            synchronized(framesLock) { frames.clear() }
            val hello = KernelProtocol.helloFrame(
                token = "local",
                appVersion = appVersion(),
                deviceModel = Build.MODEL,
            )
            evalJs("__mizarSend(${scriptString(hello)}); __mizarPump(); 0", "hello")
            welcomeFrame = pumpUntil(timeoutMs = 2_000) {
                synchronized(framesLock) { frames.firstOrNull { KernelProtocol.typeOf(it) == "welcome" } }
            }
            if (welcomeFrame == null) {
                Log.w(LOG_TAG, "boot: the kernel accepted the connection but sent no welcome frame")
            }
        }

        val welcome = welcomeFrame?.let { frame ->
            try {
                JSONObject(frame)
            } catch (error: Exception) {
                null
            }
        }
        val report = BootReport(
            ready = ready,
            bootError = bootError,
            microtasksDrainedOnEvaluate = microtasksDrained,
            welcomeFrame = welcomeFrame,
            deviceId = welcome?.optString("deviceId", "")?.takeIf { it.isNotEmpty() },
            hostId = welcome?.optString("hostId", "")?.takeIf { it.isNotEmpty() },
            durationMs = SystemClock.elapsedRealtime() - startedAt,
            bundleEvalMs = bundleEvalMs,
            engine = ENGINE_VERSION,
            kernelBundleBytes = kernelSource.toByteArray(Charsets.UTF_8).size,
            hostName = hostName,
            rootPath = rootDir.absolutePath,
        )
        bootReport = report
        isBooted = report.ready
        Log.i(
            LOG_TAG,
            "boot: ready=${report.ready} microtasksDrained=${report.microtasksDrainedOnEvaluate} " +
                "deviceId=${report.deviceId} bundle=${report.kernelBundleBytes}B " +
                "eval=${report.bundleEvalMs}ms total=${report.durationMs}ms root=${report.rootPath}",
        )
        return report
    }

    /**
     * Does `evaluate` run the promise jobs its script queued before returning?
     *
     * The kernel's `createKernel` resolves through a chain of awaits, so this is
     * the one engine property the whole bridge rests on. Probed, not assumed.
     */
    private fun microtaskProbe(): Boolean {
        evalJs(
            "globalThis.__microtaskProbe = 0;" +
                "Promise.resolve().then(function () { globalThis.__microtaskProbe = 1; }); 0",
            "microtask-probe",
        )
        return intOf(evalJs("globalThis.__microtaskProbe", "microtask-probe")) == 1
    }

    // MARK: - Calls

    /**
     * Send one `kernel.*` request and return the correlated answer.
     *
     * This is the whole PoC client: encode a frame, hand it to the kernel's
     * connection, and read the `ok`/`error` frame the kernel pushes back. The
     * same three steps the network client performs, minus the socket.
     */
    fun call(method: String, payloadJson: String? = null, timeoutMs: Long = 5_000): KernelCall =
        onEngineThread(timeoutMs + 5_000) { callInternal(method, payloadJson, timeoutMs) }

    private fun callInternal(method: String, payloadJson: String?, timeoutMs: Long): KernelCall {
        if (bootReport?.ready != true) {
            throw LocalAgentException("the kernel is not booted (call boot() first)")
        }
        val requestId = "android-${nextRequestId.incrementAndGet()}"
        val startedAt = SystemClock.elapsedRealtime()
        synchronized(framesLock) { frames.clear() }

        val frame = KernelProtocol.requestFrame(requestId, method, payloadJson)
        evalJs("__mizarSend(${scriptString(frame)}); __mizarPump(); 0", "call:$method")
        val answer = pumpUntil(timeoutMs) {
            synchronized(framesLock) { frames.firstOrNull { KernelProtocol.isAnswerTo(it, requestId) } }
        }
        val call = KernelCall(
            method = method,
            requestId = requestId,
            frame = answer,
            error = when {
                answer == null -> "the kernel sent no answer within ${timeoutMs}ms"
                else -> KernelProtocol.errorMessage(answer)
            },
            durationMs = SystemClock.elapsedRealtime() - startedAt,
        )
        Log.i(LOG_TAG, "call $method -> ${answer ?: "NO ANSWER"} (${call.durationMs}ms, id=$requestId)")
        return call
    }

    /** A snapshot of a `kernel.status` call, for hosts that only need the identity. */
    fun status(timeoutMs: Long = 5_000): String? = call("kernel.status", timeoutMs = timeoutMs).resultJson

    /**
     * Evaluate a snippet in the kernel's global scope — a debug hatch for the
     * PoC (and the engine self-test). Returns the last expression as text:
     * `null` for `undefined`/`null`, otherwise the engine's string form
     * (`"3"` for `1 + 2`, `"object"` for `typeof x`).
     */
    fun evaluate(script: String, timeoutMs: Long = 30_000): String? =
        onEngineThread(timeoutMs) { evalJs(script, "evaluate") }

    override fun close() {
        try {
            onEngineThread(10_000) {
                if (handle != 0L) {
                    QuickJsNative.close(handle)
                    handle = 0L
                }
                bootReport = null
                isBooted = false
                synchronized(framesLock) { frames.clear() }
            }
        } catch (error: Exception) {
            Log.w(LOG_TAG, "close: the engine thread did not answer: ${error.message}")
        } finally {
            engineThread.quitSafely()
        }
    }

    // MARK: - Plumbing

    private fun evalJs(script: String, fileName: String): String? {
        val current = handle
        if (current == 0L) throw LocalAgentException("the JS engine is not created")
        return try {
            QuickJsNative.evaluate(current, script, fileName)
        } catch (error: RuntimeException) {
            throw LocalAgentException("JavaScript evaluation failed ($fileName): ${error.message}", error)
        }
    }

    /**
     * Pump the host's timer queue until [predicate] answers or the deadline
     * passes.
     *
     * Everything the PoC host does is synchronous inside JavaScript (fs and kv
     * are blocking calls, there is no network yet), so in practice the first
     * check succeeds. Keeping the loop makes the wait explicit for the async
     * version to come, and it is also what makes a silent kernel a timeout
     * instead of a hang.
     */
    private fun pumpUntil(timeoutMs: Long, predicate: () -> String?): String? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            predicate()?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            evalJs("__mizarPump(); 0", "pump")
            Thread.sleep(2)
        }
    }

    private fun recordFrame(text: String) {
        synchronized(framesLock) { frames.add(text) }
        try {
            listener.onFrame(text)
        } catch (error: Exception) {
            Log.e(LOG_TAG, "frame listener threw: ${error.message}")
        }
    }

    private fun readAsset(name: String): String = appContext.assets.open(name).use { stream ->
        stream.bufferedReader(Charsets.UTF_8).readText()
    }

    private fun appVersion(): String? = try {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    } catch (error: Exception) {
        null
    }

    private fun <T> onEngineThread(timeoutMs: Long, block: () -> T): T {
        if (Thread.currentThread() === engineThread) return block()
        val task = FutureTask(Callable { block() })
        engineHandler.post(task)
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            task.cancel(true)
            throw LocalAgentException("the JS engine did not finish within ${timeoutMs}ms", error)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    companion object {
        /**
         * The engine under the bridge.
         *
         * QuickJS-NG 0.10.1, vendored under `src/main/cpp/quickjs` and compiled
         * into `libmizarquickjs.so` by `scripts/build-quickjs.ps1`. Kept as one
         * string because the report, the log line and any support request have
         * to agree on it.
         */
        const val ENGINE_VERSION = "quickjs-ng 0.10.1 + localagent JNI bridge (16 KB pages)"

        private const val KERNEL_ASSET = "mizar-kernel.js"
        private const val HOST_ASSET = "localagent-host.js"

        private fun defaultHostName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

        private fun intOf(value: String?): Int = value?.trim()?.toIntOrNull() ?: -1

        /** A JSON string as a JavaScript string literal. */
        private fun scriptString(json: String): String = JSONObject.quote(json)
    }
}
