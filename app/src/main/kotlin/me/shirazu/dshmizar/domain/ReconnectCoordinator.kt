package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.shirazu.dshmizar.Phase
import me.shirazu.dshmizar.R
import me.shirazu.mizar.kit.Backoff
import me.shirazu.mizar.kit.PairedHost

/**
 * What happens after the socket goes away.
 *
 * Three rules, and the first one is the reason this object exists:
 *
 *  - **It never gives up.** This used to be six attempts -- about half a minute -- and
 *    then a permanent failure screen. That was the single defect behind a completion
 *    nobody ever heard about: the socket dropped while the phone sat in a pocket, the
 *    host (or the tunnel) took longer than thirty seconds to come back, and the app
 *    stopped dialling forever. With no socket there is no `turn.settled` event *and*
 *    no list refresh, so both announcements -- the pushed one and the polling fallback
 *    -- die together. A monitor has to outlast the outage.
 *  - **A blip is not an outage.** The reason is recorded at once, but the banner waits
 *    [bannerDelayMillis]: a phone switching networks used to flash "connecting" in
 *    front of the operator several times a minute, which reads as a broken app when
 *    the connection was never actually missed.
 *  - **A refused credential ends the affair.** The host answered and said it does not
 *    know this phone, so every further attempt is the same rejection. Stop, and let
 *    the screen offer pairing.
 *
 * The screen deliberately stays READY throughout: the operator may be reading a
 * conversation, and throwing them back to the list for a two-second blip is worse than
 * a banner that says what is happening.
 */
internal class ReconnectCoordinator(
    private val store: StateStore,
    private val scope: CoroutineScope,
    private val strings: Text,
    private val note: (String) -> Unit,
    /**
     * The schedule. A parameter because it *is* the rule: a test that cannot shorten
     * the wait cannot assert that the loop outlasts an outage.
     */
    private val backoff: Backoff = Backoff.persistent,
    /**
     * How long a drop must last before the banner appears. A parameter for the same
     * reason as [backoff] -- "a blip is not an outage" is a statement about time.
     */
    private val bannerDelayMillis: Long = BANNER_DELAY_MILLIS,
    /** The watchers belong to the connection, and they stop with it. */
    private val stopWatchers: () -> Unit,
    /** Dial the host again; reports failure by setting the phase, not by throwing. */
    private val redial: suspend (PairedHost) -> Unit,
    /** Whatever happened while the socket was down never arrived as an event. */
    private val afterReconnected: () -> Unit,
    /** The host forgot we were on screen while we were away. */
    private val announcePresence: () -> Unit,
    /** The one line beside the banner. */
    private val publishNotice: (String) -> Unit,
    /** Whether a socket is open right now. */
    private val connected: () -> Boolean,
) {

    private var job: Job? = null

    /** Start retrying, unless a loop is already running. */
    fun begin(host: PairedHost, reason: String) {
        if (job?.isActive == true) return
        job = scope.launch {
            stopWatchers()
            // The reason is recorded at once, but the banner waits.
            store.update { it.copy(disconnectReason = reason) }
            // The reason is known but not its owner; until an attempt comes back with
            // something to classify, the notice says only that it is trying.
            publishNotice(strings.get(R.string.link_reconnecting))
            launch {
                delay(bannerDelayMillis)
                if (store.state.value.phase != Phase.READY || !connected()) {
                    store.update { it.copy(reconnecting = true) }
                }
            }
            var attempt = 0
            while (isActive) {
                delay((backoff.nextDelay() * 1000).toLong())
                attempt += 1
                note("reconnect attempt $attempt to ${host.displayName}")
                store.update { it.copy(problem = null) }
                // `redial` reports every failure by setting the phase rather than
                // throwing, so a thrown error here is only ever an unexpected one.
                runCatching { redial(host) }
                val current = store.state.value
                if (current.phase == Phase.READY) {
                    backoff.reset()
                    store.update { it.copy(reconnecting = false, disconnectReason = null) }
                    note("reconnected after $attempt attempt(s)")
                    // The screen has to be re-read once, and a turn that ended during
                    // the outage is announced from the list that read brings back.
                    afterReconnected()
                    announcePresence()
                    return@launch
                }
                // A refused credential ends the whole affair.
                if (current.needsPairing) {
                    store.update { it.copy(phase = Phase.FAILED, reconnecting = false) }
                    return@launch
                }
                // Dialling marks the phase FAILED on every failure; during a retry that
                // would swap the screen for the failure page between attempts, which is
                // worse than the banner. Put the phase back and keep going.
                store.update { if (it.reconnecting) it.copy(phase = Phase.READY) else it }
            }
        }
    }

    /** Stop any automatic retry; a caller that dials something else owns the socket now. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    private companion object {
        /** How long a drop must last before the banner appears. */
        const val BANNER_DELAY_MILLIS = 2_000L
    }
}
