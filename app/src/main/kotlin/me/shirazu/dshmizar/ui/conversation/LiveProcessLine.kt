package me.shirazu.dshmizar.ui.conversation

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.domain.LiveStateWording
import me.shirazu.dshmizar.domain.ProcessCategory
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.TwinStar
import me.shirazu.mizar.kit.SessionState

/**
 * One line of the state machine, running or settled.
 *
 * Ported from iOS `Views/Conversation/LiveProcessLine.swift` and the
 * `StreamStatusLine` in `ConversationStreamView.swift`: while the turn runs the
 * line says what is happening, in the protocol's own words, with a sweep; once a
 * body arrives it freezes into the past-tense tally ("已完成分析并执行了命令") and
 * stays quiet.
 */
@Composable
internal fun LiveStatusLine(
    states: List<SessionState>,
    running: Boolean,
    waiting: SessionState?,
    current: SessionState?,
    isChinese: Boolean,
    modifier: Modifier = Modifier,
) {
    val text = when {
        running && waiting != null -> LiveStateWording.running(waiting, isChinese)
        running && current != null -> LiveStateWording.running(current, isChinese)
        else -> LiveStateWording.completion(states, isChinese)
    }
    val category = (waiting ?: current)?.let(ProcessCategory::from)
        ?: states.mapNotNull(ProcessCategory::from).lastOrNull()
    val tint: Color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = text }
            .testTag("conv.stream.status")
            .shimmering(active = running),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = category.icon(),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (running) FontWeight.Medium else FontWeight.Normal,
            ),
            color = tint,
        )
    }
}

/** The glyph a process category implies. */
private fun ProcessCategory?.icon(): ImageVector = when (this) {
    ProcessCategory.THINKING -> Icons.Default.Info
    ProcessCategory.READ -> Icons.Default.Description
    ProcessCategory.EDIT -> Icons.Default.Edit
    ProcessCategory.COMMANDS -> Icons.Default.PlayArrow
    ProcessCategory.SEARCH -> Icons.Default.Search
    ProcessCategory.SUBAGENTS -> Icons.Default.People
    ProcessCategory.PLAN -> Icons.Default.CheckCircle
    ProcessCategory.QUESTIONS -> Icons.Default.Help
    ProcessCategory.TOOLS -> Icons.Default.Build
    ProcessCategory.BACKGROUND -> Icons.Default.Schedule
    ProcessCategory.COMPACTING -> Icons.Default.Info
    null -> Icons.Default.Info
}

/** The hairline between what the turn produced and what it is doing. */
@Composable
internal fun StreamHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)),
    )
}

/** A centred system line, the same voice as a conversation notice. */
@Composable
internal fun StreamNote(text: String, modifier: Modifier = Modifier, warning: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (warning) MizarColors.Waiting else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .semantics { contentDescription = text },
    )
}

/** The live footer's clock: a mark turning and the elapsed time it keeps. */
@Composable
internal fun StreamClock(
    startedAtMillis: Long?,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val elapsed = startedAtMillis?.let { (nowMillis - it).coerceAtLeast(0L) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("conv.stream.clock")
            .shimmering(active = true),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        TwinStar(size = 16.dp)
        Text(
            text = if (elapsed == null) {
                stringResource(R.string.conv_stream_clock_start)
            } else {
                stringResource(R.string.conv_stream_clock, spellDuration(elapsed))
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "2 min 14 s" / "18 s", the header's and the clock's shared reading. */
@Composable
internal fun spellDuration(millis: Long): String {
    val seconds = ((millis + 500) / 1000).coerceAtLeast(0)
    return if (seconds < 60) {
        stringResource(R.string.conv_seconds, seconds)
    } else {
        stringResource(R.string.conv_minutes, seconds / 60, seconds % 60)
    }
}
