package me.shirazu.dshmizar.store

import android.content.Context

/**
 * What the operator has already looked at.
 *
 * Plain preferences, not the encrypted store: none of this is a credential, and making
 * the app unable to remember which conversation was open because the keystore is
 * unavailable would be a poor trade. Keyed by host id, because "last opened" is a fact
 * about one machine's session list, not about the phone.
 */
class ActivityStore(context: Context) {

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
    fun themeMode(): String = prefs.getString(THEME, "system") ?: "system"

    fun setThemeMode(mode: String) {
        prefs.edit().putString(THEME, mode).apply()
    }

    /**
     * Sessions this phone last saw running.
     *
     * Persisted because the confirmation of a finished turn must survive the process:
     * a phone that was frozen mid-turn restored an empty in-memory baseline, and the
     * completion that happened while it was away was then never announced.
     */
    fun runningSession(hostId: String, sessionId: String): Boolean =
        prefs.getBoolean(key(hostId, "run.$sessionId"), false)

    /** Whether any session on this host is still recorded as running. */
    fun hasRunningSessions(hostId: String): Boolean = prefs.all.keys.any { it.startsWith("$hostId/run.") }

    fun setRunningSession(hostId: String, sessionId: String, running: Boolean) {
        prefs.edit().apply {
            if (running) putBoolean(key(hostId, "run.$sessionId"), true)
            else remove(key(hostId, "run.$sessionId"))
        }.apply()
    }

    /** Whether finished-turn notices are silenced between 23:00 and 08:00. */
    fun quietHours(): Boolean = prefs.getBoolean(QUIET, false)
    fun setQuietHours(on: Boolean) { prefs.edit().putBoolean(QUIET, on).apply() }

    // Four independent notice categories, as in iOS. A category that is off is
    // dropped where it arrives -- not hidden by a system setting the app cannot see.
    fun notifyCompleted(): Boolean = prefs.getBoolean(NOTIFY_COMPLETED, true)
    fun setNotifyCompleted(on: Boolean) { prefs.edit().putBoolean(NOTIFY_COMPLETED, on).apply() }

    fun notifyInterrupted(): Boolean = prefs.getBoolean(NOTIFY_INTERRUPTED, true)
    fun setNotifyInterrupted(on: Boolean) { prefs.edit().putBoolean(NOTIFY_INTERRUPTED, on).apply() }

    fun notifyQuestions(): Boolean = prefs.getBoolean(NOTIFY_QUESTIONS, true)
    fun setNotifyQuestions(on: Boolean) { prefs.edit().putBoolean(NOTIFY_QUESTIONS, on).apply() }

    fun notifyApprovals(): Boolean = prefs.getBoolean(NOTIFY_APPROVALS, true)
    fun setNotifyApprovals(on: Boolean) { prefs.edit().putBoolean(NOTIFY_APPROVALS, on).apply() }

    /**
     * Whether the operator wants the LAN tried before the relay.
     *
     * Off by default, like iOS 0.9.4: the relay is the path that works from
     * anywhere, and a scan on every resume costs a little battery and a few
     * probes. Turning it on is what unlocks the faster poll and the two
     * transfer features that do not go through the relay.
     */
    fun localNetworkFirst(): Boolean = prefs.getBoolean(LAN_FIRST, false)
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
        const val LAN_FIRST = "lan-first"
    }
}
