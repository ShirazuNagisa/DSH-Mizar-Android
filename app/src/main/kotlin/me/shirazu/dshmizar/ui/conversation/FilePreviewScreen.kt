package me.shirazu.dshmizar.ui.conversation

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.FilePreviewUiState
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.design.Corner

/**
 * One file a turn handed over, drawn on the phone.
 *
 * Five states and it says which one it is in: loading, an image, text, "this
 * phone cannot draw that", and "the read failed". The third is not an error --
 * the file exists and the computer can open it -- so it gets a sentence about the
 * file rather than a red one about the network.
 */
@Composable
internal fun FilePreviewScreen(preview: FilePreviewUiState, onClose: () -> Unit) {
    BackHandler(onBack = onClose)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        preview.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                    )
                    Text(
                        preview.path,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            ) {
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
                                stringResource(
                                    R.string.file_preview_pdf_pages,
                                    preview.pages.size,
                                    preview.pageCount,
                                ),
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
                        val bitmap = remember(bytes) {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                        if (bitmap == null) {
                            Notice(stringResource(R.string.file_preview_failed))
                        } else {
                            // Filled to the width and held to the file's own ratio:
                            // a small image left at its intrinsic size is a stamp in
                            // the corner of a page, and a large one has to shrink.
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
    }
}

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
