package me.shirazu.dshmizar.domain

import me.shirazu.dshmizar.AccountPhase

/**
 * Which screen owns the window, in the order iOS decides it.
 *
 * The account is a hard gate: every byte the app moves crosses a relay the project pays
 * for, and an account is what makes those bytes attributable. iOS answers that by never
 * letting a signed-out phone reach the shell, and this mirrors it:
 *
 *  1. while the stored session is still being read, show the launch screen -- the gate
 *     must not flash on a phone that turns out to be signed in;
 *  2. the first-run guide comes before the gate, because it is what explains the
 *     computer and the account to somebody holding a phone with nothing on it;
 *  3. after the guide, a signed-out phone stays on the gate until it signs in;
 *  4. only then does the ordinary phase (pairing, connecting, shell) own the window.
 *
 * Kept pure and here rather than inline in the composable so the ordering has unit
 * tests: a gate that is evaluated in the wrong order is a gate that flickers on every
 * launch, and a screenshot cannot prove which branch ran.
 */
object RootFlow {

    /** Which composable owns the window. */
    enum class Stage { LAUNCHING, GUIDE, GATE, CONTENT }

    /**
     * @param onboardingOpen the guide has not finished (or is replaying).
     * @param accountPhase the account store's own state; `IDLE`/`LOADING` mean the
     *   stored session has not been read yet.
     * @param signedIn whether an account session exists.
     */
    fun stage(
        onboardingOpen: Boolean,
        accountPhase: AccountPhase,
        signedIn: Boolean,
    ): Stage = when {
        accountPhase == AccountPhase.IDLE || accountPhase == AccountPhase.LOADING -> Stage.LAUNCHING
        onboardingOpen -> Stage.GUIDE
        !signedIn -> Stage.GATE
        else -> Stage.CONTENT
    }
}
