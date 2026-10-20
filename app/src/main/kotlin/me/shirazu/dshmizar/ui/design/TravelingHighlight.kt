package me.shirazu.dshmizar.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A selection mark that travels from the row it was on to the row just chosen,
 * drawn once behind the rows rather than by each row -- the sidebar's selected
 * session, a segmented control's fill.
 *
 * It is kept as an offset from wherever its target is drawn now, not as a place on
 * screen: the owner asks [place] with the target's current rect each frame, so a
 * list that scrolls carries the mark with its row, mid-travel or not, and the
 * travel only ever animates the offset back to zero. A second choice while it is
 * still travelling retargets from where the mark is, at the speed it has.
 *
 * Only a choice the operator made travels ([arm], called by the tap before it
 * selects). Anything else -- the first frame, a notification opening a session, a
 * selection made elsewhere -- lands in place. A mark whose old place is not on
 * screen, and every choice under Reduce Motion, fades in where it lands.
 */
@Stable
class TravelingHighlight internal constructor() {
    private val left = Animatable(0f)
    private val top = Animatable(0f)
    private val right = Animatable(0f)
    private val bottom = Animatable(0f)
    private val shown = Animatable(1f)
    private var armed = false

    /** The mark's opacity, 0..1: below 1 only while it fades in. */
    val alpha: Float get() = shown.value.coerceIn(0f, 1f)

    /** Where to draw the mark, given where its target is drawn now. */
    fun place(target: Rect): Rect = Rect(
        target.left + left.value,
        target.top + top.value,
        target.right + right.value,
        target.bottom + bottom.value,
    )

    /** The next selection change was the operator's own: let it travel. */
    fun arm() {
        armed = true
    }

    /**
     * The selection has changed. [from]: where the mark is drawn now, null when its
     * old target is not on screen. [to]: where the new target is drawn, null when it
     * is not on screen either.
     */
    suspend fun moveTo(from: Rect?, to: Rect?, reduceMotion: Boolean) = coroutineScope {
        val travel = armed
        armed = false
        when {
            !travel || to == null -> {
                // In place, at once.
                launch { shown.snapTo(1f) }
                for (edge in edges()) launch { edge.snapTo(0f) }
            }
            from == null || reduceMotion -> {
                for (edge in edges()) launch { edge.snapTo(0f) }
                launch {
                    shown.snapTo(0f)
                    shown.animateTo(1f, Motion.fade())
                }
            }
            else -> {
                launch { shown.animateTo(1f, Motion.fade()) }
                val start = floatArrayOf(from.left - to.left, from.top - to.top, from.right - to.right, from.bottom - to.bottom)
                edges().forEachIndexed { i, edge ->
                    // The speed it has, not a standing start: read before the jump,
                    // which re-expresses the same place against the new target.
                    val v = edge.velocity
                    launch {
                        edge.snapTo(start[i])
                        edge.animateTo(0f, Motion.smooth(), initialVelocity = v)
                    }
                }
            }
        }
    }

    private fun edges() = listOf(left, top, right, bottom)
}

@Composable
fun rememberTravelingHighlight(): TravelingHighlight = remember { TravelingHighlight() }
