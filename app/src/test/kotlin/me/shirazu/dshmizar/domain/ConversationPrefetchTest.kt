package me.shirazu.dshmizar.domain

import me.shirazu.mizar.kit.SessionRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The batch that warms the cache: newest first, never the open session, never a row
 * with nothing to read.
 */
class ConversationPrefetchTest {

    private fun row(
        id: String,
        updatedAt: Double,
        blank: Boolean = false,
        parent: String? = null,
    ) = SessionRow(
        sessionId = id,
        updatedAt = updatedAt,
        running = false,
        blank = blank,
        agentAvailable = true,
        parentSessionId = parent,
    )

    @Test
    fun `newest sessions come first and the open one is skipped`() {
        val sessions = listOf(
            row("old", 100.0),
            row("open", 900.0),
            row("newest", 800.0),
            row("middle", 400.0),
        )
        val picked = ConversationPrefetch.candidates(sessions, selectedSessionId = "open")
        assertEquals(listOf("newest", "middle", "old"), picked.map { it.sessionId })
    }

    @Test
    fun `blank rows and delegated children are not warmed`() {
        val sessions = listOf(
            row("blank", 500.0, blank = true),
            row("child", 400.0, parent = "parent"),
            row("real", 300.0),
        )
        assertEquals(
            listOf("real"),
            ConversationPrefetch.candidates(sessions, selectedSessionId = null).map { it.sessionId },
        )
    }

    @Test
    fun `the budget is bounded`() {
        val sessions = (1..10).map { row("s$it", it.toDouble()) }
        assertEquals(ConversationPrefetch.MAX_SESSIONS, ConversationPrefetch.candidates(sessions, null).size)
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(emptyList<String>(), ConversationPrefetch.candidates(emptyList(), selectedSessionId = "x"))
    }
}
