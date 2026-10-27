package me.shirazu.mizar.kit

/**
 * Which session's page can only be read through another session.
 *
 * A delegated child is not addressable as a session: DSH's `session/follow` and
 * `session/page` refuse `{kind: "session"}` for a log whose header says
 * `origin: "subagent"`, and answer `session/agent-busy` before touching the log —
 *
 *     subagent Sessions require their durable parent address
 *
 * — so a request naming only the child's id could never page it at all. Since
 * plugin 0.8.9 `session.messages` accepts the parent and rebuilds the durable
 * subagent address on the way in; the lineage is on the row this phone already
 * has from `session.list`, so it is resolved here rather than at each call site
 * that reads a page.
 *
 * A **fork** carries a parent and is *not* a subagent: it is the operator's own
 * conversation, addressed as a session like any other. Sending a parent for it
 * would describe a log that is not a child's.
 */
object SessionLineage {

    /**
     * The parent a page request has to name, or `null` for an ordinary session.
     *
     * @param sessions the rows from the last `session.list`.
     * @param sessionId the session whose page is being read.
     * @returns the parent's id when the row is the host's own `subagent` mark,
     *   `null` otherwise — including when the row is unknown (a session opened
     *   from a cache the list has not refreshed yet).
     */
    fun parentOf(sessions: List<SessionRow>, sessionId: String): String? =
        sessions.firstOrNull { it.sessionId == sessionId && it.isSubagent }?.parentSessionId
}

/**
 * The route a session will actually use, as the app and the host agree on it.
 *
 * Four sources, most specific first, and the order is the whole point: **the host
 * is the authority**. `session.model.set` answers with what it selected and the
 * client then re-reads `session.stats`, so a switch is visible as soon as it is
 * real -- and a switch the host refused never becomes visible at all.
 *
 * `pending` is the one exception, and only while it lasts: the value the operator
 * just tapped, held from the moment it is sent until the host answers. Without it
 * the pill would flick back to the old route for one round trip on every tap.
 *
 * One function rather than the same precedence in the composer's pill and in the
 * picker: the two copies drift, and when they do the pill goes on naming the model
 * the session was on before a switch while the sheet names the new one.
 */
object ModelRouting {

    /**
     * @param pending a switch that has been sent and not yet answered, if any.
     * @param reported the host's model pair from the session's stats frame.
     * @param fallback the route a new session would take.
     * @returns the selection to name, or `null` when nothing can name it.
     */
    fun agreed(
        pending: ModelSelection?,
        reported: ModelSelectionPair?,
        fallback: ModelSelection? = null,
    ): ModelSelection? = pending ?: reported?.next ?: reported?.lastUsed ?: fallback
}
