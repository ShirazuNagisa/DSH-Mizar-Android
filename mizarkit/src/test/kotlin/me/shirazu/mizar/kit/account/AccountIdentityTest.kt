package me.shirazu.mizar.kit.account

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountIdentityTest {

    private val token = "mza_abcdefghijklmnopqrst"

    @Test
    fun `a direct socket carries no identity`() {
        assertTrue(AccountIdentity.headers("install-1", token, relayBaseUrl = null).isEmpty())
        assertTrue(AccountIdentity.headers("install-1", token, relayBaseUrl = "").isEmpty())
    }

    @Test
    fun `a signed-out phone still names its install`() {
        val headers = AccountIdentity.headers("install-1", null, "https://relay.test")

        assertEquals("install-1", headers["x-mizar-client-id"])
        assertFalse(headers.containsKey("x-mizar-account"))
        assertFalse(headers.containsKey("x-mizar-session"))
    }

    @Test
    fun `a signed-in phone sends a hash, not the token`() {
        val headers = AccountIdentity.headers(
            "install-1", token, "https://other-relay.test",
            accountBaseUrl = "https://relay.test/account/v1",
        )

        assertEquals(AccountIdentity.sha256Hex(token), headers["x-mizar-account"])
        assertEquals(64, headers.getValue("x-mizar-account").length)
        assertFalse(headers.containsKey("x-mizar-session"))
    }

    @Test
    fun `only the account origin receives the token itself`() {
        val headers = AccountIdentity.headers(
            "install-1", token, "https://relay.test",
            accountBaseUrl = "https://relay.test/account/v1",
        )

        assertEquals(token, headers["x-mizar-session"])
    }

    @Test
    fun `a malformed install id is dropped rather than sent`() {
        val headers = AccountIdentity.headers("bad id", null, "https://relay.test")

        assertTrue(headers.isEmpty())
    }

    @Test
    fun `sha256 is lowercase hex of the utf8 bytes`() {
        // Known vector: sha256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AccountIdentity.sha256Hex("abc"),
        )
    }
}
