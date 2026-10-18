package me.shirazu.dshmizar.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.ui.unit.dp

/**
 * The two custom transitions, ported from iOS `Design/Motion.swift`.
 *
 * Rise -- rising into place: a few points of travel and the fade. (The iOS
 * version also blurs; per-pixel entrance blur has no Compose equivalent below
 * API 31, so the fade-and-slide carries it. Under Reduce Motion only the fade
 * is left.)
 *
 * Pop -- settling out of a scale: for things that grow from a point, like a
 * badge or a confirmation capsule.
 */
object Transitions {

    @Composable
    fun riseIn(distance: Dp = 10.dp): EnterTransition {
        if (LocalReduceMotion.current) return fadeIn(tween(220))
        val offsetPx = with(LocalDensity.current) { distance.roundToPx() }
        return fadeIn(tween(500)) + slideInVertically(tween(500)) { offsetPx }
    }

    @Composable
    fun riseOut(distance: Dp = 10.dp): ExitTransition {
        if (LocalReduceMotion.current) return fadeOut(tween(220))
        val offsetPx = with(LocalDensity.current) { distance.roundToPx() }
        return fadeOut(tween(300)) + slideOutVertically(tween(300)) { -offsetPx }
    }

    @Composable
    fun popIn(): EnterTransition {
        if (LocalReduceMotion.current) return fadeIn(tween(220))
        return fadeIn(tween(300)) + scaleIn(initialScale = 0.82f, animationSpec = Motion.bouncy<Float>())
    }

    @Composable
    fun popOut(): ExitTransition =
        fadeOut(tween(180)) + scaleOut(targetScale = 0.9f, animationSpec = tween(180))
}

/**
 * A count that rolls when it changes -- iOS `contentTransition(.numericText())`.
 * Pair with tabular digits (`fontFeatureSettings = "tnum"`).
 */
@Composable
fun <T> NumericText(
    targetState: T,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        transitionSpec = {
            slideInVertically(tween(300)) { it } + fadeIn(tween(300)) togetherWith
                slideOutVertically(tween(300)) { -it } + fadeOut(tween(300))
        },
        contentAlignment = Alignment.Center,
        label = "numericText",
    ) { content(it) }
}
