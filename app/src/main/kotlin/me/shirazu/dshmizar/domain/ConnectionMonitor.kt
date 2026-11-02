package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.ServerFrame

/**
 * A live socket, as far as the monitor is concerned.
 *
 * Narrow on purpose: the monitor only ever reads a state, a stream of frames and the
 * two calls that keep the connection honest, so its tests need no server.
 */
internal interface MonitoredConnection {

    val state: StateFlow<MizarConnection.State>

    fun events(): Flow<ServerFrame>
}

/** The live socket, behind [MonitoredConnection]. */
internal class SocketMonitor(private val socket: MizarConnection) : MonitoredConnection {

    override val state: StateFlow<MizarConnection.State> get() = socket.state

    override fun events(): Flow<ServerFrame> = socket.events()
}

/**
 * Keeping a socket honest: notice when it dies, and ask it a question often enough to
 * find out.
 *
 * The bug this exists for is a specific one, and it is not "the socket closed". A
 * websocket through a relay can be dead while both read loops stay perfectly happy --
 * the tunnel drops the mapping and the relay goes on answering websocket pings. Nothing
 * throws. The app sat "connected" for minutes: no events, every poll failing silently,
 * and every completion in the gap lost -- because the pushed announcement and the
 * polling fallback die together when the socket is a ghost.
 *
 * So the poll *is* the heartbeat: an unanswered question is the only evidence there is.
 *
 * Four rules:
 *
 *  - **Two silent polls mean dead.** One is a blip; two in a row is the tunnel gone.
 *  - **Any answer resets the count.** A socket that stutters is not a socket that died.
 *  - **The loop runs off the main dispatcher.** A heartbeat that shares a thread with
 *    the UI stops when the UI does -- which is exactly when nobody is watching it.
 *  - **The background list read is bounded and conditional.** It is the long call, and
 *    left to the default minute it blocked the poll that watches for silence. It is
 *    only worth making when something was actually running.
 *
 * The state watcher is here too because it answers the same question from the other
 * side: the connection layer already reports a close, and only the connection *on
 * screen* may act on it -- a replaced socket failing later is old news, and two
 * reconnect loops would fight over the socket.
 */
internal class ConnectionMonitor(
    private val store: StateStore,
    private val scope: CoroutineScope,
    private val note: (String) -> Unit,
    /** Everything here is network work; a heartbeat must not share the UI's thread. */
    private val dispatcher: CoroutineDispatcher,
    /** How often the poll asks. A LAN socket is a round trip on the same wire. */
    private val pollMillis: Long = INTERACTION_POLL_MILLIS,
    private val lanPollMillis: Long = LAN_INTERACTION_POLL_MILLIS,
    /** How long the background list read may take before it is abandoned. */
    private val sessionsTimeoutMillis: Long = SESSIONS_POLL_TIMEOUT_MILLIS,
    /** The one request the app already makes; timing it is how the notice learns the distance. */
    private val pollInteractions: suspend () -> Boolean,
    private val recordLatency: (Long) -> Unit,
    /** The list read that catches a turn ending while the screen is elsewhere. */
    private val pollSessions: suspend () -> Unit,
    /** A pushed frame: a waiting card, a settled turn, a relay another device moved. */
    private val onEvent: suspend (ServerFrame.Event) -> Unit,
    /** Two silent polls: say why, and let the caller dial again. */
    private val onSilent: suspend (String) -> Unit,
    /** Whether anything was running, so the background list read is worth its cost. */
    private val anythingRunning: () -> Boolean,
    private val isForeground: () -> Boolean,
) {

    private var observer: Job? = null
    private var loop: Job? = null

    /**
     * Watch the live connection's own report of its health.
     *
     * @param isLive whether this socket is still the one on screen: a replaced socket
     *   failing later must not start a second reconnect loop.
     */
    fun watchConnection(
        socket: MonitoredConnection,
        isLive: () -> Boolean,
        onFailed: (String) -> Unit,
    ) {
        observer?.cancel()
        observer = scope.launch {
            socket.state.collect { state ->
                if (state is MizarConnection.State.Failed && isLive()) onFailed(state.message)
            }
        }
    }

    /** Stop watching the connection's state; a caller about to replace it owns the socket. */
    fun stopConnectionWatch() {
        observer?.cancel()
        observer = null
    }

    /** Start the poll that is also the heartbeat. */
    fun start(socket: MonitoredConnection, isLive: () -> Boolean) {
        // Off the main dispatcher on purpose. Everything here is network work, and the
        // loop is the connection's heartbeat -- a heartbeat that shares a thread with
        // the UI stops when the UI does, which is exactly when nobody is watching it.
        loop = scope.launch(dispatcher) {
            // The pushed signals, plus a safety-net poll. Notifications are best
            // effort, and a notification lost to a reconnect must not leave a card on
            // screen that no longer exists.
            launch {
                socket.events().collect { frame ->
                    if (frame is ServerFrame.Event) {
                        note("event ${frame.name}")
                        onEvent(frame)
                    }
                }
            }
            var silentPolls = 0
            var ticks = 0
            try {
                while (true) {
                    // The relay has to survive the internet; a LAN socket is a round trip
                    // on the same wire, so the poll can be three seconds instead of ten
                    // without making the radio work harder than the UI.
                    delay(if (store.state.value.isLocalNetwork) lanPollMillis else pollMillis)
                    // The poll is a round trip, so it is also the clock: timing the one
                    // request the app already makes is how the notice learns the distance
                    // without a second protocol message invented for it.
                    val askedAt = System.nanoTime()
                    val answered = pollInteractions()
                    if (answered) recordLatency((System.nanoTime() - askedAt) / 1_000_000)
                    if (!answered && isActive) {
                        silentPolls += 1
                        note("poll got no answer ($silentPolls)")
                        if (silentPolls >= SILENT_POLLS_BEFORE_DEAD) {
                            note("the socket is dead; reconnecting")
                            onSilent("the host stopped answering")
                            return@launch
                        }
                    } else {
                        silentPolls = 0
                    }
                    ticks += 1
                    if (ticks % HEARTBEAT_LOG_EVERY == 0) {
                        note("heartbeat: connected=${isLive()} silent=$silentPolls foreground=${isForeground()}")
                    }
                    // No pushed signal says "the turn ended", so while the screen is
                    // elsewhere the list is the only place to see it -- and only worth
                    // asking when something was actually running. Bounded, because this
                    // read is the long one: left to the default minute per call it
                    // blocked the poll that watches for silence.
                    if (!isForeground() && anythingRunning()) {
                        withTimeoutOrNull(sessionsTimeoutMillis) {
                            runCatching { pollSessions() }
                        }
                    }
                }
            } finally {
                note("watchers stopped")
            }
        }
    }

    /** Stop the poll and the event stream. */
    fun stop() {
        loop?.cancel()
        loop = null
    }

    internal companion object {
        /** One silent poll is a blip; two in a row is a tunnel that is gone. */
        const val SILENT_POLLS_BEFORE_DEAD = 2

        /** How many polls between the heartbeat's own log lines. */
        const val HEARTBEAT_LOG_EVERY = 6

        /**
         * How often the poll asks a relayed socket.
         *
         * The documented safety net, not the mechanism -- and short, because the thing
         * it backs up is an agent that is stopped until somebody answers. It was 45 s,
         * long enough to watch a blocked turn sit there and conclude the app is broken;
         * ten seconds costs one round trip on a LAN or a USB tunnel and bounds the
         * worst case to something an operator will wait through.
         */
        const val INTERACTION_POLL_MILLIS = 10_000L

        /** The same question on a LAN socket: three seconds, like iOS on a LAN link. */
        const val LAN_INTERACTION_POLL_MILLIS = 3_000L

        /**
         * How long the background list read may take.
         *
         * Long enough for a slow host, short enough that it cannot hold up the poll
         * that is the connection's heartbeat.
         */
        const val SESSIONS_POLL_TIMEOUT_MILLIS = 20_000L
    }
}
