package me.shirazu.dshmizar.localagent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** The logcat tag every kernel line is written under. */
internal const val LOG_TAG = "LocalAgentPoC"

/**
 * One host, one sandbox, one operation router.
 *
 * Paths are `/`-separated and relative to the kernel root, exactly like the
 * contract says (`PlatformHost` in `iOS/LocalKernel/kernel/src/host/host.ts`);
 * anything that would leave the root is refused, never rewritten. This mirrors
 * the iOS implementation (`KernelRuntime.resolve`) so the same kernel sees the
 * same file system on both platforms.
 *
 * Every operation answers one JSON envelope — `{"ok":true,"value":…}` or
 * `{"ok":false,"error":"…"}` — because [HostDispatcher] crosses JNI, which can
 * carry only strings. The JavaScript side turns the envelope back into the
 * kernel's `cb.ok(value)` / `cb.fail({message})` pair.
 *
 * Every method runs on the engine thread (the JS engine is single-threaded and
 * calls these synchronously), so the blocking-file-I/O shape is safe here — but
 * only here: it means one slow read blocks every timer and frame delivery. The
 * report marks where that has to move off-thread before a real turn loop runs
 * against it.
 */
internal class MizarHostSandbox(
    private val root: File,
    private val kvStore: KernelKeyValueStore,
    private val listener: LocalKernelEngine.Listener,
) {

    init {
        // The kernel expects its root to exist; `makeDir(".")` would work too,
        // but a root that cannot be created should be visible before the first
        // read rather than as an empty directory.
        root.mkdirs()
    }

    // -- Routing --------------------------------------------------------------

    /**
     * Dispatch one host operation.
     *
     * The op names are the JavaScript shim's own vocabulary, not the kernel's:
     * `assets/localagent-host.js` is the only caller, and keeping the names
     * flat (`fs.readText`) is what lets the JNI bridge stay a single generic
     * callback instead of one native method per host function.
     *
     * The engine intercepts `frame`, `http.*` and `events.poll` before this
     * router sees them; what remains is exactly the file-system/KV sandbox.
     */
    fun call(op: String, first: String, second: String): String = when (op) {
        "fs.readText" -> fsReadText(first)
        "fs.writeText" -> fsWriteText(first, second)
        "fs.appendText" -> fsAppendText(first, second)
        "fs.deletePath" -> fsDeletePath(first)
        "fs.listDir" -> fsListDir(first)
        "fs.isDirectory" -> fsIsDirectory(first)
        "fs.makeDir" -> fsMakeDir(first)
        "kv.get" -> kvGet(first)
        "kv.set" -> kvSet(first, second)
        "kv.delete" -> kvDelete(first)
        "log" -> {
            log(first, second)
            ok()
        }

        "uuid" -> ok(uuid())
        "now" -> ok(now())

        else -> fail("the host bridge does not know the operation \"$op\"")
    }

    // -- Envelopes ------------------------------------------------------------

    private fun ok(value: Any? = null): String = Envelopes.ok(value)

    private fun fail(message: String): String = Envelopes.fail(message)

    private fun describe(error: Throwable): String = when (val message = error.message) {
        null -> error.javaClass.simpleName
        else -> message
    }

    /**
     * Resolve a kernel-named path inside the root, or `null` when it escapes.
     *
     * Component arithmetic rather than `canonicalFile.parentFile == root`: the
     * kernel resolves a path for every record it writes, and the pure form never
     * touches the file system. `..` at the root is a refusal, not a clamp — a
     * path the kernel did not mean to write must not silently land somewhere
     * else.
     */
    private fun resolve(path: String): File? {
        if (path.startsWith("/")) return null
        val parts = ArrayList<String>()
        for (component in path.split('/')) {
            when {
                component.isEmpty() || component == "." -> Unit
                component == ".." -> {
                    if (parts.isEmpty()) return null
                    parts.removeAt(parts.size - 1)
                }

                else -> parts.add(component)
            }
        }
        if (parts.isEmpty() && path != ".") return null
        return File(root, parts.joinToString("/"))
    }

    // -- fs -------------------------------------------------------------------

    private fun fsReadText(path: String): String {
        val file = resolve(path) ?: return fail("the path escapes the sandbox: $path")
        return try {
            // Mirrors iOS `try? String(contentsOf:)`: a path that is missing,
            // a directory, or unreadable reads as "no file" to the kernel.
            if (!file.isFile) ok(null) else ok(file.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            ok(null)
        }
    }

    private fun fsWriteText(path: String, data: String): String {
        val file = resolve(path) ?: return fail("the path escapes the sandbox: $path")
        return try {
            file.parentFile?.mkdirs()
            // Write-then-rename: the kernel treats `writeText` as atomic, and a
            // crash in the middle of `meta.json` must not truncate a session.
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(data, Charsets.UTF_8)
            if (!temporary.renameTo(file)) {
                temporary.delete()
                file.writeText(data, Charsets.UTF_8)
            }
            ok()
        } catch (error: Exception) {
            fail(describe(error))
        }
    }

    private fun fsAppendText(path: String, data: String): String {
        val file = resolve(path) ?: return fail("the path escapes the sandbox: $path")
        return try {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { stream ->
                stream.write(data.toByteArray(Charsets.UTF_8))
            }
            ok()
        } catch (error: Exception) {
            fail(describe(error))
        }
    }

    private fun fsDeletePath(path: String): String {
        val file = resolve(path) ?: return fail("the path escapes the sandbox: $path")
        // Deleting a missing file counts as deleted, exactly like iOS.
        file.delete()
        return ok()
    }

    private fun fsListDir(path: String): String {
        val file = resolve(path) ?: return fail("the path escapes the sandbox: $path")
        return try {
            val names = if (file.isDirectory) file.list() ?: emptyArray() else null
            ok(names?.let { JSONArray(it.toList()) })
        } catch (error: Exception) {
            fail(describe(error))
        }
    }

    private fun fsIsDirectory(path: String): String {
        val file = resolve(path) ?: return fail("the path escapes the sandbox: $path")
        return ok(file.isDirectory)
    }

    private fun fsMakeDir(path: String): String {
        val file = resolve(path) ?: return fail("the path escapes the sandbox: $path")
        return try {
            if (!file.isDirectory && !file.mkdirs()) fail("could not create $path") else ok()
        } catch (error: Exception) {
            fail(describe(error))
        }
    }

    // -- kv -------------------------------------------------------------------

    private fun kvGet(key: String): String = try {
        ok(kvStore.value(forKey = key))
    } catch (error: Exception) {
        fail(describe(error))
    }

    private fun kvSet(key: String, value: String): String = try {
        kvStore.set(value, forKey = key)
        ok(true)
    } catch (error: Exception) {
        fail(describe(error))
    }

    private fun kvDelete(key: String): String = try {
        kvStore.set(null, forKey = key)
        ok(true)
    } catch (error: Exception) {
        fail(describe(error))
    }

    // -- The synchronous surfaces --------------------------------------------

    private fun log(level: String, message: String) {
        try {
            listener.onLog(level, message)
            // logcat drops lines over ~4 KB; kernel messages can carry a stack.
            if (message.length <= 3000) {
                when (level) {
                    "debug" -> Log.d(LOG_TAG, "[kernel] $message")
                    "info" -> Log.i(LOG_TAG, "[kernel] $message")
                    "warn" -> Log.w(LOG_TAG, "[kernel] $message")
                    else -> Log.e(LOG_TAG, "[kernel] $message")
                }
            } else {
                for (offset in message.indices step 3000) {
                    val end = minOf(offset + 3000, message.length)
                    Log.i(LOG_TAG, "[kernel] ${message.substring(offset, end)}")
                }
            }
        } catch (error: Exception) {
            // A log sink must never fail the kernel call that produced it.
            Log.e(LOG_TAG, "host log sink threw: ${describe(error)}")
        }
    }

    private fun uuid(): String = UUID.randomUUID().toString().lowercase()

    private fun now(): Double = System.currentTimeMillis().toDouble()
}
