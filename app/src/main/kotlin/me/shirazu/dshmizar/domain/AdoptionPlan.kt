package me.shirazu.dshmizar.domain

import me.shirazu.dshmizar.PairingStage
import me.shirazu.dshmizar.Phase

/**
 * What adopting a connection does, decided once.
 *
 * Adopting a route is nine steps in a fixed order, and five of them are conditional.
 * Those five are pure decisions -- three booleans in, five values out -- so they are
 * worth stating in one place rather than re-deriving inside a two-hundred-line function
 * where the reason for each one is a comment rather than a name.
 *
 * The order itself is not here: it stays in the one function that performs it, because
 * "what happens after the socket becomes live" is a sequence, and hiding a sequence
 * behind an abstraction makes it harder to read, not easier.
 *
 * Three inputs, and each has a story:
 *
 *  - **`warm`** -- a first pairing. The screen stays on the connecting page while the
 *    index is read, because READY would swap in the shell and the progress would never
 *    be seen. A reconnect has nothing to warm and goes straight through.
 *  - **`relayed`** -- the route that answered is a tunnel, not the computer's own
 *    address. Only a relayed link has a tunnel to move and a LAN to look for.
 *  - **`localNetworkFirst`** -- the operator asked for the LAN when it exists. Looking
 *    for it is only worth a round trip on a relayed link; on the LAN the question is
 *    already answered.
 */
internal data class AdoptionPlan(
    val phase: Phase,
    val pairingStage: PairingStage?,
    /** Whether the first page is the index warm (with progress) rather than a refresh. */
    val warmsTheIndex: Boolean,
    /** Whether to go looking for the computer's own addresses now that a socket exists. */
    val looksForLan: Boolean,
    /** Whether to tell the host which relay its tunnel belongs on. */
    val syncsRelayTier: Boolean,
) {
    companion object {

        fun of(warm: Boolean, relayed: Boolean, localNetworkFirst: Boolean): AdoptionPlan = AdoptionPlan(
            phase = if (warm) Phase.CONNECTING else Phase.READY,
            pairingStage = if (warm) PairingStage.SYNCING else null,
            warmsTheIndex = warm,
            looksForLan = relayed && localNetworkFirst,
            syncsRelayTier = relayed,
        )
    }
}
