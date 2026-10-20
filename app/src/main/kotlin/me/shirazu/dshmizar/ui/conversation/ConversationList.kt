package me.shirazu.dshmizar.ui.conversation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.ui.design.mizarPerform
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.Format
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.LocalReduceMotion
import me.shirazu.dshmizar.ui.design.Motion
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.TwinStar
import me.shirazu.dshmizar.ui.design.glassCapsule
import me.shirazu.dshmizar.ui.design.pressable
import me.shirazu.dshmizar.ui.design.shapedClickable
import me.shirazu.mizar.kit.ConversationItem
import me.shirazu.mizar.kit.ConversationTurn
import kotlinx.coroutines.delay

/**
 * What was said, and how long the agent took over it -- ported from iOS
 * `Views/ConversationView.swift`.
 *
 * A turn is drawn, not folded. The host sends a *summary* of each turn with the
 * per-step narration left out, so there is nothing to expand: the row says how
 * it went and the answer sits under it. A failed turn gets the most prominent
 * treatment of anything here.
 */
@Composable
internal fun ConversationList(
    state: UiState,
    listState: LazyListState,
    now: Long,
    onLoadOlder: () -> Unit,
    onCopy: (String) -> Unit,
    onRetry: (String) -> Unit,
    highlightTurn: Int? = null,
) {
    // What the host says about the session, and how fresh the newest record is: the
    // pair decides whether a copy of a turn can still be "in flight". A stale copy is
    // how a finished turn kept its running row until the next message refreshed it.
    val sessionRunning = state.sessions.firstOrNull { it.sessionId == state.selectedSessionId }?.running == true
    val newestAt = state.conversation.lastOrNull()?.atMillis
    val recent = newestAt != null && System.currentTimeMillis() - newestAt < IN_FLIGHT_FRESH_MILLIS

    // The last answer that arrived while this screen was watching gets typed on.
    // Only a transition from in-flight to finished counts: history opened from the
    // cache and a message redrawn after a scroll are the message, not a re-run.
    var typingTurnId by remember { mutableStateOf<String?>(null) }
    var sawRunning by remember { mutableStateOf(false) }
    val newest = state.turns.lastOrNull()
    val newestActive = newest != null && (newest.isRunning || newest.isPending) && (sessionRunning || recent)
    LaunchedEffect(newest?.id, newestActive) {
        if (newestActive) {
            sawRunning = true
            typingTurnId = null
        } else if (sawRunning && newest != null) {
            sawRunning = false
            typingTurnId = newest.id
            // The reveal is over long before this, but the flag should not outlive a
            // row that was never actually drawn.
            delay(TYPEWRITER_GRACE_MILLIS)
            if (typingTurnId == newest.id) typingTurnId = null
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The top of the list, where older messages arrive. Always a visible
        // row, never a bare spinner.
        if (!state.exhausted && state.oldestSeq != null) {
            item(key = "loadOlder") {
                LoadOlderRow(loading = state.loadingConversation, onLoadOlder = onLoadOlder)
            }
        }

        if (state.turns.isEmpty()) {
            items(state.conversation, key = { it.id }) { item ->
                ConversationItemView(item, isLastPrompt = false, onCopy = onCopy)
            }
        } else {
            itemsIndexed(state.turns, key = { _, turn -> turn.id }) { index, turn ->
                // Only the newest turn can be in flight: either the host has opened it
                // (`isRunning`) or it has recorded the prompt and not yet the `turn/start`
                // (`isPending`). Nothing older is treated as waiting, and a copy that has
                // outlived the window is not trusted -- a stale "running" row is what made
                // a finished turn look unfinished until the next message refreshed it.
                TurnSection(
                    turn = turn,
                    now = now,
                    onCopy = onCopy,
                    onRetry = onRetry,
                    highlighted = index == highlightTurn,
                    typing = turn.id == typingTurnId,
                    waiting = index == state.turns.lastIndex &&
                        (turn.isRunning || turn.isPending) &&
                        (sessionRunning || recent),
                )
            }
        }

        // The very end of the page, where the scroll anchors.
        item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
    }
}

/** The top row: tap to fetch the page before this one. */
@Composable
private fun LoadOlderRow(loading: Boolean, onLoadOlder: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .height(30.dp)
                .glassCapsule()
                .pressable(scale = 0.96f)
                .shapedClickable(CircleShape, enabled = !loading, onClick = onLoadOlder)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
            } else {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.load_earlier),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One turn: the prompt, the row that says how it is going, and the answer. */
@Composable
private fun TurnSection(
    turn: ConversationTurn,
    now: Long,
    onCopy: (String) -> Unit,
    onRetry: (String) -> Unit,
    highlighted: Boolean = false,
    /** This turn's answer arrived while looking: reveal it, do not blink it in. */
    typing: Boolean = false,
    /** The turn is in flight: opened by the host, or its prompt just recorded. */
    waiting: Boolean = false,
) {
    // The glow the turn index leaves behind: a wash that fades on its own, because the
    // point is to find the turn once, not to mark it. It comes up on the smooth spring
    // and goes on a slower one, both critically damped (it is opacity); under Reduce
    // Motion both are the fade. Read in the draw phase, so the fade redraws the wash
    // and recomposes nothing.
    val wash by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = Motion.pick(if (highlighted) Motion.smooth() else WashOut),
        label = "turnGlow",
    )
    val washTint = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val w = wash.coerceIn(0f, 1f)
                if (w > 0f) drawOutline(Corner.md.createOutline(size, layoutDirection, this), washTint.copy(alpha = 0.10f * w))
            }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        turn.prompts.forEachIndexed { index, item ->
            ConversationItemView(item, isLastPrompt = index == turn.prompts.size - 1, onCopy = onCopy)
        }

        // Prompt, row, answer -- and the row does not move.
        TurnSummary(turn, now, onRetry, waiting = waiting)

        turn.steps.forEach { step ->
            RiseOnce {
                ConversationItemView(
                    step,
                    isLastPrompt = false,
                    onCopy = onCopy,
                    typing = typing && step.id == turn.steps.lastOrNull {
                        it is ConversationItem.Message && it.role == ConversationItem.Role.ASSISTANT
                    }?.id,
                )
            }
        }
    }
}

/**
 * "深度求索中~", "Completed in 2 min 14 s", or why it did not.
 *
 * The state is a glyph that changes rather than appears: the twin star while it
 * works, settling into a tick when it is done. While a turn is in flight the row
 * says what the agent is doing rather than how long it is taking -- a stopwatch
 * counting up reads like a loading indicator, and the duration only means something
 * once there is an end to measure against.
 *
 * `waiting` is passed in rather than derived here: only the caller knows whether
 * this is the newest turn, and an unnumbered group further up the log is a page-edge
 * artifact, not something in flight.
 */
@Composable
private fun TurnSummary(
    turn: ConversationTurn,
    now: Long,
    onRetry: (String) -> Unit,
    waiting: Boolean = false,
) {
    // One small tap when a turn the operator is watching finishes. Only on the
    // change, never on the first draw of a finished turn.
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val active = waiting || turn.isRunning
    var wasActive by remember(turn.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(active) {
        if (wasActive == true && !active && !turn.failed) {
            haptics.mizarPerform(HapticFeedbackType.LongPress)
        }
        wasActive = active
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (active) {
                TwinStar(size = 13.dp)
            } else {
                Icon(
                    if (turn.failed) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (turn.failed) MizarColors.Waiting else MizarColors.Running,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        Spacer(Modifier.width(7.dp))

        val headline = when {
            active -> stringResource(R.string.turn_deep_seeking)
            turn.failed -> Format.duration(turn.durationMillis)?.let { stringResource(R.string.unfinished_after, it) }
                ?: stringResource(R.string.unfinished)

            else -> Format.duration(turn.durationMillis)?.let { stringResource(R.string.completed_in, it) }
                ?: stringResource(R.string.completed)
        }
        Text(
            text = headline,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.shimmering(active = active),
        )

        Spacer(Modifier.weight(1f))

        if (turn.failed) {
            // The prompt is already here, so the retry is one tap rather than
            // retyping the sentence the agent failed to answer.
            val prompt = (turn.prompts.firstOrNull() as? ConversationItem.Message)?.text
            if (prompt != null) {
                TextButton(
                    onClick = { onRetry(prompt) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.retry_turn), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (turn.steps.isNotEmpty()) {
            Text(
                stringResource(R.string.step_count, turn.steps.size),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/** The wash leaving: critically damped, slower than it came, so it fades rather than blinks. */
private val WashOut = spring<Float>(dampingRatio = 1f, stiffness = 260f)

/**
 * A step rising into place as it arrives -- iOS `.transition(.rise)`.
 *
 * Drawn, never laid out: the step takes its place at once and only its layer travels
 * the last 14 dp, so the list does not re-measure an item for every frame of the rise.
 * Under Reduce Motion it only fades in.
 */
@Composable
private fun RiseOnce(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val reduceMotion = LocalReduceMotion.current
    val rise by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = Motion.pick(Motion.smooth()),
        label = "stepRise",
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = Motion.pick(Motion.smooth()),
        label = "stepFade",
    )
    Box(
        Modifier.graphicsLayer {
            translationY = if (reduceMotion) 0f else rise * RiseDistance.toPx()
            this.alpha = alpha.coerceIn(0f, 1f)
        },
    ) {
        content()
    }
}

private val RiseDistance = 14.dp

/**
 * How long a copy of the transcript is trusted to say "a turn is in flight".
 *
 * Same window as the session screen's: the gap between a prompt and its `turn/start`
 * is milliseconds, so two minutes is generous for it and short enough that a stale
 * copy cannot keep the row saying "深度求索中~".
 */
private const val TYPEWRITER_GRACE_MILLIS = 2_500L

private const val IN_FLIGHT_FRESH_MILLIS = 120_000L