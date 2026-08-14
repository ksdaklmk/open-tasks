package app.opentasks.core.domain

import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.ScheduleMonthProjection
import app.opentasks.core.model.ScheduleMonthDay
import app.opentasks.core.model.Task
import app.opentasks.core.model.WorkspaceSnapshot
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private const val MONTH_CELL_DOT_CAP = 6

fun computeScheduleMonthProjection(
    snapshot: WorkspaceSnapshot,
    selectedMonth: YearMonth,
    clock: Clock,
    displayZone: ZoneId,
): ScheduleMonthProjection {
    val firstDate = selectedMonth.atDay(1)
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val projectionClock = Clock.fixed(clock.instant(), displayZone)
    val tasksByDate = snapshot.tasks
        .asSequence()
        .filter { it.deletedAt == null }
        .mapNotNull { task -> task.scheduleDate()?.let { it to task } }
        .groupBy({ it.first }, { it.second })

    return ScheduleMonthProjection(
        month = selectedMonth,
        days = List(42) { offset ->
            val date = firstDate.plusDays(offset.toLong())
            val tasks = tasksByDate[date].orEmpty().sortedWith(
                compareBy<Task>(
                    { task -> (task.start ?: task.due)?.let { it.instant.atZone(it.zone()).toLocalTime() } ?: LocalTime.MAX },
                    { it.title.lowercase(Locale.UK) },
                    { it.id.value },
                ),
            )
            ScheduleMonthDay(
                date = date,
                inSelectedMonth = YearMonth.from(date) == selectedMonth,
                tasks = tasks,
                totalCount = tasks.size,
                completedCount = tasks.count(Task::isCompleted),
                overdueCount = tasks.count { !it.isCompleted && classifyDueBucket(it.due, projectionClock) == DueBucket.OVERDUE },
                densityDotCount = minOf(tasks.size, MONTH_CELL_DOT_CAP),
                hasDensityOverflow = tasks.size > MONTH_CELL_DOT_CAP,
            )
        },
    )
}

private fun Task.scheduleDate(): LocalDate? = (start ?: due)?.let { it.instant.atZone(it.zone()).toLocalDate() }
