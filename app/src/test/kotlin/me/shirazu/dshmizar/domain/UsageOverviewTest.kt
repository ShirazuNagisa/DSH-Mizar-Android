package me.shirazu.dshmizar.domain

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The usage fold: the arithmetic the home page's six figures and its year chart
 * are read from.
 *
 * Every case here is a figure an operator would notice being wrong -- a total that
 * double-counts a day two computers worked on, an hour of the day invented when the
 * host sent no breakdown, a model named by a coin toss. Layout is not tested; the
 * numbers behind the layout are.
 */
class UsageOverviewTest {

    private fun day(
        date: String,
        messages: Int = 1,
        input: Int = 100,
        output: Int = 50,
        hours: List<Int>? = null,
        models: List<ModelUsage>? = null,
    ) = UsageDay(date, messages, input, output, hours, models)

    @Test
    fun `a merged day is one row, and its figures are added`() {
        val laptop = UsageOverview(
            sessions = 3,
            days = listOf(day("2026-11-12", messages = 2, input = 100, output = 50)),
        )
        val desktop = UsageOverview(
            sessions = 4,
            days = listOf(day("2026-11-12", messages = 5, input = 300, output = 20)),
        )
        val merged = UsageOverview.merged(listOf(laptop, desktop))
        assertEquals(7, merged.sessions)
        assertEquals(1, merged.days.size)
        val only = merged.days.single()
        assertEquals(7, only.messages)
        assertEquals(400, only.inputTokens)
        assertEquals(70, only.outputTokens)
        assertEquals(470, only.tokens)
    }

    @Test
    fun `a day only one computer touched is kept as that computer reported it`() {
        val merged = UsageOverview.merged(
            listOf(
                UsageOverview(1, listOf(day("2026-11-10", messages = 2))),
                UsageOverview(1, listOf(day("2026-11-11", messages = 5))),
            ),
        )
        assertEquals(listOf("2026-11-10", "2026-11-11"), merged.days.map { it.date })
        assertEquals(7, merged.days.sumOf { it.messages })
    }

    @Test
    fun `hours and models are added bucket by bucket, not dropped`() {
        val morning = List(24) { hour -> if (hour == 9) 2 else 0 }
        val afternoon = List(24) { hour -> if (hour == 14) 3 else 0 }
        val merged = UsageOverview.merged(
            listOf(
                UsageOverview(
                    1,
                    listOf(
                        day(
                            "2026-11-12",
                            hours = morning,
                            models = listOf(ModelUsage("deepseek-v4", 2, 10, 5)),
                        ),
                    ),
                ),
                UsageOverview(
                    1,
                    listOf(
                        day(
                            "2026-11-12",
                            hours = afternoon,
                            models = listOf(
                                ModelUsage("deepseek-v4", 3, 20, 5),
                                ModelUsage("codex", 1, 3, 2),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val only = merged.days.single()
        assertEquals(2, only.hours?.get(9))
        assertEquals(3, only.hours?.get(14))
        assertEquals(listOf("deepseek-v4", "codex"), only.models?.map { it.model })
        assertEquals(5, only.models?.first { it.model == "deepseek-v4" }?.messages)
    }

    @Test
    fun `a merge sorts oldest first regardless of the order it was read in`() {
        val merged = UsageOverview.merged(
            listOf(
                UsageOverview(1, listOf(day("2026-11-12"))),
                UsageOverview(1, listOf(day("2026-11-10"))),
            ),
        )
        assertEquals(listOf("2026-11-10", "2026-11-12"), merged.days.map { it.date })
    }

    @Test
    fun `the six figures fold from the same rows the chart draws`() {
        val overview = UsageOverview(
            sessions = 9,
            days = listOf(
                day(
                    "2026-11-10",
                    messages = 2,
                    input = 100,
                    output = 50,
                    hours = List(24) { if (it == 9) 2 else 0 },
                    models = listOf(ModelUsage("deepseek-v4", 2, 100, 50)),
                ),
                day(
                    "2026-11-11",
                    messages = 3,
                    input = 200,
                    output = 100,
                    hours = List(24) { if (it == 14) 5 else 0 },
                    models = listOf(
                        ModelUsage("deepseek-v4", 1, 50, 10),
                        ModelUsage("codex", 2, 150, 90),
                    ),
                ),
            ),
        )
        val metrics = overview.metrics()
        assertEquals(9, metrics.sessions)
        assertEquals(5L, metrics.messages)
        assertEquals(450L, metrics.tokens)
        assertEquals(2, metrics.activeDays)
        // The busiest hour is a count of messages, so 14:00 wins with 5 over 09:00's 2.
        assertEquals(14, metrics.busiestHour)
        // Ties are broken by name, so two phones reading this history name one model.
        assertEquals("deepseek-v4", metrics.topModel)
    }

    @Test
    fun `an absent breakdown is unknown, not midnight and not a tie`() {
        val metrics = UsageOverview(1, listOf(day("2026-11-11", messages = 2))).metrics()
        assertNull(metrics.busiestHour)
        assertNull(metrics.topModel)
    }

    @Test
    fun `a top-model tie picks the same name every time`() {
        val overview = UsageOverview(
            1,
            listOf(
                day(
                    "2026-11-11",
                    models = listOf(
                        ModelUsage("zeta", 4, 0, 0),
                        ModelUsage("alpha", 4, 0, 0),
                    ),
                ),
            ),
        )
        assertEquals("alpha", overview.metrics().topModel)
    }

    @Test
    fun `a busy day is scaled against the peak and an empty day is no level`() {
        val quiet = day("2026-11-10", messages = 1, input = 10, output = 5)
        val peak = day("2026-11-11", messages = 5, input = 900, output = 100)
        assertEquals(3, UsageCalendar.level(peak, 1000))
        assertEquals(0, UsageCalendar.level(quiet, 1000))
        // A day with messages but no recorded tokens still happened: the empty cell
        // is for a day with no work, not for one the roll-up could not price.
        assertEquals(0, UsageCalendar.level(day("2026-11-12", messages = 2), 1000))
        assertNull(UsageCalendar.level(null, 1000))
        assertNull(UsageCalendar.level(day("2026-11-13", messages = 0, input = 0, output = 0), 1000))
    }

    @Test
    fun `the chart window is a whole number of weeks, ending today`() {
        val today = LocalDate.of(2026, 11, 13)
        val cells = UsageCalendar.cells(
            days = listOf(day("2026-11-12", messages = 2)),
            today = today,
            span = 7,
            firstDayOfWeek = DayOfWeek.MONDAY,
        )
        // 7 days back from Friday the 13th is Saturday the 7th; the window is padded
        // back to the Monday before it, so the first column is a whole week.
        assertEquals("2026-11-02", cells.first().date)
        assertEquals("2026-11-13", cells.last().date)
        assertEquals(12, cells.size)
        assertEquals(1, cells.count { it.day != null })
        assertEquals(1, cells.count { it.day?.messages == 2 })

        val columns = UsageCalendar.columns(cells)
        assertEquals(2, columns.size)
        assertTrue(columns.all { it.size <= 7 })
    }

    @Test
    fun `the chart's peak is the busiest token day in the window`() {
        val cells = UsageCalendar.cells(
            days = listOf(
                day("2026-11-11", input = 300, output = 200),
                day("2026-11-12", input = 50, output = 25),
            ),
            today = LocalDate.of(2026, 11, 13),
            span = 7,
            firstDayOfWeek = DayOfWeek.MONDAY,
        )
        assertEquals(500, UsageCalendar.peakTokens(cells))
    }

    @Test
    fun `compact counts round only where a figure is read at a glance`() {
        assertEquals("0", compactCount(0))
        assertEquals("999", compactCount(999))
        assertEquals("1K", compactCount(1_000))
        assertEquals("12.3K", compactCount(12_345))
        assertEquals("1.2M", compactCount(1_234_567))
        // Below a thousand the number is exact: rounding the last three digits would
        // make the card disagree with the list above it.
        assertEquals("999", compactCount(999))
    }

    @Test
    fun `an unknown hour draws an em dash, never midnight`() {
        assertEquals("—", hourLabel(null))
        assertEquals("09:00", hourLabel(9))
        assertEquals("00:00", hourLabel(0))
        assertEquals("23:00", hourLabel(23))
        assertEquals("—", hourLabel(24))
        assertEquals("—", hourLabel(-1))
    }

    @Test
    fun `the host DTO folds onto the chart model without wrapping or inventing zeroes`() {
        val wire = me.shirazu.mizar.kit.UsageOverview(
            sessions = 12,
            days = listOf(
                me.shirazu.mizar.kit.UsageDay(
                    date = "2026-11-12",
                    messages = 3,
                    inputTokens = 100,
                    outputTokens = 50,
                    hours = List(24) { hour -> if (hour == 9) 3 else 0 },
                    models = listOf(me.shirazu.mizar.kit.ModelUsage("deepseek-v4", 3, 100, 50)),
                ),
                // A day the host could not price: an empty list on the wire is
                // "cannot say", which must not become midnight and zero tokens.
                me.shirazu.mizar.kit.UsageDay(date = "2026-11-13"),
            ),
        )
        val app = wire.toAppHistory()
        assertEquals(12, app.sessions)
        assertEquals(2, app.days.size)
        assertEquals(150, app.days[0].tokens)
        assertEquals(3, app.days[0].hours?.get(9))
        assertEquals("deepseek-v4", app.days[0].models?.single()?.model)
        assertNull(app.days[1].hours)
        assertNull(app.days[1].models)
        // 64-bit wire counts saturate the 32-bit chart model instead of wrapping to
        // a negative bar.
        val huge = me.shirazu.mizar.kit.UsageOverview(
            sessions = Long.MAX_VALUE,
            days = listOf(me.shirazu.mizar.kit.UsageDay(date = "2026-11-13", messages = Long.MAX_VALUE)),
        ).toAppHistory()
        assertEquals(Int.MAX_VALUE, huge.sessions)
        assertEquals(Int.MAX_VALUE, huge.days.single().messages)
    }

    @Test
    fun `the sample histories are ordered, shaped like the wire, and can be merged`() {
        val today = LocalDate.of(2026, 11, 13)
        val readings = SampleUsage.readings(today)
        assertTrue(readings.isNotEmpty())
        for (reading in readings) {
            assertTrue(reading.days.isNotEmpty())
            assertTrue(reading.days.zipWithNext().all { (a, b) -> a.date < b.date })
            assertTrue(reading.days.all { it.date < today.plusDays(1).toString() })
            assertTrue(reading.days.all { it.hours?.size == 24 })
            assertTrue(reading.days.all { !it.models.isNullOrEmpty() })
        }
        val merged = UsageOverview.merged(readings)
        assertTrue(merged.sessions > 0)
        assertTrue(merged.days.isNotEmpty())
        assertTrue(merged.metrics().topModel != null)
    }
}
