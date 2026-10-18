package me.shirazu.dshmizar.ui.design

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.EaseInOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How things move, in four springs -- ported from iOS `Design/Motion.swift`.
 *
 * Springs rather than curves because a spring can be interrupted: retargeted
 * mid-flight it carries its velocity into the new destination instead of
 * restarting, and that -- not speed -- is what makes a touch feel answered.
 *
 *   snappy  controls answering a tap: chevrons, toggles, highlights
 *   smooth  things changing place: rows, sheets of content, the composer
 *   bouncy  something arriving that wants to be noticed: a send, a badge
 *   gentle  the slow settle of large surfaces and of numbers counting up
 *
 * iOS spring(duration, bounce) pairs map onto Compose (dampingRatio, stiffness)
 * by matching the response time:
 *   0.30s/b0 -> NoBouncy + High; 0.50s/b0 -> NoBouncy + MediumLow;
 *   0.45s/b0.18 -> damping 0.82 + Medium; 0.80s/b0.05 -> damping 0.95 + Low.
 */
object Motion {
    fun <T> snappy(): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessHigh)
    fun <T> smooth(): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(0.82f, Spring.StiffnessMedium)
    fun <T> gentle(): FiniteAnimationSpec<T> = spring(0.95f, Spring.StiffnessLow)

    /** What every one of them becomes under Reduce Motion. */
    fun <T> fade(): FiniteAnimationSpec<T> = tween(220, easing = EaseInOut)

    /** The animation to use, given the operator's Reduce Motion setting. */
    @Composable
    fun <T> pick(spec: AnimationSpec<T>): AnimationSpec<T> =
        if (LocalReduceMotion.current) fade() else spec
}

/**
 * Whether the operator asked for less movement. Fewer transitions, not shorter
 * ones: a state still changes, and changes visibly, but nothing travels; it
 * cross-fades in place. Provided at the root from the system setting.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }
