package me.shirazu.dshmizar.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.settings.HostPickerDialog
import me.shirazu.dshmizar.ui.LocalMizarDark
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.settings.NewSessionDialog
import me.shirazu.dshmizar.ui.conversation.SessionScreen
import me.shirazu.dshmizar.ui.settings.SettingsDialog
import me.shirazu.dshmizar.ui.design.LocalReduceMotion
import me.shirazu.dshmizar.ui.design.Radius
import kotlin.math.min

/**
 * The app, once a Mac is paired: a sidebar of workspaces, and the conversation.
 *
 * On a phone the conversation is the screen and the sidebar is underneath it.
 * Opening the sidebar -- the button at the top left, or a sideways drag anywhere
 * on the conversation -- pushes the conversation aside as a card: it follows the
 * finger, shrinks a little, takes on a rounded corner and dims, and a tap on it
 * brings it back.
 *
 * Ported from iOS `Views/Shell/MizarShell.swift`. Both halves stay mounted the
 * whole time and only graphics-layer values move: a modifier added and removed as
 * the sidebar opens would give the conversation a new identity -- rebuilding it,
 * re-running its loads, and throwing away the draft and the scroll position.
 */
@Composable
fun MizarShell(model: AppViewModel, state: UiState) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val reduceMotion = LocalReduceMotion.current
    val shell = remember {
        ShellState(
            scope = scope,
            reduceMotion = { reduceMotion },
            dismissKeyboard = { keyboard?.hide() },
        )
    }
    var placed by remember { mutableStateOf(false) }

    // The conversation's list belongs to the shell now, not to the screen: the turn
    // index scrolls it, and the turn it scrolled to has to be findable -- so the index
    // leaves a glow on it that fades by itself.
    val conversationList = remember(state.selectedSessionId) { LazyListState() }
    var glowingTurn by remember { mutableStateOf<Int?>(null) }
    // Which turns are on screen right now: the index brackets them, so the list and
    // the conversation agree about where the reader is.
    val onScreenTurns by remember {
        derivedStateOf {
            val header = if (!state.exhausted && state.oldestSeq != null) 1 else 0
            conversationList.layoutInfo.visibleItemsInfo
                .map { it.index - header }
                .filter { it in state.turns.indices }
                .toSet()
        }
    }

    var showingSettings by remember { mutableStateOf(false) }
    var showingNewSession by remember { mutableStateOf(false) }
    var showingPicker by remember { mutableStateOf(false) }
    var showingConnection by remember { mutableStateOf(false) }

    // Open on a launch with nothing selected: the list is the only useful thing to
    // look at. Placed without animating; the first frame is a state, not a change.
    LaunchedEffect(Unit) {
        if (!placed) {
            placed = true
            shell.place(at = if (state.selectedSessionId == null) 1f else 0f)
        }
    }
    // A session chosen from anywhere -- a row, a notification, a session just
    // created -- is a session the operator wants to see, so the card comes back. A
    // selection that goes away puts the list back in front.
    LaunchedEffect(state.selectedSessionId) {
        if (state.selectedSessionId != null) {
            shell.close()
        } else if (!shell.settledOpen) {
            shell.open()
        }
    }

    BackHandler(enabled = shell.isOpen) { shell.close() }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(MizarColors.SidebarBackground),
    ) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.toPx() }
        val sidebarWidth = min(330.dp.value, maxWidth.value * 0.84f).dp
        val sidebarWidthPx = with(density) { sidebarWidth.toPx() }
        // The drawers are not the same width, and the pan needs to know which one it is
        // moving: measuring a drag in points against the wrong width makes the same
        // finger travel move the card by different amounts on each side.
        val indexWidth = min(320.dp.value, maxWidth.value * 0.84f).dp
        val indexWidthPx = with(density) { indexWidth.toPx() }

        val p = shell.progress.value
        // One number, two drawers: only one of these is ever non-zero, which is what
        // keeps the card from being pushed by both at once.
        val sidebarReveal = p.coerceIn(0f, 1f)
        val indexReveal = (-p).coerceIn(0f, 1f)
        val reveal = maxOf(sidebarReveal, indexReveal)

        // The sidebar comes forward as the card leaves: a little travel and a little
        // light, so the two read as layers rather than as one strip sliding sideways.
        Box(
            modifier = Modifier
                .width(sidebarWidth)
                .fillMaxSize()
                // The list itself travels. It used to peek 44 dp from the left and be
                // revealed by the card leaving, which reads as "a strip appeared behind
                // the card" rather than as a sidebar sliding in. At rest it now sits a
                // full width out, so opening is the list arriving.
                .offset { IntOffset((-sidebarWidthPx * (1f - sidebarReveal)).toInt(), 0) }
                .alpha(0.55f + 0.45f * sidebarReveal)
                .drawerDrag(shell, sidebarWidthPx, indexWidthPx),
        ) {
            Sidebar(
                model = model,
                state = state,
                shell = shell,
                onNewSession = { showingNewSession = true },
                onPickHost = { showingPicker = true },
                onSettings = { showingSettings = true },
                onOpenConnection = { showingConnection = true },
            )
        }

        // The turn index, the sidebar's mirror on the right. Same one number, opposite
        // sign: dragging the conversation left brings this out while the card steps
        // aside on the left.
        Box(
            modifier = Modifier
                .width(indexWidth)
                .fillMaxSize()
                .align(Alignment.CenterEnd)
                .offset { IntOffset((indexWidthPx * (1f - indexReveal)).toInt(), 0) }
                .alpha(0.55f + 0.45f * indexReveal)
                .drawerDrag(shell, sidebarWidthPx, indexWidthPx),
        ) {
            TurnIndex(
                state = state,
                now = System.currentTimeMillis(),
                onScreen = onScreenTurns,
                onPick = { index ->
                    // Put the drawer away first: the settle is what makes the jump read
                    // as "go there" rather than as the list moving underneath something
                    // that is still in the way.
                    shell.close()
                    scope.launch {
                        // The page-up row above the turns shifts every index by one.
                        val header = if (!state.exhausted && state.oldestSeq != null) 1 else 0
                        conversationList.animateScrollToItem(index + header)
                        glowingTurn = index
                        kotlinx.coroutines.delay(1_400)
                        glowingTurn = null
                    }
                },
            )
        }
        ConversationCard(
            model = model,
            state = state,
            shell = shell,
            progress = p,
            reveal = reveal,
            sidebarWidth = sidebarWidth,
            sidebarWidthPx = sidebarWidthPx,
            indexWidthPx = indexWidthPx,
            conversationList = conversationList,
            glowingTurn = glowingTurn,
            onNewSession = { showingNewSession = true },
        )

        // The card, while it is pushed aside, is one big button back.
        if (reveal > 0.02f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset((if (p >= 0f) sidebarWidthPx * p else indexWidthPx * p).toInt(), 0) }
                    .drawerDrag(shell, sidebarWidthPx, indexWidthPx)
                    .then(
                        if (reveal > 0.5f) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { shell.close() }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }

    if (showingSettings) {
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
        )
    }
    if (showingConnection) {
        ConnectionPanel(
            model = model,
            state = state,
            onDismiss = { showingConnection = false },
            onSettings = { showingSettings = true },
        )
    }
    if (showingNewSession) {
        NewSessionDialog(
            state = state,
            onDismiss = { showingNewSession = false },
            onCreate = { cwd ->
                showingNewSession = false
                model.createSession(cwd)
            },
        )
    }
    if (showingPicker) {
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

/** The conversation, pushed aside as a card while the sidebar shows. */
@Composable
private fun androidx.compose.foundation.layout.BoxWithConstraintsScope.ConversationCard(
    model: AppViewModel,
    state: UiState,
    shell: ShellState,
    progress: Float,
    reveal: Float,
    sidebarWidth: Dp,
    sidebarWidthPx: Float,
    indexWidthPx: Float,
    conversationList: LazyListState,
    glowingTurn: Int?,
    onNewSession: () -> Unit,
) {
    val density = LocalDensity.current
    val dark = LocalMizarDark.current
    val corner = Radius.cardPushed * min(1f, reveal * 3f)
    val shape = RoundedCornerShape(corner)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = if (progress >= 0f) sidebarWidthPx * progress else indexWidthPx * progress
                val s = 1f - 0.06f * reveal
                scaleX = s
                scaleY = s
                // The card shrinks away from the drawer that is out, not always from
                // the left: pushed by the sidebar it recedes toward the right, pushed
                // by the index it recedes toward the left.
                transformOrigin = TransformOrigin(if (progress >= 0f) 0f else 1f, 0.5f)
            }
            .shadow(
                elevation = 30.dp * reveal,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = (if (dark) 0.5f else 0.16f) * reveal),
                spotColor = Color.Black.copy(alpha = (if (dark) 0.5f else 0.16f) * reveal),
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.background)
            .drawerDrag(shell, sidebarWidthPx, indexWidthPx),
    ) {
        // Keyed on the session, which is what makes every piece of state inside
        // belong to the session it was filled from: the draft, the scroll anchor,
        // the paging allowance. One identity per session is the tag that covers the
        // ones nobody remembered to add.
        val sessionId = state.selectedSessionId
        if (sessionId != null) {
            androidx.compose.runtime.key(sessionId) {
                SessionScreen(
                    model = model,
                    state = state,
                    onOpenSidebar = { shell.toggle() },
                    onOpenIndex = { shell.toggleIndex() },
                    listState = conversationList,
                    highlightTurn = glowingTurn,
                    drawerOpen = shell.isOpen,
                )
            }
        } else {
            HomeView(
                model = model,
                state = state,
                onOpenSidebar = { shell.toggle() },
                onChooseWorkspace = onNewSession,
            )
        }

        // A shade, and a faint rim of the accent on the edge that faces the drawer: the
        // card catching the light of whatever is behind it. The rim is on the right when
        // the index is out, which is the same statement said backwards.
        if (reveal > 0f) {
            val rim = listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f * reveal),
                Color.White.copy(alpha = 0.06f * reveal),
                Color.Transparent,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(Color.Black.copy(alpha = (if (dark) 0.32f else 0.05f) * reveal))
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(if (progress >= 0f) rim else rim.reversed()),
                        shape = shape,
                    ),
            )
        }
    }
}

/**
 * The drag that opens and closes the two drawers.
 *
 * Ported from iOS `Views/Shell/SidebarPan.swift`. Horizontal touch slop is the
 * axis gate: a drag that goes vertical is claimed by the conversation's own
 * scroll before this ever fires, which is the Compose equivalent of the pan
 * recognizer failing fast and letting the scroll view through.
 *
 * Two calls, because `awaitHorizontalTouchSlopOrCancellation` calls its callback
 * **once** -- at the moment the slop is crossed -- and returns. Treating that
 * callback as the drag loop is why the card used to twitch by the slop and settle
 * back: the gesture was accepted, one delta was applied, and then it was let go.
 * The follow-up `horizontalDrag` is what actually follows the finger.
 */
private fun Modifier.drawerDrag(
    shell: ShellState,
    sidebarWidthPx: Float,
    indexWidthPx: Float,
): Modifier = this.pointerInput(sidebarWidthPx, indexWidthPx) {
    val sidebarWidth = sidebarWidthPx.coerceAtLeast(1f)
    val indexWidth = indexWidthPx.coerceAtLeast(1f)
    awaitEachGesture {
        // Closed: either direction, and the sign of the movement picks the drawer.
        // A drawer already out accepts only the way back -- which is what stops a
        // flick that closes the sidebar from carrying the card on into the index.
        // A hair of tolerance either way: the gate is about which drawer is out, and
        // an exact float comparison there is a way to refuse a gesture nobody meant to
        // refuse.
        val allowRight = shell.settledAt <= 0.001f
        val allowLeft = shell.settledAt >= -0.001f
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        var began = false
        var rightward = false
        var width = sidebarWidth
        // `dragBy` takes the translation since the drag began, not the step since the
        // last event, so the steps are accumulated here. The touch slop that has to be
        // crossed before the drag is recognised is not part of `travelled`; the
        // thresholds in `endDrag` are read against this number and account for it.
        var travelled = 0f
        val accepted = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            rightward = change.positionChange().x > 0f
            if ((rightward && !allowRight) || (!rightward && !allowLeft)) {
                return@awaitHorizontalTouchSlopOrCancellation
            }
            width = if (rightward) sidebarWidth else indexWidth
            began = true
            shell.beginDrag()
            travelled = over / width
            tracker.addPosition(change.uptimeMillis, change.position)
            shell.dragBy(travelled)
            change.consume()
        }
        if (accepted != null && began) {
            tracker.addPosition(accepted.uptimeMillis, accepted.position)
            horizontalDrag(accepted.id) { change ->
                travelled += change.positionChange().x / width
                tracker.addPosition(change.uptimeMillis, change.position)
                shell.dragBy(travelled)
                change.consume()
            }
            val velocity = try {
                tracker.calculateVelocity().x / width
            } catch (_: IllegalStateException) {
                0f
            }
            shell.endDrag(velocity, travelled)
        }
    }
}
