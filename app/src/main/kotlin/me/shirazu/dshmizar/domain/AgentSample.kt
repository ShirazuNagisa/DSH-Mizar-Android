package me.shirazu.dshmizar.domain

/**
 * Fake agent catalogues for previews and render tests.
 *
 * The picker's real source is `host.info.engines` (and `hello.engines` before a
 * pair), which the Android kit does not expose yet -- the Lead swaps these in when
 * it does. They are the production shape, so the swap is an argument change.
 */
object SampleAgents {

    /** A desktop that fronts all three agents, with Claude's GUI surface down. */
    fun threeAgents(): AgentCatalog = AgentCatalog.from(
        served = listOf(
            HostAgent(AgentEngine.DSH, label = "DSH", connected = true),
            HostAgent(AgentEngine.CLAUDE, label = "Claude Code", connected = true),
            HostAgent(AgentEngine.CODEX, label = "Codex", connected = false, detail = "not running"),
        ),
        engineWire = "dsh",
    )

    /** The same computer, bound to a Claude CLI connection: the ordinary default. */
    fun claudeCli(): AgentCatalog = AgentCatalog(
        served = threeAgents().served,
        current = AgentTarget(AgentEngine.CLAUDE, AgentSurface.CLI),
    )

    /** An old host, or one fronting a single agent: no picker at all. */
    fun singleAgent(): AgentCatalog = AgentCatalog(
        served = listOf(HostAgent(AgentEngine.DSH, connected = true)),
        current = AgentTarget(AgentEngine.DSH),
    )
}
