package me.shirazu.dshmizar

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.shirazu.dshmizar.ui.MizarTheme
import me.shirazu.dshmizar.ui.shell.WideSidePlane
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The wide side plane's three pages.
 *
 * The plane itself is ordinary Compose: no connection, no view model, just a UiState.
 * That makes it cheap to pin the part a phone cannot show without a tablet.
 */
@RunWith(AndroidJUnit4::class)
class WideSidePlaneTest {

    @get:Rule
    val rule = createComposeRule()

    private fun text(id: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Test
    fun thePlaneOffersTurnsSubagentsAndPreview() {
        var titlesAsked = false
        rule.setContent {
            MizarTheme {
                WideSidePlane(
                    state = UiState(selectedSessionId = "parent"),
                    preview = me.shirazu.dshmizar.FilePreviewUiState(),
                    onScreen = emptySet(),
                    onPick = {},
                    onLoadSubagentTitles = { titlesAsked = true },
                )
            }
        }
        // The page content may repeat a label ("轮次" heads the turns page too), so
        // these assert that each switcher segment exists rather than that the string
        // is unique on screen.
        rule.onAllNodesWithText(text(R.string.side_page_turns)).onFirst().assertExists()
        rule.onAllNodesWithText(text(R.string.side_page_subagents)).onFirst().assertExists()
        rule.onAllNodesWithText(text(R.string.side_page_preview)).onFirst().assertExists()

        // The preview page is a real pane now, not a placeholder: with no file chosen
        // it says how to choose one.
        rule.onAllNodesWithText(text(R.string.side_page_preview)).onFirst().performClick()
        rule.onNodeWithText(text(R.string.side_page_preview_empty)).assertExists()

        rule.onAllNodesWithText(text(R.string.side_page_subagents)).onFirst().performClick()
        rule.onNodeWithText(text(R.string.subagents_empty)).assertExists()
    }
}
