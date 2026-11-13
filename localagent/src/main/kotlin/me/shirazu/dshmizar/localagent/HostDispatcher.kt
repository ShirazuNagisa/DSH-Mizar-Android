package me.shirazu.dshmizar.localagent

/**
 * The one Java method the kernel's JavaScript may call.
 *
 * The JNI bridge (`src/main/cpp/jni_bridge.c`) exposes it to JavaScript as
 * `__mizarNativeCall(op, first, second)` and resolves this method by name and
 * signature, which is why it is a plain public method on a public class rather
 * than anything Kotlin-idiomatic: `internal` members get JVM name mangling.
 *
 * Every op answers one JSON envelope — `{"ok":true,"value":…}` or
 * `{"ok":false,"error":"…"}` — because the bridge converts only strings, and a
 * `null` value, an array and an error would otherwise be indistinguishable.
 * `assets/localagent-host.js` is the only caller and turns the envelope back
 * into the kernel's callback API.
 */
class HostDispatcher internal constructor(private val sandbox: MizarHostSandbox) {
    /**
     * Dispatch one host operation.
     *
     * Ops mirror `PlatformHost` in `iOS/LocalKernel/kernel/src/host/host.ts`:
     * `fs.readText`, `fs.writeText`, `fs.appendText`, `fs.deletePath`,
     * `fs.listDir`, `fs.isDirectory`, `fs.makeDir`, `kv.get`, `kv.set`,
     * `kv.delete`, `log`, `uuid`, `now`, `frame`.
     */
    fun call(op: String, first: String?, second: String?): String =
        sandbox.call(op, first.orEmpty(), second.orEmpty())
}
