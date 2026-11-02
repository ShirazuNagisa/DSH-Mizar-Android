package me.shirazu.dshmizar.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.PairedHost
import me.shirazu.mizar.kit.PendingApproval
import me.shirazu.mizar.kit.PendingInteractions
import me.shirazu.mizar.kit.PendingQuestion
import me.shirazu.mizar.kit.SessionMessagesResult
import me.shirazu.mizar.kit.SessionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When this phone is allowed to buzz.
 *
 * Every rule here exists because breaking it produced a *missing* notice rather than
 * a wrong one, which is the hardest kind to notice: a turn that ends while the phone
 * is away is exactly the moment nobody is watching the screen to see that nothing
 * arrived. The baseline that survives a frozen process, the once-per-turn claim, the
 * quiet window that never silences a blocked agent -- all of it is pinned here.
 */
class NotificationCoordinatorTest {

    private class Fixture(
        foreground: Boolean = false,
        quiet: Boolean = false,
        hour: Int = 14,
    ) {
        var foreground = foreground
        var quiet = quiet
        var hour = hour
        var accepted = true
        var tail: SessionMessagesResult? = null
        val baseline = FakeBaseline()
        val posted = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val state = MutableStateFlow(
            UiState(
                host = PairedHost(
                    hostId = "h1",
                    hostName = "studio",
                    deviceId = "d1",
                    deviceToken = "t1",
                ),
                titles = mapOf("s1" to "Mizar"),
            ),
        )
        val coordinator = NotificationCoordinator(
            store = FlowStateStore(state),
            baseline = baseline,
            text = MarkerText(),
            note = { notes += it },
            quietHours = { quiet },
            hourOfDay = { hour },
            isForeground = { foreground },
            tail = { tail },
            postNotice = { title, body, sessionId, finished ->
                posted += "$title|$body|$sessionId|$finished"
                accepted
            },
        )
    }

    private class MarkerText : Text {
        override fun get(id: Int): String = "res:$id"
        override fun get(id: Int, vararg args: Any): String =
            "res:$id:" + args.joinToString(",")
    }

    private class FakeBaseline : RunningBaseline {
        private val running = mutableMapOf<String, Boolean>()

        override fun runningSession(hostId: String, sessionId: String): Boolean =
            running["$hostId/$sessionId"] ?: false

        override fun setRunningSession(hostId: String, sessionId: String, running: Boolean) {
            this.running["$hostId/$sessionId"] = running
        }
    }

    private fun row(running: Boolean, id: String = "s1") = SessionRow(
        sessionId = id,
        updatedAt = 0.0,
        running = running,
        blank = false,
        agentAvailable = true,
    )

    @Test
    fun `a turn that stops running is announced once`() {
        val f = Fixture()

        runBlocking {
            f.coordinator.announceFinished(listOf(row(running = true)))
            f.coordinator.announceFinished(listOf(row(running = false)))
            f.coordinator.announceFinished(listOf(row(running = false)))
        }

        assertEquals("a second read of the same idle row must not buzz again", 1, f.posted.size)
        assertTrue("the notice names the session's title", f.posted.single().contains("Mizar"))
        assertTrue("a completion uses its own id range", f.posted.single().endsWith("|true"))
    }

    @Test
    fun `a first sighting of an idle session says nothing`() {
        val f = Fixture()

        runBlocking { f.coordinator.announceFinished(listOf(row(running = false))) }

        assertTrue("there was no transition to announce", f.posted.isEmpty())
        assertTrue(f.notes.isEmpty())
    }

    @Test
    fun `a session that was running before the process died is still announced`() {
        val f = Fixture()
        // The store remembers what memory lost: the phone was frozen mid-turn.
        f.baseline.setRunningSession("h1", "s1", true)

        runBlocking { f.coordinator.announceFinished(listOf(row(running = false))) }

        assertEquals(1, f.posted.size)
    }

    @Test
    fun `nothing is announced while the screen is on`() {
        val f = Fixture(foreground = true)

        runBlocking {
            f.coordinator.announceFinished(listOf(row(running = true)))
            f.coordinator.announceFinished(listOf(row(running = false)))
        }

        assertTrue(f.posted.isEmpty())
        assertTrue(f.notes.any { it.contains("on screen") })
    }

    @Test
    fun `quiet hours silence a finished turn`() {
        val f = Fixture(quiet = true, hour = 2)

        runBlocking {
            f.coordinator.announceFinished(listOf(row(running = true)))
            f.coordinator.announceFinished(listOf(row(running = false)))
        }

        assertTrue(f.posted.isEmpty())
        assertTrue(f.notes.any { it.contains("quiet hours") })
    }

    @Test
    fun `the same hour with quiet hours off still announces`() {
        val f = Fixture(quiet = false, hour = 2)

        runBlocking {
            f.coordinator.announceFinished(listOf(row(running = true)))
            f.coordinator.announceFinished(listOf(row(running = false)))
        }

        assertEquals(1, f.posted.size)
    }

    @Test
    fun `quiet hours never silence a blocked agent`() {
        val f = Fixture(quiet = true, hour = 2)

        f.coordinator.announcePending(
            PendingInteractions(approvals = listOf(PendingApproval(requestId = "r1", toolName = "shell"))),
        )

        assertEquals("a blocked agent is not a notice the app may defer", 1, f.posted.size)
        assertTrue("a pending notice is not a finished one", f.posted.single().endsWith("|false"))
    }

    @Test
    fun `a turned-off category says nothing, and says so in the log`() {
        val f = Fixture()
        f.state.value = f.state.value.copy(notifyCompleted = false)

        runBlocking {
            f.coordinator.announceFinished(listOf(row(running = true)))
            f.coordinator.announceFinished(listOf(row(running = false)))
        }

        assertTrue(f.posted.isEmpty())
        assertTrue(f.notes.any { it.contains("notice category off") })
    }

    @Test
    fun `a batch buzzes once, and again when it changes`() {
        val f = Fixture()
        val first = PendingInteractions(approvals = listOf(PendingApproval(requestId = "r1", toolName = "shell")))

        f.coordinator.announcePending(first)
        f.coordinator.announcePending(first)
        assertEquals("the same batch must not buzz twice", 1, f.posted.size)

        f.coordinator.announcePending(
            PendingInteractions(approvals = listOf(PendingApproval(requestId = "r2", toolName = "shell"))),
        )
        assertEquals(2, f.posted.size)
    }

    @Test
    fun `an empty batch clears the memory, so the next one buzzes again`() {
        val f = Fixture()
        val batch = PendingInteractions(questions = listOf(PendingQuestion(callId = "c1")))

        f.coordinator.announcePending(batch)
        f.coordinator.announcePending(PendingInteractions())
        f.coordinator.announcePending(batch)

        assertEquals("the same cards answered and asked again are news", 2, f.posted.size)
    }

    @Test
    fun `a batch with every category off says nothing`() {
        val f = Fixture()
        f.state.value = f.state.value.copy(notifyApprovals = false, notifyQuestions = false)

        f.coordinator.announcePending(
            PendingInteractions(approvals = listOf(PendingApproval(requestId = "r1", toolName = "shell"))),
        )

        assertTrue(f.posted.isEmpty())
        assertTrue(f.notes.any { it.contains("notice categories off") })
    }

    @Test
    fun `a batch says nothing while the screen is on`() {
        val f = Fixture(foreground = true)

        f.coordinator.announcePending(
            PendingInteractions(approvals = listOf(PendingApproval(requestId = "r1", toolName = "shell"))),
        )

        assertTrue(f.posted.isEmpty())
    }

    /**
     * The settled path, not the list path, because the list path forgets a session the
     * moment it is seen running again -- which masks whether a refused notice was
     * recorded. An event has no such second chance, so it is the honest place to ask.
     */
    @Test
    fun `a refused notice is not claimed, so the event may try again`() {
        val f = Fixture()
        f.accepted = false

        runBlocking {
            f.coordinator.announceSettled("s1")
            f.coordinator.announceSettled("s1")
        }

        assertEquals("both attempts must reach the system", 2, f.posted.size)
        assertFalse(f.notes.any { it.contains("posted=true") })
    }

    @Test
    fun `an accepted notice is claimed, so the event does not repeat it`() {
        val f = Fixture()

        runBlocking {
            f.coordinator.announceSettled("s1")
            f.coordinator.announceSettled("s1")
        }

        assertEquals("one turn, one notice", 1, f.posted.size)
        assertTrue(f.notes.any { it.contains("posted=true") })
    }
}
