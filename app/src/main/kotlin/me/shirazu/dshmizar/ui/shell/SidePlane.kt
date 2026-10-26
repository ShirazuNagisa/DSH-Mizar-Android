package me.shirazu.dshmizar.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.FilePreviewUiState
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.conversation.FilePreviewPane
import me.shirazu.dshmizar.ui.conversation.SubagentList
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.SegmentedControl

/**
 * The three pages of the right-hand plane.
 *
 * Turns, the subagents a session spawned, and the file a turn handed over. iOS keeps
 * the same three in its drawer (`SessionSidePlane`), and so does the wide layout --
 * this is that switcher, shared, so a phone and a tablet cannot drift into two
 * different side panels.
 */
enum class SidePage { TURNS, SUBAGENTS, PREVIEW }

/**
 * The right-hand plane: one switcher, three pages.
 *
 * @param page - which page is showing.
 * @param onPage - called when the operator picks another.
 * @param state - everything the pages read.
 * @param preview - the file the third page is showing, if any.
 * @param now - the clock the turn index measures durations against.
 * @param onScreen - which turns the conversation is currently showing.
 * @param onPick - called when a turn is picked from the index.
 */
@Composable
fun SidePlane(
    page: SidePage,
    onPage: (SidePage) -> Unit,
    state: UiState,
    preview: FilePreviewUiState,
    now: Long,
    onScreen: Set<Int>,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxSize()
            .background(MizarColors.SidebarBackground)
            // Without this the switcher sits under the status bar, where the top
            // half of every segment is a system gesture rather than a button.
            .statusBarsPadding(),
    ) {
        // The house control, not Material's segmented buttons: this is the same
        // three-way choice the theme picker and the sign-in/create toggle make, and
        // it carries the travelling highlight the rest of the app uses for one.
        SegmentedControl(
            options = listOf(
                stringResource(R.string.side_page_turns),
                stringResource(R.string.side_page_subagents),
                stringResource(R.string.side_page_preview),
            ),
            selected = page.ordinal,
            onSelect = { onPage(SidePage.entries[it]) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        )

        when (page) {
            SidePage.TURNS -> TurnIndex(
                state = state,
                now = now,
                onScreen = onScreen,
                onPick = onPick,
            )

            SidePage.SUBAGENTS -> {
                val children = state.sessions.filter {
                    it.isSubagent && it.parentSessionId == state.selectedSessionId
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    SubagentList(state = state, children = children)
                }
            }

            SidePage.PREVIEW -> FilePreviewPane(preview)
        }
    }
}
