package me.shirazu.dshmizar.ui.design

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How things move: on springs.
 *
 * This replaced a set of 150–300 ms tweens on purpose, and it should not drift back.
 * A tween has a duration, so a retargeted tween restarts its clock and throws the
 * motion it had away; a spring has only a target, so an interrupted change carries
 * its velocity into the new one and a fast tap never looks like a stutter. Springs
 * also answer at once -- most of the travel happens in the first frames -- which is
 * what makes a tool feel responsive. "Expressive" here means choreography (stagger,
 * shared elements, a mark lighting up), never longer animations.
 *
 * The four names keep their intent; the numbers are damping ratio / stiffness:
 *
 *   snappy  1.0 / 900   controls answering a tap: chevrons, toggles, highlights.
 *                       90% in ~130 ms, settled in ~195 ms.
 *   smooth  1.0 / 500   things changing place: rows, content, the composer.
 *                       90% in ~175 ms, settled in ~260 ms.
 *   bouncy  0.78 / 600  something arriving that wants to be noticed: a send, a
 *                       badge, a mark. Peaks at ~205 ms with ~2% overshoot.
 *   gentle  0.95 / 260  large surfaces and numbers counting. Settled in ~360 ms.
 *
 * Budgets: press feedback <= 80 ms, control state <= 150 ms, content 200–350 ms,
 * large surfaces <= 500 ms.
 *
 * Rules that go with them:
 *  - Opacity never bounces: alpha uses a damping ratio of 1 even beside a bouncy
 *    transform.
 *  - Only render properties move frame by frame -- graphicsLayer translation, scale,
 *    alpha, rotation, and what is drawn. Layout changes in one step.
 *  - Ambient loops (TwinStar's orbit, the mark's search pulse, spinners, shimmer)
 *    stay on time-based tweens: a spring has nowhere to loop to.
 *  - Reduce Motion replaces every one of them with the same 220 ms cross-fade, in
 *    place: nothing travels, nothing scales.
 */
object Motion {
    fun <T> snappy(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 900f)
    fun <T> smooth(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 500f)
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.78f, stiffness = 600f)
    fun <T> gentle(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.95f, stiffness = 260f)

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
 * cross-fades in place. Provided at the root from the system setting, and kept live.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }
