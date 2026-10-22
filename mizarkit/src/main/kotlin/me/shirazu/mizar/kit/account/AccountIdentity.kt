package me.shirazu.mizar.kit.account

import java.security.MessageDigest

/**
 * Who a relayed request is from.
 *
 * The relay meters traffic per identity and reads it from two headers:
 *
 *  - `x-mizar-client-id` — an install id, `[A-Za-z0-9._-]{8,64}`;
 *  - `x-mizar-account` — the **SHA-256** of the account token, 64 lowercase hex.
 *
 * The token itself is deliberately not in either. The relay needs a stable label
 * for a bucket, not a credential it could replay; a hash gives it the label and
 * gives a leaked relay log nothing it can authenticate with.
 *
 * The paid relay is the one exception: it has to know whether the account behind
 * a session is paying, and only the token can answer that. So `x-mizar-session`
 * carries the token, and only to the origin that owns the account store. All
 * three are stripped by the relay before the request reaches the computer.
 */
object AccountIdentity {

    const val CLIENT_ID_HEADER = "x-mizar-client-id"
    const val ACCOUNT_HEADER = "x-mizar-account"
    const val SESSION_HEADER = "x-mizar-session"

    private val INSTALL_ID = Regex("^[A-Za-z0-9._-]{8,64}$")

    /**
     * The headers for one relay connection.
     *
     * A direct (LAN) socket gets none: there is no relay to meter it, and the
     * computer has no business seeing an account hash.
     */
    fun headers(
        installId: String,
        token: String?,
        relayBaseUrl: String?,
        accountBaseUrl: String = AccountClient.DEFAULT_BASE_URL,
    ): Map<String, String> {
        if (relayBaseUrl.isNullOrBlank()) return emptyMap()
        val headers = LinkedHashMap<String, String>()
        if (INSTALL_ID.matches(installId)) headers[CLIENT_ID_HEADER] = installId
        val current = token?.takeIf { it.isNotEmpty() } ?: return headers
        headers[ACCOUNT_HEADER] = sha256Hex(current)
        if (AccountClient.isAccountOrigin(relayBaseUrl, accountBaseUrl)) {
            headers[SESSION_HEADER] = current
        }
        return headers
    }

    /** Lowercase hex SHA-256, as the relay expects it. */
    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val number = byte.toInt() and 0xff
            out.append(HEX[number ushr 4]).append(HEX[number and 0x0f])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
