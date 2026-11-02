package me.shirazu.dshmizar.ui

import androidx.compose.ui.graphics.Color

/**
 * `#rrggbb` or `#aarrggbb`, as the account avatar palette writes it.
 *
 * Kept as an `Int` rather than a `Color` so the parsing is testable without a
 * device: the account screen and the sidebar avatar each had their own copy, and
 * the only thing that ever differed between them was where the colour was drawn.
 * Anything that is not six or eight hex digits is refused rather than guessed --
 * a wrong avatar colour is worse than the theme's own.
 */
internal fun hexToArgb(value: String): Int? = runCatching {
    val hex = value.removePrefix("#")
    val argb = when (hex.length) {
        6 -> 0xFF000000L or hex.toLong(16)
        8 -> hex.toLong(16)
        else -> return null
    }
    argb.toInt()
}.getOrNull()

/** The same parse, in the type the drawing code wants. */
internal fun hexColor(value: String): Color? = hexToArgb(value)?.let(::Color)
