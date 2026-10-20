package me.shirazu.dshmizar.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
        // The uptime is a fact that changes while the panel is open; ticking once a
        // second is cheaper than making the whole panel recompose from the view model.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
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
            state.connectedAtMillis?.takeIf { state.phase == Phase.READY }?.let { started ->
                RowValue(
                    stringResource(R.string.connection_uptime),
                    uptimeText((now - started).coerceAtLeast(0)),
                )
            }
            RowValue(
                stringResource(R.string.connection_poll),
                if (state.isLocalNetwork) {
                    stringResource(R.string.poll_lan)
                } else {
                    stringResource(R.string.poll_relay)
                },
            )
            state.latencyMillis?.let { rtt ->
                RowValue(stringResource(R.string.connection_latency), stringResource(R.string.latency_ms, rtt))
            }
            val history = state.latencyHistory.takeLast(24)
            if (history.size >= 2) {
                LatencyChart(history)
            }
            state.host?.let { host ->
                RowValue(stringResource(R.string.connection_route), routeText(host))
            }
        }
    }
}

/** A quiet sparkline of recent round trips: shape, not numbers. */
@Composable
private fun LatencyChart(history: List<Long>) {
    val color = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.connection_latency_history),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val max = history.maxOrNull()?.coerceAtLeast(1L) ?: 1L
            val step = if (history.size <= 1) 0f else size.width / (history.size - 1)
            var previous: androidx.compose.ui.geometry.Offset? = null
            history.forEachIndexed { index, value ->
                val point = androidx.compose.ui.geometry.Offset(
                    x = index * step,
                    y = size.height - (value.toFloat() / max.toFloat()) * size.height,
                )
                previous?.let { from -> drawLine(color, from, point, strokeWidth = 1.6.dp.toPx()) }
                previous = point
            }
        }
    }
}

private fun uptimeText(millis: Long): String {
    val seconds = millis / 1_000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
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

@Composable
private fun routeText(host: PairedHost): String {
    val relay = stringResource(R.string.route_relay)
    val direct = stringResource(R.string.route_direct)
    return host.endpoints.joinToString(" · ") { endpoint ->
        when (endpoint) {
            is MizarConnection.Endpoint.Direct -> String.format(direct, endpoint.authority)
            is MizarConnection.Endpoint.Relay -> relay
        }
    }
}
