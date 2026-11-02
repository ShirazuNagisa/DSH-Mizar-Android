package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import me.shirazu.dshmizar.AccountPhase
import me.shirazu.dshmizar.AccountUiState
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.account.AccountApi
import me.shirazu.mizar.kit.account.AccountAuth
import me.shirazu.mizar.kit.account.AccountDevicePayload
import me.shirazu.mizar.kit.account.AccountError
import me.shirazu.mizar.kit.account.AccountSession
import me.shirazu.mizar.kit.account.AccountStoring
import me.shirazu.mizar.kit.account.AccountUser
import me.shirazu.mizar.kit.account.EmailChallenge
import me.shirazu.mizar.kit.account.GithubStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account domain, without a server.
 *
 * These flows had no test at all: the account screen's device tests draw a state
 * they are handed, so nothing ever signed in, signed out or refreshed. What is
 * pinned here is the part that is easy to get subtly wrong and impossible to notice
 * by looking -- that the phone is cleared *before* the service is told, that a reply
 * which arrives after a sign-out cannot resurrect it, and that an unreachable
 * service is not the same thing as an expired token.
 */
class AccountCoordinatorTest {

    private class Fixture(initial: AccountSession? = null) {
        val events = mutableListOf<String>()
        val api = FakeApi(events)
        val store = FakeStore(events, initial)
        val state = MutableStateFlow(
            UiState(account = AccountUiState(phase = AccountPhase.READY, session = initial)),
        )
        val coordinator = AccountCoordinator(
            store = FlowStateStore(state),
            accounts = store,
            api = api,
            // Unconfined, and every fake answers without suspending: a call has
            // finished by the time it returns, which is what the assertions assume.
            scope = CoroutineScope(Dispatchers.Unconfined),
            text = MarkerText(),
            deviceName = "Google Pixel",
            deviceModel = "Pixel",
            appVersion = "0.10.3",
            openBrowser = { events += "browser"; true },
            onIdentityChanged = { events += "identity" },
            onPlanChanged = { events += "plan" },
        )

        val account get() = state.value.account
    }

    private class MarkerText : Text {
        override fun get(id: Int): String = "res:$id"
        override fun get(id: Int, vararg args: Any): String = "res:$id:" + args.joinToString(",")
    }

    private class FakeStore(
        private val events: MutableList<String>,
        var stored: AccountSession? = null,
    ) : AccountStoring {
        override val installId: String = "device-1"

        override suspend fun load(): AccountSession? = stored

        override suspend fun save(session: AccountSession) {
            events += "save"
            stored = session
        }

        override suspend fun clear() {
            events += "clear"
            stored = null
        }
    }

    private class FakeApi(private val events: MutableList<String>) : AccountApi {
        var user = AccountUser(id = "u1", username = "shirazu")
        var auth = AccountAuth(token = TOKEN, user = user)
        var challenge = EmailChallenge(email = "a@b.com", expiresAt = 0, resendAfterSeconds = 30)
        var redeemed: AccountUser? = null

        var loginFailure: Throwable? = null
        var registerFailure: Throwable? = null
        var registerStartFailure: Throwable? = null
        var meFailure: Throwable? = null
        var deleteFailure: Throwable? = null

        /** When set, [me] waits on it: the window a sign-out can land in. */
        var meGate: CompletableDeferred<Unit>? = null

        override suspend fun login(identifier: String, password: String, device: AccountDevicePayload): AccountAuth {
            events += "login"
            loginFailure?.let { throw it }
            return auth
        }

        override suspend fun register(
            username: String,
            email: String,
            password: String,
            device: AccountDevicePayload,
            code: String?,
        ): AccountAuth {
            events += "register"
            registerFailure?.let { throw it }
            return auth
        }

        override suspend fun registerStart(
            username: String,
            email: String,
            password: String,
            device: AccountDevicePayload,
        ): EmailChallenge {
            events += "registerStart"
            registerStartFailure?.let { throw it }
            return challenge
        }

        override suspend fun resetStart(email: String): EmailChallenge {
            events += "resetStart"
            return challenge
        }

        override suspend fun resetComplete(
            email: String,
            code: String,
            password: String,
            device: AccountDevicePayload,
        ): AccountAuth {
            events += "resetComplete"
            return auth
        }

        override suspend fun githubStart(device: AccountDevicePayload, token: String?): GithubStart {
            events += "githubStart"
            return GithubStart(authorizeUrl = "https://github.test/authorize")
        }

        override suspend fun githubExchange(handoff: String, device: AccountDevicePayload): AccountAuth {
            events += "githubExchange"
            return auth
        }

        override suspend fun me(token: String, deviceId: String): AccountUser {
            events += "me"
            meGate?.await()
            meFailure?.let { throw it }
            return user
        }

        override suspend fun logout(token: String) {
            events += "logout"
        }

        override suspend fun delete(token: String) {
            events += "delete"
            deleteFailure?.let { throw it }
        }

        override suspend fun redeem(token: String, code: String): AccountUser {
            events += "redeem"
            return redeemed ?: user
        }
    }

    @Test
    fun `signing in saves the session, shows it, and asks for a redial`() {
        val f = Fixture()

        f.coordinator.login("shirazu", "password123")

        assertEquals(TOKEN, f.store.stored?.token)
        assertEquals(TOKEN, f.account.session?.token)
        assertEquals(false, f.account.working)
        assertNull(f.account.error)
        // The relay reads the identity once, at the upgrade: without this the phone
        // would look signed in while the tunnel still carried the old identity.
        assertEquals(listOf("login", "save", "identity"), f.events)
    }

    @Test
    fun `a rejected sign-in says why and saves nothing`() {
        val f = Fixture()
        f.api.loginFailure = AccountError("INVALID_CREDENTIALS", "Wrong password.", 401)

        f.coordinator.login("shirazu", "password123")

        assertEquals("Wrong password.", f.account.error)
        assertNull(f.account.session)
        assertNull(f.store.stored)
        assertEquals(false, f.account.working)
        assertTrue("identity must not change", "identity" !in f.events)
    }

    @Test
    fun `an empty password never reaches the service`() {
        val f = Fixture()

        f.coordinator.login("shirazu", "")

        assertEquals("res:${R.string.account_error_missing}", f.account.error)
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `signing out clears the phone before the service is told`() {
        val f = Fixture(session())

        f.coordinator.logout()

        // Order is the point: a network call first would leave the token on the
        // phone when the service is unreachable, which is the case that matters.
        assertEquals(listOf("clear", "logout", "identity"), f.events)
        assertNull(f.account.session)
        assertNull(f.store.stored)
    }

    @Test
    fun `a profile that lands after sign-out does not resurrect the session`() {
        val f = Fixture(session())
        val gate = CompletableDeferred<Unit>()
        f.api.meGate = gate

        f.coordinator.refresh()
        f.coordinator.logout()
        gate.complete(Unit)

        assertNull(f.account.session)
        assertNull(f.store.stored)
    }

    @Test
    fun `an expired token signs the phone out instead of showing an error`() {
        val f = Fixture(session())
        f.api.meFailure = AccountError("SESSION_REVOKED", "Session revoked.", 401)

        f.coordinator.refresh()

        assertNull(f.account.session)
        assertNull(f.store.stored)
        assertEquals(AccountPhase.READY, f.account.phase)
        assertTrue("clear" in f.events)
    }

    @Test
    fun `an unreachable service keeps the cached profile`() {
        val f = Fixture(session())
        f.api.meFailure = AccountError("NETWORK", "No connection.", 0)

        f.coordinator.refresh()

        assertEquals(TOKEN, f.account.session?.token)
        assertEquals(AccountPhase.UNREACHABLE, f.account.phase)
        assertEquals("No connection.", f.account.error)
        assertTrue("nothing to clear", "clear" !in f.events)
    }

    @Test
    fun `deleting keeps the phone signed in when the service refuses`() {
        val f = Fixture(session())
        f.api.deleteFailure = AccountError("SERVER", "Try again later.", 500)

        f.coordinator.delete()

        assertEquals(TOKEN, f.account.session?.token)
        assertEquals("Try again later.", f.account.error)
        assertEquals(false, f.account.working)
        assertTrue("nothing to clear", "clear" !in f.events)
    }

    @Test
    fun `deleting clears the phone only after the service confirms`() {
        val f = Fixture(session())

        f.coordinator.delete()

        assertEquals(listOf("delete", "clear", "identity"), f.events)
        assertNull(f.account.session)
    }

    @Test
    fun `a server that wants a code is asked for one and the form remembers what was typed`() {
        val f = Fixture()
        f.api.registerFailure = AccountError("EMAIL_VERIFICATION_REQUIRED", "Code required.", 428)

        f.coordinator.register("shirazu", "a@b.com", "password123")

        assertEquals("a@b.com", f.account.pendingRegistration?.email)
        assertEquals("shirazu", f.account.pendingRegistration?.username)
        assertNotNull(f.account.registerChallenge)
        assertEquals(false, f.account.working)
        assertNull(f.account.error)
        assertEquals(listOf("register", "registerStart"), f.events)
    }

    @Test
    fun `redeeming a code says the date the account now runs to`() {
        val f = Fixture(session())
        val until = 1_800_000_000_000L
        f.api.redeemed = AccountUser(id = "u1", username = "shirazu", plan = "paid", paidUntil = until)

        f.coordinator.redeemInvite("CODE-1")

        val notice = f.account.redeemNotice ?: error("no notice")
        assertTrue(
            "the notice must name the end date, not just say it worked: $notice",
            notice.startsWith("res:${R.string.redeem_accepted}:"),
        )
        assertEquals("paid", f.account.session?.profile?.plan)
        assertEquals("save", f.events.last())
    }

    private companion object {
        const val TOKEN = "mza_0123456789abcdef0123456789"

        fun session() = AccountSession(
            token = TOKEN,
            profile = AccountUser(id = "u1", username = "shirazu"),
        )
    }
}
