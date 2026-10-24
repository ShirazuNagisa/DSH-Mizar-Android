package me.shirazu.mizar.kit.account

import kotlinx.serialization.Serializable

/**
 * One wallet, as the host reports it.
 *
 * The balance stays a string: it is a decimal the host already validated, and
 * turning it into a `Double` on the way to the screen would round somebody's money.
 */
@Serializable
data class BalanceWallet(val currency: String = "", val balance: String = "")

/**
 * The connected computer's official DeepSeek wallets.
 *
 * Not the phone's relay credits: this is what the desktop's own account holds, read
 * through the host because only the host has the credentials. `status` says why
 * there are no wallets when there are none -- a host too old to ask
 * (`unsupported`), nobody signed in to DeepSeek on that computer (`signed-out`), or
 * the query failed (`unavailable`). A `ready` answer always carries at least one.
 */
@Serializable
data class BalanceResult(
    val status: String = STATUS_UNAVAILABLE,
    val wallets: List<BalanceWallet> = emptyList(),
    val bonusWallets: List<BalanceWallet> = emptyList(),
    val topUpURL: String? = null,
) {
    val isReady: Boolean get() = status == STATUS_READY

    companion object {
        const val STATUS_READY = "ready"
        const val STATUS_UNSUPPORTED = "unsupported"
        const val STATUS_SIGNED_OUT = "signed-out"
        const val STATUS_UNAVAILABLE = "unavailable"
    }
}
