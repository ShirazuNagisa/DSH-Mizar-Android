package me.shirazu.dshmizar

import java.io.File

/**
 * One attachment being prepared for the next prompt.
 *
 * The upload happens while the operator keeps typing: the file is staged locally,
 * hashed and sent in chunks, and the id the host returns is what the prompt refers
 * to. A draft is only sendable once [readyId] is set.
 */
data class DraftAttachment(
    val key: String,
    val name: String,
    val bytes: Long,
    val kind: String,
    val mediaType: String?,
    val file: File,
    val progress: Long = 0,
    val readyId: String? = null,
    val error: String? = null,
) {
    val fraction: Float get() = if (bytes <= 0L) 0f else (progress.toFloat() / bytes).coerceIn(0f, 1f)
}
