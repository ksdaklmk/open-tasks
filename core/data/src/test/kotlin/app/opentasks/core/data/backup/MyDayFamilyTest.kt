package app.opentasks.core.data.backup

import app.opentasks.core.data.InMemoryVaultRepository
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyDayFamilyTest {

    private val journal = InMemoryBackupJournal()
    private val repository = InMemoryVaultRepository(backupJournal = journal)

    @Test
    fun myDayMutationsJournalThroughTheBackupFamily() = runBlocking {
        withTimeout(5_000) {
            val tasks = repository.currentWorkspace().tasks
                .filter { it.deletedAt == null && !it.isCompleted }
                .take(2)

            repository.execute(DomainCommand.AddTaskToMyDay(tasks[0].id))
            repository.execute(DomainCommand.AddTaskToMyDay(tasks[1].id))
            val addUpserts = journal.entries.filter {
                it.objectType == "MY_DAY" && it.mutationKind == BackupMutationKind.UPSERT
            }
            assertEquals(
                setOf(tasks[0].id.value, tasks[1].id.value),
                addUpserts.map { it.objectId }.toSet(),
            )

            // Reorder writes an upsert for the moved row only.
            repository.execute(DomainCommand.MoveMyDayEntry(tasks[1].id, afterTaskId = null))
            val movedUpserts = journal.entries.filter {
                it.objectType == "MY_DAY" && it.mutationKind == BackupMutationKind.UPSERT
            }
            assertEquals(
                listOf(tasks[0].id.value, tasks[1].id.value, tasks[1].id.value),
                movedUpserts.map { it.objectId },
            )

            val remove = repository.execute(DomainCommand.RemoveTaskFromMyDay(tasks[0].id))
            assertTrue(remove is CommandResult.Success)
            val removeDeletes = journal.entries.filter {
                it.objectType == "MY_DAY" && it.mutationKind == BackupMutationKind.DELETE
            }
            assertEquals(listOf(tasks[0].id.value), removeDeletes.map { it.objectId })

            // Purging a member task deletes its entry in the same generation
            // as the purge itself.
            repository.execute(DomainCommand.DeleteTask(tasks[1].id))
            val generationBeforePurge = journal.currentGeneration
            val purge = repository.execute(DomainCommand.PermanentlyDeleteTask(tasks[1].id))
            assertTrue(purge is CommandResult.Success)
            assertEquals(generationBeforePurge + 1, journal.currentGeneration)
            val purgeEntries = journal.entries
                .filter { it.generation.value == journal.currentGeneration }
            assertTrue(
                purgeEntries.any {
                    it.objectType == "MY_DAY" &&
                        it.mutationKind == BackupMutationKind.DELETE &&
                        it.objectId == tasks[1].id.value
                },
            )
            assertTrue(repository.currentWorkspace().myDay.isEmpty())
        }
    }
}
