package me.shirazu.dshmizar.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

    var showingSettings by remember { mutableStateOf(false) }
    var showingNewSession by remember { mutableStateOf(false) }
    var showingPicker by remember { mutableStateOf(false) }

    // Open on a launch with nothing selected: the list is the only useful thing to
    // look at. Placed without animating; the first frame is a state, not a change.
    LaunchedEffect(Unit) {
        if (!placed) {
            placed = true
            shell.place(open = state.selectedSessionId == null)
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

        val p = shell.progress.value
        val reveal = p.coerceIn(0f, 1f)

        // The sidebar comes forward as the card leaves: a little travel and a little
        // light, so the two read as layers rather than as one strip sliding sideways.
        Box(
            modifier = Modifier
                .width(sidebarWidth)
                .fillMaxSize()
                .offset { IntOffset((-with(density) { 44.dp.toPx() } * (1f - reveal)).toInt(), 0) }
                .alpha(0.35f + 0.65f * reveal)
                .sidebarDrag(shell, sidebarWidthPx, opening = false, enabled = true),
        ) {
            Sidebar(
                model = model,
                state = state,
                shell = shell,
                onNewSession = { showingNewSession = true },
                onPickHost = { showingPicker = true },
                onSettings = { showingSettings = true },
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
            onNewSession = { showingNewSession = true },
        )

        // The card, while it is pushed aside, is one big button back.
        if (reveal > 0.02f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset((sidebarWidthPx * p).toInt().coerceAtLeast(0), 0) }
                    .sidebarDrag(shell, sidebarWidthPx, opening = false, enabled = true)
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
                translationX = sidebarWidthPx * progress
                val s = 1f - 0.06f * reveal
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .shadow(
                elevation = 30.dp * reveal,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = (if (dark) 0.5f else 0.16f) * reveal),
                spotColor = Color.Black.copy(alpha = (if (dark) 0.5f else 0.16f) * reveal),
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.background)
            .sidebarDrag(
                shell,
                sidebarWidthPx,
                opening = true,
                enabled = !shell.settledOpen || shell.isDragging,
            ),
    ) {
        // Keyed on the session, which is what makes every piece of state inside
        // belong to the session it was filled from: the draft, the scroll anchor,
        // the paging allowance. One identity per session is the tag that covers the
        // ones nobody remembered to add.
        val sessionId = state.selectedSessionId
        if (sessionId != null) {
            androidx.compose.runtime.key(sessionId) {
                SessionScreen(model, state, onOpenSidebar = { shell.toggle() })
            }
        } else {
            HomeView(
                model = model,
                state = state,
                onOpenSidebar = { shell.toggle() },
                onChooseWorkspace = onNewSession,
            )
        }

        // A shade, and a faint rim of the accent on the edge that faces the sidebar:
        // the card catching the light of the list behind it.
        if (reveal > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(Color.Black.copy(alpha = (if (dark) 0.32f else 0.05f) * reveal))
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f * reveal),
                                Color.White.copy(alpha = 0.06f * reveal),
                                Color.Transparent,
                            ),
                        ),
                        shape = shape,
                    ),
            )
        }
    }
}

/**
 * The drag that opens and closes the sidebar.
 *
 * Ported from iOS `Views/Shell/SidebarPan.swift`. Horizontal touch slop is the
 * axis gate: a drag that goes vertical is claimed by the conversation's own
 * scroll before this ever fires, which is the Compose equivalent of the pan
 * recognizer failing fast and letting the scroll view through.
 */
private fun Modifier.sidebarDrag(
    shell: ShellState,
    widthPx: Float,
    opening: Boolean,
    enabled: Boolean,
): Modifier = this.pointerInput(widthPx, opening, enabled) {
    if (!enabled) return@pointerInput
    val width = widthPx.coerceAtLeast(1f)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        var began = false
        awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            val directionRight = change.positionChange().x > 0
            // Sideways means sideways, and in this recognizer's own direction: an
            // opening drag on the card goes right, a closing drag goes left.
            if (directionRight != opening && !began) return@awaitHorizontalTouchSlopOrCancellation
            if (!began) {
                shell.beginDrag()
                began = true
            }
            tracker.addPosition(change.uptimeMillis, change.position)
            shell.dragBy(over / width)
            change.consume()
        }
        if (began) {
            val velocity = try {
                tracker.calculateVelocity().x / width
            } catch (_: IllegalStateException) {
                0f
            }
            shell.endDrag(velocity)
        }
    }
}
