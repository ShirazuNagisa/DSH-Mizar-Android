package me.shirazu.dshmizar.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * here: a hairline stroke, the small corner, and a fill that lets the work
 * behind it breathe through, like the composer it floats beside.
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.84f),
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
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
            )
        }
        content()
        Spacer(Modifier.height(8.dp))
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
