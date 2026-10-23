package me.shirazu.mizar.kit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * The two public relays, and how a pairing moves between them.
 *
 * A paid account's tunnel belongs on the account relay; a free account's on the
 * free relay. The host cannot know which one a phone's account is — it has no
 * account identity — so the phone asks the host to move (`relay.set`) and keeps
 * the base URL it stores in [PairedHost.baseUrl] in step with the answer.
 *
 * Two rules keep that safe:
 *
 *  1. **Only these two origins are selectable.** The host owns the same allowlist;
 *     this side never asks for anywhere else, and a self-hosted relay never moves.
 *  2. **The room is in the path, not the origin.** Moving relays keeps `/r/<room>`
 *     and only swaps the scheme and host, which is what lets an old stored URL be
 *     repaired by trying the other relay with the same room.
 */
object RelayTier {

    /** The free relay's origin. */
    const val FREE_BASE_URL = "https://dshmizar.shirazu-nagisa.com"

    /** The account (paid) relay's origin. */
    const val PAID_BASE_URL = "https://dshmizar-1.shirazu-nagisa.com"

    /** The origin the tunnel should use for an account. */
    fun baseUrl(paid: Boolean): String = if (paid) PAID_BASE_URL else FREE_BASE_URL

    /** Whether an origin is one of the two public relays. */
    fun isPublicRelay(value: String): Boolean =
        canonicalOrigin(value) == FREE_BASE_URL || canonicalOrigin(value) == PAID_BASE_URL

    /** Whether an origin is the account relay. */
    fun isPaidRelay(value: String): Boolean = canonicalOrigin(value) == PAID_BASE_URL

    /**
     * The scheme-and-host of a URL, lowercased, without a trailing slash.
     *
     * @returns the origin, or `null` when it does not parse or is not HTTP(S).
     */
    fun canonicalOrigin(value: String): String? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }

    /**
     * The other public relay, preserving the room path.
     *
     * Used as a fallback when a phone's stored relay URL is stale because the host
     * moved while the phone was offline. A URL outside the two public relays has no
     * alternate: a self-hosted relay is the only place that host can be.
     */
    fun alternate(baseUrl: String): String? {
        val origin = canonicalOrigin(baseUrl) ?: return null
        val other = when (origin) {
            FREE_BASE_URL -> PAID_BASE_URL
            PAID_BASE_URL -> FREE_BASE_URL
            else -> return null
        }
        val path = runCatching { URI(baseUrl.trim()).rawPath }.getOrNull().orEmpty()
        return other + path
    }

    /**
     * Rebase a room URL onto another relay origin, preserving its path.
     *
     * @param baseUrl an existing room URL, e.g. `https://dshmizar…/r/ROOM`.
     * @param ontoOrigin the relay origin to move it to.
     * @returns the room URL under that origin, or `null` when either does not parse.
     */
    fun rebased(baseUrl: String, ontoOrigin: String): String? {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return null
        val origin = runCatching { URI(ontoOrigin.trim()) }.getOrNull() ?: return null
        val scheme = origin.scheme ?: return null
        val host = origin.host ?: return null
        val port = if (origin.port > 0) ":${origin.port}" else ""
        return "$scheme://$host$port${uri.rawPath.orEmpty()}"
    }
}

/**
 * What a host says about its tunnel.
 *
 * @property url the relay origin the host is configured to use.
 * @property room the room its tunnel took, or `null` before the tunnel is up.
 * @property baseUrl the room URL a phone should dial, or `null` before the room exists.
 */
@Serializable
data class HostRelay(
    val url: String,
    val room: String? = null,
    @SerialName("baseURL") val baseUrl: String? = null,
)

/** The answer to `relay.set`: where the host moved, and the URL to dial now. */
@Serializable
data class RelaySetResult(
    val url: String,
    val room: String? = null,
    @SerialName("baseURL") val baseUrl: String? = null,
)
