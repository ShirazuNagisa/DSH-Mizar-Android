package me.shirazu.dshmizar.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shirazu.mizar.kit.account.Announcement
import me.shirazu.mizar.kit.account.AnnouncementButton
import me.shirazu.mizar.kit.account.AnnouncementEvent
import me.shirazu.mizar.kit.account.AnnouncementTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's side of the operator's banners, without a socket.
 *
 * What is pinned here is the rule the server promised the phone would enforce: a
 * banner is shown once per device, the newest unshown one wins a snapshot, and the
 * button only appears when this build knows where it points. Delivery is best effort
 * by design, so a missed banner is not a bug -- a repeated one is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnnouncementCoordinatorTest {

    private class FakeSeenStore(
        private val ids: MutableList<String> = mutableListOf(),
    ) : AnnouncementSeenStore {
        override fun shown(): List<String> = ids.toList()

        override fun remember(id: String) {
            if (id in ids) return
            ids += id
            while (ids.size > AnnouncementSeenStore.REMEMBERED_LIMIT) ids.removeAt(0)
        }

        override fun clear() = ids.clear()
    }

    private fun announcement(
        id: String,
        createdAt: Long = 1_000L,
        expiresAt: Long? = null,
        active: Boolean? = null,
        button: AnnouncementButton? = null,
    ) = Announcement(
        id = id,
        title = "title-$id",
        body = "body-$id",
        symbol = "megaphone",
        tone = "info",
        button = button,
        createdAt = createdAt,
        expiresAt = expiresAt,
        active = active,
    )

    private val noFeed = AnnouncementFeed { emptyFlow() }

    @Test
    fun `a snapshot leads with the newest banner this phone has not shown`() = runTest {
        val coordinator = AnnouncementCoordinator(
            seen = FakeSeenStore(),
            scope = backgroundScope,
            feed = noFeed,
            clock = { 10_000L },
        )

        coordinator.deliver(
            listOf(
                announcement("old", createdAt = 1_000L),
                announcement("new", createdAt = 3_000L),
                announcement("middle", createdAt = 2_000L),
            ),
        )

        assertEquals("new", coordinator.state.value.announcement?.id)
        assertEquals(2, coordinator.state.value.queued)
        assertEquals(1, coordinator.state.value.shownCount)
        assertTrue(coordinator.isShown("new"))
    }

    @Test
    fun `the same id is shown once per device, across relaunches`() = runTest {
        val seen = FakeSeenStore()
        val first = AnnouncementCoordinator(seen = seen, scope = backgroundScope, feed = noFeed, clock = { 10_000L })
        first.deliver(listOf(announcement("a")))
        assertEquals("a", first.state.value.announcement?.id)

        // The phone restarts: same store, new coordinator. Reconnecting must not show
        // the same banner again.
        val second = AnnouncementCoordinator(seen = seen, scope = backgroundScope, feed = noFeed, clock = { 10_000L })
        second.deliver(listOf(announcement("a")))
        assertNull(second.state.value.announcement)

        // And the banner currently on screen is not duplicated by a redelivery.
        first.deliver(listOf(announcement("a")))
        assertEquals(1, first.state.value.shownCount)
    }

    @Test
    fun `expired and switched-off banners are not shown`() = runTest {
        val coordinator = AnnouncementCoordinator(
            seen = FakeSeenStore(),
            scope = backgroundScope,
            feed = noFeed,
            clock = { 10_000L },
        )

        coordinator.deliver(
            listOf(
                announcement("expired", expiresAt = 9_999L),
                announcement("off", active = false),
                announcement("live"),
            ),
        )

        assertEquals("live", coordinator.state.value.announcement?.id)
        assertEquals(0, coordinator.state.value.queued)
        assertFalse(coordinator.isShown("expired"))
    }

    @Test
    fun `a banner dismisses itself after fifteen seconds`() = runTest {
        val coordinator = AnnouncementCoordinator(
            seen = FakeSeenStore(),
            scope = backgroundScope,
            feed = noFeed,
            clock = { 10_000L },
        )
        coordinator.deliver(listOf(announcement("a")))
        runCurrent()
        assertEquals("a", coordinator.state.value.announcement?.id)

        advanceTimeBy(AnnouncementCoordinator.VISIBLE_MILLIS - 1)
        runCurrent()
        assertEquals("a", coordinator.state.value.announcement?.id)

        advanceTimeBy(2)
        runCurrent()
        assertNull(coordinator.state.value.announcement)
    }

    @Test
    fun `closing a banner moves to the next one in the queue`() = runTest {
        val coordinator = AnnouncementCoordinator(
            seen = FakeSeenStore(),
            scope = backgroundScope,
            feed = noFeed,
            clock = { 10_000L },
        )
        coordinator.deliver(
            listOf(
                announcement("first", createdAt = 3_000L),
                announcement("second", createdAt = 2_000L),
                announcement("third", createdAt = 1_000L),
            ),
        )
        assertEquals("first", coordinator.state.value.announcement?.id)

        coordinator.dismiss()
        assertEquals("second", coordinator.state.value.announcement?.id)
        assertEquals(1, coordinator.state.value.queued)

        coordinator.acted()
        assertEquals("third", coordinator.state.value.announcement?.id)
        coordinator.dismiss()
        assertNull(coordinator.state.value.announcement)
        assertEquals(0, coordinator.state.value.queued)
        assertEquals(3, coordinator.state.value.shownCount)
    }

    @Test
    fun `a banner asked to act comes down`() = runTest {
        val coordinator = AnnouncementCoordinator(
            seen = FakeSeenStore(),
            scope = backgroundScope,
            feed = noFeed,
            clock = { 10_000L },
        )
        coordinator.deliver(listOf(announcement("a")))
        coordinator.acted()
        assertNull(coordinator.state.value.announcement)
    }

    @Test
    fun `the socket is only wanted while signed in and in the foreground`() = runTest {
        val coordinator = AnnouncementCoordinator(
            seen = FakeSeenStore(),
            scope = backgroundScope,
            feed = AnnouncementFeed { flowOf(AnnouncementEvent.Snapshot(listOf(announcement("from-socket")))) },
            clock = { 10_000L },
        )

        coordinator.update(token = null, active = true)
        runCurrent()
        assertNull(coordinator.state.value.announcement)

        coordinator.update(token = "mza_token", active = false)
        runCurrent()
        assertNull(coordinator.state.value.announcement)

        coordinator.update(token = "mza_token", active = true)
        runCurrent()
        assertEquals("from-socket", coordinator.state.value.announcement?.id)
    }

    @Test
    fun `a single created banner arrives over the socket`() = runTest {
        val coordinator = AnnouncementCoordinator(
            seen = FakeSeenStore(),
            scope = backgroundScope,
            feed = AnnouncementFeed { flowOf(AnnouncementEvent.Created(announcement("created"))) },
            clock = { 10_000L },
        )

        coordinator.update(token = "mza_token", active = true)
        runCurrent()

        assertEquals("created", coordinator.state.value.announcement?.id)
    }

    @Test
    fun `the foreground poll delivers a banner the suspended phone missed`() = runTest {
        var reads = 0
        var polledToken: String? = null
        val coordinator = AnnouncementCoordinator(
            seen = FakeSeenStore(),
            scope = backgroundScope,
            feed = noFeed,
            poll = { token ->
                reads += 1
                polledToken = token
                listOf(announcement("from-poll"))
            },
            clock = { 10_000L },
        )

        coordinator.update(token = "mza_token", active = true)
        runCurrent()

        assertEquals(1, reads)
        assertEquals("mza_token", polledToken)
        assertEquals("from-poll", coordinator.state.value.announcement?.id)
    }

    @Test
    fun `a signed-out phone still shows a banner already queued`() = runTest {
        val coordinator = AnnouncementCoordinator(
            seen = FakeSeenStore(),
            scope = backgroundScope,
            feed = noFeed,
            clock = { 10_000L },
        )
        coordinator.deliver(listOf(announcement("a")))
        // Going to the background must not tear the card off the screen.
        coordinator.update(token = null, active = false)
        assertEquals("a", coordinator.state.value.announcement?.id)
    }

    @Test
    fun `known targets resolve and unknown ones do not`() {
        assertEquals(
            AnnouncementAction.Settings(AnnouncementTarget.NOTIFICATIONS),
            AnnouncementTargets.resolve("notifications"),
        )
        assertEquals(
            AnnouncementAction.Settings(AnnouncementTarget.ABOUT),
            AnnouncementTargets.resolve("settings:about"),
        )
        assertEquals(AnnouncementAction.Account, AnnouncementTargets.resolve("account"))
        assertEquals(
            AnnouncementAction.External("https://example.com/release"),
            AnnouncementTargets.resolve("https://example.com/release"),
        )
        assertNull(AnnouncementTargets.resolve("settings:not-a-page"))
        assertNull(AnnouncementTargets.resolve("somewhere-else"))
        assertNull(AnnouncementTargets.resolve(""))
    }

    @Test
    fun `a button is drawn only when the target is known and it has a label`() {
        val known = announcement("a", button = AnnouncementButton("Open settings", "notifications"))
        assertEquals(AnnouncementTarget.NOTIFICATIONS, (known.action() as AnnouncementAction.Settings).shelf)
        assertEquals("Open settings", known.actionableButton?.label)

        val unknown = announcement("b", button = AnnouncementButton("Open settings", "unknown-page"))
        assertNull(unknown.action())
        assertNull(unknown.actionableButton)

        val unlabeled = announcement("c", button = AnnouncementButton("", "notifications"))
        assertNull(unlabeled.action())

        // A URL is actionable even though it is not a settings shelf.
        val link = announcement("d", button = AnnouncementButton("See the release", "https://example.com"))
        assertEquals(AnnouncementAction.External("https://example.com"), link.action())
    }

    @Test
    fun `forgetting the shown ids lets the next snapshot through again`() = runTest {
        val coordinator = AnnouncementCoordinator(
            seen = FakeSeenStore(),
            scope = backgroundScope,
            feed = noFeed,
            clock = { 10_000L },
        )
        coordinator.deliver(listOf(announcement("a")))
        coordinator.dismiss()
        coordinator.deliver(listOf(announcement("a")))
        assertNull(coordinator.state.value.announcement)

        coordinator.forgetShown()
        coordinator.deliver(listOf(announcement("a")))
        assertEquals("a", coordinator.state.value.announcement?.id)
    }
}
