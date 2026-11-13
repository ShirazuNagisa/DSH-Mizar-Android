package me.shirazu.dshmizar.domain

import me.shirazu.mizar.kit.SessionState
import me.shirazu.mizar.kit.StateFrame
import me.shirazu.mizar.kit.StateFrameFlags
import me.shirazu.mizar.kit.StreamBlock
import me.shirazu.mizar.kit.TurnStream
import me.shirazu.mizar.kit.TurnStreamSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-side projection of the live turn: fold rules, ending/header inputs,
 * stale rule, copy text, and the words a state run settles into.
 *
 * The reduction itself is kit's `TurnStream` (and its own test suite); one test
 * here drives real frames through it and then through [toUi], so the adapter and
 * the display rules are pinned against the producer's actual shapes.
 */
class LiveTurnViewTest {

    private fun status(vararg states: SessionState) = StreamBlock(
        id = id++,
        interval = id.toLong(),
        kind = StreamBlock.Kind.STATUS,
        states = states.toList(),
    )

    private fun body(text: String, final: Boolean = false) = StreamBlock(
        id = id++,
        interval = id.toLong(),
        kind = StreamBlock.Kind.BODY,
        text = text,
        isFinal = final,
    )

    private var id = 1

    private fun view(
        blocks: List<StreamBlock>,
        finished: Boolean = false,
        endState: SessionState? = null,
        running: Boolean = !finished,
        history: LiveTurnHistory? = null,
        fallbackAnswer: String? = null,
        lastFrameAtMillis: Long? = null,
        current: SessionState? = null,
        startedAtMillis: Long? = 1_000L,
    ) = LiveTurnView(
        sessionId = "s1",
        stream = TurnStreamSnapshot(
            turn = 3,
            blocks = blocks,
            finished = finished,
            endState = endState,
            current = current,
            liveText = blocks.lastOrNull { it.kind == StreamBlock.Kind.BODY }?.text.orEmpty(),
        ),
        turn = 3,
        startedAtMillis = startedAtMillis,
        endedAtMillis = if (finished) 11_000L else null,
        running = running,
        history = history,
        fallbackAnswer = fallbackAnswer,
        lastFrameAtMillis = lastFrameAtMillis,
    )

    // ── Folding ─────────────────────────────────────────────────────────────

    @Test
    fun `while running every block is the process and nothing is folded`() {
        val blocks = listOf(status(SessionState.THINKING), body("thinking out loud"))
        val plan = LiveTurnFold.plan(blocks, live = true, expanded = false)
        assertEquals(blocks, plan.process)
        assertNull(plan.summary)
        assertTrue(plan.after.isEmpty())
        assertFalse(plan.canExpand)
        assertTrue(plan.showsProcess)
    }

    @Test
    fun `a settled turn folds the process behind its answer`() {
        val narration = body("narration")
        val answer = body("the answer", final = true)
        val blocks = listOf(status(SessionState.THINKING), narration, answer)
        val plan = LiveTurnFold.plan(blocks, live = false, expanded = false)
        assertEquals(2, plan.process.size)
        assertEquals(StreamBlock.Kind.STATUS, plan.process[0].kind)
        assertEquals(narration, plan.process[1])
        assertEquals(answer, plan.summary)
        assertTrue(plan.canExpand)
        assertFalse(plan.showsProcess)

        val opened = LiveTurnFold.plan(blocks, live = false, expanded = true)
        assertTrue(opened.showsProcess)
    }

    @Test
    fun `blocks after the answer stay after it`() {
        val answer = body("answer", final = true)
        val afterwards = body("a follow-up")
        val plan = LiveTurnFold.plan(listOf(answer, afterwards), live = false, expanded = false)
        assertEquals(answer, plan.summary)
        assertEquals(listOf(afterwards), plan.after)
    }

    @Test
    fun `a turn with no final body has nothing to fold`() {
        val blocks = listOf(status(SessionState.THINKING), body("only narration"))
        val plan = LiveTurnFold.plan(blocks, live = false, expanded = false)
        assertEquals(blocks, plan.process)
        assertNull(plan.summary)
        assertFalse(plan.canExpand)
        assertTrue(plan.showsProcess)
    }

    @Test
    fun `the summary is the last final body, not an earlier one`() {
        val early = body("an earlier draft", final = true)
        val late = body("the answer", final = true)
        val plan = LiveTurnFold.plan(listOf(early, late), live = false, expanded = false)
        assertEquals(late, plan.summary)
        assertEquals(listOf(early), plan.process)
    }

    @Test
    fun `sections keep only their newest forty blocks until asked`() {
        val blocks = (1..45).map { body("block $it") }
        val folded = LiveTurnFold.visible(blocks, unfolded = false)
        assertEquals(40, folded.size)
        assertEquals("block 6", folded.first().text)
        assertEquals(5, LiveTurnFold.foldedCount(blocks, unfolded = false))
        assertEquals(45, LiveTurnFold.visible(blocks, unfolded = true).size)
        assertEquals(0, LiveTurnFold.foldedCount(blocks, unfolded = true))
    }

    // ── Adapter & header inputs ─────────────────────────────────────────────

    @Test
    fun `the adapter carries the turn and the screen's beliefs`() {
        val snapshot = TurnStreamSnapshot(turn = 7, finished = false)
        val ui = snapshot.toUi(sessionId = "abc", running = true, typing = false, lastFrameAtMillis = 42L)
        assertEquals(7, ui.turn)
        assertEquals("abc", ui.sessionId)
        assertTrue(ui.live)
        assertEquals(42L, ui.lastFrameAtMillis)
        assertFalse(ui.typing)
    }

    @Test
    fun `the stream ending wins over the screen still believing it runs`() {
        val ui = view(emptyList(), finished = true, endState = SessionState.FAILED, running = true)
        assertFalse(ui.live)
        assertTrue(ui.settled)
        assertEquals(LiveTurnEnding.FAILED, ui.ending)
        assertEquals(10_000L, ui.effectiveEndedAtMillis!! - ui.effectiveStartedAtMillis!!)
    }

    @Test
    fun `history ending applies when the stream has not delivered one`() {
        val ui = view(
            emptyList(),
            finished = false,
            running = false,
            history = LiveTurnHistory(running = false, failed = false, startedAtMillis = 5L, endedAtMillis = 9L),
            startedAtMillis = null,
        )
        assertTrue(ui.settled)
        assertEquals(LiveTurnEnding.COMPLETED, ui.ending)
        assertEquals(5L, ui.effectiveStartedAtMillis)
        assertEquals(9L, ui.effectiveEndedAtMillis)
    }

    @Test
    fun `a paused stream reads as interrupted`() {
        val ui = view(emptyList(), finished = true, endState = SessionState.PAUSED)
        assertEquals(LiveTurnEnding.PAUSED, ui.ending)
    }

    // ── Stale ───────────────────────────────────────────────────────────────

    @Test
    fun `twelve silent seconds make a heartbeat state stale`() {
        val ui = view(
            emptyList(),
            current = SessionState.TOOL,
            lastFrameAtMillis = 1_000L,
        )
        assertFalse(ui.isStale(nowMillis = 12_999L))
        assertTrue(ui.isStale(nowMillis = 13_000L))
    }

    @Test
    fun `a terminal state is never stale and a missing frame time is not stale`() {
        val finished = view(emptyList(), current = SessionState.COMPLETED, lastFrameAtMillis = 0L)
        assertFalse(finished.isStale(nowMillis = 60_000L))
        val unknown = view(emptyList(), current = SessionState.TOOL, lastFrameAtMillis = null)
        assertFalse(unknown.isStale(nowMillis = 60_000L))
    }

    // ── Copy text ───────────────────────────────────────────────────────────

    @Test
    fun `copy text prefers the final answer and falls back to the last body`() {
        assertEquals("answer", view(listOf(body("narration"), body("answer", final = true))).copyText)
        assertEquals("narration", view(listOf(body("narration"))).copyText)
        assertEquals("history answer", view(emptyList(), fallbackAnswer = "history answer").copyText)
    }

    // ── Words ───────────────────────────────────────────────────────────────

    @Test
    fun `process categories dedupe adjacent states and keep narrative order`() {
        val categories = ProcessCategory.of(
            listOf(
                SessionState.TOOL,
                SessionState.TOOL,
                SessionState.EDITING,
                SessionState.THINKING,
                SessionState.COMPLETED,
            ),
        )
        assertEquals(listOf(ProcessCategory.TOOLS, ProcessCategory.EDIT, ProcessCategory.THINKING), categories)
    }

    @Test
    fun `chinese composition joins two with and drops one repeated 已`() {
        val text = LiveStateWording.completion(
            listOf(SessionState.THINKING, SessionState.TOOL),
            isChinese = true,
        )
        assertEquals("已分析并调用工具", text)
    }

    @Test
    fun `english composition joins three with commas and and`() {
        val text = LiveStateWording.completion(
            listOf(SessionState.THINKING, SessionState.TOOL, SessionState.EDITING),
            isChinese = false,
        )
        assertEquals("Analysed, Edited files and Called tools", text)
    }

    @Test
    fun `running wording is the protocol's own state name`() {
        assertEquals("调用工具中", LiveStateWording.running(SessionState.TOOL, isChinese = true))
        assertEquals("Running tool", LiveStateWording.running(SessionState.TOOL, isChinese = false))
    }

    // ── Real frames through the kit reducer ─────────────────────────────────

    @Test
    fun `frames through the kit reducer become the settled folded answer`() {
        val stream = TurnStream()
        stream.apply(StateFrame(SessionState.THINKING, interval = 1))
        stream.apply(
            StateFrame(
                state = SessionState.STREAM_FINAL,
                interval = 2,
                textSeq = 0,
                flags = StateFrameFlags.SNAPSHOT or StateFrameFlags.APPEND,
                text = "改好了。",
            ),
        )
        stream.apply(
            StateFrame(
                state = SessionState.STREAM_FINAL,
                interval = 2,
                textSeq = 12,
                flags = StateFrameFlags.APPEND,
                text = "测试通过。",
            ),
        )
        stream.apply(
            StateFrame(
                state = SessionState.STREAM_FINAL,
                interval = 2,
                textSeq = 27,
                flags = StateFrameFlags.FINAL,
            ),
        )
        // A `stream_final` FINAL only confirms the answer; the turn is over when a
        // terminal state lands (the host always sends `completed` after it).
        stream.apply(StateFrame(SessionState.COMPLETED, interval = 3))
        val ui = stream.snapshot().toUi(sessionId = "s1")
        assertTrue(ui.settled)
        assertEquals(LiveTurnEnding.COMPLETED, ui.ending)
        val plan = ui.fold(expanded = false)
        val answer = plan.summary
        assertEquals("改好了。测试通过。", answer?.text)
        assertTrue(answer?.isFinal == true)
        assertTrue(plan.process.isNotEmpty())
    }
}
