package me.shirazu.mizar.kit.account

import me.shirazu.mizar.kit.WIRE_JSON
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountModelsTest {

    @Test
    fun `timestamps are epoch milliseconds and devices decode`() {
        val user = WIRE_JSON.decodeFromString(
            AccountUser.serializer(),
            """
            {"id":"u1","username":"shirazu","email":"a@b.com","emailVerified":true,
             "plan":"paid","paidUntil":1761000000000,"paidSources":["invite"],
             "grantedPaidUntil":1761000000000,"apple":false,"github":true,"githubLogin":"octocat",
             "createdAt":1759000000000,"lastSeenAt":1760500000000,
             "avatar":{"kind":"generated","colors":["#111111"],"initials":"S"},
             "devices":[{"id":"install-1","name":"Pixel 6","model":"Pixel 6",
                         "platform":"android","loginAt":1759000000000,
                         "lastSeenAt":1760500000000,"current":true}]}
            """.trimIndent(),
        )

        assertEquals(1759000000000, user.createdAt)
        assertEquals(1761000000000, user.paidUntil)
        assertTrue(user.isPaid)
        assertTrue(user.github)
        assertEquals("octocat", user.githubLogin)
        assertEquals("S", user.initials)
        assertEquals(1, user.devices.size)
        assertTrue(user.devices.single().current)
    }

    @Test
    fun `an unknown plan reads as free rather than failing`() {
        val user = WIRE_JSON.decodeFromString(
            AccountUser.serializer(),
            """{"id":"u1","username":"shirazu","plan":"platinum"}""",
        )

        assertEquals("platinum", user.plan)
        assertFalse(user.isPaid)
    }

    @Test
    fun `a minimal user decodes with defaults`() {
        val user = WIRE_JSON.decodeFromString(
            AccountUser.serializer(),
            """{"id":"u1","username":"shirazu"}""",
        )

        assertNull(user.email)
        assertFalse(user.emailVerified)
        assertEquals("free", user.plan)
        assertTrue(user.devices.isEmpty())
        assertEquals("generated", user.avatar.kind)
        assertEquals("S", user.initials)
    }

    @Test
    fun `notice names the device that was signed out`() {
        val auth = WIRE_JSON.decodeFromString(
            AccountAuth.serializer(),
            """{"token":"mza_abcdefghijklmnopqrst","user":{"id":"u1","username":"shirazu"},
                "notice":{"code":"DEVICE_LIMIT","revokedDevice":"iPhone 13"}}""",
        )

        assertEquals("DEVICE_LIMIT", auth.notice?.code)
        assertEquals("iPhone 13", auth.notice?.revokedDevice)
    }

    @Test
    fun `a session is usable only with a real token`() {
        val user = AccountUser(id = "u1", username = "shirazu")
        assertTrue(AccountSession("mza_abcdefghijklmnopqrst", user).isUsable)
        assertFalse(AccountSession("", user).isUsable)
        assertFalse(AccountSession("nope", user).isUsable)
    }
}
