package me.shirazu.dshmizar.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.ThemeMode
import me.shirazu.dshmizar.ui.account.AccountRow
import me.shirazu.dshmizar.ui.design.ButtonShape
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.MizarMarkConnecting
import me.shirazu.dshmizar.ui.design.PopoverRow
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.shapedClickable
import me.shirazu.dshmizar.ui.pairing.NamingDialog
import me.shirazu.dshmizar.ui.settings.pages.AboutSettingsPage
import me.shirazu.dshmizar.ui.settings.pages.AccountSettingsPage
import me.shirazu.dshmizar.ui.settings.pages.CommunitySettingsPage
import me.shirazu.dshmizar.ui.settings.pages.FilesSettingsPage
import me.shirazu.dshmizar.ui.settings.pages.LocalAgentSettingsPage
import me.shirazu.dshmizar.ui.settings.pages.LogsSettingsPage
import me.shirazu.dshmizar.ui.settings.pages.NotificationsSettingsPage
import me.shirazu.dshmizar.ui.settings.pages.SendingSettingsPage
import me.shirazu.dshmizar.ui.settings.pages.TransferSettingsPage
import me.shirazu.mizar.kit.SessionGrouping
import java.io.File

/**
 * The settings window: a directory of shelves, one page behind each of them.
 *
 * Ported from iOS `Views/Settings/SettingsView.swift`. The screen is split in two
 * on purpose. The account row stays on the front page, because "who is signed in,
 * and what plan is that" is the first thing somebody looks for in settings and it
 * is not a preference -- it is a fact about the person, and it has to be reachable
 * without knowing which shelf it would be filed under. Everything else is a
 * shelf: a title, a symbol, one line about what is inside, and a page behind it
 * (`SettingsTopic`, and `pages/SettingsPages.kt`).
 *
 * The entry point's signature is the one the sidebar has always called, and it is
 * kept on purpose: the Lead mounts this from `Sidebar.kt` and a refactor of the
 * inside must not cost a wiring change. The optional parameters at the end are the
 * new shelves' data and callbacks; every one has a default, so today's call site
 * compiles unchanged and each can be wired one at a time.
 *
 * @param onReplayOnboarding opens the guide again from About; `null` until wired.
 * @param onReloadSessionData deletes the phone's sessions and reads them from the
 *   computer again (the guide's refresh step, made available afterwards).
 * @param fileCache what the preview cache holds, for the Files page.
 * @param localAgent the on-device agent's state, for its page.
 */
@Composable
fun SettingsDialog(
    state: UiState,
    onDismiss: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenBalance: () -> Unit,
    onOpenRedeem: () -> Unit,
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
    // The new shelves' seams. Defaults keep the current Sidebar call compiling;
    // see the integration list for one-line wiring per parameter.
    onReplayOnboarding: (() -> Unit)? = null,
    /** Opens the questionnaires screen; `null` until wired. */
    onOpenSurveys: (() -> Unit)? = null,
    onReloadSessionData: (() -> Unit)? = null,
    onSteerByDefault: ((Boolean) -> Unit)? = null,
    fileCache: SettingsFileCache? = null,
    onClearFileCache: (() -> Unit)? = null,
    localAgent: SettingsLocalAgent? = null,
    onOpenLocalAgentModels: (() -> Unit)? = null,
    onClearLocalAgentData: (() -> Unit)? = null,
    // A shelf to open as the window appears, when something outside asked for one
    // (an announcement banner's button, a deep link). `null` lands on the directory,
    // which is what the gear means.
    initialTopic: SettingsTopic? = null,
) {
    // A shelf to open as the window appears. Keyed on the request so a second one
    // -- a deep link while the window is already open -- opens its shelf too.
    var topic by remember(initialTopic) { mutableStateOf(initialTopic) }
    val scroll = rememberScrollState()
    // One scroll for the whole window, and every page starts at its top: a page
    // that opened where the directory was scrolled to reads as a cut-off page.
    LaunchedEffect(topic) { scroll.scrollTo(0) }

    // The whole window scrolls as one, the header included: a settings list that
    // grows must not make the rows below the fold unreachable. The height is what
    // makes the popover scroll instead of measuring a scrollable column unbounded.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = SETTINGS_WINDOW_HEIGHT)
            .verticalScroll(scroll),
    ) {
        val page = topic
        if (page == null) {
            SettingsDirectory(
                state = state,
                onDismiss = onDismiss,
                onOpenTopic = { topic = it },
                onForget = onForget,
                onAddHost = onAddHost,
                onRename = onRename,
            )
        } else {
            val onBack = { topic = null }
            when (page) {
                SettingsTopic.ACCOUNT -> AccountSettingsPage(
                    state = state,
                    onBack = onBack,
                    onDone = onDismiss,
                    onOpenAccount = onOpenAccount,
                    onOpenBalance = onOpenBalance,
                    onOpenRedeem = onOpenRedeem,
                )
                SettingsTopic.NOTIFICATIONS -> NotificationsSettingsPage(
                    state = state,
                    onBack = onBack,
                    onDone = onDismiss,
                    onQuietHours = onQuietHours,
                    onNotifyCompleted = onNotifyCompleted,
                    onNotifyInterrupted = onNotifyInterrupted,
                    onNotifyQuestions = onNotifyQuestions,
                    onNotifyApprovals = onNotifyApprovals,
                    onHaptics = onHaptics,
                )
                SettingsTopic.SENDING -> SendingSettingsPage(
                    steerByDefault = state.steerByDefault,
                    onSteerByDefault = onSteerByDefault,
                    onBack = onBack,
                    onDone = onDismiss,
                )
                SettingsTopic.TRANSFER -> TransferSettingsPage(
                    localNetworkFirst = state.localNetworkFirst,
                    onLocalNetworkFirst = onLocalNetworkFirst,
                    onBack = onBack,
                    onDone = onDismiss,
                )
                SettingsTopic.LOCAL_AGENT -> LocalAgentSettingsPage(
                    localAgent = localAgent,
                    onOpenModels = onOpenLocalAgentModels,
                    onClearData = onClearLocalAgentData,
                    onBack = onBack,
                    onDone = onDismiss,
                )
                SettingsTopic.FILES -> FilesSettingsPage(
                    cache = fileCache,
                    onClearCache = onClearFileCache,
                    onReloadSessions = onReloadSessionData,
                    onBack = onBack,
                    onDone = onDismiss,
                )
                SettingsTopic.COMMUNITY -> CommunitySettingsPage(onBack = onBack, onDone = onDismiss)
                SettingsTopic.LOGS -> LogsSettingsPage(
                    slowTypewriter = state.slowTypewriter,
                    onSlowTypewriter = onSlowTypewriter,
                    onExportLogs = onExportLogs,
                    onBack = onBack,
                    onDone = onDismiss,
                )
                SettingsTopic.ABOUT -> AboutSettingsPage(
                    themeMode = state.themeMode,
                    onTheme = onTheme,
                    onReplayOnboarding = onReplayOnboarding,
                    onOpenSurveys = onOpenSurveys,
                    onBack = onBack,
                    onDone = onDismiss,
                )
            }
        }
    }
}

/**
 * The front page: what this is, who is signed in, the eight shelves, and the
 * computers this phone has paired.
 *
 * The computers stay here rather than on a shelf because there is no computers
 * screen of its own on Android yet, and forgetting a machine is the one control
 * that must remain one tap from the gear.
 */
@Composable
private fun SettingsDirectory(
    state: UiState,
    onDismiss: () -> Unit,
    onOpenTopic: (SettingsTopic) -> Unit,
    onForget: (String) -> Unit,
    onAddHost: () -> Unit,
    onRename: (String, String?) -> Unit,
) {
    var renaming by remember { mutableStateOf<me.shirazu.mizar.kit.PairedHost?>(null) }
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

    // The account is optional, so it sits above the shelves rather than among
    // them: it is identity, not a setting.
    SectionLabel(stringResource(R.string.account))
    AccountRow(state.account, onClick = { onOpenTopic(SettingsTopic.ACCOUNT) })

    // The shelves, top to bottom, in the order SettingsTopic declares. The account
    // is the row above, not a shelf.
    Spacer(Modifier.height(Space.xs))
    for (topic in SettingsTopic.entries.drop(1)) {
        SettingsTopicRow(topic = topic, onClick = { onOpenTopic(topic) })
    }

    // The computers this phone knows. Rename and forget live here because this is
    // where a paired machine has always been handled on Android, and there is no
    // computers screen it could move to.
    SectionLabel(stringResource(R.string.hosts_section))
    for (host in state.hosts) {
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
    TextButton(onClick = onAddHost, modifier = Modifier.padding(horizontal = 6.dp)) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.add_mac))
    }
    Text(
        text = stringResource(R.string.settings_note),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
    )
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
