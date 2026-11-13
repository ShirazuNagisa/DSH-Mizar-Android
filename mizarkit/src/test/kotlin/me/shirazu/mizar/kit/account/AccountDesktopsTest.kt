package me.shirazu.mizar.kit.account

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.shirazu.mizar.kit.HttpRequest
import me.shirazu.mizar.kit.HttpResponse
import me.shirazu.mizar.kit.HttpTransport
import me.shirazu.mizar.kit.WIRE_JSON
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scripted account service: one answer per request, recorded. */
private class DesktopTransport(private val answer: (HttpRequest) -> HttpResponse) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        return answer(request)
    }
}

private fun json(status: Int, body: String) = HttpResponse(status = status, body = body)

/**
 * Account-based computer discovery.
 *
 * The wire lives on the relay, so the DTOs must read what it sends and the room URL
 * must be built the way a paired host's is (the connection loop dials it unchanged).
 */
class AccountDesktopsTest {

    private fun client(answer: (HttpRequest) -> HttpResponse): Pair<AccountClient, DesktopTransport> {
        val transport = DesktopTransport(answer)
        return AccountClient("https://relay.test/account/v1", transport) to transport
    }

    @Test
    fun `desktops reads the list and sends the bearer token`() = runBlocking {
        val (account, transport) = client {
            json(
                200,
                """
                {"desktops":[
                  {"deviceId":"d1","name":"工作室台式机","model":"Windows 11","platform":"windows",
                   "online":true,"room":"ROOM1"},
                  {"deviceId":"d2","name":"Mac mini","online":false}
                ]}
                """,
            )
        }
        val desktops = account.desktops("tok")
        assertEquals(2, desktops.size)
        assertEquals("工作室台式机", desktops[0].name)
        assertTrue(desktops[0].online)
        assertEquals("ROOM1", desktops[0].room)
        assertFalse(desktops[1].online)
        assertNull(desktops[1].room)
        assertEquals("GET", transport.requests.single().method)
        assertEquals("https://relay.test/account/v1/desktops", transport.requests.single().url)
        assertEquals("Bearer tok", transport.requests.single().headers["authorization"])
    }

    @Test
    fun `connect asks for a scoped token and returns the room`() = runBlocking {
        val (account, transport) = client { json(200, """{"room":"ROOM1","token":"aco_abc"}""") }
        val connection = account.connectToDesktop("tok", "d1", engine = "dsh")
        assertEquals("ROOM1", connection.room)
        assertEquals("aco_abc", connection.token)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertTrue(request.url.endsWith("/connect"))
        val body = WIRE_JSON.parseToJsonElement(request.body!!).jsonObject
        assertEquals("d1", body["deviceId"]!!.jsonPrimitive.content)
        assertEquals("dsh", body["engine"]!!.jsonPrimitive.content)
    }

    @Test
    fun `connect without an engine leaves the field out`() = runBlocking {
        val (account, transport) = client { json(200, """{"room":"R","token":"t"}""") }
        account.connectToDesktop("tok", "d1")
        val body = WIRE_JSON.parseToJsonElement(transport.requests.single().body!!).jsonObject
        assertFalse(body.containsKey("engine"))
    }

    @Test
    fun `a refused computer surfaces its code`() = runBlocking {
        val (account, _) = client {
            json(409, """{"error":"DESKTOP_OFFLINE","message":"the computer is not connected"}""")
        }
        val failure = assertThrows<AccountError> { runBlocking { account.connectToDesktop("tok", "d1") } }
        assertEquals("DESKTOP_OFFLINE", failure.code)
        assertEquals(409, failure.status)
    }

    @Test
    fun `a room URL keeps the origin and its port`() {
        assertEquals(
            "https://relay.test/r/ROOM1/",
            relayRoomUrl("https://relay.test/account/v1", "ROOM1"),
        )
        assertEquals(
            "http://10.0.2.2:8787/r/abc/",
            relayRoomUrl("http://10.0.2.2:8787/account/v1", "/abc/"),
        )
        assertNull(relayRoomUrl("https://relay.test/account/v1", "  "))
        assertNull(relayRoomUrl("not a url", "ROOM"))
    }
}
