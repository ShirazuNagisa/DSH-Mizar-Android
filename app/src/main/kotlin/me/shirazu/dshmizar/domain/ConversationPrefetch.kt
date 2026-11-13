package me.shirazu.dshmizar.domain

import me.shirazu.mizar.kit.SessionRow

/**
 * Which sessions a `session.sync` batch should warm.
 *
 * Opening a session cold costs a full page read before anything is drawn; the host can
 * answer several pages in one frame, so the list read is a natural moment to warm the
 * next few. The choice is deliberately conservative:
 *
 *  - the currently open session is skipped (it is being read anyway);
 *  - blank sessions and delegated children are skipped (nothing to draw yet);
 *  - newest first, so a fixed budget covers what the operator is most likely to open.
 */
internal object ConversationPrefetch {

    /** How many sessions one batch may warm. The host clamps its own item count to 1..64. */
    const val MAX_SESSIONS = 6

    fun candidates(
        sessions: List<SessionRow>,
        selectedSessionId: String?,
        limit: Int = MAX_SESSIONS,
    ): List<SessionRow> = sessions
        .asSequence()
        .filter { !it.blank && it.sessionId != selectedSessionId && it.parentSessionId == null }
        .sortedByDescending { it.updatedAt }
        .take(limit.coerceAtLeast(0))
        .toList()
}
