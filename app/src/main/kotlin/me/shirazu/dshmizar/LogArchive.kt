package me.shirazu.dshmizar

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The archive behind the settings screen's export button.
 *
 * A bug report about a phone is a conversation with somebody who cannot see the
 * phone. This turns the app's own diagnostics into a small zip of plain text files:
 * readable by a person, parseable by a machine, and nothing in it is the operator's
 * conversation. Credentials never enter the archive — the device token grants remote
 * control of an agent, and the pairing code is worse; both are left out at the point
 * the text is written rather than scrubbed later.
 */
object LogArchive {

    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** Build the zip and return it, or throw when the cache itself is unwritable. */
    fun build(
        context: Context,
        state: UiState,
        notes: List<String>,
        requests: List<String> = emptyList(),
    ): File {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(directory, "mizar-logs-${stamp.format(Date())}.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            put(zip, "README.txt", readme())
            put(zip, "environment.json", environment(context))
            put(zip, "device.txt", device(context))
            put(zip, "state.txt", state(state))
            put(zip, "settings.txt", settings(state))
            put(zip, "sessions.txt", sessions(state))
            put(zip, "activity.log", notes.joinToString("\n"))
            // Protocol round trips, one JSON object per line. Method names, timings
            // and error codes only: the arguments are where the operator's prompts
            // and the agent's answers live.
            put(zip, "requests.jsonl", if (requests.isEmpty()) "" else requests.joinToString("\n", postfix = "\n"))
        }
        return file
    }

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun readme() = buildString {
        appendLine("DSH-Mizar Android diagnostics")
        appendLine()
        appendLine("A bug report about a phone is unreadable without what the phone")
        appendLine("tried and what came back. This archive is plain text so it can be")
        appendLine("read without this app:")
        appendLine()
        appendLine("  environment.json  build, OS, device and runtime, as JSON")
        appendLine("  device.txt        the same, as a few readable lines")
        appendLine("  state.txt         connection, host identity, the last failure")
        appendLine("  settings.txt      the operator's switches")
        appendLine("  sessions.txt      the session list, without message text")
        appendLine("  activity.log      the app's own events, newest last")
        appendLine("  requests.jsonl    protocol round trips: method, direction, ms, outcome")
        appendLine()
        appendLine("Deliberately absent: the device token, pairing codes, prompts and")
        appendLine("replies. The archive exists to be emailed; a credential inside one")
        appendLine("would be a remote-control key given away with a bug report.")
        appendLine()
        appendLine("requests.jsonl is the file to read when a frame seems to have gone")
        appendLine("missing: an \"out\" line with no matching \"in\" line means it left and")
        appendLine("nothing answered, and \"error:CODE\" is the host refusing it by name.")
    }

    /**
     * The environment as JSON, for whoever is reading the archive with a script.
     *
     * The same facts as `device.txt` plus the ones a build report needs and a
     * person does not: the fingerprint (which ROM), the ABI, and whether this is an
     * emulator at all -- three bug reports about "the app is slow" have been three
     * different machines, and the archive should say which.
     */
    private fun environment(context: Context): String = buildString {
        appendLine("{")
        appendLine("  \"package\": ${json(context.packageName)},")
        appendLine("  \"versionName\": ${json(BuildConfig.VERSION_NAME)},")
        appendLine("  \"versionCode\": ${BuildConfig.VERSION_CODE},")
        appendLine("  \"exportedAt\": ${json(isoNow())},")
        appendLine("  \"android\": {")
        appendLine("    \"release\": ${json(Build.VERSION.RELEASE)},")
        appendLine("    \"sdk\": ${Build.VERSION.SDK_INT},")
        appendLine("    \"codename\": ${json(Build.VERSION.CODENAME)},")
        appendLine("    \"incremental\": ${json(Build.VERSION.INCREMENTAL)},")
        appendLine("    \"securityPatch\": ${json(Build.VERSION.SECURITY_PATCH ?: "")}")
        appendLine("  },")
        appendLine("  \"device\": {")
        appendLine("    \"manufacturer\": ${json(Build.MANUFACTURER)},")
        appendLine("    \"brand\": ${json(Build.BRAND)},")
        appendLine("    \"model\": ${json(Build.MODEL)},")
        appendLine("    \"device\": ${json(Build.DEVICE)},")
        appendLine("    \"product\": ${json(Build.PRODUCT)},")
        appendLine("    \"hardware\": ${json(Build.HARDWARE)},")
        appendLine("    \"fingerprint\": ${json(Build.FINGERPRINT)}")
        appendLine("  },")
        appendLine("  \"runtime\": {")
        appendLine("    \"locale\": ${json(Locale.getDefault().toLanguageTag())},")
        appendLine("    \"timeZone\": ${json(java.util.TimeZone.getDefault().id)},")
        appendLine("    \"abi\": ${json(Build.SUPPORTED_ABIS.joinToString(","))},")
        appendLine("    \"emulator\": ${isEmulator()},")
        appendLine("    \"cpuCores\": ${Runtime.getRuntime().availableProcessors()},")
        appendLine("    \"heapMaxMb\": ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}")
        appendLine("  }")
        append("}")
    }

    /** The usual fingerprint test: an emulator build names itself in one of these. */
    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("sdk_gphone") ||
            Build.PRODUCT.contains("sdk_gphone") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())

    /** A JSON string, escaped. The values here are build fields, never input. */
    private fun json(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        for (character in value) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (character < ' ') out.append("\\u%04x".format(character.code)) else out.append(character)
            }
        }
        return out.append('"').toString()
    }

    private fun device(context: Context) = buildString {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        appendLine("package: ${context.packageName}")
        appendLine("versionName: ${packageInfo?.versionName ?: BuildConfig.VERSION_NAME}")
        appendLine("versionCode: ${BuildConfig.VERSION_CODE}")
        appendLine("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("locale: ${Locale.getDefault().toLanguageTag()}")
        appendLine("exportedAt: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())}")
    }

    private fun state(state: UiState) = buildString {
        appendLine("phase: ${state.phase}")
        appendLine("pairingStage: ${state.pairingStage ?: "—"}")
        state.syncProgress?.let { appendLine("syncProgress: ${it.done}/${it.total} (${it.percent}%)") }
        appendLine("reconnecting: ${state.reconnecting}")
        appendLine("needsPairing: ${state.needsPairing}")
        appendLine("isLocalNetwork: ${state.isLocalNetwork}")
        appendLine("lanScanning: ${state.lanScanning}")
        appendLine("latencyMillis: ${state.latencyMillis ?: "—"}")
        appendLine("disconnectReason: ${state.disconnectReason ?: "—"}")
        appendLine("problem: ${state.problem ?: "—"}")
        appendLine("statsError: ${state.statsError ?: "—"}")
        appendLine("conversationError: ${state.conversationError ?: "—"}")
        appendLine("selectedSessionId: ${state.selectedSessionId ?: "—"}")
        appendLine("sessions: ${state.sessions.size}  running: ${state.sessions.count { it.running }}")
        appendLine("pendingApprovals: ${state.pending?.approvals?.size ?: 0}")
        appendLine("pendingQuestions: ${state.pending?.questions?.size ?: 0}")
        appendLine()
        appendLine("[host]")
        state.host?.let { host ->
            appendLine("hostId: ${host.hostId}")
            appendLine("hostName: ${host.hostName}")
            appendLine("label: ${host.label ?: "—"}")
            appendLine("authority: ${host.authority ?: "—"}")
            appendLine("baseUrl: ${host.baseUrl ?: "—"}")
            appendLine("deviceId: ${host.deviceId}")
            appendLine("pairedAtMillis: ${host.pairedAtMillis}")
        } ?: appendLine("(none)")
        appendLine()
        appendLine("[host.info]")
        state.welcome?.let { welcome ->
            appendLine("hostId: ${welcome.hostId}")
            appendLine("hostName: ${welcome.hostName}")
            appendLine("protocolVersion: ${welcome.protocolVersion}")
            appendLine("home: ${welcome.home}")
        } ?: appendLine("(none)")
        appendLine()
        appendLine("[stats]")
        state.stats?.let { stats ->
            appendLine("model: ${stats.model?.lastUsed?.model ?: "—"}")
            appendLine("permissionPreset: ${stats.permissionPreset ?: "—"}")
            appendLine("title: ${stats.title ?: "—"}")
            appendLine("context: ${stats.context?.let { "${it.percent ?: "?"}% (pressure ${it.pressureTokens ?: "—"} / window ${it.window ?: "—"})" } ?: "—"}")
        } ?: appendLine("(none)")
    }

    private fun settings(state: UiState) = buildString {
        appendLine("theme: ${state.themeMode}")
        appendLine("quietHours: ${state.quietHours}")
        appendLine("notifyCompleted: ${state.notifyCompleted}")
        appendLine("notifyInterrupted: ${state.notifyInterrupted}")
        appendLine("notifyQuestions: ${state.notifyQuestions}")
        appendLine("notifyApprovals: ${state.notifyApprovals}")
        appendLine("hapticsEnabled: ${state.hapticsEnabled}")
        appendLine("localNetworkFirst: ${state.localNetworkFirst}")
    }

    private fun sessions(state: UiState) = buildString {
        for (row in state.sessions) {
            val title = state.titles[row.sessionId]?.replace('\n', ' ') ?: "—"
            appendLine(
                listOf(
                    row.sessionId,
                    if (row.running) "running" else "idle",
                    if (row.blank) "blank" else "used",
                    title,
                    row.cwd ?: "—",
                ).joinToString("\t"),
            )
        }
    }
}
