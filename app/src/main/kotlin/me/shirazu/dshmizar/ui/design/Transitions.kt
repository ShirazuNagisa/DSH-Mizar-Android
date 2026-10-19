package me.shirazu.dshmizar.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * The two custom transitions, ported from iOS `Design/Motion.swift`.
 *
 * Rise -- rising into place: a few points of travel and the fade. (The iOS
 * version also blurs; per-pixel entrance blur has no Compose equivalent below
 * API 31, so the fade-and-slide carries it.)
 *
 * Pop -- settling out of a scale: for things that grow from a point, like a
 * badge or a confirmation capsule.
 *
 * Arrivals ride the springs (travel on smooth or bouncy, opacity always on a
 * critically damped one, so it never flickers past opaque); departures are short
 * and plain, because something leaving should be gone, not lingering. Under
 * Reduce Motion each of them is the 220 ms fade alone: nothing travels, nothing
 * scales.
 */
object Transitions {

    @Composable
    fun riseIn(distance: Dp = 10.dp): EnterTransition {
        if (LocalReduceMotion.current) return fadeIn(Motion.fade())
        val offsetPx = with(LocalDensity.current) { distance.roundToPx() }
        return fadeIn(Motion.smooth()) + slideInVertically(Motion.smooth<IntOffset>()) { offsetPx }
    }

    @Composable
    fun riseOut(distance: Dp = 10.dp): ExitTransition {
        if (LocalReduceMotion.current) return fadeOut(Motion.fade())
        val offsetPx = with(LocalDensity.current) { distance.roundToPx() }
        return fadeOut(leave()) + slideOutVertically(leave<IntOffset>()) { -offsetPx }
    }

    @Composable
    fun popIn(): EnterTransition {
        if (LocalReduceMotion.current) return fadeIn(Motion.fade())
        return fadeIn(Motion.smooth()) + scaleIn(initialScale = 0.82f, animationSpec = Motion.bouncy())
    }

    @Composable
    fun popOut(): ExitTransition {
        if (LocalReduceMotion.current) return fadeOut(Motion.fade())
        return fadeOut(leave()) + scaleOut(targetScale = 0.9f, animationSpec = leave())
    }

    /** A departure: 160 ms, accelerating away. */
    private fun <T> leave() = tween<T>(160, easing = FastOutLinearInEasing)
}

/**
 * A count that rolls when it changes -- iOS `contentTransition(.numericText())`.
 * Up when it grows, down when it shrinks. Pair with tabular digits
 * (`fontFeatureSettings = "tnum"`).
 *
 * Kept for the few counts whose change is the news -- how many turns, how many new
 * messages, how many requests wait -- not for every number on screen. The label's
 * width changes in one step (no size animation): only the roll itself moves.
 */
@Composable
fun NumericText(
    targetState: Int,
    content: @Composable (Int) -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(
        targetState = targetState,
        transitionSpec = {
            (if (reduceMotion) {
                fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
            } else {
                val up = targetState > initialState
                val roll = spring(Spring.DampingRatioNoBouncy, 700f, IntOffset.VisibilityThreshold)
                (slideInVertically(roll) { h -> if (up) h else -h } + fadeIn(Motion.smooth())) togetherWith
                    (slideOutVertically(roll) { h -> if (up) -h else h } + fadeOut(tween(120)))
            }).using(null)
        },
        contentAlignment = Alignment.Center,
        label = "numericText",
    ) { content(it) }
}
