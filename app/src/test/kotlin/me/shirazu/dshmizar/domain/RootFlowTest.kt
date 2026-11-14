package me.shirazu.dshmizar.domain

import me.shirazu.dshmizar.AccountPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The order of the first screens.
 *
 * The gate exists to keep a signed-out phone off the relay, and the guide exists to
 * explain the computer and the account before that gate. If the order flips, every
 * launch shows the wrong screen for a frame, and no screenshot of the finished screen
 * would catch it.
 */
class RootFlowTest {

    @Test
    fun `the stored session is read before either first-run screen appears`() {
        for (phase in listOf(AccountPhase.IDLE, AccountPhase.LOADING)) {
            assertEquals(
                RootFlow.Stage.LAUNCHING,
                RootFlow.stage(onboardingOpen = false, accountPhase = phase, signedIn = false),
            )
            assertEquals(
                RootFlow.Stage.LAUNCHING,
                RootFlow.stage(onboardingOpen = true, accountPhase = phase, signedIn = false),
            )
        }
    }

    @Test
    fun `the guide comes before the gate`() {
        assertEquals(
            RootFlow.Stage.GUIDE,
            RootFlow.stage(onboardingOpen = true, accountPhase = AccountPhase.READY, signedIn = false),
        )
        assertEquals(
            RootFlow.Stage.GATE,
            RootFlow.stage(onboardingOpen = false, accountPhase = AccountPhase.READY, signedIn = false),
        )
    }

    @Test
    fun `a signed-in phone goes straight to the ordinary phases`() {
        assertEquals(
            RootFlow.Stage.CONTENT,
            RootFlow.stage(onboardingOpen = false, accountPhase = AccountPhase.READY, signedIn = true),
        )
    }

    @Test
    fun `an unreachable account service still counts as signed in when a session exists`() {
        // A phone on a train keeps its account: the gate is about attribution, not
        // about reachability.
        assertEquals(
            RootFlow.Stage.CONTENT,
            RootFlow.stage(onboardingOpen = false, accountPhase = AccountPhase.UNREACHABLE, signedIn = true),
        )
        assertNotEquals(
            RootFlow.Stage.CONTENT,
            RootFlow.stage(onboardingOpen = false, accountPhase = AccountPhase.UNREACHABLE, signedIn = false),
        )
    }
}
