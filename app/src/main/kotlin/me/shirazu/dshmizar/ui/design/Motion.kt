package me.shirazu.dshmizar.ui.design

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How things move on the console: fast and short.
 *
 * No springs, no overshoot -- an operator's tool answers in 150–220 ms and
 * sits down. The four names survive so call sites keep their intent:
 *
 *   snappy  controls answering a tap: chevrons, toggles, highlights
 *   smooth  things changing place: rows, sheets of content, the composer
 *   bouncy  something arriving that wants to be noticed (a send, a badge) --
 *           still quick; "noticed" comes from the pop scale, not the spring
 *   gentle  the settle of large surfaces and of numbers counting up
 *
 * Reduce Motion replaces every one of them with the same short fade.
 */
object Motion {
    private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> snappy(): FiniteAnimationSpec<T> = tween(150, easing = Emphasized)
    fun <T> smooth(): FiniteAnimationSpec<T> = tween(200, easing = Emphasized)
    fun <T> bouncy(): FiniteAnimationSpec<T> = tween(220, easing = Emphasized)
    fun <T> gentle(): FiniteAnimationSpec<T> = tween(300, easing = Emphasized)

    /** What every one of them becomes under Reduce Motion. */
    fun <T> fade(): FiniteAnimationSpec<T> = tween(220)

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
