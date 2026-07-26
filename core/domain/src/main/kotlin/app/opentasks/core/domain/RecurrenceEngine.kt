package app.opentasks.core.domain

import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.TaskId
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

data class TaskOccurrence(
    val id: TaskId,
    val index: Int,
    val startsAt: ZonedDateTime,
)

object RecurrenceEngine {
    fun occurrences(
        seriesId: TaskId,
        firstStart: ZonedDateTime,
        rule: RecurrenceRule,
        limit: Int = 100,
    ): List<TaskOccurrence> {
        require(limit > 0)
        val maximum = minOf(limit, rule.count ?: Int.MAX_VALUE)
        if (maximum == 0) return emptyList()

        val dates = when (rule.frequency) {
            RecurrenceFrequency.DAILY -> daily(firstStart.toLocalDate(), rule, maximum)
            RecurrenceFrequency.WEEKLY -> weekly(firstStart.toLocalDate(), rule, maximum)
            RecurrenceFrequency.MONTHLY -> steppedDates(
                firstStart.toLocalDate(),
                rule,
                maximum,
            ) { base, step -> base.plusMonths(step.toLong() * rule.interval) }
            RecurrenceFrequency.YEARLY -> steppedDates(
                firstStart.toLocalDate(),
                rule,
                maximum,
            ) { base, step -> base.plusYears(step.toLong() * rule.interval) }
        }

        return dates.mapIndexed { index, date ->
            val local = date.atTime(firstStart.toLocalTime())
            val zoned = local.atZone(firstStart.zone)
            val key = "${zoned.toLocalDateTime()}@${zoned.zone.id}"
            TaskOccurrence(
                id = TaskId.deterministicOccurrence(seriesId, key),
                index = index,
                startsAt = zoned,
            )
        }
    }

    private fun daily(
        first: LocalDate,
        rule: RecurrenceRule,
        maximum: Int,
    ): List<LocalDate> = steppedDates(first, rule, maximum) { base, step ->
        base.plusDays(step.toLong() * rule.interval)
    }

    private fun steppedDates(
        first: LocalDate,
        rule: RecurrenceRule,
        maximum: Int,
        stepper: (LocalDate, Int) -> LocalDate,
    ): List<LocalDate> {
        val result = mutableListOf<LocalDate>()
        var step = 0
        while (result.size < maximum) {
            val candidate = stepper(first, step++)
            if (rule.endDate != null && candidate > rule.endDate) break
            result += candidate
        }
        return result
    }

    private fun weekly(
        first: LocalDate,
        rule: RecurrenceRule,
        maximum: Int,
    ): List<LocalDate> {
        val weekdays = rule.weekdays.ifEmpty { setOf(first.dayOfWeek) }
        val weekAnchor = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val result = mutableListOf<LocalDate>()
        var cursor = first
        while (result.size < maximum) {
            if (rule.endDate != null && cursor > rule.endDate) break
            val weeksFromAnchor = java.time.temporal.ChronoUnit.WEEKS.between(
                weekAnchor,
                cursor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
            )
            if (weeksFromAnchor % rule.interval == 0L && cursor.dayOfWeek in weekdays) {
                result += cursor
            }
            cursor = cursor.plusDays(1)
        }
        return result
    }
}
