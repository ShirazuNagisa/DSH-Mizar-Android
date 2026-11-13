package me.shirazu.dshmizar.snapshot

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import me.shirazu.dshmizar.AccountMode
import me.shirazu.dshmizar.AccountPhase
import me.shirazu.dshmizar.AccountUiState
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.Phase
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.CacheHit
import me.shirazu.mizar.kit.ContextBreakdown
import me.shirazu.mizar.kit.ContextPressure
import me.shirazu.mizar.kit.ConversationItem
import me.shirazu.mizar.kit.ConversationTurn
import me.shirazu.mizar.kit.DeliveredFile
import me.shirazu.mizar.kit.ModelCatalog
import me.shirazu.mizar.kit.ModelOption
import me.shirazu.mizar.kit.ModelSelection
import me.shirazu.mizar.kit.ModelSelectionPair
import me.shirazu.mizar.kit.PairedHost
import me.shirazu.mizar.kit.PendingApproval
import me.shirazu.mizar.kit.PendingInteractions
import me.shirazu.mizar.kit.PendingQuestion
import me.shirazu.mizar.kit.PermissionCatalog
import me.shirazu.mizar.kit.PermissionOption
import me.shirazu.mizar.kit.ProviderGroup
import me.shirazu.mizar.kit.QuestionItem
import me.shirazu.mizar.kit.QuestionOption
import me.shirazu.mizar.kit.ReasoningEffortOption
import me.shirazu.mizar.kit.SessionGrouping
import me.shirazu.mizar.kit.SessionRow
import me.shirazu.mizar.kit.SessionStatsFrame
import me.shirazu.mizar.kit.Throughput
import me.shirazu.mizar.kit.TokenUsage
import me.shirazu.mizar.kit.WorkspaceGroup
import me.shirazu.mizar.kit.WorkspaceRow
import me.shirazu.mizar.kit.account.AccountDesktop
import me.shirazu.mizar.kit.account.AccountDevice
import me.shirazu.mizar.kit.account.AccountSession
import me.shirazu.mizar.kit.account.AccountUser

/**
 * The content the goldens are drawn with.
 *
 * Every value that could vary between runs is either fixed here or derived from a
 * fixed anchor:
 *
 *  - timestamps are **absolute**, not "an hour ago", so a turn's duration and the
 *    relative labels do not move with the calendar. The one exception is the
 *    session list's row time, which `Format.relativeTime` computes against the
 *    device clock; those timestamps are pinned to *now* instead, so every row reads
 *    the "less than a minute" label today and a year from now;
 *  - text is written out in full rather than taken from resources, because a fixture
 *    is the content, and a resource renames should not silently change a golden;
 *  - the model is built once for the whole class: an `AppViewModel` is what the
 *    pages take, it does no I/O until something calls it, and constructing one per
 *    test would spend a keystore read per screenshot.
 *
 * A fixture that changes is a golden that fails once and is re-recorded; that is the
 * workflow, not a reason to make the fixture bland. What it must not be is
 * *nondeterministic*: the same run twice renders the same pixels.
 */
internal object SnapshotFixtures {

    const val HOST_NAME = "Studio Mac"

    /** The host id the shell and the fixtures agree on. */
    const val HOST_ID = "host-1"

    /** The conversation the session page opens. */
    const val OPEN_SESSION_ID = "session-2"

    /**
     * One model for every page that needs one.
     *
     * Constructed lazily on first use. `AppViewModel` does not connect on
     * construction -- `start()` is what dials -- so this is a plain state holder with
     * real preferences stores behind it, which is exactly what the pages take.
     */
    val model: AppViewModel by lazy {
        AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    }

    /** The device this app is paired with. */
    fun host(): PairedHost = PairedHost(
        hostId = HOST_ID,
        hostName = HOST_NAME,
        deviceId = "device-1",
        deviceToken = "mzd_0123456789abcdefghij",
        pairedAtMillis = 1_700_000_000_000L,
    )

    /** The route the composer's model pill names. */
    fun selection(): ModelSelection = ModelSelection(
        provider = "deepseek",
        model = "deepseek-v4.1-pro",
        reasoningEffort = "high",
    )

    /** The catalogue the pill resolves its label from. */
    fun modelCatalog(): ModelCatalog = ModelCatalog(
        defaultSelection = selection(),
        providers = listOf(
            ProviderGroup(
                id = "deepseek",
                name = "DeepSeek",
                models = listOf(
                    ModelOption(
                        id = "deepseek-v4.1-pro",
                        name = "DeepSeek V4.1 Pro",
                        efforts = listOf(
                            ReasoningEffortOption(id = "high", name = "High"),
                            ReasoningEffortOption(id = "max", name = "Max"),
                        ),
                        defaultEffort = "high",
                    ),
                ),
            ),
        ),
    )

    /** The permission presets the composer's lock window offers. */
    fun permissionCatalog(): PermissionCatalog = PermissionCatalog(
        options = listOf(
            PermissionOption("read-only", "Read only"),
            PermissionOption("auto", "Default"),
            PermissionOption("danger-full-access", "Full access"),
        ),
        defaultPreset = "auto",
    )

    /** The figures above the composer: a session mid-work with room in its window. */
    fun stats(): SessionStatsFrame = SessionStatsFrame(
        title = "Fix the pairing crash",
        tokenUsage = TokenUsage(
            uncachedInputTokens = 12_480,
            outputTokens = 3_120,
            cacheReadTokens = 98_000,
            cacheWriteTokens = 0,
            billedInputTokens = 14_080,
        ),
        cacheHit = CacheHit(ratio = 0.88, displayPercent = "88%"),
        context = ContextPressure(
            window = 200_000,
            pressureTokens = 54_300,
            projectedTokens = 61_200,
            percent = 31,
            breakdown = ContextBreakdown(
                systemTokens = 12_000,
                toolsTokens = 3_400,
                messageTokens = 38_900,
            ),
        ),
        stats = Throughput(
            turns = 4,
            steps = 11,
            llmMs = 42_000.0,
            toolMs = 6_500.0,
            ttftMs = 780.0,
            decodeTokensPerSecond = 64.2,
        ),
        model = ModelSelectionPair(lastUsed = selection(), next = selection()),
        permissionPreset = "auto",
    )

    private const val WORKSPACE_API = "/Users/shirazu/work/api"
    private const val WORKSPACE_SITE = "/Users/shirazu/work/site"

    /** What the session list shows: two workspaces, one row per real shape of session. */
    fun sessions(): List<SessionRow> {
        // `Format.relativeTime` is computed against the wall clock, so a fixed
        // timestamp would say "2 hours ago" today and "1 day ago" tomorrow. Pinned to
        // "a few seconds ago" instead, every row reads the sub-minute label no matter
        // when the suite runs.
        val fresh = System.currentTimeMillis() - 5_000.0
        return listOf(
            SessionRow(
                sessionId = OPEN_SESSION_ID,
                updatedAt = fresh,
                running = false,
                blank = false,
                agentAvailable = true,
                cwd = WORKSPACE_API,
            ),
            SessionRow(
                sessionId = "session-1",
                updatedAt = fresh - 1_000,
                running = true,
                blank = false,
                agentAvailable = true,
                cwd = WORKSPACE_API,
            ),
            SessionRow(
                sessionId = "session-3",
                updatedAt = fresh - 2_000,
                running = false,
                blank = false,
                agentAvailable = true,
                cwd = WORKSPACE_API,
            ),
            SessionRow(
                sessionId = "session-4",
                updatedAt = fresh - 3_000,
                running = false,
                blank = false,
                agentAvailable = true,
                cwd = WORKSPACE_SITE,
            ),
            SessionRow(
                sessionId = "session-5",
                updatedAt = fresh - 4_000,
                running = false,
                blank = true,
                agentAvailable = true,
                cwd = WORKSPACE_SITE,
            ),
        )
    }

    /** The host's titles, which is what the rows are called. */
    fun titles(): Map<String, String> = mapOf(
        OPEN_SESSION_ID to "Fix the pairing crash",
        "session-1" to "Write the release notes",
        "session-3" to "Regenerate the golden fixtures",
        "session-4" to "Landing page copy",
        "session-5" to "New session",
    )

    /** The workspaces the host lists now. */
    fun workspaces(): List<WorkspaceRow> = listOf(
        WorkspaceRow(workspaceId = "ws-api", path = WORKSPACE_API, title = "api"),
        WorkspaceRow(workspaceId = "ws-site", path = WORKSPACE_SITE, title = "site"),
    )

    private fun workspacePaths(): Set<String> = setOf(WORKSPACE_API, WORKSPACE_SITE)

    /** The grouped rows the sidebar draws, built by the same rules the app uses. */
    fun groups(): List<WorkspaceGroup> = SessionGrouping.groups(
        sessions = sessions(),
        workspacePaths = workspacePaths(),
        selectedSessionId = OPEN_SESSION_ID,
        workspaceTitles = mapOf(WORKSPACE_API to "api", WORKSPACE_SITE to "site"),
    )

    /** The sidebar's expanded workspaces, so its session rows are on screen. */
    fun expandedWorkspaces(): Set<String> = workspacePaths()

    /**
     * A host at rest with work waiting: the sidebar, and the settings window that
     * grows out of it, are drawn from this.
     */
    fun sidebarState(): UiState = UiState(
        phase = Phase.READY,
        host = host(),
        hosts = listOf(host()),
        sessions = sessions(),
        titles = titles(),
        workspacePaths = workspacePaths(),
        workspaces = workspaces(),
        groups = groups(),
        selectedSessionId = OPEN_SESSION_ID,
        seenAt = sessions().associate { it.sessionId to System.currentTimeMillis() },
        stats = stats(),
        modelCatalog = modelCatalog(),
        // A local link unlocks the paid features, which is what makes the sidebar's
        // footer say "via LAN" and the session screen show the whole turn.
        isLocalNetwork = true,
        connectedAtMillis = System.currentTimeMillis() - 90_000,
        latencyMillis = 42,
        latencyHistory = listOf(38L, 45L, 40L, 42L, 51L, 44L, 39L, 42L),
        pending = PendingInteractions(
            approvals = listOf(approvalFor(OPEN_SESSION_ID)),
        ),
        permissionCatalog = permissionCatalog(),
    )

    /** The home view: a paired host with two workspaces, no conversation open. */
    fun homeState(): UiState = UiState(
        phase = Phase.READY,
        host = host(),
        hosts = listOf(host()),
        workspaces = workspaces(),
        groups = groups(),
        newSessionModel = selection(),
        modelCatalog = modelCatalog(),
        isLocalNetwork = true,
        latencyMillis = 42,
    )

    /** What is waiting for an answer: enough cards to show the deck behind the front. */
    fun pending(): PendingInteractions = PendingInteractions(
        approvals = listOf(
            PendingApproval(
                requestId = "request-1",
                sessionId = OPEN_SESSION_ID,
                toolName = "shell",
                reason = "Run the instrumentation suite to reproduce the pairing crash.",
            ),
            PendingApproval(
                requestId = "request-2",
                sessionId = "session-1",
                toolName = "write_file",
                reason = "Write the regenerated golden fixtures into androidTest/assets.",
            ),
        ),
        questions = listOf(
            PendingQuestion(
                callId = "call-1",
                sessionId = OPEN_SESSION_ID,
                questions = listOf(
                    QuestionItem(
                        id = "question-1",
                        header = "Platform",
                        question = "Which platform should the fix land on first?",
                        options = listOf(
                            QuestionOption("Android", "The phone client in this repository"),
                            QuestionOption("iOS", "The Swift client, which already has the fix"),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** One approval, on its own, for the dock's compact golden. */
    fun approval(): PendingApproval = pending().approvals.first()

    private fun approvalFor(sessionId: String): PendingApproval = PendingApproval(
        requestId = "request-1",
        sessionId = sessionId,
        toolName = "shell",
        reason = "Run the instrumentation suite to reproduce the pairing crash.",
    )

    /**
     * The transcript the session page shows: a prompt, a folded tool step, an answer
     * and a delivered file -- everything a turn can carry without being in flight.
     *
     * The timestamps are absolute so the "Completed in ..." label is arithmetic, not a
     * race against the clock. One turn is enough to fill the viewport from the bottom,
     * which is where the session screen parks a conversation.
     */
    fun turns(): List<ConversationTurn> {
        val started = 1_700_000_000_000L
        val prompt = ConversationItem.Message(
            id = "seq-1",
            role = ConversationItem.Role.USER,
            text = "The pairing screen crashes when the code has a lowercase letter. " +
                "Find it and add a regression test.",
            atMillis = started,
        )
        val answer = ConversationItem.Message(
            id = "seq-2",
            role = ConversationItem.Role.ASSISTANT,
            text = "## What was wrong\n\n`PairingUri.parse` lowercased only the host " +
                "part of the code, so the device token kept the caller's case and the " +
                "comparison against the generated code failed.\n\n" +
                "The fix normalises the whole code before it is compared, and the new " +
                "test replays a recording with an uppercase code.",
            atMillis = started + 62_000,
        )
        val tool = ConversationItem.Tool(
            id = "seq-3",
            callId = "call-7",
            name = "shell",
            arguments = "{\"command\":\"\\\"./gradlew.bat :mizarkit:test --tests '*Pairing*'\\\"\"}",
            result = "PairingUriTest > aLowercaseCodeIsStillOneCode PASSED",
            failed = false,
            atMillis = started + 74_000,
        )
        val closing = ConversationItem.Message(
            id = "seq-4",
            role = ConversationItem.Role.ASSISTANT,
            text = "The suite is green. The recording lives at " +
                "`mizarkit/src/test/resources/replay/pairing-lowercase.json`.",
            atMillis = started + 96_000,
        )
        return listOf(
            ConversationTurn(
                turn = 12,
                prompts = listOf(prompt),
                steps = listOf(answer, tool, closing),
                startedAtMillis = started,
                endedAtMillis = started + 134_000,
                endedReason = "completed",
                startSeq = 1,
                // A finished turn that knows where it ended is the one that offers
                // `session.fork`, and the toolbar's fork action is part of the page
                // baseline: without a position there is nothing to branch at, the app
                // hides the button, and the golden would not cover it.
                endSeq = 4,
                deliverables = listOf(
                    DeliveredFile(
                        path = "$WORKSPACE_API/build/reports/tests/pairing.html",
                        description = "The test report for the regression",
                    ),
                ),
            ),
        )
    }

    /** The conversation items the positioning pass reads (the list itself draws turns). */
    fun conversation(): List<ConversationItem> = turns().flatMap { it.items }

    /** The session page: a finished, fully-loaded turn, and the composer ready. */
    fun sessionState(): UiState = sidebarState().copy(
        selectedSessionId = OPEN_SESSION_ID,
        conversation = conversation(),
        turns = turns(),
        oldestSeq = 1,
        exhausted = true,
        newestSeq = 4,
        pending = null,
        modelChoice = mapOf(OPEN_SESSION_ID to selection()),
    )

    /** The account screen, signed out: the form and the way in. */
    fun accountSignedOut(): AccountUiState = AccountUiState(
        phase = AccountPhase.READY,
        mode = AccountMode.SIGN_IN,
    )

    /** The account screen, signed in: a paying profile with two devices. */
    fun accountSignedIn(): AccountUiState = AccountUiState(
        phase = AccountPhase.READY,
        mode = AccountMode.SIGN_IN,
        session = AccountSession(
            token = "mza_0123456789abcdefghij",
            profile = AccountUser(
                id = "user-1",
                username = "shirazu",
                email = "shirazu@example.com",
                emailVerified = true,
                plan = "paid",
                github = true,
                githubLogin = "shirazu",
                devices = listOf(
                    AccountDevice(
                        id = "device-1",
                        name = "Pixel 8",
                        model = "Google Pixel 8",
                        platform = "android",
                        current = true,
                    ),
                    AccountDevice(
                        id = "device-2",
                        name = "iPhone 18 Pro",
                        model = "iPhone18,1",
                        platform = "ios",
                    ),
                ),
            ),
        ),
        handoffEntry = false,
    )

    /** The account screen with the account's computers: one online, one offline. */
    fun accountWithComputers(): AccountUiState = accountSignedIn().copy(
        desktops = listOf(
            AccountDesktop(
                deviceId = "desktop-1",
                name = "Studio PC",
                model = "Windows 11",
                platform = "windows",
                online = true,
                room = "ROOM1",
            ),
            AccountDesktop(
                deviceId = "desktop-2",
                name = "Mac mini",
                model = "Mac mini (M4)",
                platform = "macos",
                online = false,
            ),
        ),
    )
}
