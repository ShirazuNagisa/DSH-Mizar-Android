package me.shirazu.mizar.kit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The connection state machine, driven by a socket that can misbehave.
 *
 * These are characterisation tests: they record what the code does today, so the
 * extraction in the later stages can be proved not to change it. Two of them pin
 * behaviour that is *wrong* on purpose -- they are marked, because the risk batch
 * will have to change them, and a test that quietly encoded a bug as a requirement
 * would be worse than no test.
 *
 * Every test body must end in `Unit`: JUnit 5 does not run a `@Test` method that
 * returns a value, and it does so *silently* -- four cases here were written as
 * `= runBlocking { assertIs<...>(x) }`, which returned the assertion's argument and
 * simply never ran.
 */
class MizarConnectionTest {

    private fun connection(socket: FakeSocket, requestTimeoutMillis: Long = 5_000) =
        MizarConnection(
            MizarConnection.Configuration(
                authority = "localhost:1234",
                token = "device-token",
                requestTimeoutMillis = requestTimeoutMillis,
            ),
        ) { socket }

    /** Wait until the client has actually written [count] request frames. */
    private suspend fun awaitRequests(socket: FakeSocket, count: Int) {
        withTimeout(3_000) {
            while (socket.requestIds().size < count) delay(5)
        }
    }

    /**
     * Wait for the connection to report failure.
     *
     * The state flip deliberately trails the error: `failAll` completes the waiters
     * and `closeSocket` sets `Failed` afterwards, so a caller can hold the specific
     * error for a few microseconds while the state still says `Ready`. Asserting
     * the flip *synchronously* after `await()` pins an ordering the code does not
     * promise -- it passed on a fast machine and failed on a two-core runner, which
     * is the worst kind of green.
     */
    private suspend fun awaitFailed(connection: MizarConnection) {
        try {
            withTimeout(3_000) {
                while (connection.state.value !is MizarConnection.State.Failed) delay(5)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError(
                "the connection never reported failure; state stayed ${connection.state.value}",
                timeout,
            )
        }
    }

    private fun text(value: kotlinx.serialization.json.JsonElement?): String? =
        (value as? JsonPrimitive)?.content

    @Test
    fun `answers that arrive out of order still reach their own callers`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            val first = async { text(connection.request("session.list")) }
            awaitRequests(socket, 1)
            val second = async { text(connection.request("session.stats")) }
            awaitRequests(socket, 2)
            val ids = socket.requestIds()

            // The host answered the second call first. Correlation is by id, so the
            // order on the wire must not matter.
            socket.deliver(okFrame(ids[1], JsonPrimitive("second")))
            socket.deliver(okFrame(ids[0], JsonPrimitive("first")))

            assertEquals("first", first.await())
            assertEquals("second", second.await())
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `an answer for an id nobody is waiting for is ignored`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            val call = async { text(connection.request("session.list")) }
            awaitRequests(socket, 1)

            socket.deliver(okFrame("SOMEONE-ELSES-ID", JsonPrimitive("wrong")))
            socket.deliver(okFrame(socket.requestIds().first(), JsonPrimitive("ours")))

            assertEquals("ours", call.await())
            assertTrue(
                connection.state.value is MizarConnection.State.Ready,
                "one stray answer must not change the connection's state",
            )
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a call that never answers times out and says so`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            val error = assertThrows<MizarError> {
                runBlocking { connection.request("session.stats", timeoutMillis = 80) }
            }

            assertEquals("REQUEST_TIMEOUT", error.code)
            assertTrue(error.retryable)
            // The connection itself is still usable: one slow call is not a dead socket.
            assertTrue(
                connection.state.value is MizarConnection.State.Ready,
                "a timed-out call must not close the connection",
            )
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a refused send fails that call and leaves nothing behind`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()

            socket.sendFailure = IllegalStateException("the socket is gone")
            val error = assertThrows<MizarError> {
                runBlocking { connection.request("session.list") }
            }
            assertEquals("SOCKET_CLOSED", error.code)

            // The abandoned id must not poison the next call: a later answer for the
            // failed request would otherwise complete the wrong waiter.
            socket.sendFailure = null
            val next = async { text(connection.request("session.list")) }
            // The refused request never reached the wire, so this is the first frame.
            awaitRequests(socket, 1)
            socket.deliver(okFrame(socket.requestIds().last(), JsonPrimitive("after")))
            assertEquals("after", next.await())
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A caller that was cancelled is cancelled.
     *
     * This used to be `MizarError("SOCKET_CLOSED")`: `request` wrapped `socket.send` in
     * `catch (_: Throwable)`, so an operator leaving the screen mid-upload was reported
     * as a network fault by every `runCatching` above it -- and "the socket dropped"
     * is a sentence that makes people look at their Wi-Fi. Stage 6 changed it, and this
     * test changed with it.
     */
    @Test
    fun `a cancelled send is cancelled, not reported as a closed socket`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            val gate = CompletableDeferred<Unit>()
            socket.sendGate = gate

            val seen = CompletableDeferred<Throwable?>()
            val job = launch {
                try {
                    connection.request("upload.begin")
                    seen.complete(null)
                } catch (error: Throwable) {
                    seen.complete(error)
                }
            }
            // Wait for the frame to be inside `send`, not merely scheduled.
            withTimeout(3_000) { while (socket.sendsStarted < 2) delay(5) }
            job.cancel()
            val error = seen.await()

            // `assertTrue`, not `assertIs`: the latter returns its argument, which
            // makes this `= runBlocking { ... }` a non-Unit test -- and JUnit 5 then
            // skips it silently. The count going 360 -> 359 is what caught it here.
            assertTrue(error is CancellationException, "the caller asked to stop; nothing broke")
        } finally {
            socket.sendGate = null
            connection.disconnect()
        }
    }

    @Test
    fun `a host that hangs up fails everything that was waiting`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            val call = async { runCatching { connection.request("session.list") } }
            awaitRequests(socket, 1)

            socket.hangUp()
            val error = call.await().exceptionOrNull()

            val mizar = assertIs<MizarError>(error)
            assertEquals("CONNECTION_CLOSED", mizar.code)
            awaitFailed(connection)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A version mismatch is what the caller is told.
     *
     * It used to close the socket first, which failed every waiter with the generic
     * `CONNECTION_CLOSED`, and then hand the specific error to an already-empty set --
     * so the one message that says "the host is newer than this build" was thrown away
     * in favour of "the socket dropped". Stage 6 reversed the order.
     */
    @Test
    fun `a version mismatch closes the socket and names itself`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            val call = async { runCatching { connection.request("session.list") } }
            awaitRequests(socket, 1)

            socket.deliver(
                serverFrame(
                    "v" to jsonInt(MIZAR_PROTOCOL_VERSION + 1),
                    "type" to jsonString("ok"),
                    "id" to jsonString(socket.requestIds().first()),
                    "result" to jsonString("from the future"),
                ),
            )

            val error = call.await().exceptionOrNull()
            val mizar = assertIs<MizarError>(error)
            assertEquals("PROTOCOL_VERSION_MISMATCH", mizar.code)
            assertTrue(
                mizar.message.contains("protocol version", ignoreCase = true),
                "the reason names the mismatch: ${mizar.message}",
            )
            awaitFailed(connection)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a malformed frame is skipped and the next answer still routes`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            val call = async { text(connection.request("session.list")) }
            awaitRequests(socket, 1)

            socket.deliver("{ this is not a frame")
            socket.deliver(okFrame(socket.requestIds().first(), JsonPrimitive("fine")))

            assertEquals("fine", call.await())
        } finally {
            connection.disconnect()
        }
    }
}
