package me.shirazu.dshmizar.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Running", drawn as the app's namesake.
 *
 * Mizar is a double star -- the bright one in the Big Dipper's handle with its
 * faint companion, Alcor, beside it. A session that is working shows exactly
 * that: a dot, and a smaller one going round it. Ported from iOS
 * `Design/TwinStar.swift`. Under Reduce Motion the companion stops at -35°.
 */
@Composable
fun TwinStar(
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    periodMillis: Int = 2400,
) {
    val reduceMotion = LocalReduceMotion.current
    val angle by if (reduceMotion) {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(-35f) }
    } else {
        rememberInfiniteTransition(label = "twinStar").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing), RepeatMode.Restart),
            label = "twinStarAngle",
        )
    }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        // Main star: dot 50% diameter, with a 55%-alpha glow.
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = 0.55f),
                1f to color.copy(alpha = 0f),
                center = center,
                radius = s * 0.5f,
            ),
            radius = s * 0.5f,
            center = center,
        )
        drawCircle(color = color, radius = s * 0.25f, center = center)
        // Companion: 24% diameter at 40% radius, orbiting.
        val rad = Math.toRadians(angle.toDouble())
        val companion = Offset(
            x = center.x + (s * 0.4f) * cos(rad).toFloat(),
            y = center.y + (s * 0.4f) * sin(rad).toFloat(),
        )
        drawCircle(color = color.copy(alpha = 0.85f), radius = s * 0.12f, center = companion)
    }
}
