package me.shirazu.dshmizar.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
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
import kotlinx.coroutines.launch
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
 *
 * The selected fill is one piece, drawn behind the segments where the selected one
 * was placed: a tap sends it across (or down, stacked) to the new choice; a choice
 * made anywhere else lands in place; Reduce Motion fades it in.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Where each segment was placed, for the fill to find: written as they are
    // placed, read only as the fill draws.
    var placed by remember { mutableStateOf(emptyList<Rect>()) }
    val policy = remember { SegmentedMeasurePolicy { if (it != placed) placed = it } }
    val highlight = rememberTravelingHighlight()
    val reduceMotion = LocalReduceMotion.current
    val travelScope = rememberCoroutineScope()
    var lastSelected by remember { mutableIntStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected == lastSelected) return@LaunchedEffect
        val from = placed.getOrNull(lastSelected)?.let(highlight::place)
        val to = placed.getOrNull(selected)
        lastSelected = selected
        travelScope.launch { highlight.moveTo(from, to, reduceMotion) }
    }
    val fill = MaterialTheme.colorScheme.primary
    Layout(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.sm)
            .border(Glass.Hairline, MaterialTheme.colorScheme.outlineVariant, Corner.sm)
            .padding(TrackInset)
            .drawBehind {
                val target = placed.getOrNull(selected) ?: return@drawBehind
                val r = highlight.place(target)
                clipRect {
                    translate(r.left, r.top) {
                        drawOutline(SegmentShape.createOutline(r.size, layoutDirection, this), fill, alpha = highlight.alpha)
                    }
                }
            }
            .selectableGroup(),
        content = {
            options.forEachIndexed { index, label ->
                Segment(
                    label = label,
                    selected = index == selected,
                    onClick = {
                        highlight.arm()
                        onSelect(index)
                    },
                )
            }
        },
        measurePolicy = policy,
    )
}

/**
 * Side by side in equal shares when every label fits its share on one line, stacked
 * otherwise -- and the same answer to the intrinsic questions a parent may ask first
 * (a window sizes its column to its widest row, so it asks).
 */
private class SegmentedMeasurePolicy(private val onPlaced: (List<Rect>) -> Unit) : MeasurePolicy {
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
                val rects = placeables.mapIndexed { i, p ->
                    val x = i * (share + gap)
                    val y = (height - p.height) / 2
                    p.place(x, y)
                    Rect(x.toFloat(), y.toFloat(), (x + p.width).toFloat(), (y + p.height).toFloat())
                }
                onPlaced(rects)
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            val height = placeables.sumOf { it.height } + gap * (n - 1)
            layout(width, height) {
                var y = 0
                onPlaced(
                    placeables.map { p ->
                        p.place(0, y)
                        Rect(0f, y.toFloat(), p.width.toFloat(), (y + p.height).toFloat()).also { y += p.height + gap }
                    },
                )
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
    // The ink changes with the fill passing under it, not ahead of it.
    val ink by animateColorAsState(
        if (selected) MizarColors.OnAccent else MaterialTheme.colorScheme.onSurfaceVariant,
        Motion.pick(Motion.smooth()),
        label = "segmentInk",
    )
    Box(
        modifier = Modifier
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
            color = ink,
            textAlign = TextAlign.Center,
        )
    }
}
