package me.shirazu.dshmizar.ui.pairing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.design.ButtonShape
import me.shirazu.dshmizar.ui.design.Corner

/**
 * The one question asked after a pairing: what should this phone call the
 * computer it just met. Offered once, when the pairing lands; "skip" is a
 * decision too, and leaves the machine's own name in place.
 *
 * A centered dialog, not a popover: nothing on screen opened it, so there is
 * nothing to anchor to -- the same exception iOS makes for surfaces no tap
 * raised.
 */
@Composable
fun NamingDialog(
    machineName: String,
    onSave: (String) -> Unit,
    onSkip: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onSkip) {
        Surface(
            shape = Corner.md,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.naming_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.naming_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(machineName) },
                    singleLine = true,
                    shape = Corner.sm,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Row(modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onSkip) {
                        Text(stringResource(R.string.naming_skip))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(name) },
                        shape = ButtonShape.Primary,
                    ) {
                        Text(stringResource(R.string.naming_save))
                    }
                }
            }
        }
    }
}
