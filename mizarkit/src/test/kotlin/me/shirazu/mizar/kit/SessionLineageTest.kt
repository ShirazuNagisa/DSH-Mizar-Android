package me.shirazu.mizar.kit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The two rules that are cheap to get wrong and expensive to notice.
 *
 * A subagent's page is unreadable without its parent, and a fork's page is wrong
 * *with* one — so both directions are pinned. The model precedence has four
 * sources, and the pill and the picker used to disagree about their order.
 */
class SessionLineageTest {

    private fun row(
        id: String,
        origin: String? = null,
        parent: String? = null,
    ) = SessionRow(
        sessionId = id,
        updatedAt = 0.0,
        running = false,
        blank = false,
        agentAvailable = true,
        parentSessionId = parent,
        origin = origin,
    )

    @Test
    fun `a delegated child is read through its parent`() {
        val sessions = listOf(row("child-1", origin = "subagent", parent = "parent-1"))

        assertEquals("parent-1", SessionLineage.parentOf(sessions, "child-1"))
    }

    @Test
    fun `a fork keeps its session address`() {
        // It has a parent, and it is the operator's own conversation: naming the
        // parent would describe a log that is not a child's.
        val sessions = listOf(row("fork-1", parent = "parent-1"))

        assertNull(SessionLineage.parentOf(sessions, "fork-1"))
    }

    @Test
    fun `an ordinary session has no parent`() {
        assertNull(SessionLineage.parentOf(listOf(row("plain")), "plain"))
    }

    @Test
    fun `an unknown session has no parent`() {
        assertNull(SessionLineage.parentOf(emptyList(), "gone"))
    }

    @Test
    fun `the phone's own accepted switch wins`() {
        val chosen = ModelSelection("deepseek", "v4", "high")
        val reported = ModelSelectionPair(
            lastUsed = ModelSelection("deepseek", "v3"),
            next = ModelSelection("deepseek", "v4-pro"),
        )

        assertEquals(chosen, ModelRouting.agreed(chosen, reported, ModelSelection("other", "x")))
    }

    @Test
    fun `the host's pending change comes next`() {
        val next = ModelSelection("deepseek", "v4-pro")
        val reported = ModelSelectionPair(lastUsed = ModelSelection("deepseek", "v3"), next = next)

        assertEquals(next, ModelRouting.agreed(null, reported, ModelSelection("other", "x")))
    }

    @Test
    fun `the route the last turn took comes after that`() {
        val lastUsed = ModelSelection("deepseek", "v3")
        val reported = ModelSelectionPair(lastUsed = lastUsed)

        assertEquals(lastUsed, ModelRouting.agreed(null, reported, ModelSelection("other", "x")))
    }

    @Test
    fun `the catalogue default names a session that never ran`() {
        val fallback = ModelSelection("deepseek", "v4")

        assertEquals(fallback, ModelRouting.agreed(null, null, fallback))
        assertEquals(fallback, ModelRouting.agreed(null, ModelSelectionPair(), fallback))
    }

    @Test
    fun `nothing at all stays nothing rather than an invented route`() {
        assertNull(ModelRouting.agreed(null, null, null))
    }
}
