package me.shirazu.dshmizar.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import me.shirazu.dshmizar.AccountPhase
import me.shirazu.dshmizar.AccountUiState
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.shapedClickable

/**
 * The settings dialog's account row.
 *
 * Signed out it is an invitation; signed in it is the profile, with the plan as
 * the second line. When the service cannot be reached the profile stays and the
 * subtitle says so, which is the whole point of caching it.
 */
@Composable
fun AccountRow(account: AccountUiState, onClick: () -> Unit) {
    val profile = account.profile
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .shapedClickable(Corner.md, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Corner.md),
            contentAlignment = Alignment.Center,
        ) {
            if (profile == null) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    profile.initials,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
                // The same GitHub picture as the account screen; the initials stay
                // underneath as the fallback when the image cannot be fetched.
                profile.avatar.url?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize().clip(Corner.md),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile?.username ?: stringResource(R.string.account_sign_in),
                style = MaterialTheme.typography.bodyLarge,
                color = if (profile == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    profile == null -> stringResource(R.string.account_optional_note)
                    account.phase == AccountPhase.UNREACHABLE -> stringResource(R.string.account_unreachable)
                    profile.isPaid -> stringResource(R.string.account_paid)
                    else -> stringResource(R.string.account_free)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (account.phase == AccountPhase.UNREACHABLE) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
