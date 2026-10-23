package me.shirazu.mizar.kit

/**
 * One way a pairing attempt can reach the computer.
 *
 * A plugin 0.8.5+ QR carries two addresses: `h`, the address the computer is
 * listening on, and `u`, the relay base URL the same room is reachable through.
 * Reading both and using one was the bug this type exists to prevent: a phone
 * that cannot reach the LAN address — cellular, a client-isolated Wi-Fi, a denied
 * local-network permission, an address that has gone stale — could never finish
 * first pairing, although the relay in the same code was answering.
 *
 * Each route carries its own leash. A dead LAN address allowed the relay's
 * twenty seconds leaves the operator looking at a pairing screen they have
 * already given up on before the fallback is tried.
 */
sealed interface PairingRoute {

    /** How long this route may take. */
    val timeoutMillis: Long

    /** The address to name when this route is logged or explained. */
    val address: String

    /** Straight to `host:port` — the same Wi-Fi, or a tailnet. */
    data class Direct(val authority: String) : PairingRoute {
        override val timeoutMillis: Long get() = DIRECT_TIMEOUT_MILLIS
        override val address: String get() = authority
    }

    /** Through a reverse tunnel; the room is part of the base URL. */
    data class Relay(val baseUrl: String) : PairingRoute {
        override val timeoutMillis: Long get() = RELAY_TIMEOUT_MILLIS
        override val address: String get() = baseUrl
    }

    companion object {
        /** Enough for a direct address on a network that is going to answer at all. */
        const val DIRECT_TIMEOUT_MILLIS = 6_000L

        /** A relay that is reachable but slow is worth waiting for. */
        const val RELAY_TIMEOUT_MILLIS = 20_000L

        /**
         * The routes to try, in order.
         *
         * Direct first because the operator's own network is faster and involves
         * nobody else; the relay behind it because it is the route that still
         * works when the first one cannot.
         */
        fun plan(authority: String?, baseUrl: String?): List<PairingRoute> = buildList {
            authority?.takeIf { it.isNotBlank() }?.let { add(Direct(it)) }
            baseUrl?.takeIf { it.isNotBlank() && PairingUri.isUsableBaseUrl(it) }?.let { add(Relay(it)) }
        }
    }
}

/** Which route answered, and the pairing it produced. */
data class PairingOutcome(
    val host: PairedHost,
    val route: PairingRoute,
)

/**
 * Every route a pairing tried, and why it stopped.
 *
 * One error rather than only the last failure, because the list of routes is what
 * turns "pairing failed" into a diagnosis: a pairing that never left the phone and
 * one that reached the relay and was refused look identical on screen.
 *
 * @property attempts every route that was dialled, in order.
 * @property finalError the answer to act on: the host's refusal when it answered,
 *   otherwise the last route's transport error.
 */
class PairingFailure(
    val attempts: List<Attempt>,
    val finalError: Throwable,
) : Exception(finalError.message, finalError) {

    /** One route that did not pair. */
    data class Attempt(val route: PairingRoute, val error: Throwable) {
        /** How this attempt reads in a log line. */
        val line: String get() = "${route.label} — ${error.message ?: error::class.simpleName}"
    }

    /** One line per route, for `activity.log`. */
    fun lines(): List<String> = attempts.map { it.line }
}

/** How a route reads in a log line, e.g. `direct 10.0.0.5:19390`. */
val PairingRoute.label: String
    get() = when (this) {
        is PairingRoute.Direct -> "direct $authority"
        is PairingRoute.Relay -> "relay $baseUrl"
    }
