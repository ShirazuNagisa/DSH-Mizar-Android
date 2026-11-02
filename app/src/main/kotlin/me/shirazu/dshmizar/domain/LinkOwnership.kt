package me.shirazu.dshmizar.domain

import me.shirazu.mizar.kit.MizarConnection

/**
 * Who owns the live socket.
 *
 * These were three fields on the view model -- `connection`, `connectionEpoch` and
 * `lastConnectFailure` -- written by `connect`, `becomeLive`, `maybeSwitchToLan` and
 * the pairing teardown. "Is this answer still the current one" was therefore answered
 * in several places with several different comparisons, and the two rules that matter
 * were only written down in comments:
 *
 *  - **A socket that is no longer live must not be able to act.** Its failure must not
 *    start a reconnect loop for the relay the app just left, and a read that was in
 *    flight must not publish over the new socket's transcript.
 *  - **Replacing comes before closing.** `adopt` then disconnect the old socket, never
 *    the other way round: a close on a still-live socket reports a failure that looks
 *    exactly like a dropped connection.
 *
 * Both are properties of this object now, and `LinkOwnershipTest` holds them.
 *
 * Not thread-safe on purpose: it is read and written from the view model's coroutines
 * on the main dispatcher, which is what the fields it replaces already assumed.
 */
class LinkOwnership {

    private var live: MizarConnection? = null
    private var epoch = 0
    private var failure: Throwable? = null
    private var endpoint: MizarConnection.Endpoint? = null

    /** The socket that is live right now, or null when there is none. */
    val current: MizarConnection? get() = live

    /**
     * The number that identifies the current socket.
     *
     * A read captures it before it starts and publishes only while it still matches;
     * that is what keeps an answer from the previous socket out of the new transcript.
     */
    val currentEpoch: Int get() = epoch

    /** Why the last dial failed, and where it was dialling: the notice classifies from these. */
    val lastFailure: Throwable? get() = failure
    val lastEndpoint: MizarConnection.Endpoint? get() = endpoint

    /** Make [candidate] the live socket. Every earlier epoch becomes stale. */
    fun adopt(candidate: MizarConnection): Int {
        live = candidate
        epoch += 1
        return epoch
    }

    /**
     * Drop the live socket, or [candidate] if it is still the live one.
     *
     * Returns the socket that was dropped so the caller can close it, or null when
     * there was nothing to drop -- which is also what happens when [candidate] has
     * already been replaced: a late teardown of the old socket must not clear the new
     * one.
     *
     * The epoch deliberately does not move here. Teardown is not a new connection, and
     * a read that was waiting on the socket that just went away still belongs to it.
     */
    fun release(candidate: MizarConnection? = null): MizarConnection? {
        val socket = live ?: return null
        if (candidate != null && socket !== candidate) return null
        live = null
        return socket
    }

    /** True while [candidate] is the live socket. */
    fun owns(candidate: MizarConnection): Boolean = live === candidate

    /** True while [epoch] still identifies the live socket. */
    fun isCurrent(epoch: Int): Boolean = this.epoch == epoch

    /**
     * Remember why the last dial failed.
     *
     * [cause] is nullable because the race reports a lost route from a `Result`, which
     * can be empty; the field it replaces was nullable for the same reason.
     */
    fun recordFailure(cause: Throwable?, at: MizarConnection.Endpoint?) {
        failure = cause
        endpoint = at
    }
}
