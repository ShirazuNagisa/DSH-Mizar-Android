package me.shirazu.dshmizar.ui.gate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.shirazu.dshmizar.AccountMode
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.AccentDark
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.ContinuousCornerShape
import me.shirazu.dshmizar.ui.design.MizarMarkConnecting
import me.shirazu.dshmizar.ui.design.Space

/**
 * The screen before the app: who you are.
 *
 * Ported from iOS `WelcomeGateView.swift`. A hard gate, not a suggestion: every byte
 * this app moves crosses a relay the project pays for, and an account is what makes
 * those bytes attributable — which is what lets abnormal traffic be recognised instead
 * of the server quietly disappearing one day. The copy says that plainly rather than
 * dressing a login wall as a feature.
 *
 * It is also the screen an existing install lands on after an upgrade if it is not
 * signed in: nothing below is reachable until the account exists.
 *
 * No close, no skip, no back. The account sheet is opened over this screen and its
 * dismissal returns here.
 */
@Composable
fun WelcomeGateScreen(model: AppViewModel, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        GateBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(44.dp))
            MizarMarkConnecting(
                modifier = Modifier.width(92.dp),
                accent = AccentDark,
                ink = Color.White,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.gate_title),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("welcome.gate"),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.gate_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Space.md),
            )
            Spacer(Modifier.height(32.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GateButton(
                    label = stringResource(R.string.gate_sign_in),
                    prominent = true,
                    tag = "welcome.gate.signIn",
                    onClick = {
                        model.setAccountMode(AccountMode.SIGN_IN)
                        model.openAccountScreen()
                    },
                )
                GateButton(
                    label = stringResource(R.string.gate_create),
                    prominent = false,
                    tag = "welcome.gate.register",
                    onClick = {
                        model.setAccountMode(AccountMode.REGISTER)
                        model.openAccountScreen()
                    },
                )
                // The iOS gate offers Sign in with Apple; Android's account screen has
                // GitHub, so the gate offers the same door this platform can open.
                GateButton(
                    label = stringResource(R.string.gate_github),
                    prominent = false,
                    tag = "welcome.gate.github",
                    onClick = { model.beginGitHubSignIn() },
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.gate_footnote),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = Space.xl),
            )
        }
    }
}

/** The navy ground and the one glow behind the mark. */
@Composable
private fun GateBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MizarColors.Navy, MizarColors.NavyDeep))),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(AccentDark.copy(alpha = 0.24f), Color.Transparent),
                    ),
                ),
        )
    }
}

/** The gate's two button weights: the way in, and the two other ways in. */
@Composable
private fun GateButton(
    label: String,
    prominent: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val shape = ContinuousCornerShape(16.dp)
    val background = if (prominent) AccentDark else Color.Transparent
    val border = if (prominent) AccentDark else Color.White.copy(alpha = 0.28f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(background, shape)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (prominent) MizarColors.Navy else Color.White,
            textAlign = TextAlign.Center,
        )
    }
}
