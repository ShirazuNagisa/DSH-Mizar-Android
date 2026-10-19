package me.shirazu.dshmizar.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Where the sidebar is, as one number.
 *
 * `progress` runs from 0 (the conversation in front) to 1 (the sidebar open and
 * the conversation pushed aside as a card). Everything that moves -- the card's
 * offset, its scale, its corner, the shade over it, the toggle's glyph -- is a
 * function of this one value, so a finger dragging it and a spring settling it
 * move every piece together and nothing can get out of step.
 *
 * Ported line for line from iOS `Views/Shell/ShellState.swift`. The settle is
 * velocity-matched: a flick carries its speed into the spring.
 */
class ShellState(
    private val scope: CoroutineScope,
    private val reduceMotion: () -> Boolean,
    private val dismissKeyboard: () -> Unit,
) {
    /**
     * Where the shell is, as one number.
     *
     * `-1` is the turn index (the drawer on the right), `0` the conversation, `1` the
     * sidebar. One number rather than two flags on purpose: the drawers are mirrors,
     * they cannot both be out, and a drag that starts closed decides by its own
     * direction which one it opens.
     */
    val progress = Animatable(0f)

    /** Whether a finger is on it right now. */
    var isDragging by mutableStateOf(false)
        private set

    /** Where the last settle left it: -1 (index), 0 (conversation), 1 (sidebar). */
    var settledAt by mutableStateOf(0f)
        private set

    /** Whether the sidebar is the drawer that is out. */
    val settledOpen: Boolean get() = settledAt > 0.5f

    /** Whether the turn index is the drawer that is out. */
    val settledIndex: Boolean get() = settledAt < -0.5f

    /** Which workspaces are open, per Mac, for the life of the process. */
    private val expandedByHost = mutableMapOf<String, Set<String>>()

    private var dragOrigin = 0f

    val isOpen: Boolean get() = kotlin.math.abs(progress.value) > 0.5f

    // Opening and closing

    fun open() = settle(1f)
    fun close() = settle(0f)
    fun openIndex() = settle(-1f)
    fun toggle() = settle(if (settledOpen) 0f else 1f)
    fun toggleIndex() = settle(if (settledIndex) 0f else -1f)

    /** Put it somewhere without animating, for the first frame. */
    fun place(at: Float) {
        scope.launch { progress.snapTo(at) }
        settledAt = at
    }

    // Dragging

    fun beginDrag() {
        isDragging = true
        dragOrigin = progress.value
        dismissKeyboard()
    }

    /**
     * Follow the finger.
     *
     * @param fraction the horizontal translation as a fraction of the sidebar's width.
     */
    fun dragBy(fraction: Float) {
        val raw = dragOrigin + fraction
        // Past either end the card still moves, at a third of the finger's speed and
        // less the further it goes: the edge is felt, not hit. Both ends, because both
        // drawers have one.
        val banded = when {
            raw > 1f -> 1f + rubberBand(raw - 1f)
            raw < -1f -> -1f - rubberBand(-raw - 1f)
            else -> raw
        }
        // A very short spring rather than an assignment: grabbing the card while it is
        // still settling blends from where it is drawn instead of jumping.
        scope.launch {
            progress.animateTo(banded, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessHigh))
        }
    }

    /**
     * Let go.
     *
     * @param velocity the finger's speed in sidebar widths per second.
     */
    fun endDrag(velocity: Float) {
        isDragging = false
        // Where a flick would carry it, a tenth of a second on. Deciding on the
        // projection rather than the position is what lets a short quick flick open a
        // drawer and a long slow drag that changes its mind close it. The nearest of
        // the three stops wins, so a flick that closes one drawer can never throw the
        // card on into the other -- there is only ever one target.
        val projected = (progress.value + velocity * 0.12f).coerceIn(-1f, 1f)
        val target = when {
            projected > 0.5f -> 1f
            projected < -0.5f -> -1f
            else -> 0f
        }
        settle(target, velocity)
    }

    private fun settle(target: Float, velocity: Float = 0f) {
        if (kotlin.math.abs(target) > 0.5f) dismissKeyboard()
        scope.launch {
            if (reduceMotion()) {
                progress.animateTo(target, tween(220))
            } else {
                // initialVelocity is measured in progress per second, which is exactly
                // what the drag reports, so no iOS-style relative conversion is needed.
                progress.animateTo(
                    target,
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                    initialVelocity = velocity,
                )
            }
        }
        settledAt = target
    }

    private fun rubberBand(overshoot: Float): Float {
        val limit = 0.08f
        return limit * (1f - 1f / (overshoot * 3f / limit + 1f))
    }

    // Workspaces

    fun expanded(hostId: String?): Set<String> = expandedByHost[hostId ?: ""] ?: emptySet()

    fun setExpanded(expanded: Set<String>, hostId: String?) {
        expandedByHost[hostId ?: ""] = expanded
    }
}
