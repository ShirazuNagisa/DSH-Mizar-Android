package me.shirazu.dshmizar.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch

/**
 * The press highlight, drawn in the surface's own shape.
 *
 * Material's ripple fills the node's bounds and relies on a clip to keep it inside
 * the corners. Clipping to a continuous corner is a generic path, which is a canvas
 * clip below API 33 -- so the app's surfaces paint their shape with
 * `background(color, shape)` instead and leave the clip off, and this draws the
 * pressed (and focused, and hovered) state with that same outline. A quiet veil of
 * the content colour, in quickly and out on a spring, beside [pressable]'s scale.
 */
class ShapedIndication(private val shape: Shape) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        ShapedIndicationNode(interactionSource, shape)

    override fun equals(other: Any?): Boolean = other is ShapedIndication && other.shape == shape
    override fun hashCode(): Int = shape.hashCode()
}

private class ShapedIndicationNode(
    private val interactionSource: InteractionSource,
    private val shape: Shape,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val veil = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var pressed = 0
            var focused = 0
            var hovered = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = (pressed - 1).coerceAtLeast(0)
                    is FocusInteraction.Focus -> focused++
                    is FocusInteraction.Unfocus -> focused = (focused - 1).coerceAtLeast(0)
                    is HoverInteraction.Enter -> hovered++
                    is HoverInteraction.Exit -> hovered = (hovered - 1).coerceAtLeast(0)
                }
                val target = when {
                    pressed > 0 || focused > 0 -> 0.10f
                    hovered > 0 -> 0.06f
                    else -> 0f
                }
                launch { veil.animateTo(target, if (target > veil.value) VeilIn else VeilOut) }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = veil.value
        if (alpha > 0f) {
            val ink = currentValueOf(LocalContentColor)
            drawOutline(shape.createOutline(size, layoutDirection, this), ink.copy(alpha = alpha))
        }
    }

    private companion object {
        val VeilIn = spring<Float>(dampingRatio = 1f, stiffness = 2500f)
        val VeilOut = spring<Float>(dampingRatio = 1f, stiffness = 500f)
    }
}

/**
 * `clickable` with its press highlight cut to [shape], for surfaces that paint their
 * shape rather than clip to it.
 */
fun Modifier.shapedClickable(
    shape: Shape,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = null,
    indication = ShapedIndication(shape),
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
    onClick = onClick,
)

/**
 * The press feel, ported from iOS `Design/PressableStyle.swift`: on touch-down the
 * control sinks to its pressed scale on a stiff spring (90% there in ~78 ms, inside
 * the 80 ms press budget); on release it springs back on the bouncy one. Both are
 * springs, so a release mid-press carries the motion it has instead of restarting.
 * The dim, when asked for, never overshoots: opacity rides a critically damped
 * spring both ways. Under Reduce Motion nothing scales; the dim cross-fades.
 */
fun Modifier.pressable(
    scale: Float = 0.94f,
    dims: Boolean = false,
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    val animatedScale by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = if (pressed) PressDown else Motion.bouncy(),
        label = "pressableScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed && dims) 0.7f else 1f,
        animationSpec = when {
            reduceMotion -> Motion.fade()
            pressed -> PressDown
            else -> Motion.smooth()
        },
        label = "pressableAlpha",
    )
    this
        .pointerInput(reduceMotion) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
        .graphicsLayer {
            if (!reduceMotion) {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            this.alpha = alpha.coerceIn(0f, 1f)
        }
}

/** The touch-down: critically damped and stiff -- 90% of the way in ~78 ms. */
private val PressDown = spring<Float>(dampingRatio = 1f, stiffness = 2500f)
