package me.shirazu.dshmizar.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.ThemeMode
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.MizarMarkConnecting
import me.shirazu.dshmizar.ui.design.Radius
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.pressable
import me.shirazu.mizar.kit.SessionGrouping

/**
 * The settings sheet, the host list, and the new-session sheet -- restyled on
 * iOS `Views/SettingsView.swift`: what this is first (the icon, the name, the
 * paired computer), then what this phone does (theme, quiet hours), then what
 * it knows (the paired computers).
 */

/** Paired computers, notification state, and the way out. */
@Composable
fun SettingsDialog(
    state: UiState,
    onDismiss: () -> Unit,
    onForget: (String) -> Unit,
    onAddHost: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onQuietHours: (Boolean) -> Unit,
) {
    DialogShell(title = stringResource(R.string.settings), onDismiss = onDismiss) {
        // What this is, before what it does: the icon, the name, the pairing.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Radius.md)),
                contentAlignment = Alignment.Center,
            ) {
                MizarMarkConnecting(
                    modifier = Modifier.width(36.dp),
                    accent = MaterialTheme.colorScheme.primary,
                    ink = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    "DSH-Mizar",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    state.host?.let { stringResource(R.string.paired_with, it.displayName) }
                        ?: stringResource(R.string.unpaired_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        // The palette is a preference, and the operator should not have to change
        // the system to get a dark screen at night.
        SectionLabel(stringResource(R.string.theme))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Radius.sm))
                .padding(3.dp),
            horizontalArrangement = Arrangement4,
        ) {
            ThemeSegment(stringResource(R.string.theme_system), state.themeMode == ThemeMode.SYSTEM, Modifier.weight(1f)) { onTheme(ThemeMode.SYSTEM) }
            ThemeSegment(stringResource(R.string.theme_light), state.themeMode == ThemeMode.LIGHT, Modifier.weight(1f)) { onTheme(ThemeMode.LIGHT) }
            ThemeSegment(stringResource(R.string.theme_dark), state.themeMode == ThemeMode.DARK, Modifier.weight(1f)) { onTheme(ThemeMode.DARK) }
        }

        SectionLabel(stringResource(R.string.notifications))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(Radius.md))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.quiet_hours),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.quietHours, onCheckedChange = onQuietHours)
        }

        SectionLabel(stringResource(R.string.hosts_section))
        for (host in state.hosts) {
            ListItem(
                headlineContent = { Text(host.displayName) },
                supportingContent = { Text(host.authority ?: host.baseUrl.orEmpty()) },
                leadingContent = { Icon(Icons.Default.Computer, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = { onForget(host.hostId) }) { Text(stringResource(R.string.forget)) }
                },
            )
        }
        TextButton(onClick = onAddHost, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.add_mac))
        }
        Text(
            text = stringResource(R.string.settings_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private val Arrangement4 = Arrangement.spacedBy(4.dp)

/** One theme option in the segmented row. */
@Composable
private fun ThemeSegment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .pressable(scale = 0.95f)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) MizarColors.OnAccent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 6.dp),
    )
}

/** Pick the machine to open. */
@Composable
fun HostPickerDialog(state: UiState, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    DialogShell(title = stringResource(R.string.choose_mac), onDismiss = onDismiss) {
        for (host in state.hosts) {
            ListItem(
                headlineContent = { Text(host.displayName) },
                supportingContent = { Text(host.hostName) },
                leadingContent = { Icon(Icons.Default.Computer, contentDescription = null) },
                trailingContent = {
                    if (host.hostId == state.host?.hostId) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                },
                modifier = Modifier.clickable { onOpen(host.hostId) },
            )
        }
    }
}

/** Start a session in a directory, which the host turns into a workspace. */
@Composable
fun NewSessionDialog(state: UiState, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    val recent = remember(state.sessions) {
        state.sessions.mapNotNull { it.cwd }.distinct()
    }

    DialogShell(title = stringResource(R.string.new_session), onDismiss = onDismiss) {
        // A plain column, not a LazyColumn: the window this lives in already
        // scrolls, and a lazy list inside a scrolling column never measures.
        for (directory in recent) {
            ListItem(
                headlineContent = { Text(SessionGrouping.lastComponent(directory)) },
                supportingContent = {
                    Text(directory, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                },
                modifier = Modifier.clickable { onCreate(directory) },
            )
        }
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text(stringResource(R.string.directory_label)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Text(
            text = stringResource(R.string.new_session_note),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Button(
            onClick = { onCreate(path.trim()) },
            enabled = path.isNotBlank() && !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(Radius.md),
        ) {
            Text(stringResource(R.string.start_session))
        }
    }
}

/** A window's header: the title and a close button. Mounted inside an
 *  `AnchoredPopover` at the control that opened it -- no sheet anywhere. */
@Composable
internal fun DialogShell(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = null) }
    }
    content()
    Spacer(Modifier.height(12.dp))
}
