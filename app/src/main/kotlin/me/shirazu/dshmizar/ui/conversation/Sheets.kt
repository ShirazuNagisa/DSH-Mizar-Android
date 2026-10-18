package me.shirazu.dshmizar.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import me.shirazu.mizar.kit.ModelSelection

/**
 * The sheets the session screen opens: the model picker, the permission
 * presets, the context breakdown, and the command list.
 */

/** Choose a model, and then its reasoning effort. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(state: UiState, onDismiss: () -> Unit, onChoose: (ModelSelection) -> Unit) {
    val catalog = state.modelCatalog
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.model),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        if (catalog == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@ModalBottomSheet
        }
        LazyColumn {
            for (provider in catalog.providers) {
                item(key = "p-${provider.id}") {
                    Text(
                        provider.name,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(provider.models, key = { "${provider.id}-${it.id}" }) { option ->
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
                item(key = "failures") {
                    Text(
                        stringResource(R.string.unavailable),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(catalog.failures, key = { it.id }) { failure ->
                    ListItem(
                        headlineContent = { Text(failure.name) },
                        supportingContent = { Text(failure.message) },
                    )
                }
            }
        }
    }
}

/** Choose a permission preset, with a warning in front of the dangerous one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PermissionSheet(state: UiState, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    var confirming by remember { mutableStateOf<String?>(null) }
    val catalog = state.permissionCatalog

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.permissions),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        if (catalog == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ModalBottomSheet
        }
        LazyColumn {
            items(catalog.options, key = { it.value }) { option ->
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
            ) {
                Text(stringResource(R.string.allow_full_access))
            }
        }
    }
}

/** The context breakdown, with an em dash for everything the host did not send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextSheet(state: UiState, onDismiss: () -> Unit) {
    val context = state.stats?.context
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.context),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        if (context == null) {
            Text(
                stringResource(R.string.no_context_figure),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            return@ModalBottomSheet
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
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
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Labelled(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The four commands the composer offers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommandSheet(onDismiss: () -> Unit, onCommand: (String) -> Unit) {
    val commands = listOf(
        "/goal" to R.string.command_goal,
        "/plan" to R.string.command_plan,
        "/compact" to R.string.command_compact,
        "/model" to R.string.command_model,
    )
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.commands),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        for ((slash, label) in commands) {
            ListItem(
                headlineContent = { Text(stringResource(label)) },
                supportingContent = { Text(slash, fontFamily = FontFamily.Monospace) },
                modifier = Modifier.clickable { onCommand(slash) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
