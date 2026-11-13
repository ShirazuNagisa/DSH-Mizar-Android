package me.shirazu.mizar.kit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The reducer that turns a state stream into drawable blocks.
 *
 * Ported case by case from `iOS/MizarKit/Sources/MizarKit/TurnStream.swift` (and, where
 * the two overlap, from the Rust receiver's tests), so the two phones read the same
 * turn the same way. The extra rule only this layer owns is promotion: a
 * `stream_final` snapshot that repeats the last body word for word *is* that body,
 * now confirmed.
 */
class TurnStreamTest {

    private fun work(state: SessionState, interval: Long) = StateFrame.begin(state, interval)

    @Test
    fun `consecutive work states are one status block`() {
        val stream = TurnStream()
        assertTrue(stream.apply(work(SessionState.THINKING, 1)))
        assertTrue(stream.apply(work(SessionState.TOOL, 2)))
        // The same state again is not a new entry: the line answers "what did it do",
        // not "how many frames arrived".
        assertFalse(stream.apply(work(SessionState.TOOL, 2)))

        val block = stream.blocks.single()
        assertEquals(StreamBlock.Kind.STATUS, block.kind)
        assertEquals(listOf(SessionState.THINKING, SessionState.TOOL), block.states)
        assertEquals(1L, block.interval)
    }

    @Test
    fun `a waiting state is a line, not a block`() {
        val stream = TurnStream()
        assertTrue(stream.apply(work(SessionState.THINKING, 1)))
        assertTrue(stream.apply(work(SessionState.AWAITING_APPROVAL, 2)))
        assertEquals(SessionState.AWAITING_APPROVAL, stream.waiting)
        assertEquals(1, stream.blocks.size, "a waiting state must not become a block")

        // The next work state clears it.
        assertTrue(stream.apply(work(SessionState.TOOL, 3)))
        assertEquals(null, stream.waiting)
        assertEquals(listOf(SessionState.THINKING, SessionState.TOOL), stream.blocks.single().states)
    }

    @Test
    fun `streamed narration and the final answer are separate body blocks`() {
        val stream = TurnStream()
        stream.apply(work(SessionState.THINKING, 1))
        stream.apply(StateFrame.begin(SessionState.STREAM_NARRATION, 2))
        stream.apply(StateFrame.append(SessionState.STREAM_NARRATION, 2, 0, "我先看一下…"))
        stream.apply(StateFrame.finish(SessionState.STREAM_NARRATION, 2, 18))
        stream.apply(StateFrame.begin(SessionState.STREAM_FINAL, 3, "改好了。"))

        assertEquals(3, stream.blocks.size)
        assertIs<StreamBlock>(stream.blocks[1])
        assertEquals(StreamBlock.Kind.BODY, stream.blocks[1].kind)
        assertEquals("我先看一下…", stream.blocks[1].text)
        assertEquals(StreamBlock.Kind.BODY, stream.blocks[2].kind)
        assertEquals("改好了。", stream.blocks[2].text)
        // A body opened by `stream_final` *is* the answer; waiting for the FINAL flag
        // to style it would leave the last sentence unstyled for its whole stream.
        assertTrue(stream.blocks[2].isFinal)
    }

    @Test
    fun `a repeated final snapshot promotes the last body instead of printing it twice`() {
        val stream = TurnStream()
        // The host streamed the answer as the running stream, then re-announced the
        // same text as the final snapshot.
        stream.apply(StateFrame.begin(SessionState.STREAM_FINAL, 1, "改好了。"))
        stream.apply(StateFrame.snapshot(SessionState.STREAM_FINAL, 1, text = "改好了。"))

        assertEquals(1, stream.blocks.size, "the answer must not be drawn twice")
        assertEquals(1, stream.blocks.count { it.kind == StreamBlock.Kind.BODY })
        assertTrue(stream.blocks.single().isFinal)
        assertEquals("改好了。", stream.blocks.single().text)
        assertFalse(stream.finished, "promotion does not end the turn; a terminal state does")
    }

    @Test
    fun `an older host announcing the whole turn promotes the narration`() {
        val stream = TurnStream()
        // The old kernel sent no separate final body: the whole turn's narration was
        // concatenated into one final snapshot. The last body is still the answer.
        stream.apply(StateFrame.begin(SessionState.STREAM_NARRATION, 1, "先看日志。"))
        stream.apply(StateFrame.begin(SessionState.STREAM_NARRATION, 2, "改好了。"))
        stream.apply(StateFrame.snapshot(SessionState.STREAM_FINAL, 3, text = "先看日志。改好了。"))

        val bodies = stream.blocks.filter { it.kind == StreamBlock.Kind.BODY }
        assertEquals(2, bodies.size, "the old snapshot must not open a third body")
        assertEquals("先看日志。", bodies[0].text)
        assertFalse(bodies[0].isFinal)
        assertEquals("改好了。", bodies[1].text)
        assertTrue(bodies[1].isFinal, "the last narration is the answer, promoted in place")
    }

    @Test
    fun `an append that skips text is dropped and the snapshot repairs it`() {
        val stream = TurnStream()
        stream.apply(StateFrame.begin(SessionState.STREAM_FINAL, 1, "ab"))
        // Byte 5, not 2: a frame was lost; gluing it on would draw a sentence that was
        // never said.
        stream.apply(StateFrame.append(SessionState.STREAM_FINAL, 1, 5, "cd"))
        assertEquals("ab", stream.blocks.last().text)

        stream.apply(StateFrame.snapshot(SessionState.STREAM_FINAL, 1, text = "abcde"))
        assertEquals("abcde", stream.blocks.last().text)
    }

    @Test
    fun `a gap in a new block is refused too`() {
        val stream = TurnStream()
        // No body block exists yet, so the only valid offset is zero.
        stream.apply(StateFrame.append(SessionState.STREAM_FINAL, 1, 3, "late"))
        assertEquals(0, stream.blocks.size)
    }

    @Test
    fun `a terminal state ends the turn and keeps the blocks`() {
        val stream = TurnStream()
        stream.apply(work(SessionState.THINKING, 1))
        stream.apply(StateFrame.begin(SessionState.STREAM_FINAL, 2, "答案"))
        assertTrue(stream.apply(work(SessionState.COMPLETED, 3)))

        assertTrue(stream.finished)
        assertEquals(SessionState.COMPLETED, stream.endState)
        assertEquals(2, stream.blocks.size)
        assertEquals("", stream.liveText, "nothing is being typed after the turn ended")
    }

    @Test
    fun `a follow-up on the same stream un-finishes the screen`() {
        val stream = TurnStream()
        stream.apply(StateFrame.begin(SessionState.STREAM_FINAL, 1, "答案"))
        stream.apply(work(SessionState.COMPLETED, 2))
        // The operator answered a question, or a follow-up landed on the same turn: the
        // ending is undone, the blocks stay.
        assertTrue(stream.apply(work(SessionState.TOOL, 3)))
        assertFalse(stream.finished)
        assertEquals(null, stream.endState)
        assertTrue(stream.blocks.any { it.kind == StreamBlock.Kind.BODY })
    }

    @Test
    fun `idle and unknown codes draw nothing`() {
        val stream = TurnStream()
        assertFalse(stream.apply(work(SessionState.IDLE, 1)))
        assertFalse(stream.apply(StateFrame.begin(SessionState.UNKNOWN, 2)))
        assertTrue(stream.blocks.isEmpty())
        assertEquals(null, stream.current)
    }

    @Test
    fun `a late frame from an older interval changes nothing`() {
        val stream = TurnStream()
        stream.apply(StateFrame.begin(SessionState.STREAM_FINAL, 5, "现在"))
        assertFalse(stream.apply(work(SessionState.THINKING, 4)))
        assertEquals("现在", stream.blocks.single().text)
        assertEquals(SessionState.STREAM_FINAL, stream.current)
    }

    @Test
    fun `a replay rebuilds the turn and live frames continue from it`() {
        val replay = SessionStreamResult(
            sessionId = "dsh:abc",
            turn = 12,
            state = SessionState.STREAM_FINAL.code,
            interval = 4,
            textSeq = 12,
            entries = listOf(
                StreamEntry(interval = 1, state = SessionState.THINKING.code),
                StreamEntry(interval = 2, state = SessionState.TOOL.code),
                StreamEntry(interval = 3, state = SessionState.STREAM_NARRATION.code, text = "先看日志。"),
                StreamEntry(interval = 4, state = SessionState.STREAM_FINAL.code, text = "改好了。", isFinal = true),
            ),
        )

        val stream = TurnStream()
        stream.replace(replay)

        assertEquals(12, stream.turn)
        assertEquals(3, stream.blocks.size)
        assertEquals(SessionState.STREAM_FINAL, stream.current)
        assertEquals(4L, stream.currentInterval)
        assertEquals("改好了。", stream.blocks.last().text)

        // A live append right after the replay must land at the replay's textSeq, not
        // be judged a lost frame.
        assertTrue(stream.apply(StateFrame.append(SessionState.STREAM_FINAL, 4, 12, "真的。")))
        assertEquals("改好了。真的。", stream.blocks.last().text)
    }

    @Test
    fun `a replay that lands on a terminal state is finished`() {
        val replay = SessionStreamResult(
            state = SessionState.COMPLETED.code,
            interval = 7,
            entries = listOf(StreamEntry(interval = 1, state = SessionState.THINKING.code)),
        )
        val stream = TurnStream()
        stream.replace(replay)
        assertTrue(stream.finished)
        assertEquals(SessionState.COMPLETED, stream.endState)
    }

    @Test
    fun `an empty replay is a normal answer`() {
        val stream = TurnStream()
        stream.replace(SessionStreamResult())
        assertTrue(stream.blocks.isEmpty())
        assertFalse(stream.finished)
        // The host's "no journal" answer says the stream stands at idle, interval 0.
        assertEquals(SessionState.IDLE, stream.current)
        assertEquals(0L, stream.currentInterval)
    }

    @Test
    fun `a truncated replay says so`() {
        val stream = TurnStream()
        stream.replace(
            SessionStreamResult(
                state = SessionState.TOOL.code,
                interval = 9,
                truncated = true,
                entries = listOf(StreamEntry(interval = 9, state = SessionState.TOOL.code)),
            ),
        )
        assertTrue(stream.truncated)
    }

    @Test
    fun `beginTurn clears the stream and keeps the turn number`() {
        val stream = TurnStream()
        stream.apply(StateFrame.begin(SessionState.STREAM_FINAL, 1, "上一轮"))
        stream.apply(work(SessionState.COMPLETED, 2))
        stream.beginTurn(13)

        assertEquals(13, stream.turn)
        assertTrue(stream.blocks.isEmpty())
        assertFalse(stream.finished)
        assertEquals(null, stream.waiting)
    }

    @Test
    fun `a new turn keeps the known turn number when none is given`() {
        val stream = TurnStream()
        stream.beginTurn(12)
        stream.apply(work(SessionState.THINKING, 1))
        stream.beginTurn(null)
        assertEquals(12, stream.turn)
        assertTrue(stream.blocks.isEmpty())
    }

    @Test
    fun `disconnecting keeps the content and the banner is taken back on reconnect`() {
        val stream = TurnStream()
        stream.apply(work(SessionState.THINKING, 1))
        stream.markDisconnected()
        assertTrue(stream.disconnected)
        assertEquals(1, stream.blocks.size, "a dropped socket must not erase the turn")

        stream.markReconnected()
        assertFalse(stream.disconnected)
        assertEquals(1, stream.blocks.size)
    }

    @Test
    fun `snapshots are stable values a screen can hold`() {
        val stream = TurnStream()
        stream.apply(work(SessionState.THINKING, 1))
        val before = stream.snapshot()
        stream.apply(work(SessionState.TOOL, 2))
        val after = stream.snapshot()

        // The earlier snapshot still describes what was on screen then: publishing one
        // to a StateFlow must not have it mutate underneath the reader.
        assertEquals(1, before.blocks.single().states.size)
        assertEquals(2, after.blocks.single().states.size)
        assertEquals(SessionState.TOOL, after.current)
    }

    @Test
    fun `the block list is bounded`() {
        val stream = TurnStream()
        for (round in 0 until TurnStream.MAX_BLOCKS + 10) {
            // A status run followed by a body opens two blocks per round; past the cap
            // the oldest fall off so a pathological stream cannot grow without bound.
            stream.apply(work(SessionState.THINKING, round * 2L + 1))
            stream.apply(StateFrame.begin(SessionState.STREAM_NARRATION, round * 2L + 2, "正文$round"))
        }
        assertTrue(stream.blocks.size <= TurnStream.MAX_BLOCKS)
        assertTrue(stream.truncated)
    }
}
