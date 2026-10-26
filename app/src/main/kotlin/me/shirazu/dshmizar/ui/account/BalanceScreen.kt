package me.shirazu.dshmizar.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.BalanceUiState
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.design.ButtonShape
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.mizar.kit.account.BalanceResult
import me.shirazu.mizar.kit.account.BalanceWallet

/**
 * What the connected computer's DeepSeek account holds.
 *
 * Not the phone's relay credits: the host answers this because only the host has
 * the credentials, and a host that cannot ask says which kind of "cannot" it is --
 * too old to have the method, nobody signed in on that computer, or the query
 * failed. Each of those is a different sentence, because the operator's next move
 * is different in each.
 */
/** How tall the balance window may grow before it scrolls. */
private val BALANCE_WINDOW_HEIGHT = 320.dp

/**
 * The wallets, as a pane.
 *
 * A popover's contents rather than a screen: the balance is a thing to glance at
 * from the sidebar, not a place to go. The full-screen version had a title bar and
 * a close button, both of which the popover brings itself.
 *
 * @param model - the view model, for the refresh button.
 * @param balance - what to draw.
 */
@Composable
fun BalancePane(model: AppViewModel, balance: BalanceUiState) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The popup measures its content unbounded, and a scrollable column
            // cannot be measured that way -- without a ceiling here the window
            // crashes instead of scrolling. `SettingsDialog` carries the same line
            // for the same reason.
            .heightIn(max = BALANCE_WINDOW_HEIGHT)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.balance),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { model.refreshBalance() },
                enabled = !balance.loading,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
            }
        }
        Text(
            stringResource(R.string.balance_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        val result = balance.result
        when {
            balance.loading && result == null -> Loading()
            balance.failed && result == null -> Notice(stringResource(R.string.balance_failed))
            result == null -> Notice(stringResource(R.string.balance_unavailable))
            result.isReady -> Ready(result, onTopUp = { uriHandler.openUri(it) })
            result.status == BalanceResult.STATUS_SIGNED_OUT ->
                Notice(stringResource(R.string.balance_signed_out))
            result.status == BalanceResult.STATUS_UNSUPPORTED ->
                Notice(stringResource(R.string.balance_unsupported))
            else -> Notice(stringResource(R.string.balance_unavailable))
        }
    }
}

@Composable
private fun Loading() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(10.dp))
        Text(
            stringResource(R.string.balance_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
            .padding(14.dp),
    )
}

@Composable
private fun Ready(result: BalanceResult, onTopUp: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        result.wallets.forEach { WalletRow(it) }
        if (result.bonusWallets.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.balance_bonus),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            result.bonusWallets.forEach { WalletRow(it) }
        }
        val topUp = result.topUpURL
        if (topUp != null) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = { onTopUp(topUp) }, shape = ButtonShape.Primary) {
                Text(stringResource(R.string.balance_top_up))
            }
        }
    }
}

/** One wallet: the currency on the left, the amount in the theme's own figures. */
@Composable
private fun WalletRow(wallet: BalanceWallet) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Corner.md)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            wallet.currency,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            wallet.balance,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
                fontFamily = FontFamily.Default,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
