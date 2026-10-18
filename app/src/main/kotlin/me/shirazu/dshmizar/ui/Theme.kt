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
 * The panel's accent, ported from the iOS app's asset catalogue: a cyan deep
 * enough to carry white text in the light appearance and bright enough to
 * glow in the dark one.
 */
val AccentLight = Color(0xFF0A78AE)
val AccentDark = Color(0xFF3CCBF4)

/**
 * Neutral surfaces, on purpose.
 *
 * Setting only `primary` left every other role at Material's baseline palette, which is
 * blue-violet. On a phone that reads as "the input box is dark blue", and a text
 * selection drawn in the accent over it leaves the selected words hard to read -- the
 * two things an operator complained about before this file had any surfaces in it. The
 * greys below are the panel's own neutrality, so the accent is the only colour that
 * means anything.
 */
private val Light = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFDFDFF),
    surfaceVariant = Color(0xFFEDEFF3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9FAFC),
    surfaceContainer = Color(0xFFF2F4F7),
    surfaceContainerHigh = Color(0xFFEBEEF2),
    surfaceContainerHighest = Color(0xFFE4E8EE),
    onSurface = Color(0xFF14171C),
    onSurfaceVariant = Color(0xFF444A55),
    outline = Color(0xFF8A9099),
    outlineVariant = Color(0xFFD3D8DF),
)

private val Dark = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF03141F),
    background = Color(0xFF0D0F12),
    surface = Color(0xFF111418),
    surfaceVariant = Color(0xFF1B1F25),
    surfaceContainerLowest = Color(0xFF090B0E),
    surfaceContainerLow = Color(0xFF0F1216),
    surfaceContainer = Color(0xFF14171C),
    surfaceContainerHigh = Color(0xFF1A1E24),
    surfaceContainerHighest = Color(0xFF21262E),
    onSurface = Color(0xFFE8EAEE),
    onSurfaceVariant = Color(0xFFB3BAC4),
    outline = Color(0xFF6B7280),
    outlineVariant = Color(0xFF2C323A),
)

/**
 * A selection an operator can actually read.
 *
 * The accent is light in the dark theme, and a translucent accent over dark text is
 * exactly the combination that makes selected words disappear. These are solid mid
 * tones chosen so `onSurface` text keeps its contrast on top of them in both themes.
 */
private val SelectionLight = TextSelectionColors(
    handleColor = AccentLight,
    backgroundColor = Color(0xFFADD4E8),
)
private val SelectionDark = TextSelectionColors(
    handleColor = AccentDark,
    backgroundColor = Color(0xFF2A4A5C),
)

/** How the screen is lit. The default follows the system. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The theme.
 *
 * Material 3 with neutral surfaces and one accent, and **no dynamic colour**: the panel
 * is a tool an operator reads under pressure, and a palette that changes with the
 * wallpaper changes what "orange means waiting for you" looks like.
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
 * The semantic colours the app has always drawn with, ported from the iOS
 * `Theme.swift`. States keep the platform hues so they read the same on both
 * systems; the brand navy and the sidebar ground are the icon's own.
 */
object MizarColors {
    /** The icon's navy: pairing screen, Live Activity, settings headers. */
    val Navy = Color(0xFF131F3D)
    val NavyDeep = Color(0xFF080D1B)

    // States, in the platform hues (iOS systemGreen/Orange/Red/Blue).
    val Running @Composable get() = if (LocalMizarDark.current) Color(0xFF30D158) else Color(0xFF34C759)
    val Waiting @Composable get() = if (LocalMizarDark.current) Color(0xFFFF9F0A) else Color(0xFFFF9500)
    val Failed @Composable get() = if (LocalMizarDark.current) Color(0xFFFF453A) else Color(0xFFFF3B30)
    val Question @Composable get() = if (LocalMizarDark.current) Color(0xFF0A84FF) else Color(0xFF007AFF)

    /** Ink for text drawn on an accent fill. */
    val OnAccent @Composable get() = if (LocalMizarDark.current) Color(0xFF03141F) else Color.White

    /** The sidebar's ground: one step off the conversation's, so the lifted card reads. */
    val SidebarBackground @Composable get() = if (LocalMizarDark.current) Color(0xFF131519) else Color(0xFFF3F4F7)

    /** Under the selected row: a sheet of paper on light, a faint lift on dark. */
    val SelectionFill @Composable get() = if (LocalMizarDark.current) Color.White.copy(alpha = 0.085f) else Color.White

    // Kept names, retinted to the state hues above: existing call sites
    // (approval/failure surfaces) keep compiling while the look moves over.
    val Warning @Composable get() = Waiting
    val WarningFill @Composable get() = Waiting.copy(alpha = 0.12f)
    val Info @Composable get() = Question
    val InfoFill @Composable get() = Question.copy(alpha = 0.10f)
    val Failure @Composable get() = Failed
    val FailureFill @Composable get() = Failed.copy(alpha = 0.09f)

    /** A running turn's indicator: distinct from the accent so it reads as "busy". */
    val Busy @Composable get() = Running
}
