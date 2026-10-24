package me.shirazu.mizar.kit.account

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The account API's shapes, as the iOS client defines them.
 *
 * Two rules carry most of the meaning:
 *
 *  - **Every timestamp is epoch milliseconds.** Not seconds, not an ISO string:
 *    the service writes `createdAt: 1759000000000` and a reader that assumed
 *    seconds would place every account in 1970.
 *  - **Everything except `id`/`username` has a default.** A newer service that
 *    adds a field, or an older one that omits it, must still decode; an unknown
 *    `plan` degrades to `free` rather than failing the whole profile.
 */
@Serializable
data class AccountUser(
    val id: String,
    val username: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    /** The service computes this: `"free"` or `"paid"`. Anything else reads as free. */
    val plan: String = "free",
    val paidUntil: Long? = null,
    val paidSources: List<String> = emptyList(),
    val grantedPaidUntil: Long? = null,
    val appStore: AccountAppStore? = null,
    val avatar: AccountAvatar = AccountAvatar(),
    /** The wire key is `apple`, not `appleLinked`. */
    val apple: Boolean = false,
    val github: Boolean = false,
    val githubLogin: String? = null,
    val createdAt: Long = 0,
    val lastSeenAt: Long = 0,
    val devices: List<AccountDevice> = emptyList(),
) {
    val isPaid: Boolean get() = plan == "paid"

    /** The initials the avatar shows, falling back to the username's first letter. */
    val initials: String
        get() = avatar.initials.trim().takeIf { it.isNotEmpty() }
            ?: username.trim().take(1).uppercase()
}

@Serializable
data class AccountDevice(
    val id: String,
    val name: String = "",
    val model: String = "",
    val platform: String = "",
    val loginAt: Long = 0,
    val lastSeenAt: Long = 0,
    val current: Boolean = false,
)

@Serializable
data class AccountAvatar(
    val kind: String = "generated",
    val url: String? = null,
    val colors: List<String> = emptyList(),
    val initials: String = "",
)

@Serializable
data class AccountAppStore(
    val paidUntil: Long? = null,
    val productId: String = "",
    val environment: String = "",
    val autoRenew: Boolean = false,
    val revokedAt: Long? = null,
)

@Serializable
data class AccountNotice(
    val code: String,
    /** The device that was signed out to make room, when [code] is `DEVICE_LIMIT`. */
    val revokedDevice: String? = null,
)

/** What `/login`, `/register` and `/github/exchange` answer. */
@Serializable
data class AccountAuth(
    val token: String,
    val user: AccountUser,
    val notice: AccountNotice? = null,
)

/** What is kept on the phone: the token and the last profile it came with. */
@Serializable
data class AccountSession(
    val token: String,
    val profile: AccountUser,
    val savedAtMillis: Long = 0,
) {
    val isUsable: Boolean get() = token.startsWith("mza_") && token.length >= 20
}

/**
 * The device object every sign-in sends.
 *
 * `platform` has no default on purpose: the client is the only thing that knows
 * it, and `WIRE_JSON` omits values equal to their defaults, so a default here
 * would silently drop the field from the request.
 */
@Serializable
data class AccountDevicePayload(
    val id: String,
    val name: String,
    val model: String,
    val platform: String,
    val appVersion: String,
)

/** What `/github/start` answers. */
@Serializable
data class GithubStart(
    val authorizeUrl: String,
    val state: String = "",
    val expiresAt: Long = 0,
)

// ── request bodies ──────────────────────────────────────────────────────────

@Serializable
internal data class RegisterBody(
    val username: String,
    val email: String,
    val password: String,
    val device: AccountDevicePayload,
)

/**
 * What a reset has to tell the screen: which address the code went to, when it
 * stops working, and how long before another may be asked for.
 *
 * The service answers the same way for an address it does not know, so a caller
 * cannot use this to find out whether an account exists -- which is why the screen
 * says "if that address has an account" rather than confirming one.
 */
data class ResetChallenge(
    val email: String,
    val expiresAt: Long,
    val resendAfterSeconds: Int,
)

@Serializable
internal data class EmailBody(val email: String)

@Serializable
internal data class ResetStartEnvelope(
    val expiresAt: Long = 0,
    val resendAfterSeconds: Int = 60,
)

@Serializable
internal data class ResetBody(
    val email: String,
    val code: String,
    val password: String,
    val device: AccountDevicePayload,
)

@Serializable
internal data class LoginBody(
    val identifier: String,
    val password: String,
    val device: AccountDevicePayload,
)

@Serializable
internal data class GithubStartBody(val device: AccountDevicePayload)

@Serializable
internal data class GithubExchangeBody(
    val handoff: String,
    val device: AccountDevicePayload,
)

@Serializable
internal data class RedeemBody(val code: String)

/** No default: `encodeDefaults = false` would drop a value that matched one. */
@Serializable
internal data class DeleteBody(val confirmation: String)

// ── envelopes ───────────────────────────────────────────────────────────────

/** `/me` and `/invite/redeem` wrap the user rather than returning it bare. */
@Serializable
internal data class UserEnvelope(val user: AccountUser)

/** `{"error": "…", "message": "…"}` — the code is what callers branch on. */
@Serializable
internal data class ErrorBody(
    @SerialName("error") val error: String = "",
    val message: String? = null,
)
