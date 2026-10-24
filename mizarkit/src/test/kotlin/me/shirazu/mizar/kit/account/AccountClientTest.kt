package me.shirazu.mizar.kit.account

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.shirazu.mizar.kit.HttpRequest
import me.shirazu.mizar.kit.HttpResponse
import me.shirazu.mizar.kit.HttpTransport
import me.shirazu.mizar.kit.WIRE_JSON
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

private class ScriptedTransport(private val answer: (HttpRequest) -> HttpResponse) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        return answer(request)
    }
}

private fun ok(body: String) = HttpResponse(200, body)

private val DEVICE = AccountDevicePayload(
    id = "install-1",
    name = "Pixel 6",
    model = "Google Pixel 6",
    platform = "android",
    appVersion = "0.10.2",
)

private val USER = """
    {"id":"u1","username":"shirazu","email":"a@b.com","emailVerified":true,
     "plan":"free","createdAt":1759000000000,"lastSeenAt":1760500000000,
     "devices":[{"id":"install-1","name":"Pixel 6","platform":"android","current":true}]}
""".trimIndent()

class AccountClientTest {

    /**
     * A reset is two calls, and the first one must not say whether the account
     * exists: the service answers the same way for an unknown address, and the
     * screen's copy depends on that being true.
     */
    @Test
    fun `reset start posts the address and returns the code's lifetime`() = runBlocking {
        val transport = ScriptedTransport {
            ok("""{"expiresAt":1760500000000,"resendAfterSeconds":60}""")
        }
        val client = AccountClient("https://relay.test/account/v1", transport, appVersion = "0.10.3")

        val challenge = client.resetStart("a@b.com")

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://relay.test/account/v1/password/reset/start", request.url)
        assertNull(request.headers["authorization"])
        val body = WIRE_JSON.parseToJsonElement(request.body!!).jsonObject
        assertEquals(setOf("email"), body.keys)
        assertEquals("a@b.com", body.getValue("email").jsonPrimitive.content)

        assertEquals("a@b.com", challenge.email)
        assertEquals(1760500000000L, challenge.expiresAt)
        assertEquals(60, challenge.resendAfterSeconds)
    }

    @Test
    fun `reset complete trades the code for a session`() = runBlocking {
        val transport = ScriptedTransport {
            ok("""{"token":"mza_token_value_1234567890","user":$USER}""")
        }
        val client = AccountClient("https://relay.test/account/v1", transport, appVersion = "0.10.3")

        val auth = client.resetComplete("a@b.com", "123456", "mizar-test-password", DEVICE)

        val request = transport.requests.single()
        assertEquals("https://relay.test/account/v1/password/reset/complete", request.url)
        val body = WIRE_JSON.parseToJsonElement(request.body!!).jsonObject
        assertEquals("a@b.com", body.getValue("email").jsonPrimitive.content)
        assertEquals("123456", body.getValue("code").jsonPrimitive.content)
        assertEquals("mizar-test-password", body.getValue("password").jsonPrimitive.content)
        assertEquals("install-1", body.getValue("device").jsonObject.getValue("id").jsonPrimitive.content)
        // The reset signs this device in: the session comes back from the same call.
        assertEquals("mza_token_value_1234567890", auth.token)
    }

    @Test
    fun `login posts identifier password and an android device`() = runBlocking {
        val transport = ScriptedTransport {
            ok("""{"token":"mza_token_value_1234567890","user":$USER}""")
        }
        val client = AccountClient("https://relay.test/account/v1", transport, appVersion = "0.10.2")

        val auth = client.login("a@b.com", "mizar-test-password", DEVICE)

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://relay.test/account/v1/login", request.url)
        assertEquals("application/json", request.headers["content-type"])
        assertEquals("application/json", request.headers["accept"])
        assertNull(request.headers["authorization"])

        val body = WIRE_JSON.parseToJsonElement(request.body!!).jsonObject
        assertEquals("a@b.com", body.getValue("identifier").jsonPrimitive.content)
        assertEquals("mizar-test-password", body.getValue("password").jsonPrimitive.content)
        assertEquals("android", body.getValue("device").jsonObject.getValue("platform").jsonPrimitive.content)
        assertEquals("mza_token_value_1234567890", auth.token)
        assertEquals("shirazu", auth.user.username)
    }

    @Test
    fun `register sends username email and password`() = runBlocking {
        val transport = ScriptedTransport { ok("""{"token":"mza_abcdefghijklmnopqrst","user":$USER}""") }
        val client = AccountClient("https://relay.test/account/v1", transport)

        client.register("shirazu", "a@b.com", "mizar-test-password", DEVICE)

        val body = WIRE_JSON.parseToJsonElement(transport.requests.single().body!!).jsonObject
        assertEquals("shirazu", body.getValue("username").jsonPrimitive.content)
        assertEquals("a@b.com", body.getValue("email").jsonPrimitive.content)
        assertEquals("mizar-test-password", body.getValue("password").jsonPrimitive.content)
    }

    @Test
    fun `me carries the bearer token and the install id`() = runBlocking {
        val transport = ScriptedTransport { ok("""{"user":$USER}""") }
        val client = AccountClient("https://relay.test/account/v1", transport)

        val user = client.me("mza_token_value_1234567890", "install-1")

        val request = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals("Bearer mza_token_value_1234567890", request.headers["authorization"])
        assertEquals("install-1", request.headers["x-mizar-client-id"])
        assertEquals("shirazu", user.username)
    }

    @Test
    fun `github start is anonymous unless a token asks to link`() = runBlocking {
        val transport = ScriptedTransport {
            ok("""{"authorizeUrl":"https://github.test/authorize","state":"st","expiresAt":1}""")
        }
        val client = AccountClient("https://relay.test/account/v1", transport)

        client.githubStart(DEVICE)
        client.githubStart(DEVICE, token = "mza_token_value_1234567890")

        assertEquals(2, transport.requests.size)
        assertNull(transport.requests[0].headers["authorization"])
        assertEquals("Bearer mza_token_value_1234567890", transport.requests[1].headers["authorization"])
    }

    @Test
    fun `github exchange sends the handoff and the device`() = runBlocking {
        val transport = ScriptedTransport { ok("""{"token":"mza_abcdefghijklmnopqrst","user":$USER}""") }
        val client = AccountClient("https://relay.test/account/v1", transport)

        client.githubExchange("MZ-7Q2K-9WXA", DEVICE)

        val request = transport.requests.single()
        val body = WIRE_JSON.parseToJsonElement(request.body!!).jsonObject
        assertEquals("MZ-7Q2K-9WXA", body.getValue("handoff").jsonPrimitive.content)
        assertEquals("install-1", body.getValue("device").jsonObject.getValue("id").jsonPrimitive.content)
        assertNull(request.headers["authorization"])
    }

    @Test
    fun `logout posts an empty body`() = runBlocking {
        val transport = ScriptedTransport { ok("""{"ok":true}""") }
        val client = AccountClient("https://relay.test/account/v1", transport)

        client.logout("mza_token_value_1234567890")

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertNull(request.body)
        assertEquals("Bearer mza_token_value_1234567890", request.headers["authorization"])
    }

    @Test
    fun `redeem unwraps the user envelope`() = runBlocking {
        val transport = ScriptedTransport { ok("""{"user":$USER}""") }
        val client = AccountClient("https://relay.test/account/v1", transport)

        val user = client.redeem("mza_token_value_1234567890", "MOCK-INVITE")

        assertEquals("shirazu", user.username)
        assertEquals("MOCK-INVITE", WIRE_JSON.parseToJsonElement(transport.requests.single().body!!)
            .jsonObject.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `a revoked session is a reauthentication failure`() = runBlocking {
        val transport = ScriptedTransport {
            HttpResponse(401, """{"error":"SESSION_REVOKED","message":"this phone was signed out"}""")
        }
        val client = AccountClient("https://relay.test/account/v1", transport)

        val error = assertThrows<AccountError> { runBlocking { client.me("mza_x", "install-1") } }

        assertEquals("SESSION_REVOKED", error.code)
        assertTrue(error.requiresReauthentication)
        assertFalse(error.retryable)
    }

    @Test
    fun `rate limiting is retryable and does not clear the session`() = runBlocking {
        val transport = ScriptedTransport { HttpResponse(429, "") }
        val client = AccountClient("https://relay.test/account/v1", transport)

        val error = assertThrows<AccountError> {
            runBlocking { client.login("shirazu", "password123", DEVICE) }
        }

        assertEquals("RATE_LIMITED", error.code)
        assertTrue(error.retryable)
        assertFalse(error.requiresReauthentication)
    }

    @Test
    fun `a bodyless 500 is internal and retryable`() = runBlocking {
        val transport = ScriptedTransport { HttpResponse(500, "") }
        val client = AccountClient("https://relay.test/account/v1", transport)

        val error = assertThrows<AccountError> {
            runBlocking { client.login("shirazu", "password123", DEVICE) }
        }

        assertEquals("INTERNAL", error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun `204 is a failure rather than an empty success`() = runBlocking {
        val transport = ScriptedTransport { HttpResponse(204, "") }
        val client = AccountClient("https://relay.test/account/v1", transport)

        val error = assertThrows<AccountError> { runBlocking { client.logout("mza_x") } }

        assertEquals("MALFORMED_RESPONSE", error.code)
    }

    @Test
    fun `404 reads as a malformed response, as the iOS client does`() = runBlocking {
        val transport = ScriptedTransport { HttpResponse(404, "") }
        val client = AccountClient("https://relay.test/account/v1", transport)

        val error = assertThrows<AccountError> { runBlocking { client.me("mza_x", "install-1") } }

        assertEquals("MALFORMED_RESPONSE", error.code)
    }

    @Test
    fun `a dead network is NETWORK with no status`() = runBlocking {
        val transport = ScriptedTransport { throw IOException("connection reset") }
        val client = AccountClient("https://relay.test/account/v1", transport)

        val error = assertThrows<AccountError> {
            runBlocking { client.login("shirazu", "password123", DEVICE) }
        }

        assertEquals("NETWORK", error.code)
        assertEquals(0, error.status)
        assertTrue(error.retryable)
    }

    @Test
    fun `a 200 that is not JSON is malformed`() = runBlocking {
        val transport = ScriptedTransport { ok("<html>not json</html>") }
        val client = AccountClient("https://relay.test/account/v1", transport)

        val error = assertThrows<AccountError> {
            runBlocking { client.login("shirazu", "password123", DEVICE) }
        }

        assertEquals("MALFORMED_RESPONSE", error.code)
    }

    @Test
    fun `the account origin is scheme host and port`() {
        assertTrue(AccountClient.isAccountOrigin("https://relay.test", "https://relay.test/account/v1"))
        assertFalse(AccountClient.isAccountOrigin("https://relay.test:8443", "https://relay.test/account/v1"))
        assertFalse(AccountClient.isAccountOrigin("http://relay.test", "https://relay.test/account/v1"))
        assertFalse(AccountClient.isAccountOrigin(null))
    }
}
