package app.opentasks.core.data.backup

import app.opentasks.core.data.InMemoryVaultRepository
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.WorkflowMoveDirection
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ProjectId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InMemoryBackupJournalTest {
    @Test
    fun oneRowMutationAllocatesOneGenerationAndOneCanonicalAfterImage() = runBlocking {
        val journal = InMemoryBackupJournal(operationId = { "operation-1" })
        val repository = repository(journal)
        val task = OpenTasksFixtures.tasks.first()

        val result = repository.execute(DomainCommand.RenameTask(task.id, "Renamed"))

        assertTrue(result is CommandResult.Success)
        assertEquals(1, journal.currentGeneration)
        assertEquals(listOf(0), journal.entries.map { it.sequence })
        val entry = journal.entries.single()
        assertEquals("UPSERT", entry.mutationKind.name)
        val payload = BackupMutationCodec.decode(entry.payload)
        assertEquals(BackupRecordFamily.TASK, payload.record!!.family)
        assertEquals(listOf(task.id.value), payload.record.identity)
        assertEquals(
            "Renamed",
            payload.record.fields.single { it.name == "title" }.value,
        )
    }

    @Test
    fun multiRowMutationUsesOneGenerationAndStableSequences() = runBlocking {
        var operationIndex = 0
        val journal = InMemoryBackupJournal(
            operationId = { "operation-${operationIndex++}" },
        )
        val repository = repository(journal)
        val projectId = ProjectId("project-journal")

        val result = repository.execute(
            DomainCommand.CreateProject(
                projectId = projectId,
                name = "Journal",
            ),
        )

        assertTrue(result is CommandResult.Success)
        assertEquals(1, journal.currentGeneration)
        assertEquals(List(6) { 1L }, journal.entries.map { it.generation.value })
        assertEquals(List(6) { it }, journal.entries.map { it.sequence })
        assertEquals(
            listOf(
                BackupRecordFamily.PROJECT,
                BackupRecordFamily.WORKFLOW_STATUS,
                BackupRecordFamily.WORKFLOW_STATUS,
                BackupRecordFamily.WORKFLOW_STATUS,
                BackupRecordFamily.WORKFLOW_STATUS,
                BackupRecordFamily.WORKFLOW_STATUS,
            ),
            journal.entries.map { entry ->
                BackupMutationCodec.decode(entry.payload).record!!.family
            },
        )
    }

    @Test
    fun rejectedCommandDoesNotAllocateGeneration() = runBlocking {
        val journal = InMemoryBackupJournal()
        val repository = repository(journal)

        val result = repository.execute(
            DomainCommand.CreateProject(ProjectId("project-rejected"), " "),
        )

        assertTrue(result is CommandResult.Rejected)
        assertEquals(0, journal.currentGeneration)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun idempotentSuccessDoesNotAllocateGeneration() = runBlocking {
        val journal = InMemoryBackupJournal()
        val repository = repository(journal)
        val firstStatus = OpenTasksFixtures.snapshot.workflowStatuses
            .filter { it.projectId != null && it.archivedAt == null }
            .minBy { it.rank }

        val result = repository.execute(
            DomainCommand.MoveWorkflowStatus(
                statusId = firstStatus.id,
                direction = WorkflowMoveDirection.EARLIER,
            ),
        )

        assertTrue(result is CommandResult.Success)
        assertEquals(0, journal.currentGeneration)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun sameTitleRenameDoesNotAllocateGenerationOrJournalEntry() = runBlocking {
        val journal = InMemoryBackupJournal()
        val repository = repository(journal)
        val task = OpenTasksFixtures.tasks.first()

        val result = repository.execute(DomainCommand.RenameTask(task.id, task.title))

        assertTrue(result is CommandResult.Success)
        assertEquals(task, repository.currentWorkspace().tasks.first { it.id == task.id })
        assertEquals(0, journal.currentGeneration)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun journalAppendFailureRestoresProductRowsGenerationAndEntries() = runBlocking {
        val journal = InMemoryBackupJournal(
            appendBoundary = { throw InjectedAppendFailure() },
        )
        val repository = repository(journal)
        val task = OpenTasksFixtures.tasks.first()

        assertThrows(InjectedAppendFailure::class.java) {
            runBlocking {
                repository.execute(DomainCommand.RenameTask(task.id, "Must roll back"))
            }
        }

        assertEquals(
            task.title,
            repository.currentWorkspace().tasks.first { it.id == task.id }.title,
        )
        assertEquals(0, journal.currentGeneration)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun tagRemovalAndReAddUseRetainedTaskTagAfterImages() = runBlocking {
        val journal = InMemoryBackupJournal()
        val repository = repository(journal)
        val task = OpenTasksFixtures.tasks.first { it.tagIds.isNotEmpty() }
        val tagId = task.tagIds.first()

        repository.execute(DomainCommand.SetTaskTag(task.id, tagId, present = false))

        val removalPayloads = journal.entries
            .filter { it.generation.value == 1L }
            .map { BackupMutationCodec.decode(it.payload) }
        val removedRelation = removalPayloads.single {
            it.record?.family == BackupRecordFamily.TASK_TAG
        }
        assertEquals(BackupMutationKind.UPSERT, removedRelation.mutationKind)
        assertEquals(
            "false",
            removedRelation.record!!.fields.single { it.name == "present" }.value,
        )
        assertTrue(
            removalPayloads.none { it.deletedFamily == BackupRecordFamily.TASK_TAG },
        )

        repository.execute(DomainCommand.SetTaskTag(task.id, tagId, present = true))

        val reAddPayloads = journal.entries
            .filter { it.generation.value == 2L }
            .map { BackupMutationCodec.decode(it.payload) }
        val restoredRelation = reAddPayloads.single {
            it.record?.family == BackupRecordFamily.TASK_TAG
        }
        assertEquals(
            "true",
            restoredRelation.record!!.fields.single { it.name == "present" }.value,
        )
    }

    @Test
    fun purgingTaskDeletesRetainedFalseTaskTagRelation() = runBlocking {
        val now = Instant.parse("2026-07-29T10:00:00Z")
        val journal = InMemoryBackupJournal()
        val repository = repository(journal)
        val task = OpenTasksFixtures.tasks.first { it.tagIds.isNotEmpty() }
        val tagId = task.tagIds.first()

        repository.execute(DomainCommand.SetTaskTag(task.id, tagId, present = false))
        repository.execute(DomainCommand.DeleteTask(task.id, now.minusSeconds(60)))
        repository.execute(DomainCommand.PermanentlyDeleteTask(task.id, now))

        val purgeGeneration = journal.currentGeneration
        val purgePayloads = journal.entries
            .filter { it.generation.value == purgeGeneration }
            .map { BackupMutationCodec.decode(it.payload) }
        assertTrue(
            purgePayloads.any {
                it.mutationKind == BackupMutationKind.DELETE &&
                    it.deletedFamily == BackupRecordFamily.TASK_TAG &&
                    it.deletedIdentity == listOf(task.id.value, tagId.value)
            },
        )
    }

    private fun repository(journal: InMemoryBackupJournal): InMemoryVaultRepository =
        InMemoryVaultRepository(
            initial = OpenTasksFixtures.snapshot,
            now = { Instant.parse("2026-07-29T10:00:00Z") },
            backupJournal = journal,
        )

    private class InjectedAppendFailure : RuntimeException()
}
