package me.shirazu.dshmizar.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.Format
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.mizar.kit.ConversationItem
import me.shirazu.mizar.kit.ConversationTurn

/**
 * The sidebar's mirror: one row per turn, on the right.
 *
 * Ported from iOS `Views/Conversation/TurnIndex.swift`. The sidebar answers "which
 * session", this answers "where in this one", and they are the same gesture from
 * opposite sides -- which is why they share [ShellState]'s single number: only one
 * can be out, and a drag that starts closed opens whichever one it moves toward.
 *
 * Stage one is the list itself: a row per turn, its prompt, how long it took and
 * whether it failed. The timeline brackets, the duration hairline and the
 * unfinished-only filter are the next pass; they are presentation on top of this
 * data, not new data.
 */
@Composable
fun TurnIndex(
    state: UiState,
    now: Long,
    // Which turns the conversation is showing: bracketed in the list, so the two views
    // agree about where the reader is.
    onScreen: Set<Int> = emptySet(),
    onPick: (index: Int) -> Unit,
) {
    val turns = state.turns
    var onlyUnfinished by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.turn_index),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.turn_count, turns.size),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (turns.isEmpty()) {
            Text(
                text = stringResource(R.string.turn_index_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            )
            return@Column
        }

        // Narrowing to the turns that did not finish: the reason to open this list is
        // usually "which one went wrong", not "list everything".
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs),
        ) {
            FilterChip(
                selected = onlyUnfinished,
                onClick = { onlyUnfinished = !onlyUnfinished },
                label = { Text(stringResource(R.string.turn_index_unfinished), style = MaterialTheme.typography.labelMedium) },
            )
        }

        val visible = if (onlyUnfinished) {
            turns.withIndex().filter { (_, turn) -> turn.failed || turn.isRunning }
        } else {
            turns.withIndex().toList()
        }
        // The hairline under each prompt is drawn as a fraction of the longest turn on
        // screen, so the shape of the session is readable without reading any number.
        val longest = visible.maxOfOrNull { (_, turn) -> turn.elapsedMillis(now) ?: 0L } ?: 0L

        if (visible.isEmpty()) {
            Text(
                text = stringResource(R.string.turn_index_all_finished),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Space.md,
                vertical = Space.xs,
            ),
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            items(visible.size) { position ->
                val (index, turn) = visible[position]
                TurnIndexRow(
                    turn = turn,
                    number = index + 1,
                    now = now,
                    longest = longest,
                    onScreen = index in onScreen,
                    onClick = { onPick(index) },
                )
            }
        }
    }
}

@Composable
private fun TurnIndexRow(
    turn: ConversationTurn,
    number: Int,
    now: Long,
    longest: Long,
    onScreen: Boolean,
    onClick: () -> Unit,
) {
    val prompt = (turn.prompts.firstOrNull() as? ConversationItem.Message)?.text?.trim().orEmpty()
    val elapsed = turn.elapsedMillis(now) ?: 0L
    val failed = turn.failed
    val running = turn.isRunning

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            // The bracket: a wash on the rows the conversation is showing. It is the
            // index's answer to "where am I", and it moves as the conversation scrolls.
            .background(
                if (onScreen) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else androidx.compose.ui.graphics.Color.Transparent,
                MaterialTheme.shapes.medium,
            )
            .padding(horizontal = Space.sm, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontFeatureSettings = "tnum",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = prompt.ifBlank { stringResource(R.string.turn_index_untitled) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (elapsed > 0) {
                Spacer(Modifier.size(2.dp))
                Text(
                    text = Format.duration(elapsed) ?: "",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // A hairline as long as the turn took, against the longest here: the
                // shape of the session at a glance, with no numbers to read.
                if (longest > 0L && elapsed > 0L) {
                    Spacer(Modifier.size(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(elapsed.toFloat() / longest)
                            .height(2.dp)
                            .clip(CircleShape)
                            .background(
                                if (failed) MizarColors.Warning
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                            ),
                    )
                }
            }
        }
        // A dot on the right edge of the row, in the colour that state already has
        // everywhere else: waiting for a person, still working, or the turn that
        // failed. Silence for a turn that finished cleanly.
        if (failed || running) {
            Spacer(Modifier.width(Space.sm))
            Spacer(
                Modifier
                    .size(6.dp)
                    .background(
                        if (failed) MizarColors.Warning else MizarColors.Running,
                        CircleShape,
                    ),
            )
        }
    }
}
