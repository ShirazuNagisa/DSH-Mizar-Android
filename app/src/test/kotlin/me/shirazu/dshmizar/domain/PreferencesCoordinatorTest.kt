package me.shirazu.dshmizar.domain

import kotlinx.coroutines.flow.MutableStateFlow
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings switches, as the coordinator keeps them.
 *
 * Two things can go wrong here and neither shows up as a crash. A switch can reach
 * the screen and not the store -- the announcer and the push path read the store, so
 * notifications would ignore a toggle that looks on. Or the first state of a launch
 * can read the wrong key -- a copy-paste between nine near-identical fields -- and
 * every switch would come back wrong until it was touched again. Both are pinned
 * below.
 */
class PreferencesCoordinatorTest {

    private class FakePreferences(
        var theme: String = "system",
        var quiet: Boolean = false,
        var lanFirst: Boolean = false,
        var completed: Boolean = true,
        var interrupted: Boolean = true,
        var questions: Boolean = true,
        var approvals: Boolean = true,
        var haptics: Boolean = true,
        var slow: Boolean = false,
        var steer: Boolean = false,
    ) : Preferences {
        override fun themeMode(): String = theme
        override fun quietHours(): Boolean = quiet
        override fun localNetworkFirst(): Boolean = lanFirst
        override fun notifyCompleted(): Boolean = completed
        override fun notifyInterrupted(): Boolean = interrupted
        override fun notifyQuestions(): Boolean = questions
        override fun notifyApprovals(): Boolean = approvals
        override fun hapticsEnabled(): Boolean = haptics
        override fun slowTypewriter(): Boolean = slow
        override fun steerByDefault(): Boolean = steer

        override fun setThemeMode(mode: String) { theme = mode }
        override fun setQuietHours(on: Boolean) { quiet = on }
        override fun setNotifyCompleted(on: Boolean) { completed = on }
        override fun setNotifyInterrupted(on: Boolean) { interrupted = on }
        override fun setNotifyQuestions(on: Boolean) { questions = on }
        override fun setNotifyApprovals(on: Boolean) { approvals = on }
        override fun setHapticsEnabled(on: Boolean) { haptics = on }
        override fun setSlowTypewriter(on: Boolean) { slow = on }
        override fun setSteerByDefault(on: Boolean) { steer = on }
    }

    @Test
    fun `a fresh launch reads every stored switch into its own field`() {
        // Every value differs from its default, so a field wired to the wrong key
        // shows a value that belongs to another switch rather than a plausible one.
        val state = storedPreferences(
            FakePreferences(
                theme = "dark",
                quiet = true,
                lanFirst = true,
                completed = false,
                interrupted = false,
                questions = false,
                approvals = false,
                haptics = false,
                slow = true,
                steer = true,
            ),
        )

        assertEquals(ThemeMode.DARK, state.themeMode)
        assertTrue("quietHours", state.quietHours)
        assertTrue("localNetworkFirst", state.localNetworkFirst)
        assertFalse("notifyCompleted", state.notifyCompleted)
        assertFalse("notifyInterrupted", state.notifyInterrupted)
        assertFalse("notifyQuestions", state.notifyQuestions)
        assertFalse("notifyApprovals", state.notifyApprovals)
        assertFalse("hapticsEnabled", state.hapticsEnabled)
        assertTrue("slowTypewriter", state.slowTypewriter)
        assertTrue("steerByDefault", state.steerByDefault)
    }

    @Test
    fun `an unknown stored theme follows the system`() {
        assertEquals(ThemeMode.SYSTEM, storedPreferences(FakePreferences(theme = "sepia")).themeMode)
        assertEquals(ThemeMode.SYSTEM, "system".toThemeMode())
        assertEquals(ThemeMode.LIGHT, "light".toThemeMode())
        assertEquals(ThemeMode.DARK, "DARK".toThemeMode())
    }

    @Test
    fun `every switch is stored and shown at once`() {
        val prefs = FakePreferences()
        val state = MutableStateFlow(UiState())
        val coordinator = PreferencesCoordinator(FlowStateStore(state), prefs)

        val switches = listOf(
            Switch("theme", { coordinator.setTheme(ThemeMode.LIGHT) }, { prefs.theme }, { state.value.themeMode.name.lowercase() }, "light"),
            Switch("quietHours", { coordinator.setQuietHours(true) }, { prefs.quiet.toString() }, { state.value.quietHours.toString() }, "true"),
            Switch("notifyCompleted", { coordinator.setNotifyCompleted(false) }, { prefs.completed.toString() }, { state.value.notifyCompleted.toString() }, "false"),
            Switch("notifyInterrupted", { coordinator.setNotifyInterrupted(false) }, { prefs.interrupted.toString() }, { state.value.notifyInterrupted.toString() }, "false"),
            Switch("notifyQuestions", { coordinator.setNotifyQuestions(false) }, { prefs.questions.toString() }, { state.value.notifyQuestions.toString() }, "false"),
            Switch("notifyApprovals", { coordinator.setNotifyApprovals(false) }, { prefs.approvals.toString() }, { state.value.notifyApprovals.toString() }, "false"),
            Switch("haptics", { coordinator.setHapticsEnabled(false) }, { prefs.haptics.toString() }, { state.value.hapticsEnabled.toString() }, "false"),
            Switch("slowTypewriter", { coordinator.setSlowTypewriter(true) }, { prefs.slow.toString() }, { state.value.slowTypewriter.toString() }, "true"),
            Switch("steerByDefault", { coordinator.setSteerByDefault(true) }, { prefs.steer.toString() }, { state.value.steerByDefault.toString() }, "true"),
        )

        for (switch in switches) {
            switch.flip()
            assertEquals("${switch.name}: stored", switch.expected, switch.stored())
            assertEquals("${switch.name}: shown", switch.expected, switch.shown())
        }
    }

    @Test
    fun `the theme is stored in the spelling the store already uses`() {
        val prefs = FakePreferences()
        val coordinator = PreferencesCoordinator(FlowStateStore(MutableStateFlow(UiState())), prefs)

        coordinator.setTheme(ThemeMode.DARK)

        // Not `DARK`: a stored copy that changed case would be read back as SYSTEM by
        // every build that came before this one.
        assertEquals("dark", prefs.theme)
    }

    private class Switch(
        val name: String,
        val flip: () -> Unit,
        val stored: () -> String,
        val shown: () -> String,
        val expected: String,
    )
}
