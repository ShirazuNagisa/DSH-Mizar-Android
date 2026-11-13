package me.shirazu.dshmizar.domain

import me.shirazu.mizar.kit.UsageOverview as HostUsageOverview

/**
 * The wire's usage history, folded onto the app's own model.
 *
 * The host counts in 64-bit numbers because a long-lived install can outgrow an
 * `Int` of tokens; the app's chart model uses `Int` because every figure it draws
 * is scaled to a pixel anyway. The conversion saturates instead of wrapping: a
 * number too large for the model reads as "very large", never as a negative bar.
 *
 * `hours` and `models` stay nullable: an older host that answers without them
 * means "cannot say", which the app draws as a blank figure rather than zero.
 */
internal fun HostUsageOverview.toAppHistory(): UsageOverview = UsageOverview(
    sessions = sessions.saturatedInt(),
    days = days.map { day ->
        UsageDay(
            date = day.date,
            messages = day.messages.saturatedInt(),
            inputTokens = day.inputTokens.saturatedInt(),
            outputTokens = day.outputTokens.saturatedInt(),
            hours = day.hours.takeIf { it.isNotEmpty() }?.map { it.saturatedInt() },
            models = day.models.takeIf { it.isNotEmpty() }?.map { usage ->
                ModelUsage(
                    model = usage.model,
                    messages = usage.messages.saturatedInt(),
                    inputTokens = usage.inputTokens.saturatedInt(),
                    outputTokens = usage.outputTokens.saturatedInt(),
                )
            },
        )
    },
)

/** Clamp a host count into the chart model without wrapping at the sign bit. */
private fun Long.saturatedInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
