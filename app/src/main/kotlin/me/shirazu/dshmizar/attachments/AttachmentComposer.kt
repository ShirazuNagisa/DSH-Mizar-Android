package me.shirazu.dshmizar.attachments

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.shirazu.mizar.kit.ATTACHMENT_MAX_COUNT
import me.shirazu.mizar.kit.AttachmentCheckpoint
import me.shirazu.mizar.kit.AttachmentPromptAttempt
import me.shirazu.mizar.kit.AttachmentScope
import me.shirazu.mizar.kit.AttachmentSource
import me.shirazu.mizar.kit.AttachmentUploader
import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.MizarError
import me.shirazu.mizar.kit.PromptResult
import me.shirazu.mizar.kit.attachmentRpc
import java.util.UUID

/** A view-only row: no local path, URI, or credential leaks into the UI. */
data class PendingAttachment(
    val id: String,
    val name: String,
    val bytes: Long = 0,
    val progress: Long = 0,
    val status: String = "Waiting",
    /** Bounded <=128 px sampled preview, never the full image or its private path. */
    val thumbnail: Bitmap? = null,
)

data class AttachmentComposerState(
    val scope: AttachmentScope? = null,
    val text: String = "",
    val items: List<PendingAttachment> = emptyList(),
    val busy: Boolean = false,
    val staging: Boolean = false,
    val locked: Boolean = false,
    val error: String? = null,
) {
    val canSend: Boolean get() = scope != null && !busy && (text.isNotBlank() || items.isNotEmpty())
}

/**
 * Drafts are kept per authenticated host/device/session for this process, never transferred on
 * navigation. A failed admission is immutable until retried or explicitly discarded. Process
 * death drops drafts; private staging is swept on next use, while host objects expire by TTL.
 */
class AttachmentComposer(
    context: Context,
    private val jobs: CoroutineScope,
    private val connectionFor: (AttachmentScope) -> MizarConnection?,
    private val onAccepted: suspend (AttachmentScope, String) -> Unit,
) {
    private class Item(val id: String, val source: AttachmentSource, scope: AttachmentScope, thumbnail: Bitmap?) {
        val checkpoint = AttachmentCheckpoint(scope)
        var row = PendingAttachment(id, source.name, source.bytes, thumbnail = thumbnail)
    }
    private class Draft(val owner: AttachmentScope) {
        var text = ""
        val items = mutableListOf<Item>()
        var pending: PendingAttachment? = null
        var attempt: AttachmentPromptAttempt? = null
        var error: String? = null
        var job: Job? = null
        var busy = false
        var staging = false
        var disposed = false
        var lastConnection: MizarConnection? = null
    }

    private val staging = AttachmentStaging(context.applicationContext)
    private val drafts = mutableMapOf<AttachmentScope, Draft>()
    private var active: AttachmentScope? = null
    private val _state = MutableStateFlow(AttachmentComposerState())
    val state: StateFlow<AttachmentComposerState> = _state.asStateFlow()

    init {
        // Sweep process-death leftovers at launch, not only after the next picker selection.
        jobs.launch(Dispatchers.IO) { runCatching { staging.prepare() } }
    }

    fun activate(owner: AttachmentScope?) {
        if (owner == active) return
        active?.let { drafts[it] }?.let { draft ->
            if (draft.busy) draft.job?.cancel()
        }
        active = owner
        if (owner != null) drafts.getOrPut(owner) { Draft(owner) }
        publish()
    }

    private fun publish() {
        val draft = active?.let { drafts[it] }
        _state.value = if (draft == null) AttachmentComposerState() else AttachmentComposerState(
            scope = draft.owner,
            text = draft.text,
            items = draft.items.map { it.row } + listOfNotNull(draft.pending),
            busy = draft.busy,
            staging = draft.staging,
            locked = draft.attempt != null,
            error = draft.error,
        )
    }

    fun setText(text: String) {
        val draft = active?.let { drafts[it] } ?: return
        if (draft.busy || draft.attempt != null) return
        draft.text = text
        draft.error = null
        publish()
    }

    /** The UI captures owner at launch, so a picker result can never land in another session. */
    fun add(owner: AttachmentScope?, uris: List<Uri>, photo: Boolean) {
        if (owner == null || owner != active || uris.isEmpty()) return
        val draft = drafts[owner] ?: return
        if (draft.busy || draft.attempt != null) return
        val slots = ATTACHMENT_MAX_COUNT - draft.items.size
        if (uris.size > slots) {
            draft.error = "At most 5 attachments per message; choose $slots or fewer"
            publish()
            return
        }
        draft.busy = true
        draft.staging = true
        draft.error = null
        publish()
        draft.job = jobs.launch {
            try {
                for (uri in uris.distinct()) {
                    val id = UUID.randomUUID().toString()
                    draft.pending = PendingAttachment(id, "Copying selected ${if (photo) "photo" else "file"}", status = "Copying to private storage")
                    publish()
                    // Adopt the file inside IO before its result crosses a cancellable dispatcher boundary.
                    var staged: AttachmentSource? = null
                    var thumbnail: Bitmap? = null
                    var adopted = false
                    try {
                        withContext(Dispatchers.IO) {
                            staged = staging.stage(uri, photo) { copied ->
                                jobs.launch {
                                    if (!draft.disposed && draft.pending?.id == id) {
                                        draft.pending = draft.pending?.copy(progress = copied)
                                        publish()
                                    }
                                }
                            }
                            thumbnail = staged?.takeIf { it.kind == "image" }?.let { sampledThumbnail(it) }
                        }
                        currentCoroutineContext().ensureActive()
                        if (draft.disposed) throw CancellationException("Draft discarded")
                        draft.items += Item(id, requireNotNull(staged), owner, thumbnail)
                        adopted = true
                    } finally {
                        if (!adopted) withContext(NonCancellable + Dispatchers.IO) { staged?.file?.delete() }
                        draft.pending = null
                    }
                }
            } catch (cancelled: CancellationException) {
                draft.error = "Copy cancelled; completed attachments were kept"
                throw cancelled
            } catch (error: Exception) {
                draft.error = error.message ?: "Could not copy the selected attachment"
            } finally {
                draft.busy = false
                draft.staging = false
                publish()
            }
        }
    }

    fun send() {
        val draft = active?.let { drafts[it] } ?: return
        if (draft.busy || (draft.text.isBlank() && draft.items.isEmpty())) return
        val socket = connectionFor(draft.owner)
        if (socket == null) {
            draft.error = "Not connected to this host; draft kept. Reconnect and retry."
            publish()
            return
        }
        draft.busy = true
        draft.error = null
        draft.lastConnection = socket
        publish()
        draft.job = jobs.launch {
            var accepted = false
            try {
                val uploader = AttachmentUploader(socket.attachmentRpc())
                if (draft.attempt == null) {
                    val ids = if (draft.items.isEmpty()) emptyList() else {
                        val limits = uploader.capabilities()
                        require(draft.items.size <= limits.maxAttachments) { "This host allows only ${limits.maxAttachments} attachments" }
                        draft.items.map { item ->
                            item.row = item.row.copy(status = "Uploading")
                            publish()
                            val ready = withContext(Dispatchers.IO) {
                                uploader.upload(draft.owner, item.source, limits, item.checkpoint) { bytes ->
                                    jobs.launch {
                                        if (!draft.disposed) {
                                            item.row = item.row.copy(progress = bytes)
                                            publish()
                                        }
                                    }
                                }
                            }
                            item.row = item.row.copy(progress = item.source.bytes, status = "Uploaded")
                            publish()
                            ready.attachmentId
                        }
                    }
                    // Freeze BEFORE sending, including text-only submissions: a lost ack never creates a new ID.
                    draft.attempt = AttachmentPromptAttempt(draft.owner, draft.text, ids)
                    publish()
                }
                currentCoroutineContext().ensureActive()
                val attempt = requireNotNull(draft.attempt)
                val result = socket.call<PromptResult>("session.prompt", attempt.payload(draft.owner))
                if (!result.accepted) throw IllegalStateException("Host did not accept the message; draft kept for retry")
                accepted = true
                val sources = draft.items.map { it.source }
                draft.items.clear()
                draft.text = ""
                draft.attempt = null
                draft.error = null
                withContext(NonCancellable + Dispatchers.IO) { sources.forEach { it.file.delete() } }
                // Do not abort accepted IDs: the host may still be reading them into DSH.
                if (active == draft.owner && !draft.disposed) onAccepted(draft.owner, attempt.text)
            } catch (cancelled: CancellationException) {
                if (!accepted) draft.error = if (draft.attempt != null) "Delivery may have succeeded. Retry sends the same request ID; do not resend as a new message."
                    else "Upload paused; attachments kept for retry"
                throw cancelled
            } catch (error: Exception) {
                draft.error = when {
                    accepted -> "Message accepted; refresh the transcript to view it"
                    error is MizarError && error.code == "NOT_IMPLEMENTED" ->
                        "This host does not support attachments. Install the attachment-enabled Mizar plugin; draft kept."
                    draft.attempt != null ->
                        "${error.message ?: "Message failed"}. Draft kept: retry uses the same request ID. If expired, check the host transcript before discarding."
                    else -> "${error.message ?: "Upload failed"}. Attachments kept; retry or remove them."
                }
            } finally {
                draft.busy = false
                publish()
            }
        }
    }

    /** Cancellation before admission is resumable. Admission already sent remains locked/uncertain. */
    fun pause() {
        active?.let { drafts[it] }?.job?.cancel()
    }

    fun remove(id: String) {
        val draft = active?.let { drafts[it] } ?: return
        if (draft.attempt != null) return // Editing an uncertain admission would violate idempotency.
        val item = draft.items.firstOrNull { it.id == id } ?: return
        val oldJob = draft.job
        oldJob?.cancel()
        draft.items.remove(item)
        draft.error = null
        publish()
        val socket = connectionFor(draft.owner) ?: draft.lastConnection
        jobs.launch {
            oldJob?.join()
            release(listOf(item), socket)
        }
    }

    /** Explicit confirmation is shown by the UI when delivery might already have happened. */
    fun discard() {
        val owner = active ?: return
        val draft = drafts.remove(owner) ?: return
        draft.disposed = true
        val socket = connectionFor(owner) ?: draft.lastConnection
        drafts[owner] = Draft(owner)
        draft.job?.cancel()
        publish()
        jobs.launch {
            draft.job?.join()
            release(draft.items.toList(), socket)
        }
    }

    fun forgetHost(hostId: String) {
        val removed = drafts.filterKeys { it.hostId == hostId }.values.toList()
        removed.forEach { draft ->
            drafts.remove(draft.owner)
            draft.disposed = true
            draft.job?.cancel()
            val socket = connectionFor(draft.owner) ?: draft.lastConnection
            jobs.launch { draft.job?.join(); release(draft.items.toList(), socket) }
        }
        if (active?.hostId == hostId) active = null
        publish()
    }

    private suspend fun release(items: List<Item>, socket: MizarConnection?) = withContext(NonCancellable) {
        // Local removal never waits for network cleanup. Remote cleanup is best effort; TTL is the fallback.
        withContext(Dispatchers.IO) { items.forEach { it.source.file.delete() } }
        if (socket != null) withTimeoutOrNull(3_000) {
            val uploader = AttachmentUploader(socket.attachmentRpc())
            items.forEach { runCatching { uploader.abort(it.checkpoint) } }
        }
    }

    private fun sampledThumbnail(source: AttachmentSource): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
        BitmapFactory.decodeFile(source.file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }.getOrNull()

    fun close() {
        drafts.values.forEach { draft ->
            draft.disposed = true
            draft.job?.cancel()
            // In-flight staging has its own finally. Existing files are private and bounded (<=100 MiB).
            draft.items.forEach { it.source.file.delete() }
        }
        drafts.clear()
    }
}
