package me.shirazu.dshmizar.ui.onboarding

import me.shirazu.dshmizar.domain.OnboardingVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide's page arithmetic and its version migration.
 *
 * Both are things a screenshot cannot prove: the order of nine pages is easy to
 * look right and be wrong at the eighth, and the version comparison decides whether
 * an upgrade is asked to walk the guide again or not asked at all.
 */
class OnboardingModelTest {

    @Test
    fun `there are nine pages, and the order is the argument`() {
        assertEquals(9, OnboardingStep.count)
        assertEquals(
            listOf(
                "WELCOME",
                "DESKTOP",
                "ACCOUNT",
                "NETWORK",
                "AGENT",
                "ALERTS",
                "PLAN",
                "REFRESH",
                "SUMMARY",
            ),
            OnboardingStep.entries.map { it.name },
        )
    }

    @Test
    fun `advancing walks forward and stops at the summary`() {
        var state = OnboardingState()
        assertEquals(OnboardingStep.WELCOME, state.step)
        assertEquals(1, state.number)
        assertEquals(9, state.count)
        for (expected in OnboardingStep.entries.drop(1)) {
            state = state.advance()
            assertEquals(expected, state.step)
            assertEquals(expected.ordinal + 1, state.number)
        }
        assertEquals(OnboardingStep.SUMMARY, state.step)
        assertEquals(state, state.advance())
        assertTrue(state.isLastStep)
    }

    @Test
    fun `going back stops at the welcome`() {
        var state = OnboardingState()
        assertEquals(state, state.back())
        assertFalse(state.canGoBack)
        state = state.advance()
        assertTrue(state.canGoBack)
        assertEquals(OnboardingStep.WELCOME, state.back().step)
    }

    @Test
    fun `no page gates the exit, because the account is optional on Android`() {
        var state = OnboardingState()
        for (step in OnboardingStep.entries) {
            state = state.at(step)
            assertTrue("page $step refused to be left", state.canAdvance)
        }
    }

    @Test
    fun `a stored guide version decides whether the guide is walked again`() {
        assertFalse(OnboardingVersion.isComplete(null))
        assertFalse(OnboardingVersion.isComplete(""))
        // An older guide: this phone walks the new one, keeping its settings.
        assertFalse(OnboardingVersion.isComplete("0.9.9"))
        assertTrue(OnboardingVersion.isComplete(OnboardingVersion.REQUIRED))
        // Numeric, not lexicographic: 0.10.10 is newer than 0.9.9 ...
        assertTrue(OnboardingVersion.isComplete("0.10.10"))
        // ... and a string comparison would have said the opposite.
        assertTrue("0.10.10" < "0.9.9")
    }

    @Test
    fun `an upgrade re-walks the guide, a fresh install walks it first`() {
        assertFalse(OnboardingVersion.isUpdate(done = false, stored = null))
        assertTrue(OnboardingVersion.isUpdate(done = true, stored = null))
        assertTrue(OnboardingVersion.isUpdate(done = true, stored = "0.10.6"))
        assertFalse(OnboardingVersion.isUpdate(done = true, stored = OnboardingVersion.REQUIRED))
        // Skipping the guide leaves it not done: a phone that dismissed it walks it
        // again rather than living without the walk-through.
        assertFalse(OnboardingVersion.isUpdate(done = false, stored = "0.9.9"))
    }

    @Test
    fun `versions compare segment by segment, and a malformed segment cannot crash`() {
        assertTrue(OnboardingVersion.compareVersions("0.10.7", "0.9.9") > 0)
        assertTrue(OnboardingVersion.compareVersions("0.9.9", "0.10.7") < 0)
        assertEquals(0, OnboardingVersion.compareVersions("0.10.7", "0.10.7"))
        assertEquals(0, OnboardingVersion.compareVersions("0.10", "0.10.0"))
        assertTrue(OnboardingVersion.compareVersions("1", "0.99.99") > 0)
        // A half-written value on disk must not crash the first screen of the app.
        assertEquals(0, OnboardingVersion.compareVersions("dev", "dev"))
        assertTrue(OnboardingVersion.compareVersions("a.1", "a.0") > 0)
    }

    @Test
    fun `the desktop release page is one constant, so the copy and the link cannot disagree`() {
        assertTrue(OnboardingVersion.DESKTOP_RELEASE_URL.startsWith("https://github.com/"))
        assertTrue(OnboardingVersion.DESKTOP_RELEASE_URL.endsWith("/releases"))
        assertTrue(OnboardingVersion.PROJECT_URL.startsWith("https://dsh-mizar."))
    }

    @Test
    fun `the guide's own state is data, and every callback has a no-op default`() {
        val state = OnboardingState(
            step = OnboardingStep.REFRESH,
            account = OnboardingAccount(signedIn = true, displayName = "shirazu", isPaid = true),
            refresh = OnboardingRefresh(OnboardingRefreshState.DONE, 42),
        )
        assertEquals(PlanLabel.PAID, state.account.plan)
        assertEquals(42, state.refresh.sessions)
        assertEquals(OnboardingStep.REFRESH, state.step)
        // Rendering a page in a test needs no Lead wiring at all.
        val actions = OnboardingActions()
        actions.onNext()
        actions.onFinish()
        actions.onLocalNetworkFirst(true)
        assertNull(state.paidUntilLabel)
    }
}
