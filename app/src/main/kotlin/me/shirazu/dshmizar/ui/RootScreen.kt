package me.shirazu.dshmizar.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.PairingStage
import me.shirazu.dshmizar.Phase
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.pairing.FailedScreen
import me.shirazu.dshmizar.ui.pairing.UnpairedScreen
import me.shirazu.dshmizar.ui.design.RefreshSpinner
import me.shirazu.dshmizar.ui.design.LocalHapticsEnabled
import me.shirazu.dshmizar.ui.design.MizarHapticsState
import me.shirazu.dshmizar.ui.design.Transitions
import me.shirazu.dshmizar.ui.shell.MizarShell
import me.shirazu.mizar.kit.PairingUri

/**
 * The root: a switch on the connection's phase.
 *
 * There is no navigation graph behind this, deliberately. Which screen the operator
 * is on *is* a property of the connection — the app can be unpaired, connecting, or
 * unable to reach a Mac it knows — and modelling that as a route made the two
 * disagree the first time the socket dropped.
 *
 * Once the connection is READY the app lives in the shell: a sidebar of sessions
 * underneath, the conversation as a card on top (shell/MizarShell.kt).
 */
@Composable
fun RootScreen(model: AppViewModel, incomingPairUri: String?, onPairUriHandled: () -> Unit) {
    val state by model.state.collectAsState()

    // An effect, not an `if` in the composition. Pairing is a side effect that spends
    // a single-use code, and running it inline meant every recomposition between the
    // scan and the state update could spend it again. Keyed on the URI so a second
    // scan of the same code is still one pairing.
    LaunchedEffect(incomingPairUri) {
        val uri = incomingPairUri ?: return@LaunchedEffect
        PairingUri.parse(uri)?.let { target -> model.pair(target, "Android") }
        onPairUriHandled()
    }

    SideEffect { MizarHapticsState.enabled = state.hapticsEnabled }
    CompositionLocalProvider(LocalHapticsEnabled provides state.hapticsEnabled) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Above both screens on purpose: a lost connection matters whether the
            // operator is reading a conversation or looking at the list, and it is
            // shown rather than navigated to so nothing on screen is thrown away.
            // The 2 s delay lives in the view model's `reconnecting` flag; a one-second
            // network flap must not flash this.
            // It takes its height in one step and arrives by fading and rising its
            // last few points; leaving, it fades, then gives its height back. The
            // conversation below moves once each way and never animates.
            AnimatedVisibility(
                visible = state.reconnecting,
                enter = Transitions.riseIn(distance = 6.dp),
                exit = fadeOut(tween(160)),
            ) {
                ReconnectingBanner(state.disconnectReason)
            }
            Box(Modifier.weight(1f)) {
                when (state.phase) {
                    Phase.LAUNCHING -> Centered { CircularProgressIndicator() }
                    Phase.UNPAIRED -> UnpairedScreen(model)
                    Phase.CONNECTING -> Centered {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(
                                    when (state.pairingStage) {
                                        PairingStage.PAIRING -> R.string.pairing_stage_pairing
                                        PairingStage.SYNCING -> R.string.pairing_stage_syncing
                                        null -> R.string.connecting
                                    },
                                ),
                            )
                        }
                    }

                    Phase.READY -> MizarShell(model, state)
                    Phase.FAILED -> if (state.offlineBrowser) {
                        Column(Modifier.fillMaxSize()) {
                            OfflineBanner(onRetry = { model.retry() })
                            Box(Modifier.weight(1f)) { MizarShell(model, state) }
                        }
                    } else {
                        FailedScreen(
                            state,
                            onRetry = { model.retry() },
                            onPairAgain = { model.startPairing() },
                            onBrowseOffline = { model.browseOffline() },
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * A quiet strip over the shell while it shows cached data, with the one action that
 * matters: try the host again.
 */
@Composable
private fun OfflineBanner(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.offline_banner),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

/**
 * One narrow line saying the socket is being dialled again.
 *
 * Kept to a line because the interesting part is that it goes away: the screen below it
 * is still readable, which is the whole point of retrying instead of failing.
 */
@Composable
private fun ReconnectingBanner(reason: String?) {
    // The console's own neutral, with a hairline under it: a note on top of the
    // screen, not a coloured alarm. The three-dash spinner is the app's own sign
    // of work in progress.
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .drawBehind {
                val h = 1.dp.toPx()
                drawRect(hairline, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
            }
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RefreshSpinner(size = 16.dp, spinning = true)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = stringResource(R.string.reconnecting),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (reason != null) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
