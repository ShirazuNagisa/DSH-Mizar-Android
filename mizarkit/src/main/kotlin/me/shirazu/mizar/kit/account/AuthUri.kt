package me.shirazu.mizar.kit.account

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * `mizar://auth?handoff=…` and `mizar://github/login?code=…` — the two links the
 * GitHub landing page has handed back over the relay's lifetime.
 *
 * Parsing is deliberately forgiving in the same way [me.shirazu.mizar.kit.PairingUri]
 * is: the input arrives from a browser, so a malformed link returns `null`
 * instead of throwing, and the first occurrence of a repeated parameter wins.
 */
object AuthUri {

    const val SCHEME = "mizar"
    const val HOST = "auth"
    const val PREFIX = "mizar://auth"

    /** The relay 0.3.x spelling: `mizar://github/login?code=…`. */
    const val GITHUB_HOST = "github"
    const val GITHUB_PATH = "/login"

    /** The link for one handoff code. */
    fun build(handoff: String): String = "$PREFIX?handoff=${encode(handoff.trim())}"

    /** Whether this is an auth link at all, without insisting it is complete. */
    fun isAuth(input: String?): Boolean {
        if (input == null) return false
        val parsed = runCatching { URI(input) }.getOrNull() ?: return false
        if (parsed.scheme != SCHEME) return false
        return parsed.host == HOST || (parsed.host == GITHUB_HOST && parsed.path == GITHUB_PATH)
    }

    /** The handoff code, or `null` when the link is not one this app can use. */
    fun parse(input: String?): String? {
        if (!isAuth(input)) return null
        val parsed = runCatching { URI(input!!) }.getOrNull() ?: return null
        val query = parsed.rawQuery ?: return null
        val params = decodeQuery(query)
        val code = when (parsed.host) {
            GITHUB_HOST -> params["code"] ?: params["handoff"]
            else -> params["handoff"]
        }?.trim().orEmpty()
        if (code.isEmpty() || code.length > 64) return null
        if (!code.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return code
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private fun decodeQuery(query: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val index = pair.indexOf('=')
            val name = if (index < 0) pair else pair.substring(0, index)
            val value = if (index < 0) "" else pair.substring(index + 1)
            val key = runCatching { URLDecoder.decode(name, Charsets.UTF_8) }.getOrDefault(name)
            if (result.containsKey(key)) continue
            result[key] = runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)
        }
        return result
    }
}
