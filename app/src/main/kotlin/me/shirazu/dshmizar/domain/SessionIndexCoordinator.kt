package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.shirazu.mizar.kit.SessionGrouping
import me.shirazu.mizar.kit.SessionIndex
import me.shirazu.mizar.kit.SessionIndexStore
import me.shirazu.mizar.kit.SyncEta
import me.shirazu.mizar.kit.SyncProgress
import me.shirazu.mizar.kit.jsonObjectOf
import me.shirazu.mizar.kit.jsonString

/**
 * The session list: how it is grouped, what is remembered about it, and the names
 * behind its rows.
 *
 * Four rules, all of them about what survives a restart or an empty network:
 *
 *  - **The stored index is additive.** A list read from disk must never wipe what a
 *    live read already put on screen -- opening a phone that has been away must not
 *    make the list flicker from full to empty and back.
 *  - **A blank title is no title.** The host answers `session.stats` for every row;
 *    an untitled session answers with nothing, and storing `""` would make the row
 *    claim it has a name.
 *  - **Titles are learned once each.** Every title is a round trip, so a row already
 *    named is never asked about again, and a fresh pairing warms only the newest
 *    [TITLE_WARM_LIMIT] -- four hundred round trips before the shell appears is not
 *    a first-run experience.
 *  - **"Where was I" is restored once per process.** The operator returning to the
 *    list means they want the list; being pulled back into the conversation is the
 *    app arguing with them.
 */
internal class SessionIndexCoordinator(
    private val store: StateStore,
    private val scope: CoroutineScope,
    private val note: (String) -> Unit,
    private val indexStore: SessionIndexStore,
    /** The live link, or `null` when nothing is connected. */
    private val link: () -> TitlesLink?,
    /** Where this phone last had the operator, per host. */
    private val lastSession: (hostId: String) -> String?,
    /** Opening a session is navigation, which belongs to whoever owns the screen. */
    private val openSession: (String) -> Unit,
) {

    /** Whether the one-time restore has already had its chance this process. */
    private var restoredOnce = false

    /** Recompute the sidebar's groups from the list, the workspaces and the selection. */
    fun regroup() {
        store.update { current ->
            current.copy(
                groups = SessionGrouping.groups(
                    current.sessions,
                    current.workspacePaths,
                    current.selectedSessionId,
                    current.workspaces.associate { it.path to it.title },
                ),
            )
        }
    }

    /** Fold a stored index in, additively, and regroup. */
    fun apply(index: SessionIndex) {
        // Additive only: a stored list must never wipe what a live read put on screen.
        store.update { current ->
            current.copy(
                sessions = if (current.sessions.isEmpty()) index.sessions else current.sessions,
                titles = index.titles + current.titles,
                workspacePaths = if (current.workspacePaths.isEmpty()) index.workspacePaths.toSet() else current.workspacePaths,
            )
        }
        regroup()
    }

    /** Read the stored index for a host, if there is one. */
    suspend fun restore(hostId: String) {
        indexStore.load(hostId)?.let { apply(it) }
    }

    /** Write what the list knows now, so the next launch opens on it. */
    suspend fun persist() {
        val current = store.state.value
        val host = current.host ?: return
        indexStore.save(
            SessionIndex(
                hostId = host.hostId,
                sessions = current.sessions,
                titles = current.titles,
                workspacePaths = current.workspacePaths.toList(),
            ),
        )
    }

    /** Load titles for rows about to be drawn, one request per session, once each. */
    fun loadTitles(sessionIds: List<String>) {
        scope.launch {
            val socket = link() ?: return@launch
            var learned = false
            for (id in sessionIds) {
                if (store.state.value.titles.containsKey(id)) continue
                if (learnTitle(socket, id)) learned = true
            }
            if (learned) persist()
        }
    }

    /**
     * Read the titles a fresh pairing has none of, with progress on screen.
     *
     * A phone that has just paired knows the session list but no names: every title
     * behind it is its own `session.stats` call, which the list otherwise fetches one
     * row at a time as the operator scrolls. On a first pairing that reads as a list
     * of blanks, so they are read once, here, while the connecting screen can still
     * say how far along it is.
     *
     * Bounded by [TITLE_WARM_LIMIT] and newest first: a host with four hundred
     * sessions should not make first pairing wait on four hundred round trips, and
     * the rows the operator is about to see are the recent ones. Whatever is left
     * over keeps the lazy path in [loadTitles].
     */
    suspend fun warmTitles() {
        val socket = link() ?: return
        val known = store.state.value.titles
        val pending = store.state.value.sessions
            .asSequence()
            .filter { !known.containsKey(it.sessionId) }
            .sortedByDescending { it.updatedMillis }
            .take(TITLE_WARM_LIMIT)
            .map { it.sessionId }
            .toList()
        if (pending.isEmpty()) return

        val recent = ArrayDeque<Long>()
        var done = 0
        store.update { it.copy(syncProgress = SyncProgress(done = 0, total = pending.size), syncEtaMillis = null) }
        for (id in pending) {
            val started = System.nanoTime()
            learnTitle(socket, id)
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            done++
            recent.addLast(elapsed)
            while (recent.size > SyncEta.SAMPLE_SIZE) recent.removeFirst()
            val eta = SyncEta.estimateMillis(done, pending.size, recent.toList())
            store.update {
                it.copy(syncProgress = SyncProgress(done = done, total = pending.size), syncEtaMillis = eta)
            }
        }
        persist()
        note("warmed $done/${pending.size} titles")
    }

    /**
     * Open what was open last time, once.
     *
     * Deliberately not on every list refresh: the operator returning to the list means
     * they want the list, and yanking them back into the conversation would be the app
     * arguing with them.
     */
    fun restoreLastSession() {
        if (restoredOnce) return
        restoredOnce = true
        val current = store.state.value
        if (current.selectedSessionId != null) return
        val hostId = current.host?.hostId ?: return
        val last = lastSession(hostId) ?: return
        if (current.sessions.none { it.sessionId == last }) return
        openSession(last)
    }

    /** One session's title, remembered when the host has one. */
    private suspend fun learnTitle(socket: TitlesLink, sessionId: String): Boolean {
        val title = runCatching {
            socket.stats(jsonObjectOf("sessionId" to jsonString(sessionId))).stats?.title
        }.getOrNull()
        if (title.isNullOrBlank()) return false
        store.update { it.copy(titles = it.titles + (sessionId to title)) }
        return true
    }

    private companion object {
        /** How many titles a fresh pairing reads before handing back to the lazy path. */
        const val TITLE_WARM_LIMIT = 80
    }
}
