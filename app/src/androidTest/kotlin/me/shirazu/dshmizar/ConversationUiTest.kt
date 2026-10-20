package me.shirazu.dshmizar

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.shirazu.dshmizar.ui.MizarTheme
import me.shirazu.dshmizar.ui.conversation.ConversationItemView
import me.shirazu.dshmizar.ui.conversation.SubagentPanel
import me.shirazu.dshmizar.ui.design.LocalSlowTypewriter
import me.shirazu.mizar.kit.ConversationItem
import me.shirazu.mizar.kit.SessionRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two pieces most likely to regress silently on a phone.
 *
 * The subagent board is a dialog: the first version of it forgot the `Dialog`
 * wrapper and drew itself inline, which no compiler and no JVM test could see. The
 * typewriter is a clock: its whole point is that the text is *not* there yet, so a
 * test that only waits for the final text would pass just as well with the reveal
 * removed.
 */
@RunWith(AndroidJUnit4::class)
class ConversationUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun text(id: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Test
    fun subagentPanelListsAChildInsideItsDialog() {
        val child = SessionRow(
            sessionId = "child-1",
            updatedAt = System.currentTimeMillis().toDouble(),
            running = false,
            blank = false,
            agentAvailable = true,
            parentSessionId = "parent",
            origin = "subagent",
        )
        val state = UiState(
            selectedSessionId = "parent",
            sessions = listOf(child),
            subagentTitles = mapOf("child-1" to "Explore the test suite"),
        )
        rule.setContent {
            MizarTheme {
                SubagentPanel(state = state, children = listOf(child), onDismiss = {})
            }
        }
        // The panel's own title, from the same catalogue the device uses: a regression
        // to an unwrapped Column would not be a Dialog and could still show this text,
        // but combined with the child row it pins the content that must be visible.
        rule.onNodeWithText(text(R.string.subagents)).assertExists()
        rule.onNodeWithText("Explore the test suite").assertExists()
    }

    @Test
    fun aNewPlainAnswerIsNotWholeImmediately() {
        val answer = "The typewriter must reveal this answer one character at a time."
        rule.setContent {
            MizarTheme {
                CompositionLocalProvider(LocalSlowTypewriter provides true) {
                    ConversationItemView(
                        item = ConversationItem.Message(
                            id = "answer",
                            role = ConversationItem.Role.ASSISTANT,
                            text = answer,
                            atMillis = null,
                        ),
                        isLastPrompt = false,
                        onCopy = {},
                        typing = true,
                    )
                }
            }
        }
        // The slow reveal runs for eight seconds; at this point the whole answer must
        // not be in the tree yet. If typewriting disappears, this is the assertion that
        // fails.
        rule.onAllNodesWithText(answer).assertCountEquals(0)
        rule.waitUntil(timeoutMillis = 12_000) {
            rule.onAllNodesWithText(answer).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(answer).assertExists()
    }
}
