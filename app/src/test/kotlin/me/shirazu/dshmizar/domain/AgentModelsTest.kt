package me.shirazu.dshmizar.domain

import me.shirazu.mizar.kit.SessionRow
import me.shirazu.mizar.kit.WorkspaceGroup
import me.shirazu.mizar.kit.WorkspaceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent picker's model and the two filters that keep a scoped connection's
 * screens honest.
 *
 * The important cases are the two that were reported as bugs on iOS: a list from
 * one agent showing another agent's folders, and a session whose id happens to
 * contain a colon being read as a fourth agent and hidden from every list.
 */
class AgentModelsTest {

    private fun session(id: String, cwd: String? = "/work/a") = SessionRow(
        sessionId = id,
        updatedAt = 0.0,
        running = false,
        blank = false,
        agentAvailable = true,
        cwd = cwd,
    )

    private fun workspace(id: String, path: String = "/work/a") =
        WorkspaceRow(workspaceId = id, path = path, title = path)

    @Test
    fun `wire names are the three engines and nothing else`() {
        assertEquals(AgentEngine.DSH, AgentEngine.fromWire("dsh"))
        assertEquals(AgentEngine.CLAUDE, AgentEngine.fromWire("claude"))
        assertEquals(AgentEngine.CODEX, AgentEngine.fromWire("codex"))
        assertEquals(AgentEngine.DSH, AgentEngine.fromWire(" dsh "))
        assertNull(AgentEngine.fromWire("build"))
        assertNull(AgentEngine.fromWire(null))
        assertNull(AgentEngine.fromWire(""))
        assertEquals(setOf("dsh", "claude", "codex"), AgentEngine.knownWireNames)
    }

    @Test
    fun `the five destinations keep their ids and names`() {
        assertEquals(
            listOf("dsh", "claude-gui", "claude-cli", "codex-gui", "codex-cli"),
            AgentTarget.all.map { it.id },
        )
        assertEquals("DSH", AgentTarget.all[0].shortTitle)
        assertEquals("CC GUI", AgentTarget.all[1].shortTitle)
        assertEquals("CC CLI", AgentTarget.all[2].shortTitle)
        assertEquals("Codex GUI", AgentTarget.all[3].shortTitle)
        assertEquals("Codex CLI", AgentTarget.all[4].shortTitle)
        assertFalse(AgentTarget.all[0].isExperimental)
        assertTrue(AgentTarget.all[1].isExperimental)
    }

    @Test
    fun `a connection with no scope reads as DSH, the way every old connection did`() {
        assertEquals(AgentTarget(AgentEngine.DSH), AgentTarget.forConnection(null))
        assertEquals(
            AgentTarget(AgentEngine.CLAUDE, AgentSurface.CLI),
            AgentTarget.forConnection(AgentEngine.CLAUDE, null),
        )
        assertEquals(
            AgentTarget(AgentEngine.CODEX, AgentSurface.GUI),
            AgentTarget.forConnection(AgentEngine.CODEX, AgentSurface.GUI),
        )
    }

    @Test
    fun `the picker is offered only when the computer serves more than one agent`() {
        assertTrue(SampleAgents.threeAgents().showsPicker)
        assertFalse(SampleAgents.singleAgent().showsPicker)
        assertFalse(AgentCatalog().showsPicker)
    }

    @Test
    fun `availability reads the host's own connected flag`() {
        val catalog = SampleAgents.threeAgents()
        val dsh = AgentTarget(AgentEngine.DSH)
        val codex = AgentTarget(AgentEngine.CODEX, AgentSurface.CLI)
        assertTrue(catalog.isAvailable(dsh))
        assertFalse(catalog.isAvailable(codex))
        // The host never said anything: an old host, and "cannot say" draws as
        // available rather than as offline.
        assertTrue(AgentCatalog().isAvailable(codex))
        // The host fronts DSH only: Claude is not on offer.
        assertFalse(SampleAgents.singleAgent().isAvailable(AgentTarget(AgentEngine.CLAUDE, AgentSurface.CLI)))
    }

    @Test
    fun `the current destination is ticked, and only it`() {
        val catalog = SampleAgents.claudeCli()
        val options = catalog.options()
        assertEquals(5, options.size)
        assertEquals(listOf("claude-cli"), options.filter { it.current }.map { it.target.id })
    }

    @Test
    fun `the catalogue is built from the host's answer`() {
        val catalog = AgentCatalog.from(
            served = listOf(
                HostAgent(AgentEngine.DSH, connected = true),
                HostAgent(AgentEngine.CLAUDE, connected = true),
            ),
            engineWire = "claude",
            surfaceWire = "gui",
        )
        assertEquals(AgentTarget(AgentEngine.CLAUDE, AgentSurface.GUI), catalog.current)
        assertTrue(catalog.showsPicker)
        assertFalse(catalog.isAvailable(AgentTarget(AgentEngine.CODEX, AgentSurface.CLI)))
    }

    @Test
    fun `a session id names its agent only when the prefix is one of the three`() {
        assertEquals(AgentEngine.DSH, AgentScoping.namedEngine("dsh:session-1"))
        assertEquals(AgentEngine.CLAUDE, AgentScoping.namedEngine("claude:abc-123"))
        assertNull(AgentScoping.namedEngine("session-1"))
        // Not an agent: a session whose own id contains a colon must not be hidden.
        assertNull(AgentScoping.namedEngine("build:steps"))
        assertNull(AgentScoping.namedEngine("dsh:"))
        assertNull(AgentScoping.namedEngine(":session"))
    }

    @Test
    fun `a workspace row names its agent by field first, then by prefix`() {
        assertEquals(AgentEngine.CLAUDE, AgentScoping.namedEngine("dsh:ws-1", engine = "claude"))
        assertEquals(AgentEngine.DSH, AgentScoping.namedEngine("dsh:ws-1"))
        assertNull(AgentScoping.namedEngine("ws-1"))
    }

    @Test
    fun `scoped sessions keep the rows that name no agent`() {
        val rows = listOf(
            session("dsh:1"),
            session("claude:2"),
            session("bare-3"),
        )
        assertEquals(
            listOf("dsh:1", "bare-3"),
            AgentScoping.sessions(rows, AgentEngine.DSH).map { it.sessionId },
        )
        assertEquals(rows, AgentScoping.sessions(rows, null))
    }

    @Test
    fun `scoped workspaces keep the rows that name no agent`() {
        val rows = listOf(
            workspace("dsh:ws-1"),
            workspace("codex:ws-2"),
            workspace("ws-3"),
        )
        assertEquals(
            listOf("dsh:ws-1", "ws-3"),
            AgentScoping.workspaces(rows, AgentEngine.DSH).map { it.workspaceId },
        )
    }

    @Test
    fun `a group that belonged entirely to another agent is dropped, a mixed one is trimmed`() {
        val groups = listOf(
            WorkspaceGroup("/work/dsh", "dsh", listOf(session("dsh:1"))),
            WorkspaceGroup("/work/codex", "codex", listOf(session("codex:1"))),
            WorkspaceGroup("/work/both", "both", listOf(session("dsh:2"), session("codex:2"))),
        )
        val scoped = AgentScoping.groups(groups, AgentEngine.DSH)
        assertEquals(listOf("/work/dsh", "/work/both"), scoped.map { it.path })
        assertEquals(listOf("dsh:2"), scoped.last().sessions.map { it.sessionId })
        assertEquals(groups, AgentScoping.groups(groups, null))
    }
}
