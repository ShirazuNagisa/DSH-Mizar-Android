package me.shirazu.dshmizar.domain

import kotlinx.coroutines.flow.MutableStateFlow
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.ConversationTurn
import me.shirazu.mizar.kit.SessionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock screen's one card.
 *
 * Three things about it are invisible in a screenshot: which session wins when two are
 * running, how often the card is rewritten, and where its chronometer starts. The last
 * one is the subtle one -- the host says a turn is running, not when it started, so the
 * card either borrows the host's own number or counts from the first moment this phone
 * could see it.
 */
class LiveTurnCoordinatorTest {

    private class Fixture(selected: String? = null) {
        var now = 1_000L
        val shown = mutableListOf<String>()
        var hides = 0
        val state = MutableStateFlow(
            UiState(
                selectedSessionId = selected,
                titles = mapOf("s1" to "Mizar", "s2" to "Other"),
            ),
        )
        val coordinator = LiveTurnCoordinator(
            store = FlowStateStore(state),
            now = { now },
            showCard = { sessionId, title, since -> shown += "$sessionId|$title|$since" },
            hideCard = { hides += 1 },
        )
    }

    private fun row(id: String, running: Boolean, updatedAt: Double) = SessionRow(
        sessionId = id,
        updatedAt = updatedAt,
        running = running,
        blank = false,
        agentAvailable = true,
    )

    @Test
    fun `the card names the session that changed most recently`() {
        val f = Fixture()

        f.coordinator.sync(listOf(row("s1", true, 1.0), row("s2", true, 5.0)))

        assertEquals("s2|Other|1000", f.shown.single())
    }

    @Test
    fun `the card comes down when nothing is running`() {
        val f = Fixture()
        f.coordinator.sync(listOf(row("s1", true, 1.0)))

        f.coordinator.sync(listOf(row("s1", false, 1.0)))

        assertEquals(1, f.hides)
        assertEquals(1, f.shown.size)
    }

    @Test
    fun `the card is only rewritten when it would read differently`() {
        val f = Fixture()
        val rows = listOf(row("s1", true, 1.0))

        f.coordinator.sync(rows)
        f.coordinator.sync(rows)
        f.coordinator.sync(rows)

        assertEquals("a status surface that reposts every poll is noise", 1, f.shown.size)
    }

    @Test
    fun `the open conversation's own start time is used when it is the one running`() {
        val f = Fixture(selected = "s1")
        f.state.value = f.state.value.copy(
            turns = listOf(
                ConversationTurn(
                    turn = 1,
                    prompts = emptyList(),
                    steps = emptyList(),
                    startedAtMillis = 42L,
                    endedAtMillis = null,
                    endedReason = null,
                ),
            ),
        )

        f.coordinator.sync(listOf(row("s1", true, 1.0)))

        assertEquals("the host's number beats this phone's observation", "s1|Mizar|42", f.shown.single())
    }

    @Test
    fun `a start time observed once is kept, not restarted by the next poll`() {
        val f = Fixture()
        f.coordinator.sync(listOf(row("s1", true, 1.0)))

        f.now = 9_000L
        f.coordinator.sync(listOf(row("s1", true, 1.0)))

        assertEquals("a chronometer that restarts every second is worse than none", 1, f.shown.size)
        assertTrue(f.shown.single().endsWith("|1000"))
    }

    @Test
    fun `a settle takes the card down only if the card is about it`() {
        val f = Fixture()
        f.coordinator.sync(listOf(row("s1", true, 1.0)))

        f.coordinator.forget("s2")
        assertEquals("another turn is still running", 0, f.hides)

        f.coordinator.forget("s1")
        assertEquals(1, f.hides)
    }

    @Test
    fun `hiding takes the card down whatever it was about`() {
        val f = Fixture()

        f.coordinator.hide()

        assertEquals("a cancelled turn must not wait for the host to admit it", 1, f.hides)
    }
}
