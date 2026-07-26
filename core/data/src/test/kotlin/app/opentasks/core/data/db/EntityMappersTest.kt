package app.opentasks.core.data.db

import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Revision
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.ZonedMoment
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class EntityMappersTest {
    @Test
    fun taskRoundTripPreservesPersistedFieldsAndRelationships() {
        val task = Task(
            id = TaskId("round-trip"),
            workspaceId = OpenTasksFixtures.workspaceId,
            projectId = OpenTasksFixtures.studioProject.id,
            statusId = OpenTasksFixtures.planned,
            semanticStatus = app.opentasks.core.model.SemanticStatus.PLANNED,
            title = "Persistent task",
            description = "Private description",
            priority = app.opentasks.core.model.Priority.HIGH,
            start = ZonedMoment(Instant.parse("2026-08-01T02:00:00Z"), "Asia/Bangkok"),
            due = ZonedMoment(Instant.parse("2026-08-01T05:00:00Z"), "Asia/Bangkok"),
            recurrence = RecurrenceRule(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 2,
                weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                count = 6,
                endDate = LocalDate.of(2026, 10, 1),
            ),
            estimate = Duration.ofMinutes(75),
            tagIds = setOf(TagId("deep-work")),
            checklist = listOf(ChecklistItem("step", "First step", true, "a0")),
            blockedBy = setOf(TaskId("dependency")),
            revision = Revision(DeviceId("device"), 1234, 2),
        )

        val restored = task.toEntity().toModel(
            tagIds = task.tagIds,
            checklist = task.checklist,
            blockedBy = task.blockedBy,
        )

        assertEquals(task, restored)
    }
}
