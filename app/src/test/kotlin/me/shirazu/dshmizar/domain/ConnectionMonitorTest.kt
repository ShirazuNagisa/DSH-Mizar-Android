package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.ServerFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeping a socket honest.
 *
 * The bug behind this object is not "the socket closed" -- a close is reported. It is a
 * socket that is *dead and quiet*: a relay tunnel drops the mapping, the relay keeps
 * answering websocket pings, both read loops stay happy, and nothing throws. The app
 * sat "connected" for minutes while every poll failed silently, so no event arrived and
 * no list refresh happened, and every completion in the gap was lost.
 *
 * The poll is the only evidence, so the rules about it are the whole point: two silent
 * answers in a row is death, one is a blip, and any answer resets the count.
 *
 * Time is virtual here. These cases used to wait 30-80 real milliseconds each and assert
 * "at least one poll happened", which is a race against whatever else the machine was
 * doing; `pass(80)` advances the scheduler instead, so "sixteen polls in 80 ms at a 5 ms
 * interval" is arithmetic rather than luck. Every case that leaves the poll loop running
 * stops it before returning: `runTest` drains the scheduler when the body ends, and an
 * endless 5 ms loop would be drained forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionMonitorTest {

    private class FakeSocket : MonitoredConnection {
        private val states = MutableStateFlow<MizarConnection.State>(MizarConnection.State.Connecting)
        private val frames = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 8)

        override val state: StateFlow<MizarConnection.State> get() = states

        override fun events(): Flow<ServerFrame> = frames

        fun fail(message: String) {
            states.value = MizarConnection.State.Failed(message)
        }

        fun emit(event: ServerFrame) {
            frames.tryEmit(event)
        }
    }

    private class Fixture(
        pollMillis: Long = 5,
        /** Where the poll loop runs. A test dispatcher makes its `delay` virtual. */
        dispatcher: CoroutineDispatcher,
        /** What the poll answers, by attempt number. */
        var answers: (Int) -> Boolean = { true },
    ) {
        val socket = FakeSocket()
        val notes = mutableListOf<String>()
        val silences = mutableListOf<String>()
        val events = mutableListOf<String>()
        val latencies = mutableListOf<Long>()
        var polls = 0
        var sessionsPolls = 0
        var anythingRunning = false
        var foreground = true

        private val job = Job()
        val monitor = ConnectionMonitor(
            store = FlowStateStore(MutableStateFlow(UiState())),
            scope = CoroutineScope(Dispatchers.Unconfined + job),
            note = { notes += it },
            dispatcher = dispatcher,
            pollMillis = pollMillis,
            lanPollMillis = pollMillis,
            sessionsTimeoutMillis = 100,
            pollInteractions = {
                polls += 1
                answers(polls)
            },
            recordLatency = { latencies += it },
            pollSessions = { sessionsPolls += 1 },
            onEvent = { frame -> events += frame.name },
            onSilent = { reason -> silences += reason },
            anythingRunning = { anythingRunning },
            isForeground = { foreground },
        )
    }

    /** Let [millis] of virtual time pass, then run whatever that time made due. */
    private fun TestScope.pass(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun TestScope.fixture(answers: (Int) -> Boolean = { true }) =
        Fixture(dispatcher = StandardTestDispatcher(testScheduler), answers = answers)

    private companion object {
        val event = ServerFrame.Event("turn.settled", JsonObject(emptyMap()))
    }

    @Test
    fun `two silent polls mean the socket is dead`() = runTest {
        val f = fixture(answers = { false })
        f.monitor.start(f.socket, isLive = { true })

        pass(80)

        assertEquals("a ghost socket is announced once, not once per poll", 1, f.silences.size)
        assertEquals("the host stopped answering", f.silences.single())
        // Exactly two, not "at most three": the old assertion passed with the
        // threshold moved to 3, which is the one thing this case is named after.
        assertEquals("the second silent answer is what kills it", 2, f.polls)
        f.monitor.stop()
    }

    @Test
    fun `one silent poll is a blip`() = runTest {
        val f = fixture(answers = { attempt -> attempt != 1 })
        f.monitor.start(f.socket, isLive = { true })

        pass(80)

        assertTrue("one unanswered question is not a dead socket: ${f.silences}", f.silences.isEmpty())
        assertTrue(f.notes.any { it.contains("no answer (1)") })
        f.monitor.stop()
    }

    @Test
    fun `any answer resets the silence count`() = runTest {
        // Silent, answered, silent, silent: only the last two are consecutive.
        val f = fixture(answers = { attempt -> attempt == 2 })
        f.monitor.start(f.socket, isLive = { true })

        pass(80)

        assertEquals(1, f.silences.size)
        assertTrue("the counter starts again after an answer", f.polls >= 4)
        f.monitor.stop()
    }

    @Test
    fun `an answered poll is timed`() = runTest {
        val f = fixture()
        f.monitor.start(f.socket, isLive = { true })

        pass(60)

        assertTrue("the poll is also the clock: ${f.latencies}", f.latencies.isNotEmpty())
        f.monitor.stop()
    }

    @Test
    fun `a pushed frame reaches the handler`() = runTest {
        val f = fixture()
        f.monitor.start(f.socket, isLive = { true })
        // The stream is cold: let the collector subscribe before anything is pushed.
        pass(30)

        f.socket.emit(event)
        pass(40)

        assertEquals(listOf("turn.settled"), f.events)
        assertTrue(f.notes.any { it.contains("event turn.settled") })
        f.monitor.stop()
    }

    @Test
    fun `the list is read in the background only when something is running`() = runTest {
        val quiet = fixture()
        quiet.foreground = false
        quiet.anythingRunning = false
        quiet.monitor.start(quiet.socket, isLive = { true })
        pass(60)
        assertEquals("nothing was running, so there is nothing to notice", 0, quiet.sessionsPolls)
        quiet.monitor.stop()

        val busy = fixture()
        busy.foreground = false
        busy.anythingRunning = true
        busy.monitor.start(busy.socket, isLive = { true })
        pass(60)
        assertTrue("a turn ended while the screen was elsewhere", busy.sessionsPolls > 0)
        busy.monitor.stop()
    }

    @Test
    fun `the live socket failing starts a reconnect`() = runTest {
        val f = fixture()
        val failures = mutableListOf<String>()
        f.monitor.watchConnection(f.socket, isLive = { true }, onFailed = { failures += it })

        f.socket.fail("the host went away")
        pass(30)

        assertEquals(listOf("the host went away"), failures)
        f.monitor.stopConnectionWatch()
    }

    @Test
    fun `a replaced socket failing later is ignored`() = runTest {
        val f = fixture()
        val failures = mutableListOf<String>()
        f.monitor.watchConnection(f.socket, isLive = { false }, onFailed = { failures += it })

        f.socket.fail("old news")
        pass(30)

        assertTrue("two loops would fight over the socket: $failures", failures.isEmpty())
        f.monitor.stopConnectionWatch()
    }

    @Test
    fun `stopping the watch leaves nothing behind`() = runTest {
        val f = fixture()
        val failures = mutableListOf<String>()
        f.monitor.watchConnection(f.socket, isLive = { true }, onFailed = { failures += it })
        f.monitor.stopConnectionWatch()

        f.socket.fail("after the switch")
        pass(30)

        assertTrue(failures.isEmpty())
    }

    @Test
    fun `stopping the poll stops the questions`() = runTest {
        val f = fixture()
        f.monitor.start(f.socket, isLive = { true })
        pass(40)
        val before = f.polls

        f.monitor.stop()
        pass(60)

        assertTrue("a stopped monitor must not keep polling: ${f.polls}", f.polls <= before + 1)
        assertFalse(f.silences.isNotEmpty())
    }
}
