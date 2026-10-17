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

/** The panel's own accent, so a Mizar screen is recognisable as one. */
private val MizarBlue = Color(0xFF2F6FED)
private val MizarBlueDark = Color(0xFF8FB4FF)

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
    primary = MizarBlue,
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
    primary = MizarBlueDark,
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
    handleColor = Color(0xFF2F6FED),
    backgroundColor = Color(0xFFB9CFFB),
)
private val SelectionDark = TextSelectionColors(
    handleColor = MizarBlueDark,
    backgroundColor = Color(0xFF3A4A66),
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
    MaterialTheme(colorScheme = if (dark) Dark else Light) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides if (dark) SelectionDark else SelectionLight,
            content = content,
        )
    }
}

/** The colours an approval or a failure is drawn in, kept out of the scheme. */
object MizarColors {
    val Warning = Color(0xFFB26A00)
    val WarningFill = Color(0x1FB26A00)
    val Info = Color(0xFF1B5E9C)
    val InfoFill = Color(0x1A1B5E9C)
    val Failure = Color(0xFFB3261E)
    val FailureFill = Color(0x1AB3261E)

    /** A running turn's indicator: distinct from the accent so it reads as "busy". */
    val Busy = Color(0xFF2E9E6B)
}
