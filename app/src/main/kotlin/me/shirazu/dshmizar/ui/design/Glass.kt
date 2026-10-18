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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.ui.LocalMizarDark

/**
 * Glass, approximated.
 *
 * The iOS app draws its floating controls in Liquid Glass; Android has no
 * system glass, and minSdk 26 rules out unconditional blur. The approximation
 * the design itself suggests: a translucent solid fill, a hairline highlight
 * stroke (light from above), and a soft shadow. Glass belongs to things that
 * float above content -- toolbars, the composer, the approval dock -- content
 * itself stays solid.
 */
object Glass {

    /** The fill under a glass surface. */
    @Composable
    fun fill(tint: Color? = null): Color {
        val base = MaterialTheme.colorScheme.surface
        val dark = LocalMizarDark.current
        return (tint?.copy(alpha = 0.08f) ?: base).let {
            if (tint != null) it else base.copy(alpha = if (dark) 0.66f else 0.72f)
        }
    }

    /** The hairline highlight stroke: light from above, stronger on dark. */
    @Composable
    fun strokeBrush(): Brush {
        val dark = LocalMizarDark.current
        val top = Color.White.copy(alpha = if (dark) 0.16f else 0.55f)
        val bottom = Color.White.copy(alpha = if (dark) 0.05f else 0.18f)
        return Brush.verticalGradient(listOf(top, bottom))
    }
}

/** A rounded-rectangle glass surface. */
@Composable
fun Modifier.glassCard(
    radius: Dp = Radius.card,
    tint: Color? = null,
    tintedBorder: Color? = null,
): Modifier {
    val shape: Shape = RoundedCornerShape(radius)
    val borderBrush = if (tintedBorder != null) {
        Brush.verticalGradient(listOf(tintedBorder.copy(alpha = 0.28f), tintedBorder.copy(alpha = 0.14f)))
    } else {
        Glass.strokeBrush()
    }
    return this
        .shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))
        .clip(shape)
        .background(Glass.fill(tint))
        .border(0.75.dp, borderBrush, shape)
}

/** A capsule glass surface. */
@Composable
fun Modifier.glassCapsule(tint: Color? = null): Modifier {
    val shape = CircleShape
    return this
        .shadow(6.dp, shape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.08f))
        .clip(shape)
        .background(Glass.fill(tint))
        .border(0.75.dp, Glass.strokeBrush(), shape)
}

/**
 * The circular glass buttons the iOS app uses for the sidebar toggle, refresh,
 * jump-to-latest and close: 40/42/44/54 pt.
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
