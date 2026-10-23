package me.shirazu.dshmizar.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.DraftAttachment
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.glassCapsule
import me.shirazu.dshmizar.ui.design.pressable
import me.shirazu.dshmizar.ui.design.shapedClickable

/**
 * The draft's attachments: one chip per file with its upload progress.
 *
 * The two pickers live in the composer's `+` window now — one row of two large
 * targets there, where a thumb already is — so this bar is only the files
 * themselves, and it draws nothing at all until there is one.
 */
@Composable
internal fun AttachmentBar(
    attachments: List<DraftAttachment>,
    onRemove: (String) -> Unit,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(attachments, key = { it.key }) { draft ->
                AttachmentChip(draft, onRemove = { onRemove(draft.key) })
            }
        }
    }
}

@Composable
private fun AttachmentChip(draft: DraftAttachment, onRemove: () -> Unit) {
    val tint = when {
        draft.error != null -> MizarColors.Failure
        draft.readyId != null -> MizarColors.Running
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.sm)
            .padding(start = 9.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(112.dp)) {
            Text(
                draft.name,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                draft.error != null -> Text(
                    draft.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MizarColors.Failure,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                draft.readyId == null -> LinearProgressIndicator(
                    progress = { draft.fraction },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                else -> Unit
            }
        }
        if (draft.readyId == null && draft.error == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp).padding(start = 4.dp),
                strokeWidth = 1.5.dp,
            )
        }
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.remove_attachment),
            modifier = Modifier
                .size(24.dp)
                .shapedClickable(androidx.compose.foundation.shape.CircleShape, onClick = onRemove)
                .padding(5.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
