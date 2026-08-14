package app.opentasks.core.domain

import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.ScheduleMonthProjection
import app.opentasks.core.model.ScheduleMonthDay
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private const val MONTH_CELL_DOT_CAP = 6

sealed interface ScheduleMoveTarget {
    data class Day(val date: LocalDate) : ScheduleMoveTarget
    data class Unscheduled(val reminderRemovalConfirmed: Boolean) : ScheduleMoveTarget
}

enum class ScheduleMoveFailure {
    TASK_NOT_MOVABLE,
    REMINDER_IDENTITY_MISMATCH,
    DUE_BEFORE_START,
    RECURRENCE_REQUIRES_SCHEDULE,
    RECURRENCE_COUNT_AND_END_DATE,
    RECURRENCE_END_BEFORE_SCHEDULE,
    REMINDER_REQUIRES_DUE,
    REMINDER_IN_PAST,
}

sealed interface ScheduleMovePlan {
    data object NoChange : ScheduleMovePlan
    data object ReminderRemovalConfirmationRequired : ScheduleMovePlan
    data class Ready(
        val start: ZonedMoment?,
        val due: ZonedMoment?,
        val reminder: Reminder?,
    ) : ScheduleMovePlan

    data class Rejected(val failure: ScheduleMoveFailure) : ScheduleMovePlan
}

fun planTaskScheduleMove(
    task: Task,
    reminder: Reminder?,
    target: ScheduleMoveTarget,
    now: Instant,
    displayZone: ZoneId,
): ScheduleMovePlan {
    if (task.isCompleted || task.deletedAt != null) {
        return ScheduleMovePlan.Rejected(ScheduleMoveFailure.TASK_NOT_MOVABLE)
    }

    val proposed = when (target) {
        is ScheduleMoveTarget.Unscheduled -> {
            if (task.recurrence != null) {
                return ScheduleMovePlan.Rejected(ScheduleMoveFailure.RECURRENCE_REQUIRES_SCHEDULE)
            }
            if (reminder != null && !target.reminderRemovalConfirmed) {
                return ScheduleMovePlan.ReminderRemovalConfirmationRequired
            }
            ScheduleMovePlan.Ready(null, null, null)
        }

        is ScheduleMoveTarget.Day -> {
            val sourceStart = task.start
            val sourceDue = task.due
            val (start, due) = when {
                sourceStart == null && sourceDue == null -> {
                    val dueAt = target.date.atTime(18, 0).atZone(displayZone)
                    null to ZonedMoment(dueAt.toInstant(), displayZone.id)
                }
                sourceStart == null -> null to sourceDue!!.onDate(target.date)
                sourceDue == null -> sourceStart.onDate(target.date) to null
                else -> {
                    val sourceDate = sourceStart.instant.atZone(sourceStart.zone()).toLocalDate()
                    val days = ChronoUnit.DAYS.between(sourceDate, target.date)
                    sourceStart.plusCalendarDays(days) to sourceDue.plusCalendarDays(days)
                }
            }
            val movedReminder = if (reminder != null && due != sourceDue && sourceDue != null) {
                val lead = Duration.between(reminder.triggerAt.instant, sourceDue.instant)
                reminder.copy(triggerAt = ZonedMoment(due!!.instant.minus(lead), due.zoneId))
            } else {
                reminder
            }
            ScheduleMovePlan.Ready(start, due, movedReminder)
        }
    }

    if (proposed.start == task.start && proposed.due == task.due && proposed.reminder == reminder) {
        return ScheduleMovePlan.NoChange
    }
    return validateTaskScheduleState(task.id, proposed.start, proposed.due, task.recurrence, proposed.reminder, now)
        ?.let(ScheduleMovePlan::Rejected)
        ?: proposed
}

fun validateTaskScheduleState(
    taskId: TaskId,
    start: ZonedMoment?,
    due: ZonedMoment?,
    recurrence: RecurrenceRule?,
    reminder: Reminder?,
    now: Instant,
    allowPastReminder: Boolean = false,
): ScheduleMoveFailure? {
    val anchor = due ?: start
    val endBeforeAnchor = recurrence?.endDate?.let { endDate ->
        anchor?.let { moment ->
            endDate.isBefore(moment.instant.atZone(moment.zone()).toLocalDate())
        }
    } == true
    return when {
        reminder != null && (
            reminder.taskId != taskId || reminder.id != Reminder.primaryId(taskId)
        ) -> ScheduleMoveFailure.REMINDER_IDENTITY_MISMATCH
        start != null && due != null && due.instant.isBefore(start.instant) ->
            ScheduleMoveFailure.DUE_BEFORE_START
        recurrence != null && anchor == null ->
            ScheduleMoveFailure.RECURRENCE_REQUIRES_SCHEDULE
        recurrence?.count != null && recurrence.endDate != null ->
            ScheduleMoveFailure.RECURRENCE_COUNT_AND_END_DATE
        endBeforeAnchor -> ScheduleMoveFailure.RECURRENCE_END_BEFORE_SCHEDULE
        reminder != null && due == null -> ScheduleMoveFailure.REMINDER_REQUIRES_DUE
        reminder != null && !allowPastReminder && !reminder.triggerAt.instant.isAfter(now) ->
            ScheduleMoveFailure.REMINDER_IN_PAST
        else -> null
    }
}

fun ScheduleMoveFailure.toCommandRejection(): CommandResult.Rejected = when (this) {
    ScheduleMoveFailure.TASK_NOT_MOVABLE -> CommandResult.Rejected(
        RejectionReason.INVALID_STATE,
        "Only open tasks can be rescheduled.",
    )
    ScheduleMoveFailure.REMINDER_IDENTITY_MISMATCH -> CommandResult.Rejected(
        RejectionReason.INVALID_STATE,
        "That reminder does not belong to this task.",
    )
    ScheduleMoveFailure.DUE_BEFORE_START -> CommandResult.Rejected(
        RejectionReason.INVALID_STATE,
        "Due time cannot be before start time.",
    )
    ScheduleMoveFailure.RECURRENCE_REQUIRES_SCHEDULE -> CommandResult.Rejected(
        RejectionReason.INVALID_STATE,
        "A repeating task needs a start or due time.",
    )
    ScheduleMoveFailure.RECURRENCE_COUNT_AND_END_DATE -> CommandResult.Rejected(
        RejectionReason.INVALID_STATE,
        "Choose either an occurrence count or an end date.",
    )
    ScheduleMoveFailure.RECURRENCE_END_BEFORE_SCHEDULE -> CommandResult.Rejected(
        RejectionReason.INVALID_STATE,
        "The repeat end date cannot be before the schedule.",
    )
    ScheduleMoveFailure.REMINDER_REQUIRES_DUE -> CommandResult.Rejected(
        RejectionReason.INVALID_STATE,
        "Add a due date before setting a reminder.",
    )
    ScheduleMoveFailure.REMINDER_IN_PAST -> CommandResult.Rejected(
        RejectionReason.REMINDER_IN_PAST,
        "Choose a reminder time in the future.",
    )
}

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

private fun ZonedMoment.onDate(targetDate: LocalDate): ZonedMoment {
    val current = instant.atZone(zone())
    val moved = ZonedDateTime.ofLocal(
        targetDate.atTime(current.toLocalTime()),
        current.zone,
        current.offset,
    )
    return ZonedMoment(moved.toInstant(), zoneId)
}

private fun ZonedMoment.plusCalendarDays(days: Long): ZonedMoment {
    val moved = instant.atZone(zone()).plusDays(days)
    return ZonedMoment(moved.toInstant(), zoneId)
}
