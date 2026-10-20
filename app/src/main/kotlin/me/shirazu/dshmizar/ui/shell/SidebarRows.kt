package me.shirazu.dshmizar.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.Format
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.LocalReduceMotion
import me.shirazu.dshmizar.ui.design.Motion
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.TwinStar
import me.shirazu.dshmizar.ui.design.shapedClickable
import me.shirazu.mizar.kit.SessionGrouping
import me.shirazu.mizar.kit.SessionRow
import me.shirazu.mizar.kit.WorkspaceGroup

/** How many rows a workspace shows before it asks. */
private const val PAGE_SIZE = 10

/**
 * The workspace-grouped session rows, ported from iOS `Views/Shell/SidebarRows.swift`:
 * collapsed by default, staggered rise on expand, ten at a time, and the selected
 * row marked by a sheet of paper that slides between rows.
 */
@Composable
fun WorkspaceGroups(
    state: UiState,
    query: String,
    expanded: Set<String>,
    waitingSessions: Set<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggle: (String) -> Unit,
    onOpen: (String) -> Unit,
    onLoadTitles: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var limits by remember { mutableStateOf(mapOf<String, Int>()) }

    val groups = remember(state.groups, query) {
        if (query.isBlank()) {
            state.groups
        } else {
            state.groups.mapNotNull { group ->
                val q = query.trim()
                val hits = group.sessions.filter {
                    SessionGrouping.sessionTitle(it, state.titles).contains(q, ignoreCase = true)
                }
                when {
                    hits.isNotEmpty() -> WorkspaceGroup(group.path, group.title, hits)
                    group.title.contains(q, ignoreCase = true) -> group
                    else -> null
                }
            }
        }
    }

    // Titles are learned one session at a time, and only for the rows about to be
    // drawn: asking about every row of every workspace is a burst of requests for
    // names nobody is looking at.
    val visibleIds = remember(groups, expanded, limits, query) {
        groups
            .filter { expanded.contains(it.path) || query.isNotBlank() }
            .flatMap { group -> group.sessions.take(limits[group.path] ?: PAGE_SIZE) }
            .map { it.sessionId }
    }
    LaunchedEffect(visibleIds) {
        if (visibleIds.isNotEmpty()) onLoadTitles(visibleIds)
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
            if (groups.isEmpty()) {
                item(key = "empty") {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.no_sessions),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            for (group in groups) {
                val key = group.path
                val isOpen = expanded.contains(key) || query.isNotBlank()
                item(key = "h-$key") {
                    WorkspaceHeader(
                        group = group,
                        open = isOpen,
                        running = group.sessions.any { it.running },
                        waiting = group.sessions.any { it.sessionId in waitingSessions },
                        onToggle = { onToggle(key) },
                    )
                }
                if (!isOpen) continue

                val limit = limits[key] ?: PAGE_SIZE
                val rows = group.sessions.take(limit)
                rows.forEachIndexed { index, session ->
                    item(key = session.sessionId) {
                        StaggeredRow(index) {
                            SessionRowButton(
                                session = session,
                                state = state,
                                selected = session.sessionId == state.selectedSessionId,
                                waiting = session.sessionId in waitingSessions,
                                query = query,
                                onClick = { onOpen(session.sessionId) },
                            )
                        }
                    }
                }
                if (group.sessions.size > limit) {
                    item(key = "m-$key") {
                        TextButton(
                            onClick = { limits = limits + (key to limit + PAGE_SIZE) },
                            modifier = Modifier.padding(start = 42.dp),
                        ) {
                            Text(
                                stringResource(R.string.show_more, group.sessions.size - limit),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
    }
}

/** A row that rises into place, staggered 28 ms behind the row above it. */
@Composable
private fun StaggeredRow(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(11) * 28).toLong())
        visible = true
    }
    // The rise is the layer's: the row is in place from the first frame and only its
    // drawing travels the last 10 dp. Under Reduce Motion it only fades.
    val reduceMotion = LocalReduceMotion.current
    val rise by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = Motion.pick(Motion.smooth()),
        label = "rowRise",
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = Motion.pick(Motion.smooth()),
        label = "rowFade",
    )
    Box(
        Modifier.graphicsLayer {
            translationY = if (reduceMotion) 0f else rise * 10.dp.toPx()
            this.alpha = alpha.coerceIn(0f, 1f)
        },
    ) {
        content()
    }
}

/** One workspace: the chevron, the folder, the name, the badges, the count. */
@Composable
private fun WorkspaceHeader(
    group: WorkspaceGroup,
    open: Boolean,
    running: Boolean,
    waiting: Boolean,
    onToggle: () -> Unit,
) {
    val chevron by animateFloatAsState(if (open) 90f else 0f, Motion.pick(Motion.snappy()), label = "chevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp).rotate(chevron),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Space.xs))
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            group.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (waiting) {
            Box(Modifier.size(6.dp).background(MizarColors.Waiting, CircleShape))
            Spacer(Modifier.width(Space.xs))
        }
        if (!open && running) {
            TwinStar(size = 11.dp)
            Spacer(Modifier.width(Space.xs))
        }
        Text(
            "${group.sessions.size}",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFeatureSettings = "tnum",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One session: status indicator, title, relative time, the raised hand. */
@Composable
private fun SessionRowButton(
    session: SessionRow,
    state: UiState,
    selected: Boolean,
    waiting: Boolean,
    query: String,
    onClick: () -> Unit,
) {
    // A turn that finished while the operator was elsewhere shows the unread dot.
    val unread = !session.running && session.updatedMillis > (state.seenAt[session.sessionId] ?: 0L)
    val title = SessionGrouping.sessionTitle(session, state.titles)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = 1.dp)
            .then(
                if (selected) {
                    // The console's selection is a whisper of the accent with a
                    // leading bar, not a lifted sheet of paper.
                    Modifier.background(MizarColors.SelectionFill, Corner.sm)
                } else {
                    Modifier
                },
            )
            .shapedClickable(Corner.sm, onClick = onClick)
            .padding(top = 9.dp, bottom = 9.dp, end = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(28.dp - Space.md), contentAlignment = Alignment.Center) {
            if (selected) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(20.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)),
                )
            }
        }
        Box(modifier = Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            if (session.running) {
                TwinStar(size = 13.dp)
            } else {
                Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
            }
        }
        Spacer(Modifier.width(Space.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = highlight(title, query, selected),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                Format.relativeTime(session.updatedMillis),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (waiting) {
            Box(Modifier.size(6.dp).background(MizarColors.Waiting, CircleShape))
            Spacer(Modifier.width(Space.xs))
        }
        if (unread) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

/** The query's hits in the accent, so a searched row says why it matched. */
@Composable
private fun highlight(title: String, query: String, selected: Boolean): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(title)
    val accent = MaterialTheme.colorScheme.primary
    return remember(title, query, accent) {
        buildAnnotatedString {
            var cursor = 0
            val lower = title.lowercase()
            val q = query.trim().lowercase()
            while (true) {
                val hit = lower.indexOf(q, cursor)
                if (hit < 0) break
                append(title.substring(cursor, hit))
                pushStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold))
                append(title.substring(hit, hit + q.length))
                pop()
                cursor = hit + q.length
            }
            append(title.substring(cursor))
        }
    }
}
