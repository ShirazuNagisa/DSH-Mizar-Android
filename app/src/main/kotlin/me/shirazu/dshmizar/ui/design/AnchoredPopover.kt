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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * [width] may be [Dp.Unspecified]: the window then takes its widest row, as a
 * dropdown menu does.
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
    if (!expanded) return
    val density = LocalDensity.current
    val placement = remember(offset, density) { AnchoredPlacement(offset, density) }
    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = placement,
        properties = PopupProperties(focusable = true),
    ) {
        PopoverSurface(modifier = modifier, width = width, maxHeight = maxHeight, title = title, content = content)
    }
}

/** The window itself: what a dropdown menu draws, in the console's clothes. */
@Composable
private fun PopoverSurface(
    modifier: Modifier,
    width: Dp,
    maxHeight: Dp,
    title: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = Corner.md,
        // Translucent, but only just: the work behind shows as a breath, not as
        // words competing with the window's own.
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
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
            PopoverBody(title = title, content = content)
        }
    }
}

@Composable
private fun ColumnScope.PopoverBody(title: String?, content: @Composable ColumnScope.() -> Unit) {
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
    Spacer(Modifier.height(8.dp))
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
