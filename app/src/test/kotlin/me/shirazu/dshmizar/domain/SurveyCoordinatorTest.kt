package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.shirazu.dshmizar.R
import me.shirazu.mizar.kit.account.AccountSurveysApi
import me.shirazu.mizar.kit.account.AccountUser
import me.shirazu.mizar.kit.account.Survey
import me.shirazu.mizar.kit.account.SurveyAnswer
import me.shirazu.mizar.kit.account.SurveyAnswerValue
import me.shirazu.mizar.kit.account.SurveyOption
import me.shirazu.mizar.kit.account.SurveyQuestion
import me.shirazu.mizar.kit.account.SurveyQuestionType
import me.shirazu.mizar.kit.account.SurveySubmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The questionnaire domain, without a service.
 *
 * Two things are easy to get subtly wrong here and impossible to notice by looking at
 * the screen: the wire's answer union (a single choice is a string, a multi-select an
 * array) and the reward path (the server, not the client, decides "already done", so
 * the list must be re-read after a submission). Both are pinned below.
 */
class SurveyCoordinatorTest {

    private companion object {
        const val NOW = 1_000_000L
        const val TOKEN = "mza_abcdefghijklmnopqrst"
    }

    private class FakeError(val code: String, message: String) : Exception(message)

    private class MarkerText : Text {
        override fun get(id: Int): String = "res:$id"
        override fun get(id: Int, vararg args: Any): String = "res:$id:" + args.joinToString(",")
    }

    private class FakeApi : AccountSurveysApi {
        var available: List<Survey> = emptyList()
        var loadFailure: Throwable? = null
        var submitFailure: Throwable? = null
        var submitResult = SurveySubmission(user = AccountUser(id = "u1", username = "shirazu"), rewardDays = 7)

        var surveysCalls = 0
        var submitCalls = 0
        var lastToken: String? = null
        var lastSurveyId: String? = null
        var lastAnswers: List<SurveyAnswer> = emptyList()

        override suspend fun surveys(token: String): List<Survey> {
            surveysCalls += 1
            lastToken = token
            loadFailure?.let { throw it }
            return available
        }

        override suspend fun submitSurvey(
            token: String,
            surveyId: String,
            answers: List<SurveyAnswer>,
        ): SurveySubmission {
            submitCalls += 1
            lastToken = token
            lastSurveyId = surveyId
            lastAnswers = answers
            submitFailure?.let { throw it }
            // The relay answers "already paid" on the next read; the fake does too.
            available = available.map { row ->
                if (row.id == surveyId) row.copy(completed = true) else row
            }
            return submitResult
        }
    }

    private class Fixture(token: String? = TOKEN) {
        val api = FakeApi()
        var planChanges = 0
        val coordinator = SurveyCoordinator(
            api = api,
            // Unconfined, and every fake answers without suspending: a call has
            // finished by the time it returns, which is what the assertions assume.
            scope = CoroutineScope(Dispatchers.Unconfined),
            text = MarkerText(),
            token = { token },
            clock = { NOW },
            onPlanChanged = { planChanges += 1 },
            errorCode = { (it as? FakeError)?.code },
        )

        val state get() = coordinator.state.value
    }

    private fun survey(
        id: String = "survey-1",
        completed: Boolean = false,
        active: Boolean = true,
        expiresAt: Long? = null,
        rewardDays: Int = 7,
    ) = Survey(
        id = id,
        title = "Questionnaire $id",
        summary = "A short one.",
        rewardDays = rewardDays,
        questions = listOf(
            SurveyQuestion(
                id = "q1",
                type = SurveyQuestionType.SINGLE,
                prompt = "Pick one",
                options = listOf(SurveyOption("o1", "One"), SurveyOption("o2", "Two")),
                required = true,
            ),
            SurveyQuestion(
                id = "q2",
                type = SurveyQuestionType.MULTI,
                prompt = "Pick some",
                options = listOf(SurveyOption("m1", "M one"), SurveyOption("m2", "M two")),
                required = false,
            ),
            SurveyQuestion(
                id = "q3",
                type = SurveyQuestionType.TEXT,
                prompt = "Say something",
                required = true,
                maxLength = 5,
            ),
        ),
        active = active,
        expiresAt = expiresAt,
        completed = completed,
    )

    private fun completeForm() = SurveyForm()
        .choose("q1", "o2")
        .toggle("q2", "m1")
        .toggle("q2", "m2")
        .write("q3", "hello")

    // ── loading ─────────────────────────────────────────────────────────────

    @Test
    fun `opening the directory loads it and splits answered from open`() {
        val fixture = Fixture()
        fixture.api.available = listOf(
            survey("open"),
            survey("done", completed = true),
            survey("expired", expiresAt = NOW - 1),
        )

        fixture.coordinator.open()

        assertEquals(SurveyPhase.READY, fixture.state.phase)
        assertTrue(fixture.state.open)
        assertEquals(listOf("open"), fixture.state.openSurveys.map { it.id })
        assertEquals(listOf("done"), fixture.state.completedSurveys.map { it.id })
        assertEquals(TOKEN, fixture.api.lastToken)
    }

    @Test
    fun `signed out shows the sign-in sentence instead of asking the service`() {
        val fixture = Fixture(token = null)
        fixture.coordinator.open()

        assertEquals(SurveyPhase.SIGNED_OUT, fixture.state.phase)
        assertEquals(0, fixture.api.surveysCalls)
    }

    @Test
    fun `a failed first read offers its message and can be retried`() {
        val fixture = Fixture()
        fixture.api.loadFailure = FakeError("", "The account service is unreachable.")
        fixture.coordinator.open()

        assertEquals(SurveyPhase.FAILED, fixture.state.phase)
        assertEquals("The account service is unreachable.", fixture.state.failure)

        fixture.api.loadFailure = null
        fixture.api.available = listOf(survey())
        fixture.coordinator.load()

        assertEquals(SurveyPhase.READY, fixture.state.phase)
        assertNull(fixture.state.failure)
        assertEquals(2, fixture.api.surveysCalls)
    }

    @Test
    fun `selecting and going back swaps the questionnaire and clears a refusal`() {
        val fixture = Fixture()
        fixture.api.available = listOf(survey())
        fixture.coordinator.open()

        fixture.coordinator.select("survey-1")
        assertEquals("survey-1", fixture.state.selectedSurveyId)
        assertNull(fixture.state.failure)

        fixture.coordinator.back()
        assertNull(fixture.state.selectedSurveyId)
    }

    // ── submitting ──────────────────────────────────────────────────────────

    @Test
    fun `submitting sends the answers in form order with the wire's union shapes`() {
        val fixture = Fixture()
        fixture.api.available = listOf(survey())
        fixture.coordinator.open()
        fixture.coordinator.select("survey-1")

        fixture.coordinator.submit(fixture.state.selected!!, completeForm())

        assertEquals(1, fixture.api.submitCalls)
        assertEquals("survey-1", fixture.api.lastSurveyId)
        assertEquals(listOf("q1", "q2", "q3"), fixture.api.lastAnswers.map { it.questionId })
        assertEquals(SurveyAnswerValue.Text("o2"), fixture.api.lastAnswers[0].value)
        assertEquals(SurveyAnswerValue.Choices(listOf("m1", "m2")), fixture.api.lastAnswers[1].value)
        assertEquals(SurveyAnswerValue.Text("hello"), fixture.api.lastAnswers[2].value)

        // The reward comes back, the plan is refreshed, and the list is re-read
        // because the server is the one that says "answered".
        assertEquals(7, fixture.state.rewardDays)
        assertTrue(fixture.state.rewardVisible)
        assertEquals(1, fixture.planChanges)
        assertEquals(2, fixture.api.surveysCalls)
        assertFalse(fixture.state.submitting)
    }

    @Test
    fun `a required question left blank is refused without calling the service`() {
        val fixture = Fixture()
        fixture.api.available = listOf(survey())
        fixture.coordinator.open()
        fixture.coordinator.select("survey-1")

        fixture.coordinator.submit(fixture.state.selected!!, SurveyForm().write("q3", "hi"))

        assertEquals(0, fixture.api.submitCalls)
        assertEquals("res:${R.string.survey_required_missing}", fixture.state.failure)
        assertEquals(0, fixture.planChanges)
    }

    @Test
    fun `an already answered questionnaire is never submitted again`() {
        val fixture = Fixture()
        fixture.api.available = listOf(survey(completed = true))
        fixture.coordinator.open()
        fixture.coordinator.select("survey-1")

        fixture.coordinator.submit(fixture.state.selected!!, completeForm())

        assertEquals(0, fixture.api.submitCalls)
        assertEquals("res:${R.string.survey_already_done}", fixture.state.failure)
    }

    @Test
    fun `optional questions left blank are absent from the submission`() {
        val fixture = Fixture()
        fixture.api.available = listOf(survey())
        fixture.coordinator.open()
        fixture.coordinator.select("survey-1")

        fixture.coordinator.submit(
            fixture.state.selected!!,
            SurveyForm().choose("q1", "o1").write("q3", "ok"),
        )

        assertEquals(listOf("q1", "q3"), fixture.api.lastAnswers.map { it.questionId })
    }

    @Test
    fun `the relay's codes become their own sentences, and an answered survey re-reads the list`() {
        val fixture = Fixture()
        fixture.api.available = listOf(survey())
        fixture.coordinator.open()
        fixture.coordinator.select("survey-1")
        fixture.api.submitFailure = FakeError(
            SurveyErrorCodes.ALREADY_DONE,
            "SURVEY_ALREADY_DONE: answered on another device",
        )

        fixture.coordinator.submit(fixture.state.selected!!, completeForm())

        assertEquals("res:${R.string.survey_already_done}", fixture.state.failure)
        assertNull(fixture.state.rewardDays)
        assertFalse(fixture.state.submitting)
        assertEquals(2, fixture.api.surveysCalls)
    }

    @Test
    fun `a failed submission can be sent again`() {
        val fixture = Fixture()
        fixture.api.available = listOf(survey())
        fixture.coordinator.open()
        fixture.coordinator.select("survey-1")
        fixture.api.submitFailure = FakeError("", "network down")

        fixture.coordinator.submit(fixture.state.selected!!, completeForm())
        assertEquals("network down", fixture.state.failure)
        assertEquals(0, fixture.planChanges)

        fixture.api.submitFailure = null
        fixture.coordinator.submit(fixture.state.selected!!, completeForm())

        assertEquals(2, fixture.api.submitCalls)
        assertNull(fixture.state.failure)
        assertEquals(7, fixture.state.rewardDays)
        assertEquals(1, fixture.planChanges)
    }

    @Test
    fun `a completed row keeps a survey open for reading but not for submitting`() {
        val fixture = Fixture()
        fixture.api.available = listOf(survey())
        fixture.coordinator.open()
        fixture.coordinator.select("survey-1")
        fixture.coordinator.submit(fixture.state.selected!!, completeForm())

        // Still on screen: the reward has to be readable after the list reloads.
        assertEquals("survey-1", fixture.state.selectedSurveyId)
        assertEquals(7, fixture.state.rewardDays)
        assertTrue(fixture.state.selected!!.completed)

        // One account answers one questionnaire once; the second press is refused.
        fixture.coordinator.submit(fixture.state.selected!!, completeForm())
        assertEquals(1, fixture.api.submitCalls)
        assertEquals("res:${R.string.survey_already_done}", fixture.state.failure)
    }

    // ── the form ────────────────────────────────────────────────────────────

    @Test
    fun `the form's answers survive the round trip and toggle correctly`() {
        val form = SurveyForm()
            .choose("q1", "o1")
            .toggle("q2", "m1")
            .toggle("q2", "m2")
            .write("q3", "hi")

        assertEquals("o1", form.singleChoice("q1"))
        assertEquals(setOf("m1", "m2"), form.chosenOptions("q2"))
        assertEquals("hi", form.text("q3"))
        assertTrue(form.isComplete(survey()))

        val toggled = form.toggle("q2", "m1")
        assertEquals(setOf("m2"), toggled.chosenOptions("q2"))
        assertTrue(toggled.isComplete(survey()))
    }

    @Test
    fun `writing then deleting a required answer empties it again`() {
        val form = SurveyForm().choose("q1", "o1").write("q3", "hi").write("q3", "")
        assertNull(form.answer("q3"))
        assertFalse(form.isComplete(survey()))
    }

    @Test
    fun `a written answer longer than the question allows is cut`() {
        val answers = SurveyForm().choose("q1", "o1").write("q3", "abcdefgh").answersFor(survey())
        assertEquals(SurveyAnswerValue.Text("abcde"), answers.first { it.questionId == "q3" }.value)
    }

    @Test
    fun `an answer that does not match its question type is dropped`() {
        val form = SurveyForm(
            answers = mapOf(
                "q1" to SurveyAnswerValue.Choices(listOf("o1")),
                "q2" to SurveyAnswerValue.Text("m1"),
                "q3" to SurveyAnswerValue.Text("ok"),
            ),
        )
        val answers = form.answersFor(survey())

        assertEquals(listOf("q3"), answers.map { it.questionId })
    }

    @Test
    fun `an unknown wire question type reads as text`() {
        assertEquals(SurveyQuestionType.TEXT, SurveyQuestionType.of("slider"))
        assertEquals(SurveyQuestionType.SINGLE, SurveyQuestionType.of("single"))
        assertEquals(SurveyQuestionType.MULTI, SurveyQuestionType.of("multi"))
    }

    @Test
    fun `a survey that expired or was switched off cannot be opened`() {
        assertFalse(survey(expiresAt = NOW - 1).isOpen(NOW))
        assertFalse(survey(active = false).isOpen(NOW))
        assertFalse(survey(completed = true).isOpen(NOW))
        assertTrue(survey().isOpen(NOW))
    }
}
