package me.shirazu.dshmizar.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.ui.LocalMizarDark

/**
 * Glass, for what floats above the content -- the composer, the dock of pending
 * requests, the sidebar's controls, the jump-to-latest pill, the windows -- and
 * for nothing else: the transcript and its cards stay solid. iOS draws these with
 * Liquid Glass; here glass is a translucent fill, a hairline, and a faint light
 * along the top edge. No blur, no shadow, no elevation.
 *
 * The fill comes in two strengths, and the numbers are contrast, not taste
 * (worst case composited, text >= 4.5:1, icons >= 3:1; measured for this round):
 *
 *   resting  a control in the layout flow, over the screen's own ground -- the
 *            composer, the dock, the sidebar's buttons. Passes from 0.50; drawn at
 *            0.78 light / 0.72 dark.
 *   overlay  a surface that floats over the transcript itself -- the windows.
 *            Text over text ink is the worst case: 0.95 light / 0.94 dark is the
 *            least that keeps a subtitle at 4.5:1.
 *
 * Anything that later moves over content (the deferred content-under-bars pass)
 * moves to the overlay strength with it.
 *
 * The camera is the one ground the theme does not own: over the viewfinder the
 * glass is always dark ([camera]), whatever the theme, under white ink.
 */
object Glass {
    private const val RestingLight = 0.78f
    private const val RestingDark = 0.72f
    private const val OverlayLight = 0.95f
    private const val OverlayDark = 0.94f

    /** The panel stroke: the hairline every glass surface is drawn with. */
    val Hairline: Dp = 1.dp

    /** The fill under an in-flow control, optionally washed with a state's [tint]. */
    @Composable
    fun fill(tint: Color? = null): Color {
        val base = MaterialTheme.colorScheme.surfaceContainerHigh
            .copy(alpha = if (LocalMizarDark.current) RestingDark else RestingLight)
        return tint?.copy(alpha = 0.08f)?.compositeOver(base) ?: base
    }

    /** The fill under a surface that floats over the transcript. */
    @Composable
    fun overlayFill(): Color = MaterialTheme.colorScheme.surfaceContainerHigh
        .copy(alpha = if (LocalMizarDark.current) OverlayDark else OverlayLight)

    /** Over the camera: dark in both themes, so white ink reads over a white frame (5.7:1). */
    val camera: Color = Color.Black.copy(alpha = 0.60f)

    /** The hairline stroke around a panel. */
    @Composable
    fun strokeBrush(): Brush {
        val line = MaterialTheme.colorScheme.outlineVariant
        return Brush.verticalGradient(listOf(line, line))
    }

    /**
     * The light along the top edge: a second hairline over the first, brightest at
     * the top and gone by a third of the way down, so the panel reads as lit from
     * above rather than outlined.
     */
    @Composable
    fun rimBrush(): Brush {
        val rim = Color.White.copy(alpha = if (LocalMizarDark.current) 0.08f else 0.50f)
        return Brush.verticalGradient(0f to rim, 0.35f to Color.Transparent)
    }
}

/** A rounded-rectangle panel of glass.
 *
 *  The panel paints its shape rather than clipping to it; pass [clip] only when
 *  the content really reaches the corners. A clickable panel takes its press
 *  highlight from `shapedClickable` with the same radius. */
@Composable
fun Modifier.glassCard(
    radius: Dp = Radius.card,
    tint: Color? = null,
    tintedBorder: Color? = null,
    clip: Boolean = false,
): Modifier {
    val shape: Shape = ContinuousCornerShape(radius)
    val borderBrush = if (tintedBorder != null) {
        Brush.verticalGradient(listOf(tintedBorder.copy(alpha = 0.35f), tintedBorder.copy(alpha = 0.20f)))
    } else {
        Glass.strokeBrush()
    }
    return this
        .then(if (clip) Modifier.clip(shape) else Modifier)
        .background(Glass.fill(tint), shape)
        .border(Glass.Hairline, borderBrush, shape)
        .border(Glass.Hairline, Glass.rimBrush(), shape)
}

/** A capsule of glass. [fill] replaces the themed fill -- [Glass.camera] over the viewfinder. */
@Composable
fun Modifier.glassCapsule(tint: Color? = null, fill: Color? = null): Modifier {
    val shape = CircleShape
    return this
        .background(fill ?: Glass.fill(tint), shape)
        .border(Glass.Hairline, Glass.strokeBrush(), shape)
        .border(Glass.Hairline, Glass.rimBrush(), shape)
}

/**
 * The round glass buttons: the sidebar's refresh and settings, the scanner's
 * close. Sized to their place (38/44/54 dp); a smaller one still answers a
 * 48 dp touch, because Compose extends the hit area of any pointer target below
 * the minimum touch size without changing its layout.
 */
@Composable
fun GlassCircleLabel(
    size: Dp,
    onClick: (() -> Unit)? = null,
    fill: Color? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .glassCapsule(fill = fill)
            .then(
                if (onClick != null) {
                    Modifier.pressable(scale = 0.92f).shapedClickable(CircleShape, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
