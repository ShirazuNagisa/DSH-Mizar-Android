package me.shirazu.dshmizar.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.Format
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.mizar.kit.SessionGrouping
import me.shirazu.mizar.kit.SessionRow

/**
 * The delegated children of the open session, as a census.
 *
 * iOS puts this page in the drawer's second plane; Android's drawer has no planes,
 * so the same census opens from a person mark in the conversation's top bar. The
 * rows are read-only on purpose: a child is the agent's own bookkeeping — who is
 * doing the work — not a conversation the operator continues.
 */
@Composable
internal fun SubagentPanel(
    state: UiState,
    children: List<SessionRow>,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = Corner.md,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.subagents),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SubagentList(state = state, children = children)
                }
            }
        }
    }
}

@Composable
internal fun SubagentList(state: UiState, children: List<SessionRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (children.isEmpty()) {
            Text(
                stringResource(R.string.subagents_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Text(
            stringResource(R.string.subagents_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        )
        if (state.subagentLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        children.forEachIndexed { index, child ->
            SubagentRowItem(number = index + 1, child = child, state = state)
        }
    }
}

@Composable
private fun SubagentRowItem(number: Int, child: SessionRow, state: UiState) {
    val title = state.subagentTitles[child.sessionId]
        ?: SessionGrouping.sessionTitle(child, state.titles)
    val tint = if (child.running) MizarColors.Running else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.sm)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp),
        )
        Box(Modifier.size(7.dp).background(tint, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                stringResource(
                    if (child.running) R.string.subagent_running else R.string.subagent_finished,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
        Text(
            Format.relativeTime(child.updatedMillis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
