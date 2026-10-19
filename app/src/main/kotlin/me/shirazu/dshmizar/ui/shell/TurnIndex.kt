package me.shirazu.dshmizar.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onPick: (index: Int) -> Unit,
) {
    val turns = state.turns
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
                style = MaterialTheme.typography.labelMedium,
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Space.md,
                vertical = Space.xs,
            ),
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            items(turns.size) { index ->
                TurnIndexRow(
                    turn = turns[index],
                    number = index + 1,
                    now = now,
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
            .padding(horizontal = Space.sm, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelMedium,
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
