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

    /** Whether finished-turn notices are silenced between 23:00 and 08:00. */
    fun quietHours(): Boolean = prefs.getBoolean(QUIET, false)
    fun setQuietHours(on: Boolean) { prefs.edit().putBoolean(QUIET, on).apply() }

    private fun key(hostId: String, suffix: String) = "$hostId/$suffix"


    private companion object {
        const val FILE = "mizar-activity"
        const val THEME = "theme"
        const val QUIET = "quiet"
    }
}
