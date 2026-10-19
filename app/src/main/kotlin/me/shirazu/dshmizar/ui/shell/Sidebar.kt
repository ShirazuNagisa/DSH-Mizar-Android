package me.shirazu.dshmizar.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.GlassCircleLabel
import me.shirazu.dshmizar.ui.design.Motion
import me.shirazu.dshmizar.ui.design.Radius
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.glassCapsule
import me.shirazu.dshmizar.ui.design.glassCard
import me.shirazu.dshmizar.ui.design.pressable

/**
 * The session list under the conversation card, ported from iOS
 * `Views/Shell/Sidebar.swift`: header with the Mac's name and a glass refresh
 * button, a search capsule, the new-session button, the workspace groups
 * (SidebarRows.kt), and the connection footer.
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
    onOpenConnection: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val hostId = state.host?.hostId
    var expanded by remember(hostId) { mutableStateOf(shell.expanded(hostId)) }

    // Which sessions have something waiting, for the raised-hand badges.
    val waitingSessions = remember(state.pending) {
        buildSet {
            state.pending?.approvals?.forEach { it.sessionId?.let(::add) }
            state.pending?.questions?.forEach { it.sessionId?.let(::add) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // Header: the Mac's name, and a refresh button that turns while it works.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.host?.displayName ?: stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            val spin by rememberInfiniteTransition(label = "refresh").animateFloat(
                initialValue = 0f,
                targetValue = if (state.refreshing) 360f else 0f,
                animationSpec = infiniteRepeatable(tween(900)),
                label = "refreshSpin",
            )
            GlassCircleLabel(size = 38.dp, onClick = { model.refreshSessions() }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.refresh),
                    modifier = Modifier.size(18.dp).rotate(if (state.refreshing) spin else 0f),
                )
            }
        }

        // A refresh failure is one red line, dismissable, above the list.
        state.problem?.let { problem ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg, vertical = Space.xs)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MizarColors.FailureFill)
                    .padding(horizontal = Space.md, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = problem,
                    style = MaterialTheme.typography.labelSmall,
                    color = MizarColors.Failure,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.dismiss),
                    style = MaterialTheme.typography.labelSmall,
                    color = MizarColors.Failure,
                    modifier = Modifier.clickable { model.dismissProblem() },
                )
            }
        }

        // Search, in a glass capsule.
        //
        // A `BasicTextField`, not a Material one. `TextField` carries its own 56 dp
        // control height and internal padding, so inside this 42 dp capsule it was
        // clipped and its placeholder sat low -- the word was never where the capsule
        // said it should be. Laying the row out explicitly puts the glyph, the word and
        // the caret on one centre line, and makes the clear button part of the row
        // rather than something the field reserves space for.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.md, vertical = Space.xs)
                .height(42.dp)
                .glassCapsule(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(Space.sm))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.search_sessions),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(Space.xs))
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.clear),
                        modifier = Modifier.size(18.dp).clickable { query = "" },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // New session.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.md, vertical = Space.xs)
                .height(48.dp)
                .glassCard(radius = Radius.md)
                .pressable(scale = 0.97f)
                .clickable { onNewSession() }
                .padding(horizontal = Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Space.sm))
            Text(
                stringResource(R.string.new_session),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
        }

        Text(
            text = stringResource(R.string.workspaces).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs),
        )

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { model.refreshSessions() },
            modifier = Modifier.weight(1f),
        ) {
            WorkspaceGroups(
                state = state,
                query = query,
                expanded = expanded,
                waitingSessions = waitingSessions,
                onToggle = { path ->
                    expanded = if (path in expanded) expanded - path else expanded + path
                    shell.setExpanded(expanded, hostId)
                },
                onOpen = { model.selectSession(it) },
                onLoadTitles = { model.loadTitles(it) },
            )
        }

        // The pending cards dock in the conversation, above the composer, as on
        // iOS; the list says which rows are waiting with a raised hand instead.
        ConnectionFooter(state = state, onSettings = onSettings, onOpen = onOpenConnection)
    }
}

/** The capsule at the bottom: a breathing dot, the route, and settings. */
@Composable
private fun ConnectionFooter(state: UiState, onSettings: () -> Unit, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
                .glassCapsule()
                .clickable(onClick = onOpen)
                .padding(horizontal = Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionDot(connected = state.phase == me.shirazu.dshmizar.Phase.READY && !state.reconnecting)
            Spacer(Modifier.width(Space.md))
            Column {
                Text(
                    text = stringResource(
                        if (state.phase == me.shirazu.dshmizar.Phase.READY && !state.reconnecting) {
                            R.string.connected
                        } else {
                            R.string.connecting
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = state.host?.displayName ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        GlassCircleLabel(size = 54.dp, onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings), modifier = Modifier.size(20.dp))
        }
    }
}

/** A dot that breathes while the socket is up. */
@Composable
private fun ConnectionDot(connected: Boolean) {
    if (!connected) {
        Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
        return
    }
    val pulse by rememberInfiniteTransition(label = "connDot").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600)),
        label = "connDotPulse",
    )
    Box(contentAlignment = Alignment.Center) {
        // The ring grows and fades out over 1.6 s.
        Box(
            Modifier
                .size(10.dp + 14.dp * pulse)
                .background(MizarColors.Running.copy(alpha = (1f - pulse) * 0.4f), CircleShape),
        )
        Box(Modifier.size(10.dp).background(MizarColors.Running, CircleShape))
    }
}
