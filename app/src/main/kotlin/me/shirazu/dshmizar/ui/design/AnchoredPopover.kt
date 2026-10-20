package me.shirazu.dshmizar.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * A small window that grows out of the control that was tapped -- the console's
 * answer to iOS `Design/Popover.swift`, whose rule carries over word for word:
 * everything that opens from a tap is a window out of that tap, not a sheet
 * that takes the screen. A sheet says "you are somewhere else now"; a window
 * out of the button says "this is about the thing you just touched", and a tap
 * anywhere else puts it away.
 *
 * Compose has no popover, so this is one: a [Popup] placed by [AnchoredPlacement],
 * which follows Material's dropdown rules exactly -- below the control, else above
 * it, else centred on it, else against the window, always clear of a 48 dp margin
 * top and bottom -- so the windows open where the menus they replace opened. It
 * wears the console's clothes: a hairline stroke, the medium corner, and a fill
 * that lets the work behind it breathe through, like the composer it floats beside.
 *
 * [width] may be [Dp.Unspecified]: the window then takes a dropdown menu's measure --
 * as wide as its widest row, and Material's 8 dp of padding top and bottom with
 * none of the windows' own trailing breath under the last row.
 *
 * Mount it inside the `Box` that wraps the trigger -- the anchor is the parent.
 */
@Composable
fun AnchoredPopover(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    width: Dp = PopoverSize.LIST_WIDTH,
    maxHeight: Dp = PopoverSize.LIST_HEIGHT,
    // The window grows out of its control; a small negative pull keeps it on
    // its leash instead of hovering a hand's breadth away from it.
    offset: DpOffset = DpOffset(0.dp, (-4).dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    // The popup outlives `expanded` by its exit, and only by its exit: it is mounted
    // while open or while the fade out runs, and a reopen during that fade takes the
    // same popup back rather than stacking a second one.
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    if (!visibility.currentState && !visibility.targetState && visibility.isIdle) return

    val density = LocalDensity.current
    // Which corner of the window faces the control, so the window grows out of it.
    var origin by remember { mutableStateOf(TransformOrigin(0f, 0f)) }
    val placement = remember(offset, density) {
        AnchoredPlacement(offset, density) { anchor, window ->
            origin = growthOrigin(anchor, window)
        }
    }
    Popup(
        // Dismissal is the caller's at once; the fade that follows is decoration and
        // never holds the decision up.
        onDismissRequest = onDismiss,
        popupPositionProvider = placement,
        // A window on its way out gives up focus at once: Back and the next tap
        // belong to the screen again, not to a window that is already leaving.
        properties = PopupProperties(focusable = expanded),
    ) {
        val reduceMotion = LocalReduceMotion.current
        val rise = with(density) { GrowthTravel.toPx() }
        val transition = rememberTransition(visibility, label = "popover")
        val progress by transition.animateFloat(
            transitionSpec = {
                when {
                    !targetState -> tween(ExitMillis, easing = FastOutLinearInEasing)
                    reduceMotion -> Motion.fade()
                    else -> Motion.bouncy()
                }
            },
            label = "popoverGrowth",
        ) { open -> if (open) 1f else 0f }
        val alpha by transition.animateFloat(
            transitionSpec = {
                when {
                    !targetState -> tween(ExitMillis, easing = FastOutLinearInEasing)
                    reduceMotion -> Motion.fade()
                    else -> Motion.smooth()
                }
            },
            label = "popoverAlpha",
        ) { open -> if (open) 1f else 0f }
        PopoverSurface(
            modifier = modifier,
            width = width,
            maxHeight = maxHeight,
            title = title,
            layer = Modifier
                // Nor can a leaving window be chosen from: its rows stop answering.
                .then(if (expanded) Modifier else Modifier.pointerInput(Unit) { swallowTouches() })
                .graphicsLayer {
                this.alpha = alpha.coerceIn(0f, 1f)
                if (!reduceMotion) {
                    transformOrigin = origin
                    val s = GrowthFrom + (1f - GrowthFrom) * progress
                    scaleX = s
                    scaleY = s
                    // Out of the control: a window below it drops from above, one
                    // above it rises from below.
                    translationY = (1f - progress) * rise * (if (origin.pivotFractionY < 0.5f) -1f else 1f)
                }
            },
            content = content,
        )
    }
}

/** Takes every touch before the content sees it. */
private suspend fun PointerInputScope.swallowTouches() {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/** How small the window starts, and how far it travels, growing out of its control. */
private const val GrowthFrom = 0.92f
private val GrowthTravel = 7.dp

/** The exit: quick and plain, so a dismissed window is gone, not leaving -- the same
 *  under Reduce Motion, where it is already only a fade. */
private const val ExitMillis = 140

/**
 * The window corner nearest the control: a window below its control grows from its
 * top edge, one above it from its bottom; across, from whichever side it shares
 * with the control.
 */
private fun growthOrigin(anchor: IntRect, window: IntRect): TransformOrigin {
    val y = if (window.top >= anchor.center.y) 0f else 1f
    val anchorX = anchor.center.x.coerceIn(window.left, window.right)
    val x = if (window.width == 0) 0.5f else (anchorX - window.left).toFloat() / window.width
    return TransformOrigin(x.coerceIn(0f, 1f), y)
}

/** The window itself: what a dropdown menu draws, in the console's clothes. */
@Composable
private fun PopoverSurface(
    modifier: Modifier,
    width: Dp,
    maxHeight: Dp,
    title: String?,
    layer: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = layer,
        shape = Corner.md,
        // Translucent, but only just: the work behind shows as a breath, not as
        // words competing with the window's own (the overlay strength, see Glass).
        color = Glass.overlayFill(),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = modifier
                .then(if (width == Dp.Unspecified) Modifier else Modifier.width(width))
                .heightIn(max = maxHeight)
                .padding(vertical = 8.dp)
                .width(IntrinsicSize.Max)
                .verticalScroll(rememberScrollState()),
        ) {
            PopoverBody(title = title, trailingBreath = width != Dp.Unspecified, content = content)
        }
    }
}

@Composable
private fun ColumnScope.PopoverBody(
    title: String?,
    trailingBreath: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (title != null) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
        )
    }
    content()
    if (trailingBreath) Spacer(Modifier.height(8.dp))
}

/**
 * Where the window goes, by Material's own dropdown rules (material3 1.3.1,
 * `DropdownMenuPositionProvider`), re-stated so the windows land exactly where the
 * menus they replace did:
 *
 *  - across: the window's start on the control's start, else its end on the
 *    control's end, else against the window edge on the control's side;
 *  - down: the window's top on the control's bottom, else its bottom on the
 *    control's top, else its centre on the control's top, else against the window
 *    edge on the control's half -- each tried only if it stays [VerticalMargin]
 *    clear of the window's top and bottom.
 *
 * [offset] nudges the anchored candidates (mirrored across in right-to-left), not
 * the window-edge ones. [onPlaced] reports the control and the window, in window
 * coordinates, for the motion that grows the window out of the control.
 */
internal class AnchoredPlacement(
    offset: DpOffset,
    density: Density,
    private val onPlaced: (anchor: IntRect, window: IntRect) -> Unit = { _, _ -> },
) : PopupPositionProvider {
    private val dx = with(density) { offset.x.roundToPx() }
    private val dy = with(density) { offset.y.roundToPx() }
    private val margin = with(density) { VerticalMargin.roundToPx() }

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val ltr = layoutDirection == LayoutDirection.Ltr
        val nudge = if (ltr) dx else -dx
        val startToStart = (if (ltr) anchorBounds.left else anchorBounds.right - w) + nudge
        val endToEnd = (if (ltr) anchorBounds.right - w else anchorBounds.left) + nudge
        val toEdge = edge(w, windowSize.width, 0, alignEnd = anchorBounds.center.x >= windowSize.width / 2)
        val xs = intArrayOf(startToStart, endToEnd, toEdge)
        val x = xs.firstOrNull { it >= 0 && it + w <= windowSize.width } ?: xs.last()

        val belowAnchor = anchorBounds.bottom + dy
        val aboveAnchor = anchorBounds.top - h + dy
        val centredOnTop = anchorBounds.top - half(h) + dy
        val toWindow = edge(h, windowSize.height, margin, alignEnd = anchorBounds.center.y >= windowSize.height / 2)
        val ys = intArrayOf(belowAnchor, aboveAnchor, centredOnTop, toWindow)
        val y = ys.dropLast(1).firstOrNull { it >= margin && it + h <= windowSize.height - margin } ?: ys.last()

        onPlaced(anchorBounds, IntRect(IntOffset(x, y), popupContentSize))
        return IntOffset(x, y)
    }

    /** Against the near or far edge of [space], held [margin] clear, or centred if it cannot be. */
    private fun edge(size: Int, space: Int, margin: Int, alignEnd: Boolean): Int {
        if (size >= space - 2 * margin) return half(space - size)
        val aligned = if (alignEnd) space - size else 0
        return aligned.coerceIn(margin, space - margin - size)
    }

    /** Half, rounded the way Compose's `Alignment` rounds a centre. */
    private fun half(value: Int): Int = (value / 2f).roundToInt()

    private companion object {
        /** Material's `MenuVerticalMargin`. */
        val VerticalMargin = 48.dp
    }
}

/**
 * One row in a popover, measured like the workspace rows it sits beside: the
 * same compact height and quiet padding, not the hand-span ListItem that made
 * the first windows read like dialogs.
 */
@Composable
fun PopoverRow(
    title: String,
    subtitle: String? = null,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** A section label inside a popover, at the workspace label's scale. */
@Composable
fun PopoverSection(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** The sizes the windows come in. Narrower than the iOS ladder: a phone's
 *  window borrows the workspace's proportions, not a tablet's. */
object PopoverSize {
    val COMPACT_WIDTH: Dp = 272.dp
    val COMPACT_HEIGHT: Dp = 340.dp
    val LIST_WIDTH: Dp = 292.dp
    val LIST_HEIGHT: Dp = 420.dp
    val TALL_WIDTH: Dp = 320.dp
    val TALL_HEIGHT: Dp = 560.dp
}
