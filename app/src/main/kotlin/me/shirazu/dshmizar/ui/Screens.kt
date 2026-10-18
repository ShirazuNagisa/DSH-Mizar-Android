package me.shirazu.dshmizar.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.Phase
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.PairingTarget
import me.shirazu.mizar.kit.PairingUri
import me.shirazu.mizar.kit.SessionGrouping
import me.shirazu.mizar.kit.SessionRow

/**
 * The screens that are not the shell: pairing, failure, and the shared pieces
 * (session list, dialogs, scanner) the shell and the root both draw with.
 *
 * There is no navigation graph behind any of this, deliberately. Which screen the
 * operator is on *is* a property of the connection — the app can be unpaired,
 * connecting, or unable to reach a Mac it knows — and modelling that as a route
 * made the two disagree the first time the socket dropped. The root dispatch lives
 * in RootScreen.kt; the conversation/list shell lives in shell/MizarShell.kt.
 */

/**
 * One narrow line saying the socket is being dialled again.
 *
 * Kept to a line because the interesting part is that it goes away: the screen below it
 * is still readable, which is the whole point of retrying instead of failing.
 */
@Composable
internal fun ReconnectingBanner(reason: String?) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.reconnecting),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (reason != null) {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** A host that was known and did not answer, or one that no longer knows this phone. */
@Composable
internal fun FailedScreen(state: UiState, onRetry: () -> Unit, onPairAgain: () -> Unit) {
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
            // Stored sessions stay readable: an unreachable Mac is not a reason to
            // hide what the phone already knows.
            Text(stringResource(R.string.stored_sessions_kept), style = MaterialTheme.typography.labelMedium)
            SessionList(state = state, onOpen = {}, onNewSession = {}, onPickHost = {}, onDismissProblem = {})
        }
    }
}

/** The workspace-grouped session list. */
@Composable
internal fun SessionList(
    state: UiState,
    onOpen: (String) -> Unit,
    onNewSession: () -> Unit,
    onPickHost: () -> Unit,
    onDismissProblem: () -> Unit,
    onLoadTitles: (List<String>) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var limits by remember { mutableStateOf(mapOf<String, Int>()) }

    // Titles are learned one session at a time, and only for the rows about to be
    // drawn: the host reports none for most sessions, so asking about every row of
    // every workspace is a burst of requests for names nobody is looking at.
    val visibleIds = remember(state.groups, expanded, limits) {
        state.groups
            .filter { expanded.contains(it.path) }
            .flatMap { group -> group.sessions.take(limits[group.path] ?: PAGE_SIZE) }
            .map { it.sessionId }
    }
    LaunchedEffect(visibleIds) {
        if (visibleIds.isNotEmpty()) onLoadTitles(visibleIds)
    }

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

        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.new_session)) },
                leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                modifier = Modifier.clickable { onNewSession() },
            )
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
                    SessionRowItem(session, state.titles, state.seenAt) { onOpen(session.sessionId) }
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

        if (state.hosts.size > 1) {
            item {
                TextButton(onClick = onPickHost) { Text(stringResource(R.string.choose_mac)) }
            }
        }
    }
}

@Composable
private fun SessionRowItem(
    session: SessionRow,
    titles: Map<String, String>,
    seenAt: Map<String, Long>,
    onClick: () -> Unit,
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
        modifier = Modifier.clickable { onClick() },
    )
}

/** How many rows a workspace shows before it asks. */
private const val PAGE_SIZE = 10

