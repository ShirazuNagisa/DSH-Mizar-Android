package me.shirazu.dshmizar.ui.account.surveys

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.shirazu.dshmizar.domain.SurveyCoordinator
import me.shirazu.dshmizar.domain.SurveyForm

/**
 * The directory and the questionnaire on top of it, bound to one coordinator.
 *
 * The Lead mounts this where the settings directory's "About" page links to the
 * questionnaires; [onClose] leaves the whole directory and [onSignIn] opens the
 * account screen from the signed-out state. The form's answers are the only state
 * kept here: they are per-questionnaire, and the coordinator has no business holding
 * a half-typed answer while the list reloads.
 */
@Composable
internal fun SurveysRoute(
    coordinator: SurveyCoordinator,
    onClose: () -> Unit,
    onSignIn: () -> Unit,
) {
    val state by coordinator.state.collectAsState()
    val selected = state.selected

    BackHandler {
        if (selected != null) coordinator.back() else onClose()
    }

    if (selected != null) {
        var form by remember(selected.id) { mutableStateOf(SurveyForm()) }
        SurveyDetailScreen(
            survey = selected,
            form = form,
            submitting = state.submitting,
            rewardDays = if (state.rewardVisible) state.rewardDays else null,
            failure = state.failure,
            onFormChange = { next ->
                form = next
                coordinator.clearFailure()
            },
            onSubmit = { coordinator.submit(selected, form) },
            onBack = { coordinator.back() },
        )
    } else {
        SurveysScreen(
            state = state,
            onClose = onClose,
            onSelect = coordinator::select,
            onRetry = coordinator::load,
            onRefresh = coordinator::load,
            onSignIn = onSignIn,
        )
    }
}
