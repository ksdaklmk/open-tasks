package app.opentasks.core.data

import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.WorkspaceSnapshot
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Twin of `RoomProjectProgressInstrumentedTest`: the workspace snapshot
 * restates project task counts, while the store keeps the columns a command
 * actually wrote.
 */
class InMemoryProjectProgressTest {
    @Test
    fun projectReportsCompletedOverTotalTasksThroughTheWorkspaceSnapshot() = runBlocking {
        withTimeout(5_000) {
            val repository = repository()
            repository.execute(DomainCommand.CreateProject(projectId, "Plan the week"))
            repeat(3) { index ->
                repository.execute(DomainCommand.CreateTask("Task $index", projectId))
            }
            val tasks = repository.currentWorkspace().tasks
            assertEquals(3, tasks.size)

            repository.execute(
                DomainCommand.CompleteTask(tasks.first().id, completedAt = fixedNow()),
            )

            val project = repository.project()
            assertEquals(1, project.completedTasks)
            assertEquals(3, project.totalTasks)
            assertEquals(1f / 3f, project.progress, 0.0001f)
            assertEquals(project, repository.observeHome().first().projects.single())
        }
    }

    @Test
    fun deletedTasksLeaveTheProjectCount() = runBlocking {
        withTimeout(5_000) {
            val repository = repository()
            repository.execute(DomainCommand.CreateProject(projectId, "Plan the week"))
            repository.execute(DomainCommand.CreateTask("Kept", projectId))
            repository.execute(DomainCommand.CreateTask("Dropped", projectId))
            val dropped = repository.currentWorkspace().tasks.single { it.title == "Dropped" }

            repository.execute(DomainCommand.DeleteTask(dropped.id, deletedAt = fixedNow()))

            assertEquals(1, repository.project().totalTasks)
        }
    }

    @Test
    fun emptyProjectReportsZeroOfZero() = runBlocking {
        withTimeout(5_000) {
            val repository = repository()
            repository.execute(DomainCommand.CreateProject(projectId, "Plan the week"))

            val project = repository.project()
            assertEquals(0, project.completedTasks)
            assertEquals(0, project.totalTasks)
            assertEquals(0f, project.progress, 0.0001f)
        }
    }

    /**
     * The counts are a read projection only. Room's guards, undo payloads and
     * backup records read the stored columns through its DAO, so the in-memory
     * store must not absorb the derived values either.
     */
    @Test
    fun storedProjectRecordKeepsTheColumnsTheCreateCommandWrote() = runBlocking {
        withTimeout(5_000) {
            val repository = repository()
            repository.execute(DomainCommand.CreateProject(projectId, "Plan the week"))
            repository.execute(DomainCommand.CreateTask("Only", projectId))
            assertEquals(1, repository.project().totalTasks)

            val result = repository.execute(
                DomainCommand.UpdateProject(
                    projectId = projectId,
                    name = "Plan the fortnight",
                    summary = "",
                    health = ProjectHealth.ON_TRACK,
                    dueDate = null,
                ),
            )

            assertTrue(result is CommandResult.Success)
            val restore = (result as CommandResult.Success).undo as DomainCommand.RestoreProject
            assertEquals(0, restore.project.completedTasks)
            assertEquals(0, restore.project.totalTasks)
        }
    }

    private fun repository() = InMemoryVaultRepository(initial = empty, now = fixedNow)

    private suspend fun InMemoryVaultRepository.project(): Project =
        currentWorkspace().projects.single { it.id == projectId }

    private companion object {
        val projectId = ProjectId("project-progress")
        val fixedNow: () -> Instant = { Instant.parse("2026-09-04T09:00:00Z") }
        val empty = WorkspaceSnapshot(
            home = HomeSnapshot(
                today = LocalDate.of(2026, 9, 4),
                focusTasks = emptyList(),
                upcomingTasks = emptyList(),
                projects = emptyList(),
                activeTimer = null,
                overdueCount = 0,
            ),
            tasks = emptyList(),
            projects = emptyList(),
            workflowStatuses = emptyList(),
            milestones = emptyList(),
            tags = emptyList(),
        )
    }
}
