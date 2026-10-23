package me.shirazu.dshmizar.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.HorizontalDivider
import me.shirazu.dshmizar.ui.design.pressable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.ModelRouting
import me.shirazu.dshmizar.ui.Format
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.ButtonShape
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.Glass
import me.shirazu.dshmizar.ui.design.PopoverRow
import me.shirazu.dshmizar.ui.design.shapedClickable
import me.shirazu.dshmizar.ui.design.PopoverSection
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
internal fun ModelPickerContent(
    state: UiState,
    onChoose: (ModelSelection) -> Unit,
    homeChoice: ModelSelection? = null,
) {
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
    // The same resolver the composer's pill uses -- see ModelRouting.
    val inForce = ModelRouting.agreed(
        chosen = homeChoice ?: state.selectedSessionId?.let { state.modelChoice[it] },
        reported = state.stats?.model,
        fallback = catalog.defaultSelection,
    )
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
                        .background(
                            if (on) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            Corner.sm,
                        )
                        .shapedClickable(Corner.sm) { onPick(effort.id) }
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
            shape = ButtonShape.Inline,
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
internal fun CommandContent(
    attachmentsEnabled: Boolean,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onCommand: (String) -> Unit,
) {
    // The two ways to put a file in the draft sit on top, in one row of their own:
    // they are not commands (they change what will be sent, not what the agent is
    // told), they are the most-used pair in this window, and a row of two large
    // targets is what makes them findable without reading.
    if (attachmentsEnabled) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AttachmentTile(
                label = R.string.attach_photo,
                icon = Icons.Outlined.AddPhotoAlternate,
                onClick = onPickPhoto,
                modifier = Modifier.weight(1f),
            )
            AttachmentTile(
                label = R.string.attach_file,
                icon = Icons.Outlined.AttachFile,
                onClick = onPickFile,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
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

/**
 * One of the two attachment pickers, as a target rather than a line of text.
 *
 * Taller than a command row on purpose: this row is a pair of actions on the draft,
 * and the height is what says so before the label is read. The icon carries the
 * colour, the label stays in the page's own ink.
 */
@Composable
private fun AttachmentTile(
    label: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(68.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, Corner.md)
            // A hairline, the same one every chip in this app carries: the window
            // behind is translucent glass, so a fill alone leaves the target's
            // edge to be guessed at.
            .border(Glass.Hairline, MaterialTheme.colorScheme.outlineVariant, Corner.md)
            .pressable()
            .shapedClickable(Corner.md, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
