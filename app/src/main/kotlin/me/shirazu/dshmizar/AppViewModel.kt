package me.shirazu.dshmizar

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.shirazu.dshmizar.push.PushTokens
import me.shirazu.dshmizar.push.Notifications
import me.shirazu.dshmizar.store.ActivityStore
import me.shirazu.dshmizar.ui.ThemeMode
import me.shirazu.dshmizar.store.EncryptedCredentialStore
import me.shirazu.dshmizar.store.FileSessionIndexStore
import me.shirazu.mizar.kit.AcceptanceResult
import me.shirazu.mizar.kit.ApprovalOutcome
import me.shirazu.mizar.kit.Backoff
import me.shirazu.mizar.kit.Conversation
import me.shirazu.mizar.kit.ConversationItem
import me.shirazu.mizar.kit.ConversationTurn
import me.shirazu.mizar.kit.CreateSessionResult
import me.shirazu.mizar.kit.CredentialStore
import me.shirazu.mizar.kit.Diagnoses
import me.shirazu.dshmizar.ui.Format
import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.ModelCatalog
import me.shirazu.mizar.kit.ModelSelection
import me.shirazu.mizar.kit.PairedHost
import me.shirazu.mizar.kit.PairingTarget
import me.shirazu.mizar.kit.PairingClient
import me.shirazu.mizar.kit.PendingInteractions
import me.shirazu.mizar.kit.PermissionCatalog
import me.shirazu.mizar.kit.PromptResult
import me.shirazu.mizar.kit.QuestionAnswer
import me.shirazu.mizar.kit.SessionIndex
import me.shirazu.mizar.kit.SessionIndexStore
import me.shirazu.mizar.kit.SessionGrouping
import me.shirazu.mizar.kit.SessionListResult
import kotlinx.serialization.json.contentOrNull
import me.shirazu.mizar.kit.SessionMessagesResult
import me.shirazu.mizar.kit.SessionRecord
import me.shirazu.mizar.kit.SessionRow
import me.shirazu.mizar.kit.SessionStatsFrame
import me.shirazu.mizar.kit.SessionStatsResult
import me.shirazu.mizar.kit.ServerFrame
import me.shirazu.mizar.kit.WorkspaceGroup
import me.shirazu.mizar.kit.WorkspaceListResult
import me.shirazu.mizar.kit.get
import me.shirazu.mizar.kit.intValue
import me.shirazu.mizar.kit.jsonBool
import me.shirazu.mizar.kit.jsonString
import me.shirazu.mizar.kit.mergeConversations
import me.shirazu.mizar.kit.mergeTurns
import java.io.IOException
import java.util.UUID

/** Which screen the app is on, which is a property of the connection rather than of navigation. */
enum class Phase { LAUNCHING, UNPAIRED, CONNECTING, READY, FAILED }

/** Everything the UI draws. */
data class UiState(
    val phase: Phase = Phase.LAUNCHING,
    val hosts: List<PairedHost> = emptyList(),
    val host: PairedHost? = null,
    val welcome: ServerFrame.Welcome? = null,
    val sessions: List<SessionRow> = emptyList(),
    val titles: Map<String, String> = emptyMap(),
    val workspacePaths: Set<String> = emptySet(),
    val groups: List<WorkspaceGroup> = emptyList(),
    val selectedSessionId: String? = null,
    val conversation: List<ConversationItem> = emptyList(),
    val turns: List<ConversationTurn> = emptyList(),
    val oldestSeq: Int? = null,
    val hasMore: Boolean = false,
    val exhausted: Boolean = false,
    val stats: SessionStatsFrame? = null,
    val pending: PendingInteractions? = null,
    val modelCatalog: ModelCatalog? = null,
    val permissionCatalog: PermissionCatalog? = null,
    val modelChoice: Map<String, ModelSelection> = emptyMap(),
    val problem: String? = null,
    /** A dropped socket is being retried; the screen stays as it was. */
    val reconnecting: Boolean = false,
    /** Why the last connection ended, shown beside the reconnect notice. */
    val disconnectReason: String? = null,
    /**
     * The host refused this phone's credential: only pairing again can help, so the
     * screen must offer that instead of a retry that cannot work.
     */
    val needsPairing: Boolean = false,
    val statsError: String? = null,
    val conversationError: String? = null,
    val loadingConversation: Boolean = false,
    val busy: Boolean = false,
    /** A refresh is in flight, so the screen can say so and refuse a second one. */
    val refreshing: Boolean = false,
    /** The log position this screen has read up to, so a refresh can ask for a delta. */
    val newestSeq: Int? = null,
    /** How the screen is lit; the settings dialog can override the system. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Silence finished-turn notices at night; the settings dialog owns the switch. */
    val quietHours: Boolean = false,
    /** When each session was last on screen, so the list can mark what is new. */
    val seenAt: Map<String, Long> = emptyMap(),
)

/**
 * The app's one state holder.
 *
 * It owns the connection, the session list, the open conversation and the
 * interaction surface, because all four are views of the same socket: a fresh
 * connection invalidates every one of them together.
 *
 * Two behaviours here are ports of bugs the iOS app paid for:
 *
 *  - **A refresh that arrives while one is running is remembered, not dropped.**
 *    Dropping it made the screen ignore the operator asking it to refresh.
 *  - **A load that returns nothing on a live connection is retried once.** The first
 *    request after a launch lands while the socket is still settling, and without
 *    the retry the figures above the composer and the transcript both stay blank
 *    until the operator leaves the session and comes back.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val credentials: CredentialStore = EncryptedCredentialStore(application)
    private val activity = ActivityStore(application)
    private val indexStore: SessionIndexStore = FileSessionIndexStore(application)

    private val _state = MutableStateFlow(
        UiState(
            themeMode = activity.themeMode().toThemeMode(),
            quietHours = activity.quietHours(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var connection: MizarConnection? = null
    /** Watches the live connection's state, so a dropped socket is noticed. */
    private var connectionObserver: Job? = null
    /** The single automatic retry loop; never two at once. */
    private var reconnectJob: Job? = null
    /** Last presence we reported, re-asserted after a reconnect. */
    // False until an activity says otherwise: a process restarted in the background by
    // the foreground service is *not* being watched, and defaulting to true made the app
    // suppress exactly the notifications that restart exists to deliver.
    private var foreground = false

    /** When the screen last read the host, so returning to it can decide whether to refresh. */
    private var lastRefreshAt = 0L

    /**
     * Which sessions were running the last time the list was read.
     *
     * A turn that ends is the other thing worth a notification: the operator asked for
     * something, put the phone down, and the answer is ready. The host does not push a
     * "turn finished" signal to this client, so the transition is observed where the list
     * is refreshed rather than waited for.
     */
    private val wasRunning = mutableMapOf<String, Boolean>()

    /**
     * What keeps the two announcing paths from buzzing twice for one turn.
     *
     * See [me.shirazu.mizar.kit.CompletionAnnouncer]: it remembers *when* a notice went
     * out, because a flag outlived the turn it described and swallowed the next one.
     */
    private val announcer = me.shirazu.mizar.kit.CompletionAnnouncer(ANNOUNCE_DEDUPE_MILLIS)

    /** Whether the last-opened conversation has already been restored this process. */
    private var restoredOnce = false

    /** Signature of the pending set already announced, so a batch buzzes once. */
    private var lastNotified: String? = null
    private var watchers: Job? = null

    /** Guards the load, and remembers a request that arrived during one. */
    private var loading = false
    private var reloadWanted = false

    private val appVersion: String get() = BuildConfig.VERSION_NAME
    private val deviceModel: String
        get() = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")

    // ── launch, pairing, connection ──────────────────────────────────────────

    /** Load what is stored and connect to the most recent host. */
    fun start() {
        viewModelScope.launch {
            val hosts = credentials.loadAll().sortedBy { it.pairedAtMillis }
            val host = hosts.lastOrNull()
            if (host == null) {
                _state.update { it.copy(phase = Phase.UNPAIRED, hosts = emptyList()) }
                return@launch
            }
            _state.update { it.copy(phase = Phase.CONNECTING, hosts = hosts, host = host) }
            connect(host)
        }
    }

    /** Redeem a scanned or typed pairing code. */
    fun pair(target: PairingTarget, deviceName: String) {
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.CONNECTING, problem = null) }
            val client = PairingClient(appVersion = appVersion)
            try {
                val paired = client.pair(
                    code = target.code,
                    deviceName = deviceName.ifBlank { "Android" },
                    deviceModel = deviceModel,
                    authority = target.authority,
                    baseUrl = target.baseUrl,
                )
                credentials.save(paired)
                val hosts = credentials.loadAll().sortedBy { it.pairedAtMillis }
                _state.update { it.copy(hosts = hosts, host = paired) }
                connect(paired)
            } catch (error: Throwable) {
                _state.update { it.copy(phase = Phase.FAILED, problem = messageOf(error, target.authority ?: target.baseUrl)) }
            }
        }
    }

    private suspend fun connect(host: PairedHost) {
        stopWatchers()
        connection?.disconnect()
        connection = null

        // The stored list first: a phone with no network still shows what it knows.
        indexStore.load(host.hostId)?.let { applyIndex(it) }

        var failure: Throwable? = null
        for (endpoint in host.endpoints) {
            val candidate = MizarConnection(host.configurationFor(endpoint, appVersion, deviceModel))
            try {
                val welcome = candidate.connect()
                connection = candidate
                _state.update {
                    it.copy(
                        phase = Phase.READY,
                        welcome = welcome,
                        problem = null,
                        needsPairing = false,
                        seenAt = activity.seenAt(host.hostId),
                    )
                }
                startWatchers(candidate)
                watchConnection(candidate, host)
                note("connected to $endpoint")
                refreshSessions()
                registerPush(candidate)
                return
            } catch (error: Throwable) {
                note("endpoint $endpoint failed: ${error.message}")
                failure = error
                candidate.disconnect()
            }
        }

        val address = host.authority ?: host.baseUrl
        _state.update {
            it.copy(
                phase = Phase.FAILED,
                problem = messageOf(failure ?: IOException("no address answered"), address),
                needsPairing = failure?.isRepairNeeded() == true,
            )
        }
    }

    /**
     * Notice when the live connection dies, and reconnect.
     *
     * The connection layer already reports a close — `closeSocket` ends on
     * `State.Failed` — but nothing in this app ever watched that state. So a dropped
     * socket left the app looking connected: sessions still listed, every poll failing
     * silently, no error anywhere, and no way back except relaunching the app. That is
     * exactly what a host restart used to do to the phone.
     */
    private fun watchConnection(socket: MizarConnection, host: PairedHost) {
        connectionObserver?.cancel()
        connectionObserver = viewModelScope.launch {
            socket.state.collect { state ->
                // Only the connection on screen may start a reconnect: a replaced one
                // failing later is old news, and two loops would fight.
                if (state is MizarConnection.State.Failed && connection === socket) {
                    beginReconnect(host, state.message)
                }
            }
        }
    }

    /**
     * Retry a dropped connection on the documented backoff, without disturbing the screen.
     *
     * `phase` deliberately stays READY. The operator may be reading a conversation, and
     * throwing them back to the list for a two-second blip is worse than a banner that
     * says what is happening.
     *
     * **It never gives up.** This used to be six attempts -- about half a minute -- and
     * then a permanent failure screen. That was the single defect behind a completion
     * nobody ever heard about: the socket dropped while the phone sat in a pocket, the
     * host (or the tunnel) took longer than thirty seconds to come back, and the app
     * stopped dialling forever. With no socket there is no `turn.settled` event *and*
     * no list refresh, so both announcements -- the pushed one and the polling
     * fallback -- die together. A monitor has to outlast the outage, so the retry
     * continues at [Backoff.persistent]'s capped cadence until it succeeds, until the
     * credential is refused (which only pairing can fix), or until something else
     * takes the socket.
     *
     * @param host - the host to dial again.
     * @param reason - what the connection said when it ended, shown beside the banner.
     */
    private fun beginReconnect(host: PairedHost, reason: String) {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            stopWatchers()
            // The reason is recorded at once, but the banner waits. A one-second blip --
            // a phone switching networks, a tunnel re-establishing -- used to flash
            // "connecting" in front of the operator several times a minute, which reads
            // as a broken app when the connection was never actually missed.
            _state.update { it.copy(disconnectReason = reason) }
            launch {
                delay(BANNER_DELAY_MILLIS)
                if (_state.value.phase != Phase.READY || connection == null) {
                    _state.update { it.copy(reconnecting = true) }
                }
            }
            val backoff = Backoff.persistent
            var attempt = 0
            while (isActive) {
                delay((backoff.nextDelay() * 1000).toLong())
                attempt += 1
                note("reconnect attempt $attempt to ${host.displayName}")
                _state.update { it.copy(problem = null) }
                // `connect` reports every failure by setting the phase rather than
                // throwing, so a thrown error here is only ever an unexpected one.
                runCatching { connect(host) }
                val current = _state.value
                if (current.phase == Phase.READY) {
                    backoff.reset()
                    _state.update { it.copy(reconnecting = false, disconnectReason = null) }
                    note("reconnected after $attempt attempt(s)")
                    // Whatever happened while the socket was down never arrived as an
                    // event, so the screen has to be re-read once -- and a turn that
                    // ended during the outage is announced from the list it reads.
                    refreshConversation()
                    // The host forgot we were on screen while we were away.
                    setPresence(foreground)
                    return@launch
                }
                // A refused credential ends the whole affair: the host answered and said
                // it does not know this phone, so every further attempt is the same
                // rejection. Stop, and send the operator to pairing.
                if (current.needsPairing) {
                    _state.update { it.copy(phase = Phase.FAILED, reconnecting = false) }
                    return@launch
                }
                // `connect` marks the phase FAILED on every failure; during a retry that
                // would swap the screen for the failure page between attempts, which is
                // worse than the banner. Put the phase back and keep going.
                _state.update { if (it.reconnecting) it.copy(phase = Phase.READY) else it }
            }
        }
    }

    /** Stop any automatic retry; a caller that dials something else owns the socket now. */
    /**
     * Open the conversation that was open last time, once per process.
     *
     * Deliberately not on every list refresh: the operator returning to the list means
     * they want the list, and yanking them back into the conversation would be the app
     * arguing with them.
     */
    private fun maybeRestoreLastSession() {
        if (restoredOnce) return
        restoredOnce = true
        val current = _state.value
        if (current.selectedSessionId != null) return
        val hostId = current.host?.hostId ?: return
        val last = activity.lastSession(hostId) ?: return
        if (current.sessions.none { it.sessionId == last }) return
        selectSession(last)
    }

    /**
     * Notify the sessions whose turn just ended, while the operator is elsewhere.
     *
     * Only a transition counts: a list read that finds a session idle says nothing about
     * whether it *just* became idle. And only in the background -- a notification for a
     * screen you are looking at is noise.
     */
    private suspend fun announceFinishedTurns(rows: List<SessionRow>) {
        val context = getApplication<Application>()
        for (row in rows) {
            val runningNow = row.running
            val hostId = _state.value.host?.hostId
            // In memory first, then the store: a process that was frozen mid-turn came
            // back with an empty map and lost the completion that happened while away.
            val was = wasRunning[row.sessionId]
                ?: hostId?.let { activity.runningSession(it, row.sessionId) }
            if (hostId != null) activity.setRunningSession(hostId, row.sessionId, runningNow)
            wasRunning[row.sessionId] = runningNow
            if (was != true || runningNow) {
                if (was != true) { /* first sighting: nothing to announce */ }
                continue
            }
            note("list: ${row.sessionId} settled (was running)")
            // A turn ended. Announced once per turn: a second read of the same idle row
            // must not buzz again, and neither must the event that reported the same
            // ending. Nothing is recorded unless a notice is actually posted -- recording
            // a suppression (on screen, quiet hours) is what lost the following turn.
            if (announcer.announcedRecently(row.sessionId, System.currentTimeMillis())) { note("list: ${row.sessionId} already announced"); continue }
            if (foreground) { note("list: ${row.sessionId} on screen, nothing to announce"); continue }
            // A finished turn can wait; a blocked one cannot. Quiet hours silence only the
            // former.
            if (activity.quietHours() && isQuietNow()) { note("list: ${row.sessionId} quiet hours"); continue }

            val detail = describeFinished(row.sessionId)
            val title = context.getString(
                if (detail.failed) R.string.turn_failed_notice_title else R.string.turn_completed_title,
            )
            val name = _state.value.titles[row.sessionId] ?: row.sessionId.takeLast(8)
            val body = detail.summary
                ?.let { context.getString(R.string.turn_completed_body, name, it) }
                ?: context.getString(R.string.turn_completed_body_plain, name)
            val posted = runCatching {
                Notifications.show(
                    context,
                    title,
                    body,
                    row.sessionId,
                    // Its own range: a completion must not replace a waiting card.
                    baseId = me.shirazu.dshmizar.push.Notifications.FINISHED_NOTIFICATION_BASE,
                )
            }
            if (posted.isSuccess) announcer.record(row.sessionId, System.currentTimeMillis())
            note("list: ${row.sessionId} posted=${posted.isSuccess} body=$body")
        }
        // Forget the memory once a session is seen running again, so the next completion
        // is announced too.
        for (row in rows) if (row.running) announcer.forget(row.sessionId)
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
        val socket = connection ?: return FinishedDetail(false, null)
        val page = runCatching {
            socket.call<SessionMessagesResult>(
                "session.messages",
                payload(
                    "sessionId" to jsonString(sessionId),
                    "maxMessages" to me.shirazu.mizar.kit.jsonInt(12),
                ),
            )
        }.getOrNull() ?: return FinishedDetail(false, null)

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
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= 23 || hour < 8
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /** Try the stored host again, patiently. */
    fun retry() {
        cancelReconnect()
        val host = _state.value.host
        if (host == null) {
            start()
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.CONNECTING, problem = null) }
            connect(host)
        }
    }

    /**
     * Open another Mac that is already paired.
     *
     * The session list and the open conversation are cleared first: they belong to
     * the previous host, and leaving them on screen while the new one connects shows
     * one machine's conversations under another machine's name.
     */
    fun openHost(hostId: String) {
        cancelReconnect()
        viewModelScope.launch {
            val host = credentials.loadAll().firstOrNull { it.hostId == hostId } ?: return@launch
            _state.update {
                it.copy(
                    host = host,
                    phase = Phase.CONNECTING,
                    selectedSessionId = null,
                    sessions = emptyList(),
                    groups = emptyList(),
                    titles = emptyMap(),
                    conversation = emptyList(),
                    turns = emptyList(),
                    stats = null,
                    problem = null,
                )
            }
            connect(host)
        }
    }

    /**
     * Go to the pairing screen without forgetting anything.
     *
     * The connection is dropped while pairing, because a code is redeemed by a phone
     * that is not yet a client of anything.
     */
    fun startPairing() {
        cancelReconnect()
        viewModelScope.launch {
            stopWatchers()
            connection?.disconnect()
            connection = null
            _state.update {
                it.copy(phase = Phase.UNPAIRED, problem = null, needsPairing = false, selectedSessionId = null)
            }
        }
    }

    /** Forget one Mac, here and at the relay. */
    fun forget(hostId: String) {
        viewModelScope.launch {
            val wasOpen = _state.value.host?.hostId == hostId
            if (wasOpen) {
                cancelReconnect()
                runCatching { connection?.call<AcceptanceResult>("push.unregister") }
                stopWatchers()
                connection?.disconnect()
                connection = null
            }
            credentials.remove(hostId)
            indexStore.remove(hostId)
            val hosts = credentials.loadAll().sortedBy { it.pairedAtMillis }
            if (wasOpen) {
                _state.value = UiState(phase = if (hosts.isEmpty()) Phase.UNPAIRED else Phase.CONNECTING, hosts = hosts)
                hosts.lastOrNull()?.let { connect(it) }
            } else {
                _state.update { it.copy(hosts = hosts) }
            }
        }
    }

    // ── sessions ────────────────────────────────────────────────────────────

    fun refreshSessions() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                refreshSessionsNow()
                lastRefreshAt = System.currentTimeMillis()
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    private suspend fun refreshSessionsNow() {
        val socket = connection ?: return
        val list = try {
            socket.call<SessionListResult>("session.list")
        } catch (error: Throwable) {
            _state.update { it.copy(problem = messageOf(error, addressOf())) }
            return
        }

        // Best effort, and an empty answer is treated as no answer: a host that
        // genuinely has no workspaces is a host with no sessions, and clearing the
        // set here would empty the list it is used to filter.
        val paths = runCatching {
            socket.call<WorkspaceListResult>("workspace.list").items.map { it.path }.toSet()
        }.getOrNull()

        _state.update { current ->
            current.copy(
                sessions = list.items,
                workspacePaths = if (paths.isNullOrEmpty()) current.workspacePaths else paths,
                problem = null,
            )
        }
        regroup()
        persistIndex()
        announceFinishedTurns(list.items)
        // The screen opens on what was last read, once: after that the operator is in
        // control of where they are.
        maybeRestoreLastSession()
    }

    /** Load titles for rows about to be drawn, one request per session, once each. */
    fun loadTitles(sessionIds: List<String>) {
        viewModelScope.launch {
            val socket = connection ?: return@launch
            var learned = false
            for (id in sessionIds) {
                if (_state.value.titles.containsKey(id)) continue
                val title = runCatching { socket.call<SessionStatsResult>("session.stats", payload("sessionId" to jsonString(id))).stats?.title }
                    .getOrNull()
                if (!title.isNullOrBlank()) {
                    _state.update { it.copy(titles = it.titles + (id to title)) }
                    learned = true
                }
            }
            if (learned) persistIndex()
        }
    }

    /**
     * Open a session, and remember it.
     *
     * "Where was I" is the one piece of navigation state worth persisting: a phone is
     * put down and picked up constantly, and re-walking list → workspace → row each
     * time is the cost of not having it.
     */
    fun selectSession(sessionId: String?) {
        val hostId = _state.value.host?.hostId
        if (hostId != null) {
            activity.rememberLastSession(hostId, sessionId)
            if (sessionId != null) {
                val now = System.currentTimeMillis()
                activity.markSeen(hostId, sessionId, now)
                _state.update { it.copy(seenAt = it.seenAt + (sessionId to now)) }
            }
        }
        _state.update {
            it.copy(
                selectedSessionId = sessionId,
                conversation = emptyList(),
                turns = emptyList(),
                stats = null,
                oldestSeq = null,
                hasMore = false,
                exhausted = false,
                conversationError = null,
                statsError = null,
                pending = null,
                newestSeq = null,
            )
        }
        if (sessionId == null) return
        viewModelScope.launch {
            reload()
            refreshInteractions()
        }
    }

    /** Create a session in a directory, which the host turns into a workspace. */
    fun createSession(cwd: String) {
        viewModelScope.launch {
            val socket = connection ?: return@launch
            _state.update { it.copy(busy = true) }
            try {
                // Only `cwd`. The host resolves it to a workspace and creates one when
                // DSH has none; sending `workspaceId` as well is refused outright, and
                // a session that belongs to no workspace is one the Mac never lists.
                val created = socket.call<CreateSessionResult>(
                    "session.create",
                    buildJsonObject { put("cwd", cwd) },
                )
                // Workspaces too, or the new session's directory is not in the set the
                // list filters by and the session would vanish from this screen.
                refreshSessionsNow()
                selectSession(created.sessionId)
            } catch (error: Throwable) {
                _state.update { it.copy(problem = messageOf(error, addressOf())) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // ── the open conversation ───────────────────────────────────────────────

    /** Load the open session, with the latch and the retry described on this class. */
    suspend fun reload(tail: Int = FULL_PAGE, afterSeq: Int? = null) {
        if (loading) {
            reloadWanted = true
            return
        }
        loading = true
        try {
            loadOnce(tail, afterSeq)
            val current = _state.value
            if (current.phase == Phase.READY && (current.stats == null || current.conversation.isEmpty())) {
                delay(700)
                loadOnce(tail, afterSeq)
            }
            while (reloadWanted) {
                reloadWanted = false
                loadOnce(tail, afterSeq)
            }
        } finally {
            loading = false
        }
    }

    /** Fold one page of records into the transcript. */
    private fun applyPage(page: SessionMessagesResult) {
        val fresh = Conversation.items(page)
        val freshTurns = Conversation.turns(page)
        val oldest = page.records.firstNotNullOfOrNull { SessionRecord(it).payload["seq"].intValue() }
        _state.update { current ->
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
    /**
     * Whether the host implements `session.refresh`.
     *
     * Asked once per process. The call is an optimisation -- one round trip for the
     * whole screen instead of three -- and a host without it answers NOT_IMPLEMENTED
     * every single time, so re-asking on every pull spends a round trip to be told the
     * same thing. The 0.8.3 plugin does not implement it; the three plain reads below
     * are the path that runs, and they are the path this build should be judged on.
     */
    private var refreshBundleSupported = true

    private suspend fun loadBundle(tail: Int, afterSeq: Int?): Boolean {
        if (!refreshBundleSupported) return false
        val socket = connection ?: return true
        val sessionId = _state.value.selectedSessionId ?: return true
        val bundle = try {
            socket.call<RefreshBundle>(
                "session.refresh",
                payload(
                    *buildList {
                        add("sessionId" to jsonString(sessionId))
                        add("maxMessages" to me.shirazu.mizar.kit.jsonInt(tail))
                        afterSeq?.let { add("afterSeq" to me.shirazu.mizar.kit.jsonInt(it)) }
                    }.toTypedArray(),
                ),
            )
        } catch (error: Throwable) {
            if ((error as? me.shirazu.mizar.kit.MizarError)?.known == me.shirazu.mizar.kit.MizarErrorCode.NOT_IMPLEMENTED) {
                note("host does not implement session.refresh; using the three reads")
                refreshBundleSupported = false
            }
            return false
        }
        applyPage(bundle.messages)
        bundle.stats?.let { stats -> _state.update { it.copy(stats = stats.stats, statsError = null) } }
        bundle.pending?.let { pending -> _state.update { it.copy(pending = pending) } }
        return true
    }

    private suspend fun loadOnce(tail: Int = FULL_PAGE, afterSeq: Int? = null) {
        val socket = connection ?: return
        val sessionId = _state.value.selectedSessionId ?: return
        _state.update { it.copy(loadingConversation = true) }

        try {
            val page = socket.call<SessionMessagesResult>(
                "session.messages",
                payload(
                    *buildList {
                        add("sessionId" to jsonString(sessionId))
                        add("maxMessages" to me.shirazu.mizar.kit.jsonInt(tail))
                        // A number is this screen's own claim about what it holds; `null`
                        // is the claim that it holds **nothing**. The difference matters
                        // since plugin 0.8.3: an *absent* key is silence, and the host
                        // answers silence from a ledger of what it already delivered to
                        // this device -- so a cold screen that stayed quiet would be sent
                        // only the records after the last delivery and open empty.
                        add(
                            "afterSeq" to (
                                afterSeq?.let { me.shirazu.mizar.kit.jsonInt(it) }
                                    ?: kotlinx.serialization.json.JsonNull
                                ),
                        )
                    }.toTypedArray(),
                ),
            )
            applyPage(page)
        } catch (error: Throwable) {
            _state.update { it.copy(conversationError = messageOf(error, addressOf())) }
        }

        try {
            val stats = socket.call<SessionStatsResult>("session.stats", payload("sessionId" to jsonString(sessionId)))
            _state.update { it.copy(stats = stats.stats, statsError = null) }
        } catch (error: Throwable) {
            _state.update { it.copy(statsError = messageOf(error, addressOf())) }
        }

        _state.update { it.copy(loadingConversation = false) }
    }

    /**
     * Load an older page.
     *
     * @returns the number of items added, `0` when the host says there is nothing
     *   older, or `null` when it could not be asked — which must never be read as
     *   "the top of the conversation".
     */
    suspend fun loadOlder(): Int? {
        val socket = connection ?: return null
        val current = _state.value
        val sessionId = current.selectedSessionId ?: return null
        val before = current.oldestSeq ?: return null
        if (!current.hasMore) {
            _state.update { it.copy(exhausted = true) }
            return 0
        }

        return try {
            val page = socket.call<SessionMessagesResult>(
                "session.messages",
                payload(
                    "sessionId" to jsonString(sessionId),
                    "maxMessages" to me.shirazu.mizar.kit.jsonInt(200),
                    "beforeSeq" to me.shirazu.mizar.kit.jsonInt(before),
                ),
            )
            val reached = page.records.firstNotNullOfOrNull { SessionRecord(it).payload["seq"].intValue() }
            if (reached == null || reached == before || page.records.isEmpty()) {
                _state.update { it.copy(exhausted = true, hasMore = false) }
                return 0
            }
            val fresh = Conversation.items(page)
            val freshTurns = Conversation.turns(page)
            val before_count = _state.value.conversation.size
            _state.update {
                it.copy(
                    conversation = mergeConversations(it.conversation, fresh),
                    turns = mergeTurns(it.turns, freshTurns),
                    oldestSeq = listOfNotNull(before, reached).minOrNull(),
                    hasMore = page.hasMore,
                )
            }
            _state.value.conversation.size - before_count
        } catch (_: Throwable) {
            null
        }
    }

    fun sendPrompt(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val socket = connection ?: return@launch
            val sessionId = _state.value.selectedSessionId ?: return@launch
            // A fresh key per submission: reusing one would make a second identical
            // message look like a retry of the first and be dropped by the host.
            val requestId = UUID.randomUUID().toString()
            try {
                val result = socket.call<PromptResult>(
                    "session.prompt",
                    payload(
                        "sessionId" to jsonString(sessionId),
                        "text" to jsonString(text),
                        "requestId" to jsonString(requestId),
                    ),
                )
                if (!result.accepted) {
                    _state.update { it.copy(problem = "the host did not accept the message") }
                    return@launch
                }
                awaitPromptRecorded(text)
                reload()
            } catch (error: Throwable) {
                _state.update { it.copy(problem = messageOf(error, addressOf())) }
            }
        }
    }

    /**
     * Wait for the host to record what was just sent.
     *
     * The log is written asynchronously, so a single read after an accepted prompt
     * usually does not contain it — which looks exactly like a message that was
     * silently dropped.
     */
    private suspend fun awaitPromptRecorded(text: String) {
        repeat(8) {
            loadOnce()
            val recorded = _state.value.conversation.any {
                it is ConversationItem.Message && it.role == ConversationItem.Role.USER && it.text == text
            }
            if (recorded) return
            delay(500)
        }
    }

    fun cancelTurn() {
        viewModelScope.launch {
            val socket = connection ?: return@launch
            val sessionId = _state.value.selectedSessionId ?: return@launch
            runCatching { socket.call<AcceptanceResult>("session.cancel", payload("sessionId" to jsonString(sessionId))) }
            reload()
        }
    }

    // ── interactions ────────────────────────────────────────────────────────

    /**
     * Re-read the open conversation from the host, on demand.
     *
     * The screen already follows pushed events, but "I typed something on the computer
     * and the phone has not caught up" is a real state -- a dropped socket, a frozen
     * process, a host that restarted -- and the only cure the operator has is a button.
     * It refreshes the transcript, the figures and the pending cards together, because
     * those are the three things the screen draws.
     */
    fun refreshConversation() {
        if (_state.value.selectedSessionId == null) return
        // Claimed before the launch so two taps -- or a tap racing the automatic refresh
        // after a reconnect -- cannot start two round trips.
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                // A tail, not a page: the merge keeps the history, so a refresh only has
                // to fetch what happened since. 200 messages over a tunnel is the reason
                // refreshing felt slow.
                val bundle = loadBundle(REFRESH_PAGE, _state.value.newestSeq)
                if (!bundle) {
                    // An older host: three calls, as before.
                    reload(tail = REFRESH_PAGE, afterSeq = _state.value.newestSeq)
                    refreshInteractionsNow()
                }
                lastRefreshAt = System.currentTimeMillis()
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }
    fun refreshInteractions() {
        viewModelScope.launch { refreshInteractionsNow() }
    }

    /**
     * Read the pending interactions, and say whether the host answered at all.
     *
     * The answer matters as much as the contents: on a relay, a socket can be dead
     * while both ends still believe it is open -- the tunnel drops the mapping, the
     * relay keeps answering websocket pings, and nothing in either read loop ever
     * fails. The only proof is a question that goes unanswered, which is why this
     * returns a boolean and why every call carries a short timeout.
     *
     * @returns whether the host answered, or `true` when there is no connection to
     *   judge (a reconnect already owns that case).
     */
    private suspend fun refreshInteractionsNow(): Boolean {
        val socket = connection ?: return true
        // One retry: a refresh that loses a race with a reconnect, or that lands in
        // the same instant as the host restarting, would otherwise leave a card the
        // operator cannot see until the next poll -- and an agent is blocked the
        // whole time it is unseen.
        val pending = runCatching {
            socket.call<PendingInteractions>("interactions.pending", timeoutMillis = POLL_TIMEOUT_MILLIS)
        }.getOrElse {
            delay(500)
            runCatching {
                socket.call<PendingInteractions>("interactions.pending", timeoutMillis = POLL_TIMEOUT_MILLIS)
            }.getOrNull()
        } ?: return false
        _state.update { it.copy(pending = pending) }
        // Announced only while the operator is elsewhere: in the foreground the cards
        // are already on screen, and a notification for what you are looking at is
        // noise. `lastNotified` keeps the same batch from buzzing twice.
        val signature = (pending.approvals.map { it.requestId } + pending.questions.map { it.callId })
            .sorted().joinToString(",")
        if (pending.isEmpty) {
            lastNotified = null
        } else {
            // Split into plain steps with a log at each one: on a real phone the
            // combined condition silently declined while the log above showed a
            // non-empty set in the background, and only side-specific logging can
            // say which half was false.
            if (foreground) return true
            if (signature == lastNotified) return true
            lastNotified = signature
            val app = getApplication<Application>()
            val counts = listOfNotNull(
                pending.approvals.size.takeIf { it > 0 }?.let { if (it == 1) "1 approval" else "$it approvals" },
                pending.questions.size.takeIf { it > 0 }?.let { if (it == 1) "1 question" else "$it questions" },
            ).joinToString(" + ")
            runCatching {
                Notifications.show(
                    app,
                    app.getString(R.string.pending_title),
                    counts + app.getString(R.string.pending_body),
                    pending.approvals.firstOrNull()?.sessionId ?: pending.questions.firstOrNull()?.sessionId,
                )
            }
        }
        return true
    }

    fun answerApproval(requestId: String, outcome: ApprovalOutcome) {
        viewModelScope.launch {
            val socket = connection ?: return@launch
            runCatching {
                socket.call<AcceptanceResult>(
                    "approval.answer",
                    payload("requestId" to jsonString(requestId), "outcome" to jsonString(outcome.wire)),
                )
            }.onFailure { error -> _state.update { it.copy(problem = messageOf(error, addressOf())) } }
            refreshInteractionsNow()
        }
    }

    fun answerQuestions(callId: String, answers: List<QuestionAnswer>) {
        viewModelScope.launch {
            val socket = connection ?: return@launch
            runCatching {
                socket.call<AcceptanceResult>(
                    "question.answer",
                    buildJsonObject {
                        put("callId", callId)
                        putJsonArray("answers") {
                            for (answer in answers) {
                                add(
                                    buildJsonObject {
                                        put("id", answer.id)
                                        putJsonArray("selected") { answer.selected.forEach { add(it) } }
                                        answer.custom?.let { put("custom", it) }
                                    },
                                )
                            }
                        }
                    },
                )
            }.onFailure { error -> _state.update { it.copy(problem = messageOf(error, addressOf())) } }
            refreshInteractionsNow()
        }
    }

    // ── catalogs, model, permissions ────────────────────────────────────────

    fun loadCatalogs() {
        viewModelScope.launch {
            val socket = connection ?: return@launch
            if (_state.value.modelCatalog == null) {
                runCatching { socket.call<ModelCatalog>("model.catalog") }
                    .onSuccess { catalog -> _state.update { it.copy(modelCatalog = catalog) } }
            }
            if (_state.value.permissionCatalog == null) {
                runCatching { socket.call<PermissionCatalog>("permissions.catalog") }
                    .onSuccess { catalog -> _state.update { it.copy(permissionCatalog = catalog) } }
            }
        }
    }

    fun setModel(selection: ModelSelection) {
        val sessionId = _state.value.selectedSessionId ?: return
        // Remembered before the call: there is no read-back, so a failure must still
        // leave the picker showing what was asked for rather than snapping back.
        _state.update { it.copy(modelChoice = it.modelChoice + (sessionId to selection)) }
        viewModelScope.launch {
            val socket = connection ?: return@launch
            runCatching {
                socket.call<JsonObject>(
                    "session.model.set",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("provider", selection.provider)
                        put("model", selection.model)
                        selection.reasoningEffort?.let { put("reasoningEffort", it) }
                    },
                )
            }.onFailure { error -> _state.update { it.copy(problem = messageOf(error, addressOf())) } }
        }
    }

    fun setPermission(preset: String) {
        val sessionId = _state.value.selectedSessionId ?: return
        viewModelScope.launch {
            val socket = connection ?: return@launch
            runCatching {
                socket.call<JsonObject>(
                    "session.permission.set",
                    payload("sessionId" to jsonString(sessionId), "preset" to jsonString(preset)),
                )
            }.onFailure { error -> _state.update { it.copy(problem = messageOf(error, addressOf())) } }
            loadOnce()
        }
    }

    // ── push ────────────────────────────────────────────────────────────────

    /** Tell the host this device's push token, and that it is on screen. */
    fun registerPush(candidate: MizarConnection? = connection) {
        viewModelScope.launch {
            val socket = candidate ?: return@launch
            val context = getApplication<Application>()
            // The stored copy first (written by the token callback), and a fresh
            // request only when there is none: a build with no Firebase project
            // answers `null` and nothing else changes.
            val token = PushTokens.current(context) ?: PushTokens.refresh(context)
            if (token != null) {
                runCatching {
                    socket.call<JsonObject>(
                        "push.register",
                        buildJsonObject {
                            put("token", token)
                            // Android has no APNs environment, and the relay routes on
                            // this field: without it the token would be sent to Apple.
                            put("platform", "android")
                            put("locale", java.util.Locale.getDefault().toLanguageTag())
                        },
                    )
                }
            }
            runCatching {
                socket.call<AcceptanceResult>("push.presence", buildJsonObject { put("foreground", true) })
            }
        }
    }

    /** Report whether the app is on screen, which decides who gets notified. */
    /** Remember the theme across launches. */
    fun setTheme(mode: ThemeMode) {
        activity.setThemeMode(mode.name.lowercase())
        _state.update { it.copy(themeMode = mode) }
    }

    /** Remember the quiet-hours switch; the announcer reads the store directly. */
    fun setQuietHours(on: Boolean) {
        activity.setQuietHours(on)
        _state.update { it.copy(quietHours = on) }
    }

    fun setPresence(foreground: Boolean) {
        // Remembered, because a reconnect has to re-assert it: the host forgets which
        // client is on screen the moment the socket drops, and a phone that looks
        // backgrounded gets notifications instead of the inline cards.
        this.foreground = foreground
        // Coming back to the screen is the most common reason the transcript is stale:
        // the process may have been frozen, or the socket paused in the background. One
        // quiet refresh, throttled so a quick app switch does not cost a round trip.
        if (foreground && System.currentTimeMillis() - lastRefreshAt > STALE_MILLIS) {
            refreshConversation()
        }
        viewModelScope.launch {
            val socket = connection ?: return@launch
            runCatching {
                socket.call<AcceptanceResult>("push.presence", buildJsonObject { put("foreground", foreground) })
            }
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun startWatchers(socket: MizarConnection) {
        // Off the main dispatcher on purpose. Everything here is network work, and the
        // loop is also the connection's heartbeat -- a heartbeat that shares a thread
        // with the UI stops when the UI does, which is exactly when nobody is watching
        // it. On a backgrounded phone that showed up as minutes of silence: no polls,
        // no reconnect, and every completion in the gap lost.
        watchers = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            // The pushed signals, plus a safety-net poll. Notifications are best
            // effort, and a notification lost to a reconnect must not leave a card on
            // screen that no longer exists.
            launch {
                socket.events().collect { frame ->
                    if (frame is me.shirazu.mizar.kit.ServerFrame.Event) {
                        note("event ${frame.name}")
                        if (frame.name in INTERACTION_EVENTS) refreshInteractionsNow()
                        // The host knows when a turn ends; a connected client should be
                        // told, not left to infer it from a list refresh.
                        if (frame.name == "turn.settled") onTurnSettled(frame.data)
                    }
                }
            }
            var silentPolls = 0
            var ticks = 0
            try {
                while (true) {
                    delay(INTERACTION_POLL_MILLIS)
                    if (!refreshInteractionsNow() && isActive) {
                        // The poll is also the heartbeat. A websocket through a relay can
                        // be dead while both read loops stay happy -- the tunnel drops the
                        // mapping and the relay goes on answering websocket pings -- so an
                        // unanswered question is the only evidence there is. Without this,
                        // the app sat "connected" for minutes at a time and every
                        // completion in the gap was lost: no event, and the list poll that
                        // would have caught it was failing silently too.
                        silentPolls += 1
                        note("poll got no answer ($silentPolls)")
                        if (silentPolls >= 2) {
                            note("the socket is dead; reconnecting")
                            val host = _state.value.host
                            if (host != null) beginReconnect(host, "the host stopped answering") else socket.disconnect()
                            return@launch
                        }
                    } else {
                        silentPolls = 0
                    }
                    ticks += 1
                    if (ticks % 6 == 0) {
                        note("heartbeat: connected=${connection != null} silent=$silentPolls foreground=$foreground")
                    }
                    // No pushed signal says "the turn ended", so while the screen is elsewhere
                    // the list is the only place to see it -- and only worth asking when
                    // something was actually running. Bounded, because this read is the
                    // long one: left to the default minute per call it blocked the poll
                    // that watches for silence, and a dead socket went unnoticed for
                    // minutes at a time.
                    val hostId = _state.value.host?.hostId
                    if (!foreground && (wasRunning.values.any { it } || hostId?.let { activity.hasRunningSessions(it) } == true)) {
                        withTimeoutOrNull(SESSIONS_POLL_TIMEOUT_MILLIS) {
                            runCatching { refreshSessionsNow() }
                        }
                    }
                }
            } finally {
                note("watchers stopped")
            }
        }
    }

    private fun stopWatchers() {
        watchers?.cancel()
        watchers = null
    }

    /**
     * One line on the device log, under a tag an operator can filter on.
     *
     * The notification path is invisible by construction -- it fires while the app is
     * off screen, so nothing on the display can say whether it ran -- and `logcat -s
     * mizar-notify` is the only way to tell "the host never sent it" from "the app
     * decided not to". Cheap enough to keep, and the alternative is guessing.
     */
    private fun note(message: String) {
        android.util.Log.i("mizar-notify", message)
    }

    private fun regroup() {
        _state.update { current ->
            current.copy(
                groups = SessionGrouping.groups(current.sessions, current.workspacePaths, current.selectedSessionId),
            )
        }
    }

    /**
     * A turn ended, pushed by the host.
     *
     * This is the signal the polling version was approximating. The host knows exactly
     * when a turn ends, so a connected client is told instead of left to notice a
     * transition on a list refresh -- which is how a completion that happened during a
     * dropped socket was lost. The polling path stays as a fallback for a client that was
     * away when the event fired; both record when they post, so a turn is announced once.
     */
    private suspend fun onTurnSettled(data: kotlinx.serialization.json.JsonElement) {
        val sessionId = (data["sessionId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            ?: run { note("settled: no sessionId in the frame"); return }
        if (foreground) { note("settled $sessionId: on screen, nothing to announce"); return }
        if (announcer.announcedRecently(sessionId, System.currentTimeMillis())) { note("settled $sessionId: already announced"); return }
        if (activity.quietHours() && isQuietNow()) { note("settled $sessionId: quiet hours"); return }

        val context = getApplication<Application>()
        val detail = describeFinished(sessionId)
        val title = context.getString(
            if (detail.failed) R.string.turn_failed_notice_title else R.string.turn_completed_title,
        )
        val name = _state.value.titles[sessionId] ?: sessionId.takeLast(8)
        val body = detail.summary
            ?.let { context.getString(R.string.turn_completed_body, name, it) }
            ?: context.getString(R.string.turn_completed_body_plain, name)
        val posted = runCatching {
            Notifications.show(
                context,
                title,
                body,
                sessionId,
                baseId = me.shirazu.dshmizar.push.Notifications.FINISHED_NOTIFICATION_BASE,
            )
        }
        if (posted.isSuccess) announcer.record(sessionId, System.currentTimeMillis())
        note("settled $sessionId: posted=${posted.isSuccess} failed=${detail.failed} body=$body")
    }

    private fun applyIndex(index: SessionIndex) {
        // Additive only: a stored list must never wipe what a live read put on screen.
        _state.update { current ->
            current.copy(
                sessions = if (current.sessions.isEmpty()) index.sessions else current.sessions,
                titles = index.titles + current.titles,
                workspacePaths = if (current.workspacePaths.isEmpty()) index.workspacePaths.toSet() else current.workspacePaths,
            )
        }
        regroup()
    }

    private suspend fun persistIndex() {
        val current = _state.value
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

    private fun addressOf(): String? = _state.value.host?.let { it.authority ?: it.baseUrl }

    private fun messageOf(error: Throwable, address: String?): String {
        // A refused credential gets the phrasing that names the action (pair again),
        // not the host's terse "unknown or revoked token", which reads like a bug.
        if (error.isRepairNeeded()) return Diagnoses.message(Diagnoses.of(error, address))
        val mizar = error as? me.shirazu.mizar.kit.MizarError
        if (mizar != null) return mizar.message
        return Diagnoses.message(Diagnoses.of(error, address))
    }

    /** The host answered and refused this phone: retrying cannot fix that. */
    private fun Throwable.isRepairNeeded(): Boolean =
        this is me.shirazu.mizar.kit.MizarError && requiresRepairing

    private fun payload(vararg members: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        buildJsonObject { for ((key, value) in members) put(key, value) }

    fun dismissProblem() {
        _state.update { it.copy(problem = null) }
    }

    private companion object {
        /** The five names that all mean "the pending set changed": refresh it. */
        val INTERACTION_EVENTS = setOf(
            "interactions.changed",
            "approval.request",
            "approval.resolved",
            "question.request",
            "question.resolved",
        )

        /**
         * The documented safety net, not the mechanism -- and short, because the thing
         * it backs up is an agent that is stopped until somebody answers.
         *
         * It was 45 s, which is long enough to watch a blocked turn sit there and
         * conclude the app is broken. Ten seconds costs one round trip on a link that
         * is a LAN or a USB tunnel, and bounds the worst case to something an operator
         * will wait through.
         */
        const val INTERACTION_POLL_MILLIS = 10_000L

        /**
         * How long a poll waits before deciding the host is not there.
         *
         * Much shorter than the 60 s a normal call gets, because this is the
         * heartbeat: the whole value of the poll is noticing silence, and a host on
         * the other end of a tunnel answers in milliseconds when it answers at all.
         */
        const val POLL_TIMEOUT_MILLIS = 5_000L

        /**
         * How long the background session-list read may take.
         *
         * Long enough for a slow host, short enough that it cannot hold up the poll
         * that is the connection's heartbeat.
         */
        const val SESSIONS_POLL_TIMEOUT_MILLIS = 20_000L

        /** The transcript page an open session starts with. */
        const val FULL_PAGE = 200

        /**
         * What a refresh asks for.
         *
         * A refresh is a delta, not a reload: the merge keeps everything already held, so
         * asking for the last few dozen messages is enough -- and over a tunnel it is the
         * difference between a refresh that feels immediate and one that feels broken.
         */
        const val REFRESH_PAGE = 60

        /** How stale the screen may get before returning to it triggers a refresh. */
        const val STALE_MILLIS = 15_000L

        /** How long a posted completion counts as "the same turn" for the other path. */
        const val ANNOUNCE_DEDUPE_MILLIS = 5_000L

        /** How long a drop must last before the banner appears: one second is a blip. */
        const val BANNER_DELAY_MILLIS = 2_000L
    }
}

/** What session.refresh answers with: one round trip for the whole screen. */
@kotlinx.serialization.Serializable
private data class RefreshBundle(
    val messages: SessionMessagesResult,
    val stats: SessionStatsResult? = null,
    val pending: PendingInteractions? = null,
)

/** The stored spelling back into the enum; an unknown value follows the system. */
private fun String.toThemeMode(): ThemeMode =
    when (lowercase()) {
        "light" -> ThemeMode.LIGHT
        "dark" -> ThemeMode.DARK
        else -> ThemeMode.SYSTEM
    }

