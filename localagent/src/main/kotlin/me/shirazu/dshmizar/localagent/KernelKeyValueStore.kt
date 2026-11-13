package me.shirazu.dshmizar.localagent

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * The kernel's small synchronous string store, mirroring iOS's
 * `KernelKeyValueStore` protocol.
 *
 * The surface carries provider API keys (`apiKey.<provider>`) and the kernel's
 * own identity (`kernel.hostId`, `kernel.deviceId`), which is why iOS puts it in
 * the Keychain. Android's counterpart should be
 * `EncryptedSharedPreferences` — this PoC deliberately stays on the plain store
 * so the engine question can be answered without dragging `androidx.security`
 * into the experiment; the interface exists so the app can inject the encrypted
 * store when the module is wired in for real (see README.md §Risks).
 */
interface KernelKeyValueStore {
    fun value(forKey: String): String?

    /** `null` deletes the key, like the iOS store. */
    fun set(value: String?, forKey: String)
}

/**
 * The PoC's store: one `SharedPreferences` file under the `mizar.kernel.` prefix
 * iOS uses, so a key's identity does not depend on which platform holds it.
 *
 * Writes use `commit()` on purpose: the host API is synchronous, and the kernel
 * treats a `kv.set` that returned as durable. The values are small (keys and
 * ids), so the call is cheap; a real deployment swaps the whole class out.
 */
class SharedPreferencesKernelKeyValueStore(
    context: Context,
    private val prefix: String = "mizar.kernel.",
) : KernelKeyValueStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences("localagent-kernel", Context.MODE_PRIVATE)

    override fun value(forKey: String): String? = try {
        preferences.getString(prefix + forKey, null)
    } catch (error: Exception) {
        Log.w(LOG_TAG, "kv.get($forKey) failed: ${error.message}")
        null
    }

    override fun set(value: String?, forKey: String) {
        val editor = preferences.edit()
        if (value == null) editor.remove(prefix + forKey) else editor.putString(prefix + forKey, value)
        editor.commit()
    }
}
