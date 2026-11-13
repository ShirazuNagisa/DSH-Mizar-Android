package me.shirazu.mizar.kit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The one field that makes agent switching work: the chosen agent has to reach the
 * `hello` frame, and the pairing code's token cannot carry it.
 *
 * A plain unit test rather than a device test because the value's only job is to be
 * copied into a configuration; whether `hello` then carries it is pinned in
 * `MizarConnectionStateTest`.
 */
class CredentialStoreEngineTest {

    private val host = PairedHost(
        hostId = "host-1",
        hostName = "PC",
        authority = "192.168.1.5:19390",
        baseUrl = "https://relay.test/r/ROOM",
        deviceId = "device-1",
        deviceToken = "device-token",
    )

    @Test
    fun `a chosen agent reaches both endpoints' configuration`() {
        val direct = host.configurationFor(
            MizarConnection.Endpoint.Direct("192.168.1.5:19390"),
            appVersion = "0.10.3",
            deviceModel = "Pixel 6",
            engine = "claude",
        )
        assertEquals("claude", direct.engine)
        assertEquals("device-token", direct.token)
        assertEquals("192.168.1.5:19390", direct.authority)

        val relay = host.configurationFor(
            MizarConnection.Endpoint.Relay("https://relay.test/r/ROOM"),
            appVersion = "0.10.3",
            deviceModel = "Pixel 6",
            engine = "claude",
        )
        assertEquals("claude", relay.engine)
        assertEquals("https://relay.test/r/ROOM", relay.baseUrl)
    }

    @Test
    fun `not choosing an agent keeps the unscoped connection`() {
        val unscoped = host.configurationFor(
            MizarConnection.Endpoint.Direct("192.168.1.5:19390"),
            appVersion = null,
            deviceModel = null,
        )
        assertNull(unscoped.engine)
    }

    @Test
    fun `the identity headers and the engine travel together`() {
        val relay = host.configurationFor(
            MizarConnection.Endpoint.Relay("https://relay.test/r/ROOM"),
            appVersion = null,
            deviceModel = null,
            headers = mapOf("x-mizar-session" to "session"),
            engine = "dsh",
        )
        assertEquals(mapOf("x-mizar-session" to "session"), relay.headers)
        assertEquals("dsh", relay.engine)
    }
}
