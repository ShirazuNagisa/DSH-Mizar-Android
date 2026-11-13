package me.shirazu.dshmizar.localagent

/**
 * The JNI surface of the vendored QuickJS-NG engine.
 *
 * `src/main/cpp` holds the engine (QuickJS-NG 0.10.1, MIT), one JNI bridge, and
 * the `ndk-build` project that compiles them into `libmizarquickjs.so`. The
 * normal Gradle build only packages the prebuilt libraries under
 * `src/main/jniLibs/`; rebuilding them needs the NDK and is a separate,
 * explicit step (`scripts/build-quickjs.ps1`).
 *
 * Why a hand-written bridge instead of a Maven artifact: the published QuickJS
 * bindings (`app.cash.quickjs:quickjs-android:0.9.2`, `1.4.6` forks) never run
 * `JS_ExecutePendingJob`, so a promise-based kernel cannot boot on them, and
 * their 2021 `.so` files are 4 KB-aligned, which Android 15+ devices with 16 KB
 * pages refuse to load. See the module README and the PoC report.
 *
 * Public by necessity: JNI resolves these by exact class/method name, so Kotlin
 * `internal` (which mangles JVM names) would break the link. Nothing outside
 * this module should call it — [LocalKernelEngine] is the entry point.
 */
object QuickJsNative {
    init {
        System.loadLibrary("mizarquickjs")
    }

    /** Create a runtime + context; returns `0` on failure. */
    external fun create(): Long

    /**
     * Attach the object whose `call(op, first, second)` the JS host calls.
     *
     * The bridge keeps a global reference for the engine's lifetime and looks
     * the method up once, by the exact signature
     * `(String, String, String) -> String`.
     */
    external fun setDispatcher(handle: Long, dispatcher: HostDispatcher)

    /**
     * Evaluate [source] in the global scope and then drain the promise job
     * queue before returning.
     *
     * This drain is the whole point of the bridge: the kernel's `createKernel`
     * is async, so without it booting stops at the first `await`.
     *
     * Returns the last expression as a string (`undefined`/`null` become Java
     * `null`, objects fall back to their JS string form), or throws
     * `RuntimeException` when evaluation itself failed.
     */
    external fun evaluate(handle: Long, source: String, fileName: String): String?

    /** Free the context and runtime. */
    external fun close(handle: Long)
}
