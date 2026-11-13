package me.shirazu.dshmizar.ui.onboarding

/**
 * The first-run guide's state machine, with no Compose and no Android in it.
 *
 * The nine pages are the iOS `OnboardingView`'s, in its order, because the order
 * is the argument: welcome, install the desktop app first (the phone is a remote,
 * and a guide walked before the desktop exists ends with nothing to drive), the
 * account, how the connection travels, where the agent runs, what reaches the
 * phone, the plan, the first refresh, and the summary.
 *
 * Two things are deliberately *not* the iOS app's:
 *
 *  - **There is no sign-in gate.** The Android app works without an account, so the
 *    account page is an offer with a "later" answer, not a door. `canAdvance` is
 *    true on every page, always.
 *  - **The plan page sells nothing in an app store.** Android has no Play Billing
 *    (a decided non-goal); paid time arrives as a redemption code against the
 *    signed-in account, and the balance is the computer's. The page explains that
 *    and opens the two existing screens.
 *
 * The version marker is what makes an upgrade re-walk the guide once without
 * losing anything: the guide's version is written when it finishes, and a phone
 * that comes from an older build (or from none) walks this one. The account and
 * every setting are kept -- the guide is a walk-through, not a reset.
 *
 * Kept here rather than in the screen so the migration and the page arithmetic have
 * JVM unit tests: neither is something a screenshot can prove.
 */

/** One page of the guide, in the order they are read. */
enum class OnboardingStep {
    WELCOME,
    DESKTOP,
    ACCOUNT,
    NETWORK,
    AGENT,
    ALERTS,
    PLAN,
    REFRESH,
    SUMMARY,
    ;

    /** Pages already read, for the progress line. */
    val number: Int get() = ordinal + 1

    companion object {
        /** Nine pages, and the count is read from the enum so they cannot drift. */
        val count: Int = entries.size

        /** The page after this one, or `null` on the summary. */
        fun next(step: OnboardingStep): OnboardingStep? = entries.getOrNull(step.ordinal + 1)

        /** The page before this one, or `null` on the welcome. */
        fun previous(step: OnboardingStep): OnboardingStep? = entries.getOrNull(step.ordinal - 1)
    }
}

/** The account, as the guide draws it. Optional throughout. */
data class OnboardingAccount(
    val signedIn: Boolean = false,
    /** The name or address under the avatar; the guide only prints it. */
    val displayName: String? = null,
    val isPaid: Boolean = false,
) {
    /** The plan shown on the account page and in the summary. */
    val plan: PlanLabel get() = if (isPaid) PlanLabel.PAID else PlanLabel.FREE
}

/** Which plan the account is on, as a key the screen localises. */
enum class PlanLabel { FREE, PAID }

/** What the first refresh is doing. */
enum class OnboardingRefreshState { IDLE, RUNNING, DONE, FAILED }

/** The refresh page's own state: the guide's one side effect with a visible outcome. */
data class OnboardingRefresh(
    val state: OnboardingRefreshState = OnboardingRefreshState.IDLE,
    /** Sessions read, when the reload reached the computer. */
    val sessions: Int = 0,
)

/**
 * Everything the guide draws.
 *
 * All of it is passed in: the screen owns no state and no ViewModel, so the Lead
 * can drive it from `UiState` and visual-qa can render any page with a literal.
 * The callbacks live in [OnboardingActions] beside this.
 */
data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    /**
     * The phone has been here before (it walked an older guide). Changes the copy
     * on the account page -- an upgrade is not asked to sign in again -- and
     * nothing else: the settings it already has are what the summary will show.
     */
    val isUpdate: Boolean = false,
    val account: OnboardingAccount = OnboardingAccount(),
    /** The Android settings value; the guide's network page is the Settings switch. */
    val localNetworkFirst: Boolean = false,
    /** Where the sessions should run. */
    val wantsLocalAgent: Boolean = false,
    /** Whether this build carries the on-device agent at all. */
    val localAgentAvailable: Boolean = false,
    /** Whether an on-device model provider has a key configured. */
    val localAgentConfigured: Boolean = false,
    /** The four notification kinds, as one switch: on means none is suppressed. */
    val notificationsEnabled: Boolean = true,
    /** Android 16's Live Updates surface, offered only where it exists. */
    val liveUpdatesSupported: Boolean = false,
    val liveUpdatesEnabled: Boolean = true,
    /** The signed-in account's paid-until line, when there is one. */
    val paidUntilLabel: String? = null,
    /** The computer's wallet line, when the balance screen has been read. */
    val balanceLabel: String? = null,
    val refresh: OnboardingRefresh = OnboardingRefresh(),
) {
    /** How many pages the progress line draws. */
    val count: Int get() = OnboardingStep.count

    /** The 1-based page number for the "3 / 9" line. */
    val number: Int get() = step.number

    /** A back control exists on every page but the first. */
    val canGoBack: Boolean get() = step != OnboardingStep.WELCOME

    /**
     * Whether the footer's button works.
     *
     * Always true: the account is optional on Android, so no page can refuse to be
     * left. Kept as a property rather than a constant so the screen has one line to
     * read and a future gate has one place to go.
     */
    val canAdvance: Boolean get() = true

    /** The footer's label: the last page starts the app, every other page continues. */
    val isLastStep: Boolean get() = step == OnboardingStep.SUMMARY

    /** The page after this one, clamped on the summary. */
    fun advance(): OnboardingState =
        OnboardingStep.next(step)?.let { copy(step = it) } ?: this

    /** The page before this one, clamped on the welcome. */
    fun back(): OnboardingState =
        OnboardingStep.previous(step)?.let { copy(step = it) } ?: this

    /** Jump to one page; used by tests and by a resume that remembers the page. */
    fun at(step: OnboardingStep): OnboardingState = copy(step = step)
}

/**
 * What the guide's controls report back.
 *
 * One value object rather than a lambda per page: the screen hands the same object
 * to every page, and the Lead wires it once. Every member has a no-op default, so a
 * page can be rendered in a test with `OnboardingActions(onNext = {})` and nothing
 * else.
 */
data class OnboardingActions(
    /** The footer's Continue / Start, and the only way forward. */
    val onNext: () -> Unit = {},
    val onBack: () -> Unit = {},
    /** Written when the summary is left: the guide is done. */
    val onFinish: () -> Unit = {},
    /** The desktop page's two buttons. */
    val onOpenDesktopDownload: () -> Unit = {},
    val onCopyDesktopLink: () -> Unit = {},
    /** The account page: the existing account screen, and its register half. */
    val onOpenAccount: () -> Unit = {},
    val onCreateAccount: () -> Unit = {},
    /** The network page: the same switch the Settings screen owns. */
    val onLocalNetworkFirst: (Boolean) -> Unit = {},
    /** The agent page. */
    val onWantsLocalAgent: (Boolean) -> Unit = {},
    val onConfigureLocalAgent: () -> Unit = {},
    /** The alerts page. */
    val onNotificationsEnabled: (Boolean) -> Unit = {},
    val onLiveUpdatesEnabled: (Boolean) -> Unit = {},
    val onOpenSystemNotificationSettings: () -> Unit = {},
    /** The plan page: the two existing screens, no store purchase. */
    val onRedeemCode: () -> Unit = {},
    val onOpenBalance: () -> Unit = {},
    /** The refresh page's retry, when the host did not answer. */
    val onRetryRefresh: () -> Unit = {},
    /**
     * The refresh page was reached: run the reload.
     *
     * Called once when the page appears, so the screen does not have to know that
     * the read is a side effect rather than a state. Its outcome comes back as
     * [OnboardingState.refresh].
     */
    val onRefreshShown: () -> Unit = {},
)
