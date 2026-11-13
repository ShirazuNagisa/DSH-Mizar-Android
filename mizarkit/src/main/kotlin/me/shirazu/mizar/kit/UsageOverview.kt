package me.shirazu.mizar.kit

import kotlinx.serialization.Serializable

/**
 * `usage.overview` — what the home page charts: the last weeks of work, one row per
 * day that had any, folded across every agent the host serves.
 *
 * The host already does the folding (a date two engines worked on is one row),
 * because the alternative is the phone adding up rows that disagree about where a
 * day starts. The phone reads what it is given and draws it.
 *
 * This is the shape `AGI Mizar/crates/agi-protocol/src/api.rs` serialises with
 * `rename_all = "camelCase"`; `hours` is always 24 buckets and `models` is always a
 * list (possibly empty), so neither is optional here.
 */
@Serializable
data class UsageOverview(
    /** Top-level, unarchived sessions across every agent. */
    val sessions: Long = 0,
    /** Only days with activity, oldest first. */
    val days: List<UsageDay> = emptyList(),
) {
    /** The day for a "YYYY-MM-DD" label, when the host worked on it. */
    fun day(date: String): UsageDay? = days.firstOrNull { it.date == date }
}

/** One day of work. */
@Serializable
data class UsageDay(
    /** "YYYY-MM-DD", the host's local calendar day. */
    val date: String,
    /** Every user or assistant message counts once; tool calls do not. */
    val messages: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    /** 24 buckets, the host's local hours, index 0 being midnight. */
    val hours: List<Long> = emptyList(),
    /** What each model did on this day, when the host says. */
    val models: List<ModelUsage> = emptyList(),
) {
    /** Everything billed for this day, as the one number a chart can scale by. */
    val tokens: Long get() = inputTokens + outputTokens
}

/** What one model did on one day. */
@Serializable
data class ModelUsage(
    val model: String,
    val messages: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
)
