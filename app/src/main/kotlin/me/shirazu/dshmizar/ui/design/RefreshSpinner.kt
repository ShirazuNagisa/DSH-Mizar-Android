package me.shirazu.dshmizar.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The pull-to-refresh glyph: three square-ended dashes chasing each other
 * around a ring, clockwise. Deliberately not the circular arrow the header
 * button wears -- a pull gesture and a button tap are two different doors to
 * the same refresh, and each door gets its own face.
 *
 * While the pull is still in the operator's hand the ring turns with the hand
 * ([dragRotation]); once the refresh owns it, it spins on its own.
 */
@Composable
fun RefreshSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    spinning: Boolean = false,
    dragRotation: Float = 0f,
) {
    val turn by if (spinning && !LocalReduceMotion.current) {
        rememberInfiniteTransition(label = "pullSpin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
            label = "pullSpinTurn",
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }
    }
    val tint = MaterialTheme.colorScheme.primary

    Canvas(modifier.size(size)) {
        val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Butt)
        val inset = stroke.width / 2f
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke.width, this.size.height - stroke.width)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        rotate(if (spinning) turn else dragRotation) {
            for (arm in 0 until 3) {
                drawArc(
                    color = tint,
                    startAngle = arm * 120f + 8f,
                    sweepAngle = 104f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
        }
    }
}
