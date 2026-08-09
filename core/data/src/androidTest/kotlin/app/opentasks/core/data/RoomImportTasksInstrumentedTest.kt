package app.opentasks.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.ImportedTaskRow
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Priority
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomImportTasksInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var databaseKey: ByteArray
    private var database: VaultDatabase? = null
    private var repository: RoomVaultRepository? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "vault-import-test-${UUID.randomUUID()}.db"
        databaseKey = ByteArray(32) { index -> (index + 1).toByte() }
        database = VaultDatabase.create(context, databaseName, databaseKey)
        repository = RoomVaultRepository(
            database = database!!,
            deviceId = DeviceId("import-instrumented-test-device"),
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
    fun importAndUndoRestoreExactTableCountsAndJournalOneGenerationEach() = runBlocking {
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.currentWorkspace()
            val beforeCounts = recordTableCounts()
            val beforeState = database!!.backupStateDao().require(VAULT_ID)

            val imported = repository!!.execute(DomainCommand.ImportTasks(listOf(importRow())))
                as CommandResult.Success

            val afterImport = database!!.backupStateDao().require(VAULT_ID)
            assertEquals(beforeState.currentGeneration + 1, afterImport.currentGeneration)
            assertGenerationIsOrderedWithUniqueOperationIds(afterImport.currentGeneration)
            assertTableDeltas(
                before = beforeCounts,
                expected = mapOf(
                    "projects" to 1,
                    "workflow_statuses" to 5,
                    "tasks" to 1,
                    "tags" to 1,
                    "task_tags" to 1,
                    "activity_entries" to 2,
                ),
            )

            repository!!.execute(checkNotNull(imported.undo))

            val afterUndo = database!!.backupStateDao().require(VAULT_ID)
            assertEquals(afterImport.currentGeneration + 1, afterUndo.currentGeneration)
            assertGenerationIsOrderedWithUniqueOperationIds(afterUndo.currentGeneration)
            assertEquals(beforeCounts, recordTableCounts())
        }
    }

    @Test
    fun undoConflictLeavesEveryTableAndJournalUntouched() = runBlocking {
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.currentWorkspace()
            val imported = repository!!.execute(DomainCommand.ImportTasks(listOf(importRow())))
                as CommandResult.Success
            val undo = imported.undo as DomainCommand.RemoveImportedRecords
            repository!!.execute(
                DomainCommand.AddChecklistItem(undo.receipt.tasks.single().taskId, "Keep this"),
            )
            val beforeCounts = allTableCounts()
            val beforeState = database!!.backupStateDao().require(VAULT_ID)

            val rejected = repository!!.execute(undo) as CommandResult.Rejected

            assertEquals(RejectionReason.IMPORT_UNDO_CONFLICT, rejected.reason)
            assertEquals(beforeState, database!!.backupStateDao().require(VAULT_ID))
            assertEquals(beforeCounts, allTableCounts())
        }
    }

    @Test
    fun undoRejectsBeforeMutationWhenProjectedStateIsNotBackupRepresentable() = runBlocking {
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.currentWorkspace()
            val imported = repository!!.execute(DomainCommand.ImportTasks(listOf(importRow())))
                as CommandResult.Success
            val undo = imported.undo as DomainCommand.RemoveImportedRecords
            val importedTask = checkNotNull(
                database!!.taskDao().getById(undo.receipt.tasks.single().taskId.value),
            )
            database!!.taskDao().upsert(
                importedTask.copy(
                    id = "unrepresentable-task",
                    projectId = null,
                    statusId = "missing-status",
                ),
            )
            val beforeCounts = allTableCounts()
            val beforeState = database!!.backupStateDao().require(VAULT_ID)

            val rejected = repository!!.execute(undo) as CommandResult.Rejected

            assertEquals(RejectionReason.IMPORT_UNDO_CONFLICT, rejected.reason)
            assertEquals(beforeState, database!!.backupStateDao().require(VAULT_ID))
            assertEquals(beforeCounts, allTableCounts())
        }
    }

    private fun importRow() = ImportedTaskRow(
        sourceRowNumber = 1,
        title = "Imported task",
        projectName = "Imported project",
        statusName = "In progress",
        priority = Priority.HIGH,
        start = null,
        due = null,
        completedAt = null,
        estimateMinutes = 25,
        tagNames = listOf("Imported tag"),
        description = "Created from this app's tasks CSV",
    )

    private suspend fun assertGenerationIsOrderedWithUniqueOperationIds(generation: Long) {
        val entries = database!!.backupJournalDao().between(VAULT_ID, generation, generation)
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { it.generation == generation })
        assertEquals(entries.indices.toList(), entries.map { it.sequence })
        assertEquals(entries.size, entries.map { it.operationId }.toSet().size)
    }

    private fun assertTableDeltas(before: Map<String, Int>, expected: Map<String, Int>) {
        val after = recordTableCounts()
        before.forEach { (table, count) ->
            assertEquals("$table count", count + expected.getOrDefault(table, 0), after[table])
        }
    }

    private fun recordTableCounts(): Map<String, Int> = RECORD_TABLES.associateWith(::tableCount)

    private fun allTableCounts(): Map<String, Int> =
        (RECORD_TABLES + "backup_journal" + "backup_state").associateWith(::tableCount)

    private fun tableCount(table: String): Int =
        database!!.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val VAULT_ID = "vault-primary"
        val RECORD_TABLES = listOf(
            "vaults",
            "workspaces",
            "members",
            "projects",
            "workflow_statuses",
            "milestones",
            "tasks",
            "checklist_items",
            "task_dependencies",
            "tags",
            "task_tags",
            "reminders",
            "notes",
            "attachments",
            "attachment_transfer",
            "retired_blob_sets",
            "activity_entries",
            "time_entries",
            "templates",
            "saved_views",
            "sync_operations",
            "tombstones",
        )
    }
}
