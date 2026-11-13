package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.shirazu.dshmizar.AccountMode
import me.shirazu.dshmizar.AccountPhase
import me.shirazu.dshmizar.PendingRegistration
import me.shirazu.dshmizar.R
import me.shirazu.mizar.kit.account.AccountApi
import me.shirazu.mizar.kit.account.AccountAuth
import me.shirazu.mizar.kit.account.AccountDevicePayload
import me.shirazu.mizar.kit.account.AccountError
import me.shirazu.mizar.kit.account.AccountSession
import me.shirazu.mizar.kit.account.AccountStoring
import me.shirazu.mizar.kit.account.AuthUri
import java.text.DateFormat
import java.util.Date

/**
 * Who this phone is signed in as: sign-in, registration, the reset, the invite and
 * the GitHub exchange.
 *
 * Every path that ends signed in goes through [adopt] -- a path that saved the token
 * but forgot to clear the code form, or one that showed the profile without saving
 * it, would look right until the next launch. The two things this domain cannot do
 * itself are handed back as calls: [onIdentityChanged] because the relay reads the
 * identity headers once at the upgrade, so signing in or out cannot be patched onto
 * a live socket, and [onPlanChanged] because a change of plan decides which relay the
 * host's tunnel should use. Both are the connection's business; this class only says
 * when they happened.
 */
internal class AccountCoordinator(
    private val store: StateStore,
    private val accounts: AccountStoring,
    private val api: AccountApi,
    private val scope: CoroutineScope,
    private val text: Text,
    private val deviceName: String,
    private val deviceModel: String,
    private val appVersion: String,
    /** Opens a URL; false when there is no browser to open it with. */
    private val openBrowser: (String) -> Boolean,
    private val onIdentityChanged: () -> Unit,
    private val onPlanChanged: () -> Unit,
    /** The account's computer directory, when the build carries one. */
    private val desktopsApi: me.shirazu.mizar.kit.account.AccountDesktopsApi? = null,
) {

    /**
     * Read the computers this account can open.
     *
     * Discovery is the relay's answer and costs one round trip; it is read when the
     * account screen opens rather than at launch, because a signed-out phone has
     * nothing to ask and a free account still works without a computer list.
     */
    fun loadDesktops() {
        val session = store.state.value.account.session ?: return
        val api = desktopsApi ?: return
        if (store.state.value.account.desktopsLoading) return
        store.update { it.copy(account = it.account.copy(desktopsLoading = true, desktopsError = null)) }
        scope.launch {
            try {
                val list = api.desktops(session.token)
                if (store.state.value.account.session?.token != session.token) return@launch
                store.update {
                    it.copy(account = it.account.copy(desktops = list, desktopsLoading = false))
                }
            } catch (error: AccountError) {
                if (store.state.value.account.session?.token != session.token) return@launch
                // A directory that cannot be read is not a sign-out: the cached list
                // stays, and the screen offers a retry.
                store.update {
                    it.copy(account = it.account.copy(desktopsLoading = false, desktopsError = error.message))
                }
            }
        }
    }

    /** Read the stored session and quietly refresh it. Never blocks a launch. */
    suspend fun load() {
        // Warm the install id off the main thread: the first read touches disk.
        withContext(Dispatchers.IO) { accounts.installId }
        val stored = runCatching { accounts.load() }.getOrNull()
        if (stored == null) {
            store.update { it.copy(account = it.account.copy(phase = AccountPhase.READY)) }
            return
        }
        store.update { it.copy(account = it.account.copy(phase = AccountPhase.READY, session = stored)) }
        // The refresh is deliberately not awaited: connecting to the computer must
        // not wait on an account service that may be slow or unreachable.
        scope.launch { refreshNow(silent = true) }
    }

    /** Pull the profile again; the account screen's retry and the foreground hook. */
    fun refresh() {
        scope.launch { refreshNow() }
    }

    /** A phone that comes back to the foreground asks once, quietly. */
    fun refreshOnForeground() {
        val account = store.state.value.account
        if (account.session == null || account.phase == AccountPhase.LOADING || account.working) return
        scope.launch { refreshNow(silent = true) }
    }

    private suspend fun refreshNow(silent: Boolean = false) {
        val session = store.state.value.account.session ?: return
        if (!silent) store.update { it.copy(account = it.account.copy(phase = AccountPhase.LOADING, error = null)) }
        try {
            val user = api.me(session.token, accounts.installId)
            // A sign-out may have landed while this was in flight; do not resurrect it.
            if (store.state.value.account.session?.token != session.token) return
            val updated = session.copy(profile = user, savedAtMillis = System.currentTimeMillis())
            val wasPaid = store.state.value.account.isPaid
            runCatching { accounts.save(updated) }
            store.update {
                it.copy(account = it.account.copy(phase = AccountPhase.READY, session = updated, error = null))
            }
            // The plan decides which relay the host's tunnel should use. A change in
            // either direction asks the host to move; nothing else here touches it.
            if (wasPaid != updated.profile?.isPaid) onPlanChanged()
        } catch (error: AccountError) {
            if (store.state.value.account.session?.token != session.token) return
            if (error.requiresReauthentication) {
                runCatching { accounts.clear() }
                store.update {
                    it.copy(account = it.account.copy(phase = AccountPhase.READY, session = null, notice = null))
                }
            } else {
                // Unreachable is not signed out: the cached profile stays on screen.
                store.update {
                    it.copy(account = it.account.copy(phase = AccountPhase.UNREACHABLE, error = error.message))
                }
            }
        }
    }

    fun openScreen() {
        store.update { it.copy(account = it.account.copy(screenOpen = true, error = null)) }
    }

    fun closeScreen() {
        store.update { it.copy(account = it.account.copy(screenOpen = false, error = null, handoffEntry = false)) }
    }

    fun setMode(mode: AccountMode) {
        store.update { it.copy(account = it.account.copy(mode = mode, error = null)) }
    }

    fun showHandoffEntry(show: Boolean) {
        store.update { it.copy(account = it.account.copy(handoffEntry = show, error = null)) }
    }

    fun openReset() {
        store.update {
            it.copy(account = it.account.copy(resetOpen = true, resetChallenge = null, error = null))
        }
    }

    fun closeReset() {
        store.update {
            it.copy(account = it.account.copy(resetOpen = false, resetChallenge = null, error = null))
        }
    }

    /**
     * Ask for a reset code.
     *
     * The service answers the same way for an address it does not know, so the
     * screen's copy says "if that address has an account" rather than confirming
     * one. That is the service's privacy decision, not a wording choice here.
     */
    fun startReset(email: String) {
        val address = email.trim()
        if (!address.contains('@') || address.startsWith('@') || address.endsWith('@')) {
            setError(text.get(R.string.account_error_email))
            return
        }
        if (store.state.value.account.working) return
        scope.launch {
            store.update { it.copy(account = it.account.copy(working = true, error = null)) }
            try {
                val challenge = api.resetStart(address)
                store.update {
                    it.copy(account = it.account.copy(working = false, resetChallenge = challenge))
                }
            } catch (error: Throwable) {
                store.update {
                    it.copy(account = it.account.copy(working = false, error = messageFor(error)))
                }
            }
        }
    }

    /** Finish the reset; the service signs this device in on the way through. */
    fun completeReset(code: String, password: String) {
        val challenge = store.state.value.account.resetChallenge ?: return
        if (password.length !in 8..128) {
            setError(text.get(R.string.account_error_password))
            return
        }
        if (code.trim().isEmpty()) {
            setError(text.get(R.string.account_error_code))
            return
        }
        authenticate { api.resetComplete(challenge.email, code.trim(), password, devicePayload()) }
    }

    fun clearError() {
        store.update { it.copy(account = it.account.copy(error = null)) }
    }

    /** Sign in with a username or an email. */
    fun login(identifier: String, password: String) {
        val name = identifier.trim()
        if (name.isEmpty() || password.isEmpty()) {
            setError(text.get(R.string.account_error_missing))
            return
        }
        authenticate { api.login(name, password, devicePayload()) }
    }

    fun register(username: String, email: String, password: String) {
        val name = username.trim()
        val address = email.trim()
        val problem = when {
            name.length !in 2..32 -> R.string.account_error_username
            name.any { it.isWhitespace() || it == '@' } -> R.string.account_error_username
            !address.contains('@') || address.startsWith('@') || address.endsWith('@') -> R.string.account_error_email
            password.length !in 8..128 -> R.string.account_error_password
            else -> null
        }
        if (problem != null) {
            setError(text.get(problem))
            return
        }
        // Two deployments, one build. A server with mail configured refuses this
        // without a code (428) and wants `/register/start` first; one without mail
        // accepts it here. Trying once is how the client tells them apart without a
        // capability endpoint neither service has.
        if (store.state.value.account.working) return
        scope.launch {
            store.update { it.copy(account = it.account.copy(working = true, error = null)) }
            try {
                adopt(api.register(name, address, password, devicePayload()))
            } catch (error: AccountError) {
                if (error.code != "EMAIL_VERIFICATION_REQUIRED") {
                    store.update {
                        it.copy(account = it.account.copy(working = false, error = messageFor(error)))
                    }
                    return@launch
                }
                try {
                    val challenge = api.registerStart(name, address, password, devicePayload())
                    store.update {
                        it.copy(
                            account = it.account.copy(
                                working = false,
                                error = null,
                                pendingRegistration = PendingRegistration(name, address, password),
                                registerChallenge = challenge,
                            ),
                        )
                    }
                } catch (startError: Throwable) {
                    store.update {
                        it.copy(account = it.account.copy(working = false, error = messageFor(startError)))
                    }
                }
            } catch (error: Throwable) {
                store.update {
                    it.copy(account = it.account.copy(working = false, error = messageFor(error)))
                }
            }
        }
    }

    /** Finish a registration whose code has arrived. */
    fun completeRegistration(code: String) {
        val pending = store.state.value.account.pendingRegistration ?: return
        if (code.trim().isEmpty()) {
            setError(text.get(R.string.account_error_code))
            return
        }
        authenticate {
            api.register(
                pending.username,
                pending.email,
                pending.password,
                devicePayload(),
                code.trim(),
            )
        }
    }

    /** Back out of the code step: nothing was created, so nothing is left behind. */
    fun cancelRegistration() {
        store.update {
            it.copy(account = it.account.copy(pendingRegistration = null, registerChallenge = null, error = null))
        }
    }

    /** One path for login, registration and the GitHub exchange. */
    private fun authenticate(block: suspend () -> AccountAuth) {
        if (store.state.value.account.working) return
        scope.launch {
            store.update { it.copy(account = it.account.copy(working = true, error = null)) }
            try {
                adopt(block())
            } catch (error: Throwable) {
                store.update {
                    it.copy(account = it.account.copy(working = false, error = messageFor(error)))
                }
            }
        }
    }

    /**
     * Take a session: save the token, show the profile, reconnect with identity.
     *
     * Shared by every path that ends signed in -- login, registration, the GitHub
     * exchange, a password reset -- because a path that forgot one of these would
     * look signed in until the next launch.
     */
    private suspend fun adopt(auth: AccountAuth) {
        val session = AccountSession(auth.token, auth.user, System.currentTimeMillis())
        accounts.save(session)
        store.update {
            it.copy(
                account = it.account.copy(
                    phase = AccountPhase.READY,
                    session = session,
                    notice = auth.notice,
                    working = false,
                    error = null,
                    mode = AccountMode.SIGN_IN,
                    handoffEntry = false,
                    resetOpen = false,
                    resetChallenge = null,
                    pendingRegistration = null,
                    registerChallenge = null,
                ),
            )
        }
        onIdentityChanged()
    }

    /** Open GitHub in the browser. With a session, this links instead of signing in. */
    fun beginGitHubSignIn(link: Boolean = false) {
        val session = store.state.value.account.session
        if (link && session == null) return
        if (store.state.value.account.working) return
        scope.launch {
            store.update { it.copy(account = it.account.copy(working = true, error = null)) }
            try {
                val start = api.githubStart(devicePayload(), if (link) session?.token else null)
                store.update { it.copy(account = it.account.copy(working = false)) }
                if (!openBrowser(start.authorizeUrl)) {
                    setError(text.get(R.string.account_error_no_browser))
                }
            } catch (error: Throwable) {
                store.update {
                    it.copy(account = it.account.copy(working = false, error = messageFor(error)))
                }
            }
        }
    }

    /** The landing page handed the app a code; trade it for a session. */
    fun handleAuthUri(uri: String) {
        val handoff = AuthUri.parse(uri) ?: return
        redeemHandoff(handoff)
    }

    fun redeemHandoff(handoff: String) {
        val code = handoff.trim()
        if (code.isEmpty()) return
        authenticate { api.githubExchange(code, devicePayload()) }
    }

    /** Add paid days to the signed-in account. */
    fun openRedeem() {
        store.update {
            it.copy(account = it.account.copy(redeemOpen = true, redeemNotice = null, error = null))
        }
    }

    fun closeRedeem() {
        store.update {
            it.copy(account = it.account.copy(redeemOpen = false, redeemNotice = null, error = null))
        }
    }

    /**
     * Turn a code into paid days.
     *
     * The service adds the days to whatever the account already has, so two codes in
     * a row stack; the answer is the whole profile, which is where the new end date
     * comes from. Saying that date out loud is the point of this function -- a
     * redeem that only changed a line further up the screen reads as nothing
     * happening.
     */
    fun redeemInvite(code: String) {
        val session = store.state.value.account.session ?: return
        val value = code.trim()
        if (value.isEmpty() || store.state.value.account.working) return
        scope.launch {
            store.update { it.copy(account = it.account.copy(working = true, error = null, redeemNotice = null)) }
            try {
                val user = api.redeem(session.token, value)
                val updated = session.copy(profile = user, savedAtMillis = System.currentTimeMillis())
                accounts.save(updated)
                val until = user.paidUntil ?: user.grantedPaidUntil
                val notice = until?.let {
                    text.get(
                        R.string.redeem_accepted,
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)),
                    )
                } ?: text.get(R.string.redeem_accepted_plain)
                store.update {
                    it.copy(
                        account = it.account.copy(
                            phase = AccountPhase.READY,
                            session = updated,
                            working = false,
                            redeemNotice = notice,
                        ),
                    )
                }
            } catch (error: Throwable) {
                store.update {
                    it.copy(account = it.account.copy(working = false, error = messageFor(error)))
                }
            }
        }
    }

    /** Sign out locally first; the service call is best effort. */
    fun logout() {
        val session = store.state.value.account.session ?: return
        scope.launch {
            runCatching { accounts.clear() }
            store.update {
                it.copy(
                    account = it.account.copy(
                        phase = AccountPhase.READY,
                        session = null,
                        notice = null,
                        error = null,
                        handoffEntry = false,
                    ),
                )
            }
            runCatching { api.logout(session.token) }
            onIdentityChanged()
        }
    }

    /** Delete the account; only clear the phone once the service has confirmed. */
    fun delete() {
        val session = store.state.value.account.session ?: return
        if (store.state.value.account.working) return
        scope.launch {
            store.update { it.copy(account = it.account.copy(working = true, error = null)) }
            try {
                api.delete(session.token)
            } catch (error: Throwable) {
                store.update {
                    it.copy(account = it.account.copy(working = false, error = messageFor(error)))
                }
                return@launch
            }
            runCatching { accounts.clear() }
            store.update {
                it.copy(
                    account = it.account.copy(
                        phase = AccountPhase.READY,
                        session = null,
                        notice = null,
                        working = false,
                        error = null,
                        handoffEntry = false,
                    ),
                )
            }
            onIdentityChanged()
        }
    }

    private fun devicePayload() = AccountDevicePayload(
        id = accounts.installId,
        name = deviceName,
        model = deviceModel,
        platform = "android",
        appVersion = appVersion,
    )

    private fun setError(message: String) {
        store.update { it.copy(account = it.account.copy(error = message)) }
    }

    private fun messageFor(error: Throwable): String = when (error) {
        is AccountError -> error.message
        else -> error.message ?: text.get(R.string.account_error_generic)
    }
}
