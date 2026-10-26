package me.shirazu.dshmizar.ui.conversation

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.FilePreviewUiState
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.design.Corner

/**
 * One file a turn handed over, drawn in the side plane.
 *
 * Five states and it says which one it is in: loading, an image, a PDF's pages,
 * text, "this phone cannot draw that", and "the read failed". The fourth is not an
 * error -- the file exists and the computer can open it -- so it gets a sentence
 * about the file rather than a red one about the network.
 *
 * A pane rather than a screen: the preview lives on the right-hand plane's third
 * page, next to the turn index and the subagents, the way the wide layout and iOS
 * both put it.
 */
@Composable
internal fun FilePreviewPane(preview: FilePreviewUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 24.dp),
    ) {
        if (!preview.open) {
            Text(
                stringResource(R.string.side_page_preview_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            )
            return@Column
        }

        Text(
            preview.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Text(
            preview.path,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(10.dp))

        when {
            preview.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.file_preview_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

            preview.failed -> Notice(stringResource(R.string.file_preview_failed))

            preview.pages.isNotEmpty() -> {
                preview.pages.forEachIndexed { index, page ->
                    Image(
                        bitmap = page.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (index == 0) 0.dp else 10.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md),
                    )
                }
                if (preview.pageCount > preview.pages.size) {
                    Text(
                        stringResource(R.string.file_preview_pdf_pages, preview.pages.size, preview.pageCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            preview.unsupported -> Notice(
                stringResource(R.string.file_preview_unsupported, formatBytes(preview.byteSize)),
            )

            preview.image != null -> {
                val bytes = preview.image
                val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bitmap == null) {
                    Notice(stringResource(R.string.file_preview_failed))
                } else {
                    // Filled to the width and held to the file's own ratio: a small
                    // image left at its intrinsic size is a stamp in the corner.
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md),
                    )
                }
            }

            preview.text != null -> {
                Text(
                    preview.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
                        .padding(12.dp),
                )
                if (preview.truncated) {
                    Text(
                        stringResource(R.string.file_preview_truncated, formatBytes(preview.byteSize)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** One quiet line, for the states that are a sentence rather than a document. */
@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
            .padding(14.dp),
    )
}

/** Bytes in the units a person reads: KB and MB, one decimal above a thousand. */
private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
