package me.shirazu.dshmizar.localagent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.shirazu.mizar.kit.MizarConnection
import org.json.JSONObject
import java.io.File

/**
 * The local agent as a host the app can dial.
 *
 * One instance owns one booted kernel (one JS thread, one QuickJS context). It is
 * the app's only handle on the local side:
 *
 *  - [boot] creates the kernel (idempotent, ≈150 ms, call it off the main thread);
 *  - [openTransport] hands `MizarConnection` an in-process transport — this is the
 *    whole integration seam, because the connection layer above it is unchanged;
 *  - [configuration] is the connection configuration a local dial uses: a valid but
 *    never-dialled authority, a token the kernel accepts, no relay headers;
 *  - [status] is what the settings page and the agent picker draw;
 *  - [close] stops the kernel and everything it started.
 *
 * Lifecycle is deliberately foreground-only (the same product promise iOS makes):
 * the caller closes this when the local mode ends; the kernel's sessions are on
 * disk and a restart closes a turn that was interrupted.
 *
 * A closed host is reusable: [close] tears the engine down and the next [boot]
 * creates a fresh one, which is what a "restart the local agent" action needs.
 */
class LocalAgentHost(
    context: Context,
    private val appVersion: String? = null,
    private val deviceModel: String? = android.os.Build.MODEL,
    private val engineFactory: (Context) -> LocalKernelEngine = { LocalKernelEngine(it) },
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val lock = Any()
    @Volatile
    private var engine: LocalKernelEngine? = null
    @Volatile
    private var report: LocalKernelEngine.BootReport? = null

    /** Whether a live kernel is ready right now (no I/O, safe from any thread). */
    val isBooted: Boolean get() = report?.ready == true

    /** The kernel identity's host id, once booted. */
    val hostId: String? get() = report?.hostId

    /** The sandbox root, once booted. */
    val rootPath: String? get() = report?.rootPath

    /**
     * Boot the kernel if it is not already booted.
     *
     * Blocking; the first call costs the bundle evaluation (measured at 8–12 ms) plus
     * the kernel's own boot (≈150 ms total). Callers use [ensureBooted] on IO.
     */
    fun boot(): LocalKernelEngine.BootReport {
        synchronized(lock) {
            report?.takeIf { it.ready }?.let { return it }
            val current = engine ?: engineFactory(appContext).also { engine = it }
            val booted = current.boot()
            report = booted
            if (!booted.ready) {
                booted.bootError?.let { android.util.Log.e(LOG_TAG, "local kernel boot failed: $it") }
            }
            return booted
        }
    }

    /** [boot] on the IO dispatcher. */
    suspend fun ensureBooted(): LocalKernelEngine.BootReport = withContext(Dispatchers.IO) { boot() }

    /**
     * A fresh transport for one `MizarConnection`.
     *
     * One transport per dial. The transport opens its own kernel connection when
     * `connect()` runs, and boots the kernel first if nobody has yet — a local dial
     * must not require a separate warm-up step the UI can forget.
     */
    fun openTransport(): LocalKernelTransport = LocalKernelTransport(
        LocalKernelLink {
            boot()
            val current = engine ?: throw LocalAgentException("the local kernel is closed")
            current.openConnection()
        },
    )

    /**
     * The configuration a local dial uses.
     *
     * `authority` is a syntactically valid, never-dialled loopback name: the
     * connection layer builds a URL from it (`PairingUri.webSocketUrl`) and the local
     * transport ignores it. The token is accepted by a local kernel like any other —
     * there is nothing to pair with.
     *
     * The endpoint is `Direct`, so the adoption plan treats the link as "local
     * network": LAN scans, relay tiers and push registration are all skipped, and
     * paid gates that apply to relayed traffic do not apply to the phone's own model.
     */
    fun configuration(): MizarConnection.Configuration = MizarConnection.Configuration(
        authority = AUTHORITY,
        token = LOCAL_TOKEN,
        secure = false,
        appVersion = appVersion,
        deviceModel = deviceModel,
    )

    /**
     * What the settings page and the agent picker need.
     *
     * A status read boots the kernel, so it costs one boot on a cold process and is
     * cheap afterwards. Everything is best effort: a kernel that cannot answer
     * yields `available = false` with the reason rather than throwing at the UI.
     */
    suspend fun status(): LocalAgentStatus = withContext(Dispatchers.IO) {
        val booted = try {
            boot()
        } catch (error: Throwable) {
            return@withContext LocalAgentStatus(
                available = false,
                bootError = error.message ?: error.javaClass.simpleName,
            )
        }
        if (!booted.ready) {
            return@withContext LocalAgentStatus(
                available = false,
                bootError = booted.bootError ?: "the local kernel did not boot",
            )
        }
        val current = engine
        val statusJson = current?.let { runCatching { it.call("kernel.status") }.getOrNull() }
            ?.resultJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val configJson = current?.let { runCatching { it.call("kernel.config.get") }.getOrNull() }
            ?.resultJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONObject("config")
        val keysJson = current?.let { runCatching { it.call("kernel.apiKey.status") }.getOrNull() }
            ?.resultJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val providers = configJson?.optJSONArray("providers")
        val hasKey = keysJson?.optJSONArray("providers")?.let { rows ->
            (0 until rows.length()).any { index -> rows.optJSONObject(index)?.optBoolean("hasKey") == true }
        } ?: false
        val defaultModel = configJson?.optJSONObject("defaultModel")
        LocalAgentStatus(
            available = true,
            configured = hasKey,
            storedBytes = storedBytes(booted),
            bootError = null,
            kernelVersion = statusJson?.optString("kernelVersion")?.takeIf { it.isNotEmpty() },
            protocolVersion = statusJson?.optInt("protocolVersion", 0)?.takeIf { it > 0 },
            sessions = statusJson?.optInt("sessions", -1)?.takeIf { it >= 0 },
            provider = defaultModel?.optString("provider")?.takeIf { it.isNotEmpty() },
            model = defaultModel?.optString("model")?.takeIf { it.isNotEmpty() },
            providerCount = providers?.length() ?: 0,
            hostId = booted.hostId,
            hostName = booted.hostName,
            rootPath = booted.rootPath,
        )
    }

    /**
     * Delete the kernel's sandbox: its sessions, config and workspaces.
     *
     * The kernel is closed first so nothing holds a file handle. Model API keys live
     * in `KernelKeyValueStore`, not here, and survive — same split the iOS settings
     * page documents ("清除本地会话" keeps the workspace and model config).
     */
    fun clearData(): Boolean {
        val path = report?.rootPath ?: engine?.rootDir?.absolutePath
        close()
        if (path == null) return false
        return runCatching { File(path).deleteRecursively() }.getOrDefault(false)
    }

    /**
     * Stop the kernel and release the JS thread. Idempotent.
     *
     * The next [boot] builds a fresh engine, so this is also the "restart" path a
     * crashed or wedged kernel needs.
     */
    override fun close() {
        synchronized(lock) {
            val current = engine
            engine = null
            report = null
            runCatching { current?.close() }
        }
    }

    private fun storedBytes(booted: LocalKernelEngine.BootReport): Long? = runCatching {
        val root = File(booted.rootPath)
        if (!root.isDirectory) return@runCatching 0L
        var bytes = 0L
        root.walkTopDown().forEach { entry ->
            if (entry.isFile) bytes += entry.length()
        }
        bytes
    }.getOrNull()

    companion object {
        /**
         * The authority a local dial reports.
         *
         * `local` is a valid URI host (`ws://local:19390/…` parses) but it never
         * resolves and is never dialled; it only gives the connection layer the
         * `Direct` endpoint it needs.
         */
        const val AUTHORITY = "local:19390"

        /** A local kernel accepts any hello token; this one says where it came from. */
        const val LOCAL_TOKEN = "local"
    }
}

/**
 * What the app needs to draw the local agent.
 *
 * A plain value so the settings page and the agent picker can be driven from a
 * `StateFlow` without knowing about engines or JNI.
 *
 * @property available the build carries a working engine and the kernel booted.
 * @property configured at least one model provider has an API key stored.
 * @property storedBytes what the local sandbox takes on disk, when it can be read.
 * @property provider / [model] the default model selection, for a one-line summary.
 */
data class LocalAgentStatus(
    val available: Boolean,
    val configured: Boolean = false,
    val storedBytes: Long? = null,
    val bootError: String? = null,
    val kernelVersion: String? = null,
    val protocolVersion: Int? = null,
    val sessions: Int? = null,
    val provider: String? = null,
    val model: String? = null,
    val providerCount: Int = 0,
    val hostId: String? = null,
    val hostName: String? = null,
    val rootPath: String? = null,
)
