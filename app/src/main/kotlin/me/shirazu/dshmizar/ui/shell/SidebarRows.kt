package me.shirazu.dshmizar.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.Format
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.LocalReduceMotion
import me.shirazu.dshmizar.ui.design.Motion
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.TwinStar
import me.shirazu.dshmizar.ui.design.rememberTravelingHighlight
import me.shirazu.dshmizar.ui.design.shapedClickable
import me.shirazu.mizar.kit.SessionGrouping
import me.shirazu.mizar.kit.SessionRow
import me.shirazu.mizar.kit.WorkspaceGroup

/** How many rows a workspace shows before it asks. */
private const val PAGE_SIZE = 10

/**
 * The workspace-grouped session rows, ported from iOS `Views/Shell/SidebarRows.swift`:
 * collapsed by default, staggered rise on expand, ten at a time, and the selected
 * row marked by one highlight, drawn behind the rows, that travels to the row the
 * operator taps.
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
    onArchive: (String) -> Unit,
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

    // The selection is drawn once, behind the rows, where the selected row is laid out
    // this frame: the fill and its leading bar travel from the row that was selected
    // to the one just tapped, and a scroll carries them with their row.
    val selectedId = state.selectedSessionId
    val highlight = rememberTravelingHighlight()
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val inset = with(density) { Space.md.toPx() }
    val gap = with(density) { 1.dp.toPx() }
    fun rowRect(key: String?): Rect? {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
        val width = listState.layoutInfo.viewportSize.width.toFloat()
        return Rect(inset, info.offset + gap, width - inset, info.offset + info.size - gap)
    }
    // The travel outlives the selection that started it: a second tap retargets it
    // from where it is rather than cancelling it back to a standing start.
    val travelScope = rememberCoroutineScope()
    var lastSelected by remember { mutableStateOf(selectedId) }
    LaunchedEffect(selectedId) {
        if (selectedId == lastSelected) return@LaunchedEffect
        val from = rowRect(lastSelected)?.let(highlight::place)
        val to = rowRect(selectedId)
        lastSelected = selectedId
        travelScope.launch { highlight.moveTo(from, to, reduceMotion) }
    }
    val fill = MizarColors.SelectionFill
    val bar = MaterialTheme.colorScheme.primary

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().drawBehind {
            val target = rowRect(selectedId) ?: return@drawBehind
            val r = highlight.place(target)
            val a = highlight.alpha
            clipRect {
                translate(r.left, r.top) {
                    drawOutline(Corner.sm.createOutline(r.size, layoutDirection, this), fill, alpha = a)
                    // The leading bar, 2 x 20 dp, centred in the row's first 16 dp --
                    // the row's start, which is its right edge in RTL.
                    val bw = 2.dp.toPx()
                    val bh = 20.dp.toPx()
                    val cx = if (layoutDirection == LayoutDirection.Rtl) r.width - 8.dp.toPx() else 8.dp.toPx()
                    drawRoundRect(
                        color = bar,
                        topLeft = Offset(cx - bw / 2f, (r.height - bh) / 2f),
                        size = Size(bw, bh),
                        cornerRadius = CornerRadius(bw / 2f),
                        alpha = a,
                    )
                }
            }
        },
    ) {
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
                                onClick = {
                                    highlight.arm()
                                    onOpen(session.sessionId)
                                },
                                onArchive = onArchive,
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRowButton(
    session: SessionRow,
    state: UiState,
    selected: Boolean,
    waiting: Boolean,
    query: String,
    onClick: () -> Unit,
    onArchive: (String) -> Unit,
) {
    // A turn that finished while the operator was elsewhere shows the unread dot.
    val unread = !session.running && session.updatedMillis > (state.seenAt[session.sessionId] ?: 0L)
    val title = SessionGrouping.sessionTitle(session, state.titles)
    var menuOpen by remember { mutableStateOf(false) }
    var rowWidthPx by remember { mutableStateOf(0) }
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // The menu hangs off the row's trailing edge, not its leading one: it belongs
    // to the row but should not cover the titles to its left, and the thumb that
    // opened it is on the right half of the screen.
    val menuWidth = 200.dp
    val menuOffset = if (layoutDirection == LayoutDirection.Ltr) {
        DpOffset(x = (with(density) { rowWidthPx.toDp() } - menuWidth).coerceAtLeast(0.dp), y = 0.dp)
    } else {
        DpOffset.Zero
    }

    Box(modifier = Modifier.fillMaxWidth().onSizeChanged { rowWidthPx = it.width }) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The selection -- a whisper of the accent with a leading bar -- is
            // drawn by the list behind the rows, so it can travel between them.
            .padding(horizontal = Space.md, vertical = 1.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
            )
            .padding(top = 9.dp, bottom = 9.dp, end = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Where the leading bar is drawn.
        Spacer(Modifier.width(28.dp - Space.md))
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
    // The long-press menu is the mobile equivalent of the iOS row's context menu:
    // copying the id is the first thing a bug report wants, and archiving is the
    // only way to take a finished conversation off this phone's list without
    // deleting it on the computer.
    DropdownMenu(
        expanded = menuOpen,
        onDismissRequest = { menuOpen = false },
        modifier = Modifier.width(menuWidth),
        offset = menuOffset,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.copy_session_id)) },
            onClick = {
                clipboard.setText(AnnotatedString(session.sessionId))
                menuOpen = false
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.archive_session)) },
            onClick = {
                menuOpen = false
                onArchive(session.sessionId)
            },
        )
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
