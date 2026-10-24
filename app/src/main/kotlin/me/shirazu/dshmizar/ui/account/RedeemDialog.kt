package me.shirazu.dshmizar.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.AccountUiState
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.R

/**
 * Turn a code into paid days.
 *
 * The answer is the point: the service adds the days to whatever the account has,
 * so the dialog says the new end date instead of only closing. A redeem that just
 * made a line further up the screen change reads as nothing having happened, and
 * the first thing an operator does then is try the code again.
 *
 * Signed out, the field is not shown at all -- a code adds time to an account, and
 * there is nothing here to add it to.
 */
@Composable
fun RedeemDialog(model: AppViewModel, account: AccountUiState) {
    var code by remember { mutableStateOf("") }
    val signedIn = account.session != null
    val busy = account.working

    AlertDialog(
        onDismissRequest = { model.closeRedeem() },
        title = { Text(stringResource(R.string.redeem_title)) },
        text = {
            Column {
                if (!signedIn) {
                    Text(stringResource(R.string.redeem_needs_account))
                } else {
                    Text(
                        stringResource(R.string.redeem_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase() },
                        label = { Text(stringResource(R.string.account_invite_hint)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    val notice = account.redeemNotice
                    if (notice != null) {
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    val error = account.error
                    if (error != null) {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.redeem_stacks),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (signedIn) {
                TextButton(
                    onClick = { model.redeemInvite(code) },
                    enabled = code.isNotBlank() && !busy,
                ) {
                    Text(stringResource(R.string.redeem_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { model.closeRedeem() }) {
                Text(stringResource(R.string.redeem_close))
            }
        },
    )
}
