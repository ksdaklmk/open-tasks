package app.opentasks.core.domain

import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TagId
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.ZonedMoment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class TaskDuplicationTest {
    @Test
    fun copiesOnlyTheApprovedFieldSet() {
        val anchor = ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"), "UTC")
        val source = OpenTasksFixtures.tasks.first { it.checklist.isNotEmpty() }.copy(
            id = TaskId("source"),
            parentTaskId = TaskId("parent"),
            title = "x".repeat(240),
            description = "description",
            priority = Priority.URGENT,
            start = anchor,
            due = anchor,
            recurrence = RecurrenceRule(RecurrenceFrequency.WEEKLY),
            recurrenceSeriesId = TaskId("series"),
            recurrenceAnchor = anchor,
            recurrenceOccurrenceIndex = 3,
            estimate = Duration.ofMinutes(90),
            milestoneId = MilestoneId("milestone-duplicate-source"),
            tagIds = setOf(TagId("tag-a"), TagId("tag-b")),
            checklist = listOf(
                ChecklistItem("old-1", "First", completed = true, rank = "a"),
                ChecklistItem("old-2", "Second", completed = false, rank = "b"),
            ),
            dependencyIds = setOf(TaskId("dependency")),
            blockedBy = setOf(TaskId("dependency")),
            completedAt = Instant.parse("2026-08-10T09:00:00Z"),
            deletedAt = Instant.parse("2026-08-10T09:30:00Z"),
        )
        val target = OpenTasksFixtures.workflowStatuses.first {
            it.projectId == source.projectId && it.semanticStatus == SemanticStatus.BACKLOG
        }
        val revision = Revision(DeviceId("duplicate-device"), 42L, 0)

        val duplicate = planTaskDuplicate(
            source = source,
            targetStatus = target,
            duplicateId = TaskId("duplicate"),
            checklistItemIds = listOf("new-1", "new-2"),
            revision = revision,
        )

        assertEquals(TaskId("duplicate"), duplicate.id)
        assertEquals("x".repeat(233) + " (copy)", duplicate.title)
        assertEquals(source.workspaceId, duplicate.workspaceId)
        assertEquals(source.projectId, duplicate.projectId)
        assertEquals(source.parentTaskId, duplicate.parentTaskId)
        assertEquals(source.description, duplicate.description)
        assertEquals(source.priority, duplicate.priority)
        assertEquals(source.start, duplicate.start)
        assertEquals(source.due, duplicate.due)
        assertEquals(source.estimate, duplicate.estimate)
        assertNotNull(source.milestoneId)
        assertEquals(MilestoneId("milestone-duplicate-source"), duplicate.milestoneId)
        assertEquals(source.tagIds, duplicate.tagIds)
        assertEquals(source.dependencyIds, duplicate.dependencyIds)
        assertEquals(target.id, duplicate.statusId)
        assertEquals(target.semanticStatus, duplicate.semanticStatus)
        assertEquals(listOf("new-1", "new-2"), duplicate.checklist.map { it.id })
        assertEquals(listOf("First", "Second"), duplicate.checklist.map { it.text })
        assertEquals(listOf("a", "b"), duplicate.checklist.map { it.rank })
        assertTrue(duplicate.checklist.none { it.completed })
        assertNull(duplicate.completedAt)
        assertNotNull(source.deletedAt)
        assertNull(duplicate.deletedAt)
        assertTrue(duplicate.blockedBy.isEmpty())
        assertNull(duplicate.recurrence)
        assertNull(duplicate.recurrenceSeriesId)
        assertNull(duplicate.recurrenceAnchor)
        assertNull(duplicate.recurrenceOccurrenceIndex)
        assertEquals(revision, duplicate.revision)
    }

    @Test
    fun rejectsMismatchedTargetOrChecklistIdentityCounts() {
        val source = OpenTasksFixtures.tasks.first { it.checklist.isNotEmpty() }
        val otherProjectStatus = OpenTasksFixtures.workflowStatuses.first {
            it.projectId != source.projectId
        }
        assertThrows(IllegalArgumentException::class.java) {
            planTaskDuplicate(
                source,
                otherProjectStatus,
                TaskId("copy"),
                source.checklist.map { UUID.randomUUID().toString() },
                source.revision,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            planTaskDuplicate(
                source,
                OpenTasksFixtures.workflowStatuses.first { it.id == source.statusId },
                TaskId("copy"),
                emptyList(),
                source.revision,
            )
        }
    }
}
