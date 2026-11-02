package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.shirazu.dshmizar.DraftAttachment
import me.shirazu.dshmizar.R
import me.shirazu.mizar.kit.AttachmentCheckpoint
import me.shirazu.mizar.kit.AttachmentScope
import me.shirazu.mizar.kit.AttachmentSource
import me.shirazu.mizar.kit.AttachmentTransport
import me.shirazu.mizar.kit.attachmentDisplayName
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * The files staged for the next prompt.
 *
 * Three jobs and no more: refuse what the host would refuse anyway, keep the draft
 * rows in step with the upload, and make sure the local copy is deleted whenever the
 * row goes away -- a leaked temp file is invisible until a phone runs out of space.
 *
 * The bytes are somebody else's problem: [transport] hands over the current upload
 * or `null` when there is no socket, and everything about chunks, digests and
 * `upload.*` lives behind it. The work runs on [io] because it reads and hashes a
 * file that may be tens of megabytes; the state writes inside it are what the
 * composer draws.
 */
internal class AttachmentCoordinator(
    private val store: StateStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val text: Text,
    private val note: (String) -> Unit,
    /** The upload for the live socket, or `null` when nothing is connected. */
    private val transport: () -> AttachmentTransport?,
) {

    /**
     * Stage one attachment and upload it for the next prompt.
     *
     * The picker and image re-encode happen on the UI side; this keeps the state and
     * the transport. The host advertises its own limits, read once and lowered to
     * this app's own caps by the kit.
     */
    fun add(file: File, name: String, kind: String, mediaType: String?) {
        val current = store.state.value
        val host = current.host ?: return
        val sessionId = current.selectedSessionId ?: return
        if (!current.advancedFeaturesUnlocked) {
            file.delete()
            store.update { it.copy(problem = text.get(R.string.attachment_lan_only)) }
            return
        }
        val displayName = attachmentDisplayName(name)
        val size = file.length()
        // The host accepts a media type for images only; a document is identified
        // by its name, and sending a type is a malformed frame.
        val effectiveMediaType = mediaType.takeIf { kind == "image" }
        if (size <= 0L) {
            file.delete()
            store.update { it.copy(problem = text.get(R.string.attachment_unreadable)) }
            return
        }
        val key = UUID.randomUUID().toString()
        store.update {
            it.copy(
                attachments = it.attachments + DraftAttachment(
                    key = key,
                    name = displayName,
                    bytes = size,
                    kind = kind,
                    mediaType = effectiveMediaType,
                    file = file,
                ),
                attachmentBusy = true,
            )
        }
        scope.launch(io) {
            try {
                note("attachment: staging $displayName ($size bytes, $kind)")
                val upload = transport() ?: error("not connected")
                val caps = store.state.value.attachmentCapabilities
                    ?: upload.capabilities().also { limits ->
                        store.update { it.copy(attachmentCapabilities = limits) }
                    }
                if (store.state.value.attachments.size > caps.attachments) error(text.get(R.string.attachment_too_many))
                val max = if (kind == "image") caps.imageBytes else caps.fileBytes
                if (size > max) error(text.get(R.string.attachment_too_large))
                // Streamed, not `readBytes()`: a 32 MiB document is the host's limit,
                // and holding a copy of it just to hash it is the difference between
                // a phone that keeps the session alive and one the system reclaims.
                val sha = digestOf(file)
                val source = AttachmentSource(
                    file = file,
                    name = displayName,
                    bytes = size,
                    sha256 = sha,
                    kind = kind,
                    mediaType = effectiveMediaType ?: "application/octet-stream",
                )
                val attachmentScope = AttachmentScope(host.hostId, host.deviceId, sessionId)
                val ready = upload.upload(
                    scope = attachmentScope,
                    source = source,
                    limits = caps,
                    checkpoint = AttachmentCheckpoint(attachmentScope),
                    onProgress = { offset ->
                        store.update { state ->
                            state.copy(attachments = state.attachments.map {
                                if (it.key == key) it.copy(progress = offset) else it
                            })
                        }
                    },
                )
                note("attachment: ready ${ready.attachmentId} for $displayName")
                store.update { state ->
                    state.copy(attachments = state.attachments.map {
                        if (it.key == key) it.copy(progress = size, readyId = ready.attachmentId) else it
                    })
                }
            } catch (error: Throwable) {
                note("attachment: failed for $displayName: ${error.message}")
                store.update { state ->
                    state.copy(attachments = state.attachments.map {
                        if (it.key == key) it.copy(error = error.message ?: text.get(R.string.attachment_failed)) else it
                    })
                }
            } finally {
                store.update { state ->
                    state.copy(attachmentBusy = state.attachments.any { it.readyId == null && it.error == null })
                }
            }
        }
    }

    /** Drop one staged attachment, and its local copy. */
    fun remove(key: String) {
        val draft = store.state.value.attachments.firstOrNull { it.key == key } ?: return
        draft.file.delete()
        store.update { current ->
            current.copy(attachments = current.attachments.filterNot { it.key == key })
        }
    }

    /** Drop every staged attachment after a send, or when the session changes. */
    fun clear() {
        val files = store.state.value.attachments.map { it.file }
        store.update { it.copy(attachments = emptyList(), attachmentBusy = false) }
        files.forEach { file -> runCatching { file.delete() } }
    }

    /** Lowercase hex SHA-256 of a staged file, read in bounded chunks. */
    private fun digestOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
