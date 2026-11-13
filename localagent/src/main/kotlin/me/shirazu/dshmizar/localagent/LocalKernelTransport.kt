package me.shirazu.dshmizar.localagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withContext
import me.shirazu.mizar.kit.Incoming
import me.shirazu.mizar.kit.MizarError
import me.shirazu.mizar.kit.WebSocketTransport

/**
 * The local kernel as a `WebSocketTransport`.
 *
 * The whole point of the kernel's protocol fidelity is on display here:
 * `MizarConnection` needs four methods — connect, send, receive, close — and a
 * transport that behaves like the wire. This class provides them against an
 * in-process [LocalKernelLink], so the same connection layer, reconnection plan,
 * correlation and event stream that serve a desktop host serve the kernel too, and
 * the app switches between "remote" and "local" purely by choosing a transport.
 *
 * The `url` and `headers` of [connect] are accepted and ignored: a local kernel has
 * no socket to dial. Callers pass whatever endpoint the connection's configuration
 * carries (a valid but never-dialled loopback authority), and the frames travel in
 * process.
 *
 * Threading: every engine call blocks on the single JS thread, so those calls run on
 * [Dispatchers.IO]; the frame channel itself is safe to await from any dispatcher.
 */
class LocalKernelTransport(private val link: LocalKernelLink) : WebSocketTransport {

    /** The open connection, or `null` before [connect] / after [close]. */
    @Volatile
    private var connection: LocalLinkConnection? = null

    /** Whether [connect] has completed and no [close] has happened. */
    val isConnected: Boolean get() = connection != null

    override suspend fun connect(url: String, headers: Map<String, String>) {
        if (connection != null) {
            throw MizarError(
                "SOCKET_CLOSED",
                "the local transport is already connected",
                retryable = true,
            )
        }
        val opened = try {
            withContext(Dispatchers.IO) { link.openConnection() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw MizarError(
                "SOCKET_CLOSED",
                "the local kernel could not open a connection: ${error.message}",
                retryable = true,
            )
        }
        connection = opened
    }

    override suspend fun send(text: String) {
        val current = connection
            ?: throw MizarError("SOCKET_CLOSED", "the local transport is not connected", retryable = true)
        try {
            withContext(Dispatchers.IO) { current.send(text) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw MizarError(
                "SOCKET_CLOSED",
                "the local kernel refused the frame: ${error.message}",
                retryable = true,
            )
        }
    }

    /**
     * The next frame the kernel pushed, or `null` once the connection has closed.
     *
     * Only text frames exist on this transport: the local kernel does not speak the
     * desktop state channel (turns are observed by polling `session.messages`).
     */
    override suspend fun receive(): String? {
        val current = connection ?: return null
        return current.frames.receiveCatching().getOrNull()
    }

    override suspend fun receiveMessage(): Incoming? = receive()?.let { Incoming.Text(it) }

    override suspend fun close() {
        val current = connection ?: return
        connection = null
        // The engine's close path ends the frame channel, which is what makes a
        // blocked `receive()` return null and lets the connection layer finish.
        withContext(Dispatchers.IO) { runCatching { current.close() } }
        // Belt and braces: a reader must never outlive close(), even if the engine
        // call above timed out (a wedged JS thread) or the link never got to run its
        // close hook. Closing the channel here is thread-safe and idempotent.
        runCatching { (current.frames as? SendChannel<*>)?.close() }
    }
}
