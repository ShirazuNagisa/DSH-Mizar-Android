package me.shirazu.dshmizar.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.InteractionsSection
import me.shirazu.dshmizar.ui.SessionList

/**
 * The session list under the conversation card.
 *
 * Stage 2 keeps the list's existing rows; the iOS-styled grouped sidebar
 * (collapsible workspaces, staggered row entrance, the connection footer) is the
 * next stage. What is already here because the old home screen had it: the pending
 * approvals/questions pinned to the bottom -- an agent blocked on an answer is
 * stopped until somebody answers, and the list is where a phone spends its time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sidebar(
    model: AppViewModel,
    state: UiState,
    shell: ShellState,
    onNewSession: () -> Unit,
    onPickHost: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.host?.displayName ?: stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (state.sessions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.session_count, state.sessions.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { model.refreshSessions() }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
            }
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { model.refreshSessions() },
            modifier = Modifier.weight(1f),
        ) {
            SessionList(
                state = state,
                onOpen = { model.selectSession(it) },
                onNewSession = onNewSession,
                onPickHost = onPickHost,
                onDismissProblem = { model.dismissProblem() },
                onLoadTitles = { model.loadTitles(it) },
            )
        }

        state.pending?.let { pending ->
            if (!pending.isEmpty) {
                InteractionsSection(
                    approvals = pending.approvals,
                    questions = pending.questions,
                    onApproval = { id, outcome -> model.answerApproval(id, outcome) },
                    onQuestions = { callId, answers -> model.answerQuestions(callId, answers) },
                )
            }
        }
    }
}
