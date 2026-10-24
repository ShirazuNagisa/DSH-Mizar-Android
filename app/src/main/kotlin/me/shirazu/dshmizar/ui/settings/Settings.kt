package me.shirazu.dshmizar.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import me.shirazu.dshmizar.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.foundation.border
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.ThemeMode
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.ButtonShape
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.shapedClickable
import me.shirazu.dshmizar.ui.design.MizarMarkConnecting
import me.shirazu.dshmizar.ui.design.PopoverRow
import me.shirazu.dshmizar.ui.design.SegmentedControl
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.pairing.NamingDialog
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
    onOpenAccount: () -> Unit,
    onOpenBalance: () -> Unit,
    onForget: (String) -> Unit,
    onAddHost: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onQuietHours: (Boolean) -> Unit,
    onNotifyCompleted: (Boolean) -> Unit,
    onNotifyInterrupted: (Boolean) -> Unit,
    onNotifyQuestions: (Boolean) -> Unit,
    onNotifyApprovals: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onSlowTypewriter: (Boolean) -> Unit,
    onLocalNetworkFirst: (Boolean) -> Unit,
    onExportLogs: ((File?) -> Unit) -> Unit,
    onRename: (String, String?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    // The whole window scrolls as one: the header is part of it, because a settings
    // list that grows must not make the rows below the fold unreachable. Drawn like
    // the workspace list -- one column, dragged vertically.
    // The popup measures its content unbounded, and a scrollable column cannot be
    // measured that way; a height here is what makes the window scroll instead of
    // crash -- the same reason a LazyColumn needs one inside a popup.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = SETTINGS_WINDOW_HEIGHT)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        // What this is, before what it does: the icon, the name, the pairing.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Corner.md),
                contentAlignment = Alignment.Center,
            ) {
                MizarMarkConnecting(
                    modifier = Modifier.width(28.dp),
                    accent = MaterialTheme.colorScheme.primary,
                    ink = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    stringResource(R.string.app_name),
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

        // The account is optional, so it sits above the preferences rather than
        // among them: it is identity, not a setting.
        SectionLabel(stringResource(R.string.account))
        me.shirazu.dshmizar.ui.account.AccountRow(state.account, onClick = onOpenAccount)

        // Under the account, because it is the other half of the same question: who
        // this phone is, and what the computer it talks to has to spend. The figure
        // belongs to that computer's DeepSeek account, never to the relay.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .shapedClickable(Corner.md, onClick = onOpenBalance)
                .padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.AccountBalanceWallet,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.balance),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }

        // What the paid plan buys, and how it is obtained on this platform. It sits
        // under the balance because both answer "what does money do here".
        var showPaidDetail by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .shapedClickable(Corner.md, onClick = { showPaidDetail = true })
                .padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.paid_detail_title),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        if (showPaidDetail) {
            me.shirazu.dshmizar.ui.account.PaidDetailDialog(onDismiss = { showPaidDetail = false })
        }

        // The palette is a preference, and the operator should not have to change
        // the system to get a dark screen at night.
        SectionLabel(stringResource(R.string.theme))
        val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
        SegmentedControl(
            options = listOf(
                stringResource(R.string.theme_system),
                stringResource(R.string.theme_light),
                stringResource(R.string.theme_dark),
            ),
            selected = modes.indexOf(state.themeMode),
            onSelect = { onTheme(modes[it]) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        )

        SectionLabel(stringResource(R.string.notifications))
        // The system can still veto every one of the switches below; saying so here
        // is the difference between "the app is broken" and "notifications are off".
        val allowed = remember { NotificationManagerCompat.from(context).areNotificationsEnabled() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (allowed) R.string.system_notifications_on else R.string.system_notifications_off),
                style = MaterialTheme.typography.bodySmall,
                color = if (allowed) MaterialTheme.colorScheme.onSurfaceVariant else MizarColors.Warning,
                modifier = Modifier.weight(1f),
            )
        }
        SettingToggle(stringResource(R.string.quiet_hours), state.quietHours, onQuietHours)
        SettingToggle(stringResource(R.string.notify_completed), state.notifyCompleted, onNotifyCompleted)
        SettingToggle(stringResource(R.string.notify_interrupted), state.notifyInterrupted, onNotifyInterrupted)
        SettingToggle(stringResource(R.string.notify_questions), state.notifyQuestions, onNotifyQuestions)
        SettingToggle(stringResource(R.string.notify_approvals), state.notifyApprovals, onNotifyApprovals)

        SectionLabel(stringResource(R.string.haptics))
        SettingToggle(stringResource(R.string.haptics_enabled), state.hapticsEnabled, onHaptics)

        // Where the bytes travel, and what that unlocks. The relay works from
        // anywhere; the LAN is faster and is what makes the running-turn detail and
        // attachments available without an account.
        SectionLabel(stringResource(R.string.transfer))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.local_network_first),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.local_network_first_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.localNetworkFirst, onCheckedChange = onLocalNetworkFirst)
        }

        SectionLabel(stringResource(R.string.hosts_section))
        var renaming by remember { mutableStateOf<me.shirazu.mizar.kit.PairedHost?>(null) }
        for (host in state.hosts) {
            // Measured like the workspace rows, not like the popover's list rows: this
            // window is where settings will keep growing, so every row is compact
            // enough to leave room for the ones that are coming.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(host.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        host.authority ?: host.baseUrl.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    stringResource(R.string.rename),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { renaming = host }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                Text(
                    stringResource(R.string.forget),
                    style = MaterialTheme.typography.labelMedium,
                    color = MizarColors.Failure,
                    modifier = Modifier
                        .clickable { onForget(host.hostId) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        renaming?.let { host ->
            NamingDialog(
                machineName = host.hostName ?: host.displayName,
                onSave = { label ->
                    renaming = null
                    onRename(host.hostId, label)
                },
                onSkip = { renaming = null },
            )
        }
        TextButton(onClick = onAddHost, modifier = Modifier.padding(horizontal = 6.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.add_mac))
        }

        SectionLabel(stringResource(R.string.about))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.app_version, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = stringResource(R.string.source_code),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ShirazuNagisa/DSH-Mizar-Android")),
                        )
                    }
                }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Text(
            text = stringResource(R.string.acknowledgements),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )

        SectionLabel(stringResource(R.string.diagnostics))
        SettingToggle(stringResource(R.string.slow_typewriter), state.slowTypewriter, onSlowTypewriter)
        TextButton(
            onClick = {
                onExportLogs { file ->
                    if (file == null) {
                        Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        shareArchive(context, file)
                    }
                }
            },
            modifier = Modifier.padding(horizontal = 6.dp),
        ) {
            Text(stringResource(R.string.export_logs))
        }

        Text(
            text = stringResource(R.string.settings_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}


/** Hand the diagnostics zip to whichever app the operator picks. */
private fun shareArchive(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.export_logs)))
    }.onFailure {
        Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
    }
}

/** One compact switch row in the notifications section. */
@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
            .padding(horizontal = 10.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionLabel(text: String) {    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 10.dp).padding(top = 10.dp, bottom = 4.dp),
    )
}

/** Pick the machine to open. */
@Composable
fun HostPickerDialog(state: UiState, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    DialogShell(title = stringResource(R.string.choose_mac), onDismiss = onDismiss) {
        for (host in state.hosts) {
            PopoverRow(
                title = host.displayName,
                subtitle = host.hostName,
                onClick = { onOpen(host.hostId) },
                trailing = {
                    if (host.hostId == state.host?.hostId) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
            )
        }
    }
}

/** Start a session in a directory, which the host turns into a workspace. */
@Composable
fun NewSessionDialog(state: UiState, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    // The host's own workspace list, with the names the operator gave them. The
    // session-derived list is only a fallback: a workspace nobody has started a
    // session in yet was unreachable here, and the title it showed was invented
    // from the path.
    val recent = remember(state.workspaces, state.sessions) {
        if (state.workspaces.isNotEmpty()) {
            state.workspaces.map { it.path to it.title }
        } else {
            state.sessions.mapNotNull { it.cwd }.distinct()
                .map { it to SessionGrouping.lastComponent(it) }
        }
    }

    DialogShell(title = stringResource(R.string.new_session), onDismiss = onDismiss) {
        // Plain rows, measured like the workspace list the window floats beside.
        for ((directory, title) in recent) {
            PopoverRow(
                title = title,
                subtitle = directory,
                onClick = { onCreate(directory) },
            )
        }
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text(stringResource(R.string.directory_label)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )
        Text(
            text = stringResource(R.string.new_session_note),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Button(
            onClick = { onCreate(path.trim()) },
            enabled = path.isNotBlank() && !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = ButtonShape.Primary,
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
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        // The default touch target is 48dp, which is a whole row in a window this
        // short; 36 keeps it a comfortable target without owning the header.
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
    content()
    Spacer(Modifier.height(8.dp))
}

/** How tall the settings window grows before it starts scrolling. */
private val SETTINGS_WINDOW_HEIGHT = 360.dp