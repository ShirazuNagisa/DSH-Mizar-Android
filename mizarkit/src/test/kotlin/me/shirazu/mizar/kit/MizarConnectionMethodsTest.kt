package me.shirazu.mizar.kit

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The methods Android gained after the host's first release: `session.sync`,
 * `session.stream`, `session.compact`, `session.fork`, `usage.overview`, `file.stat`
 * and `file.read`.
 *
 * Two things are pinned for each: the payload this client writes (a wrong key is a
 * silent no-op on the host, not an error) and the answer shape. The third pinned
 * thing is the one the whole additive design rests on: a host that predates a method
 * answers `NOT_IMPLEMENTED`, and that is `null` (or `Unsupported`), never an
 * exception a screen has to catch.
 */
class MizarConnectionMethodsTest {

    private fun connection(socket: WebSocketTransport): MizarConnection = MizarConnection(
        MizarConnection.Configuration(
            authority = "localhost:1234",
            token = "device-token",
            requestTimeoutMillis = 5_000,
        ),
    ) { socket }

    private suspend fun awaitRequest(socket: FakeSocket) {
        withTimeout(3_000) { while (socket.requestIds().isEmpty()) delay(5) }
    }

    /**
     * The next request this socket has not been answered for, in order.
     *
     * A counter rather than "the last one": two calls in a row would otherwise answer
     * the first request twice -- one caller gets the wrong answer and the other times
     * out.
     */
    private val answeredRequests = java.util.IdentityHashMap<FakeSocket, Int>()

    private suspend fun nextRequest(socket: FakeSocket): String {
        val expected = (answeredRequests[socket] ?: 0) + 1
        withTimeout(3_000) { while (socket.requestIds().size < expected) delay(5) }
        answeredRequests[socket] = expected
        return socket.requestIds()[expected - 1]
    }

    /** Run one call and answer it from the scripted host. */
    private suspend fun <T> answered(
        socket: FakeSocket,
        result: String,
        call: suspend () -> T,
    ): T = coroutineScope {
        val pending = async { call() }
        val id = nextRequest(socket)
        socket.deliver(okFrame(id, WIRE_JSON.parseToJsonElement(result)))
        pending.await()
    }

    /** Run one call and refuse it with [code]. */
    private suspend fun <T> refused(
        socket: FakeSocket,
        code: String,
        call: suspend () -> T,
    ): T = coroutineScope {
        val pending = async { call() }
        val id = nextRequest(socket)
        socket.deliver(errorFrame(code, id = id))
        pending.await()
    }

    /** The payload of the last request, or `null` when it carried none. */
    private fun lastPayload(socket: FakeSocket): JsonObject? =
        (WIRE_JSON.parseToJsonElement(socket.sent.last()) as JsonObject)["payload"] as? JsonObject

    @Test
    fun `session stream asks by session id and decodes the journal`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            val result = answered(
                socket,
                """
                {"sessionId":"dsh:abc","turn":12,"state":6,"interval":37,"textSeq":120,
                 "truncated":false,
                 "entries":[
                   {"interval":30,"state":1,"text":"","final":false},
                   {"interval":33,"state":6,"text":"改好了。","final":true}]}
                """.trimIndent(),
            ) { connection.streamSession("dsh:abc") }

            assertEquals("dsh:abc", lastPayload(socket)?.get("sessionId")?.jsonPrimitive?.content)
            val replay = assertIs<SessionStreamResult>(result)
            assertEquals(12, replay.turn)
            assertEquals(6, replay.state)
            assertEquals(37L, replay.interval)
            assertEquals(120L, replay.textSeq)
            assertEquals(2, replay.entries.size)
            assertEquals("改好了。", replay.entries[1].text)
            assertTrue(replay.entries[1].isFinal)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `an older host turns the additive methods into null`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()

            assertNull(refused(socket, "NOT_IMPLEMENTED") { connection.streamSession("s1") })
            assertNull(refused(socket, "NOT_IMPLEMENTED") { connection.compactSession("s1") })
            assertNull(refused(socket, "NOT_IMPLEMENTED") { connection.forkSession("s1", 42) })
            assertNull(refused(socket, "NOT_IMPLEMENTED") { connection.usageOverview() })
            assertNull(refused(socket, "NOT_IMPLEMENTED") { connection.fileStat("s1", "a.png") })
            assertIs<SessionSyncOutcome.Unsupported>(
                refused(socket, "NOT_IMPLEMENTED") {
                    connection.syncSessions(listOf(SessionSyncRequest("s1")))
                },
            )
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a real failure is still thrown, not flattened into null`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            val error = assertThrows<MizarError> {
                runBlocking { refused(socket, "REMOTE_ERROR") { connection.streamSession("s1") } }
            }
            assertEquals("REMOTE_ERROR", error.code)
            assertFalse(error.isNotImplemented)
            assertTrue(MizarError("NOT_IMPLEMENTED", "old host").isNotImplemented)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `usage overview carries no payload and decodes the days`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            val result = answered(
                socket,
                """
                {"sessions":42,"days":[
                  {"date":"2026-11-12","messages":10,"inputTokens":1200,"outputTokens":300,
                   "hours":[0,0,0,0,0,0,0,0,0,10,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                   "models":[{"model":"deepseek","messages":10,"inputTokens":1200,"outputTokens":300}]}]}
                """.trimIndent(),
            ) { connection.usageOverview() }

            assertNull(lastPayload(socket), "usage.overview takes no arguments")
            val overview = assertIs<UsageOverview>(result)
            assertEquals(42L, overview.sessions)
            assertEquals(1, overview.days.size)
            val day = overview.days.single()
            assertEquals("2026-11-12", day.date)
            assertEquals(1500L, day.tokens)
            assertEquals(24, day.hours.size)
            assertEquals(10L, day.hours[9])
            assertEquals("deepseek", day.models.single().model)
            assertEquals(day, overview.day("2026-11-12"))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `file stat and file read carry the session and the path`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            val stat = answered(
                socket,
                """{"path":"shots/a.png","name":"a.png","byteSize":2048,"modifiedAt":1760500000000,
                    "mediaType":"image/png","previewable":true}""",
            ) { connection.fileStat("s1", "shots/a.png") }
            val statPayload = assertIs<JsonObject>(lastPayload(socket))
            assertEquals("s1", statPayload["sessionId"]?.jsonPrimitive?.content)
            assertEquals("shots/a.png", statPayload["path"]?.jsonPrimitive?.content)
            val facts = assertIs<FileStat>(stat)
            assertEquals(2048L, facts.byteSize)
            assertEquals("image/png", facts.mediaType)
            assertTrue(facts.previewable)

            val slice = answered(
                socket,
                """{"offset":0,"length":3,"byteSize":3,"data":"YWJj","eof":true}""",
            ) { connection.readFileSlice("s1", "shots/a.png", offset = 0, length = 1536) }
            val slicePayload = assertIs<JsonObject>(lastPayload(socket))
            assertEquals(0, slicePayload["offset"]?.jsonPrimitive?.content?.toInt())
            assertEquals(1536, slicePayload["length"]?.jsonPrimitive?.content?.toInt())
            assertEquals("abc", slice.bytes().decodeToString())
            assertTrue(slice.eof)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `compact and fork use the documented payloads`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()

            val compacted = answered(socket, """{"compacted":true}""") { connection.compactSession("s1") }
            assertEquals("s1", assertIs<JsonObject>(lastPayload(socket))["sessionId"]?.jsonPrimitive?.content)
            assertTrue(assertIs<CompactResult>(compacted).compacted)

            val forked = answered(socket, """{"sessionId":"dsh:new"}""") { connection.forkSession("s1", 4242) }
            val forkPayload = assertIs<JsonObject>(lastPayload(socket))
            assertEquals("s1", forkPayload["sessionId"]?.jsonPrimitive?.content)
            assertEquals(4242, forkPayload["atSeq"]?.jsonPrimitive?.content?.toInt())
            val branch = assertIs<ForkResult>(forked)
            assertEquals("dsh:new", branch.sessionId)
            assertTrue(branch.hasBranch)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `sync batches the documented item shape and decodes the answer`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            val outcome = answered(
                socket,
                """
                {"items":[
                   {"sessionId":"a","unchanged":true},
                   {"sessionId":"b","messages":{"sessionId":"b","asOfSeq":9,"records":[{"type":"turn/start"}],"hasMore":false}},
                   {"sessionId":"c","error":{"code":"REMOTE_ERROR","message":"subagent"}}],
                 "remaining":["d"]}
                """.trimIndent(),
            ) {
                connection.syncSessions(
                    listOf(
                        SessionSyncRequest("a", SessionMessagesCursor.After(5), updatedAt = 1760500000000.0),
                        // An empty cursor is a claim and must travel as an explicit null,
                        // not as an absent key (the host treats them differently).
                        SessionSyncRequest("b", SessionMessagesCursor.Empty, maxMessages = 200),
                        SessionSyncRequest("c", parentSessionId = "parent"),
                        SessionSyncRequest("d"),
                    ),
                    maxBytes = 600_000,
                    maxItems = 32,
                )
            }

            val payload = assertIs<JsonObject>(lastPayload(socket))
            assertEquals(600_000, payload["maxBytes"]?.jsonPrimitive?.content?.toInt())
            assertEquals(32, payload["maxItems"]?.jsonPrimitive?.content?.toInt())
            val items = payload["items"]!!.jsonArray
            assertEquals(5, items[0].jsonObject["afterSeq"]?.jsonPrimitive?.content?.toInt())
            assertEquals(1760500000000.0, items[0].jsonObject["updatedAt"]?.jsonPrimitive?.content?.toDouble())
            assertEquals(JsonNull, items[1].jsonObject["afterSeq"], "an empty cursor must be an explicit null")
            assertFalse(items[1].jsonObject.containsKey("updatedAt"))
            assertEquals("parent", items[2].jsonObject["parentSessionId"]?.jsonPrimitive?.content)

            val answered = assertIs<SessionSyncOutcome.Answered>(outcome).result
            assertEquals(3, answered.items.size)
            assertEquals(true, answered.items[0].unchanged)
            assertEquals("b", answered.items[1].messages?.sessionId)
            assertEquals("REMOTE_ERROR", answered.items[2].error?.code)
            assertEquals(listOf("d"), answered.remaining)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `an empty sync batch is answered locally instead of round-tripping`() = runBlocking {
        val socket = FakeSocket()
        val connection = connection(socket)
        try {
            connection.connect()
            val outcome = connection.syncSessions(emptyList())
            val answered = assertIs<SessionSyncOutcome.Answered>(outcome).result
            assertTrue(answered.items.isEmpty())
            assertTrue(socket.requestIds().isEmpty(), "an empty batch must not open a round trip")
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `the prompt payload only spells out steer`() {
        // `queue` is the host's default and an older plugin has never heard of the
        // field; spelling it on every ordinary send would gate ordinary prompts on a
        // newer plugin.
        val queued = promptPayload("s1", text = "hi")
        assertFalse(queued.containsKey("mode"))
        assertEquals("s1", queued["sessionId"]?.jsonPrimitive?.content)
        assertEquals("hi", queued["text"]?.jsonPrimitive?.content)

        val steered = promptPayload("s1", text = "stop", mode = PromptMode.STEER)
        assertEquals("steer", steered["mode"]?.jsonPrimitive?.content)
        assertTrue(PromptMode.STEER.isExplicitOnTheWire)
        assertFalse(PromptMode.QUEUE.isExplicitOnTheWire)
        assertEquals(PromptMode.QUEUE, PromptMode.of("queue"))
        assertEquals(PromptMode.STEER, PromptMode.of("steer"))
        assertNull(PromptMode.of("unknown"))
    }

    @Test
    fun `the prompt payload carries attachments and the client time zone`() {
        val payload = promptPayload(
            sessionId = "s1",
            text = null,
            attachmentIds = listOf("a1", "a2"),
            requestId = "req-1",
            clientTimeZone = "Asia/Shanghai",
        )
        assertFalse(payload.containsKey("text"), "an attachment-only prompt may omit text")
        assertEquals(listOf("a1", "a2"), payload["attachmentIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("req-1", payload["requestId"]?.jsonPrimitive?.content)
        assertEquals("Asia/Shanghai", payload["clientTimeZone"]?.jsonPrimitive?.content)
        assertFalse(payload.containsKey("mode"))
    }

    @Test
    fun `the event names are the host's`() {
        assertEquals("session.changed", HostEvents.SESSION_CHANGED)
        assertEquals("relay.changed", HostEvents.RELAY_CHANGED)
    }
}
