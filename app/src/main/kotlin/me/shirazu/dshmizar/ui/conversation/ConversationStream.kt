package me.shirazu.dshmizar.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.domain.LiveTurnEnding
import me.shirazu.dshmizar.domain.LiveTurnFold
import me.shirazu.dshmizar.domain.LiveTurnView
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.LocalReduceMotion
import me.shirazu.dshmizar.ui.design.Motion
import me.shirazu.dshmizar.ui.design.TwinStar
import me.shirazu.mizar.kit.SessionState
import me.shirazu.mizar.kit.StreamBlock
import me.shirazu.mizar.kit.TypewriterPlan
import java.util.Locale

/**
 * One turn's live stream: the status lines it passed through, the bodies it wrote
 * between them, and the answer it settled on.
 *
 * Ported from iOS `Views/Conversation/ConversationStreamView.swift`. Three shapes,
 * one component:
 *
 *  - just sent: one line saying the model is thinking, and a clock;
 *  - working: every block produced so far, a hairline, and the clock under it;
 *  - settled: the clock is replaced by the header ("已完成，用时 2 分 14 秒"), the
 *    process folds behind it, and the answer stays on the page.
 *
 * The data comes from the kit's `TurnStream` (via [LiveTurnView]); this file owns no
 * stream state, only what a screen decides (folded or not, typed or not).
 */
@Composable
internal fun ConversationStream(
    view: LiveTurnView,
    onCopy: (String) -> Unit = {},
    /** Branch this settled turn into a new conversation, when the caller has a boundary. */
    onFork: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isChinese = remember { Locale.getDefault().language == "zh" }
    // The clock and the stale check need a ticking instant while the turn is live.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(view.sessionId, view.live) {
        while (view.live) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }

    // A settled turn opens folded and the header is the way back; a new turn is a
    // new piece of paper.
    var showsProcess by remember(view.sessionId, view.turn) { mutableStateOf(false) }
    var unfoldedPrefix by remember(view.sessionId, view.turn) { mutableStateOf(false) }
    var typedBlockId by remember(view.sessionId, view.turn) { mutableStateOf<Int?>(null) }

    val fold = view.fold(showsProcess)
    val stale = view.isStale(now)
    val ending = view.ending
    val headerDuration = view.effectiveStartedAtMillis?.let { start ->
        view.effectiveEndedAtMillis?.let { end -> (end - start).coerceAtLeast(0L) }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (view.live) {
            BlockList(
                blocks = view.stream.blocks,
                live = true,
                waiting = view.stream.waiting,
                current = view.stream.current,
                latestBodyId = view.latestBodyId,
                typing = view.typing,
                typedBlockId = typedBlockId,
                isChinese = isChinese,
                unfolded = unfoldedPrefix,
                foldable = false,
                foldedCount = 0,
                onUnfold = {},
                onTyped = { typedBlockId = it },
                onCopy = onCopy,
            )
            if (view.stream.blocks.isNotEmpty()) {
                StreamHairline(Modifier.padding(top = 2.dp))
            }
            when {
                view.stream.disconnected -> StreamNote(stringResource(R.string.conv_stream_lost), warning = true)
                stale -> StreamNote(stringResource(R.string.conv_stream_stale), warning = true)
                else -> StreamClock(
                    startedAtMillis = view.effectiveStartedAtMillis,
                    nowMillis = now,
                )
            }
        } else {
            header(view, fold, ending, headerDuration, showsProcess, onFork) {
                showsProcess = !showsProcess
            }
            if (view.stream.blocks.isNotEmpty()) StreamHairline()

            if (fold.showsProcess && fold.process.isNotEmpty()) {
                BlockList(
                    blocks = fold.process,
                    live = false,
                    waiting = null,
                    current = null,
                    latestBodyId = null,
                    typing = false,
                    typedBlockId = null,
                    isChinese = isChinese,
                    unfolded = unfoldedPrefix,
                    foldable = true,
                    foldedCount = LiveTurnFold.foldedCount(fold.process, unfoldedPrefix),
                    onUnfold = { unfoldedPrefix = true },
                    onTyped = {},
                    onCopy = onCopy,
                )
            }
            if (fold.summary != null) {
                BodyBlock(
                    block = fold.summary,
                    typing = false,
                    isChinese = isChinese,
                    onCopy = onCopy,
                )
            }
            if (fold.after.isNotEmpty()) {
                BlockList(
                    blocks = fold.after,
                    live = false,
                    waiting = null,
                    current = null,
                    latestBodyId = null,
                    typing = false,
                    typedBlockId = null,
                    isChinese = isChinese,
                    unfolded = true,
                    foldable = false,
                    foldedCount = 0,
                    onUnfold = {},
                    onTyped = {},
                    onCopy = onCopy,
                )
            }
            if (!view.hasAnswerBody && !view.fallbackAnswer.isNullOrEmpty()) {
                TypewriterStreamText(
                    text = view.fallbackAnswer!!,
                    typing = false,
                    isChinese = isChinese,
                    onCopy = onCopy,
                )
            }
            if (view.stream.truncated) {
                StreamNote(stringResource(R.string.conv_stream_earlier_omitted))
            }
            if (view.stream.disconnected) {
                StreamNote(stringResource(R.string.conv_stream_lost), warning = true)
            } else when (ending) {
                LiveTurnEnding.PAUSED -> StreamNote(stringResource(R.string.conv_stream_note_paused))
                LiveTurnEnding.FAILED -> StreamNote(stringResource(R.string.conv_stream_note_failed), warning = true)
                else -> Unit
            }
        }
    }
}

/** The settled header: what the turn was, how long it took, and the way into the process. */
@Composable
private fun header(
    view: LiveTurnView,
    fold: LiveTurnFold.Plan,
    ending: LiveTurnEnding?,
    durationMillis: Long?,
    expanded: Boolean,
    onFork: (() -> Unit)?,
    onToggle: () -> Unit,
) {
    val interrupted = ending == LiveTurnEnding.FAILED || ending == LiveTurnEnding.PAUSED
    val title = when {
        durationMillis == null && interrupted -> stringResource(R.string.conv_stream_interrupted)
        durationMillis == null -> stringResource(R.string.conv_stream_completed)
        interrupted -> stringResource(R.string.conv_stream_interrupted_after, spellDuration(durationMillis))
        else -> stringResource(R.string.conv_stream_completed_in, spellDuration(durationMillis))
    }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.pick(Motion.snappy()),
        label = "streamFoldChevron",
    )
    val rowModifier = if (fold.canExpand) {
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier.padding(vertical = 2.dp).testTag("conv.stream.header"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (interrupted) Icons.Default.Warning else Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (interrupted) MizarColors.Waiting else MizarColors.Running,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f, fill = false)
                .semantics { contentDescription = title },
        )
        if (onFork != null) {
            TextButton(
                onClick = onFork,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    stringResource(R.string.conv_fork_turn),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (fold.canExpand) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.conv_stream_hide_process else R.string.conv_stream_show_process,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp).rotate(rotation).testTag("conv.stream.fold"),
            )
        }
    }
}

/** One section's blocks: the fold control, then the blocks themselves. */
@Composable
private fun BlockList(
    blocks: List<StreamBlock>,
    live: Boolean,
    waiting: SessionState?,
    current: SessionState?,
    latestBodyId: Int?,
    typing: Boolean,
    typedBlockId: Int?,
    isChinese: Boolean,
    unfolded: Boolean,
    foldable: Boolean,
    foldedCount: Int,
    onUnfold: () -> Unit,
    onTyped: (Int) -> Unit,
    onCopy: (String) -> Unit,
) {
    val visible = LiveTurnFold.visible(blocks, unfolded)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (foldable && foldedCount > 0) {
            Text(
                text = stringResource(R.string.conv_stream_earlier_steps, foldedCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onUnfold)
                    .padding(vertical = 2.dp),
            )
        }
        val lastId = blocks.lastOrNull()?.id
        for (block in visible) {
            when (block.kind) {
                StreamBlock.Kind.STATUS -> {
                    val isLiveLine = live && block.id == lastId
                    LiveStatusLine(
                        states = block.states,
                        running = isLiveLine,
                        waiting = if (isLiveLine) waiting else null,
                        current = if (isLiveLine) current else null,
                        isChinese = isChinese,
                    )
                }
                StreamBlock.Kind.BODY -> BodyBlock(
                    block = block,
                    typing = typing && block.id == latestBodyId && typedBlockId != block.id,
                    isChinese = isChinese,
                    onCopy = onCopy,
                    onTypingStarted = { onTyped(block.id) },
                )
            }
        }
    }
}

/** One body block: narration or the answer, typed while it is the newest. */
@Composable
private fun BodyBlock(
    block: StreamBlock,
    typing: Boolean,
    isChinese: Boolean,
    onCopy: (String) -> Unit,
    onTypingStarted: () -> Unit = {},
) {
    if (block.text.isEmpty()) return
    Box(
        modifier = Modifier.testTag(
            if (block.isFinal) "conv.stream.answer" else "conv.stream.body",
        ),
    ) {
        Column(modifier = Modifier.widthIn(max = 680.dp)) {
            SelectionContainer {
                TypewriterStreamText(
                    text = block.text,
                    typing = typing,
                    isChinese = isChinese,
                    onCopy = onCopy,
                    identity = block.id,
                    onStarted = onTypingStarted,
                )
            }
        }
    }
}

/**
 * The stream body's reveal.
 *
 * A live body arrives in deltas, so the reveal must continue from what is already on
 * screen instead of restarting from zero on every append (which would re-type the
 * whole paragraph several times a second). Parsed Markdown is cut block by block,
 * exactly like the settled answer's typewriter; plain text stays character-paced.
 */
@Composable
private fun TypewriterStreamText(
    text: String,
    typing: Boolean,
    isChinese: Boolean,
    onCopy: (String) -> Unit,
    identity: Int = 0,
    onStarted: () -> Unit = {},
) {
    val reduceMotion = LocalReduceMotion.current
    val blocky = remember(text) { MarkdownBlocks.looksBlocky(text) }
    val blocks = remember(text, blocky) { if (blocky) MarkdownBlocks.parse(text) else emptyList() }
    val paced = remember(text, blocks, blocky) { if (blocky) MarkdownReveal.plainText(blocks) else text }
    // Keyed on the block's identity, not on its text: a body that grows by a delta
    // must continue the reveal from what is on screen, not restart it.
    var revealed by remember(identity, blocky, reduceMotion) {
        mutableIntStateOf(if (reduceMotion || !typing) paced.length else 0)
    }
    LaunchedEffect(identity, text, typing, reduceMotion) {
        if (reduceMotion || !typing) {
            revealed = paced.length
            return@LaunchedEffect
        }
        val plan = TypewriterPlan.forText(
            paced,
            maximumDurationMillis = TypewriterPlan.DEFAULT_MAXIMUM_DURATION_MILLIS,
        )
        val from = revealed.coerceIn(0, paced.length)
        if (from >= paced.length) return@LaunchedEffect
        onStarted()
        val start = System.nanoTime()
        while (true) {
            val elapsedMicros = (System.nanoTime() - start) / 1_000L
            val shown = (from + plan.revealedCount(elapsedMicros)).coerceAtMost(paced.length)
            revealed = shown
            if (shown >= paced.length) break
            delay(16)
        }
    }
    if (blocky) {
        MarkdownBlocksView(
            blocks = MarkdownReveal.blocks(blocks, revealed),
            color = MaterialTheme.colorScheme.onSurface,
            codeColor = MaterialTheme.colorScheme.onSurface,
            linkColor = MaterialTheme.colorScheme.primary,
            onCopy = onCopy,
        )
    } else {
        Text(
            text = text.take(revealed),
            style = MaterialTheme.typography.bodyLarge.copy(
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.15f,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

