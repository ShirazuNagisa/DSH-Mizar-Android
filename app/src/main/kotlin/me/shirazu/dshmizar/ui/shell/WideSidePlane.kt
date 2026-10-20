package me.shirazu.dshmizar.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.conversation.SubagentList

/** The wide side plane's three pages, in the order the switcher shows them. */
private enum class WidePage { TURNS, SUBAGENTS, PREVIEW }

/**
 * The right column on a wide screen, as three pages under one switcher.
 *
 * iOS keeps the same three pages in its drawer (`SessionSidePlane`): turns,
 * subagents, and a preview that is deliberately still a placeholder there too.
 * On a phone the turns page remains the only drawer page because the two others
 * have their own doors; on a tablet there is room to stand all three in a column
 * and let the operator turn between them without leaving the conversation.
 */
@Composable
fun WideSidePlane(
    model: AppViewModel,
    state: UiState,
    onScreen: Set<Int>,
    onPick: (Int) -> Unit,
) {
    var page by remember { mutableStateOf(WidePage.TURNS) }
    // Only read the children's names when their page is actually in front, and
    // read them again for a different session.
    LaunchedEffect(page, state.selectedSessionId) {
        if (page == WidePage.SUBAGENTS) model.loadSubagentTitles()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MizarColors.SidebarBackground),
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            val labels = listOf(
                R.string.side_page_turns,
                R.string.side_page_subagents,
                R.string.side_page_preview,
            )
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = page.ordinal == index,
                    onClick = { page = WidePage.entries[index] },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                    label = {
                        Text(
                            stringResource(label),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
        when (page) {
            WidePage.TURNS -> TurnIndex(
                state = state,
                now = System.currentTimeMillis(),
                onScreen = onScreen,
                onPick = onPick,
            )

            WidePage.SUBAGENTS -> {
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

            WidePage.PREVIEW -> PreviewPlaceholder()
        }
    }
}

/** iOS `PreviewPage`: a named, honest placeholder rather than an empty page. */
@Composable
private fun PreviewPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.side_page_preview),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.preview_under_development),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
