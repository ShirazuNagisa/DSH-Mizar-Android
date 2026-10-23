package me.shirazu.mizar.kit

/**
 * How far the first pairing's index warm-up has got.
 *
 * A newly paired phone knows nothing: the session list arrives in one frame, but
 * every title behind it is a separate `session.stats` call. That is the only part
 * of first pairing that takes visible time, and it is also the part the operator
 * would otherwise watch as a spinner with no end.
 *
 * @property done titles resolved so far.
 * @property total sessions the host listed.
 */
data class SyncProgress(val done: Int, val total: Int) {

    /** 0f…1f, and 0f for an empty host rather than a division by zero. */
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    /** The whole percent the screen shows. */
    val percent: Int get() = (fraction * 100f).toInt().coerceIn(0, 100)

    /** Whether there is nothing left to read. */
    val isComplete: Boolean get() = done >= total
}

/**
 * When the warm-up will finish, from how long the recent titles took.
 *
 * Deliberately not "elapsed / done × remaining": the first call carries the cost of
 * waking a cold host, and averaging it in makes a two-second remainder look like a
 * minute. The recent sample is what the rest of the run will actually cost, and an
 * estimate that is wrong by a factor of two in the first second is worse than one
 * that appears a second later.
 */
object SyncEta {

    /** How many recent titles the estimate averages. */
    const val SAMPLE_SIZE = 8

    /**
     * The remaining time, or `null` when there is not enough to go on.
     *
     * @param done titles resolved so far.
     * @param total sessions the host listed.
     * @param recentDurationsMillis how long the most recent titles took, newest last.
     * @returns milliseconds left, or `null` before the first sample exists.
     */
    fun estimateMillis(done: Int, total: Int, recentDurationsMillis: List<Long>): Long? {
        val remaining = total - done
        if (remaining <= 0) return 0L
        val sample = recentDurationsMillis.takeLast(SAMPLE_SIZE).filter { it >= 0 }
        if (sample.isEmpty()) return null
        val average = sample.sum() / sample.size
        return average * remaining
    }
}
