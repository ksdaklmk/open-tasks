package app.opentasks.core.domain

import app.opentasks.core.model.ActivityEntry
import app.opentasks.core.model.ActivityKind
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkspaceId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class ReviewQueueTest {
    private val now = Instant.parse("2026-08-09T12:00:00Z")

    @Test
    fun overdueTaskAppearsOnlyInOverdueEvenWhenAlsoStale() {
        val overdue = taskFixture(
            id = "overdue",
            due = now.minusSeconds(1),
            revisedAt = now.minus(Duration.ofDays(30)),
        )

        val queue = buildReviewQueue(snapshot(tasks = listOf(overdue)), now)

        assertEquals(listOf(overdue.id), queue.overdue.map(Task::id))
        assertTrue(queue.stale.isEmpty())
        assertTrue(queue.unscheduled.isEmpty())
    }

    @Test
    fun freshActivityRescuesTaskWhoseRevisionIsStale() {
        val task = taskFixture(id = "active", revisedAt = now.minus(Duration.ofDays(30)))

        val queue = buildReviewQueue(
            snapshot(
                tasks = listOf(task),
                activityEntries = listOf(activityFixture(task.id, now.minus(Duration.ofDays(1)))),
            ),
            now,
        )

        assertTrue(queue.stale.isEmpty())
        assertEquals(listOf(task.id), queue.unscheduled.map(Task::id))
    }

    @Test
    fun unscheduledExcludesStaleAndCompletedTasks() {
        val stale = taskFixture(id = "stale", revisedAt = now.minus(Duration.ofDays(15)))
        val completed = taskFixture(
            id = "completed",
            semanticStatus = SemanticStatus.COMPLETED,
        )
        val unscheduled = taskFixture(id = "unscheduled", title = "A task")

        val queue = buildReviewQueue(snapshot(tasks = listOf(stale, completed, unscheduled)), now)

        assertEquals(listOf(stale.id), queue.stale.map(Task::id))
        assertEquals(listOf(unscheduled.id), queue.unscheduled.map(Task::id))
    }

    @Test
    fun binnedTasksAndArchivedProjectsAppearNowhere() {
        val binned = taskFixture(id = "binned", deletedAt = now.minusSeconds(1))
        val archived = projectFixture(id = "archived", archivedAt = now.minusSeconds(1))

        val queue = buildReviewQueue(snapshot(tasks = listOf(binned), projects = listOf(archived)), now)

        assertTrue(queue.overdue.isEmpty())
        assertTrue(queue.stale.isEmpty())
        assertTrue(queue.unscheduled.isEmpty())
        assertTrue(queue.projects.isEmpty())
    }

    @Test
    fun taskExactlyFourteenDaysOldIsNotStale() {
        val boundary = taskFixture(id = "boundary", revisedAt = now.minus(Duration.ofDays(14)))

        val queue = buildReviewQueue(snapshot(tasks = listOf(boundary)), now)

        assertTrue(queue.stale.isEmpty())
        assertEquals(listOf(boundary.id), queue.unscheduled.map(Task::id))
    }

    private fun snapshot(
        tasks: List<Task> = emptyList(),
        projects: List<Project> = emptyList(),
        activityEntries: List<ActivityEntry> = emptyList(),
    ) = WorkspaceSnapshot(
        home = HomeSnapshot(LocalDate.of(2026, 8, 9), emptyList(), emptyList(), emptyList(), null, 0),
        tasks = tasks,
        projects = projects,
        workflowStatuses = emptyList(),
        milestones = emptyList(),
        tags = emptyList(),
        activityEntries = activityEntries,
    )

    private fun taskFixture(
        id: String,
        title: String = id,
        due: Instant? = null,
        revisedAt: Instant = now.minus(Duration.ofDays(1)),
        semanticStatus: SemanticStatus = SemanticStatus.PLANNED,
        deletedAt: Instant? = null,
    ) = Task(
        id = TaskId(id),
        workspaceId = WorkspaceId("workspace"),
        projectId = null,
        statusId = WorkflowStatus.defaultId(null, semanticStatus),
        semanticStatus = semanticStatus,
        title = title,
        due = due?.let { ZonedMoment(it, "UTC") },
        deletedAt = deletedAt,
        revision = Revision(DeviceId("device"), revisedAt.toEpochMilli(), 0),
    )

    private fun projectFixture(id: String, archivedAt: Instant? = null) = Project(
        id = ProjectId(id),
        workspaceId = WorkspaceId("workspace"),
        name = id,
        summary = "",
        status = ProjectHealth.ON_TRACK,
        dueDate = null,
        completedTasks = 0,
        totalTasks = 0,
        archivedAt = archivedAt,
    )

    private fun activityFixture(taskId: TaskId, createdAt: Instant) = ActivityEntry(
        id = "activity-${taskId.value}",
        taskId = taskId,
        projectId = null,
        kind = ActivityKind.RECORD_CREATED,
        body = "Created",
        createdAt = createdAt,
    )
}
