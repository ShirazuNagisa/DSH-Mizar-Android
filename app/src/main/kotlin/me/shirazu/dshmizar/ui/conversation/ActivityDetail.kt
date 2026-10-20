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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.glassCard
import me.shirazu.dshmizar.ui.design.pressable
import me.shirazu.dshmizar.ui.design.shapedClickable
import me.shirazu.mizar.kit.ActivityEntry

/**
 * The running turn's compact feed, opened from the status bar.
 *
 * One line per thing the agent did -- a thought, a command, its output, a tool call
 * -- newest at the bottom. It is the mobile answer to "what is it doing?" for a turn
 * that has been running for minutes, without turning the phone into a terminal.
 */
@Composable
internal fun ActivityDetailPanel(
    state: UiState,
    onLoadOlder: () -> Unit,
    onClose: () -> Unit,
) {
    val activity = state.activity
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .glassCard(radius = me.shirazu.dshmizar.ui.design.Radius.md)
            .heightIn(max = 220.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.running_detail),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onLoadOlder, enabled = activity?.hasMore == true && !state.activityLoadingOlder) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(3.dp))
                Text(stringResource(R.string.detail_older), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.detail_close),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val entries = activity?.entries.orEmpty()
        when {
            state.activityLoading && activity == null -> Box(
                Modifier.fillMaxWidth().padding(18.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.5.dp) }

            entries.isEmpty() -> Text(
                text = state.activityError?.takeIf { it.isNotBlank() } ?: stringResource(R.string.detail_nothing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                entries.forEach { entry -> ActivityRow(entry) }
            }
        }
    }
}

@Composable
private fun ActivityRow(entry: ActivityEntry) {
    val tint = when (entry.kind) {
        "thinking" -> MaterialTheme.colorScheme.onSurfaceVariant
        "command" -> MizarColors.Running
        "output" -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.primary
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 16.dp)
                .background(tint.copy(alpha = if (entry.kind == "thinking") 0.25f else 0.7f), Corner.xs)
                .shimmering(active = false),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (entry.kind == "output") FontWeight.Normal else FontWeight.Medium,
                    fontFamily = if (entry.kind == "command" || entry.kind == "output") FontFamily.Monospace else null,
                ),
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.text?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = if (entry.kind == "command" || entry.kind == "output") FontFamily.Monospace else null,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

