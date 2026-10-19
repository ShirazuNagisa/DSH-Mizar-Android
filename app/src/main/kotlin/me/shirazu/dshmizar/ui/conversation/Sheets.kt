package me.shirazu.dshmizar.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.Format
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.PopoverRow
import me.shirazu.dshmizar.ui.design.PopoverSection
import me.shirazu.dshmizar.ui.design.Radius
import me.shirazu.mizar.kit.ModelSelection
import me.shirazu.mizar.kit.ReasoningEffortOption

/**
 * The contents of the windows the composer and the top bar open: the model
 * picker, the permission presets, the context breakdown, and the command list.
 *
 * They are contents, not sheets: each one is mounted inside an `AnchoredPopover`
 * at the control that opens it, so the window grows out of the tap. Rows are
 * `PopoverRow`s, measured like the workspace rows the window floats beside.
 */

/** Choose a model, and then its reasoning effort. */
@Composable
internal fun ModelPickerContent(state: UiState, onChoose: (ModelSelection) -> Unit) {
    val catalog = state.modelCatalog
    if (catalog == null) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        return
    }

    // What this session is on, by the same three sources the composer's chip reads.
    // Seed the local choice from it, or the rows open with nothing ticked and no way
    // to tell which model is in force.
    val inForce = state.selectedSessionId?.let { state.modelChoice[it] }
        ?: state.stats?.model?.next
        ?: state.stats?.model?.lastUsed
        ?: catalog.defaultSelection
    var chosenProvider by remember(inForce) { mutableStateOf(inForce?.provider) }
    var chosenModel by remember(inForce) { mutableStateOf(inForce?.model) }
    var chosenEffort by remember(inForce) { mutableStateOf(inForce?.reasoningEffort) }

    for (provider in catalog.providers) {
        PopoverSection(provider.name)
        for (option in provider.models) {
            val chosen = chosenProvider == provider.id && chosenModel == option.id
            PopoverRow(
                title = option.name,
                subtitle = option.description,
                onClick = {
                    chosenProvider = provider.id
                    chosenModel = option.id
                    chosenEffort = option.defaultEffort
                    // A model with no levels commits at once: there is nothing else
                    // to choose, and a second tap would be a formality.
                    if (option.efforts.isEmpty()) {
                        onChoose(ModelSelection(provider.id, option.id, null))
                    }
                },
                trailing = if (chosen) {
                    {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    null
                },
            )
            // The levels are the second half of the same choice, so they fold out
            // under the row that was tapped rather than opening a window of their own.
            if (chosen && option.efforts.isNotEmpty()) {
                EffortChooser(
                    efforts = option.efforts,
                    selected = chosenEffort ?: option.defaultEffort,
                    onPick = { effort ->
                        chosenEffort = effort
                        onChoose(ModelSelection(provider.id, option.id, effort))
                    },
                )
            }
        }
    }
    // A provider that could not be listed is shown, not hidden: an empty
    // picker with no explanation is a bug report nobody can act on.
    if (catalog.failures.isNotEmpty()) {
        PopoverSection(stringResource(R.string.unavailable))
        for (failure in catalog.failures) {
            PopoverRow(title = failure.name, subtitle = failure.message)
        }
    }
}

/**
 * The reasoning levels of the chosen model.
 *
 * It used to be missing entirely: every row committed `defaultEffort`, so picking a
 * model silently reset the intensity and there was no way to set one. The chip went
 * on naming a level the host was not running at.
 */
@Composable
private fun EffortChooser(
    efforts: List<ReasoningEffortOption>,
    selected: String?,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 9.dp)) {
        Text(
            stringResource(R.string.reasoning_effort),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (effort in efforts) {
                val on = effort.id == selected
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(
                            if (on) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                        .clickable { onPick(effort.id) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        effort.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** Choose a permission preset, with a warning in front of the dangerous one. */
@Composable
internal fun PermissionContent(state: UiState, onChoose: (String) -> Unit) {
    var confirming by remember { mutableStateOf<String?>(null) }
    val catalog = state.permissionCatalog
    if (catalog == null) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        return
    }
    for (option in catalog.options) {
        PopoverRow(
            title = option.name,
            subtitle = option.description,
            onClick = {
                if (option.isDangerous) confirming = option.value else onChoose(option.value)
            },
            trailing = if (option.isDangerous) {
                {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MizarColors.Failure,
                        modifier = Modifier.size(14.dp),
                    )
                }
            } else {
                null
            },
        )
    }
    Text(
        stringResource(R.string.permission_note),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
    )
    confirming?.let { preset ->
        Button(
            onClick = { onChoose(preset) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            shape = RoundedCornerShape(Radius.sm),
        ) {
            Text(stringResource(R.string.allow_full_access))
        }
    }
}

/** The context breakdown, with an em dash for everything the host did not send. */
@Composable
internal fun ContextContent(state: UiState) {
    val context = state.stats?.context
    if (context == null) {
        Text(
            stringResource(R.string.no_context_figure),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(14.dp),
        )
        return
    }
    Labelled(stringResource(R.string.window), Format.countOrDash(context.window))
    Labelled(stringResource(R.string.pressure), Format.countOrDash(context.pressureTokens))
    Labelled(stringResource(R.string.projected), Format.countOrDash(context.projectedTokens))
    Labelled(stringResource(R.string.percent_full), context.percent?.let { "$it%" } ?: "—")
    context.breakdown?.let { breakdown ->
        Spacer(Modifier.height(6.dp))
        Labelled(stringResource(R.string.system), Format.count(breakdown.systemTokens))
        Labelled(stringResource(R.string.tools), Format.count(breakdown.toolsTokens))
        Labelled(stringResource(R.string.messages), Format.count(breakdown.messageTokens))
    }
}

@Composable
private fun Labelled(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontFeatureSettings = "tnum",
            ),
        )
    }
}

/** The four commands the composer offers. */
@Composable
internal fun CommandContent(onCommand: (String) -> Unit) {
    val commands = listOf(
        "/goal" to R.string.command_goal,
        "/plan" to R.string.command_plan,
        "/compact" to R.string.command_compact,
        "/model" to R.string.command_model,
    )
    for ((slash, label) in commands) {
        PopoverRow(
            title = stringResource(label),
            subtitle = slash,
            onClick = { onCommand(slash) },
        )
    }
}
