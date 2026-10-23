package me.shirazu.mizar.kit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The page's own context figure, which is what keeps the composer's ring live.
 *
 * Before plugin 0.8.9 the only source of this number was `session.stats`, and the
 * incremental refresh an open screen runs while a turn is going never asked for
 * one — so the figure froze at the last full load. Every page carries it now, and
 * the two ways to lose it are dropping the field and reading an absent one as a
 * zeroed window.
 */
class SessionMessagesContextTest {

    private fun decode(json: String): SessionMessagesResult =
        WIRE_JSON.decodeFromString(SessionMessagesResult.serializer(), json)

    @Test
    fun `a page carries the occupancy and its breakdown`() {
        val page = decode(
            """
            {
              "sessionId": "s1",
              "asOfSeq": 42,
              "hasMore": false,
              "records": [],
              "context": {
                "window": 128000,
                "pressureTokens": 64000,
                "projectedTokens": 70000,
                "percent": 55,
                "breakdown": {"systemTokens": 1000, "toolsTokens": 2000, "messageTokens": 61000}
              }
            }
            """.trimIndent(),
        )

        assertEquals(55, page.context?.percent)
        assertEquals(128_000L, page.context?.window)
        assertEquals(61_000L, page.context?.breakdown?.messageTokens)
    }

    @Test
    fun `a page without the figure is unknown, not zero`() {
        val page = decode("""{"sessionId":"s1","asOfSeq":1,"records":[],"hasMore":false}""")

        // An older plugin sends no key at all. A zeroed window would draw a full
        // ring and claim the context was empty.
        assertNull(page.context)
    }

    @Test
    fun `a partial figure keeps the fields the host did send`() {
        val page = decode("""{"sessionId":"s1","context":{"percent":7}}""")

        assertEquals(7, page.context?.percent)
        assertNull(page.context?.window)
        assertNull(page.context?.breakdown)
    }
}
