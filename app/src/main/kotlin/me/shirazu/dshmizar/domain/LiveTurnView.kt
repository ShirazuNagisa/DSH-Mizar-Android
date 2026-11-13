package me.shirazu.dshmizar.domain

import me.shirazu.mizar.kit.SessionState
import me.shirazu.mizar.kit.StreamBlock
import me.shirazu.mizar.kit.TurnStreamSnapshot

/**
 * The app-side projection of one live turn.
 *
 * The reduction itself lives in `mizarkit/TurnStream.kt` (the single implementation
 * shared with the wire rules); this file adds only what a screen needs on top of the
 * immutable [TurnStreamSnapshot]: the turn's identity/times, whether it is still
 * running, whether the typewriter may start, the answer the history holds when the
 * stream carried none, and the fold plan a settled turn opens with.
 *
 * The display rules are ported from iOS `Views/Conversation/ConversationStreamView.swift`
 * (`StreamFold`, `StreamHeader`, `StreamWording`) and `LiveProcessLine.swift`
 * (`ProcessCategory`, `ProcessWording`). No Android type appears here.
 */

/** One kind of work a turn can have done, as DSH names it, in narration order. */
enum class ProcessCategory {
    THINKING,
    READ,
    EDIT,
    COMMANDS,
    SEARCH,
    SUBAGENTS,
    PLAN,
    QUESTIONS,
    TOOLS,
    BACKGROUND,
    COMPACTING,
    ;

    companion object {
        /** The category a work state implies, when it implies one. */
        fun from(state: SessionState): ProcessCategory? = when (state) {
            SessionState.THINKING -> THINKING
            SessionState.TOOL -> TOOLS
            SessionState.EDITING -> EDIT
            SessionState.SUBAGENT -> SUBAGENTS
            SessionState.BACKGROUND -> BACKGROUND
            SessionState.COMPACTING -> COMPACTING
            else -> null
        }

        /** The categories a status run touched, adjacent duplicates removed, in order. */
        fun of(states: List<SessionState>): List<ProcessCategory> {
            val out = mutableListOf<ProcessCategory>()
            for (state in states) {
                val category = from(state) ?: continue
                if (out.lastOrNull() != category) out += category
            }
            return out
        }
    }
}

/**
 * Composing "已完成分析并执行了命令" from the categories a segment touched.
 *
 * The wording is the app's; the protocol only carries codes. Same rules as DSH:
 * two join with "并" / " and ", three or more with "，" / ", ", and anything past
 * three is elided with "等" / " etc.".
 */
object ProcessWording {

    fun compose(
        categories: List<ProcessCategory>,
        labels: (ProcessCategory) -> String,
        isChinese: Boolean,
    ): String {
        val ordered = categories.distinct().sortedBy { it.ordinal }
        if (ordered.isEmpty()) return labels(ProcessCategory.THINKING)
        val words = ordered.map(labels)
        if (words.size == 1) return words[0]
        if (isChinese) {
            val first = words[0]
            val rest = words.drop(1).map { continuation(it, after = first) }
            val title = if (rest.size == 1) {
                "${first}并${rest[0]}"
            } else {
                (listOf(first) + rest).joinToString("，")
            }
            return if (words.size > 3) "${title}等" else title
        }
        if (words.size == 2) return "${words[0]} and ${words[1]}"
        val title = words.dropLast(1).joinToString(", ") + " and " + words.last()
        return if (words.size > 3) "$title etc." else title
    }

    /** "已读取文件" after "已完成分析" becomes "读取文件": one "已" is enough. */
    private fun continuation(label: String, after: String): String {
        if (after.startsWith("已") && label.startsWith("已")) return label.drop(1)
        return label
    }
}

/** The status line's words: the state's own two strings while running, past tense after. */
object LiveStateWording {

    /** Present tense, from the protocol's own table ("思考推理中" / "Thinking"). */
    fun running(state: SessionState, isChinese: Boolean): String =
        if (isChinese) state.text else state.english

    /** A settled status run: "已完成分析并执行了命令". */
    fun completion(states: List<SessionState>, isChinese: Boolean): String =
        ProcessWording.compose(
            categories = ProcessCategory.of(states),
            labels = { if (isChinese) chineseLabel(it) else englishLabel(it) },
            isChinese = isChinese,
        )

    fun chineseLabel(category: ProcessCategory): String = when (category) {
        ProcessCategory.THINKING -> "已分析"
        ProcessCategory.READ -> "已读取文件"
        ProcessCategory.EDIT -> "已修改文件"
        ProcessCategory.COMMANDS -> "已执行命令"
        ProcessCategory.SEARCH -> "已搜索代码"
        ProcessCategory.SUBAGENTS -> "已协调子代理"
        ProcessCategory.PLAN -> "已更新计划"
        ProcessCategory.QUESTIONS -> "已向你提问"
        ProcessCategory.TOOLS -> "已调用工具"
        ProcessCategory.BACKGROUND -> "已交给后台"
        ProcessCategory.COMPACTING -> "已压缩上下文"
    }

    fun englishLabel(category: ProcessCategory): String = when (category) {
        ProcessCategory.THINKING -> "Analysed"
        ProcessCategory.READ -> "Read files"
        ProcessCategory.EDIT -> "Edited files"
        ProcessCategory.COMMANDS -> "Ran commands"
        ProcessCategory.SEARCH -> "Searched code"
        ProcessCategory.SUBAGENTS -> "Coordinated subagents"
        ProcessCategory.PLAN -> "Updated the plan"
        ProcessCategory.QUESTIONS -> "Asked you questions"
        ProcessCategory.TOOLS -> "Called tools"
        ProcessCategory.BACKGROUND -> "Handed to the background"
        ProcessCategory.COMPACTING -> "Compacted the context"
    }

    /** Present-tense wording for a category ("调用工具中" / "Calling tools"). */
    fun runningLabel(category: ProcessCategory, isChinese: Boolean): String = when (category) {
        ProcessCategory.THINKING -> if (isChinese) "正在分析" else "Analysing"
        ProcessCategory.READ -> if (isChinese) "读取文件中" else "Reading files"
        ProcessCategory.EDIT -> if (isChinese) "修改文件中" else "Editing files"
        ProcessCategory.COMMANDS -> if (isChinese) "执行命令中" else "Running commands"
        ProcessCategory.SEARCH -> if (isChinese) "搜索代码中" else "Searching code"
        ProcessCategory.SUBAGENTS -> if (isChinese) "协调子代理中" else "Coordinating subagents"
        ProcessCategory.PLAN -> if (isChinese) "更新计划中" else "Updating the plan"
        ProcessCategory.QUESTIONS -> if (isChinese) "正在向你提问" else "Asking you questions"
        ProcessCategory.TOOLS -> if (isChinese) "调用工具中" else "Calling tools"
        ProcessCategory.BACKGROUND -> if (isChinese) "等待后台任务" else "Waiting for the background"
        ProcessCategory.COMPACTING -> if (isChinese) "压缩上下文中" else "Compacting the context"
    }
}

/** How a turn ended, for the header line. */
enum class LiveTurnEnding { COMPLETED, FAILED, PAUSED }

/** What `session.messages` says about a turn, for a stream that has not ended yet. */
data class LiveTurnHistory(
    val running: Boolean,
    val failed: Boolean,
    val startedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
)

/**
 * Everything the running-turn view needs.
 *
 * @property sessionId the session whose workspace pictures are read from.
 * @property stream the reducer's immutable snapshot.
 * @property turn the turn number, once the host has recorded it.
 * @property startedAtMillis when the turn began, from the host's clock when known.
 * @property endedAtMillis when it ended, from the host's clock.
 * @property running whether the screen above still believes the turn is running.
 * @property typing whether the newest body should type itself in.
 * @property fallbackAnswer the answer the history holds, when the stream carried
 *   none (a host without a journal, or a lost frame).
 * @property history what `session.messages` says about this turn, used only when
 *   the stream has not delivered its ending yet.
 * @property lastFrameAtMillis when the phone last received a frame, for the 12 s
 *   stale rule (`null` while no frame has arrived).
 */
data class LiveTurnView(
    val sessionId: String,
    val stream: TurnStreamSnapshot,
    val turn: Int? = null,
    val startedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
    val running: Boolean = true,
    val typing: Boolean = true,
    val fallbackAnswer: String? = null,
    val history: LiveTurnHistory? = null,
    val lastFrameAtMillis: Long? = null,
) {
    /** The live footer still belongs on screen: the stream's own terminal wins. */
    val live: Boolean get() = !stream.finished && running

    /** Whether the turn has ended, from either authority. */
    val settled: Boolean get() = !live

    /** How it ended, when it did. */
    val ending: LiveTurnEnding?
        get() {
            if (stream.finished) {
                return when (stream.endState) {
                    SessionState.FAILED -> LiveTurnEnding.FAILED
                    SessionState.PAUSED -> LiveTurnEnding.PAUSED
                    else -> LiveTurnEnding.COMPLETED
                }
            }
            val known = history ?: return null
            if (known.running) return null
            return if (known.failed) LiveTurnEnding.FAILED else LiveTurnEnding.COMPLETED
        }

    /** The start/end the header measures, preferring the stream's. */
    val effectiveStartedAtMillis: Long? get() = startedAtMillis ?: history?.startedAtMillis
    val effectiveEndedAtMillis: Long? get() = endedAtMillis ?: history?.endedAtMillis

    /**
     * Whether the state line is stale: an interval state that has gone 12 s without
     * a frame. The status stays on screen; the view only says the link stopped talking.
     */
    fun isStale(nowMillis: Long, thresholdMillis: Long = STALE_AFTER_MILLIS): Boolean {
        if (!live) return false
        val code = stream.current ?: return false
        if (!code.needsHeartbeat) return false
        val last = lastFrameAtMillis ?: return false
        return nowMillis - last >= thresholdMillis
    }

    /** The folded plan for this turn at a given expansion. */
    fun fold(expanded: Boolean): LiveTurnFold.Plan =
        LiveTurnFold.plan(stream.blocks, live, expanded)

    /** The last body block, the only one that may start a reveal. */
    val latestBodyId: Int?
        get() = stream.blocks.lastOrNull { it.kind == StreamBlock.Kind.BODY }?.id

    /** What the copy action copies: the settled answer, else the last body. */
    val copyText: String?
        get() {
            val final = stream.blocks.lastOrNull {
                it.kind == StreamBlock.Kind.BODY && it.isFinal && it.text.isNotEmpty()
            }
            if (final != null) return final.text
            val last = stream.blocks.lastOrNull {
                it.kind == StreamBlock.Kind.BODY && it.text.isNotEmpty()
            }
            return last?.text ?: fallbackAnswer?.takeIf { it.isNotEmpty() }
        }

    /** Whether any body was produced at all. */
    val hasAnswerBody: Boolean
        get() = stream.blocks.any { it.kind == StreamBlock.Kind.BODY && it.text.isNotEmpty() }

    companion object {
        /** Two missed heartbeats: the spec's stale threshold. */
        const val STALE_AFTER_MILLIS = 12_000L
    }
}

/**
 * Map the kit's snapshot onto what the screen draws.
 *
 * This is the whole adapter between the reducer and Compose: no rules live here,
 * only the turn's identity and the screen's beliefs.
 */
fun TurnStreamSnapshot.toUi(
    sessionId: String,
    turn: Int? = this.turn,
    startedAtMillis: Long? = null,
    endedAtMillis: Long? = null,
    running: Boolean = true,
    typing: Boolean = true,
    fallbackAnswer: String? = null,
    history: LiveTurnHistory? = null,
    lastFrameAtMillis: Long? = null,
): LiveTurnView = LiveTurnView(
    sessionId = sessionId,
    stream = this,
    turn = turn,
    startedAtMillis = startedAtMillis,
    endedAtMillis = endedAtMillis,
    running = running,
    typing = typing,
    fallbackAnswer = fallbackAnswer,
    history = history,
    lastFrameAtMillis = lastFrameAtMillis,
)

/**
 * How a settled turn folds its process away.
 *
 * While the turn runs everything it has produced is on the page; once settled only
 * the answer is, with the process behind the header. Pure, so the rule can be pinned
 * without a screen.
 */
object LiveTurnFold {

    /** The most blocks a section shows before the oldest fold behind a tap. */
    const val FOLDED_LIMIT = 40

    data class Plan(
        /** The blocks before the answer: status runs and narration. */
        val process: List<StreamBlock>,
        /** The answer the turn settled on. */
        val summary: StreamBlock?,
        /** Blocks after the answer. Rare, but the order is the order. */
        val after: List<StreamBlock>,
        /** Whether there is a process to bring back. */
        val canExpand: Boolean,
        /** Whether the process is on screen. */
        val showsProcess: Boolean,
    )

    fun plan(blocks: List<StreamBlock>, live: Boolean, expanded: Boolean): Plan {
        val index = if (live) null else summaryIndex(blocks)
        if (index == null) {
            return Plan(
                process = blocks,
                summary = null,
                after = emptyList(),
                canExpand = false,
                showsProcess = true,
            )
        }
        val process = blocks.subList(0, index).toList()
        return Plan(
            process = process,
            summary = blocks[index],
            after = if (index < blocks.lastIndex) blocks.subList(index + 1, blocks.size).toList() else emptyList(),
            canExpand = process.isNotEmpty(),
            showsProcess = expanded,
        )
    }

    /** The index of the turn's answer: the last body the host marked final. */
    fun summaryIndex(blocks: List<StreamBlock>): Int? =
        blocks.indexOfLast {
            it.kind == StreamBlock.Kind.BODY && it.isFinal && it.text.isNotEmpty()
        }.takeIf { it >= 0 }

    /** One section's blocks, oldest folded away past the limit unless asked for. */
    fun visible(blocks: List<StreamBlock>, unfolded: Boolean): List<StreamBlock> =
        if (unfolded || blocks.size <= FOLDED_LIMIT) blocks
        else blocks.subList(blocks.size - FOLDED_LIMIT, blocks.size).toList()

    /** How many blocks the fold is hiding. */
    fun foldedCount(blocks: List<StreamBlock>, unfolded: Boolean): Int =
        maxOf(0, blocks.size - visible(blocks, unfolded).size)
}
