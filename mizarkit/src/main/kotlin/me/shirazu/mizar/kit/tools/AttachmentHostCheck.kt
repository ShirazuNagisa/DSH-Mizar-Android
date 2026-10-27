package me.shirazu.mizar.kit.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.shirazu.mizar.kit.AttachmentCheckpoint
import me.shirazu.mizar.kit.AttachmentPromptAttempt
import me.shirazu.mizar.kit.AttachmentScope
import me.shirazu.mizar.kit.AttachmentSource
import me.shirazu.mizar.kit.AttachmentUploader
import me.shirazu.mizar.kit.CreateSessionResult
import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.MizarError
import me.shirazu.mizar.kit.PairingClient
import me.shirazu.mizar.kit.PromptResult
import me.shirazu.mizar.kit.ModelCatalog
import me.shirazu.mizar.kit.ModelSelection
import me.shirazu.mizar.kit.PermissionCatalog
import me.shirazu.mizar.kit.SelectModelResult
import me.shirazu.mizar.kit.SessionStatsResult
import me.shirazu.mizar.kit.SessionSettings
import me.shirazu.mizar.kit.SettingsScope
import me.shirazu.mizar.kit.SetPermissionResult
import me.shirazu.mizar.kit.attachmentRpc
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.system.exitProcess

/**
 * Real JVM client -> isolated plugin listener check. Never defaults to a production host.
 * The safety gate permits only the Lead's temporary loopback test listener on port 19491.
 * Do not print pairing responses, credentials, request payloads, or exception messages.
 */
fun main() {
    val status = try {
        runBlocking { attachmentHostCheck() }
        0
    } catch (error: Exception) {
        // Remote messages could contain a token. Only a bounded protocol code/class is safe output.
        val code = (error as? MizarError)?.code?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) }
        System.err.println("FAIL attachmentHostCheck (${code ?: error.javaClass.simpleName})")
        1
    }
    exitProcess(status) // OkHttp's idle non-daemon threads otherwise linger after a CLI check.
}

private suspend fun attachmentHostCheck() {
    val base = requireNotNull(System.getenv("MIZAR_ATTACHMENT_BASE")) { "Pass -Pbase" }
    val code = requireNotNull(System.getenv("MIZAR_ATTACHMENT_CODE")) { "Pass -Pcode or MIZAR_ATTACHMENT_CODE" }
    val uri = URI(base)
    require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 19491 &&
        uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in listOf("", "/")) {
        "Only the explicitly configured isolated loopback listener on 19491 is allowed"
    }
    require(code.isNotBlank())
    println("attachmentHostCheck: isolated loopback listener 19491")
    val paired = PairingClient(appVersion = "0.8.21-attachments.1-jvm-check").pair(
        code = code,
        deviceName = "attachmentHostCheck",
        deviceModel = "JVM isolated contract check",
        authority = uri.authority,
    )
    check(paired.deviceToken.isNotBlank() && paired.hostId.isNotBlank() && paired.deviceId.isNotBlank())
    println("PASS pair (credentials withheld)")
    val socket = MizarConnection(MizarConnection.Configuration(
        authority = uri.authority,
        token = paired.deviceToken,
        appVersion = "0.8.21-attachments.1-jvm-check",
        deviceModel = "JVM isolated contract check",
    ))
    val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("mizar-attachment-check-").toFile() }
    val checkpoints = mutableListOf<AttachmentCheckpoint>()
    var accepted = false
    try {
        val welcome = socket.connect()
        check(welcome.hostId == paired.hostId && welcome.deviceId == paired.deviceId)
        println("PASS authenticated welcome identity")
        val created = socket.call<CreateSessionResult>("session.create", buildJsonObject {
            put("cwd", System.getenv("MIZAR_ATTACHMENT_CWD") ?: welcome.home)
        })
        check(created.sessionId.isNotBlank())
        println("PASS create isolated test session")
        val scope = AttachmentScope(welcome.hostId, welcome.deviceId, created.sessionId)
        checkSessionSettings(socket, SettingsScope(welcome.hostId, welcome.deviceId, created.sessionId, 0))
        val uploader = AttachmentUploader(socket.attachmentRpc())
        val limits = uploader.capabilities()
        check(limits.attachments >= 2 && "image/png" in limits.imageMediaTypes)
        println("PASS upload capabilities chunkBytes=${limits.chunkBytes} imageBytes=${limits.imageBytes} fileBytes=${limits.fileBytes}")
        val sources = withContext(Dispatchers.IO) {
            listOf(
                fixture(directory, "contract-image.png", makePng(), "image", "image/png"),
                fixture(directory, "contract-binary.bin", ByteArray(150_001) { (it % 251).toByte() }, "file"),
            )
        }
        val ids = sources.map { source ->
            val checkpoint = AttachmentCheckpoint(scope).also { checkpoints += it }
            val ready = withContext(Dispatchers.IO) { uploader.upload(scope, source, limits, checkpoint) }
            check(ready.byteSize == source.bytes && checkpoint.offset == source.bytes)
            // This exercises real repeated finish against the TS store, not just a fixture RPC.
            val repeated = withContext(Dispatchers.IO) { uploader.upload(scope, source, limits, checkpoint) }
            check(repeated == ready)
            println("PASS upload+repeat-finish ${source.kind} bytes=${source.bytes} sha256=${source.sha256 ?: "not declared"}")
            ready.attachmentId
        }
        val attempt = AttachmentPromptAttempt(scope, "", ids)
        val payload = attempt.payload(scope)
        val result = socket.call<PromptResult>("session.prompt", payload)
        check(result.accepted && result.requestId == attempt.requestId)
        accepted = true
        println("PASS attachment-only session.prompt accepted=true")
        val replay = socket.call<PromptResult>("session.prompt", payload)
        check(replay.accepted && replay.requestId == attempt.requestId)
        println("PASS identical requestId replay accepted=true")
        println("PASS attachmentHostCheck complete (2 attachments; no credentials printed)")
    } finally {
        withContext(NonCancellable) {
            if (!accepted) {
                val uploader = AttachmentUploader(socket.attachmentRpc())
                checkpoints.forEach { runCatching { uploader.abort(it) } }
            }
            socket.disconnect()
            withContext(Dispatchers.IO) {
                directory.listFiles()?.forEach { check(it.delete()) }
                check(directory.delete())
            }
        }
    }
}

private suspend fun checkSessionSettings(socket: MizarConnection, scope: SettingsScope) {
    val catalog = socket.call<ModelCatalog>("model.catalog")
    val provider = catalog.providers.first { group -> group.models.any { SessionSettings.efforts(it).size >= 2 } }
    val thinking = provider.models.first { SessionSettings.efforts(it).size >= 2 }
    val chosenEffort = SessionSettings.efforts(thinking).last().id
    val requested = ModelSelection(provider.id, thinking.id, chosenEffort)
    val selected = socket.call<SelectModelResult>("session.model.set", SessionSettings.modelPayload(scope, requested))
    check(SessionSettings.confirmedModel(scope, selected, null).model?.next == requested)
    val readback = socket.call<SessionStatsResult>("session.stats", buildJsonObject { put("sessionId", scope.sessionId) })
    check(SessionSettings.current(readback.stats, null) == requested)
    println("PASS actual model+reasoning selection and session.stats readback")
    val plainProvider = catalog.providers.first { group -> group.models.any { SessionSettings.efforts(it).isEmpty() } }
    val plain = plainProvider.models.first { SessionSettings.efforts(it).isEmpty() }
    val withoutEffort = SessionSettings.seed(plainProvider.id, plain, requested)
    check(!SessionSettings.modelPayload(scope, withoutEffort).containsKey("reasoningEffort"))
    val plainResult = socket.call<SelectModelResult>("session.model.set", SessionSettings.modelPayload(scope, withoutEffort))
    check(plainResult.selected.reasoningEffort == null)
    println("PASS non-reasoning model omits effort")
    // Restore the image-capable fixture route before the existing image prompt check.
    socket.call<SelectModelResult>("session.model.set", SessionSettings.modelPayload(scope, requested))
    val permissions = socket.call<PermissionCatalog>("permissions.catalog")
    val options = SessionSettings.permissions(permissions)
    check(options.isNotEmpty() && options.none { it.value == "custom" })
    for (option in options) {
        val result = socket.call<SetPermissionResult>("session.permission.set", buildJsonObject {
            put("sessionId", scope.sessionId); put("preset", option.value)
        })
        check(result.commandResult?.succeeded == true)
        val stats = socket.call<SessionStatsResult>("session.stats", buildJsonObject { put("sessionId", scope.sessionId) })
        check(SessionSettings.permissionConfirmed(scope, option.value, stats))
    }
    println("PASS dynamic permission catalog and ${options.size} preset readbacks")
}

private fun fixture(directory: File, name: String, bytes: ByteArray, kind: String, mediaType: String = "application/octet-stream"): AttachmentSource {
    val file = File(directory, name).apply { writeBytes(bytes) }
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    return AttachmentSource(file, name, bytes.size.toLong(), hash, kind, mediaType)
}

/** A real 16x16 RGBA PNG using only JDK binary/ZIP APIs (no AWT dependency in Android's kit). */
private fun makePng(): ByteArray {
    val output = ByteArrayOutputStream()
    val data = DataOutputStream(output)
    data.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
    fun chunk(type: String, body: ByteArray) {
        val label = type.toByteArray(Charsets.US_ASCII)
        data.writeInt(body.size)
        data.write(label)
        data.write(body)
        val crc = CRC32().apply { update(label); update(body) }
        data.writeInt(crc.value.toInt())
    }
    val header = ByteArrayOutputStream()
    DataOutputStream(header).use { it.writeInt(16); it.writeInt(16); it.write(byteArrayOf(8, 6, 0, 0, 0)) }
    chunk("IHDR", header.toByteArray())
    val pixels = ByteArrayOutputStream()
    DeflaterOutputStream(pixels).use { compressed ->
        repeat(16) { y ->
            compressed.write(0)
            repeat(16) { x -> compressed.write(byteArrayOf((x * 16).toByte(), (y * 16).toByte(), 100, 255.toByte())) }
        }
    }
    chunk("IDAT", pixels.toByteArray())
    chunk("IEND", byteArrayOf())
    return output.toByteArray()
}
