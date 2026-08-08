package app.opentasks.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.WorkspaceSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Room persistence parity for the bulk composite commands: each accepted
 * batch commits its records and its ordered backup-journal entries in one
 * transaction, and a preflight rejection leaves both tables untouched.
 */
@RunWith(AndroidJUnit4::class)
class RoomBulkCommandInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var databaseKey: ByteArray
    private var database: VaultDatabase? = null
    private var repository: RoomVaultRepository? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "vault-bulk-test-${UUID.randomUUID()}.db"
        databaseKey = ByteArray(32) { index -> (index + 1).toByte() }
        database = VaultDatabase.create(context, databaseName, databaseKey)
        repository = RoomVaultRepository(
            database = database!!,
            deviceId = DeviceId("bulk-instrumented-test-device"),
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
    fun completeTasksJournalsOneGenerationAndUndoRestoresStatuses() = runBlocking {
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            val ids = repository!!.currentWorkspace().tasks
                .filterNot { it.isCompleted || it.isBlocked }
                .take(2).map { it.id }
            val before = database!!.backupStateDao().require(VAULT_ID)

            val result = repository!!.execute(
                DomainCommand.CompleteTasks(ids),
            ) as CommandResult.Success

            assertEquals("2 tasks completed", result.message)
            val after = database!!.backupStateDao().require(VAULT_ID)
            assertEquals(before.currentGeneration + 1, after.currentGeneration)
            val entries = database!!.backupJournalDao().between(
                VAULT_ID,
                after.currentGeneration,
                after.currentGeneration,
            )
            assertTrue(entries.isNotEmpty())
            assertEquals(
                entries.indices.toList(),
                entries.map { it.sequence },
            )
            assertEquals(entries.size, entries.map { it.operationId }.toSet().size)
            awaitWorkspace { snapshot ->
                snapshot.tasks.filter { it.id in ids }.all { it.isCompleted }
            }

            repository!!.execute(result.undo!!)

            awaitWorkspace { snapshot ->
                snapshot.tasks.filter { it.id in ids }.none { it.isCompleted }
            }
            repository!!.currentWorkspace().tasks
                .filter { it.id in ids }
                .forEach { assertFalse(it.isCompleted) }
        }
    }

    @Test
    fun blockedCompletionRejectionLeavesTablesAndJournalUntouched() = runBlocking {
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            val snapshot = repository!!.currentWorkspace()
            val blocked = snapshot.tasks.first { it.isBlocked && !it.isCompleted }
            val unblocked = snapshot.tasks.first { !it.isBlocked && !it.isCompleted }
            val stateBefore = database!!.backupStateDao().require(VAULT_ID)
            val journalBefore = database!!.backupJournalDao().between(
                VAULT_ID,
                0,
                stateBefore.currentGeneration,
            )

            val result = repository!!.execute(
                DomainCommand.CompleteTasks(listOf(blocked.id, unblocked.id)),
            ) as CommandResult.Rejected

            assertEquals(RejectionReason.BLOCKED_TASK_WARNING_REQUIRED, result.reason)
            val stateAfter = database!!.backupStateDao().require(VAULT_ID)
            assertEquals(stateBefore.currentGeneration, stateAfter.currentGeneration)
            val journalAfter = database!!.backupJournalDao().between(
                VAULT_ID,
                0,
                stateAfter.currentGeneration,
            )
            assertEquals(journalBefore.size, journalAfter.size)
            assertEquals(snapshot.tasks.size, repository!!.currentWorkspace().tasks.size)
            assertFalse(
                repository!!.currentWorkspace().tasks
                    .filter { it.id == blocked.id || it.id == unblocked.id }
                    .any { it.isCompleted },
            )
        }
    }

    @Test
    fun setTasksTagAppliesAcrossTasksAndUndoFlipsOnlyChangedIds() = runBlocking {
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            val snapshot = repository!!.currentWorkspace()
            val tag = snapshot.tags.first()
            val alreadyTagged = snapshot.tasks.first { tag.id in it.tagIds }
            val untagged = snapshot.tasks.first { tag.id !in it.tagIds && !it.isCompleted }
            val ids = listOf(alreadyTagged.id, untagged.id)

            val result = repository!!.execute(
                DomainCommand.SetTasksTag(ids, tag.id, present = true),
            ) as CommandResult.Success

            awaitWorkspace { current ->
                current.tasks.filter { it.id in ids }.all { tag.id in it.tagIds }
            }

            repository!!.execute(result.undo!!)

            awaitWorkspace { current ->
                val byId = current.tasks.associateBy { it.id }
                tag.id in byId.getValue(alreadyTagged.id).tagIds &&
                    tag.id !in byId.getValue(untagged.id).tagIds
            }
        }
    }

    private suspend fun awaitWorkspace(predicate: (WorkspaceSnapshot) -> Boolean) {
        repository!!.observeWorkspace().first(predicate)
    }

    private companion object {
        const val VAULT_ID = "vault-primary"
    }
}
