package me.shirazu.mizar.kit.account

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import me.shirazu.mizar.kit.HttpRequest
import me.shirazu.mizar.kit.HttpResponse
import me.shirazu.mizar.kit.HttpTransport
import me.shirazu.mizar.kit.OkHttpHttpTransport
import me.shirazu.mizar.kit.WIRE_JSON
import java.io.IOException
import java.net.URI

/**
 * One account failure, in the shape the callers branch on.
 *
 * [code] is the service's `error` string whenever it sent one; the HTTP status is
 * only a fallback. That ordering matters: the service answers `INVITE_EXPIRED`
 * with 410 and `USERNAME_TAKEN` with 409, so a client that switched on status
 * would explain the wrong problem.
 */
class AccountError(
    val code: String,
    override val message: String,
    val status: Int = 0,
    val retryable: Boolean = false,
) : Exception(message) {

    /** Whether this failure means the stored session is no longer valid. */
    val requiresReauthentication: Boolean
        get() = status == 401 || code in REAUTHENTICATING

    private companion object {
        val REAUTHENTICATING = setOf("UNAUTHENTICATED", "SESSION_REVOKED", "ACCOUNT_DISABLED")
    }
}

/**
 * The account HTTP API.
 *
 * Same transport seam as [me.shirazu.mizar.kit.PairingClient]: every request goes
 * through [HttpTransport], so the interesting behaviour — which header is set,
 * whether a 401 is distinguishable from a 500 — is testable on the JVM without a
 * server.
 *
 * Nothing here is required for the app to work. Every free feature runs without a
 * token, so a failure in this class must never be able to break the rest.
 */
class AccountClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val transport: HttpTransport = OkHttpHttpTransport(),
    private val appVersion: String? = null,
) {

    suspend fun register(
        username: String,
        email: String,
        password: String,
        device: AccountDevicePayload,
    ): AccountAuth = decode(
        send(
            "POST", "/register", token = null,
            body = WIRE_JSON.encodeToString(RegisterBody.serializer(), RegisterBody(username, email, password, device)),
        ),
        AccountAuth.serializer(),
    )

    suspend fun login(
        identifier: String,
        password: String,
        device: AccountDevicePayload,
    ): AccountAuth = decode(
        send(
            "POST", "/login", token = null,
            body = WIRE_JSON.encodeToString(LoginBody.serializer(), LoginBody(identifier, password, device)),
        ),
        AccountAuth.serializer(),
    )

    /**
     * Begin a GitHub sign-in.
     *
     * With a [token] this is a *link* request: the browser flow ends by attaching
     * GitHub to that account instead of signing in. The token never leaves the
     * service's own store, and the APK carries neither a client id nor a secret.
     */
    suspend fun githubStart(device: AccountDevicePayload, token: String? = null): GithubStart = decode(
        send(
            "POST", "/github/start", token = token,
            body = WIRE_JSON.encodeToString(GithubStartBody.serializer(), GithubStartBody(device)),
        ),
        GithubStart.serializer(),
    )

    /** Trade the one-time handoff from the landing page for a real session. */
    suspend fun githubExchange(handoff: String, device: AccountDevicePayload): AccountAuth = decode(
        send(
            "POST", "/github/exchange", token = null,
            body = WIRE_JSON.encodeToString(
                GithubExchangeBody.serializer(),
                GithubExchangeBody(handoff, device),
            ),
        ),
        AccountAuth.serializer(),
    )

    /** The current profile; also the probe that tells a stale token from an outage. */
    suspend fun me(token: String, deviceId: String): AccountUser = decode(
        send("GET", "/me", token = token, extraHeaders = mapOf(CLIENT_ID_HEADER to deviceId)),
        UserEnvelope.serializer(),
    ).user

    suspend fun logout(token: String) {
        send("POST", "/logout", token = token, body = null)
    }

    suspend fun delete(token: String) {
        send(
            "POST", "/delete", token = token,
            body = WIRE_JSON.encodeToString(DeleteBody.serializer(), DeleteBody("DELETE")),
        )
    }

    suspend fun redeem(token: String, code: String): AccountUser = decode(
        send(
            "POST", "/invite/redeem", token = token,
            body = WIRE_JSON.encodeToString(RedeemBody.serializer(), RedeemBody(code)),
        ),
        UserEnvelope.serializer(),
    ).user

    /** The device object this install sends. */
    fun device(id: String, name: String, model: String): AccountDevicePayload =
        AccountDevicePayload(
            id = id,
            name = name,
            model = model,
            platform = "android",
            appVersion = appVersion ?: "0",
        )

    // ── transport ───────────────────────────────────────────────────────────

    private suspend fun send(
        method: String,
        path: String,
        token: String?,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val headers = buildMap {
            put("accept", "application/json")
            if (body != null) put("content-type", "application/json")
            token?.takeIf { it.isNotEmpty() }?.let { put("authorization", "Bearer $it") }
            putAll(extraHeaders)
        }
        val response = try {
            transport.send(
                HttpRequest(method = method, url = url(path), headers = headers, body = body),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            // A timeout and a dead network are the same code on purpose: both mean
            // "nothing was answered", and both keep the cached profile.
            throw AccountError("NETWORK", error.message ?: "the account service could not be reached", status = 0, retryable = true)
        } catch (error: Exception) {
            throw AccountError("NETWORK", error.message ?: "the account service could not be reached", status = 0, retryable = true)
        }
        if (response.status !in 200..299 || response.status == 204) throw failure(response)
        return response
    }

    private fun <T> decode(response: HttpResponse, serializer: KSerializer<T>): T = try {
        WIRE_JSON.decodeFromString(serializer, response.body)
    } catch (_: Exception) {
        throw AccountError("MALFORMED_RESPONSE", "the account service answered something this app could not read", response.status)
    }

    private fun failure(response: HttpResponse): AccountError {
        val reported = runCatching {
            WIRE_JSON.decodeFromString(ErrorBody.serializer(), response.body)
        }.getOrNull()?.takeIf { it.error.isNotBlank() }

        val code = reported?.error ?: when (response.status) {
            400 -> "INVALID_INPUT"
            401 -> "UNAUTHENTICATED"
            403 -> "ACCOUNT_DISABLED"
            404, 204 -> "MALFORMED_RESPONSE"
            429 -> "RATE_LIMITED"
            else -> if (response.status >= 500) "INTERNAL" else "INVALID_INPUT"
        }
        val message = reported?.message?.takeIf { it.isNotBlank() } ?: describe(code, response.status)
        return AccountError(
            code = code,
            message = message,
            status = response.status,
            retryable = code in RETRYABLE || response.status >= 500,
        )
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    companion object {
        const val DEFAULT_BASE_URL = "https://dshmizar-1.shirazu-nagisa.com/account/v1"
        const val CLIENT_ID_HEADER = "x-mizar-client-id"

        private val RETRYABLE = setOf(
            "RATE_LIMITED",
            "INTERNAL",
            "GITHUB_UNAVAILABLE",
            "GITHUB_EXCHANGE_FAILED",
        )

        /**
         * Whether a relay is the host that owns the account store.
         *
         * The paid relay verifies a session token; a free relay only meters by its
         * hash. Handing the token to anything but the account origin is handing out
         * a credential for no benefit, so this comparison decides where
         * `x-mizar-session` may go. Origin, not host: the AVD's mock account server
         * and its relay can share an address and differ only by port.
         */
        fun isAccountOrigin(relayBaseUrl: String?, accountBaseUrl: String = DEFAULT_BASE_URL): Boolean {
            val left = origin(relayBaseUrl ?: return false) ?: return false
            val right = origin(accountBaseUrl) ?: return false
            return left == right
        }

        private fun origin(value: String): String? {
            val parsed = try {
                URI(value)
            } catch (_: Exception) {
                return null
            }
            val host = parsed.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val scheme = parsed.scheme?.lowercase() ?: return null
            val port = if (parsed.port > 0) parsed.port else if (scheme == "https") 443 else 80
            return "$scheme://$host:$port"
        }

        private fun describe(code: String, status: Int): String = when (code) {
            "UNAUTHENTICATED" -> "that username or password was not accepted"
            "SESSION_REVOKED" -> "this phone was signed out"
            "ACCOUNT_DISABLED" -> "this account is disabled"
            "RATE_LIMITED" -> "too many attempts; try again in a few minutes"
            "USERNAME_TAKEN" -> "that username is already taken"
            "EMAIL_TAKEN" -> "that email is already registered"
            "INVITE_INVALID" -> "that invite code is not valid"
            "INVITE_EXPIRED" -> "that invite code has expired"
            "INVITE_USED_UP" -> "that invite code has been used up"
            "GITHUB_EMAIL_CONFLICT" -> "this email belongs to a password account; sign in with the password first"
            "GITHUB_ACCOUNT_CONFLICT" -> "this GitHub account is already linked to another account"
            "GITHUB_ALREADY_LINKED" -> "this account is already linked to a different GitHub account"
            "GITHUB_HANDOFF_EXPIRED" -> "that sign-in code has expired; start again"
            "GITHUB_STATE_EXPIRED" -> "that sign-in took too long; start again"
            "INTERNAL" -> "the account service had a problem"
            else -> "the account service refused the request ($status)"
        }
    }
}
