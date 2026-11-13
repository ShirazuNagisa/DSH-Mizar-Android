package me.shirazu.dshmizar.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.shirazu.mizar.kit.CreateSessionResult
import me.shirazu.mizar.kit.HostInfo
import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.PendingInteractions
import me.shirazu.mizar.kit.PromptResult
import me.shirazu.mizar.kit.SessionListResult
import me.shirazu.mizar.kit.WIRE_JSON
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The local kernel, dialled through the real connection layer.
 *
 * This is the integration proof for the transport swap: `MizarConnection` is the
 * same class the app uses against a desktop host, its transport is the in-process
 * [LocalKernelTransport], and the assertions are protocol-level (welcome, session
 * list/create, prompt → `turn/start…turn/end`, optional methods falling back). The
 * test APK is the only installation: no app, no computer, no network for the
 * protocol itself.
 *
 * The turn is expected to end with an error rather than an answer: a fresh test
 * install has no model API key, and the kernel's documented failure for that is
 * `turn/end {reason:{kind:"error", error:{code:"MISSING_CREDENTIAL"}}}`. That is
 * exactly the degradation path the app must render, so it is asserted rather than
 * worked around.
 */
@RunWith(AndroidJUnit4::class)
class LocalKernelLinkInstrumentedTest {

    private lateinit var host: LocalAgentHost

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        host = LocalAgentHost(
            context = context,
            appVersion = "instrumentation",
            deviceModel = "emulator",
        )
    }

    @After
    fun tearDown() {
        host.close()
    }

    @Test
    fun aLocalDialSpeaksTheProtocolAndRunsOneTurn() = test {
        val status = host.status()
        assertTrue("the local agent is available: ${status.bootError}", status.available)
        assertNotNull("the kernel reports a host id", status.hostId)

        val socket = MizarConnection(
            configuration = testConfiguration(),
            makeTransport = { host.openTransport() },
        )
        try {
            val welcome = socket.connect()
            assertEquals(1, welcome.protocolVersion)
            assertTrue("welcome carries a device id", welcome.deviceId.isNotEmpty())
            assertEquals(
                "host.info is the welcome's identity",
                welcome.hostId,
                socket.call<HostInfo>("host.info", timeoutMillis = REQUEST_TIMEOUT_MILLIS).hostId,
            )

            val before = socket.call<SessionListResult>("session.list", timeoutMillis = REQUEST_TIMEOUT_MILLIS)
            val created = socket.call<CreateSessionResult>(
                "session.create",
                buildJsonObject { },
                timeoutMillis = REQUEST_TIMEOUT_MILLIS,
            )
            assertTrue(
                "the new session did not exist before",
                before.items.none { it.sessionId == created.sessionId },
            )
            val listed = socket.call<SessionListResult>("session.list", timeoutMillis = REQUEST_TIMEOUT_MILLIS)
            assertTrue(
                "session.list carries the new session",
                listed.items.any { it.sessionId == created.sessionId },
            )

            val prompt = socket.call<PromptResult>(
                "session.prompt",
                buildJsonObject {
                    put("sessionId", created.sessionId)
                    put("text", "hello from the local agent instrumentation test")
                    put("requestId", "instrumentation-1")
                },
                timeoutMillis = REQUEST_TIMEOUT_MILLIS,
            )
            assertTrue("session.prompt is accepted", prompt.accepted)

            // The turn runs in the background and is observed by polling, exactly like
            // the app's conversation refresh does. `session.messages` answers a *delta*
            // once the connection's delivery cursor has moved, so the types are
            // accumulated across polls rather than read from the last page.
            val deadline = System.currentTimeMillis() + TURN_DEADLINE_MILLIS
            val seenTypes = LinkedHashSet<String>()
            val seenRecords = ArrayList<JsonElement>()
            var page: JsonElement? = null
            while (System.currentTimeMillis() < deadline) {
                page = socket.request(
                    "session.messages",
                    buildJsonObject {
                        put("sessionId", created.sessionId)
                        put("afterSeq", JsonNull)
                    },
                    timeoutMillis = POLL_TIMEOUT_MILLIS,
                )
                for (record in recordsOf(page)) {
                    seenRecords += record
                    recordType(record)?.let { seenTypes += it }
                }
                if (seenTypes.contains("turn/end")) break
                delay(100)
            }
            assertTrue("turn/start was written: $seenTypes", seenTypes.contains("turn/start"))
            assertTrue("turn/end closed the turn: $seenTypes", seenTypes.contains("turn/end"))
            assertTrue("the user prompt was recorded: $seenTypes", seenTypes.contains("user/message"))

            val turnEnd = seenRecords
                .firstOrNull { recordType(it) == "turn/end" }
                ?.jsonObject?.get("event")?.jsonObject?.get("data")
            assertNotNull("turn/end has a payload", turnEnd)
            val reason = turnEnd!!.jsonObject["reason"]!!.jsonObject
            assertTrue("turn/end names why it ended", reason.containsKey("kind"))
        } finally {
            runCatching { socket.disconnect() }
        }
    }

    @Test
    fun optionalDesktopMethodsDegradeToNull() = test {
        val socket = MizarConnection(
            configuration = testConfiguration(),
            makeTransport = { host.openTransport() },
        )
        try {
            socket.connect()
            // The kernel does not implement the desktop-only surfaces; the kit maps
            // NOT_IMPLEMENTED to null so the matching screens hide instead of failing.
            assertNull("usage.overview is desktop-only", socket.usageOverview())
            assertNull("file.stat is desktop-only", socket.fileStat("session", "a.png"))
        } finally {
            runCatching { socket.disconnect() }
        }
    }

    @Test
    fun theAutomaticInteractionPolicyFindsNothingPendingOnAFreshKernel() = test {
        val socket = MizarConnection(
            configuration = testConfiguration(),
            makeTransport = { host.openTransport() },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            socket.connect()
            val responder = LocalInteractions.launch(scope, socket, LocalInteractionPolicy.AUTO_DENY)
            val pending = socket.request("interactions.pending", timeoutMillis = POLL_TIMEOUT_MILLIS)
            assertNotNull("interactions.pending answers", pending)
            val decoded = WIRE_JSON.decodeFromJsonElement(PendingInteractions.serializer(), pending!!)
            assertTrue("a fresh kernel has no pending interactions", decoded.isEmpty)
            responder.cancel()
        } finally {
            scope.cancel()
            runCatching { socket.disconnect() }
        }
    }

    /**
     * Run one test body with a hard ceiling.
     *
     * A wedged kernel must fail the test, not hang the instrumentation runner: every
     * wait inside a body also has its own bound (dial/handshake and each request via
     * [testConfiguration], each poll via [POLL_TIMEOUT_MILLIS]), but this is the net
     * that catches a bound someone forgets later.
     */
    private fun test(body: suspend CoroutineScope.() -> Unit) {
        runBlocking {
            val finished = withTimeoutOrNull(TEST_TIMEOUT_MILLIS) { body() }
            assertNotNull(
                "the test did not finish within ${TEST_TIMEOUT_MILLIS}ms — a kernel wait was not bounded",
                finished,
            )
        }
    }

    /** The dial configuration with waits short enough that a stuck kernel fails fast. */
    private fun testConfiguration() = host.configuration().copy(
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS,
        handshakeTimeoutMillis = REQUEST_TIMEOUT_MILLIS,
    )

    private fun recordsOf(page: JsonElement?): List<JsonElement> =
        page?.jsonObject?.get("records")?.jsonArray ?: emptyList()

    private fun recordType(record: JsonElement): String? =
        record.jsonObject["event"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull

    private companion object {
        /** Hard ceiling for one test body. */
        const val TEST_TIMEOUT_MILLIS = 90_000L

        /** Ceiling for one correlated call (dial handshake and every request). */
        const val REQUEST_TIMEOUT_MILLIS = 10_000L

        /** Ceiling for one `session.messages` poll. */
        const val POLL_TIMEOUT_MILLIS = 5_000L

        /** How long the test waits for the turn's `turn/end`. */
        const val TURN_DEADLINE_MILLIS = 20_000L
    }
}
