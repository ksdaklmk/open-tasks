package app.opentasks.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.data.backup.BackupMutationCodec
import app.opentasks.core.data.backup.BackupRecordFamily
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.data.db.toModel
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Note
import app.opentasks.core.model.WorkspaceSnapshot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/**
 * Room persistence parity for the note commands: [DomainCommand.AddNote],
 * [DomainCommand.UpdateNote], [DomainCommand.DeleteNote], and
 * [DomainCommand.RestoreNote] must behave identically to
 * `InMemoryVaultRepository`'s handlers, and every accepted mutation must
 * append a `NOTE`-family backup-journal entry in the same transaction.
 */
@RunWith(AndroidJUnit4::class)
class RoomNoteCommandInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var databaseKey: ByteArray
    private var database: VaultDatabase? = null
    private var repository: RoomVaultRepository? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "vault-note-test-${UUID.randomUUID()}.db"
        databaseKey = ByteArray(32) { index -> (index + 1).toByte() }
        database = VaultDatabase.create(context, databaseName, databaseKey)
        repository = RoomVaultRepository(
            database = database!!,
            deviceId = DeviceId("note-instrumented-test-device"),
        )
    }

    @After
    fun tearDown() {
        repository?.close()
        database?.close()
        context.deleteDatabase(databaseName)
        databaseKey.fill(0)
    }

    @Test
    fun addUpdateDeleteRestoreRoundTripPersistsThroughRoom() = runBlocking {
        withTimeout(5_000) {
            val task = repository!!.currentWorkspace().tasks.first()

            val added = repository!!.execute(
                DomainCommand.AddNote(
                    taskId = task.id,
                    projectId = null,
                    body = "  Call the venue  ",
                ),
            ) as CommandResult.Success
            val note = awaitNote { it.taskId == task.id }
            assertEquals("Call the venue", note.body)
            assertNull(note.editedAt)
            assertEquals(note.id, (added.undo as DomainCommand.DeleteNote).noteId)

            val updated = repository!!.execute(
                DomainCommand.UpdateNote(note.id, "v2"),
            ) as CommandResult.Success
            val afterUpdate = awaitNote { it.id == note.id && it.body == "v2" }
            assertNotNull(afterUpdate.editedAt)
            assertEquals(note, (updated.undo as DomainCommand.RestoreNote).note)

            val deleted = repository!!.execute(
                DomainCommand.DeleteNote(note.id),
            ) as CommandResult.Success
            awaitWorkspace { snapshot -> snapshot.notes.none { it.id == note.id } }
            assertEquals(afterUpdate, (deleted.undo as DomainCommand.RestoreNote).note)

            repository!!.execute(deleted.undo as DomainCommand)
            val restored = awaitNote { it.id == note.id }
            assertEquals(afterUpdate, restored)
        }
    }

    @Test
    fun addNoteRequiresExactlyOneOwner() = runBlocking {
        withTimeout(5_000) {
            val task = repository!!.currentWorkspace().tasks.first()
            val project = repository!!.currentWorkspace().projects.first()

            val both = repository!!.execute(
                DomainCommand.AddNote(taskId = task.id, projectId = project.id, body = "x"),
            )
            val neither = repository!!.execute(
                DomainCommand.AddNote(taskId = null, projectId = null, body = "x"),
            )

            assertEquals(RejectionReason.INVALID_STATE, (both as CommandResult.Rejected).reason)
            assertEquals(RejectionReason.INVALID_STATE, (neither as CommandResult.Rejected).reason)
        }
    }

    @Test
    fun addNoteJournalsANoteFamilyEntryAtTheCommandGeneration() = runBlocking {
        withTimeout(5_000) {
            val task = repository!!.currentWorkspace().tasks.first()
            val before = database!!.backupStateDao().require(VAULT_ID)

            val result = repository!!.execute(
                DomainCommand.AddNote(taskId = task.id, projectId = null, body = "Journalled note"),
            )

            val after = database!!.backupStateDao().require(VAULT_ID)
            val rows = database!!.backupJournalDao().between(
                VAULT_ID,
                after.currentGeneration,
                after.currentGeneration,
            )
            assertTrue(result is CommandResult.Success)
            assertEquals(before.currentGeneration + 1, after.currentGeneration)
            assertTrue(rows.isNotEmpty())
            assertEquals("NOTE", rows.maxBy { it.sequence }.objectType)
        }
    }

    /**
     * The Room mirror of the in-memory round trip: executing the undo an edit
     * hands back must put the previous body on disk, not merely report that a
     * note with that identity is present.
     */
    @Test
    fun executingTheUpdateUndoRevertsTheStoredNoteAndJournalsTheRestoredBody() = runBlocking {
        withTimeout(5_000) {
            val task = repository!!.currentWorkspace().tasks.first()
            repository!!.execute(
                DomainCommand.AddNote(taskId = task.id, projectId = null, body = "v1"),
            )
            val original = awaitNote { it.taskId == task.id }
            val updated = repository!!.execute(
                DomainCommand.UpdateNote(original.id, "v2"),
            ) as CommandResult.Success
            val edited = awaitNote { it.id == original.id && it.body == "v2" }
            val before = database!!.backupStateDao().require(VAULT_ID)

            val undone = repository!!.execute(updated.undo as DomainCommand)

            assertTrue(undone is CommandResult.Success)
            val restored = awaitNote { it.id == original.id && it.body == "v1" }
            assertEquals(original, restored)
            assertNull(restored.editedAt)
            assertEquals("v1", database!!.workspaceDao().getNoteById(original.id.value)!!.toModel().body)
            assertTrue(edited.revision.logicalCounter > restored.revision.logicalCounter)
            val after = database!!.backupStateDao().require(VAULT_ID)
            assertEquals(before.currentGeneration + 1, after.currentGeneration)
            val rows = database!!.backupJournalDao().between(
                VAULT_ID,
                after.currentGeneration,
                after.currentGeneration,
            )
            val payloads = rows.map { BackupMutationCodec.decode(it.payload) }
            assertTrue(
                payloads.any {
                    it.record?.family == BackupRecordFamily.NOTE &&
                        it.record.identity == listOf(original.id.value)
                },
            )

            // Replaying the same restore writes the same content, so it leaves
            // one record behind and allocates no further generation.
            assertTrue(repository!!.execute(DomainCommand.RestoreNote(original)) is CommandResult.Success)
            assertEquals(original, awaitNote { it.id == original.id })
            assertEquals(
                after.currentGeneration,
                database!!.backupStateDao().require(VAULT_ID).currentGeneration,
            )
        }
    }

    @Test
    fun permanentlyDeleteTaskRemovesNotesAndJournalsTheNoteDelete() = runBlocking {
        withTimeout(5_000) {
            val task = repository!!.currentWorkspace().tasks.first()
            repository!!.execute(
                DomainCommand.AddNote(
                    taskId = task.id,
                    projectId = null,
                    body = "Goes with the task",
                ),
            )
            val note = awaitNote { it.taskId == task.id }

            repository!!.execute(DomainCommand.DeleteTask(task.id, Instant.now()))
            val before = database!!.backupStateDao().require(VAULT_ID)
            val result = repository!!.execute(DomainCommand.PermanentlyDeleteTask(task.id))

            val after = database!!.backupStateDao().require(VAULT_ID)
            assertTrue(result is CommandResult.Success)
            assertNull(database!!.workspaceDao().getNoteById(note.id.value))
            val rows = database!!.backupJournalDao().between(
                VAULT_ID,
                before.currentGeneration + 1,
                after.currentGeneration,
            )
            val payloads = rows.map { BackupMutationCodec.decode(it.payload) }
            assertTrue(
                payloads.any {
                    it.deletedFamily == BackupRecordFamily.NOTE &&
                        it.deletedIdentity == listOf(note.id.value)
                },
            )
        }
    }

    private suspend fun awaitNote(predicate: (Note) -> Boolean): Note =
        repository!!.observeWorkspace()
            .map { snapshot -> snapshot.notes.firstOrNull(predicate) }
            .filterNotNull()
            .first()

    private suspend fun awaitWorkspace(predicate: (WorkspaceSnapshot) -> Boolean) {
        repository!!.observeWorkspace().first(predicate)
    }

    private companion object {
        const val VAULT_ID = "vault-primary"
    }
}
