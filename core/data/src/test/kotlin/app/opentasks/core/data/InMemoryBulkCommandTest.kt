package app.opentasks.core.data

import app.opentasks.core.data.backup.InMemoryBackupJournal
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.ZonedMoment
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InMemoryBulkCommandTest {
    private val journal = InMemoryBackupJournal()
    private val repository = InMemoryVaultRepository(
        now = { Instant.parse("2026-07-26T10:00:00Z") },
        backupJournal = journal,
    )

    @Test
    fun completeTasksAppliesAllAndUndoBatchRestoresEveryStatus() = runBlocking {
        withTimeout(5_000) {
            val ids = repository.currentWorkspace().tasks
                .filterNot { it.isCompleted || it.isBlocked }
                .take(2).map { it.id }
            val result = repository.execute(
                DomainCommand.CompleteTasks(ids),
            ) as CommandResult.Success
            assertTrue(
                repository.currentWorkspace().tasks
                    .filter { it.id in ids }.all { it.isCompleted },
            )
            repository.execute(result.undo!!)
            assertTrue(
                repository.currentWorkspace().tasks
                    .filter { it.id in ids }.none { it.isCompleted },
            )
        }
    }

    @Test
    fun missingIdsAreSkippedNotFatal() = runBlocking {
        withTimeout(5_000) {
            val real = repository.currentWorkspace().tasks
                .first { !it.isCompleted && !it.isBlocked }.id
            val result = repository.execute(
                DomainCommand.CompleteTasks(listOf(real, TaskId("missing-task"))),
            ) as CommandResult.Success
            assertTrue(repository.currentWorkspace().tasks.single { it.id == real }.isCompleted)
            assertEquals("1 task completed", result.message)
        }
    }

    @Test
    fun twoHundredAndOneIdsAreRejectedWithoutMutation() = runBlocking {
        withTimeout(5_000) {
            val before = repository.currentWorkspace()
            val result = repository.execute(
                DomainCommand.DeleteTasks(List(201) { TaskId("task-$it") }),
            ) as CommandResult.Rejected
            assertEquals(RejectionReason.BULK_SELECTION_TOO_LARGE, result.reason)
            assertEquals(before, repository.currentWorkspace())
        }
    }

    @Test
    fun setTasksTagUndoFlipsOnlyIdsThatChanged() = runBlocking {
        withTimeout(5_000) {
            val snapshot = repository.currentWorkspace()
            val tag = snapshot.tags.first()
            val ids = snapshot.tasks.filter { !it.isCompleted }.take(2).map { it.id }
            repository.execute(DomainCommand.SetTaskTag(ids[0], tag.id, present = true))
            repository.execute(DomainCommand.SetTaskTag(ids[1], tag.id, present = false))

            val result = repository.execute(
                DomainCommand.SetTasksTag(ids, tag.id, present = true),
            ) as CommandResult.Success
            repository.execute(result.undo!!)

            val restored = repository.currentWorkspace().tasks.associateBy { it.id }
            assertTrue(tag.id in restored.getValue(ids[0]).tagIds)
            assertFalse(tag.id in restored.getValue(ids[1]).tagIds)
        }
    }

    @Test
    fun blockedCompletionRejectsWholeBatchUntilAcknowledged() = runBlocking {
        withTimeout(5_000) {
            val before = repository.currentWorkspace()
            val blocked = before.tasks.first { it.isBlocked && !it.isCompleted }
            val unblocked = before.tasks.first { !it.isBlocked && !it.isCompleted }
            val journalSizeBefore = journal.entries.size

            val first = repository.execute(
                DomainCommand.CompleteTasks(listOf(blocked.id, unblocked.id)),
            ) as CommandResult.Rejected
            assertEquals(RejectionReason.BLOCKED_TASK_WARNING_REQUIRED, first.reason)
            assertEquals(before, repository.currentWorkspace())
            assertEquals(journalSizeBefore, journal.entries.size)

            val second = repository.execute(
                DomainCommand.CompleteTasks(
                    listOf(blocked.id, unblocked.id),
                    acknowledgeBlocked = true,
                ),
            )
            assertTrue(second is CommandResult.Success)
            val completed = repository.currentWorkspace().tasks.associateBy { it.id }
            assertTrue(completed.getValue(blocked.id).isCompleted)
            assertTrue(completed.getValue(unblocked.id).isCompleted)
        }
    }

    @Test
    fun rescheduleTasksAndUndo() = runBlocking {
        withTimeout(5_000) {
            val before = repository.currentWorkspace()
            val originals = before.tasks
                .filterNot { it.isCompleted || it.recurrence != null }
                .take(2)
            val ids = originals.map { it.id }
            val due = ZonedMoment(Instant.parse("2026-08-14T10:00:00Z"), "Asia/Bangkok")

            val result = repository.execute(
                DomainCommand.RescheduleTasks(ids, due),
            ) as CommandResult.Success

            val rescheduled = repository.currentWorkspace().tasks.associateBy { it.id }
            originals.forEach { original ->
                val updated = rescheduled.getValue(original.id)
                assertEquals(due, updated.due)
                assertTrue(updated.revision.logicalCounter > original.revision.logicalCounter)
                // Only due and the revision may differ from the original task.
                assertEquals(original.copy(due = due, revision = updated.revision), updated)
            }
            assertEquals(before.reminders, repository.currentWorkspace().reminders)

            repository.execute(result.undo!!)
            val restored = repository.currentWorkspace().tasks.associateBy { it.id }
            originals.forEach { original ->
                assertEquals(original.due, restored.getValue(original.id).due)
            }
        }
    }

    @Test
    fun moveTasksAndUndo() = runBlocking {
        withTimeout(5_000) {
            val before = repository.currentWorkspace()
            val destination = before.projects.first().id
            val originals = before.tasks
                .filter { it.projectId != destination && !it.isCompleted }
                .take(2)
            val ids = originals.map { it.id }

            val result = repository.execute(
                DomainCommand.MoveTasksToProject(ids, destination),
            ) as CommandResult.Success

            val afterMove = repository.currentWorkspace()
            val moved = afterMove.tasks.associateBy { it.id }
            originals.forEach { original ->
                val updated = moved.getValue(original.id)
                assertEquals(destination, updated.projectId)
                assertEquals(original.semanticStatus, updated.semanticStatus)
                val status = afterMove.workflowStatuses.first { it.id == updated.statusId }
                assertEquals(destination, status.projectId)
            }

            repository.execute(result.undo!!)
            val restored = repository.currentWorkspace().tasks.associateBy { it.id }
            originals.forEach { original ->
                val task = restored.getValue(original.id)
                assertEquals(original.projectId, task.projectId)
                assertEquals(original.statusId, task.statusId)
            }
        }
    }

    @Test
    fun deleteTasksAndUndo() = runBlocking {
        withTimeout(5_000) {
            val originals = repository.currentWorkspace().tasks
                .filter { it.deletedAt == null && !it.isCompleted }
                .take(2)
            val ids = originals.map { it.id }

            val result = repository.execute(
                DomainCommand.DeleteTasks(ids),
            ) as CommandResult.Success

            val binned = repository.currentWorkspace().tasks.associateBy { it.id }
            ids.forEach { id -> assertTrue(binned.getValue(id).deletedAt != null) }

            repository.execute(result.undo!!)
            val restored = repository.currentWorkspace().tasks.associateBy { it.id }
            ids.forEach { id -> assertTrue(restored.getValue(id).deletedAt == null) }
        }
    }

    @Test
    fun recurringCompletionAndUndo() = runBlocking {
        withTimeout(5_000) {
            val originals = repository.currentWorkspace().tasks
                .filterNot { it.isCompleted || it.isBlocked }
                .take(2)
            originals.forEach { task ->
                val updated = repository.execute(
                    DomainCommand.UpdateTask(
                        taskId = task.id,
                        title = task.title,
                        description = task.description,
                        projectId = task.projectId,
                        priority = task.priority,
                        due = task.due,
                        recurrence = RecurrenceRule(RecurrenceFrequency.DAILY),
                        estimate = task.estimate,
                        milestoneId = task.milestoneId,
                    ),
                )
                assertTrue(updated is CommandResult.Success)
            }
            val ids = originals.map { it.id }
            val taskIdsBefore = repository.currentWorkspace().tasks.mapTo(hashSetOf()) { it.id }

            val result = repository.execute(
                DomainCommand.CompleteTasks(ids),
            ) as CommandResult.Success

            val generated = repository.currentWorkspace().tasks.filter { it.id !in taskIdsBefore }
            assertEquals(2, generated.size)
            assertTrue(generated.none { it.isCompleted })
            val undoBatch = result.undo as DomainCommand.UndoBatch
            assertEquals(
                generated.mapTo(hashSetOf()) { it.id },
                undoBatch.commands
                    .filterIsInstance<DomainCommand.RestoreTaskStatus>()
                    .mapNotNullTo(hashSetOf()) { it.generatedOccurrenceId },
            )

            repository.execute(undoBatch)
            val restored = repository.currentWorkspace()
            assertTrue(restored.tasks.none { it.id !in taskIdsBefore })
            ids.forEach { id ->
                assertFalse(restored.tasks.single { task -> task.id == id }.isCompleted)
            }
        }
    }
}
