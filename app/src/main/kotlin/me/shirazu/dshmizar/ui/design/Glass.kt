package me.shirazu.dshmizar.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Panels, not glass.
 *
 * The console draws its floating controls the way it draws everything else: a
 * flat fill one step up the surface ladder, a hairline stroke, no shadow. The
 * `glass*` names survive because the call sites picked them; what they mean
 * now is "a control that floats above content".
 */
object Glass {

    /** The fill under a panel: solid, one step up from the ground beneath it. */
    @Composable
    fun fill(tint: Color? = null): Color =
        tint?.copy(alpha = 0.08f) ?: MaterialTheme.colorScheme.surfaceContainerHigh

    /** The hairline stroke around a panel. */
    @Composable
    fun strokeBrush(): Brush {
        val line = MaterialTheme.colorScheme.outlineVariant
        return Brush.verticalGradient(listOf(line, line))
    }
}

/** A rounded-rectangle panel. [translucent] lets the content behind breathe
 *  through the fill, for the one panel that always floats over a transcript:
 *  the composer. */
@Composable
fun Modifier.glassCard(
    radius: Dp = Radius.card,
    tint: Color? = null,
    tintedBorder: Color? = null,
    translucent: Boolean = false,
): Modifier {
    val shape: Shape = RoundedCornerShape(radius)
    val fill = Glass.fill(tint).let { if (translucent) it.copy(alpha = 0.72f) else it }
    val borderBrush = if (tintedBorder != null) {
        Brush.verticalGradient(listOf(tintedBorder.copy(alpha = 0.35f), tintedBorder.copy(alpha = 0.20f)))
    } else {
        Glass.strokeBrush()
    }
    return this
        .clip(shape)
        .background(fill)
        .border(1.dp, borderBrush, shape)
}

/** A capsule panel. */
@Composable
fun Modifier.glassCapsule(tint: Color? = null): Modifier {
    val shape = CircleShape
    return this
        .clip(shape)
        .background(Glass.fill(tint))
        .border(1.dp, Glass.strokeBrush(), shape)
}

/**
 * The circular buttons for the sidebar toggle, refresh, jump-to-latest and
 * close: 40/42/44/54 dp.
 */
@Composable
fun GlassCircleLabel(
    size: Dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .glassCapsule()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
