package me.shirazu.mizar.kit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Moving a host's tunnel between the two public relays.
 *
 * The rules that keep this safe are all here: only those two origins are
 * selectable, the room survives the move, and a self-hosted relay never moves —
 * it is the only place that host can be.
 */
class RelayTierTest {

    @Test
    fun `the plan picks the relay`() {
        assertEquals(RelayTier.PAID_BASE_URL, RelayTier.baseUrl(paid = true))
        assertEquals(RelayTier.FREE_BASE_URL, RelayTier.baseUrl(paid = false))
    }

    @Test
    fun `an origin is the scheme and host, whatever the path`() {
        assertEquals(
            "https://dshmizar.shirazu-nagisa.com",
            RelayTier.canonicalOrigin("https://dshmizar.shirazu-nagisa.com/r/ROOM"),
        )
        assertEquals(
            "https://dshmizar.shirazu-nagisa.com",
            RelayTier.canonicalOrigin("HTTPS://DSHMizar.Shirazu-Nagisa.com/"),
        )
    }

    @Test
    fun `a room URL is recognised as one of the two public relays`() {
        assertTrue(RelayTier.isPublicRelay("https://dshmizar.shirazu-nagisa.com/r/ROOM"))
        assertTrue(RelayTier.isPublicRelay("https://dshmizar-1.shirazu-nagisa.com/r/ROOM"))
        assertTrue(RelayTier.isPaidRelay("https://dshmizar-1.shirazu-nagisa.com/r/ROOM"))
        assertFalse(RelayTier.isPaidRelay("https://dshmizar.shirazu-nagisa.com/r/ROOM"))
    }

    @Test
    fun `a self-hosted relay is not one of the two`() {
        assertFalse(RelayTier.isPublicRelay("https://mizar.example.com/r/ROOM"))
        assertNull(RelayTier.alternate("https://mizar.example.com/r/ROOM"))
    }

    @Test
    fun `the alternate keeps the room`() {
        assertEquals(
            "https://dshmizar-1.shirazu-nagisa.com/r/ROOM",
            RelayTier.alternate("https://dshmizar.shirazu-nagisa.com/r/ROOM"),
        )
        assertEquals(
            "https://dshmizar.shirazu-nagisa.com/r/ROOM",
            RelayTier.alternate("https://dshmizar-1.shirazu-nagisa.com/r/ROOM"),
        )
    }

    @Test
    fun `rebasing moves the origin and keeps the path`() {
        assertEquals(
            "https://dshmizar-1.shirazu-nagisa.com/r/ROOM",
            RelayTier.rebased(
                "https://dshmizar.shirazu-nagisa.com/r/ROOM",
                "https://dshmizar-1.shirazu-nagisa.com",
            ),
        )
    }

    @Test
    fun `garbage in, nothing out`() {
        assertNull(RelayTier.canonicalOrigin("not a url"))
        assertNull(RelayTier.canonicalOrigin("ftp://example.com/r/ROOM"))
        assertNull(RelayTier.alternate("not a url"))
        assertNull(RelayTier.rebased("not a url", "https://dshmizar-1.shirazu-nagisa.com"))
    }

    @Test
    fun `the wire spells the key baseURL`() {
        val result = WIRE_JSON.decodeFromString(
            RelaySetResult.serializer(),
            """{"url":"https://dshmizar-1.shirazu-nagisa.com","room":"ROOM","baseURL":"https://dshmizar-1.shirazu-nagisa.com/r/ROOM"}""",
        )

        assertEquals("ROOM", result.room)
        assertEquals("https://dshmizar-1.shirazu-nagisa.com/r/ROOM", result.baseUrl)
    }

    @Test
    fun `a host with no relay facts decodes as unknown`() {
        val info = WIRE_JSON.decodeFromString(
            HostInfo.serializer(),
            """{"hostId":"h","hostName":"desk","home":"/h","protocolVersion":1}""",
        )

        // Absent is an older host: nothing to move, and nothing to repair.
        assertNull(info.relay)
    }
}
