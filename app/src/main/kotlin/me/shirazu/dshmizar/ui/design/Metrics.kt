package me.shirazu.dshmizar.ui.design

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The few numbers the whole app is drawn with.
 *
 * The radii are the iOS app's ladder (`Design/Theme.swift`), and every one of them is
 * cut as a continuous corner ([ContinuousCornerShape]) through [Corner]. The 4-pt
 * spacing grid carries over unchanged: the corners got softer, the console did not
 * get roomier.
 */
object Radius {
    /** Small inner pieces. */
    val xs = 8.dp

    /** Rows, code blocks, the sent bubble, text fields. */
    val sm = 12.dp

    /** Buttons, popovers, the failure card, grouped fields. */
    val md = 16.dp

    /** Chips and pills: on a 32 dp chip this resolves to a capsule, as on iOS. */
    val lg = 20.dp

    /** The pending-request cards. */
    val card = 22.dp

    /** The composer slabs. */
    val xl = 26.dp

    /**
     * The conversation card pushed aside to reveal a drawer, when the screen's own
     * corner is unknown or too tight to follow (see [rememberPushedCardRadius]).
     */
    val cardPushed = 26.dp

    /** The sent bubble's tightened corner, nearest the answer. */
    val tail = 4.dp

    /** A checkbox mark. */
    val mark = 6.dp
}

/** The radius of a surface nested inside another, so their curves stay parallel. */
fun concentric(outer: Dp, inset: Dp): Dp = (outer - inset).coerceAtLeast(0.dp)

/** The ladder, cut continuous, allocated once. */
object Corner {
    val xs = ContinuousCornerShape(Radius.xs)
    val sm = ContinuousCornerShape(Radius.sm)
    val md = ContinuousCornerShape(Radius.md)
    val lg = ContinuousCornerShape(Radius.lg)
    val card = ContinuousCornerShape(Radius.card)
    val xl = ContinuousCornerShape(Radius.xl)
}

/**
 * Buttons take their shape from their role, not their width.
 *
 *   Primary  a screen's own call to action -- Pair, Scan, Retry, Pair again, Start
 *            session, Save: a continuous 16 dp rectangle, tool-like.
 *   Inline   an action inside a card or a window -- Allow once, Reject, Next,
 *            Submit, Allow full access: a capsule, even when it spans the card.
 *
 * Icon-only controls are round ([GlassCircleLabel]). Material's own buttons already
 * default to a capsule, so only Primary needs passing at a call site. Shape is never
 * the only cue for emphasis: fill and colour still carry it.
 */
object ButtonShape {
    val Primary: Shape = Corner.md
    val Inline: Shape = CircleShape
}

/**
 * The pushed card's corner: the phone's own screen corner, so the card's curve
 * continues the glass it sits behind -- the iOS app's reason for its 39 pt.
 *
 * Read from the window on API 31+, then held to 26–34 dp: emulators and some phones
 * report a corner far tighter than the glass (10.7 dp on the AVD), and a card that
 * rounds less than it used to reads as flat.
 */
@Composable
fun rememberPushedCardRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember(view, density) {
        val px = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
        } else {
            0
        }
        val dp = with(density) { px.toDp() }
        if (dp < Radius.cardPushed) Radius.cardPushed else dp.coerceAtMost(34.dp)
    }
}

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
