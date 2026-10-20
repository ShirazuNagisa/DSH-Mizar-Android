package me.shirazu.dshmizar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

/** A picked file on disk, ready to be hashed and uploaded. */
data class StagedAttachment(
    val file: File,
    val name: String,
    val kind: String,
    val mediaType: String?,
)

/**
 * Copy a picked document, or decode and re-encode a picked image, into the app's
 * cache so the uploader has a stable file to read and hash.
 *
 * Images are bounded to 2048 px on the long edge and re-encoded as JPEG, as the
 * iOS client does: the phone camera produces 4000 px files for a question that
 * needs none of that detail, and the host's image limit is bytes, not pixels.
 */
suspend fun stageAttachment(context: Context, uri: Uri, image: Boolean): StagedAttachment? =
    withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "attachments").apply { mkdirs() }
        var target: File? = null
        try {
            val resolver = context.contentResolver
            val display = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: if (image) "photo.jpg" else "attachment"

            target = File(directory, UUID.randomUUID().toString())
            if (image) {
                val bitmap = decodeBounded(resolver, uri) ?: return@withContext null
                FileOutputStream(target).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output)
                }
                bitmap.recycle()
                StagedAttachment(
                    file = target,
                    name = display.substringBeforeLast('.').ifBlank { "photo" } + ".jpg",
                    kind = "image",
                    mediaType = "image/jpeg",
                )
            } else {
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext null
                if (target.length() <= 0L) return@withContext null
                StagedAttachment(
                    file = target,
                    name = display,
                    kind = "file",
                    mediaType = resolver.getType(uri) ?: "application/octet-stream",
                )
            }
        } catch (_: Throwable) {
            target?.delete()
            null
        }
    }

/** Decode at most 2048 px on the long edge, without ever allocating the original bitmap. */
private fun decodeBounded(resolver: android.content.ContentResolver, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null
    val longEdge = max(width, height)
    val target = 2048

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > target) {
                val scale = target.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    var sample = 1
    while (longEdge / (sample * 2) >= target) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    if (max(decoded.width, decoded.height) <= target) return decoded
    val scale = target.toFloat() / max(decoded.width, decoded.height)
    val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
    if (scaled !== decoded) decoded.recycle()
    return scaled
}
