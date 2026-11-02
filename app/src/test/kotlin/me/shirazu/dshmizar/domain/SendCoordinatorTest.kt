package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.shirazu.dshmizar.DraftAttachment
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.AcceptanceResult
import me.shirazu.mizar.kit.ConversationItem
import me.shirazu.mizar.kit.PromptResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sending a prompt, and stopping a turn.
 *
 * Everything here happens in the two seconds after a tap, and every failure looks the
 * same from the outside: the message does not appear. The difference is whether the
 * host never got it (a refused attachment), got it and dropped it (a reused request
 * id), or got it and the phone read the log too early -- three causes that need three
 * different answers, and only one of which is worth retrying.
 */
class SendCoordinatorTest {

    private class Fixture(
        sessionId: String? = "s1",
        connected: Boolean = true,
    ) {
        val server = FakeLink()
        /** The send's own coroutine: a test joins it instead of guessing at timing. */
        private val job = Job()
        val state = MutableStateFlow(UiState(selectedSessionId = sessionId, isLocalNetwork = true))
        var clears = 0
        var hides = 0
        var reloads = 0
        var reads = 0

        /** What a read puts into the transcript, given which read it is. */
        var onRead: ((Int) -> Unit)? = null

        val coordinator = SendCoordinator(
            store = FlowStateStore(state),
            // Unconfined: a send has finished by the time it returns, so a test only has
            // to say what the host answers.
            scope = CoroutineScope(Dispatchers.Unconfined + job),
            strings = MarkerText(),
            describe = { error -> "described:${error.message}" },
            link = { if (connected) server else null },
            reload = { reloads += 1 },
            readOnce = {
                reads += 1
                onRead?.invoke(reads)
            },
            clearAttachments = { clears += 1 },
            hideCard = { hides += 1 },
        )

        val ui get() = state.value

        /** Run the send to completion, including the wait for the log to catch up. */
        fun settle() = runBlocking { job.children.toList().forEach { it.join() } }
    }

    private class MarkerText : Text {
        override fun get(id: Int): String = "res:$id"
        override fun get(id: Int, vararg args: Any): String = "res:$id:" + args.joinToString(",")
    }

    private class FakeLink : SendLink {
        val prompts = mutableListOf<JsonObject>()
        val cancels = mutableListOf<JsonObject>()
        var answer = PromptResult(accepted = true)
        var cancelFailure: Throwable? = null
        var promptFailure: Throwable? = null

        override suspend fun prompt(payload: JsonObject): PromptResult {
            prompts += payload
            promptFailure?.let { throw it }
            return answer
        }

        override suspend fun cancel(payload: JsonObject): AcceptanceResult {
            cancels += payload
            cancelFailure?.let { throw it }
            return AcceptanceResult(accepted = true)
        }
    }

    private companion object {
        fun draft(
            key: String,
            readyId: String? = "up-$key",
            error: String? = null,
        ): DraftAttachment = DraftAttachment(
            key = key,
            name = "$key.png",
            bytes = 10,
            kind = "image",
            mediaType = "image/png",
            file = File("staging/$key.png"),
            readyId = readyId,
            error = error,
        )

        fun userMessage(text: String): ConversationItem.Message =
            ConversationItem.Message("seq-1", ConversationItem.Role.USER, text, 1_000L)
    }

    @Test
    fun `nothing is sent when the composer is empty`() {
        val f = Fixture()

        f.coordinator.send("   ")
        f.settle()

        assertEquals(0, f.server.prompts.size)
        assertNull(f.ui.problem)
    }

    @Test
    fun `a message that is still uploading is refused before it is sent`() {
        val f = Fixture()
        f.state.value = f.ui.copy(attachments = listOf(draft("a", readyId = null)))

        f.coordinator.send("hello")
        f.settle()

        assertEquals("the host must not receive a prompt whose file it cannot open", 0, f.server.prompts.size)
        assertTrue("the operator is told why: ${f.ui.problem}", f.ui.problem!!.startsWith("res:"))
    }

    @Test
    fun `a failed attachment is refused with its own words`() {
        val f = Fixture()
        f.state.value = f.ui.copy(attachments = listOf(draft("a", readyId = null, error = "too big")))

        f.coordinator.send("hello")
        f.settle()

        assertEquals(0, f.server.prompts.size)
        assertEquals("too big", f.ui.problem)
    }

    @Test
    fun `a plain message goes with its text and no attachments`() {
        val f = Fixture()

        f.coordinator.send("hello")
        f.settle()

        val sent = f.server.prompts.single()
        assertEquals(JsonPrimitive("hello"), sent["text"])
        assertEquals(JsonPrimitive("s1"), sent["sessionId"])
        assertFalse("no attachmentIds key at all, not an empty array", sent.containsKey("attachmentIds"))
    }

    @Test
    fun `an attachment-only message still carries an empty text`() {
        val f = Fixture()
        f.state.value = f.ui.copy(attachments = listOf(draft("a")))

        f.coordinator.send("")
        f.settle()

        val sent = f.server.prompts.single()
        assertEquals("an omitted text is a malformed frame, not 'no text'", JsonPrimitive(""), sent["text"])
        assertEquals(JsonArray(listOf(JsonPrimitive("up-a"))), sent["attachmentIds"])
    }

    @Test
    fun `every submission gets its own request id`() {
        val f = Fixture()

        f.coordinator.send("one")
        f.settle()
        f.coordinator.send("one")

        val ids = f.server.prompts.map { it["requestId"].toString() }
        assertEquals(2, ids.size)
        assertNotEquals("a reused id makes the second message look like a retry", ids[0], ids[1])
    }

    @Test
    fun `a refused prompt keeps the staged files and says so`() {
        val f = Fixture()
        f.server.answer = PromptResult(accepted = false)

        f.coordinator.send("hello")
        f.settle()

        assertEquals("the host did not accept the message", f.ui.problem)
        assertEquals("the files are still ours to retry with", 0, f.clears)
        assertEquals("and there is nothing new to read", 0, f.reloads)
    }

    @Test
    fun `an accepted prompt hands the files over and reads until the message appears`() {
        val f = Fixture()
        // The first read misses it -- the log is written asynchronously -- and the
        // second one has it.
        f.onRead = { attempt ->
            if (attempt >= 2) f.state.value = f.ui.copy(conversation = listOf(userMessage("hello")))
        }

        f.coordinator.send("hello")
        f.settle()

        assertEquals(1, f.clears)
        assertEquals("the read that found it ends the wait", 2, f.reads)
        assertEquals("then the screen is reloaded properly", 1, f.reloads)
    }

    @Test
    fun `the wait stops at the first read that sees the message`() {
        val f = Fixture()
        f.onRead = { f.state.value = f.ui.copy(conversation = listOf(userMessage("hello"))) }

        f.coordinator.send("hello")
        f.settle()

        assertEquals("a host that wrote it at once costs one read, not eight", 1, f.reads)
    }

    @Test
    fun `an attachment-only prompt does not wait for a message`() {
        val f = Fixture()
        f.state.value = f.ui.copy(attachments = listOf(draft("a")))

        f.coordinator.send("")
        f.settle()

        assertEquals("there is no text to look for", 0, f.reads)
        assertEquals("but the screen is still reloaded", 1, f.reloads)
    }

    @Test
    fun `a failed send says why and reads nothing`() {
        val f = Fixture()
        f.server.promptFailure = IllegalStateException("socket gone")

        f.coordinator.send("hello")
        f.settle()

        assertEquals("described:socket gone", f.ui.problem)
        assertEquals(0, f.reads)
        assertEquals(0, f.reloads)
        assertEquals("the files are still ours", 0, f.clears)
    }

    @Test
    fun `sending without a connection does nothing`() {
        val f = Fixture(connected = false)

        f.coordinator.send("hello")
        f.settle()

        assertEquals(0, f.server.prompts.size)
        assertNull(f.ui.problem)
    }

    @Test
    fun `cancelling another session does not read this one again`() {
        val f = Fixture(sessionId = "s1")

        f.coordinator.cancel("s2")
        f.settle()

        assertEquals("the stop still goes out", JsonPrimitive("s2"), f.server.cancels.single()["sessionId"])
        assertEquals("but s1 is not the session that changed", 0, f.reloads)
        assertEquals(1, f.hides)
    }

    @Test
    fun `cancelling the open session reads it again`() {
        val f = Fixture(sessionId = "s1")

        f.coordinator.cancel("s1")
        f.settle()

        assertEquals(1, f.reloads)
    }

    @Test
    fun `a stop takes the card down even when the host refuses`() {
        val f = Fixture(sessionId = "s1")
        f.server.cancelFailure = IllegalStateException("turn already over")

        f.coordinator.cancel("s1")
        f.settle()

        assertEquals("the card is about the stop, not about the acknowledgement", 1, f.hides)
        assertEquals("the screen is still read again", 1, f.reloads)
    }

    @Test
    fun `stopping without a connection leaves the card alone`() {
        val f = Fixture(connected = false)

        f.coordinator.cancel("s1")
        f.settle()

        assertEquals(0, f.hides)
        assertEquals(0, f.reloads)
    }

    @Test
    fun `stopping with no session open does nothing`() {
        val f = Fixture(sessionId = null)

        f.coordinator.cancelTurn()
        f.settle()

        assertEquals(0, f.server.cancels.size)
        assertEquals(0, f.hides)
    }
}
