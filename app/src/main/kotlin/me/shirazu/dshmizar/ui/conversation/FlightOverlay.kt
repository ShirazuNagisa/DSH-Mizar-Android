package me.shirazu.dshmizar.ui.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
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
    val progress = remember(flight.key) { Animatable(0f) }
    LaunchedEffect(flight.key, size) {
        if (size == IntSize.Zero) return@LaunchedEffect
        progress.animateTo(1f, tween(550, easing = EaseInOut))
        onDone()
    }

    val density = LocalDensity.current
    val arc = with(density) { 18.dp.toPx() }
    val margin = with(density) { 18.dp.toPx() }
    val gap = with(density) { 8.dp.toPx() }

    val p = progress.value
    val bubbleW = size.width.toFloat()
    val bubbleH = size.height.toFloat()
    val startX = flight.from.left - overlayOrigin.x
    val startY = flight.from.bottom - overlayOrigin.y - bubbleH
    val endX = overlaySize.width - bubbleW - margin
    val endY = (ComposerGeometry.slabTop ?: overlaySize.height.toFloat()) - overlayOrigin.y - bubbleH - gap

    // A little arc: up and out first, then down into place.
    val x = startX + (endX - startX) * p
    val y = startY + (endY - startY) * p - sin(p * Math.PI).toFloat() * arc

    Box(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .scale(0.86f + 0.14f * p)
            .alpha(if (size == IntSize.Zero) 0f else min(1f, p * 4f))
            .onSizeChanged { if (size == IntSize.Zero) size = it },
    ) {
        SentBubbleBody(text = flight.text, tail = true)
    }
}
