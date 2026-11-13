package me.shirazu.mizar.kit.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.shirazu.mizar.kit.Backoff
import me.shirazu.mizar.kit.Incoming
import me.shirazu.mizar.kit.OkHttpWebSocketTransport
import me.shirazu.mizar.kit.WIRE_JSON
import me.shirazu.mizar.kit.WebSocketTransport
import me.shirazu.mizar.kit.stringValue
import java.net.URI

/**
 * The banners the account relay pushes to phones, and the socket they arrive on.
 *
 * An announcement is a title, a sentence and optionally one button. The button may
 * name a settings page to open; the phone validates the name against
 * [AnnouncementTarget] and hides the button when it does not recognise it, which is
 * what the server promised ("hide it when the server did not ask for one, show it
 * when it did").
 *
 * The socket is on the *relay* origin rather than the account API's, because a
 * WebSocket upgrade cannot be proxied by the account relay's HTTP forwarder. The
 * endpoint is `/app/v1/announcements`, authenticated with the same session token the
 * data plane uses (`x-mizar-session`). The polling twin,
 * `/account/v1/announcements`, is the fallback for a phone that cannot hold a socket.
 *
 * Mirrors `iOS/MizarKit/Sources/MizarKit/Announcements.swift`; timestamps are epoch
 * milliseconds like the rest of the account API.
 */

/** One settings page a banner may open, as the server names it. */
enum class AnnouncementTarget(val wire: String) {
    ACCOUNT("account"),
    NOTIFICATIONS("notifications"),
    SENDING("sending"),
    TRANSFER("transfer"),
    LOCAL_AGENT("localAgent"),
    FILES("files"),
    COMMUNITY("community"),
    LOGS("logs"),
    ABOUT("about"),
    ;

    companion object {
        /** What the server may send, for a test to pin against the relay. */
        val allWireValues: List<String> = entries.map { it.wire }

        /** The target a wire value names, or `null` for one this build does not know. */
        fun of(wire: String): AnnouncementTarget? = entries.firstOrNull { it.wire == wire }
    }
}

/** The optional call to action. */
@Serializable
data class AnnouncementButton(
    val label: String = "",
    val target: String = "",
) {
    /** The shelf this button opens, when this build knows it. */
    val destination: AnnouncementTarget? get() = AnnouncementTarget.of(target)
}

/**
 * One banner.
 *
 * @property symbol the server-chosen icon name; unknown values fall back at draw time.
 * @property tone `info` (the app's accent) or `notice` (its waiting colour).
 * @property active whether the server says it is still live.
 */
@Serializable
data class Announcement(
    val id: String,
    val title: String = "",
    val body: String = "",
    val symbol: String? = null,
    val tone: String? = null,
    val button: AnnouncementButton? = null,
    /** Epoch milliseconds. */
    val createdAt: Long? = null,
    /** Epoch milliseconds. */
    val expiresAt: Long? = null,
    val active: Boolean? = null,
) {
    /** The symbol to draw, with the app's own default. */
    val resolvedSymbol: String get() = symbol?.takeIf { it.isNotBlank() } ?: DEFAULT_SYMBOL

    /** Whether this banner should still be shown. */
    fun isLive(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (active == false) return false
        val expires = expiresAt ?: return true
        return expires > nowMillis
    }

    /** The button, when this build can act on it. */
    val actionableButton: AnnouncementButton?
        get() = button?.takeIf { it.destination != null && it.label.isNotBlank() }

    companion object {
        /** The icon to draw when the server did not choose one this build can use. */
        const val DEFAULT_SYMBOL: String = "megaphone"
    }
}

/** What one socket connection yields. */
sealed interface AnnouncementEvent {
    /** Everything live when the socket opened. */
    data class Snapshot(val announcements: List<Announcement>) : AnnouncementEvent

    /** One banner, as it was created. */
    data class Created(val announcement: Announcement) : AnnouncementEvent
}

/** The `GET /account/v1/announcements` envelope. */
@Serializable
internal data class AnnouncementListEnvelope(
    val announcements: List<Announcement> = emptyList(),
)

/**
 * The polling half of the announcement API.
 *
 * Separate from [AccountApi] for the same reason as [AccountSurveysApi]: signing in
 * and reading banners are different callers.
 */
interface AccountAnnouncementsApi {

    /**
     * The banners live right now, oldest first.
     *
     * The polling twin of the announcement socket: what a phone that cannot hold a
     * socket reads on the foreground, and what a test asserts against.
     */
    suspend fun announcements(token: String): List<Announcement>
}

/**
 * The announcement socket and its polling fallback.
 *
 * One connection per account session, reconnected with a capped backoff while the
 * caller keeps collecting. The flow finishes when the consuming coroutine is
 * cancelled, which is how the app stops it: the caller cancels its collection when
 * the account signs out or the app goes to the background.
 *
 * The relay origin is derived from the account API's origin ([accountBaseUrl]):
 * `https://host/account/v1` becomes `wss://host/app/v1/announcements`. A WebSocket
 * upgrade cannot be proxied by the account API's HTTP forwarder, but the *origin* is
 * the same host, which is why no second address has to be configured.
 */
class AnnouncementClient(
    private val accountBaseUrl: String = AccountClient.DEFAULT_BASE_URL,
    private val makeTransport: () -> WebSocketTransport = { OkHttpWebSocketTransport() },
    /**
     * How the polling fallback reads the list; injectable so a test needs no server.
     *
     * `null` means "use the real account client", created once and reused.
     */
    private val pollFallback: (suspend (String) -> List<Announcement>)? = null,
) {

    private val account: AccountClient by lazy { AccountClient(accountBaseUrl) }

    /** The socket this client would dial, or `null` when the account URL is unusable. */
    val webSocketUrl: String? get() = webSocketUrl(accountBaseUrl)

    /**
     * The live banners, pushed as the relay creates them, reconnecting on its own.
     *
     * @param token the account session token.
     */
    fun events(token: String): Flow<AnnouncementEvent> = flow {
        val url = webSocketUrl(accountBaseUrl) ?: return@flow
        val backoff = Backoff.persistent
        while (currentCoroutineContext().isActive) {
            try {
                runOnce(url, token) { emit(it) }
                backoff.reset()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A relay restart, a phone changing networks, an expired token: the
                // next attempt is the shortest way to find out which.
            }
            // No delay right after a clean close either: a relay that closes an idle
            // socket is answered by one reconnect, not by a growing wait.
            if (!currentCoroutineContext().isActive) break
            delay((backoff.nextDelay() * 1000).toLong())
        }
    }

    /** The polling twin: every live banner, oldest first, as the account API serves it. */
    suspend fun poll(token: String): List<Announcement> =
        pollFallback?.invoke(token) ?: account.announcements(token)

    /** Connect once and forward frames until the socket closes. */
    private suspend fun runOnce(
        url: String,
        token: String,
        emit: suspend (AnnouncementEvent) -> Unit,
    ) {
        val transport = makeTransport()
        transport.connect(url, mapOf("x-mizar-session" to token))
        try {
            while (true) {
                when (val message = transport.receiveMessage() ?: break) {
                    is Incoming.Text -> for (event in decode(message.text)) emit(event)
                    // The announcement socket is JSON only today; a binary frame is a
                    // newer dialect, ignored rather than allowed to break the socket.
                    is Incoming.Binary -> continue
                }
            }
        } finally {
            transport.close()
        }
    }

    companion object {
        /** The WebSocket path below the relay origin. */
        const val WEB_SOCKET_PATH: String = "/app/v1/announcements"

        /** The header the relay authenticates the upgrade with. */
        const val SESSION_HEADER: String = "x-mizar-session"

        /**
         * The socket URL derived from the account API's base URL.
         *
         * Origin only: `/account/v1` is the HTTP API's prefix, and the socket lives at
         * the relay root. `https` becomes `wss`, `http` becomes `ws`.
         */
        fun webSocketUrl(accountBaseUrl: String): String? {
            val parsed = try {
                URI(accountBaseUrl.trim())
            } catch (_: Exception) {
                return null
            }
            val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
            val scheme = when (parsed.scheme?.lowercase()) {
                "https", "wss" -> "wss"
                "http", "ws" -> "ws"
                else -> return null
            }
            val port = if (parsed.port > 0) ":${parsed.port}" else ""
            return "$scheme://$host$port$WEB_SOCKET_PATH"
        }

        /**
         * Decode one server frame.
         *
         * The two frames the relay sends are the whole protocol: a `snapshot` of
         * everything live when the socket opened, and one `announcement` as it is
         * created. Anything else is ignored, because the event union is open.
         */
        fun decode(text: String): List<AnnouncementEvent> {
            val root = try {
                WIRE_JSON.parseToJsonElement(text)
            } catch (_: Exception) {
                return emptyList()
            }
            val objectValue = root as? JsonObject ?: return emptyList()
            return when (objectValue["type"].stringValue()) {
                "snapshot" -> {
                    val raw = objectValue["announcements"] as? JsonArray ?: return emptyList()
                    listOf(AnnouncementEvent.Snapshot(raw.mapNotNull(::decodeAnnouncement)))
                }

                "announcement" -> {
                    val element = objectValue["announcement"] ?: return emptyList()
                    listOfNotNull(decodeAnnouncement(element)?.let(AnnouncementEvent::Created))
                }

                else -> emptyList()
            }
        }

        /** Decode one `Announcement`, or `null` when the payload is not one. */
        fun decodeAnnouncement(element: JsonElement): Announcement? {
            if (element is JsonNull) return null
            return try {
                WIRE_JSON.decodeFromJsonElement(Announcement.serializer(), element)
            } catch (_: Exception) {
                null
            }
        }

        /** The banner a raw object is, for a mock or a preview. */
        fun announcementFromJson(element: JsonElement): Announcement? = decodeAnnouncement(element)
    }
}
