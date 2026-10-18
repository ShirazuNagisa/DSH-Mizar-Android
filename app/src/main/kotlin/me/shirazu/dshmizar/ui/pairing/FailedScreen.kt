package me.shirazu.dshmizar.ui.pairing

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.Format
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.mizar.kit.SessionGrouping
import me.shirazu.mizar.kit.SessionRow

/**
 * A host that was known and did not answer, or one that no longer knows this
 * phone. Stored sessions stay readable: an unreachable computer is not a reason
 * to hide what the phone already knows.
 */
@Composable
fun FailedScreen(state: UiState, onRetry: () -> Unit, onPairAgain: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Text(
            text = stringResource(
                if (state.needsPairing) R.string.cannot_recognise else R.string.cannot_reach,
            ),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(state.problem ?: stringResource(R.string.cannot_reach_body), style = MaterialTheme.typography.bodyMedium)
        // A refused credential is not something a retry can fix: the same token would be
        // rejected again. Offer the one action that works.
        if (state.needsPairing) {
            Button(onClick = onPairAgain) { Text(stringResource(R.string.pair_again)) }
        } else {
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        if (state.sessions.isNotEmpty()) {
            Text(stringResource(R.string.stored_sessions_kept), style = MaterialTheme.typography.labelMedium)
            SessionList(state = state, onDismissProblem = {})
        }
    }
}

/** The workspace-grouped session list, read-only while the host is away. */
@Composable
private fun SessionList(
    state: UiState,
    onDismissProblem: () -> Unit,
) {
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var limits by remember { mutableStateOf(mapOf<String, Int>()) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        state.problem?.let { problem ->
            item {
                Surface(color = MizarColors.WarningFill, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(problem, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismissProblem) { Text(stringResource(R.string.dismiss)) }
                    }
                }
            }
        }

        if (state.groups.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_sessions), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        for (group in state.groups) {
            val key = group.path
            val isOpen = expanded.contains(key)
            item(key = "h-$key") {
                ListItem(
                    headlineContent = { Text(group.title, style = MaterialTheme.typography.titleSmall) },
                    supportingContent = { Text(stringResource(R.string.workspace_count, group.sessions.size)) },
                    trailingContent = {
                        Icon(
                            if (isOpen) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable {
                        expanded = if (isOpen) expanded - key else expanded + key
                    },
                )
            }
            if (!isOpen) continue

            val limit = limits[key] ?: PAGE_SIZE
            for (session in group.sessions.take(limit)) {
                item(key = session.sessionId) {
                    SessionRowItem(session, state.titles, state.seenAt)
                }
            }
            if (group.sessions.size > limit) {
                item(key = "m-$key") {
                    TextButton(onClick = { limits = limits + (key to limit + PAGE_SIZE) }) {
                        Text(stringResource(R.string.show_more, group.sessions.size - limit))
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRowItem(
    session: SessionRow,
    titles: Map<String, String>,
    seenAt: Map<String, Long>,
) {
    // A turn that finished while the operator was elsewhere. Running is already obvious
    // from the spinner on the left, so the dot here means "there is something new to
    // read", which is the one thing the list could not say before.
    val unread = !session.running && session.updatedMillis > (seenAt[session.sessionId] ?: 0L)
    ListItem(
        headlineContent = { Text(SessionGrouping.sessionTitle(session, titles)) },
        supportingContent = { Text(Format.relativeTime(session.updatedMillis)) },
        leadingContent = {
            if (session.running) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Computer, contentDescription = null)
            }
        },
        trailingContent = {
            if (unread) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        },
    )
}

/** How many rows a workspace shows before it asks. */
private const val PAGE_SIZE = 10
