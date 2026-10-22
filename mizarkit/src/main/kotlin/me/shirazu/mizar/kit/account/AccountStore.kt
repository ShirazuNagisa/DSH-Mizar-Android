package me.shirazu.mizar.kit.account

/**
 * Where the account session lives.
 *
 * The interface sits in `mizarkit` so the protocol layer can be tested without
 * Android; the encrypted implementation lives in the app module.
 *
 * Two properties are not negotiable:
 *
 *  - **[save] throws when the write fails.** A login that appears to work and
 *    disappears on the next launch is worse than a login that fails.
 *  - **[installId] survives sign-out.** It is the device id the service counts
 *    against the two-device limit; regenerating it on every sign-in would spend
 *    that allowance on the same phone over and over.
 */
interface AccountStoring {
    val installId: String

    suspend fun load(): AccountSession?

    suspend fun save(session: AccountSession)

    suspend fun clear()
}

/** A store for tests, and for a build that has no Android keystore. */
class InMemoryAccountStore(
    session: AccountSession? = null,
    override val installId: String = "00000000-0000-4000-8000-000000000000",
) : AccountStoring {

    @Volatile
    private var stored: AccountSession? = session

    override suspend fun load(): AccountSession? = stored

    override suspend fun save(session: AccountSession) {
        stored = session
    }

    override suspend fun clear() {
        stored = null
    }
}
