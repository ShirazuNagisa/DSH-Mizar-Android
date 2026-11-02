package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
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
 */
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
            dispatcher = Dispatchers.Default,
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

        fun settle() = runBlocking { job.children.toList().forEach { it.join() } }
    }

    private companion object {
        val event = ServerFrame.Event("turn.settled", JsonObject(emptyMap()))
    }

    @Test
    fun `two silent polls mean the socket is dead`() = runBlocking {
        val f = Fixture(answers = { false })
        f.monitor.start(f.socket, isLive = { true })

        delay(80)

        assertEquals("a ghost socket is announced once, not once per poll", 1, f.silences.size)
        assertEquals("the host stopped answering", f.silences.single())
        assertTrue("and it stops asking: ${f.polls}", f.polls <= 3)
    }

    @Test
    fun `one silent poll is a blip`() = runBlocking {
        val f = Fixture(answers = { attempt -> attempt != 1 })
        f.monitor.start(f.socket, isLive = { true })

        delay(80)

        assertTrue("one unanswered question is not a dead socket: ${f.silences}", f.silences.isEmpty())
        assertTrue(f.notes.any { it.contains("no answer (1)") })
    }

    @Test
    fun `any answer resets the silence count`() = runBlocking {
        // Silent, answered, silent, silent: only the last two are consecutive.
        val f = Fixture(answers = { attempt -> attempt == 2 })
        f.monitor.start(f.socket, isLive = { true })

        delay(80)

        assertEquals(1, f.silences.size)
        assertTrue("the counter starts again after an answer", f.polls >= 4)
    }

    @Test
    fun `an answered poll is timed`() = runBlocking {
        val f = Fixture(answers = { true })
        f.monitor.start(f.socket, isLive = { true })

        delay(60)

        assertTrue("the poll is also the clock: ${f.latencies}", f.latencies.isNotEmpty())
    }

    @Test
    fun `a pushed frame reaches the handler`() = runBlocking {
        val f = Fixture()
        f.monitor.start(f.socket, isLive = { true })
        // The stream is cold: let the collector subscribe before anything is pushed.
        delay(30)

        f.socket.emit(event)
        delay(40)

        assertEquals(listOf("turn.settled"), f.events)
        assertTrue(f.notes.any { it.contains("event turn.settled") })
    }

    @Test
    fun `the list is read in the background only when something is running`() = runBlocking {
        val quiet = Fixture()
        quiet.foreground = false
        quiet.anythingRunning = false
        quiet.monitor.start(quiet.socket, isLive = { true })
        delay(60)
        assertEquals("nothing was running, so there is nothing to notice", 0, quiet.sessionsPolls)
        quiet.monitor.stop()

        val busy = Fixture()
        busy.foreground = false
        busy.anythingRunning = true
        busy.monitor.start(busy.socket, isLive = { true })
        delay(60)
        assertTrue("a turn ended while the screen was elsewhere", busy.sessionsPolls > 0)
        busy.monitor.stop()
    }

    @Test
    fun `the live socket failing starts a reconnect`() = runBlocking {
        val f = Fixture()
        val failures = mutableListOf<String>()
        f.monitor.watchConnection(f.socket, isLive = { true }, onFailed = { failures += it })

        f.socket.fail("the host went away")
        delay(30)

        assertEquals(listOf("the host went away"), failures)
        f.monitor.stopConnectionWatch()
    }

    @Test
    fun `a replaced socket failing later is ignored`() = runBlocking {
        val f = Fixture()
        val failures = mutableListOf<String>()
        f.monitor.watchConnection(f.socket, isLive = { false }, onFailed = { failures += it })

        f.socket.fail("old news")
        delay(30)

        assertTrue("two loops would fight over the socket: $failures", failures.isEmpty())
        f.monitor.stopConnectionWatch()
    }

    @Test
    fun `stopping the watch leaves nothing behind`() = runBlocking {
        val f = Fixture()
        val failures = mutableListOf<String>()
        f.monitor.watchConnection(f.socket, isLive = { true }, onFailed = { failures += it })
        f.monitor.stopConnectionWatch()

        f.socket.fail("after the switch")
        delay(30)

        assertTrue(failures.isEmpty())
    }

    @Test
    fun `stopping the poll stops the questions`() = runBlocking {
        val f = Fixture()
        f.monitor.start(f.socket, isLive = { true })
        delay(40)
        val before = f.polls

        f.monitor.stop()
        delay(60)

        assertTrue("a stopped monitor must not keep polling: ${f.polls}", f.polls <= before + 1)
        assertFalse(f.silences.isNotEmpty())
    }
}
