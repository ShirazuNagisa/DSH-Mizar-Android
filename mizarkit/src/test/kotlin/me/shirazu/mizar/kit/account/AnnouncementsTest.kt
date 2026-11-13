package me.shirazu.mizar.kit.account

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.shirazu.mizar.kit.Incoming
import me.shirazu.mizar.kit.WebSocketTransport
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A relay socket a test answers frame by frame. */
private class FakeAnnouncementSocket : WebSocketTransport {
    private val inbox = Channel<Incoming?>(Channel.UNLIMITED)

    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    var closed: Boolean = false
        private set

    var url: String? = null
        private set

    var headers: Map<String, String> = emptyMap()
        private set

    override suspend fun connect(url: String, headers: Map<String, String>) {
        this.url = url
        this.headers = headers
        connected = true
    }

    override suspend fun send(text: String) = Unit

    override suspend fun receive(): String? = null

    override suspend fun receiveMessage(): Incoming? = inbox.receiveCatching().getOrNull()

    override suspend fun close() {
        closed = true
        inbox.close()
    }

    fun deliver(text: String) {
        inbox.trySend(Incoming.Text(text))
    }

    fun deliverBinary(bytes: ByteArray) {
        inbox.trySend(Incoming.Binary(bytes))
    }
}

/**
 * The announcement socket, its decoder and its URL derivation.
 *
 * The decoder is tested directly because the wire has exactly two frames and getting
 * either wrong is invisible until a banner fails to appear. The socket test drives it
 * with a fake transport, so the reconnect loop and the auth header are checkable
 * without a relay.
 */
class AnnouncementsTest {

    private val snapshotJson = """
        {"type":"snapshot","announcements":[
          {"id":"a1","title":"维护通知","body":"今晚 22:00 升级","symbol":"megaphone",
           "tone":"info","createdAt":1760500000000,"active":true,
           "button":{"label":"查看","target":"notifications"}},
          {"id":"a2","title":"社区","body":"来聊聊"}]}
    """.trimIndent()

    @Test
    fun `the socket URL is derived from the account origin`() {
        assertEquals(
            "wss://dshmizar-1.shirazu-nagisa.com/app/v1/announcements",
            AnnouncementClient.webSocketUrl("https://dshmizar-1.shirazu-nagisa.com/account/v1"),
        )
        // A local mock account server and relay share an address and differ by port.
        assertEquals(
            "ws://127.0.0.1:19396/app/v1/announcements",
            AnnouncementClient.webSocketUrl("http://127.0.0.1:19396/account/v1"),
        )
        assertNull(AnnouncementClient.webSocketUrl("not a url"))
        assertNull(AnnouncementClient.webSocketUrl("ftp://example.com/account/v1"))
        assertEquals("/app/v1/announcements", AnnouncementClient.WEB_SOCKET_PATH)
        assertEquals("x-mizar-session", AnnouncementClient.SESSION_HEADER)
    }

    @Test
    fun `a snapshot decodes into the whole batch`() {
        val events = AnnouncementClient.decode(snapshotJson)
        val snapshot = assertIs<AnnouncementEvent.Snapshot>(events.single())
        assertEquals(2, snapshot.announcements.size)
        val first = snapshot.announcements[0]
        assertEquals("a1", first.id)
        assertEquals("维护通知", first.title)
        assertEquals("info", first.tone)
        assertEquals(AnnouncementTarget.NOTIFICATIONS, first.actionableButton?.destination)
        assertEquals("查看", first.actionableButton?.label)
        assertTrue(first.isLive(nowMillis = 1_760_500_000_001))
    }

    @Test
    fun `a created banner decodes on its own`() {
        val events = AnnouncementClient.decode(
            """{"type":"announcement","announcement":{"id":"a3","title":"新功能","body":"试试看"}}""",
        )
        val created = assertIs<AnnouncementEvent.Created>(events.single())
        assertEquals("a3", created.announcement.id)
    }

    @Test
    fun `unknown and malformed frames are empty, never an exception`() {
        assertTrue(AnnouncementClient.decode("""{"type":"something-new","x":1}""").isEmpty())
        assertTrue(AnnouncementClient.decode("not json").isEmpty())
        assertTrue(AnnouncementClient.decode("""{"type":"snapshot"}""").isEmpty())
        // A snapshot whose entries are all unreadable is still a snapshot: the socket
        // said "these are the banners", and the answer is "none of them parsed".
        val unreadable = AnnouncementClient.decode(
            """{"type":"snapshot","announcements":[{"noId":true}]}""",
        )
        val snapshot = assertIs<AnnouncementEvent.Snapshot>(unreadable.single())
        assertTrue(snapshot.announcements.isEmpty())
    }

    @Test
    fun `a banner with an unknown target keeps its text and hides the button`() {
        val banner = AnnouncementClient.decodeAnnouncement(
            me.shirazu.mizar.kit.WIRE_JSON.parseToJsonElement(
                """{"id":"a4","title":"更新","body":"说明","button":{"label":"打开","target":"not-a-page"}}""",
            ),
        )
        assertTrue(banner != null)
        assertEquals("更新", banner.title)
        assertNull(banner.actionableButton, "a target this build does not know must not draw a dead button")
        assertEquals("megaphone", banner.resolvedSymbol)
        // No expiry means "live until the server says otherwise".
        assertTrue(banner.isLive(nowMillis = Long.MAX_VALUE))
    }

    @Test
    fun `an expired banner is not live`() {
        val banner = AnnouncementClient.decodeAnnouncement(
            me.shirazu.mizar.kit.WIRE_JSON.parseToJsonElement(
                """{"id":"a5","title":"t","body":"b","expiresAt":1000}""",
            ),
        )!!
        assertTrue(banner.isLive(nowMillis = 999))
        assertFalse(banner.isLive(nowMillis = 1001))
        assertFalse(banner.copy(active = false).isLive(nowMillis = 0))
    }

    @Test
    fun `the target vocabulary is the settings directory's`() {
        assertEquals(
            listOf("account", "notifications", "sending", "transfer", "localAgent", "files", "community", "logs", "about"),
            AnnouncementTarget.allWireValues,
        )
        assertEquals(AnnouncementTarget.LOCAL_AGENT, AnnouncementTarget.of("localAgent"))
        assertNull(AnnouncementTarget.of("not-a-page"))
    }

    @Test
    fun `the socket authenticates with the session header and yields frames`() = runBlocking {
        val socket = FakeAnnouncementSocket()
        val client = AnnouncementClient("https://relay.test/account/v1", makeTransport = { socket })

        val events = async {
            client.events("mza_session_token").take(2).toList()
        }
        withTimeout(3_000) { while (!socket.connected) delay(5) }
        assertEquals(
            "wss://relay.test/app/v1/announcements",
            socket.url,
        )
        assertEquals("mza_session_token", socket.headers[AnnouncementClient.SESSION_HEADER])

        // A binary frame in a JSON-only stream is ignored, not fatal.
        socket.deliverBinary(byteArrayOf(0x00, 0x01))
        socket.deliver(snapshotJson)
        socket.deliver("""{"type":"announcement","announcement":{"id":"a9","title":"新","body":"b"}}""")

        val received = withTimeout(3_000) { events.await() }
        assertEquals(2, received.size)
        assertIs<AnnouncementEvent.Snapshot>(received[0])
        assertEquals("a9", assertIs<AnnouncementEvent.Created>(received[1]).announcement.id)
    }

    @Test
    fun `the polling fallback reads through the injected account call`() = runBlocking {
        var askedWith: String? = null
        val client = AnnouncementClient(
            "https://relay.test/account/v1",
            pollFallback = { token ->
                askedWith = token
                listOf(Announcement(id = "a1", title = "t", body = "b"))
            },
        )
        val banners = client.poll("mza_token")
        assertEquals("mza_token", askedWith)
        assertEquals(listOf("a1"), banners.map { it.id })
    }

    @Test
    fun `the account client serves the polling twin with bearer auth`() = runBlocking {
        val requests = mutableListOf<me.shirazu.mizar.kit.HttpRequest>()
        val client = AccountClient(
            "https://relay.test/account/v1",
            object : me.shirazu.mizar.kit.HttpTransport {
                override suspend fun send(request: me.shirazu.mizar.kit.HttpRequest): me.shirazu.mizar.kit.HttpResponse {
                    requests += request
                    return me.shirazu.mizar.kit.HttpResponse(
                        200,
                        """{"announcements":[{"id":"a1","title":"维护","body":"今晚"}]}""",
                    )
                }
            },
        )

        val banners = client.announcements("mza_token")
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://relay.test/account/v1/announcements", request.url)
        assertEquals("Bearer mza_token", request.headers["authorization"])
        assertEquals("a1", banners.single().id)
    }
}
