package me.shirazu.dshmizar.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A small window that grows out of the control that was tapped -- the console's
 * answer to iOS `Design/Popover.swift`, whose rule carries over word for word:
 * everything that opens from a tap is a window out of that tap, not a sheet
 * that takes the screen. A sheet says "you are somewhere else now"; a window
 * out of the button says "this is about the thing you just touched", and a tap
 * anywhere else puts it away.
 *
 * Compose has no popover; `DropdownMenu` is the one primitive that anchors to
 * its parent and flips to stay on screen, so it wears the console's clothes
 * here: a flat panel, a hairline stroke, the small corner.
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
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Radius.md),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.width(width).heightIn(max = maxHeight),
    ) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            )
        }
        content()
        Spacer(Modifier.height(12.dp))
    }
}

/** The sizes the windows come in, matching the iOS `PopoverSize` ladder. */
object PopoverSize {
    val COMPACT_WIDTH: Dp = 320.dp
    val COMPACT_HEIGHT: Dp = 380.dp
    val LIST_WIDTH: Dp = 340.dp
    val LIST_HEIGHT: Dp = 480.dp
    val TALL_WIDTH: Dp = 360.dp
    val TALL_HEIGHT: Dp = 620.dp
}
