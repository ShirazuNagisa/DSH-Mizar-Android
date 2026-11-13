package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.shirazu.dshmizar.R
import me.shirazu.mizar.kit.account.AccountError
import me.shirazu.mizar.kit.account.AccountSurveysApi
import me.shirazu.mizar.kit.account.Survey
import me.shirazu.mizar.kit.account.SurveyAnswer
import me.shirazu.mizar.kit.account.SurveyAnswerValue
import me.shirazu.mizar.kit.account.SurveyQuestion
import me.shirazu.mizar.kit.account.SurveyQuestionType

/**
 * The questionnaires the operator dispatches, and the form that pays for one.
 *
 * The wire shapes and the HTTP calls live in mizarkit (`AccountSurveys.kt`,
 * `AccountSurveysApi`), mirroring `iOS/MizarKit/Sources/MizarKit/AccountSurveys.swift`:
 * a survey arrives with the reward it carries and whether this account already
 * finished it, and the submission is a list of `(questionId, value)` pairs in the
 * order the form drew them.
 *
 * What belongs here is the decision, not the network: the list's phases, the form's
 * validation, and the reward. The reward is paid once per account per questionnaire
 * by the *server*; the client only refuses to offer a second submission and shows the
 * list as done again.
 */

/** The relay's survey error codes, as iOS pins them. */
object SurveyErrorCodes {
    const val ALREADY_DONE = "SURVEY_ALREADY_DONE"
    const val INVALID_ANSWERS = "SURVEY_INVALID_ANSWERS"
    const val NOT_FOUND = "SURVEY_NOT_FOUND"
    const val UNAVAILABLE = "SURVEY_UNAVAILABLE"
}

// MARK: - The form

/**
 * The operator's answers so far, keyed by question id.
 *
 * A value, not a screen: every method returns a new form, which is what lets the
 * sheet remember it and what makes the validation testable without Compose. A
 * question that was not answered is absent; a blank text answer is removed again
 * when written, so "typed once, then deleted" cannot satisfy a required question.
 */
data class SurveyForm(
    val answers: Map<String, SurveyAnswerValue> = emptyMap(),
) {

    /** Choose the one option of a single-select question. */
    fun choose(questionId: String, optionId: String): SurveyForm =
        copy(answers = answers + (questionId to SurveyAnswerValue.Text(optionId)))

    /** Add or remove one option of a multi-select question. */
    fun toggle(questionId: String, optionId: String): SurveyForm {
        val current = (answers[questionId] as? SurveyAnswerValue.Choices)?.value.orEmpty()
        val next = if (optionId in current) current - optionId else current + optionId
        return if (next.isEmpty()) {
            copy(answers = answers - questionId)
        } else {
            copy(answers = answers + (questionId to SurveyAnswerValue.Choices(next)))
        }
    }

    /** Write a free-form answer. */
    fun write(questionId: String, value: String): SurveyForm =
        if (value.isBlank()) {
            copy(answers = answers - questionId)
        } else {
            copy(answers = answers + (questionId to SurveyAnswerValue.Text(value)))
        }

    /** One question's answer, or `null`. */
    fun answer(questionId: String): SurveyAnswerValue? = answers[questionId]

    /** The chosen option id of a single-select question, for the radio buttons. */
    fun singleChoice(questionId: String): String? =
        (answers[questionId] as? SurveyAnswerValue.Text)?.value?.takeIf { it.isNotEmpty() }

    /** The chosen option ids of a multi-select question, for the checkboxes. */
    fun chosenOptions(questionId: String): Set<String> =
        (answers[questionId] as? SurveyAnswerValue.Choices)?.value?.toSet().orEmpty()

    /** The written text of a text question, or an empty string. */
    fun text(questionId: String): String =
        (answers[questionId] as? SurveyAnswerValue.Text)?.value.orEmpty()

    /** Whether every required question has an answer. */
    fun isComplete(survey: Survey): Boolean = survey.questions.all { question ->
        !question.required || hasAnswer(question)
    }

    /**
     * The answers to send, in form order.
     *
     * Optional questions left blank are absent, as the wire expects; an answer whose
     * shape does not match its question (a choice stored as text, or the reverse) is
     * dropped rather than sent as something the server cannot parse. A written
     * answer longer than the question's `maxLength` is cut to it.
     */
    fun answersFor(survey: Survey): List<SurveyAnswer> = survey.questions.mapNotNull { question ->
        sanitize(question)?.let { value -> SurveyAnswer(question.id, value) }
    }

    private fun hasAnswer(question: SurveyQuestion): Boolean = when (val value = answers[question.id]) {
        null -> false
        is SurveyAnswerValue.Text ->
            if (question.type == SurveyQuestionType.MULTI) false else value.value.isNotBlank()
        is SurveyAnswerValue.Choices ->
            question.type == SurveyQuestionType.MULTI && value.value.isNotEmpty()
    }

    private fun sanitize(question: SurveyQuestion): SurveyAnswerValue? =
        when (val value = answers[question.id]) {
            null -> null
            is SurveyAnswerValue.Choices -> {
                if (question.type != SurveyQuestionType.MULTI) {
                    null
                } else {
                    val allowed = question.options.map { it.id }.toSet()
                    val kept = value.value.filter { it in allowed }
                    if (kept.isEmpty()) null else SurveyAnswerValue.Choices(kept)
                }
            }

            is SurveyAnswerValue.Text -> {
                if (question.type == SurveyQuestionType.MULTI) {
                    null
                } else if (value.value.isBlank()) {
                    null
                } else if (question.type == SurveyQuestionType.SINGLE) {
                    // A single choice is one of the options; anything else would be
                    // rejected by the server, so it is not sent.
                    val allowed = question.options.map { it.id }.toSet()
                    if (allowed.isNotEmpty() && value.value !in allowed) {
                        null
                    } else {
                        SurveyAnswerValue.Text(value.value)
                    }
                } else {
                    val limit = question.maxLength
                    SurveyAnswerValue.Text(if (limit != null && limit > 0) value.value.take(limit) else value.value)
                }
            }
        }
}

// MARK: - What the screens draw

/** Where the survey list is in its own small lifecycle. */
enum class SurveyPhase {
    /** Nothing asked yet; the directory has not been opened. */
    IDLE,

    /** The first read is in flight. */
    LOADING,

    /** A list, possibly empty. */
    READY,

    /** The first read failed and there is nothing cached to show. */
    FAILED,

    /** No account session, so there is nothing to submit against. */
    SIGNED_OUT,
}

/** The survey directory and the questionnaire open on top of it. */
data class SurveysUiState(
    /** Whether the directory should be on screen. */
    val open: Boolean = false,
    val phase: SurveyPhase = SurveyPhase.IDLE,
    val surveys: List<Survey> = emptyList(),
    /** The questionnaire open on top of the list, if any. */
    val selectedSurveyId: String? = null,
    /** A submission is in flight. */
    val submitting: Boolean = false,
    /** The days granted, once the relay accepted a submission. */
    val rewardDays: Int? = null,
    /** Which questionnaire the reward belongs to. */
    val rewardSurveyId: String? = null,
    /** The refusal, when the relay (or the form) said no. */
    val failure: String? = null,
    /** The clock reading the list was classified with. */
    val loadedAtMillis: Long = 0L,
) {

    /** The questionnaire open on top of the list, if it still exists. */
    val selected: Survey? get() = surveys.firstOrNull { it.id == selectedSurveyId }

    /** The questionnaires this account can still answer. */
    val openSurveys: List<Survey> get() = surveys.filter { it.isOpen(loadedAtMillis) }

    /** The questionnaires this account already answered. */
    val completedSurveys: List<Survey> get() = surveys.filter { it.completed }

    /** Whether the thank-you is for the questionnaire on screen. */
    val rewardVisible: Boolean get() = rewardDays != null && rewardSurveyId == selectedSurveyId
}

// MARK: - The coordinator

/**
 * The survey directory's own small state machine: load, open, submit, retry.
 *
 * The list is re-read after a submission because the server, not this class, decides
 * "already answered" -- a client that marked it done on its own would show a reward
 * for a submission the relay rejected. A successful submission also calls
 * [onPlanChanged], because the paid days have changed and the profile on screen is
 * now stale.
 *
 * @param api the questionnaire half of the account client.
 * @param scope owns the reads and the submission.
 * @param text the sentences a failure shows.
 * @param token the account session token, or `null` when signed out.
 * @param clock the time, injectable for tests.
 * @param onPlanChanged called after a reward: the Lead re-reads `/me` from it.
 * @param errorCode pulls the relay's code out of a service error, so the four known
 *   refusals get their own sentences.
 */
internal class SurveyCoordinator(
    private val api: AccountSurveysApi,
    private val scope: CoroutineScope,
    private val text: Text,
    private val token: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPlanChanged: () -> Unit = {},
    private val errorCode: (Throwable) -> String? = { (it as? AccountError)?.code },
) {

    private val mutable = MutableStateFlow(SurveysUiState())
    private var loadJob: Job? = null

    /** The directory and the questionnaire open on top of it. */
    val state: StateFlow<SurveysUiState> = mutable.asStateFlow()

    /** Put the directory on screen and read the list. */
    fun open() {
        mutable.update { it.copy(open = true) }
        load()
    }

    /** Close the directory and forget the open questionnaire. */
    fun close() {
        loadJob?.cancel()
        loadJob = null
        mutable.update {
            it.copy(open = false, selectedSurveyId = null, submitting = false, failure = null)
        }
    }

    /** Read the list again; the retry button and the refresh button share it. */
    fun load() {
        val token = token()
        if (token == null) {
            loadJob?.cancel()
            mutable.update {
                it.copy(phase = SurveyPhase.SIGNED_OUT, surveys = emptyList(), selectedSurveyId = null, failure = null)
            }
            return
        }
        loadJob?.cancel()
        mutable.update {
            it.copy(
                phase = if (it.surveys.isEmpty()) SurveyPhase.LOADING else it.phase,
                failure = null,
            )
        }
        loadJob = scope.launch {
            try {
                val surveys = api.surveys(token)
                mutable.update { state ->
                    state.copy(
                        phase = SurveyPhase.READY,
                        surveys = surveys,
                        selectedSurveyId = state.selectedSurveyId
                            ?.takeIf { id -> surveys.any { it.id == id } },
                        loadedAtMillis = clock(),
                        failure = null,
                    )
                }
            } catch (error: Throwable) {
                mutable.update {
                    it.copy(
                        phase = if (it.surveys.isEmpty()) SurveyPhase.FAILED else SurveyPhase.READY,
                        failure = loadMessageFor(error),
                    )
                }
            }
        }
    }

    /** Open one questionnaire on top of the list. */
    fun select(surveyId: String) {
        mutable.update { state ->
            if (state.surveys.none { it.id == surveyId }) return@update state
            if (state.selectedSurveyId == surveyId) return@update state
            state.copy(
                selectedSurveyId = surveyId,
                failure = null,
                rewardDays = null,
                rewardSurveyId = null,
            )
        }
    }

    /** Go back from the questionnaire to the list. */
    fun back() {
        mutable.update {
            it.copy(selectedSurveyId = null, failure = null, rewardDays = null, rewardSurveyId = null)
        }
    }

    /** Forget a refusal so the form can be tried again. */
    fun clearFailure() {
        mutable.update { it.copy(failure = null) }
    }

    /**
     * Send one filled-in questionnaire.
     *
     * Refused locally when the survey is already answered, the form is incomplete or
     * there is no session; otherwise the reward is read from the reply and the list
     * is re-read. A known refusal code re-reads the list too: "already done" means
     * another device answered it, and the directory should say so.
     */
    fun submit(survey: Survey, form: SurveyForm) {
        if (mutable.value.submitting) return
        if (survey.completed) {
            mutable.update { it.copy(failure = text.get(R.string.survey_already_done)) }
            return
        }
        if (!form.isComplete(survey)) {
            mutable.update { it.copy(failure = text.get(R.string.survey_required_missing)) }
            return
        }
        val answers = form.answersFor(survey)
        if (answers.isEmpty() && survey.questions.any { it.required }) {
            mutable.update { it.copy(failure = text.get(R.string.survey_required_missing)) }
            return
        }
        val sessionToken = token()
        if (sessionToken == null) {
            mutable.update {
                it.copy(phase = SurveyPhase.SIGNED_OUT, failure = text.get(R.string.survey_sign_in_first))
            }
            return
        }
        scope.launch {
            mutable.update { it.copy(submitting = true, failure = null) }
            try {
                val submission = api.submitSurvey(sessionToken, survey.id, answers)
                mutable.update { state ->
                    state.copy(
                        submitting = false,
                        rewardDays = submission.rewardDays,
                        rewardSurveyId = survey.id,
                        failure = null,
                        // Show it as answered at once; the read below replaces this
                        // with the server's own answer.
                        surveys = state.surveys.map { row ->
                            if (row.id == survey.id) row.copy(completed = true, completedAt = clock()) else row
                        },
                    )
                }
                onPlanChanged()
                reloadAfterSubmit(sessionToken)
            } catch (error: Throwable) {
                val code = errorCode(error)
                mutable.update {
                    it.copy(submitting = false, failure = messageFor(error, code))
                }
                if (code == SurveyErrorCodes.ALREADY_DONE || code == SurveyErrorCodes.NOT_FOUND) {
                    reloadAfterSubmit(sessionToken)
                }
            }
        }
    }

    /** Re-read the list after a submission; a failure keeps the reward on screen. */
    private suspend fun reloadAfterSubmit(token: String) {
        runCatching { api.surveys(token) }
            .onSuccess { surveys ->
                mutable.update { state ->
                    state.copy(
                        phase = SurveyPhase.READY,
                        surveys = surveys,
                        selectedSurveyId = state.selectedSurveyId
                            ?.takeIf { id -> surveys.any { it.id == id } },
                        loadedAtMillis = clock(),
                    )
                }
            }
    }

    private fun messageFor(error: Throwable, code: String?): String = when (code) {
        SurveyErrorCodes.ALREADY_DONE -> text.get(R.string.survey_already_done)
        SurveyErrorCodes.INVALID_ANSWERS -> text.get(R.string.survey_invalid_answers)
        SurveyErrorCodes.NOT_FOUND -> text.get(R.string.survey_gone)
        SurveyErrorCodes.UNAVAILABLE -> text.get(R.string.survey_unavailable)
        else -> error.message?.takeIf { it.isNotBlank() } ?: text.get(R.string.survey_failed)
    }

    private fun loadMessageFor(error: Throwable): String = when (errorCode(error)) {
        SurveyErrorCodes.UNAVAILABLE -> text.get(R.string.survey_unavailable)
        SurveyErrorCodes.NOT_FOUND -> text.get(R.string.survey_gone)
        else -> error.message?.takeIf { it.isNotBlank() } ?: text.get(R.string.survey_unavailable)
    }
}
