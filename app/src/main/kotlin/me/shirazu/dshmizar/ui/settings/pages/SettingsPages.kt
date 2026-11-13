package me.shirazu.dshmizar.ui.settings.pages

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import me.shirazu.dshmizar.BuildConfig
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.push.LiveTurn
import me.shirazu.dshmizar.ui.Format
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.ThemeMode
import me.shirazu.dshmizar.ui.account.AccountRow
import me.shirazu.dshmizar.ui.account.PaidDetailDialog
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.SegmentedControl
import me.shirazu.dshmizar.ui.settings.SectionLabel
import me.shirazu.dshmizar.ui.settings.SettingToggle
import me.shirazu.dshmizar.ui.settings.SettingsActionRow
import me.shirazu.dshmizar.ui.settings.SettingsFileCache
import me.shirazu.dshmizar.ui.settings.SettingsLocalAgent
import me.shirazu.dshmizar.ui.settings.SettingsNavRow
import me.shirazu.dshmizar.ui.settings.SettingsNote
import me.shirazu.dshmizar.ui.settings.SettingsPageHeader
import me.shirazu.dshmizar.ui.settings.SettingsValueRow
import me.shirazu.dshmizar.ui.settings.copyToClipboard
import me.shirazu.dshmizar.ui.settings.openUrl
import me.shirazu.dshmizar.ui.settings.shareArchive
import java.io.File

/**
 * The nine pages behind the settings directory.
 *
 * Ported from iOS `Views/Settings/…SettingsView.swift`, and each page keeps the
 * sections it always had -- this file only draws them where the shelf said they
 * would be. Every page is stateless: a value in, a callback out, and the two
 * screens that already exist (account, balance, redeem) are reached through the
 * callbacks the old single-window settings already had.
 *
 * What is deliberately *not* here: a Play Billing button. Android does not sell
 * subscriptions in an app store (a decided non-goal); paid time arrives as a code
 * against the signed-in account, which is why the account page offers Redeem and
 * the balance rather than an upgrade.
 */

// MARK: - Account

/** Who is signed in, what plan it pays for, and the two ways that plan is bought. */
@Composable
internal fun AccountSettingsPage(
    state: UiState,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenBalance: () -> Unit,
    onOpenRedeem: () -> Unit,
) {
    var showPaidDetail by remember { mutableStateOf(false) }
    Column {
        SettingsPageHeader(stringResource(R.string.shell_topic_account), onBack, onDone)
        AccountRow(state.account, onClick = onOpenAccount)
        SettingsNavRow(
            icon = Icons.Default.AccountBalanceWallet,
            title = stringResource(R.string.balance),
            onClick = onOpenBalance,
        )
        SettingsNavRow(
            icon = Icons.Default.Star,
            title = stringResource(R.string.paid_detail_title),
            onClick = { showPaidDetail = true },
        )
        SettingsNavRow(
            icon = Icons.Default.ConfirmationNumber,
            title = stringResource(R.string.redeem_title),
            onClick = onOpenRedeem,
        )
        SettingsNote(stringResource(R.string.shell_settings_account_note))
    }
    if (showPaidDetail) {
        PaidDetailDialog(onDismiss = { showPaidDetail = false })
    }
}

// MARK: - Notifications & Live Activity

/** What this phone will tell you: the four kinds, quiet hours, the card, the tap. */
@Composable
internal fun NotificationsSettingsPage(
    state: UiState,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onQuietHours: (Boolean) -> Unit,
    onNotifyCompleted: (Boolean) -> Unit,
    onNotifyInterrupted: (Boolean) -> Unit,
    onNotifyQuestions: (Boolean) -> Unit,
    onNotifyApprovals: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // The system can still veto every switch below; saying so here is the
    // difference between "the app is broken" and "notifications are off".
    val allowed = remember { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    Column {
        SettingsPageHeader(stringResource(R.string.shell_topic_notifications), onBack, onDone)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                }
                .padding(horizontal = 10.dp, vertical = 9.dp)
                .testTag("settings.notify.authorization"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(
                    if (allowed) R.string.system_notifications_on else R.string.system_notifications_off,
                ),
                style = MaterialTheme.typography.labelMedium,
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
        SectionLabel(stringResource(R.string.shell_settings_live_updates_section))
        SettingsValueRow(
            icon = Icons.Default.Bolt,
            title = stringResource(R.string.shell_settings_live_updates),
            value = stringResource(
                if (LiveTurn.isSupported()) {
                    R.string.shell_settings_live_updates_auto
                } else {
                    R.string.shell_settings_live_updates_unsupported
                },
            ),
        )
        SettingsNote(stringResource(R.string.shell_settings_notifications_note))
    }
}

// MARK: - Sending

/** What happens to a message sent while a turn is running. */
@Composable
internal fun SendingSettingsPage(
    steerByDefault: Boolean,
    onSteerByDefault: ((Boolean) -> Unit)?,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    Column {
        SettingsPageHeader(stringResource(R.string.shell_topic_sending), onBack, onDone)
        SettingToggle(
            label = stringResource(R.string.shell_settings_steer_by_default),
            checked = steerByDefault,
            onChange = { onSteerByDefault?.invoke(it) },
        )
        SettingsNote(stringResource(R.string.shell_settings_sending_note))
    }
}

// MARK: - Transfer

/** Which routes this phone may use. */
@Composable
internal fun TransferSettingsPage(
    localNetworkFirst: Boolean,
    onLocalNetworkFirst: (Boolean) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    Column {
        SettingsPageHeader(stringResource(R.string.shell_topic_transfer), onBack, onDone)
        SectionLabel(stringResource(R.string.transfer))
        SettingToggle(
            label = stringResource(R.string.local_network_first),
            checked = localNetworkFirst,
            onChange = onLocalNetworkFirst,
        )
        SettingsNote(stringResource(R.string.local_network_first_note))
        SettingsNote(stringResource(R.string.shell_settings_transfer_note))
    }
}

// MARK: - On-device agent

/** The agent that runs on this phone: one page, and the promise that it stops when
 *  the app does. */
@Composable
internal fun LocalAgentSettingsPage(
    localAgent: SettingsLocalAgent?,
    onOpenModels: (() -> Unit)?,
    onClearData: (() -> Unit)?,
    localAgentActive: Boolean = false,
    onUseLocalAgent: (() -> Unit)? = null,
    onLeaveLocalAgent: (() -> Unit)? = null,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    Column {
        SettingsPageHeader(stringResource(R.string.shell_topic_local_agent), onBack, onDone)
        if (localAgent?.available != true) {
            SettingsNote(stringResource(R.string.shell_settings_local_agent_unavailable))
            return@Column
        }
        SettingsActionRow(
            icon = Icons.Default.Memory,
            title = stringResource(
                if (localAgentActive) R.string.localagent_leave_action else R.string.localagent_use_action,
            ),
            subtitle = stringResource(R.string.localagent_switch_detail),
            onClick = {
                if (localAgentActive) onLeaveLocalAgent?.invoke() else onUseLocalAgent?.invoke()
            },
        )
        SettingsNavRow(
            icon = Icons.Default.Memory,
            title = stringResource(R.string.shell_settings_local_agent_models),
            subtitle = stringResource(
                if (localAgent.configured) {
                    R.string.shell_settings_local_agent_configured
                } else {
                    R.string.shell_settings_local_agent_not_configured
                },
            ),
            onClick = { onOpenModels?.invoke() },
        )
        SettingsActionRow(
            icon = Icons.Default.Delete,
            title = stringResource(R.string.shell_settings_local_agent_clear),
            subtitle = localAgent.storedBytes?.let { Format.bytes(it) },
            destructive = true,
            onClick = { confirming = true },
        )
        SettingsNote(stringResource(R.string.shell_settings_local_agent_note))
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.shell_settings_local_agent_clear)) },
            text = { Text(stringResource(R.string.shell_settings_local_agent_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onClearData?.invoke()
                }) {
                    Text(stringResource(R.string.shell_settings_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

// MARK: - Files & cache

/** What this phone keeps, and the two ways to get the space and the staleness back. */
@Composable
internal fun FilesSettingsPage(
    cache: SettingsFileCache?,
    onClearCache: (() -> Unit)?,
    onReloadSessions: (() -> Unit)?,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    var confirmingReload by remember { mutableStateOf(false) }
    val cacheLine = when {
        cache == null -> stringResource(R.string.shell_settings_files_cache_unknown)
        cache.files <= 0 -> stringResource(R.string.shell_settings_files_cache_empty)
        else -> stringResource(
            R.string.shell_settings_files_cache_files,
            cache.files,
            Format.bytes(cache.bytes),
        )
    }
    Column {
        SettingsPageHeader(stringResource(R.string.shell_topic_files), onBack, onDone)
        SectionLabel(stringResource(R.string.shell_settings_files_section))
        SettingsValueRow(
            icon = Icons.Default.Folder,
            title = stringResource(R.string.shell_settings_files_previewed),
            value = cacheLine,
        )
        SettingsActionRow(
            icon = Icons.Default.Delete,
            title = stringResource(R.string.shell_settings_clear_file_cache),
            destructive = true,
            enabled = cache != null && cache.files > 0,
            onClick = { onClearCache?.invoke() },
        )
        SettingsNote(stringResource(R.string.shell_settings_files_note))
        SectionLabel(stringResource(R.string.shell_settings_session_data))
        SettingsActionRow(
            icon = Icons.Default.Sync,
            title = stringResource(R.string.shell_settings_reload_sessions),
            destructive = true,
            onClick = { confirmingReload = true },
        )
        SettingsNote(stringResource(R.string.shell_settings_reload_sessions_note))
    }
    if (confirmingReload) {
        AlertDialog(
            onDismissRequest = { confirmingReload = false },
            title = { Text(stringResource(R.string.shell_settings_reload_sessions)) },
            text = { Text(stringResource(R.string.shell_settings_reload_sessions_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReload = false
                    onReloadSessions?.invoke()
                }) {
                    Text(stringResource(R.string.shell_settings_reload))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReload = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

// MARK: - Community

/** Where to find the project and the people using it. */
@Composable
internal fun CommunitySettingsPage(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var qqCopied by remember { mutableStateOf(false) }
    Column {
        SettingsPageHeader(stringResource(R.string.shell_topic_community), onBack, onDone)
        SettingsNavRow(
            icon = Icons.Default.Public,
            title = stringResource(R.string.shell_settings_community_website),
            subtitle = "dsh-mizar.shirazu-nagisa.com",
            onClick = { openUrl(context, PROJECT_URL) },
        )
        SettingsNavRow(
            icon = Icons.Default.Forum,
            title = stringResource(R.string.shell_settings_community_qq),
            subtitle = QQ_GROUP,
            onClick = {
                // QQ answers a group card; a phone without QQ has no handler for the
                // scheme at all, so the number goes to the clipboard and the page
                // says so -- a tap that appears to do nothing is the one outcome
                // worth ruling out.
                val card = Uri.parse(
                    "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$QQ_GROUP&card_type=group&source=qrcode",
                )
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, card))
                } catch (_: ActivityNotFoundException) {
                    copyToClipboard(context, context.getString(R.string.shell_settings_community_qq), QQ_GROUP)
                    qqCopied = true
                } catch (_: SecurityException) {
                    copyToClipboard(context, context.getString(R.string.shell_settings_community_qq), QQ_GROUP)
                    qqCopied = true
                }
            },
        )
        if (qqCopied) {
            SettingsNote(stringResource(R.string.shell_settings_community_qq_copied))
        }
        SettingsNote(stringResource(R.string.shell_settings_community_note))
    }
}

private const val PROJECT_URL = "https://dsh-mizar.shirazu-nagisa.com"
private const val QQ_GROUP = "1130443236"

// MARK: - Logs

/** What to send when something is wrong. */
@Composable
internal fun LogsSettingsPage(
    slowTypewriter: Boolean,
    onSlowTypewriter: (Boolean) -> Unit,
    onExportLogs: ((File?) -> Unit) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    Column {
        SettingsPageHeader(stringResource(R.string.shell_topic_logs), onBack, onDone)
        SettingsActionRow(
            icon = Icons.Default.Upload,
            title = stringResource(R.string.export_logs),
            subtitle = stringResource(R.string.shell_settings_logs_export_note),
            onClick = {
                onExportLogs { file ->
                    if (file == null) {
                        Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        shareArchive(context, file)
                    }
                }
            },
        )
        SectionLabel(stringResource(R.string.diagnostics))
        SettingToggle(stringResource(R.string.slow_typewriter), slowTypewriter, onSlowTypewriter)
        SettingsNote(stringResource(R.string.shell_settings_logs_note))
    }
}

// MARK: - About & more

/** What this build is, where its source is, and the way back into the guide. */
@Composable
internal fun AboutSettingsPage(
    themeMode: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    onReplayOnboarding: (() -> Unit)?,
    onOpenSurveys: (() -> Unit)? = null,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
    Column {
        SettingsPageHeader(stringResource(R.string.shell_topic_about), onBack, onDone)
        SectionLabel(stringResource(R.string.theme))
        SegmentedControl(
            options = listOf(
                stringResource(R.string.theme_system),
                stringResource(R.string.theme_light),
                stringResource(R.string.theme_dark),
            ),
            selected = modes.indexOf(themeMode),
            onSelect = { onTheme(modes[it]) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        )
        SectionLabel(stringResource(R.string.about))
        SettingsValueRow(
            icon = Icons.Default.Info,
            title = stringResource(R.string.shell_settings_version),
            value = stringResource(R.string.app_version, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME),
        )
        SettingsNavRow(
            icon = Icons.Default.Public,
            title = stringResource(R.string.source_code),
            subtitle = SOURCE_URL,
            onClick = { openUrl(context, SOURCE_URL) },
        )
        SettingsActionRow(
            icon = Icons.Default.Replay,
            title = stringResource(R.string.shell_settings_replay_onboarding),
            subtitle = stringResource(R.string.shell_settings_replay_onboarding_note),
            onClick = { onReplayOnboarding?.invoke() },
        )
        // A questionnaire is about the product the way "about" is: the operator
        // asks, the operator pays in paid days, and nothing about using the app
        // changes while one is open.
        SettingsActionRow(
            icon = Icons.Default.Checklist,
            title = stringResource(R.string.survey_title),
            subtitle = stringResource(R.string.survey_about_note),
            onClick = { onOpenSurveys?.invoke() },
        )
        SettingsNote(stringResource(R.string.acknowledgements))
        SettingsNote(stringResource(R.string.shell_settings_about_note))
    }
}

private const val SOURCE_URL = "https://github.com/ShirazuNagisa/DSH-Mizar"
