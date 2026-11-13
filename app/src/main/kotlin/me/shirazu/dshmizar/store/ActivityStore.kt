package me.shirazu.dshmizar.store

import android.content.Context
import me.shirazu.dshmizar.domain.AgentEngine
import me.shirazu.dshmizar.domain.AgentSurface
import me.shirazu.dshmizar.domain.Preferences
import me.shirazu.dshmizar.domain.RunningBaseline

/**
 * What the operator has already looked at.
 *
 * Plain preferences, not the encrypted store: none of this is a credential, and making
 * the app unable to remember which conversation was open because the keystore is
 * unavailable would be a poor trade. Keyed by host id, because "last opened" is a fact
 * about one machine's session list, not about the phone.
 */
class ActivityStore(context: Context) : Preferences, RunningBaseline {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The session the operator was last reading on this host. */
    fun lastSession(hostId: String): String? = prefs.getString(key(hostId, "last"), null)

    /** Remember the session open now, or forget it when nothing is open. */
    fun rememberLastSession(hostId: String, sessionId: String?) {
        prefs.edit().apply {
            if (sessionId == null) remove(key(hostId, "last")) else putString(key(hostId, "last"), sessionId)
        }.apply()
    }

    /**
     * When each session was last on screen, as epoch millis.
     *
     * Only compared against the host's own `updatedAt`, so a phone with a skewed clock
     * mislabels at worst one dot; it never invents activity.
     */
    fun seenAt(hostId: String): Map<String, Long> {
        val prefix = key(hostId, "seen.")
        return prefs.all
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { it.key.removePrefix(prefix) }
            .mapValues { (_, value) -> (value as? Long) ?: 0L }
    }

    /** Mark a session as read now. */
    fun markSeen(hostId: String, sessionId: String, at: Long) {
        prefs.edit().putLong(key(hostId, "seen.$sessionId"), at).apply()
    }

    /** How the operator wants the screen lit: "system" (the default), "light" or "dark". */
    override fun themeMode(): String = prefs.getString(THEME, "system") ?: "system"

    override fun setThemeMode(mode: String) {
        prefs.edit().putString(THEME, mode).apply()
    }

    /**
     * Sessions this phone last saw running.
     *
     * Persisted because the confirmation of a finished turn must survive the process:
     * a phone that was frozen mid-turn restored an empty in-memory baseline, and the
     * completion that happened while it was away was then never announced.
     */
    override fun runningSession(hostId: String, sessionId: String): Boolean =
        prefs.getBoolean(key(hostId, "run.$sessionId"), false)

    /** Whether any session on this host is still recorded as running. */
    fun hasRunningSessions(hostId: String): Boolean = prefs.all.keys.any { it.startsWith("$hostId/run.") }

    override fun setRunningSession(hostId: String, sessionId: String, running: Boolean) {
        prefs.edit().apply {
            if (running) putBoolean(key(hostId, "run.$sessionId"), true)
            else remove(key(hostId, "run.$sessionId"))
        }.apply()
    }

    /** Whether finished-turn notices are silenced between 23:00 and 08:00. */
    override fun quietHours(): Boolean = prefs.getBoolean(QUIET, false)
    override fun setQuietHours(on: Boolean) { prefs.edit().putBoolean(QUIET, on).apply() }

    // Four independent notice categories, as in iOS. A category that is off is
    // dropped where it arrives -- not hidden by a system setting the app cannot see.
    override fun notifyCompleted(): Boolean = prefs.getBoolean(NOTIFY_COMPLETED, true)
    override fun setNotifyCompleted(on: Boolean) { prefs.edit().putBoolean(NOTIFY_COMPLETED, on).apply() }

    override fun notifyInterrupted(): Boolean = prefs.getBoolean(NOTIFY_INTERRUPTED, true)
    override fun setNotifyInterrupted(on: Boolean) { prefs.edit().putBoolean(NOTIFY_INTERRUPTED, on).apply() }

    override fun notifyQuestions(): Boolean = prefs.getBoolean(NOTIFY_QUESTIONS, true)
    override fun setNotifyQuestions(on: Boolean) { prefs.edit().putBoolean(NOTIFY_QUESTIONS, on).apply() }

    override fun notifyApprovals(): Boolean = prefs.getBoolean(NOTIFY_APPROVALS, true)
    override fun setNotifyApprovals(on: Boolean) { prefs.edit().putBoolean(NOTIFY_APPROVALS, on).apply() }

    /** Whether the operator wants the app to use haptics at all. */
    override fun hapticsEnabled(): Boolean = prefs.getBoolean(HAPTICS, true)
    override fun setHapticsEnabled(on: Boolean) { prefs.edit().putBoolean(HAPTICS, on).apply() }

    /** Slower typewriter, for watching the reveal on a real screen. */
    override fun slowTypewriter(): Boolean = prefs.getBoolean(SLOW_TYPEWRITER, false)
    override fun setSlowTypewriter(on: Boolean) { prefs.edit().putBoolean(SLOW_TYPEWRITER, on).apply() }

    // ── the first-run guide ─────────────────────────────────────────────────
    //
    // Three facts, not one: whether it was finished, whether it was started (a
    // guide interrupted halfway should not count as seen), and for which build.
    // A new build replays it once, keeping the account and the settings.

    fun onboardingDone(): Boolean = prefs.getBoolean(ONBOARDING_DONE, false)
    fun setOnboardingDone(on: Boolean) { prefs.edit().putBoolean(ONBOARDING_DONE, on).apply() }

    fun onboardingStarted(): Boolean = prefs.getBoolean(ONBOARDING_STARTED, false)
    fun setOnboardingStarted(on: Boolean) { prefs.edit().putBoolean(ONBOARDING_STARTED, on).apply() }

    fun onboardingVersion(): String? = prefs.getString(ONBOARDING_VERSION, null)
    fun setOnboardingVersion(version: String) { prefs.edit().putString(ONBOARDING_VERSION, version).apply() }

    // ── which agent this phone is scoped to ─────────────────────────────────
    //
    // The engine travels in the hello frame and the surface is the phone's own
    // preference; both are remembered so a restart comes back to the same agent.

    fun agentEngine(): String? = prefs.getString(AGENT_ENGINE, null)
    fun setAgentEngine(engine: AgentEngine?) {
        prefs.edit().apply {
            if (engine == null) remove(AGENT_ENGINE) else putString(AGENT_ENGINE, engine.wireName)
        }.apply()
    }

    fun agentSurface(): String? = prefs.getString(AGENT_SURFACE, null)
    fun setAgentSurface(surface: AgentSurface?) {
        prefs.edit().apply {
            if (surface == null) remove(AGENT_SURFACE) else putString(AGENT_SURFACE, surface.wireName)
        }.apply()
    }

    /**
     * Whether a message typed while a turn is running is sent straight to the host as
     * a steer. Off by default: holding it until the turn settles is what the iOS app
     * does, and it is the less destructive of the two.
     */
    override fun steerByDefault(): Boolean = prefs.getBoolean(STEER_BY_DEFAULT, false)
    override fun setSteerByDefault(on: Boolean) { prefs.edit().putBoolean(STEER_BY_DEFAULT, on).apply() }

    /**
     * Whether the operator wants the LAN tried before the relay.
     *
     * Off by default, like iOS 0.9.4: the relay is the path that works from
     * anywhere, and a scan on every resume costs a little battery and a few
     * probes. Turning it on is what unlocks the faster poll and the two
     * transfer features that do not go through the relay.
     */
    override fun localNetworkFirst(): Boolean = prefs.getBoolean(LAN_FIRST, false)
    fun setLocalNetworkFirst(on: Boolean) { prefs.edit().putBoolean(LAN_FIRST, on).apply() }

    private fun key(hostId: String, suffix: String) = "$hostId/$suffix"


    private companion object {
        const val FILE = "mizar-activity"
        const val THEME = "theme"
        const val QUIET = "quiet"
        const val NOTIFY_COMPLETED = "notify-completed"
        const val NOTIFY_INTERRUPTED = "notify-interrupted"
        const val NOTIFY_QUESTIONS = "notify-questions"
        const val NOTIFY_APPROVALS = "notify-approvals"
        const val HAPTICS = "haptics"
        const val SLOW_TYPEWRITER = "slow-typewriter"
        const val STEER_BY_DEFAULT = "steer-by-default"
        const val LAN_FIRST = "lan-first"
        const val ONBOARDING_DONE = "onboarding-done"
        const val ONBOARDING_STARTED = "onboarding-started"
        const val ONBOARDING_VERSION = "onboarding-version"
        const val AGENT_ENGINE = "agent-engine"
        const val AGENT_SURFACE = "agent-surface"
    }
}
