package me.shirazu.dshmizar.localagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.shirazu.mizar.kit.Incoming
import me.shirazu.mizar.kit.MizarError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport's framing rules, against a scripted kernel.
 *
 * These run on a plain JVM: the whole point of [LocalKernelLink] is that the layer
 * that decides framing, close and error behaviour can be tested without QuickJS, an
 * emulator or a real kernel. The fake answers like a kernel connection: a `hello`
 * gets a `welcome`, everything else is the test's to push.
 *
 * **Every test is bounded.** A transport's `receive()` is *supposed* to suspend until
 * a frame arrives or the peer closes — that is what makes it a socket — so a test
 * that waits for a frame which never comes would otherwise hang the whole suite
 * (this happened: an assertion expected an immediate `null` from a connected
 * transport, and the JVM test worker blocked on it for 11 minutes). [test] gives
 * every body a hard 5 s ceiling, and the one place that checks "no extra frame" uses
 * a bounded probe instead of an open-ended receive.
 */
class LocalKernelTransportTest {

    private class FakeConnection : LocalLinkConnection {
        override val deviceId: String? = "device-1"
        private val channel = Channel<String>(Channel.UNLIMITED)
        override val frames: ReceiveChannel<String> = channel
        val sent = mutableListOf<String>()

        /** Set by [close]; the tests assert the kernel connection was actually closed. */
        var closed: Boolean = false
            private set

        override fun send(text: String) {
            if (closed) throw IllegalStateException("the connection is closed")
            sent += text
            if (text.contains("\"type\":\"hello\"")) {
                channel.trySend(WELCOME)
            }
        }

        fun push(text: String) {
            channel.trySend(text)
        }

        override fun close() {
            closed = true
            channel.close()
        }
    }

    private class FakeLink : LocalKernelLink {
        val connections = mutableListOf<FakeConnection>()

        override fun openConnection(): LocalLinkConnection =
            FakeConnection().also { connections += it }
    }

    /** Run one test body with a hard ceiling, so no wait can outlive the suite. */
    private fun test(body: suspend CoroutineScope.() -> Unit) {
        runBlocking {
            val finished = withTimeoutOrNull(TEST_TIMEOUT_MILLIS) { body() }
            assertNotNull(
                "the test did not finish within ${TEST_TIMEOUT_MILLIS}ms — a transport wait was not bounded",
                finished,
            )
        }
    }

    @Test
    fun connectIgnoresTheEndpointAndDeliversFramesInOrder() = test {
        val link = FakeLink()
        val transport = LocalKernelTransport(link)

        transport.connect("ws://127.0.0.1:1/ws", mapOf("x-test" to "ignored"))
        val connection = link.connections.single()
        assertEquals("device-1", connection.deviceId)

        transport.send(HELLO)
        assertEquals(listOf(HELLO), connection.sent)
        assertEquals(WELCOME, transport.receive())

        // "Nothing else was pushed" is checked with a bounded probe: an unbounded
        // receive() on a live transport is exactly the wait that must not appear.
        val extra = withTimeoutOrNull(NO_FRAME_PROBE_MILLIS) { transport.receive() }
        assertNull("no frame arrives without one being pushed", extra)
    }

    @Test
    fun receiveMessageReportsTextFrames() = test {
        val link = FakeLink()
        val transport = LocalKernelTransport(link)
        transport.connect("ws://local:19390/ws")
        transport.send(HELLO)
        val message = transport.receiveMessage()
        assertTrue("a text frame arrives as Incoming.Text", message is Incoming.Text)
        assertEquals(WELCOME, (message as Incoming.Text).text)
    }

    @Test
    fun theKernelsPushOrderIsPreserved() = test {
        val link = FakeLink()
        val transport = LocalKernelTransport(link)
        transport.connect("ws://local:19390/ws")
        val connection = link.connections.single()

        connection.push(OK_FRAME)
        connection.push(EVENT_FRAME)
        assertEquals(OK_FRAME, transport.receive())
        assertEquals(EVENT_FRAME, transport.receive())
    }

    @Test
    fun closingEndsTheReadLoopAndRefusesSends() = test {
        val link = FakeLink()
        val transport = LocalKernelTransport(link)
        transport.connect("ws://local:19390/ws")
        val connection = link.connections.single()

        transport.close()
        assertNull("a closed transport has no next frame", transport.receive())
        assertFalse("a closed transport reports itself closed", transport.isConnected)

        val failed = runCatching { transport.send(HELLO) }.exceptionOrNull()
        assertTrue("send after close is a retryable socket error", failed is MizarError)
        assertEquals("SOCKET_CLOSED", (failed as MizarError).code)
        assertTrue("the kernel connection was closed too", connection.closed)
    }

    @Test
    fun connectingTwiceIsRefused() = test {
        val transport = LocalKernelTransport(FakeLink())
        transport.connect("ws://local:19390/ws")
        val failed = runCatching { transport.connect("ws://local:19390/ws") }.exceptionOrNull()
        assertNotNull("a second connect is refused", failed)
        assertTrue(failed is MizarError)
        assertEquals("SOCKET_CLOSED", (failed as MizarError).code)
    }

    @Test
    fun anOpenFailureBecomesARetryableSocketError() = test {
        val failing = LocalKernelLink { throw LocalAgentException("the kernel is not booted") }
        val transport = LocalKernelTransport(failing)
        val failed = runCatching { transport.connect("ws://local:19390/ws") }.exceptionOrNull()
        assertTrue("a failed open surfaces as a MizarError", failed is MizarError)
        assertEquals("SOCKET_CLOSED", (failed as MizarError).code)
        assertTrue("the caller may retry", (failed as MizarError).retryable)
    }

    private companion object {
        /** Hard ceiling for one test body. */
        const val TEST_TIMEOUT_MILLIS = 5_000L

        /** How long "no frame right now" is allowed to take to prove itself. */
        const val NO_FRAME_PROBE_MILLIS = 200L

        const val HELLO = """{"v":1,"type":"hello","token":"local"}"""
        const val WELCOME = """{"v":1,"type":"welcome","hostId":"h","hostName":"n","protocolVersion":1,"home":"/x","deviceId":"d"}"""
        const val OK_FRAME = """{"v":1,"type":"ok","id":"1","result":{}}"""
        const val EVENT_FRAME = """{"v":1,"type":"event","event":"interactions.changed","data":{"approvals":0,"questions":0}}"""
    }
}
