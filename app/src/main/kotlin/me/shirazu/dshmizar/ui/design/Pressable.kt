package me.shirazu.dshmizar.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.EaseOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The press feel: on touch-down the control snaps to its pressed scale in 80 ms
 * (easeOut), on release it springs back with the bouncy spring. Ported from iOS
 * `Design/PressableStyle.swift`.
 */
fun Modifier.pressable(
    scale: Float = 0.94f,
    dims: Boolean = false,
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    val animatedScale by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = if (pressed) tween(80, easing = EaseOut) else Motion.bouncy(),
        label = "pressableScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed && dims) 0.7f else 1f,
        animationSpec = if (pressed) tween(80, easing = EaseOut) else Motion.bouncy(),
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
            this.alpha = alpha
        }
}
