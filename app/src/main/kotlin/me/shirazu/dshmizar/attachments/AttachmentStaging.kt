package me.shirazu.dshmizar.attachments

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.shirazu.mizar.kit.ATTACHMENT_MAX_BYTES
import me.shirazu.mizar.kit.AttachmentSource
import me.shirazu.mizar.kit.attachmentDisplayName
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** No external storage permission, persistent URI grant, user path, or full-image allocation. */
class AttachmentStaging(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "attachment-drafts")
    private var initialized = false
    private val copyMutex = Mutex()

    /** Called on IO, once per model. A killed process cannot leave private copies indefinitely. */
    @Synchronized
    private fun initialize() {
        if (initialized) return
        check(root.isDirectory || root.mkdirs()) { "Cannot create private attachment storage" }
        root.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        initialized = true
    }

    suspend fun prepare() = copyMutex.withLock { initialize() }

    suspend fun stage(uri: Uri, photo: Boolean, progress: (Long) -> Unit): AttachmentSource =
        copyMutex.withLock { copy(uri, photo, progress) }

    private suspend fun copy(uri: Uri, photo: Boolean, progress: (Long) -> Unit): AttachmentSource {
        initialize()
        require(uri.scheme == "content") { "Only content from the system picker is supported" }
        val resolver = context.contentResolver
        var displayName = if (photo) "photo" else "attachment"
        var declaredSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
        require(declaredSize == null || declaredSize!! <= ATTACHMENT_MAX_BYTES) { "Each attachment must be at most 20 MiB" }
        val existing = root.listFiles()?.sumOf { it.length() } ?: 0L
        val required = declaredSize ?: ATTACHMENT_MAX_BYTES
        require(existing + required <= MAX_STAGED_BYTES) { "Private attachment storage is full; remove other drafts first" }
        require(root.usableSpace >= required + SPACE_RESERVE) { "Not enough free space to stage this attachment" }
        // Display names never determine a local path. Files from OpenDocument are byte-for-byte copies.
        val name = attachmentDisplayName(displayName)
        val file = File(root, "${UUID.randomUUID()}.stage")
        var complete = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            val input = resolver.openInputStream(uri) ?: throw IOException("Cannot read the selected attachment")
            input.use { source ->
                file.outputStream().use { target ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        bytes += count
                        require(bytes <= ATTACHMENT_MAX_BYTES) { "Each attachment must be at most 20 MiB" }
                        require(existing + bytes <= MAX_STAGED_BYTES && root.usableSpace >= count + SPACE_RESERVE) {
                            "Not enough free space to stage this attachment"
                        }
                        target.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        progress(bytes)
                    }
                }
            }
            require(bytes > 0) { "Empty attachments are not supported" }
            val mediaType = if (photo) imageType(file) else null
            if (photo) {
                require(mediaType != null) { "Choose a JPEG, PNG, WebP or GIF photo; convert HEIC/other formats first" }
                // Bounds-only decode prevents huge pixel dimensions causing bitmap OOM on either side.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
                    bounds.outWidth.toLong() * bounds.outHeight <= MAX_IMAGE_PIXELS) {
                    "This photo is too large to decode safely; resize to at most 8192 pixels per side and 32 megapixels"
                }
            }
            currentCoroutineContext().ensureActive()
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            complete = true
            return AttachmentSource(file, name, bytes, hash, if (photo) "image" else "file", mediaType)
        } finally {
            if (!complete) file.delete()
        }
    }

    private fun imageType(file: File): String? {
        val bytes = ByteArray(12)
        val size = file.inputStream().use { it.read(bytes) }
        fun matches(vararg prefix: Int) = size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 255) == prefix[it] }
        return when {
            matches(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
            matches(255, 216, 255) -> "image/jpeg"
            size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
            size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }
    }

    companion object {
        private const val SPACE_RESERVE = 16L * 1024 * 1024
        private const val MAX_STAGED_BYTES = 100L * 1024 * 1024
        private const val MAX_IMAGE_PIXELS = 32_000_000L
    }
}
