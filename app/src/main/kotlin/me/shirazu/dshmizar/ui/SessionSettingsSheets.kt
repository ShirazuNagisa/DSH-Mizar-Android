package me.shirazu.dshmizar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.ModelSelection
import me.shirazu.mizar.kit.SessionSettings

/** Both entry points use the exact provider's catalog, never invented low/medium/high levels. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionModelSheet(model: AppViewModel, state: UiState, effortOnly: Boolean, onDismiss: () -> Unit) {
    val current = SessionSettings.current(state.stats, state.modelCatalog)
    val catalog = state.modelCatalog
    var selected by remember(state.settingsGeneration, effortOnly, catalog) {
        mutableStateOf(current?.let { selection ->
            SessionSettings.option(catalog, selection)?.let { SessionSettings.seed(selection.provider, it, selection) }
        })
    }
    // Independent effort changes always refer to the latest projected current model.
    val route = if (effortOnly) current else selected
    val option = SessionSettings.option(catalog, route)
    val efforts = SessionSettings.efforts(option)
    var chosenEffort by remember(state.settingsGeneration, route?.provider, route?.model, catalog) {
        mutableStateOf(route?.let { selection -> option?.let { SessionSettings.seed(selection.provider, it, selection).reasoningEffort } })
    }
    var effortDirty by remember(state.settingsGeneration, route?.provider, route?.model, catalog) { mutableStateOf(false) }
    LaunchedEffect(current, effortOnly) {
        if (effortOnly && !effortDirty) chosenEffort = current?.reasoningEffort?.takeIf { id -> efforts.any { it.id == id } }
    }
    val scroll = rememberLazyListState()
    val uiScope = rememberCoroutineScope()
    val candidate = route?.copy(reasoningEffort = chosenEffort.takeIf { efforts.isNotEmpty() })
    val busy = state.modelSaving || state.permissionSaving

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp), state = scroll) {
            item {
                Text(stringResource(if (effortOnly) R.string.reasoning_effort else R.string.model),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                Text(stringResource(R.string.model_scope_note), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
                Text(stringResource(R.string.settings_current, current?.let { "${it.provider} / ${it.model} · ${it.reasoningEffort ?: "—"}" } ?: "—"),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                CatalogStatus(state.modelCatalogLoading, state.modelCatalogError, onRetry = { model.loadCatalogs(force = true) })
                SaveStatus(state.modelSaving, state.modelSaved, state.modelSaveError)
            }
            if (catalog != null) {
                item {
                    Text(stringResource(R.string.settings_selected, route?.let { "${it.provider} / ${it.model}" } ?: "—"),
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
                }
                if (option == null) {
                    item { Text(stringResource(R.string.effort_model_unavailable), modifier = Modifier.padding(16.dp)) }
                } else if (efforts.isEmpty()) {
                    item { Text(stringResource(R.string.effort_not_supported), modifier = Modifier.padding(16.dp)) }
                } else {
                    item { Text(stringResource(R.string.reasoning_effort), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp)) }
                    items(efforts, key = { "effort-${it.id}" }) { effort ->
                        ListItem(
                            headlineContent = { Text(effort.name) },
                            supportingContent = { Text(listOfNotNull(effort.id, effort.description).joinToString(" · ")) },
                            leadingContent = { RadioButton(chosenEffort == effort.id, onClick = null) },
                            modifier = Modifier.clickable(enabled = !busy) { chosenEffort = effort.id; effortDirty = true },
                        )
                    }
                }
                item {
                    Button(
                        onClick = { candidate?.let(model::setModel) },
                        enabled = !busy && !state.modelCatalogLoading && candidate != null && SessionSettings.valid(catalog, candidate) && (!effortOnly || efforts.isNotEmpty()),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) { Text(stringResource(if (state.modelSaveError != null) R.string.settings_retry_save else R.string.settings_apply)) }
                }
                if (!effortOnly) {
                    item { Text(stringResource(R.string.settings_choose_model), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(16.dp)) }
                    catalog.providers.forEach { provider ->
                        item(key = "provider-${provider.id}") {
                            Text(provider.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp))
                        }
                        items(provider.models, key = { "model-${provider.id}-${it.id}" }) { entry ->
                            ListItem(
                                headlineContent = { Text(entry.name) },
                                supportingContent = { Text(listOfNotNull(entry.id, entry.description).joinToString(" · ")) },
                                leadingContent = { RadioButton(selected?.provider == provider.id && selected?.model == entry.id, onClick = null) },
                                modifier = Modifier.clickable(enabled = !busy) {
                                    selected = SessionSettings.seed(provider.id, entry, current)
                                    chosenEffort = selected?.reasoningEffort
                                    effortDirty = false
                                    uiScope.launch { scroll.animateScrollToItem(1) }
                                },
                            )
                        }
                    }
                    if (catalog.providers.all { it.models.isEmpty() }) item {
                        Text(stringResource(R.string.settings_no_models), modifier = Modifier.padding(16.dp))
                    }
                }
                items(catalog.failures, key = { "failure-${it.id}" }) { failure ->
                    ListItem(headlineContent = { Text(failure.name) }, supportingContent = { Text(failure.message) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionPermissionSheet(model: AppViewModel, state: UiState, onDismiss: () -> Unit) {
    var confirming by remember(state.settingsGeneration) { mutableStateOf<String?>(null) }
    val current = state.stats?.permissionPreset
    val options = SessionSettings.permissions(state.permissionCatalog)
    val busy = state.modelSaving || state.permissionSaving
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp)) {
            item {
                Text(stringResource(R.string.access_permissions), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp))
                Text(stringResource(R.string.permission_session_scope), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
                Text(stringResource(R.string.settings_current, options.firstOrNull { it.value == current }?.name ?: current ?: "—"),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
                if (current == "custom") Text(stringResource(R.string.permission_custom_current), modifier = Modifier.padding(horizontal = 16.dp))
                CatalogStatus(state.permissionCatalogLoading, state.permissionCatalogError, onRetry = { model.loadCatalogs(force = true) })
                SaveStatus(state.permissionSaving, state.permissionSaved, state.permissionSaveError)
            }
            items(options, key = { it.value }) { option ->
                ListItem(
                    headlineContent = { Text(option.name, color = if (option.isDangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                    supportingContent = { Text(listOfNotNull(option.value, option.description).joinToString(" · ")) },
                    leadingContent = {
                        if (current == option.value) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.settings_active))
                    },
                    modifier = Modifier.clickable(enabled = !busy && !state.permissionCatalogLoading) {
                        if (option.isDangerous) confirming = option.value else model.setPermission(option.value)
                    },
                )
            }
            if (state.permissionCatalog != null && options.isEmpty()) item {
                Text(stringResource(R.string.settings_no_permissions), modifier = Modifier.padding(16.dp))
            }
            item {
                TextButton(onClick = { model.refreshConversation() }, enabled = !busy, modifier = Modifier.padding(8.dp)) {
                    Text(stringResource(R.string.settings_readback))
                }
            }
        }
    }
    confirming?.let { preset ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.permission_danger_title)) },
            text = { Text(stringResource(R.string.permission_danger_body)) },
            confirmButton = { TextButton(onClick = { confirming = null; model.setPermission(preset, dangerConfirmed = true) }) {
                Text(stringResource(R.string.allow_full_access), color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

@Composable
private fun CatalogStatus(loading: Boolean, error: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (loading) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.settings_loading))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onRetry, enabled = !loading) { Text(stringResource(R.string.settings_reload_catalog)) }
    }
}

@Composable
private fun SaveStatus(saving: Boolean, saved: Boolean, error: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (saving) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.settings_saving))
        } else if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        else if (saved) Text(stringResource(R.string.settings_confirmed), color = MaterialTheme.colorScheme.primary)
    }
}
