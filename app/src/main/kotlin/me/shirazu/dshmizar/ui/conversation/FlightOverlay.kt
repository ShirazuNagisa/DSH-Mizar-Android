package me.shirazu.dshmizar.ui.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.sin

/**
 * A send, travelling -- ported from iOS `Views/Conversation/FlightOverlay.swift`.
 *
 * When the operator sends, the words lift out of the field and fly up towards
 * the place their bubble will take, turning from typed text into a sent message
 * on the way. It is the one moment in the app that is pure motion, and it earns
 * its place: it says "that went" at the instant of the tap, a round trip before
 * the host can say so.
 *
 * The Android port is honest about being a promise but simpler about the
 * landing: the host's own copy, read back a moment later, is the proof the
 * message arrived; the ghost only announces the send. Under Reduce Motion the
 * flight is skipped entirely -- the send is as instant as it ever was.
 */

/** Where the composer's field is, read once at the moment of a send. */
internal object ComposerGeometry {
    /** The text field, in root coordinates. */
    var fieldBounds: Rect? = null

    /** The top edge of the composer slab, in root coordinates. */
    var slabTop: Float? = null
}

/** One send in flight. */
internal data class FlightSpec(val text: String, val from: Rect, val key: Long)

/** Critically damped: the trip arrives without overshooting the bubble's place. */
private val FlightSpring = spring<Float>(dampingRatio = 1f, stiffness = 300f)

/**
 * The words in flight: from the field's leading edge, arcing up and out, into
 * the row above the composer where the host's copy will land.
 */
@Composable
internal fun FlightGhost(
    flight: FlightSpec,
    overlaySize: IntSize,
    overlayOrigin: Offset,
    onDone: () -> Unit,
) {
    // Measured before it moves, so the text does not reflow mid-air and the
    // destination is the bubble's own size.
    var size by remember(flight.key) { mutableStateOf(IntSize.Zero) }
    // Settled means landed: the threshold is a thousandth of the trip, well under a
    // pixel, so the ghost hands over at its destination and never a few points short.
    val progress = remember(flight.key) { Animatable(0f, visibilityThreshold = 0.001f) }
    LaunchedEffect(flight.key, size) {
        if (size == IntSize.Zero) return@LaunchedEffect
        // A spring, not a fixed 550 ms: most of the trip happens at once, so the
        // words leave the field with the tap, and the landing eases in on its own.
        progress.animateTo(1f, FlightSpring)
        onDone()
    }

    val density = LocalDensity.current
    val arc = with(density) { 18.dp.toPx() }
    val margin = with(density) { 18.dp.toPx() }
    val gap = with(density) { 8.dp.toPx() }

    val bubbleW = size.width.toFloat()
    val bubbleH = size.height.toFloat()
    val startX = flight.from.left - overlayOrigin.x
    val startY = flight.from.bottom - overlayOrigin.y - bubbleH
    val endX = overlaySize.width - bubbleW - margin
    val endY = (ComposerGeometry.slabTop ?: overlaySize.height.toFloat()) - overlayOrigin.y - bubbleH - gap

    // The bubble it becomes is at most the conversation's width less its margins; a
    // ghost measured against the whole overlay came out wider than its destination,
    // and a long send landed with its left edge off the screen.
    val maxWidth = with(density) { (overlaySize.width - 2 * margin).coerceAtLeast(0f).toDp() }
    Box(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .onSizeChanged { if (size == IntSize.Zero) size = it }
            // Every frame of the flight is drawn, never laid out: progress is read here,
            // in the layer, so the trip recomposes nothing.
            .graphicsLayer {
                val p = progress.value.coerceIn(0f, 1f)
                // A little arc: up and out first, then down into place.
                translationX = startX + (endX - startX) * p
                translationY = startY + (endY - startY) * p - sin(p * Math.PI).toFloat() * arc
                transformOrigin = TransformOrigin(0f, 1f)
                val s = 0.86f + 0.14f * p
                scaleX = s
                scaleY = s
                alpha = if (size == IntSize.Zero) 0f else min(1f, p * 4f)
            },
    ) {
        SentBubbleBody(text = flight.text, tail = true)
    }
}
