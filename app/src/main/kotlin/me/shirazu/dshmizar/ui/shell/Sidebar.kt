package me.shirazu.dshmizar.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.AnchoredPopover
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.GlassCircleLabel
import me.shirazu.dshmizar.ui.design.LightUp
import me.shirazu.dshmizar.ui.design.LocalReduceMotion
import me.shirazu.dshmizar.ui.design.MizarMark
import me.shirazu.dshmizar.ui.design.Motion
import me.shirazu.dshmizar.ui.design.PopoverSize
import me.shirazu.dshmizar.ui.design.Radius
import me.shirazu.dshmizar.ui.design.RefreshSpinner
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.glassCapsule
import me.shirazu.dshmizar.ui.design.searchPulse
import me.shirazu.dshmizar.ui.design.glassCard
import me.shirazu.dshmizar.ui.design.pressable
import me.shirazu.dshmizar.ui.design.shapedClickable
import me.shirazu.dshmizar.ui.settings.HostPickerDialog
import me.shirazu.dshmizar.ui.settings.NewSessionDialog
import me.shirazu.dshmizar.ui.settings.SettingsDialog

/**
 * The session list under the conversation card, ported from iOS
 * `Views/Shell/Sidebar.swift`: header with the Mac's name and a glass refresh
 * button, a search capsule, the new-session button, the workspace groups
 * (SidebarRows.kt), and the connection footer.
 */
@Composable
fun Sidebar(
    model: AppViewModel,
    state: UiState,
    shell: ShellState,
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

    // Pull-to-refresh, drawn by hand: the stock indicator swallows the list's
    // own scrolling and drops a spinner over the content. Here the list always
    // scrolls, and a pull at the top opens a gap between the new-session key
    // and the workspaces, with the three-dash spinner turning inside it.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val gap = remember { Animatable(0f) }
    val thresholdPx = with(density) { 56.dp.toPx() }
    val spinnerPx = with(density) { 40.dp.toPx() }
    val maxPx = with(density) { 96.dp.toPx() }
    val spinnerSizePx = with(density) { 22.dp.toPx() }

    val pullConnection = remember(state.refreshing) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (state.refreshing || source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y > 0f) {
                    scope.launch { gap.snapTo((gap.value + available.y * 0.45f).coerceAtMost(maxPx)) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (state.refreshing || source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y < 0f && gap.value > 0f) {
                    val take = minOf(-available.y, gap.value)
                    scope.launch { gap.snapTo(gap.value - take) }
                    return Offset(0f, -take)
                }
                return Offset.Zero
            }
        }
    }

    // The hand leaves the screen: past the line the pull becomes a refresh and
    // the gap holds the spinner for as long as the refresh runs; short of the
    // line the gap folds away.
    LaunchedEffect(listState.isScrollInProgress, state.refreshing) {
        if (listState.isScrollInProgress) return@LaunchedEffect
        when {
            state.refreshing -> gap.animateTo(spinnerPx, Motion.snappy())
            gap.value >= thresholdPx -> model.refreshSessions()
            else -> gap.animateTo(0f, Motion.snappy())
        }
    }

    Column(modifier = Modifier.fillMaxSize().nestedScroll(pullConnection).statusBarsPadding().navigationBarsPadding()) {
        // Header: the computer's name -- a tap on it switches computers, when
        // there is more than one -- and a refresh button that turns while it works.
        var showingPicker by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.host?.displayName ?: stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    modifier = Modifier.clickable(
                        enabled = state.hosts.size > 1,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { showingPicker = true },
                )
                AnchoredPopover(
                    expanded = showingPicker,
                    onDismiss = { showingPicker = false },
                ) {
                    HostPickerDialog(
                        state = state,
                        onDismiss = { showingPicker = false },
                        onOpen = { hostId ->
                            showingPicker = false
                            model.openHost(hostId)
                        },
                    )
                }
            }
            val reduceMotion = LocalReduceMotion.current
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
                    // Turned in the layer, as it draws; held still under Reduce Motion.
                    modifier = Modifier.size(18.dp).graphicsLayer {
                        rotationZ = if (state.refreshing && !reduceMotion) spin else 0f
                    },
                )
            }
        }

        // A refresh failure is one red line, dismissable, above the list.
        state.problem?.let { problem ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg, vertical = Space.xs)
                    .background(MizarColors.FailureFill, Corner.sm)
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

        // New session, and the window that grows out of it.
        var showingNewSession by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.md, vertical = Space.xs)
                    .height(48.dp)
                    .glassCard(radius = Radius.md)
                    .pressable(scale = 0.97f)
                    .shapedClickable(Corner.md) { showingNewSession = true }
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
            AnchoredPopover(
                expanded = showingNewSession,
                onDismiss = { showingNewSession = false },
            ) {
                NewSessionDialog(
                    state = state,
                    onDismiss = { showingNewSession = false },
                    onCreate = { cwd ->
                        showingNewSession = false
                        model.createSession(cwd)
                    },
                )
            }
        }

        // The gap a pull opens between the new-session key and the workspaces:
        // empty but for the three-dash spinner turning inside it. The workspaces move
        // down by their layer, not by a spacer growing above them, so a pull
        // re-measures nothing; the spinner is drawn in the gap and clipped to it.
        Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
        Column(modifier = Modifier.fillMaxSize().graphicsLayer { translationY = gap.value }) {
        Text(
            text = stringResource(R.string.workspaces).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs),
        )

        WorkspaceGroups(
            state = state,
            query = query,
            expanded = expanded,
            waitingSessions = waitingSessions,
            listState = listState,
            onToggle = { path ->
                expanded = if (path in expanded) expanded - path else expanded + path
                shell.setExpanded(expanded, hostId)
            },
            onOpen = { model.selectSession(it) },
            onLoadTitles = { model.loadTitles(it) },
            modifier = Modifier.weight(1f),
        )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { maxPx.toDp() })
                .drawWithContent { if (gap.value > 0.5f) clipRect(bottom = gap.value) { this@drawWithContent.drawContent() } },
            contentAlignment = Alignment.TopCenter,
        ) {
            RefreshSpinner(
                modifier = Modifier.graphicsLayer {
                    translationY = (gap.value - spinnerSizePx) / 2f
                    alpha = (gap.value / spinnerSizePx).coerceIn(0f, 1f)
                },
                spinning = state.refreshing,
                dragRotation = { gap.value / maxPx * 270f },
            )
        }
        }

        // The pending cards dock in the conversation, above the composer, as on
        // iOS; the list says which rows are waiting with a raised hand instead.
        ConnectionFooter(model = model, state = state)
    }
}

/** The capsule at the bottom: a breathing dot, the route, and settings -- and
 *  the two windows that grow out of them. */
@Composable
private fun ConnectionFooter(model: AppViewModel, state: UiState) {
    var showingConnection by remember { mutableStateOf(false) }
    var showingSettings by remember { mutableStateOf(false) }
    // Both windows are anchored to the footer as a whole, not to their own button:
    // a window anchored to the gear at the right end gets pushed against the screen
    // edge by the popup's own right margin, and the window reads as detached from
    // the sidebar. The footer's left edge is inside it, so both windows grow out of
    // the footer and stay in the card.
    Box(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // At least the settings key's height, and as tall as its two lines need:
                    // a fixed 54 dp clipped the host's name at large text sizes.
                    .heightIn(min = 54.dp)
                    .glassCapsule()
                    .shapedClickable(CircleShape) { showingConnection = true }
                    .padding(horizontal = Space.lg, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConnectionMark(connected = state.phase == me.shirazu.dshmizar.Phase.READY && !state.reconnecting)
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
        }
        Box {
            GlassCircleLabel(size = 54.dp, onClick = { showingSettings = true }) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    AnchoredPopover(
        expanded = showingConnection,
        onDismiss = { showingConnection = false },
        // Three short rows: the smallest window on the ladder.
        width = 240.dp,
        maxHeight = 280.dp,
        // The window sits a hand's breadth off its anchor by default; the footer is
        // one row off the bottom of the screen, where that breadth reads as a gap.
        offset = androidx.compose.ui.unit.DpOffset(0.dp, 44.dp),
    ) {
        ConnectionPanel(
            state = state,
            onDismiss = { showingConnection = false },
        )
    }
    // Narrower than the ladder's tall window: the settings list scrolls, and more
    // sections are coming. The footer is the anchor so the window is not pushed off
    // the right edge, and the offset slides it back toward the gear it belongs to --
    // right-aligned inside the sidebar, not flush against its left edge.
    AnchoredPopover(
        expanded = showingSettings,
        onDismiss = { showingSettings = false },
        width = 272.dp,
        maxHeight = 400.dp,
        offset = androidx.compose.ui.unit.DpOffset(x = 21.dp, y = 44.dp),
    ) {
        SettingsDialog(
            state = state,
            onDismiss = { showingSettings = false },
            onForget = { hostId ->
                showingSettings = false
                model.forget(hostId)
            },
            onAddHost = {
                showingSettings = false
                model.startPairing()
            },
            onTheme = { mode -> model.setTheme(mode) },
            onQuietHours = { on -> model.setQuietHours(on) },
            onRename = { hostId, label -> model.renameHost(hostId, label) },
        )
    }
    }
}

/**
 * The connection, as the icon's own mark: lit and still while the socket is up,
 * pulsing in the neutral ink while it looks for the computer, and on the edge
 * from looking to found, lit dot-outward once before it rests. Being up needs no
 * motion to say so; only looking moves. 24 dp across, in the box the dot had, so
 * the text beside it never moves; the lighting is read as it draws.
 */
@Composable
private fun ConnectionMark(connected: Boolean) {
    val reduceMotion = LocalReduceMotion.current
    // Composed already up -- the sidebar opened on a live link -- the mark starts lit
    // and stays: the light-up belongs to the moment the link comes back.
    val lit = remember { Animatable(if (connected) 3f else 0.6f) }
    LaunchedEffect(connected, reduceMotion) {
        when {
            reduceMotion -> lit.snapTo(3f)
            connected -> if (lit.value < 3f) {
                lit.snapTo(0f)
                lit.animateTo(3f, LightUp)
            }
            else -> searchPulse(lit)
        }
    }
    val tint = if (connected) MizarColors.Running else MaterialTheme.colorScheme.outline
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        MizarMark(Modifier.width(24.dp), lit = { lit.value }, accent = tint, ink = tint)
    }
}
