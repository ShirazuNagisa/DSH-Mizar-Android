package me.shirazu.dshmizar.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.shapedClickable

/**
 * The settings directory: one shelf per topic, in the order the shelves are read
 * on screen.
 *
 * Ported from iOS `Views/Settings/SettingsTopic.swift`. The order is the whole
 * design: it runs from what the app will say to the operator (notifications, and
 * how a half-sent message is handled), through how the message travels (transfer,
 * the agent on this phone), to what this phone keeps (files, the local agent), and
 * finally outwards: the community, the log to send when something breaks, and what
 * the build is.
 *
 * Each topic owns its title, its one line and its symbol, so the directory row and
 * the page it opens cannot disagree about what the shelf is called.
 */
enum class SettingsTopic(
    val titleRes: Int,
    val summaryRes: Int,
    val icon: ImageVector,
) {
    /**
     * The account is not a shelf on the directory -- it is the row above it, because
     * "who is signed in, and what plan is that" is the first thing somebody looks
     * for in settings and it is not a preference. It is still a page like the
     * others, so it lives here and the directory skips it.
     */
    ACCOUNT(
        R.string.shell_topic_account,
        R.string.shell_topic_account_summary,
        Icons.Default.Person,
    ),
    NOTIFICATIONS(
        R.string.shell_topic_notifications,
        R.string.shell_topic_notifications_summary,
        Icons.Default.Notifications,
    ),
    SENDING(
        R.string.shell_topic_sending,
        R.string.shell_topic_sending_summary,
        Icons.Default.Send,
    ),
    TRANSFER(
        R.string.shell_topic_transfer,
        R.string.shell_topic_transfer_summary,
        Icons.Default.SwapHoriz,
    ),
    LOCAL_AGENT(
        R.string.shell_topic_local_agent,
        R.string.shell_topic_local_agent_summary,
        Icons.Default.Memory,
    ),
    FILES(
        R.string.shell_topic_files,
        R.string.shell_topic_files_summary,
        Icons.Default.Folder,
    ),
    COMMUNITY(
        R.string.shell_topic_community,
        R.string.shell_topic_community_summary,
        Icons.Default.Public,
    ),
    LOGS(
        R.string.shell_topic_logs,
        R.string.shell_topic_logs_summary,
        Icons.Default.Article,
    ),
    ABOUT(
        R.string.shell_topic_about,
        R.string.shell_topic_about_summary,
        Icons.Default.Info,
    ),
}

/**
 * One directory row: the shelf's symbol, its name, and one line about it.
 *
 * Two lines rather than one because eight bare titles do not say what is behind
 * them, and the operator should not have to open each shelf to find out whether
 * their setting is inside.
 */
@Composable
internal fun SettingsTopicRow(topic: SettingsTopic, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .shapedClickable(Corner.md, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            topic.icon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(topic.titleRes),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(topic.summaryRes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
