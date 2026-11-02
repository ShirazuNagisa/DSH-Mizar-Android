package me.shirazu.mizar.kit.account

/**
 * What the app asks the account service.
 *
 * The client itself is concrete and talks HTTP; this is the shape of it, so the
 * coordinator that owns sign-in, registration, the reset and the invite can be run
 * without a server. Those flows are the part of the app with no other way to test
 * them: the account screen's UI tests draw a state, they do not sign anyone in.
 */
interface AccountApi {

    suspend fun register(
        username: String,
        email: String,
        password: String,
        device: AccountDevicePayload,
        code: String? = null,
    ): AccountAuth

    suspend fun registerStart(
        username: String,
        email: String,
        password: String,
        device: AccountDevicePayload,
    ): EmailChallenge

    suspend fun login(
        identifier: String,
        password: String,
        device: AccountDevicePayload,
    ): AccountAuth

    suspend fun resetStart(email: String): EmailChallenge

    suspend fun resetComplete(
        email: String,
        code: String,
        password: String,
        device: AccountDevicePayload,
    ): AccountAuth

    suspend fun githubStart(device: AccountDevicePayload, token: String? = null): GithubStart

    suspend fun githubExchange(handoff: String, device: AccountDevicePayload): AccountAuth

    suspend fun me(token: String, deviceId: String): AccountUser

    suspend fun logout(token: String)

    suspend fun delete(token: String)

    suspend fun redeem(token: String, code: String): AccountUser
}
