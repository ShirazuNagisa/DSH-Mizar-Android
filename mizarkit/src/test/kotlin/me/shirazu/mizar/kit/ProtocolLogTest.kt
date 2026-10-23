package me.shirazu.mizar.kit

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A socket that answers whatever the test tells it to. */
private class ScriptedSocket(private val answer: (String) -> String?) : WebSocketTransport {

    private val inbox = Channel<String?>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()

    /** When set, the next `send` fails as a closed socket would. */
    var failNextSend = false

    override suspend fun connect(url: String, headers: Map<String, String>) = Unit

    override suspend fun send(text: String) {
        if (failNextSend) {
            failNextSend = false
            throw MizarError("SOCKET_CLOSED", "the socket refused the frame")
        }
        sent += text
        answer(text)?.let { inbox.trySend(it) }
    }

    override suspend fun receive(): String? = inbox.receiveCatching().getOrNull()

    override suspend fun close() {
        inbox.close()
    }

    /** Push a frame the host would have sent on its own. */
    fun push(frame: String) {
        inbox.trySend(frame)
    }
}

/** The id a request frame carries, so the fake can answer it. */
private fun requestId(frame: String): String =
    WIRE_JSON.parseToJsonElement(frame).jsonObject.getValue("id").jsonPrimitive.content

/** The method a request frame carries. */
private fun requestType(frame: String): String =
    WIRE_JSON.parseToJsonElement(frame).jsonObject.getValue("type").jsonPrimitive.content

/** An `ok` frame carrying an empty session list, so `call` has something to decode. */
private fun okWithEmptyList(id: String): String =
    okFrame(id, result = jsonObjectOf("items" to JsonArray(emptyList())))

private fun connection(
    log: ProtocolLog,
    answer: (String) -> String?,
): Pair<MizarConnection, ScriptedSocket> {
    val socket = ScriptedSocket { frame ->
        if (requestType(frame) == "hello") welcomeFrame() else answer(frame)
    }
    val connection = MizarConnection(
        MizarConnection.Configuration(authority = "10.0.2.2:19390", token = "t1"),
        makeTransport = { socket },
        traffic = log,
    )
    return connection to socket
}

class ProtocolLogTest {

    @Test
    fun `a call is one line out and one line back`() = runBlocking {
        val log = ProtocolLog()
        val (connection, _) = connection(log) { frame -> okWithEmptyList(requestId(frame)) }

        connection.connect()
        connection.call<SessionListResult>("session.list")

        val entries = log.snapshot()
        assertEquals(2, entries.size)
        assertEquals(ProtocolLog.Direction.OUT, entries[0].direction)
        assertEquals("session.list", entries[0].method)
        assertEquals(ProtocolLog.Direction.IN, entries[1].direction)
        assertEquals("ok", entries[1].outcome)
        assertTrue((entries[1].durationMillis ?: -1) >= 0)
        connection.disconnect()
    }

    @Test
    fun `a refusal keeps the host's code`() = runBlocking {
        val log = ProtocolLog()
        val (connection, _) = connection(log) { frame ->
            errorFrame(code = "UNAUTHORIZED", id = requestId(frame))
        }

        connection.connect()
        assertThrows<MizarError> { runBlocking { connection.call<SessionListResult>("session.list") } }

        assertEquals("error:UNAUTHORIZED", log.snapshot().last().outcome)
        connection.disconnect()
    }

    @Test
    fun `an event is recorded by name and carries no payload`() = runBlocking {
        val log = ProtocolLog()
        val (connection, socket) = connection(log) { null }

        connection.connect()
        // Undispatched: the subscriber has to exist before the frame is pushed,
        // or the event is published to nobody and `first()` waits for ever.
        val received = async(start = CoroutineStart.UNDISPATCHED) { connection.events().first() }
        // A frame the host pushes without being asked.
        socket.push(eventFrame("turn.settled"))
        received.await()

        val entry = log.snapshot().last()
        assertEquals("event:turn.settled", entry.method)
        assertEquals("event", entry.outcome)
        connection.disconnect()
    }

    @Test
    fun `a frame the socket refuses is recorded as unsent`() = runBlocking {
        val log = ProtocolLog()
        val (connection, socket) = connection(log) { frame -> okWithEmptyList(requestId(frame)) }

        connection.connect()
        socket.failNextSend = true
        assertThrows<MizarError> { runBlocking { connection.call<SessionListResult>("session.list") } }

        val entry = log.snapshot().last()
        assertEquals("session.list", entry.method)
        assertEquals("unsent", entry.outcome)
        connection.disconnect()
    }

    @Test
    fun `the buffer keeps the newest entries and stays bounded`() {
        val log = ProtocolLog(capacity = 3)
        repeat(5) { index -> log.sent("method$index", bytes = 1) }

        val entries = log.snapshot()
        assertEquals(3, entries.size)
        assertEquals("method2", entries.first().method)
        assertEquals("method4", entries.last().method)
    }

    @Test
    fun `the jsonl form is one object per line`() {
        val log = ProtocolLog()
        log.sent("session.list", bytes = 42)
        log.answered("session.list", durationMillis = 12, bytes = null)
        log.refused("session.prompt", durationMillis = 3, code = "RATE_LIMITED")

        val lines = log.jsonLines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("\"method\":\"session.list\""))
        assertTrue(lines[0].contains("\"direction\":\"out\""))
        assertTrue(lines[1].contains("\"ms\":12"))
        assertTrue(lines[2].contains("\"outcome\":\"error:RATE_LIMITED\""))
        assertTrue(lines.none { it.contains("\n") })
    }

    @Test
    fun `a log nobody passes records nothing`() = runBlocking {
        val socket = ScriptedSocket { frame ->
            if (requestType(frame) == "hello") welcomeFrame() else okWithEmptyList(requestId(frame))
        }
        val connection = MizarConnection(
            MizarConnection.Configuration(authority = "10.0.2.2:19390", token = "t1"),
            makeTransport = { socket },
        )

        // No log, no cost: the call has to behave exactly as it did before the log
        // existed, which is the only thing this test needs to pin.
        connection.connect()
        connection.call<SessionListResult>("session.list")
        connection.disconnect()
    }
}
