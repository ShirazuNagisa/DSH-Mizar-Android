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
    /** 0 closed, 1 open. Values a little outside that range while dragging are the rubber band. */
    val progress = Animatable(0f)

    /** Whether a finger is on it right now. */
    var isDragging by mutableStateOf(false)
        private set

    /** Whether the last settle left it open, for the haptic and for the toggle. */
    var settledOpen by mutableStateOf(false)
        private set

    /** Which workspaces are open, per Mac, for the life of the process. */
    private val expandedByHost = mutableMapOf<String, Set<String>>()

    private var dragOrigin = 0f

    val isOpen: Boolean get() = progress.value > 0.5f

    // Opening and closing

    fun open() = settle(1f)
    fun close() = settle(0f)
    fun toggle() = settle(if (settledOpen) 0f else 1f)

    /** Put it somewhere without animating, for the first frame. */
    fun place(open: Boolean) {
        scope.launch { progress.snapTo(if (open) 1f else 0f) }
        settledOpen = open
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
        // less the further it goes: the edge is felt, not hit.
        val banded = when {
            raw > 1f -> 1f + rubberBand(raw - 1f)
            raw < 0f -> -rubberBand(-raw)
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
        // projection rather than the position is what lets a short quick flick open it
        // and a long slow drag that changes its mind close it.
        val projected = progress.value + velocity * 0.12f
        settle(if (projected > 0.5f) 1f else 0f, velocity)
    }

    private fun settle(target: Float, velocity: Float = 0f) {
        if (target > 0.5f) dismissKeyboard()
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
        settledOpen = target > 0.5f
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
