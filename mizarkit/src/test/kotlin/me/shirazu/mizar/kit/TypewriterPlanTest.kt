package me.shirazu.mizar.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The plan is pure arithmetic; these tests pin its promises without a screen. */
class TypewriterPlanTest {

    @Test
    fun `empty message reveals nothing`() {
        val plan = TypewriterPlan.forText("")
        assertEquals(0, plan.totalCharacters)
        assertTrue(plan.isFinished(0))
    }

    @Test
    fun `revealed count is monotonic and reaches the whole message`() {
        val plan = TypewriterPlan.forText("The quick brown fox jumps over the lazy dog.")
        var last = 0
        for (step in 0..200) {
            val count = plan.revealedCount(step * plan.durationMillis * 1_000L / 200)
            assertTrue(count >= last, "reveal went backwards at step $step")
            last = count
        }
        assertEquals(plan.totalCharacters, plan.revealedCount(plan.durationMicros + 1))
        assertTrue(plan.isFinished(plan.durationMicros))
    }

    @Test
    fun `same text produces the same schedule`() {
        val first = TypewriterPlan.forText("Deterministic pacing, twice.")
        val second = TypewriterPlan.forText("Deterministic pacing, twice.")
        assertEquals(first.durationMillis, second.durationMillis)
        for (index in 0 until first.totalCharacters) {
            assertEquals(first.revealTimeMicros(index), second.revealTimeMicros(index))
        }
    }

    @Test
    fun `a short message still takes the minimum duration`() {
        val plan = TypewriterPlan.forText("a")
        assertTrue(plan.durationMillis >= TypewriterPlan.DEFAULT_MINIMUM_DURATION_MILLIS)
    }

    @Test
    fun `a long message is capped at the maximum duration`() {
        val plan = TypewriterPlan.forText("word ".repeat(10_000))
        assertTrue(plan.durationMillis <= TypewriterPlan.DEFAULT_MAXIMUM_DURATION_MILLIS + 1)
    }

    @Test
    fun `a newline gets a longer beat than an ordinary character`() {
        // Both messages have the same total weight budget, so the first character's
        // share of the fixed duration shows its own weight.
        val plain = TypewriterPlan.forText("aa", maximumDurationMillis = 5_000, minimumDurationMillis = 5_000)
        val newline = TypewriterPlan.forText("\na", maximumDurationMillis = 5_000, minimumDurationMillis = 5_000)
        assertTrue(
            newline.revealTimeMicros(0)!! > plain.revealTimeMicros(0)!!,
            "newline should type slower than a letter",
        )
    }
}
