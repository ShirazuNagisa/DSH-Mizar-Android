package me.shirazu.dshmizar

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.shirazu.dshmizar.ui.MizarTheme
import me.shirazu.dshmizar.ui.conversation.ContextContent
import me.shirazu.dshmizar.ui.conversation.PermissionContent
import me.shirazu.mizar.kit.ContextBreakdown
import me.shirazu.mizar.kit.ContextPressure
import me.shirazu.mizar.kit.PermissionCatalog
import me.shirazu.mizar.kit.PermissionOption
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two popovers and the subagent row, pinned before their parameters are narrowed.
 *
 * These are the leaves that used to take a whole `UiState` to read one field. The
 * assertions are about the words on screen, so they survive the signature change and
 * still fail if the narrowing quietly dropped a figure or a preset.
 */
@RunWith(AndroidJUnit4::class)
class PopoverContentTest {

    @get:Rule
    val rule = createComposeRule()

    private fun text(id: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    private val pressure = ContextPressure(
        window = 200_000,
        pressureTokens = 70_000,
        projectedTokens = 74_500,
        percent = 35,
        breakdown = ContextBreakdown(systemTokens = 12_000, toolsTokens = 3_400, messageTokens = 54_600),
    )

    @Test
    fun contextContentShowsEveryFigureTheHostSent() {
        rule.setContent { MizarTheme { ContextContent(context = pressure) } }

        rule.onNodeWithText(text(R.string.window)).assertExists()
        rule.onNodeWithText("200,000").assertExists()
        rule.onNodeWithText("70,000").assertExists()
        rule.onNodeWithText("74,500").assertExists()
        rule.onNodeWithText("35%").assertExists()
        // The breakdown only appears when the host sent one.
        rule.onNodeWithText(text(R.string.system)).assertExists()
        rule.onNodeWithText("12,000").assertExists()
        rule.onNodeWithText("3,400").assertExists()
        rule.onNodeWithText("54,600").assertExists()
    }

    @Test
    fun contextContentUsesADashForWhatTheHostDidNotSend() {
        rule.setContent { MizarTheme { ContextContent(context = ContextPressure()) } }

        // Four em dashes: window, pressure, projected, percent. Never a zero.
        rule.onAllNodesWithText("—").assertCountEquals(4)
        rule.onAllNodesWithText(text(R.string.system)).assertCountEquals(0)
    }

    @Test
    fun contextContentSaysSoWhenThereIsNoFigureAtAll() {
        rule.setContent { MizarTheme { ContextContent(context = null) } }

        rule.onNodeWithText(text(R.string.no_context_figure)).assertExists()
    }

    @Test
    fun permissionContentOffersThePresetsButNeverCustom() {
        val catalog = PermissionCatalog(
            options = listOf(
                PermissionOption("read-only", "Read only"),
                PermissionOption("auto", "Default"),
                PermissionOption("danger-full-access", "Full access"),
                PermissionOption("custom", "Custom"),
            ),
        )
        rule.setContent {
            MizarTheme {
                PermissionContent(
                    catalog = catalog,
                    inForce = "auto",
                    onChoose = {},
                )
            }
        }

        rule.onNodeWithText("Read only").assertExists()
        rule.onNodeWithText("Default").assertExists()
        rule.onNodeWithText("Full access").assertExists()
        // `custom` is a derived value the phone can never set.
        rule.onAllNodesWithText("Custom").assertCountEquals(0)
        rule.onNodeWithText(text(R.string.permission_note)).assertExists()
    }
}
