package me.shirazu.dshmizar.ui.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The few numbers the whole app is drawn with. The console look is small
 * corners and flat surfaces: three radii cover everything, and the 4-pt
 * spacing grid carries over.
 */
object Radius {
    /** Chips, small buttons. */
    val xs = 4.dp

    /** Rows, code blocks, inner surfaces. */
    val sm = 6.dp

    /** Cards, sheets, the composer. */
    val md = 10.dp

    /** The one large corner: a sheet-sized card's top. */
    val lg = 14.dp

    /** Kept for call sites that sized a big sheet. */
    val xl = 18.dp

    /** A sheet-sized card. */
    val card = 10.dp

    /** The conversation card pushed aside to reveal a drawer. */
    val cardPushed = 20.dp
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
