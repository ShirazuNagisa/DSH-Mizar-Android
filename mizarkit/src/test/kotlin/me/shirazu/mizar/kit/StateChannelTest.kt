package me.shirazu.mizar.kit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The receiver's view of one session's state channel.
 *
 * Ported from `AGI Mizar/crates/agi-protocol/src/state_frame.rs`'s receiver tests, so
 * the two ends cannot disagree about what "this frame is newer" means. Every case
 * here is a rule in `protocol/state-channel-v1.md` §3-§5.
 */
class StateChannelTest {

    @Test
    fun `a new interval replaces state and text`() {
        val channel = StateChannel()
        assertEquals(
            StateApplied.BEGAN,
            channel.apply(StateFrame.begin(SessionState.STREAM_FINAL, 1, "旧"), 0),
        )
        assertEquals(SessionState.STREAM_FINAL, channel.state)
        assertEquals("旧", channel.text)

        assertEquals(
            StateApplied.BEGAN,
            channel.apply(StateFrame.begin(SessionState.THINKING, 2), 10),
        )
        assertEquals(SessionState.THINKING, channel.state)
        assertEquals("", channel.text)
        assertEquals(2L, channel.interval)
    }

    @Test
    fun `the receiver follows intervals and appends`() {
        val channel = StateChannel()
        assertEquals(
            StateApplied.BEGAN,
            channel.apply(StateFrame.begin(SessionState.STREAM_FINAL, 1, "第一段"), 0),
        )
        // 第一段 is 9 UTF-8 bytes, so the next delta names byte 9.
        assertEquals(
            StateApplied.UPDATED,
            channel.apply(StateFrame.append(SessionState.STREAM_FINAL, 1, 9, "第二段"), 100),
        )
        assertEquals("第一段第二段", channel.text)
        assertEquals(SessionState.STREAM_FINAL, channel.state)
    }

    @Test
    fun `a late frame from an older interval changes nothing`() {
        val channel = StateChannel()
        channel.apply(StateFrame.begin(SessionState.STREAM_FINAL, 2, "现在"), 0)
        assertEquals(StateApplied.STALE, channel.apply(StateFrame.begin(SessionState.IDLE, 1), 100))
        assertEquals(SessionState.STREAM_FINAL, channel.state)
        assertEquals("现在", channel.text)
        assertEquals(2L, channel.interval)
    }

    @Test
    fun `an append that skips text is refused rather than spliced`() {
        val channel = StateChannel()
        assertEquals(StateApplied.BEGAN, channel.apply(StateFrame.begin(SessionState.STREAM_NARRATION, 1), 0))
        assertEquals(
            StateApplied.UPDATED,
            channel.apply(StateFrame.append(SessionState.STREAM_NARRATION, 1, 0, "ab"), 1),
        )
        assertEquals("ab", channel.text)

        // Byte 5, not 2: the frame carrying bytes 2..5 was lost.
        assertEquals(
            StateApplied.STALE,
            channel.apply(StateFrame.append(SessionState.STREAM_NARRATION, 1, 5, "cd"), 2),
        )
        assertEquals("ab", channel.text, "a gap must not be spliced")

        // The snapshot after the gap is authoritative again.
        assertEquals(
            StateApplied.UPDATED,
            channel.apply(StateFrame.snapshot(SessionState.STREAM_NARRATION, 1, text = "abcde"), 3),
        )
        assertEquals("abcde", channel.text)
    }

    @Test
    fun `a snapshot repairs a missed chunk`() {
        val channel = StateChannel()
        channel.apply(StateFrame.begin(SessionState.STREAM_FINAL, 1, "开头"), 0)
        channel.apply(StateFrame.snapshot(SessionState.STREAM_FINAL, 1, text = "开头，补全了"), 5)
        assertEquals("开头，补全了", channel.text)
    }

    @Test
    fun `a heartbeat renews and draws nothing`() {
        val channel = StateChannel()
        channel.apply(StateFrame.begin(SessionState.THINKING, 3), 0)
        assertEquals(StateApplied.RENEWED, channel.apply(StateFrame.heartbeat(SessionState.THINKING, 3), 4_000))
        assertEquals(SessionState.THINKING, channel.state)
        assertFalse(channel.finished)
        // The renewal moved the clock: a heartbeat at 4s keeps the interval alive.
        assertFalse(channel.isStale(4_000 + StateFrame.STALE_AFTER_MILLIS))
        assertTrue(channel.isStale(4_000 + StateFrame.STALE_AFTER_MILLIS + 1))
    }

    @Test
    fun `a final frame ends the stream`() {
        val channel = StateChannel()
        channel.apply(StateFrame.begin(SessionState.STREAM_FINAL, 1, "改好了。"), 0)
        assertEquals(
            StateApplied.FINISHED,
            channel.apply(StateFrame.finish(SessionState.STREAM_FINAL, 1, 12), 5),
        )
        assertTrue(channel.finished)
    }

    @Test
    fun `silence ages out a held state but not a terminal one`() {
        val held = StateChannel()
        held.apply(StateFrame.begin(SessionState.TOOL, 1), 0)
        assertFalse(held.isStale(StateFrame.STALE_AFTER_MILLIS))
        assertTrue(held.isStale(StateFrame.STALE_AFTER_MILLIS + 1))

        val terminal = StateChannel()
        terminal.apply(StateFrame.begin(SessionState.COMPLETED, 2), 0)
        // "failed"/"completed" stays true after the host disconnects, because the
        // ending already happened; a disconnect does not undo it.
        assertFalse(terminal.isStale(600_000))
    }

    @Test
    fun `a receiver with no frame is not stale`() {
        assertFalse(StateChannel().isStale(Long.MAX_VALUE))
        assertNull(StateChannel().state)
        assertNull(StateChannel().interval)
        assertEquals("", StateChannel().text)
    }

    @Test
    fun `a byte offset counts UTF-8 bytes, not characters`() {
        val channel = StateChannel()
        channel.apply(StateFrame.begin(SessionState.STREAM_FINAL, 1, "🎯"), 0)
        // One emoji is four bytes; a delta naming four is contiguous, one naming one is
        // a character count and must be rejected.
        assertEquals(StateApplied.STALE, channel.apply(StateFrame.append(SessionState.STREAM_FINAL, 1, 1, "x"), 1))
        assertEquals(StateApplied.UPDATED, channel.apply(StateFrame.append(SessionState.STREAM_FINAL, 1, 4, "x"), 2))
        assertEquals("🎯x", channel.text)
    }
}
