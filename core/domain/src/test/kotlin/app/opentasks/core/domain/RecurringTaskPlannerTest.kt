package app.opentasks.core.domain

import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.WorkspaceId
import app.opentasks.core.model.ZonedMoment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class RecurringTaskPlannerTest {
    @Test
    fun monthlySeriesKeepsOriginalMonthEndAnchor() {
        val first = task(
            due = moment("2026-01-31T02:00:00Z", "Asia/Bangkok"),
            rule = RecurrenceRule(RecurrenceFrequency.MONTHLY, count = 3),
        ).withSeriesMetadata()

        val february = checkNotNull(RecurringTaskPlanner.next(first, planned, SemanticStatus.PLANNED, revision))
        val march = checkNotNull(
            RecurringTaskPlanner.next(february, planned, SemanticStatus.PLANNED, revision),
        )

        assertEquals("2026-02-28", february.due?.localDate())
        assertEquals("2026-03-31", march.due?.localDate())
        assertEquals(first.due, march.recurrenceAnchor)
        assertEquals(2, march.recurrenceOccurrenceIndex)
    }

    @Test
    fun dailySeriesPreservesWallClockTimeAcrossDst() {
        val first = task(
            due = moment("2026-03-07T14:00:00Z", "America/New_York"),
            rule = RecurrenceRule(RecurrenceFrequency.DAILY, count = 3),
        ).withSeriesMetadata()

        val second = checkNotNull(
            RecurringTaskPlanner.next(first, planned, SemanticStatus.PLANNED, revision),
        )
        val third = checkNotNull(
            RecurringTaskPlanner.next(second, planned, SemanticStatus.PLANNED, revision),
        )

        assertEquals(9, second.due?.localHour())
        assertEquals(9, third.due?.localHour())
        assertNotEquals(first.due?.instantOffset(), third.due?.instantOffset())
    }

    @Test
    fun nextOccurrenceIsDeterministicAndResetsOccurrenceState() {
        val first = task(
            due = moment("2026-07-26T03:00:00Z", "Asia/Bangkok"),
            rule = RecurrenceRule(RecurrenceFrequency.DAILY, count = 2),
        ).withSeriesMetadata()
        val firstResult = checkNotNull(
            RecurringTaskPlanner.next(first, planned, SemanticStatus.PLANNED, revision),
        )
        val redelivered = checkNotNull(
            RecurringTaskPlanner.next(first, planned, SemanticStatus.PLANNED, revision),
        )

        assertEquals(firstResult.id, redelivered.id)
        assertEquals(first.tagIds, firstResult.tagIds)
        assertEquals(listOf(false), firstResult.checklist.map { it.completed })
        assertNotEquals(first.checklist.single().id, firstResult.checklist.single().id)
        assertEquals(firstResult.checklist.single().id, redelivered.checklist.single().id)
        assertEquals(emptySet<TaskId>(), firstResult.blockedBy)
        assertNull(firstResult.completedAt)
        assertFalse(firstResult.isCompleted)
    }

    @Test
    fun countAndEndDateStopSeries() {
        val countLimited = task(
            due = moment("2026-07-26T03:00:00Z", "Asia/Bangkok"),
            rule = RecurrenceRule(RecurrenceFrequency.DAILY, count = 1),
        ).withSeriesMetadata()
        val dateLimited = task(
            due = moment("2026-07-26T03:00:00Z", "Asia/Bangkok"),
            rule = RecurrenceRule(
                frequency = RecurrenceFrequency.DAILY,
                endDate = LocalDate.of(2026, 7, 26),
            ),
        ).withSeriesMetadata()

        assertNull(
            RecurringTaskPlanner.next(countLimited, planned, SemanticStatus.PLANNED, revision),
        )
        assertNull(
            RecurringTaskPlanner.next(dateLimited, planned, SemanticStatus.PLANNED, revision),
        )
    }

    private fun Task.withSeriesMetadata(): Task {
        val metadata = checkNotNull(RecurringTaskPlanner.metadataForUpdate(this, due, recurrence))
        return copy(
            recurrenceSeriesId = metadata.seriesId,
            recurrenceAnchor = metadata.anchor,
            recurrenceOccurrenceIndex = metadata.occurrenceIndex,
        )
    }

    private fun task(
        due: ZonedMoment,
        rule: RecurrenceRule,
    ) = Task(
        id = TaskId("recurring-task"),
        workspaceId = WorkspaceId("workspace"),
        projectId = null,
        statusId = WorkflowStatusId("planned"),
        semanticStatus = SemanticStatus.PLANNED,
        title = "Recurring task",
        due = due,
        recurrence = rule,
        tagIds = setOf(TagId("tag-client")),
        checklist = listOf(
            ChecklistItem(
                id = "checklist-original",
                text = "Prepare",
                completed = true,
                rank = "a0",
            ),
        ),
        blockedBy = setOf(TaskId("blocking-task")),
        completedAt = Instant.parse("2026-07-26T03:30:00Z"),
        revision = revision,
    )

    private fun moment(instant: String, zoneId: String) =
        ZonedMoment(Instant.parse(instant), zoneId)

    private fun ZonedMoment.localDate(): String =
        instant.atZone(zone()).toLocalDate().toString()

    private fun ZonedMoment.localHour(): Int = instant.atZone(zone()).hour

    private fun ZonedMoment.instantOffset() = instant.atZone(zone()).offset

    private companion object {
        val planned = WorkflowStatusId("planned")
        val revision = Revision(DeviceId("test-device"), 1L, 1)
    }
}
