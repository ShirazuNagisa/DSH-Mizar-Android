package me.shirazu.dshmizar.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.MizarMarkConnecting
import me.shirazu.dshmizar.ui.design.Radius
import me.shirazu.dshmizar.ui.design.glassCard
import me.shirazu.dshmizar.ui.design.pressable
import androidx.compose.animation.core.animateFloatAsState
import me.shirazu.dshmizar.ui.design.Motion

/**
 * The conversation pane with no conversation in it -- which is a place to start
 * one, not an empty screen. Ported from iOS `Views/Shell/HomeView.swift`: the
 * computer's name, the mark lighting up dot-outward as if finding it, and the
 * composer, ready. Sending starts a session in the chosen workspace with those
 * words as its first prompt.
 */
@Composable
fun HomeView(
    model: AppViewModel,
    state: UiState,
    onOpenSidebar: () -> Unit,
    onChooseWorkspace: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    // Where the new session will work. Defaults to the first workspace, and
    // follows the list if the choice disappears.
    var workspace by remember { mutableStateOf<String?>(null) }
    val choices = state.groups
    if (workspace == null || choices.none { it.path == workspace }) {
        workspace = choices.firstOrNull()?.path
    }
    val haptics = LocalHapticFeedback.current

    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))
        // The greeting.
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MizarMarkConnecting(modifier = Modifier.width(64.dp))
            Spacer(Modifier.height(18.dp))
            Text(
                text = state.host?.displayName ?: stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_ask),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            // The list is a swipe away; the button says so for anyone who has
            // not learned the gesture yet.
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.TextButton(onClick = onOpenSidebar) {
                Text(stringResource(R.string.show_sidebar))
            }
        }
        Spacer(Modifier.weight(1f))

        if (choices.isEmpty()) {
            Text(
                stringResource(R.string.home_no_workspaces),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                textAlign = TextAlign.Center,
            )
        }

        // The composer, ready: type, choose where the work happens, send.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .padding(bottom = 6.dp)
                .glassCard(radius = Radius.xl),
        ) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(stringResource(R.string.composer_hint)) },
                maxLines = 8,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        val target = workspace
                        if (target != null && draft.isNotBlank()) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val text = draft
                            draft = ""
                            model.createSessionWithPrompt(target, text)
                        }
                    },
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 2.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkspaceMenu(
                    choices = choices.map { it.path to it.title },
                    selected = workspace,
                    onSelect = { workspace = it },
                    onChooseOther = onChooseWorkspace,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                val canSend = workspace != null && draft.isNotBlank()
                val scale by animateFloatAsState(if (canSend) 1f else 0.92f, Motion.pick(Motion.bouncy()), label = "homeSend")
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(
                            if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        )
                        .pressable(scale = 0.88f)
                        .clickable(enabled = canSend) {
                            val target = workspace ?: return@clickable
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val text = draft
                            draft = ""
                            model.createSessionWithPrompt(target, text)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.send),
                        tint = if (canSend) MizarColors.OnAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** One button, labelled with its choice, opening the list of workspaces. */
@Composable
private fun WorkspaceMenu(
    choices: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    onChooseOther: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val title = choices.firstOrNull { it.first == selected }?.second
        ?: stringResource(R.string.choose_workspace)

    Box(modifier) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                .pressable()
                .clickable { open = true }
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((path, name) in choices) {
                DropdownMenuItem(
                    text = { Text(name) },
                    trailingIcon = {
                        if (path == selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = {
                        onSelect(path)
                        open = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.somewhere_else)) },
                onClick = {
                    open = false
                    onChooseOther()
                },
            )
        }
    }
}
