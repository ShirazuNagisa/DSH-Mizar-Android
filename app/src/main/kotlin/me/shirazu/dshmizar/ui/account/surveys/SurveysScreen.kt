package me.shirazu.dshmizar.ui.account.surveys

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.domain.SurveyPhase
import me.shirazu.dshmizar.domain.SurveysUiState
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.mizar.kit.account.Survey

/**
 * The questionnaire directory: what is open, what it pays, and what this account has
 * already answered.
 *
 * Two sections rather than one list: a questionnaire that pays and can be filled in
 * is a different thing from a receipt, and the directory's whole job is to say which
 * is which. Unfinished first -- that is the one with an action in it.
 *
 * Stateless on purpose: the coordinator hands over a [SurveysUiState] and the screen
 * calls back. The detail form is [SurveyDetailScreen]; [SurveysRoute] binds both.
 */
@Composable
fun SurveysScreen(
    state: SurveysUiState,
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onSignIn: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize().testTag("surveys.list")) {
        // The screen is edge-to-edge; without the inset the title and close button
        // sit under the status bar and the close button becomes untappable.
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.survey_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                if (state.phase != SurveyPhase.SIGNED_OUT) {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.survey_close),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            ) {
                when {
                    state.phase == SurveyPhase.SIGNED_OUT -> SignedOutBody(onSignIn)
                    state.phase == SurveyPhase.LOADING && state.surveys.isEmpty() -> LoadingBody()
                    state.phase == SurveyPhase.FAILED && state.surveys.isEmpty() ->
                        FailedBody(state.failure, onRetry)
                    state.surveys.isEmpty() -> EmptyBody()
                    else -> DirectoryBody(state, onSelect)
                }
            }
        }
    }
}

@Composable
private fun DirectoryBody(state: SurveysUiState, onSelect: (String) -> Unit) {
    val open = state.openSurveys
    val done = state.completedSurveys
    if (open.isNotEmpty()) {
        SectionLabel(stringResource(R.string.survey_section_open))
        open.forEachIndexed { index, survey ->
            if (index > 0) RowDivider()
            SurveyRow(survey = survey, completed = false, onClick = { onSelect(survey.id) })
        }
    }
    if (done.isNotEmpty()) {
        if (open.isNotEmpty()) Spacer(Modifier.height(Space.lg))
        SectionLabel(stringResource(R.string.survey_section_completed))
        done.forEachIndexed { index, survey ->
            if (index > 0) RowDivider()
            SurveyRow(survey = survey, completed = true, onClick = { onSelect(survey.id) })
        }
    }
    Spacer(Modifier.height(Space.lg))
    Text(
        text = stringResource(R.string.survey_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("surveys.footer"),
    )
    state.failure?.let { message ->
        Spacer(Modifier.height(Space.md))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("surveys.error"),
        )
    }
}

/** One row: what it asks, what it pays, and whether it is done. */
@Composable
private fun SurveyRow(survey: Survey, completed: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag("surveys.row.${survey.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (completed) Icons.Filled.CheckCircle else Icons.Outlined.Checklist,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (completed) MizarColors.Waiting else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(Space.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = survey.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (completed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (survey.summary.isNotBlank()) {
                Text(
                    text = survey.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(Space.sm))
        Text(
            text = if (completed) {
                stringResource(R.string.survey_done)
            } else {
                stringResource(R.string.survey_days, survey.rewardDays)
            },
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.md, bottom = Space.xs),
    )
}

@Composable
private fun LoadingBody() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.survey_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("surveys.loading"),
        )
    }
}

@Composable
private fun EmptyBody() {
    Text(
        text = stringResource(R.string.survey_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = Space.lg).testTag("surveys.empty"),
    )
}

@Composable
private fun FailedBody(failure: String?, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Space.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MizarColors.Waiting,
            )
            Spacer(Modifier.width(Space.sm))
            Text(
                text = failure ?: stringResource(R.string.survey_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("surveys.error"),
            )
        }
        Spacer(Modifier.height(Space.sm))
        TextButton(onClick = onRetry, modifier = Modifier.padding(start = Space.xs)) {
            Text(stringResource(R.string.retry))
        }
    }
}

@Composable
private fun SignedOutBody(onSignIn: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.survey_sign_in_first),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("surveys.signed_out"),
            )
            Spacer(Modifier.height(Space.md))
            TextButton(onClick = onSignIn) {
                Text(stringResource(R.string.account_sign_in))
            }
        }
    }
}
