package app.opentasks.core.domain

import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Milestone
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TemplateId
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkspaceId
import app.opentasks.core.model.ZonedMoment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ProjectTemplatePlannerTest {
    private val workspaceId = WorkspaceId("workspace")
    private val projectId = ProjectId("source")
    private val revision = Revision(DeviceId("device"), 1, 0)
    private val statuses = WorkflowStatus.defaults(projectId)
    private val milestone = Milestone(
        id = MilestoneId("milestone"),
        projectId = projectId,
        name = "Launch",
        dueDate = LocalDate.of(2026, 8, 10),
    )
    private val tag = Tag(TagId("tag"), workspaceId, "Client")

    @Test
    fun captureAndInstantiateShiftDatesAndResetProgress() {
        val prerequisite = task(
            id = "brief",
            title = "Approve brief",
            dueDate = LocalDate.of(2026, 8, 3),
            checklist = listOf(ChecklistItem("check", "Send draft", true, "a0")),
        )
        val delivery = task(
            id = "delivery",
            title = "Deliver work",
            dueDate = LocalDate.of(2026, 8, 10),
            dependencyIds = setOf(prerequisite.id),
            recurrence = RecurrenceRule(
                frequency = RecurrenceFrequency.WEEKLY,
                endDate = LocalDate.of(2026, 8, 31),
            ),
            milestoneId = milestone.id,
        )
        val template = ProjectTemplatePlanner.capture(
            templateId = TemplateId("template"),
            templateName = "Client launch",
            project = project(dueDate = LocalDate.of(2026, 8, 10)),
            workflowStatuses = statuses,
            milestones = listOf(milestone),
            tasks = listOf(prerequisite, delivery),
            tags = listOf(tag),
            revision = revision,
            fallbackAnchor = LocalDate.of(2026, 7, 27),
        )

        val created = ProjectTemplatePlanner.instantiate(
            template = template,
            projectId = ProjectId("created"),
            projectName = "Autumn launch",
            anchorDate = LocalDate.of(2026, 9, 1),
            revision = revision,
        )

        assertEquals(LocalDate.of(2026, 9, 8), created.project.dueDate)
        assertEquals(LocalDate.of(2026, 9, 8), created.milestones.single().dueDate)
        val createdBrief = created.tasks.first { it.title == "Approve brief" }
        val createdDelivery = created.tasks.first { it.title == "Deliver work" }
        assertEquals(
            LocalDate.of(2026, 9, 1),
            createdBrief.due?.instant?.atZone(ZoneId.of("Europe/London"))?.toLocalDate(),
        )
        assertEquals(setOf(createdBrief.id), createdDelivery.dependencyIds)
        assertFalse(createdBrief.checklist.single().completed)
        assertEquals(LocalDate.of(2026, 9, 29), createdDelivery.recurrence?.endDate)
        assertEquals(createdDelivery.id, createdDelivery.recurrenceSeriesId)
        assertEquals(setOf("Client"), created.tagNamesByTaskId.getValue(createdBrief.id))
        assertNotEquals(prerequisite.id, createdBrief.id)
    }

    @Test
    fun captureOmitsCompletedDeletedAndHistoricalMilestones() {
        val completed = task(
            id = "done",
            title = "Done",
            dueDate = LocalDate.of(2026, 7, 1),
        ).copy(
            statusId = statuses.last().id,
            semanticStatus = SemanticStatus.COMPLETED,
            completedAt = Instant.parse("2026-07-01T12:00:00Z"),
        )
        val deleted = task(
            id = "deleted",
            title = "Deleted",
            dueDate = LocalDate.of(2026, 7, 2),
        ).copy(deletedAt = Instant.parse("2026-07-02T12:00:00Z"))
        val historicalMilestone = milestone.copy(
            completedAt = Instant.parse("2026-07-03T12:00:00Z"),
        )

        val template = ProjectTemplatePlanner.capture(
            templateId = TemplateId("template"),
            templateName = "Reusable",
            project = project(dueDate = null),
            workflowStatuses = statuses,
            milestones = listOf(historicalMilestone),
            tasks = listOf(completed, deleted),
            tags = emptyList(),
            revision = revision,
            fallbackAnchor = LocalDate.of(2026, 7, 27),
        )

        assertEquals(emptyList<Any>(), template.tasks)
        assertEquals(emptyList<Any>(), template.milestones)
        assertNull(template.projectDueOffsetDays)
    }

    @Test
    fun startOnlyRecurrenceKeepsItsShiftedAnchor() {
        val sourceStart = LocalDate.of(2026, 8, 5)
            .atTime(9, 30)
            .atZone(ZoneId.of("Europe/London"))
        val source = task(
            id = "stand-up",
            title = "Stand-up",
            dueDate = LocalDate.of(2026, 8, 5),
            recurrence = RecurrenceRule(RecurrenceFrequency.WEEKLY),
        ).copy(
            start = ZonedMoment(sourceStart.toInstant(), sourceStart.zone.id),
            due = null,
        )
        val template = ProjectTemplatePlanner.capture(
            templateId = TemplateId("start-template"),
            templateName = "Stand-up",
            project = project(dueDate = null),
            workflowStatuses = statuses,
            milestones = emptyList(),
            tasks = listOf(source),
            tags = emptyList(),
            revision = revision,
            fallbackAnchor = LocalDate.of(2026, 7, 27),
        )

        val created = ProjectTemplatePlanner.instantiate(
            template = template,
            projectId = ProjectId("created-start"),
            projectName = "Autumn stand-up",
            anchorDate = LocalDate.of(2026, 9, 9),
            revision = revision,
        ).tasks.single()

        assertEquals(created.start, created.recurrenceAnchor)
        assertEquals(created.id, created.recurrenceSeriesId)
    }

    private fun project(dueDate: LocalDate?): Project = Project(
        id = projectId,
        workspaceId = workspaceId,
        name = "Client launch",
        summary = "Repeatable delivery",
        status = ProjectHealth.AT_RISK,
        dueDate = dueDate,
        completedTasks = 0,
        totalTasks = 0,
    )

    private fun task(
        id: String,
        title: String,
        dueDate: LocalDate,
        checklist: List<ChecklistItem> = emptyList(),
        dependencyIds: Set<TaskId> = emptySet(),
        recurrence: RecurrenceRule? = null,
        milestoneId: MilestoneId? = null,
    ): Task {
        val due = dueDate
            .atTime(17, 0)
            .atZone(ZoneId.of("Europe/London"))
        return Task(
            id = TaskId(id),
            workspaceId = workspaceId,
            projectId = projectId,
            statusId = statuses.first().id,
            semanticStatus = SemanticStatus.BACKLOG,
            title = title,
            priority = Priority.HIGH,
            due = ZonedMoment(due.toInstant(), due.zone.id),
            recurrence = recurrence,
            estimate = Duration.ofMinutes(45),
            milestoneId = milestoneId,
            tagIds = setOf(tag.id),
            checklist = checklist,
            dependencyIds = dependencyIds,
            blockedBy = dependencyIds,
            revision = revision,
        )
    }
}
