package me.shirazu.dshmizar

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
import me.shirazu.dshmizar.push.ConnectionService
import me.shirazu.dshmizar.push.LiveTurn
import me.shirazu.dshmizar.push.LiveTurnCommands
import me.shirazu.dshmizar.push.Notifications
import me.shirazu.dshmizar.store.ActivityStore
import me.shirazu.dshmizar.store.ConversationCache
import me.shirazu.dshmizar.store.EncryptedAccountStore
import me.shirazu.mizar.kit.account.AccountAuth
import me.shirazu.mizar.kit.account.AccountClient
import me.shirazu.mizar.kit.account.AccountDevicePayload
import me.shirazu.mizar.kit.account.AccountError
import me.shirazu.mizar.kit.account.AccountIdentity
import me.shirazu.mizar.kit.account.AccountNotice
import me.shirazu.mizar.kit.account.AccountSession
import me.shirazu.mizar.kit.account.AccountStoring
import me.shirazu.mizar.kit.account.AuthUri
import me.shirazu.dshmizar.ui.ThemeMode
import me.shirazu.dshmizar.store.EncryptedCredentialStore
import me.shirazu.dshmizar.store.FileSessionIndexStore
import me.shirazu.mizar.kit.AcceptanceResult
import me.shirazu.mizar.kit.ApprovalOutcome
import me.shirazu.mizar.kit.ArchiveSessionResult
import me.shirazu.mizar.kit.AttachmentCapabilities
import me.shirazu.mizar.kit.AttachmentCheckpoint
import me.shirazu.mizar.kit.AttachmentScope
import me.shirazu.mizar.kit.AttachmentSource
import me.shirazu.mizar.kit.AttachmentUploader
import me.shirazu.mizar.kit.attachmentDisplayName
import me.shirazu.mizar.kit.attachmentRpc
import me.shirazu.mizar.kit.Backoff
import me.shirazu.mizar.kit.Conversation
import me.shirazu.mizar.kit.ConversationItem
import me.shirazu.mizar.kit.ConversationTurn
import me.shirazu.mizar.kit.CreateSessionResult
import me.shirazu.mizar.kit.CredentialStore
import me.shirazu.mizar.kit.Diagnosis
import me.shirazu.mizar.kit.Diagnoses
import me.shirazu.mizar.kit.HostInfo
import me.shirazu.mizar.kit.probeHostId
import me.shirazu.dshmizar.ui.Format
import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.ModelCatalog
import me.shirazu.mizar.kit.ModelSelection
import me.shirazu.mizar.kit.PairedHost
import me.shirazu.mizar.kit.PairingTarget
import me.shirazu.mizar.kit.PairingClient
import me.shirazu.mizar.kit.PairingFailure
import me.shirazu.mizar.kit.PairingRoute
import me.shirazu.mizar.kit.HostRelay
import me.shirazu.mizar.kit.RelaySetResult
import me.shirazu.mizar.kit.RelayTier
import me.shirazu.mizar.kit.label
import me.shirazu.mizar.kit.ProtocolLog
import me.shirazu.mizar.kit.SyncEta
import me.shirazu.mizar.kit.SyncProgress
import me.shirazu.mizar.kit.PendingInteractions
import me.shirazu.mizar.kit.PermissionCatalog
import me.shirazu.mizar.kit.PromptResult
import me.shirazu.mizar.kit.QuestionAnswer
import me.shirazu.mizar.kit.SessionActivityResult
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
import me.shirazu.mizar.kit.WorkspaceRow
import me.shirazu.mizar.kit.get
import me.shirazu.mizar.kit.intValue
import me.shirazu.mizar.kit.jsonBool
import me.shirazu.mizar.kit.jsonString
import me.shirazu.mizar.kit.mergeConversations
import me.shirazu.mizar.kit.mergeTurns
import java.io.IOException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.security.MessageDigest
import java.util.UUID

/** Which screen the app is on, which is a property of the connection rather than of navigation. */
enum class Phase { LAUNCHING, UNPAIRED, CONNECTING, READY, FAILED }

/** Which part of the first pairing is running, for the connecting screen. */
enum class PairingStage { PAIRING, SYNCING }

/** Where the account is, which is independent of the connection. */
enum class AccountPhase { IDLE, LOADING, READY, UNREACHABLE }

/** Which half of the signed-out form is showing. */
enum class AccountMode { SIGN_IN, REGISTER }

/**
 * The account, as the UI draws it.
 *
 * `UNREACHABLE` keeps the session: a phone on a train still shows whose account
 * it is and what plan it has. Only a 401/403 clears it, which is the one case
 * where the cached profile is known to be wrong rather than merely stale.
 */
data class AccountUiState(
    val phase: AccountPhase = AccountPhase.IDLE,
    val session: AccountSession? = null,
    val notice: AccountNotice? = null,
    val error: String? = null,
    val working: Boolean = false,
    val screenOpen: Boolean = false,
    val mode: AccountMode = AccountMode.SIGN_IN,
    /** The landing page's "type the code" fallback is showing. */
    val handoffEntry: Boolean = false,
    /** The password-reset form is showing instead of the sign-in form. */
    val resetOpen: Boolean = false,
    /** Set once the reset code has been asked for: which address, and for how long. */
    val resetChallenge: me.shirazu.mizar.kit.account.EmailChallenge? = null,
    /**
     * A registration waiting on its code. The service creates nothing until the
     * code is accepted, so the form's three fields are held here and sent again.
     */
    val pendingRegistration: PendingRegistration? = null,
    val registerChallenge: me.shirazu.mizar.kit.account.EmailChallenge? = null,
    /** The redeem-code dialog, and what the last successful redeem bought. */
    val redeemOpen: Boolean = false,
    val redeemNotice: String? = null,
) {
    val profile get() = session?.profile
    val isPaid: Boolean get() = profile?.isPaid == true
}

/**
 * The balance screen's own state.
 *
 * Kept apart from the account's: the figure belongs to the computer, not to this
 * phone's login, and it is refreshed on demand rather than on every profile read.
 */
data class BalanceUiState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val result: me.shirazu.mizar.kit.account.BalanceResult? = null,
    val failed: Boolean = false,
)

/**
 * The file preview screen.
 *
 * One file at a time, and the bytes are either text or an image: everything else
 * is "cannot preview here", which is a state rather than an error -- the file
 * exists and the computer can open it, this phone just cannot draw it.
 */
data class FilePreviewUiState(
    val open: Boolean = false,
    val path: String = "",
    val name: String = "",
    val loading: Boolean = false,
    val text: String? = null,
    val image: ByteArray? = null,
    /** The first pages of a PDF, already rendered: the framework's renderer needs a file. */
    val pages: List<android.graphics.Bitmap> = emptyList(),
    /** How many pages the document has, so the screen can say what it left out. */
    val pageCount: Int = 0,
    /** The whole file's size, so a first page can say how much is missing. */
    val byteSize: Long = 0,
    /** More than the first page exists and was not read. */
    val truncated: Boolean = false,
    /** The file is real, and not something this screen can draw. */
    val unsupported: Boolean = false,
    val failed: Boolean = false,
)

/** A registration whose code has been asked for and not yet typed. */
data class PendingRegistration(val username: String, val email: String, val password: String)

/** Everything the UI draws. */
data class UiState(
    val phase: Phase = Phase.LAUNCHING,
    /** Set while a fresh pairing is between its steps; null once connected. */
    val pairingStage: PairingStage? = null,
    /** How far the first pairing's index warm-up has got, while it runs. */
    val syncProgress: SyncProgress? = null,
    /** Milliseconds left in that warm-up, once there is a rate to go on. */
    val syncEtaMillis: Long? = null,
    /** Showing the cached session list while the host is unreachable. */
    val offlineBrowser: Boolean = false,
    /** Titles read from the delegated children themselves, by session id. */
    val subagentTitles: Map<String, String> = emptyMap(),
    val subagentLoading: Boolean = false,
    val hosts: List<PairedHost> = emptyList(),
    val host: PairedHost? = null,
    val welcome: ServerFrame.Welcome? = null,
    val sessions: List<SessionRow> = emptyList(),
    val titles: Map<String, String> = emptyMap(),
    val workspacePaths: Set<String> = emptySet(),
    /** The workspaces the host lists now, with the names its operator gave them. */
    val workspaces: List<WorkspaceRow> = emptyList(),
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
    /** The model a session started from the home screen should begin on. */
    val newSessionModel: ModelSelection? = null,
    val problem: String? = null,
    /** A dropped socket is being retried; the screen stays as it was. */
    val reconnecting: Boolean = false,
    /** Why the last connection ended, shown beside the reconnect notice. */
    val disconnectReason: String? = null,
    /** Round trip to the host, from the last poll that got an answer. */
    val latencyMillis: Long? = null,
    /** When the current link came up, for the connection panel's uptime. */
    val connectedAtMillis: Long? = null,
    /** The last few round trips, oldest first, for the panel's tiny chart. */
    val latencyHistory: List<Long> = emptyList(),
    /**
     * The host refused this phone's credential: only pairing again can help, so the
     * screen must offer that instead of a retry that cannot work.
     */
    val needsPairing: Boolean = false,
    /**
     * A pairing just succeeded and the operator has not yet been offered to name
     * the computer. Set by `pair`, cleared by `renameHost`/`dismissNaming` and by
     * switching computers: a naming prompt belongs to the pairing that raised it.
     */
    val pendingNameHostId: String? = null,
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
    /** Four independent notice categories, as in iOS. */
    val notifyCompleted: Boolean = true,
    val notifyInterrupted: Boolean = true,
    val notifyQuestions: Boolean = true,
    val notifyApprovals: Boolean = true,
    /** Master switch for every haptic in the app. */
    val hapticsEnabled: Boolean = true,
    /** Diagnostic: stretch the typewriter to eight seconds so it can be watched. */
    val slowTypewriter: Boolean = false,
    /** Prefer a direct LAN socket over the relay when one can be found. */
    val localNetworkFirst: Boolean = false,
    /** The live link is a direct/LAN socket rather than the relay. */
    val isLocalNetwork: Boolean = false,
    /** A LAN scan is in flight, so the connection panel can say so. */
    val lanScanning: Boolean = false,
    /** The open turn's compact activity feed; only requested on a LAN link. */
    val activity: SessionActivityResult? = null,
    val activityExpanded: Boolean = false,
    val activityLoading: Boolean = false,
    val activityLoadingOlder: Boolean = false,
    val activityError: String? = null,
    /** Attachments staged for the next prompt, uploaded in the background. */
    val attachments: List<DraftAttachment> = emptyList(),
    /** The host's advertised attachment limits, read once per connection. */
    val attachmentCapabilities: AttachmentCapabilities? = null,
    /** An upload is still running; send is refused until every draft is ready. */
    val attachmentBusy: Boolean = false,
    /** When each session was last on screen, so the list can mark what is new. */
    val seenAt: Map<String, Long> = emptyMap(),
    /** The account service, which is optional and never gates the free features. */
    val account: AccountUiState = AccountUiState(),
    val balance: BalanceUiState = BalanceUiState(),
    val filePreview: FilePreviewUiState = FilePreviewUiState(),
) {
    /**
     * The gate iOS uses for attachments and the running turn's detail: a paying
     * account anywhere, or any account on the LAN (which costs the relay nothing).
     */
    val advancedFeaturesUnlocked: Boolean get() = account.isPaid || isLocalNetwork
}

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
    private val conversationCache = ConversationCache(application)
    private val accounts: AccountStoring = EncryptedAccountStore(application)
    private val accountClient = AccountClient(
        baseUrl = BuildConfig.ACCOUNT_BASE_URL,
        appVersion = BuildConfig.VERSION_NAME,
    )

    private val _state = MutableStateFlow(
        UiState(
            themeMode = activity.themeMode().toThemeMode(),
            quietHours = activity.quietHours(),
            localNetworkFirst = activity.localNetworkFirst(),
            notifyCompleted = activity.notifyCompleted(),
            notifyInterrupted = activity.notifyInterrupted(),
            notifyQuestions = activity.notifyQuestions(),
            notifyApprovals = activity.notifyApprovals(),
            hapticsEnabled = activity.hapticsEnabled(),
            slowTypewriter = activity.slowTypewriter(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // The live update's stop button is a broadcast: it reaches this model through
        // a slot that exists only while the model does, so a dead process cannot be
        // asked to cancel anything and the receiver falls back to opening the app.
        LiveTurnCommands.install { sessionId ->
            cancelSession(sessionId)
            true
        }
    }

    override fun onCleared() {
        LiveTurnCommands.install(null)
        LiveTurn.hide(getApplication())
        super.onCleared()
    }

    private var connection: MizarConnection? = null
    /** Watches the live connection's state, so a dropped socket is noticed. */
    private var connectionObserver: Job? = null
    /** The single automatic retry loop; never two at once. */
    private var reconnectJob: Job? = null
    /**
     * Bumped whenever a new socket becomes the live one.
     *
     * A read that was waiting on the old socket can fail -- or succeed -- long after
     * the reconnect, and writing either result over the new socket's transcript is
     * how a recovered screen went back to "there is no connection to the host" all
     * on its own. Reads capture this number and only publish when it still matches.
     */
    private var connectionEpoch = 0
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

    /** The app's own events, newest last, for the diagnostics export. */
    private val recentNotes = ArrayDeque<String>()

    /**
     * What crossed the wire, newest last, for the diagnostics export.
     *
     * The other half of [recentNotes]: a note says what the app decided, this says
     * whether the frame left and what came back. Bounded in the kit, so a long
     * session cannot grow it.
     */
    private val traffic = ProtocolLog()

    /** Signature of the pending set already announced, so a batch buzzes once. */
    private var lastNotified: String? = null
    private var watchers: Job? = null

    /** The one poll that reads the running turn's activity while its detail is open. */
    private var activityJob: Job? = null

    /** One LAN discovery at a time; a second trigger reuses the first. */
    private var lanSwitchJob: Job? = null

    /** The relay redial after a tier change, so two changes cannot stack two of them. */
    private var relaySwitchJob: Job? = null

    /**
     * The one in-flight re-read of the open conversation after a turn ended.
     *
     * Two things can say the transcript is stale -- the host's `turn.settled` event,
     * and the session list turning a row from running to idle -- and both can arrive
     * within the same second. One job, replaced never, keeps the two from fighting.
     */
    private var quietRefreshJob: Job? = null

    /** Why the last dial failed, and where it was dialling: the notice classifies from these. */
    /** One route's result in the connect race. */
    private data class DialOutcome(
        val endpoint: MizarConnection.Endpoint,
        val outcome: Result<Pair<MizarConnection, ServerFrame.Welcome>>,
    ) {
        val connected: Pair<MizarConnection, ServerFrame.Welcome>? get() = outcome.getOrNull()
        val failure: Throwable? get() = outcome.exceptionOrNull()
    }

    private var lastConnectFailure: Throwable? = null
    private var lastConnectEndpoint: MizarConnection.Endpoint? = null

    /** The sentence the permanent notice currently shows, so it is only rewritten on change. */
    private var lastLinkNotice: String? = null

    /**
     * When each running session's turn was first seen running.
     *
     * The host reports that a turn is running, not when it started, so the live
     * update's chronometer is based on the earliest moment this phone could observe.
     * The open conversation's own `startedAtMillis` is used instead whenever it is
     * available -- that is the host's number rather than an observation.
     */
    private val runningSince = mutableMapOf<String, Long>()

    /** The session the live update is currently about, so a settle knows to take it down. */
    private var liveSessionId: String? = null

    /**
     * What the live update last said, so the ten-second poll does not repost it.
     *
     * A live update is a status surface: re-notifying it every poll resets the
     * system's own promotion bookkeeping for no visible gain.
     */
    private var liveSignature: String? = null

    /** The transcript's shape as last logged, so the diagnostic line only moves when it does. */
    private var lastTurnShape: String? = null

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
            loadAccount()
            val hosts = credentials.loadAll().sortedBy { it.pairedAtMillis }
            val host = hosts.lastOrNull()
            if (host == null) {
                _state.update { it.copy(phase = Phase.UNPAIRED, hosts = emptyList()) }
                publishLinkNotice(getApplication<Application>().getString(R.string.link_no_host))
                return@launch
            }
            _state.update { it.copy(phase = Phase.CONNECTING, hosts = hosts, host = host) }
            publishLinkNotice(getApplication<Application>().getString(R.string.link_connecting))
            connect(host)
        }
    }

    // ── account ──────────────────────────────────────────────────────────────

    /** Read the stored session and quietly refresh it. Never blocks a launch. */
    private suspend fun loadAccount() {
        // Warm the install id off the main thread: the first read touches disk.
        withContext(Dispatchers.IO) { accounts.installId }
        val stored = runCatching { accounts.load() }.getOrNull()
        if (stored == null) {
            _state.update { it.copy(account = it.account.copy(phase = AccountPhase.READY)) }
            return
        }
        _state.update { it.copy(account = it.account.copy(phase = AccountPhase.READY, session = stored)) }
        // The refresh is deliberately not awaited: connecting to the computer must
        // not wait on an account service that may be slow or unreachable.
        viewModelScope.launch { refreshAccountNow(silent = true) }
    }

    /** Pull the profile again; the account screen's retry and the foreground hook. */
    fun refreshAccount() {
        viewModelScope.launch { refreshAccountNow() }
    }

    /** A phone that comes back to the foreground asks once, quietly. */
    fun refreshAccountOnForeground() {
        val account = _state.value.account
        if (account.session == null || account.phase == AccountPhase.LOADING || account.working) return
        viewModelScope.launch { refreshAccountNow(silent = true) }
    }

    private suspend fun refreshAccountNow(silent: Boolean = false) {
        val session = _state.value.account.session ?: return
        if (!silent) _state.update { it.copy(account = it.account.copy(phase = AccountPhase.LOADING, error = null)) }
        try {
            val user = accountClient.me(session.token, accounts.installId)
            // A sign-out may have landed while this was in flight; do not resurrect it.
            if (_state.value.account.session?.token != session.token) return
            val updated = session.copy(profile = user, savedAtMillis = System.currentTimeMillis())
            val wasPaid = _state.value.account.isPaid
            runCatching { accounts.save(updated) }
            _state.update {
                it.copy(account = it.account.copy(phase = AccountPhase.READY, session = updated, error = null))
            }
            // The plan decides which relay the host's tunnel should use. A change in
            // either direction asks the host to move; nothing else here touches it.
            if (wasPaid != updated.profile?.isPaid) {
                connection?.let { socket -> viewModelScope.launch { syncRelayTier(socket) } }
            }
        } catch (error: AccountError) {
            if (_state.value.account.session?.token != session.token) return
            if (error.requiresReauthentication) {
                runCatching { accounts.clear() }
                _state.update {
                    it.copy(account = it.account.copy(phase = AccountPhase.READY, session = null, notice = null))
                }
            } else {
                // Unreachable is not signed out: the cached profile stays on screen.
                _state.update {
                    it.copy(account = it.account.copy(phase = AccountPhase.UNREACHABLE, error = error.message))
                }
            }
        }
    }

    fun openAccountScreen() {
        _state.update { it.copy(account = it.account.copy(screenOpen = true, error = null)) }
    }

    // ── balance ─────────────────────────────────────────────────────────────

    /**
     * Open the balance screen and read it.
     *
     * The figure is the connected computer's, so it is read through the host rather
     * than from this phone's account: `account.balance` is answered by the plugin,
     * and a host that cannot ask DeepSeek says so instead of failing the screen.
     */
    fun openBalance() {
        _state.update { it.copy(balance = it.balance.copy(open = true)) }
        refreshBalance()
    }

    fun closeBalance() {
        _state.update { it.copy(balance = it.balance.copy(open = false)) }
    }

    fun refreshBalance() {
        _state.update { it.copy(balance = it.balance.copy(loading = true, failed = false)) }
        viewModelScope.launch {
            val socket = connection
            if (socket == null) {
                _state.update { it.copy(balance = it.balance.copy(loading = false, failed = true)) }
                return@launch
            }
            // The host validates all three: a version, a locale and an offset, because
            // DeepSeek's own answer is formatted for whoever asks.
            val client = me.shirazu.mizar.kit.jsonObjectOf(
                "version" to jsonString(appVersion),
                "locale" to jsonString(java.util.Locale.getDefault().toLanguageTag()),
                "timezoneOffsetSeconds" to me.shirazu.mizar.kit.jsonInt(
                    java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000,
                ),
            )
            val result = runCatching {
                socket.call<me.shirazu.mizar.kit.account.BalanceResult>(
                    "account.balance",
                    payload("client" to client),
                )
            }
            result.fold(
                onSuccess = { answer ->
                    _state.update {
                        it.copy(balance = it.balance.copy(loading = false, failed = false, result = answer))
                    }
                    note("balance: ${answer.status}")
                },
                onFailure = { error ->
                    _state.update { it.copy(balance = it.balance.copy(loading = false, failed = true)) }
                    note("balance failed: ${error.message}")
                },
            )
        }
    }

    fun closeAccountScreen() {
        _state.update { it.copy(account = it.account.copy(screenOpen = false, error = null, handoffEntry = false)) }
    }

    fun setAccountMode(mode: AccountMode) {
        _state.update { it.copy(account = it.account.copy(mode = mode, error = null)) }
    }

    fun showHandoffEntry(show: Boolean) {
        _state.update { it.copy(account = it.account.copy(handoffEntry = show, error = null)) }
    }

    // ── delivered files ─────────────────────────────────────────────────────

    /**
     * Open one of the files a turn handed over.
     *
     * The host reads the bytes; this screen decides how to draw them. One slice is
     * asked for, because a preview is a look and not a download: text says when it
     * is only the first 256 KB, and an image that does not fit says so instead of
     * pretending to be blank.
     */
    fun openFilePreview(path: String, name: String) {
        _state.update {
            it.copy(filePreview = FilePreviewUiState(open = true, path = path, name = name, loading = true))
        }
        val kind = kindOf(name)
        if (kind == FileKind.OTHER) {
            _state.update { it.copy(filePreview = it.filePreview.copy(loading = false, unsupported = true)) }
            return
        }
        viewModelScope.launch {
            val socket = connection
            val sessionId = _state.value.selectedSessionId
            val slice = if (socket == null || sessionId == null) {
                null
            } else {
                runCatching {
                    socket.call<me.shirazu.mizar.kit.FileSlice>(
                        "file.read",
                        payload(
                            "sessionId" to jsonString(sessionId),
                            "path" to jsonString(path),
                            "offset" to me.shirazu.mizar.kit.jsonInt(0),
                            "length" to me.shirazu.mizar.kit.jsonInt(me.shirazu.mizar.kit.FileSlice.MAX_SLICE_BYTES),
                        ),
                    )
                }.onFailure { note("file.read failed: ${it.message}") }.getOrNull()
            }
            if (slice == null) {
                _state.update { it.copy(filePreview = it.filePreview.copy(loading = false, failed = true)) }
                return@launch
            }
            val bytes = slice.bytes()
            if (kind == FileKind.PDF) {
                // A PDF is rendered, not shown: the framework's renderer reads a file
                // descriptor, so the slice is written to the cache first. A document
                // that did not fit in one slice is not rendered at all -- half a PDF
                // is not a preview of it.
                val rendered = if (slice.eof) withContext(Dispatchers.IO) { renderPdf(bytes) } else null
                _state.update {
                    it.copy(
                        filePreview = it.filePreview.copy(
                            loading = false,
                            byteSize = slice.byteSize,
                            unsupported = rendered == null,
                            pages = rendered?.pages ?: emptyList(),
                            pageCount = rendered?.pageCount ?: 0,
                        ),
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    filePreview = it.filePreview.copy(
                        loading = false,
                        byteSize = slice.byteSize,
                        truncated = !slice.eof,
                        text = if (kind == FileKind.TEXT) String(bytes, Charsets.UTF_8) else null,
                        // An image larger than one slice would render as a corrupt
                        // half-file; saying so is the honest answer.
                        image = if (kind == FileKind.IMAGE && slice.eof) bytes else null,
                        unsupported = kind == FileKind.IMAGE && !slice.eof,
                    ),
                )
            }
        }
    }

    /**
     * Render the first pages of a PDF.
     *
     * `PdfRenderer` wants a file descriptor, so the bytes go through the cache; the
     * file is deleted as soon as the pages are drawn. Page count is capped because
     * each page is a full-screen bitmap: a 200-page report would be a memory leak
     * with a nice name.
     *
     * @param bytes - the document.
     * @returns the pages and how many the document has, or `null` when it cannot be
     *   opened as a PDF at all.
     */
    private fun renderPdf(bytes: ByteArray): RenderedPdf? {
        val file = java.io.File(getApplication<Application>().cacheDir, "preview.pdf")
        return try {
            file.writeBytes(bytes)
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                    val total = renderer.pageCount
                    val pages = ArrayList<android.graphics.Bitmap>(minOf(total, PDF_PAGE_LIMIT))
                    for (index in 0 until minOf(total, PDF_PAGE_LIMIT)) {
                        renderer.openPage(index).use { page ->
                            // A white sheet: a rendered page has transparent pixels
                            // wherever the PDF does, and those read as holes on a dark
                            // theme.
                            val bitmap = android.graphics.Bitmap.createBitmap(
                                page.width * PDF_SCALE,
                                page.height * PDF_SCALE,
                                android.graphics.Bitmap.Config.ARGB_8888,
                            )
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pages += bitmap
                        }
                    }
                    RenderedPdf(pages, total)
                }
            }
        } catch (error: Throwable) {
            note("pdf render failed: ${error.message}")
            null
        } finally {
            file.delete()
        }
    }

    private class RenderedPdf(val pages: List<android.graphics.Bitmap>, val pageCount: Int)

    /** How many pages a preview renders. */
    private val PDF_PAGE_LIMIT = 5

    /** Rendered at 1.5x so text is legible without a zoom control. */
    private val PDF_SCALE = 2

    /** How this screen can draw a file, decided by its extension. */
    private enum class FileKind { TEXT, IMAGE, PDF, OTHER }

    private fun kindOf(name: String): FileKind {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "md", "markdown", "txt", "log", "json", "yaml", "yml", "toml", "ini", "csv",
            "kt", "kts", "java", "py", "ts", "tsx", "js", "jsx", "css", "html", "xml",
            "sh", "bat", "ps1", "sql", "gradle", "properties",
            -> FileKind.TEXT
            "png", "jpg", "jpeg", "webp", "gif", "bmp",
            -> FileKind.IMAGE
            "pdf",
            -> FileKind.PDF
            else -> FileKind.OTHER
        }
    }

    // ── password reset ──────────────────────────────────────────────────────

    fun openPasswordReset() {
        _state.update {
            it.copy(account = it.account.copy(resetOpen = true, resetChallenge = null, error = null))
        }
    }

    fun closePasswordReset() {
        _state.update {
            it.copy(account = it.account.copy(resetOpen = false, resetChallenge = null, error = null))
        }
    }

    /**
     * Ask for a reset code.
     *
     * The service answers the same way for an address it does not know, so the
     * screen's copy says "if that address has an account" rather than confirming
     * one. That is the service's privacy decision, not a wording choice here.
     */
    fun startPasswordReset(email: String) {
        val address = email.trim()
        if (!address.contains('@') || address.startsWith('@') || address.endsWith('@')) {
            setAccountError(getApplication<Application>().getString(R.string.account_error_email))
            return
        }
        if (_state.value.account.working) return
        viewModelScope.launch {
            _state.update { it.copy(account = it.account.copy(working = true, error = null)) }
            try {
                val challenge = accountClient.resetStart(address)
                _state.update {
                    it.copy(account = it.account.copy(working = false, resetChallenge = challenge))
                }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(account = it.account.copy(working = false, error = messageFor(error)))
                }
            }
        }
    }

    /** Finish the reset; the service signs this device in on the way through. */
    fun completePasswordReset(code: String, password: String) {
        val challenge = _state.value.account.resetChallenge ?: return
        if (password.length !in 8..128) {
            setAccountError(getApplication<Application>().getString(R.string.account_error_password))
            return
        }
        if (code.trim().isEmpty()) {
            setAccountError(getApplication<Application>().getString(R.string.account_error_code))
            return
        }
        authenticate { accountClient.resetComplete(challenge.email, code.trim(), password, devicePayload()) }
    }

    fun clearAccountError() {
        _state.update { it.copy(account = it.account.copy(error = null)) }
    }

    /** Sign in with a username or an email. */
    fun loginAccount(identifier: String, password: String) {
        val name = identifier.trim()
        if (name.isEmpty() || password.isEmpty()) {
            setAccountError(getApplication<Application>().getString(R.string.account_error_missing))
            return
        }
        authenticate { accountClient.login(name, password, devicePayload()) }
    }

    fun registerAccount(username: String, email: String, password: String) {
        val name = username.trim()
        val address = email.trim()
        val problem = when {
            name.length !in 2..32 -> R.string.account_error_username
            name.any { it.isWhitespace() || it == '@' } -> R.string.account_error_username
            !address.contains('@') || address.startsWith('@') || address.endsWith('@') -> R.string.account_error_email
            password.length !in 8..128 -> R.string.account_error_password
            else -> null
        }
        if (problem != null) {
            setAccountError(getApplication<Application>().getString(problem))
            return
        }
        // Two deployments, one build. A server with mail configured refuses this
        // without a code (428) and wants `/register/start` first; one without mail
        // accepts it here. Trying once is how the client tells them apart without a
        // capability endpoint neither service has.
        if (_state.value.account.working) return
        viewModelScope.launch {
            _state.update { it.copy(account = it.account.copy(working = true, error = null)) }
            try {
                adopt(accountClient.register(name, address, password, devicePayload()))
            } catch (error: AccountError) {
                if (error.code != "EMAIL_VERIFICATION_REQUIRED") {
                    _state.update {
                        it.copy(account = it.account.copy(working = false, error = messageFor(error)))
                    }
                    return@launch
                }
                try {
                    val challenge = accountClient.registerStart(name, address, password, devicePayload())
                    _state.update {
                        it.copy(
                            account = it.account.copy(
                                working = false,
                                error = null,
                                pendingRegistration = PendingRegistration(name, address, password),
                                registerChallenge = challenge,
                            ),
                        )
                    }
                } catch (startError: Throwable) {
                    _state.update {
                        it.copy(account = it.account.copy(working = false, error = messageFor(startError)))
                    }
                }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(account = it.account.copy(working = false, error = messageFor(error)))
                }
            }
        }
    }

    /** Finish a registration whose code has arrived. */
    fun completeRegistration(code: String) {
        val pending = _state.value.account.pendingRegistration ?: return
        if (code.trim().isEmpty()) {
            setAccountError(getApplication<Application>().getString(R.string.account_error_code))
            return
        }
        authenticate {
            accountClient.register(
                pending.username,
                pending.email,
                pending.password,
                devicePayload(),
                code.trim(),
            )
        }
    }

    /** Back out of the code step: nothing was created, so nothing is left behind. */
    fun cancelRegistration() {
        _state.update {
            it.copy(account = it.account.copy(pendingRegistration = null, registerChallenge = null, error = null))
        }
    }

    /** One path for login, registration and the GitHub exchange. */
    private fun authenticate(block: suspend () -> AccountAuth) {
        if (_state.value.account.working) return
        viewModelScope.launch {
            _state.update { it.copy(account = it.account.copy(working = true, error = null)) }
            try {
                adopt(block())
            } catch (error: Throwable) {
                _state.update {
                    it.copy(account = it.account.copy(working = false, error = messageFor(error)))
                }
            }
        }
    }

    /**
     * Take a session: save the token, show the profile, reconnect with identity.
     *
     * Shared by every path that ends signed in -- login, registration, the GitHub
     * exchange, a password reset -- because a path that forgot one of these would
     * look signed in until the next launch.
     */
    private suspend fun adopt(auth: AccountAuth) {
        val session = AccountSession(auth.token, auth.user, System.currentTimeMillis())
        accounts.save(session)
        _state.update {
            it.copy(
                account = it.account.copy(
                    phase = AccountPhase.READY,
                    session = session,
                    notice = auth.notice,
                    working = false,
                    error = null,
                    mode = AccountMode.SIGN_IN,
                    handoffEntry = false,
                    resetOpen = false,
                    resetChallenge = null,
                    pendingRegistration = null,
                    registerChallenge = null,
                ),
            )
        }
        reconnectForIdentity()
    }

    /** Open GitHub in the browser. With a session, this links instead of signing in. */
    fun beginGitHubSignIn(link: Boolean = false) {
        val session = _state.value.account.session
        if (link && session == null) return
        if (_state.value.account.working) return
        viewModelScope.launch {
            _state.update { it.copy(account = it.account.copy(working = true, error = null)) }
            try {
                val start = accountClient.githubStart(devicePayload(), if (link) session?.token else null)
                _state.update { it.copy(account = it.account.copy(working = false)) }
                openBrowser(start.authorizeUrl)
            } catch (error: Throwable) {
                _state.update {
                    it.copy(account = it.account.copy(working = false, error = messageFor(error)))
                }
            }
        }
    }

    /** The landing page handed the app a code; trade it for a session. */
    fun handleAuthUri(uri: String) {
        val handoff = AuthUri.parse(uri) ?: return
        redeemGitHubHandoff(handoff)
    }

    fun redeemGitHubHandoff(handoff: String) {
        val code = handoff.trim()
        if (code.isEmpty()) return
        authenticate { accountClient.githubExchange(code, devicePayload()) }
    }

    /** Add paid days to the signed-in account. */
    fun openRedeem() {
        _state.update {
            it.copy(account = it.account.copy(redeemOpen = true, redeemNotice = null, error = null))
        }
    }

    fun closeRedeem() {
        _state.update {
            it.copy(account = it.account.copy(redeemOpen = false, redeemNotice = null, error = null))
        }
    }

    /**
     * Turn a code into paid days.
     *
     * The service adds the days to whatever the account already has, so two codes in
     * a row stack; the answer is the whole profile, which is where the new end date
     * comes from. Saying that date out loud is the point of this function -- a
     * redeem that only changed a line further up the screen reads as nothing
     * happening.
     */
    fun redeemInvite(code: String) {
        val session = _state.value.account.session ?: return
        val value = code.trim()
        if (value.isEmpty() || _state.value.account.working) return
        viewModelScope.launch {
            _state.update { it.copy(account = it.account.copy(working = true, error = null, redeemNotice = null)) }
            try {
                val user = accountClient.redeem(session.token, value)
                val updated = session.copy(profile = user, savedAtMillis = System.currentTimeMillis())
                accounts.save(updated)
                val until = user.paidUntil ?: user.grantedPaidUntil
                val notice = until?.let {
                    getApplication<Application>().getString(
                        R.string.redeem_accepted,
                        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it)),
                    )
                } ?: getApplication<Application>().getString(R.string.redeem_accepted_plain)
                _state.update {
                    it.copy(
                        account = it.account.copy(
                            phase = AccountPhase.READY,
                            session = updated,
                            working = false,
                            redeemNotice = notice,
                        ),
                    )
                }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(account = it.account.copy(working = false, error = messageFor(error)))
                }
            }
        }
    }

    /** Sign out locally first; the service call is best effort. */
    fun logoutAccount() {
        val session = _state.value.account.session ?: return
        viewModelScope.launch {
            runCatching { accounts.clear() }
            _state.update {
                it.copy(
                    account = it.account.copy(
                        phase = AccountPhase.READY,
                        session = null,
                        notice = null,
                        error = null,
                        handoffEntry = false,
                    ),
                )
            }
            runCatching { accountClient.logout(session.token) }
            reconnectForIdentity()
        }
    }

    /** Delete the account; only clear the phone once the service has confirmed. */
    fun deleteAccount() {
        val session = _state.value.account.session ?: return
        if (_state.value.account.working) return
        viewModelScope.launch {
            _state.update { it.copy(account = it.account.copy(working = true, error = null)) }
            try {
                accountClient.delete(session.token)
            } catch (error: Throwable) {
                _state.update {
                    it.copy(account = it.account.copy(working = false, error = messageFor(error)))
                }
                return@launch
            }
            runCatching { accounts.clear() }
            _state.update {
                it.copy(
                    account = it.account.copy(
                        phase = AccountPhase.READY,
                        session = null,
                        notice = null,
                        working = false,
                        error = null,
                        handoffEntry = false,
                    ),
                )
            }
            reconnectForIdentity()
        }
    }

    /**
     * Redial with the new identity headers.
     *
     * The relay reads them once, at the upgrade, so signing in or out cannot be
     * patched onto a live socket.
     */
    private fun reconnectForIdentity() {
        val host = _state.value.host ?: return
        viewModelScope.launch { connect(host) }
    }

    private fun devicePayload() = AccountDevicePayload(
        id = accounts.installId,
        name = deviceModel,
        model = Build.MODEL,
        platform = "android",
        appVersion = BuildConfig.VERSION_NAME,
    )

    private fun setAccountError(message: String) {
        _state.update { it.copy(account = it.account.copy(error = message)) }
    }

    private fun messageFor(error: Throwable): String = when (error) {
        is AccountError -> error.message
        else -> error.message ?: getApplication<Application>().getString(R.string.account_error_generic)
    }

    private fun openBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { setAccountError(getApplication<Application>().getString(R.string.account_error_no_browser)) }
    }

    /** Redeem a scanned or typed pairing code. */
    fun pair(target: PairingTarget, deviceName: String) {
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.CONNECTING, problem = null, pairingStage = PairingStage.PAIRING) }
            // Relay attempts carry the install's identity: pairing is the first
            // request a fresh install makes, and the one the relay meters. A
            // direct attempt gets none of this -- see PairingClient.
            val client = PairingClient(
                appVersion = appVersion,
                relayHeaders = AccountIdentity.headers(
                    installId = accounts.installId,
                    token = _state.value.account.session?.token,
                    relayBaseUrl = target.baseUrl,
                    accountBaseUrl = BuildConfig.ACCOUNT_BASE_URL,
                ),
            )
            try {
                // The QR carries a direct address and a relay; a phone that cannot
                // reach the first must still be able to pair through the second.
                val outcome = client.pairWithFallback(
                    code = target.code,
                    deviceName = deviceName.ifBlank { "Android" },
                    deviceModel = deviceModel,
                    authority = target.authority,
                    baseUrl = target.baseUrl,
                )
                val paired = outcome.host
                note("paired over ${outcome.route.label}")
                credentials.save(paired)
                val hosts = credentials.loadAll().sortedBy { it.pairedAtMillis }
                _state.update {
                    it.copy(hosts = hosts, host = paired, pendingNameHostId = paired.hostId, pairingStage = PairingStage.SYNCING)
                }
                // Start on the route that just answered, so the first connect does
                // not spend the other route's timeout before it.
                connect(paired, preferred = outcome.route, warm = true)
            } catch (error: Throwable) {
                // One line per route: "pairing failed" and "the relay refused the
                // code" are the same screen and different problems.
                if (error is PairingFailure) error.lines().forEach { note("pair route $it") }
                val cause = (error as? PairingFailure)?.finalError ?: error
                _state.update {
                    it.copy(
                        phase = Phase.FAILED,
                        problem = messageOf(cause, target.authority ?: target.baseUrl),
                        pairingStage = null,
                        syncProgress = null,
                    )
                }
            }
        }
    }

    private suspend fun connect(host: PairedHost, preferred: PairingRoute? = null, warm: Boolean = false) {
        stopWatchers()
        connection?.disconnect()
        connection = null

        // The stored list first: a phone with no network still shows what it knows.
        indexStore.load(host.hostId)?.let { applyIndex(it) }

        var failure: Throwable? = null
        // The failure that proves the most, and the route it came from: with several
        // routes tried, "the last one to fail" is an accident of timing.
        var bestWeight = -1
        var bestEndpoint: MizarConnection.Endpoint? = null
        // The route that just answered goes first; the other stays behind it.
        val endpoints = when (preferred) {
            is PairingRoute.Relay -> host.endpoints.sortedBy { it !is MizarConnection.Endpoint.Relay }
            is PairingRoute.Direct -> host.endpoints.sortedBy { it !is MizarConnection.Endpoint.Direct }
            null -> host.endpoints
        }
        // Dial every route at once. The handshake timeout is twenty seconds, and a
        // route that blackholes spends all of it before the next one is even tried --
        // the slowest possible order for a phone that only wants to connect. The
        // first route to answer wins; the rest are cancelled without being waited on.
        val results = Channel<DialOutcome>(Channel.UNLIMITED)
        val jobs = endpoints.map { endpoint ->
            viewModelScope.launch {
                val outcome = try {
                    Result.success(dial(host, endpoint))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                results.send(DialOutcome(endpoint, outcome))
            }
        }

        var winner: DialOutcome? = null
        var settled = 0
        while (settled < endpoints.size && winner == null) {
            val outcome = results.receive()
            settled += 1
            if (outcome.connected != null) {
                winner = outcome
                // A loser may still be inside its own handshake; cancelling closes
                // its socket, and awaiting that would only delay the winner.
                jobs.forEach { it.cancel() }
                break
            }
            val error = outcome.failure ?: continue
            note("endpoint ${outcome.endpoint} failed: ${error.message}")
            val weight = me.shirazu.mizar.kit.Diagnoses.weight(
                me.shirazu.mizar.kit.Diagnoses.of(
                    error,
                    addressOf(outcome.endpoint),
                    throughRelay = outcome.endpoint is MizarConnection.Endpoint.Relay,
                ),
            )
            if (weight > bestWeight) {
                bestWeight = weight
                failure = error
                bestEndpoint = outcome.endpoint
            }
        }

        val won = winner
        if (won == null) {
            val address = host.authority ?: host.baseUrl
            lastConnectFailure = failure
            lastConnectEndpoint = bestEndpoint ?: host.endpoints.lastOrNull()
            _state.update {
                it.copy(
                    phase = Phase.FAILED,
                    problem = messageOf(
                        failure ?: IOException("no address answered"),
                        address,
                        throughRelay = lastConnectEndpoint is MizarConnection.Endpoint.Relay,
                    ),
                    needsPairing = failure?.isRepairNeeded() == true,
                    pairingStage = null,
                )
            }
            publishLinkFailure()
            return
        }

        val connected = won.connected ?: return
        adoptConnection(host, won.endpoint, connected.first, connected.second, warm)
    }

    /**
     * One route's attempt: build the connection, complete the handshake.
     *
     * Disconnects its own candidate when the attempt fails or when the race is lost
     * mid-handshake, so a route that was cancelled after another one won does not
     * leave a socket open behind the connection the app is actually using.
     */
    private suspend fun dial(
        host: PairedHost,
        endpoint: MizarConnection.Endpoint,
    ): Pair<MizarConnection, ServerFrame.Welcome> {
        val identityHeaders = AccountIdentity.headers(
            installId = accounts.installId,
            token = _state.value.account.session?.token,
            relayBaseUrl = host.baseUrl,
            accountBaseUrl = BuildConfig.ACCOUNT_BASE_URL,
        )
        val candidate = MizarConnection(
            host.configurationFor(endpoint, appVersion, deviceModel, identityHeaders),
            traffic,
        )
        try {
            val welcome = candidate.connect()
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                candidate.disconnect()
                throw kotlinx.coroutines.CancellationException("another route answered first")
            }
            return candidate to welcome
        } catch (error: Throwable) {
            candidate.disconnect()
            throw error
        }
    }

    /**
     * Take the route that answered: state, watchers, the first page, push.
     *
     * Split out of [connect] because the race decides *which* route, and everything
     * here is the same whichever one it was.
     */
    private suspend fun adoptConnection(
        host: PairedHost,
        endpoint: MizarConnection.Endpoint,
        candidate: MizarConnection,
        welcome: ServerFrame.Welcome,
        warm: Boolean,
    ) {
        connection = candidate
        connectionEpoch++
        _state.update {
            it.copy(
                // A first pairing stays on the connecting screen while it
                // warms the index: READY would swap in the shell and the
                // progress would never be seen. A reconnect has nothing to
                // warm, so it goes straight through.
                phase = if (warm) Phase.CONNECTING else Phase.READY,
                welcome = welcome,
                problem = null,
                needsPairing = false,
                pairingStage = if (warm) PairingStage.SYNCING else null,
                offlineBrowser = false,
                seenAt = activity.seenAt(host.hostId),
                isLocalNetwork = endpoint is MizarConnection.Endpoint.Direct,
                connectedAtMillis = System.currentTimeMillis(),
                latencyHistory = emptyList(),
            )
        }
        // The notice says who we are talking to and how far away it is; the
        // distance arrives with the first poll, a second from now.
        publishLinkNotice(linkConnectedText(host, _state.value.latencyMillis))
        startWatchers(candidate)
        watchConnection(candidate, host)
        note("connected to $endpoint")
        if (warm) {
            // `finally`: a host that goes away mid-warm must still land on the
            // shell. Staying on "reading sessions" for ever is the one failure
            // mode of a progress screen.
            try {
                refreshSessionsNow()
                warmTitles(candidate)
            } finally {
                _state.update {
                    it.copy(
                        phase = Phase.READY,
                        pairingStage = null,
                        syncProgress = null,
                        syncEtaMillis = null,
                    )
                }
            }
        } else {
            refreshSessions()
        }
        registerPush(candidate)
        // A relay socket is the fallback, not the goal: when the operator asked
        // for LAN-first, look for the computer's own addresses now that a socket
        // exists to ask `host.info` over.
        if (endpoint !is MizarConnection.Endpoint.Direct && _state.value.localNetworkFirst) {
            maybeSwitchToLan(host, candidate, welcome)
        }
        // A paid account's tunnel belongs on the account relay, and the host
        // has no account identity: the phone is the only side that knows the
        // plan. Skipped by hosts that predate relay tiers.
        if (endpoint !is MizarConnection.Endpoint.Direct) {
            viewModelScope.launch { syncRelayTier(candidate) }
        }
    }

    /**
     * What the notice says while the link is up: who, and how far away.
     *
     * The latency is omitted rather than guessed on the first frame; a "0 ms" that is
     * really "not measured yet" is the kind of detail that makes a monitor untrustworthy.
     */
    private fun linkConnectedText(host: PairedHost, latencyMillis: Long?): String {
        val context = getApplication<Application>()
        val name = host.displayName
        return if (latencyMillis == null) {
            context.getString(R.string.link_connected, name)
        } else {
            context.getString(R.string.link_connected_latency, name, latencyMillis)
        }
    }

    /**
     * Which half of the link is down.
     *
     * The relay answers with a status when it is up and something behind it is not:
     * 404/410 is "that room has no tunnel", which is the computer being offline rather
     * than the server. Everything else -- a timeout, a refused connection, 429 or a
     * 5xx from the relay itself -- is the server's side of the trip not answering. A
     * direct endpoint has no server in the middle: if it fails, the host is offline.
     */
    private fun publishLinkFailure() {
        val context = getApplication<Application>()
        val endpoint = lastConnectEndpoint
        val status = (lastConnectFailure as? me.shirazu.mizar.kit.UpgradeRefused)?.status
        val text = when {
            endpoint is MizarConnection.Endpoint.Direct -> context.getString(R.string.link_host_offline)
            status == 404 || status == 410 -> context.getString(R.string.link_host_offline)
            else -> context.getString(R.string.link_server_unresponsive)
        }
        publishLinkNotice(text)
    }

    /** Write the notice once; the same sentence twice in a row is not news. */
    private fun publishLinkNotice(text: String) {
        if (text == lastLinkNotice) return
        lastLinkNotice = text
        ConnectionService.update(getApplication(), text)
        note("link notice: $text")
    }

    /**
     * Remember the round trip, and say it in the notice when it is news.
     *
     * A few milliseconds of jitter is not news: the notice is a monitor, not a
     * speedometer, so it is rewritten only when the number has moved by something a
     * person would notice, or when it was never measured before.
     */
    private fun recordLatency(millis: Long) {
        val rounded = millis.coerceIn(1, 9_999)
        val previous = _state.value.latencyMillis
        _state.update {
            it.copy(
                latencyMillis = rounded,
                latencyHistory = (it.latencyHistory + rounded).takeLast(30),
            )
        }
        val host = _state.value.host ?: return
        if (previous == null || kotlin.math.abs(rounded - previous) >= LATENCY_NOTICE_STEP_MILLIS) {
            publishLinkNotice(linkConnectedText(host, rounded))
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
            // The reason is known but not its owner; until an attempt comes back with
            // something to classify, the notice says only that it is trying.
            publishLinkNotice(getApplication<Application>().getString(R.string.link_reconnecting))
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
                    refreshAfterReconnect()
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
            if ((detail.failed && !_state.value.notifyInterrupted) || (!detail.failed && !_state.value.notifyCompleted)) {
                note("list: ${row.sessionId} notice category off")
                continue
            }
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
            }.getOrDefault(false)
            if (posted) announcer.record(row.sessionId, System.currentTimeMillis())
            note("list: ${row.sessionId} posted=$posted body=$body")
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
                payload(*pageAddress(sessionId), "maxMessages" to me.shirazu.mizar.kit.jsonInt(12)),
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
            _state.update { it.copy(phase = Phase.CONNECTING, problem = null, offlineBrowser = false) }
            connect(host)
        }
    }

    /**
     * Read the sessions the phone already knows while the host is away.
     *
     * The session list and the transcript cache are on disk; an unreachable computer
     * is not a reason to make them unreadable. Sending still fails, which is what the
     * shell's own error surfaces say.
     */
    fun browseOffline() {
        _state.update { it.copy(offlineBrowser = true) }
    }

    /**
     * Read the delegated children's own names, once per child.
     *
     * The session list carries the children but not their titles; iOS shows the
     * title from the child's usage frame, else its first prompt. Same sources here,
     * read only when the board is opened -- the app deliberately never syncs
     * subagent pages in bulk.
     */
    fun loadSubagentTitles() {
        val selected = _state.value.selectedSessionId ?: return
        if (_state.value.subagentLoading) return
        val children = _state.value.sessions.filter {
            it.isSubagent && it.parentSessionId == selected
        }
        val pending = children.map { it.sessionId }.filterNot { it in _state.value.subagentTitles }.take(20)
        if (pending.isEmpty()) return
        val socket = connection ?: return
        _state.update { it.copy(subagentLoading = true) }
        viewModelScope.launch {
            try {
                for (childId in pending) {
                    val title = readSubagentTitle(socket, childId)
                    if (title != null) {
                        _state.update { it.copy(subagentTitles = it.subagentTitles + (childId to title)) }
                    }
                }
            } finally {
                _state.update { it.copy(subagentLoading = false) }
            }
        }
    }

    /** The child's usage-frame title, else the first line of its first prompt. */
    private suspend fun readSubagentTitle(socket: MizarConnection, childId: String): String? {
        val fromStats = runCatching {
            socket.call<SessionStatsResult>("session.stats", payload("sessionId" to jsonString(childId)))
        }.getOrNull()?.stats?.title?.takeIf { it.isNotBlank() }
        if (fromStats != null) {
            note("subagent title $childId -> stats")
            return fromStats
        }
        val title = runCatching {
            // The tail of a short delegated session usually contains its whole log;
            // 200 records is the same page size the conversation asks for and enough
            // to reach the prompt the child was given.
            val page = socket.call<SessionMessagesResult>(
                "session.messages",
                payload(*pageAddress(childId), "maxMessages" to me.shirazu.mizar.kit.jsonInt(200)),
            )
            Conversation.items(page)
                .filterIsInstance<ConversationItem.Message>()
                .firstOrNull { it.role == ConversationItem.Role.USER }
                ?.text
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
                ?.take(80)
        }.getOrNull()
        note("subagent title $childId -> ${title ?: "miss"}")
        return title
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
                    pendingNameHostId = null,
                )
            }
            connect(host)
        }
    }

    /**
     * Give a paired computer a label of the operator's own, or take one away.
     * An empty label means "use the machine's own name" (`PairedHost.renamed`).
     */
    fun renameHost(hostId: String, label: String?) {
        viewModelScope.launch {
            val current = credentials.loadAll().firstOrNull { it.hostId == hostId } ?: return@launch
            val renamed = current.renamed(to = label)
            credentials.save(renamed)
            val hosts = credentials.loadAll().sortedBy { it.pairedAtMillis }
            _state.update {
                it.copy(
                    hosts = hosts,
                    host = if (it.host?.hostId == hostId) renamed else it.host,
                    pendingNameHostId = if (it.pendingNameHostId == hostId) null else it.pendingNameHostId,
                )
            }
        }
    }

    /** The naming prompt was declined: naming is optional, and "skip" is final. */
    fun dismissNaming() {
        _state.update { it.copy(pendingNameHostId = null) }
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
                _state.update {
                    it.copy(phase = if (hosts.isEmpty()) Phase.UNPAIRED else Phase.CONNECTING, hosts = hosts)
                }
                hosts.lastOrNull()?.let { connect(it) }
            } else {
                _state.update { it.copy(hosts = hosts) }
            }
        }
    }

    // ── sessions ────────────────────────────────────────────────────────────

    fun refreshSessions() {
        // A manual refresh is also a natural moment to look for the LAN again: the
        // operator pulled because something looked stale, often after moving onto the
        // same Wi-Fi as the computer.
        val host = _state.value.host
        val socket = connection
        val welcome = _state.value.welcome
        if (host != null && socket != null && welcome != null) maybeSwitchToLan(host, socket, welcome)
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
        val workspaces = runCatching {
            socket.call<WorkspaceListResult>("workspace.list").items
        }.getOrNull()

        _state.update { current ->
            current.copy(
                sessions = list.items,
                workspaces = if (workspaces == null) current.workspaces else workspaces,
                workspacePaths = if (workspaces.isNullOrEmpty()) current.workspacePaths else workspaces.map { it.path }.toSet(),
                problem = null,
            )
        }
        regroup()
        persistIndex()
        // The host's list is the authority on whether the selected turn is still
        // running. If the open conversation still thinks it is, that copy is stale --
        // a host that does not broadcast `turn.settled` (an older plugin) would
        // otherwise leave the row running for ever.
        val selected = _state.value.selectedSessionId
        if (selected != null) {
            val rowRunning = list.items.firstOrNull { it.sessionId == selected }?.running == true
            val lastInFlight = _state.value.turns.lastOrNull()?.let { it.isRunning || it.isPending } == true
            if (!rowRunning && lastInFlight) refreshConversationQuietly()
        }
        announceFinishedTurns(list.items)
        syncLiveTurn(list.items)
        // The screen opens on what was last read, once: after that the operator is in
        // control of where they are.
        maybeRestoreLastSession()
    }

    /**
     * Keep the live update in step with the session list.
     *
     * One card, for the session that changed most recently: the lock screen has room
     * for one running turn, and the operator's attention is on the one they just
     * started. When nothing is running the card comes down here, which is the path
     * that covers a host too old to broadcast `turn.settled`.
     */
    private fun syncLiveTurn(rows: List<SessionRow>) {
        val running = rows.filter { it.running }
        runningSince.keys.retainAll(running.map { it.sessionId }.toSet())
        val shown = running.maxByOrNull { it.updatedMillis }
        if (shown == null) {
            liveSessionId = null
            liveSignature = null
            LiveTurn.hide(getApplication())
            return
        }
        // The host's own start time when this is the open conversation; otherwise the
        // first moment this phone could see the row running.
        val hostedStart = if (shown.sessionId == _state.value.selectedSessionId) {
            _state.value.turns.lastOrNull()?.startedAtMillis
        } else {
            null
        }
        val since = runningSince.getOrPut(shown.sessionId) { hostedStart ?: System.currentTimeMillis() }
        liveSessionId = shown.sessionId
        val title = _state.value.titles[shown.sessionId] ?: shown.sessionId.takeLast(8)
        val signature = "${shown.sessionId}|$title|$since"
        if (signature == liveSignature) return
        liveSignature = signature
        LiveTurn.show(getApplication(), shown.sessionId, title, since)
    }

    /** Load titles for rows about to be drawn, one request per session, once each. */    fun loadTitles(sessionIds: List<String>) {
        viewModelScope.launch {
            val socket = connection ?: return@launch
            var learned = false
            for (id in sessionIds) {
                if (_state.value.titles.containsKey(id)) continue
                if (learnTitle(socket, id)) learned = true
            }
            if (learned) persistIndex()
        }
    }

    /** One session's title, remembered when the host has one. */
    private suspend fun learnTitle(socket: MizarConnection, sessionId: String): Boolean {
        val title = runCatching {
            socket.call<SessionStatsResult>("session.stats", payload("sessionId" to jsonString(sessionId))).stats?.title
        }.getOrNull()
        if (title.isNullOrBlank()) return false
        _state.update { it.copy(titles = it.titles + (sessionId to title)) }
        return true
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
     * Bounded by [WARM_TITLE_LIMIT] and newest first: a host with four hundred
     * sessions should not make first pairing wait on four hundred round trips, and
     * the rows the operator is about to see are the recent ones. Whatever is left
     * over keeps the lazy path in [loadTitles].
     */
    private suspend fun warmTitles(socket: MizarConnection) {
        val known = _state.value.titles
        val pending = _state.value.sessions
            .asSequence()
            .filter { !known.containsKey(it.sessionId) }
            .sortedByDescending { it.updatedMillis }
            .take(WARM_TITLE_LIMIT)
            .map { it.sessionId }
            .toList()
        if (pending.isEmpty()) return

        val recent = ArrayDeque<Long>()
        var done = 0
        _state.update { it.copy(syncProgress = SyncProgress(done = 0, total = pending.size), syncEtaMillis = null) }
        for (id in pending) {
            val started = System.nanoTime()
            learnTitle(socket, id)
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            done++
            recent.addLast(elapsed)
            while (recent.size > SyncEta.SAMPLE_SIZE) recent.removeFirst()
            val eta = SyncEta.estimateMillis(done, pending.size, recent.toList())
            _state.update {
                it.copy(syncProgress = SyncProgress(done = done, total = pending.size), syncEtaMillis = eta)
            }
        }
        persistIndex()
        note("warmed $done/${pending.size} titles")
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
                activity = null,
                activityExpanded = false,
                activityLoading = false,
                activityLoadingOlder = false,
                activityError = null,
            )
        }
        activityJob?.cancel()
        activityJob = null
        clearAttachments()
        if (sessionId == null) return
        viewModelScope.launch {
            // What was on screen last time, before the first round trip: opening a
            // session offline shows its conversation rather than an empty card.
            val activeHost = _state.value.host?.hostId ?: hostId
            if (activeHost != null) {
                conversationCache.load(activeHost, sessionId)?.let { cached ->
                    if (_state.value.selectedSessionId == sessionId) {
                        applyPage(cached.messages)
                        cached.stats?.let { stats ->
                            _state.update { it.copy(stats = stats.stats, statsError = null) }
                        }
                    }
                }
            }
            reload()
            refreshInteractions()
        }
    }

    /** Create a session whose first prompt is already typed: create, then send. */
    fun createSessionWithPrompt(cwd: String, prompt: String) {
        viewModelScope.launch {
            val socket = connection ?: return@launch
            _state.update { it.copy(busy = true) }
            try {
                val created = socket.call<CreateSessionResult>(
                    "session.create",
                    buildJsonObject { put("cwd", cwd) },
                )
                applyNewSessionModel(socket, created.sessionId)
                refreshSessionsNow()
                selectSession(created.sessionId)
                sendPrompt(prompt)
            } catch (error: Throwable) {
                _state.update { it.copy(problem = messageOf(error, addressOf())) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
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
                applyNewSessionModel(socket, created.sessionId)
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
        val before = _state.value.stats?.context?.percent
        _state.update { current ->
            current.copy(stats = (current.stats ?: SessionStatsFrame()).copy(context = pressure))
        }
        // Only when it moves: this runs on every page, and a line per refresh would
        // bury the rest of the log. A figure that *stops* moving is the bug this
        // exists to catch, and silence here is how that reads.
        if (before != pressure.percent) note("context ${pressure.percent}% from page")
    }

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
        applyPageContext(page)
        // One line when the shape of the transcript changes, not one per poll: a
        // turn's reason is what the summary row reads, and "the row says the wrong
        // thing" is unanswerable without knowing which reason arrived.
        val shape = _state.value.turns.joinToString("|") {
            "${it.turn}:${it.endedReason}:${it.prompts.size}:${it.steps.size}"
        }
        if (shape != lastTurnShape) {
            lastTurnShape = shape
            note(
                "turns: " + _state.value.turns.joinToString(" | ") {
                    "t=${it.turn} reason=${it.endedReason} p=${it.prompts.size} s=${it.steps.size} failed=${it.failed} running=${it.isRunning}"
                },
            )
        }
        val hostId = _state.value.host?.hostId
        val sessionId = _state.value.selectedSessionId
        if (hostId != null && sessionId != null) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                conversationCache.record(hostId, sessionId, page)
            }
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
     * and running it through {@link applyPage} would move the paging cursors to
     * that turn's edges and break "load older".
     */
    fun loadTurnDetail(turn: Int) {
        val state = _state.value
        if (!state.advancedFeaturesUnlocked) return
        val sessionId = state.selectedSessionId ?: return
        viewModelScope.launch {
            val socket = connection ?: return@launch
            val page = runCatching {
                socket.call<SessionMessagesResult>(
                    "session.messages",
                    payload(
                        *pageAddress(sessionId),
                        "full" to me.shirazu.mizar.kit.jsonBool(true),
                        "turn" to me.shirazu.mizar.kit.jsonInt(turn),
                    ),
                )
            }.onFailure { note("turn $turn detail failed: ${it.message}") }.getOrNull() ?: return@launch

            val fresh = Conversation.turns(page)
            val items = Conversation.items(page)
            _state.update { current ->
                current.copy(
                    conversation = mergeConversations(current.conversation, items),
                    turns = mergeTurns(current.turns, fresh),
                )
            }
            note("turn $turn detail: ${page.records.size} records")
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
        val epoch = connectionEpoch
        val bundle = try {
            socket.call<RefreshBundle>(
                "session.refresh",
                payload(
                    *buildList {
                        addAll(pageAddress(sessionId))
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
        // The answer belongs to a socket that is no longer the live one: let the
        // caller read again on the new connection instead of merging this one in.
        if (epoch != connectionEpoch) {
            note("discarding a bundle from a replaced socket")
            return false
        }
        applyPage(bundle.messages)
        bundle.stats?.let { stats ->
            _state.update { it.copy(stats = stats.stats, statsError = null) }
            val hostId = _state.value.host?.hostId
            if (hostId != null) {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    conversationCache.recordStats(hostId, sessionId, stats)
                }
            }
        }
        bundle.pending?.let { pending -> _state.update { it.copy(pending = pending) } }
        return true
    }

    private suspend fun loadOnce(tail: Int = FULL_PAGE, afterSeq: Int? = null) {
        val socket = connection ?: return
        val sessionId = _state.value.selectedSessionId ?: return
        val epoch = connectionEpoch
        _state.update { it.copy(loadingConversation = true) }

        try {
            val page = socket.call<SessionMessagesResult>(
                "session.messages",
                payload(
                    *buildList {
                        addAll(pageAddress(sessionId))
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
            if (epoch == connectionEpoch) {
                applyPage(page)
            } else {
                note("discarding a page from a replaced socket")
            }
        } catch (error: Throwable) {
            if (epoch == connectionEpoch) {
                _state.update { it.copy(conversationError = messageOf(error, addressOf())) }
            } else {
                note("ignoring a failure from a replaced socket")
            }
        }

        try {
            val stats = socket.call<SessionStatsResult>("session.stats", payload("sessionId" to jsonString(sessionId)))
            if (epoch != connectionEpoch) {
                _state.update { it.copy(loadingConversation = false) }
                return
            }
            _state.update { it.copy(stats = stats.stats, statsError = null) }
            val hostId = _state.value.host?.hostId
            if (hostId != null) {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    conversationCache.recordStats(hostId, sessionId, stats)
                }
            }
        } catch (error: Throwable) {
            if (epoch == connectionEpoch) {
                _state.update { it.copy(statsError = messageOf(error, addressOf())) }
            }
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
                    *pageAddress(sessionId),
                    "maxMessages" to me.shirazu.mizar.kit.jsonInt(200),
                    "beforeSeq" to me.shirazu.mizar.kit.jsonInt(before),
                ),
            )
            val reached = page.records.firstNotNullOfOrNull { SessionRecord(it).payload["seq"].intValue() }
            if (reached == null || reached == before || page.records.isEmpty()) {
                _state.update { it.copy(exhausted = true, hasMore = false) }
                return 0
            }
            applyPageContext(page)
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
        val current = _state.value
        val ready = current.attachments.filter { it.readyId != null }
        if (text.isBlank() && ready.isEmpty()) return
        if (current.attachments.any { it.readyId == null && it.error == null }) {
            _state.update { it.copy(problem = getApplication<Application>().getString(R.string.attachment_still_uploading)) }
            return
        }
        current.attachments.firstNotNullOfOrNull { it.error }?.let { message ->
            _state.update { it.copy(problem = message) }
            return
        }
        viewModelScope.launch {
            val socket = connection ?: return@launch
            val sessionId = _state.value.selectedSessionId ?: return@launch
            // A fresh key per submission: reusing one would make a second identical
            // message look like a retry of the first and be dropped by the host.
            val requestId = UUID.randomUUID().toString()
            try {
                val result = socket.call<PromptResult>(
                    "session.prompt",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("requestId", requestId)
                        // The attached-prompt path requires a string for text even
                        // when the file is the whole message: an omitted key is a
                        // malformed frame, not "no text".
                        put("text", text)
                        if (ready.isNotEmpty()) {
                            putJsonArray("attachments") {
                                ready.forEach { add(requireNotNull(it.readyId)) }
                            }
                        }
                    },
                )
                if (!result.accepted) {
                    _state.update { it.copy(problem = "the host did not accept the message") }
                    return@launch
                }
                // The host owns the bytes once the prompt is accepted; the staging
                // files have done their job and any retry would be a new prompt.
                clearAttachments()
                if (text.isNotBlank()) awaitPromptRecorded(text)
                reload()
            } catch (error: Throwable) {
                _state.update { it.copy(problem = messageOf(error, addressOf())) }
            }
        }
    }

    /**
     * Stage one attachment and upload it for the next prompt.
     *
     * The picker and image re-encode happen on the UI side; this keeps the state and
     * the transport. The host advertises its own limits, read once and lowered to
     * this app's own caps by the kit.
     */
    fun addAttachment(file: File, name: String, kind: String, mediaType: String?) {
        val host = _state.value.host ?: return
        val sessionId = _state.value.selectedSessionId ?: return
        if (!_state.value.advancedFeaturesUnlocked) {
            file.delete()
            _state.update { it.copy(problem = getApplication<Application>().getString(R.string.attachment_lan_only)) }
            return
        }
        val context = getApplication<Application>()
        val displayName = attachmentDisplayName(name)
        val size = file.length()
        // The host accepts a media type for images only; a document is identified
        // by its name, and sending a type is a malformed frame.
        val effectiveMediaType = mediaType.takeIf { kind == "image" }
        if (size <= 0L) {
            file.delete()
            _state.update { it.copy(problem = context.getString(R.string.attachment_unreadable)) }
            return
        }
        val key = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                attachments = it.attachments + DraftAttachment(
                    key = key,
                    name = displayName,
                    bytes = size,
                    kind = kind,
                    mediaType = effectiveMediaType,
                    file = file,
                ),
                attachmentBusy = true,
            )
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                note("attachment: staging $displayName ($size bytes, $kind)")
                val socket = connection ?: error("not connected")
                val caps = _state.value.attachmentCapabilities
                    ?: AttachmentUploader(socket.attachmentRpc()).capabilities().also { limits ->
                        _state.update { it.copy(attachmentCapabilities = limits) }
                    }
                if (_state.value.attachments.size > caps.maxAttachments) error(context.getString(R.string.attachment_too_many))
                val max = if (kind == "image") caps.maxImageBytes else caps.maxFileBytes
                if (size > max) error(context.getString(R.string.attachment_too_large))
                val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { byte -> "%02x".format(byte) }
                val source = AttachmentSource(
                    file = file,
                    name = displayName,
                    bytes = size,
                    sha256 = sha,
                    kind = kind,
                    mediaType = effectiveMediaType,
                )
                val scope = AttachmentScope(host.hostId, host.deviceId, sessionId)
                val ready = AttachmentUploader(socket.attachmentRpc()).upload(
                    scope = scope,
                    source = source,
                    limits = caps,
                    checkpoint = AttachmentCheckpoint(scope),
                    onProgress = { offset ->
                        _state.update { current ->
                            current.copy(attachments = current.attachments.map {
                                if (it.key == key) it.copy(progress = offset) else it
                            })
                        }
                    },
                )
                note("attachment: ready ${ready.attachmentId} for $displayName")
                _state.update { current ->
                    current.copy(attachments = current.attachments.map {
                        if (it.key == key) it.copy(progress = size, readyId = ready.attachmentId) else it
                    })
                }
            } catch (error: Throwable) {
                note("attachment: failed for $displayName: ${error.message}")
                _state.update { current ->
                    current.copy(attachments = current.attachments.map {
                        if (it.key == key) it.copy(error = error.message ?: context.getString(R.string.attachment_failed)) else it
                    })
                }
            } finally {
                _state.update { current ->
                    current.copy(attachmentBusy = current.attachments.any { it.readyId == null && it.error == null })
                }
            }
        }
    }

    /** Drop one staged attachment, and its local copy. */
    fun removeAttachment(key: String) {
        val draft = _state.value.attachments.firstOrNull { it.key == key } ?: return
        draft.file.delete()
        _state.update { current ->
            current.copy(attachments = current.attachments.filterNot { it.key == key })
        }
    }

    /** Drop every staged attachment after a send, or when the session changes. */
    fun clearAttachments() {
        val files = _state.value.attachments.map { it.file }
        _state.update { it.copy(attachments = emptyList(), attachmentBusy = false) }
        files.forEach { file -> runCatching { file.delete() } }
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
        val sessionId = _state.value.selectedSessionId ?: return
        cancelSession(sessionId)
    }

    /**
     * Cancel one session's turn, from the screen or from the live update's stop button.
     *
     * Split from [cancelTurn] because the notification names its own session: the
     * operator can stop a turn without the conversation being the open one.
     */
    fun cancelSession(sessionId: String) {
        viewModelScope.launch {
            val socket = connection ?: return@launch
            runCatching { socket.call<AcceptanceResult>("session.cancel", payload("sessionId" to jsonString(sessionId))) }
            if (_state.value.selectedSessionId == sessionId) reload()
            // The card is about a turn that was just asked to stop; waiting for the
            // host's `turn.settled` would leave it up for however long the stop takes.
            LiveTurn.hide(getApplication())
            liveSessionId = null
            liveSignature = null
        }
    }

    /**
     * Open or close the running turn's detail.
     *
     * The detail is a paid feature on iOS; this client accepts the same two keys:
     * a paying account, or any account on the LAN. On a relay socket with a free
     * account the control is not drawn and the host is never asked.
     */
    fun toggleActivity() {
        if (_state.value.activityExpanded) {
            activityJob?.cancel()
            activityJob = null
            _state.update { it.copy(activityExpanded = false) }
            return
        }
        if (!_state.value.advancedFeaturesUnlocked) return
        _state.update { it.copy(activityExpanded = true, activityError = null) }
        activityJob?.cancel()
        startActivityPolling()
    }

    private fun startActivityPolling() {
        activityJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            while (isActive && _state.value.activityExpanded) {
                val socket = connection ?: break
                val sessionId = _state.value.selectedSessionId ?: break
                if (_state.value.activity == null) _state.update { it.copy(activityLoading = true) }
                runCatching {
                    socket.call<SessionActivityResult>(
                        "session.activity",
                        payload(
                            "sessionId" to jsonString(sessionId),
                            "maxEntries" to me.shirazu.mizar.kit.jsonInt(60),
                        ),
                    )
                }.onSuccess { page ->
                    _state.update { it.copy(activity = page, activityError = null, activityLoading = false) }
                }.onFailure { error ->
                    _state.update { it.copy(activityError = messageOf(error, addressOf()), activityLoading = false) }
                }
                delay(ACTIVITY_POLL_MILLIS)
            }
        }
    }

    /** Fetch the page before the oldest activity line on screen. */
    fun loadOlderActivity() {
        val current = _state.value.activity ?: return
        val turn = current.turn ?: return
        val oldest = current.oldestSeq ?: return
        if (!current.hasMore || _state.value.activityLoadingOlder) return
        viewModelScope.launch {
            val socket = connection ?: return@launch
            _state.update { it.copy(activityLoadingOlder = true) }
            runCatching {
                socket.call<SessionActivityResult>(
                    "session.activity",
                    payload(
                        "sessionId" to jsonString(current.sessionId),
                        "maxEntries" to me.shirazu.mizar.kit.jsonInt(100),
                        "beforeSeq" to me.shirazu.mizar.kit.jsonInt(oldest.toInt()),
                        "turn" to me.shirazu.mizar.kit.jsonInt(turn),
                    ),
                )
            }.onSuccess { page ->
                _state.update {
                    val merged = (page.entries + (it.activity?.entries.orEmpty()))
                        .distinctBy { entry -> entry.id }
                        .takeLast(MAX_ACTIVITY_ENTRIES)
                    it.copy(activity = page.copy(entries = merged), activityLoadingOlder = false)
                }
            }.onFailure { error ->
                _state.update { it.copy(activityLoadingOlder = false, activityError = messageOf(error, addressOf())) }
            }
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

    /**
     * Bring the open conversation back after the socket was away.
     *
     * A delta refresh is right when the screen already held a transcript: the merge
     * keeps it and only the missing tail arrives. It is the wrong call when the last
     * read failed while the socket was half-alive -- the screen is showing an error,
     * not a transcript, and a delta has nothing to merge into. That case asks for a
     * full page again, which is what clears the error instead of leaving it up until
     * the next manual pull.
     */
    private fun refreshAfterReconnect() {
        val current = _state.value
        if (current.selectedSessionId == null) {
            refreshConversation()
            return
        }
        val needsFullPage = current.conversation.isEmpty() ||
            current.conversationError != null ||
            current.statsError != null
        if (!needsFullPage) {
            refreshConversation()
            return
        }
        // A refresh from before the drop may still be holding the latch; it can never
        // finish on a socket that is gone, so clear it for this recovery read.
        if (current.refreshing) _state.update { it.copy(refreshing = false) }
        viewModelScope.launch {
            note("reconnected: full page for ${current.selectedSessionId}")
            reload(FULL_PAGE, afterSeq = null)
            if (_state.value.conversationError != null && connection != null) {
                // A socket that just came back can lose the first race with the old
                // one's teardown; one quiet retry is enough to make recovery invisible.
                delay(900)
                note("reconnected: retrying the recovery read")
                reload(FULL_PAGE, afterSeq = null)
            }
            refreshInteractionsNow()
            lastRefreshAt = System.currentTimeMillis()
        }
    }
    fun refreshInteractions() {
        viewModelScope.launch { refreshInteractionsNow() }
    }

    /**
     * Re-read the open conversation after something made it stale, without the pull
     * spinner.
     *
     * A turn that has ended leaves the last row saying it is still running, and the
     * status line under the title derives "running" from that row -- so without this
     * the screen sat on "深度求索中~" until the operator sent something else, which is
     * exactly what made the completion look like it never happened. The read is a
     * tail, not a page: the merge keeps the history and only what arrived is fetched.
     */
    private fun refreshConversationQuietly() {
        if (quietRefreshJob?.isActive == true) return
        quietRefreshJob = viewModelScope.launch {
            val sessionId = _state.value.selectedSessionId ?: return@launch
            runCatching {
                if (!loadBundle(REFRESH_PAGE, _state.value.newestSeq)) {
                    reload(tail = REFRESH_PAGE, afterSeq = _state.value.newestSeq)
                }
                refreshSessionsNow()
            }.onFailure { note("quiet refresh failed: ${it.message}") }
            if (_state.value.selectedSessionId == sessionId) lastRefreshAt = System.currentTimeMillis()
        }
    }

    /**
     * A settled turn makes the transcript stale even when the operator is looking at
     * it: there is nothing to announce, but the last row still reads "running".
     */
    private fun afterTurnSettled(sessionId: String) {
        val current = _state.value
        if (current.selectedSessionId == sessionId) {
            refreshConversationQuietly()
        } else {
            // Not on screen: the list is what shows it, and the badge is about to stop.
            viewModelScope.launch { runCatching { refreshSessionsNow() } }
        }
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
            // Only the categories the operator left on. An agent blocked on a
            // question the phone has been told not to surface is not a notice the
            // app is entitled to ignore, so the count is filtered rather than the
            // whole batch.
            val shownApprovals = if (_state.value.notifyApprovals) pending.approvals.size else 0
            val shownQuestions = if (_state.value.notifyQuestions) pending.questions.size else 0
            if (shownApprovals == 0 && shownQuestions == 0) {
                note("pending: notice categories off")
                return true
            }
            // Counted from the catalogue, not spelled here: the hardcoded English was
            // the one notification that stayed English on a Chinese phone. Parity over
            // grammar, like the rest of the catalogue, so a count of one reads
            // "1 approvals" rather than inventing plural rules the iOS client lacks.
            val counts = listOfNotNull(
                pending.approvals.size.takeIf { shownApprovals > 0 }
                    ?.let { app.getString(R.string.pending_approvals, it) },
                pending.questions.size.takeIf { shownQuestions > 0 }
                    ?.let { app.getString(R.string.pending_questions, it) },
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
            }.onSuccess {
                note("model set: ${selection.provider}/${selection.model} effort=${selection.reasoningEffort}")
            }.onFailure { error ->
                note("model set failed: ${error.message}")
                _state.update { it.copy(problem = messageOf(error, addressOf())) }
            }
        }
    }

    fun setHomeModel(selection: ModelSelection) {
        _state.update { it.copy(newSessionModel = selection) }
    }

    /**
     * Apply the model chosen on the home screen to the session that just started.
     *
     * The home screen has no session to point `session.model.set` at, so the choice is
     * held in [UiState.newSessionModel] until `session.create` returns an id. Applied
     * before the first prompt: the point of choosing on the home screen is that the
     * session's first turn already runs on it, not the second.
     */
    private suspend fun applyNewSessionModel(socket: MizarConnection, sessionId: String) {
        val selection = _state.value.newSessionModel ?: return
        _state.update { it.copy(modelChoice = it.modelChoice + (sessionId to selection)) }
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
        }.onFailure { error ->
            _state.update { it.copy(problem = messageOf(error, addressOf())) }
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

    fun setNotifyCompleted(on: Boolean) {
        activity.setNotifyCompleted(on)
        _state.update { it.copy(notifyCompleted = on) }
    }

    fun setNotifyInterrupted(on: Boolean) {
        activity.setNotifyInterrupted(on)
        _state.update { it.copy(notifyInterrupted = on) }
    }

    fun setNotifyQuestions(on: Boolean) {
        activity.setNotifyQuestions(on)
        _state.update { it.copy(notifyQuestions = on) }
    }

    fun setNotifyApprovals(on: Boolean) {
        activity.setNotifyApprovals(on)
        _state.update { it.copy(notifyApprovals = on) }
    }

    fun setHapticsEnabled(on: Boolean) {
        activity.setHapticsEnabled(on)
        _state.update { it.copy(hapticsEnabled = on) }
    }

    fun setSlowTypewriter(on: Boolean) {
        activity.setSlowTypewriter(on)
        _state.update { it.copy(slowTypewriter = on) }
    }

    /**
     * Prefer the local network over the relay, like iOS 0.9.4's transfer setting.
     *
     * Off by default. Turning it on while on a relay socket starts a scan at once,
     * because the operator's intent is the current session, not the next one.
     */
    fun setLocalNetworkFirst(on: Boolean) {
        activity.setLocalNetworkFirst(on)
        _state.update { it.copy(localNetworkFirst = on) }
        if (!on) return
        val host = _state.value.host ?: return
        val socket = connection ?: return
        val welcome = _state.value.welcome ?: return
        maybeSwitchToLan(host, socket, welcome)
    }

    /**
     * Ask the host where it is reachable, and move to a direct socket if one answers.
     *
     * The relay cannot tell a phone its LAN address, so `host.info` is asked over the
     * relay socket that is already up. Each candidate is confirmed with the same
     * unauthenticated `hello` the pairing screen uses: the id must equal the host the
     * phone is already talking to, or a stranger on the network would be adopted.
     */
    // ── relay tier ───────────────────────────────────────────────────────────

    /**
     * Ask the host to put its tunnel on the relay this account's tier pays for.
     *
     * The host has no account identity, so it cannot know whether the phone's plan
     * is paid; the phone asks (`relay.set`) and the host decides — it owns the
     * two-relay allowlist, remembers the choice, and redials its tunnel. The token
     * does not change, so the room does not change: moving relays is a redial, not
     * a re-pairing.
     *
     * Everything here is best effort. A host from before relay tiers answers
     * `NOT_IMPLEMENTED` and is left alone; anything else is a connection failure
     * the next request will surface, and must not take the screen down.
     */
    private suspend fun syncRelayTier(socket: MizarConnection) {
        val info = runCatching { socket.call<HostInfo>("host.info") }.getOrNull() ?: return
        val relay = info.relay ?: return
        val desired = RelayTier.baseUrl(_state.value.account.isPaid)
        if (RelayTier.canonicalOrigin(relay.url) == desired) {
            // The host is already where it belongs. The phone's stored base URL can
            // still be stale — a fallback may have landed on the alternate relay —
            // so repair that from the host's own answer.
            note("relay: host already on $desired")
            relay.baseUrl?.let { rememberRelayBaseUrl(it) }
            return
        }
        val result = try {
            socket.call<RelaySetResult>("relay.set", payload("url" to jsonString(desired)))
        } catch (error: Throwable) {
            val known = (error as? me.shirazu.mizar.kit.MizarError)?.known
            if (known == me.shirazu.mizar.kit.MizarErrorCode.NOT_IMPLEMENTED) {
                note("relay: host does not implement relay.set")
            } else {
                note("relay: could not move the host to $desired — ${error.message}")
            }
            return
        }
        val baseUrl = result.baseUrl
            ?: _state.value.host?.baseUrl?.let { RelayTier.rebased(it, result.url) }
        note("relay: host moved to ${result.url}")
        baseUrl?.let { rememberRelayBaseUrl(it) }
        scheduleRelayReconnect(baseUrl)
    }

    /** Persist the relay base URL the host reported or promised. */
    private suspend fun rememberRelayBaseUrl(baseUrl: String) {
        val current = _state.value.host ?: return
        if (current.baseUrl == baseUrl) return
        val moved = current.copy(baseUrl = baseUrl)
        runCatching { credentials.save(moved) }
        _state.update { state ->
            state.copy(
                host = moved,
                hosts = state.hosts.map { if (it.hostId == moved.hostId) moved else it },
            )
        }
    }

    /**
     * Redial once the host has had time to move its tunnel.
     *
     * The answer travelled over the tunnel the host is about to replace, and the
     * host waits a beat before moving — dialling too early would reconnect to the
     * relay it is leaving. Only a relayed socket redials: on the LAN the tunnel's
     * relay is irrelevant to this phone.
     */
    private fun scheduleRelayReconnect(baseUrl: String?) {
        if (baseUrl.isNullOrBlank() || _state.value.isLocalNetwork) return
        relaySwitchJob?.cancel()
        relaySwitchJob = viewModelScope.launch {
            delay(RELAY_SWITCH_DELAY_MILLIS)
            // One dial at a time. The host cutting its tunnel usually starts the
            // patient reconnect loop, and two loops racing for the socket would
            // leave whichever finished last as the live one.
            cancelReconnect()
            val host = _state.value.host ?: return@launch
            note("relay: redialling $baseUrl")
            connect(host, preferred = PairingRoute.Relay(baseUrl))
        }
    }

    /** A `relay.changed` event: another device moved the tunnel this phone stores. */
    private fun applyRelayChanged(data: kotlinx.serialization.json.JsonElement) {
        val changed = runCatching {
            me.shirazu.mizar.kit.WIRE_JSON.decodeFromJsonElement(HostRelay.serializer(), data)
        }.getOrNull() ?: return
        note("relay: another device moved the host to ${changed.url}")
        viewModelScope.launch {
            val baseUrl = changed.baseUrl
                ?: _state.value.host?.baseUrl?.let { RelayTier.rebased(it, changed.url) }
            baseUrl?.let { rememberRelayBaseUrl(it) }
            scheduleRelayReconnect(baseUrl)
        }
    }

    private fun maybeSwitchToLan(
        host: PairedHost,
        socket: MizarConnection,
        welcome: me.shirazu.mizar.kit.ServerFrame.Welcome,
    ) {
        if (!_state.value.localNetworkFirst || _state.value.isLocalNetwork) return
        if (lanSwitchJob?.isActive == true) return
        lanSwitchJob = viewModelScope.launch {
            _state.update { it.copy(lanScanning = true) }
            try {
                val info = runCatching {
                    socket.call<HostInfo>("host.info", timeoutMillis = LAN_PROBE_TIMEOUT_MILLIS)
                }.getOrNull() ?: return@launch
                val addresses = info.addresses ?: return@launch
                for (address in addresses.distinct()) {
                    if (address.isBlank()) continue
                    if (address.startsWith("127.") || address.startsWith("localhost") || address.startsWith("[::1]")) continue
                    if (address == host.authority) continue
                    val probed = probeHostId(address, LAN_PROBE_TIMEOUT_MILLIS)
                    if (probed != welcome.hostId) continue
                    val updated = host.copy(authority = address)
                    val candidateWelcome = runCatching {
                        val candidate = MizarConnection(
                            updated.configurationFor(MizarConnection.Endpoint.Direct(address), appVersion, deviceModel),
                        )
                        val hello = candidate.connect()
                        if (hello.hostId != welcome.hostId) {
                            runCatching { candidate.disconnect() }
                            null
                        } else {
                            candidate
                        }
                    }.getOrNull() ?: continue
                    // Replace the connection before closing the old socket: a failed
                    // state on a socket that is no longer `connection` must not start a
                    // reconnect loop for the relay we just left.
                    stopWatchers()
                    connectionObserver?.cancel()
                    connectionObserver = null
                    connection = candidateWelcome
                    connectionEpoch++
                    runCatching { socket.disconnect() }
                    credentials.save(updated)
                    _state.update { current ->
                        current.copy(
                            host = updated,
                            hosts = current.hosts.map { if (it.hostId == updated.hostId) updated else it },
                            welcome = candidateWelcome.welcome,
                            phase = Phase.READY,
                            isLocalNetwork = true,
                            problem = null,
                            connectedAtMillis = System.currentTimeMillis(),
                        )
                    }
                    publishLinkNotice(linkConnectedText(updated, _state.value.latencyMillis))
                    startWatchers(candidateWelcome)
                    watchConnection(candidateWelcome, updated)
                    refreshSessions()
                    registerPush(candidateWelcome)
                    // The host forgets which client is on screen when a socket goes
                    // away; the new socket has to say it again.
                    setPresence(foreground)
                    note("switched to LAN at $address")
                    return@launch
                }
                note("LAN scan found no address for ${welcome.hostId}")
            } finally {
                _state.update { it.copy(lanScanning = false) }
            }
        }
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
        if (foreground) {
            val host = _state.value.host
            val socket = connection
            val welcome = _state.value.welcome
            if (host != null && socket != null && welcome != null) maybeSwitchToLan(host, socket, welcome)
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
                        // Another device asked the host to move its tunnel; the base
                        // URL this phone stores has to follow, or the next reconnect
                        // dials a relay the host has left.
                        if (frame.name == "relay.changed") applyRelayChanged(frame.data)
                    }
                }
            }
            var silentPolls = 0
            var ticks = 0
            try {
                while (true) {
                    // The relay has to survive the internet; a LAN socket is a round trip
                    // on the same wire, so the interaction poll can be three seconds
                    // instead of ten without making the radio work harder than the UI.
                    delay(if (_state.value.isLocalNetwork) LAN_INTERACTION_POLL_MILLIS else INTERACTION_POLL_MILLIS)
                    // The poll is a round trip, so it is also the clock: timing the one
                    // request the app already makes is how the notice learns the distance
                    // without a second protocol message invented for it.
                    val askedAt = System.nanoTime()
                    val answered = refreshInteractionsNow()
                    if (answered) recordLatency((System.nanoTime() - askedAt) / 1_000_000)
                    if (!answered && isActive) {
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
        val line = "${SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}  $message"
        recentNotes.addLast(line)
        while (recentNotes.size > 800) recentNotes.removeFirst()
    }

    /**
     * Write the diagnostics zip and hand it to the caller on the main thread.
     *
     * Built off the UI thread: it reads the whole session list and writes a zip, and
     * a settings button that freezes the screen for that looks broken.
     */
    fun exportLogs(onReady: (File?) -> Unit) {
        val snapshot = _state.value
        val notes = recentNotes.toList()
        val requests = traffic.jsonLines()
        viewModelScope.launch(Dispatchers.IO) {
            val file = runCatching { LogArchive.build(getApplication(), snapshot, notes, requests) }.getOrNull()
            withContext(Dispatchers.Main) { onReady(file) }
        }
    }

    private fun regroup() {
        _state.update { current ->
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

    /**
     * Archive a session on the computer, and take it off this phone's list.
     *
     * The host archives without stopping work, so a running session is refused; the
     * refusal is shown in the problem line rather than silently removed. On success
     * the row leaves immediately and the list is re-read, so the workspace counts,
     * the pending set and the stored index agree with the host.
     */
    fun archiveSession(sessionId: String) {
        viewModelScope.launch {
            val socket = connection ?: return@launch
            runCatching {
                socket.call<ArchiveSessionResult>(
                    "session.archive",
                    payload("sessionId" to jsonString(sessionId)),
                )
            }.onSuccess {
                if (_state.value.selectedSessionId == sessionId) selectSession(null)
                _state.update { current ->
                    current.copy(
                        sessions = current.sessions.filterNot { it.sessionId == sessionId },
                        titles = current.titles - sessionId,
                        modelChoice = current.modelChoice - sessionId,
                    )
                }
                regroup()
                persistIndex()
                refreshSessionsNow()
            }.onFailure { error ->
                _state.update { it.copy(problem = messageOf(error, addressOf())) }
            }
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
        // The turn this card reports on is over: take it down before anything else,
        // so a slow refresh below cannot leave it up on the lock screen.
        if (liveSessionId == sessionId) {
            LiveTurn.hide(getApplication())
            liveSessionId = null
            liveSignature = null
            runningSince.remove(sessionId)
        }
        // Before any decision about the notice: the transcript is stale the moment the
        // turn ends, in the foreground as much as in the background. A refresh that
        // only happened off screen is what left the row saying "running" while the
        // operator watched it finish.
        afterTurnSettled(sessionId)
        if (foreground) { note("settled $sessionId: on screen, nothing to announce"); return }
        if (announcer.announcedRecently(sessionId, System.currentTimeMillis())) { note("settled $sessionId: already announced"); return }
        if (activity.quietHours() && isQuietNow()) { note("settled $sessionId: quiet hours"); return }

        val context = getApplication<Application>()
        val detail = describeFinished(sessionId)
        if ((detail.failed && !_state.value.notifyInterrupted) || (!detail.failed && !_state.value.notifyCompleted)) {
            note("settled $sessionId: notice category off")
            return
        }
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
        }.getOrDefault(false)
        if (posted) announcer.record(sessionId, System.currentTimeMillis())
        note("settled $sessionId: posted=$posted failed=${detail.failed} body=$body")
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

    private fun messageOf(error: Throwable, address: String?, throughRelay: Boolean = false): String {
        // A refused credential gets the phrasing that names the action (pair again),
        // not the host's terse "unknown or revoked token", which reads like a bug.
        if (error.isRepairNeeded()) {
            return diagnosisText(Diagnoses.of(error, address, throughRelay = throughRelay))
        }
        val mizar = error as? me.shirazu.mizar.kit.MizarError
        if (mizar != null) return mizar.message
        return diagnosisText(Diagnoses.of(error, address, throughRelay = throughRelay))
    }

    /** The address an endpoint dials, for a diagnosis that names it. */
    private fun addressOf(endpoint: MizarConnection.Endpoint): String = when (endpoint) {
        is MizarConnection.Endpoint.Direct -> endpoint.authority
        is MizarConnection.Endpoint.Relay -> endpoint.baseUrl
    }

    /**
     * The diagnosis in the operator's language.
     *
     * The kit phrases an English one for callers that have no resources. This app does,
     * and the single message shown when nothing works is the worst place in the app for
     * a second language -- it was the one screen still reading English on a Chinese
     * phone. Kept here rather than in `mizarkit` because the kit has no resources and
     * is shared with a platform that phrases its own.
     */
    private fun diagnosisText(diagnosis: Diagnosis): String {
        val context = getApplication<Application>()
        return when (diagnosis) {
            is Diagnosis.OutsideTailnet ->
                context.getString(R.string.diagnosis_outside_tailnet, diagnosis.authority)

            is Diagnosis.TailnetPeerSilent ->
                context.getString(R.string.diagnosis_tailnet_silent, diagnosis.authority)

            is Diagnosis.RelayRoomEmpty ->
                context.getString(R.string.diagnosis_relay_room_empty)

            is Diagnosis.Silent ->
                context.getString(R.string.diagnosis_silent, diagnosis.authority)

            Diagnosis.Offline -> context.getString(R.string.diagnosis_offline)

            is Diagnosis.RequiresRepairing ->
                if (diagnosis.authority.isEmpty()) {
                    context.getString(R.string.diagnosis_repair_no_address)
                } else {
                    context.getString(R.string.diagnosis_repair, diagnosis.authority)
                }

            is Diagnosis.UnknownHost ->
                context.getString(R.string.diagnosis_unknown_host, diagnosis.authority)

            is Diagnosis.Other -> diagnosis.detail
        }
    }

    /** The host answered and refused this phone: retrying cannot fix that. */
    private fun Throwable.isRepairNeeded(): Boolean =
        this is me.shirazu.mizar.kit.MizarError && requiresRepairing

    private fun payload(vararg members: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        buildJsonObject { for ((key, value) in members) put(key, value) }

    /**
     * The address every page read starts with.
     *
     * A delegated child is not addressable as a session: the host answers
     * `session/agent-busy` before it touches the log, so the parent has to travel
     * with the request (plugin 0.8.9 rebuilds the subagent address from it). A fork
     * carries a parent and is not a child -- see [me.shirazu.mizar.kit.SessionLineage].
     */
    private fun pageAddress(sessionId: String): Array<Pair<String, kotlinx.serialization.json.JsonElement>> =
        buildList {
            add("sessionId" to jsonString(sessionId))
            me.shirazu.mizar.kit.SessionLineage.parentOf(_state.value.sessions, sessionId)
                ?.let { add("parentSessionId" to jsonString(it)) }
        }.toTypedArray()

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
        /** Same-wire polling: three seconds instead of ten, like iOS on a LAN link. */
        const val LAN_INTERACTION_POLL_MILLIS = 3_000L
        /** How long one LAN probe gets before the next address is tried. */
        const val LAN_PROBE_TIMEOUT_MILLIS = 4_000L
        /** The open activity detail polls once a second, like iOS. */
        const val ACTIVITY_POLL_MILLIS = 1_000L
        /** Older pages accumulate up to this many lines before the oldest are dropped. */
        const val MAX_ACTIVITY_ENTRIES = 1500

        /**
         * How many session titles a first pairing reads before letting the list lazy-load.
         *
         * One `session.stats` per row: a hundred rows is a hundred round trips, and a
         * host on a slow tunnel would turn "pairing" into a minute of waiting for
         * names nobody is looking at yet.
         */
        const val WARM_TITLE_LIMIT = 80

        /**
         * How long the host gets to move its tunnel before this phone redials.
         *
         * The `relay.set` answer travels over the tunnel being replaced, and the
         * host waits 250 ms before cutting it; a little more than that, and the new
         * relay is already listening for this room.
         */
        const val RELAY_SWITCH_DELAY_MILLIS = 700L

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

        /** How much the round trip has to move before the notice is worth rewriting. */
    private const val LATENCY_NOTICE_STEP_MILLIS = 20L

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

