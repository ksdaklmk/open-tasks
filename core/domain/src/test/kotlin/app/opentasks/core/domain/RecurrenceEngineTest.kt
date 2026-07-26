package app.opentasks.core.domain

import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class RecurrenceEngineTest {
    @Test
    fun dailyRecurrencePreservesWallTimeAcrossDst() {
        val start = ZonedDateTime.of(2026, 3, 7, 9, 0, 0, 0, ZoneId.of("America/New_York"))
        val occurrences = RecurrenceEngine.occurrences(
            seriesId = TaskId("daily-series"),
            firstStart = start,
            rule = RecurrenceRule(RecurrenceFrequency.DAILY, count = 3),
        )

        assertEquals(listOf(9, 9, 9), occurrences.map { it.startsAt.hour })
        assertNotEquals(occurrences[0].startsAt.offset, occurrences[2].startsAt.offset)
        assertEquals(3, occurrences.map { it.id }.distinct().size)
    }

    @Test
    fun deterministicIdsSurviveRedelivery() {
        val start = ZonedDateTime.parse("2026-07-26T09:00:00+07:00[Asia/Bangkok]")
        val rule = RecurrenceRule(
            frequency = RecurrenceFrequency.WEEKLY,
            weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
            count = 6,
        )

        val first = RecurrenceEngine.occurrences(TaskId("weekly-series"), start, rule)
        val second = RecurrenceEngine.occurrences(TaskId("weekly-series"), start, rule)

        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun monthlyRecurrenceUsesOriginalDayInsteadOfDrifting() {
        val start = ZonedDateTime.parse("2026-01-31T09:00:00+07:00[Asia/Bangkok]")
        val dates = RecurrenceEngine.occurrences(
            TaskId("month-end"),
            start,
            RecurrenceRule(RecurrenceFrequency.MONTHLY, count = 3),
        ).map { it.startsAt.toLocalDate().toString() }

        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31"), dates)
    }

    @Test
    fun everyRuleFormHonoursIntervalsWeekdaysAndLimits() {
        val zone = ZoneId.of("Asia/Bangkok")

        val daily = RecurrenceEngine.occurrences(
            TaskId("daily-interval"),
            ZonedDateTime.of(2026, 7, 26, 17, 0, 0, 0, zone),
            RecurrenceRule(RecurrenceFrequency.DAILY, interval = 2, count = 3),
        )
        assertEquals(
            listOf("2026-07-26", "2026-07-28", "2026-07-30"),
            daily.map { it.startsAt.toLocalDate().toString() },
        )

        val weekly = RecurrenceEngine.occurrences(
            TaskId("weekly-multiple-days"),
            ZonedDateTime.of(2026, 7, 26, 17, 0, 0, 0, zone),
            RecurrenceRule(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 2,
                weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
                count = 4,
            ),
        )
        assertEquals(
            listOf("2026-08-03", "2026-08-06", "2026-08-17", "2026-08-20"),
            weekly.map { it.startsAt.toLocalDate().toString() },
        )

        val monthly = RecurrenceEngine.occurrences(
            TaskId("monthly-interval"),
            ZonedDateTime.of(2026, 1, 31, 17, 0, 0, 0, zone),
            RecurrenceRule(RecurrenceFrequency.MONTHLY, interval = 2, count = 3),
        )
        assertEquals(
            listOf("2026-01-31", "2026-03-31", "2026-05-31"),
            monthly.map { it.startsAt.toLocalDate().toString() },
        )

        val yearly = RecurrenceEngine.occurrences(
            TaskId("yearly-interval"),
            ZonedDateTime.of(2024, 2, 29, 17, 0, 0, 0, zone),
            RecurrenceRule(
                frequency = RecurrenceFrequency.YEARLY,
                interval = 2,
                endDate = java.time.LocalDate.of(2028, 2, 29),
            ),
        )
        assertEquals(
            listOf("2024-02-29", "2026-02-28", "2028-02-29"),
            yearly.map { it.startsAt.toLocalDate().toString() },
        )
    }
}
