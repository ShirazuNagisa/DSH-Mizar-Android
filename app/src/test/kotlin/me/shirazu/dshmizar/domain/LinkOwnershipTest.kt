package me.shirazu.dshmizar.domain

import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.WebSocketTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules that used to live in comments beside three fields.
 *
 * A replaced socket must not be able to act -- its failure must not start a reconnect
 * loop for the relay the app just left, and a read that was in flight must not publish
 * over the new socket's transcript. And replacing comes before closing, because a close
 * on a still-live socket reports a failure that looks exactly like a dropped connection.
 */
class LinkOwnershipTest {

    private fun socket(): MizarConnection = MizarConnection(
        MizarConnection.Configuration(authority = "localhost:1234", token = "device-token"),
    ) {
        object : WebSocketTransport {
            override suspend fun connect(url: String, headers: Map<String, String>) = Unit
            override suspend fun send(text: String) = Unit
            override suspend fun receive(): String? = null
            override suspend fun close() = Unit
        }
    }

    @Test
    fun `adopting a second socket makes the first epoch stale`() {
        val ownership = LinkOwnership()
        val first = socket()
        val firstEpoch = ownership.adopt(first)
        val second = socket()
        val secondEpoch = ownership.adopt(second)

        assertFalse("a read captured on the old socket must not publish", ownership.isCurrent(firstEpoch))
        assertTrue(ownership.isCurrent(secondEpoch))
        assertSame(second, ownership.current)
        assertFalse(ownership.owns(first))
        assertTrue(ownership.owns(second))
    }

    @Test
    fun `releasing a replaced socket leaves the live one alone`() {
        val ownership = LinkOwnership()
        val first = socket()
        ownership.adopt(first)
        val second = socket()
        ownership.adopt(second)

        // The old socket finishing its teardown late must not clear the new one.
        assertNull(ownership.release(first))
        assertSame(second, ownership.current)
    }

    @Test
    fun `releasing the live socket drops it and hands it back to close`() {
        val ownership = LinkOwnership()
        val only = socket()
        ownership.adopt(only)

        assertSame(only, ownership.release(only))
        assertNull(ownership.current)
    }

    @Test
    fun `release with no candidate drops whatever is live`() {
        val ownership = LinkOwnership()
        val only = socket()
        ownership.adopt(only)

        assertSame(only, ownership.release())
        assertNull(ownership.current)
    }

    @Test
    fun `teardown does not move the epoch`() {
        val ownership = LinkOwnership()
        val only = socket()
        val epoch = ownership.adopt(only)
        ownership.release()

        // Teardown is not a new connection: a read waiting on the socket that just went
        // away still belongs to it, which is what lets its result be dropped quietly
        // instead of being mistaken for the new one's.
        assertEquals(epoch, ownership.currentEpoch)
        assertTrue(ownership.isCurrent(epoch))
    }

    @Test
    fun `a failure keeps the endpoint it came from`() {
        val ownership = LinkOwnership()
        val cause = IllegalStateException("no route answered")
        val at = MizarConnection.Endpoint.Direct("192.168.1.9:8787")

        ownership.recordFailure(cause, at)

        assertSame(cause, ownership.lastFailure)
        assertEquals(at, ownership.lastEndpoint)
    }

    @Test
    fun `adopting a socket does not erase the last failure`() {
        val ownership = LinkOwnership()
        val cause = IllegalStateException("no route answered")
        ownership.recordFailure(cause, null)

        ownership.adopt(socket())

        // The notice is written after the dial that failed, sometimes after a later
        // route won; clearing it on adopt would leave the notice without its cause.
        assertSame(cause, ownership.lastFailure)
    }
}
