package me.shirazu.dshmizar.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.R

/**
 * What the paid plan actually buys, in the operator's own words.
 *
 * The copy is deliberately blunt about the money: it is a cost-sharing plan, not a
 * product with a margin, and saying so is the honest thing to put next to a price.
 * The second paragraph exists only on Android: there is no in-app purchase here, so
 * a page that explained the plan without saying how to get it would be a dead end.
 */
@Composable
fun PaidDetailDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paid_detail_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.paid_detail_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.paid_detail_how),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.paid_detail_ok))
            }
        },
    )
}
