package me.shirazu.dshmizar.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import me.shirazu.dshmizar.ui.Format
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.Radius
import me.shirazu.mizar.kit.ModelSelection

/**
 * The contents of the windows the composer and the top bar open: the model
 * picker, the permission presets, the context breakdown, and the command list.
 *
 * They are contents, not sheets: each one is mounted inside an `AnchoredPopover`
 * at the control that opens it, so the window grows out of the tap. Titles and
 * sizes live at the mount site; what is here is what the window holds.
 */

/** Choose a model, and then its reasoning effort. */
@Composable
internal fun ModelPickerContent(state: UiState, onChoose: (ModelSelection) -> Unit) {
    val catalog = state.modelCatalog
    if (catalog == null) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    for (provider in catalog.providers) {
        Text(
            provider.name,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        for (option in provider.models) {
            ListItem(
                headlineContent = { Text(option.name) },
                supportingContent = option.description?.let { { Text(it) } },
                modifier = Modifier.clickable {
                    onChoose(
                        ModelSelection(
                            provider = provider.id,
                            model = option.id,
                            reasoningEffort = option.defaultEffort ?: option.efforts.firstOrNull()?.id,
                        ),
                    )
                },
            )
        }
    }
    // A provider that could not be listed is shown, not hidden: an empty
    // picker with no explanation is a bug report nobody can act on.
    if (catalog.failures.isNotEmpty()) {
        Text(
            stringResource(R.string.unavailable),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(16.dp),
        )
        for (failure in catalog.failures) {
            ListItem(
                headlineContent = { Text(failure.name) },
                supportingContent = { Text(failure.message) },
            )
        }
    }
}

/** Choose a permission preset, with a warning in front of the dangerous one. */
@Composable
internal fun PermissionContent(state: UiState, onChoose: (String) -> Unit) {
    var confirming by remember { mutableStateOf<String?>(null) }
    val catalog = state.permissionCatalog
    if (catalog == null) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    for (option in catalog.options) {
        ListItem(
            headlineContent = { Text(option.name) },
            supportingContent = option.description?.let { { Text(it) } },
            leadingContent = {
                if (option.isDangerous) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = MizarColors.Failure)
                }
            },
            modifier = Modifier.clickable {
                if (option.isDangerous) confirming = option.value else onChoose(option.value)
            },
        )
    }
    Text(
        stringResource(R.string.permission_note),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(16.dp),
    )
    confirming?.let { preset ->
        Button(
            onClick = { onChoose(preset) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(Radius.md),
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
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    Labelled(stringResource(R.string.window), Format.countOrDash(context.window))
    Labelled(stringResource(R.string.pressure), Format.countOrDash(context.pressureTokens))
    Labelled(stringResource(R.string.projected), Format.countOrDash(context.projectedTokens))
    Labelled(stringResource(R.string.percent_full), context.percent?.let { "$it%" } ?: "—")
    context.breakdown?.let { breakdown ->
        Spacer(Modifier.height(8.dp))
        Labelled(stringResource(R.string.system), Format.count(breakdown.systemTokens))
        Labelled(stringResource(R.string.tools), Format.count(breakdown.toolsTokens))
        Labelled(stringResource(R.string.messages), Format.count(breakdown.messageTokens))
    }
}

@Composable
private fun Labelled(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
        ListItem(
            headlineContent = { Text(stringResource(label)) },
            supportingContent = { Text(slash, fontFamily = FontFamily.Monospace) },
            modifier = Modifier.clickable { onCommand(slash) },
        )
    }
}
