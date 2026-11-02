package me.shirazu.dshmizar.domain

import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.Format
import me.shirazu.mizar.kit.CompletionAnnouncer
import me.shirazu.mizar.kit.Conversation
import me.shirazu.mizar.kit.ConversationItem
import me.shirazu.mizar.kit.PendingInteractions
import me.shirazu.mizar.kit.SessionMessagesResult
import me.shirazu.mizar.kit.SessionRow

/**
 * What this phone last saw running, kept across a process that was frozen mid-turn.
 *
 * In memory alone the baseline was lost whenever the system reclaimed the app: the
 * phone came back with an empty map, the turn that ended while it was away had no
 * "was running" to compare against, and the completion was never announced.
 */
internal interface RunningBaseline {

    fun runningSession(hostId: String, sessionId: String): Boolean

    fun setRunningSession(hostId: String, sessionId: String, running: Boolean)
}

/**
 * When this phone is allowed to buzz, and about what.
 *
 * Three rules live here because they all end in the same place -- a notice the
 * operator may never see:
 *
 *  - **Only a transition counts.** A list read that finds a session idle says nothing
 *    about whether it *just* became idle, so a baseline of what was running is kept,
 *    in memory and in the store.
 *  - **Only in the background.** A notice for a screen you are looking at is noise.
 *  - **A finished turn can wait; a blocked one cannot.** Quiet hours silence the
 *    former and never the latter, which is why the pending notice ignores them.
 *
 * Nothing is recorded unless a notice is actually posted: recording a suppression
 * (on screen, quiet hours) is what lost the following turn.
 */
internal class NotificationCoordinator(
    private val store: StateStore,
    private val baseline: RunningBaseline,
    private val text: Text,
    private val note: (String) -> Unit,
    private val quietHours: () -> Boolean,
    /** The wall clock's hour, 0..23: the quiet window is a rule about time of day. */
    private val hourOfDay: () -> Int,
    private val isForeground: () -> Boolean,
    /** The last page of a session's transcript, or `null` when it cannot be read. */
    private val tail: suspend (String) -> SessionMessagesResult?,
    /**
     * Post one notice and say whether the system accepted it.
     *
     * `finished` picks the id range: a completion must not replace a waiting card.
     */
    private val postNotice: (title: String, body: String, sessionId: String?, finished: Boolean) -> Boolean,
) {

    /**
     * What keeps the two announcing paths from buzzing twice for one turn.
     *
     * It remembers *when* a notice went out, because a flag outlived the turn it
     * described and swallowed the next one.
     */
    private val announcer = CompletionAnnouncer(DEDUPE_MILLIS)

    /** Which sessions were running the last time the list was read. */
    private val wasRunning = mutableMapOf<String, Boolean>()

    /** Signature of the pending set already announced, so a batch buzzes once. */
    private var lastNotified: String? = null

    /**
     * Whether any session is known to be running.
     *
     * The heartbeat asks before spending a list read: with nothing running there is
     * nothing that could finish, so the read would only be a guess.
     */
    fun anyRunning(): Boolean = wasRunning.values.any { it }

    /**
     * Notify the sessions whose turn just ended, while the operator is elsewhere.
     *
     * Only a transition counts: a list read that finds a session idle says nothing about
     * whether it *just* became idle. And only in the background -- a notification for a
     * screen you are looking at is noise.
     */
    suspend fun announceFinished(rows: List<SessionRow>) {
        for (row in rows) {
            val runningNow = row.running
            val hostId = store.state.value.host?.hostId
            // In memory first, then the store: a process that was frozen mid-turn came
            // back with an empty map and lost the completion that happened while away.
            val was = wasRunning[row.sessionId]
                ?: hostId?.let { baseline.runningSession(it, row.sessionId) }
            if (hostId != null) baseline.setRunningSession(hostId, row.sessionId, runningNow)
            wasRunning[row.sessionId] = runningNow
            if (was != true || runningNow) continue
            note("list: ${row.sessionId} settled (was running)")
            // A turn ended. Announced once per turn: a second read of the same idle row
            // must not buzz again, and neither must the event that reported the same
            // ending.
            if (announcer.announcedRecently(row.sessionId, System.currentTimeMillis())) {
                note("list: ${row.sessionId} already announced")
                continue
            }
            if (isForeground()) {
                note("list: ${row.sessionId} on screen, nothing to announce")
                continue
            }
            // A finished turn can wait; a blocked one cannot. Quiet hours silence only the
            // former.
            if (quietHours() && isQuietNow()) {
                note("list: ${row.sessionId} quiet hours")
                continue
            }
            val announcement = announce(row.sessionId)
            if (announcement == null) {
                note("list: ${row.sessionId} notice category off")
            } else {
                note("list: ${row.sessionId} posted=${announcement.posted} body=${announcement.body}")
            }
        }
        // Forget the memory once a session is seen running again, so the next completion
        // is announced too.
        for (row in rows) if (row.running) announcer.forget(row.sessionId)
    }

    /**
     * The same notice, from the host's own `turn.settled` event.
     *
     * This is the signal the polling path was approximating: the host knows exactly
     * when a turn ends. Both paths record when they post, so a turn is announced once.
     */
    suspend fun announceSettled(sessionId: String) {
        if (isForeground()) {
            note("settled $sessionId: on screen, nothing to announce")
            return
        }
        if (announcer.announcedRecently(sessionId, System.currentTimeMillis())) {
            note("settled $sessionId: already announced")
            return
        }
        if (quietHours() && isQuietNow()) {
            note("settled $sessionId: quiet hours")
            return
        }
        val announcement = announce(sessionId)
        if (announcement == null) {
            note("settled $sessionId: notice category off")
        } else {
            note("settled $sessionId: posted=${announcement.posted} failed=${announcement.failed} body=${announcement.body}")
        }
    }

    /**
     * Say that an agent is waiting, once per batch, and only while the operator is
     * elsewhere.
     *
     * In the foreground the cards are already on screen, and a notification for what
     * you are looking at is noise. [lastNotified] keeps the same batch from buzzing
     * twice.
     */
    fun announcePending(pending: PendingInteractions) {
        val signature = (pending.approvals.map { it.requestId } + pending.questions.map { it.callId })
            .sorted().joinToString(",")
        if (pending.isEmpty) {
            lastNotified = null
            return
        }
        // Split into plain steps with a log at each one: on a real phone the combined
        // condition silently declined while the log above showed a non-empty set in the
        // background, and only side-specific logging can say which half was false.
        if (isForeground()) return
        if (signature == lastNotified) return
        lastNotified = signature
        // Only the categories the operator left on. An agent blocked on a question the
        // phone has been told not to surface is not a notice the app is entitled to
        // ignore, so the count is filtered rather than the whole batch.
        val shownApprovals = if (store.state.value.notifyApprovals) pending.approvals.size else 0
        val shownQuestions = if (store.state.value.notifyQuestions) pending.questions.size else 0
        if (shownApprovals == 0 && shownQuestions == 0) {
            note("pending: notice categories off")
            return
        }
        // Counted from the catalogue, not spelled here: the hardcoded English was the
        // one notification that stayed English on a Chinese phone. Parity over grammar,
        // like the rest of the catalogue, so a count of one reads "1 approvals" rather
        // than inventing plural rules the iOS client lacks.
        val counts = listOfNotNull(
            pending.approvals.size.takeIf { shownApprovals > 0 }
                ?.let { text.get(R.string.pending_approvals, it) },
            pending.questions.size.takeIf { shownQuestions > 0 }
                ?.let { text.get(R.string.pending_questions, it) },
        ).joinToString(" + ")
        postNotice(
            text.get(R.string.pending_title),
            counts + text.get(R.string.pending_body),
            pending.approvals.firstOrNull()?.sessionId ?: pending.questions.firstOrNull()?.sessionId,
            false,
        )
    }

    /** What a notice said, so the caller can log it in its own dialect. */
    private data class Announcement(val posted: Boolean, val failed: Boolean, val body: String)

    /**
     * Read the tail, decide what to say, and claim the turn only if the phone agreed.
     *
     * @return `null` when the operator turned this category off, so the caller can say
     *   which path declined.
     */
    private suspend fun announce(sessionId: String): Announcement? {
        val detail = describeFinished(sessionId)
        if ((detail.failed && !store.state.value.notifyInterrupted) ||
            (!detail.failed && !store.state.value.notifyCompleted)
        ) {
            return null
        }
        val title = text.get(
            if (detail.failed) R.string.turn_failed_notice_title else R.string.turn_completed_title,
        )
        val name = store.state.value.titles[sessionId] ?: sessionId.takeLast(8)
        val body = detail.summary
            ?.let { text.get(R.string.turn_completed_body, name, it) }
            ?: text.get(R.string.turn_completed_body_plain, name)
        val posted = postNotice(title, body, sessionId, true)
        if (posted) announcer.record(sessionId, System.currentTimeMillis())
        return Announcement(posted, detail.failed, body)
    }

    /** What a finished turn was: failed or not, how long it took, and its first line. */
    private data class FinishedDetail(val failed: Boolean, val summary: String?)

    /**
     * Read the tail of a session that just finished.
     *
     * The session list knows only that a turn stopped running. Whether it failed, how long
     * it took and what it produced are in the transcript, and one small page is cheaper
     * than a notification that says nothing an operator can act on.
     */
    private suspend fun describeFinished(sessionId: String): FinishedDetail {
        val page = tail(sessionId) ?: return FinishedDetail(false, null)
        val last = Conversation.turns(page).lastOrNull() ?: return FinishedDetail(false, null)
        val answer = last.steps.lastOrNull {
            it is ConversationItem.Message && it.role == ConversationItem.Role.ASSISTANT
        } as? ConversationItem.Message
        val elapsed = last.elapsedMillis(System.currentTimeMillis()) ?: 0L
        val duration = if (elapsed > 0) Format.duration(elapsed) else null
        val firstLine = answer?.text
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { line -> line.isNotEmpty() && !line.startsWith("```") }
            ?.take(80)
        // The completion notice is the whole story in one line: how long, and what it said.
        val summary = listOfNotNull(duration, firstLine).joinToString(" · ").ifBlank { null }
        return FinishedDetail(last.failed, summary)
    }

    /** Whether the clock is inside the quiet window (23:00 to 08:00). */
    private fun isQuietNow(): Boolean {
        val hour = hourOfDay()
        return hour >= 23 || hour < 8
    }

    private companion object {
        /** Two paths can report the same turn end; one notice, not two. */
        const val DEDUPE_MILLIS = 5_000L
    }
}
