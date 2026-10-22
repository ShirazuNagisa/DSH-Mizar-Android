package me.shirazu.dshmizar.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.shirazu.mizar.kit.WIRE_JSON
import me.shirazu.mizar.kit.account.AccountSession
import me.shirazu.mizar.kit.account.AccountStoring
import java.io.IOException
import java.util.UUID

/**
 * The account session, in Keystore-backed storage.
 *
 * The token is a credential, so it lives where the pairing tokens live rather
 * than in the settings preferences. Two deliberate choices:
 *
 *  - **[save] commits, and throws when it fails.** A login that looks like it
 *    worked and is gone on the next launch is worse than one that says it failed.
 *  - **[installId] is not cleared by sign-out.** It is the device id the service
 *    counts against its two-device limit; regenerating it would spend that
 *    allowance on the same phone every time.
 */
class EncryptedAccountStore(context: Context) : AccountStoring {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "mizar-account",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Plain preferences: an install id is a label, not a secret. */
    private val installPrefs = context.getSharedPreferences("mizar-install", Context.MODE_PRIVATE)

    override val installId: String by lazy {
        installPrefs.getString(INSTALL_ID, null)?.takeIf { it.isNotBlank() } ?: run {
            val generated = UUID.randomUUID().toString().lowercase()
            installPrefs.edit().putString(INSTALL_ID, generated).commit()
            generated
        }
    }

    override suspend fun load(): AccountSession? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(KEY, null) ?: return@withContext null
        // A blob that will not decode reads as "signed out" rather than throwing:
        // the alternative is an app that cannot start until its data is cleared.
        runCatching { WIRE_JSON.decodeFromString(AccountSession.serializer(), stored) }
            .getOrNull()
            ?.takeIf { it.isUsable }
    }

    override suspend fun save(session: AccountSession) = withContext(Dispatchers.IO) {
        val body = WIRE_JSON.encodeToString(AccountSession.serializer(), session)
        if (!prefs.edit().putString(KEY, body).commit()) {
            throw IOException("the account session could not be written")
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY).commit()
        Unit
    }

    private companion object {
        const val KEY = "session"
        const val INSTALL_ID = "installId"
    }
}
