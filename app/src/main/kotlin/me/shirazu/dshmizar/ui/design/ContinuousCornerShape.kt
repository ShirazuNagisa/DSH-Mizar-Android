package me.shirazu.dshmizar.ui.design

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The corner every surface in the app is cut with: Apple's continuous corner, not
 * a quarter circle.
 *
 * A circular corner joins a straight edge with a jump in curvature -- zero along the
 * edge, 1/r the instant the arc starts -- and the eye reads that jump as a seam. The
 * continuous corner (SwiftUI `RoundedRectangle(style: .continuous)`, the iOS app's
 * every surface) eases into the turn instead: it leaves the edge earlier, bends
 * gently, and only reaches full curvature at the diagonal.
 *
 * The construction, per corner, walking clockwise:
 *
 *   1. leave the edge `p = (1 + s)·r` before the corner -- the curve claims more of
 *      the edge than a circle would. iOS's own path (UIBezierPath since iOS 7, as
 *      traced by PaintCode) leaves at ≈1.528·r, which is s ≈ 0.53;
 *   2. a cubic that eases from the straight edge into the circle;
 *   3. a short circular arc around the diagonal, spanning `90°·(1 − s)` of the turn;
 *   4. the mirror of (2) onto the next edge.
 *
 * The segment lengths are the published smoothed-corner construction (Figma,
 * "Desperately seeking squircles"); [smoothing] is its `s`. At s = 0 it is exactly a
 * circular corner; the default, 0.528, is Apple's.
 *
 * When a side is too short for the corner to claim `p` of it -- a 32 dp chip with a
 * 20 dp corner -- the smoothing is shed first and the radius second, so a short
 * surface degrades into a true capsule rather than a pinched one, which is what iOS
 * does too.
 *
 * Fast paths, because most of these never need a path at all:
 *  - every corner zero → [Outline.Rectangle];
 *  - smoothing zero, or every corner degraded to circular → [Outline.Rounded], which
 *    the render node clips natively on every API level;
 *  - otherwise [Outline.Generic]. Clipping to a generic path is native from API 33
 *    and a canvas clip below it, so callers prefer `background(color, shape)` over
 *    `clip(shape)` wherever nothing overflows.
 *
 * Circles and capsules are not this shape's job: they stay `CircleShape`. Neither are
 * one- or two-point decorative radii, where curvature continuity is invisible; those
 * stay `RoundedCornerShape`.
 *
 * [CONTINUOUS_CORNERS] turns every instance back into circular corners at once -- the
 * one switch to pull if the curve ever misbehaves.
 */
data class ContinuousCornerShape(
    private val topStartSize: CornerSize,
    private val topEndSize: CornerSize,
    private val bottomEndSize: CornerSize,
    private val bottomStartSize: CornerSize,
    val smoothing: Float = IosSmoothing,
) : CornerBasedShape(topStartSize, topEndSize, bottomEndSize, bottomStartSize) {

    constructor(radius: Dp, smoothing: Float = IosSmoothing) : this(
        CornerSize(radius),
        CornerSize(radius),
        CornerSize(radius),
        CornerSize(radius),
        smoothing,
    )

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = ContinuousCornerShape(topStart, topEnd, bottomEnd, bottomStart, smoothing)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        if (topStart + topEnd + bottomEnd + bottomStart == 0f) return Outline.Rectangle(size.toRect())
        val ltr = layoutDirection == LayoutDirection.Ltr
        val half = size.minDimension / 2f
        val wanted = if (CONTINUOUS_CORNERS) smoothing else 0f
        // Clockwise from the top-left, in screen space.
        val corners = arrayOf(
            fit(if (ltr) topStart else topEnd, wanted, half),
            fit(if (ltr) topEnd else topStart, wanted, half),
            fit(if (ltr) bottomEnd else bottomStart, wanted, half),
            fit(if (ltr) bottomStart else bottomEnd, wanted, half),
        )
        if (corners.all { it.s == 0f }) {
            return Outline.Rounded(
                RoundRect(
                    rect = size.toRect(),
                    topLeft = CornerRadius(corners[0].r),
                    topRight = CornerRadius(corners[1].r),
                    bottomRight = CornerRadius(corners[2].r),
                    bottomLeft = CornerRadius(corners[3].r),
                ),
            )
        }
        val w = size.width
        val h = size.height
        // Each corner as its point, the direction back along the edge that arrives at
        // it, and the direction along the edge that leaves it.
        val frames = arrayOf(
            Frame(Offset(w, 0f), Offset(-1f, 0f), Offset(0f, 1f)),
            Frame(Offset(w, h), Offset(0f, -1f), Offset(-1f, 0f)),
            Frame(Offset(0f, h), Offset(1f, 0f), Offset(0f, -1f)),
            Frame(Offset(0f, 0f), Offset(0f, 1f), Offset(1f, 0f)),
        )
        val order = intArrayOf(1, 2, 3, 0)
        val path = Path()
        path.moveTo(corners[0].p, 0f)
        for (i in 0 until 4) {
            corner(path, frames[i], corners[order[i]])
        }
        path.close()
        return Outline.Generic(path)
    }

    override fun toString(): String =
        "ContinuousCornerShape(topStart = $topStart, topEnd = $topEnd, bottomEnd = $bottomEnd, " +
            "bottomStart = $bottomStart, smoothing = $smoothing)"

    companion object {
        /**
         * iOS's own edge extension: the curve leaves the edge at 1.528·r, as Apple's
         * path does. (Figma's 60%, often quoted as the iOS match, leaves at 1.6·r and
         * reads a shade squarer beside the iOS app's screenshots.)
         */
        const val IosSmoothing = 0.528f
    }
}

/** Off: every continuous corner draws as a circular one. The rollback lever. */
internal const val CONTINUOUS_CORNERS = true

private class Frame(val k: Offset, val u: Offset, val v: Offset) {
    fun at(alongU: Float, alongV: Float) = Offset(k.x + u.x * alongU + v.x * alongV, k.y + u.y * alongU + v.y * alongV)
}

/** One corner's segment lengths, already fitted to the side it sits on. */
private class Fitted(val r: Float, val s: Float) {
    val p = (1f + s) * r
    private val arcDegrees = 90f * (1f - s)
    private val arcChord = sin(rad(arcDegrees / 2f)) * r * sqrt(2f)
    private val c: Float
    val d: Float
    private val b: Float
    private val a: Float

    init {
        val alpha = (90f - arcDegrees) / 2f
        val tangentRun = r * tan(rad(alpha / 2f))
        val beta = 45f * s
        c = tangentRun * cos(rad(beta))
        d = c * tan(rad(beta))
        b = (p - arcChord - c - d) / 3f
        a = 2f * b
    }

    /** Where the easing curve meets the arc, measured back along the arriving edge. */
    val arcStart get() = p - a - b - c
    val control1 get() = p - a
    val control2 get() = p - a - b
    val arcSweep get() = rad(arcDegrees)
}

private fun fit(radius: Float, smoothing: Float, half: Float): Fitted {
    if (radius <= 0f || half <= 0f) return Fitted(0f, 0f)
    val r = min(radius, half)
    // Shed smoothing before radius: the curve may only claim half the shortest side.
    val s = smoothing.coerceAtMost(half / r - 1f).coerceAtLeast(0f)
    return Fitted(r, s)
}

private fun corner(path: Path, f: Frame, c: Fitted) {
    if (c.r == 0f) {
        path.lineTo(f.k.x, f.k.y)
        return
    }
    val start = f.at(c.p, 0f)
    path.lineTo(start.x, start.y)
    // Ease off the arriving edge.
    val c1 = f.at(c.control1, 0f)
    val c2 = f.at(c.control2, 0f)
    val arcFrom = f.at(c.arcStart, c.d)
    path.cubicTo(c1.x, c1.y, c2.x, c2.y, arcFrom.x, arcFrom.y)
    // The circular part, as one cubic: exact to well under a pixel for <= 90°.
    val arcTo = f.at(c.d, c.arcStart)
    val centre = f.at(c.r, c.r)
    val handle = 4f / 3f * tan(c.arcSweep / 4f) * c.r
    val t1 = tangent(arcFrom, centre, arcTo)
    val t2 = tangent(arcTo, centre, arcFrom) * -1f
    path.cubicTo(
        arcFrom.x + t1.x * handle, arcFrom.y + t1.y * handle,
        arcTo.x - t2.x * handle, arcTo.y - t2.y * handle,
        arcTo.x, arcTo.y,
    )
    // And onto the leaving edge.
    val c3 = f.at(0f, c.control2)
    val c4 = f.at(0f, c.control1)
    val end = f.at(0f, c.p)
    path.cubicTo(c3.x, c3.y, c4.x, c4.y, end.x, end.y)
}

/** The unit tangent of the circle round [centre] at [point], pointing towards [toward]. */
private fun tangent(point: Offset, centre: Offset, toward: Offset): Offset {
    val radial = point - centre
    val length = radial.getDistance().coerceAtLeast(1e-6f)
    var t = Offset(-radial.y / length, radial.x / length)
    val travel = toward - point
    if (t.x * travel.x + t.y * travel.y < 0f) t *= -1f
    return t
}

private fun rad(degrees: Float) = degrees * (Math.PI.toFloat() / 180f)
