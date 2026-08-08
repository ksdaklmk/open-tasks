package app.opentasks.core.data

import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.domain.buildReviewQueue
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.TaskId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class InMemoryMarkReviewedTest {
    private val now = Instant.parse("2026-08-09T12:00:00Z")

    @Test
    fun markReviewedRecordsActivityResetsStalenessAndKeepsNewestFiveHundred() = runBlocking {
        val staleTask = OpenTasksFixtures.tasks.first().copy(
            due = null,
            revision = Revision(
                OpenTasksFixtures.tasks.first().revision.deviceId,
                now.minus(Duration.ofDays(15)).toEpochMilli(),
                0,
            ),
        )
        val repository = InMemoryVaultRepository(OpenTasksFixtures.snapshot.copy(tasks = listOf(staleTask)))

        assertEquals(listOf(staleTask.id), buildReviewQueue(repository.currentWorkspace(), now).stale.map { it.id })
        assertEquals(
            CommandResult.Success("Marked as reviewed"),
            repository.execute(DomainCommand.MarkReviewed(taskId = staleTask.id, reviewedAt = now)),
        )
        val entry = repository.currentWorkspace().activityEntries.single()
        assertEquals(staleTask.id, entry.taskId)
        assertEquals("REVIEWED", entry.kind.name)
        assertEquals("Reviewed", entry.body)
        assertTrue(buildReviewQueue(repository.currentWorkspace(), now).stale.isEmpty())

        repeat(500) { index ->
            repository.execute(
                DomainCommand.MarkReviewed(
                    taskId = staleTask.id,
                    reviewedAt = now.plusSeconds(index.toLong() + 1),
                ),
            )
        }

        val entries = repository.currentWorkspace().activityEntries.filter { it.taskId == staleTask.id }
        assertEquals(500, entries.size)
        assertEquals(now.plusSeconds(1), entries.minOf { it.createdAt })
    }

    @Test
    fun invalidReviewOwnersRejectWithoutMutation() = runBlocking {
        val task = OpenTasksFixtures.tasks.first()
        val project = OpenTasksFixtures.studioProject
        val binnedTask = task.copy(id = TaskId("binned"), deletedAt = now)
        val archivedProject = project.copy(id = ProjectId("archived"), archivedAt = now)
        val repository = InMemoryVaultRepository(
            OpenTasksFixtures.snapshot.copy(
                tasks = listOf(task, binnedTask),
                projects = listOf(project, archivedProject),
            ),
        )
        val before = repository.currentWorkspace()

        listOf(
            DomainCommand.MarkReviewed(),
            DomainCommand.MarkReviewed(task.id, project.id),
            DomainCommand.MarkReviewed(TaskId("missing")),
            DomainCommand.MarkReviewed(binnedTask.id),
            DomainCommand.MarkReviewed(projectId = ProjectId("missing")),
            DomainCommand.MarkReviewed(projectId = archivedProject.id),
        ).forEach { command ->
            val result = repository.execute(command)
            assertTrue(result is CommandResult.Rejected)
            assertEquals(RejectionReason.NOT_FOUND, (result as CommandResult.Rejected).reason)
            assertEquals(before, repository.currentWorkspace())
        }
    }

    @Test
    fun markReviewedAcceptsAnActiveProject() = runBlocking {
        val project = OpenTasksFixtures.studioProject
        val repository = InMemoryVaultRepository()

        assertEquals(
            CommandResult.Success("Marked as reviewed"),
            repository.execute(DomainCommand.MarkReviewed(projectId = project.id, reviewedAt = now)),
        )
        val entry = repository.currentWorkspace().activityEntries.single()
        assertEquals(project.id, entry.projectId)
        assertEquals("REVIEWED", entry.kind.name)
        assertEquals("Reviewed", entry.body)
    }
}
