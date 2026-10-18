package me.shirazu.mizar.kit

/**
 * Whether a finished turn still needs a notice.
 *
 * Two paths can announce the same ending — the host's `turn.settled` event, and the
 * list poll that catches what the event missed — and they can both see it milliseconds
 * apart, so one of them has to give way. This is the memory they share.
 *
 * It remembers **when** a notice went out for each session, and only for a few seconds.
 * That is the whole design, and it is a correction: the first version remembered a
 * flag per session and cleared it only when a refresh saw the session *running* again.
 * A turn that began and ended between two observations never produced that clearance,
 * so the flag outlived the turn it described and the *next* completion was dropped as
 * "already announced" on a phone that had been told nothing. Time is the one property
 * of a turn that cannot get stuck.
 *
 * Recording is the caller's decision, and it must only happen when a notice was
 * actually posted: a suppression (the screen is on, quiet hours) is not an
 * announcement, and recording one is the same bug in another disguise.
 *
 * @param windowMillis how long a notice counts as "the same turn" for the other path.
 *   Kept short — it only has to cover the race — because a genuine second turn inside
 *   the window would be dropped, and turns can be quick.
 */
class CompletionAnnouncer(private val windowMillis: Long) {

    private val postedAt = mutableMapOf<String, Long>()

    /**
     * Whether a notice for this session was posted inside the window.
     *
     * @param sessionId the session the turn belongs to.
     * @param now the current time, in the caller's clock.
     */
    fun announcedRecently(sessionId: String, now: Long): Boolean {
        val at = postedAt[sessionId] ?: return false
        return now - at < windowMillis
    }

    /**
     * Record that a notice was posted.
     *
     * @param sessionId the session the turn belongs to.
     * @param now when it was posted, in the caller's clock.
     */
    fun record(sessionId: String, now: Long) {
        postedAt[sessionId] = now
    }

    /**
     * Forget a session, because it was seen running again.
     *
     * Not required for correctness — the window expires on its own — but it keeps the
     * map from carrying an entry per session forever.
     */
    fun forget(sessionId: String) {
        postedAt.remove(sessionId)
    }
}
