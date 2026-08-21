package app.opentasks.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.data.backup.BackupMutationCodec
import app.opentasks.core.data.backup.BackupRecordFamily
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.SemanticStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomActivityGenerationInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var databaseKey: ByteArray
    private var database: VaultDatabase? = null
    private var repository: RoomVaultRepository? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "vault-activity-test-${UUID.randomUUID()}.db"
        databaseKey = ByteArray(32) { index -> (index + 1).toByte() }
        database = VaultDatabase.create(context, databaseName, databaseKey)
        repository = RoomVaultRepository(
            database = database!!,
            deviceId = DeviceId("activity-instrumented-test-device"),
            seedSnapshot = OpenTasksFixtures.snapshot,
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
    fun completionWritesActivityAndJournalAtTheCommandGeneration() = runBlocking {
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            val task = repository!!.currentWorkspace().tasks.first()
            val before = database!!.backupStateDao().require(VAULT_ID)

            val result = repository!!.execute(
                DomainCommand.CompleteTask(
                    taskId = task.id,
                    completedAt = Instant.parse("2026-08-02T10:00:00Z"),
                ),
            )

            val after = database!!.backupStateDao().require(VAULT_ID)
            val activity = database!!.recoveryImportDao().allActivityEntries().single()
            val rows = database!!.backupJournalDao().between(
                VAULT_ID,
                after.currentGeneration,
                after.currentGeneration,
            )
            val payloads = rows.map { BackupMutationCodec.decode(it.payload) }

            assertTrue(result is CommandResult.Success)
            assertEquals(before.currentGeneration + 1, after.currentGeneration)
            assertEquals("COMPLETED", activity.kind)
            assertTrue(rows.all { it.generation == after.currentGeneration })
            assertTrue(
                payloads.any {
                    it.record?.family == BackupRecordFamily.ACTIVITY_ENTRY &&
                        it.record.identity == listOf(activity.id)
                },
            )
            assertTrue(payloads.any { it.record?.family == BackupRecordFamily.TASK })
        }
    }

    @Test
    fun pruningJournalsOldestActivityDeletion() = runBlocking {
        withTimeout(60_000) {
            // Snapshot task order is the repository's, not the fixture's, so the
            // task is chosen by the status it must alternate away from. The bound
            // is wider than the usual five seconds because 501 real Room
            // transactions run here, not one.
            val task = repository!!.currentWorkspace().tasks.first {
                it.semanticStatus == SemanticStatus.STARTED
            }
            val started = task.statusId
            val planned = repository!!.currentWorkspace().workflowStatuses.first {
                it.projectId == task.projectId && it.semanticStatus == SemanticStatus.PLANNED
            }.id
            assertNotEquals(started, planned)
            val firstChange = Instant.parse("2026-08-02T10:00:00Z")

            repeat(501) { index ->
                repository!!.execute(
                    DomainCommand.ChangeTaskStatus(
                        taskId = task.id,
                        statusId = if (index % 2 == 0) planned else started,
                        changedAt = firstChange.plusSeconds(index.toLong()),
                    ),
                )
            }

            val after = database!!.backupStateDao().require(VAULT_ID)
            val payloads = database!!.backupJournalDao()
                .between(VAULT_ID, after.currentGeneration, after.currentGeneration)
                .map { BackupMutationCodec.decode(it.payload) }

            assertEquals(500, database!!.recoveryImportDao().allActivityEntries().size)
            assertTrue(
                payloads.any { it.deletedFamily == BackupRecordFamily.ACTIVITY_ENTRY },
            )
        }
    }

    private companion object {
        const val VAULT_ID = "vault-primary"
    }
}
