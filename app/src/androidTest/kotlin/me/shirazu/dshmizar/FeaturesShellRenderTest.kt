package me.shirazu.dshmizar

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import me.shirazu.dshmizar.domain.SampleAgents
import me.shirazu.dshmizar.domain.SampleUsage
import me.shirazu.dshmizar.domain.UsageOverview
import me.shirazu.dshmizar.ui.MizarTheme
import me.shirazu.dshmizar.ui.ThemeMode
import me.shirazu.dshmizar.ui.onboarding.OnboardingAccount
import me.shirazu.dshmizar.ui.onboarding.OnboardingActions
import me.shirazu.dshmizar.ui.onboarding.OnboardingRefresh
import me.shirazu.dshmizar.ui.onboarding.OnboardingRefreshState
import me.shirazu.dshmizar.ui.onboarding.OnboardingScreen
import me.shirazu.dshmizar.ui.onboarding.OnboardingState
import me.shirazu.dshmizar.ui.onboarding.OnboardingStep
import me.shirazu.dshmizar.ui.settings.SettingsDialog
import me.shirazu.dshmizar.ui.settings.SettingsFileCache
import me.shirazu.dshmizar.ui.settings.SettingsLocalAgent
import me.shirazu.dshmizar.ui.settings.SettingsTopic
import me.shirazu.dshmizar.ui.shell.AgentCapsule
import me.shirazu.dshmizar.ui.shell.AgentSwitcher
import me.shirazu.dshmizar.ui.shell.HomeUsage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders the four surfaces this round adds and writes them to PNGs, so the first
 * look at them does not have to wait for the Lead's wiring.
 *
 * This is deliberately **not** a golden-image test: visual-qa owns the baselines
 * (`ConversationSnapshotTest` and the suite around it), and a second owner of the
 * same goldens is how two diffs start arguing. What is asserted here is only that
 * each surface composes, lays out and draws something that is not a blank frame --
 * the PNGs are the deliverable, pulled from the device and looked at.
 *
 * Fixed density and a 360 x 740 dp frame, so a phone and the emulator save the same
 * picture:
 *
 * ```
 * adb -s emulator-5558 shell am instrument -w \
 *   -e class me.shirazu.dshmizar.FeaturesShellRenderTest \
 *   me.shirazu.dshmizar.test/androidx.test.runner.AndroidJUnitRunner
 * adb -s emulator-5558 pull /sdcard/Android/data/me.shirazu.dshmizar/files/features-shell .
 * ```
 */
@RunWith(AndroidJUnit4::class)
class FeaturesShellRenderTest {

    @get:Rule
    val rule = createComposeRule()

    /** Every page of the guide, with the state that page is interesting in. */
    @Test
    fun onboardingPagesRender() {
        val account = OnboardingAccount(signedIn = true, displayName = "shirazu", isPaid = true)
        val pages = listOf(
            OnboardingStep.WELCOME to OnboardingState(step = OnboardingStep.WELCOME),
            OnboardingStep.DESKTOP to OnboardingState(step = OnboardingStep.DESKTOP),
            OnboardingStep.ACCOUNT to OnboardingState(step = OnboardingStep.ACCOUNT, account = account),
            OnboardingStep.NETWORK to OnboardingState(step = OnboardingStep.NETWORK, localNetworkFirst = true),
            OnboardingStep.AGENT to OnboardingState(
                step = OnboardingStep.AGENT,
                wantsLocalAgent = true,
                localAgentAvailable = true,
                localAgentConfigured = true,
            ),
            OnboardingStep.ALERTS to OnboardingState(
                step = OnboardingStep.ALERTS,
                liveUpdatesSupported = true,
            ),
            OnboardingStep.PLAN to OnboardingState(
                step = OnboardingStep.PLAN,
                account = account,
                paidUntilLabel = "2027-01-01",
                balanceLabel = "¥ 42.00",
            ),
            OnboardingStep.REFRESH to OnboardingState(
                step = OnboardingStep.REFRESH,
                refresh = OnboardingRefresh(OnboardingRefreshState.DONE, 37),
            ),
            OnboardingStep.SUMMARY to OnboardingState(step = OnboardingStep.SUMMARY, account = account),
        )
        var current by mutableStateOf(pages.first().second)
        rule.setContent {
            Frame {
                OnboardingScreen(state = current, actions = OnboardingActions())
            }
        }
        for ((name, state) in pages) {
            rule.runOnUiThread { current = state }
            rule.waitForIdle()
            snapshot("onboarding-${name.name.lowercase()}")
        }
    }

    /** The settings directory, then each shelf behind it. */
    @Test
    fun settingsDirectoryAndPagesRender() {
        val state = UiState()
        var topic by mutableStateOf<SettingsTopic?>(null)
        rule.setContent {
            Frame {
                MizarTheme(mode = ThemeMode.DARK) {
                    SettingsDialog(
                        state = state,
                        onDismiss = {},
                        onOpenAccount = {},
                        onOpenBalance = {},
                        onOpenRedeem = {},
                        onForget = {},
                        onAddHost = {},
                        onTheme = {},
                        onQuietHours = {},
                        onNotifyCompleted = {},
                        onNotifyInterrupted = {},
                        onNotifyQuestions = {},
                        onNotifyApprovals = {},
                        onHaptics = {},
                        onSlowTypewriter = {},
                        onLocalNetworkFirst = {},
                        onExportLogs = { _ -> },
                        onReplayOnboarding = {},
                        onReloadSessionData = {},
                        onSteerByDefault = {},
                        fileCache = SettingsFileCache(files = 12, bytes = 3_400_000),
                        onClearFileCache = {},
                        localAgent = SettingsLocalAgent(
                            available = true,
                            configured = true,
                            storedBytes = 1_200_000,
                        ),
                        onOpenLocalAgentModels = {},
                        onClearLocalAgentData = {},
                        initialTopic = topic,
                    )
                }
            }
        }
        rule.waitForIdle()
        snapshot("settings-directory")
        for (shelf in SettingsTopic.entries) {
            rule.runOnUiThread { topic = shelf }
            rule.waitForIdle()
            snapshot("settings-${shelf.name.lowercase()}")
        }
    }

    /** The usage overview: with data, while loading, and with nothing at all. */
    @Test
    fun homeUsageRenders() {
        var overview by mutableStateOf<UsageOverview?>(UsageOverview.merged(SampleUsage.readings()))
        var loading by mutableStateOf(false)
        rule.setContent {
            Frame {
                MizarTheme(mode = ThemeMode.DARK) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        HomeUsage(
                            overview = overview,
                            loading = loading,
                            updatedLabel = "Updated 2 min ago",
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        snapshot("home-usage")
        rule.runOnUiThread {
            overview = null
            loading = true
        }
        rule.waitForIdle()
        snapshot("home-usage-loading")
        // No data and no read: the block must draw nothing at all rather than
        // zeroes. An empty frame is therefore the correct frame here, and the PNG is
        // kept as the evidence for it.
        rule.runOnUiThread { loading = false }
        rule.waitForIdle()
        snapshot("home-usage-hidden", requireContent = false)
    }

    /** The agent capsule and its picker. */
    @Test
    fun agentSwitcherRenders() {
        // 0 the picker, 1 the DSH capsule, 2 the Claude capsule, 3 no capsule.
        var mode by mutableStateOf(0)
        rule.setContent {
            Frame {
                MizarTheme(mode = ThemeMode.DARK) {
                    Box(Modifier.padding(12.dp)) {
                        when (mode) {
                            0 -> Box(Modifier.width(280.dp)) {
                                AgentSwitcher(
                                    catalog = SampleAgents.threeAgents(),
                                    onPick = {},
                                    onDismiss = {},
                                )
                            }
                            1 -> AgentCapsule(catalog = SampleAgents.threeAgents(), onClick = {})
                            2 -> AgentCapsule(catalog = SampleAgents.claudeCli(), onClick = {})
                            // A single-agent computer draws no capsule at all: there is
                            // nothing to switch, so an empty frame is the correct frame.
                            else -> AgentCapsule(catalog = SampleAgents.singleAgent(), onClick = {})
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        snapshot("agent-switcher")
        for ((state, name) in listOf(
            1 to "agent-capsule-dsh",
            2 to "agent-capsule-claude",
            3 to "agent-capsule-single",
        )) {
            rule.runOnUiThread { mode = state }
            rule.waitForIdle()
            // The single-agent case draws nothing, which the blank check tolerates
            // only because the frame's own background is already there.
            snapshot(name, requireContent = state != 3)
        }
    }

    // MARK: - Helpers

    /** A fixed phone-sized frame, drawn under a fixed density, on the app's dark ground. */
    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 1f)) {
            Box(
                Modifier
                    .width(360.dp)
                    .height(740.dp)
                    .background(Ground),
            ) {
                content()
            }
        }
    }

    private companion object {
        /** The dark theme's background, so a light-mode default cannot shine through
         *  the margin around a surface that is drawn on the console's ground. */
        val Ground = androidx.compose.ui.graphics.Color(0xFF0C111B)
    }

    /**
     * Save the current frame as a PNG.
     *
     * A screenshot of a blank frame is the one failure that looks like success in a
     * folder of images, so blankness is checked rather than trusted -- except where
     * an empty frame is the expected answer.
     */
    private fun snapshot(name: String, requireContent: Boolean = true) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("$name captured nothing", bitmap.width > 0 && bitmap.height > 0)
        if (requireContent) {
            assertTrue("$name drew a single colour", hasMoreThanOneColour(bitmap))
        }
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "features-shell",
        )
        directory.mkdirs()
        val file = File(directory, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("features-shell: ${bitmap.width}x${bitmap.height} → ${file.absolutePath}")
    }

    private fun hasMoreThanOneColour(bitmap: Bitmap): Boolean {
        val first = bitmap.getPixel(0, 0)
        for (x in 0 until bitmap.width step 7) {
            for (y in 0 until bitmap.height step 7) {
                if (bitmap.getPixel(x, y) != first) return true
            }
        }
        return false
    }
}
