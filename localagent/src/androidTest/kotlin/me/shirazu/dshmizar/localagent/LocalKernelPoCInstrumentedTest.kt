package me.shirazu.dshmizar.localagent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.shirazu.dshmizar.localagent.poc.PoCResultsActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/**
 * The local agent PoC, run on an emulator or a phone.
 *
 * What it proves, in one run:
 *
 *  1. `app.cash.quickjs:quickjs-android` loads its native library on this ABI
 *     and API level, and evaluates ES2020 (the kernel bundle's target) —
 *     including promise jobs, which the kernel's async boot depends on.
 *  2. The kernel bundle in `assets/` is the iOS one (same bytes, same
 *     `MizarKernel` global) and `createKernel({host})` boots against the
 *     Android host bridge, creating its sandbox, workspace and identity.
 *  3. A client handshake (`hello` -> `welcome`) and two `kernel.*` methods
 *     answer through the protocol frames — the PoC's end-to-end path.
 *  4. Real numbers for the report: boot time, per-call latency, native/java
 *     heap, PSS, and the device's page size (the 16 KB compatibility question).
 *
 * Evidence lands in the app's external files dir and in logcat under
 * `LocalAgentPoC`; the last step puts the same summary on screen for a
 * screenshot.
 */
@RunWith(AndroidJUnit4::class)
class LocalKernelPoCInstrumentedTest {

    private lateinit var context: Context
    private lateinit var engine: LocalKernelEngine

    private val summaryLines = ArrayList<String>()

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        engine = LocalKernelEngine(context = context)
    }

    @After
    fun tearDown() {
        engine.close()
    }

    @Test
    fun runsTheIosKernelOnQuickJsAndAnswersKernelMethods() {
        // -- Boot ------------------------------------------------------------
        val boot = engine.boot()
        summaryLines += "Mizar local agent — Android PoC (2026-11-13)"
        summaryLines += "Device: ${Build.MODEL} • API ${Build.VERSION.SDK_INT} • ${Build.SUPPORTED_ABIS.firstOrNull()} • page ${pageSizeLabel()}"
        summaryLines += "Engine: ${boot.engine}"
        summaryLines += "Kernel bundle: ${boot.kernelBundleBytes} B (same bytes as iOS dist/mizar-kernel.js)"
        summaryLines += "Boot: ready=${boot.ready} in ${boot.durationMs} ms (bundle eval ${boot.bundleEvalMs} ms)"
        summaryLines += "Promise jobs drained on evaluate: ${boot.microtasksDrainedOnEvaluate}"
        summaryLines += "Sandbox root: ${boot.rootPath}"
        assertTrue("the kernel booted: ${boot.bootError}", boot.ready)
        assertEquals("the engine must drain promise jobs inside evaluate", true, boot.microtasksDrainedOnEvaluate)

        // -- What the engine can see ----------------------------------------
        val probes = JSONObject()
        probes.put("arithmetic", engine.evaluate("1 + 2"))
        probes.put("optionalChaining", engine.evaluate("({a:{b:7}}).a?.b ?? -1"))
        probes.put("mizarKernelType", engine.evaluate("typeof MizarKernel"))
        probes.put("mizarKernelExports", engine.evaluate("Object.keys(MizarKernel).sort().join(',')"))
        probes.put("privateClassFields", runCatching {
            engine.evaluate("(function(){ class A { #x = 5; get(){ return this.#x; } } return new A().get(); })()")
        }.fold({ it }, { "unsupported: ${it.message}" }))
        summaryLines += "Probes: 1+2=${probes.get("arithmetic")}, ?./??=${probes.get("optionalChaining")}, " +
            "MizarKernel=${probes.get("mizarKernelType")}, private fields=${probes.get("privateClassFields")}"
        assertEquals("the engine evaluates arithmetic", "3", probes.get("arithmetic"))
        assertEquals("the bundle defines the MizarKernel global", "object", probes.get("mizarKernelType"))

        // -- Handshake ------------------------------------------------------
        val welcome = boot.welcomeFrame
        assertNotNull("the kernel must answer hello with a welcome frame", welcome)
        assertEquals("welcome", KernelProtocol.typeOf(welcome!!))
        assertTrue("welcome carries a device id", !boot.deviceId.isNullOrEmpty())
        summaryLines += "Handshake: welcome deviceId=${boot.deviceId} hostId=${boot.hostId}"

        // -- kernel.* methods ----------------------------------------------
        val status = engine.call("kernel.status")
        val statusJson = JSONObject(status.resultJson ?: "{}")
        summaryLines += "kernel.status (${status.durationMs} ms): ${status.frame}"
        assertTrue("kernel.status answered ok (${status.error})", status.ok)
        assertNull(status.error)
        assertEquals("protocol version", 1, statusJson.optInt("protocolVersion", -1))
        assertTrue("kernel.status reports a kernelVersion", statusJson.optString("kernelVersion").isNotEmpty())

        val config = engine.call("kernel.config.get")
        val configJson = JSONObject(config.resultJson ?: "{}")
        summaryLines += "kernel.config.get (${config.durationMs} ms): ${config.frame}"
        assertTrue("kernel.config.get answered ok (${config.error})", config.ok)
        assertTrue("kernel.config.get carries a config", configJson.has("config"))
        assertTrue(
            "the config carries the provider list",
            configJson.getJSONObject("config").has("providers"),
        )

        // -- Latency sample (engine + shim + protocol, no network) ----------
        val samples = ArrayList<Long>()
        repeat(50) { samples.add(engine.call("kernel.status").durationMs) }
        samples.sort()
        val latency = JSONObject()
            .put("samples", samples.size)
            .put("minMs", samples.first())
            .put("p50Ms", samples[samples.size / 2])
            .put("maxMs", samples.last())
            .put("avgMs", "%.1f".format(Locale.US, samples.average()))
        summaryLines += "Latency kernel.status x${samples.size}: min=${samples.first()} ms, " +
            "p50=${samples[samples.size / 2]} ms, avg=${"%.1f".format(Locale.US, samples.average())} ms, " +
            "max=${samples.last()} ms"

        // -- Memory ---------------------------------------------------------
        val memory = JSONObject()
            .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("nativeHeapTotalBytes", Debug.getNativeHeapSize())
            .put("javaHeapUsedBytes", Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() })
            .put("pssKb", Debug.getPss())
        summaryLines += "Memory: native heap ${megabytes(Debug.getNativeHeapAllocatedSize())}, " +
            "java heap ${megabytes(memory.getLong("javaHeapUsedBytes"))}, PSS ${Debug.getPss()} kB"

        // -- Device facts ---------------------------------------------------
        val device = JSONObject()
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("abis", Build.SUPPORTED_ABIS.joinToString(","))
            .put("pageSizeBytes", pageSizeBytes())
            .put("testApkPackage", context.packageName)
            .put("kernelRoot", boot.rootPath)

        // -- Evidence -------------------------------------------------------
        val evidence = JSONObject()
            .put("generatedAt", Instant.now().toString())
            .put("generatedAtLocal", localTimestamp())
            .put("device", device)
            .put("engine", boot.engine)
            .put("kernelBundleBytes", boot.kernelBundleBytes)
            .put("boot", JSONObject()
                .put("ready", boot.ready)
                .put("bootError", boot.bootError ?: JSONObject.NULL)
                .put("durationMs", boot.durationMs)
                .put("bundleEvalMs", boot.bundleEvalMs)
                .put("microtasksDrainedOnEvaluate", boot.microtasksDrainedOnEvaluate)
                .put("deviceId", boot.deviceId ?: JSONObject.NULL)
                .put("hostId", boot.hostId ?: JSONObject.NULL)
                .put("welcomeFrame", boot.welcomeFrame ?: JSONObject.NULL)
            )
            .put("probes", probes)
            .put("kernelStatus", JSONObject()
                .put("ok", status.ok)
                .put("durationMs", status.durationMs)
                .put("frame", status.frame ?: JSONObject.NULL)
            )
            .put("kernelConfigGet", JSONObject()
                .put("ok", config.ok)
                .put("durationMs", config.durationMs)
                .put("frame", config.frame ?: JSONObject.NULL)
            )
            .put("latency", latency)
            .put("memory", memory)

        val directory = evidenceDirectory()
        val textFile = File(directory, "localagent-poc-2026-11-13.txt")
        val jsonFile = File(directory, "localagent-poc-2026-11-13.json")
        textFile.writeText(summaryLines.joinToString("\n"))
        jsonFile.writeText(evidence.toString(2))
        summaryLines += "Evidence: ${textFile.absolutePath}"
        summaryLines += "          ${jsonFile.absolutePath}"

        summaryLines.forEach { line -> Log.i(LOG_TAG, "evidence: $line") }

        // -- Screen + screenshot -------------------------------------------
        val screenshot = showOnScreenAndScreenshot(summaryLines.joinToString("\n"))
        summaryLines += "Screenshot: ${screenshot.absolutePath}"
        Log.i(LOG_TAG, "evidence: Screenshot: ${screenshot.absolutePath}")
    }

    // MARK: - Evidence helpers

    private fun evidenceDirectory(): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, "localagent-poc").apply { mkdirs() }
    }

    private fun showOnScreenAndScreenshot(summary: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(context, PoCResultsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(PoCResultsActivity.EXTRA_TEXT, summary)
        val activity = instrumentation.startActivitySync(intent)
        try {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(600)
            val bitmap: Bitmap? = instrumentation.uiAutomation.takeScreenshot()
            val file = File(evidenceDirectory(), "localagent-poc-2026-11-13.png")
            if (bitmap != null) {
                FileOutputStream(file).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
            } else {
                Log.w(LOG_TAG, "evidence: takeScreenshot returned null")
            }
            return file
        } finally {
            activity.finish()
        }
    }

    private fun localTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun megabytes(bytes: Long): String = "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)

    private fun pageSizeBytes(): Long? = try {
        android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
    } catch (error: Throwable) {
        null
    }

    private fun pageSizeLabel(): String = pageSizeBytes()?.let { "$it B" } ?: "unknown"
}
