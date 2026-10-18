package me.shirazu.mizar.kit

import kotlin.random.Random

/**
 * An escalating, jittered retry schedule.
 *
 * The last step repeats forever, and the jitter is *subtracted* rather than added
 * around the step: a delay is always in `[step * (1 - jitter), step)`. That matters
 * when several phones reconnect after the same relay restart — they are spread
 * across the interval instead of arriving together at the top of it.
 *
 * @property steps the schedule in seconds; the last value repeats.
 * @property jitter the fraction of each step to spread over, clamped to `[0, 0.99]`.
 */
class Backoff(
    private val steps: List<Double>,
    jitter: Double = 0.5,
) {
    init {
        require(steps.isNotEmpty()) { "a backoff with no steps never delays anything" }
    }

    private val jitter: Double = jitter.coerceIn(0.0, 0.99)

    /** How many delays have been handed out since the last [reset]. */
    var attempts: Int = 0
        private set

    /**
     * The next delay, in seconds.
     *
     * @param random a source of `[0, 1)`; injected so tests can pin the schedule.
     */
    fun nextDelay(random: () -> Double = { Random.nextDouble() }): Double {
        val step = steps[minOf(attempts, steps.size - 1)]
        attempts += 1
        val floor = step * (1 - jitter)
        return floor + (step - floor) * random()
    }

    /** Start again from the first step, after a success. */
    fun reset() {
        attempts = 0
    }

    companion object {
        /** The schedule the connection layer retries with. */
        val standard: Backoff = Backoff(listOf(0.5, 1.0, 2.0, 4.0, 8.0, 10.0), 0.5)

        /**
         * The schedule a lost socket retries with, for as long as the app is alive.
         *
         * [standard] was all this client had, and it was used for a *finite* number
         * of attempts: six tries, about half a minute, and then the app sat in its
         * failure screen and never dialled again. On a monitor that is the one
         * outcome that must not happen -- a host restart, a tunnel re-establishing
         * or a phone changing networks all outlast half a minute, and every second
         * after that was a completion the operator never heard about. The last step
         * is the cap: 30 seconds between attempts is cheap enough to run forever
         * and short enough that the app is back before the operator needs it.
         */
        val persistent: Backoff = Backoff(listOf(0.5, 1.0, 2.0, 4.0, 8.0, 10.0, 20.0, 30.0), 0.5)
    }
}
