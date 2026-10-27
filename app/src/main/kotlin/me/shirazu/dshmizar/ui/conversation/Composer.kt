package me.shirazu.dshmizar.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.ui.design.mizarPerform
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.ModelRouting
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.LocalReduceMotion
import me.shirazu.dshmizar.ui.design.Motion
import me.shirazu.dshmizar.ui.design.AnchoredPopover
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.Glass
import me.shirazu.dshmizar.ui.design.PopoverSize
import me.shirazu.dshmizar.ui.design.Radius
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.glassCard
import me.shirazu.dshmizar.ui.design.pressable
import me.shirazu.dshmizar.ui.design.shapedClickable
import kotlin.math.cos
import kotlin.math.sin

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
    onChooseModel: (me.shirazu.mizar.kit.ModelSelection) -> Unit,
    onCommand: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** Whether the stop button's companion may go round: it is the one running
     *  mark in view. Otherwise it parks. */
    orbit: Boolean = false,
    /** A ready attachment can be sent without text; the send key must know. */
    hasAttachments: Boolean = false,
    /** Whether this draft may take attachments: a paid account, or a local link. */
    attachmentsEnabled: Boolean = false,
    onPickPhoto: () -> Unit = {},
    onPickFile: () -> Unit = {},
    /** Reported upward so the interactions dock can tuck itself away while typing. */
    onFocusChanged: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val sendable = draft.isNotBlank() || hasAttachments
    // Each window grows out of the chip that opens it; the chips own their
    // windows, so the screen above never has to know they exist.
    var showingModel by remember { mutableStateOf(false) }
    var showingContext by remember { mutableStateOf(false) }
    var showingCommands by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(bottom = 14.dp),
    ) {
        // The field is its own slab now, and the send key rides at its right
        // end; the chips are not the field's contents, they are its company,
        // in a row of their own a breath below it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { ComposerGeometry.slabTop = it.boundsInRoot().top }
                .glassCard(radius = Radius.xl)
                // A tap anywhere on the slab is a tap on the field.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { focusRequester.requestFocus() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) {
                    Text(
                        stringResource(R.string.composer_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp),
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraft,
                    maxLines = 8,
                    // Enter sends, as it does on the desktop. A multi-line draft is still
                    // possible -- the field grows -- but the key that people expect to
                    // submit is the one that submits.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (sendable) onSend() }),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .onGloballyPositioned { ComposerGeometry.fieldBounds = it.boundsInRoot() }
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            focused = it.isFocused
                            onFocusChanged(it.isFocused)
                        }
                        .padding(start = 20.dp),
                )
            }
            Box(modifier = Modifier.padding(end = 8.dp)) {
                PrimaryButton(
                    running = running,
                    orbit = orbit,
                    draftBlank = !sendable,
                    onSend = {
                        haptics.mizarPerform(HapticFeedbackType.LongPress)
                        onSend()
                    },
                    onStop = onStop,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement6,
        ) {
            ComposerBarRow(
                state = state,
                focused = focused,
                attachmentsEnabled = attachmentsEnabled,
                onPickPhoto = onPickPhoto,
                onPickFile = onPickFile,
                showingModel = showingModel,
                showingContext = showingContext,
                showingCommands = showingCommands,
                onModel = { showingModel = true },
                onContext = { showingContext = true },
                onCommands = { showingCommands = true },
                onDismissModel = { showingModel = false },
                onDismissContext = { showingContext = false },
                onDismissCommands = { showingCommands = false },
                onChooseModel = { selection ->
                    showingModel = false
                    onChooseModel(selection)
                },
                onCommand = onCommand,
            )
        }
    }
}

private val Arrangement6 = Arrangement.spacedBy(6.dp)

/** The controls along the bottom of the composer: commands, the model, context.
 *  Each chip carries its window: a popover that grows out of the chip itself. */
@Composable
private fun ComposerBarRow(
    state: UiState,
    focused: Boolean,
    attachmentsEnabled: Boolean,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    showingModel: Boolean,
    showingContext: Boolean,
    showingCommands: Boolean,
    onModel: () -> Unit,
    onContext: () -> Unit,
    onCommands: () -> Unit,
    onDismissModel: () -> Unit,
    onDismissContext: () -> Unit,
    onDismissCommands: () -> Unit,
    onChooseModel: (me.shirazu.mizar.kit.ModelSelection) -> Unit,
    onCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement6) {
        Box {
            CommandButton(open = showingCommands, onClick = onCommands)
            AnchoredPopover(
                expanded = showingCommands,
                onDismiss = onDismissCommands,
                title = stringResource(R.string.commands),
                // Four short words that will never grow: the window fits them,
                // not some future list.
                // Wider than the four command words need: the two attachment
                // targets on top are half the window each and have to read as
                // buttons, not as squeezed text.
                width = 240.dp,
                maxHeight = 340.dp,
            ) {
                CommandContent(
                    attachmentsEnabled = attachmentsEnabled,
                    onPickPhoto = { onDismissCommands(); onPickPhoto() },
                    onPickFile = { onDismissCommands(); onPickFile() },
                    onCommand = { onCommand(it); onDismissCommands() },
                )
            }
        }
        Box(modifier = Modifier.weight(1f, fill = false)) {
            ModelPill(state = state, focused = focused, onClick = onModel)
            AnchoredPopover(
                expanded = showingModel,
                onDismiss = onDismissModel,
                title = stringResource(R.string.model),
            ) {
                ModelPickerContent(state = state, onChoose = onChooseModel)
            }
        }
        Box {
            ContextPill(state = state, onClick = onContext)
            AnchoredPopover(
                expanded = showingContext,
                onDismiss = onDismissContext,
                title = stringResource(R.string.context),
                width = PopoverSize.COMPACT_WIDTH,
                maxHeight = PopoverSize.COMPACT_HEIGHT,
            ) {
                ContextContent(state = state)
            }
        }
    }
}

/** The host's own commands, behind a plus that turns into a close. */
@Composable
private fun CommandButton(open: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (open) 45f else 0f, Motion.pick(Motion.bouncy()), label = "plusSpin")
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(Glass.fill(), Corner.lg)
            .border(Glass.Hairline, MaterialTheme.colorScheme.outlineVariant, Corner.lg)
            .pressable(scale = 0.9f)
            .shapedClickable(Corner.lg, onClick = onClick),
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
    val label = run {
        // One resolver for the pill and the picker: when they each carried their
        // own copy of this precedence, a switch made the pill name the old route
        // while the sheet named the new one.
        val chosen = ModelRouting.agreed(
            pending = state.selectedSessionId?.let { state.modelChoice[it] },
            reported = state.stats?.model,
            fallback = state.modelCatalog?.defaultSelection,
        )
        // The button naming nothing but "Model" is the one thing it must never do.
        if (chosen == null) return@run stringResource(R.string.model)
        // The level's name as the catalogue spells it, not the raw id: the wire value
        // need not read like a word ("max", "high"), and the picker draws its names.
        val effortName = chosen.reasoningEffort?.let { effort ->
            state.modelCatalog?.providers
                ?.firstOrNull { it.id == chosen.provider }
                ?.models?.firstOrNull { it.id == chosen.model }
                ?.efforts?.firstOrNull { it.id == effort }?.name
                ?: effort.replaceFirstChar { it.uppercase() }
        }
        listOfNotNull(chosen.model, effortName).joinToString(" · ")
    }

    // While the field has the keyboard the pill folds to its icon, a 32 dp circle.
    // The icon never moves; only the label fades, and the pill's width changes once
    // (as the label arrives, or once it has gone) instead of being animated frame by
    // frame and dragging the context pill beside it along.
    val reduceMotion = LocalReduceMotion.current
    val labelFade = if (reduceMotion) Motion.fade<Float>() else Motion.smooth()
    Row(
        modifier = modifier
            .height(32.dp)
            .background(Glass.fill(), Corner.lg)
            .border(Glass.Hairline, MaterialTheme.colorScheme.outlineVariant, Corner.lg)
            .pressable()
            .shapedClickable(Corner.lg, onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Memory,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedVisibility(
            visible = !focused,
            enter = fadeIn(labelFade),
            exit = fadeOut(labelFade),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(5.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

/** The context dial: how full the window is, warming as it fills. */
@Composable
private fun ContextPill(state: UiState, onClick: () -> Unit) {
    val context = state.stats?.context
    val percent = context?.percent
    // The pill itself says only the figure -- the dial beside it already says what
    // the figure is about, and the word made the row wide enough to crowd the model
    // pill next to it. A screen reader still gets the whole sentence.
    val spoken = percent?.let { stringResource(R.string.context_percent_spoken, it) }
    Row(
        modifier = Modifier
            .height(32.dp)
            .background(Glass.fill(), Corner.lg)
            .border(Glass.Hairline, MaterialTheme.colorScheme.outlineVariant, Corner.lg)
            .then(if (context != null) Modifier.pressable().shapedClickable(Corner.lg, onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp)
            .semantics(mergeDescendants = true) {
                if (spoken != null) contentDescription = spoken
            },
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
    orbit: Boolean,
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
    val companion = alcor(stopping = mode == Mode.STOP, orbit = orbit)
    Box(
        modifier = Modifier
            .size(34.dp)
            .scale(scale)
            // Drawn before the clip, so it can go round outside the rim -- and
            // under the fill, so a companion spiralling in is taken in by it.
            .then(companion)
            .clip(CircleShape)
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

/** One turn of the companion: TwinStar's period. */
private const val AlcorPeriodMillis = 2400
private const val AlcorParkDegrees = -35f
private val AlcorOrbit = 20.dp
private val AlcorDot = 1.5.dp

/**
 * Alcor, Mizar's faint companion, going round the stop button while the turn is
 * running and the button is the one running mark in view ([orbit]); parked at -35°
 * when it is not, and under Reduce Motion.
 *
 * Out of the centre when the button becomes Stop; into it when the button stops
 * being Stop -- the turn finished, was cancelled, or a draft turned it into Send --
 * still turning as it goes, so the end of a turn is drawn as the companion coming
 * home. Its reach follows the state, not a timer: a cancel that lands mid-spiral
 * turns it round with the speed it has. Under Reduce Motion it fades rather than
 * travels.
 *
 * Everything moves in the draw phase: the angle and the reach are read as the dot
 * is drawn, and the button recomposes for none of it.
 */
@Composable
private fun alcor(stopping: Boolean, orbit: Boolean): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val reach = animateFloatAsState(
        if (stopping) 1f else 0f,
        Motion.pick(Motion.smooth()),
        label = "alcorReach",
    )
    val angle = remember { Animatable(AlcorParkDegrees) }
    val spinning = stopping && orbit && !reduceMotion
    LaunchedEffect(spinning, stopping, reduceMotion) {
        // Kept within one turn, so the numbers never grow.
        angle.snapTo(angle.value.mod(360f))
        val speed = 360f / AlcorPeriodMillis * 1000f // degrees per second
        when {
            reduceMotion -> angle.snapTo(AlcorParkDegrees.mod(360f))
            spinning -> while (true) {
                angle.animateTo(angle.value + 360f, tween(AlcorPeriodMillis, easing = LinearEasing))
                angle.snapTo(angle.value.mod(360f))
            }
            // Coming home: a last third of a turn as it spirals in.
            !stopping -> angle.animateTo(angle.value + 120f, Motion.smooth(), initialVelocity = speed)
            // Parked: on round to the park position, not back to it.
            else -> {
                var park = AlcorParkDegrees.mod(360f)
                if (park < angle.value) park += 360f
                angle.animateTo(park, Motion.smooth())
            }
        }
    }
    return Modifier.drawBehind {
        val r = reach.value.coerceIn(0f, 1f)
        if (r <= 0.001f) return@drawBehind
        val rad = Math.toRadians(angle.value.toDouble())
        val orbitPx = AlcorOrbit.toPx() * (if (reduceMotion) 1f else r)
        drawCircle(
            color = color,
            alpha = if (reduceMotion) r else 1f,
            radius = AlcorDot.toPx(),
            center = Offset(
                center.x + orbitPx * cos(rad).toFloat(),
                center.y + orbitPx * sin(rad).toFloat(),
            ),
        )
    }
}
