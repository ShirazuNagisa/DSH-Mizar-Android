package me.shirazu.dshmizar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.shirazu.dshmizar.domain.AnnouncementAction
import me.shirazu.dshmizar.domain.AnnouncementBannerState
import me.shirazu.dshmizar.domain.AnnouncementPreferenceStore
import me.shirazu.dshmizar.domain.SurveyForm
import me.shirazu.dshmizar.domain.SurveyPhase
import me.shirazu.dshmizar.domain.SurveysUiState
import me.shirazu.dshmizar.ui.MizarTheme
import me.shirazu.dshmizar.ui.account.surveys.SurveyDetailScreen
import me.shirazu.dshmizar.ui.account.surveys.SurveysScreen
import me.shirazu.dshmizar.ui.shell.AnnouncementBanner
import me.shirazu.dshmizar.ui.shell.AnnouncementBannerHost
import me.shirazu.mizar.kit.account.Announcement
import me.shirazu.mizar.kit.account.AnnouncementButton
import me.shirazu.mizar.kit.account.AnnouncementTarget
import me.shirazu.mizar.kit.account.Survey
import me.shirazu.mizar.kit.account.SurveyOption
import me.shirazu.mizar.kit.account.SurveyQuestion
import me.shirazu.mizar.kit.account.SurveyQuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The announcement banner and the questionnaires, drawn without a service.
 *
 * The state is constructed directly, the same way `AccountUiTest` does it: an
 * instrumentation run clears the app's data, so a test that needed a real sign-in
 * would be both slow and unrepeatable. Strings come from resources, not literals:
 * the emulator runs in Chinese, and so may a phone.
 */
@RunWith(AndroidJUnit4::class)
class AnnouncementSurveysRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int): String = context.getString(id)

    private fun banner(target: String? = "notifications", tone: String = "info") = Announcement(
        id = "announcement-1",
        title = "0.9.9 is on the way",
        body = "The new settings directory and turn cycle are ready to try.",
        symbol = "megaphone",
        tone = tone,
        button = target?.let { AnnouncementButton(label = "Open settings", target = it) },
        createdAt = 1_000L,
        active = true,
    )

    private fun survey(completed: Boolean = false) = Survey(
        id = "survey-1",
        title = "What should the turn cycle do next?",
        summary = "Three short questions.",
        rewardDays = 7,
        questions = listOf(
            SurveyQuestion(
                id = "q1",
                type = SurveyQuestionType.SINGLE,
                prompt = "Pick the update you want first",
                options = listOf(SurveyOption("o1", "Faster syncing"), SurveyOption("o2", "Better Markdown")),
                required = true,
            ),
            SurveyQuestion(
                id = "q2",
                type = SurveyQuestionType.MULTI,
                prompt = "Which platforms do you use?",
                options = listOf(SurveyOption("m1", "Android"), SurveyOption("m2", "iOS")),
            ),
            SurveyQuestion(
                id = "q3",
                type = SurveyQuestionType.TEXT,
                prompt = "Anything else?",
                required = true,
            ),
        ),
        completed = completed,
    )

    // ── the banner ──────────────────────────────────────────────────────────

    @Test
    fun bannerShowsItsTitleBodyAndAction() {
        var action: AnnouncementAction? = null
        compose.setContent {
            MizarTheme {
                AnnouncementBanner(
                    announcement = banner(),
                    onAction = { action = it },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("announcement.banner").assertExists()
        compose.onNodeWithTag("announcement.title").assertExists()
        compose.onNodeWithText("0.9.9 is on the way").assertExists()
        compose.onNodeWithTag("announcement.action").performClick()

        assertEquals(AnnouncementAction.Settings(AnnouncementTarget.NOTIFICATIONS), action)
    }

    @Test
    fun anUnknownTargetDrawsTheTextWithoutTheButton() {
        compose.setContent {
            MizarTheme {
                AnnouncementBanner(
                    announcement = banner(target = "somewhere-this-build-does-not-know"),
                    onAction = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("0.9.9 is on the way").assertExists()
        compose.onNodeWithTag("announcement.action").assertDoesNotExist()
    }

    @Test
    fun theBannersCloseButtonCallsBack() {
        var dismissed = 0
        compose.setContent {
            MizarTheme {
                AnnouncementBanner(
                    announcement = banner(),
                    onAction = {},
                    onDismiss = { dismissed += 1 },
                )
            }
        }

        compose.onNodeWithTag("announcement.close").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun theBannerHostPutsTheCardUnderTheStatusBar() {
        compose.setContent {
            MizarTheme {
                AnnouncementBannerHost(
                    state = AnnouncementBannerState(announcement = banner()),
                    onAction = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("announcement.banner").assertExists()
        compose.onNodeWithTag("announcement.title").assertExists()
    }

    // ── the directory ───────────────────────────────────────────────────────

    @Test
    fun theDirectorySplitsOpenQuestionnairesFromAnsweredOnes() {
        var opened: String? = null
        compose.setContent {
            MizarTheme {
                SurveysScreen(
                    state = SurveysUiState(
                        open = true,
                        phase = SurveyPhase.READY,
                        surveys = listOf(survey(), survey(completed = true).copy(id = "survey-2", title = "Done one")),
                        loadedAtMillis = 1_000L,
                    ),
                    onClose = {},
                    onSelect = { opened = it },
                    onRetry = {},
                    onRefresh = {},
                    onSignIn = {},
                )
            }
        }

        compose.onNodeWithText(text(R.string.survey_section_open)).assertExists()
        compose.onNodeWithText(text(R.string.survey_section_completed)).assertExists()
        compose.onNodeWithTag("surveys.row.survey-1").performClick()
        assertEquals("survey-1", opened)
        compose.onNodeWithText(text(R.string.survey_done)).assertExists()
    }

    @Test
    fun theDirectorySaysWhenThereIsNothingToAnswer() {
        compose.setContent {
            MizarTheme {
                SurveysScreen(
                    state = SurveysUiState(open = true, phase = SurveyPhase.READY),
                    onClose = {},
                    onSelect = {},
                    onRetry = {},
                    onRefresh = {},
                    onSignIn = {},
                )
            }
        }

        compose.onNodeWithTag("surveys.empty").assertExists()
    }

    @Test
    fun theDirectoryAsksTheOperatorToSignInFirst() {
        var signIn = 0
        compose.setContent {
            MizarTheme {
                SurveysScreen(
                    state = SurveysUiState(open = true, phase = SurveyPhase.SIGNED_OUT),
                    onClose = {},
                    onSelect = {},
                    onRetry = {},
                    onRefresh = {},
                    onSignIn = { signIn += 1 },
                )
            }
        }

        compose.onNodeWithTag("surveys.signed_out").assertExists()
        compose.onNodeWithText(text(R.string.account_sign_in)).performClick()
        assertEquals(1, signIn)
    }

    // ── the form ────────────────────────────────────────────────────────────

    @Test
    fun theFormDrawsThreeQuestionTypesAndEnablesSubmitWhenRequiredOnesAreFilled() {
        var form by mutableStateOf(SurveyForm())
        var submitted = 0
        compose.setContent {
            MizarTheme {
                SurveyDetailScreen(
                    survey = survey(),
                    form = form,
                    submitting = false,
                    rewardDays = null,
                    failure = null,
                    onFormChange = { form = it },
                    onSubmit = { submitted += 1 },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag("survey.question.q1").assertExists()
        compose.onNodeWithTag("survey.question.q2").assertExists()
        compose.onNodeWithTag("survey.answer.q3").assertExists()
        compose.onNodeWithTag("survey.submit").performScrollTo().assertIsNotEnabled()

        compose.onNodeWithTag("survey.option.q1.o1").performScrollTo().performClick()
        compose.onNodeWithTag("survey.option.q2.m1").performScrollTo().performClick()
        compose.onNodeWithTag("survey.answer.q3").performScrollTo().performTextInput("More themes")
        assertEquals("o1", form.singleChoice("q1"))

        compose.onNodeWithTag("survey.submit").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, submitted)
    }

    @Test
    fun anAnsweredQuestionnaireShowsTheAlreadyAnsweredSentence() {
        compose.setContent {
            MizarTheme {
                SurveyDetailScreen(
                    survey = survey(completed = true),
                    form = SurveyForm(),
                    submitting = false,
                    rewardDays = null,
                    failure = null,
                    onFormChange = {},
                    onSubmit = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag("survey.completed").performScrollTo().assertExists()
        compose.onNodeWithTag("survey.submit").assertDoesNotExist()
    }

    @Test
    fun aRewardIsShownInPlaceOfTheSubmitButton() {
        compose.setContent {
            MizarTheme {
                SurveyDetailScreen(
                    survey = survey(),
                    form = SurveyForm(),
                    submitting = false,
                    rewardDays = 7,
                    failure = null,
                    onFormChange = {},
                    onSubmit = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag("survey.reward").performScrollTo().assertExists()
        compose.onNodeWithText(context.getString(R.string.survey_thanks, 7)).assertExists()
        compose.onNodeWithTag("survey.submit").assertDoesNotExist()
    }

    // ── persistence ─────────────────────────────────────────────────────────

    @Test
    fun shownAnnouncementIdsSurviveInTheDevicesPreferences() {
        val store = AnnouncementPreferenceStore(context)
        store.clear()
        try {
            store.remember("announcement-a")
            store.remember("announcement-b")

            val reread = AnnouncementPreferenceStore(context).shown()
            assertEquals(listOf("announcement-a", "announcement-b"), reread)
            assertTrue("announcement-a" in reread)
        } finally {
            store.clear()
        }
    }
}
