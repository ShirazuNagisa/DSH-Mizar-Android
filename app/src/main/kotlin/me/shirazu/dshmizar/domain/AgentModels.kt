package me.shirazu.dshmizar.domain

import me.shirazu.mizar.kit.SessionRow
import me.shirazu.mizar.kit.WorkspaceGroup
import me.shirazu.mizar.kit.WorkspaceRow

/**
 * Which agent on the computer this phone is talking to.
 *
 * A desktop fronts DSH, Claude Code and Codex at the same time and the three share
 * nothing -- not sessions, not working directories, not tool surfaces -- so "this
 * computer's list" is really "this agent on this computer". The choice travels in
 * the connection: the host scopes `session.list`, `workspace.list`, the state
 * channel and every event to one engine, and `hello.engine` (already scoped) or
 * `host.info.engine` says which one arrived. `host.info.engines` says what the
 * computer could serve, and is deliberately *not* narrowed by the scope: it is
 * what a picker has to draw.
 *
 * This is the Android counterpart of iOS `App/AgentEngine.swift` and
 * `MizarKit/WorkspaceCatalog.swift`'s `scoped` rule. It is pure Kotlin -- the wire
 * enums, the five destinations, and the filter -- so the picker's behaviour has a
 * test rather than a screenshot.
 *
 * The host's wire vocabulary (`agi-protocol`): engine ids `dsh`, `claude`, `codex`;
 * `host.info.engines` rows are `{ id, label, connected, detail }`.
 */

/** The agents a computer can serve. */
enum class AgentEngine(
    /** The name the wire uses (`EngineId::as_str` on the desktop). */
    val wireName: String,
    /**
     * What the picker calls it. A product name, so it is not translated: DSH is
     * DSH in every language.
     */
    val productName: String,
) {
    DSH("dsh", "DSH"),
    CLAUDE("claude", "Claude Code"),
    CODEX("codex", "Codex"),
    ;

    /** The prefix a session id carries on a host that fronts several agents. */
    val sessionPrefix: String get() = "$wireName:"

    companion object {
        /**
         * The closed list of wire names.
         *
         * Reading any leading token as an agent would turn a session called
         * `build:steps` into a fourth agent and hide it from every list, so the
         * prefix is only an agent when it is one of these three.
         */
        val knownWireNames: Set<String> = entries.mapTo(LinkedHashSet()) { it.wireName }

        /** The engine a wire name means, or `null` when it is not one of the three. */
        fun fromWire(wire: String?): AgentEngine? {
            val name = wire?.trim().orEmpty()
            if (name.isEmpty()) return null
            return entries.firstOrNull { it.wireName == name }
        }
    }
}

/** The two surfaces a coding agent shows on a computer. */
enum class AgentSurface(val wireName: String) {
    GUI("gui"),
    CLI("cli"),
    ;

    companion object {
        fun fromWire(wire: String?): AgentSurface? {
            val name = wire?.trim().orEmpty()
            return entries.firstOrNull { it.wireName == name }
        }
    }
}

/**
 * One row in the phone's agent picker.
 *
 * The host scopes connections by engine, but a GUI transcript and a CLI
 * conversation are different things to open even when the same binary wrote both,
 * so the picker presents five destinations. DSH has no surface split.
 */
data class AgentTarget(
    val engine: AgentEngine,
    val surface: AgentSurface? = null,
) {
    /** Stable id: `dsh`, `claude-gui`, `codex-cli`. */
    val id: String = surface?.let { "${engine.wireName}-${it.wireName}" } ?: engine.wireName

    /** The short name a capsule can use. */
    val shortTitle: String = when (engine) {
        AgentEngine.DSH -> "DSH"
        AgentEngine.CLAUDE -> when (surface) {
            AgentSurface.GUI -> "CC GUI"
            AgentSurface.CLI -> "CC CLI"
            null -> "Claude"
        }
        AgentEngine.CODEX -> when (surface) {
            AgentSurface.GUI -> "Codex GUI"
            AgentSurface.CLI -> "Codex CLI"
            null -> "Codex"
        }
    }

    /** GUI/CLI split targets are still experimental. */
    val isExperimental: Boolean = engine != AgentEngine.DSH

    companion object {
        /** The five destinations, always in the same order. */
        val all: List<AgentTarget> = listOf(
            AgentTarget(AgentEngine.DSH),
            AgentTarget(AgentEngine.CLAUDE, AgentSurface.GUI),
            AgentTarget(AgentEngine.CLAUDE, AgentSurface.CLI),
            AgentTarget(AgentEngine.CODEX, AgentSurface.GUI),
            AgentTarget(AgentEngine.CODEX, AgentSurface.CLI),
        )

        /**
         * The destination for a connection bound to [engine] on [surface].
         *
         * `null` engine is "no scope at all", which every connection did before the
         * choice existed; the picker has to draw something, and DSH is what those
         * connections were.
         */
        fun forConnection(engine: AgentEngine?, surface: AgentSurface? = null): AgentTarget {
            val chosen = engine ?: AgentEngine.DSH
            return if (chosen == AgentEngine.DSH) {
                AgentTarget(chosen)
            } else {
                AgentTarget(chosen, surface ?: AgentSurface.CLI)
            }
        }
    }
}

/**
 * One agent a computer serves, as `hello.engines` / `host.info.engines` lists it.
 *
 * @property connected whether the agent is reachable right now; the host's word,
 *   and absent means "did not say", which draws as available rather than offline.
 */
data class HostAgent(
    val engine: AgentEngine,
    val label: String? = null,
    val connected: Boolean? = null,
    val detail: String? = null,
)

/** One row the picker draws: a destination, and whether it can be chosen. */
data class AgentOption(
    val target: AgentTarget,
    /** Whether this computer serves the agent at all, and says it is up. */
    val available: Boolean,
    /** Whether this is the agent the current connection is bound to. */
    val current: Boolean,
    /** What the host said about it, when it said anything. */
    val host: HostAgent? = null,
)

/**
 * The agents this connection may be switched between.
 *
 * @property served every agent the computer hosts, in the host's own order, from
 *   `host.info.engines`. Empty is the old host (or one that fronts a single
 *   agent), and then there is nothing to pick: the answer reads exactly like a
 *   computer with one agent, which is why the picker hides rather than drawing
 *   four disabled rows.
 * @property current what this connection is bound to, from `hello.engine` /
 *   `host.info.engine`; `null` means no scope at all.
 */
data class AgentCatalog(
    val served: List<HostAgent> = emptyList(),
    val current: AgentTarget? = null,
) {
    /** The destinations to draw, always in the same order. */
    val targets: List<AgentTarget> get() = AgentTarget.all

    /** Whether switching is on offer: a computer that serves more than one agent. */
    val showsPicker: Boolean
        get() = served.map { it.engine }.distinct().size > 1

    /** The destination the capsule names. */
    val currentTarget: AgentTarget get() = current ?: AgentTarget.forConnection(null)

    /** Whether the host serves this destination's agent and reports it up. */
    fun isAvailable(target: AgentTarget): Boolean {
        if (served.isEmpty()) return true
        val agent = served.firstOrNull { it.engine == target.engine } ?: return false
        return agent.connected != false
    }

    /** One row per destination, in the fixed order, with the host's states folded in. */
    fun options(): List<AgentOption> = targets.map { target ->
        AgentOption(
            target = target,
            available = isAvailable(target),
            current = target == currentTarget,
            host = served.firstOrNull { it.engine == target.engine },
        )
    }

    companion object {
        /**
         * Build the picker's model from the host's own answer.
         *
         * @param served `host.info.engines`, mapped to [HostAgent]; empty when the
         *   host did not say.
         * @param engineWire the bound engine's wire name (`hello.engine`).
         * @param surfaceWire the remembered GUI/CLI surface, when one was stored.
         */
        fun from(
            served: List<HostAgent>,
            engineWire: String?,
            surfaceWire: String? = null,
        ): AgentCatalog = AgentCatalog(
            served = served,
            current = engineWire?.let { wire ->
                AgentTarget.forConnection(AgentEngine.fromWire(wire), AgentSurface.fromWire(surfaceWire))
            },
        )
    }
}

/**
 * The two filters that keep a scoped connection's screens honest.
 *
 * A phone that opened DSH must not draw Codex's folders: the reported shape was
 * DSH's workspaces with two of Codex's under them, empty, because the sessions in
 * them had already been filtered out on the other half of the screen.
 *
 * The rule reads the row's own marker first and the id's prefix second, and keeps
 * a row that names neither. A host that fronts one agent sends bare ids (the old
 * plugin, the on-device kernel), and dropping those would empty a list that is
 * already correct.
 */
object AgentScoping {

    /** The engine a session id names, when it names one. */
    fun namedEngine(sessionId: String): AgentEngine? = prefixEngine(sessionId)

    /**
     * The engine a workspace row names, when it names one.
     *
     * The host's own `engine` field wins; else the id's prefix, which is the same
     * spelling (`dsh:<workspace-id>`).
     */
    fun namedEngine(workspaceId: String, engine: String? = null): AgentEngine? =
        AgentEngine.fromWire(engine) ?: prefixEngine(workspaceId)

    private fun prefixEngine(id: String): AgentEngine? {
        val colon = id.indexOf(':')
        // A prefix is a whole leading token followed by the row's own id: `dsh:` is
        // a marker, `build:steps` and `:` alone are not.
        if (colon <= 0 || colon == id.lastIndex) return null
        return AgentEngine.fromWire(id.substring(0, colon))
    }

    /** The session rows a connection bound to [engine] may draw. */
    fun sessions(rows: List<SessionRow>, engine: AgentEngine?): List<SessionRow> {
        if (engine == null) return rows
        return rows.filter { row -> namedEngine(row.sessionId)?.let { it == engine } ?: true }
    }

    /** The workspace rows a connection bound to [engine] may draw. */
    fun workspaces(rows: List<WorkspaceRow>, engine: AgentEngine?): List<WorkspaceRow> {
        if (engine == null) return rows
        return rows.filter { row ->
            // The Android kit's `WorkspaceRow` has no `engine` field yet; when it
            // arrives the explicit value goes in this call and the rule does not move.
            namedEngine(row.workspaceId)?.let { it == engine } ?: true
        }
    }

    /**
     * The session groups a connection bound to [engine] may draw.
     *
     * A group keeps the sessions the engine owns (and the ones that name none, for
     * the single-agent hosts). A group whose every session belonged to another
     * agent is dropped: it is that agent's folder, not an empty one of ours.
     */
    fun groups(groups: List<WorkspaceGroup>, engine: AgentEngine?): List<WorkspaceGroup> {
        if (engine == null) return groups
        return groups.mapNotNull { group ->
            val kept = sessions(group.sessions, engine)
            when {
                kept.size == group.sessions.size -> group
                kept.isEmpty() && group.sessions.isNotEmpty() -> null
                else -> group.copy(sessions = kept)
            }
        }
    }
}
