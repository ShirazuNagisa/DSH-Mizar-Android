package me.shirazu.mizar.kit

import kotlinx.serialization.Serializable
import java.util.Base64

/**
 * One slice of a file, as `file.read` answers.
 *
 * The host sends the bytes base64-encoded in pages of at most 256 KiB, so a
 * preview that wants more than one page has to ask again with a larger offset.
 * `byteSize` is the whole file's size, which is what a screen needs to say "this
 * is only the first page" without a second call.
 */
@Serializable
data class FileSlice(
    val offset: Int = 0,
    val length: Int = 0,
    val byteSize: Long = 0,
    /** The slice, standard base64. */
    val data: String = "",
    /** Whether this slice ends the file. */
    val eof: Boolean = false,
) {
    /**
     * The bytes of this slice.
     *
     * A body that is not valid base64 decodes to nothing rather than throwing:
     * the screen then says it could not read the file, which is the same thing it
     * would say for any other unreadable answer.
     */
    fun bytes(): ByteArray = runCatching { Base64.getDecoder().decode(data) }.getOrDefault(ByteArray(0))

    companion object {
        /** The largest slice the host will send, in bytes. */
        const val MAX_SLICE_BYTES = 256 * 1024
    }
}
