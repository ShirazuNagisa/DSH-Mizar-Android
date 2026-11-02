package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import me.shirazu.mizar.kit.MizarConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dialling every route at once.
 *
 * The three failures this prevents are all invisible on a fast network: waiting for a
 * blackholed route before trying the next one (the app looks frozen for twenty
 * seconds), leaving a loser's socket open (a second connection to the same computer
 * that nobody closes), and reporting "the last route to fail" as the reason (which is
 * whichever one the scheduler happened to run last, not the one that proves anything).
 */
class RouteRaceTest {

    private class Ranked(val rank: Int) : Exception("ranked $rank")

    private class Fixture(weight: (Throwable, MizarConnection.Endpoint) -> Int = { error, _ ->
        (error as? Ranked)?.rank ?: 0
    }) {
        val notes = mutableListOf<String>()
        private val job = Job()
        val race = RouteRace(
            // Unconfined: a dial that answers at once has answered by the time it returns.
            scope = CoroutineScope(Dispatchers.Unconfined + job),
            note = { notes += it },
            weight = weight,
        )

        fun settle() = runBlocking { job.children.toList().forEach { it.join() } }
    }

    private companion object {
        val direct = MizarConnection.Endpoint.Direct("192.168.1.10:19390")
        val relay = MizarConnection.Endpoint.Relay("https://relay.example")
    }

    @Test
    fun `the route that answers first wins`() = runBlocking {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()

        val outcome = f.race.race(listOf(relay, direct)) { endpoint ->
            if (endpoint == relay) {
                gate.await()
                "relay"
            } else {
                "direct"
            }
        }

        val won = outcome as RouteOutcome.Won
        assertEquals("the slow route must not hold up the fast one", direct, won.endpoint)
        assertEquals("direct", won.value)
        f.settle()
    }

    @Test
    fun `a winner cancels the routes still dialling`() = runBlocking {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        var loserCancelled = false

        f.race.race(listOf(relay, direct)) { endpoint ->
            if (endpoint == relay) {
                try {
                    gate.await()
                } finally {
                    // This is what a real dial does: close its own socket on the way out.
                    loserCancelled = true
                }
                "relay"
            } else {
                "direct"
            }
        }
        f.settle()

        assertTrue("a loser left handshaking would keep a second socket open", loserCancelled)
    }

    @Test
    fun `a cancelled attempt is not reported as a failure`() = runBlocking {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()

        f.race.race(listOf(relay, direct)) { endpoint ->
            if (endpoint == relay) {
                gate.await()
                "relay"
            } else {
                "direct"
            }
        }
        f.settle()

        assertTrue("losing the race is not an error: ${f.notes}", f.notes.isEmpty())
    }

    @Test
    fun `when nothing answers the failure that proves the most is reported`() = runBlocking {
        val f = Fixture()
        val weak = Ranked(1)
        val strong = Ranked(5)

        val outcome = f.race.race(listOf(direct, relay)) { endpoint ->
            throw if (endpoint == direct) weak else strong
        }

        val lost = outcome as RouteOutcome.Lost
        assertEquals("the route that proves the most, not the last one to fail", relay, lost.endpoint)
        assertEquals(strong, lost.failure)
    }

    @Test
    fun `a tie keeps the earlier route`() = runBlocking {
        val f = Fixture()
        val first = Ranked(5)
        val second = Ranked(5)

        val outcome = f.race.race(listOf(direct, relay)) { endpoint ->
            throw if (endpoint == direct) first else second
        }

        val lost = outcome as RouteOutcome.Lost
        assertEquals("equal weight must not depend on who finished last", direct, lost.endpoint)
        assertEquals(first, lost.failure)
    }

    @Test
    fun `every failed route is written to the log`() = runBlocking {
        val f = Fixture()

        f.race.race(listOf(direct, relay)) { endpoint -> throw Ranked(1) }

        assertEquals(2, f.notes.size)
        assertTrue(f.notes.any { it.contains("$direct") })
        assertTrue(f.notes.any { it.contains("$relay") })
    }

    @Test
    fun `no routes at all is a loss with no failure`() = runBlocking {
        val f = Fixture()

        val outcome = f.race.race(emptyList<MizarConnection.Endpoint>()) { "never" }

        val lost = outcome as RouteOutcome.Lost
        assertNull("the caller supplies its own 'no address answered'", lost.failure)
        assertNull(lost.endpoint)
        assertTrue(f.notes.isEmpty())
    }
}
