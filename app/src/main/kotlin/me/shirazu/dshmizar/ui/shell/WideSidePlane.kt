package me.shirazu.dshmizar.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.shirazu.dshmizar.FilePreviewUiState
import me.shirazu.dshmizar.UiState

/**
 * The right column on a wide screen: the same three pages as the phone's drawer,
 * standing instead of sliding.
 *
 * The switcher itself lives in [SidePlane], shared with the narrow layout, so the
 * two cannot drift into different sets of pages.
 *
 * @param state - everything the pages read.
 * @param preview - the file the third page is showing, if any.
 * @param onScreen - which turns the conversation is currently showing.
 * @param onPick - called when a turn is picked from the index.
 * @param onLoadSubagentTitles - called when the subagents page comes to the front.
 */
@Composable
fun WideSidePlane(
    state: UiState,
    preview: FilePreviewUiState,
    onScreen: Set<Int>,
    onPick: (Int) -> Unit,
    onLoadSubagentTitles: () -> Unit = {},
) {
    var page by remember { mutableStateOf(SidePage.TURNS) }
    // Only read the children's names when their page is actually in front, and
    // read them again for a different session.
    LaunchedEffect(page, state.selectedSessionId) {
        if (page == SidePage.SUBAGENTS) onLoadSubagentTitles()
    }
    SidePlane(
        page = page,
        onPage = { page = it },
        state = state,
        preview = preview,
        now = System.currentTimeMillis(),
        onScreen = onScreen,
        onPick = onPick,
        modifier = Modifier.fillMaxSize(),
    )
}
