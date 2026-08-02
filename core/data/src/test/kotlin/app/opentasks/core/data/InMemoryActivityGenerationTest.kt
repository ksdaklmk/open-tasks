package app.opentasks.core.data

import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.model.SemanticStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InMemoryActivityGenerationTest {

    @Test
    fun completingTaskAppendsExactlyOneCompletedEntry() = runBlocking {
        withTimeout(5_000) {
            val repository = InMemoryVaultRepository()
            val task = repository.currentWorkspace().tasks.first()

            repository.execute(
                DomainCommand.CompleteTask(
                    taskId = task.id,
                    completedAt = Instant.parse("2026-08-02T10:00:00Z"),
                ),
            )

            val entries = repository.currentWorkspace().activityEntries.filter { it.taskId == task.id }
            assertEquals(1, entries.size)
            assertEquals("COMPLETED", entries.single().kind.toString())
            assertEquals("Completed", entries.single().body)
        }
    }

    @Test
    fun statusChangeRecordsOldAndNewNames() = runBlocking {
        withTimeout(5_000) {
            val repository = InMemoryVaultRepository()
            val task = repository.currentWorkspace().tasks.first()
            val planned = repository.currentWorkspace().workflowStatuses.first {
                it.projectId == task.projectId && it.semanticStatus == SemanticStatus.PLANNED
            }

            repository.execute(
                DomainCommand.ChangeTaskStatus(
                    taskId = task.id,
                    statusId = planned.id,
                    changedAt = Instant.parse("2026-08-02T10:00:00Z"),
                ),
            )

            val entry = repository.currentWorkspace().activityEntries.single()
            assertEquals("STATUS_CHANGED", entry.kind.toString())
            assertEquals("In progress → Planned", entry.body)
        }
    }

    @Test
    fun generated501EntriesKeepNewest500WithOldestEviction() = runBlocking {
        withTimeout(5_000) {
            val repository = InMemoryVaultRepository()
            val task = repository.currentWorkspace().tasks.first()
            val started = task.statusId
            val planned = repository.currentWorkspace().workflowStatuses.first {
                it.projectId == task.projectId && it.semanticStatus == SemanticStatus.PLANNED
            }.id
            val firstChange = Instant.parse("2026-08-02T10:00:00Z")

            repeat(501) { index ->
                repository.execute(
                    DomainCommand.ChangeTaskStatus(
                        taskId = task.id,
                        statusId = if (index % 2 == 0) planned else started,
                        changedAt = firstChange.plusSeconds(index.toLong()),
                    ),
                )
            }

            val entries = repository.currentWorkspace().activityEntries.filter { it.taskId == task.id }
            assertEquals(500, entries.size)
            assertEquals(firstChange.plusSeconds(1), entries.minOf { it.createdAt })
            assertEquals(firstChange.plusSeconds(500), entries.maxOf { it.createdAt })
        }
    }

    @Test
    fun notesDoNotCreateOrMutateActivityEntries() = runBlocking {
        withTimeout(5_000) {
            val repository = InMemoryVaultRepository()
            val task = repository.currentWorkspace().tasks.first()
            val before = repository.currentWorkspace().activityEntries

            repository.execute(
                DomainCommand.AddNote(taskId = task.id, projectId = null, body = "No activity"),
            )

            assertEquals(before, repository.currentWorkspace().activityEntries)
        }
    }

    @Test
    fun permanentlyDeletingTaskRemovesItsActivityEntries() = runBlocking {
        withTimeout(5_000) {
            val repository = InMemoryVaultRepository()
            val task = repository.currentWorkspace().tasks.first()

            repository.execute(DomainCommand.CompleteTask(task.id))
            repository.execute(DomainCommand.DeleteTask(task.id))
            repository.execute(DomainCommand.PermanentlyDeleteTask(task.id))

            assertTrue(repository.currentWorkspace().activityEntries.none { it.taskId == task.id })
        }
    }
}
