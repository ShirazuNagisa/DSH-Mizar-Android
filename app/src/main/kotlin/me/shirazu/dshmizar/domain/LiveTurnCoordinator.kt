package me.shirazu.dshmizar.domain

import me.shirazu.mizar.kit.SessionRow

/**
 * The lock screen's one card: which turn is running, what it is called, and since when.
 *
 * Three rules, and each exists because the alternative was visible on a lock screen:
 *
 *  - **One card.** The lock screen has room for one running turn, and the operator's
 *    attention is on the one they just started -- so the row that changed most
 *    recently wins.
 *  - **Only rewritten when it would read differently.** A live update is a status
 *    surface; re-notifying it every poll resets the system's own promotion
 *    bookkeeping for no visible gain.
 *  - **The clock starts at the first moment this phone could see it.** The host says
 *    a turn is running, not when it started, so the open conversation's own
 *    `startedAtMillis` is used when it is the one running -- that is the host's
 *    number rather than an observation.
 */
internal class LiveTurnCoordinator(
    private val store: StateStore,
    /** The wall clock, so "first seen running" can be pinned in a test. */
    private val now: () -> Long,
    private val showCard: (sessionId: String, title: String, since: Long) -> Unit,
    private val hideCard: () -> Unit,
) {

    /** When each running session's turn was first seen running. */
    private val runningSince = mutableMapOf<String, Long>()

    /** The session the card is currently about, so a settle knows to take it down. */
    private var shownSessionId: String? = null

    /** What the card last said, so a poll that changed nothing does not repost it. */
    private var signature: String? = null

    /**
     * Keep the card in step with the session list.
     *
     * When nothing is running the card comes down here, which is the path that covers
     * a host too old to broadcast `turn.settled`.
     */
    fun sync(rows: List<SessionRow>) {
        val running = rows.filter { it.running }
        runningSince.keys.retainAll(running.map { it.sessionId }.toSet())
        val shown = running.maxByOrNull { it.updatedMillis }
        if (shown == null) {
            shownSessionId = null
            signature = null
            hideCard()
            return
        }
        // The host's own start time when this is the open conversation; otherwise the
        // first moment this phone could see the row running.
        val hostedStart = if (shown.sessionId == store.state.value.selectedSessionId) {
            store.state.value.turns.lastOrNull()?.startedAtMillis
        } else {
            null
        }
        val since = runningSince.getOrPut(shown.sessionId) { hostedStart ?: now() }
        shownSessionId = shown.sessionId
        val title = store.state.value.titles[shown.sessionId] ?: shown.sessionId.takeLast(8)
        val next = "${shown.sessionId}|$title|$since"
        if (next == signature) return
        signature = next
        showCard(shown.sessionId, title, since)
    }

    /**
     * A turn ended: take the card down if it was about this session.
     *
     * A settle for a session that is not on the card leaves it alone -- another turn
     * is still running, and its chronometer must not restart.
     */
    fun forget(sessionId: String) {
        if (shownSessionId != sessionId) return
        hideCard()
        shownSessionId = null
        signature = null
        runningSince.remove(sessionId)
    }

    /**
     * Take the card down whatever it was about.
     *
     * Used when the operator asks a turn to stop: waiting for the host's
     * `turn.settled` would leave the card up for however long the stop takes.
     */
    fun hide() {
        hideCard()
        shownSessionId = null
        signature = null
    }
}
