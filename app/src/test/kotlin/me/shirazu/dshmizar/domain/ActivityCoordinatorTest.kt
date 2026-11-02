package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.ActivityEntry
import me.shirazu.mizar.kit.SessionActivityResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The running turn's activity panel.
 *
 * Two things here are invisible until they hurt: a poll that keeps running after the
 * panel is closed (a request a second against a host the operator is not watching),
 * and a paging merge that duplicates or loses lines at the seam. The first is about
 * lifetime, the second about ordering, and neither shows up as an error.
 */
class ActivityCoordinatorTest {

    private class Fixture(
        unlocked: Boolean = true,
        connected: Boolean = true,
        sessionId: String? = "s1",
    ) {
        val server = FakeLink()
        val state = MutableStateFlow(
            UiState(
                selectedSessionId = sessionId,
                isLocalNetwork = unlocked,
            ),
        )
        val coordinator = ActivityCoordinator(
            store = FlowStateStore(state),
            // Unconfined on both: a call has finished by the time it returns, and the
            // poll parks on its first delay instead of spinning.
            scope = CoroutineScope(Dispatchers.Unconfined),
            pollDispatcher = Dispatchers.Unconfined,
            describe = { error -> "described:${error.message}" },
            link = { if (connected) server else null },
        )

        val ui get() = state.value
    }

    private class FakeLink : ActivityLink {
        val calls = mutableListOf<String>()
        var answer = SessionActivityResult(sessionId = "s1", turn = 4, entries = listOf(entry("a")))
        var failure: Throwable? = null
        var olderAnswer: SessionActivityResult? = null
        var lastBeforeSeq: Int? = null
        var lastTurn: Int? = null
        var lastMax: Int? = null

        override suspend fun page(
            sessionId: String,
            maxEntries: Int,
            beforeSeq: Int?,
            turn: Int?,
        ): SessionActivityResult {
            calls += if (beforeSeq == null) "page" else "older"
            lastMax = maxEntries
            lastBeforeSeq = beforeSeq
            lastTurn = turn
            failure?.let { throw it }
            return olderAnswer ?: answer
        }
    }

    @Test
    fun `opening the detail reads the first page at once`() {
        val f = Fixture()

        f.coordinator.toggle()

        assertTrue(f.ui.activityExpanded)
        assertEquals(listOf("a"), f.ui.activity?.entries?.map { it.id })
        assertFalse("the spinner must stop once a page is in", f.ui.activityLoading)
        assertNull(f.ui.activityError)
        assertEquals(60, f.server.lastMax)
        f.coordinator.cancel()
    }

    @Test
    fun `a free account on a relay never asks the host`() {
        val f = Fixture(unlocked = false)

        f.coordinator.toggle()

        assertFalse(f.ui.activityExpanded)
        assertTrue("the host must not be asked at all", f.server.calls.isEmpty())
    }

    @Test
    fun `closing the detail stops the poll`() {
        val f = Fixture()

        f.coordinator.toggle()
        f.coordinator.toggle()

        assertFalse(f.ui.activityExpanded)
        // Nothing more may be asked for after the close: the poll is cancelled, not
        // merely told to look at a flag on its next tick.
        val asked = f.server.calls.size
        f.coordinator.cancel()
        assertEquals(asked, f.server.calls.size)
    }

    @Test
    fun `a failed read says why and stops the spinner`() {
        val f = Fixture()
        f.server.failure = IllegalStateException("socket died")

        f.coordinator.toggle()

        assertEquals("described:socket died", f.ui.activityError)
        assertFalse(f.ui.activityLoading)
        f.coordinator.cancel()
    }

    @Test
    fun `nothing is polled without a connection or a session`() {
        val offline = Fixture(connected = false)
        offline.coordinator.toggle()
        assertTrue(offline.server.calls.isEmpty())

        val noSession = Fixture(sessionId = null)
        noSession.coordinator.toggle()
        assertTrue(noSession.server.calls.isEmpty())
    }

    @Test
    fun `the older page is merged at the seam without duplicating a line`() {
        val f = Fixture()
        f.state.value = f.ui.copy(
            activity = SessionActivityResult(
                sessionId = "s1",
                turn = 4,
                entries = listOf(entry("b"), entry("c")),
                hasMore = true,
                oldestSeq = 30.0,
            ),
        )
        f.server.olderAnswer = SessionActivityResult(
            sessionId = "s1",
            turn = 4,
            entries = listOf(entry("a"), entry("b")),
            hasMore = false,
            oldestSeq = 10.0,
        )

        f.coordinator.loadOlder()

        assertEquals(listOf("a", "b", "c"), f.ui.activity?.entries?.map { it.id })
        assertEquals(30, f.server.lastBeforeSeq)
        assertEquals(4, f.server.lastTurn)
        assertFalse(f.ui.activityLoadingOlder)
        assertFalse("the oldest page must not claim more", f.ui.activity?.hasMore ?: true)
    }

    @Test
    fun `an older page is not asked for when there is none`() {
        val f = Fixture()
        f.state.value = f.ui.copy(
            activity = SessionActivityResult(
                sessionId = "s1",
                turn = 4,
                entries = listOf(entry("a")),
                hasMore = false,
                oldestSeq = 10.0,
            ),
        )

        f.coordinator.loadOlder()

        assertTrue(f.server.calls.isEmpty())
    }

    @Test
    fun `a failed older page says why and stops the spinner`() {
        val f = Fixture()
        f.state.value = f.ui.copy(
            activity = SessionActivityResult(
                sessionId = "s1",
                turn = 4,
                entries = listOf(entry("b")),
                hasMore = true,
                oldestSeq = 30.0,
            ),
        )
        f.server.failure = IllegalStateException("no more")

        f.coordinator.loadOlder()

        assertEquals("described:no more", f.ui.activityError)
        assertFalse(f.ui.activityLoadingOlder)
        assertNotNull("the lines already read stay", f.ui.activity?.entries)
    }

    private companion object {
        fun entry(id: String) = ActivityEntry(id = id, seq = 1.0, kind = "tool", title = id)
    }
}
