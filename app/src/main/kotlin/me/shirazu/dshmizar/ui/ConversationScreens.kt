package me.shirazu.dshmizar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.Phase
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.ApprovalOutcome
import me.shirazu.mizar.kit.ConversationItem
import me.shirazu.mizar.kit.ConversationTurn
import me.shirazu.mizar.kit.Markdown
import me.shirazu.mizar.kit.MarkdownStyle
import me.shirazu.mizar.kit.ModelSelection
import me.shirazu.mizar.kit.PendingApproval
import me.shirazu.mizar.kit.PendingQuestion
import me.shirazu.mizar.kit.QuestionAnswer
import me.shirazu.mizar.kit.QuestionItem

/**
 * One conversation: what was said, what is waiting for an answer, and the composer.
 *
 * The running-turn clock ticks here rather than being re-read from the host: an
 * elapsed time is arithmetic on a timestamp that has not changed, and asking the
 * host once a second would put a request behind every visible second.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(model: AppViewModel, state: UiState) {
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
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var showingModelPicker by remember { mutableStateOf(false) }
    var showingPermissions by remember { mutableStateOf(false) }
    var showingContext by remember { mutableStateOf(false) }
    var showingCommands by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    BackHandler { model.selectSession(null) }

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
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
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
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    // A different conversation opens at its own end, and the first load has to land
    // there too. `itemCount` is an effect key on purpose: the first pass runs before the
    // list has measured anything, and scrolling then scrolled to the top -- which is
    // exactly the "always opens at the top" the operator reported.
    val itemCount = listState.layoutInfo.totalItemsCount

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
            listState.scrollToItem(itemCount - 1)
            positioned = true
        } else if (atBottom) {
            counted = size
            waitingNew = 0
            following = true
            listState.animateScrollToItem(itemCount - 1)
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
        listState.scrollToItem(itemCount - 1)
    }
    val running = state.sessions.firstOrNull { it.sessionId == state.selectedSessionId }?.running == true
    val hasPending = state.pending?.isEmpty == false
    val lastTurnFailed = state.turns.lastOrNull()?.steps?.any { it is ConversationItem.Failure } == true

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.stats?.title ?: stringResource(R.string.session),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        StatusLine(running = running, pending = hasPending, failed = lastTurnFailed)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { model.selectSession(null) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // The screen follows pushed events, but a phone whose process was
                    // paused -- or a host that restarted -- can be stale for minutes.
                    // One button beats explaining why the transcript is behind.
                    IconButton(onClick = { model.refreshConversation() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
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
                if (!atBottom && state.conversation.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(end = 12.dp), contentAlignment = Alignment.CenterEnd) {
                        SmallFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    scrollToEnd()
                                    waitingNew = 0
                                }
                            },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
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
                ComposerBar(
                    state = state,
                    onModel = { showingModelPicker = true },
                    onContext = { showingContext = true },
                    onCommands = { showingCommands = true },
                )
                Composer(
                    draft = draft,
                    onDraft = { draft = it },
                    running = running,
                    onSend = {
                        val text = draft
                        draft = ""
                        model.sendPrompt(text)
                    },
                    onStop = { model.cancelTurn() },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).alpha(if (positioned) 1f else 0f)) {
            when {
                state.loadingConversation && state.conversation.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                state.conversation.isEmpty() && state.conversationError != null ->
                    Message(stringResource(R.string.conversation_failed), state.conversationError)

                state.conversation.isEmpty() ->
                    Message(stringResource(R.string.nothing_yet), stringResource(R.string.nothing_yet_body))

                else -> ConversationList(
                    state = state,
                    listState = listState,
                    now = now,
                    onLoadOlder = { scope.launch { model.loadOlder() } },
                    onCopy = onCopy,
                )
            }
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
private fun Message(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConversationList(
    state: UiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    now: Long,
    onLoadOlder: () -> Unit,
    onCopy: (String) -> Unit,
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        if (!state.exhausted && state.oldestSeq != null) {
            item {
                TextButton(onClick = onLoadOlder, modifier = Modifier.fillMaxWidth()) {
                    if (state.loadingConversation) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.load_earlier))
                }
            }
        }

        if (state.turns.isEmpty()) {
            items(state.conversation, key = { it.id }) { item -> ConversationRow(item, onCopy = onCopy) }
        } else {
            items(state.turns, key = { it.id }) { turn -> TurnSection(turn, now, state, onCopy) }
        }
    }
}

/**
 * One turn.
 *
 * The prompt is always drawn; the steps fold behind a bar **except** for a failed
 * turn, whose failure is the thing the operator needs to see. A running turn shows
 * the bar and no answer, because an answer that has not been written does not exist.
 */
@Composable
private fun TurnSection(turn: ConversationTurn, now: Long, state: UiState, onCopy: (String) -> Unit) {
    var open by remember(turn.id, turn.endedAtMillis) { mutableStateOf(false) }
    val answer = if (turn.isRunning) null else turn.steps.lastOrNull { it is ConversationItem.Message && it.role == ConversationItem.Role.ASSISTANT }
    val steps = if (answer == null) turn.steps else turn.steps - answer

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        for (prompt in turn.prompts) ConversationRow(prompt, onCopy = onCopy)

        if (turn.isCollapsible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (turn.isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = if (turn.failed) MizarColors.Warning else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(summary(turn, now), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.step_count, turn.steps.size),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (!turn.isCollapsible || open) {
            for (step in steps) ConversationRow(step, indent = true, onCopy = onCopy)
        }

        answer?.let { ConversationRow(it, onCopy = onCopy) }
    }
}

/** "Running for 12 s", "Completed in 1 min 4 s", "Unfinished after 3 s". */
@Composable
private fun summary(turn: ConversationTurn, now: Long): String {
    val spelled = Format.duration(turn.elapsedMillis(now))
    return when {
        turn.isRunning -> stringResource(R.string.running_for, spelled ?: "0 s")
        turn.failed -> spelled?.let { stringResource(R.string.unfinished_after, it) }
            ?: stringResource(R.string.unfinished)
        else -> spelled?.let { stringResource(R.string.completed_in, it) }
            ?: stringResource(R.string.completed)
    }
}

/** One item: a message, an activity marker or a failure card. */
@Composable
private fun ConversationRow(item: ConversationItem, indent: Boolean = false, onCopy: (String) -> Unit) {
    when (item) {
        is ConversationItem.Message -> MessageBubble(item, indent, onCopy)
        is ConversationItem.Activity -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(item.label, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            item.atMillis?.let {
                Text(Format.clockTime(it), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }

        is ConversationItem.Failure -> Surface(
            color = MizarColors.FailureFill,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.turn_failed),
                    style = MaterialTheme.typography.labelLarge,
                    color = MizarColors.Failure,
                )
                Spacer(Modifier.height(4.dp))
                SelectionContainer { Text(item.message, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** A user turn on the right, everything else on the left. */
@Composable
private fun MessageBubble(item: ConversationItem.Message, indent: Boolean, onCopy: (String) -> Unit) {
    val isUser = item.role == ConversationItem.Role.USER
    val bubble = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else if (item.role == ConversationItem.Role.SYSTEM) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(color = bubble, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(0.92f)) {
            Column(Modifier.padding(10.dp)) {
                if (item.role == ConversationItem.Role.SYSTEM) {
                    Text(stringResource(R.string.system_label), style = MaterialTheme.typography.labelSmall, color = textColor)
                }
                if (item.role != ConversationItem.Role.SYSTEM) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { onCopy(item.text) }, modifier = Modifier.size(26.dp)) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.copy_message),
                                tint = textColor,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
                SelectionContainer {
                    Text(
                        text = markdown(item.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                }
            }
        }
    }
}

/**
 * Markdown, as an `AnnotatedString`.
 *
 * The parser is in the protocol module and returns offsets, so it can be tested on
 * the JVM; this only maps its spans onto Compose styling. A document that could not
 * be parsed is drawn verbatim, which is the fallback that guarantees a half-streamed
 * answer is still readable.
 */
@Composable
private fun markdown(source: String): AnnotatedString {
    val document = remember(source) { Markdown.parse(source) }
    if (document.plain) return AnnotatedString(document.text)

    // Read here, outside the builder: the builder's lambda is not composable, so a
    // theme read inside it is a compile error rather than a styling bug.
    val codeColor = MaterialTheme.colorScheme.onSurface
    val headingSize = MaterialTheme.typography.titleMedium.fontSize
    return remember(document, codeColor, headingSize) {
        buildAnnotatedString {
            append(document.text)
            for (span in document.spans) {
                if (span.end > document.text.length) continue
                val style = when (span.style) {
                    MarkdownStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    MarkdownStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    MarkdownStyle.CODE, MarkdownStyle.CODE_BLOCK ->
                        SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)

                    MarkdownStyle.HEADING -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingSize)
                    MarkdownStyle.LIST_ITEM -> SpanStyle(fontWeight = FontWeight.Normal)
                    MarkdownStyle.QUOTE -> SpanStyle(fontStyle = FontStyle.Italic)
                    MarkdownStyle.LINK -> SpanStyle(textDecoration = TextDecoration.Underline)
                }
                addStyle(style, span.start, span.end)
            }
        }
    }
}

/** The two pills above the composer, and the command button. */
@Composable
private fun ComposerBar(
    state: UiState,
    onModel: () -> Unit,
    onContext: () -> Unit,
    onCommands: () -> Unit,
) {
    val selection = state.selectedSessionId?.let { state.modelChoice[it] }
    val model = selection?.model ?: state.stats?.model?.next?.model ?: state.stats?.model?.lastUsed?.model
    val effort = selection?.reasoningEffort ?: state.stats?.model?.next?.reasoningEffort
    val label = listOfNotNull(model, effort?.replaceFirstChar { it.uppercase() }).joinToString(" · ")

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill(onClick = onModel, modifier = Modifier.weight(1f, fill = false)) {
            Text(label.ifEmpty { stringResource(R.string.model) }, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }

        val context = state.stats?.context
        val percent = context?.percent
        Pill(
            onClick = onContext,
            enabled = context != null,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            // An em dash when the host reported no context figure. Rendering a missing
            // projection as `0%` is the one thing this protocol asks clients not to do
            // -- "absent means the capability is not composed, not that its value is
            // zero" -- and it is also just wrong: an empty context is not a full one.
            Text(
                text = if (context == null || percent == null) {
                    stringResource(R.string.context_unknown)
                } else {
                    stringResource(R.string.context_percent, percent)
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Pill(onClick = onCommands, modifier = Modifier.weight(1f, fill = false)) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.commands), maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Pill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
        modifier = modifier.then(
            if (enabled) Modifier.clickable { onClick() } else Modifier,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
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
    val tint: Color
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
            Box(Modifier.size(6.dp).background(tint, CircleShape))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
/** The field, and the one button that is send, stop, or neither. */
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            placeholder = { Text(stringResource(R.string.composer_hint)) },
            maxLines = 8,
            shape = RoundedCornerShape(19.dp),
            modifier = Modifier.weight(1f),
            // Explicit surfaces, not the theme defaults: the default container is a
            // tinted role that read as "a dark blue box", and a selection drawn in the
            // accent over it left the selected words hard to see. The selection
            // colours themselves come from MizarTheme.
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        val showsStop = running && draft.isBlank()
        val sendEnabled = !showsStop && draft.isNotBlank()
        if (showsStop) {
            IconButton(
                onClick = onStop,
                modifier = Modifier.size(48.dp).background(MizarColors.Failure, CircleShape),
            ) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop), tint = Color.White)
            }
        } else {
            IconButton(
                onClick = onSend,
                enabled = sendEnabled,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (sendEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    ),
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = stringResource(R.string.send),
                    tint = if (sendEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * What is waiting for an answer.
 *
 * An approval has exactly two outcomes. There is no "always allow": a permission
 * that outlives the request it was granted for is a permission *change*, and that
 * belongs on the permission screen where it is visible and revocable.
 */
@Composable
internal fun InteractionsSection(
    approvals: List<PendingApproval>,
    questions: List<PendingQuestion>,
    onApproval: (String, ApprovalOutcome) -> Unit,
    onQuestions: (String, List<QuestionAnswer>) -> Unit,
) {
    // Bounded and scrollable. A pending plan review carries the whole plan as its
    // detail: unbounded, it made the bottom bar taller than the screen, which squeezed
    // the Scaffold's content to nothing and left the bar, the transcript and the app bar
    // drawing over each other. The height is a fraction of the screen so landscape and
    // split-screen behave too.
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        for (approval in approvals) {
            Surface(
                color = MizarColors.WarningFill,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .border(1.dp, MizarColors.Warning.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.wants_to_run, approval.toolName),
                        style = MaterialTheme.typography.titleSmall,
                        color = MizarColors.Warning,
                    )
                    approval.reasonText(java.util.Locale.getDefault().language)?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onApproval(approval.requestId, ApprovalOutcome.REJECTED) },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.reject)) }
                        Button(
                            onClick = { onApproval(approval.requestId, ApprovalOutcome.ALLOWED_ONCE) },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.allow_once)) }
                    }
                }
            }
        }

        for (question in questions) {
            QuestionCard(question, onQuestions)
        }
    }
}

@Composable
private fun QuestionCard(question: PendingQuestion, onSubmit: (String, List<QuestionAnswer>) -> Unit) {
    val selected = remember(question.callId) { mutableStateMapOf<String, Set<String>>() }
    val custom = remember(question.callId) { mutableStateMapOf<String, String>() }

    // A question with one option is a confirmation, so it starts answered.
    LaunchedEffect(question.callId) {
        for (item in question.questions) {
            if (item.options.size == 1 && selected[item.id] == null) {
                selected[item.id] = setOf(item.options.first().label)
            }
        }
    }

    val complete = question.questions.all { item ->
        !(selected[item.id].isNullOrEmpty()) || !custom[item.id].isNullOrBlank()
    }
    val planReview = question.questions.any { it.intent?.isPlanReview == true }

    Surface(
        color = MizarColors.InfoFill,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, MizarColors.Info.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
    ) {
        Column(Modifier.padding(12.dp)) {
            for (item in question.questions) {
                QuestionBody(
                    item = item,
                    chosen = selected[item.id].orEmpty(),
                    customText = custom[item.id].orEmpty(),
                    onChoose = { label ->
                        val current = selected[item.id].orEmpty()
                        selected[item.id] = when {
                            item.multiSelect && label in current -> current - label
                            item.multiSelect -> current + label
                            label in current -> emptySet()
                            else -> setOf(label)
                        }
                    },
                    onCustom = { custom[item.id] = it },
                )
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    val answers = question.questions.map { item ->
                        QuestionAnswer(
                            id = item.id,
                            selected = selected[item.id]?.toList().orEmpty(),
                            custom = custom[item.id]?.trim()?.takeIf { it.isNotEmpty() },
                        )
                    }
                    onSubmit(question.callId, answers)
                },
                enabled = complete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (planReview) stringResource(R.string.approve_plan) else stringResource(R.string.send_answers))
            }
        }
    }
}

@Composable
private fun QuestionBody(
    item: QuestionItem,
    chosen: Set<String>,
    customText: String,
    onChoose: (String) -> Unit,
    onCustom: (String) -> Unit,
) {
    Column {
        item.header?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MizarColors.Info)
        }
        Text(item.question, style = MaterialTheme.typography.titleSmall)
        item.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Spacer(Modifier.height(2.dp))
            if (detail.length <= LONG_DETAIL) {
                Text(detail, style = MaterialTheme.typography.bodySmall)
            } else {
                // A plan is thousands of characters. Six lines and a toggle keep the
                // card — and with it the buttons under it — on screen.
                var expanded by remember(item.id, detail.length) { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (expanded) {
                                Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (expanded) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (expanded) {
                            stringResource(R.string.collapse_detail)
                        } else {
                            stringResource(R.string.expand_detail, detail.length)
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        for (option in item.options) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onChoose(option.label) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.multiSelect) {
                    Checkbox(checked = option.label in chosen, onCheckedChange = { onChoose(option.label) })
                } else {
                    RadioButton(selected = option.label in chosen, onClick = { onChoose(option.label) })
                }
                Column {
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    option.description?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        OutlinedTextField(
            value = customText,
            onValueChange = onCustom,
            placeholder = { Text(stringResource(R.string.or_type_your_own)) },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Choose a model, and then its reasoning effort. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(state: UiState, onDismiss: () -> Unit, onChoose: (ModelSelection) -> Unit) {
    val catalog = state.modelCatalog
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.model),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        if (catalog == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@ModalBottomSheet
        }
        LazyColumn {
            for (provider in catalog.providers) {
                item(key = "p-${provider.id}") {
                    Text(
                        provider.name,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(provider.models, key = { "${provider.id}-${it.id}" }) { option ->
                    ListItem(
                        headlineContent = { Text(option.name) },
                        supportingContent = option.description?.let { { Text(it) } },
                        modifier = Modifier.clickable {
                            onChoose(
                                ModelSelection(
                                    provider = provider.id,
                                    model = option.id,
                                    reasoningEffort = option.defaultEffort ?: option.efforts.firstOrNull()?.id,
                                ),
                            )
                        },
                    )
                }
            }
            // A provider that could not be listed is shown, not hidden: an empty
            // picker with no explanation is a bug report nobody can act on.
            if (catalog.failures.isNotEmpty()) {
                item(key = "failures") {
                    Text(
                        stringResource(R.string.unavailable),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(catalog.failures, key = { it.id }) { failure ->
                    ListItem(
                        headlineContent = { Text(failure.name) },
                        supportingContent = { Text(failure.message) },
                    )
                }
            }
        }
    }
}

/** Choose a permission preset, with a warning in front of the dangerous one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionSheet(state: UiState, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    var confirming by remember { mutableStateOf<String?>(null) }
    val catalog = state.permissionCatalog

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.permissions),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        if (catalog == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ModalBottomSheet
        }
        LazyColumn {
            items(catalog.options, key = { it.value }) { option ->
                ListItem(
                    headlineContent = { Text(option.name) },
                    supportingContent = option.description?.let { { Text(it) } },
                    leadingContent = {
                        if (option.isDangerous) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = MizarColors.Failure)
                        }
                    },
                    modifier = Modifier.clickable {
                        if (option.isDangerous) confirming = option.value else onChoose(option.value)
                    },
                )
            }
        }
        Text(
            stringResource(R.string.permission_note),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(16.dp),
        )
        confirming?.let { preset ->
            Button(
                onClick = { onChoose(preset) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text(stringResource(R.string.allow_full_access))
            }
        }
    }
}

/** The context breakdown, with an em dash for everything the host did not send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextSheet(state: UiState, onDismiss: () -> Unit) {
    val context = state.stats?.context
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.context),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        if (context == null) {
            Text(
                stringResource(R.string.no_context_figure),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            return@ModalBottomSheet
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Labelled(stringResource(R.string.window), Format.countOrDash(context.window))
            Labelled(stringResource(R.string.pressure), Format.countOrDash(context.pressureTokens))
            Labelled(stringResource(R.string.projected), Format.countOrDash(context.projectedTokens))
            Labelled(stringResource(R.string.percent_full), context.percent?.let { "$it%" } ?: "—")
            context.breakdown?.let { breakdown ->
                Spacer(Modifier.height(8.dp))
                Labelled(stringResource(R.string.system), Format.count(breakdown.systemTokens))
                Labelled(stringResource(R.string.tools), Format.count(breakdown.toolsTokens))
                Labelled(stringResource(R.string.messages), Format.count(breakdown.messageTokens))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Labelled(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The four commands the composer offers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandSheet(onDismiss: () -> Unit, onCommand: (String) -> Unit) {
    val commands = listOf(
        "/goal" to R.string.command_goal,
        "/plan" to R.string.command_plan,
        "/compact" to R.string.command_compact,
        "/model" to R.string.command_model,
    )
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.commands),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        for ((slash, label) in commands) {
            ListItem(
                headlineContent = { Text(stringResource(label)) },
                supportingContent = { Text(slash, fontFamily = FontFamily.Monospace) },
                modifier = Modifier.clickable { onCommand(slash) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Detail text beyond this many characters is offered collapsed. */
private const val LONG_DETAIL = 400
