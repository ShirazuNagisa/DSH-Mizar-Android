package me.shirazu.dshmizar.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import me.shirazu.dshmizar.domain.AgentCatalog
import me.shirazu.dshmizar.domain.AgentEngine
import me.shirazu.dshmizar.domain.AgentOption
import me.shirazu.dshmizar.domain.AgentSurface
import me.shirazu.dshmizar.domain.AgentTarget
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.Glass
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.pressable
import me.shirazu.dshmizar.ui.design.shapedClickable

/**
 * The agent capsule and the window behind it.
 *
 * Ported from iOS `Views/AgentSwitcher.swift`. A desktop fronts DSH, Claude Code
 * and Codex at once, and one connection is always exactly one of the five
 * destinations -- there is no mixed list, because the three share no sessions and a
 * prompt sent to the wrong agent is the failure this exists to prevent.
 *
 * Stateless, both of them: the capsule renders nothing when the host serves one
 * agent (nothing to switch), and the window lists what [AgentCatalog] says the
 * computer hosts. The Lead mounts the capsule over the session list and the window
 * in an `AnchoredPopover`, and passes `AppViewModel`-backed values.
 */

/**
 * The pill that names the agent this connection is on.
 *
 * Draws nothing at all when there is nothing to switch, so the Lead can mount it
 * unconditionally: an old host, or one fronting a single agent, reads exactly like
 * a computer without a picker.
 */
@Composable
fun AgentCapsule(
    catalog: AgentCatalog,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!catalog.showsPicker) return
    val current = catalog.currentTarget
    Row(
        modifier = modifier
            .height(32.dp)
            .background(Glass.fill(), Corner.lg)
            .border(Glass.Hairline, MaterialTheme.colorScheme.outlineVariant, Corner.lg)
            .pressable()
            .shapedClickable(Corner.lg, onClick = onClick)
            .padding(horizontal = 11.dp)
            .testTag("agent.capsule"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    if (catalog.isAvailable(current)) MizarColors.Running
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = capsuleLabel(current),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The capsule is often given the list's full width; the chevron belongs at
        // the far end of it, not a breath after the label.
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Default.KeyboardArrowUp,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The picker: one row per destination, the current one ticked, the ones this
 * computer cannot serve dimmed and refused.
 *
 * Mount inside an `AnchoredPopover` whose anchor is the capsule; the window is the
 * popover's own chrome, so this draws only the rows.
 */
@Composable
fun AgentSwitcher(
    catalog: AgentCatalog,
    onPick: (AgentTarget) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.testTag("agent.switcher")) {
        Text(
            text = stringResource(R.string.shell_agent_which),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
        for (option in catalog.options()) {
            AgentRow(
                option = option,
                onPick = onPick,
                onDismiss = onDismiss,
            )
        }
    }
}

/** One agent, ticked when it is the one on screen. */
@Composable
private fun AgentRow(
    option: AgentOption,
    onPick: (AgentTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val target = option.target
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (option.available) {
                    Modifier.clickable {
                        // Put the window away first: the redial that follows takes
                        // the list with it, and a window floating over a screen that
                        // is already changing reads as a tap that did not land.
                        onDismiss()
                        onPick(target)
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("agent.switch.${target.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .then(
                    if (option.available) {
                        Modifier.background(MizarColors.Running)
                    } else {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    },
                ),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = agentTitle(target),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (option.current) FontWeight.SemiBold else FontWeight.Normal,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (option.available) {
                    agentDetail(target)
                } else {
                    stringResource(R.string.shell_agent_offline)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (option.current) {
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

/** The capsule's label: the short name, with the experimental marker when it has one. */
@Composable
private fun capsuleLabel(target: AgentTarget): String =
    if (target.isExperimental) {
        "${target.shortTitle} ${stringResource(R.string.shell_agent_experimental)}"
    } else {
        target.shortTitle
    }

/** The product's name, with the marker the picker puts beside an experimental one. */
@Composable
private fun agentTitle(target: AgentTarget): String =
    if (target.isExperimental) {
        "${target.shortTitle} ${stringResource(R.string.shell_agent_experimental)}"
    } else {
        target.shortTitle
    }

/**
 * One line about what the destination holds.
 *
 * The words live in the string catalogue rather than in the domain model, because
 * the domain model also runs in unit tests and the details are the only part of an
 * agent row a translator would ever need.
 */
@Composable
private fun agentDetail(target: AgentTarget): String = when (target.engine) {
    AgentEngine.DSH -> stringResource(R.string.shell_agent_detail_dsh)
    AgentEngine.CLAUDE -> when (target.surface) {
        AgentSurface.GUI -> stringResource(R.string.shell_agent_detail_claude_gui)
        AgentSurface.CLI -> stringResource(R.string.shell_agent_detail_claude_cli)
        null -> stringResource(R.string.shell_agent_detail_claude)
    }
    AgentEngine.CODEX -> when (target.surface) {
        AgentSurface.GUI -> stringResource(R.string.shell_agent_detail_codex_gui)
        AgentSurface.CLI -> stringResource(R.string.shell_agent_detail_codex_cli)
        null -> stringResource(R.string.shell_agent_detail_codex)
    }
}
