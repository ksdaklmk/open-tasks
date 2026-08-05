package app.opentasks.core.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.crypto.AndroidVaultContentKeyStore
import app.opentasks.core.crypto.Argon2Metadata
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.LocalVaultRepositoryFactory
import app.opentasks.core.data.LocalVaultRuntimeFactory
import app.opentasks.core.data.VaultSlot
import app.opentasks.core.data.db.ActivityEntryEntity
import app.opentasks.core.data.db.AttachmentEntity
import app.opentasks.core.data.db.ChecklistItemEntity
import app.opentasks.core.data.db.MemberEntity
import app.opentasks.core.data.db.MilestoneEntity
import app.opentasks.core.data.db.NoteEntity
import app.opentasks.core.data.db.ProjectEntity
import app.opentasks.core.data.db.ReminderEntity
import app.opentasks.core.data.db.RetiredBlobSetEntity
import app.opentasks.core.data.db.SavedViewEntity
import app.opentasks.core.data.db.TagEntity
import app.opentasks.core.data.db.TaskDependencyEntity
import app.opentasks.core.data.db.TaskEntity
import app.opentasks.core.data.db.TaskTagEntity
import app.opentasks.core.data.db.TemplateEntity
import app.opentasks.core.data.db.TimeEntryEntity
import app.opentasks.core.data.db.TombstoneEntity
import app.opentasks.core.data.db.VAULT_DATABASE_VERSION
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.data.db.VaultEntity
import app.opentasks.core.data.db.WorkflowStatusEntity
import app.opentasks.core.data.db.WorkspaceEntity
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.VaultId
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRecordImporterInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val crypto: VaultCrypto = TinkVaultCrypto()
    private lateinit var slot: VaultSlot
    private var database: VaultDatabase? = null
    private lateinit var importer: BackupRecordImporter

    @Before
    fun setUp() {
        slot = VaultSlot.new()
        val staging = LocalVaultRepositoryFactory.createStagingDatabase(context, slot)
        database = staging
        importer = RoomBackupRecordImporter(staging, staging.recoveryImportDao())
    }

    @After
    fun tearDown() {
        runCatching { database?.close() }
        database = null
        runCatching { LocalVaultRuntimeFactory(context, crypto).discard(slot) }
    }

    // ---------------------------------------------------------------- Step 1

    @Test
    fun importsEveryBackupRecordFamilyExactly() = runBlocking {
        val request = requestWithOneRecordPerFamily()

        importer.importInto(staging(), request)

        for (family in BackupRecordFamily.entries) {
            assertEquals(
                "missing import assertion for $family",
                1,
                importedCount(staging(), family),
            )
        }
    }

    @Test
    fun importedRowsHoldTheExactAuthenticatedValues() = runBlocking {
        importer.importInto(staging(), requestWithOneRecordPerFamily())

        assertRow(
            table = "vaults",
            where = "id = ?",
            whereArgs = arrayOf(VAULT_ID),
            expected = mapOf(
                "id" to VAULT_ID,
                "storageMode" to "LOCAL",
                "createdAtEpochMillis" to 1_700_000_000_000L,
                "schemaVersion" to VAULT_DATABASE_VERSION,
                "cryptoVersion" to 1,
                "minimumReaderVersion" to 1,
            ),
        )
        assertRow(
            table = "workspaces",
            where = "id = ?",
            whereArgs = arrayOf(WORKSPACE_ID),
            expected = mapOf(
                "id" to WORKSPACE_ID,
                "vaultId" to VAULT_ID,
                "ownerId" to MEMBER_ID,
                "name" to "Open Tasks",
            ),
        )
        assertRow(
            table = "members",
            where = "id = ?",
            whereArgs = arrayOf(MEMBER_ID),
            expected = mapOf("id" to MEMBER_ID, "displayName" to "You"),
        )
        assertRow(
            table = "projects",
            where = "id = ?",
            whereArgs = arrayOf(PROJECT_ID),
            expected = mapOf(
                "id" to PROJECT_ID,
                "workspaceId" to WORKSPACE_ID,
                "name" to "Recovered project",
                "summary" to "Recovered summary",
                "health" to "AT_RISK",
                "dueDate" to "2026-09-01",
                "completedTasks" to 1,
                "totalTasks" to 4,
                "archivedAtEpochMillis" to 1_700_000_000_100L,
                "revisionWallMillis" to 1_700_000_000_200L,
                "revisionLogical" to 3,
                "revisionDeviceId" to SOURCE_DEVICE_ID,
            ),
        )
        assertRow(
            table = "workflow_statuses",
            where = "id = ?",
            whereArgs = arrayOf(STATUS_ID),
            expected = mapOf(
                "id" to STATUS_ID,
                "projectId" to PROJECT_ID,
                "name" to "In progress",
                "semanticStatus" to "STARTED",
                "rank" to "a0",
                "archivedAtEpochMillis" to 1_700_000_000_300L,
                "revisionWallMillis" to 1_700_000_000_400L,
                "revisionLogical" to 4,
                "revisionDeviceId" to SOURCE_DEVICE_ID,
            ),
        )
        assertRow(
            table = "milestones",
            where = "id = ?",
            whereArgs = arrayOf(MILESTONE_ID),
            expected = mapOf(
                "id" to MILESTONE_ID,
                "projectId" to PROJECT_ID,
                "name" to "Recovered milestone",
                "dueDate" to "2026-10-02",
                "completedAtEpochMillis" to 1_700_000_000_500L,
                "revisionWallMillis" to 1_700_000_000_600L,
                "revisionLogical" to 5,
                "revisionDeviceId" to SOURCE_DEVICE_ID,
            ),
        )
        assertRow(
            table = "tasks",
            where = "id = ?",
            whereArgs = arrayOf(TASK_ID),
            expected = mapOf(
                "id" to TASK_ID,
                "workspaceId" to WORKSPACE_ID,
                "projectId" to PROJECT_ID,
                "parentTaskId" to PARENT_TASK_ID,
                "statusId" to STATUS_ID,
                "semanticStatus" to "STARTED",
                "title" to "Recovered task",
                "descriptionCiphertext" to DESCRIPTION_CIPHERTEXT,
                "priority" to "URGENT",
                "startEpochMillis" to 1_700_000_001_000L,
                "startZoneId" to "Asia/Bangkok",
                "dueEpochMillis" to 1_700_000_002_000L,
                "dueZoneId" to "UTC",
                "recurrenceFrequency" to "WEEKLY",
                "recurrenceInterval" to 2,
                "recurrenceWeekdays" to "MONDAY,WEDNESDAY",
                "recurrenceCount" to 5,
                "recurrenceEndDate" to null,
                "recurrenceSeriesId" to "series-recovered",
                "recurrenceAnchorEpochMillis" to 1_700_000_003_000L,
                "recurrenceAnchorZoneId" to "UTC",
                "recurrenceOccurrenceIndex" to 2,
                "estimateSeconds" to 3_600L,
                "milestoneId" to MILESTONE_ID,
                "completedAtEpochMillis" to null,
                "deletedAtEpochMillis" to null,
                "revisionWallMillis" to 1_700_000_004_000L,
                "revisionLogical" to 6,
                "revisionDeviceId" to SOURCE_DEVICE_ID,
            ),
        )
        assertRow(
            table = "checklist_items",
            where = "id = ?",
            whereArgs = arrayOf(CHECKLIST_ID),
            expected = mapOf(
                "id" to CHECKLIST_ID,
                "taskId" to TASK_ID,
                "text" to "Recovered step",
                "completed" to true,
                "rank" to "a0",
            ),
        )
        assertRow(
            table = "task_dependencies",
            where = "taskId = ? AND dependsOnTaskId = ?",
            whereArgs = arrayOf(TASK_ID, PREREQUISITE_TASK_ID),
            expected = mapOf(
                "taskId" to TASK_ID,
                "dependsOnTaskId" to PREREQUISITE_TASK_ID,
                "revisionWallMillis" to 1_700_000_005_000L,
                "revisionLogical" to 7,
                "revisionDeviceId" to SOURCE_DEVICE_ID,
            ),
        )
        assertRow(
            table = "tags",
            where = "id = ?",
            whereArgs = arrayOf(TAG_ID),
            expected = mapOf(
                "id" to TAG_ID,
                "workspaceId" to WORKSPACE_ID,
                "name" to "Recovered tag",
            ),
        )
        assertRow(
            table = "task_tags",
            where = "taskId = ? AND tagId = ?",
            whereArgs = arrayOf(TASK_ID, TAG_ID),
            expected = mapOf(
                "taskId" to TASK_ID,
                "tagId" to TAG_ID,
                "present" to true,
                "revisionWallMillis" to 1_700_000_006_000L,
                "revisionLogical" to 8,
                "revisionDeviceId" to SOURCE_DEVICE_ID,
            ),
        )
        assertRow(
            table = "reminders",
            where = "id = ?",
            whereArgs = arrayOf(REMINDER_ID),
            expected = mapOf(
                "id" to REMINDER_ID,
                "taskId" to TASK_ID,
                "triggerAtEpochMillis" to 1_700_000_007_000L,
                "zoneId" to "Europe/Paris",
                "precise" to true,
            ),
        )
        assertRow(
            table = "attachments",
            where = "id = ?",
            whereArgs = arrayOf(ATTACHMENT_ID),
            expected = mapOf(
                "id" to ATTACHMENT_ID,
                "taskId" to TASK_ID,
                "displayNameCiphertext" to DISPLAY_NAME_CIPHERTEXT,
                "mimeType" to "application/pdf",
                "byteCount" to 4_096L,
                "contentHash" to CONTENT_HASH,
                "blobSetId" to null,
                "chunkCount" to 0,
                "deletedAtEpochMillis" to null,
                "revisionWallMillis" to 1_700_000_007_500L,
                "revisionLogical" to 0,
                "revisionDeviceId" to SOURCE_DEVICE_ID,
            ),
        )
        assertRow(
            table = "activity_entries",
            where = "id = ?",
            whereArgs = arrayOf(ACTIVITY_ID),
            expected = mapOf(
                "id" to ACTIVITY_ID,
                "taskId" to TASK_ID,
                "projectId" to PROJECT_ID,
                "kind" to "UPDATED",
                "bodyCiphertext" to BODY_CIPHERTEXT,
                "createdAtEpochMillis" to 1_700_000_008_000L,
            ),
        )
        assertRow(
            table = "time_entries",
            where = "id = ?",
            whereArgs = arrayOf(TIME_ENTRY_ID),
            expected = mapOf(
                "id" to TIME_ENTRY_ID,
                "taskId" to TASK_ID,
                "deviceId" to SOURCE_DEVICE_ID,
                "startedAtEpochMillis" to 1_700_000_009_000L,
                "stoppedAtEpochMillis" to 1_700_000_010_000L,
                "noteCiphertext" to NOTE_CIPHERTEXT,
            ),
        )
        assertRow(
            table = "templates",
            where = "id = ?",
            whereArgs = arrayOf(TEMPLATE_ID),
            expected = mapOf(
                "id" to TEMPLATE_ID,
                "workspaceId" to WORKSPACE_ID,
                "name" to "Recovered template",
                "encryptedPayload" to TEMPLATE_CIPHERTEXT,
                "revisionWallMillis" to 1_700_000_011_000L,
                "revisionLogical" to 9,
                "revisionDeviceId" to SOURCE_DEVICE_ID,
            ),
        )
        assertRow(
            table = "saved_views",
            where = "id = ?",
            whereArgs = arrayOf(SAVED_VIEW_ID),
            expected = mapOf(
                "id" to SAVED_VIEW_ID,
                "workspaceId" to WORKSPACE_ID,
                "name" to "Recovered view",
                "encryptedQuery" to SAVED_VIEW_CIPHERTEXT,
            ),
        )
        assertRow(
            table = "tombstones",
            where = "objectId = ? AND objectType = ?",
            whereArgs = arrayOf(TOMBSTONE_OBJECT_ID, TOMBSTONE_OBJECT_TYPE),
            expected = mapOf(
                "objectId" to TOMBSTONE_OBJECT_ID,
                "objectType" to TOMBSTONE_OBJECT_TYPE,
                "deletedAtEpochMillis" to 1_700_000_012_000L,
                "purgeAfterEpochMillis" to 1_700_000_013_000L,
                "revisionWallMillis" to 1_700_000_014_000L,
                "revisionLogical" to 10,
                "revisionDeviceId" to SOURCE_DEVICE_ID,
            ),
        )
    }

    @Test
    fun importedNullableColumnsStayNull() = runBlocking {
        importer.importInto(staging(), request(sparseSnapshot()))

        assertRow(
            table = "projects",
            where = "id = ?",
            whereArgs = arrayOf(PROJECT_ID),
            expected = mapOf(
                "id" to PROJECT_ID,
                "workspaceId" to WORKSPACE_ID,
                "name" to "Sparse project",
                "summary" to "",
                "health" to "ON_TRACK",
                "dueDate" to null,
                "completedTasks" to 0,
                "totalTasks" to 0,
                "archivedAtEpochMillis" to null,
                "revisionWallMillis" to 1L,
                "revisionLogical" to 0,
                "revisionDeviceId" to SOURCE_DEVICE_ID,
            ),
        )
        assertRow(
            table = "workflow_statuses",
            where = "id = ?",
            whereArgs = arrayOf(STATUS_ID),
            expected = mapOf(
                "id" to STATUS_ID,
                "projectId" to null,
                "name" to "Inbox",
                "semanticStatus" to "BACKLOG",
                "rank" to "a0",
                "archivedAtEpochMillis" to null,
                "revisionWallMillis" to 1L,
                "revisionLogical" to 0,
                "revisionDeviceId" to SOURCE_DEVICE_ID,
            ),
        )
        assertRow(
            table = "tasks",
            where = "id = ?",
            whereArgs = arrayOf(TASK_ID),
            expected = mapOf(
                "id" to TASK_ID,
                "workspaceId" to WORKSPACE_ID,
                "projectId" to null,
                "parentTaskId" to null,
                "statusId" to STATUS_ID,
                "semanticStatus" to "BACKLOG",
                "title" to "Sparse task",
                "descriptionCiphertext" to ByteArray(0),
                "priority" to "MEDIUM",
                "startEpochMillis" to null,
                "startZoneId" to null,
                "dueEpochMillis" to null,
                "dueZoneId" to null,
                "recurrenceFrequency" to null,
                "recurrenceInterval" to null,
                "recurrenceWeekdays" to null,
                "recurrenceCount" to null,
                "recurrenceEndDate" to null,
                "recurrenceSeriesId" to null,
                "recurrenceAnchorEpochMillis" to null,
                "recurrenceAnchorZoneId" to null,
                "recurrenceOccurrenceIndex" to null,
                "estimateSeconds" to null,
                "milestoneId" to null,
                "completedAtEpochMillis" to null,
                "deletedAtEpochMillis" to null,
                "revisionWallMillis" to 1L,
                "revisionLogical" to 0,
                "revisionDeviceId" to SOURCE_DEVICE_ID,
            ),
        )
        assertRow(
            table = "activity_entries",
            where = "id = ?",
            whereArgs = arrayOf(ACTIVITY_ID),
            expected = mapOf(
                "id" to ACTIVITY_ID,
                "taskId" to null,
                "projectId" to null,
                "kind" to "CREATED",
                "bodyCiphertext" to ByteArray(0),
                "createdAtEpochMillis" to 1L,
            ),
        )
        assertRow(
            table = "time_entries",
            where = "id = ?",
            whereArgs = arrayOf(TIME_ENTRY_ID),
            expected = mapOf(
                "id" to TIME_ENTRY_ID,
                "taskId" to TASK_ID,
                "deviceId" to SOURCE_DEVICE_ID,
                "startedAtEpochMillis" to 1L,
                "stoppedAtEpochMillis" to null,
                "noteCiphertext" to ByteArray(0),
            ),
        )
    }

    // ---------------------------------------------------------------- Step 2

    @Test
    fun replayAppliesUpsertAfterImagesInGenerationOrder() = runBlocking {
        val first = taskEntity().copy(title = "First after-image", revisionLogical = 11)
        val second = taskEntity().copy(
            title = "Second after-image",
            descriptionCiphertext = byteArrayOf(41, 42, 43),
            revisionLogical = 12,
        )
        val request = request(
            snapshot = oneRecordPerFamilySnapshot(),
            segments = listOf(
                segment(
                    listOf(
                        upsertEntry("op-1", generation = 6, sequence = 0, record = first),
                        upsertEntry("op-2", generation = 7, sequence = 0, record = second),
                    ),
                ),
            ),
            expectedGeneration = BackupGeneration(7),
        )

        importer.importInto(staging(), request)

        assertEquals(1, importedCount(staging(), BackupRecordFamily.TASK))
        assertRow(
            table = "tasks",
            where = "id = ?",
            whereArgs = arrayOf(TASK_ID),
            expected = expectedTaskRow(
                title = "Second after-image",
                descriptionCiphertext = byteArrayOf(41, 42, 43),
                revisionLogical = 12,
            ),
        )
        assertEquals(
            7L,
            staging().backupStateDao().require(VAULT_ID).currentGeneration,
        )
    }

    /**
     * A recovered vault must keep its vault row and at least one workspace, so
     * the extra workspace here is what exercises the WORKSPACE deletion path.
     */
    @Test
    fun replayDeletesEveryDeletableFamilyIdentity() = runBlocking {
        val base = oneRecordPerFamilySnapshot()
        val snapshot = base.copy(records = base.records + secondWorkspaceRecord())
        val retained = setOf(
            BackupRecordFamily.VAULT to listOf(VAULT_ID),
            BackupRecordFamily.WORKSPACE to listOf(WORKSPACE_ID),
        )
        val entries = snapshot.records
            .filterNot { (it.family to it.identity) in retained }
            .mapIndexed { index, record ->
                deleteEntry(
                    operationId = "delete-$index",
                    generation = 6 + index.toLong(),
                    sequence = 0,
                    family = record.family,
                    identity = record.identity,
                )
            }
        val request = request(
            snapshot = snapshot,
            segments = listOf(segment(entries)),
            expectedGeneration = BackupGeneration(5 + entries.size.toLong()),
        )

        importer.importInto(staging(), request)

        for (family in BackupRecordFamily.entries) {
            val expected = when (family) {
                BackupRecordFamily.VAULT, BackupRecordFamily.WORKSPACE -> 1
                else -> 0
            }
            assertEquals(
                "surviving rows for $family",
                expected,
                importedCount(staging(), family),
            )
        }
        assertRow(
            table = "workspaces",
            where = "id = ?",
            whereArgs = arrayOf(WORKSPACE_ID),
            expected = mapOf(
                "id" to WORKSPACE_ID,
                "vaultId" to VAULT_ID,
                "ownerId" to MEMBER_ID,
                "name" to "Open Tasks",
            ),
        )
    }

    /**
     * The VAULT family is deletable in replay as long as the final state still
     * describes exactly one vault, which is what exercises `deleteVault`.
     */
    @Test
    fun replayDeletesAndReUpsertsTheRecoveredVaultRow() = runBlocking {
        val restored = vaultEntity().copy(createdAtEpochMillis = 1_700_000_099_000)
        val request = request(
            snapshot = oneRecordPerFamilySnapshot(),
            segments = listOf(
                segment(
                    listOf(
                        deleteEntry(
                            operationId = "delete-vault",
                            generation = 6,
                            sequence = 0,
                            family = BackupRecordFamily.VAULT,
                            identity = listOf(VAULT_ID),
                        ),
                        recordUpsertEntry(
                            operationId = "restore-vault",
                            generation = 7,
                            sequence = 0,
                            record = restored.toBackupRecordV1(),
                        ),
                    ),
                ),
            ),
            expectedGeneration = BackupGeneration(7),
        )

        importer.importInto(staging(), request)

        assertEquals(1, importedCount(staging(), BackupRecordFamily.VAULT))
        assertRow(
            table = "vaults",
            where = "id = ?",
            whereArgs = arrayOf(VAULT_ID),
            expected = mapOf(
                "id" to VAULT_ID,
                "storageMode" to "LOCAL",
                "createdAtEpochMillis" to 1_700_000_099_000L,
                "schemaVersion" to VAULT_DATABASE_VERSION,
                "cryptoVersion" to 1,
                "minimumReaderVersion" to 1,
            ),
        )
        assertEquals(7L, staging().backupStateDao().require(VAULT_ID).currentGeneration)
    }

    @Test
    fun replayDeletingTheRecoveredVaultIsRejected() = runBlocking {
        assertRejected {
            importer.importInto(
                staging(),
                request(
                    snapshot = oneRecordPerFamilySnapshot(),
                    segments = listOf(
                        segment(
                            listOf(
                                deleteEntry(
                                    operationId = "delete-vault",
                                    generation = 6,
                                    sequence = 0,
                                    family = BackupRecordFamily.VAULT,
                                    identity = listOf(VAULT_ID),
                                ),
                            ),
                        ),
                    ),
                    expectedGeneration = BackupGeneration(6),
                ),
            )
        }
    }

    @Test
    fun replayDeletingEveryWorkspaceIsRejected() = runBlocking {
        assertRejected {
            importer.importInto(
                staging(),
                request(
                    snapshot = oneRecordPerFamilySnapshot(),
                    segments = listOf(
                        segment(
                            listOf(
                                deleteEntry(
                                    operationId = "delete-workspace",
                                    generation = 6,
                                    sequence = 0,
                                    family = BackupRecordFamily.WORKSPACE,
                                    identity = listOf(WORKSPACE_ID),
                                ),
                            ),
                        ),
                    ),
                    expectedGeneration = BackupGeneration(6),
                ),
            )
        }
    }

    @Test
    fun replayAcceptsADeleteForAnAbsentIdentity() = runBlocking {
        val request = request(
            snapshot = oneRecordPerFamilySnapshot(),
            segments = listOf(
                segment(
                    listOf(
                        deleteEntry(
                            operationId = "delete-absent",
                            generation = 6,
                            sequence = 0,
                            family = BackupRecordFamily.TASK,
                            identity = listOf("task-never-present"),
                        ),
                    ),
                ),
            ),
            expectedGeneration = BackupGeneration(6),
        )

        importer.importInto(staging(), request)

        assertEquals(1, importedCount(staging(), BackupRecordFamily.TASK))
        assertRow(
            table = "tasks",
            where = "id = ?",
            whereArgs = arrayOf(TASK_ID),
            expected = expectedTaskRow(),
        )
    }

    @Test
    fun duplicateSnapshotIdentityIsRejected() = runBlocking {
        val snapshot = oneRecordPerFamilySnapshot()
        val duplicated = snapshot.copy(
            records = snapshot.records + snapshot.records.single {
                it.family == BackupRecordFamily.TASK
            },
        )

        assertRejected { importer.importInto(staging(), request(duplicated)) }
    }

    @Test
    fun segmentGapIsRejected() = runBlocking {
        assertRejected {
            importer.importInto(
                staging(),
                request(
                    snapshot = oneRecordPerFamilySnapshot(),
                    segments = listOf(
                        segment(
                            listOf(
                                upsertEntry("op-gap", generation = 8, sequence = 0),
                            ),
                        ),
                    ),
                    expectedGeneration = BackupGeneration(8),
                ),
            )
        }
    }

    @Test
    fun segmentOverlapIsRejected() = runBlocking {
        assertRejected {
            importer.importInto(
                staging(),
                request(
                    snapshot = oneRecordPerFamilySnapshot(),
                    segments = listOf(
                        segment(listOf(upsertEntry("op-a", generation = 6, sequence = 0))),
                        segment(listOf(upsertEntry("op-b", generation = 6, sequence = 1))),
                    ),
                    expectedGeneration = BackupGeneration(6),
                ),
            )
        }
    }

    @Test
    fun reversedSegmentRangeIsRejected() = runBlocking {
        val entries = listOf(upsertEntry("op-reversed", generation = 6, sequence = 0))
        assertRejected {
            importer.importInto(
                staging(),
                request(
                    snapshot = oneRecordPerFamilySnapshot(),
                    segments = listOf(
                        BackupOperationSegmentPayloadV1(
                            vaultId = VAULT_ID,
                            firstGeneration = 7,
                            lastGeneration = 6,
                            entries = entries,
                            entryCount = entries.size,
                        ),
                    ),
                    expectedGeneration = BackupGeneration(6),
                ),
            )
        }
    }

    @Test
    fun segmentFromAnotherVaultIsRejected() = runBlocking {
        assertRejected {
            importer.importInto(
                staging(),
                request(
                    snapshot = oneRecordPerFamilySnapshot(),
                    segments = listOf(
                        segment(
                            entries = listOf(upsertEntry("op-foreign", generation = 6, sequence = 0)),
                            vaultId = "vault-elsewhere",
                        ),
                    ),
                    expectedGeneration = BackupGeneration(6),
                ),
            )
        }
    }

    @Test
    fun segmentInventoryThatMissesTheExpectedGenerationIsRejected() = runBlocking {
        assertRejected {
            importer.importInto(
                staging(),
                request(
                    snapshot = oneRecordPerFamilySnapshot(),
                    segments = listOf(
                        segment(listOf(upsertEntry("op-short", generation = 6, sequence = 0))),
                    ),
                    expectedGeneration = BackupGeneration(9),
                ),
            )
        }
    }

    @Test
    fun duplicateOperationIdentifierAcrossSegmentsIsRejected() = runBlocking {
        assertRejected {
            importer.importInto(
                staging(),
                request(
                    snapshot = oneRecordPerFamilySnapshot(),
                    segments = listOf(
                        segment(listOf(upsertEntry("op-shared", generation = 6, sequence = 0))),
                        segment(listOf(upsertEntry("op-shared", generation = 7, sequence = 0))),
                    ),
                    expectedGeneration = BackupGeneration(7),
                ),
            )
        }
    }

    @Test
    fun snapshotVaultIdentityMismatchIsRejected() = runBlocking {
        val snapshot = oneRecordPerFamilySnapshot().copy(vaultId = "vault-elsewhere")

        assertRejected { importer.importInto(staging(), request(snapshot)) }
    }

    @Test
    fun invalidTombstoneRecordIsRejected() = runBlocking {
        val snapshot = replacing(
            oneRecordPerFamilySnapshot(),
            TombstoneEntity(
                objectId = TOMBSTONE_OBJECT_ID,
                objectType = TOMBSTONE_OBJECT_TYPE,
                deletedAtEpochMillis = 20,
                purgeAfterEpochMillis = 10,
                revisionWallMillis = 1,
                revisionLogical = 0,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1(),
        )

        assertRejected { importer.importInto(staging(), request(snapshot)) }
    }

    @Test
    fun futureSchemaVersionIsRejected() = runBlocking {
        val snapshot = replacing(
            oneRecordPerFamilySnapshot(),
            vaultEntity().copy(schemaVersion = VAULT_DATABASE_VERSION + 1).toBackupRecordV1(),
        )

        assertRejected { importer.importInto(staging(), request(snapshot)) }
    }

    /**
     * A vault that migrated through Room v7->v8 before it was captured
     * carries row marker 8. Recovery must accept it, not reject it as
     * unreadable, and normalize it to the database version the recovered
     * vault now lives in.
     */
    @Test
    fun migratedSchemaVersionIsAcceptedAndNormalizedToTheDatabaseVersion() = runBlocking {
        val snapshot = replacing(
            oneRecordPerFamilySnapshot(),
            vaultEntity().copy(schemaVersion = 8).toBackupRecordV1(),
        )

        importer.importInto(staging(), request(snapshot))

        assertRow(
            table = "vaults",
            where = "id = ?",
            whereArgs = arrayOf(VAULT_ID),
            expected = mapOf(
                "id" to VAULT_ID,
                "storageMode" to "LOCAL",
                "createdAtEpochMillis" to 1_700_000_000_000L,
                "schemaVersion" to VAULT_DATABASE_VERSION,
                "cryptoVersion" to 1,
                "minimumReaderVersion" to 1,
            ),
        )
    }

    @Test
    fun futureReaderVersionIsRejected() = runBlocking {
        val snapshot = replacing(
            oneRecordPerFamilySnapshot(),
            vaultEntity().copy(minimumReaderVersion = 2).toBackupRecordV1(),
        )

        assertRejected { importer.importInto(staging(), request(snapshot)) }
    }

    @Test
    fun snapshotBeyondTheRecordBoundIsRejected() = runBlocking {
        val record = vaultEntity().toBackupRecordV1()
        val snapshot = oneRecordPerFamilySnapshot().copy(
            records = List(MAX_RECORDS_PER_SNAPSHOT + 1) { record },
        )

        assertRejected { importer.importInto(staging(), request(snapshot)) }
    }

    @Test
    fun segmentBeyondTheOperationBoundIsRejected() = runBlocking {
        val template = upsertEntry("op-template", generation = 6, sequence = 0)
        val entries = List(MAX_OPERATIONS_PER_SEGMENT + 1) { index ->
            template.copy(operationId = "op-$index", sequence = index)
        }

        assertRejected {
            importer.importInto(
                staging(),
                request(
                    snapshot = oneRecordPerFamilySnapshot(),
                    segments = listOf(segment(entries)),
                    expectedGeneration = BackupGeneration(6),
                ),
            )
        }
    }

    @Test
    fun segmentEntryThatContradictsItsMutationIsRejected() = runBlocking {
        val entry = upsertEntry("op-mismatch", generation = 6, sequence = 0)
            .copy(objectId = "task-somewhere-else")

        assertRejected {
            importer.importInto(
                staging(),
                request(
                    snapshot = oneRecordPerFamilySnapshot(),
                    segments = listOf(segment(listOf(entry))),
                    expectedGeneration = BackupGeneration(6),
                ),
            )
        }
    }

    @Test
    fun importingIntoANonEmptyStagingDatabaseIsRejected() = runBlocking {
        importer.importInto(staging(), requestWithOneRecordPerFamily())

        assertThrows(IllegalStateException::class.java) {
            runBlocking { importer.importInto(staging(), requestWithOneRecordPerFamily()) }
        }
        Unit
    }

    @Test
    fun importLeavesJournalOutboxAndRemoteTablesEmpty() = runBlocking {
        importer.importInto(staging(), requestWithOneRecordPerFamily())

        listOf(
            "backup_journal",
            "sync_operations",
            "remote_backup_config",
            "remote_backup_object",
            "remote_backup_operation",
        ).forEach { table ->
            assertEquals("$table must stay empty", 0, tableCount(table))
        }
    }

    @Test
    fun importInitializesOnlyFreshLocalOperationalState() = runBlocking {
        importer.importInto(
            staging(),
            request(
                snapshot = oneRecordPerFamilySnapshot(coveredGeneration = 5),
                expectedGeneration = BackupGeneration(5),
            ),
        )

        val state = staging().backupStateDao().require(VAULT_ID)
        assertEquals(5L, state.currentGeneration)
        assertNull(state.lastVerifiedSnapshotGeneration)
        assertNull(state.currentBaseObjectId)
        assertNull(state.previousBaseObjectId)
        assertNull(state.latestVerifiedSegmentGeneration)
        assertNull(state.portablePackageGeneration)
        assertNull(state.portablePackageBytes)
        assertNull(state.portablePackageProducedAtEpochMillis)
        assertEquals("NOT_PREPARED", state.packageState)
        assertTrue("the stored envelope must be recorded as ready", state.recoveryEnvelopeReady)
        assertNull(state.failureCategory)
        assertNull(state.legacyOutboxCoveredAtGeneration)
        assertNull(state.snapshotCreatedAtEpochMillis)
        assertEquals(1, tableCount("backup_state"))

        val stored = staging().vaultRecoveryEnvelopeDao().get(VAULT_ID)
        assertNotNull("the recovery envelope was not stored", stored)
        assertEquals(1, tableCount("vault_recovery_envelope"))
        assertArrayEquals(RECOVERY_SALT, stored!!.salt)
        assertArrayEquals(RECOVERY_NONCE, stored.nonce)
        assertArrayEquals(RECOVERY_WRAPPED_KEYSET, stored.wrappedKeyset)
    }

    // ---------------------------------------------------------------- Step 7

    @Test
    fun verifiedStagingReopensAndServesTheRecoveredWorkspace() = runBlocking {
        val request = request(completeVaultSnapshot(), expectedGeneration = BackupGeneration(12))
        importer.importInto(staging(), request)
        closeStaging()
        installContentKey()

        val verified = withTimeout(TIMEOUT_MILLIS) {
            verifier().verify(
                slot = slot,
                expectedVaultId = VaultId(VAULT_ID),
                expectedGeneration = BackupGeneration(12),
                expectedCapture = request.expectedCapture(),
            )
        }

        assertEquals(slot, verified.slot)
        assertEquals(VaultId(VAULT_ID), verified.vaultId)
        assertEquals(BackupGeneration(12), verified.recoveredGeneration)

        val runtime = LocalVaultRepositoryFactory.openRuntime(context, slot, crypto)
        try {
            val workspace = withTimeout(TIMEOUT_MILLIS) { runtime.repository.currentWorkspace() }
            assertEquals(
                listOf(COMPLETE_PREREQUISITE_ID, COMPLETE_TASK_ID).sorted(),
                workspace.tasks.map { it.id.value }.sorted(),
            )
            assertEquals(listOf(COMPLETE_PROJECT_ID), workspace.projects.map { it.id.value })
            assertEquals(listOf("Recovered tag"), workspace.tags.map { it.name })
        } finally {
            runtime.close()
        }
    }

    @Test
    fun verificationRejectsAGenerationThatDoesNotMatchTheStagedVault() = runBlocking {
        val request = request(completeVaultSnapshot(), expectedGeneration = BackupGeneration(12))
        importer.importInto(staging(), request)
        closeStaging()
        installContentKey()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                verifier().verify(
                    slot = slot,
                    expectedVaultId = VaultId(VAULT_ID),
                    expectedGeneration = BackupGeneration(13),
                    expectedCapture = request.expectedCapture()
                        .copy(generation = BackupGeneration(13)),
                )
            }
        }
        Unit
    }

    @Test
    fun verificationRejectsADanglingRelation() = runBlocking {
        val snapshot = completeVaultSnapshot().let { complete ->
            complete.copy(
                records = complete.records + ChecklistItemEntity(
                    id = "check-orphan",
                    taskId = "task-that-does-not-exist",
                    text = "Orphan step",
                    completed = false,
                    rank = "z9",
                ).toBackupRecordV1(),
            )
        }
        val request = request(snapshot, expectedGeneration = BackupGeneration(12))
        importer.importInto(staging(), request)
        closeStaging()
        installContentKey()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                verifier().verify(
                    slot = slot,
                    expectedVaultId = VaultId(VAULT_ID),
                    expectedGeneration = BackupGeneration(12),
                    expectedCapture = request.expectedCapture(),
                )
            }
        }
        Unit
    }

    /**
     * The staged record set is read through vault-scoped capture queries, so a
     * row bound to another vault is invisible to them and to every relation
     * check; only an unscoped total can exclude it.
     */
    @Test
    fun verificationRejectsAStagedRowOutsideTheRecoveredVault() = runBlocking {
        val request = request(completeVaultSnapshot(), expectedGeneration = BackupGeneration(12))
        importer.importInto(staging(), request)
        staging().openHelper.writableDatabase.execSQL(
            """
            INSERT INTO vaults (
                id, storageMode, createdAtEpochMillis, schemaVersion, cryptoVersion,
                minimumReaderVersion
            ) VALUES ('$FOREIGN_VAULT_ID', 'LOCAL', 1, 7, 1, 1)
            """.trimIndent(),
        )
        closeStaging()
        installContentKey()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                verifier().verify(
                    slot = slot,
                    expectedVaultId = VaultId(VAULT_ID),
                    expectedGeneration = BackupGeneration(12),
                    expectedCapture = request.expectedCapture(),
                )
            }
        }
        Unit
    }

    /**
     * Every reference resolves and every record is individually valid here, yet
     * the task's semantic status contradicts the workflow status it points at —
     * a state Stage 2 would refuse to capture.
     */
    @Test
    fun verificationRejectsARecordSetThatIsNotAValidVaultState() = runBlocking {
        val contradiction = completeTask(
            id = COMPLETE_PREREQUISITE_ID,
            semantic = SemanticStatus.PLANNED,
            milestoneId = null,
        ).copy(semanticStatus = SemanticStatus.BLOCKED.name).toBackupRecordV1()
        val request = request(
            replacing(completeVaultSnapshot(), contradiction),
            expectedGeneration = BackupGeneration(12),
        )
        importer.importInto(staging(), request)
        closeStaging()
        installContentKey()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                verifier().verify(
                    slot = slot,
                    expectedVaultId = VaultId(VAULT_ID),
                    expectedGeneration = BackupGeneration(12),
                    expectedCapture = request.expectedCapture(),
                )
            }
        }
        Unit
    }

    /**
     * Opening a normal repository runs the local retention purge, so a recovery
     * old enough to hold expired trash legitimately moves past the record set
     * that was just verified. That must be accounted for, not rejected.
     */
    @Test
    fun verificationAcceptsAndReportsTheRetentionPurgeTheRuntimePerforms() = runBlocking {
        val request = request(expiredTrashSnapshot(), expectedGeneration = BackupGeneration(12))
        importer.importInto(staging(), request)
        closeStaging()
        installContentKey()

        val verified = withTimeout(TIMEOUT_MILLIS) {
            verifier().verify(
                slot = slot,
                expectedVaultId = VaultId(VAULT_ID),
                expectedGeneration = BackupGeneration(12),
                expectedCapture = request.expectedCapture(),
            )
        }

        assertEquals(BackupGeneration(12), verified.recoveredGeneration)
        assertEquals(BackupGeneration(13), verified.activationGeneration)
        assertEquals(1, verified.retentionPurge.purgedTaskCount)
        assertEquals(EXPIRED_TRASH_RECORDS, verified.retentionPurge.removedRecordCount)
        assertEquals(EXPIRED_TRASH_RECORDS + 2, verified.retentionPurge.journalEntryCount)

        reopenStaging()
        assertEquals(
            verified.activationGeneration.value,
            staging().backupStateDao().require(VAULT_ID).currentGeneration,
        )
        assertEquals(3, importedCount(staging(), BackupRecordFamily.TASK))
        assertEquals(
            null,
            checkNotNull(staging().taskDao().getById(EXPIRED_CHILD_TASK_ID)).parentTaskId,
        )
        assertEquals(EXPIRED_TRASH_RECORDS + 2, tableCount("backup_journal"))
        assertEquals(EXPIRED_DELETED_AT, tombstoneDeletedAt(EXPIRED_TASK_ID))
    }

    /**
     * The retention purge also retires any blob-bearing attachment it
     * removes; verification must accept that extra write as attributed
     * drift instead of rejecting it, and Task 7 (encrypted vault import)
     * reuses this same verifier for its own staged opens.
     */
    @Test
    fun verificationAcceptsAndReportsTheRetentionPurgeOfABlobBearingAttachment() = runBlocking {
        val request = request(
            expiredTrashSnapshot(
                attachmentBlobSetId = EXPIRED_ATTACHMENT_BLOB_SET_ID,
                attachmentChunkCount = 2,
            ),
            expectedGeneration = BackupGeneration(12),
        )
        importer.importInto(staging(), request)
        closeStaging()
        installContentKey()

        val verified = withTimeout(TIMEOUT_MILLIS) {
            verifier().verify(
                slot = slot,
                expectedVaultId = VaultId(VAULT_ID),
                expectedGeneration = BackupGeneration(12),
                expectedCapture = request.expectedCapture(),
            )
        }

        assertEquals(BackupGeneration(12), verified.recoveredGeneration)
        assertEquals(BackupGeneration(13), verified.activationGeneration)
        assertEquals(1, verified.retentionPurge.purgedTaskCount)
        assertEquals(EXPIRED_TRASH_RECORDS, verified.retentionPurge.removedRecordCount)
        // One more written record than the blob-less case: the retired blob set.
        assertEquals(EXPIRED_TRASH_RECORDS + 3, verified.retentionPurge.journalEntryCount)

        reopenStaging()
        assertEquals(
            verified.activationGeneration.value,
            staging().backupStateDao().require(VAULT_ID).currentGeneration,
        )
        assertEquals(1, importedCount(staging(), BackupRecordFamily.RETIRED_BLOB_SET))
        val retired = checkNotNull(
            staging().workspaceDao().getRetiredBlobSet(EXPIRED_ATTACHMENT_BLOB_SET_ID),
        ) { "expected the purged attachment's blob set to be retired" }
        assertEquals(2, retired.chunkCount)
        assertEquals(0, retired.revisionLogical)
        assertEquals(retired.retiredAtEpochMillis, retired.revisionWallMillis)
    }

    /**
     * The same purge, judged by a clock under which that task was not yet
     * eligible: drift verification cannot attribute to expired trash is drift
     * that must fail closed.
     */
    @Test
    fun verificationRejectsDriftItCannotAttributeToExpiredTrash() = runBlocking {
        val request = request(expiredTrashSnapshot(), expectedGeneration = BackupGeneration(12))
        importer.importInto(staging(), request)
        closeStaging()
        installContentKey()

        val verifier = DefaultStagedVaultVerifier(
            context = context,
            crypto = crypto,
            now = { Instant.ofEpochMilli(EXPIRED_DELETED_AT) },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                verifier.verify(
                    slot = slot,
                    expectedVaultId = VaultId(VAULT_ID),
                    expectedGeneration = BackupGeneration(12),
                    expectedCapture = request.expectedCapture(),
                )
            }
        }
        Unit
    }

    @Test
    fun verificationRejectsStagingThatHoldsOperationalRows() = runBlocking {
        val request = request(completeVaultSnapshot(), expectedGeneration = BackupGeneration(12))
        importer.importInto(staging(), request)
        staging().openHelper.writableDatabase.execSQL(
            """
            INSERT INTO sync_operations (
                id, deviceId, objectId, objectType, encryptedPayload,
                revisionWallMillis, revisionLogical, uploadedAtEpochMillis
            ) VALUES ('legacy-1', 'device-source', 'task-1', 'task', x'00', 1, 0, NULL)
            """.trimIndent(),
        )
        closeStaging()
        installContentKey()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                verifier().verify(
                    slot = slot,
                    expectedVaultId = VaultId(VAULT_ID),
                    expectedGeneration = BackupGeneration(12),
                    expectedCapture = request.expectedCapture(),
                )
            }
        }
        Unit
    }

    // ------------------------------------------------------------- Fixtures

    private fun staging(): VaultDatabase = checkNotNull(database) { "staging is closed" }

    private fun closeStaging() {
        database?.close()
        database = null
    }

    /** Reads the staged slot again once verification has released it. */
    private fun reopenStaging() {
        closeStaging()
        database = LocalVaultRepositoryFactory.openStagingDatabase(context, slot)
    }

    private fun verifier(): StagedVaultVerifier = DefaultStagedVaultVerifier(context, crypto)

    private fun installContentKey() {
        AndroidVaultContentKeyStore(
            context = context,
            crypto = crypto,
            storageNamespace = LocalVaultRepositoryFactory.storageNamespace(slot),
        ).getOrCreate(VaultId(VAULT_ID)).close()
    }

    private fun requestWithOneRecordPerFamily(): RecoveryImportRequest =
        request(oneRecordPerFamilySnapshot())

    private fun request(
        snapshot: BackupSnapshotPayloadV1,
        segments: List<BackupOperationSegmentPayloadV1> = emptyList(),
        expectedGeneration: BackupGeneration = BackupGeneration(snapshot.coveredGeneration),
    ): RecoveryImportRequest = RecoveryImportRequest(
        snapshot = snapshot,
        segments = segments,
        recoveryEnvelope = recoveryEnvelope(),
        expectedGeneration = expectedGeneration,
    )

    private fun recoveryEnvelope(): VaultKeyEnvelope = VaultKeyEnvelope(
        formatVersion = 1,
        kdf = Argon2Metadata(
            salt = RECOVERY_SALT.copyOf(),
            memoryKiB = 65_536,
            iterations = 3,
            parallelism = 1,
        ),
        nonce = RECOVERY_NONCE.copyOf(),
        wrappedKeyset = RECOVERY_WRAPPED_KEYSET.copyOf(),
    )

    private fun oneRecordPerFamilySnapshot(
        coveredGeneration: Long = 5,
    ): BackupSnapshotPayloadV1 = BackupSnapshotPayloadV1(
        vaultId = VAULT_ID,
        coveredGeneration = coveredGeneration,
        records = listOf(
            vaultEntity().toBackupRecordV1(),
            WorkspaceEntity(
                id = WORKSPACE_ID,
                vaultId = VAULT_ID,
                ownerId = MEMBER_ID,
                name = "Open Tasks",
            ).toBackupRecordV1(),
            MemberEntity(id = MEMBER_ID, displayName = "You").toBackupRecordV1(),
            ProjectEntity(
                id = PROJECT_ID,
                workspaceId = WORKSPACE_ID,
                name = "Recovered project",
                summary = "Recovered summary",
                health = "AT_RISK",
                dueDate = "2026-09-01",
                completedTasks = 1,
                totalTasks = 4,
                archivedAtEpochMillis = 1_700_000_000_100,
                revisionWallMillis = 1_700_000_000_200,
                revisionLogical = 3,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1(),
            WorkflowStatusEntity(
                id = STATUS_ID,
                projectId = PROJECT_ID,
                name = "In progress",
                semanticStatus = "STARTED",
                rank = "a0",
                archivedAtEpochMillis = 1_700_000_000_300,
                revisionWallMillis = 1_700_000_000_400,
                revisionLogical = 4,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1(),
            MilestoneEntity(
                id = MILESTONE_ID,
                projectId = PROJECT_ID,
                name = "Recovered milestone",
                dueDate = "2026-10-02",
                completedAtEpochMillis = 1_700_000_000_500,
                revisionWallMillis = 1_700_000_000_600,
                revisionLogical = 5,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1(),
            taskEntity().toBackupRecordV1(),
            ChecklistItemEntity(
                id = CHECKLIST_ID,
                taskId = TASK_ID,
                text = "Recovered step",
                completed = true,
                rank = "a0",
            ).toBackupRecordV1(),
            TaskDependencyEntity(
                taskId = TASK_ID,
                dependsOnTaskId = PREREQUISITE_TASK_ID,
                revisionWallMillis = 1_700_000_005_000,
                revisionLogical = 7,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1(),
            TagEntity(
                id = TAG_ID,
                workspaceId = WORKSPACE_ID,
                name = "Recovered tag",
            ).toBackupRecordV1(),
            TaskTagEntity(
                taskId = TASK_ID,
                tagId = TAG_ID,
                present = true,
                revisionWallMillis = 1_700_000_006_000,
                revisionLogical = 8,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1(),
            ReminderEntity(
                id = REMINDER_ID,
                taskId = TASK_ID,
                triggerAtEpochMillis = 1_700_000_007_000,
                zoneId = "Europe/Paris",
                precise = true,
            ).toBackupRecordV1(),
            AttachmentEntity(
                id = ATTACHMENT_ID,
                taskId = TASK_ID,
                displayNameCiphertext = DISPLAY_NAME_CIPHERTEXT.copyOf(),
                mimeType = "application/pdf",
                byteCount = 4_096,
                contentHash = CONTENT_HASH,
                blobSetId = null,
                chunkCount = 0,
                deletedAtEpochMillis = null,
                revisionWallMillis = 1_700_000_007_500,
                revisionLogical = 0,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1(),
            ActivityEntryEntity(
                id = ACTIVITY_ID,
                taskId = TASK_ID,
                projectId = PROJECT_ID,
                kind = "UPDATED",
                bodyCiphertext = BODY_CIPHERTEXT.copyOf(),
                createdAtEpochMillis = 1_700_000_008_000,
            ).toBackupRecordV1(),
            NoteEntity(
                id = NOTE_ID,
                taskId = TASK_ID,
                projectId = null,
                bodyCiphertext = NOTE_BODY_CIPHERTEXT.copyOf(),
                createdAtEpochMillis = 1_700_000_008_500,
                editedAtEpochMillis = 1_700_000_008_900,
                revisionWallMillis = 1_700_000_008_950,
                revisionLogical = 2,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1(),
            RetiredBlobSetEntity(
                blobSetId = RETIRED_BLOB_SET_ID,
                chunkCount = 3,
                retiredAtEpochMillis = 1_700_000_008_600,
                revisionWallMillis = 1_700_000_008_650,
                revisionLogical = 0,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1(),
            TimeEntryEntity(
                id = TIME_ENTRY_ID,
                taskId = TASK_ID,
                deviceId = SOURCE_DEVICE_ID,
                startedAtEpochMillis = 1_700_000_009_000,
                stoppedAtEpochMillis = 1_700_000_010_000,
                noteCiphertext = NOTE_CIPHERTEXT.copyOf(),
            ).toBackupRecordV1(),
            TemplateEntity(
                id = TEMPLATE_ID,
                workspaceId = WORKSPACE_ID,
                name = "Recovered template",
                encryptedPayload = TEMPLATE_CIPHERTEXT.copyOf(),
                revisionWallMillis = 1_700_000_011_000,
                revisionLogical = 9,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1(),
            SavedViewEntity(
                id = SAVED_VIEW_ID,
                workspaceId = WORKSPACE_ID,
                name = "Recovered view",
                encryptedQuery = SAVED_VIEW_CIPHERTEXT.copyOf(),
            ).toBackupRecordV1(),
            TombstoneEntity(
                objectId = TOMBSTONE_OBJECT_ID,
                objectType = TOMBSTONE_OBJECT_TYPE,
                deletedAtEpochMillis = 1_700_000_012_000,
                purgeAfterEpochMillis = 1_700_000_013_000,
                revisionWallMillis = 1_700_000_014_000,
                revisionLogical = 10,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1(),
        ),
    )

    private fun secondWorkspaceRecord(): BackupRecordV1 = WorkspaceEntity(
        id = SECOND_WORKSPACE_ID,
        vaultId = VAULT_ID,
        ownerId = MEMBER_ID,
        name = "Second workspace",
    ).toBackupRecordV1()

    private fun sparseSnapshot(): BackupSnapshotPayloadV1 = BackupSnapshotPayloadV1(
        vaultId = VAULT_ID,
        coveredGeneration = 0,
        records = listOf(
            vaultEntity().toBackupRecordV1(),
            WorkspaceEntity(
                id = WORKSPACE_ID,
                vaultId = VAULT_ID,
                ownerId = MEMBER_ID,
                name = "Open Tasks",
            ).toBackupRecordV1(),
            MemberEntity(id = MEMBER_ID, displayName = "You").toBackupRecordV1(),
            ProjectEntity(
                id = PROJECT_ID,
                workspaceId = WORKSPACE_ID,
                name = "Sparse project",
                summary = "",
                health = "ON_TRACK",
                dueDate = null,
                completedTasks = 0,
                totalTasks = 0,
                archivedAtEpochMillis = null,
                revisionWallMillis = 1,
                revisionLogical = 0,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1(),
            WorkflowStatusEntity(
                id = STATUS_ID,
                projectId = null,
                name = "Inbox",
                semanticStatus = "BACKLOG",
                rank = "a0",
                archivedAtEpochMillis = null,
                revisionWallMillis = 1,
                revisionLogical = 0,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1(),
            TaskEntity(
                id = TASK_ID,
                workspaceId = WORKSPACE_ID,
                projectId = null,
                parentTaskId = null,
                statusId = STATUS_ID,
                semanticStatus = "BACKLOG",
                title = "Sparse task",
                descriptionCiphertext = ByteArray(0),
                priority = "MEDIUM",
                startEpochMillis = null,
                startZoneId = null,
                dueEpochMillis = null,
                dueZoneId = null,
                recurrenceFrequency = null,
                recurrenceInterval = null,
                recurrenceWeekdays = null,
                recurrenceCount = null,
                recurrenceEndDate = null,
                recurrenceSeriesId = null,
                recurrenceAnchorEpochMillis = null,
                recurrenceAnchorZoneId = null,
                recurrenceOccurrenceIndex = null,
                estimateSeconds = null,
                milestoneId = null,
                completedAtEpochMillis = null,
                deletedAtEpochMillis = null,
                revisionWallMillis = 1,
                revisionLogical = 0,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1(),
            ActivityEntryEntity(
                id = ACTIVITY_ID,
                taskId = null,
                projectId = null,
                kind = "CREATED",
                bodyCiphertext = ByteArray(0),
                createdAtEpochMillis = 1,
            ).toBackupRecordV1(),
            TimeEntryEntity(
                id = TIME_ENTRY_ID,
                taskId = TASK_ID,
                deviceId = SOURCE_DEVICE_ID,
                startedAtEpochMillis = 1,
                stoppedAtEpochMillis = null,
                noteCiphertext = ByteArray(0),
            ).toBackupRecordV1(),
        ),
    )

    private fun completeVaultSnapshot(): BackupSnapshotPayloadV1 {
        val records = mutableListOf<BackupRecordV1>()
        records += vaultEntity().toBackupRecordV1()
        records += MemberEntity(MEMBER_ID, "You").toBackupRecordV1()
        records += WorkspaceEntity(
            id = WORKSPACE_ID,
            vaultId = VAULT_ID,
            ownerId = MEMBER_ID,
            name = "Open Tasks",
        ).toBackupRecordV1()
        records += ProjectEntity(
            id = COMPLETE_PROJECT_ID,
            workspaceId = WORKSPACE_ID,
            name = "Recovered project",
            summary = "",
            health = "ON_TRACK",
            dueDate = null,
            completedTasks = 0,
            totalTasks = 2,
            archivedAtEpochMillis = null,
            revisionWallMillis = 1,
            revisionLogical = 0,
            revisionDeviceId = SOURCE_DEVICE_ID,
        ).toBackupRecordV1()
        SemanticStatus.entries.forEachIndexed { index, semantic ->
            records += WorkflowStatusEntity(
                id = projectStatusId(semantic),
                projectId = COMPLETE_PROJECT_ID,
                name = semantic.name,
                semanticStatus = semantic.name,
                rank = "a$index",
                archivedAtEpochMillis = null,
                revisionWallMillis = 1,
                revisionLogical = 0,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1()
            records += WorkflowStatusEntity(
                id = "status-inbox-${semantic.name.lowercase()}",
                projectId = null,
                name = semantic.name,
                semanticStatus = semantic.name,
                rank = "b$index",
                archivedAtEpochMillis = null,
                revisionWallMillis = 1,
                revisionLogical = 0,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1()
        }
        records += MilestoneEntity(
            id = COMPLETE_MILESTONE_ID,
            projectId = COMPLETE_PROJECT_ID,
            name = "Recovered milestone",
            dueDate = null,
            completedAtEpochMillis = null,
            revisionWallMillis = 1,
            revisionLogical = 0,
            revisionDeviceId = SOURCE_DEVICE_ID,
        ).toBackupRecordV1()
        records += completeTask(
            id = COMPLETE_TASK_ID,
            semantic = SemanticStatus.STARTED,
            milestoneId = COMPLETE_MILESTONE_ID,
        ).toBackupRecordV1()
        records += completeTask(
            id = COMPLETE_PREREQUISITE_ID,
            semantic = SemanticStatus.PLANNED,
            milestoneId = null,
        ).toBackupRecordV1()
        records += ChecklistItemEntity(
            id = "check-recovered",
            taskId = COMPLETE_TASK_ID,
            text = "Recovered step",
            completed = false,
            rank = "a0",
        ).toBackupRecordV1()
        records += TaskDependencyEntity(
            taskId = COMPLETE_TASK_ID,
            dependsOnTaskId = COMPLETE_PREREQUISITE_ID,
            revisionWallMillis = 1,
            revisionLogical = 0,
            revisionDeviceId = SOURCE_DEVICE_ID,
        ).toBackupRecordV1()
        records += TagEntity(TAG_ID, WORKSPACE_ID, "Recovered tag").toBackupRecordV1()
        records += TaskTagEntity(
            taskId = COMPLETE_TASK_ID,
            tagId = TAG_ID,
            present = true,
            revisionWallMillis = 1,
            revisionLogical = 0,
            revisionDeviceId = SOURCE_DEVICE_ID,
        ).toBackupRecordV1()
        records += ReminderEntity(
            id = "reminder:$COMPLETE_TASK_ID",
            taskId = COMPLETE_TASK_ID,
            triggerAtEpochMillis = 1_700_000_007_000,
            zoneId = "UTC",
            precise = false,
        ).toBackupRecordV1()
        records += AttachmentEntity(
            id = ATTACHMENT_ID,
            taskId = COMPLETE_TASK_ID,
            displayNameCiphertext = DISPLAY_NAME_CIPHERTEXT.copyOf(),
            mimeType = "application/pdf",
            byteCount = 4_096,
            contentHash = CONTENT_HASH,
            blobSetId = null,
            chunkCount = 0,
            deletedAtEpochMillis = null,
            revisionWallMillis = 1,
            revisionLogical = 0,
            revisionDeviceId = SOURCE_DEVICE_ID,
        ).toBackupRecordV1()
        records += ActivityEntryEntity(
            id = ACTIVITY_ID,
            taskId = COMPLETE_TASK_ID,
            projectId = COMPLETE_PROJECT_ID,
            kind = "UPDATED",
            bodyCiphertext = BODY_CIPHERTEXT.copyOf(),
            createdAtEpochMillis = 1_700_000_008_000,
        ).toBackupRecordV1()
        records += TimeEntryEntity(
            id = TIME_ENTRY_ID,
            taskId = COMPLETE_TASK_ID,
            deviceId = SOURCE_DEVICE_ID,
            startedAtEpochMillis = 1_700_000_009_000,
            stoppedAtEpochMillis = 1_700_000_010_000,
            noteCiphertext = NOTE_CIPHERTEXT.copyOf(),
        ).toBackupRecordV1()
        records += TemplateEntity(
            id = TEMPLATE_ID,
            workspaceId = WORKSPACE_ID,
            name = "Recovered template",
            encryptedPayload = TEMPLATE_CIPHERTEXT.copyOf(),
            revisionWallMillis = 1,
            revisionLogical = 0,
            revisionDeviceId = SOURCE_DEVICE_ID,
        ).toBackupRecordV1()
        records += SavedViewEntity(
            id = SAVED_VIEW_ID,
            workspaceId = WORKSPACE_ID,
            name = "Recovered view",
            encryptedQuery = SAVED_VIEW_CIPHERTEXT.copyOf(),
        ).toBackupRecordV1()
        records += TombstoneEntity(
            objectId = TOMBSTONE_OBJECT_ID,
            objectType = TOMBSTONE_OBJECT_TYPE,
            deletedAtEpochMillis = 1_700_000_012_000,
            purgeAfterEpochMillis = 1_700_000_013_000,
            revisionWallMillis = 1_700_000_014_000,
            revisionLogical = 10,
            revisionDeviceId = SOURCE_DEVICE_ID,
        ).toBackupRecordV1()
        return BackupSnapshotPayloadV1(
            vaultId = VAULT_ID,
            coveredGeneration = 12,
            records = records,
        )
    }

    /**
     * A complete vault whose Bin holds one task deleted long enough ago that a
     * normal repository purges it the moment it opens, together with every
     * child record that purge cascades through.
     */
    private fun expiredTrashSnapshot(
        attachmentBlobSetId: String? = null,
        attachmentChunkCount: Int = 0,
    ): BackupSnapshotPayloadV1 {
        val complete = completeVaultSnapshot()
        return complete.copy(
            records = complete.records + listOf(
                completeTask(
                    id = EXPIRED_TASK_ID,
                    semantic = SemanticStatus.PLANNED,
                    milestoneId = null,
                ).copy(
                    title = "Expired task",
                    deletedAtEpochMillis = EXPIRED_DELETED_AT,
                ).toBackupRecordV1(),
                completeTask(
                    id = EXPIRED_CHILD_TASK_ID,
                    semantic = SemanticStatus.PLANNED,
                    milestoneId = null,
                ).copy(
                    title = "Surviving child",
                    parentTaskId = EXPIRED_TASK_ID,
                ).toBackupRecordV1(),
                ChecklistItemEntity(
                    id = "check-expired",
                    taskId = EXPIRED_TASK_ID,
                    text = "Expired step",
                    completed = false,
                    rank = "a0",
                ).toBackupRecordV1(),
                TaskDependencyEntity(
                    taskId = EXPIRED_TASK_ID,
                    dependsOnTaskId = COMPLETE_PREREQUISITE_ID,
                    revisionWallMillis = 1,
                    revisionLogical = 0,
                    revisionDeviceId = SOURCE_DEVICE_ID,
                ).toBackupRecordV1(),
                TaskTagEntity(
                    taskId = EXPIRED_TASK_ID,
                    tagId = TAG_ID,
                    present = true,
                    revisionWallMillis = 1,
                    revisionLogical = 0,
                    revisionDeviceId = SOURCE_DEVICE_ID,
                ).toBackupRecordV1(),
                ReminderEntity(
                    id = "reminder:$EXPIRED_TASK_ID",
                    taskId = EXPIRED_TASK_ID,
                    triggerAtEpochMillis = 1_700_000_007_000,
                    zoneId = "UTC",
                    precise = false,
                ).toBackupRecordV1(),
                AttachmentEntity(
                    id = "attachment-expired",
                    taskId = EXPIRED_TASK_ID,
                    displayNameCiphertext = DISPLAY_NAME_CIPHERTEXT.copyOf(),
                    mimeType = "application/pdf",
                    byteCount = 8,
                    contentHash = CONTENT_HASH,
                    blobSetId = attachmentBlobSetId,
                    chunkCount = attachmentChunkCount,
                    deletedAtEpochMillis = null,
                    revisionWallMillis = 1,
                    revisionLogical = 0,
                    revisionDeviceId = SOURCE_DEVICE_ID,
                ).toBackupRecordV1(),
                ActivityEntryEntity(
                    id = "activity-expired",
                    taskId = EXPIRED_TASK_ID,
                    projectId = COMPLETE_PROJECT_ID,
                    kind = "UPDATED",
                    bodyCiphertext = BODY_CIPHERTEXT.copyOf(),
                    createdAtEpochMillis = 1_700_000_008_000,
                ).toBackupRecordV1(),
                TimeEntryEntity(
                    id = "time-expired",
                    taskId = EXPIRED_TASK_ID,
                    deviceId = SOURCE_DEVICE_ID,
                    startedAtEpochMillis = 1_700_000_009_000,
                    stoppedAtEpochMillis = 1_700_000_010_000,
                    noteCiphertext = NOTE_CIPHERTEXT.copyOf(),
                ).toBackupRecordV1(),
            ),
        )
    }

    private fun completeTask(
        id: String,
        semantic: SemanticStatus,
        milestoneId: String?,
    ): TaskEntity = TaskEntity(
        id = id,
        workspaceId = WORKSPACE_ID,
        projectId = COMPLETE_PROJECT_ID,
        parentTaskId = null,
        statusId = projectStatusId(semantic),
        semanticStatus = semantic.name,
        title = "Task $id",
        descriptionCiphertext = ByteArray(0),
        priority = "MEDIUM",
        startEpochMillis = null,
        startZoneId = null,
        dueEpochMillis = null,
        dueZoneId = null,
        recurrenceFrequency = null,
        recurrenceInterval = null,
        recurrenceWeekdays = null,
        recurrenceCount = null,
        recurrenceEndDate = null,
        recurrenceSeriesId = null,
        recurrenceAnchorEpochMillis = null,
        recurrenceAnchorZoneId = null,
        recurrenceOccurrenceIndex = null,
        estimateSeconds = null,
        milestoneId = milestoneId,
        completedAtEpochMillis = null,
        deletedAtEpochMillis = null,
        revisionWallMillis = 1,
        revisionLogical = 0,
        revisionDeviceId = SOURCE_DEVICE_ID,
    )

    private fun projectStatusId(semantic: SemanticStatus): String =
        "status-project-${semantic.name.lowercase()}"

    private fun vaultEntity(): VaultEntity = VaultEntity(
        id = VAULT_ID,
        storageMode = "LOCAL",
        createdAtEpochMillis = 1_700_000_000_000,
        schemaVersion = 6,
        cryptoVersion = 1,
        minimumReaderVersion = 1,
    )

    private fun taskEntity(): TaskEntity = TaskEntity(
        id = TASK_ID,
        workspaceId = WORKSPACE_ID,
        projectId = PROJECT_ID,
        parentTaskId = PARENT_TASK_ID,
        statusId = STATUS_ID,
        semanticStatus = "STARTED",
        title = "Recovered task",
        descriptionCiphertext = DESCRIPTION_CIPHERTEXT.copyOf(),
        priority = "URGENT",
        startEpochMillis = 1_700_000_001_000,
        startZoneId = "Asia/Bangkok",
        dueEpochMillis = 1_700_000_002_000,
        dueZoneId = "UTC",
        recurrenceFrequency = "WEEKLY",
        recurrenceInterval = 2,
        recurrenceWeekdays = "MONDAY,WEDNESDAY",
        recurrenceCount = 5,
        recurrenceEndDate = null,
        recurrenceSeriesId = "series-recovered",
        recurrenceAnchorEpochMillis = 1_700_000_003_000,
        recurrenceAnchorZoneId = "UTC",
        recurrenceOccurrenceIndex = 2,
        estimateSeconds = 3_600,
        milestoneId = MILESTONE_ID,
        completedAtEpochMillis = null,
        deletedAtEpochMillis = null,
        revisionWallMillis = 1_700_000_004_000,
        revisionLogical = 6,
        revisionDeviceId = SOURCE_DEVICE_ID,
    )

    private fun expectedTaskRow(
        title: String = "Recovered task",
        descriptionCiphertext: ByteArray = DESCRIPTION_CIPHERTEXT,
        revisionLogical: Int = 6,
    ): Map<String, Any?> = mapOf(
        "id" to TASK_ID,
        "workspaceId" to WORKSPACE_ID,
        "projectId" to PROJECT_ID,
        "parentTaskId" to PARENT_TASK_ID,
        "statusId" to STATUS_ID,
        "semanticStatus" to "STARTED",
        "title" to title,
        "descriptionCiphertext" to descriptionCiphertext,
        "priority" to "URGENT",
        "startEpochMillis" to 1_700_000_001_000L,
        "startZoneId" to "Asia/Bangkok",
        "dueEpochMillis" to 1_700_000_002_000L,
        "dueZoneId" to "UTC",
        "recurrenceFrequency" to "WEEKLY",
        "recurrenceInterval" to 2,
        "recurrenceWeekdays" to "MONDAY,WEDNESDAY",
        "recurrenceCount" to 5,
        "recurrenceEndDate" to null,
        "recurrenceSeriesId" to "series-recovered",
        "recurrenceAnchorEpochMillis" to 1_700_000_003_000L,
        "recurrenceAnchorZoneId" to "UTC",
        "recurrenceOccurrenceIndex" to 2,
        "estimateSeconds" to 3_600L,
        "milestoneId" to MILESTONE_ID,
        "completedAtEpochMillis" to null,
        "deletedAtEpochMillis" to null,
        "revisionWallMillis" to 1_700_000_004_000L,
        "revisionLogical" to revisionLogical,
        "revisionDeviceId" to SOURCE_DEVICE_ID,
    )

    private fun replacing(
        snapshot: BackupSnapshotPayloadV1,
        record: BackupRecordV1,
    ): BackupSnapshotPayloadV1 = snapshot.copy(
        records = snapshot.records.map { existing ->
            if (existing.family == record.family && existing.identity == record.identity) {
                record
            } else {
                existing
            }
        },
    )

    private fun segment(
        entries: List<BackupSegmentEntryV1>,
        vaultId: String = VAULT_ID,
    ): BackupOperationSegmentPayloadV1 = BackupOperationSegmentPayloadV1(
        vaultId = vaultId,
        firstGeneration = entries.first().generation,
        lastGeneration = entries.last().generation,
        entries = entries,
        entryCount = entries.size,
    )

    private fun upsertEntry(
        operationId: String,
        generation: Long,
        sequence: Int,
        record: TaskEntity = taskEntity(),
    ): BackupSegmentEntryV1 = recordUpsertEntry(
        operationId = operationId,
        generation = generation,
        sequence = sequence,
        record = record.toBackupRecordV1(),
    )

    private fun recordUpsertEntry(
        operationId: String,
        generation: Long,
        sequence: Int,
        record: BackupRecordV1,
    ): BackupSegmentEntryV1 = entry(
        operationId = operationId,
        generation = generation,
        sequence = sequence,
        family = record.family,
        identity = record.identity,
        payload = BackupMutationCodec.encode(
            BackupMutationPayloadV1(
                mutationKind = BackupMutationKind.UPSERT,
                record = record,
                deletedFamily = null,
                deletedIdentity = null,
            ),
        ),
    )

    private fun deleteEntry(
        operationId: String,
        generation: Long,
        sequence: Int,
        family: BackupRecordFamily,
        identity: List<String>,
    ): BackupSegmentEntryV1 = entry(
        operationId = operationId,
        generation = generation,
        sequence = sequence,
        family = family,
        identity = identity,
        payload = BackupMutationCodec.encode(
            BackupMutationPayloadV1(
                mutationKind = BackupMutationKind.DELETE,
                record = null,
                deletedFamily = family,
                deletedIdentity = identity,
            ),
        ),
    )

    private fun entry(
        operationId: String,
        generation: Long,
        sequence: Int,
        family: BackupRecordFamily,
        identity: List<String>,
        payload: ByteArray,
    ): BackupSegmentEntryV1 = try {
        BackupSegmentEntryV1(
            operationId = operationId,
            generation = generation,
            sequence = sequence,
            objectId = identity.journalObjectId(),
            objectType = family.name,
            revisionWallMillis = 1,
            revisionLogical = 0,
            sourceDeviceId = SOURCE_DEVICE_ID,
            payloadBase64 = Base64.getEncoder().withoutPadding().encodeToString(payload),
        )
    } finally {
        payload.fill(0)
    }

    private fun List<String>.journalObjectId(): String =
        if (size == 1) single() else joinToString(separator = "|") { "${it.length}:$it" }

    // ------------------------------------------------------------ Assertions

    private suspend fun assertRejected(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertNotNull("the import was accepted", failure)
        assertTrue(
            "unexpected failure ${failure!!::class.java.simpleName}",
            failure is IllegalArgumentException || failure is IllegalStateException,
        )
        assertStagingIsEmpty()
    }

    private fun assertStagingIsEmpty() {
        for (family in BackupRecordFamily.entries) {
            assertEquals(
                "rejected import left $family rows",
                0,
                importedCount(staging(), family),
            )
        }
        listOf("backup_state", "vault_recovery_envelope", "backup_journal").forEach { table ->
            assertEquals("rejected import left $table rows", 0, tableCount(table))
        }
    }

    private fun importedCount(
        database: VaultDatabase,
        family: BackupRecordFamily,
    ): Int = tableCount(family.tableName(), database)

    private fun tombstoneDeletedAt(objectId: String): Long? = staging()
        .openHelper
        .readableDatabase
        .query(
            "SELECT deletedAtEpochMillis FROM tombstones WHERE objectId = ? AND objectType = ?",
            arrayOf(objectId, TOMBSTONE_OBJECT_TYPE),
        )
        .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    private fun tableCount(
        table: String,
        database: VaultDatabase = staging(),
    ): Int = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM $table")
        .use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun assertRow(
        table: String,
        where: String,
        whereArgs: Array<String>,
        expected: Map<String, Any?>,
    ) {
        staging().openHelper.readableDatabase
            .query("SELECT * FROM $table WHERE $where", whereArgs)
            .use { cursor ->
                assertEquals("row count in $table", 1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "asserted columns in $table",
                    cursor.columnNames.toSortedSet(),
                    expected.keys.toSortedSet(),
                )
                expected.forEach { (column, value) ->
                    val index = cursor.getColumnIndexOrThrow(column)
                    val label = "$table.$column"
                    when (value) {
                        null -> assertTrue(label, cursor.isNull(index))
                        is ByteArray -> assertArrayEquals(label, value, cursor.getBlob(index))
                        is Boolean ->
                            assertEquals(label, if (value) 1L else 0L, cursor.getLong(index))
                        is Long -> assertEquals(label, value, cursor.getLong(index))
                        is Int -> assertEquals(label, value.toLong(), cursor.getLong(index))
                        is String -> assertEquals(label, value, cursor.getString(index))
                        else -> fail("unsupported expectation for $label")
                    }
                }
            }
    }

    private fun BackupRecordFamily.tableName(): String = when (this) {
        BackupRecordFamily.VAULT -> "vaults"
        BackupRecordFamily.WORKSPACE -> "workspaces"
        BackupRecordFamily.MEMBER -> "members"
        BackupRecordFamily.PROJECT -> "projects"
        BackupRecordFamily.WORKFLOW_STATUS -> "workflow_statuses"
        BackupRecordFamily.MILESTONE -> "milestones"
        BackupRecordFamily.TASK -> "tasks"
        BackupRecordFamily.CHECKLIST_ITEM -> "checklist_items"
        BackupRecordFamily.TASK_DEPENDENCY -> "task_dependencies"
        BackupRecordFamily.TAG -> "tags"
        BackupRecordFamily.TASK_TAG -> "task_tags"
        BackupRecordFamily.REMINDER -> "reminders"
        BackupRecordFamily.ATTACHMENT -> "attachments"
        BackupRecordFamily.ACTIVITY_ENTRY -> "activity_entries"
        BackupRecordFamily.TIME_ENTRY -> "time_entries"
        BackupRecordFamily.TEMPLATE -> "templates"
        BackupRecordFamily.SAVED_VIEW -> "saved_views"
        BackupRecordFamily.NOTE -> "notes"
        BackupRecordFamily.RETIRED_BLOB_SET -> "retired_blob_sets"
        BackupRecordFamily.TOMBSTONE -> "tombstones"
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val MAX_RECORDS_PER_SNAPSHOT = 100_000
        const val MAX_OPERATIONS_PER_SEGMENT = 10_000
        const val VAULT_ID = "vault-primary"
        const val WORKSPACE_ID = "workspace-primary"
        const val SECOND_WORKSPACE_ID = "workspace-secondary"
        const val MEMBER_ID = "member-owner"
        const val PROJECT_ID = "project-recovered"
        const val STATUS_ID = "status-recovered"
        const val MILESTONE_ID = "milestone-recovered"
        const val TASK_ID = "task-recovered"
        const val PARENT_TASK_ID = "task-parent"
        const val PREREQUISITE_TASK_ID = "task-prerequisite"
        const val CHECKLIST_ID = "check-recovered"
        const val TAG_ID = "tag-recovered"
        const val REMINDER_ID = "reminder:task-recovered"
        const val ATTACHMENT_ID = "attachment-recovered"
        const val ACTIVITY_ID = "activity-recovered"
        const val NOTE_ID = "note-recovered"
        const val RETIRED_BLOB_SET_ID = "blob-set-recovered"
        const val TIME_ENTRY_ID = "time-recovered"
        const val TEMPLATE_ID = "template-recovered"
        const val SAVED_VIEW_ID = "view-recovered"
        const val TOMBSTONE_OBJECT_ID = "purged-task"
        const val TOMBSTONE_OBJECT_TYPE = "task"
        const val SOURCE_DEVICE_ID = "device-source"
        const val CONTENT_HASH = "sha256-recovered"
        const val COMPLETE_PROJECT_ID = "project-complete"
        const val COMPLETE_MILESTONE_ID = "milestone-complete"
        const val COMPLETE_TASK_ID = "task-complete"
        const val COMPLETE_PREREQUISITE_ID = "task-complete-prerequisite"
        const val EXPIRED_TASK_ID = "task-expired"
        const val EXPIRED_CHILD_TASK_ID = "task-expired-child"
        const val EXPIRED_ATTACHMENT_BLOB_SET_ID = "blob-set-expired"

        /** The task itself plus every child record `purgeTask` cascades through. */
        const val EXPIRED_TRASH_RECORDS = 8

        /** Sorts after the recovered vault, so it cannot be read as its identity. */
        const val FOREIGN_VAULT_ID = "vault-zzz-elsewhere"
        val EXPIRED_DELETED_AT: Long =
            System.currentTimeMillis() - Duration.ofDays(60).toMillis()
        val DESCRIPTION_CIPHERTEXT = byteArrayOf(1, 2, 3, 4, 5)
        val DISPLAY_NAME_CIPHERTEXT = byteArrayOf(11, 12, 13)
        val BODY_CIPHERTEXT = byteArrayOf(21, 22)
        val NOTE_CIPHERTEXT = byteArrayOf(31)
        val NOTE_BODY_CIPHERTEXT = byteArrayOf(41, 42, 43)
        val TEMPLATE_CIPHERTEXT = byteArrayOf(51, 52, 53, 54)
        val SAVED_VIEW_CIPHERTEXT = byteArrayOf(61, 62)
        val RECOVERY_SALT = ByteArray(16) { (it + 1).toByte() }
        val RECOVERY_NONCE = ByteArray(12) { (it + 21).toByte() }
        val RECOVERY_WRAPPED_KEYSET = ByteArray(48) { (it + 41).toByte() }
    }
}
