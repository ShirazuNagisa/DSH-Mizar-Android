package me.shirazu.dshmizar.ui.conversation

import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A markdown link is a tap target, not just underlined text.
 *
 * The protocol parser carries the destination on the span (it is never drawn), and
 * the message view attaches a Compose [androidx.compose.ui.text.LinkAnnotation.Url]
 * to that run. This test proves the wiring end to end: a tap asks the system to open
 * the target, and a relative target is deliberately inert.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownLinkTest {

    @get:Rule
    val rule = createComposeRule()

    @Before
    fun setUp() {
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    private fun render(source: String) {
        rule.setContent {
            MaterialTheme {
                MarkdownMessage(
                    text = source,
                    color = MaterialTheme.colorScheme.onSurface,
                    codeColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    linkColor = MaterialTheme.colorScheme.primary,
                    onCopy = {},
                )
            }
        }
    }

    @Test
    fun tappingALinkStartsAViewIntentForItsTarget() {
        render("[docs](https://example.com/x)")
        rule.onNodeWithText("docs").performClick()
        intended(hasAction(Intent.ACTION_VIEW))
    }

    @Test
    fun aRelativeTargetIsStyledButNotLinkable() {
        render("[local](notes.md)")
        rule.onNodeWithText("local").performClick()
        // Nothing was started; the assertion is that no view intent exists at all.
        var started = false
        try {
            intended(hasAction(Intent.ACTION_VIEW))
            started = true
        } catch (_: AssertionError) {
            // expected: the styled label swallowed no tap
        }
        assertFalse("a relative markdown target must not open an intent", started)
    }
}
