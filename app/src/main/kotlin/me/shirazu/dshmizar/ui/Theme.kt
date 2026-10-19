package me.shirazu.dshmizar.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * The console palette.
 *
 * The app is an operator's console -- a phone whose job is to be read while a
 * remote agent works -- so it is dressed like one: a dark ink-blue ground as the
 * primary scene, one signal cyan that means "this is the accent", and a
 * blue-grey neutral ladder for everything else. The light appearance is the
 * paper counterpart, not the other way around.
 *
 * State colours stay legible next to the accent by hue distance: running is the
 * accent itself (the console's own colour of work), waiting is amber, failure
 * is red, a question is blue.
 */

/** The one accent: signal cyan. Deep on light, glowing on dark. */
val AccentLight = Color(0xFF0E7C86)
val AccentDark = Color(0xFF57D7E3)

private val Light = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EEF1),
    onPrimaryContainer = Color(0xFF062024),
    background = Color(0xFFF6F7F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF0F4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F6F8),
    surfaceContainer = Color(0xFFEDF0F4),
    surfaceContainerHigh = Color(0xFFE6EAEF),
    surfaceContainerHighest = Color(0xFFDDE2EA),
    onSurface = Color(0xFF161B26),
    onSurfaceVariant = Color(0xFF5A6372),
    outline = Color(0xFF9AA3B2),
    outlineVariant = Color(0xFFDDE2EA),
)

private val Dark = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF062024),
    primaryContainer = Color(0xFF14343B),
    onPrimaryContainer = Color(0xFFCDEFF4),
    background = Color(0xFF0C111B),
    surface = Color(0xFF101623),
    surfaceVariant = Color(0xFF161D2C),
    surfaceContainerLowest = Color(0xFF080C14),
    surfaceContainerLow = Color(0xFF0E1320),
    surfaceContainer = Color(0xFF121828),
    surfaceContainerHigh = Color(0xFF161D2E),
    surfaceContainerHighest = Color(0xFF1C2438),
    onSurface = Color(0xFFE3E8F0),
    onSurfaceVariant = Color(0xFF8B94A7),
    outline = Color(0xFF3A4356),
    outlineVariant = Color(0xFF232B3B),
)

/**
 * A selection an operator can actually read: solid mid tones so `onSurface`
 * text keeps its contrast on top of them in both themes.
 */
private val SelectionLight = TextSelectionColors(
    handleColor = AccentLight,
    backgroundColor = Color(0xFFBCE3E8),
)
private val SelectionDark = TextSelectionColors(
    handleColor = AccentDark,
    backgroundColor = Color(0xFF1E4A52),
)

/** How the screen is lit. The default follows the system. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The theme.
 *
 * Material 3 with neutral surfaces and one accent, and **no dynamic colour**: the panel
 * is a tool an operator reads under pressure, and a palette that changes with the
 * wallpaper changes what "amber means waiting for you" looks like.
 */
@Composable
fun MizarTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val reduceMotion = rememberReduceMotion()
    MaterialTheme(colorScheme = if (dark) Dark else Light) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides if (dark) SelectionDark else SelectionLight,
            LocalMizarDark provides dark,
            me.shirazu.dshmizar.ui.design.LocalReduceMotion provides reduceMotion,
            content = content,
        )
    }
}

/**
 * The operator's Reduce Motion setting, the Android way: the animator duration
 * scale set to zero (accessibility → remove animations).
 */
@Composable
private fun rememberReduceMotion(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

/** Whether the current [MizarTheme] is dark, for the semantic colours below. */
val LocalMizarDark = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * The semantic colours of the console. The brand navy survives for the pairing
 * screen; the states are chosen to read at a glance against both grounds.
 */
object MizarColors {
    /** The icon's navy: pairing screen and brand surfaces. */
    val Navy = Color(0xFF131F3D)
    val NavyDeep = Color(0xFF080D1B)

    // States. Running is the accent itself: on this console, "working" is the
    // house colour.
    val Running @Composable get() = MaterialTheme.colorScheme.primary
    val Waiting = Color(0xFFE8A33D)
    val Failed = Color(0xFFE5484D)
    val Question @Composable get() = if (LocalMizarDark.current) Color(0xFF6EA2FF) else Color(0xFF2F6FED)

    /** Ink for text drawn on an accent fill. */
    val OnAccent @Composable get() = MaterialTheme.colorScheme.onPrimary

    /** The sidebar's ground: one step off the conversation's, so the lifted card reads. */
    val SidebarBackground @Composable get() = if (LocalMizarDark.current) Color(0xFF080C14) else Color(0xFFEEF1F5)

    /** The selected row: a whisper of the accent, so the mark is colour, not lift. */
    val SelectionFill @Composable get() = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)

    // Kept names for the approval/failure call sites; the look is the state hues.
    val Warning get() = Waiting
    val WarningFill get() = Waiting.copy(alpha = 0.12f)
    val Info @Composable get() = Question
    val InfoFill @Composable get() = Question.copy(alpha = 0.10f)
    val Failure get() = Failed
    val FailureFill get() = Failed.copy(alpha = 0.09f)

    /** A running turn's indicator. */
    val Busy @Composable get() = Running
}
