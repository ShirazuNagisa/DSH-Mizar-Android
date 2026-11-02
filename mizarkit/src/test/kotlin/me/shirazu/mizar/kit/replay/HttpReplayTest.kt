package me.shirazu.mizar.kit.replay

import kotlinx.coroutines.runBlocking
import me.shirazu.mizar.kit.HttpRequest
import me.shirazu.mizar.kit.HttpResponse
import me.shirazu.mizar.kit.HttpTransport
import me.shirazu.mizar.kit.MizarError
import me.shirazu.mizar.kit.PairingClient
import me.shirazu.mizar.kit.PairingFailure
import me.shirazu.mizar.kit.PairingRoute
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * Pairing, replayed from bytes a host really sent.
 *
 * The scenarios here are the ones that cannot be reached from a JVM unit test any
 * other way: a relay pairing whose direct address is dead, and a host that refuses
 * an expired code. Both used to be "try it on a phone and see".
 */
class HttpReplayTest {

    private fun recording(name: String): HttpReplay {
        val url = requireNotNull(javaClass.getResource("/replay/$name.json")) { "missing fixture $name" }
        return HttpReplay.load(File(url.toURI()))
    }

    /**
     * A replay that reaches only the relay.
     *
     * The recording is keyed on the path, and both routes pair through the same
     * path, so the difference between "the LAN answered" and "the LAN was dead" has
     * to be modelled by the transport. That is also what the phone sees: nothing at
     * the direct address.
     */
    private fun deadDirect(replay: HttpReplay): HttpTransport = object : HttpTransport {
        override suspend fun send(request: HttpRequest): HttpResponse {
            if (request.url.contains("10.0.2.2")) throw IOException("nothing answered")
            return replay.send(request)
        }
    }

    @Test
    fun `a recorded relay pairing pairs over the relay`() = runBlocking {
        val transport = deadDirect(recording("pairing-relay"))
        val client = PairingClient(transport)

        val outcome = client.pairWithFallback(
            code = "ABCD-EFGH",
            deviceName = "Pixel",
            // The direct address is in the QR but nothing answers it, which is what
            // the recording proves: only the relay exchange exists.
            authority = "10.0.2.2:19390",
            baseUrl = "https://relay.test/r/room",
        )

        assertTrue(outcome.route is PairingRoute.Relay)
        assertEquals("host-rec", outcome.host.hostId)
        assertEquals("token-rec", outcome.host.deviceToken)
        assertNull(outcome.host.authority)
    }

    @Test
    fun `a recorded refusal is final and keeps the host's code`() = runBlocking {
        val transport = recording("pairing-expired")
        val client = PairingClient(transport)

        val failure = assertThrows<PairingFailure> {
            runBlocking {
                client.pairWithFallback(
                    code = "ABCD-EFGH",
                    authority = "10.0.2.2:19390",
                    baseUrl = "https://relay.test/r/room",
                )
            }
        }

        // One attempt: the host answered, so the relay was never asked.
        assertEquals(1, failure.attempts.size)
        assertEquals("PAIRING_CODE_EXPIRED", (failure.finalError as MizarError).code)
    }

    @Test
    fun `a request with no recorded answer fails loudly`() = runBlocking {
        val transport = recording("pairing-relay")

        val error = assertThrows<AssertionError> {
            runBlocking {
                transport.send(HttpRequest(method = "GET", url = "http://10.0.2.2:19390/mizar/v1/hello"))
            }
        }

        // The message names what was asked for and what exists: a replay that
        // invented an answer would hide the drift it exists to catch.
        assertTrue(error.message!!.contains("GET /mizar/v1/hello"))
        assertTrue(error.message!!.contains("POST /r/room/mizar/v1/pair"))
    }

    @Test
    fun `a recording is keyed on the path, so a replay survives a different host`() = runBlocking {
        val transport = recording("pairing-relay")

        // Same room, seen through a different relay host: the recording keys on the
        // path, which is what survives a relay moving or a room being re-dialled.
        val response = transport.send(
            HttpRequest(method = "POST", url = "https://relay.test/r/room/mizar/v1/pair"),
        )

        assertEquals(200, response.status)
        assertEquals("/r/room/mizar/v1/pair", HttpReplay.pathOf("https://relay.test/r/room/mizar/v1/pair"))
    }

    @Test
    fun `recording wraps a real transport and can be written and read back`() = runBlocking {
        val real = object : HttpTransport {
            override suspend fun send(request: HttpRequest) =
                HttpResponse(status = 200, body = """{"ok":true}""", headers = mapOf("content-type" to "application/json"))
        }
        val recorder = RecordingTransport(real)

        recorder.send(HttpRequest(method = "get", url = "https://relay.test/mizar/v1/hello"))

        val file = File.createTempFile("mizar-recording", ".json")
        try {
            HttpReplay.save(file, recorder.snapshot("round-trip"))
            val replayed = HttpReplay.load(file)
            val response = replayed.send(HttpRequest(method = "GET", url = "https://elsewhere/mizar/v1/hello"))

            assertEquals(200, response.status)
            assertEquals("""{"ok":true}""", response.body)
        } finally {
            file.delete()
        }
    }

    /**
     * The other half of a replay: what was asked, in what order.
     *
     * Counting answers alone cannot tell "asked once, correctly" from "asked twice,
     * once wrong" -- and the pairing path asks twice on purpose when the LAN address
     * is dead, so the order is part of the contract.
     */
    @Test
    fun `a replay remembers every request it was asked for, in order`() = runBlocking {
        val replay = HttpReplay(
            Recording(
                name = "order",
                exchanges = listOf(
                    RecordedExchange("POST", "/mizar/v1/pair", 200, """{"ok":1}"""),
                    RecordedExchange("GET", "/mizar/v1/hello", 200, """{"ok":2}"""),
                ),
            ),
        )

        replay.send(HttpRequest(method = "post", url = "http://10.0.2.2:19390/mizar/v1/pair", body = """{"code":"A"}"""))
        replay.send(HttpRequest(method = "GET", url = "https://relay.test/mizar/v1/hello"))

        assertEquals(2, replay.served)
        assertEquals(listOf("POST", "GET"), replay.requests.map { it.method })
        assertEquals(listOf("/mizar/v1/pair", "/mizar/v1/hello"), replay.requests.map { it.path })
        assertEquals("""{"code":"A"}""", replay.requests.first().body)
        assertNull(replay.requests.last().body)
    }

    /** A recording that names a body is a scenario, not just an endpoint. */
    @Test
    fun `a recording that names a body refuses a different one`() = runBlocking {
        val replay = HttpReplay(
            Recording(
                name = "body",
                exchanges = listOf(
                    RecordedExchange(
                        "POST", "/mizar/v1/pair", 200, """{"ok":1}""",
                        requestBody = """{"code":"RIGHT","name":"Pixel"}""",
                    ),
                ),
            ),
        )

        val error = assertThrows<AssertionError> {
            runBlocking {
                replay.send(
                    HttpRequest(
                        method = "POST",
                        url = "http://10.0.2.2:19390/mizar/v1/pair",
                        body = """{"code":"WRONG","name":"Pixel"}""",
                    ),
                )
            }
        }

        // The message has to name the body it saw, or the drift is invisible.
        assertTrue(error.message!!.contains("WRONG"), error.message)
        assertEquals(0, replay.served)
    }

    /** What the recorder writes down is what the replay insists on, round trip. */
    @Test
    fun `a recorded request body survives save and load and is enforced`() = runBlocking {
        val real = object : HttpTransport {
            override suspend fun send(request: HttpRequest) = HttpResponse(status = 200, body = """{"ok":true}""")
        }
        val recorder = RecordingTransport(real)
        recorder.send(
            HttpRequest(method = "POST", url = "https://relay.test/r/room/mizar/v1/pair", body = """{"code":"A"}"""),
        )

        val file = File.createTempFile("mizar-body", ".json")
        try {
            HttpReplay.save(file, recorder.snapshot("body-round-trip"))
            val replayed = HttpReplay.load(file)

            assertThrows<AssertionError> {
                runBlocking {
                    replayed.send(
                        HttpRequest(
                            method = "POST",
                            url = "https://relay.test/r/room/mizar/v1/pair",
                            body = """{"code":"B"}""",
                        ),
                    )
                }
            }
            assertEquals(0, replayed.served)

            // The recorded body itself still replays.
            replayed.send(
                HttpRequest(
                    method = "POST",
                    url = "https://relay.test/r/room/mizar/v1/pair",
                    body = """{"code":"A"}""",
                ),
            )
            assertEquals(1, replayed.served)
        } finally {
            file.delete()
        }
    }
}
