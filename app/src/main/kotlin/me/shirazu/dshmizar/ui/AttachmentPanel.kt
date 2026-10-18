package me.shirazu.dshmizar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.attachments.AttachmentComposerState
import me.shirazu.mizar.kit.AttachmentScope

@Composable
internal fun AttachmentPanel(model: AppViewModel, state: AttachmentComposerState) {
    // Save the launch owner, not the currently drawn owner. Late picker results cannot cross scopes.
    var pickerOwner by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    fun captureOwner(): Boolean {
        val owner = state.scope ?: return false
        pickerOwner = arrayListOf(owner.hostId, owner.deviceId, owner.sessionId)
        return true
    }
    fun consumeOwner(): AttachmentScope? {
        val saved = pickerOwner
        pickerOwner = null
        return saved?.takeIf { it.size == 3 }?.let { AttachmentScope(it[0], it[1], it[2]) }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        model.addAttachments(consumeOwner(), uris, photo = true)
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.addAttachments(consumeOwner(), uris, photo = false)
    }
    val canAdd = state.scope != null && !state.busy && !state.locked && state.items.size < 5
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = canAdd, onClick = {
                if (captureOwner()) photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(stringResource(R.string.attach_photos)) }
            TextButton(enabled = canAdd, onClick = {
                if (captureOwner()) documents.launch(arrayOf("*/*"))
            }) { Text(stringResource(R.string.attach_files)) }
            if (state.busy) {
                TextButton(onClick = model::pauseAttachmentWork) { Text(stringResource(R.string.attach_pause)) }
            } else if (state.items.isNotEmpty() || state.locked) {
                TextButton(onClick = { confirmDiscard = true }) { Text(stringResource(R.string.attach_discard)) }
            }
        }
        if (state.items.isNotEmpty()) {
            Column(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                state.items.forEach { item ->
                    Row(Modifier.fillMaxWidth()) {
                        item.thumbnail?.let { preview ->
                            Image(preview.asImageBitmap(), contentDescription = item.name,
                                modifier = Modifier.size(48.dp).padding(4.dp), contentScale = ContentScale.Fit)
                        }
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            val amount = if (item.bytes > 0) "${item.progress / 1024} / ${item.bytes / 1024} KiB" else "${item.progress / 1024} KiB"
                            Text("${item.status} · $amount", style = MaterialTheme.typography.labelSmall)
                            if (item.bytes > 0) {
                                LinearProgressIndicator(
                                    progress = { (item.progress.toFloat() / item.bytes).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else if (state.staging) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        TextButton(enabled = !state.locked && item.bytes > 0, onClick = { model.removeAttachment(item.id) }) {
                            Text(stringResource(R.string.attach_remove))
                        }
                    }
                }
            }
        }
        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 90.dp).verticalScroll(rememberScrollState()))
        }
        if (state.locked && !state.busy) Text(stringResource(R.string.attach_retry_notice), style = MaterialTheme.typography.labelSmall)
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.attach_discard)) },
        text = { Text(stringResource(if (state.locked) R.string.attach_discard_uncertain else R.string.attach_discard_body)) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; model.discardDraft() }) { Text(stringResource(R.string.attach_discard)) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.attach_keep)) } },
    )
}
