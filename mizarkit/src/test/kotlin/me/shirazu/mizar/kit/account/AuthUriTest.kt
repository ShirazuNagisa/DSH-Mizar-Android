package me.shirazu.mizar.kit.account

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthUriTest {

    @Test
    fun `build and parse round-trip`() {
        val link = AuthUri.build("MZ-7Q2K-9WXA")

        assertEquals("mizar://auth?handoff=MZ-7Q2K-9WXA", link)
        assertEquals("MZ-7Q2K-9WXA", AuthUri.parse(link))
        assertTrue(AuthUri.isAuth(link))
    }

    @Test
    fun `a pairing link is not an auth link`() {
        val pair = "mizar://pair?h=10.0.2.2:19390&c=ABCD-EFGH&v=1"

        assertFalse(AuthUri.isAuth(pair))
        assertNull(AuthUri.parse(pair))
    }

    @Test
    fun `a link without a handoff is unusable`() {
        assertNull(AuthUri.parse("mizar://auth"))
        assertNull(AuthUri.parse("mizar://auth?handoff="))
        assertNull(AuthUri.parse("mizar://auth?state=abc"))
    }

    @Test
    fun `a handoff with unexpected characters is refused`() {
        assertNull(AuthUri.parse("mizar://auth?handoff=abc%20def"))
        assertNull(AuthUri.parse("mizar://auth?handoff=${"x".repeat(65)}"))
    }

    @Test
    fun `the first repeated handoff wins`() {
        assertEquals("MZ-AAAA-AAAA", AuthUri.parse("mizar://auth?handoff=MZ-AAAA-AAAA&handoff=MZ-BBBB-BBBB"))
    }

    @Test
    fun `the 0_3 relay link carries the code`() {
        val link = "mizar://github/login?code=MZ-7Q2K-9WXA"

        assertTrue(AuthUri.isAuth(link))
        assertEquals("MZ-7Q2K-9WXA", AuthUri.parse(link))
    }

    @Test
    fun `a github link on another path is not auth`() {
        assertFalse(AuthUri.isAuth("mizar://github/other?code=MZ-7Q2K-9WXA"))
        assertNull(AuthUri.parse("mizar://github/login"))
    }
}
