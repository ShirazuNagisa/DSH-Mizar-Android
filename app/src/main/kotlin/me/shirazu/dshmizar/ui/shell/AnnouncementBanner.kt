package me.shirazu.dshmizar.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.domain.AnnouncementAction
import me.shirazu.dshmizar.domain.AnnouncementBannerState
import me.shirazu.dshmizar.domain.action
import me.shirazu.dshmizar.domain.isNotice
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.ButtonShape
import me.shirazu.dshmizar.ui.design.LocalReduceMotion
import me.shirazu.dshmizar.ui.design.Motion
import me.shirazu.dshmizar.ui.design.Radius
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.glassCard
import me.shirazu.mizar.kit.account.Announcement
import me.shirazu.mizar.kit.account.AnnouncementButton

/**
 * The banner the operator's message arrives on.
 *
 * Drawn in the app's own materials rather than as an alert: a glass card under the
 * status bar, an accent-coloured symbol, the sentence, and -- only when the server
 * asked for one and this build knows where it points -- a single button that opens a
 * settings page or a link. A banner is not a decision the operator has to answer
 * before using the app, so nothing here blocks, dims or takes focus.
 *
 * Two composables: [AnnouncementBanner] is the card, drawn from explicit props;
 * [AnnouncementBannerHost] is the placement and the entrance/exit motion that a shell
 * mounts once. The English and Chinese copy lives in the two `strings_announce.xml`
 * resource files.
 */

/** The card itself: one banner, no state of its own. */
@Composable
fun AnnouncementBanner(
    announcement: Announcement,
    onAction: (AnnouncementAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val action = announcement.action()
    val button = announcement.button
    // `notice` is the waiting colour; `info` (and an unknown tone) the accent.
    val tone = if (announcement.isNotice) MizarColors.Waiting else MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .glassCard(radius = Radius.card, clip = true)
            .padding(Space.lg)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("announcement.banner"),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(tone, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Campaign,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MizarColors.OnAccent,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = announcement.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("announcement.title"),
            )
            if (announcement.body.isNotBlank()) {
                Spacer(Modifier.height(Space.xxs))
                Text(
                    text = announcement.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("announcement.body"),
                )
            }
            if (action != null && button != null) {
                Spacer(Modifier.height(Space.sm))
                Button(
                    onClick = {
                        onAction(action)
                        onDismiss()
                    },
                    shape = ButtonShape.Inline,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.xs),
                    modifier = Modifier.testTag("announcement.action"),
                ) {
                    Text(
                        text = button.label,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(28.dp)
                .testTag("announcement.close"),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.announcement_dismiss),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Where the banner lives: the top of the shell, under the status bar, with the
 * entrance and exit motion the rest of the app uses.
 *
 * It drops in from above and fades out; with Reduce Motion it only fades. It holds
 * no business state -- the queue lives in `AnnouncementCoordinator` -- but it does
 * remember the last banner it was handed so the exit animation has something to draw
 * while the coordinator has already moved on.
 */
@Composable
fun AnnouncementBannerHost(
    state: AnnouncementBannerState,
    onAction: (AnnouncementAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Remember the last banner so the exit animation has something to draw once the
    // coordinator has already moved on. Written after composition rather than during
    // it: the state belongs to the coordinator, not to this host.
    var last by remember { mutableStateOf(state.announcement) }
    androidx.compose.runtime.SideEffect { state.announcement?.let { last = it } }
    val reduceMotion = LocalReduceMotion.current
    val enter = if (reduceMotion) {
        fadeIn(Motion.fade())
    } else {
        slideInVertically(Motion.bouncy<IntOffset>()) { height -> -height } + fadeIn(Motion.smooth())
    }
    val exit = if (reduceMotion) {
        fadeOut(Motion.fade())
    } else {
        slideOutVertically(tween(180)) { height -> -height } + fadeOut(tween(160))
    }

    AnimatedVisibility(
        visible = state.announcement != null,
        enter = enter,
        exit = exit,
        modifier = modifier,
    ) {
        last?.let { announcement ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = Space.md, vertical = Space.sm),
                contentAlignment = Alignment.TopCenter,
            ) {
                AnnouncementBanner(
                    announcement = announcement,
                    onAction = onAction,
                    onDismiss = onDismiss,
                    modifier = Modifier.widthIn(max = BANNER_MAX_WIDTH),
                )
            }
        }
    }
}

/** On a tablet the banner stops being a full-width strip. */
private val BANNER_MAX_WIDTH = 560.dp

/** A preview needs a banner that did not come off a socket. */
internal fun previewAnnouncement(
    title: String,
    body: String,
    button: AnnouncementButton? = null,
): Announcement = Announcement(
    id = "preview",
    title = title,
    body = body,
    symbol = "megaphone",
    tone = "info",
    button = button,
)
