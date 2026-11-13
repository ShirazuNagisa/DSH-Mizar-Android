package me.shirazu.dshmizar.domain

import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.ThemeMode

/**
 * The stored switches on the settings screen.
 *
 * Reads are here for one reason: the first [UiState] a process builds is made of
 * them, and that happens before the state flow exists. Everything after that goes
 * through [PreferencesCoordinator], which writes both halves of a switch together.
 */
interface Preferences {

    /** "system" (the default), "light" or "dark". */
    fun themeMode(): String

    /** Whether finished-turn notices are silenced between 23:00 and 08:00. */
    fun quietHours(): Boolean

    /** Whether the LAN is tried before the relay. */
    fun localNetworkFirst(): Boolean

    fun notifyCompleted(): Boolean

    fun notifyInterrupted(): Boolean

    fun notifyQuestions(): Boolean

    fun notifyApprovals(): Boolean

    fun hapticsEnabled(): Boolean

    fun slowTypewriter(): Boolean

    /** Whether a message typed mid-turn steers instead of waiting for the turn to end. */
    fun steerByDefault(): Boolean

    fun setThemeMode(mode: String)

    fun setQuietHours(on: Boolean)

    fun setNotifyCompleted(on: Boolean)

    fun setNotifyInterrupted(on: Boolean)

    fun setNotifyQuestions(on: Boolean)

    fun setNotifyApprovals(on: Boolean)

    fun setHapticsEnabled(on: Boolean)

    fun setSlowTypewriter(on: Boolean)

    fun setSteerByDefault(on: Boolean)
}

/**
 * The state a fresh process starts from: the stored switches, read once.
 *
 * A function rather than a coordinator method because it runs before the state flow
 * it would otherwise write to exists -- the switches have to be in the first
 * [UiState] a screen ever sees, not in a second one a frame later.
 */
internal fun storedPreferences(preferences: Preferences): UiState = UiState(
    themeMode = preferences.themeMode().toThemeMode(),
    quietHours = preferences.quietHours(),
    localNetworkFirst = preferences.localNetworkFirst(),
    notifyCompleted = preferences.notifyCompleted(),
    notifyInterrupted = preferences.notifyInterrupted(),
    notifyQuestions = preferences.notifyQuestions(),
    notifyApprovals = preferences.notifyApprovals(),
    hapticsEnabled = preferences.hapticsEnabled(),
    slowTypewriter = preferences.slowTypewriter(),
    steerByDefault = preferences.steerByDefault(),
)

/**
 * The settings switches: what the operator turned on, kept for the next launch.
 *
 * Every write is two halves that must not part -- the stored copy and the copy on
 * screen. The stored one is what the announcer and the push path read, so a switch
 * that only reached the state would show "on" while notifications stayed off; a
 * switch that only reached the store would look stuck until the next launch. One
 * method per switch, both halves together, is the smallest thing that cannot split
 * them.
 *
 * The local-network switch is *not* here: turning it on starts a discovery scan on
 * the current host, which is the connection's business rather than a preference.
 */
internal class PreferencesCoordinator(
    private val store: StateStore,
    private val stored: Preferences,
) {

    fun setTheme(mode: ThemeMode) {
        stored.setThemeMode(mode.name.lowercase())
        store.update { it.copy(themeMode = mode) }
    }

    /** Remember the quiet-hours switch; the announcer reads the store directly. */
    fun setQuietHours(on: Boolean) {
        stored.setQuietHours(on)
        store.update { it.copy(quietHours = on) }
    }

    fun setNotifyCompleted(on: Boolean) {
        stored.setNotifyCompleted(on)
        store.update { it.copy(notifyCompleted = on) }
    }

    fun setNotifyInterrupted(on: Boolean) {
        stored.setNotifyInterrupted(on)
        store.update { it.copy(notifyInterrupted = on) }
    }

    fun setNotifyQuestions(on: Boolean) {
        stored.setNotifyQuestions(on)
        store.update { it.copy(notifyQuestions = on) }
    }

    fun setNotifyApprovals(on: Boolean) {
        stored.setNotifyApprovals(on)
        store.update { it.copy(notifyApprovals = on) }
    }

    fun setHapticsEnabled(on: Boolean) {
        stored.setHapticsEnabled(on)
        store.update { it.copy(hapticsEnabled = on) }
    }

    fun setSlowTypewriter(on: Boolean) {
        stored.setSlowTypewriter(on)
        store.update { it.copy(slowTypewriter = on) }
    }

    fun setSteerByDefault(on: Boolean) {
        stored.setSteerByDefault(on)
        store.update { it.copy(steerByDefault = on) }
    }
}

/** The stored spelling back into the enum; an unknown value follows the system. */
internal fun String.toThemeMode(): ThemeMode =
    when (lowercase()) {
        "light" -> ThemeMode.LIGHT
        "dark" -> ThemeMode.DARK
        else -> ThemeMode.SYSTEM
    }
