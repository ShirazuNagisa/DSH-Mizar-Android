package me.shirazu.dshmizar.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.Phase
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.settings.DialogShell
import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.PairedHost

/**
 * What the connection actually is, said plainly.
 *
 * Ported from iOS `Views/Shell/ConnectionPanel.swift`. The footer capsule says
 * "connected" or "connecting"; when it says the second thing, the next question is
 * always "to what, and how" -- which address was tried, whether it went through the
 * relay, and what the last failure said. All of it already in `UiState`; nothing is
 * measured here.
 *
 * A sheet rather than the iOS popover: Compose has no anchored popover, and a panel
 * with five rows of text is not a popover's shape anyway. The iOS version grows out of
 * the capsule; this one slides up from the same control.
 */
@Composable
fun ConnectionPanel(
    state: UiState,
    onDismiss: () -> Unit,
) {
    DialogShell(title = stringResource(R.string.connection_details), onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            RowValue(
                label = stringResource(R.string.connection_status),
                value = statusText(state),
                tint = when {
                    state.phase == Phase.READY && !state.reconnecting -> MizarColors.Running
                    state.reconnecting -> MizarColors.Warning
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            state.latencyMillis?.let { rtt ->
                RowValue(stringResource(R.string.connection_latency), "$rtt ms")
            }
            state.host?.let { host ->
                RowValue(stringResource(R.string.connection_route), routeText(host))
            }
        }
    }
}

@Composable
private fun RowValue(label: String, value: String, tint: androidx.compose.ui.graphics.Color? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = tint ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun statusText(state: UiState): String = when {
    state.phase == Phase.READY && state.reconnecting -> stringResource(R.string.reconnecting)
    state.phase == Phase.READY -> stringResource(R.string.connected)
    state.phase == Phase.FAILED -> stringResource(R.string.connection_failed)
    else -> stringResource(R.string.connecting)
}

private fun routeText(host: PairedHost): String = host.endpoints.joinToString(" · ") { endpoint ->
    when (endpoint) {
        is MizarConnection.Endpoint.Direct -> "direct ${endpoint.authority}"
        is MizarConnection.Endpoint.Relay -> "relay"
    }
}
