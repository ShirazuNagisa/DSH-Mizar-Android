package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import me.shirazu.dshmizar.Phase
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.MizarError
import me.shirazu.mizar.kit.PairedHost
import me.shirazu.mizar.kit.SessionMessagesResult
import me.shirazu.mizar.kit.SessionStatsFrame
import me.shirazu.mizar.kit.SessionStatsResult
import me.shirazu.mizar.kit.WIRE_JSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the open conversation.
 *
 * The failures this domain can have are all silent. A page merged after the socket was
 * replaced puts one computer's transcript under another's title; a read that skipped its
 * `afterSeq` claim opens a cold screen empty; two overlapping reads leave the paging
 * cursors pointing at whichever finished last. None of them throws, and none of them
 * looks wrong on a fast link -- which is exactly where a manual test runs.
 */
class ConversationCoordinatorTest {

    private class Fixture(
        connected: Boolean = true,
        sessionId: String? = "s1",
        phase: Phase = Phase.CONNECTING,
    ) {
        val server = FakeLink()
        var epoch = 1
        val notes = mutableListOf<String>()
        val statsSeen = mutableListOf<SessionStatsFrame?>()
        val pagesCached = mutableListOf<String>()
        val statsCached = mutableListOf<String>()
        val state = MutableStateFlow(
            UiState(
                selectedSessionId = sessionId,
                phase = phase,
                isLocalNetwork = true,
                host = PairedHost(hostId = "h1", hostName = "Mac", deviceId = "d1", deviceToken = "t1"),
            ),
        )
        val coordinator = ConversationCoordinator(
            store = FlowStateStore(state),
            // Unconfined: a call has finished by the time it returns, so a test only has
            // to say what the host answers.
            scope = CoroutineScope(Dispatchers.Unconfined),
            note = { notes += it },
            describe = { error -> "described:${error.message}" },
            epoch = { epoch },
            link = { if (connected) server else null },
            applyStats = { frame ->
                statsSeen += frame
                frame?.let { fresh -> state.value = state.value.copy(stats = fresh) }
            },
            recordPage = { hostId, id, _ -> pagesCached += "$hostId/$id" },
            recordStats = { hostId, id, _ -> statsCached += "$hostId/$id" },
        )

        val ui get() = state.value
    }

    private class FakeLink : ConversationLink {
        val payloads = mutableListOf<JsonObject>()
        val refreshPayloads = mutableListOf<JsonObject>()
        var calls = 0
        var statsCalls = 0
        var refreshCalls = 0
        var answer: SessionMessagesResult = page(said(1, "here"))
        var refreshAnswer: SessionMessagesResult = page()
        var messagesFailure: Throwable? = null
        var statsFailure: Throwable? = null
        var refreshFailure: Throwable? = null
        /** Parks a read until the test lets it go, for the overlapping-read case. */
        var gate: CompletableDeferred<Unit>? = null
        /** Runs inside the read, before the answer: the window a replacement fits in. */
        var duringMessages: (() -> Unit)? = null
        /** The same window, for the one-round-trip path. */
        var duringRefresh: (() -> Unit)? = null

        override suspend fun messages(payload: JsonObject): SessionMessagesResult {
            calls += 1
            payloads += payload
            gate?.await()
            duringMessages?.invoke()
            messagesFailure?.let { throw it }
            return answer
        }

        override suspend fun refresh(payload: JsonObject): RefreshBundle {
            refreshCalls += 1
            refreshPayloads += payload
            duringRefresh?.invoke()
            refreshFailure?.let { throw it }
            return RefreshBundle(messages = refreshAnswer)
        }

        override suspend fun stats(payload: JsonObject): SessionStatsResult {
            statsCalls += 1
            statsFailure?.let { throw it }
            return SessionStatsResult(sessionId = "s1", stats = SessionStatsFrame())
        }
    }

    private companion object {

        fun page(vararg events: String, asOfSeq: Int? = 4, hasMore: Boolean = true): SessionMessagesResult =
            WIRE_JSON.decodeFromJsonElement(
                WIRE_JSON.parseToJsonElement(
                    """
                    {"sessionId":"s1","asOfSeq":${asOfSeq ?: "null"},"hasMore":$hasMore,
                     "records":[${events.joinToString(",")}]}
                    """.trimIndent(),
                ),
            )

        /** One assistant message, wrapped the way the host writes it. */
        fun said(seq: Int, text: String): String =
            """{"type":"event","event":{"type":"assistant/message","seq":$seq,"time":${seq * 1000},
               "data":{"turn":1,"step":1,"message":{"role":"assistant",
               "content":[{"type":"text","text":"$text"}]}}}}"""

        fun nothing(): SessionMessagesResult =
            WIRE_JSON.decodeFromJsonElement(
                WIRE_JSON.parseToJsonElement("""{"sessionId":"s1","records":[]}"""),
            )
    }

    @Test
    fun `a page from a replaced socket is dropped`() {
        val f = Fixture()
        // The answer is in flight when the connection is replaced: the epoch the read
        // started on is no longer the live one by the time it returns.
        f.server.duringMessages = { f.epoch = 2 }

        runBlocking { f.coordinator.readOnce() }

        assertEquals("nothing is merged into the session now on screen", 0, f.ui.conversation.size)
        assertTrue(
            "the drop is logged, because a silently empty screen is unanswerable: ${f.notes}",
            f.notes.any { it.contains("replaced socket") },
        )
    }

    @Test
    fun `a figures failure leaves the transcript's error alone`() {
        val f = Fixture()
        f.server.statsFailure = IllegalStateException("no figures")

        runBlocking { f.coordinator.readOnce() }

        assertNull("the page read succeeded", f.ui.conversationError)
        assertEquals("described:no figures", f.ui.statsError)
        assertTrue("the page still landed", f.ui.conversation.isNotEmpty())
    }

    @Test
    fun `a transcript failure does not clear the figures' error`() {
        val f = Fixture()
        f.state.value = f.ui.copy(statsError = "older figure failure")
        f.server.messagesFailure = IllegalStateException("no page")

        runBlocking { f.coordinator.readOnce() }

        assertEquals("described:no page", f.ui.conversationError)
        assertEquals("a page that failed must not clear the other line", "older figure failure", f.ui.statsError)
    }

    @Test
    fun `a second request during a read runs once the first finishes`() = runBlocking {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.server.gate = gate

        val first = launch(Dispatchers.Unconfined) { f.coordinator.reload() }
        // The read is parked inside the host call, so this one only raises the flag.
        f.coordinator.reload()
        assertEquals("two reads must not overlap", 1, f.server.calls)

        gate.complete(Unit)
        first.join()

        assertEquals("the request made during the read is served after it", 2, f.server.calls)
    }

    @Test
    fun `a host that answers with nothing is read once more`() {
        val f = Fixture(phase = Phase.READY)
        f.server.answer = nothing()

        runBlocking { f.coordinator.reload() }

        assertEquals("a READY host with an empty log gets a second look", 2, f.server.calls)
    }

    @Test
    fun `a host that answers with a page is not read twice`() {
        val f = Fixture(phase = Phase.READY)
        f.server.answer = page(said(1, "here"))

        runBlocking { f.coordinator.reload() }

        assertEquals(1, f.server.calls)
    }

    @Test
    fun `a page read claims emptiness when there is no position yet`() {
        val f = Fixture()

        runBlocking { f.coordinator.readOnce(afterSeq = null) }

        val sent = f.server.payloads.single()
        assertTrue("the key is present: an absent key is silence, not a claim", sent.containsKey("afterSeq"))
        assertEquals(JsonNull, sent["afterSeq"])
    }

    @Test
    fun `a page read sends the position it holds`() {
        val f = Fixture()

        runBlocking { f.coordinator.readOnce(afterSeq = 7) }

        assertEquals(JsonPrimitive(7), f.server.payloads.single()["afterSeq"])
    }

    @Test
    fun `a refresh omits the position instead of claiming emptiness`() {
        val f = Fixture()

        runBlocking { f.coordinator.refreshBundle(afterSeq = null) }

        // The opposite of a page read on purpose: a refresh that claimed "I hold
        // nothing" would make the host resend a page the screen already has.
        assertFalse(
            "a refresh must not carry afterSeq: null",
            f.server.refreshPayloads.single().containsKey("afterSeq"),
        )
    }

    @Test
    fun `a host without session refresh is asked only once`() {
        val f = Fixture()
        f.server.refreshFailure = MizarError("NOT_IMPLEMENTED", "no such method")

        val first = runBlocking { f.coordinator.refreshBundle() }
        val second = runBlocking { f.coordinator.refreshBundle() }

        assertFalse(first)
        assertFalse(second)
        assertEquals("the host is asked once per process, not once per pull", 1, f.server.refreshCalls)
    }

    @Test
    fun `a bundle from a replaced socket is not merged`() {
        val f = Fixture()
        f.server.refreshAnswer = page(said(1, "from the old socket"))
        f.server.duringRefresh = { f.epoch = 2 }

        val merged = runBlocking { f.coordinator.refreshBundle() }

        assertFalse("the caller must read again on the new connection", merged)
        assertEquals(0, f.ui.conversation.size)
    }

    @Test
    fun `an older page that does not move the cut means the top`() {
        val f = Fixture()
        f.state.value = f.ui.copy(oldestSeq = 5, hasMore = true)
        // The host answers with the same cut it was asked from: nothing above this.
        f.server.answer = page(said(5, "same cut"))

        val added = runBlocking { f.coordinator.loadOlder() }

        assertEquals(0, added)
        assertTrue(f.ui.exhausted)
        assertFalse("there is nothing more to ask for", f.ui.hasMore)
    }

    @Test
    fun `an older page that cannot be read is not the top`() {
        val f = Fixture()
        f.state.value = f.ui.copy(oldestSeq = 5, hasMore = true)
        f.server.messagesFailure = IllegalStateException("gone")

        val added = runBlocking { f.coordinator.loadOlder() }

        assertNull("null is 'could not ask', which must never read as 'the top'", added)
        assertFalse("an unreadable page does not exhaust the log", f.ui.exhausted)
        assertTrue(f.ui.hasMore)
    }

    @Test
    fun `there is nothing older is not asked for`() {
        val f = Fixture()
        f.state.value = f.ui.copy(oldestSeq = 5, hasMore = false)

        val added = runBlocking { f.coordinator.loadOlder() }

        assertEquals(0, added)
        assertEquals("the host is not asked when the last page already said so", 0, f.server.calls)
        assertTrue(f.ui.exhausted)
    }

    @Test
    fun `an older page moves the cut down and keeps the lines`() {
        val f = Fixture()
        f.state.value = f.ui.copy(oldestSeq = 9, hasMore = true, conversation = emptyList())
        f.server.answer = page(said(3, "older"), said(4, "older still"), asOfSeq = 4)

        val added = runBlocking { f.coordinator.loadOlder() }

        assertTrue("the merge added lines", (added ?: 0) > 0)
        assertEquals(3, f.ui.oldestSeq ?: -1)
    }

    @Test
    fun `turn detail does not move the paging cursors`() {
        val f = Fixture()
        f.state.value = f.ui.copy(oldestSeq = 10, newestSeq = 20, hasMore = true)
        f.server.answer = page(said(3, "one turn's narration"), asOfSeq = 3, hasMore = false)

        f.coordinator.loadTurnDetail(1)

        assertEquals("a detail page must not drag the top of the log down", 10, f.ui.oldestSeq ?: -1)
        assertEquals("nor move where the next refresh asks from", 20, f.ui.newestSeq ?: -1)
        assertTrue("but its records are merged", f.ui.conversation.isNotEmpty())
    }

    @Test
    fun `a cached page goes up before the first read`() {
        val f = Fixture()
        f.state.value = f.ui.copy(conversationError = "the last read failed")

        f.coordinator.show(page(said(1, "what was on screen last time")), forSession = "s1")

        assertTrue(f.ui.conversation.isNotEmpty())
        assertNull("a page that arrived is no longer an error", f.ui.conversationError)
    }

    @Test
    fun `the page is cached under the session it was read for`() {
        val f = Fixture()

        runBlocking { f.coordinator.readOnce() }

        assertEquals(listOf("h1/s1"), f.pagesCached)
    }

    /**
     * A page is folded into the session it was read for, or nowhere.
     *
     * The epoch covers a socket that was replaced; it says nothing about the operator
     * opening another conversation while this read was in flight. Without the guard the
     * old session's transcript was merged into the new one -- one conversation's lines
     * under another session's title, with no error and nothing on screen to suggest it.
     */
    @Test
    fun `a page for a session that is no longer open is discarded`() {
        val f = Fixture()
        // The read starts on s1 and the operator moves to s2 while it is in flight.
        f.server.duringMessages = { f.state.value = f.state.value.copy(selectedSessionId = "s2") }

        runBlocking { f.coordinator.readOnce() }

        assertEquals("nothing from s1 may land under s2", 0, f.ui.conversation.size)
        assertTrue(
            "and the drop is in the log: ${f.notes}",
            f.notes.any { it.contains("discarding a page for s1") },
        )
    }

    @Test
    fun `an older page does not refresh the figures`() {
        val f = Fixture()
        f.state.value = f.ui.copy(oldestSeq = 9, hasMore = true)
        f.server.answer = page(said(3, "older"), asOfSeq = 4)

        runBlocking { f.coordinator.loadOlder() }

        assertEquals("the page was read", 1, f.server.calls)
        assertEquals("an older page must not spend a second round trip on figures", 0, f.server.statsCalls)
    }
}
