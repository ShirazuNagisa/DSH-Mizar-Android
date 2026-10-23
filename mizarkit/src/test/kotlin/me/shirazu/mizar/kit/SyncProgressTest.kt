package me.shirazu.mizar.kit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncProgressTest {

    @Test
    fun `an empty host is zero rather than a division by zero`() {
        val progress = SyncProgress(done = 0, total = 0)

        assertEquals(0f, progress.fraction)
        assertEquals(0, progress.percent)
        assertTrue(progress.isComplete)
    }

    @Test
    fun `the percent is whole and never past a hundred`() {
        assertEquals(0, SyncProgress(0, 48).percent)
        assertEquals(25, SyncProgress(12, 48).percent)
        assertEquals(100, SyncProgress(48, 48).percent)
        assertEquals(100, SyncProgress(60, 48).percent)
    }

    @Test
    fun `the estimate waits for a sample`() {
        assertNull(SyncEta.estimateMillis(done = 0, total = 10, recentDurationsMillis = emptyList()))
    }

    @Test
    fun `the estimate is the recent rate times what is left`() {
        // 200 ms a title, 10 to go.
        assertEquals(2_000L, SyncEta.estimateMillis(done = 40, total = 50, recentDurationsMillis = listOf(200, 200)))
    }

    @Test
    fun `only the recent sample counts, so a cold first call does not skew it`() {
        val coldStart = listOf(9_000L) + List(SyncEta.SAMPLE_SIZE) { 100L }

        assertEquals(1_000L, SyncEta.estimateMillis(done = 40, total = 50, recentDurationsMillis = coldStart))
    }

    @Test
    fun `nothing left is zero rather than null`() {
        assertEquals(0L, SyncEta.estimateMillis(done = 50, total = 50, recentDurationsMillis = listOf(100)))
    }

    @Test
    fun `a finished warm-up reports complete`() {
        assertTrue(SyncProgress(done = 50, total = 50).isComplete)
        assertFalse(SyncProgress(done = 49, total = 50).isComplete)
    }
}
