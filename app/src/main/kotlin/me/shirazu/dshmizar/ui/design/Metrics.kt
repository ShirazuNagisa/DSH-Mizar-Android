package me.shirazu.dshmizar.ui.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The few numbers the whole app is drawn with, ported from the iOS
 * `Design/Theme.swift`: one radius scale (continuous corners) and the 4-pt
 * spacing grid.
 */
object Radius {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 26.dp

    /** A sheet-sized card. */
    val card = 22.dp

    /** The conversation card pushed aside to reveal the sidebar. */
    val cardPushed = 39.dp
}

/** The radius of a surface nested inside another, so their curves stay parallel. */
fun concentric(outer: Dp, inset: Dp): Dp = (outer - inset).coerceAtLeast(0.dp)

object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val huge = 32.dp
}
