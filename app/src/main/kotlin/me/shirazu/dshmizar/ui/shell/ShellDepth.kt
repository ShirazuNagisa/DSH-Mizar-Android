package me.shirazu.dshmizar.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/** Which edge of the shell a drawer comes from. */
internal enum class DrawerSide { LEFT, RIGHT }

/**
 * The shell's depth vocabulary.
 *
 * Depth here is made of four cheap ingredients rather than a 3D transform: a
 * shadow the lifted card casts, a contact shadow where that card meets the
 * drawer, a darker/recessed drawer surface, and one thin specular edge. All of
 * it is draw-phase work with fixed geometry; only opacity follows the finger.
 */

/** Elevation of the conversation card at a fully open drawer. */
private val CardLift = 22.dp

/** How far the card's contact shadow reaches onto a drawer. */
private val ContactWidth = 56.dp

/** The darkest part of the drawer's inner edge, where the card covers it. */
private val InnerDark = Color.Black.copy(alpha = 0.32f)

/** A card that still fills the frame: it lifts without shrinking away. */
internal fun cardScale(reveal: Float): Float = 1f - 0.006f * reveal.coerceIn(0f, 1f)

/**
 * Shadow cast by the lifted card.
 *
 * The elevation follows the drawer; only the shadow's colour alpha changes with
 * it. Compose's `shadow` draws from the shape outline rather than from the
 * layer's pixels, so a running shimmer or typewriter does not re-blur a
 * full-screen Gaussian under the drag.
 */
@Composable
internal fun Modifier.cardDepth(reveal: Float, dark: Boolean, shape: Shape): Modifier {
    val r = reveal.coerceIn(0f, 1f)
    if (r <= 0f) return this
    val tint = Color.Black.copy(alpha = if (dark) 0.35f * r else 0.14f * r)
    return shadow(
        elevation = CardLift * r,
        shape = shape,
        clip = false,
        ambientColor = tint,
        spotColor = tint,
    )
}

/**
 * The card's glass rim: a bright top edge falling to the ordinary outline
 * colour, so the lifted card reads as a lit pane rather than a cut-out.
 */
@Composable
internal fun Modifier.cardRim(reveal: Float, dark: Boolean, shape: Shape): Modifier {
    val r = reveal.coerceIn(0f, 1f)
    val top = if (dark) {
        Color.White.copy(alpha = 0.10f * r)
    } else {
        Color.White.copy(alpha = 0.22f * r)
    }
    return border(
        width = 1.dp,
        brush = Brush.verticalGradient(listOf(top, MaterialTheme.colorScheme.outlineVariant)),
        shape = shape,
    )
}

/**
 * A thin sheet of light over the card: strongest on the leading edge that
 * faces the open drawer. Drawn above the content, so it is a reflection on the
 * surface rather than a tint on the text.
 */
@Composable
internal fun Modifier.cardSheen(reveal: Float, dark: Boolean, side: DrawerSide): Modifier {
    val r = reveal.coerceIn(0f, 1f)
    if (r <= 0f) return this
    val light = if (dark) 0.035f else 0.030f
    val endPx = with(androidx.compose.ui.platform.LocalDensity.current) { 150.dp.toPx() }
    val edge = Color.White.copy(alpha = light * r)
    val near = Color.White.copy(alpha = 0.015f * r)
    val colors = if (side == DrawerSide.LEFT) listOf(edge, near, Color.Transparent)
    else listOf(Color.Transparent, near, edge)
    return background(Brush.horizontalGradient(colors = colors, startX = 0f, endX = endPx))
}

/**
 * The glass half of the depth: one soft, blurred strip of card-coloured light
 * spilling from the lifted card onto the drawer's inner edge. This is a real
 * blur of a static shape, not a backdrop capture -- the surface is a lit pane
 * reflecting its neighbour, which is the part a phone can afford every frame.
 *
 * Below API 31 there is no offscreen blur; the strip is skipped and the
 * gradient/contact-shadow half carries the depth on its own.
 */
@Composable
internal fun DrawerGlass(reveal: Float, dark: Boolean, side: DrawerSide, modifier: Modifier = Modifier) {
    val r = reveal.coerceIn(0f, 1f)
    if (r <= 0f) return
    val spill = if (dark) 0.07f else 0.10f
    val colours = if (side == DrawerSide.LEFT) {
        listOf(Color.Transparent, Color.White.copy(alpha = spill * r))
    } else {
        listOf(Color.White.copy(alpha = spill * r), Color.Transparent)
    }
    Box(modifier.fillMaxSize()) {
        val strip = Modifier
            .fillMaxHeight()
            .width(30.dp)
            .align(if (side == DrawerSide.LEFT) Alignment.CenterEnd else Alignment.CenterStart)
            .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier)
            .background(Brush.horizontalGradient(colours))
        Box(strip)
    }
}

/** The same spill, painted just inside the lifted card's leading edge. */
@Composable
internal fun CardGlass(reveal: Float, dark: Boolean, side: DrawerSide, modifier: Modifier = Modifier) {
    val r = reveal.coerceIn(0f, 1f)
    if (r <= 0f) return
    val spill = if (dark) 0.05f else 0.07f
    val colours = if (side == DrawerSide.LEFT) {
        listOf(Color.White.copy(alpha = spill * r), Color.Transparent)
    } else {
        listOf(Color.Transparent, Color.White.copy(alpha = spill * r))
    }
    Box(modifier.fillMaxSize()) {
        val strip = Modifier
            .fillMaxHeight()
            .width(22.dp)
            .align(if (side == DrawerSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
            .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(14.dp) else Modifier)
            .background(Brush.horizontalGradient(colours))
        Box(strip)
    }
}

/**
 * The drawer's recess: a scrim that takes it down a tone, an inner shadow along
 * the edge the card covers, and a top-down shade that makes the panel read as
 * set into the frame rather than pasted on it.
 */
internal fun DrawScope.drawerDepth(reveal: Float, dark: Boolean, side: DrawerSide) {
    val r = reveal.coerceIn(0f, 1f)
    if (r <= 0f) return
    val width = size.width

    // The whole page recedes a little behind the lifted card.
    drawRect(Color.Black.copy(alpha = (if (dark) 0.085f else 0.05f) * r))

    // Contact shadow at the inner edge: the card is the light source, so the
    // side of the drawer it covers is the darkest part.
    val contact = ContactWidth.toPx()
    val inner = InnerDark.copy(alpha = InnerDark.alpha * r)
    val startX = if (side == DrawerSide.LEFT) width - contact else 0f
    drawRect(
        brush = Brush.horizontalGradient(
            colors = if (side == DrawerSide.LEFT) listOf(Color.Transparent, inner) else listOf(inner, Color.Transparent),
            startX = startX,
            endX = startX + contact,
        ),
        topLeft = Offset(startX, 0f),
        size = Size(contact, size.height),
    )

    // Light falls from above: the top of the drawer carries a shade too.
    val topShade = 28.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Black.copy(alpha = (if (dark) 0.16f else 0.055f) * r),
                Color.Transparent,
            ),
            startY = 0f,
            endY = topShade,
        ),
        size = Size(width, topShade),
    )

    // One specular edge, on the side away from the card, plus a hairline at the
    // very top: the two cues that make a dark surface read as glass.
    val glass = Color.White.copy(alpha = (if (dark) 0.05f else 0.20f) * r)
    val edgeX = if (side == DrawerSide.LEFT) 0.5.dp.toPx() else width - 0.5.dp.toPx()
    drawLine(
        color = glass,
        start = Offset(edgeX, 0f),
        end = Offset(edgeX, size.height),
        strokeWidth = 1.dp.toPx(),
    )
    drawLine(
        color = glass.copy(alpha = glass.alpha * 0.65f),
        start = Offset(0f, 0.5.dp.toPx()),
        end = Offset(width, 0.5.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )
}

/** The standing right column on a wide screen: a groove beside the card. */
internal fun DrawScope.columnDepth(dark: Boolean) {
    val contact = ContactWidth.toPx()
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Black.copy(alpha = if (dark) 0.14f else 0.05f),
                Color.Transparent,
            ),
            startX = 0f,
            endX = contact,
        ),
        size = Size(contact, size.height),
    )
    val glass = Color.White.copy(alpha = if (dark) 0.055f else 0.25f)
    drawLine(
        color = glass,
        start = Offset(0f, 0.5.dp.toPx()),
        end = Offset(size.width, 0.5.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )
}
