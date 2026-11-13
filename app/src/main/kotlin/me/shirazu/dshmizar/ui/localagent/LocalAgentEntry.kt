package me.shirazu.dshmizar.ui.localagent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.localagent.LocalAgentStatus
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.settings.SettingsLocalAgent

/**
 * The "local agent" destination in the agent picker.
 *
 * Stateless and self-contained: the Lead mounts it at the end of the existing
 * `AgentSwitcher` popover and passes `AppViewModel`-backed values. It is a separate
 * row rather than a sixth `AgentTarget` because the three desktop agents are scopes
 * of one host and the local kernel is a different host entirely — folding it into
 * `AgentCatalog` would make the switch logic believe a redial can move between them.
 *
 * `onPick` is only wired when the kernel is available; an unavailable row is drawn
 * dimmed with the boot error as its detail, so a failed engine is visible rather than
 * a tap that does nothing.
 */
@Composable
fun LocalAgentSwitcherRow(
    status: LocalAgentStatus?,
    current: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val available = status?.available == true
    val configured = status?.configured == true
    val title = stringResource(
        R.string.localagent_switch_title_format,
        stringResource(R.string.localagent_switch_title),
        stringResource(R.string.localagent_switch_experimental),
    )
    val detail = when {
        !available -> status?.bootError ?: stringResource(R.string.localagent_switch_unavailable)
        !configured -> stringResource(R.string.localagent_switch_unconfigured)
        else -> status?.model ?: stringResource(R.string.localagent_switch_detail)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (available) {
                    Modifier.clickable(onClick = onPick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("agent.switch.local"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .then(
                    when {
                        !available -> Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        // A kernel that runs but has no model is a warning, not an error:
                        // local mode without a provider cannot answer anything.
                        configured -> Modifier.background(MizarColors.Running)
                        else -> Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    },
                ),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) {
            Spacer(Modifier.width(Space.xs))
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The settings page's view of the same facts.
 *
 * One mapping so the settings page (already built by features-shell) and the picker
 * cannot disagree about availability or size.
 */
fun LocalAgentStatus?.toSettingsLocalAgent(): SettingsLocalAgent = SettingsLocalAgent(
    available = this?.available == true,
    configured = this?.configured == true,
    storedBytes = this?.storedBytes,
)
