package me.shirazu.dshmizar.domain

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.shirazu.mizar.kit.account.Announcement
import me.shirazu.mizar.kit.account.AnnouncementButton
import me.shirazu.mizar.kit.account.AnnouncementEvent
import me.shirazu.mizar.kit.account.AnnouncementTarget

/**
 * The operator's banners: what the relay pushes to phones, and the decision of which
 * one is on screen.
 *
 * A banner is a title, a sentence and at most one button. It is not a screen and not
 * a decision the operator has to answer, so nothing here blocks: the queue shows one
 * at a time, it goes away by itself after fifteen seconds, and the worst outcome of a
 * bug is one shown twice or one missed.
 *
 * Delivery is best effort by design -- the relay broadcasts to whoever is connected,
 * and a phone that was asleep misses it. What this guarantees is that a banner is
 * shown once per *phone*: the ids it has displayed are written to
 * [AnnouncementSeenStore], so a reconnect or a relaunch does not repeat one.
 *
 * The wire shape and the socket live in mizarkit (`Announcements.kt`,
 * `AnnouncementClient`); this file owns the decision, and is tested without a server.
 */

// MARK: - What the button does

/**
 * What a banner button does, once this build recognises its target.
 *
 * The kit validates the target vocabulary ([AnnouncementTarget]); this is the app
 * navigation it implies. `account` is not a settings shelf, and a URL is not a shelf
 * either, which is why the three cases are separate.
 */
sealed interface AnnouncementAction {

    /** Open one page of the settings directory. */
    data class Settings(val shelf: AnnouncementTarget) : AnnouncementAction

    /** Open the account screen (the server's `account` target). */
    data object Account : AnnouncementAction

    /** Open a link in the browser; the server may hand a release page or a post. */
    data class External(val url: String) : AnnouncementAction
}

/**
 * The server's target vocabulary and the one rule that keeps a bad target harmless: a
 * target this build does not know is not a reason to drop the banner, only a reason
 * not to draw its button.
 */
object AnnouncementTargets {

    /** The prefix a deployment may use for a shelf name (`settings:notifications`). */
    const val SETTINGS_PREFIX = "settings:"

    /** Resolve one server target, or `null` when this build cannot act on it. */
    fun resolve(target: String): AnnouncementAction? {
        val value = target.trim()
        if (value.isEmpty()) return null
        if (value.startsWith(SETTINGS_PREFIX)) {
            val shelf = AnnouncementTarget.of(value.removePrefix(SETTINGS_PREFIX)) ?: return null
            return actionFor(shelf)
        }
        AnnouncementTarget.of(value)?.let { return actionFor(it) }
        return if (isHttpUrl(value)) AnnouncementAction.External(value) else null
    }

    /** Whether a target is a URL a phone can hand to a browser. */
    fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)

    private fun actionFor(shelf: AnnouncementTarget): AnnouncementAction =
        if (shelf == AnnouncementTarget.ACCOUNT) AnnouncementAction.Account else AnnouncementAction.Settings(shelf)
}

/** Where this banner's button goes, or `null` when there is no button this build can act on. */
fun Announcement.action(): AnnouncementAction? {
    val button = button ?: return null
    if (button.label.isBlank()) return null
    return AnnouncementTargets.resolve(button.target)
}

/** Whether the server asked for attention rather than plain information. */
val Announcement.isNotice: Boolean get() = tone == "notice"

/** The button and its destination, when both exist. */
val Announcement.actionable: Pair<AnnouncementButton, AnnouncementAction>?
    get() {
        val button = button ?: return null
        val action = action() ?: return null
        return button to action
    }

// MARK: - What the screen draws

/** The banner on screen, and what waits behind it. */
data class AnnouncementBannerState(
    /** The banner on screen, if any. */
    val announcement: Announcement? = null,
    /** Banners that arrived while one was already up, oldest first. */
    val queued: Int = 0,
    /** How many ids this device remembers having shown. */
    val shownCount: Int = 0,
)

// MARK: - Persistence

/**
 * The ids this device has already shown.
 *
 * Injectable so the rule can be tested on the JVM; [AnnouncementPreferenceStore] is
 * the real one. The order matters: the oldest id is dropped when the bound is
 * reached, which is why a plain `StringSet` is not used.
 */
interface AnnouncementSeenStore {

    /** Ids already displayed on this device, oldest first. */
    fun shown(): List<String>

    /** Record one id, trimming the oldest past [REMEMBERED_LIMIT]. */
    fun remember(id: String)

    /** Forget every id. The debug fresh-start hook and tests use this. */
    fun clear()

    companion object {
        /**
         * How many ids are remembered. A banner's id is a UUID, so this is only a
         * bound on a record that would otherwise grow for every announcement ever.
         */
        const val REMEMBERED_LIMIT = 200
    }
}

/**
 * [AnnouncementSeenStore] on plain preferences, in the house style of
 * `store/ActivityStore.kt` but its own file: none of this is a credential, and an
 * unavailable keystore must not stop a banner from being remembered.
 */
class AnnouncementPreferenceStore(context: Context) : AnnouncementSeenStore {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun shown(): List<String> =
        prefs.getString(KEY, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    override fun remember(id: String) {
        if (id.isEmpty()) return
        val ids = shown().toMutableList()
        if (id in ids) return
        ids += id
        while (ids.size > AnnouncementSeenStore.REMEMBERED_LIMIT) ids.removeAt(0)
        prefs.edit().putString(KEY, ids.joinToString(SEPARATOR)).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        /** The preferences file, separate from the activity store so clearing one is safe. */
        const val FILE = "mizar-announcements"

        /** The key the shown ids live under. */
        const val KEY = "shown"

        /** Ids are UUIDs, so a newline can never be part of one. */
        private const val SEPARATOR = "\n"
    }
}

// MARK: - The feed

/** The announcement socket, as the coordinator needs it: mizarkit's `AnnouncementClient.events`. */
fun interface AnnouncementFeed {

    /**
     * Open the announcement socket and yield events until the caller stops
     * collecting: the first emission is the snapshot, later ones a single banner.
     * Reconnection is the client's job.
     */
    fun events(token: String): Flow<AnnouncementEvent>
}

// MARK: - The decision

/**
 * Which banner is on screen, which ones this phone has already shown, and when the
 * socket should be listening at all.
 *
 * @param seen the persisted shown ids.
 * @param scope owns the socket collection, the foreground poll and the auto-dismiss timer.
 * @param feed the socket (`AnnouncementClient::events`).
 * @param poll the HTTP twin, read once each time the app comes to the foreground
 *   with a session, so a banner created while the phone was suspended is not missed.
 * @param clock the time, injectable for tests.
 * @param visibleMillis how long a banner stays up on its own.
 */
internal class AnnouncementCoordinator(
    private val seen: AnnouncementSeenStore,
    private val scope: CoroutineScope,
    private val feed: AnnouncementFeed,
    private val poll: (suspend (String) -> List<Announcement>)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val visibleMillis: Long = VISIBLE_MILLIS,
) {

    private val mutable = MutableStateFlow(AnnouncementBannerState(shownCount = seen.shown().size))

    /** The banner on screen and what waits behind it. */
    val state: StateFlow<AnnouncementBannerState> = mutable.asStateFlow()

    /** The socket collection, while one is wanted. */
    private var feedJob: Job? = null
    private var runningToken: String? = null

    /** The banner's own timer; one at a time, replaced with the banner. */
    private var dismissJob: Job? = null

    /** Banners that arrived while one was already up, oldest first. */
    private val queue = ArrayDeque<Announcement>()

    // -- Socket lifecycle ----------------------------------------------------

    /**
     * Start or stop the socket to match the app's state.
     *
     * @param token the account session token, or `null` when signed out.
     * @param active whether the app is in the foreground.
     */
    fun update(token: String?, active: Boolean) {
        val wanted = token?.takeIf { it.isNotEmpty() && active }
        if (wanted == null) {
            stop()
            return
        }
        // Reconnecting for the same token would tear down a socket that is already
        // doing the one job it has.
        if (runningToken == wanted && feedJob?.isActive == true) return
        stop()
        runningToken = wanted
        feedJob = scope.launch {
            feed.events(wanted).collect { event -> deliver(event) }
        }
        // One read per foreground: the socket's snapshot covers a reconnect, but a
        // phone that was suspended while a banner was created has no snapshot to
        // miss it from. The dedupe above makes the overlap harmless.
        val read = poll ?: return
        scope.launch {
            runCatching { read(wanted) }
                .onSuccess { announcements ->
                    // A sign-out may have landed while the read was in flight.
                    if (runningToken == wanted) deliver(announcements)
                }
        }
    }

    /** Close the socket and forget why it was open. The banner on screen stays. */
    fun stop() {
        feedJob?.cancel()
        feedJob = null
        runningToken = null
    }

    // -- Showing -------------------------------------------------------------

    /** Sort one socket event into the queue. */
    fun deliver(event: AnnouncementEvent) {
        when (event) {
            is AnnouncementEvent.Snapshot -> deliver(event.announcements)
            is AnnouncementEvent.Created -> deliver(listOf(event.announcement))
        }
    }

    /**
     * Sort what the relay just sent into the queue.
     *
     * A snapshot can carry several banners; the newest live one this phone has not
     * shown goes on screen and the rest wait, in the same order. Ids already shown
     * -- including the one on screen now -- are dropped here, which is the whole
     * per-device dedupe rule.
     */
    fun deliver(announcements: List<Announcement>) {
        if (announcements.isEmpty()) return
        val shown = seen.shown().toHashSet()
        val now = clock()
        val candidates = announcements
            .filter { announcement -> announcement.isLive(now) && announcement.id !in shown }
            // Newest first: a snapshot that knows about three unshown banners should
            // lead with the one the operator has not seen.
            .sortedByDescending { it.createdAt ?: Long.MIN_VALUE }
            .distinctBy { it.id }
        if (candidates.isEmpty()) return
        for (announcement in candidates) {
            val onScreen = mutable.value.announcement
            when {
                onScreen == null -> show(announcement)
                onScreen.id == announcement.id -> Unit
                queue.any { it.id == announcement.id } -> Unit
                else -> queue.addLast(announcement)
            }
        }
        publish(mutable.value.announcement)
    }

    /** Dismiss the banner on screen and move to the next one, if any. */
    fun dismiss() {
        if (mutable.value.announcement == null && queue.isEmpty()) return
        dismissJob?.cancel()
        dismissJob = null
        val next = queue.removeFirstOrNull()
        if (next == null) publish(null) else show(next)
    }

    /**
     * The button took the operator somewhere, so the banner has done its job. The
     * caller navigates; this only takes the banner down.
     */
    fun acted() {
        dismiss()
    }

    /** Whether this phone has already shown one. */
    fun isShown(id: String): Boolean = id in seen.shown()

    /** Forget every shown id; a debug build's fresh-start hook uses this. */
    fun forgetShown() {
        seen.clear()
        publish(mutable.value.announcement)
    }

    private fun show(announcement: Announcement) {
        markShown(announcement.id)
        publish(announcement)
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(visibleMillis)
            dismiss()
        }
    }

    /** Record an id, trimming the oldest when the bound is reached. */
    private fun markShown(id: String) {
        if (id.isEmpty() || isShown(id)) return
        seen.remember(id)
    }

    private fun publish(announcement: Announcement?) {
        mutable.update {
            it.copy(
                announcement = announcement,
                queued = queue.size,
                shownCount = seen.shown().size,
            )
        }
    }

    companion object {
        /** How long a banner stays up on its own, as on iOS. */
        const val VISIBLE_MILLIS = 15_000L
    }
}
