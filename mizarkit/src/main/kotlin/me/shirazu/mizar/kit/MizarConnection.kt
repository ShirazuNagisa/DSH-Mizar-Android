package me.shirazu.mizar.kit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.util.UUID

/** How many state frames one connection keeps for a slow collector. */
private const val STATE_FRAME_BUFFER = 256

/**
 * One authenticated connection to a host.
 *
 * The handshake, the correlated calls and the pushed events, with the failure
 * behaviour an operator actually meets: a dropped socket fails everything in flight
 * with a *retryable* error rather than hanging, and a version mismatch is the one
 * decode failure that closes the connection instead of being skipped.
 */
class MizarConnection(
    private val configuration: Configuration,
    /**
     * Where the round trips are recorded, when the caller wants them.
     *
     * Null in the protocol's own tests: a log nobody reads is a cost. The app
     * passes one so the diagnostics archive can answer "did this frame leave, and
     * what came back" without the operator reproducing it.
     *
     * Declared before the transport so a trailing lambda still builds the
     * transport, which is how every existing call site reads.
     */
    private val traffic: ProtocolLog? = null,
    private val makeTransport: () -> WebSocketTransport = { OkHttpWebSocketTransport() },
) {

    /**
     * Where to dial and who to be.
     *
     * @property authority `host:port` for a direct connection.
     * @property baseUrl a relay base URL (`https://relay/r/ROOM`) instead.
     * @property token the device token from pairing.
     * @property secure whether a direct connection speaks `wss`.
     */
    data class Configuration(
        val authority: String? = null,
        val baseUrl: String? = null,
        val token: String,
        val secure: Boolean = false,
        val appVersion: String? = null,
        val deviceModel: String? = null,
        /**
         * The agent this socket is for, when the phone chose one.
         *
         * Only a token minted by the relay carries a scope of its own, and that one
         * wins on the host; this field is how a phone that paired by scanning a code
         * chooses. `null` keeps the old unlocked-to-everything connection.
         */
        val engine: String? = null,
        val requestTimeoutMillis: Long = 60_000,
        val handshakeTimeoutMillis: Long = 20_000,
        /**
         * Identity headers for the relay's upgrade request.
         *
         * Empty for a direct socket: there is no relay to meter it. The relay
         * reads these once, at the handshake, so a change means a new connection.
         */
        val headers: Map<String, String> = emptyMap(),
    ) {
        /** Direct first: one QR code covers "same Wi-Fi" and "anywhere". */
        val endpoint: Endpoint?
            get() = when {
                !authority.isNullOrBlank() -> Endpoint.Direct(authority)
                !baseUrl.isNullOrBlank() && PairingUri.isUsableBaseUrl(baseUrl) -> Endpoint.Relay(baseUrl)
                else -> null
            }
    }

    /** A direct authority or a relay base. */
    sealed interface Endpoint {
        data class Direct(val authority: String) : Endpoint
        data class Relay(val baseUrl: String) : Endpoint
    }

    /** Where the connection is. `Failed` is not terminal: reconnecting leaves it. */
    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data class Ready(val welcome: ServerFrame.Welcome) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var transport: WebSocketTransport? = null
    private var readJob: Job? = null
    // Upload calls run on IO alongside the normal UI/poll calls and the socket reader.
    private val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()

    /**
     * What each in-flight request was, so its answer can be recorded with a name.
     *
     * The protocol correlates by id and nothing else, so without this the reply to
     * `session.list` and the reply to `session.prompt` are the same line in a log.
     */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, InFlight>()

    private data class InFlight(val method: String, val startedAtNanos: Long) {
        /** Whole milliseconds since the request went out. */
        fun elapsedMillis(): Long = (System.nanoTime() - startedAtNanos) / 1_000_000L
    }
    private var handshake: CompletableDeferred<ServerFrame.Welcome>? = null
    private val subscribers = LinkedHashMap<String, Channel<ServerFrame>>()

    /**
     * The host's state channels, one per session it talks about.
     *
     * A binary state frame names its session in the transport envelope, not in the
     * frame itself, so the association is made here. Guarded by [stateLock] because
     * the reader coroutine writes while the UI may read.
     */
    private val stateChannels = HashMap<String, StateChannel>()
    private val stateLock = Any()

    /**
     * Every state frame this socket delivered, oldest first while a collector keeps up.
     *
     * A `SharedFlow` rather than a queue because a screen subscribes once, per
     * connection: the subscription's lifetime is the socket's. No replay: a frame
     * that arrived before the screen opened is recovered with `session.stream`, and
     * replaying it here would apply the same interval twice.
     *
     * The buffer is bounded and drops the oldest under pressure: a screen that stops
     * reading must not grow this without bound. Dropping is safe for the same reason
     * no replay is kept -- the journal is authoritative for a gap.
     */
    private val _stateFrames = MutableSharedFlow<DeliveredStateFrame>(
        replay = 0,
        extraBufferCapacity = STATE_FRAME_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Each binary state frame, with the session it belongs to.
     *
     * Envelope: `u8` session-id length, the id, then the 13-byte [StateFrame]. The
     * host sends the same stream to every phone connection, so the session id is what
     * lets a screen pick out the conversation it is drawing.
     */
    val stateFrames: Flow<DeliveredStateFrame> = _stateFrames.asSharedFlow()

    private val _state = MutableStateFlow<State>(State.Idle)

    /** Observable, for a UI that wants to draw the connection's state. */
    val state: StateFlow<State> = _state

    /** The host's identity, once the handshake has completed. */
    val welcome: ServerFrame.Welcome? get() = (_state.value as? State.Ready)?.welcome

    /**
     * Pushed frames, for one collector.
     *
     * A fresh flow per call, which **completes when the socket closes** — the
     * subscribers are finished on every close path, so a caller looping over this
     * sees the end of the connection rather than a silent stall.
     */
    fun events(): Flow<ServerFrame> = flow {
        val id = UUID.randomUUID().toString()
        val channel = Channel<ServerFrame>(Channel.UNLIMITED)
        subscribers[id] = channel
        try {
            for (frame in channel) emit(frame)
        } finally {
            subscribers.remove(id)
            channel.close()
        }
    }

    /**
     * Complete the handshake.
     *
     * Idempotent when already connected: a caller that reconnects on a timer should
     * not have to ask whether it already is.
     */
    suspend fun connect(): ServerFrame.Welcome {
        (_state.value as? State.Ready)?.let { return it.welcome }

        val endpoint = configuration.endpoint
            ?: throw failAndReturn("BAD_AUTHORITY", "there is no address to dial")

        val url = when (endpoint) {
            is Endpoint.Direct -> PairingUri.webSocketUrl(endpoint.authority, configuration.secure)
            is Endpoint.Relay -> PairingUri.relayWebSocketUrl(endpoint.baseUrl)
        } ?: throw failAndReturn("BAD_AUTHORITY", "cannot dial ${endpoint.describe()}")

        _state.value = State.Connecting
        // A new socket is a new epoch of the state channel. The host's interval
        // counter is per session and can restart with a desktop update or a fresh
        // state machine; keeping the old channel would make every new frame look
        // stale and the phone would freeze until the app was killed.
        clearStateChannels()
        val socket = makeTransport()
        transport = socket

        try {
            socket.connect(url, configuration.headers)
        } catch (error: Throwable) {
            transport = null
            _state.value = State.Failed(describe(error))
            throw error
        }

        // Reading starts *before* the hello is sent: the welcome can beat the send,
        // and a socket with no reader loses it.
        startReading(socket)

        val awaiting = CompletableDeferred<ServerFrame.Welcome>()
        handshake = awaiting

        try {
            socket.send(
                Frames.encodeHello(
                    token = configuration.token,
                    appVersion = configuration.appVersion,
                    deviceModel = configuration.deviceModel,
                    engine = configuration.engine,
                ),
            )
        } catch (error: Throwable) {
            closeSocket("the handshake could not be sent")
            throw error
        }

        return try {
            withTimeout(configuration.handshakeTimeoutMillis) { awaiting.await() }
        } catch (_: TimeoutCancellationException) {
            val message = "the host did not answer the handshake in time"
            closeSocket(message)
            throw MizarError("REQUEST_TIMEOUT", message, retryable = true)
        }
    }

    /** Connect, retrying on the schedule until it works or the caller is cancelled. */
    suspend fun connectWithRetry(backoff: Backoff = Backoff.standard): ServerFrame.Welcome {
        while (true) {
            try {
                return connect()
            } catch (cancelled: CancellationException) {
                // Cancellation is the caller's decision, not a connection failure to
                // retry: swallowing it here would make a cancelled screen reconnect
                // forever.
                throw cancelled
            } catch (_: Throwable) {
                delay((backoff.nextDelay() * 1000).toLong())
            }
        }
    }

    /**
     * One method call.
     *
     * @param type the method name.
     * @param payload the arguments, omitted entirely when `null`.
     * @param timeoutMillis how long to wait for an answer, or a non-positive value for
     *   the configured request timeout. A liveness probe wants a short one: the whole
     *   point is to notice silence quickly, and the default minute is long enough that
     *   a backgrounded phone looks connected while it is not.
     * @returns whatever the host answered, or throws [MizarError].
     */
    suspend fun request(
        type: String,
        payload: JsonElement? = null,
        timeoutMillis: Long = -1,
    ): JsonElement? {
        val socket = transport
            ?: throw MizarError("NO_CONNECTION", "there is no connection to the host", retryable = true)

        val id = UUID.randomUUID().toString().uppercase()
        val awaiting = CompletableDeferred<JsonElement?>()
        pending[id] = awaiting
        inFlight[id] = InFlight(type, System.nanoTime())

        try {
            val frame = Frames.encodeRequest(type, id, payload)
            // Recorded before the frame reaches the transport, not after. An answer
            // cannot precede the request it answers, but the log can: a transport
            // that answers inline -- a test double, an in-process pipe -- lets the
            // reader thread route the reply while the caller has not yet written its
            // own line, and the diagnostics then read backwards. A request the socket
            // refuses is still recorded as unsent, one line below.
            traffic?.sent(type, frame.toByteArray(Charsets.UTF_8).size)
            socket.send(frame)
        } catch (cancelled: CancellationException) {
            // A caller that was cancelled is not a socket that broke. Telling it
            // `SOCKET_CLOSED` turned "the operator left the screen" into a network
            // fault, and every `runCatching { ... }` above treated it as one.
            pending.remove(id)
            inFlight.remove(id)
            traffic?.failed(type, "cancelled")
            throw cancelled
        } catch (_: Throwable) {
            pending.remove(id)
            inFlight.remove(id)
            traffic?.failed(type, "unsent")
            throw MizarError("SOCKET_CLOSED", "the request could not be sent", retryable = true)
        }

        val budget = if (timeoutMillis > 0) timeoutMillis else configuration.requestTimeoutMillis
        return try {
            withTimeout(budget) { awaiting.await() }
        } catch (_: TimeoutCancellationException) {
            inFlight.remove(id)?.let { traffic?.failed(type, "timeout", it.elapsedMillis()) }
            throw MizarError("REQUEST_TIMEOUT", "$type did not answer in time", retryable = true)
        } finally {
            // Navigation/remove can cancel an upload while awaiting its acknowledgement.
            // The upload checkpoint stays unchanged; the abandoned RPC waiter must not leak.
            pending.remove(id)
        }
    }

    /**
     * One method call, decoded.
     *
     * A `null` result is an error rather than a default: a caller that asked for
     * stats and got nothing must not be handed a zeroed frame.
     */
    suspend inline fun <reified T> call(
        method: String,
        payload: JsonElement? = null,
        timeoutMillis: Long = -1,
    ): T {
        val result = request(method, payload, timeoutMillis)
        if (result == null || result is JsonNull) {
            throw MizarError("EMPTY_RESULT", "$method answered without a result")
        }
        return WIRE_JSON.decodeFromJsonElement(serializer<T>(), result)
    }

    /** Close, failing everything in flight. */
    suspend fun disconnect() {
        closeSocket("disconnected")
    }

    // ── Additive methods ────────────────────────────────────────────────────
    //
    // Every method below is a later addition to the host's method table. A host that
    // predates one answers `NOT_IMPLEMENTED`; `null` is that answer, and the caller
    // keeps the behaviour it had before the method existed. Any *other* failure is
    // thrown: "the computer is too old" and "the request timed out" are different
    // answers, and flattening them hides a real fault.

    /**
     * `session.sync` — several conversation pages in one frame.
     *
     * @returns [SessionSyncOutcome.Answered] with the batch, or
     *   [SessionSyncOutcome.Unsupported] when the host predates the method (read one
     *   `session.messages` per session instead).
     */
    suspend fun syncSessions(
        sessions: List<SessionSyncRequest>,
        /** A byte ceiling for the whole answer; the host clamps it to 16 KiB..800 KiB. */
        maxBytes: Int? = null,
        /** How many items the answer may carry; the host clamps it to 1..64. */
        maxItems: Int? = null,
    ): SessionSyncOutcome {
        if (sessions.isEmpty()) return SessionSyncOutcome.Answered(SessionSyncResult())
        val payload = buildJsonObject {
            put("items", JsonArray(sessions.map { it.toPayload() }))
            maxBytes?.let { put("maxBytes", jsonInt(it)) }
            maxItems?.let { put("maxItems", jsonInt(it)) }
        }
        return try {
            SessionSyncOutcome.Answered(call("session.sync", payload))
        } catch (error: MizarError) {
            if (error.isNotImplemented) SessionSyncOutcome.Unsupported else throw error
        }
    }

    /**
     * `session.stream` — the host's journal for the turn that is (or was) running.
     *
     * @returns the replay, or `null` when the host predates the method. A host new
     *   enough to answer but with no journal answers an empty entry list, which is a
     *   normal answer rather than a failure.
     */
    suspend fun streamSession(sessionId: String): SessionStreamResult? =
        optionalCall("session.stream", buildJsonObject { put("sessionId", sessionId) })

    /**
     * `session.compact` — run the engine's own context-compaction mechanism.
     *
     * @returns the host's acknowledgement, or `null` when the host predates the method.
     */
    suspend fun compactSession(sessionId: String): CompactResult? =
        optionalCall("session.compact", buildJsonObject { put("sessionId", sessionId) })

    /**
     * `session.fork` — branch the conversation at one completed turn boundary.
     *
     * @param atSeq the selected turn's true inclusive `turn/end.seq`, never a summary
     *   seq or a turn number: the host validates the boundary exists and is complete.
     * @returns the new session's id, or `null` when the host predates the method.
     */
    suspend fun forkSession(sessionId: String, atSeq: Int): ForkResult? =
        optionalCall(
            "session.fork",
            buildJsonObject {
                put("sessionId", sessionId)
                put("atSeq", jsonInt(atSeq))
            },
        )

    /** `usage.overview` — the home page's per-day work history, or `null` on an older host. */
    suspend fun usageOverview(): UsageOverview? = optionalCall("usage.overview", null)

    /**
     * `file.stat` — what a path is, in the session's working directory.
     *
     * @returns the file's facts, or `null` when the host predates the method (no
     *   in-conversation preview can be drawn then).
     */
    suspend fun fileStat(sessionId: String, path: String): FileStat? =
        optionalCall(
            "file.stat",
            buildJsonObject {
                put("sessionId", sessionId)
                put("path", path)
            },
        )

    /**
     * `file.read` — one slice of a delivered file.
     *
     * Slicing is not a preference: the listener refuses a frame over 512 KiB and
     * base64 costs a third on top, so [FileSlice.MAX_SLICE_BYTES] of bytes is the
     * largest piece that fits with room for the envelope. A caller loops until
     * [FileSlice.eof].
     */
    suspend fun readFileSlice(
        sessionId: String,
        path: String,
        offset: Long = 0,
        length: Int = FileSlice.MAX_SLICE_BYTES,
    ): FileSlice = call(
        "file.read",
        buildJsonObject {
            put("sessionId", sessionId)
            put("path", path)
            put("offset", jsonLong(offset))
            put("length", jsonInt(length))
        },
    )

    /**
     * One additive method, with `NOT_IMPLEMENTED` mapped to `null`.
     *
     * The single place the "older host" rule lives, so every new method answers the
     * same way instead of each re-deciding whether it is allowed to throw.
     */
    private suspend inline fun <reified T> optionalCall(
        method: String,
        payload: JsonElement?,
    ): T? = try {
        call<T>(method, payload)
    } catch (error: MizarError) {
        if (error.isNotImplemented) null else throw error
    }

    private fun startReading(socket: WebSocketTransport) {
        readJob = scope.launch {
            try {
                while (true) {
                    when (val message = socket.receiveMessage()) {
                        null -> break
                        is Incoming.Text -> {
                            when (val decoded = Frames.decodeServer(message.text)) {
                                is FrameDecode.Decoded -> route(decoded.frame)
                                is FrameDecode.VersionMismatch -> {
                                    val seen = decoded.seen
                                    val message = "host speaks protocol version $seen, this build speaks $MIZAR_PROTOCOL_VERSION"
                                    val error = MizarError(MizarErrorCode.PROTOCOL_VERSION_MISMATCH.wire, message)
                                    // The specific error goes to the waiters *before* the socket
                                    // is closed: closing fails everything with the generic
                                    // CONNECTION_CLOSED, which buries the one message that says
                                    // what to do about it.
                                    failAll(error)
                                    closeSocket(message)
                                    return@launch
                                }

                                FrameDecode.Malformed -> continue
                            }
                        }

                        is Incoming.Binary -> handleStateMessage(message.bytes)
                    }
                }
                closeSocket("the host closed the connection")
            } catch (cancelled: CancellationException) {
                // Deliberate close: `closeSocket` cancels this job and is already
                // tearing the socket down. Closing again from here would race a
                // reconnect that has already started on a new socket -- the stale
                // read loop used to complete the new handshake with
                // CONNECTION_CLOSED.
                throw cancelled
            } catch (error: Throwable) {
                closeSocket(describe(error))
            }
        }
    }

    /**
     * Apply one binary state message.
     *
     * A binary message that is not a state frame is **ignored, not fatal**: the
     * channel is additive, and a newer host may grow a second binary dialect. The
     * only evidence left is the diagnostics log, which is exactly where "the app
     * ignored something" belongs.
     */
    private fun handleStateMessage(bytes: ByteArray) {
        val delivered = StateFrameEnvelope.decode(bytes).getOrElse {
            traffic?.ignored("state-frame", bytes.size)
            return
        }
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            val channel = stateChannels.getOrPut(delivered.sessionId) { StateChannel() }
            channel.apply(delivered.frame, now)
        }
        _stateFrames.tryEmit(delivered)
    }

    /**
     * What the host says one session is doing, when it says anything.
     *
     * `null` until the host opens the channel: an older host never does, and the UI
     * must fall back to what it can infer from the conversation.
     */
    fun sessionState(sessionId: String): SessionState? = synchronized(stateLock) {
        stateChannels[sessionId]?.state
    }

    /** The body text streamed for a session in its current state interval. */
    fun streamedText(sessionId: String): String = synchronized(stateLock) {
        stateChannels[sessionId]?.text.orEmpty()
    }

    /** Whether the host has gone quiet while promising heartbeats. */
    fun isStateStale(
        sessionId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(stateLock) {
        stateChannels[sessionId]?.isStale(nowMillis) ?: false
    }

    private fun clearStateChannels() {
        synchronized(stateLock) { stateChannels.clear() }
    }

    private fun route(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.Welcome -> {
                _state.value = State.Ready(frame)
                handshake?.complete(frame)
                handshake = null
            }

            is ServerFrame.Ok -> {
                inFlight.remove(frame.id)?.let { traffic?.answered(it.method, it.elapsedMillis(), null) }
                pending.remove(frame.id)?.complete(frame.result)
            }

            is ServerFrame.Failure -> {
                // An answer nobody is waiting for is a socket-level complaint, and
                // the operator is the only one who can act on it: it goes to the
                // subscribers rather than being dropped.
                val id = frame.id
                id?.let { inFlight.remove(it) }?.let {
                    traffic?.refused(it.method, it.elapsedMillis(), frame.error.code)
                }
                val waiting = if (id == null) null else pending.remove(id)
                if (waiting != null) waiting.completeExceptionally(frame.error) else publish(frame)
            }

            is ServerFrame.Event -> {
                traffic?.event(frame.name, null)
                publish(frame)
            }

            is ServerFrame.Unknown -> {
                traffic?.event(frame.type, null)
                publish(frame)
            }
        }
    }

    private fun publish(frame: ServerFrame) {
        for (channel in subscribers.values) channel.trySend(frame)
    }

    private fun closeSocket(reason: String) {
        val socket = transport
        transport = null
        readJob?.cancel()
        readJob = null
        if (socket != null) scope.launch { socket.close() }
        handshake?.completeExceptionally(MizarError("CONNECTION_CLOSED", reason, retryable = true))
        handshake = null
        failAll(MizarError("CONNECTION_CLOSED", reason, retryable = true))
        for (channel in subscribers.values) channel.close()
        subscribers.clear()
        _state.value = State.Failed(reason)
    }

    private fun failAll(error: MizarError) {
        val waiting = pending.values.toList()
        pending.clear()
        // Whatever was in flight did not answer; its metadata must not outlive the
        // socket and mislabel the next connection's ids.
        inFlight.clear()
        for (deferred in waiting) deferred.completeExceptionally(error)
    }

    private fun failAndReturn(code: String, message: String): MizarError {
        _state.value = State.Failed(message)
        return MizarError(code, message)
    }

    private fun Endpoint.describe(): String = when (this) {
        is Endpoint.Direct -> authority
        is Endpoint.Relay -> baseUrl
    }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "the connection failed"
}
