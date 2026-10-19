package me.shirazu.dshmizar.ui.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.Transitions
import me.shirazu.dshmizar.ui.design.glassCapsule
import me.shirazu.dshmizar.ui.design.pressable
import me.shirazu.mizar.kit.ConversationItem

/**
 * One conversation: what was said, what is waiting for an answer, and the composer.
 *
 * The running-turn clock ticks here rather than being re-read from the host: an
 * elapsed time is arithmetic on a timestamp that has not changed, and asking the
 * host once a second would put a request behind every visible second.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    model: AppViewModel,
    state: UiState,
    onOpenSidebar: () -> Unit = {},
    // Tapping the title opens the turn index, as it does on iOS: the two drawers are
    // mirrors, and this is the mirror of the menu button.
    onOpenIndex: () -> Unit = {},
    // The shell owns these two: the turn index scrolls this list, and highlights the
    // turn it scrolled to. Null means "make your own", which is what every other
    // caller wants.
    listState: androidx.compose.foundation.lazy.LazyListState? = null,
    highlightTurn: Int? = null,
    // A drawer is out in front of the conversation: back belongs to the drawer, not
    // to leaving the session.
    drawerOpen: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val copiedMessage = stringResource(R.string.copied)
    // Whole-message copy. Selecting inside a bubble is for fragments and it is fiddly
    // with a thumb; "take the answer" is the common action and deserves one tap.
    val onCopy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbar.showSnackbar(copiedMessage) }
    }
    val list = listState ?: rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    // Whether the field holds the keyboard, so the dock can tuck into a pill.
    var composerFocused by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var showingModelPicker by remember { mutableStateOf(false) }
    var showingPermissions by remember { mutableStateOf(false) }
    var showingContext by remember { mutableStateOf(false) }
    var showingCommands by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    BackHandler(enabled = !drawerOpen) { model.selectSession(null) }

    // The catalogs behind the model and permission sheets. Loaded here rather than
    // when a sheet opens: a sheet that has to fetch before it can draw shows a spinner
    // for as long as the round trip takes, and on the device that looked like a sheet
    // that never loads at all. The model already skips a second fetch.
    LaunchedEffect(state.selectedSessionId) {
        if (state.selectedSessionId != null) model.loadCatalogs()
    }

    // One ticker for every running turn on screen, alive only while this is.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    // Follow new output only while the operator is at the bottom. Scrolling up to read
    // something must not be undone by the agent writing its next sentence -- which is
    // exactly what the old unconditional scroll did.
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    var counted by remember { mutableIntStateOf(0) }
    var waitingNew by remember { mutableIntStateOf(0) }
    // Nothing is drawn until the first load is parked at the end: the operator opens a
    // conversation and it is already there, rather than watching it slide up from the top.
    var positioned by remember { mutableStateOf(false) }
    // Whether the view should track the end. Kept explicitly because it has to survive
    // changes the operator did not make -- approving a plan removes the card under the
    // transcript, and the newest message was left half cut off without this.
    var following by remember { mutableStateOf(true) }

    /**
     * The end of the list, whatever is in it.
     *
     * The transcript is `[load earlier]? + items`, so the last index is not simply the
     * item count; `totalItemsCount - 1` is the one form that is correct in both shapes
     * and does nothing while the list is still empty.
     */
    suspend fun scrollToEnd() {
        val last = list.layoutInfo.totalItemsCount - 1
        if (last >= 0) list.animateScrollToItem(last)
    }

    // A different conversation opens at its own end, and the first load has to land
    // there too. `itemCount` is an effect key on purpose: the first pass runs before the
    // list has measured anything, and scrolling then scrolled to the top -- which is
    // exactly the "always opens at the top" the operator reported.
    val itemCount = list.layoutInfo.totalItemsCount

    LaunchedEffect(state.selectedSessionId) {
        counted = 0
        waitingNew = 0
        positioned = false
        following = true
    }

    LaunchedEffect(state.conversation.size, itemCount) {
        val size = state.conversation.size
        if (size == 0) {
            counted = 0
            waitingNew = 0
            positioned = true
            following = true
            return@LaunchedEffect
        }
        if (itemCount == 0) return@LaunchedEffect
        val firstLoad = counted == 0
        val added = (size - counted).coerceAtLeast(0)
        if (firstLoad) {
            counted = size
            waitingNew = 0
            following = true
            // Instant, not animated: opening a conversation shows its newest message at
            // once. Following live output stays animated, because that one is a motion
            // the operator should be able to track.
            list.scrollToItem(itemCount - 1)
            positioned = true
        } else if (atBottom) {
            counted = size
            waitingNew = 0
            following = true
            list.animateScrollToItem(itemCount - 1)
        } else if (added > 0) {
            counted = size
            following = false
            waitingNew += added
        }
    }

    // The pending cards live under the transcript, so when one is answered the list's
    // viewport grows. Anchored at the end before the change must mean anchored at the
    // end after it: without this, approving a plan left the newest message clipped and
    // the "latest" button showing as if the operator had scrolled away.
    val pendingCardCount = (state.pending?.approvals?.size ?: 0) + (state.pending?.questions?.size ?: 0)
    LaunchedEffect(pendingCardCount, itemCount) {
        if (!following || itemCount == 0) return@LaunchedEffect
        list.scrollToItem(itemCount - 1)
    }
    // Say when a refresh finished by simply stopping: the spinner halting and
    // the list settling is statement enough -- a black toast on top of the
    // conversation was noise.
    val running = state.sessions.firstOrNull { it.sessionId == state.selectedSessionId }?.running == true
    val hasPending = state.pending?.isEmpty == false
    val lastTurnFailed = state.turns.lastOrNull()?.steps?.any { it is ConversationItem.Failure } == true

    // A send in flight: the words lift out of the field towards the place their
    // bubble will take. Skipped under Reduce Motion -- the send is as instant
    // as it ever was.
    val reduceMotion = me.shirazu.dshmizar.ui.design.LocalReduceMotion.current
    var flight by remember { mutableStateOf<FlightSpec?>(null) }
    var overlayOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var overlaySize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.boundsInRoot().topLeft }
            .onSizeChanged { overlaySize = it },
    ) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenIndex),
                    ) {
                        Text(
                            text = state.stats?.title ?: stringResource(R.string.session),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        StatusLine(running = running, pending = hasPending, failed = lastTurnFailed)
                    }
                },
                navigationIcon = {
                    // The shell's toggle: the list is a card underneath, not a
                    // screen behind, so this opens the sidebar rather than popping.
                    IconButton(onClick = onOpenSidebar) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.show_sidebar))
                    }
                },
                actions = {
                    // The screen follows pushed events, but a phone whose process was
                    // paused -- or a host that restarted -- can be stale. The button says
                    // when it is working: a refresh whose progress is invisible reads as
                    // a refresh that did nothing.
                    IconButton(
                        onClick = { model.refreshConversation() },
                        enabled = !state.refreshing,
                    ) {
                        if (state.refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh),
                            )
                        }
                    }
                    IconButton(onClick = { showingPermissions = true }) {
                        Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.permissions))
                    }
                },
            )
        },
        bottomBar = {
            Column(modifier = Modifier.imePadding()) {
                // Shown whenever the transcript is not at its end, not only when something
                // has arrived: "get me back to the newest output" is useful on its own,
                // and it makes the button's absence meaningful.
                AnimatedVisibility(
                    visible = !atBottom && state.conversation.isNotEmpty(),
                    enter = Transitions.popIn(),
                    exit = Transitions.popOut(),
                ) {
                    Box(
                        Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 4.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Row(
                            modifier = Modifier
                                .height(34.dp)
                                .glassCapsule()
                                .pressable(scale = 0.94f)
                                .clickable {
                                    scope.launch {
                                        scrollToEnd()
                                        waitingNew = 0
                                    }
                                }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (waitingNew > 0) {
                                    stringResource(R.string.new_messages, waitingNew)
                                } else {
                                    stringResource(R.string.jump_to_latest)
                                },
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                            )
                        }
                    }
                }
                InteractionsDock(
                    pending = state.pending,
                    titles = state.titles,
                    selectedSessionId = state.selectedSessionId,
                    composerFocused = composerFocused,
                    onExpand = { focusManager.clearFocus() },
                    onApproval = { id, outcome -> model.answerApproval(id, outcome) },
                    onQuestions = { callId, answers -> model.answerQuestions(callId, answers) },
                )
                Composer(
                    state = state,
                    draft = draft,
                    onDraft = { draft = it },
                    running = running,
                    onModel = { showingModelPicker = true },
                    onContext = { showingContext = true },
                    onCommands = { showingCommands = true },
                    commandsOpen = showingCommands,
                    onSend = {
                        val text = draft
                        draft = ""
                        val from = ComposerGeometry.fieldBounds
                        if (!reduceMotion && from != null && atBottom) {
                            flight = FlightSpec(text, from, System.nanoTime())
                        }
                        model.sendPrompt(text)
                    },
                    onStop = { model.cancelTurn() },
                    onFocusChanged = { composerFocused = it },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).alpha(if (positioned) 1f else 0f)) {
            when {
                state.loadingConversation && state.conversation.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                state.conversation.isEmpty() && state.conversationError != null ->
                    EmptyMessage(stringResource(R.string.conversation_failed), state.conversationError)

                state.conversation.isEmpty() ->
                    EmptyMessage(stringResource(R.string.nothing_yet), stringResource(R.string.nothing_yet_body))

                else -> PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { model.refreshConversation() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    ConversationList(
                        state = state,
                        listState = list,
                        now = now,
                        onLoadOlder = { scope.launch { model.loadOlder() } },
                        onCopy = onCopy,
                        onRetry = { model.sendPrompt(it) },
                        highlightTurn = highlightTurn,
                    )
                }
            }
        }
    }

    // The ghost of what was just sent, flying up into the conversation.
    flight?.let { spec ->
        FlightGhost(
            flight = spec,
            overlaySize = overlaySize,
            overlayOrigin = overlayOrigin,
            onDone = { if (flight?.key == spec.key) flight = null },
        )
    }
    }

    if (showingModelPicker) {
        ModelPickerSheet(
            state = state,
            onDismiss = { showingModelPicker = false },
            onChoose = { selection ->
                showingModelPicker = false
                model.setModel(selection)
            },
        )
    }
    if (showingPermissions) {
        PermissionSheet(
            state = state,
            onDismiss = { showingPermissions = false },
            onChoose = { preset ->
                showingPermissions = false
                model.setPermission(preset)
            },
        )
    }
    if (showingContext) {
        ContextSheet(state) { showingContext = false }
    }
    if (showingCommands) {
        CommandSheet(
            onDismiss = { showingCommands = false },
            onCommand = { slash ->
                // Into the draft, then closed: the operator finishes the sentence.
                draft = if (draft.isBlank()) "$slash " else "$draft $slash "
                showingCommands = false
            },
        )
    }
}

@Composable
private fun EmptyMessage(title: String, body: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * What the agent is doing, in the one place the operator already looks.
 *
 * The transcript says what happened; this says what is happening. Four states, because
 * they need four different reactions: waiting on you (act now), thinking (wait), a turn
 * that failed (read the red card), idle (nothing to do).
 */
@Composable
private fun StatusLine(running: Boolean, pending: Boolean, failed: Boolean) {
    val label: String
    val tint: androidx.compose.ui.graphics.Color
    when {
        pending -> { label = stringResource(R.string.status_waiting); tint = MizarColors.Warning }
        running -> { label = stringResource(R.string.status_thinking); tint = MizarColors.Busy }
        failed -> { label = stringResource(R.string.status_failed); tint = MizarColors.Failure }
        else -> { label = stringResource(R.string.status_idle); tint = MaterialTheme.colorScheme.onSurfaceVariant }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = tint,
            )
        } else {
            Box(Modifier.size(6.dp).background(tint, androidx.compose.foundation.shape.CircleShape))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
            color = tint,
        )
    }
}
