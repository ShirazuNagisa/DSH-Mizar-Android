package me.shirazu.mizar.kit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A socket a test drives frame by frame.
 *
 * The interface exists so the state machine can be tested without a network; what
 * was missing was one implementation that could also be *misbehaved with*: answer
 * out of order, stay silent, refuse a frame, hang up, or hold a send open long
 * enough for the caller to be cancelled. Each of those is a real host or a real
 * network, and each used to need a phone to reproduce.
 *
 * Outbound frames are kept verbatim and in order, so a test can assert both what
 * was sent and how many times.
 */
class FakeSocket(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val answerHello: Boolean = true,
) : WebSocketTransport {

    private val inbox = Channel<String?>(Channel.UNLIMITED)
    private val outbox = CopyOnWriteArrayList<String>()

    /** Thrown by [send], as a socket that is already gone would. */
    @Volatile
    var sendFailure: Throwable? = null

    /** When set, [send] waits for it: the window a cancellation test needs. */
    @Volatile
    var sendGate: CompletableDeferred<Unit>? = null

    /** How many sends have been entered, so a test can wait for one in progress. */
    @Volatile
    var sendsStarted: Int = 0
        private set

    /** Thrown by [connect], for a host that refuses the upgrade. */
    @Volatile
    var connectFailure: Throwable? = null

    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    var closed: Boolean = false
        private set

    /** Everything this socket was asked to send, in order. */
    val sent: List<String> get() = outbox.toList()

    /** The `id` of every request frame sent, in order. */
    fun requestIds(): List<String> = outbox
        .filter { !it.contains("\"type\":\"hello\"") }
        .mapNotNull { frameId(it) }

    private fun frameId(text: String): String? = runCatching {
        (WIRE_JSON.parseToJsonElement(text) as JsonObject)["id"]?.let { (it as JsonPrimitive).content }
    }.getOrNull()

    override suspend fun connect(url: String, headers: Map<String, String>) {
        connectFailure?.let { throw it }
        connected = true
    }

    override suspend fun send(text: String) {
        sendsStarted += 1
        sendGate?.await()
        sendFailure?.let { throw it }
        outbox += text
        if (answerHello && text.contains("\"type\":\"hello\"")) {
            inbox.trySend(welcomeFrame())
        }
    }

    override suspend fun receive(): String? = inbox.receiveCatching().getOrNull()

    override suspend fun close() {
        closed = true
        inbox.close()
    }

    /** Push a frame now. */
    fun deliver(text: String) {
        inbox.trySend(text)
    }

    /** Push a frame later, which is how "out of order" is built. */
    fun deliverAfter(delayMillis: Long, text: String) {
        scope.launch {
            delay(delayMillis)
            inbox.trySend(text)
        }
    }

    /** The host went away without a close frame. */
    fun hangUp() {
        inbox.close()
    }
}
