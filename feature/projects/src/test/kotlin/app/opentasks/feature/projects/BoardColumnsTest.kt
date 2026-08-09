package app.opentasks.feature.projects

import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.WorkspaceId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardColumnsTest {
    private val projectId = ProjectId("project")
    private val otherProjectId = ProjectId("other-project")
    private val workspaceId = WorkspaceId("workspace")
    private val revision = Revision(DeviceId("device"), 1, 0)
    private val project = Project(
        id = projectId,
        workspaceId = workspaceId,
        name = "Project",
        summary = "",
        status = ProjectHealth.ON_TRACK,
        dueDate = null,
        completedTasks = 0,
        totalTasks = 0,
    )
    private val backlog = status("backlog", "Backlog", "b")
    private val started = status("started", "In progress", "a")

    @Test
    fun columnsContainOnlyOpenProjectTasksInWorkflowAndTaskOrder() {
        val archived = status("archived", "Archived", "c", archived = true)
        val otherStatus = status(
            id = "other",
            name = "Other",
            rank = "a",
            owner = otherProjectId,
        )
        val tasks = listOf(
            task("z-low", backlog.id, "Zulu", Priority.LOW),
            task("b-high", backlog.id, "bravo", Priority.HIGH),
            task("a-high", backlog.id, "Alpha", Priority.HIGH),
            task("started", started.id, "Started", Priority.MEDIUM),
            task("completed", backlog.id, "Completed", semantic = SemanticStatus.COMPLETED),
            task("binned", backlog.id, "Binned", deletedAt = Instant.EPOCH),
            task("archived-status", archived.id, "Archived status"),
            task("other-project", otherStatus.id, "Other project", owner = otherProjectId),
        )

        val result = boardColumns(
            project = project,
            statuses = listOf(backlog, archived, otherStatus, started),
            tasks = tasks,
        )

        assertEquals(listOf(started.id, backlog.id), result.map { it.status.id })
        assertEquals(listOf("started"), result[0].tasks.map { it.id.value })
        assertEquals(listOf("a-high", "b-high", "z-low"), result[1].tasks.map { it.id.value })
    }

    @Test
    fun moveTargetsExcludeCurrentStatusAndKeepBoardOrder() {
        val columns = listOf(started, backlog, status("done", "Done", "c")).map {
            BoardColumn(status = it, tasks = emptyList())
        }

        assertEquals(
            listOf(backlog.id, WorkflowStatusId("done")),
            moveTargets(columns, started.id).map(WorkflowStatus::id),
        )
    }

    private fun status(
        id: String,
        name: String,
        rank: String,
        archived: Boolean = false,
        owner: ProjectId = projectId,
    ) = WorkflowStatus(
        id = WorkflowStatusId(id),
        projectId = owner,
        name = name,
        semanticStatus = SemanticStatus.BACKLOG,
        rank = rank,
        archivedAt = Instant.EPOCH.takeIf { archived },
    )

    private fun task(
        id: String,
        statusId: WorkflowStatusId,
        title: String,
        priority: Priority = Priority.NONE,
        semantic: SemanticStatus = SemanticStatus.BACKLOG,
        deletedAt: Instant? = null,
        owner: ProjectId = projectId,
    ) = Task(
        id = TaskId(id),
        workspaceId = workspaceId,
        projectId = owner,
        statusId = statusId,
        semanticStatus = semantic,
        title = title,
        priority = priority,
        deletedAt = deletedAt,
        revision = revision,
    )
}
