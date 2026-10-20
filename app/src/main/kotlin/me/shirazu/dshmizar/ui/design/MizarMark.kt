package me.shirazu.dshmizar.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.min

/**
 * The icon's mark -- a dot and three arcs of signal -- as something that can
 * move. Ported from iOS `Design/MizarMark.swift`: the geometry is the icon's
 * own (a 68×46 box, arcs centred on the dot and spanning 124°, starting at
 * 208°), each arc lit on its own.
 */

private val Radii = floatArrayOf(11.1f, 22.1f, 33f)

/** The mark, with as many arcs lit as asked. Fractions light an arc partially. */
@Composable
fun MizarMark(
    modifier: Modifier = Modifier,
    lit: Float = 3f,
    accent: Color = MaterialTheme.colorScheme.primary,
    ink: Color = MaterialTheme.colorScheme.onSurface,
) = MizarMark(modifier, lit = { lit }, accent = accent, ink = ink)

/**
 * The mark, lit by a number read as it draws: a mark that is animating redraws
 * each frame and recomposes nothing.
 */
@Composable
fun MizarMark(
    modifier: Modifier = Modifier,
    lit: () -> Float,
    accent: Color = MaterialTheme.colorScheme.primary,
    ink: Color = MaterialTheme.colorScheme.onSurface,
) {
    Canvas(modifier.aspectRatio(68f / 46f)) {
        val lit = lit()
        val scale = min(size.width / 68f, size.height / 46f)
        // The arc centre: the dot's position, (34, 38) in mark units.
        val center = Offset(size.width / 2f, size.height / 2f - 23f * scale + 38f * scale)
        // The dot.
        drawCircle(color = ink, radius = 6.25f * scale, center = center)
        // Three arcs, each lit dot-outward.
        Radii.forEachIndexed { index, radius ->
            val amount = (lit - index).coerceIn(0f, 1f)
            drawArc(
                color = if (index == 2) accent else ink,
                startAngle = 208f,
                sweepAngle = 124f,
                useCenter = false,
                topLeft = Offset(center.x - radius * scale, center.y - radius * scale),
                size = Size(2f * radius * scale, 2f * radius * scale),
                alpha = 0.16f + 0.84f * amount,
                style = Stroke(width = 7.4f * scale, cap = StrokeCap.Butt),
            )
        }
    }
}

/** The mark, lighting dot-outward once, then resting. The home screen's greeting. */
@Composable
fun MizarMarkConnecting(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    ink: Color = MaterialTheme.colorScheme.onSurface,
) {
    val reduceMotion = LocalReduceMotion.current
    val lit = remember { Animatable(if (reduceMotion) 3f else 0f) }
    LaunchedEffect(reduceMotion) {
        if (!reduceMotion && lit.value == 0f) {
            kotlinx.coroutines.delay(150)
            lit.animateTo(3f, LightUp)
        }
    }
    MizarMark(modifier, lit = { lit.value }, accent = accent, ink = ink)
}

/**
 * The mark, sending a pulse up through its arcs for as long as it is on
 * screen: a signal looking for something to answer it. One beat is 2.9 s:
 * arcs come up in turn, hold, fall away together, then rest.
 */
@Composable
fun MizarMarkSearching(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    ink: Color = MaterialTheme.colorScheme.onSurface,
) {
    val reduceMotion = LocalReduceMotion.current
    if (reduceMotion) {
        MizarMark(modifier, lit = 3f, accent = accent, ink = ink)
        return
    }
    val lit = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { searchPulse(lit) }
    MizarMark(modifier, lit = { lit.value }, accent = accent, ink = ink)
}

/** The searching beat, for ever: arcs up in turn (1.1 s), hold (0.5 s), fall away
 *  together (0.6 s), rest (0.7 s). */
internal suspend fun searchPulse(lit: Animatable<Float, AnimationVector1D>) {
    while (true) {
        lit.animateTo(3f, tween(1100, easing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)))
        lit.animateTo(3f, tween(500, easing = LinearEasing))
        lit.animateTo(0.6f, tween(600, easing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)))
        lit.animateTo(0.6f, tween(700, easing = LinearEasing))
    }
}

/** The lighting of [MizarMarkConnecting]: dot-outward, once. */
internal val LightUp = tween<Float>(900, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f))
