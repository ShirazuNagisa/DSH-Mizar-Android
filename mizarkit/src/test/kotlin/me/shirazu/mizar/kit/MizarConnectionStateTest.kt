package me.shirazu.mizar.kit

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The state channel as it arrives over the socket: the session envelope, the
 * per-session channel, and the rule that an unreadable binary frame is ignored
 * rather than allowed to break the connection.
 *
 * The wire bytes themselves are [StateFrameTest]'s job; these tests drive the
 * connection layer, which is where the envelope is parsed and attributed.
 */
class MizarConnectionStateTest {

    private fun connection(
        socket: WebSocketTransport,
        traffic: ProtocolLog? = null,
        makeTransport: () -> WebSocketTransport = { socket },
    ): MizarConnection = MizarConnection(
        MizarConnection.Configuration(
            authority = "localhost:1234",
            token = "device-token",
            requestTimeoutMillis = 5_000,
        ),
        traffic,
        makeTransport,
    )

    /** A state message: session id prefix plus one frame, exactly as the host sends it. */
    private fun stateMessage(sessionId: String, frame: StateFrame): ByteArray =
        StateFrameEnvelope.encode(sessionId, frame)

    /** Wait until [condition] holds, so a test does not race the reader coroutine. */
    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(3_000) {
            while (!condition()) delay(5)
        }
    }

    @Test
    fun `a binary frame is attributed to its session and reaches the flow`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            val frame = StateFrame.begin(SessionState.STREAM_FINAL, 7, "改好了。")
            val received = async { withTimeout(3_000) { connection.stateFrames.first() } }
            // Give the collector a moment to subscribe: a SharedFlow does not replay.
            delay(50)
            socket.deliverBinary(stateMessage("dsh:abc", frame))

            val delivered = received.await()
            assertEquals("dsh:abc", delivered.sessionId)
            assertEquals(frame, delivered.frame)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `the connection keeps one channel per session`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            socket.deliverBinary(stateMessage("a", StateFrame.begin(SessionState.STREAM_FINAL, 1, "第一段")))
            socket.deliverBinary(stateMessage("b", StateFrame.begin(SessionState.THINKING, 1)))
            eventually { connection.sessionState("a") != null && connection.sessionState("b") != null }

            assertEquals(SessionState.STREAM_FINAL, connection.sessionState("a"))
            assertEquals(SessionState.THINKING, connection.sessionState("b"))
            assertEquals("第一段", connection.streamedText("a"))
            assertEquals("", connection.streamedText("b"))
            assertNull(connection.sessionState("never-mentioned"))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `appends continue the text and staleness follows the clock`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            socket.deliverBinary(stateMessage("s1", StateFrame.begin(SessionState.STREAM_FINAL, 3, "第一段")))
            socket.deliverBinary(stateMessage("s1", StateFrame.append(SessionState.STREAM_FINAL, 3, 9, "第二段")))
            eventually { connection.streamedText("s1") == "第一段第二段" }

            assertFalse(connection.isStateStale("s1", System.currentTimeMillis()))
            assertTrue(connection.isStateStale("s1", System.currentTimeMillis() + StateFrame.STALE_AFTER_MILLIS + 1))
            // A terminal state never goes stale.
            socket.deliverBinary(stateMessage("done", StateFrame.begin(SessionState.COMPLETED, 1)))
            eventually { connection.sessionState("done") == SessionState.COMPLETED }
            assertFalse(connection.isStateStale("done", Long.MAX_VALUE))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `an unreadable binary frame is ignored and the socket stays up`() = runBlocking {
        val socket = FakeSocket()
        val traffic = ProtocolLog()
        val connection = connection(socket, traffic)
        try {
            connection.connect()
            // Not a state message at all: a newer binary dialect, or junk.
            socket.deliverBinary(byteArrayOf(0x7B, 0x7D))
            socket.deliverBinary(byteArrayOf(0x99.toByte(), 0x01, 0x02, 0x03))
            eventually { traffic.snapshot().any { it.outcome == "ignored" } }

            // The socket must still work: a JSON event after the junk is delivered.
            socket.deliver(eventFrame("session.changed"))
            eventually {
                traffic.snapshot().any { it.method == "event:session.changed" }
            }
            assertIs<MizarConnection.State.Ready>(connection.state.value)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a known envelope carrying an unknown state code is ignored too`() = runBlocking {
        val socket = FakeSocket()
        val traffic = ProtocolLog()
        val connection = connection(socket, traffic)
        try {
            connection.connect()
            val bytes = stateMessage("s1", StateFrame.heartbeat(SessionState.IDLE, 1))
            bytes[1 + "s1".length + 1] = 200.toByte() // the state-code byte
            socket.deliverBinary(bytes)
            eventually { traffic.snapshot().any { it.outcome == "ignored" } }
            assertEquals(null, connection.sessionState("s1"))
            assertIs<MizarConnection.State.Ready>(connection.state.value)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a new socket starts from an empty state channel`() = runBlocking {
        var socket = FakeSocket()
        val connection = connection(socket) { socket }
        try {
            connection.connect()
            socket.deliverBinary(stateMessage("s1", StateFrame.begin(SessionState.STREAM_FINAL, 9, "旧")))
            eventually { connection.sessionState("s1") != null }

            connection.disconnect()
            // A new socket is a new epoch: the host's interval counter can restart, and
            // a kept channel would make every new frame look stale.
            socket = FakeSocket()
            connection.connect()
            assertNull(connection.sessionState("s1"))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `the hello frame carries the chosen agent`() = runBlocking {
        val socket = FakeSocket()
        val connection = MizarConnection(
            MizarConnection.Configuration(
                authority = "localhost:1234",
                token = "device-token",
                engine = "claude",
            ),
        ) { socket }
        try {
            connection.connect()
            val hello = socket.sent.first { it.contains("\"type\":\"hello\"") }
            assertTrue(hello.contains("\"engine\":\"claude\""), hello)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `an unscoped hello stays exactly as it was`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            val hello = socket.sent.first { it.contains("\"type\":\"hello\"") }
            assertFalse(hello.contains("\"engine\""), "an unscoped connection must not name an agent: $hello")
            assertTrue(hello.contains("\"token\":\"device-token\""))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `host info decodes the agent list, the desktop and the plugin capabilities`() {
        val info = WIRE_JSON.parseToJsonElement(
            """
            {"hostId":"h1","hostName":"PC","home":"/h","protocolVersion":1,
             "addresses":["192.168.1.5:19390"],
             "desktop":{"os":"Windows","client":"AGI Mizar"},
             "engines":[{"id":"dsh","label":"DSH","connected":true},
                        {"id":"claude","label":"Claude Code","connected":false,"detail":"not running"}],
             "engine":"dsh",
             "plugin":{"id":"dsh-mizar","capabilities":["deepseek.balance.v1"]},
             "netEndpointId":null}
            """.trimIndent(),
        ).decodeAs<HostInfo>()

        assertEquals("Windows", info.desktop?.os)
        assertEquals("AGI Mizar", info.desktop?.client)
        assertEquals("dsh", info.engine)
        assertEquals(2, info.engines?.size)
        assertEquals("claude", info.engines?.get(1)?.id)
        assertFalse(info.engines!![1].connected)
        assertEquals("not running", info.engines!![1].detail)
        assertTrue(info.supportsDeepSeekBalance)
        assertFalse(info.supports("state.v1"))
    }

    @Test
    fun `an older host info decodes without the additive fields`() {
        val info = WIRE_JSON.parseToJsonElement(
            """{"hostId":"h1","hostName":"old","home":"/h","protocolVersion":1}""",
        ).decodeAs<HostInfo>()
        assertNull(info.engines)
        assertNull(info.engine)
        assertNull(info.desktop)
        assertNull(info.plugin)
        assertNull(info.addresses)
        assertFalse(info.supportsDeepSeekBalance)
    }

    @Test
    fun `the session-changed event decodes both shapes`() {
        val running = HostEvents.sessionChanged(
            WIRE_JSON.parseToJsonElement("""{"sessionId":"s1","running":true}"""),
        )
        assertNotNull(running)
        assertEquals("s1", running.sessionId)
        assertEquals(true, running.running)
        assertFalse(running.removed)

        val removed = HostEvents.sessionChanged(
            WIRE_JSON.parseToJsonElement("""{"sessionId":"s1","removed":true}"""),
        )
        assertNotNull(removed)
        assertTrue(removed.removed)
        assertNull(HostEvents.sessionChanged(kotlinx.serialization.json.JsonNull))
        assertNull(HostEvents.sessionChanged(WIRE_JSON.parseToJsonElement("\"not an object\"")))
    }
}
