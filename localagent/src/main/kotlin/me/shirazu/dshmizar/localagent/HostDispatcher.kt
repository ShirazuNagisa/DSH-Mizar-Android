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
 *
 * [route] is where the answer is decided. The engine owns the interesting cases
 * (a protocol frame the kernel pushed, a streaming HTTP request, the event queue);
 * everything else is the file-system/KV sandbox.
 */
class HostDispatcher internal constructor(private val route: (String, String, String) -> String) {
    /**
     * Dispatch one host operation.
     *
     * Ops mirror `PlatformHost` in `iOS/LocalKernel/kernel/src/host/host.ts`:
     * `fs.*`, `kv.*`, `http.*`, `log`, `uuid`, `now`, plus the two engine-owned
     * ones — `frame` (a protocol frame for a connection) and `events.poll` (host
     * work finished off the JS thread).
     */
    fun call(op: String, first: String?, second: String?): String =
        route(op, first.orEmpty(), second.orEmpty())
}
