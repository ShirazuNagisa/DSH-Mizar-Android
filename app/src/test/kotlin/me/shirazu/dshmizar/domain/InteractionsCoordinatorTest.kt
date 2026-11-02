package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.AcceptanceResult
import me.shirazu.mizar.kit.ApprovalOutcome
import me.shirazu.mizar.kit.PendingApproval
import me.shirazu.mizar.kit.PendingInteractions
import me.shirazu.mizar.kit.QuestionAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cards waiting for an answer.
 *
 * The poll's *silence* is the signal here, which is why one lost read is retried
 * before the app admits it cannot see: an unseen card is an agent blocked for as long
 * as nobody notices. The other half is that answering always re-reads -- the card may
 * have been answered from the computer a second earlier, and a screen showing a
 * question that no longer exists is worse than one showing nothing.
 */
class InteractionsCoordinatorTest {

    private class Fixture(connected: Boolean = true) {
        val server = FakeLink()
        val announced = mutableListOf<PendingInteractions>()
        val state = MutableStateFlow(UiState())
        val coordinator = InteractionsCoordinator(
            store = FlowStateStore(state),
            // Unconfined: a call has finished by the time it returns.
            scope = CoroutineScope(Dispatchers.Unconfined),
            describe = { error -> "described:${error.message}" },
            announcePending = { announced += it },
            link = { if (connected) server else null },
        )
    }

    private class FakeLink : InteractionsLink {
        val calls = mutableListOf<String>()
        var pendingAnswer = PendingInteractions()
        /** How many of the next [pending] calls fail, as a silent socket would. */
        var silentPolls = 0
        var answerFailure: Throwable? = null
        var approvalPayload: JsonObject? = null
        var questionsPayload: JsonObject? = null

        override suspend fun pending(): PendingInteractions {
            calls += "pending"
            if (silentPolls > 0) {
                silentPolls -= 1
                throw IllegalStateException("no answer")
            }
            return pendingAnswer
        }

        override suspend fun answerApproval(payload: JsonObject): AcceptanceResult {
            calls += "approval.answer"
            approvalPayload = payload
            answerFailure?.let { throw it }
            return AcceptanceResult(accepted = true)
        }

        override suspend fun answerQuestions(payload: JsonObject): AcceptanceResult {
            calls += "question.answer"
            questionsPayload = payload
            answerFailure?.let { throw it }
            return AcceptanceResult(accepted = true)
        }
    }

    private val batch = PendingInteractions(
        approvals = listOf(PendingApproval(requestId = "r1", toolName = "shell")),
    )

    @Test
    fun `a batch of cards is shown as soon as it arrives`() {
        val f = Fixture()
        f.server.pendingAnswer = batch

        val answered = runBlocking { f.coordinator.refreshNow() }

        assertTrue(answered)
        assertEquals(batch, f.state.value.pending)
        assertEquals("the notice decision is the notification domain's", 1, f.announced.size)
    }

    @Test
    fun `one silent poll is retried before the app gives up`() {
        val f = Fixture()
        f.server.silentPolls = 1
        f.server.pendingAnswer = batch

        val answered = runBlocking { f.coordinator.refreshNow() }

        assertTrue("the host did answer, on the second ask", answered)
        assertEquals(listOf("pending", "pending"), f.server.calls)
        assertEquals(batch, f.state.value.pending)
    }

    @Test
    fun `two silent polls mean the host is not there`() {
        val f = Fixture()
        f.server.silentPolls = 2
        f.server.pendingAnswer = batch

        val answered = runBlocking { f.coordinator.refreshNow() }

        assertFalse("silence twice is a verdict", answered)
        assertNull("what was on screen stays", f.state.value.pending)
        assertTrue("nothing to announce", f.announced.isEmpty())
    }

    @Test
    fun `no connection is not a verdict`() {
        val f = Fixture(connected = false)

        val answered = runBlocking { f.coordinator.refreshNow() }

        assertTrue("a reconnect already owns that case", answered)
        assertTrue(f.server.calls.isEmpty())
    }

    @Test
    fun `answering an approval sends it, then re-reads`() {
        val f = Fixture()
        f.server.pendingAnswer = PendingInteractions()

        f.coordinator.answerApproval("r1", ApprovalOutcome.ALLOWED_ONCE)

        assertEquals(listOf("approval.answer", "pending"), f.server.calls)
        val payload = f.server.approvalPayload ?: error("no payload")
        assertEquals(JsonPrimitive("r1"), payload["requestId"])
        assertEquals(JsonPrimitive("allowed-once"), payload["outcome"])
        assertEquals(PendingInteractions(), f.state.value.pending)
    }

    @Test
    fun `a refused answer says why and still re-reads`() {
        val f = Fixture()
        f.server.answerFailure = IllegalStateException("already answered")

        f.coordinator.answerApproval("r1", ApprovalOutcome.REJECTED)

        assertEquals("described:already answered", f.state.value.problem)
        assertTrue("the card may be gone; the screen must find out", "pending" in f.server.calls)
    }

    @Test
    fun `answering questions sends every answer, and the typed text only when there is one`() {
        val f = Fixture()

        f.coordinator.answerQuestions(
            "c1",
            listOf(
                QuestionAnswer(id = "q1", selected = listOf("a", "b")),
                QuestionAnswer(id = "q2", custom = "my own"),
            ),
        )

        assertEquals(listOf("question.answer", "pending"), f.server.calls)
        val payload = f.server.questionsPayload ?: error("no payload")
        assertEquals(JsonPrimitive("c1"), payload["callId"])
        val answers = payload["answers"]!!.jsonArray
        assertEquals(2, answers.size)
        val first = answers[0] as JsonObject
        assertEquals(JsonPrimitive("q1"), first["id"])
        assertEquals(
            listOf("a", "b"),
            first["selected"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertNull("a question answered by tapping has no text", first["custom"])
        assertEquals(JsonPrimitive("my own"), (answers[1] as JsonObject)["custom"])
    }
}
