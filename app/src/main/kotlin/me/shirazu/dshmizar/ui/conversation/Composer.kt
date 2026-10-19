package me.shirazu.dshmizar.ui.conversation

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.LocalReduceMotion
import me.shirazu.dshmizar.ui.design.Motion
import me.shirazu.dshmizar.ui.design.Radius
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.glassCard
import me.shirazu.dshmizar.ui.design.pressable

/**
 * Sending a prompt, and stopping one -- ported from iOS `Views/ComposerView.swift`
 * and `Views/ComposerBar.swift`.
 *
 * One slab of glass: the field on top, growing with the text, and along its
 * bottom edge the controls -- commands, the model, the context dial -- with the
 * one round button at the end. The button carries both actions, and which one
 * it carries is the whole state machine:
 *
 *   the session is running, and nothing has been typed   -> stop
 *   anything else                                        -> send
 *
 * One button that changes meaning keeps both one tap away; it changes shape as
 * it changes meaning, so the meaning is never a surprise.
 */
@Composable
internal fun Composer(
    state: UiState,
    draft: String,
    onDraft: (String) -> Unit,
    running: Boolean,
    onModel: () -> Unit,
    onContext: () -> Unit,
    onCommands: () -> Unit,
    commandsOpen: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** Reported upward so the interactions dock can tuck itself away while typing. */
    onFocusChanged: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(bottom = 6.dp)
            .onGloballyPositioned { ComposerGeometry.slabTop = it.boundsInRoot().top }
            .glassCard(radius = Radius.md)
            // A tap anywhere on the slab is a tap on the field.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusRequester.requestFocus() },
    ) {
        TextField(
            value = draft,
            onValueChange = onDraft,
            placeholder = {
                Text(
                    stringResource(R.string.composer_hint),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                )
            },
            maxLines = 8,
            // Enter sends, as it does on the desktop. A multi-line draft is still
            // possible -- the field grows -- but the key that people expect to submit
            // is the one that submits.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank()) onSend() }),
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { ComposerGeometry.fieldBounds = it.boundsInRoot() }
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    onFocusChanged(it.isFocused)
                }
                .padding(horizontal = 8.dp)
                .padding(top = 2.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement6,
        ) {
            ComposerBarRow(
                state = state,
                focused = focused,
                commandsOpen = commandsOpen,
                onModel = onModel,
                onContext = onContext,
                onCommands = onCommands,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            PrimaryButton(
                running = running,
                draftBlank = draft.isBlank(),
                onSend = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSend()
                },
                onStop = onStop,
            )
        }
    }
}

private val Arrangement6 = Arrangement.spacedBy(6.dp)

/** The controls along the bottom of the composer: commands, the model, context. */
@Composable
private fun ComposerBarRow(
    state: UiState,
    focused: Boolean,
    commandsOpen: Boolean,
    onModel: () -> Unit,
    onContext: () -> Unit,
    onCommands: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement6) {
        CommandButton(open = commandsOpen, onClick = onCommands)
        ModelPill(state = state, focused = focused, onClick = onModel, modifier = Modifier.weight(1f, fill = false))
        ContextPill(state = state, onClick = onContext)
    }
}

/** The host's own commands, behind a plus that turns into a close. */
@Composable
private fun CommandButton(open: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (open) 45f else 0f, Motion.pick(Motion.bouncy()), label = "plusSpin")
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Radius.sm))
            .pressable(scale = 0.9f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = stringResource(R.string.commands),
            modifier = Modifier.size(18.dp).rotate(rotation),
        )
    }
}

/** `deepseek-v4 · High`, as the host reports the route it is using. */
@Composable
private fun ModelPill(
    state: UiState,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selection = state.selectedSessionId?.let { state.modelChoice[it] }
    val label = run {
        val chosen = selection
            ?: state.stats?.model?.next
            ?: state.stats?.model?.lastUsed
            ?: state.modelCatalog?.defaultSelection
        // The button naming nothing but "Model" is the one thing it must never do.
        if (chosen == null) return@run stringResource(R.string.model)
        listOfNotNull(
            chosen.model,
            chosen.reasoningEffort?.replaceFirstChar { it.uppercase() },
        ).joinToString(" · ")
    }

    Row(
        modifier = modifier
            .height(32.dp)
            .animateContentSize(Motion.smooth())
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Radius.sm))
            .pressable()
            .clickable(onClick = onClick)
            .padding(horizontal = if (focused) 0.dp else 11.dp)
            .then(if (focused) Modifier.width(32.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Memory,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!focused) {
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The context dial: how full the window is, warming as it fills. */
@Composable
private fun ContextPill(state: UiState, onClick: () -> Unit) {
    val context = state.stats?.context
    val percent = context?.percent
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Radius.sm))
            .then(if (context != null) Modifier.pressable().clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContextRing(percent = percent)
        if (percent != null) {
            Spacer(Modifier.width(5.dp))
            Text(
                stringResource(R.string.context_percent, percent),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
    }
}

/**
 * The little occupancy dial -- iOS `ContextRing`.
 *
 * Its colour slides rather than switches: the accent while there is room,
 * warming to amber past three quarters and to red past nine tenths -- a window
 * that is nearly full is the thing that makes a session start summarising
 * itself. Past nine tenths it also breathes, once every few seconds.
 */
@Composable
internal fun ContextRing(
    percent: Int?,
    size: androidx.compose.ui.unit.Dp = 15.dp,
    lineWidth: androidx.compose.ui.unit.Dp = 2.4.dp,
) {
    val tint = when {
        percent == null -> MaterialTheme.colorScheme.onSurfaceVariant
        percent >= 90 -> MizarColors.Failed
        percent >= 75 -> MizarColors.Waiting
        else -> MaterialTheme.colorScheme.primary
    }
    val fraction = ((percent ?: 0).coerceIn(0, 100)) / 100f
    val animatedFraction by animateFloatAsState(fraction, Motion.pick(Motion.gentle()), label = "ringFill")

    val breathing = percent != null && percent >= 90 && !LocalReduceMotion.current
    val breath by if (breathing) {
        rememberInfiniteTransition(label = "ringBreath").animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = 3400
                    1f at 0
                    1f at 2600
                    1.2f at 2900
                    1f at 3400
                },
            ),
            label = "ringBreathScale",
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    Canvas(Modifier.size(size).scale(breath)) {
        val stroke = Stroke(width = lineWidth.toPx(), cap = StrokeCap.Round)
        // Track: the full circle, without the round cap showing at the seam.
        drawCircle(color = track, style = Stroke(width = lineWidth.toPx()))
        drawArc(
            color = tint,
            startAngle = -90f,
            sweepAngle = 360f * animatedFraction,
            useCenter = false,
            style = stroke,
        )
    }
}

/** The one round button: stop, send, or neither. */
@Composable
private fun PrimaryButton(
    running: Boolean,
    draftBlank: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val mode = when {
        running && draftBlank -> Mode.STOP
        !draftBlank -> Mode.SEND
        else -> Mode.IDLE
    }
    val fill = when (mode) {
        Mode.STOP -> MizarColors.Failed
        Mode.SEND -> MaterialTheme.colorScheme.primary
        Mode.IDLE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    val ink = when (mode) {
        Mode.STOP -> Color.White
        Mode.SEND -> MizarColors.OnAccent
        Mode.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val scale by animateFloatAsState(
        if (mode == Mode.IDLE) 0.92f else 1f,
        Motion.pick(Motion.bouncy()),
        label = "primaryScale",
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .scale(scale)
            .clip(RoundedCornerShape(Radius.sm))
            .background(fill)
            .pressable(scale = 0.88f)
            .clickable(enabled = mode != Mode.IDLE, onClick = if (mode == Mode.STOP) onStop else onSend),
        contentAlignment = Alignment.Center,
    ) {
        if (mode == Mode.STOP) {
            Icon(
                Icons.Default.Stop,
                contentDescription = stringResource(R.string.stop),
                tint = ink,
                modifier = Modifier.size(15.dp),
            )
        } else {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.send),
                tint = ink,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private enum class Mode { IDLE, SEND, STOP }
