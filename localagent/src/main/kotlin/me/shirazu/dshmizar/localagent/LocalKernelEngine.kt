package me.shirazu.dshmizar.localagent

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.OkHttpClient
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
 * KernelRuntime.swift`): one JavaScript context, one serial queue, host callbacks
 * delivered onto that queue, and `boot()` + `openConnection()` above it. The
 * engine is QuickJS-NG behind a small JNI bridge; the protocol implementation is
 * the kernel bundle itself.
 *
 * Two surfaces:
 *
 *  - **The transport surface** — [openConnection] (via [LocalKernelLink]) hands a
 *    caller the in-process equivalent of a socket: JSON frames in both directions.
 *    [LocalKernelTransport] wraps it into `mizarkit`'s `WebSocketTransport`, so the
 *    whole connection layer is reused unchanged.
 *  - **The PoC surface** — [boot]/[call]/[evaluate] drive one default connection
 *    directly, which is what the instrumentation self-test uses.
 *
 * Threading: every touch of the QuickJS context happens on [engineThread]. The class
 * is safe to call from any thread; calls block the caller and execute on the engine
 * thread. Frames for one transport connection arrive on that connection's channel;
 * frames for the default connection also go to [Listener.onFrame].
 *
 * Host work that finishes off the JS thread (HTTP streaming) goes through
 * [HostEventQueue]; the engine thread drains it into JavaScript on a short tick and
 * whenever a chunk arrives, because QuickJS has no event loop of its own.
 *
 * The [rootDir] is the kernel's sandbox: everything it persists lives under here,
 * and the kernel only ever names paths relative to it.
 */
class LocalKernelEngine(
    context: Context,
    val rootDir: File = File(context.applicationContext.filesDir, "LocalAgent"),
    private val hostName: String = defaultHostName(),
    private val defaultWorkspaceName: String = "默认工作区",
    private val kvStore: KernelKeyValueStore = SharedPreferencesKernelKeyValueStore(context),
    private val listener: Listener = Listener.NONE,
    httpClient: OkHttpClient? = null,
) : AutoCloseable, LocalKernelLink {

    /** The sinks a host (and a test) wants besides logcat. */
    interface Listener {
        /** One structured kernel log line — the host API's `log(level, message)`. */
        fun onLog(level: String, message: String) = Unit

        /** One protocol frame the default connection pushed, already encoded as JSON text. */
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
     * `evaluate` returns would need a different driving loop. The vendored bridge
     * drains them; the probe records that fact rather than assuming it.
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
    private val nextConnectionId = AtomicInteger(0)

    // Only touched on the engine thread, except where noted.
    private var handle: Long = 0L
    private var bootReport: BootReport? = null
    private val frames = ArrayList<String>()
    private val framesLock = Any()
    private val connections = HashMap<String, LocalLinkConnectionImpl>()

    private val hostEvents = HostEventQueue()
    private val hostHttp = HostHttpTransport(
        client = httpClient ?: defaultHttpClient(),
        events = hostEvents,
        onWork = { engineHandler.post { pumpHostWork() } },
    )
    private val sandbox = MizarHostSandbox(
        root = rootDir,
        kvStore = kvStore,
        listener = listener,
    )
    private val dispatcher = HostDispatcher(::routeHostCall)

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
            // The default connection is the PoC's own client: boot() shakes hands on
            // it so the report can prove the protocol works before anything else runs.
            evalJs("__mizarOpenConnection(${scriptString(DEFAULT_CONNECTION)})", "open-default")
            synchronized(framesLock) { frames.clear() }
            val hello = KernelProtocol.helloFrame(
                token = "local",
                appVersion = appVersion(),
                deviceModel = Build.MODEL,
            )
            evalJs(
                "__mizarSendOn(${scriptString(DEFAULT_CONNECTION)}, ${scriptString(hello)}); " +
                    "__mizarPump(); 0",
                "hello",
            )
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
        if (report.ready) startHostPump()
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

    // MARK: - Transport surface (LocalKernelLink)

    /**
     * Open one protocol connection for a transport.
     *
     * Each call returns an independent connection with its own frame stream, which
     * is what makes `MizarConnection`'s reconnect story work unchanged: a reconnect
     * builds a new transport, and a new transport asks for a new connection.
     */
    override fun openConnection(): LocalLinkConnection =
        onEngineThread(10_000) { openConnectionInternal() }

    private fun openConnectionInternal(): LocalLinkConnection {
        if (bootReport?.ready != true) {
            throw LocalAgentException("the local kernel is not booted")
        }
        val id = "conn-${nextConnectionId.incrementAndGet()}"
        val deviceId = try {
            evalJs("__mizarOpenConnection(${scriptString(id)})", "open-connection")
        } catch (error: Exception) {
            throw LocalAgentException("the local kernel refused the connection: ${error.message}", error)
        }
        val connection = LocalLinkConnectionImpl(
            id = id,
            deviceId = deviceId?.takeIf { it.isNotEmpty() && it != "undefined" },
        )
        connections[id] = connection
        Log.i(LOG_TAG, "connection $id opened (deviceId=${connection.deviceId})")
        return connection
    }

    /** One connection as the transport sees it; every method hops to the engine thread. */
    private inner class LocalLinkConnectionImpl(
        private val id: String,
        override val deviceId: String?,
    ) : LocalLinkConnection {
        private val channel = Channel<String>(Channel.UNLIMITED)

        override val frames: ReceiveChannel<String> get() = channel

        fun deliver(text: String) {
            channel.trySend(text)
        }

        override fun send(text: String) = sendFrame(id, text)

        override fun close() = closeConnection(id)

        fun closeFrames() {
            channel.close()
        }
    }

    private fun sendFrame(connectionId: String, text: String) {
        onEngineThread(10_000) {
            if (!connections.containsKey(connectionId)) {
                throw LocalAgentException("SOCKET_CLOSED: connection $connectionId is closed")
            }
            val sent = evalJs(
                "__mizarSendOn(${scriptString(connectionId)}, ${scriptString(text)})",
                "send",
            )
            if (intOf(sent) != 1) {
                throw LocalAgentException("SOCKET_CLOSED: the kernel connection $connectionId is gone")
            }
        }
    }

    private fun closeConnection(connectionId: String) {
        onEngineThread(10_000) {
            val connection = connections.remove(connectionId) ?: return@onEngineThread
            try {
                evalJs("__mizarCloseConnection(${scriptString(connectionId)})", "close-connection")
            } catch (error: Exception) {
                Log.w(LOG_TAG, "close-connection $connectionId: ${error.message}")
            }
            connection.closeFrames()
        }
    }

    // MARK: - Host bridge

    /**
     * route one `__mizarNativeCall(op, a, b)`.
     *
     * Runs on the engine thread, called synchronously out of JavaScript. The engine
     * owns the operations that need state it keeps — protocol frames, HTTP streaming,
     * the host event queue — and hands the rest to the sandbox.
     */
    private fun routeHostCall(op: String, first: String, second: String): String = when (op) {
        "frame" -> {
            onKernelFrame(first, second)
            Envelopes.ok()
        }

        "http.start" -> hostHttp.start(first)
        "http.abort" -> {
            hostHttp.abort(first)
            Envelopes.ok()
        }

        "events.poll" -> hostEvents.poll()
        else -> sandbox.call(op, first, second)
    }

    private fun onKernelFrame(connectionId: String, text: String) {
        val connection = connections[connectionId]
        if (connection != null) {
            connection.deliver(text)
            return
        }
        if (connectionId == DEFAULT_CONNECTION) {
            synchronized(framesLock) { frames.add(text) }
            try {
                listener.onFrame(text)
            } catch (error: Exception) {
                Log.e(LOG_TAG, "frame listener threw: ${error.message}")
            }
            return
        }
        // A frame for a connection the client already closed: the kernel can push
        // into a close that is in flight. Dropping it is correct — the client asked
        // for no more frames by closing.
        Log.d(LOG_TAG, "frame for a closed connection $connectionId dropped (${text.take(60)})")
    }

    /**
     * Give JavaScript whatever the host finished off-thread (HTTP chunks).
     *
     * Called from [HostHttpTransport]'s callbacks (posted to the engine thread) and
     * from the periodic tick, which also runs due timers. QuickJS has no event loop,
     * so those are the only two moments the JS host can make progress without a
     * client request.
     */
    private fun pumpHostWork() {
        if (handle == 0L) return
        try {
            evalJs("__mizarPump(); 0", "host-pump")
        } catch (error: Exception) {
            Log.w(LOG_TAG, "host pump failed: ${error.message}")
        }
    }

    private fun startHostPump() {
        engineHandler.removeCallbacks(hostTick)
        engineHandler.postDelayed(hostTick, HOST_TICK_MILLIS)
    }

    /**
     * The idle heartbeat: runs due timers and delivers host events.
     *
     * 250 ms is a compromise: the kernel's timers are tool timeouts, not animation,
     * and an empty `__mizarPump()` is a fraction of a millisecond. Streaming chunks
     * do not wait for this — their callbacks post a drain directly.
     */
    private val hostTick = object : Runnable {
        override fun run() {
            if (handle == 0L) return
            pumpHostWork()
            if (handle != 0L) engineHandler.postDelayed(this, HOST_TICK_MILLIS)
        }
    }

    // MARK: - PoC surface

    /**
     * Send one `kernel.*` request on the default connection and return the answer.
     *
     * This is the instrumentation self-test's client: encode a frame, hand it to the
     * kernel, read the correlated `ok`/`error` frame back. Real callers use
     * [LocalKernelTransport], which speaks the same frames through `MizarConnection`.
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
        evalJs(
            "__mizarSendOn(${scriptString(DEFAULT_CONNECTION)}, ${scriptString(frame)}); " +
                "__mizarPump(); 0",
            "call:$method",
        )
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
     * Evaluate a snippet in the kernel's global scope — a debug hatch for the PoC
     * (and the engine self-test). Returns the last expression as text: `null` for
     * `undefined`/`null`, otherwise the engine's string form.
     */
    fun evaluate(script: String, timeoutMs: Long = 30_000): String? =
        onEngineThread(timeoutMs) { evalJs(script, "evaluate") }

    override fun close() {
        try {
            onEngineThread(10_000) {
                engineHandler.removeCallbacks(hostTick)
                hostHttp.cancelAll()
                for (connectionId in connections.keys.toList()) {
                    try {
                        evalJs("__mizarCloseConnection(${scriptString(connectionId)})", "close-connection")
                    } catch (error: Exception) {
                        Log.w(LOG_TAG, "close ${connectionId}: ${error.message}")
                    }
                }
                for (connection in connections.values) connection.closeFrames()
                connections.clear()
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
     * Pump the host's timer/event queue until [predicate] answers or the deadline
     * passes, so a silent kernel is a timeout rather than a hang.
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

        /** The connection [boot]/[call]/[evaluate] use; transports open their own. */
        private const val DEFAULT_CONNECTION = "default"

        /** How often the engine thread runs due timers and delivers host events. */
        private const val HOST_TICK_MILLIS = 250L

        private const val KERNEL_ASSET = "mizar-kernel.js"
        private const val HOST_ASSET = "localagent-host.js"

        private fun defaultHostName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

        private fun intOf(value: String?): Int = value?.trim()?.toIntOrNull() ?: -1

        /** A JSON string as a JavaScript string literal. */
        private fun scriptString(json: String): String = JSONObject.quote(json)

        /**
         * The HTTP client behind `host.http`.
         *
         * No read timeout: a model answer is a stream that stays quiet between tokens
         * and can run for minutes. The kernel owns interruption (its `abort`), and
         * `retryOnConnectionFailure` is off so a silent retry cannot duplicate a
         * streamed conversation.
         */
        internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
