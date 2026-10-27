package me.shirazu.mizar.kit

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
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

/**
 * The limits this client will not exceed, whatever the host advertises.
 *
 * They mirror the host's own documented numbers rather than being a second
 * opinion: a host that advertises more than this is either newer than the client
 * understands or wrong, and in both cases the smaller number is the safe one.
 */
const val ATTACHMENT_MAX_IMAGE_BYTES = 10L * 1024 * 1024
const val ATTACHMENT_MAX_FILE_BYTES = 32L * 1024 * 1024
const val ATTACHMENT_MAX_PROMPT_BYTES = 64L * 1024 * 1024

/** Most attachments one prompt may carry, and how many of them may be images. */
const val ATTACHMENT_MAX_COUNT = 8
const val ATTACHMENT_MAX_IMAGES = 4

/**
 * The chunk size the protocol documents.
 *
 * A host may name a different one in its `upload.begin` answer and that answer
 * wins: the host writes each chunk at `index × chunkBytes`, so a client that
 * slices to its own idea of the size puts the bytes at the wrong offsets. This
 * constant is only what the app uses before a host has spoken.
 */
const val ATTACHMENT_CHUNK_BYTES = 131_072

/**
 * The largest chunk size this client will honour from a host.
 *
 * Not a preference: a chunk is held in memory as bytes and as base64, so a host
 * that named something enormous would be asking the phone to allocate it. Well
 * above the documented 128 KiB, so any sane host is unaffected.
 */
private const val ATTACHMENT_CHUNK_CEILING = 1024 * 1024

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

/**
 * What `upload.capabilities` answers.
 *
 * Field names are the host's, not this app's: `imageBytes` and `fileBytes` are
 * the per-attachment ceilings, `promptBytes` bounds one prompt's total, and
 * `attachments`/`images` are the counts. `sha256` says whether the host will
 * verify a digest the client declares.
 */
@Serializable
data class AttachmentCapabilities(
    val chunkBytes: Int,
    val imageBytes: Long,
    val fileBytes: Long,
    @SerialName("promptBytes") val promptBytes: Long = ATTACHMENT_MAX_PROMPT_BYTES,
    val attachments: Int,
    @SerialName("images") val images: Int = ATTACHMENT_MAX_IMAGES,
    val expiresSeconds: Int,
    val imageMediaTypes: List<String>,
    @SerialName("sha256") val verifiesSha256: Boolean = false,
) {
    fun checked(): AttachmentCapabilities {
        require(chunkBytes > 0 && imageBytes > 0 && fileBytes > 0 && attachments > 0 && expiresSeconds > 0) {
            "Invalid attachment limits from host"
        }
        return copy(
            chunkBytes = minOf(chunkBytes, ATTACHMENT_CHUNK_CEILING),
            imageBytes = minOf(imageBytes, ATTACHMENT_MAX_IMAGE_BYTES),
            fileBytes = minOf(fileBytes, ATTACHMENT_MAX_FILE_BYTES),
            promptBytes = minOf(promptBytes, ATTACHMENT_MAX_PROMPT_BYTES),
            attachments = minOf(attachments, ATTACHMENT_MAX_COUNT),
            images = minOf(images, ATTACHMENT_MAX_IMAGES),
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
    /** Lowercase hex, or `null` to let the host trust its own byte count. */
    val sha256: String? = null,
    val kind: String,
    /**
     * Required by `upload.begin` even for a plain file: the host stores what it
     * is told and hands the same string back. `application/octet-stream` is the
     * honest answer for a document whose type nobody knows.
     */
    val mediaType: String = "application/octet-stream",
)

/** What `upload.finish` returns, and what `session.prompt` takes an id from. */
@Serializable
data class AttachmentReady(
    val attachmentId: String,
    val kind: String,
    val name: String,
    val mediaType: String,
    val byteSize: Long,
)

@Serializable
private data class UploadBegin(val uploadId: String, val chunkBytes: Int = 0)

@Serializable
private data class UploadChunk(val received: Int)

/**
 * How far one upload got, kept across a retry.
 *
 * The host addresses chunks by index, so the index is the resume point: a lost
 * reply leaves it where it was, and sending that index again is an idempotent
 * replay of bytes the host already holds. Nothing here is cleared on a timeout --
 * only on an answer that proves the upload is gone or unusable.
 */
class AttachmentCheckpoint(val scope: AttachmentScope) {
    var uploadId: String? = null
        internal set
    var chunkBytes: Int = 0
        internal set
    var index: Int = 0
        internal set
    /** Bytes acknowledged so far; the last chunk is usually short, so this is not `index × chunkBytes`. */
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

/**
 * Carries a file to the host over the socket that is already open.
 *
 * Four unary calls, as the protocol documents them: `upload.begin`, one
 * `upload.chunk` per slice, `upload.finish`, and `upload.abort` when the draft is
 * thrown away. The names are `upload.*` and not `attachment.*` because the host
 * and the iOS client both spell it this way; a client that invents its own names
 * gets `NOT_IMPLEMENTED` for every call and looks like a host without the feature.
 */
class AttachmentUploader(private val rpc: AttachmentRpc) {
    suspend fun capabilities(): AttachmentCapabilities =
        WIRE_JSON.decodeFromJsonElement<AttachmentCapabilities>(
            rpc.call("upload.capabilities", buildJsonObject {}),
        ).checked()

    /** Caller runs on IO. Memory is bounded by one chunk, not the file size. */
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
            // A refused upload cannot be resumed: the host has either forgotten it
            // or holds bytes that no longer match. Anything that merely failed to
            // answer keeps the checkpoint, because the chunk it was sent is
            // replayable at the same index.
            if (!error.retryable || error.code == "UPLOAD_UNKNOWN" || error.code == "UPLOAD_BAD_CHUNK") {
                checkpoint.uploadId = null
                checkpoint.chunkBytes = 0
                checkpoint.index = 0
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
        val max = if (source.kind == "image") safe.imageBytes else safe.fileBytes
        require(source.bytes in 1..max) { "Attachment exceeds host limit ($max bytes) or is empty" }
        require(source.file.isFile && source.file.length() == source.bytes) { "Staged attachment is missing or changed" }
        source.sha256?.let { require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid attachment SHA-256" } }
        require(source.name == attachmentDisplayName(source.name) && source.name.toByteArray(Charsets.UTF_8).size <= 240) {
            "Invalid attachment display name"
        }
        require(source.mediaType.isNotBlank() && source.mediaType.length <= 240) { "Invalid attachment media type" }
        require(source.kind != "image" || source.mediaType in safe.imageMediaTypes) { "Host does not support this image format" }
        // A ready object is re-finished before a NEW admission, so an attachment that
        // expired while another file failed cannot be frozen into a message. A frozen
        // prompt retry never comes through here: it reuses the ids it already has.
        if (checkpoint.uploadId == null) {
            val begin = WIRE_JSON.decodeFromJsonElement<UploadBegin>(rpc.call("upload.begin", buildJsonObject {
                put("sessionId", scope.sessionId)
                put("kind", source.kind)
                put("name", source.name)
                put("mediaType", source.mediaType)
                put("byteSize", source.bytes)
                source.sha256?.let { put("sha256", it) }
            }))
            // Retained even if the rest of the answer is unusable, so removal can abort it.
            require(begin.uploadId.isNotBlank()) { "Host returned an empty upload ID" }
            checkpoint.uploadId = begin.uploadId
            // The host's chunk size is the offset convention, so it is taken as given
            // rather than clamped: slicing to a smaller one would write every chunk
            // after the first at the wrong place. An absurd value is a refusal, not a
            // number to negotiate.
            val chunk = if (begin.chunkBytes > 0) begin.chunkBytes else ATTACHMENT_CHUNK_BYTES
            require(chunk in 1..ATTACHMENT_CHUNK_CEILING) { "Host asked for an unusable chunk size: $chunk" }
            checkpoint.chunkBytes = chunk
            checkpoint.index = 0
            checkpoint.offset = 0
        }
        require(checkpoint.chunkBytes in 1..ATTACHMENT_CHUNK_CEILING) { "Invalid upload checkpoint" }
        require(checkpoint.index >= 0 && checkpoint.offset <= source.bytes) { "Invalid upload checkpoint" }
        val uploadId = requireNotNull(checkpoint.uploadId)
        onProgress(checkpoint.offset)
        RandomAccessFile(source.file, "r").use { input ->
            input.seek(checkpoint.offset)
            while (checkpoint.offset < source.bytes) {
                currentCoroutineContext().ensureActive()
                val start = checkpoint.offset
                val buffer = ByteArray(minOf(checkpoint.chunkBytes.toLong(), source.bytes - start).toInt())
                input.readFully(buffer)
                val received = WIRE_JSON.decodeFromJsonElement<UploadChunk>(rpc.call("upload.chunk", buildJsonObject {
                    put("uploadId", uploadId)
                    put("index", checkpoint.index)
                    put("data", Base64.getEncoder().encodeToString(buffer))
                })).received
                // An acknowledgement that does not name this chunk means the two ends
                // disagree about the offset convention; continuing would write the rest
                // of the file at a hole and only fail at finish.
                require(received == checkpoint.index + 1) { "Host acknowledged chunk $received after chunk ${checkpoint.index}" }
                // A lost reply leaves the index where it was. Retrying replays exactly
                // this chunk, which the host compares byte for byte.
                checkpoint.index = received
                checkpoint.offset = start + buffer.size
                onProgress(checkpoint.offset)
            }
        }
        currentCoroutineContext().ensureActive()
        val ready = WIRE_JSON.decodeFromJsonElement<AttachmentReady>(rpc.call("upload.finish", buildJsonObject {
            put("uploadId", uploadId)
        }))
        require(ready.attachmentId.isNotBlank() && ready.byteSize == source.bytes && ready.kind == source.kind &&
            (source.kind != "image" || ready.mediaType == source.mediaType)) { "Invalid attachment finish response" }
        checkpoint.ready = ready
        return ready
    }

    suspend fun abort(checkpoint: AttachmentCheckpoint) {
        checkpoint.uploadId?.let { id -> rpc.call("upload.abort", buildJsonObject { put("uploadId", id) }) }
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
            // `attachmentIds` is the host's spelling; a key it does not read is a
            // message that arrives with no files and no error.
            if (attachmentIds.isNotEmpty()) putJsonArray("attachmentIds") { attachmentIds.forEach { add(it) } }
        }
    }
}
