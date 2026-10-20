package me.shirazu.dshmizar.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.ui.MizarColors

/** The track's inset, and the segment corner that keeps its curve parallel to the track's. */
private val TrackInset = 2.dp
private val SegmentGap = 4.dp
private val SegmentShape = ContinuousCornerShape(concentric(Radius.sm, TrackInset))

/**
 * One choice of a few, as a track of segments -- the theme switch, and the index's
 * "all / unfinished" filter.
 *
 * The labels never break mid-word. Each is measured on one line; if they all fit side
 * by side the segments share the track equally, and if any would not -- a long
 * translation, or text at twice its size -- the same segments stack, one per line, in
 * the same order. The choice is the same control either way.
 *
 * Each segment is a radio button to accessibility services, inside one selectable
 * group, so TalkBack reads "System, selected, 1 of 3" rather than three loose buttons.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.sm)
            .border(Glass.Hairline, MaterialTheme.colorScheme.outlineVariant, Corner.sm)
            .padding(TrackInset)
            .selectableGroup(),
        content = {
            options.forEachIndexed { index, label ->
                Segment(label = label, selected = index == selected, onClick = { onSelect(index) })
            }
        },
        measurePolicy = SegmentedMeasurePolicy,
    )
}

/**
 * Side by side in equal shares when every label fits its share on one line, stacked
 * otherwise -- and the same answer to the intrinsic questions a parent may ask first
 * (a window sizes its column to its widest row, so it asks).
 */
private object SegmentedMeasurePolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val gap = SegmentGap.roundToPx()
        val n = measurables.size.coerceAtLeast(1)
        // Unbounded across (asked to size itself): its natural width, side by side.
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            naturalWidth(measurables, gap).coerceAtLeast(constraints.minWidth)
        }
        val share = share(width, n, gap)
        return if (fits(measurables, share)) {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(share)) }
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(width, height) {
                placeables.forEachIndexed { i, p -> p.place(i * (share + gap), (height - p.height) / 2) }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            val height = placeables.sumOf { it.height } + gap * (n - 1)
            layout(width, height) {
                var y = 0
                placeables.forEach { p -> p.place(0, y); y += p.height + gap }
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        naturalWidth(measurables, SegmentGap.roundToPx())

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.maxOfOrNull { it.minIntrinsicWidth(height) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        heightFor(measurables, width, SegmentGap.roundToPx())

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        heightFor(measurables, width, SegmentGap.roundToPx())

    private fun share(width: Int, n: Int, gap: Int) = ((width - gap * (n - 1)) / n).coerceAtLeast(0)

    private fun fits(measurables: List<IntrinsicMeasurable>, share: Int) =
        measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= share }

    /** Every segment as wide as the widest label needs, side by side. */
    private fun naturalWidth(measurables: List<IntrinsicMeasurable>, gap: Int): Int {
        val widest = measurables.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        return widest * measurables.size + gap * (measurables.size - 1).coerceAtLeast(0)
    }

    private fun heightFor(measurables: List<IntrinsicMeasurable>, width: Int, gap: Int): Int {
        val n = measurables.size.coerceAtLeast(1)
        if (width == Constraints.Infinity) return measurables.maxOfOrNull { it.maxIntrinsicHeight(Constraints.Infinity) } ?: 0
        val share = share(width, n, gap)
        return if (fits(measurables, share)) {
            measurables.maxOfOrNull { it.maxIntrinsicHeight(share) } ?: 0
        } else {
            measurables.sumOf { it.maxIntrinsicHeight(width) } + gap * (n - 1)
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, SegmentShape)
            .pressable(scale = 0.95f)
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = ShapedIndication(SegmentShape),
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (selected) MizarColors.OnAccent else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
