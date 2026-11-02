package me.shirazu.dshmizar.ui.conversation

/**
 * How long a copy of the transcript is trusted to say "a turn is in flight".
 *
 * The gap between sending a prompt and the host opening its turn is milliseconds;
 * two minutes is generous for that window and short enough that a stale copy cannot
 * hold the screen on "深度求索中~" after the work is over. A copy left behind by an
 * older host, or one whose refresh failed, is exactly the case this bounds.
 *
 * It lives here, once, because the session screen and the transcript each used to
 * declare their own copy of the same number with the same explanation.
 */
internal const val IN_FLIGHT_FRESH_MILLIS = 120_000L

/**
 * Whether the newest message is recent enough to believe the host's "running" lag.
 *
 * `now` is passed in rather than read here on purpose: the callers read the clock at
 * the same point in composition they always did, and a helper that read it itself
 * would move that moment -- and start recomposing on a tick nobody asked for.
 */
internal fun isRecent(newestAtMillis: Long?, nowMillis: Long): Boolean =
    newestAtMillis != null && nowMillis - newestAtMillis < IN_FLIGHT_FRESH_MILLIS
