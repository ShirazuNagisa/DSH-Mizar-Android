package me.shirazu.mizar.kit

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import java.util.UUID

const val ATTACHMENT_MAX_BYTES = 20L * 1024 * 1024
const val ATTACHMENT_MAX_COUNT = 5
const val ATTACHMENT_CHUNK_BYTES = 65_536

/** Display metadata only: never use this as a local or remote filesystem path. */
fun attachmentDisplayName(raw: String): String {
    val reserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
    var name = raw.substringAfterLast('/').substringAfterLast('\\')
        .map { if (it.isISOControl() || it in ":*?\"<>|" || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069') '_' else it }
        .joinToString("").trim().trimEnd('.').ifBlank { "attachment" }
    if (name.substringBefore('.').uppercase(java.util.Locale.ROOT) in reserved) name = "_$name"
    // Keep a short extension while bounding both UTF-8 bytes (host) and UTF-16 units (UI).
    val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 20 }
        ?.let { ".$it" }.orEmpty()
    val stem = if (extension.isEmpty()) name else name.removeSuffix(extension)
    val output = StringBuilder()
    var offset = 0
    var bytes = extension.toByteArray(Charsets.UTF_8).size
    while (offset < stem.length) {
        val next = stem.offsetByCodePoints(offset, 1)
        val part = stem.substring(offset, next)
        val size = part.toByteArray(Charsets.UTF_8).size
        if (bytes + size > 240 || output.length + part.length + extension.length > 180) break
        output.append(part)
        bytes += size
        offset = next
    }
    return (output.toString() + extension).trimEnd('.', ' ').ifBlank { "attachment" }
}

/** Includes the device credential identity: pairing the same host again is a new scope. */
data class AttachmentScope(val hostId: String, val deviceId: String, val sessionId: String)

@Serializable
data class AttachmentCapabilities(
    val version: Int,
    val maxFileBytes: Long,
    val maxImageBytes: Long,
    val maxAttachments: Int,
    val chunkBytes: Int,
    val expiresSeconds: Int,
    val imageMediaTypes: List<String>,
) {
    fun checked(): AttachmentCapabilities {
        require(version == 1) { "Unsupported attachment protocol version: $version" }
        require(maxFileBytes > 0 && maxImageBytes > 0 && maxAttachments > 0 && chunkBytes > 0 && expiresSeconds > 0) {
            "Invalid attachment limits from host"
        }
        return copy(
            maxFileBytes = minOf(maxFileBytes, ATTACHMENT_MAX_BYTES),
            maxImageBytes = minOf(maxImageBytes, ATTACHMENT_MAX_BYTES),
            maxAttachments = minOf(maxAttachments, ATTACHMENT_MAX_COUNT),
            chunkBytes = minOf(chunkBytes, ATTACHMENT_CHUNK_BYTES),
            imageMediaTypes = imageMediaTypes.filter { it in IMAGE_MEDIA_TYPES },
        )
    }

    companion object {
        val IMAGE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    }
}

/** Paths are local only; begin sends metadata, never a path or URI. */
data class AttachmentSource(
    val file: File,
    val name: String,
    val bytes: Long,
    val sha256: String,
    val kind: String,
    val mediaType: String? = null,
)

@Serializable
data class AttachmentReady(
    val attachmentId: String,
    val name: String,
    val bytes: Long,
    val kind: String,
    val mediaType: String? = null,
)

@Serializable
private data class UploadBegin(val uploadId: String, val chunkBytes: Int, val offset: Long)
@Serializable
private data class UploadChunk(val offset: Long)

/** Mutated by a single upload job, retained verbatim on an uncertain response. */
class AttachmentCheckpoint(val scope: AttachmentScope) {
    var uploadId: String? = null
        internal set
    var chunkBytes: Int = 0
        internal set
    var offset: Long = 0
        internal set
    var ready: AttachmentReady? = null
        internal set
}

/** Small seam for JVM contract tests; production adapts the existing call<T> API. */
fun interface AttachmentRpc {
    suspend fun call(method: String, payload: JsonObject): JsonObject
}

fun MizarConnection.attachmentRpc(): AttachmentRpc = AttachmentRpc { method, payload ->
    call<JsonObject>(method, payload)
}

class AttachmentUploader(private val rpc: AttachmentRpc) {
    suspend fun capabilities(): AttachmentCapabilities =
        WIRE_JSON.decodeFromJsonElement<AttachmentCapabilities>(
            rpc.call("attachment.capabilities", buildJsonObject {}),
        ).checked()

    /** Caller runs on IO. Memory is bounded by one <=64 KiB chunk, not the file size. */
    suspend fun upload(
        scope: AttachmentScope,
        source: AttachmentSource,
        limits: AttachmentCapabilities,
        checkpoint: AttachmentCheckpoint,
        onProgress: (Long) -> Unit = {},
    ): AttachmentReady {
        return try {
            transfer(scope, source, limits, checkpoint, onProgress)
        } catch (error: MizarError) {
            // Before prompt admission it is safe to restart an expired upload, but never silently
            // replace IDs in a frozen prompt. The app calls upload only before freezing admission.
            if (error.code == "UPLOAD_EXPIRED" || error.code == "UPLOAD_NOT_FOUND") {
                checkpoint.uploadId = null
                checkpoint.chunkBytes = 0
                checkpoint.offset = 0
                checkpoint.ready = null
            }
            throw error
        }
    }

    private suspend fun transfer(
        scope: AttachmentScope,
        source: AttachmentSource,
        limits: AttachmentCapabilities,
        checkpoint: AttachmentCheckpoint,
        onProgress: (Long) -> Unit,
    ): AttachmentReady {
        require(scope == checkpoint.scope) { "Attachment belongs to another host, device or session" }
        val safe = limits.checked()
        require(source.kind == "file" || source.kind == "image") { "Invalid attachment kind" }
        val max = if (source.kind == "image") safe.maxImageBytes else safe.maxFileBytes
        require(source.bytes in 1..max) { "Attachment exceeds host limit ($max bytes) or is empty" }
        require(source.file.isFile && source.file.length() == source.bytes) { "Staged attachment is missing or changed" }
        require(source.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid attachment SHA-256" }
        require(source.name == attachmentDisplayName(source.name) && source.name.toByteArray(Charsets.UTF_8).size <= 240) {
            "Invalid attachment display name"
        }
        require(source.kind != "image" || source.mediaType in safe.imageMediaTypes) { "Host does not support this image format" }
        // Even a ready object is re-finished before a NEW admission, so expiry while another
        // file failed cannot freeze a stale attachment ID into the message. Frozen prompt retries
        // bypass upload entirely and keep their original IDs/requestId.
        if (checkpoint.uploadId == null) {
            val begin = WIRE_JSON.decodeFromJsonElement<UploadBegin>(rpc.call("attachment.begin", buildJsonObject {
                put("sessionId", scope.sessionId)
                put("name", source.name)
                put("bytes", source.bytes)
                put("sha256", source.sha256)
                put("kind", source.kind)
                source.mediaType?.let { put("mediaType", it) }
            }))
            // Retain a known ID even if the host returns a malformed offset, so removal can abort it.
            require(begin.uploadId.isNotBlank()) { "Host returned an empty upload ID" }
            checkpoint.uploadId = begin.uploadId
            require(begin.offset == 0L && begin.chunkBytes in 1..safe.chunkBytes) { "Invalid upload offset or chunk limit" }
            checkpoint.chunkBytes = begin.chunkBytes
        }
        require(checkpoint.chunkBytes in 1..safe.chunkBytes && checkpoint.offset in 0..source.bytes) { "Invalid upload checkpoint" }
        val uploadId = requireNotNull(checkpoint.uploadId)
        onProgress(checkpoint.offset)
        RandomAccessFile(source.file, "r").use { input ->
            input.seek(checkpoint.offset)
            while (checkpoint.offset < source.bytes) {
                currentCoroutineContext().ensureActive()
                val start = checkpoint.offset
                val buffer = ByteArray(minOf(checkpoint.chunkBytes.toLong(), source.bytes - start).toInt())
                input.readFully(buffer)
                val next = WIRE_JSON.decodeFromJsonElement<UploadChunk>(rpc.call("attachment.chunk", buildJsonObject {
                    put("uploadId", uploadId)
                    put("offset", start)
                    put("data", Base64.getEncoder().encodeToString(buffer))
                })).offset
                require(next == start + buffer.size) { "Host acknowledged an invalid upload offset" }
                // A lost reply leaves this at start. Retrying replays exactly the same chunk.
                checkpoint.offset = next
                onProgress(next)
            }
        }
        currentCoroutineContext().ensureActive()
        val ready = WIRE_JSON.decodeFromJsonElement<AttachmentReady>(rpc.call("attachment.finish", buildJsonObject {
            put("uploadId", uploadId)
        }))
        require(ready.attachmentId.isNotBlank() && ready.bytes == source.bytes && ready.kind == source.kind &&
            (source.kind != "image" || ready.mediaType == source.mediaType)) { "Invalid attachment finish response" }
        checkpoint.ready = ready
        return ready
    }

    suspend fun abort(checkpoint: AttachmentCheckpoint) {
        checkpoint.uploadId?.let { id -> rpc.call("attachment.abort", buildJsonObject { put("uploadId", id) }) }
    }
}

/** Immutable wire payload: once sent, retries must not silently substitute text or IDs. */
data class AttachmentPromptAttempt(
    val scope: AttachmentScope,
    val text: String,
    val attachmentIds: List<String>,
    val requestId: String = UUID.randomUUID().toString(),
) {
    init {
        require(text.isNotBlank() || attachmentIds.isNotEmpty()) { "A message needs text or attachments" }
        require(attachmentIds.size <= ATTACHMENT_MAX_COUNT && attachmentIds.distinct().size == attachmentIds.size) {
            "Too many or duplicate attachments"
        }
        require(attachmentIds.none { it.isBlank() } && requestId.isNotBlank())
    }

    fun payload(forScope: AttachmentScope): JsonObject {
        require(forScope == scope) { "Message belongs to another host, device or session" }
        return buildJsonObject {
            put("sessionId", scope.sessionId)
            put("text", text)
            put("requestId", requestId)
            if (attachmentIds.isNotEmpty()) putJsonArray("attachments") { attachmentIds.forEach { add(it) } }
        }
    }
}
