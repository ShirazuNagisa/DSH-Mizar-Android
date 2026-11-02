package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.shirazu.dshmizar.Phase
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.Backoff
import me.shirazu.mizar.kit.PairedHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens after the socket goes away.
 *
 * The rule this object exists for is "it never gives up", and the reason is a bug that
 * shipped: six attempts, about half a minute, then a permanent failure screen -- so a
 * phone in a pocket outlasted the app, and the completion nobody heard about was the
 * one nobody could have heard. The rest are about not crying wolf: a blip must not show
 * a banner, and a refused credential must stop rather than knock forever.
 *
 * Every assertion here is about time, so the clock is a parameter: the backoff is a
 * one-millisecond schedule and the banner delay is short enough to observe.
 */
class ReconnectCoordinatorTest {

    private class Fixture(
        bannerDelayMillis: Long = 50,
        backoffSeconds: Double = 0.001,
    ) {
        val state = MutableStateFlow(UiState(phase = Phase.CONNECTING))
        val notices = mutableListOf<String>()
        var redials = 0
        var stopWatcherCalls = 0
        var refreshes = 0
        var presences = 0
        var connected = false

        /** What a dial does to the state; by default it fails the way `connect` does. */
        var onRedial: () -> Unit = { state.value = state.value.copy(phase = Phase.CONNECTING) }

        /** Parks a dial, for the "a second begin is ignored" case. */
        var gate: CompletableDeferred<Unit>? = null

        private val job = Job()
        val coordinator = ReconnectCoordinator(
            store = FlowStateStore(state),
            scope = CoroutineScope(Dispatchers.Unconfined + job),
            strings = MarkerText(),
            note = {},
            backoff = Backoff(listOf(backoffSeconds), 0.0),
            bannerDelayMillis = bannerDelayMillis,
            stopWatchers = { stopWatcherCalls += 1 },
            redial = {
                redials += 1
                gate?.await()
                onRedial()
            },
            afterReconnected = { refreshes += 1 },
            announcePresence = { presences += 1 },
            publishNotice = { notices += it },
            connected = { connected },
        )

        val ui get() = state.value

        fun settle() = runBlocking { job.children.toList().forEach { it.join() } }
    }

    private class MarkerText : Text {
        override fun get(id: Int): String = "res:$id"
        override fun get(id: Int, vararg args: Any): String = "res:$id:" + args.joinToString(",")
    }

    private companion object {
        val host = PairedHost(hostId = "h1", hostName = "Mac", deviceId = "d1", deviceToken = "t1")
    }

    @Test
    fun `the reason is recorded at once and the banner is not`() {
        val f = Fixture()

        f.coordinator.begin(host, "the host stopped answering")

        assertEquals("the reason is known immediately", "the host stopped answering", f.ui.disconnectReason)
        assertFalse("a two-second blip must not put a banner in front of the operator", f.ui.reconnecting)
        f.coordinator.cancel()
    }

    @Test
    fun `the banner appears once the drop lasts`() = runBlocking {
        val f = Fixture(bannerDelayMillis = 50)
        f.coordinator.begin(host, "gone")

        delay(200)

        assertTrue("a drop that outlasts the delay is an outage", f.ui.reconnecting)
        f.coordinator.cancel()
    }

    @Test
    fun `a blip never shows the banner`() {
        val f = Fixture(bannerDelayMillis = 50)
        // The socket comes back on the first attempt: a new connection is the live one,
        // which is what the banner checks before it puts itself up.
        f.onRedial = {
            f.connected = true
            f.state.value = f.state.value.copy(phase = Phase.READY)
        }

        f.coordinator.begin(host, "blip")
        f.settle()

        assertFalse("a drop that ended is not worth a banner", f.ui.reconnecting)
    }

    @Test
    fun `the one notice says it is trying, before anything is known`() {
        val f = Fixture()

        f.coordinator.begin(host, "gone")

        assertEquals(1, f.notices.size)
        assertTrue("it names the action, not a diagnosis it does not have", f.notices.single().startsWith("res:"))
        f.coordinator.cancel()
    }

    @Test
    fun `a second begin while one loop runs is ignored`() = runBlocking {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.gate = gate

        f.coordinator.begin(host, "gone")
        f.coordinator.begin(host, "gone again")
        delay(20)

        assertEquals("two loops would dial over each other", 1, f.stopWatcherCalls)
        gate.complete(Unit)
        f.coordinator.cancel()
    }

    @Test
    fun `a refused credential stops the loop and sends the screen to pairing`() {
        val f = Fixture(bannerDelayMillis = 50)
        f.onRedial = {
            f.state.value = f.state.value.copy(phase = Phase.CONNECTING, needsPairing = true)
        }

        f.coordinator.begin(host, "unknown token")
        f.settle()

        assertEquals("every further attempt is the same rejection", 1, f.redials)
        assertEquals(Phase.FAILED, f.ui.phase)
        // `reconnecting` is not asserted: on a failed dial the banner's own timer is
        // still armed, and whether it fires first is a race the screen does not care
        // about -- FAILED is the page it shows either way.
    }

    @Test
    fun `a failed attempt does not leave the screen on the failure page`() = runBlocking {
        val f = Fixture()
        // The banner is up, and the dial marks the phase FAILED the way `connect` does.
        f.onRedial = {
            f.state.value = f.state.value.copy(phase = Phase.FAILED, reconnecting = true)
        }

        f.coordinator.begin(host, "gone")
        delay(40)

        assertEquals("the banner is what the operator should see, not the failure page", Phase.READY, f.ui.phase)
        f.coordinator.cancel()
    }

    @Test
    fun `it keeps trying instead of giving up`() = runBlocking {
        val f = Fixture()
        f.coordinator.begin(host, "gone")

        delay(400)

        // Six is not a number chosen at random: it is the limit the shipped bug had.
        assertTrue("a monitor has to outlast the outage, not count attempts: ${f.redials}", f.redials > 6)
        assertNotEquals(Phase.FAILED, f.ui.phase)
        f.coordinator.cancel()
    }

    @Test
    fun `a reconnect clears the reason, reads again and says hello`() {
        val f = Fixture(bannerDelayMillis = 50)
        f.state.value = f.ui.copy(disconnectReason = "gone")
        f.onRedial = {
            f.connected = true
            f.state.value = f.state.value.copy(phase = Phase.READY)
        }

        f.coordinator.begin(host, "gone")
        f.settle()

        assertNull("the banner has nothing left to explain", f.ui.disconnectReason)
        assertFalse(f.ui.reconnecting)
        assertEquals("whatever happened during the outage never arrived as an event", 1, f.refreshes)
        assertEquals("the host forgot we were on screen", 1, f.presences)
    }

    @Test
    fun `cancelling stops the loop`() = runBlocking {
        val f = Fixture()
        f.coordinator.begin(host, "gone")
        delay(50)
        val before = f.redials

        f.coordinator.cancel()
        delay(80)

        assertTrue("a cancelled loop may finish the dial it was in, and no more", f.redials <= before + 1)
    }

    @Test
    fun `the watchers stop as soon as the socket goes`() {
        val f = Fixture()
        f.onRedial = { f.state.value = f.state.value.copy(phase = Phase.READY) }

        f.coordinator.begin(host, "gone")

        assertEquals("watching a socket that is gone only produces noise", 1, f.stopWatcherCalls)
        f.settle()
    }
}
