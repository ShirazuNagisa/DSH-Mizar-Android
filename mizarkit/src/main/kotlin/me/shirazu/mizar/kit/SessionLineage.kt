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
 * Four sources, most specific first. The switch this phone made and the host
 * accepted is the newest: the host has no "what is this session using" read on
 * this side of the protocol, and its stats frame only catches up on the next read
 * — on an idle session nothing asks it to. The host's own pending change (`next`)
 * is what the following turn will use, its `lastUsed` is the route the previous
 * turn took, and the catalogue default names a session that has never run.
 *
 * One function rather than the same precedence in the composer's pill and in the
 * picker: the two copies drift, and when they do the pill goes on naming the model
 * the session was on before a switch while the sheet names the new one.
 */
object ModelRouting {

    /**
     * @param chosen the route this phone last had the host accept, when there is one.
     * @param reported the host's model pair from the session's stats frame.
     * @param fallback the route a new session would take.
     * @returns the selection to name, or `null` when nothing can name it.
     */
    fun agreed(
        chosen: ModelSelection?,
        reported: ModelSelectionPair?,
        fallback: ModelSelection? = null,
    ): ModelSelection? = chosen ?: reported?.next ?: reported?.lastUsed ?: fallback
}
