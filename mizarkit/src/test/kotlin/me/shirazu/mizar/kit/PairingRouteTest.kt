package me.shirazu.mizar.kit

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

private class RouteTransport(private val answer: (HttpRequest) -> HttpResponse?) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        return answer(request) ?: throw IOException("nothing answered")
    }
}

private const val PAIRED = """{"hostId":"h1","hostName":"desk","deviceId":"d1","deviceToken":"t1","protocolVersion":1}"""

class PairingRouteTest {

    @Test
    fun `the plan is direct first and relay behind it`() {
        val routes = PairingRoute.plan("10.0.2.2:19390", "https://relay.test/r/room")

        assertEquals(2, routes.size)
        assertTrue(routes[0] is PairingRoute.Direct)
        assertTrue(routes[1] is PairingRoute.Relay)
        assertEquals(PairingRoute.DIRECT_TIMEOUT_MILLIS, routes[0].timeoutMillis)
        assertEquals(PairingRoute.RELAY_TIMEOUT_MILLIS, routes[1].timeoutMillis)
    }

    @Test
    fun `a blank or unusable address is not a route`() {
        assertTrue(PairingRoute.plan(null, null).isEmpty())
        assertTrue(PairingRoute.plan("  ", "not a url").isEmpty())
        assertEquals(1, PairingRoute.plan(null, "https://relay.test/r/room").size)
    }

    @Test
    fun `an unanswered direct route falls through to the relay`() = runBlocking {
        val transport = RouteTransport { request ->
            if (request.url.startsWith("http://10.0.2.2")) null else HttpResponse(200, PAIRED)
        }
        val client = PairingClient(transport)

        val outcome = client.pairWithFallback(
            code = "ABCD-EFGH",
            deviceName = "Pixel",
            deviceModel = "Pixel 6",
            authority = "10.0.2.2:19390",
            baseUrl = "https://relay.test/r/room",
        )

        assertEquals(2, transport.requests.size)
        assertEquals(PairingRoute.DIRECT_TIMEOUT_MILLIS, transport.requests[0].timeoutMillis)
        assertEquals(PairingRoute.RELAY_TIMEOUT_MILLIS, transport.requests[1].timeoutMillis)
        assertTrue(outcome.route is PairingRoute.Relay)
        assertNull(outcome.host.authority)
        assertEquals("https://relay.test/r/room", outcome.host.baseUrl)
        assertEquals("t1", outcome.host.deviceToken)
    }

    @Test
    fun `a refusal on the direct route is final`() = runBlocking {
        val transport = RouteTransport {
            HttpResponse(400, """{"error":"PAIRING_CODE_INVALID"}""")
        }
        val client = PairingClient(transport)

        val error = assertThrows<MizarError> {
            runBlocking {
                client.pairWithFallback(
                    code = "ABCD-EFGH",
                    authority = "10.0.2.2:19390",
                    baseUrl = "https://relay.test/r/room",
                )
            }
        }

        assertEquals("PAIRING_CODE_INVALID", error.code)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a relay-only code pairs over the relay`() = runBlocking {
        val transport = RouteTransport { HttpResponse(200, PAIRED) }
        val client = PairingClient(transport)

        val outcome = client.pairWithFallback(code = "ABCD-EFGH", baseUrl = "https://relay.test/r/room")

        assertEquals(1, transport.requests.size)
        assertEquals(PairingRoute.RELAY_TIMEOUT_MILLIS, transport.requests[0].timeoutMillis)
        assertTrue(outcome.route is PairingRoute.Relay)
    }

    @Test
    fun `no address at all is a bad authority`() {
        val client = PairingClient(RouteTransport { HttpResponse(200, PAIRED) })

        val error = assertThrows<MizarError> {
            runBlocking { client.pairWithFallback(code = "ABCD-EFGH") }
        }

        assertEquals("BAD_AUTHORITY", error.code)
    }
}
