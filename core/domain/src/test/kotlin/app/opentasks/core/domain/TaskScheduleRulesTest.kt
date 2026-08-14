package app.opentasks.core.domain

import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.ZonedMoment
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskScheduleRulesTest {
    private val now = Instant.parse("2026-08-10T00:00:00Z")
    private val displayZone = ZoneId.of("Asia/Bangkok")
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun undatedMoveUsesEighteenHundredInDisplayZone() {
        val plan = move(task(start = null, due = null), ScheduleMoveTarget.Day(LocalDate.of(2026, 8, 22)))

        assertEquals(
            ScheduleMovePlan.Ready(null, moment("2026-08-22T11:00:00Z", "Asia/Bangkok"), null),
            plan,
        )
    }

    @Test
    fun dueOnlyPreservesLocalTimeZoneAndPreferredOverlapOffset() {
        val plan = move(
            task(due = moment("2026-10-31T05:30:00Z", "America/New_York")),
            ScheduleMoveTarget.Day(LocalDate.of(2026, 11, 1)),
        )

        assertEquals(
            ScheduleMovePlan.Ready(null, moment("2026-11-01T05:30:00Z", "America/New_York"), null),
            plan,
        )
    }

    @Test
    fun startOnlyPreservesLocalTimeZoneAndGapMovesForward() {
        val plan = move(
            task(start = moment("2026-03-07T07:30:00Z", "America/New_York"), due = null),
            ScheduleMoveTarget.Day(LocalDate.of(2026, 3, 8)),
        )

        assertEquals(
            ScheduleMovePlan.Ready(moment("2026-03-08T07:30:00Z", "America/New_York"), null, null),
            plan,
        )
    }

    @Test
    fun startAndDueShiftTheSameCalendarDeltaAcrossDifferentZones() {
        val plan = move(
            task(
                start = moment("2026-08-20T13:00:00Z", "America/New_York"),
                due = moment("2026-08-22T03:00:00Z", "Asia/Bangkok"),
            ),
            ScheduleMoveTarget.Day(LocalDate.of(2026, 8, 23)),
        )

        assertEquals(
            ScheduleMovePlan.Ready(
                moment("2026-08-23T13:00:00Z", "America/New_York"),
                moment("2026-08-25T03:00:00Z", "Asia/Bangkok"),
                null,
            ),
            plan,
        )
    }

    @Test
    fun startAndDuePlusDaysPinsDstGapAndOverlapBehaviour() {
        val plan = move(
            task(
                start = moment("2026-03-07T07:30:00Z", "America/New_York"),
                due = moment("2026-10-31T05:30:00Z", "America/New_York"),
            ),
            ScheduleMoveTarget.Day(LocalDate.of(2026, 3, 8)),
        )

        assertEquals(
            ScheduleMovePlan.Ready(
                moment("2026-03-08T07:30:00Z", "America/New_York"),
                moment("2026-11-01T05:30:00Z", "America/New_York"),
                null,
            ),
            plan,
        )
    }

    @Test
    fun sameSourceMoveReturnsNoChange() {
        val task = task(start = moment("2026-08-20T13:00:00Z", "America/New_York"), due = moment("2026-08-21T03:00:00Z", "Asia/Bangkok"))

        assertEquals(ScheduleMovePlan.NoChange, move(task, ScheduleMoveTarget.Day(LocalDate.of(2026, 8, 20))))
    }

    @Test
    fun sameSourceWithAnUnchangedPastReminderReturnsNoChange() {
        val task = task(due = moment("2026-08-20T03:00:00Z", "Asia/Bangkok"))
        val reminder = reminder(task, "2026-08-20T02:00:00Z")

        assertEquals(ScheduleMovePlan.NoChange, move(task, ScheduleMoveTarget.Day(LocalDate.of(2026, 8, 20)), reminder))
    }

    @Test
    fun reminderLeadIdentityAndPrecisionMoveWithDue() {
        val task = task(due = moment("2026-08-20T03:00:00Z", "Asia/Bangkok"))
        val reminder = reminder(task, "2026-08-20T02:15:00Z", precise = true)
        val plan = move(task, ScheduleMoveTarget.Day(LocalDate.of(2026, 8, 22)), reminder) as ScheduleMovePlan.Ready
        val movedReminder = requireNotNull(plan.reminder)

        assertEquals("reminder:task", movedReminder.id)
        assertEquals(task.id, movedReminder.taskId)
        assertEquals(true, movedReminder.precise)
        assertEquals(Duration.ofMinutes(45), Duration.between(reminder.triggerAt.instant, task.due!!.instant))
        assertEquals(Duration.ofMinutes(45), Duration.between(movedReminder.triggerAt.instant, plan.due!!.instant))
        assertEquals("Asia/Bangkok", movedReminder.triggerAt.zoneId)
        assertEquals(Instant.parse("2026-08-22T02:15:00Z"), movedReminder.triggerAt.instant)
    }

    @Test
    fun moveRejectsReminderAtOrBeforeNow() {
        val task = task(due = moment("2026-08-11T01:00:00Z", "UTC"))
        val reminder = reminder(task, "2026-08-11T00:00:00Z")

        assertEquals(
            ScheduleMovePlan.Rejected(ScheduleMoveFailure.REMINDER_IN_PAST),
            move(task, ScheduleMoveTarget.Day(LocalDate.of(2026, 8, 10)), reminder),
        )
    }

    @Test
    fun moveRejectsDueBeforeStart() {
        val task = task(
            start = moment("2026-08-11T10:00:00Z", "UTC"),
            due = moment("2026-08-10T10:00:00Z", "UTC"),
        )

        assertEquals(
            ScheduleMovePlan.Rejected(ScheduleMoveFailure.DUE_BEFORE_START),
            move(task, ScheduleMoveTarget.Day(LocalDate.of(2026, 8, 12))),
        )
    }

    @Test
    fun validationRejectsMismatchedReminderIdentity() {
        val task = task(due = moment("2026-08-11T10:00:00Z", "UTC"))

        assertEquals(
            ScheduleMoveFailure.REMINDER_IDENTITY_MISMATCH,
            validateTaskScheduleState(task.id, null, task.due, null, reminder(task, "2026-08-11T09:00:00Z").copy(id = "wrong"), now),
        )
    }

    @Test
    fun validationRejectsRecurrenceWithoutSchedule() {
        val task = task()

        assertEquals(
            ScheduleMoveFailure.RECURRENCE_REQUIRES_SCHEDULE,
            validateTaskScheduleState(task.id, null, null, recurrence(), null, now),
        )
    }

    @Test
    fun validationRejectsRecurrenceCountAndEndDateTogether() {
        val task = task(due = moment("2026-08-11T10:00:00Z", "UTC"))

        assertEquals(
            ScheduleMoveFailure.RECURRENCE_COUNT_AND_END_DATE,
            validateTaskScheduleState(task.id, null, task.due, recurrence(count = 2, endDate = LocalDate.of(2026, 8, 20)), null, now),
        )
    }

    @Test
    fun validationRejectsRecurrenceEndBeforeSchedule() {
        val task = task(due = moment("2026-08-11T10:00:00Z", "UTC"))

        assertEquals(
            ScheduleMoveFailure.RECURRENCE_END_BEFORE_SCHEDULE,
            validateTaskScheduleState(task.id, null, task.due, recurrence(endDate = LocalDate.of(2026, 8, 10)), null, now),
        )
    }

    @Test
    fun validationRejectsReminderWithoutDue() {
        val task = task()

        assertEquals(
            ScheduleMoveFailure.REMINDER_REQUIRES_DUE,
            validateTaskScheduleState(task.id, null, null, null, reminder(task, "2026-08-11T09:00:00Z"), now),
        )
    }

    @Test
    fun scheduleFailuresMapToStableCommandRejections() {
        assertEquals(
            listOf(
                CommandResult.Rejected(RejectionReason.INVALID_STATE, "Only open tasks can be rescheduled."),
                CommandResult.Rejected(RejectionReason.INVALID_STATE, "That reminder does not belong to this task."),
                CommandResult.Rejected(RejectionReason.INVALID_STATE, "Due time cannot be before start time."),
                CommandResult.Rejected(RejectionReason.INVALID_STATE, "A repeating task needs a start or due time."),
                CommandResult.Rejected(RejectionReason.INVALID_STATE, "Choose either an occurrence count or an end date."),
                CommandResult.Rejected(RejectionReason.INVALID_STATE, "The repeat end date cannot be before the schedule."),
                CommandResult.Rejected(RejectionReason.INVALID_STATE, "Add a due date before setting a reminder."),
                CommandResult.Rejected(RejectionReason.REMINDER_IN_PAST, "Choose a reminder time in the future."),
            ),
            ScheduleMoveFailure.entries.map(ScheduleMoveFailure::toCommandRejection),
        )
    }

    @Test
    fun trayRequiresReminderConfirmationThenClearsTogether() {
        val task = task(start = moment("2026-08-11T09:00:00Z", "UTC"), due = moment("2026-08-11T10:00:00Z", "UTC"))
        val reminder = reminder(task, "2026-08-11T09:30:00Z")

        assertEquals(
            ScheduleMovePlan.ReminderRemovalConfirmationRequired,
            move(task, ScheduleMoveTarget.Unscheduled(reminderRemovalConfirmed = false), reminder),
        )
        assertEquals(
            ScheduleMovePlan.Ready(null, null, null),
            move(task, ScheduleMoveTarget.Unscheduled(reminderRemovalConfirmed = true), reminder),
        )
    }

    @Test
    fun recurringTaskCannotMoveToTray() {
        val task = task(due = moment("2026-08-11T10:00:00Z", "UTC"), recurrence = recurrence())

        assertEquals(
            ScheduleMovePlan.Rejected(ScheduleMoveFailure.RECURRENCE_REQUIRES_SCHEDULE),
            move(task, ScheduleMoveTarget.Unscheduled(reminderRemovalConfirmed = true)),
        )
    }

    @Test
    fun completedAndBinnedTasksCannotMove() {
        val completed = task().copy(semanticStatus = SemanticStatus.COMPLETED)
        val binned = task().copy(deletedAt = now)
        val target = ScheduleMoveTarget.Day(LocalDate.of(2026, 8, 22))

        assertEquals(ScheduleMovePlan.Rejected(ScheduleMoveFailure.TASK_NOT_MOVABLE), move(completed, target))
        assertEquals(ScheduleMovePlan.Rejected(ScheduleMoveFailure.TASK_NOT_MOVABLE), move(binned, target))
    }

    private fun move(task: Task, target: ScheduleMoveTarget, reminder: Reminder? = null) =
        planTaskScheduleMove(task, reminder, target, now, displayZone)

    private fun task(
        start: ZonedMoment? = null,
        due: ZonedMoment? = null,
        recurrence: RecurrenceRule? = null,
    ) = OpenTasksFixtures.tasks.first().copy(
        id = TaskId("task"),
        start = start,
        due = due,
        recurrence = recurrence,
    )

    private fun reminder(task: Task, trigger: String, precise: Boolean = false) = Reminder(
        id = Reminder.primaryId(task.id),
        taskId = task.id,
        triggerAt = moment(trigger, "UTC"),
        precise = precise,
    )

    private fun recurrence(count: Int? = null, endDate: LocalDate? = null) =
        RecurrenceRule(RecurrenceFrequency.DAILY, count = count, endDate = endDate)

    private fun moment(instant: String, zone: String) = ZonedMoment(Instant.parse(instant), zone)
}
