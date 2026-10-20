package me.shirazu.mizar.kit

/**
 * How a finished answer is typed onto the screen.
 *
 * The host sends a turn's answer once, whole: the summary protocol deliberately does
 * not stream per-token updates to the phone. This is the phone's stand-in for that
 * arrival — a deterministic schedule of when each character becomes visible, so the
 * answer reads as a voice rather than as a screen that refreshed.
 *
 * It is not an animation curve and it never reads a clock: a plan is built from a
 * string, and the reader asks it what is visible at some elapsed time. That keeps the
 * pacing testable without a screen or a wait.
 */
class TypewriterPlan private constructor(
    val totalCharacters: Int,
    /** How long the whole message takes to appear, in microseconds. */
    val durationMicros: Long,
    private val revealTimesMicros: LongArray,
) {

    /** How long the whole message takes to appear, in milliseconds. */
    val durationMillis: Long get() = durationMicros / 1_000L

    /** How many characters are visible after [elapsedMicros] since the reveal began. */
    fun revealedCount(elapsedMicros: Long): Int {
        if (totalCharacters == 0 || elapsedMicros <= 0L) return 0
        if (elapsedMicros >= durationMicros) return totalCharacters
        var low = 0
        var high = revealTimesMicros.size
        while (low < high) {
            val middle = (low + high) / 2
            if (revealTimesMicros[middle] <= elapsedMicros) low = middle + 1 else high = middle
        }
        return low
    }

    /** Whether the reveal is over. */
    fun isFinished(elapsedMicros: Long): Boolean =
        totalCharacters == 0 || elapsedMicros >= durationMicros

    /** When a character at [index] appears, in microseconds; null outside the message. */
    fun revealTimeMicros(index: Int): Long? = revealTimesMicros.getOrNull(index)

    companion object {
        /** Base rate before punctuation pauses: fast enough to read as a model voice. */
        const val DEFAULT_CHARACTERS_PER_SECOND: Double = 180.0

        /** The longest a reveal may take, whatever the message length. */
        const val DEFAULT_MAXIMUM_DURATION_MILLIS: Long = 2_000L

        /** The shortest reveal worth showing. */
        const val DEFAULT_MINIMUM_DURATION_MILLIS: Long = 100L

        /** Build the schedule for one message. The same text always types the same way. */
        fun forText(
            text: String,
            charactersPerSecond: Double = DEFAULT_CHARACTERS_PER_SECOND,
            maximumDurationMillis: Long = DEFAULT_MAXIMUM_DURATION_MILLIS,
            minimumDurationMillis: Long = DEFAULT_MINIMUM_DURATION_MILLIS,
        ): TypewriterPlan {
            val characters = text.toList()
            if (characters.isEmpty()) return TypewriterPlan(0, 0L, LongArray(0))

            // Seeded from the text so the plan is deterministic: one message always
            // types the same way, however many times the row is drawn.
            var random = SplitMix64(seedFor(characters))
            // ±22% jitter: enough that the rhythm breathes, small enough that it
            // never reads as a stutter.
            val weights = DoubleArray(characters.size) { index ->
                weightOf(characters[index]) * (0.78 + random.nextUnit() * 0.44)
            }
            val totalWeight = weights.sum()
            val baseSeconds = totalWeight / charactersPerSecond.coerceAtLeast(1.0)
            val seconds = baseSeconds.coerceIn(
                minimumDurationMillis / 1_000.0,
                maximumDurationMillis / 1_000.0,
            )
            val unit = seconds / totalWeight

            val times = LongArray(characters.size)
            var elapsed = 0.0
            for (index in weights.indices) {
                elapsed += weights[index] * unit
                times[index] = (elapsed * 1_000_000.0).toLong()
            }
            return TypewriterPlan(characters.size, times.last(), times)
        }

        /** How long one character is worth: a full stop is a breath, a space is quick. */
        private fun weightOf(character: Char): Double = when (character) {
            '\n' -> 3.6
            '。', '！', '？', '…' -> 3.2
            '，', '、', '；', '：' -> 2.4
            '.', '!', '?' -> 2.8
            ';', ':' -> 2.2
            ',' -> 1.9
            ' ' -> 0.55
            else -> 1.0
        }

        /** FNV-1a over the message: short, stable across processes. */
        private fun seedFor(characters: List<Char>): Long {
            var hash = 0xCBF29CE484222325UL
            for (character in characters) {
                hash = hash xor character.code.toULong()
                hash *= 0x100000001B3UL
            }
            return hash.toLong()
        }
    }
}

/** SplitMix64, the jitter source: deterministic for a given seed and platform-free. */
private class SplitMix64(seed: Long) {
    private var state = seed

    fun nextUnit(): Double {
        state += 0x9E3779B97F4A7C15UL.toLong()
        var z = state
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBUL.toLong()
        z = z xor (z ushr 31)
        return (z ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}
