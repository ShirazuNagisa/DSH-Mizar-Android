package me.shirazu.dshmizar.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.Phase
import me.shirazu.dshmizar.R
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

    // A scanned or deep-linked URI is handled once, then cleared, so a rotation does
    // not pair a second time with a single-use code.
    if (incomingPairUri != null) {
        PairingUri.parse(incomingPairUri)?.let { target -> model.pair(target, "Android") }
        onPairUriHandled()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Above both screens on purpose: a lost connection matters whether the
            // operator is reading a conversation or looking at the list, and it is
            // shown rather than navigated to so nothing on screen is thrown away.
            // The 2 s delay lives in the view model's `reconnecting` flag; a one-second
            // network flap must not flash this.
            if (state.reconnecting) {
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
                            Text(stringResource(R.string.connecting))
                        }
                    }

                    Phase.READY -> MizarShell(model, state)
                    Phase.FAILED -> FailedScreen(
                        state,
                        onRetry = { model.retry() },
                        onPairAgain = { model.startPairing() },
                    )
                }
            }
        }
    }
}
