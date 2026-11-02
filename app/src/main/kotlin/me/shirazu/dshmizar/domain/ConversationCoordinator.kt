package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.shirazu.dshmizar.Phase
import me.shirazu.mizar.kit.Conversation
import me.shirazu.mizar.kit.MizarError
import me.shirazu.mizar.kit.MizarErrorCode
import me.shirazu.mizar.kit.SessionLineage
import me.shirazu.mizar.kit.SessionMessagesResult
import me.shirazu.mizar.kit.SessionRecord
import me.shirazu.mizar.kit.SessionStatsFrame
import me.shirazu.mizar.kit.SessionStatsResult
import me.shirazu.mizar.kit.jsonBool
import me.shirazu.mizar.kit.jsonInt
import me.shirazu.mizar.kit.get
import me.shirazu.mizar.kit.intValue
import me.shirazu.mizar.kit.jsonString
import me.shirazu.mizar.kit.mergeConversations
import me.shirazu.mizar.kit.mergeTurns

/** One full page: the window a session opens with. */
internal const val CONVERSATION_FULL_PAGE = 200

/** A tail read: what a pull-to-refresh or a quiet catch-up asks for. */
internal const val CONVERSATION_REFRESH_PAGE = 60

/**
 * Reading the open conversation, and folding each page into the transcript.
 *
 * Everything here is about *when an answer may land*, which is why it is a separate
 * object rather than a handful of methods on the view model:
 *
 *  - **One read at a time.** The latch ([loading]) and the flag a second request
 *    raises ([reloadWanted]) mean two taps, or a tap racing the automatic refresh
 *    after a reconnect, cannot run two round trips over each other's cursors.
 *  - **A reply belongs to the socket that asked.** The epoch is read when the read
 *    starts and compared when the answer arrives; a page from a socket that has since
 *    been replaced is dropped instead of being merged into another computer's session.
 *  - **A page read asks from where this screen is, or says it holds nothing.**
 *    `afterSeq: null` is that claim; omitting the key is silence, and the host answers
 *    silence from its delivery ledger -- which is how a cold screen opens empty.
 *
 * The session *list*, sending, cancelling and the title index are not here: this
 * object only ever reads one session's log.
 */
internal class ConversationCoordinator(
    private val store: StateStore,
    private val scope: CoroutineScope,
    private val note: (String) -> Unit,
    /** The sentence a failed call shows, built where the diagnostics live. */
    private val describe: (Throwable) -> String,
    /** The connection's epoch, part of every decision about a late answer. */
    private val epoch: () -> Int,
    /** The live link, or `null` when nothing is connected. */
    private val link: () -> ConversationLink?,
    /** A figures frame goes on the bar through the domain that owns it. */
    private val applyStats: (SessionStatsFrame?) -> Unit,
    private val recordPage: suspend (hostId: String, sessionId: String, page: SessionMessagesResult) -> Unit,
    private val recordStats: suspend (hostId: String, sessionId: String, stats: SessionStatsResult) -> Unit,
) {

    /** Guards the load, and remembers a request that arrived during one. */
    private var loading = false
    private var reloadWanted = false

    /**
     * Whether the host implements `session.refresh`.
     *
     * Asked once per process. The call is an optimisation -- one round trip for the
     * whole screen instead of three -- and a host without it answers NOT_IMPLEMENTED
     * every single time, so re-asking on every pull spends a round trip to be told the
     * same thing. The 0.8.3 plugin does not implement it; the three plain reads are
     * the path that runs, and they are the path this build should be judged on.
     */
    private var refreshBundleSupported = true

    /** The transcript's shape as last logged, so the diagnostic line only moves when it does. */
    private var lastTurnShape: String? = null

    /**
     * Load the open session.
     *
     * The retry is for a host that has the session but has not written its log yet:
     * the screen is READY and still has nothing to show, so one more read half a
     * second later is what turns a blank card into a conversation.
     */
    suspend fun reload(tail: Int = CONVERSATION_FULL_PAGE, afterSeq: Int? = null) {
        if (loading) {
            reloadWanted = true
            return
        }
        loading = true
        try {
            readOnce(tail, afterSeq)
            val current = store.state.value
            if (current.phase == Phase.READY && (current.stats == null || current.conversation.isEmpty())) {
                delay(RETRY_AFTER_MILLIS)
                readOnce(tail, afterSeq)
            }
            while (reloadWanted) {
                reloadWanted = false
                readOnce(tail, afterSeq)
            }
        } finally {
            loading = false
        }
    }

    /**
     * One page of records, then the figures.
     *
     * Two calls, two chances to fail, and they fail separately: a page that could not
     * be read leaves the transcript alone and says so under the title, while a figures
     * read that failed must not clear the transcript's error or the other way round.
     */
    suspend fun readOnce(tail: Int = CONVERSATION_FULL_PAGE, afterSeq: Int? = null) {
        val socket = link() ?: return
        val sessionId = store.state.value.selectedSessionId ?: return
        val started = epoch()
        store.update { it.copy(loadingConversation = true) }

        try {
            val page = socket.messages(pagePayload(sessionId, tail, afterSeq))
            if (started == epoch()) {
                applyPage(page, forSession = sessionId)
            } else {
                note("discarding a page from a replaced socket")
            }
        } catch (error: Throwable) {
            if (started == epoch()) {
                store.update { it.copy(conversationError = describe(error)) }
            } else {
                note("ignoring a failure from a replaced socket")
            }
        }

        try {
            val stats = socket.stats(payload("sessionId" to jsonString(sessionId)))
            if (started != epoch()) {
                store.update { it.copy(loadingConversation = false) }
                return
            }
            applyStats(stats.stats)
            cacheStats(sessionId, stats)
        } catch (error: Throwable) {
            if (started == epoch()) {
                store.update { it.copy(statsError = describe(error)) }
            }
        }

        store.update { it.copy(loadingConversation = false) }
    }

    /**
     * A refresh in one round trip: transcript delta, figures and pending cards together.
     *
     * Three sequential calls on a tunnel is three times the latency for one screen
     * refresh. The host answers them concurrently as `session.refresh`.
     *
     * @returns false when the host does not know the method, so the caller can fall
     *   back to the three separate calls -- an older plugin must not lose the feature.
     */
    suspend fun refreshBundle(tail: Int = CONVERSATION_REFRESH_PAGE, afterSeq: Int? = null): Boolean {
        if (!refreshBundleSupported) return false
        val socket = link() ?: return true
        val sessionId = store.state.value.selectedSessionId ?: return true
        val started = epoch()
        val bundle = try {
            socket.refresh(
                payload(
                    *buildList {
                        addAll(addressOf(sessionId))
                        add("maxMessages" to jsonInt(tail))
                        // Absent, not null: a refresh always has a position to ask from,
                        // and `null` would claim the screen holds nothing.
                        afterSeq?.let { add("afterSeq" to jsonInt(it)) }
                    }.toTypedArray(),
                ),
            )
        } catch (error: Throwable) {
            if ((error as? MizarError)?.known == MizarErrorCode.NOT_IMPLEMENTED) {
                note("host does not implement session.refresh; using the three reads")
                refreshBundleSupported = false
            }
            return false
        }
        // The answer belongs to a socket that is no longer the live one: let the
        // caller read again on the new connection instead of merging this one in.
        if (started != epoch()) {
            note("discarding a bundle from a replaced socket")
            return false
        }
        applyPage(bundle.messages, forSession = sessionId)
        bundle.stats?.let { stats ->
            applyStats(stats.stats)
            cacheStats(sessionId, stats)
        }
        bundle.pending?.let { pending -> store.update { it.copy(pending = pending) } }
        return true
    }

    /**
     * Load an older page.
     *
     * @returns the number of items added, `0` when the host says there is nothing
     *   older, or `null` when it could not be asked — which must never be read as
     *   "the top of the conversation".
     */
    suspend fun loadOlder(): Int? {
        val socket = link() ?: return null
        val current = store.state.value
        val sessionId = current.selectedSessionId ?: return null
        val before = current.oldestSeq ?: return null
        if (!current.hasMore) {
            store.update { it.copy(exhausted = true) }
            return 0
        }

        return try {
            val page = socket.messages(
                payload(
                    *addressOf(sessionId),
                    "maxMessages" to jsonInt(OLDER_PAGE),
                    "beforeSeq" to jsonInt(before),
                ),
            )
            val reached = page.records.firstNotNullOfOrNull { SessionRecord(it).payload["seq"].intValue() }
            // The host answering with the same cut it was asked from is the host
            // saying "there is nothing above this" -- not a page that moved.
            if (reached == null || reached == before || page.records.isEmpty()) {
                store.update { it.copy(exhausted = true, hasMore = false) }
                return 0
            }
            applyPageContext(page)
            val fresh = Conversation.items(page)
            val freshTurns = Conversation.turns(page)
            val held = store.state.value.conversation.size
            store.update {
                it.copy(
                    conversation = mergeConversations(it.conversation, fresh),
                    turns = mergeTurns(it.turns, freshTurns),
                    oldestSeq = listOfNotNull(before, reached).minOrNull(),
                    hasMore = page.hasMore,
                )
            }
            store.state.value.conversation.size - held
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Read one turn whole: the narration and the tool calls a summary page drops.
     *
     * Gated with the attachments -- a paid account, or a local link -- because it
     * is the same kind of cost: one turn's detail can weigh what a whole summary
     * page does. Asked for per turn, when the operator opens it, never as part of
     * a refresh.
     *
     * The answer is merged rather than applied: a detail page carries one turn,
     * and running it through [applyPage] would move the paging cursors to that
     * turn's edges and break "load older".
     */
    fun loadTurnDetail(turn: Int) {
        val current = store.state.value
        if (!current.advancedFeaturesUnlocked) return
        val sessionId = current.selectedSessionId ?: return
        scope.launch {
            val socket = link() ?: return@launch
            val page = runCatching {
                socket.messages(
                    payload(
                        *addressOf(sessionId),
                        "full" to jsonBool(true),
                        "turn" to jsonInt(turn),
                    ),
                )
            }.onFailure { note("turn $turn detail failed: ${it.message}") }.getOrNull() ?: return@launch

            val fresh = Conversation.turns(page)
            val items = Conversation.items(page)
            store.update { state ->
                state.copy(
                    conversation = mergeConversations(state.conversation, items),
                    turns = mergeTurns(state.turns, fresh),
                )
            }
            note("turn $turn detail: ${page.records.size} records")
        }
    }

    /**
     * Put a page the phone already had on screen, before the first round trip.
     *
     * Opening a session offline shows its conversation rather than an empty card; the
     * read that follows is what makes it current.
     */
    fun show(page: SessionMessagesResult, forSession: String) = applyPage(page, forSession)

    /**
     * Fold one page of records into the transcript.
     *
     * @param forSession the session this page was read for. The epoch covers a replaced
     *   socket, not a switched session: without this, a page still in flight when the
     *   operator opened another conversation was folded into the new one -- one
     *   computer's transcript under another session's title, with no error anywhere.
     */
    private fun applyPage(page: SessionMessagesResult, forSession: String) {
        val open = store.state.value.selectedSessionId
        if (open != forSession) {
            note("discarding a page for $forSession; ${open ?: "nothing"} is open")
            return
        }
        val fresh = Conversation.items(page)
        val freshTurns = Conversation.turns(page)
        val oldest = page.records.firstNotNullOfOrNull { SessionRecord(it).payload["seq"].intValue() }
        store.update { current ->
            current.copy(
                conversation = mergeConversations(current.conversation, fresh),
                turns = mergeTurns(current.turns, freshTurns),
                oldestSeq = listOfNotNull(current.oldestSeq, oldest).minOrNull(),
                hasMore = page.hasMore,
                conversationError = null,
                // Where this page ended. Kept even when the delta was empty, so the next
                // refresh still asks from the right place.
                newestSeq = listOfNotNull(current.newestSeq, page.asOfSeq).maxOrNull(),
            )
        }
        applyPageContext(page)
        // One line when the shape of the transcript changes, not one per poll: a
        // turn's reason is what the summary row reads, and "the row says the wrong
        // thing" is unanswerable without knowing which reason arrived.
        val shape = store.state.value.turns.joinToString("|") {
            "${it.turn}:${it.endedReason}:${it.prompts.size}:${it.steps.size}"
        }
        if (shape != lastTurnShape) {
            lastTurnShape = shape
            note(
                "turns: " + store.state.value.turns.joinToString(" | ") {
                    "t=${it.turn} reason=${it.endedReason} p=${it.prompts.size} s=${it.steps.size} failed=${it.failed} running=${it.isRunning}"
                },
            )
        }
        val hostId = store.state.value.host?.hostId
        val sessionId = store.state.value.selectedSessionId
        if (hostId != null && sessionId != null) {
            scope.launch { recordPage(hostId, sessionId, page) }
        }
    }

    /**
     * Put a page's context figure on the bar.
     *
     * Every `session.messages` page carries the occupancy as of its cut (plugin
     * 0.8.9), derived from the same projection baseline that names the cut. The
     * incremental refresh — the one an open screen actually runs while a turn is
     * going — never asks for `session.stats`, so before this the composer's figure
     * froze at whatever the last full load saw.
     *
     * A page without the figure leaves the frame alone: unknown, never zero.
     */
    private fun applyPageContext(page: SessionMessagesResult) {
        val pressure = page.context ?: return
        val before = store.state.value.stats?.context?.percent
        store.update { current ->
            current.copy(stats = (current.stats ?: SessionStatsFrame()).copy(context = pressure))
        }
        // Only when it moves: this runs on every page, and a line per refresh would
        // bury the rest of the log. A figure that *stops* moving is the bug this
        // exists to catch, and silence here is how that reads.
        if (before != pressure.percent) note("context ${pressure.percent}% from page")
    }

    private fun cacheStats(sessionId: String, stats: SessionStatsResult) {
        val hostId = store.state.value.host?.hostId ?: return
        scope.launch { recordStats(hostId, sessionId, stats) }
    }

    /**
     * The address every page read starts with.
     *
     * A delegated child is not addressable as a session: the host answers
     * `session/agent-busy` before it touches the log, so the parent has to travel
     * with the request (plugin 0.8.9 rebuilds the subagent address from it). A fork
     * carries a parent and is not a child -- see [SessionLineage].
     */
    private fun addressOf(sessionId: String): Array<Pair<String, JsonElement>> =
        buildList {
            add("sessionId" to jsonString(sessionId))
            SessionLineage.parentOf(store.state.value.sessions, sessionId)
                ?.let { add("parentSessionId" to jsonString(it)) }
        }.toTypedArray()

    private fun payload(vararg members: Pair<String, JsonElement>): JsonObject =
        buildJsonObject { for ((key, value) in members) put(key, value) }

    private fun pagePayload(sessionId: String, tail: Int, afterSeq: Int?): JsonObject =
        payload(
            *buildList {
                addAll(addressOf(sessionId))
                add("maxMessages" to jsonInt(tail))
                // A number is this screen's own claim about what it holds; `null`
                // is the claim that it holds **nothing**. The difference matters
                // since plugin 0.8.3: an *absent* key is silence, and the host
                // answers silence from a ledger of what it already delivered to
                // this device -- so a cold screen that stayed quiet would be sent
                // only the records after the last delivery and open empty.
                add("afterSeq" to (afterSeq?.let { jsonInt(it) } ?: JsonNull))
            }.toTypedArray(),
        )

    private companion object {
        /** The page an "older" read asks for: the same size as a first page. */
        const val OLDER_PAGE = 200

        /** How long to wait before the second read that catches a just-written log. */
        const val RETRY_AFTER_MILLIS = 700L
    }
}
