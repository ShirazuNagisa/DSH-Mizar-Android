package me.shirazu.dshmizar.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.ApprovalOutcome
import me.shirazu.mizar.kit.ModelSelection
import me.shirazu.mizar.kit.PendingApproval
import me.shirazu.mizar.kit.PendingQuestion
import me.shirazu.mizar.kit.QuestionAnswer
import me.shirazu.mizar.kit.QuestionItem

/**
 * What remains of the old conversation screen file: the pending interaction
 * cards and the sheets. The transcript and the composer moved to
 * `conversation/` in the GUI rewrite; these follow in their own stages.
 */

/**
 * What the agent is doing, in the one place the operator already looks.
 *
 * The transcript says what happened; this says what is happening. Four states, because
 * they need four different reactions: waiting on you (act now), thinking (wait), a turn
 * that failed (read the red card), idle (nothing to do).
 */
@Composable
internal fun StatusLine(running: Boolean, pending: Boolean, failed: Boolean) {
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
internal fun ModelPickerSheet(state: UiState, onDismiss: () -> Unit, onChoose: (ModelSelection) -> Unit) {
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
internal fun PermissionSheet(state: UiState, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
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
internal fun ContextSheet(state: UiState, onDismiss: () -> Unit) {
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
internal fun CommandSheet(onDismiss: () -> Unit, onCommand: (String) -> Unit) {
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
