package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import me.shirazu.mizar.kit.MizarConnection

/** How a race between routes ended. */
internal sealed interface RouteOutcome<out T> {

    /** A route answered; the others were cancelled. */
    data class Won<T>(val endpoint: MizarConnection.Endpoint, val value: T) : RouteOutcome<T>

    /**
     * Nothing answered.
     *
     * @property endpoint the route whose failure proves the most, when there was one.
     * @property failure that failure, or `null` when no route got far enough to fail.
     */
    data class Lost(
        val endpoint: MizarConnection.Endpoint?,
        val failure: Throwable?,
    ) : RouteOutcome<Nothing>
}

/**
 * Dial every route at once, and take the first that answers.
 *
 * One route at a time is the slowest possible order for a phone that only wants to
 * connect: the handshake timeout is twenty seconds, and a route that blackholes spends
 * all of it before the next one is even tried. So all of them are started together and
 * the winner is whoever completes first.
 *
 * Three rules that are easy to get wrong and invisible when they are:
 *
 *  - **A loser is cancelled, not awaited.** It may still be inside its own handshake;
 *    waiting for it would only delay the connection that is already open. Cancelling
 *    is what tells it to close its socket (see the dialler's own contract).
 *  - **The failure reported is the one that proves the most**, not the last to fail:
 *    with several routes, "last" is an accident of timing. [weight] decides, and a tie
 *    keeps the earlier one so the answer is stable.
 *  - **A cancelled attempt is not a failure.** Cancellation is how a loser leaves; it
 *    must not be weighed, reported, or remembered.
 */
internal class RouteRace(
    private val scope: CoroutineScope,
    private val note: (String) -> Unit,
    /** How much a failure proves, given the route it came from. */
    private val weight: (Throwable, MizarConnection.Endpoint) -> Int,
) {

    suspend fun <T : Any> race(
        endpoints: List<MizarConnection.Endpoint>,
        dial: suspend (MizarConnection.Endpoint) -> T,
    ): RouteOutcome<T> {
        val results = Channel<Attempt<T>>(Channel.UNLIMITED)
        val jobs = endpoints.map { endpoint ->
            scope.launch {
                val attempt = try {
                    Attempt(endpoint, Result.success(dial(endpoint)))
                } catch (cancelled: CancellationException) {
                    // A loser leaving: not a failure, and not something to report.
                    throw cancelled
                } catch (error: Throwable) {
                    Attempt(endpoint, Result.failure(error))
                }
                results.send(attempt)
            }
        }

        var best: Attempt<T>? = null
        var bestWeight = -1
        var settled = 0
        while (settled < endpoints.size) {
            val attempt = results.receive()
            settled += 1
            val value = attempt.outcome.getOrNull()
            if (value != null) {
                // A loser may still be inside its own handshake; cancelling closes its
                // socket, and awaiting that would only delay the winner.
                jobs.forEach { it.cancel() }
                return RouteOutcome.Won(attempt.endpoint, value)
            }
            val error = attempt.outcome.exceptionOrNull() ?: continue
            note("endpoint ${attempt.endpoint} failed: ${error.message}")
            val score = weight(error, attempt.endpoint)
            if (score > bestWeight) {
                bestWeight = score
                best = attempt
            }
        }

        val lost = best
        return RouteOutcome.Lost(lost?.endpoint, lost?.outcome?.exceptionOrNull())
    }

    private class Attempt<T>(val endpoint: MizarConnection.Endpoint, val outcome: Result<T>)
}
