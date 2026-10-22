package me.shirazu.dshmizar

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.shirazu.dshmizar.ui.account.AccountRow
import me.shirazu.dshmizar.ui.account.DeleteDialog
import me.shirazu.mizar.kit.account.AccountSession
import me.shirazu.mizar.kit.account.AccountUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The account surface, without a service or a network.
 *
 * The state is constructed directly, the same way `ConversationUiTest` does it:
 * an instrumentation run clears the app's data, so a test that needed a real
 * sign-in would be both slow and unrepeatable.
 */
@RunWith(AndroidJUnit4::class)
class AccountUiTest {

    @get:Rule
    val compose = createComposeRule()

    /** Strings, not literals: the emulator runs in Chinese, and so may a phone. */
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int): String = context.getString(id)

    private fun session(plan: String = "free", username: String = "shirazu"): AccountSession =
        AccountSession(
            token = "mza_abcdefghijklmnopqrst",
            profile = AccountUser(id = "user-1", username = username, email = "a@b.com", plan = plan),
        )

    @Test
    fun signedOutRowOffersSignIn() {
        compose.setContent {
            AccountRow(AccountUiState(phase = AccountPhase.READY), onClick = {})
        }
        compose.onNodeWithText(text(R.string.account_sign_in)).assertExists()
    }

    @Test
    fun signedInRowShowsTheUsernameAndPlan() {
        compose.setContent {
            AccountRow(
                AccountUiState(phase = AccountPhase.READY, session = session(plan = "paid")),
                onClick = {},
            )
        }
        compose.onNodeWithText("shirazu").assertExists()
        compose.onNodeWithText(text(R.string.account_paid)).assertExists()
    }

    @Test
    fun anUnreachableAccountStillShowsWhoItIs() {
        compose.setContent {
            AccountRow(
                AccountUiState(phase = AccountPhase.UNREACHABLE, session = session()),
                onClick = {},
            )
        }
        compose.onNodeWithText("shirazu").assertExists()
        compose.onNodeWithText(text(R.string.account_unreachable)).assertExists()
    }

    @Test
    fun theRowIsClickable() {
        var taps = 0
        compose.setContent {
            AccountRow(AccountUiState(), onClick = { taps += 1 })
        }
        compose.onNodeWithText(text(R.string.account_sign_in)).performClick()
        assertEquals(1, taps)
    }

    @Test
    fun deletingNeedsTheWordTyped() {
        var confirmed = 0
        compose.setContent {
            DeleteDialog(busy = false, onCancel = {}, onConfirm = { confirmed += 1 })
        }
        compose.onNodeWithText(text(R.string.account_delete_action)).assertIsNotEnabled()
        compose.onNodeWithTag("account-delete-confirm").performTextInput("delete")
        compose.onNodeWithText(text(R.string.account_delete_action)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.account_delete_action)).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun paidOrLanUnlocksAttachmentsAndTurnDetail() {
        val base = UiState(isLocalNetwork = false)
        assertFalse(base.copy(account = AccountUiState()).advancedFeaturesUnlocked)
        assertTrue(base.copy(isLocalNetwork = true).advancedFeaturesUnlocked)
        assertTrue(base.copy(account = AccountUiState(session = session(plan = "paid"))).advancedFeaturesUnlocked)
        assertFalse(base.copy(account = AccountUiState(session = session(plan = "free"))).advancedFeaturesUnlocked)
    }
}
