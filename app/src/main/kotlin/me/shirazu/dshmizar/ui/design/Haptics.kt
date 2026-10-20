package me.shirazu.dshmizar.ui.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * The app's one haptic gate.
 *
 * Compose's `LocalHapticFeedback` can only be read during composition, while most
 * haptics fire inside a click or effect lambda. So the setting is mirrored into this
 * tiny holder by [RootScreen], and every call site keeps the `HapticFeedback` it
 * already captured and calls [mizarPerform].
 */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

object MizarHapticsState {
    @Volatile
    var enabled: Boolean = true
}

/** Fire a haptic unless the operator turned them off. */
fun HapticFeedback.mizarPerform(type: HapticFeedbackType) {
    if (MizarHapticsState.enabled) performHapticFeedback(type)
}
