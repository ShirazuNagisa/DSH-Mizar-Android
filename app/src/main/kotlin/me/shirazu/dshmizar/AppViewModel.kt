package me.shirazu.dshmizar

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.putJsonObject
import me.shirazu.dshmizar.domain.ActivityCoordinator
import me.shirazu.dshmizar.domain.AttachmentCoordinator
import me.shirazu.dshmizar.domain.AccountCoordinator
import me.shirazu.dshmizar.domain.AdoptionPlan
import me.shirazu.dshmizar.domain.CONVERSATION_FULL_PAGE
import me.shirazu.dshmizar.domain.CONVERSATION_REFRESH_PAGE
import me.shirazu.dshmizar.domain.ConnectionMonitor
import me.shirazu.dshmizar.domain.ConversationCoordinator
import me.shirazu.dshmizar.domain.SendCoordinator
import me.shirazu.dshmizar.domain.ReconnectCoordinator
import me.shirazu.dshmizar.domain.RouteOutcome
import me.shirazu.dshmizar.domain.RouteRace
import me.shirazu.dshmizar.domain.SessionIndexCoordinator
import me.shirazu.dshmizar.domain.SocketTitlesLink
import me.shirazu.dshmizar.domain.SocketConversationLink
import me.shirazu.dshmizar.domain.SocketMonitor
import me.shirazu.dshmizar.domain.SocketSendLink
import me.shirazu.dshmizar.domain.Text
import me.shirazu.dshmizar.domain.NotificationCoordinator
import me.shirazu.dshmizar.domain.InteractionsCoordinator
import me.shirazu.dshmizar.domain.SocketInteractionsLink
import me.shirazu.dshmizar.domain.LinkOwnership
import me.shirazu.dshmizar.domain.LiveTurnCoordinator
import me.shirazu.dshmizar.domain.FlowStateStore
import me.shirazu.dshmizar.domain.SettingsCoordinator
import me.shirazu.dshmizar.domain.SocketActivityLink
import me.shirazu.dshmizar.domain.SocketSettingsLink
import me.shirazu.dshmizar.domain.PreferencesCoordinator
import me.shirazu.dshmizar.domain.storedPreferences
import me.shirazu.dshmizar.push.PushTokens
import me.shirazu.dshmizar.push.ConnectionService
import me.shirazu.dshmizar.push.LiveTurn
import me.shirazu.dshmizar.push.LiveTurnCommands
import me.shirazu.dshmizar.push.Notifications
import me.shirazu.dshmizar.store.ActivityStore
import me.shirazu.dshmizar.store.ConversationCache
import me.shirazu.dshmizar.store.EncryptedAccountStore
import me.shirazu.mizar.kit.account.AccountClient
import me.shirazu.mizar.kit.account.AccountIdentity
import me.shirazu.mizar.kit.account.AccountNotice
import me.shirazu.mizar.kit.account.AccountSession
import me.shirazu.mizar.kit.account.AccountStoring
import me.shirazu.dshmizar.ui.ThemeMode
import me.shirazu.dshmizar.store.EncryptedCredentialStore
import me.shirazu.dshmizar.store.FileSessionIndexStore
import me.shirazu.mizar.kit.AcceptanceResult
import me.shirazu.mizar.kit.ApprovalOutcome
import me.shirazu.mizar.kit.ArchiveSessionResult
import me.shirazu.mizar.kit.AttachmentCapabilities
import me.shirazu.mizar.kit.AttachmentUploader
import me.shirazu.mizar.kit.attachmentRpc
import me.shirazu.mizar.kit.Conversation
import me.shirazu.mizar.kit.ConversationItem
import me.shirazu.mizar.kit.ConversationTurn
import me.shirazu.mizar.kit.CreateSessionResult
import me.shirazu.mizar.kit.CredentialStore
import me.shirazu.mizar.kit.Diagnosis
import me.shirazu.mizar.kit.Diagnoses
import me.shirazu.mizar.kit.HostInfo
import me.shirazu.mizar.kit.DeliveredStateFrame
import me.shirazu.mizar.kit.FileSlice
import me.shirazu.mizar.kit.TurnStream
import me.shirazu.mizar.kit.UsageOverview as HostUsageOverview
import me.shirazu.dshmizar.domain.AgentCatalog
import me.shirazu.dshmizar.domain.AgentEngine
import me.shirazu.dshmizar.domain.AgentScoping
import me.shirazu.dshmizar.domain.AgentSurface
import me.shirazu.dshmizar.domain.AgentTarget
import me.shirazu.dshmizar.domain.HostAgent
import me.shirazu.dshmizar.domain.LiveTurnView
import me.shirazu.dshmizar.domain.OnboardingStorageKeys
import me.shirazu.dshmizar.domain.OnboardingVersion
import me.shirazu.dshmizar.domain.UsageOverview as UsageHistory
import me.shirazu.dshmizar.domain.toAppHistory
import me.shirazu.dshmizar.domain.toUi
import me.shirazu.dshmizar.ui.conversation.ConversationImageBridge
import me.shirazu.dshmizar.ui.conversation.ConversationImagePresenter
import me.shirazu.dshmizar.ui.conversation.PictureFacts
import me.shirazu.dshmizar.ui.settings.SettingsFileCache
import me.shirazu.dshmizar.ui.settings.SettingsTopic
import me.shirazu.dshmizar.domain.AnnouncementAction
import me.shirazu.dshmizar.domain.AnnouncementBannerState
import me.shirazu.dshmizar.domain.AnnouncementCoordinator
import me.shirazu.dshmizar.domain.AnnouncementFeed
import me.shirazu.dshmizar.domain.AnnouncementPreferenceStore
import me.shirazu.dshmizar.domain.SurveyCoordinator
import me.shirazu.dshmizar.domain.SurveysUiState
import me.shirazu.mizar.kit.account.AnnouncementClient
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
import me.shirazu.mizar.kit.SyncProgress
import me.shirazu.mizar.kit.PendingInteractions
import me.shirazu.mizar.kit.PermissionCatalog
import me.shirazu.mizar.kit.QuestionAnswer
import me.shirazu.mizar.kit.SessionActivityResult
import me.shirazu.mizar.kit.SessionIndexStore
import me.shirazu.mizar.kit.SessionListResult
import kotlinx.serialization.json.contentOrNull
import me.shirazu.mizar.kit.SessionMessagesResult
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
import java.io.IOException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    /**
     * Send a message typed while a turn is running straight to the host as a steer,
     * instead of holding it above the composer until the turn settles. Off by
     * default, like iOS 0.9.9's 「默认引导发送」.
     */
    val steerByDefault: Boolean = false,
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
    /**
     * The open session's running turn, reduced from the host's state channel.
     *
     * Null when no turn is running, when nothing is selected, or when the host is
     * too old to push state frames — the screen then falls back to what the
     * transcript says, which is what it drew before the channel existed.
     */
    val liveTurn: LiveTurnView? = null,
    /** The host's usage history for the home page, or null when it cannot be read. */
    val usageOverview: UsageHistory? = null,
    val usageLoading: Boolean = false,
    val usageUpdatedAtMillis: Long? = null,
    /** The agents this computer serves, and which one this phone is scoped to. */
    val agentCatalog: AgentCatalog = AgentCatalog(),
    /** The first-run guide is on screen. */
    val onboardingOpen: Boolean = false,
    /** The guide is replaying because this build is newer than the stored version. */
    val onboardingUpdate: Boolean = false,
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

    private val _state = MutableStateFlow(storedPreferences(activity))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** What the domain coordinators read and write app state through. */
    private val store = FlowStateStore(_state)

    /** The settings switches; see [PreferencesCoordinator]. */
    private val preferences = PreferencesCoordinator(store, activity)

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

    /**
     * The live socket, the epoch that identifies it, and why the last dial failed.
     *
     * The rules that used to be comments beside three separate fields -- a replaced
     * socket cannot act, and replacing comes before closing -- live in the object.
     */
    private val ownership = LinkOwnership()
    /** Bumped per settings request, so a late answer cannot overwrite a newer one. */
    private var settingsRevision = 0L
    /** Last presence we reported, re-asserted after a reconnect. */
    // False until an activity says otherwise: a process restarted in the background by
    // the foreground service is *not* being watched, and defaulting to true made the app
    // suppress exactly the notifications that restart exists to deliver.
    private var foreground = false

    /** When the screen last read the host, so returning to it can decide whether to refresh. */
    private var lastRefreshAt = 0L



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

    /** The sentence the permanent notice currently shows, so it is only rewritten on change. */
    private var lastLinkNotice: String? = null

    private val appVersion: String get() = BuildConfig.VERSION_NAME
    private val deviceModel: String
        get() = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")

    // ── launch, pairing, connection ──────────────────────────────────────────

    /** Load what is stored and connect to the most recent host. */
    fun start() {
        viewModelScope.launch {
            loadAccount()
            // The first-run guide comes before anything else: it is what explains the
            // computer to a person holding a phone with no pairing code yet. An
            // upgrade replays it once when the build is newer than the stored one.
            val (guide, update) = onboardingShouldOpen()
            if (guide) _state.update { it.copy(onboardingOpen = true, onboardingUpdate = update) }
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
    //
    // The account domain lives in AccountCoordinator; these keep the names the
    // screens already call. What stays here is the wiring: where a string comes
    // from, how a browser is opened, and what a changed identity means for the
    // socket. The explanations sit with the code that does the work.

    private val strings = object : Text {
        override fun get(id: Int): String = this@AppViewModel.getApplication<Application>().getString(id)

        override fun get(id: Int, vararg args: Any): String =
            this@AppViewModel.getApplication<Application>().getString(id, *args)
    }

    private val account = AccountCoordinator(
        store = store,
        accounts = accounts,
        api = accountClient,
        scope = viewModelScope,
        text = strings,
        deviceName = deviceModel,
        deviceModel = Build.MODEL,
        appVersion = appVersion,
        openBrowser = ::openBrowser,
        onIdentityChanged = {
            reconnectForIdentity()
            // The announcement socket is per account session: a sign-in or sign-out
            // has to move it to the token that is current now.
            announcements.update(_state.value.account.session?.token, foregroundForAnnouncements)
        },
        // The plan decides which relay the host's tunnel uses; a change asks the
        // host to move. Nothing else in the account domain touches the socket.
        onPlanChanged = { ownership.current?.let { socket -> viewModelScope.launch { syncRelayTier(socket) } } },
    )

    /** Read the stored session and quietly refresh it. Never blocks a launch. */
    private suspend fun loadAccount() {
        account.load()
        // The stored session is what a cold start finds already signed in, and it
        // arrives after the activity's first onStart. The identity callback covers a
        // sign-in but not this, so the announcement socket would stay shut for a
        // signed-in phone until the next foreground cycle without this line.
        announcements.update(_state.value.account.session?.token, foregroundForAnnouncements)
    }


    /** Pull the profile again; the account screen's retry and the foreground hook. */
    fun refreshAccount() = account.refresh()

    /** A phone that comes back to the foreground asks once, quietly. */
    fun refreshAccountOnForeground() = account.refreshOnForeground()

    fun openAccountScreen() = account.openScreen()

    // ── balance ─────────────────────────────────────────────────────────────

    fun refreshBalance() {
        _state.update { it.copy(balance = it.balance.copy(loading = true, failed = false)) }
        viewModelScope.launch {
            val socket = ownership.current
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

    fun closeAccountScreen() = account.closeScreen()

    fun setAccountMode(mode: AccountMode) = account.setMode(mode)

    fun showHandoffEntry(show: Boolean) = account.showHandoffEntry(show)

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
            val socket = ownership.current
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

    // ── password reset, sign-in, invite ──────────────────────────────────────

    fun openPasswordReset() = account.openReset()

    fun closePasswordReset() = account.closeReset()

    fun startPasswordReset(email: String) = account.startReset(email)

    fun completePasswordReset(code: String, password: String) = account.completeReset(code, password)

    fun clearAccountError() = account.clearError()

    fun loginAccount(identifier: String, password: String) = account.login(identifier, password)

    fun registerAccount(username: String, email: String, password: String) =
        account.register(username, email, password)

    fun completeRegistration(code: String) = account.completeRegistration(code)

    fun cancelRegistration() = account.cancelRegistration()

    fun beginGitHubSignIn(link: Boolean = false) = account.beginGitHubSignIn(link)

    fun handleAuthUri(uri: String) = account.handleAuthUri(uri)

    fun redeemGitHubHandoff(handoff: String) = account.redeemHandoff(handoff)

    fun openRedeem() = account.openRedeem()

    fun closeRedeem() = account.closeRedeem()

    fun redeemInvite(code: String) = account.redeemInvite(code)

    fun logoutAccount() = account.logout()

    fun deleteAccount() = account.delete()

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

    /** Open a URL, and say so when there is nothing to open it with. */
    private fun openBrowser(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { getApplication<Application>().startActivity(intent) }.isSuccess
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

    private val routes = RouteRace(
        scope = viewModelScope,
        note = ::note,
        weight = { error, endpoint ->
            me.shirazu.mizar.kit.Diagnoses.weight(
                me.shirazu.mizar.kit.Diagnoses.of(
                    error,
                    addressOf(endpoint),
                    throughRelay = endpoint is MizarConnection.Endpoint.Relay,
                ),
            )
        },
    )

    private suspend fun connect(host: PairedHost, preferred: PairingRoute? = null, warm: Boolean = false) {
        stopWatchers()
        ownership.current?.disconnect()
        ownership.release()

        // The stored list first: a phone with no network still shows what it knows.
        index.restore(host.hostId)

        // The route that just answered goes first; the other stays behind it.
        val endpoints = when (preferred) {
            is PairingRoute.Relay -> host.endpoints.sortedBy { it !is MizarConnection.Endpoint.Relay }
            is PairingRoute.Direct -> host.endpoints.sortedBy { it !is MizarConnection.Endpoint.Direct }
            null -> host.endpoints
        }
        // Every route is dialled at once, the first to answer wins and the rest are
        // cancelled; which failure to report when none answers is decided by weight,
        // not by who failed last. All of that lives in RouteRace.
        when (val outcome = routes.race(endpoints) { endpoint -> dial(host, endpoint) }) {
            is RouteOutcome.Won ->
                adoptConnection(host, outcome.endpoint, outcome.value.first, outcome.value.second, warm)

            is RouteOutcome.Lost -> {
                val address = host.authority ?: host.baseUrl
                ownership.recordFailure(
                    outcome.failure,
                    outcome.endpoint ?: host.endpoints.lastOrNull(),
                )
                _state.update {
                    it.copy(
                        phase = Phase.FAILED,
                        problem = messageOf(
                            outcome.failure ?: IOException("no address answered"),
                            address,
                            throughRelay = ownership.lastEndpoint is MizarConnection.Endpoint.Relay,
                        ),
                        needsPairing = outcome.failure?.isRepairNeeded() == true,
                        pairingStage = null,
                    )
                }
                publishLinkFailure()
            }
        }
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
            host.configurationFor(
                endpoint,
                appVersion,
                deviceModel,
                identityHeaders,
                // The agent scope travels in the hello frame: a socket cannot change
                // which agent it is bound to after the handshake.
                engine = activity.agentEngine(),
            ),
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
        val plan = AdoptionPlan.of(
            warm = warm,
            relayed = endpoint !is MizarConnection.Endpoint.Direct,
            localNetworkFirst = _state.value.localNetworkFirst,
        )
        // The order is the point: the socket is live before anything reads it, the screen
        // is told before the index is read, and push is last because it is the only step
        // that waits on the account.
        becomeLive(host, endpoint, candidate, welcome, plan)
        // The notice says who we are talking to and how far away it is; the distance
        // arrives with the first poll, a second from now.
        publishLinkNotice(linkConnectedText(host, _state.value.latencyMillis))
        startWatchers(candidate)
        startStateChannel(candidate)
        watchConnection(candidate, host)
        note("connected to $endpoint")
        // What this computer serves, and the usage history the home page charts. Both
        // are best effort: an older host answers NOT_IMPLEMENTED and the screens hide
        // the sections rather than drawing zeroes.
        viewModelScope.launch { loadAgentCatalog(candidate) }
        viewModelScope.launch { loadUsageOverview(force = true) }
        // The host can be reconfigured while the phone is away, and a fresh socket is the
        // one moment it is worth asking again: a cached catalog would offer a model the
        // computer no longer has.
        loadCatalogs(force = true)
        if (plan.warmsTheIndex) warmTheIndex() else refreshSessions()
        registerPush(candidate)
        // A relay socket is the fallback, not the goal: when the operator asked for
        // LAN-first, look for the computer's own addresses now that a socket exists to
        // ask `host.info` over.
        if (plan.looksForLan) maybeSwitchToLan(host, candidate, welcome)
        // A paid account's tunnel belongs on the account relay, and the host has no
        // account identity: the phone is the only side that knows the plan. Skipped by
        // hosts that predate relay tiers.
        if (plan.syncsRelayTier) viewModelScope.launch { syncRelayTier(candidate) }
    }

    /** Make the new socket the live one, and put the screen where the plan says. */
    private fun becomeLive(
        host: PairedHost,
        endpoint: MizarConnection.Endpoint,
        candidate: MizarConnection,
        welcome: ServerFrame.Welcome,
        plan: AdoptionPlan,
    ) {
        ownership.adopt(candidate)
        _state.update {
            it.copy(
                phase = plan.phase,
                welcome = welcome,
                problem = null,
                needsPairing = false,
                pairingStage = plan.pairingStage,
                offlineBrowser = false,
                seenAt = activity.seenAt(host.hostId),
                isLocalNetwork = endpoint is MizarConnection.Endpoint.Direct,
                connectedAtMillis = System.currentTimeMillis(),
                latencyHistory = emptyList(),
            )
        }
    }

    /**
     * The first page of a first pairing: the index, with progress on screen.
     *
     * `finally`: a host that goes away mid-warm must still land on the shell. Staying on
     * "reading sessions" for ever is the one failure mode of a progress screen.
     */
    private suspend fun warmTheIndex() {
        try {
            refreshSessionsNow()
            index.warmTitles()
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
        val endpoint = ownership.lastEndpoint
        val status = (ownership.lastFailure as? me.shirazu.mizar.kit.UpgradeRefused)?.status
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
    /** Notice a close the connection layer already reports, and act on the live one only. */
    private fun watchConnection(socket: MizarConnection, host: PairedHost) {
        monitor.watchConnection(
            socket = SocketMonitor(socket),
            // A replaced socket failing later is old news, and two loops would fight.
            isLive = { ownership.owns(socket) },
            onFailed = { message -> beginReconnect(host, message) },
        )
    }

    // ── reconnecting ────────────────────────────────────────────────────────
    //
    // The loop lives in ReconnectCoordinator: it never gives up, it waits before
    // showing the banner, and it stops when the credential is refused. What stays here
    // is the dialling it asks for and the two things that follow a success.

    private val reconnect = ReconnectCoordinator(
        store = store,
        scope = viewModelScope,
        strings = strings,
        note = ::note,
        stopWatchers = ::stopWatchers,
        redial = { host -> connect(host) },
        afterReconnected = ::refreshAfterReconnect,
        announcePresence = { setPresence(foreground) },
        publishNotice = ::publishLinkNotice,
        connected = { ownership.current != null },
    )

    /** Retry a dropped connection; the loop and its rules live in the domain. */
    private fun beginReconnect(host: PairedHost, reason: String) = reconnect.begin(host, reason)

    // ── notifications ───────────────────────────────────────────────────────
    //
    // The rules live in NotificationCoordinator: a transition is what counts, only in
    // the background, and quiet hours never silence a blocked agent. What stays here
    // is the wiring: the transcript tail a notice describes itself from, and the one
    // call that actually posts.

    private val notifier = NotificationCoordinator(
        store = store,
        baseline = activity,
        text = strings,
        note = ::note,
        quietHours = { activity.quietHours() },
        hourOfDay = { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) },
        isForeground = { foreground },
        tail = { sessionId ->
            ownership.current?.let { socket ->
                runCatching {
                    socket.call<SessionMessagesResult>(
                        "session.messages",
                        payload(*pageAddress(sessionId), "maxMessages" to me.shirazu.mizar.kit.jsonInt(12)),
                    )
                }.getOrNull()
            }
        },
        postNotice = { title, body, sessionId, finished ->
            val app = getApplication<Application>()
            runCatching {
                // Its own range: a completion must not replace a waiting card.
                if (finished) {
                    Notifications.show(
                        app,
                        title,
                        body,
                        sessionId,
                        baseId = me.shirazu.dshmizar.push.Notifications.FINISHED_NOTIFICATION_BASE,
                    )
                } else {
                    Notifications.show(app, title, body, sessionId)
                }
            }.getOrDefault(false)
        },
    )

    /** Notify the sessions whose turn just ended, while the operator is elsewhere. */
    private suspend fun announceFinishedTurns(rows: List<SessionRow>) = notifier.announceFinished(rows)

    /** Try the stored host again, patiently. */
    fun retry() {
        reconnect.cancel()
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
        val socket = ownership.current ?: return
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
        reconnect.cancel()
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
        reconnect.cancel()
        viewModelScope.launch {
            stopWatchers()
            ownership.current?.disconnect()
            ownership.release()
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
                reconnect.cancel()
                runCatching { ownership.current?.call<AcceptanceResult>("push.unregister") }
                stopWatchers()
                ownership.current?.disconnect()
                ownership.release()
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
        val socket = ownership.current
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
        val socket = ownership.current ?: return
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

        // A connection is scoped to one agent on a computer that fronts several:
        // the host's list may describe all of them, but this screen may only draw
        // the ones the open scope owns. A row naming no engine is kept -- an older
        // host, or a single-agent one, has no markers to filter on.
        val engine = _state.value.agentCatalog.current?.engine
        val scoped = AgentScoping.sessions(list.items, engine)
        val scopedWorkspaces = workspaces?.let { rows -> AgentScoping.workspaces(rows, engine) }

        _state.update { current ->
            current.copy(
                sessions = scoped,
                workspaces = if (scopedWorkspaces == null) current.workspaces else scopedWorkspaces,
                workspacePaths = if (scopedWorkspaces.isNullOrEmpty()) current.workspacePaths else scopedWorkspaces.map { it.path }.toSet(),
                problem = null,
            )
        }
        index.regroup()
        index.persist()
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
        liveTurn.sync(list.items)
        // The screen opens on what was last read, once: after that the operator is in
        // control of where they are.
        index.restoreLastSession()
    }

    // ── catalog change bursts ───────────────────────────────────────────────
    //
    // A computer that is working creates, renames and settles sessions in bursts.
    // One `session.list` per event would be one round trip per event over a metered
    // relay, so many events become one read: 250 ms of quiet, and never more often
    // than once every two seconds.

    private var catalogRefreshJob: Job? = null
    private var catalogRefreshedAt = 0L

    private fun scheduleCatalogRefresh() {
        catalogRefreshJob?.cancel()
        catalogRefreshJob = viewModelScope.launch {
            delay(CATALOG_MERGE_MILLIS)
            val since = System.currentTimeMillis() - catalogRefreshedAt
            if (since in 0 until CATALOG_SILENCE_MILLIS) {
                delay(CATALOG_SILENCE_MILLIS - since)
            }
            runCatching { refreshSessionsNow() }
            // If the open conversation belongs to a session the host just changed, its
            // last page is stale in the same way a settled turn leaves it stale.
            if (_state.value.selectedSessionId != null) refreshConversationQuietly()
            catalogRefreshedAt = System.currentTimeMillis()
        }
    }

    /**
     * Throw the local copy of the session data away and read it again.
     *
     * The settings screen's 「完全刷新本地会话数据」: it clears the transcript cache
     * (not the index of what exists), drops the in-memory page, then re-reads the
     * list and the open conversation. Useful when a phone has been away long enough
     * that merging a delta would keep a half-page the host no longer agrees with.
     *
     * [onDone] reports how many sessions the host listed and whether it answered at
     * all, so the guide's refresh step can say what happened instead of guessing.
     */
    fun clearSessionDataAndReload(onDone: (sessions: Int, reached: Boolean) -> Unit = { _, _ -> }) {
        if (_state.value.refreshing) {
            onDone(_state.value.sessions.size, false)
            return
        }
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                conversationCache.clearAll()
                _state.update {
                    it.copy(
                        conversation = emptyList(),
                        turns = emptyList(),
                        stats = null,
                        oldestSeq = null,
                        hasMore = false,
                        exhausted = false,
                        newestSeq = null,
                        liveTurn = null,
                    )
                }
                val reached = runCatching { refreshSessionsNow() }.isSuccess
                _state.value.selectedSessionId?.let { sessionId ->
                    reload(tail = FULL_PAGE)
                    openLiveStream(sessionId)
                }
                lastRefreshAt = System.currentTimeMillis()
                onDone(_state.value.sessions.size, reached)
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    /**
     * Re-read the session list, as the guide's refresh step does.
     *
     * Unlike [clearSessionDataAndReload] this keeps the cached transcripts: it is the
     * "refresh what the computer has" step, not "throw my copy away".
     */
    fun refreshSessionMetadata(onDone: (sessions: Int, reached: Boolean) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val reached = runCatching { refreshSessionsNow() }.isSuccess
            onDone(_state.value.sessions.size, reached)
        }
    }

    // ── the state channel: the running turn, live ───────────────────────────
    //
    // The host pushes one small binary frame per state change and one heartbeat
    // every five seconds; `protocol/state-channel-v1.md` is the contract. The
    // reducer lives in mizarkit (`TurnStream`, ported from iOS); the app maps its
    // snapshot onto the screen and, when a turn ends, refreshes the transcript.

    private val stream = TurnStream()
    private var streamSession: String? = null
    private var streamJob: Job? = null
    private var lastStreamFrameAtMillis: Long? = null

    private fun startStateChannel(socket: MizarConnection) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            socket.stateFrames.collect { delivered ->
                if (!ownership.owns(socket)) return@collect
                onStateFrame(delivered)
            }
        }
    }

    private fun onStateFrame(delivered: DeliveredStateFrame) {
        val selected = _state.value.selectedSessionId ?: return
        if (delivered.sessionId != selected) return
        if (streamSession != selected) {
            streamSession = selected
            stream.beginTurn(null)
        }
        lastStreamFrameAtMillis = System.currentTimeMillis()
        val changed = stream.apply(delivered.frame)
        // A heartbeat renews an interval without changing anything the screen draws;
        // republishing it would rebuild the conversation once every five seconds.
        if (changed) publishLiveTurn()
    }

    /**
     * Start the live turn for [sessionId], replaying what the host kept for it.
     *
     * The journal replay is the only way to see a turn that started while the phone
     * was away: state frames are forward-only and heartbeats carry no text. A host
     * that answers `NOT_IMPLEMENTED` simply has no replay — the live frames still
     * work, and an empty snapshot draws nothing until they arrive.
     */
    private fun openLiveStream(sessionId: String?) {
        if (sessionId == null) {
            streamSession = null
            lastStreamFrameAtMillis = null
            _state.update { it.copy(liveTurn = null) }
            return
        }
        streamSession = sessionId
        stream.beginTurn(null)
        lastStreamFrameAtMillis = null
        publishLiveTurn()
        viewModelScope.launch {
            val replay = runCatching { ownership.current?.streamSession(sessionId) }.getOrNull()
                ?: return@launch
            if (_state.value.selectedSessionId != sessionId) return@launch
            stream.replace(replay)
            publishLiveTurn()
        }
    }

    private fun publishLiveTurn() {
        val selected = _state.value.selectedSessionId
        if (selected == null || streamSession != selected) {
            _state.update { it.copy(liveTurn = null) }
            return
        }
        val last = _state.value.turns.lastOrNull()
        val view = stream.snapshot().toUi(
            sessionId = selected,
            running = last?.isRunning == true,
            startedAtMillis = last?.startedAtMillis,
            endedAtMillis = last?.endedAtMillis,
            lastFrameAtMillis = lastStreamFrameAtMillis,
        )
        _state.update { it.copy(liveTurn = view) }
    }

    // ── usage, agents, the guide ────────────────────────────────────────────

    /**
     * Read the home page's usage history.
     *
     * A host without the method (an older AGI Mizar) answers `NOT_IMPLEMENTED`; the
     * null result keeps the section hidden rather than drawing zeroes.
     */
    fun loadUsageOverview(force: Boolean = false) {
        if (_state.value.usageLoading) return
        if (!force && _state.value.usageOverview != null) return
        val socket = ownership.current ?: return
        _state.update { it.copy(usageLoading = true) }
        viewModelScope.launch {
            val answered = runCatching { socket.usageOverview() }.getOrNull()
            _state.update {
                it.copy(
                    usageLoading = false,
                    usageOverview = answered?.toAppHistory() ?: it.usageOverview,
                    usageUpdatedAtMillis = if (answered != null) System.currentTimeMillis() else it.usageUpdatedAtMillis,
                )
            }
        }
    }

    // ── inline pictures ─────────────────────────────────────────────────────
    //
    // The store lives once per process so scrolling back does not re-read the
    // computer; the bridge is the only place that knows the socket, and the gate is
    // read from the same `advancedFeaturesUnlocked` the file preview uses. A locked
    // link never asks: the presenter returns `Locked` before any call is made.

    private val images = ConversationImagePresenter(
        object : ConversationImageBridge {
            override val isUnlocked: Boolean
                get() = _state.value.advancedFeaturesUnlocked
            override val isLocalAgent: Boolean
                get() = false

            override suspend fun stat(sessionId: String, path: String): PictureFacts? {
                val socket = ownership.current ?: return null
                val stat = socket.fileStat(sessionId, path) ?: return null
                return PictureFacts(
                    path = stat.path.ifEmpty { path },
                    name = stat.name,
                    byteSize = stat.byteSize,
                    mediaType = stat.mediaType,
                    modifiedAt = stat.modifiedAt,
                )
            }

            override suspend fun read(
                sessionId: String,
                path: String,
                expectedBytes: Long,
            ): ByteArray? {
                val socket = ownership.current ?: return null
                val out = java.io.ByteArrayOutputStream()
                var offset = 0L
                // A file that claims more than the picture budget is refused by the
                // policy before this runs; the bound here is a loop guard, not a rule.
                while (offset < expectedBytes) {
                    val slice = socket.readFileSlice(
                        sessionId,
                        path,
                        offset = offset,
                        length = minOf(expectedBytes - offset, FileSlice.MAX_SLICE_BYTES.toLong()).toInt(),
                    )
                    val bytes = slice.bytes()
                    out.write(bytes)
                    offset += bytes.size
                    if (slice.eof || bytes.isEmpty()) break
                }
                return out.toByteArray().takeIf { it.isNotEmpty() }
            }
        },
    )

    /** The one inline-picture store the conversation and its history share. */
    val conversationImages: ConversationImagePresenter get() = images

    /**
     * What the app's local caches hold, for the settings file page.
     *
     * Counted on the caller's thread because the page opens rarely and the trees are
     * small; walking them on every recomposition would not be. The counts are facts
     * about this phone, never about the computer.
     */
    fun fileCacheFacts(): SettingsFileCache {
        val app = getApplication<Application>()
        val roots = listOf(
            java.io.File(app.filesDir, "conversation-cache"),
            java.io.File(app.cacheDir, "attachments"),
            java.io.File(app.cacheDir, "exports"),
            java.io.File(app.cacheDir, "preview.pdf"),
        )
        var files = 0
        var bytes = 0L
        for (root in roots) {
            if (!root.exists()) continue
            if (root.isFile) {
                files += 1
                bytes += root.length()
                continue
            }
            runCatching {
                root.walkTopDown().forEach { entry ->
                    if (entry.isFile) {
                        files += 1
                        bytes += entry.length()
                    }
                }
            }
        }
        return SettingsFileCache(files = files, bytes = bytes)
    }

    /** Drop the local caches the file page owns; conversations re-read from the host. */
    fun clearFileCache() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            conversationCache.clearAll()
            withContext(Dispatchers.IO) {
                runCatching { java.io.File(app.cacheDir, "attachments").deleteRecursively() }
                runCatching { java.io.File(app.cacheDir, "exports").deleteRecursively() }
                runCatching { java.io.File(app.cacheDir, "preview.pdf").delete() }
            }
            _state.update { it.copy(filePreview = FilePreviewUiState()) }
        }
    }

    /**
     * Read what this computer serves and which agent this phone is scoped to.
     *
     * `host.info` is the authority: it names every engine the computer hosts, which
     * are connected, and which one this connection belongs to. A host that predates
     * the field simply has no picker.
     */
    private suspend fun loadAgentCatalog(socket: MizarConnection) {
        val info = runCatching { socket.call<HostInfo>("host.info") }.getOrNull() ?: return
        val served = info.engines.orEmpty().mapNotNull { engine ->
            val parsed = AgentEngine.fromWire(engine.id) ?: return@mapNotNull null
            HostAgent(
                engine = parsed,
                label = engine.label.ifEmpty { null },
                connected = engine.connected,
                detail = engine.detail,
            )
        }
        val catalog = AgentCatalog.from(served, info.engine, activity.agentSurface())
        _state.update { it.copy(agentCatalog = catalog) }
    }

    /** Switch the conversation scope to another agent on the same computer. */
    fun chooseAgent(target: AgentTarget) {
        activity.setAgentSurface(target.surface)
        activity.setAgentEngine(target.engine)
        _state.update { it.copy(agentCatalog = it.agentCatalog.copy(current = target)) }
        // The scope travels in the hello frame, so a live socket cannot adopt it:
        // the same rule as signing in or out.
        reconnectForIdentity()
    }

    // ── the first-run guide ─────────────────────────────────────────────────

    /** Open the guide; [replay] starts it from the top even when it was finished. */
    fun startOnboarding(replay: Boolean = false) {
        if (replay) {
            activity.setOnboardingStarted(true)
            activity.setOnboardingDone(false)
        }
        _state.update { it.copy(onboardingOpen = true, onboardingUpdate = false) }
    }

    /** The guide finished: remember it for this build and close it. */
    fun finishOnboarding() {
        activity.setOnboardingDone(true)
        activity.setOnboardingVersion(OnboardingVersion.REQUIRED)
        _state.update { it.copy(onboardingOpen = false, onboardingUpdate = false) }
    }

    private fun onboardingShouldOpen(): Pair<Boolean, Boolean> {
        val done = activity.onboardingDone()
        val stored = activity.onboardingVersion()
        return when {
            !done -> true to false
            OnboardingVersion.isUpdate(done, stored) -> true to true
            else -> false to false
        }
    }

    // ── the lock screen's card ──────────────────────────────────────────────
    //
    // The rules live in LiveTurnCoordinator: one card, rewritten only when it would
    // read differently, with a clock that starts at the first moment this phone could
    // see the turn running. What stays here is the one call that touches the system.

    private val liveTurn = LiveTurnCoordinator(
        store = store,
        now = { System.currentTimeMillis() },
        showCard = { sessionId, title, since ->
            LiveTurn.show(getApplication(), sessionId, title, since)
        },
        hideCard = { LiveTurn.hide(getApplication()) },
    )

    // ── the session list's index ────────────────────────────────────────────
    //
    // Grouping, what is remembered across launches, and the names behind the rows all
    // live in SessionIndexCoordinator. What stays here is the one navigation call it
    // cannot make itself: opening a session.

    private val index = SessionIndexCoordinator(
        store = store,
        scope = viewModelScope,
        note = ::note,
        indexStore = indexStore,
        link = { ownership.current?.let { socket -> SocketTitlesLink(socket) } },
        lastSession = { hostId -> activity.lastSession(hostId) },
        openSession = { sessionId -> selectSession(sessionId) },
    )

    /** Load titles for rows about to be drawn, one request per session, once each. */
    fun loadTitles(sessionIds: List<String>) = index.loadTitles(sessionIds)
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
        activityPanel.cancel()
        clearAttachments()
        // The state channel belongs to the open session: switching away drops the
        // live turn, switching to one replays whatever the host kept for it.
        openLiveStream(sessionId)
        if (sessionId == null) return
        viewModelScope.launch {
            // What was on screen last time, before the first round trip: opening a
            // session offline shows its conversation rather than an empty card.
            val activeHost = _state.value.host?.hostId ?: hostId
            if (activeHost != null) {
                conversationCache.load(activeHost, sessionId)?.let { cached ->
                    if (_state.value.selectedSessionId == sessionId) {
                        conversation.show(cached.messages, forSession = sessionId)
                        cached.stats?.let { stats -> applyStats(stats.stats) }
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
            val socket = ownership.current ?: return@launch
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
            val socket = ownership.current ?: return@launch
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
    //
    // The latch that keeps two reads from overlapping, the epoch check that drops a
    // page from a replaced socket, the paging cursors and the cache write all live in
    // ConversationCoordinator. These keep the names the screens already call, and hand
    // the socket over as a link so the domain never sees one.

    private val conversation = ConversationCoordinator(
        store = store,
        scope = viewModelScope,
        note = ::note,
        describe = { error -> messageOf(error, addressOf()) },
        epoch = { ownership.currentEpoch },
        link = { ownership.current?.let { socket -> SocketConversationLink(socket) } },
        // A lambda, not a method reference: the settings coordinator is built further
        // down this class, and a bound reference would capture it before it exists.
        applyStats = { frame -> settings.applyStats(frame) },
        recordPage = { hostId, sessionId, page -> conversationCache.record(hostId, sessionId, page) },
        recordStats = { hostId, sessionId, stats -> conversationCache.recordStats(hostId, sessionId, stats) },
    )

    /** Load the open session: latch, epoch guard and the one retry live in the domain. */
    suspend fun reload(tail: Int = FULL_PAGE, afterSeq: Int? = null) = conversation.reload(tail, afterSeq)

    /** One page of records, then the figures. */
    private suspend fun loadOnce(tail: Int = FULL_PAGE, afterSeq: Int? = null) =
        conversation.readOnce(tail, afterSeq)

    /** One round trip for the whole screen, when the host implements it. */
    private suspend fun loadBundle(tail: Int, afterSeq: Int?): Boolean =
        conversation.refreshBundle(tail, afterSeq)

    suspend fun loadOlder(): Int? = conversation.loadOlder()

    fun loadTurnDetail(turn: Int) = conversation.loadTurnDetail(turn)


    fun sendPrompt(text: String) = prompts.send(text, steer = shouldSteer())

    /** Whether this submission should guide the running turn instead of queueing. */
    private fun shouldSteer(): Boolean =
        _state.value.steerByDefault && _state.value.turns.lastOrNull()?.isRunning == true

    /**
     * Run the computer's own context compaction on the open session.
     *
     * The answer is only an acknowledgement: the effect on screen arrives through
     * the state channel (`compacting`, then a terminal state), not from this result.
     */
    fun compactSession() {
        val sessionId = _state.value.selectedSessionId ?: return
        val socket = ownership.current ?: return
        viewModelScope.launch {
            runCatching { socket.compactSession(sessionId) }
                .onSuccess { result ->
                    if (result == null) {
                        _state.update { it.copy(problem = getApplication<Application>().getString(R.string.host_too_old)) }
                    }
                }
                .onFailure { error -> _state.update { it.copy(problem = messageOf(error, addressOf())) } }
        }
    }

    /**
     * Branch the open conversation at one finished turn's boundary.
     *
     * @param atSeq the turn's true inclusive `turn/end.seq`, never a turn number:
     *   the host validates that the boundary exists and is complete.
     */
    fun forkSession(atSeq: Int) {
        val sessionId = _state.value.selectedSessionId ?: return
        val socket = ownership.current ?: return
        viewModelScope.launch {
            runCatching { socket.forkSession(sessionId, atSeq) }
                .onSuccess { fork ->
                    if (fork == null) {
                        _state.update { it.copy(problem = getApplication<Application>().getString(R.string.host_too_old)) }
                        return@onSuccess
                    }
                    refreshSessionsNow()
                    selectSession(fork.sessionId)
                }
                .onFailure { error -> _state.update { it.copy(problem = messageOf(error, addressOf())) } }
        }
    }

    // ── attachments ─────────────────────────────────────────────────────────
    //
    // The domain lives in AttachmentCoordinator; these keep the names the composer
    // already calls. The upload is handed over as a provider, so the coordinator
    // never sees a socket and its own tests need no server.

    private val attachments = AttachmentCoordinator(
        store = store,
        scope = viewModelScope,
        io = Dispatchers.IO,
        text = strings,
        note = ::note,
        transport = { ownership.current?.let { socket -> AttachmentUploader(socket.attachmentRpc()) } },
    )

    fun addAttachment(file: File, name: String, kind: String, mediaType: String?) =
        attachments.add(file, name, kind, mediaType)

    /** Drop one staged attachment, and its local copy. */
    fun removeAttachment(key: String) = attachments.remove(key)

    /** Drop every staged attachment after a send, or when the session changes. */
    fun clearAttachments() = attachments.clear()

    // ── sending and stopping ────────────────────────────────────────────────
    //
    // The domain lives in SendCoordinator: what may be sent, the fresh request id per
    // submission, the bounded re-read that waits for the log, and the stop that takes
    // the card down without waiting for the host.

    private val prompts = SendCoordinator(
        store = store,
        scope = viewModelScope,
        strings = strings,
        describe = { error -> messageOf(error, addressOf()) },
        link = { ownership.current?.let { socket -> SocketSendLink(socket) } },
        reload = { conversation.reload() },
        readOnce = { conversation.readOnce() },
        clearAttachments = { attachments.clear() },
        hideCard = { liveTurn.hide() },
    )

    fun cancelTurn() = prompts.cancelTurn()

    /** Cancel one session's turn, from the screen or from the live update's stop button. */
    fun cancelSession(sessionId: String) = prompts.cancel(sessionId)

    // ── the running turn's activity ─────────────────────────────────────────
    //
    // The panel lives in ActivityCoordinator: it owns the poll and its lifetime, so
    // switching sessions stops it from one place instead of from three hundred
    // lines away.

    private val activityPanel = ActivityCoordinator(
        store = store,
        scope = viewModelScope,
        pollDispatcher = Dispatchers.Default,
        describe = { error -> messageOf(error, addressOf()) },
        link = { ownership.current?.let { socket -> SocketActivityLink(socket) } },
    )

    /**
     * Open or close the running turn's detail.
     *
     * The detail is a paid feature on iOS; this client accepts the same two keys:
     * a paying account, or any account on the LAN. On a relay socket with a free
     * account the control is not drawn and the host is never asked.
     */
    fun toggleActivity() = activityPanel.toggle()

    /** Fetch the page before the oldest activity line on screen. */
    fun loadOlderActivity() = activityPanel.loadOlder()

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
        // The state channel restarts from an empty interval after a reconnect; the
        // journal replay is what puts the running turn back on screen.
        openLiveStream(current.selectedSessionId)
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
            if (_state.value.conversationError != null && ownership.current != null) {
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
    fun refreshInteractions() = interactions.refresh()

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

    // ── interactions ────────────────────────────────────────────────────────
    //
    // The cards and their poll live in InteractionsCoordinator. What stays here is
    // the wiring: the live socket, and the notifier that decides whether a batch
    // nobody is looking at is worth a buzz.

    private val interactions = InteractionsCoordinator(
        store = store,
        scope = viewModelScope,
        describe = { error -> messageOf(error, addressOf()) },
        announcePending = notifier::announcePending,
        link = { ownership.current?.let { socket -> SocketInteractionsLink(socket) } },
    )

    /**
     * Read the pending interactions, and say whether the host answered at all.
     *
     * @returns whether the host answered, or `true` when there is no connection to
     *   judge (a reconnect already owns that case).
     */
    private suspend fun refreshInteractionsNow(): Boolean = interactions.refreshNow()

    fun answerApproval(requestId: String, outcome: ApprovalOutcome) =
        interactions.answerApproval(requestId, outcome)

    fun answerQuestions(callId: String, answers: List<QuestionAnswer>) =
        interactions.answerQuestions(callId, answers)

    // ── catalogs, model, permissions ────────────────────────────────────────
    //
    // The domain lives in SettingsCoordinator. What stays here is the wiring: the
    // live socket as a link, the connection epoch, and the diagnostics sentence a
    // failure shows.

    private val settings = SettingsCoordinator(
        store = store,
        scope = viewModelScope,
        note = ::note,
        describe = { error -> messageOf(error, addressOf()) },
        epoch = { ownership.currentEpoch },
        link = { ownership.current?.let { socket -> SocketSettingsLink(socket) } },
    )

    /**
     * Read the catalogs, once unless asked again.
     *
     * The host's list of models changes when it is reconfigured, and a phone that
     * cached the old one for the life of the process would offer a model that no
     * longer exists. `force` is what the reconnect path passes.
     */
    fun loadCatalogs(force: Boolean = false) = settings.loadCatalogs(force)

    /**
     * Fold a stats frame into what is on screen.
     *
     * The conversation's own loads carry a frame too; what a frame *means* belongs
     * to the settings the screen shows, so the merge lives with them.
     */
    private fun applyStats(fresh: SessionStatsFrame?) = settings.applyStats(fresh)

    /** Choose the model for the session on screen. */
    fun setModel(selection: ModelSelection) = settings.setModel(selection)

    fun setHomeModel(selection: ModelSelection) = settings.setHomeModel(selection)

    /** Apply the model chosen on the home screen to the session that just started. */
    private suspend fun applyNewSessionModel(socket: MizarConnection, sessionId: String) =
        settings.applyToNewSession(SocketSettingsLink(socket), sessionId)

    /** Change the session's permission preset. */
    fun setPermission(preset: String) = settings.setPermission(preset)

    // ── push ────────────────────────────────────────────────────────────────

    /** Tell the host this device's push token, and that it is on screen. */
    fun registerPush(candidate: MizarConnection? = ownership.current) {
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

    // ── preferences ─────────────────────────────────────────────────────────
    //
    // One line each: the switch is stored and shown together, and both halves live
    // in PreferencesCoordinator. The view model stays the name the screens call.

    /** Remember the theme across launches. */
    fun setTheme(mode: ThemeMode) = preferences.setTheme(mode)

    /** Remember the quiet-hours switch; the announcer reads the store directly. */
    fun setQuietHours(on: Boolean) = preferences.setQuietHours(on)

    fun setNotifyCompleted(on: Boolean) = preferences.setNotifyCompleted(on)

    fun setNotifyInterrupted(on: Boolean) = preferences.setNotifyInterrupted(on)

    fun setNotifyQuestions(on: Boolean) = preferences.setNotifyQuestions(on)

    fun setNotifyApprovals(on: Boolean) = preferences.setNotifyApprovals(on)

    fun setHapticsEnabled(on: Boolean) = preferences.setHapticsEnabled(on)

    fun setSlowTypewriter(on: Boolean) = preferences.setSlowTypewriter(on)

    /** Whether a message typed mid-turn should steer instead of waiting for the end. */
    fun setSteerByDefault(on: Boolean) = preferences.setSteerByDefault(on)

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
        val socket = ownership.current ?: return
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
            reconnect.cancel()
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
                    monitor.stopConnectionWatch()
                    ownership.adopt(candidateWelcome)
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
            val socket = ownership.current
            val welcome = _state.value.welcome
            if (host != null && socket != null && welcome != null) maybeSwitchToLan(host, socket, welcome)
        }
        viewModelScope.launch {
            val socket = ownership.current ?: return@launch
            runCatching {
                socket.call<AcceptanceResult>("push.presence", buildJsonObject { put("foreground", foreground) })
            }
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    // ── the monitor ─────────────────────────────────────────────────────────
    //
    // The rules live in ConnectionMonitor: the poll is the heartbeat, two silent polls
    // mean the socket is dead, and only the socket on screen may start a reconnect.
    // What stays here is what each of those facts does to the rest of the app.

    private val monitor = ConnectionMonitor(
        store = store,
        scope = viewModelScope,
        note = ::note,
        dispatcher = Dispatchers.Default,
        pollInteractions = ::refreshInteractionsNow,
        recordLatency = ::recordLatency,
        pollSessions = { refreshSessionsNow() },
        onEvent = { frame ->
            if (frame.name in INTERACTION_EVENTS) refreshInteractionsNow()
            // The host knows when a turn ends; a connected client should be told, not
            // left to infer it from a list refresh.
            if (frame.name == "turn.settled") onTurnSettled(frame.data)
            // The catalog changed on the computer (a session was created, renamed,
            // archived or settled elsewhere). iOS merges bursts with a 200 ms debounce
            // and a 2 s silence window; doing the same here keeps a busy computer from
            // turning into one `session.list` per event.
            if (frame.name == "session.changed") scheduleCatalogRefresh()
            // Another device asked the host to move its tunnel; the base URL this phone
            // stores has to follow, or the next reconnect dials a relay the host left.
            if (frame.name == "relay.changed") applyRelayChanged(frame.data)
        },
        onSilent = { reason ->
            val host = _state.value.host
            // The host is no longer renewing the interval: keep the state on screen
            // (a failure already happened) but stop pretending it is current.
            stream.markDisconnected()
            publishLiveTurn()
            if (host != null) beginReconnect(host, reason) else ownership.current?.disconnect()
        },
        anythingRunning = {
            notifier.anyRunning() ||
                _state.value.host?.hostId?.let { activity.hasRunningSessions(it) } == true
        },
        isForeground = { foreground },
    )

    private fun startWatchers(socket: MizarConnection) {
        monitor.start(SocketMonitor(socket), isLive = { ownership.owns(socket) })
    }

    private fun stopWatchers() {
        monitor.stop()
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
            val socket = ownership.current ?: return@launch
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
                index.regroup()
                index.persist()
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
        liveTurn.forget(sessionId)
        // Before any decision about the notice: the transcript is stale the moment the
        // turn ends, in the foreground as much as in the background. A refresh that
        // only happened off screen is what left the row saying "running" while the
        // operator watched it finish.
        afterTurnSettled(sessionId)
        notifier.announceSettled(sessionId)
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

    // ── announcements and surveys ───────────────────────────────────────────
    //
    // Both are account features served by the relay, not by the computer: the
    // announcement socket is its own WebSocket and the survey list is HTTP. They are
    // built last in the class so every field they read already exists.

    private val announcementClient = AnnouncementClient(
        accountBaseUrl = BuildConfig.ACCOUNT_BASE_URL,
        pollFallback = accountClient::announcements,
    )

    private val announcements = AnnouncementCoordinator(
        seen = AnnouncementPreferenceStore(getApplication<Application>()),
        scope = viewModelScope,
        feed = AnnouncementFeed { token -> announcementClient.events(token) },
        poll = { token -> announcementClient.poll(token) },
    )

    /** The banner on screen and what waits behind it. */
    val announcementBanner: StateFlow<AnnouncementBannerState> = announcements.state

    private var foregroundForAnnouncements = false

    /**
     * MainActivity tells the announcements whether a person is looking.
     *
     * A socket held while the app is in the background is a socket the system will
     * kill anyway; stopping it here is what makes the reconnect-on-resume path the
     * one that catches a banner created while the phone was away.
     */
    fun announcementsForeground(active: Boolean) {
        foregroundForAnnouncements = active
        announcements.update(_state.value.account.session?.token, active)
    }

    fun dismissAnnouncement() = announcements.dismiss()

    /** A banner's button, routed to the screen it names. */
    fun handleAnnouncementAction(action: AnnouncementAction) {
        when (action) {
            is AnnouncementAction.Settings ->
                openSettingsTopic(runCatching { SettingsTopic.valueOf(action.shelf.name) }.getOrNull())
            AnnouncementAction.Account -> openAccountScreen()
            is AnnouncementAction.External -> openBrowser(action.url)
        }
    }

    // The settings window lives in the sidebar's own state; a banner needs a way to
    // ask it to open on one page. A one-shot flow keeps the side effect out of the
    // banner and leaves the sidebar the owner of what is on screen.
    private val _settingsTopic = MutableStateFlow<SettingsTopic?>(null)
    val settingsTopic: StateFlow<SettingsTopic?> = _settingsTopic.asStateFlow()

    fun openSettingsTopic(topic: SettingsTopic?) {
        _settingsTopic.value = topic
    }

    fun consumeSettingsTopic() {
        _settingsTopic.value = null
    }

    private val surveys = SurveyCoordinator(
        api = accountClient,
        scope = viewModelScope,
        text = strings,
        token = { _state.value.account.session?.token },
        // Paid days change the plan: the profile on screen is re-read, never patched.
        onPlanChanged = { refreshAccount() },
    )

    /** The surveys sheet's own state. */
    val surveyState: StateFlow<SurveysUiState> = surveys.state

    fun openSurveys() = surveys.open()
    fun closeSurveys() = surveys.close()

    /** The sheet body needs the coordinator itself (list, detail, submit). */
    internal val surveysCoordinator: SurveyCoordinator get() = surveys

    private companion object {
        /** The five names that all mean "the pending set changed": refresh it. */
        val INTERACTION_EVENTS = setOf(
            "interactions.changed",
            "approval.request",
            "approval.resolved",
            "question.request",
            "question.resolved",
        )

        /** How long one LAN probe gets before the next address is tried. */
        const val LAN_PROBE_TIMEOUT_MILLIS = 4_000L

        /** Events in a burst become one catalog read after this much quiet. */
        const val CATALOG_MERGE_MILLIS = 250L

        /** ...and no more than one catalog read per this window, however long the burst. */
        const val CATALOG_SILENCE_MILLIS = 2_000L

        /**
         * How long the host gets to move its tunnel before this phone redials.
         *
         * The `relay.set` answer travels over the tunnel being replaced, and the
         * host waits 250 ms before cutting it; a little more than that, and the new
         * relay is already listening for this room.
         */
        const val RELAY_SWITCH_DELAY_MILLIS = 700L

        /** The transcript page an open session starts with. */
        const val FULL_PAGE = CONVERSATION_FULL_PAGE

        /**
         * What a refresh asks for.
         *
         * A refresh is a delta, not a reload: the merge keeps everything already held, so
         * asking for the last few dozen messages is enough -- and over a tunnel it is the
         * difference between a refresh that feels immediate and one that feels broken.
         */
        const val REFRESH_PAGE = CONVERSATION_REFRESH_PAGE

        /** How stale the screen may get before returning to it triggers a refresh. */
        const val STALE_MILLIS = 15_000L

        /** How much the round trip has to move before the notice is worth rewriting. */
        const val LATENCY_NOTICE_STEP_MILLIS = 20L

    }
}
