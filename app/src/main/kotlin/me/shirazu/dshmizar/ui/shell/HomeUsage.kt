package me.shirazu.dshmizar.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.domain.UsageCalendar
import me.shirazu.dshmizar.domain.UsageDay
import me.shirazu.dshmizar.domain.UsageHeatmapCell
import me.shirazu.dshmizar.domain.UsageMetrics
import me.shirazu.dshmizar.domain.UsageOverview
import me.shirazu.dshmizar.domain.compactCount
import me.shirazu.dshmizar.domain.hourLabel
import me.shirazu.dshmizar.ui.Format
import me.shirazu.dshmizar.ui.design.ContinuousCornerShape
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.Space

/**
 * The home page's record of the last year of work, drawn from the host's own
 * `usage.overview`.
 *
 * Ported from iOS `Views/Shell/HomeDashboard.swift` + `HomeView.swift`'s
 * `UsageReadings`: six figures folded from the same rows the chart draws, then a
 * year of weeks with one square per day, then the totals and the colour key. No
 * second request and no second source, so the figures cannot disagree with the
 * squares under them.
 *
 * The phone shows a year where the desktop's home page does too -- 53 columns of
 * small squares read as one bar at this width, the way the desktop draws it -- and
 * the column count is what makes the squares small enough to fit.
 *
 * **No data is hidden, not drawn as zero.** `overview == null` and not loading
 * draws nothing at all; a figure the host cannot report is an em dash, never a
 * zero, which is the rule the whole app is built on.
 *
 * Stateless: the caller passes the merged history and the flags, so the Lead wires
 * it to `UiState` and visual-qa renders it from a literal.
 *
 * @param overview every computer's history, already merged (see
 *   [UsageOverview.merged]), or `null` while there is nothing to draw.
 * @param loading whether a read is on the socket.
 * @param updatedLabel "Updated 3 min ago"-style line, when the caller has one.
 */
@Composable
fun HomeUsage(
    overview: UsageOverview?,
    loading: Boolean,
    modifier: Modifier = Modifier,
    updatedLabel: String? = null,
) {
    if (overview == null) {
        // A read that has not answered yet says so in one quiet line; a read that
        // answered "nothing" draws nothing at all.
        if (loading) UsageLoading(modifier)
        return
    }
    val metrics = remember(overview) { overview.metrics() }
    Column(modifier = modifier.testTag("home.usage")) {
        if (updatedLabel != null) {
            Text(
                text = updatedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.xs),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        UsageReadings(metrics = metrics)
        Spacer(Modifier.height(Space.md))
        UsageHeatmap(days = overview.days, loading = loading)
    }
}

/** One quiet line while the first read is on the socket. */
@Composable
private fun UsageLoading(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().testTag("home.usage.loading"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 1.5.dp,
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            text = stringResource(R.string.shell_usage_loading),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The six readings, three across and two down: a phone reads six short figures as
 * one block, and two rows keep the way back into the last conversation on screen.
 */
@Composable
private fun UsageReadings(metrics: UsageMetrics, modifier: Modifier = Modifier) {
    Column(modifier = modifier.testTag("home.usage.readings")) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Reading(stringResource(R.string.shell_usage_sessions), Format.count(metrics.sessions.toLong()), "sessions", Modifier.weight(1f))
            Reading(stringResource(R.string.shell_usage_messages), Format.count(metrics.messages), "messages", Modifier.weight(1f))
            Reading(stringResource(R.string.shell_usage_tokens), compactCount(metrics.tokens), "tokens", Modifier.weight(1f))
        }
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Reading(stringResource(R.string.shell_usage_active_days), Format.count(metrics.activeDays.toLong()), "days", Modifier.weight(1f))
            Reading(stringResource(R.string.shell_usage_busiest_hour), hourLabel(metrics.busiestHour), "busiest", Modifier.weight(1f))
            // The model's name, set smaller: a long id has to fit its card, and a
            // name is read rather than compared at a glance.
            Reading(
                stringResource(R.string.shell_usage_top_model),
                metrics.topModel ?: "—",
                "model",
                Modifier.weight(1f),
                compact = true,
            )
        }
    }
}

/** One figure: what it is over what it is, in the console's card. */
@Composable
private fun Reading(
    title: String,
    value: String,
    tag: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
            .padding(horizontal = Space.md, vertical = Space.sm)
            .testTag("home.usage.reading.$tag"),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = value,
            style = if (compact) {
                MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
            } else {
                MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("home.usage.value.$tag"),
        )
    }
}

/**
 * A year of work as one column per week.
 *
 * The window starts on the week boundary before it, so every column is a whole
 * week and the rows line up with the weekdays they mean; the first column is
 * usually short and is padded rather than shifting every other column by a day.
 */
@Composable
private fun UsageHeatmap(days: List<UsageDay>, loading: Boolean, modifier: Modifier = Modifier) {
    val cells = remember(days) { UsageCalendar.cells(days) }
    val columns = remember(cells) { UsageCalendar.columns(cells) }
    val peak = remember(cells) { UsageCalendar.peakTokens(cells) }
    val messages = remember(cells) { cells.sumOf { it.day?.messages ?: 0 } }
    val tokens = remember(cells) { cells.sumOf { it.day?.tokens ?: 0 } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.lg)
            .padding(Space.md)
            .testTag("home.usage.heatmap"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.shell_usage_last_year),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.4.dp)
            }
        }
        Spacer(Modifier.height(Space.sm))
        // Seven rows of squares, as many columns as the year has weeks, each as wide
        // as the row allows -- so the chart is exactly as wide as the page and no
        // taller than a year of weeks needs.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 2.dp
            val across = columns.size.coerceAtLeast(1)
            val available = maxWidth - gap * (across - 1).toFloat()
            val side = (available / across.toFloat()).coerceIn(2.5.dp, 9.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (column in columns) {
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        for (cell in column) {
                            Square(cell = cell, peak = peak, side = side)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            UnitReading(Format.count(messages.toLong()), stringResource(R.string.shell_usage_messages_unit))
            Spacer(Modifier.width(Space.md))
            UnitReading(compactCount(tokens.toLong()), stringResource(R.string.shell_usage_tokens_unit))
            Spacer(Modifier.weight(1f))
            // What the colours mean, said once rather than in a legend box.
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (step in 0..3) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(levelColor(step), ContinuousCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

/** One day's square: the accent in four steps up from an empty cell. */
@Composable
private fun Square(cell: UsageHeatmapCell, peak: Int, side: androidx.compose.ui.unit.Dp) {
    val level = UsageCalendar.level(cell.day, peak)
    Box(
        Modifier
            .size(side)
            .then(
                if (level == null) {
                    Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), ContinuousCornerShape(side * 0.22f))
                } else {
                    Modifier.background(levelColor(level), ContinuousCornerShape(side * 0.22f))
                },
            ),
    )
}

@Composable
private fun levelColor(step: Int): androidx.compose.ui.graphics.Color {
    val opacity = 0.22f + 0.78f * (step.coerceIn(0, 3) / 3f)
    return MaterialTheme.colorScheme.primary.copy(alpha = opacity)
}

/** One total, with its unit beside it in the quiet ink. */
@Composable
private fun UnitReading(value: String, unit: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = unit,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
