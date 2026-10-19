package me.shirazu.dshmizar.ui.conversation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.Format
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.Motion
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.TwinStar
import me.shirazu.dshmizar.ui.design.glassCapsule
import me.shirazu.dshmizar.ui.design.pressable
import me.shirazu.mizar.kit.ConversationItem
import me.shirazu.mizar.kit.ConversationTurn

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
                TurnSection(turn, now, onCopy, onRetry, highlighted = index == highlightTurn)
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
                .clickable(enabled = !loading, onClick = onLoadOlder)
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
) {
    // The glow the turn index leaves behind: a wash that fades on its own, because the
    // point is to find the turn once, not to mark it. `animateFloatAsState` drives it,
    // so the fade survives a recomposition in the middle of it.
    val wash by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = tween(600),
        label = "turnGlow",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f * wash))
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        turn.prompts.forEachIndexed { index, item ->
            ConversationItemView(item, isLastPrompt = index == turn.prompts.size - 1, onCopy = onCopy)
        }

        // Prompt, row, answer -- and the row does not move.
        TurnSummary(turn, now, onRetry)

        turn.steps.forEach { step ->
            RiseOnce {
                ConversationItemView(step, isLastPrompt = false, onCopy = onCopy)
            }
        }
    }
}

/**
 * "DeepSeeking for 2 min 14 s", "Completed in 2 min 14 s", or why it did not.
 *
 * The state is a glyph that changes rather than appears: the twin star while it
 * works, settling into a tick when it is done. The clock is ticked locally --
 * the host already said when the turn started -- and it shimmers while it runs.
 */
@Composable
private fun TurnSummary(
    turn: ConversationTurn,
    now: Long,
    onRetry: (String) -> Unit,
) {
    // One small tap when a turn the operator is watching finishes. Only on the
    // change, never on the first draw of a finished turn.
    val haptics = LocalHapticFeedback.current
    var wasRunning by remember(turn.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(turn.isRunning) {
        if (wasRunning == true && !turn.isRunning && !turn.failed) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        wasRunning = turn.isRunning
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (turn.isRunning) {
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

        val elapsed = turn.elapsedMillis(now)
        val headline = when {
            turn.isRunning -> stringResource(R.string.running_for, Format.duration(elapsed) ?: "0 s")
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
            modifier = Modifier.shimmering(active = turn.isRunning),
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

/** A step rising into place as it arrives -- iOS `.transition(.rise)`. */
@Composable
private fun RiseOnce(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val offset by animateDpAsState(
        targetValue = if (visible) 0.dp else 14.dp,
        animationSpec = Motion.pick(Motion.smooth()),
        label = "stepRise",
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = Motion.pick(Motion.smooth()),
        label = "stepFade",
    )
    Box(Modifier.padding(top = offset).alpha(alpha)) {
        content()
    }
}
