package me.shirazu.dshmizar.ui.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.Format
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.LocalReduceMotion
import me.shirazu.dshmizar.ui.design.Radius
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.mizar.kit.ConversationItem

/**
 * The voices of a conversation, ported from iOS `Views/ConversationView.swift`.
 *
 * Two voices, drawn differently on purpose. The operator's words are a bubble
 * in the accent, on the right: short, theirs, and easy to find when scrolling
 * back for "what did I ask". The agent's answer is not a bubble at all -- it is
 * the page, full width, set like text to be read, because that is what it is.
 */

/** One item, drawn by kind. */
@Composable
internal fun ConversationItemView(
    item: ConversationItem,
    isLastPrompt: Boolean,
    onCopy: (String) -> Unit,
) {
    when (item) {
        is ConversationItem.Message -> when (item.role) {
            ConversationItem.Role.USER -> UserBubble(item.text, tail = isLastPrompt, onCopy = onCopy)
            ConversationItem.Role.ASSISTANT -> AgentText(item.text, onCopy = onCopy)
            ConversationItem.Role.SYSTEM -> SystemNote(item.text, onCopy = onCopy)
        }

        is ConversationItem.Activity -> ActivityRow(item)
        is ConversationItem.Failure -> FailureCard(item)
    }
}

/** Selectable text: blocks when the body is markdown, one run of text otherwise. */
@Composable
private fun MessageText(
    text: String,
    color: Color,
    codeColor: Color,
    linkColor: Color,
    onCopy: (String) -> Unit,
    style: androidx.compose.ui.text.TextStyle,
) {
    if (MarkdownBlocks.looksBlocky(text)) {
        MarkdownMessage(text, color = color, codeColor = codeColor, linkColor = linkColor, onCopy = onCopy)
    } else {
        Text(text, style = style, color = color)
    }
}

/** The operator's words: a gradient bubble on the right, with the quiet tail. */
@Composable
private fun UserBubble(text: String, tail: Boolean, onCopy: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1f).widthIn(min = 56.dp))
        SelectionContainer {
            SentBubbleBody(text = text, tail = tail, onCopy = onCopy)
        }
    }
}

/**
 * The look of a sent message, shared by the bubble in the conversation and the
 * ghost that flies there -- iOS `SentBubbleBody`.
 */
@Composable
internal fun SentBubbleBody(
    text: String,
    tail: Boolean = true,
    onCopy: (String) -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MizarColors.OnAccent
    // The corner nearest the answer tightens -- the tail that says "this is
    // the message that was answered".
    val shape = RoundedCornerShape(
        topStart = Radius.lg,
        topEnd = Radius.lg,
        bottomEnd = if (tail) 6.dp else Radius.lg,
        bottomStart = Radius.lg,
    )
    Box(
        modifier = Modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(accent, accent.scaled(0.80f))))
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        MessageText(
            text = text,
            color = ink,
            codeColor = ink,
            linkColor = ink,
            onCopy = onCopy,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The agent's answer: the page, not a bubble. */
@Composable
private fun AgentText(text: String, onCopy: (String) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        // A readable measure on a wide screen; edge to edge on a phone.
        Box(modifier = Modifier.widthIn(max = 680.dp)) {
            SelectionContainer {
                MessageText(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurface,
                    codeColor = MaterialTheme.colorScheme.onSurface,
                    linkColor = MaterialTheme.colorScheme.primary,
                    onCopy = onCopy,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.15,
                    ),
                )
            }
        }
    }
}

/** What the system said, as an aside. */
@Composable
private fun SystemNote(text: String, onCopy: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.system_label).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.xs))
        SelectionContainer {
            MessageText(
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                codeColor = MaterialTheme.colorScheme.onSurface,
                linkColor = MaterialTheme.colorScheme.primary,
                onCopy = onCopy,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** An activity marker: a dot, what happened, and when. */
@Composable
private fun ActivityRow(item: ConversationItem.Activity) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
        Spacer(Modifier.width(Space.sm))
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        item.atMillis?.let {
            Text(
                Format.clockTime(it),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A turn that produced nothing -- the most prominent treatment of anything
 * here, because it is the one state where the agent looks busy and is in fact
 * doing nothing. Shakes once when it first comes on screen: two swings of four
 * points, the "no" of a head being shaken. A failure scrolled back into view is
 * news nobody needs announced twice.
 */
@Composable
private fun FailureCard(item: ConversationItem.Failure) {
    val shake = remember { Animatable(0f) }
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(item.id) {
        val at = item.atMillis
        if (!reduceMotion && at != null && System.currentTimeMillis() - at < 20_000) {
            shake.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 450
                    4f at 70
                    -4f at 170
                    3f at 270
                    -2f at 360
                    0f at 450
                },
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(shake.value.roundToInt(), 0) }
            .clip(RoundedCornerShape(Radius.md))
            .background(MizarColors.Failed.copy(alpha = 0.09f))
            .border(1.dp, MizarColors.Failed.copy(alpha = 0.28f), RoundedCornerShape(Radius.md))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MizarColors.Failed,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Space.sm))
            Text(
                stringResource(R.string.turn_failed),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MizarColors.Failed,
            )
        }
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(item.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A colour pulled towards black, for the bubble's gradient bottom. */
private fun Color.scaled(factor: Float): Color = Color(red * factor, green * factor, blue * factor, alpha)

/**
 * A band of light sweeping across, for text that is still being worked on --
 * iOS `.shimmering()`. Drawn over the content with `SrcAtop`, so only the
 * glyphs catch the light and what a screen reader sees is untouched.
 */
@Composable
internal fun Modifier.shimmering(active: Boolean = true): Modifier {
    if (!active || LocalReduceMotion.current) return this
    val accent = MaterialTheme.colorScheme.primary
    val phase by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "shimmerPhase",
    )
    return drawWithContent {
        drawContent()
        val band = size.width * 0.45f
        if (band <= 0f) return@drawWithContent
        val x = -band + phase * (size.width + band)
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.35f to accent.copy(alpha = 0.55f),
                0.5f to Color.White.copy(alpha = 0.5f),
                0.65f to accent.copy(alpha = 0.55f),
                1f to Color.Transparent,
                startX = x,
                endX = x + band,
            ),
            blendMode = BlendMode.SrcAtop,
        )
    }
}
