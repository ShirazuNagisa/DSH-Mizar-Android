package me.shirazu.mizar.kit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The data half of the live turn stream: state frames in, a list of drawable blocks
 * out. No UI types in this file on purpose -- a screen should be able to say "draw
 * these blocks" without knowing a single rule about intervals, snapshots or text
 * offsets.
 *
 * Three things live here, in order:
 *
 *   [StreamBlock]        one status run or one body of text
 *   [SessionStreamResult] the whole stream as the host replays it on (re)connect
 *   [TurnStream]         the reducer that turns frames into blocks
 *
 * The rules are the state-channel spec's (`protocol/state-channel-v1.md` §4/§5/§9),
 * with one addition that only matters to the display: `stream_final` is promoted
 * onto the last body when it repeats it word for word, so a turn that ended on a
 * narration is not printed twice.
 *
 * This is a read-only port of `iOS/MizarKit/Sources/MizarKit/TurnStream.swift`; the
 * two are meant to fold the same frames into the same blocks.
 */

/** One block of a turn's visible stream. */
data class StreamBlock(
    /** Stable identity for a list key. */
    val id: Int,
    /**
     * The interval the block opened on. A status block keeps the interval of its
     * first state even after later states advance past it.
     */
    val interval: Long,
    val kind: Kind,
    /**
     * Status only: the work states seen inside this run, in order, deduplicated
     * adjacently. Waiting states and body states never appear here.
     */
    val states: List<SessionState> = emptyList(),
    /** Body only: the text accumulated for this block. */
    val text: String = "",
    /** Body only: whether this block is confirmed to be the final answer. */
    val isFinal: Boolean = false,
) {
    enum class Kind { STATUS, BODY }
}

/**
 * `session.stream` — the host's journal for the turn that is, or was, running.
 *
 * `entries` is the frame log in order: the first entry of an interval is a snapshot
 * of that interval's text, later entries of the same interval are deltas. `state` /
 * `interval` / `textSeq` describe where the stream stands *now*, so live frames can
 * continue from the replay without a gap.
 *
 * A host that keeps no journal (a session that never ran, or a desktop restart)
 * answers an empty list with `state: 0`; that is a normal answer, not a failure.
 */
@Serializable
data class SessionStreamResult(
    val sessionId: String? = null,
    /** The turn this journal describes, when the engine numbers turns. */
    val turn: Int? = null,
    /** The wire state code at the moment the replay was taken; `0` is idle. */
    val state: Int = 0,
    /** The interval the stream is on now. */
    val interval: Long = 0,
    /** How many bytes of this interval's text the host has sent. */
    val textSeq: Long = 0,
    /** The host dropped earlier entries: draw "earlier steps were omitted" above them. */
    val truncated: Boolean = false,
    val entries: List<StreamEntry> = emptyList(),
)

/** One interval in a `session.stream` replay: a snapshot, then the deltas after it. */
@Serializable
data class StreamEntry(
    val interval: Long,
    val state: Int,
    val text: String = "",
    /** Whether this entry's text is the turn's final answer. */
    @SerialName("final") val isFinal: Boolean = false,
)

/** An immutable snapshot of a [TurnStream], safe to publish as a `StateFlow` value. */
data class TurnStreamSnapshot(
    val turn: Int? = null,
    val blocks: List<StreamBlock> = emptyList(),
    val current: SessionState? = null,
    val currentInterval: Long? = null,
    val waiting: SessionState? = null,
    val finished: Boolean = false,
    val endState: SessionState? = null,
    val disconnected: Boolean = false,
    val truncated: Boolean = false,
    /** The text of the body the agent is still writing, for the typewriter. */
    val liveText: String = "",
)

/**
 * The reducer: one turn's frames folded into drawable blocks.
 *
 * A mutable class rather than a value type because it is fed from the socket's read
 * loop once per frame; take [snapshot] to hand a screen a stable copy. It is **not**
 * thread-safe: apply frames from one coroutine (the connection's reader), publish
 * snapshots to whoever draws.
 */
class TurnStream {

    /** The turn number, when the engine numbers turns. */
    var turn: Int? = null
        private set

    /**
     * Everything the stream has shown, oldest first.
     *
     * The list is replaced on every change rather than mutated in place, so a
     * reference taken before a frame still describes what was on screen then.
     */
    var blocks: List<StreamBlock> = emptyList()
        private set

    /** The state of the most recently applied frame. */
    var current: SessionState? = null
        private set

    /** The interval the most recently applied frame belonged to. */
    var currentInterval: Long? = null
        private set

    /**
     * A waiting state (approval, question). Shown as the current line only; it never
     * becomes a block and the next work state or body clears it.
     */
    var waiting: SessionState? = null
        private set

    /** Whether the turn has ended ([SessionState.COMPLETED] / [FAILED] / [PAUSED]). */
    var finished: Boolean = false
        private set

    /** How it ended, when it did. */
    var endState: SessionState? = null
        private set

    /** The connection went away. The screen draws "remote connection failed" after the last block. */
    var disconnected: Boolean = false
        private set

    /** Part of the stream is missing: the host truncated the replay, or blocks fell off the cap. */
    var truncated: Boolean = false
        private set

    /** Body bytes already received in the interval being written. */
    private var bodyBytes: Int = 0

    /**
     * The body block currently accepting text, and the interval that text belongs to.
     * Tracked by id rather than by interval because a promoted block keeps the
     * interval it opened on while it takes over the next interval's deltas.
     */
    private var bodyBlockId: Int? = null

    private var bodyInterval: Long? = null

    /** Source of [StreamBlock.id]. */
    private var nextBlockId: Int = 1

    /** The text of the body the agent is still writing, for the typewriter. */
    val liveText: String
        get() {
            if (finished) return ""
            val last = blocks.lastOrNull() ?: return ""
            return if (last.kind == StreamBlock.Kind.BODY) last.text else ""
        }

    /** A stable copy of everything a screen draws. */
    fun snapshot(): TurnStreamSnapshot = TurnStreamSnapshot(
        turn = turn,
        blocks = blocks,
        current = current,
        currentInterval = currentInterval,
        waiting = waiting,
        finished = finished,
        endState = endState,
        disconnected = disconnected,
        truncated = truncated,
        liveText = liveText,
    )

    // ── Applying frames ─────────────────────────────────────────────────────

    /**
     * Apply one frame.
     *
     * @returns whether anything the person can see changed, so a view can skip
     *   redrawing on heartbeats.
     */
    fun apply(frame: StateFrame): Boolean {
        val state = frame.state

        // "Idle" and unknown codes draw nothing special: no block, no line, no change.
        if (state == SessionState.IDLE || state == SessionState.UNKNOWN) return false

        // An older interval is a late frame. Whatever it says, the screen has moved on.
        val seen = currentInterval
        if (seen != null && frame.interval < seen) return false

        var changed = false

        // A non-terminal frame after an ending means the same stream carried on (the
        // agent answered the question it was waiting on, or a follow-up landed on the
        // same turn). Only the screen's ending is undone; the blocks stay, and a
        // genuinely new turn goes through `beginTurn`.
        if (finished && !isTerminal(state)) {
            finished = false
            endState = null
            changed = true
        }

        when (state) {
            SessionState.THINKING,
            SessionState.TOOL,
            SessionState.EDITING,
            SessionState.SUBAGENT,
            SessionState.BACKGROUND,
            SessionState.COMPACTING,
            SessionState.RETRYING,
            SessionState.QUEUED,
            -> return applyWork(state, frame.interval) || changed

            SessionState.AWAITING_APPROVAL,
            SessionState.AWAITING_USER,
            -> return applyWaiting(state, frame.interval) || changed

            SessionState.STREAM_NARRATION,
            SessionState.STREAM_FINAL,
            -> return applyBody(frame) || changed

            SessionState.COMPLETED,
            SessionState.FAILED,
            SessionState.PAUSED,
            -> return applyEnd(state, frame.interval) || changed

            SessionState.IDLE,
            SessionState.UNKNOWN,
            -> return false
        }
    }

    /**
     * Rebuild the whole stream from a replay (reconnect, opening a session).
     *
     * Everything is cleared first; the entries are then folded through the same rules
     * a live stream uses, so there is exactly one implementation of the
     * merge/promotion/offset logic.
     */
    fun replace(replay: SessionStreamResult) {
        reset()
        turn = replay.turn
        truncated = replay.truncated

        var previousInterval: Long? = null
        var offset = 0

        for (entry in replay.entries) {
            val opensInterval = previousInterval != entry.interval
            var flags = StateFrameFlags.NONE
            var textSeq = 0L

            if (opensInterval) {
                // The first entry of an interval is that interval's snapshot.
                flags = StateFrameFlags.SNAPSHOT or StateFrameFlags.APPEND
            } else {
                flags = StateFrameFlags.APPEND
                textSeq = offset.toLong()
            }
            if (entry.isFinal) flags = flags or StateFrameFlags.FINAL

            apply(
                StateFrame(
                    state = SessionState.fromCode(entry.state),
                    interval = entry.interval,
                    textSeq = textSeq,
                    flags = flags,
                    text = entry.text,
                ),
            )

            offset = if (opensInterval) entry.text.encodeToByteArray().size else offset + entry.text.encodeToByteArray().size
            previousInterval = entry.interval
        }

        // Continue from where the replay says the stream stands, so the next live
        // append is accepted rather than judged a lost frame.
        val landed = SessionState.fromCode(replay.state)
        current = landed
        currentInterval = maxOf(currentInterval ?: replay.interval, replay.interval)
        bodyBytes = replay.textSeq.toInt()

        // A replay may end without an entry for its own current state; honour the
        // ending either way rather than waiting for a frame that will never come.
        if (isTerminal(landed)) {
            finished = true
            endState = landed
            waiting = null
        }
    }

    /** The connection dropped. Content is kept: the failure is drawn after it. */
    fun markDisconnected() {
        disconnected = true
    }

    /** The connection is back. Content is rebuilt by [replace], so this only takes the banner away. */
    fun markReconnected() {
        disconnected = false
    }

    /**
     * Start the next turn on the same session: empty the stream, keep the turn
     * number (pass `null` to keep the one already known).
     */
    fun beginTurn(turn: Int? = null) {
        val carried = turn ?: this.turn
        reset()
        this.turn = carried
    }

    // ── Rule groups ─────────────────────────────────────────────────────────

    /** A work state. Consecutive work states are one status block. */
    private fun applyWork(state: SessionState, interval: Long): Boolean {
        var changed = false
        currentInterval = interval
        current = state

        if (waiting != null) {
            waiting = null
            changed = true
        }

        // A status run ends the moment text starts, so a body after it is a new block
        // even if another body block is still remembered.
        bodyBlockId = null
        bodyInterval = null

        val lastIndex = blocks.lastIndex
        if (lastIndex >= 0 && blocks[lastIndex].kind == StreamBlock.Kind.STATUS) {
            val block = blocks[lastIndex]
            if (block.states.lastOrNull() != state) {
                blocks = blocks.replace(lastIndex, block.copy(states = block.states + state))
                changed = true
            }
        } else {
            blocks = blocks + StreamBlock(
                id = takeId(),
                interval = interval,
                kind = StreamBlock.Kind.STATUS,
                states = listOf(state),
            )
            trim()
            changed = true
        }
        return changed
    }

    /** A waiting state: a line under the stream, never a block. */
    private fun applyWaiting(state: SessionState, interval: Long): Boolean {
        currentInterval = interval
        current = state
        if (waiting == state) return false
        waiting = state
        return true
    }

    /** An ending. The blocks stay: what happened still happened. */
    private fun applyEnd(state: SessionState, interval: Long): Boolean {
        val changed = waiting != null || !finished || endState != state
        currentInterval = interval
        current = state
        waiting = null
        finished = true
        endState = state
        bodyBlockId = null
        bodyInterval = null
        return changed
    }

    /** Body text: narration or the final answer. */
    private fun applyBody(frame: StateFrame): Boolean {
        val state = frame.state
        val isSnapshot = StateFrameFlag.SNAPSHOT in frame.flags
        val carriesText = StateFrameFlag.APPEND in frame.flags
        val endsHere = StateFrameFlag.FINAL in frame.flags
        val opensInterval = frame.interval != currentInterval

        var changed = false
        currentInterval = frame.interval
        current = state

        if (waiting != null) {
            waiting = null
            changed = true
        }

        // A heartbeat on a stream interval: liveness only, nothing to draw.
        if (!isSnapshot && !endsHere && frame.text.isEmpty()) return changed

        // Promotion. A final answer that says exactly what the last body already says
        // is that body, now confirmed. Printing it a second time would read like the
        // model repeated itself.
        //
        // "Exactly" means the whole text, so this can only fire on a snapshot or a
        // FINAL -- never on a delta, which is a fragment by nature. `opensInterval`
        // lets a replayed answer re-confirm a block that is already final instead of
        // duplicating it on reconnect.
        val lastIndex = blocks.lastIndex
        if (state == SessionState.STREAM_FINAL && (isSnapshot || endsHere) && frame.text.isNotEmpty() &&
            lastIndex >= 0 && blocks[lastIndex].kind == StreamBlock.Kind.BODY &&
            blocks[lastIndex].text == frame.text &&
            (!blocks[lastIndex].isFinal || opensInterval)
        ) {
            if (!blocks[lastIndex].isFinal) {
                blocks = blocks.replace(lastIndex, blocks[lastIndex].copy(isFinal = true))
                changed = true
            }
            bodyBlockId = blocks[lastIndex].id
            bodyInterval = frame.interval
            bodyBytes = blocks[lastIndex].text.encodeToByteArray().size
            return changed
        }

        // An older host re-announces the whole turn's text as the final snapshot
        // (before this build the kernel concatenated every narration message). The
        // last body is still the answer; the prefix is narration already on screen,
        // so promoting it is the whole update. Exact concatenation only -- a suffix
        // test would promote "好了" over a real answer "改好了".
        if (state == SessionState.STREAM_FINAL && isSnapshot && frame.text.isNotEmpty() &&
            lastIndex >= 0 && blocks[lastIndex].kind == StreamBlock.Kind.BODY &&
            blocks[lastIndex].text.isNotEmpty()
        ) {
            val narration = blocks
                .filter { it.kind == StreamBlock.Kind.BODY && !it.isFinal }
                .joinToString("") { it.text }
            if (narration == frame.text) {
                if (!blocks[lastIndex].isFinal) {
                    blocks = blocks.replace(lastIndex, blocks[lastIndex].copy(isFinal = true))
                    changed = true
                }
                bodyBlockId = blocks[lastIndex].id
                bodyInterval = frame.interval
                bodyBytes = blocks[lastIndex].text.encodeToByteArray().size
                return changed
            }
        }

        // A FINAL with no text (the usual shape) only closes the answer already there.
        // Opening an empty block for it would draw an empty bubble.
        if (endsHere && !carriesText && frame.text.isEmpty() && !isSnapshot) {
            val index = blocks.indexOfLast { it.kind == StreamBlock.Kind.BODY }
            if (index >= 0 && !blocks[index].isFinal) {
                blocks = blocks.replace(index, blocks[index].copy(isFinal = true))
                return true
            }
        }

        // Where does the text go? The body block this interval has been writing to, if
        // any. A new interval opens a new block; a status run never takes text, it
        // freezes and the body starts a block of its own.
        val target: Int? = bodyBlockId?.let { id ->
            if (bodyInterval != frame.interval) null else blocks.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        }

        // A delta must land exactly where the body ended. Text at any other offset
        // means a frame was lost; gluing it on would silently corrupt the answer, so
        // it is dropped and the next snapshot repairs the block. A block that is about
        // to be created starts at zero, hence the `?: 0`.
        if (carriesText && !isSnapshot && frame.textSeq != (if (target != null) bodyBytes.toLong() else 0L)) {
            return changed
        }

        // An interval's first frame is an empty snapshot -- the `begin` of a
        // text-carrying state. It is not a body yet: drawing it would open an empty
        // block, and the snapshot that follows would fill that block with text the
        // previous block already shows. Wait for the text.
        if (isSnapshot && frame.text.isEmpty()) return changed

        var index: Int
        if (target != null) {
            index = target
        } else {
            val block = StreamBlock(
                id = takeId(),
                interval = frame.interval,
                kind = StreamBlock.Kind.BODY,
                // A body opened by `stream_final` *is* the answer; it does not have to
                // wait for the FINAL flag to be styled as one.
                isFinal = state == SessionState.STREAM_FINAL,
            )
            blocks = blocks + block
            index = blocks.lastIndex
            bodyBytes = 0
            bodyBlockId = block.id
            bodyInterval = frame.interval
            trim()
            // `trim` drops from the front, so the new block is no longer at the index
            // it was appended at.
            index = blocks.indexOfFirst { it.id == block.id }.takeIf { it >= 0 } ?: blocks.lastIndex
            changed = true
        }

        if (isSnapshot) {
            if (blocks[index].text != frame.text) {
                blocks = blocks.replace(index, blocks[index].copy(text = frame.text))
                changed = true
            }
            bodyBytes = frame.text.encodeToByteArray().size
        } else if (carriesText && frame.text.isNotEmpty()) {
            blocks = blocks.replace(index, blocks[index].copy(text = blocks[index].text + frame.text))
            bodyBytes += frame.text.encodeToByteArray().size
            changed = true
        }

        if (endsHere && !blocks[index].isFinal) {
            blocks = blocks.replace(index, blocks[index].copy(isFinal = true))
            changed = true
        }
        return changed
    }

    // ── Housekeeping ────────────────────────────────────────────────────────

    private fun reset() {
        turn = null
        blocks = emptyList()
        current = null
        currentInterval = null
        waiting = null
        finished = false
        endState = null
        disconnected = false
        truncated = false
        bodyBytes = 0
        bodyBlockId = null
        bodyInterval = null
        nextBlockId = 1
    }

    private fun takeId(): Int {
        val id = nextBlockId
        nextBlockId += 1
        return id
    }

    /** Keep the block list bounded, oldest first. The newest block is never the one dropped. */
    private fun trim() {
        while (blocks.size > MAX_BLOCKS) {
            blocks = blocks.drop(1)
            truncated = true
        }
        val id = bodyBlockId
        if (id != null && blocks.none { it.id == id }) {
            bodyBlockId = null
            bodyInterval = null
        }
    }

    private fun isTerminal(state: SessionState): Boolean = when (state) {
        SessionState.COMPLETED, SessionState.FAILED, SessionState.PAUSED -> true
        else -> false
    }

    companion object {
        /** Longest history kept. Past this the oldest blocks fall off. */
        const val MAX_BLOCKS: Int = 200
    }
}

/** Replace one element, returning a list that is safe to hand to a snapshot. */
private fun List<StreamBlock>.replace(index: Int, value: StreamBlock): List<StreamBlock> {
    val copy = toMutableList()
    copy[index] = value
    return copy
}
