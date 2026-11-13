package me.shirazu.dshmizar.ui.account.surveys

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.domain.SurveyForm
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.ButtonShape
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.mizar.kit.account.Survey
import me.shirazu.mizar.kit.account.SurveyOption
import me.shirazu.mizar.kit.account.SurveyQuestion
import me.shirazu.mizar.kit.account.SurveyQuestionType

/**
 * One questionnaire: its questions, the answers, and the reward.
 *
 * Stateless: the form is a [SurveyForm] handed down, each control calls back with the
 * next one, and the coordinator owns submitting, the reward and the refusal. Three
 * question types, drawn as the system controls they are -- radios, checkboxes and a
 * growing text field -- which is also what makes them usable with TalkBack without a
 * second layout.
 */
@Composable
fun SurveyDetailScreen(
    survey: Survey,
    form: SurveyForm,
    submitting: Boolean,
    /** The days granted, once the relay accepted the submission. */
    rewardDays: Int?,
    failure: String?,
    onFormChange: (SurveyForm) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize().testTag("survey.detail")) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.survey_back),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    stringResource(R.string.survey_questionnaire),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            ) {
                Spacer(Modifier.height(Space.sm))
                Text(
                    text = survey.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                if (survey.summary.isNotBlank()) {
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        text = survey.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(Space.sm))
                Reward(survey.rewardDays)

                Spacer(Modifier.height(Space.lg))
                Text(
                    text = stringResource(R.string.survey_questions),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                survey.questions.forEachIndexed { index, question ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = Space.md),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    } else {
                        Spacer(Modifier.height(Space.sm))
                    }
                    Question(question = question, form = form, onFormChange = onFormChange)
                }
                Spacer(Modifier.height(Space.lg))
                Text(
                    text = stringResource(R.string.survey_answers_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(Space.xl))
                when {
                    rewardDays != null -> ThankYou(rewardDays)
                    survey.completed -> AlreadyAnswered()
                    else -> SubmitArea(
                        enabled = form.isComplete(survey) && !submitting,
                        submitting = submitting,
                        failure = failure,
                        onSubmit = onSubmit,
                    )
                }
            }
        }
    }
}

@Composable
private fun Reward(days: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Star,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(Space.xs))
        Text(
            text = stringResource(R.string.survey_reward, days),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ThankYou(days: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("survey.reward"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(Space.sm))
        Text(
            text = stringResource(R.string.survey_thanks, days),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AlreadyAnswered() {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("survey.completed"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MizarColors.Waiting,
        )
        Spacer(Modifier.size(Space.sm))
        Text(
            text = stringResource(R.string.survey_already_answered),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SubmitArea(
    enabled: Boolean,
    submitting: Boolean,
    failure: String?,
    onSubmit: () -> Unit,
) {
    if (failure != null) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag("survey.error"),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.size(Space.sm))
            Text(
                text = failure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(Space.md))
    }
    Button(
        onClick = onSubmit,
        enabled = enabled,
        shape = ButtonShape.Primary,
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("survey.submit"),
    ) {
        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.size(Space.sm))
        }
        Text(
            text = if (submitting) {
                stringResource(R.string.survey_sending)
            } else {
                stringResource(R.string.survey_submit)
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

// ── questions ───────────────────────────────────────────────────────────────

@Composable
private fun Question(
    question: SurveyQuestion,
    form: SurveyForm,
    onFormChange: (SurveyForm) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag("survey.question.${question.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = question.prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (question.required) {
                Text(
                    text = stringResource(R.string.survey_required),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Space.sm),
                )
            }
        }
        Spacer(Modifier.height(Space.xs))
        when (question.type) {
            SurveyQuestionType.SINGLE -> question.options.forEach { option ->
                ChoiceRow(
                    question = question,
                    option = option,
                    selected = form.singleChoice(question.id) == option.id,
                    multi = false,
                    onClick = { onFormChange(form.choose(question.id, option.id)) },
                )
            }

            SurveyQuestionType.MULTI -> question.options.forEach { option ->
                ChoiceRow(
                    question = question,
                    option = option,
                    selected = option.id in form.chosenOptions(question.id),
                    multi = true,
                    onClick = { onFormChange(form.toggle(question.id, option.id)) },
                )
            }

            SurveyQuestionType.TEXT -> OutlinedTextField(
                value = form.text(question.id),
                onValueChange = { onFormChange(form.write(question.id, it)) },
                placeholder = { Text(stringResource(R.string.survey_answer_hint)) },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().testTag("survey.answer.${question.id}"),
            )
        }
    }
}

/** One option of a single- or multi-select question. */
@Composable
private fun ChoiceRow(
    question: SurveyQuestion,
    option: SurveyOption,
    selected: Boolean,
    multi: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
            .testTag("survey.option.${question.id}.${option.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (multi) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            RadioButton(selected = selected, onClick = onClick)
        }
        Spacer(Modifier.size(Space.sm))
        Text(
            text = option.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
