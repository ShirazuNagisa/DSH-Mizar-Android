package me.shirazu.mizar.kit.account

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * A computer the account can reach.
 *
 * Discovery belongs to the relay, not the phone: a computer is online because its
 * tunnel is up, and [room] is where that tunnel answers. The token is minted by the
 * computer itself, at the relay's request, when a phone asks to connect — it is the
 * same kind of credential a pairing code issues, so the rest of the client treats it
 * the same way. Mirrors `iOS/MizarKit/Sources/MizarKit/AccountDesktop.swift`.
 */
@Serializable
data class AccountDesktop(
    val deviceId: String,
    val name: String,
    val model: String = "",
    val platform: String = "",
    val online: Boolean = false,
    val room: String? = null,
)

/** What `/account/v1/connect` hands back: where the computer is, and this phone's token. */
@Serializable
data class DesktopConnection(
    val room: String,
    val token: String,
)

/** The account's computer directory, and the broker for a phone's token. */
interface AccountDesktopsApi {

    /** Every computer on this account, offline ones included. */
    suspend fun desktops(token: String): List<AccountDesktop>

    /**
     * Ask a connected computer for a token this phone may use.
     *
     * A computer that is offline, or not on this account, is refused with a code;
     * the relay only brokers the request.
     */
    suspend fun connectToDesktop(
        token: String,
        deviceId: String,
        engine: String? = null,
    ): DesktopConnection
}

/**
 * Where a room answers, derived from the account API's origin.
 *
 * `https://host/account/v1` becomes `https://host/r/<room>/`, which is the same
 * shape a paired host stores, so the ordinary connection loop dials it unchanged.
 * Ports are preserved: the AVD's mock account server and its relay can share an
 * address and differ only by port.
 */
fun relayRoomUrl(accountBaseUrl: String, room: String): String? {
    val origin = runCatching {
        val uri = URI(accountBaseUrl.trim())
        val scheme = uri.scheme ?: return@runCatching null
        val authority = uri.rawAuthority ?: return@runCatching null
        "$scheme://$authority"
    }.getOrNull() ?: return null
    val cleaned = room.trim().trim('/')
    if (cleaned.isEmpty()) return null
    return "$origin/r/$cleaned/"
}

/** The request body of `POST /account/v1/connect`. */
@Serializable
internal data class ConnectDesktopBody(
    val deviceId: String,
    val engine: String? = null,
)

/** The envelope `GET /account/v1/desktops` answers with. */
@Serializable
internal data class DesktopsEnvelope(
    @SerialName("desktops") val desktops: List<AccountDesktop> = emptyList(),
)
