package app.opentasks.core.data

import app.opentasks.core.data.backup.BackupMutationCodec
import app.opentasks.core.data.backup.BackupRecordFamily
import app.opentasks.core.data.backup.InMemoryBackupJournal
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class InMemoryNoteCommandTest {

    private val repository = InMemoryVaultRepository()

    @Test
    fun addNoteToTaskAppearsInSnapshotWithExactUndo() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()

            val result = repository.execute(
                DomainCommand.AddNote(taskId = task.id, projectId = null, body = "  Call the venue  "),
            ) as CommandResult.Success

            val note = repository.currentWorkspace().notes.single { it.taskId == task.id }
            assertEquals("Call the venue", note.body)
            assertNull(note.editedAt)
            val undo = result.undo as DomainCommand.DeleteNote
            assertEquals(note.id, undo.noteId)
        }
    }

    @Test
    fun addNoteRequiresExactlyOneOwner() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            val project = repository.currentWorkspace().projects.first()

            val both = repository.execute(
                DomainCommand.AddNote(taskId = task.id, projectId = project.id, body = "x"),
            )
            val neither = repository.execute(
                DomainCommand.AddNote(taskId = null, projectId = null, body = "x"),
            )

            assertEquals(RejectionReason.INVALID_STATE, (both as CommandResult.Rejected).reason)
            assertEquals(RejectionReason.INVALID_STATE, (neither as CommandResult.Rejected).reason)
        }
    }

    @Test
    fun noteBodyBoundsAreEnforced() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()

            val empty = repository.execute(
                DomainCommand.AddNote(taskId = task.id, projectId = null, body = "   "),
            )
            val oversize = repository.execute(
                DomainCommand.AddNote(taskId = task.id, projectId = null, body = "a".repeat(10_001)),
            )

            assertEquals(RejectionReason.EMPTY_NOTE, (empty as CommandResult.Rejected).reason)
            assertEquals(RejectionReason.NOTE_TOO_LONG, (oversize as CommandResult.Rejected).reason)
        }
    }

    @Test
    fun updateAndDeleteProduceExactRestoreUndo() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            repository.execute(DomainCommand.AddNote(taskId = task.id, projectId = null, body = "v1"))
            val original = repository.currentWorkspace().notes.single { it.taskId == task.id }

            val updated = repository.execute(
                DomainCommand.UpdateNote(original.id, "v2"),
            ) as CommandResult.Success
            val afterUpdate = repository.currentWorkspace().notes.single { it.id == original.id }
            assertEquals("v2", afterUpdate.body)
            assertNotNull(afterUpdate.editedAt)
            assertEquals(original, (updated.undo as DomainCommand.RestoreNote).note)

            val deleted = repository.execute(
                DomainCommand.DeleteNote(original.id),
            ) as CommandResult.Success
            assertTrue(repository.currentWorkspace().notes.none { it.id == original.id })
            assertEquals(afterUpdate, (deleted.undo as DomainCommand.RestoreNote).note)

            repository.execute(deleted.undo as DomainCommand)
            assertEquals(afterUpdate, repository.currentWorkspace().notes.single { it.id == original.id })
        }
    }

    /**
     * The undo of an edit is only worth offering if executing it puts the
     * previous text back. The edited note still carries the same identity, so
     * a restore that treats an existing identity as "already restored" would
     * report success while changing nothing.
     */
    @Test
    fun executingTheUpdateUndoRevertsTheNoteAndJournalsTheRestoredBody() = runBlocking {
        withTimeout(5_000) {
            val journal = InMemoryBackupJournal()
            val repository = InMemoryVaultRepository(backupJournal = journal)
            val task = repository.currentWorkspace().tasks.first()
            repository.execute(DomainCommand.AddNote(taskId = task.id, projectId = null, body = "v1"))
            val original = repository.currentWorkspace().notes.single { it.taskId == task.id }
            val updated = repository.execute(
                DomainCommand.UpdateNote(original.id, "v2"),
            ) as CommandResult.Success
            val edited = repository.currentWorkspace().notes.single { it.id == original.id }
            val generationBefore = journal.currentGeneration

            val undone = repository.execute(updated.undo as DomainCommand)

            assertTrue(undone is CommandResult.Success)
            val restored = repository.currentWorkspace().notes.single { it.id == original.id }
            assertEquals(original, restored)
            assertEquals("v1", restored.body)
            assertNull(restored.editedAt)
            assertTrue(edited.revision.logicalCounter > restored.revision.logicalCounter)
            assertEquals(generationBefore + 1, journal.currentGeneration)
            val restorePayloads = journal.entries
                .filter { it.generation.value == journal.currentGeneration }
                .map { BackupMutationCodec.decode(it.payload) }
            val afterImage = restorePayloads.single { it.record?.family == BackupRecordFamily.NOTE }
            assertEquals(BackupMutationKind.UPSERT, afterImage.mutationKind)
            assertEquals(listOf(original.id.value), afterImage.record!!.identity)
            assertEquals(
                "v1",
                String(
                    Base64.getDecoder().decode(
                        afterImage.record.fields.single { it.name == "bodyCiphertext" }.value,
                    ),
                    Charsets.UTF_8,
                ),
            )
        }
    }

    /**
     * Restore is replayed whenever a journal is re-applied, so writing the
     * same content twice must leave one record and allocate one generation.
     */
    @Test
    fun replayingTheSameNoteRestoreChangesNothingAndAllocatesNoGeneration() = runBlocking {
        withTimeout(5_000) {
            val journal = InMemoryBackupJournal()
            val repository = InMemoryVaultRepository(backupJournal = journal)
            val task = repository.currentWorkspace().tasks.first()
            repository.execute(DomainCommand.AddNote(taskId = task.id, projectId = null, body = "v1"))
            val original = repository.currentWorkspace().notes.single { it.taskId == task.id }
            repository.execute(DomainCommand.UpdateNote(original.id, "v2"))
            repository.execute(DomainCommand.RestoreNote(original))
            val afterFirstRestore = repository.currentWorkspace()
            val generationAfterFirstRestore = journal.currentGeneration
            val entriesAfterFirstRestore = journal.entries.size

            val replay = repository.execute(DomainCommand.RestoreNote(original))

            assertTrue(replay is CommandResult.Success)
            assertEquals(original, repository.currentWorkspace().notes.single { it.id == original.id })
            assertEquals(afterFirstRestore.notes, repository.currentWorkspace().notes)
            assertEquals(generationAfterFirstRestore, journal.currentGeneration)
            assertEquals(entriesAfterFirstRestore, journal.entries.size)
        }
    }

    @Test
    fun noteBodyOfExactlyTenThousandCharactersIsAccepted() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()

            val result = repository.execute(
                DomainCommand.AddNote(taskId = task.id, projectId = null, body = "a".repeat(10_000)),
            )

            assertTrue(result is CommandResult.Success)
            assertEquals(
                10_000,
                repository.currentWorkspace().notes.single { it.taskId == task.id }.body.length,
            )
        }
    }

    @Test
    fun ownerNoteCapIsEnforcedAt500() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            repeat(500) {
                val result = repository.execute(
                    DomainCommand.AddNote(taskId = task.id, projectId = null, body = "note $it"),
                )
                assertTrue(result is CommandResult.Success)
            }

            val overCap = repository.execute(
                DomainCommand.AddNote(taskId = task.id, projectId = null, body = "one more"),
            )

            assertEquals(
                RejectionReason.NOTE_LIMIT_REACHED,
                (overCap as CommandResult.Rejected).reason,
            )
        }
    }
}
