package me.shirazu.dshmizar.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The host's usage history (`usage.overview`) and the folds the home page reads
 * from it.
 *
 * This is the Android counterpart of iOS `MizarKit/UsageOverview.swift`, and it
 * keeps the same rule: a day that had no work is a day the host does not report,
 * so **absence is the zero, and a missing answer is not a zero**. Everything here
 * is pure Kotlin -- no Android type appears -- because the folding is the part
 * that can be wrong, and a wrong fold has no visible symptom except a figure
 * nobody can check.
 *
 * The wire shape is `AGI Mizar/crates/agi-protocol/src/api.rs`:
 *
 * ```
 * { "sessions": 42, "days": [ { "date": "2026-11-12", "messages": 3,
 *   "inputTokens": 1200, "outputTokens": 800,
 *   "hours": [ ...24 numbers... ],
 *   "models": [ { "model": "...", "messages": 3, ... } ] } ] }
 * ```
 *
 * `hours` and `models` are optional here even though today's host always sends
 * them: an older host answering without them must read as "cannot say", which is
 * a blank figure rather than a wrong hour of the day.
 */

/** One day of work, as `usage.overview` reports it. */
data class UsageDay(
    /** "YYYY-MM-DD", the host's own local calendar day. */
    val date: String,
    /** Every user or assistant message counts once; tool calls do not. */
    val messages: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    /** 24 buckets of message counts, the host's local hours, index 0 midnight. */
    val hours: List<Int>? = null,
    /** What each model did on this day, when the host says. */
    val models: List<ModelUsage>? = null,
) {
    /** Everything billed for this day, as the one number a chart scales by. */
    val tokens: Int get() = inputTokens + outputTokens
}

/** What one model did on one day. */
data class ModelUsage(
    val model: String,
    val messages: Int,
    val inputTokens: Int,
    val outputTokens: Int,
)

/** The whole history the host keeps, oldest day first. */
data class UsageOverview(
    /** Top-level, unarchived sessions across every agent. */
    val sessions: Int,
    /** Only days with activity, oldest first. */
    val days: List<UsageDay>,
) {
    /** The day for a "YYYY-MM-DD" label, when the host worked on it. */
    fun day(date: String): UsageDay? = days.firstOrNull { it.date == date }

    /** The six figures the home page draws, folded from the same rows as the heatmap. */
    fun metrics(): UsageMetrics {
        var messages = 0L
        var tokens = 0L
        val hours = IntArray(24)
        var hoursKnown = false
        val byModel = LinkedHashMap<String, Long>()
        for (day in days) {
            messages += day.messages
            tokens += day.tokens
            day.hours?.let { buckets ->
                hoursKnown = true
                for (hour in 0 until minOf(24, buckets.size)) hours[hour] += buckets[hour]
            }
            for (usage in day.models.orEmpty()) {
                byModel[usage.model] = (byModel[usage.model] ?: 0L) + usage.messages
            }
        }
        // The busiest hour is a count of messages, so a tie is real rather than a
        // rounding artefact: the earliest hour wins, which is the one a person would
        // say first if asked "when do you work".
        var busiest: Int? = null
        if (hoursKnown) {
            var peak = 0
            for (hour in 0 until 24) {
                if (hours[hour] > peak) {
                    peak = hours[hour]
                    busiest = hour
                }
            }
        }
        // Ties on the top model are broken by name so two devices reading the same
        // history cannot name different models under the same figures.
        var topModel: String? = null
        var topMessages = -1L
        for ((model, count) in byModel) {
            if (count > topMessages || (count == topMessages && topModel != null && model < topModel)) {
                topMessages = count
                topModel = model
            }
        }
        return UsageMetrics(
            sessions = sessions,
            messages = messages,
            tokens = tokens,
            activeDays = days.size,
            busiestHour = busiest,
            topModel = topModel,
        )
    }

    companion object {
        /**
         * Several computers' histories, added up day by day.
         *
         * A day that two machines worked on is one row, the same rule the host
         * applies to two agents on one machine: the operator's question is "what did
         * I do on Tuesday", not "which machine was it". Hours and models are added
         * bucket by bucket rather than dropped, because the busiest hour and the top
         * model are read from the merged history.
         */
        fun merged(readings: List<UsageOverview>): UsageOverview {
            val byDate = HashMap<String, UsageDay>(readings.sumOf { it.days.size })
            var sessions = 0
            for (reading in readings) {
                sessions += reading.sessions
                for (day in reading.days) {
                    val existing = byDate[day.date]
                    byDate[day.date] = if (existing == null) {
                        day
                    } else {
                        UsageDay(
                            date = day.date,
                            messages = existing.messages + day.messages,
                            inputTokens = existing.inputTokens + day.inputTokens,
                            outputTokens = existing.outputTokens + day.outputTokens,
                            hours = addHours(existing.hours, day.hours),
                            models = addModels(existing.models, day.models),
                        )
                    }
                }
            }
            return UsageOverview(sessions, byDate.values.sortedBy { it.date })
        }

        private fun addHours(left: List<Int>?, right: List<Int>?): List<Int>? = when {
            left == null && right == null -> null
            left == null -> right
            right == null -> left
            else -> {
                val count = maxOf(left.size, right.size)
                (0 until count).map { hour ->
                    (left.getOrNull(hour) ?: 0) + (right.getOrNull(hour) ?: 0)
                }
            }
        }

        private fun addModels(left: List<ModelUsage>?, right: List<ModelUsage>?): List<ModelUsage>? {
            if (left == null && right == null) return null
            val byModel = LinkedHashMap<String, ModelUsage>()
            for (usage in left.orEmpty() + right.orEmpty()) {
                val existing = byModel[usage.model]
                byModel[usage.model] = if (existing == null) {
                    usage
                } else {
                    ModelUsage(
                        model = usage.model,
                        messages = existing.messages + usage.messages,
                        inputTokens = existing.inputTokens + usage.inputTokens,
                        outputTokens = existing.outputTokens + usage.outputTokens,
                    )
                }
            }
            return byModel.values.sortedWith(compareByDescending<ModelUsage> { it.messages }.thenBy { it.model })
        }
    }
}

/** The six readings the home page draws, already folded. */
data class UsageMetrics(
    val sessions: Int,
    val messages: Long,
    val tokens: Long,
    /** Days the host reported any work on. */
    val activeDays: Int,
    /** 0..23, or `null` when no day carried an hour breakdown. */
    val busiestHour: Int?,
    /** The model that answered the most messages, or `null` when none was named. */
    val topModel: String?,
)

/** One square of the year chart: a date, and the day behind it when there was one. */
data class UsageHeatmapCell(
    /** "YYYY-MM-DD". */
    val date: String,
    /** The host's row for that day, or `null` when nothing happened. */
    val day: UsageDay?,
)

/**
 * The calendar the year chart is laid out on.
 *
 * A phone shows the history as one column per week, seven squares tall, because a
 * year does not fit the width in any other shape. The first column is usually
 * short -- the window did not start on the week's first day -- and the chart pads
 * it rather than shifting every other column by a day, so a column really is a
 * week and the rows line up with the weekdays they mean.
 */
object UsageCalendar {

    /** About a year of weeks: 53 columns of 7 days. */
    const val DEFAULT_SPAN_DAYS = 371

    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** The first day of the week the phone's locale uses, which is what a column means. */
    fun firstDayOfWeek(locale: Locale = Locale.getDefault()): DayOfWeek =
        WeekFields.of(locale).firstDayOfWeek

    /**
     * The squares to draw, oldest first, padded at the front to a whole week.
     *
     * @param days the host's rows, any order.
     * @param today the last day of the window; a parameter rather than a clock read
     *   so the layout has a test.
     * @param span how many days before `today` the window reaches.
     * @param firstDayOfWeek which weekday starts a column.
     */
    fun cells(
        days: List<UsageDay>,
        today: LocalDate = LocalDate.now(),
        span: Int = DEFAULT_SPAN_DAYS,
        firstDayOfWeek: DayOfWeek = firstDayOfWeek(),
    ): List<UsageHeatmapCell> {
        val safeSpan = span.coerceAtLeast(1)
        val byDate = HashMap<String, UsageDay>(days.size)
        for (day in days) byDate[day.date] = day
        val start = today.minusDays((safeSpan - 1).toLong())
        // Back to the first weekday of the window, so every column is a whole week.
        val lead = ((start.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
        val first = start.minusDays(lead.toLong())
        val count = ChronoUnit.DAYS.between(first, today).toInt().coerceAtLeast(0)
        return (0..count).map { offset ->
            val date = first.plusDays(offset.toLong()).format(ISO)
            UsageHeatmapCell(date, byDate[date])
        }
    }

    /** Cut the squares into whole weeks, oldest column first. */
    fun columns(cells: List<UsageHeatmapCell>): List<List<UsageHeatmapCell>> = cells.chunked(7)

    /**
     * Which of four accent steps a day is drawn in, by the tokens it spent against
     * the busiest day in the window. `null` is an empty square.
     */
    fun level(day: UsageDay?, peakTokens: Int): Int? {
        if (day == null) return null
        if (day.tokens <= 0 && day.messages <= 0) return null
        val peak = peakTokens.coerceAtLeast(1)
        val share = day.tokens.toDouble() / peak.toDouble()
        return (((share * 4.0).roundToLong() - 1).toInt()).coerceIn(0, 3)
    }

    /** The busiest day's token count in the window, which the colours scale against. */
    fun peakTokens(cells: List<UsageHeatmapCell>): Int =
        cells.maxOfOrNull { it.day?.tokens ?: 0 }?.coerceAtLeast(0) ?: 0
}

/**
 * A count the way a compact reading shows it: `999`, `12.3K`, `4.1M`.
 *
 * The iOS app prints `formatted(.number.notation(.compactName))`; Android has no
 * one-call equivalent on minSdk 26, so the ladder is written out. Below a thousand
 * the number is exact -- rounding the last three digits of a day's messages would
 * make the figure disagree with the list above it.
 */
fun compactCount(value: Long): String {
    val safe = value.coerceAtLeast(0)
    return when {
        safe < 1_000 -> safe.toString()
        safe < 1_000_000 -> compactUnit(safe / 1_000.0, "K")
        safe < 1_000_000_000 -> compactUnit(safe / 1_000_000.0, "M")
        else -> compactUnit(safe / 1_000_000_000.0, "B")
    }
}

private fun compactUnit(value: Double, unit: String): String {
    val rounded = (value * 10.0).roundToLong() / 10.0
    val whole = rounded.toLong()
    return if (rounded == whole.toDouble()) "$whole$unit" else "${rounded}$unit"
}

/** `09:00`, or an em dash when no hour breakdown arrived: never `00:00` for unknown. */
fun hourLabel(hour: Int?): String =
    if (hour == null || hour !in 0..23) "—" else String.format(Locale.ROOT, "%02d:00", hour)

