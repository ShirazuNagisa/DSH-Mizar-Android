package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The running turn's activity log: a panel that polls once a second while it is
 * open, and pages backwards when the operator scrolls up.
 *
 * Two things make this worth its own object rather than a few methods on the view
 * model. The poll is a loop with a lifetime that outlives any one call, so somebody
 * has to own cancelling it -- switching sessions used to cancel it from three
 * hundred lines away. And the detail is a paid feature: a free account on a relay
 * never asks the host at all, which is a rule about this panel and nothing else.
 */
internal class ActivityCoordinator(
    private val store: StateStore,
    private val scope: CoroutineScope,
    /** The poll runs here: it is a read loop, not UI work. */
    private val pollDispatcher: CoroutineDispatcher,
    /** The sentence a failed read shows, built where the diagnostics live. */
    private val describe: (Throwable) -> String,
    /** The live link, or `null` when nothing is connected. */
    private val link: () -> ActivityLink?,
) {

    /** The one poll; a second toggle reuses or replaces it, never stacks. */
    private var job: Job? = null

    /**
     * Open or close the running turn's detail.
     *
     * The detail is a paid feature on iOS; this client accepts the same two keys:
     * a paying account, or any account on the LAN. On a relay socket with a free
     * account the control is not drawn and the host is never asked.
     */
    fun toggle() {
        if (store.state.value.activityExpanded) {
            cancel()
            store.update { it.copy(activityExpanded = false) }
            return
        }
        if (!store.state.value.advancedFeaturesUnlocked) return
        store.update { it.copy(activityExpanded = true, activityError = null) }
        job?.cancel()
        startPolling()
    }

    private fun startPolling() {
        job = scope.launch(pollDispatcher) {
            while (isActive && store.state.value.activityExpanded) {
                val socket = link() ?: break
                val sessionId = store.state.value.selectedSessionId ?: break
                if (store.state.value.activity == null) store.update { it.copy(activityLoading = true) }
                runCatching {
                    socket.page(sessionId, POLL_ENTRIES)
                }.onSuccess { page ->
                    store.update { it.copy(activity = page, activityError = null, activityLoading = false) }
                }.onFailure { error ->
                    store.update { it.copy(activityError = describe(error), activityLoading = false) }
                }
                delay(POLL_MILLIS)
            }
        }
    }

    /** Fetch the page before the oldest activity line on screen. */
    fun loadOlder() {
        val current = store.state.value.activity ?: return
        val turn = current.turn ?: return
        val oldest = current.oldestSeq ?: return
        if (!current.hasMore || store.state.value.activityLoadingOlder) return
        scope.launch {
            val socket = link() ?: return@launch
            store.update { it.copy(activityLoadingOlder = true) }
            runCatching {
                socket.page(current.sessionId, OLDER_PAGE_ENTRIES, beforeSeq = oldest.toInt(), turn = turn)
            }.onSuccess { page ->
                store.update {
                    val merged = (page.entries + it.activity?.entries.orEmpty())
                        .distinctBy { entry -> entry.id }
                        .takeLast(MAX_ENTRIES)
                    it.copy(activity = page.copy(entries = merged), activityLoadingOlder = false)
                }
            }.onFailure { error ->
                store.update { it.copy(activityLoadingOlder = false, activityError = describe(error)) }
            }
        }
    }

    /** Stop the poll: the session on screen is gone, or the panel was closed. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    private companion object {
        /** The open detail polls once a second, like iOS. */
        const val POLL_MILLIS = 1_000L

        /** How many lines the poll asks for, and how many a page back asks for. */
        const val POLL_ENTRIES = 60
        const val OLDER_PAGE_ENTRIES = 100

        /** Older pages accumulate up to this many lines before the oldest are dropped. */
        const val MAX_ENTRIES = 1500
    }
}
