package me.shirazu.dshmizar.domain

import java.time.LocalDate
import kotlin.random.Random

/**
 * Fake usage histories for previews, render tests and wiring before the host
 * answers `usage.overview`.
 *
 * Deterministic on purpose: [overview] takes the window's last day as a parameter
 * and builds from a fixed seed, so a screenshot taken twice shows the same chart.
 * Nothing here is used in production -- the Lead swaps in real readings -- but the
 * shape is the production shape, so the swap is an assignment rather than a
 * rewrite.
 */
object SampleUsage {

    /** Two computers' histories, the shape the home page merges. */
    fun readings(today: LocalDate = LocalDate.now()): List<UsageOverview> = listOf(
        overview(today, seed = 7, density = 5, sessions = 23),
        // The second computer works fewer days, so the merge has a day only one of
        // them touched -- the case that used to be one machine's answer.
        overview(today, seed = 19, density = 9, sessions = 9),
    )

    /** One computer's history: about a year of days, only some of them worked. */
    fun overview(
        today: LocalDate = LocalDate.now(),
        seed: Int = 7,
        density: Int = 5,
        sessions: Int = 23,
    ): UsageOverview {
        val random = Random(seed)
        val days = ArrayList<UsageDay>(260)
        for (offset in USAGE_SPAN - 1 downTo 0) {
            // Every `density`-th day has a burst of work, most days are light, and
            // the rest are empty -- which is what a year of a real operator looks
            // like, and what the chart's colour ladder needs to be legible.
            val roll = random.nextInt(100)
            if (roll >= 62) continue
            val busy = offset % (density * 7) < 2
            val messages = if (busy) 14 + roll % 18 else 1 + roll % 9
            val date = today.minusDays(offset.toLong()).toString()
            val models = modelsFor(random, messages)
            days.add(
                UsageDay(
                    date = date,
                    messages = messages,
                    inputTokens = messages * (900 + random.nextInt(600)),
                    outputTokens = messages * (240 + random.nextInt(160)),
                    hours = hoursFor(random, messages),
                    models = models,
                ),
            )
        }
        return UsageOverview(sessions = sessions, days = days)
    }

    private fun hoursFor(random: Random, messages: Int): List<Int> {
        val buckets = MutableList(24) { 0 }
        repeat(messages) {
            // A working day: mornings and afternoons, with the odd late session.
            val hour = when (random.nextInt(10)) {
                in 0..3 -> 9 + random.nextInt(4)
                in 4..7 -> 13 + random.nextInt(5)
                8 -> 20 + random.nextInt(3)
                else -> 7
            }
            buckets[hour] += 1
        }
        return buckets
    }

    private fun modelsFor(random: Random, messages: Int): List<ModelUsage> {
        val names = listOf("deepseek-v4", "claude-sonnet-4.5", "gpt-5.2-codex")
        val first = names[random.nextInt(names.size)]
        val second = names[(names.indexOf(first) + 1 + random.nextInt(2)) % names.size]
        val firstMessages = (messages * (55 + random.nextInt(30)) / 100).coerceAtLeast(1)
        val secondMessages = (messages - firstMessages).coerceAtLeast(0)
        return listOfNotNull(
            ModelUsage(first, firstMessages, firstMessages * 1100, firstMessages * 260),
            if (secondMessages > 0) {
                ModelUsage(second, secondMessages, secondMessages * 950, secondMessages * 220)
            } else {
                null
            },
        )
    }

    /** About a year, which is the window the chart draws. */
    private const val USAGE_SPAN = UsageCalendar.DEFAULT_SPAN_DAYS
}
