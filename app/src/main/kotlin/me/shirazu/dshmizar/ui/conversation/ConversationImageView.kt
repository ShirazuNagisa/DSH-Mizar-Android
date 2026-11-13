package me.shirazu.dshmizar.ui.conversation

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.LocalReduceMotion
import me.shirazu.dshmizar.ui.design.Motion
import me.shirazu.dshmizar.ui.design.shapedClickable

/** The pictures of the session on screen; provided by the conversation page. */
val LocalConversationImages = staticCompositionLocalOf<ConversationImagePresenter?> { null }

/** The session whose workspace a picture path is resolved in. */
val LocalConversationSessionId = staticCompositionLocalOf<String?> { null }

/** Hand the conversation's pictures to every message drawn under it. */
@Composable
internal fun ProvideConversationImages(
    presenter: ConversationImagePresenter?,
    sessionId: String?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalConversationImages provides presenter,
        LocalConversationSessionId provides sessionId,
        content = content,
    )
}

/**
 * One inline picture in a message.
 *
 * Four states and it says which one it is in: locked (no request is made), loading,
 * the picture, or a one-line failure with a retry. Tapping the picture opens the
 * lightbox.
 */
@Composable
internal fun ConversationImageBlock(
    target: ConversationImageTarget,
    modifier: Modifier = Modifier,
) {
    val presenter = LocalConversationImages.current
    val sessionId = LocalConversationSessionId.current
    val caption = target.alt?.takeIf { it.isNotBlank() }
    if (presenter == null || sessionId == null || !presenter.unlocked) {
        LockedPictureCard(caption = caption, modifier = modifier)
        return
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sessionId, target) {
        presenter.ensure(sessionId, target, scope)
    }
    val state = presenter.state(sessionId, target)
    when (state) {
        ConversationPictureState.Locked -> LockedPictureCard(caption = caption, modifier = modifier)
        ConversationPictureState.Loading -> LoadingPictureCard(caption = caption, modifier = modifier)
        is ConversationPictureState.Failed -> FailedPictureCard(
            failure = state.failure,
            caption = caption,
            onRetry = { presenter.retry(sessionId, target) },
            modifier = modifier,
        )
        is ConversationPictureState.Loaded -> LoadedPicture(
            bitmap = state.bitmap,
            caption = caption,
            modifier = modifier,
        )
    }
}

/** The gate: no request is made, the card only says how pictures are unlocked. */
@Composable
private fun LockedPictureCard(caption: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Corner.md)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(R.string.conv_image_locked_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(R.string.conv_image_locked_body),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (caption != null) {
            Text(
                text = stringResource(R.string.conv_image_caption, caption),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LoadingPictureCard(caption: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.5.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.conv_image_loading),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PictureCaption(caption)
    }
}

@Composable
private fun FailedPictureCard(
    failure: ConversationImageFailure,
    caption: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Corner.md)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MizarColors.Warning,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(failure.message()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.conv_image_retry), style = MaterialTheme.typography.labelSmall)
            }
        }
        PictureCaption(caption)
    }
}

@Composable
private fun LoadedPicture(bitmap: Bitmap, caption: String?, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val description = caption?.let { stringResource(R.string.conv_image_caption, it) }
        ?: stringResource(R.string.conv_image_open)
    Column(modifier = modifier) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
                .clip(Corner.md)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { open = true }
                .padding(0.dp),
        )
        if (caption != null) {
            Spacer(Modifier.height(4.dp))
            PictureCaption(caption)
        }
    }
    if (open) {
        PictureLightbox(bitmap = bitmap, caption = caption, onClose = { open = false })
    }
}

@Composable
private fun PictureCaption(caption: String?) {
    if (caption == null) return
    Text(
        text = caption,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A failure's one sentence. */
private fun ConversationImageFailure.message(): Int = when (this) {
    ConversationImageFailure.MISSING -> R.string.conv_image_missing
    ConversationImageFailure.FORBIDDEN -> R.string.conv_image_forbidden
    ConversationImageFailure.TOO_LARGE -> R.string.conv_image_too_large
    ConversationImageFailure.OLD_PLUGIN -> R.string.conv_image_old_plugin
    ConversationImageFailure.LOCKED -> R.string.conv_image_locked_title
    ConversationImageFailure.OFFLINE -> R.string.conv_image_offline
    ConversationImageFailure.NOT_AN_IMAGE -> R.string.conv_image_not_image
    ConversationImageFailure.UNREADABLE -> R.string.conv_image_unreadable
}

/**
 * The lightbox: a photo viewer's gestures and nothing else -- pinch or double-tap to
 * zoom, drag to pan, one way out.
 */
@Composable
private fun PictureLightbox(bitmap: Bitmap, caption: String?, onClose: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val closeLabel = stringResource(R.string.conv_image_close)
    val openLabel = stringResource(R.string.conv_image_open)
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .semantics { contentDescription = openLabel },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = caption,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
            )
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = closeLabel,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(18.dp)
                    .size(28.dp)
                    .shapedClickable(CircleShape, onClick = onClose),
            )
        }
    }
}
