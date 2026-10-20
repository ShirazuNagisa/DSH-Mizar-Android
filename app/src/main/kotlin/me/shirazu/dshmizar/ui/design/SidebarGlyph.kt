package me.shirazu.dshmizar.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/**
 * How far the sidebar is out, 0 (closed) to 1 (open), as the shell's one number.
 * A lambda, read where it is used: a reader that only draws with it redraws as the
 * drawer moves and never recomposes. Negative values are the turn index's drawer.
 */
val LocalShellProgress = staticCompositionLocalOf<() -> Float> { { 0f } }

/**
 * The sidebar toggle's three lines, turning into a back arrow as the sidebar
 * comes out -- by the drawer's own number, so a drag scrubs it, a tap plays it,
 * and a settle lands it, all without a second animation to fall out of step.
 *
 * Each line's ends travel in a straight line between the two drawings: the outer
 * lines swing in to meet at the middle line's left end and shorten into the
 * arrowhead, and the middle line becomes its shaft. Stroke width and bounds never
 * change, so every frame in between is still a legible glyph. Under Reduce Motion the
 * drawer snaps, and the glyph follows the same number.
 */
@Composable
fun SidebarGlyph(contentDescription: String, modifier: Modifier = Modifier) {
    val progress = LocalShellProgress.current
    val ink = LocalContentColor.current
    Canvas(modifier.size(24.dp).semantics { this.contentDescription = contentDescription }) {
        val t = progress().coerceIn(0f, 1f)
        val u = size.width / 24f
        val stroke = 2.dp.toPx()
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, cx0: Float, cy0: Float, cx1: Float, cy1: Float) =
            drawLine(
                color = ink,
                start = Offset(lerp(x0, cx0, t) * u, lerp(y0, cy0, t) * u),
                end = Offset(lerp(x1, cx1, t) * u, lerp(y1, cy1, t) * u),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        // Three lines (x 4..20 at y 7, 12, 17) -> an arrow pointing back toward the
        // card's resting place, its point at (5, 12).
        line(4f, 7f, 20f, 7f, 5f, 12f, 11.5f, 5.5f)
        line(4f, 12f, 20f, 12f, 5f, 12f, 19f, 12f)
        line(4f, 17f, 20f, 17f, 5f, 12f, 11.5f, 18.5f)
    }
}
