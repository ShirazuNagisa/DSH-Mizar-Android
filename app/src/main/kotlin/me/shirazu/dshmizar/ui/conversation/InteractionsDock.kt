package me.shirazu.dshmizar.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.Motion
import me.shirazu.dshmizar.ui.design.Radius
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.glassCapsule
import me.shirazu.dshmizar.ui.design.glassCard
import me.shirazu.dshmizar.ui.design.pressable
import me.shirazu.mizar.kit.ApprovalOutcome
import me.shirazu.mizar.kit.PendingApproval
import me.shirazu.mizar.kit.PendingQuestion
import me.shirazu.mizar.kit.QuestionAnswer
import me.shirazu.mizar.kit.QuestionItem
import me.shirazu.mizar.kit.PendingInteractions

/**
 * What the agent is waiting for -- ported from iOS `Views/InteractionsView.swift`.
 *
 * The requests dock above the composer, where the thumb already is, and the
 * conversation reads underneath. One card at a time, with the others stacked
 * behind it, because a decision is made one at a time; "Next" moves through the
 * deck. Typing tucks the deck into a small pill so the field has the room, and
 * tapping the pill brings it back.
 *
 * Two rules shape the cards:
 *
 *  - **The decision is made with the information, not after it.** The tool, the
 *    reason and the session are on the card, because approving something you
 *    cannot see is not approving it.
 *  - **Only `allowed-once` grants anything.** There is deliberately no "always
 *    allow": a grant that outlives its request is a permission change, and those
 *    belong in the permission picker where they are visible and reversible.
 */

/** One pending request, of either kind. */
internal sealed interface DockCard {
    val id: String
    val sessionId: String?

    data class Approval(val approval: PendingApproval) : DockCard {
        override val id: String get() = "a.${approval.requestId}"
        override val sessionId: String? get() = approval.sessionId
    }

    data class Question(val question: PendingQuestion) : DockCard {
        override val id: String get() = "q.${question.callId}"
        override val sessionId: String? get() = question.sessionId
    }
}

private val DockCard.tint: Color
    @Composable get() = when (this) {
        is DockCard.Approval -> MizarColors.Waiting
        is DockCard.Question -> MizarColors.Question
    }

/** A brief "done" note, shown where the answered card was. */
private data class Confirmation(val text: String, val allowed: Boolean, val key: Long)

@Composable
internal fun InteractionsDock(
    pending: PendingInteractions?,
    titles: Map<String, String>,
    selectedSessionId: String?,
    composerFocused: Boolean,
    onExpand: () -> Unit,
    onApproval: (String, ApprovalOutcome) -> Unit,
    onQuestions: (String, List<QuestionAnswer>) -> Unit,
) {
    val cards = remember(pending) {
        (pending?.approvals.orEmpty().map { DockCard.Approval(it) }) +
            (pending?.questions.orEmpty().map { DockCard.Question(it) })
    }
    var frontId by remember { mutableStateOf<String?>(null) }
    val front = cards.firstOrNull { it.id == frontId } ?: cards.firstOrNull()
    val confirmations = remember { mutableStateListOf<Confirmation>() }
    val context = LocalContext.current

    fun confirm(text: String, allowed: Boolean) {
        val note = Confirmation(text, allowed, System.nanoTime())
        confirmations += note
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (note in confirmations) {
            key(note.key) {
                ConfirmationCapsule(note)
                // "Allowed write_file", for a moment, where the card was.
                LaunchedEffect(note.key) {
                    delay(1700)
                    confirmations.removeAll { it.key == note.key }
                }
            }
        }

        if (cards.isNotEmpty()) {
            if (composerFocused) {
                TuckedPill(count = cards.size, onExpand = onExpand)
            } else if (front != null) {
                Deck(
                    front = front,
                    all = cards,
                    titles = titles,
                    selectedSessionId = selectedSessionId,
                    onNext = {
                        val index = cards.indexOf(front)
                        frontId = cards[(index + 1) % cards.size].id
                    },
                    onApproval = { approval, outcome ->
                        onApproval(approval.requestId, outcome)
                        confirm(
                            context.getString(
                                if (outcome == ApprovalOutcome.ALLOWED_ONCE) R.string.allowed_tool else R.string.rejected_tool,
                                approval.toolName,
                            ),
                            allowed = outcome == ApprovalOutcome.ALLOWED_ONCE,
                        )
                    },
                    onQuestions = { question, answers ->
                        onQuestions(question.callId, answers)
                        confirm(context.getString(R.string.answer_sent), allowed = true)
                    },
                )
            }
        }
    }
}

/** The tucked deck: a pill that says how many are waiting, and brings them back. */
@Composable
private fun TuckedPill(count: Int, onExpand: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .height(38.dp)
                .glassCapsule()
                .pressable(scale = 0.94f)
                .clickable(onClick = onExpand)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✋", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.requests_waiting, count),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The front card, with the rest of the deck peeking over its top edge. */
@Composable
private fun Deck(
    front: DockCard,
    all: List<DockCard>,
    titles: Map<String, String>,
    selectedSessionId: String?,
    onNext: () -> Unit,
    onApproval: (PendingApproval, ApprovalOutcome) -> Unit,
    onQuestions: (PendingQuestion, List<QuestionAnswer>) -> Unit,
) {
    val behind = all.filter { it.id != front.id }.take(2)
    val tint = front.tint
    Box(modifier = Modifier.fillMaxWidth().padding(top = (behind.size * 7).dp)) {
        // The cards behind, as edges peeking over the top of the one in front:
        // there is more, and roughly how much.
        behind.forEachIndexed { depth, card ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .align(Alignment.TopCenter)
                    .offset(y = (-(depth + 1) * 7).dp)
                    .scale(1f - (depth + 1) * 0.05f)
                    .alpha(1f - depth * 0.35f)
                    .zIndex(-(depth + 1).toFloat())
                    .clip(RoundedCornerShape(Radius.card))
                    .background(card.tint.copy(alpha = 0.25f)),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1f)
                .glassCard(radius = Radius.card, tint = tint, tintedBorder = tint),
        ) {
            if (all.size > 1 || (front.sessionId != null && front.sessionId != selectedSessionId)) {
                DeckHeader(
                    card = front,
                    index = all.indexOf(front),
                    count = all.size,
                    titles = titles,
                    selectedSessionId = selectedSessionId,
                    onNext = onNext,
                )
            }
            // Scrolls only once it is taller than the dock may be.
            val maxHeight = maxOf(160.dp, LocalConfiguration.current.screenHeightDp.dp * 0.34f)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                when (front) {
                    is DockCard.Approval -> ApprovalCard(front.approval, onAnswered = { outcome -> onApproval(front.approval, outcome) })
                    is DockCard.Question -> QuestionCard(front.question, onAnswered = { answers -> onQuestions(front.question, answers) })
                }
            }
        }
    }
}

/** Whose request this is, and where in the deck. */
@Composable
private fun DeckHeader(
    card: DockCard,
    index: Int,
    count: Int,
    titles: Map<String, String>,
    selectedSessionId: String?,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val sessionId = card.sessionId
        if (sessionId != null && sessionId != selectedSessionId) {
            // A request from another session is still this operator's to answer,
            // but it should say whose it is.
            Text(
                titles[sessionId] ?: sessionId.take(8),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (count > 1) {
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.deck_position, index + 1, count),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .height(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                    .pressable()
                    .clickable(onClick = onNext)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.next_card),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/** "Allowed write_file", for a moment, where the card was. */
@Composable
private fun ConfirmationCapsule(note: Confirmation) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(note.key) {
        haptics.performHapticFeedback(
            if (note.allowed) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove,
        )
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .height(38.dp)
                .glassCapsule()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (note.allowed) Icons.Default.CheckCircle else Icons.Default.Close,
                contentDescription = null,
                tint = if (note.allowed) MizarColors.Running else MizarColors.Failed,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                note.text,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
    }
}

// MARK: - Approval

/** One approval. Two outcomes, no "always allow" -- see the file header. */
@Composable
private fun ApprovalCard(approval: PendingApproval, onAnswered: (ApprovalOutcome) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✋", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.wants_to_run, approval.toolName),
                style = MaterialTheme.typography.titleMedium,
                color = MizarColors.Waiting,
            )
        }

        approval.reasonText(java.util.Locale.getDefault().language)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        approval.sessionId?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }

        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = { onAnswered(ApprovalOutcome.REJECTED) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MizarColors.Failed),
            ) { Text(stringResource(R.string.reject)) }
            Button(
                onClick = { onAnswered(ApprovalOutcome.ALLOWED_ONCE) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.allow_once)) }
        }
    }
}

// MARK: - Questions

/**
 * One question batch. Answers accumulate locally and are submitted together:
 * the host matches a batch by its call id, and sending one answer per question
 * would leave the agent waiting on the rest.
 */
@Composable
private fun QuestionCard(question: PendingQuestion, onAnswered: (List<QuestionAnswer>) -> Unit) {
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
        !selected[item.id].isNullOrEmpty() || !custom[item.id].isNullOrBlank()
    }
    val planReview = question.questions.any { it.intent?.isPlanReview == true }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                onAnswered(answers)
            },
            enabled = complete,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (planReview) stringResource(R.string.approve_plan) else stringResource(R.string.send_answers))
        }
    }
}

/** One question and its options. */
@Composable
private fun QuestionBody(
    item: QuestionItem,
    chosen: Set<String>,
    customText: String,
    onChoose: (String) -> Unit,
    onCustom: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item.header?.let {
            Text(
                it.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MizarColors.Question,
            )
        }
        Text(item.question, style = MaterialTheme.typography.titleMedium)
        item.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in item.options) {
                OptionRow(
                    label = option.label,
                    description = option.description,
                    isOn = option.label in chosen,
                    multiple = item.multiSelect,
                    onClick = { onChoose(option.label) },
                )
            }
        }

        OutlinedTextField(
            value = customText,
            onValueChange = onCustom,
            placeholder = { Text(stringResource(R.string.or_type_your_own)) },
            maxLines = 4,
            shape = RoundedCornerShape(Radius.sm),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One option: a rounded row with a mark that springs in. */
@Composable
private fun OptionRow(
    label: String,
    description: String?,
    isOn: Boolean,
    multiple: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(
                if (isOn) MizarColors.Question.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
            )
            .then(
                if (isOn) {
                    Modifier.border(1.dp, MizarColors.Question.copy(alpha = 0.45f), RoundedCornerShape(Radius.sm))
                } else {
                    Modifier
                },
            )
            .pressable(scale = 0.98f)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ChoiceMark(isOn = isOn, multiple = multiple, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A radio or a checkbox whose mark springs in -- iOS `ChoiceMark`. */
@Composable
private fun ChoiceMark(isOn: Boolean, multiple: Boolean, modifier: Modifier = Modifier) {
    val shape = if (multiple) RoundedCornerShape(6.dp) else CircleShape
    val fillScale by animateFloatAsState(if (isOn) 1f else 0.3f, Motion.pick(Motion.bouncy()), label = "markScale")
    val fillAlpha by animateFloatAsState(if (isOn) 1f else 0f, Motion.pick(Motion.bouncy()), label = "markAlpha")
    Box(modifier = modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(20.dp)
                .border(
                    1.6.dp,
                    if (isOn) MizarColors.Question else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    shape,
                ),
        )
        Box(Modifier.size(20.dp).scale(fillScale).alpha(fillAlpha).clip(shape).background(MizarColors.Question))
        if (isOn) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp).scale(fillScale),
            )
        }
    }
}
