package me.shirazu.dshmizar.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import me.shirazu.dshmizar.AccountMode
import me.shirazu.dshmizar.AccountPhase
import me.shirazu.dshmizar.AccountUiState
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.design.ButtonShape
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.SegmentedControl
import me.shirazu.mizar.kit.account.AccountDevice
import me.shirazu.mizar.kit.account.AccountUser
import java.text.DateFormat
import java.util.Date

/**
 * The account, on one screen.
 *
 * Optional by design: closing this leaves every free feature working. The two
 * states are the whole surface — signed out is a form, signed in is a profile and
 * a device list — and the errors stay inline rather than in a dialog, because a
 * wrong password is not an event that deserves to block the screen.
 */
@Composable
fun AccountScreen(model: AppViewModel, account: AccountUiState, onClose: () -> Unit) {
    BackHandler(onBack = onClose)

    Surface(modifier = Modifier.fillMaxSize()) {
        // The screen is edge-to-edge, so the title and close button would sit under
        // the status bar without this inset -- on a phone with a tall camera cutout
        // the close button becomes untappable.
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.account),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            ) {
                if (account.session == null) {
                    SignedOut(model, account)
                } else {
                    SignedIn(model, account)
                }
            }
        }
    }
}

// ── signed out ──────────────────────────────────────────────────────────────

@Composable
private fun SignedOut(model: AppViewModel, account: AccountUiState) {
    var identifier by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var handoff by remember { mutableStateOf("") }

    val registering = account.mode == AccountMode.REGISTER
    val busy = account.working

    SegmentedControl(
        options = listOf(
            stringResource(R.string.account_sign_in),
            stringResource(R.string.account_create),
        ),
        selected = if (registering) 1 else 0,
        onSelect = { model.setAccountMode(if (it == 1) AccountMode.REGISTER else AccountMode.SIGN_IN) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )

    if (registering) {
        Field(
            value = username,
            onValueChange = { username = it },
            label = stringResource(R.string.account_username),
            keyboard = KeyboardType.Text,
        )
        Field(
            value = email,
            onValueChange = { email = it },
            label = stringResource(R.string.account_email),
            keyboard = KeyboardType.Email,
        )
    } else {
        Field(
            value = identifier,
            onValueChange = { identifier = it },
            label = stringResource(R.string.account_identifier),
            keyboard = KeyboardType.Email,
        )
    }
    Field(
        value = password,
        onValueChange = { password = it },
        label = stringResource(R.string.account_password),
        keyboard = KeyboardType.Password,
        secret = true,
    )

    Button(
        onClick = {
            if (registering) model.registerAccount(username, email, password)
            else model.loginAccount(identifier, password)
        },
        enabled = !busy,
        shape = ButtonShape.Primary,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(50.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(
                stringResource(
                    if (registering) R.string.account_create else R.string.account_sign_in,
                ),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }

    OutlinedButton(
        onClick = { model.beginGitHubSignIn() },
        enabled = !busy,
        shape = ButtonShape.Primary,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(50.dp),
    ) {
        Text(stringResource(R.string.account_continue_github))
    }

    if (account.error != null) {
        Text(
            account.error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 12.dp),
        )
    }

    // The landing page's fallback: if the browser could not open the app, the
    // code it printed is still enough to finish.
    if (account.handoffEntry) {
        Field(
            value = handoff,
            onValueChange = { handoff = it },
            label = stringResource(R.string.account_handoff_hint),
            keyboard = KeyboardType.Text,
        )
        Button(
            onClick = { model.redeemGitHubHandoff(handoff) },
            enabled = !busy && handoff.isNotBlank(),
            shape = ButtonShape.Primary,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(48.dp),
        ) {
            Text(stringResource(R.string.account_handoff_action))
        }
        TextButton(
            onClick = { model.showHandoffEntry(false) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.account_cancel))
        }
    } else {
        TextButton(
            onClick = { model.showHandoffEntry(true) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.account_handoff_entry))
        }
    }

    Text(
        stringResource(R.string.account_optional_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
    )
}

// ── signed in ───────────────────────────────────────────────────────────────

@Composable
private fun SignedIn(model: AppViewModel, account: AccountUiState) {
    val profile = account.profile ?: return
    var invite by remember { mutableStateOf("") }
    var confirmingDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(profile, size = 58)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile.username,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                stringResource(
                    if (profile.isPaid) R.string.account_paid else R.string.account_free,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            profile.email?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (profile.github) {
                Text(
                    profile.githubLogin?.let { login -> "GitHub · $login" }
                        ?: stringResource(R.string.account_github_linked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }

    if (account.phase == AccountPhase.UNREACHABLE) {
        Text(
            stringResource(R.string.account_unreachable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    account.notice?.let { notice ->
        Text(
            stringResource(
                R.string.account_notice_device_limit,
                notice.revokedDevice ?: "",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    profile.paidUntil?.let { until ->
        Text(
            stringResource(R.string.account_paid_until, dateText(until)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (account.error != null) {
        Text(
            account.error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 10.dp),
        )
    }

    Divider()
    SectionTitle(stringResource(R.string.account_devices))
    if (profile.devices.isEmpty()) {
        Text(
            stringResource(R.string.account_no_devices),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        for (device in profile.devices) DeviceRow(device)
    }

    Divider()
    SectionTitle(stringResource(R.string.account_invite))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Field(
                value = invite,
                onValueChange = { invite = it.uppercase() },
                label = stringResource(R.string.account_invite_hint),
                keyboard = KeyboardType.Text,
            )
        }
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = { model.redeemInvite(invite) },
            enabled = !account.working && invite.isNotBlank(),
            shape = ButtonShape.Primary,
        ) {
            Text(stringResource(R.string.account_invite_redeem))
        }
    }

    if (!profile.github) {
        Divider()
        OutlinedButton(
            onClick = { model.beginGitHubSignIn(link = true) },
            enabled = !account.working,
            shape = ButtonShape.Primary,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(48.dp),
        ) {
            Text(stringResource(R.string.account_link_github))
        }
    }

    Divider()
    TextButton(
        onClick = { model.logoutAccount() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.account_sign_out))
    }
    TextButton(
        onClick = { confirmingDelete = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.account_delete),
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (confirmingDelete) {
        DeleteDialog(
            busy = account.working,
            onCancel = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                model.deleteAccount()
            },
        )
    }
}

@Composable
private fun DeviceRow(device: AccountDevice) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                device.name.ifBlank { device.model.ifBlank { device.id } },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            Text(
                listOf(device.platform, device.model).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (device.current) {
            Text(
                stringResource(R.string.account_device_current),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun DeleteDialog(busy: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.account_delete_title)) },
        text = {
            Column {
                Text(stringResource(R.string.account_delete_body))
                Field(
                    value = typed,
                    onValueChange = { typed = it.uppercase() },
                    label = "DELETE",
                    keyboard = KeyboardType.Text,
                    modifier = Modifier.testTag("account-delete-confirm"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed == "DELETE" && !busy) {
                Text(
                    stringResource(R.string.account_delete_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.account_cancel))
            }
        },
    )
}

// ── pieces ──────────────────────────────────────────────────────────────────

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType,
    secret: Boolean = false,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            imeAction = if (secret) ImeAction.Done else ImeAction.Next,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier.fillMaxWidth().padding(top = 10.dp),
    )
}

@Composable
private fun Avatar(profile: AccountUser, size: Int) {
    val colors = profile.avatar.colors.mapNotNull { parseColor(it) }.takeIf { it.isNotEmpty() }
        ?: listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(Brush.linearGradient(colors), Corner.md)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Corner.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profile.initials,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        // GitHub's avatar, over the gradient: if the host is blocked or the image
        // fails, the initials underneath are already the fallback.
        profile.avatar.url?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().clip(Corner.md),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

private fun dateText(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

private fun parseColor(value: String): Color? = runCatching {
    val hex = value.removePrefix("#")
    val argb = when (hex.length) {
        6 -> 0xFF000000L or hex.toLong(16)
        8 -> hex.toLong(16)
        else -> return null
    }
    Color(argb.toInt())
}.getOrNull()
