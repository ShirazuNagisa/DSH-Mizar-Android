package me.shirazu.mizar.kit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the host reports, typed.
 *
 * Every optional here mirrors an optional on the host, and the rule the whole app
 * is built on lives in this file: **an absent projection key means the capability
 * is not composed, not that its value is zero.** `cacheHit == null` renders as "—",
 * `tokenUsage == {0,0,0,0}` renders as `0`, and nothing anywhere converts one into
 * the other.
 */

/** `host.info`. */
@Serializable
data class HostInfo(
    val hostId: String,
    val hostName: String,
    val home: String,
    val protocolVersion: Int,
    /**
     * The addresses the listener is actually bound to, when the host plugin is new
     * enough to answer. Absent is "this host cannot tell us" — not an empty list,
     * which would mean "there is nowhere to dial".
     */
    val addresses: List<String>? = null,
    /**
     * The relay the host's tunnel is on, when the plugin is new enough to answer.
     *
     * The host owns the allowlist and the choice; the phone only says which of the
     * two public relays its account pays for. Absent is an older host: nothing to
     * move, and nothing to repair.
     */
    val relay: HostRelay? = null,
    /**
     * What the computer is: `{os, client}` display names, e.g. `{"os":"Windows",
     * "client":"AGI Mizar"}`. Absent on an older host — never guess one from the
     * pairing record, because a guess is a wrong claim about somebody's machine.
     */
    val desktop: HostDesktop? = null,
    /**
     * The agents this computer serves, when it serves more than the base protocol.
     *
     * Additive: an older host omits the key, and the phone then keeps the merged
     * behaviour it had before agent switching existed.
     */
    val engines: List<HostEngine>? = null,
    /**
     * The agent this connection is scoped to, `null` for an unscoped one.
     *
     * Absent and `null` read the same: no choice was made, and the phone must not
     * invent a filter.
     */
    val engine: String? = null,
    /** What this host's plugin can do beyond the base protocol. */
    val plugin: HostPlugin? = null,
    /**
     * The direct path's key, or `null` when this host has none. A phone that sees it
     * may grow a `.p2p` route; one that sees `null` keeps the routes it had.
     */
    val netEndpointId: String? = null,
) {
    /** Whether the host offered a capability bit this build understands. */
    fun supports(capability: String): Boolean = plugin?.capabilities?.contains(capability) == true

    /** Whether the wallet card can be offered: the host serves DSH and says so. */
    val supportsDeepSeekBalance: Boolean
        get() = supports(PLUGIN_CAPABILITY_DEEPSEEK_BALANCE)

    companion object {
        /** The one capability the current plugin advertises (see `server.rs` `host.info`). */
        const val PLUGIN_CAPABILITY_DEEPSEEK_BALANCE: String = "deepseek.balance.v1"
    }
}

/** `host.info.desktop` — display names, not protocol enums. */
@Serializable
data class HostDesktop(
    val os: String = "",
    val client: String = "",
)

/**
 * One agent a computer serves, as `host.info.engines` lists it.
 *
 * @property id the wire name `session.create`/`hello.engine` accept.
 * @property label the display name, already localized by the host.
 * @property connected whether that engine is running on the computer right now.
 * @property detail why it is not connected, when the host can say.
 */
@Serializable
data class HostEngine(
    val id: String,
    val label: String = "",
    val connected: Boolean = false,
    val detail: String? = null,
)

/** `host.info.plugin` — the plugin's identity and the capabilities it advertises. */
@Serializable
data class HostPlugin(
    val id: String = "",
    val capabilities: List<String> = emptyList(),
)

/** `session.list`. */
@Serializable
data class SessionListResult(val items: List<SessionRow> = emptyList())

/** `workspace.list` — the workspaces DSH has *now*, which is not the same as the directories sessions live in. */
@Serializable
data class WorkspaceListResult(val items: List<WorkspaceRow> = emptyList())

/** One workspace DSH knows about. */
@Serializable
data class WorkspaceRow(
    val workspaceId: String,
    val path: String,
    val title: String,
)

/**
 * One row in the session list.
 *
 * @property updatedAt epoch **milliseconds** as a double: the host sends a JSON
 *   number, and narrowing it to an `Int` would date every session to 1970.
 * @property blank a session that exists but has never been prompted.
 * @property agentAvailable whether an agent is configured for it at all.
 */
@Serializable
data class SessionRow(
    val sessionId: String,
    val updatedAt: Double,
    val running: Boolean,
    val blank: Boolean,
    val agentAvailable: Boolean,
    val cwd: String? = null,
    val parentSessionId: String? = null,
    val origin: String? = null,
) {
    /** Epoch milliseconds, for formatting. */
    val updatedMillis: Long get() = updatedAt.toLong()

    /**
     * Whether this session is a delegated child of another session.
     *
     * Only the host's own mark (`origin == "subagent"`) counts. A session forked on
     * the computer also carries a [parentSessionId], but it is a conversation the
     * operator opened and can continue — hiding it the way a delegated child is
     * hidden is what the iOS client calls "where did my session go".
     */
    val isSubagent: Boolean get() = origin == "subagent"

    /** A forked conversation: it has a parent, but it is the operator's own work. */
    val isFork: Boolean get() = parentSessionId != null && !isSubagent
}

/** `session.stats`. */
@Serializable
data class SessionStatsResult(
    val sessionId: String,
    val asOfSeq: Double? = null,
    val stats: SessionStatsFrame? = null,
)

/**
 * The usage frame.
 *
 * Every field is an independent optional: any subset may arrive, and `null` means
 * "this host cannot report it", never "zero".
 */
@Serializable
data class SessionStatsFrame(
    val tokenUsage: TokenUsage? = null,
    val cacheHit: CacheHit? = null,
    val context: ContextPressure? = null,
    val stats: Throughput? = null,
    val model: ModelSelectionPair? = null,
    val permissionPreset: String? = null,
    val title: String? = null,
)

/** The four counters, plus the host's own sum of the three inputs. */
@Serializable
data class TokenUsage(
    val uncachedInputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val billedInputTokens: Long,
)

/**
 * A cache hit ratio.
 *
 * @property displayPercent a **string** on purpose: the host decides the precision,
 *   and an app that re-rounded it could turn 99.96% into "100%", which is the one
 *   claim a cache report must never make.
 */
@Serializable
data class CacheHit(
    val ratio: Double? = null,
    val displayPercent: String? = null,
)

/** How full the context window is. */
@Serializable
data class ContextPressure(
    val window: Long? = null,
    val pressureTokens: Long? = null,
    val projectedTokens: Long? = null,
    val percent: Int? = null,
    val breakdown: ContextBreakdown? = null,
)

/** The breakdown, which deliberately does not sum to `projectedTokens`. */
@Serializable
data class ContextBreakdown(
    val systemTokens: Long,
    val toolsTokens: Long,
    val messageTokens: Long,
)

/** Throughput figures for a session. */
@Serializable
data class Throughput(
    val turns: Long,
    val steps: Long,
    val llmMs: Double,
    val toolMs: Double,
    val ttftMs: Double? = null,
    val decodeTokensPerSecond: Double? = null,
)

/** The model in use, and the one the next turn will use. */
@Serializable
data class ModelSelectionPair(
    val lastUsed: ModelSelection? = null,
    val next: ModelSelection? = null,
)

/** One provider/model/effort route. */
@Serializable
data class ModelSelection(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)

/** `model.catalog`. */
@Serializable
data class ModelCatalog(
    // `default` is a keyword on the Swift side, hence the rename; the wire key is
    // still `default`.
    @SerialName("default") val defaultSelection: ModelSelection? = null,
    val providers: List<ProviderGroup> = emptyList(),
    val failures: List<CatalogFailure> = emptyList(),
)

/** One provider's models. */
@Serializable
data class ProviderGroup(
    val id: String,
    val name: String,
    val models: List<ModelOption> = emptyList(),
)

/**
 * One selectable model.
 *
 * @property efforts never `null`: absent decodes to an empty list, because the
 *   picker branches on emptiness and sending an effort to a model that has none is
 *   an error upstream rather than something to clamp.
 */
@Serializable
data class ModelOption(
    val id: String,
    val name: String,
    val description: String? = null,
    val efforts: List<ReasoningEffortOption> = emptyList(),
    val defaultEffort: String? = null,
)

/** One reasoning-effort level. */
@Serializable
data class ReasoningEffortOption(
    val id: String,
    val name: String,
    val description: String? = null,
)

/** A provider that could not be listed. Shown, not hidden. */
@Serializable
data class CatalogFailure(
    val id: String,
    val name: String,
    val message: String,
)

/** `session.model.set`. */
@Serializable
data class SelectModelResult(
    val sessionId: String,
    val selected: ModelSelection,
)

/** `permissions.catalog`. */
@Serializable
data class PermissionCatalog(
    val options: List<PermissionOption> = emptyList(),
    val defaults: List<PermissionOption> = emptyList(),
    val defaultPreset: String = "",
)

/**
 * One permission preset.
 *
 * @property isDangerous exactly `danger-full-access`: `auto` is broad but not this,
 *   and `custom` is derived-only — it may be the current value but is never offered
 *   as a target.
 */
@Serializable
data class PermissionOption(
    val value: String,
    val name: String,
    val description: String? = null,
) {
    val isDangerous: Boolean get() = value == "danger-full-access"
}

/** `interactions.pending`. */
@Serializable
data class PendingInteractions(
    val approvals: List<PendingApproval> = emptyList(),
    val questions: List<PendingQuestion> = emptyList(),
) {
    val isEmpty: Boolean get() = approvals.isEmpty() && questions.isEmpty()
}

/** An approval waiting for an answer. */
@Serializable
data class PendingApproval(
    val requestId: String,
    val sessionId: String? = null,
    val toolName: String,
    val callId: String? = null,
    val reason: String? = null,
    val displayReason: Map<String, String>? = null,
    val requestedAt: Double = 0.0,
) {
    /**
     * The reason to show, in the operator's language when the host offered one.
     *
     * Lookup is by the first two letters of the locale, then English, then the raw
     * reason — the order the host's own catalogue was written for.
     */
    fun reasonText(preferring: String): String? {
        val table = displayReason ?: return reason
        val language = preferring.lowercase().take(2)
        return table[language] ?: table["en"] ?: reason
    }
}

/** The only two answers an approval accepts. */
enum class ApprovalOutcome(val wire: String) {
    ALLOWED_ONCE("allowed-once"),
    REJECTED("rejected"),
}

/** A question batch waiting for answers. */
@Serializable
data class PendingQuestion(
    val callId: String,
    val sessionId: String? = null,
    val questions: List<QuestionItem> = emptyList(),
    val requestedAt: Double = 0.0,
)

/** One question inside a batch. */
@Serializable
data class QuestionItem(
    val id: String,
    val question: String,
    val detail: String? = null,
    val header: String? = null,
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
    val intent: QuestionIntent? = null,
)

/** One option inside a question. */
@Serializable
data class QuestionOption(
    val label: String,
    val description: String? = null,
)

/** What the host intends the question to mean. `plan-review` changes the button's wording. */
@Serializable
data class QuestionIntent(
    val kind: String,
    val approve: String = "",
) {
    val isPlanReview: Boolean get() = kind == "plan-review"
}

/** One answer inside a batch. */
@Serializable
data class QuestionAnswer(
    val id: String,
    val selected: List<String> = emptyList(),
    val custom: String? = null,
)

/** The `question.answer` payload. */
@Serializable
data class QuestionAnswerBatch(val answers: List<QuestionAnswer>)

/** `push.register`. */
@Serializable
data class PushRegistrationResult(val registered: Boolean = false)

/** `push.unregister`. */
@Serializable
data class PushUnregisterResult(val unregistered: Boolean = false)

/** `approval.answer`, `session.cancel`, `push.presence`. */
@Serializable
data class AcceptanceResult(val accepted: Boolean = false)

/** `session.create`. */
@Serializable
data class CreateSessionResult(
    val sessionId: String,
    val agentPreset: String? = null,
)

/** `session.prompt`. */
@Serializable
data class PromptResult(
    val accepted: Boolean = false,
    val requestId: String = "",
)

/** What a command-shaped method reports back. */
@Serializable
data class CommandResult(
    val kind: String,
    val text: String? = null,
) {
    val succeeded: Boolean get() = kind == "success"
}

/** `session.permission.set`. */
@Serializable
data class SetPermissionResult(val commandResult: CommandResult? = null)

/** `session.rename`. */
@Serializable
data class RenameResult(val renamed: Boolean = false)

/** `session.archive`. The host archives without stopping work; a running session is refused. */
@Serializable
data class ArchiveSessionResult(val archived: Boolean = false)

/**
 * `session.activity` — the compact feed of the turn that is running now.
 *
 * Only the running turn is described; `turn` is `null` when nothing is running.
 * `entries` are one line each and already truncated by the host. The cursor pair
 * exists for paging backwards: `beforeSeq` without its `turn` would name a page
 * spanning earlier turns and cannot be answered honestly.
 */
@Serializable
data class SessionActivityResult(
    val sessionId: String,
    val asOfSeq: Double? = null,
    val turn: Int? = null,
    val entries: List<ActivityEntry> = emptyList(),
    val hasMore: Boolean = false,
    val oldestSeq: Double? = null,
)

/** One line of a running turn: thought, command, its output, or a tool call. */
@Serializable
data class ActivityEntry(
    val id: String,
    val seq: Double,
    val at: Double? = null,
    val kind: String,
    val title: String,
    val text: String? = null,
)

/**
 * How a prompt reaches a host that is already running a turn.
 *
 * The wire calls this field `mode`, and the two values are the host's own:
 * [QUEUE] is the old behaviour -- the host starts the prompt once the current turn
 * ends -- and [STEER] feeds it to the model inside the turn that is running,
 * without interrupting anything.
 *
 * Omitted rather than spelled out for [QUEUE]: a host older than the field defaults
 * to it, and a client that always sent the default would put every ordinary send
 * behind a newer plugin. Known older hosts accept [STEER] and treat it as a queue,
 * which is a queue rather than an error and the right degradation.
 */
enum class PromptMode(val wire: String) {
    /** Start it when the running turn ends. */
    QUEUE("queue"),

    /** Hand it to the running turn as guidance. */
    STEER("steer"),
    ;

    /** Whether the field has to travel on the wire. */
    val isExplicitOnTheWire: Boolean get() = this == STEER

    companion object {
        /** The mode a wire value names, or `null` for one this build does not know. */
        fun of(wire: String): PromptMode? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The `session.prompt` arguments, including the mode field only when it must travel.
 *
 * Building the payload in the kit keeps the "queue is the default and stays off the
 * wire" rule in one place; an app that spelled `mode: queue` itself would make every
 * ordinary prompt depend on a newer host.
 */
fun promptPayload(
    sessionId: String,
    text: String? = null,
    attachmentIds: List<String> = emptyList(),
    requestId: String? = null,
    clientTimeZone: String? = null,
    mode: PromptMode = PromptMode.QUEUE,
): JsonObject = buildJsonObject {
    put("sessionId", sessionId)
    text?.let { put("text", it) }
    if (attachmentIds.isNotEmpty()) {
        put("attachmentIds", JsonArray(attachmentIds.map { JsonPrimitive(it) }))
    }
    requestId?.let { put("requestId", it) }
    clientTimeZone?.let { put("clientTimeZone", it) }
    if (mode.isExplicitOnTheWire) put("mode", mode.wire)
}

/** `session.compact` — the host ran the engine's own compaction mechanism. */
@Serializable
data class CompactResult(val compacted: Boolean = false)

/** `session.fork` — a new conversation branched at one completed turn boundary. */
@Serializable
data class ForkResult(val sessionId: String = "") {
    /** Whether the host named the branch it created. */
    val hasBranch: Boolean get() = sessionId.isNotBlank()
}

/**
 * `session.changed` — the host's live nudge that a session's running flag moved.
 *
 * Pushed as `{"event":"session.changed","data":{...}}`: `{sessionId, running}` when
 * it started or stopped, `{sessionId, removed:true}` when it left the list. It never
 * carries the transcript; it is a signal to re-read the list.
 */
@Serializable
data class SessionChanged(
    val sessionId: String,
    /** Whether the session is running now, when the host said. */
    val running: Boolean? = null,
    /** Whether the session was archived or vanished. */
    val removed: Boolean = false,
)
