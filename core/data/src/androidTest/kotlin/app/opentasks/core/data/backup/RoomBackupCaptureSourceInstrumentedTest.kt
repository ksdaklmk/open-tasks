package app.opentasks.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.data.db.ActivityEntryEntity
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.model.VaultId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBackupCaptureSourceInstrumentedTest {
    private lateinit var database: VaultDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun multiVaultCaptureRejectsAmbiguousInboxWorkflowOwnership() {
        runBlocking {
            seedVaultGraph(scope = "alpha", vaultId = "vault-alpha", generation = 7)
            seedVaultGraph(scope = "beta", vaultId = "vault-beta", generation = 11)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    RoomBackupCaptureSource(
                        database = database,
                        vaultId = VaultId("vault-alpha"),
                    ).capture()
                }
            }
        }
    }

    @Test
    fun captureIncludesCompleteInboxWorkflowWhenInboxHasNoTasks() = runBlocking {
        seedVaultGraph(scope = "alpha", vaultId = "vault-alpha", generation = 7)
        seedRemainingInboxStatuses(scope = "alpha")
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM tasks WHERE id = ?",
            arrayOf("inbox-task-alpha"),
        )

        val capture = RoomBackupCaptureSource(
            database = database,
            vaultId = VaultId("vault-alpha"),
        ).capture()

        assertEquals(
            SemanticFixture.entries.map(Enum<*>::name).toSet(),
            capture.inboxSemanticStatuses(),
        )
        BackupSnapshotCodec.encode(BackupSnapshotCodec.fromCapture(capture)).fill(0)
    }

    @Test
    fun captureIncludesBacklogWhenInboxTaskReferencesOnlyStarted() = runBlocking {
        seedVaultGraph(scope = "alpha", vaultId = "vault-alpha", generation = 7)
        seedRemainingInboxStatuses(scope = "alpha")

        val capture = RoomBackupCaptureSource(
            database = database,
            vaultId = VaultId("vault-alpha"),
        ).capture()

        assertEquals(
            SemanticFixture.entries.map(Enum<*>::name).toSet(),
            capture.inboxSemanticStatuses(),
        )
        BackupSnapshotCodec.encode(BackupSnapshotCodec.fromCapture(capture)).fill(0)
    }

    @Test
    fun captureRejectsInboxWorkflowWithAmbiguousWorkspaceOwnership() {
        runBlocking {
            seedVaultGraph(scope = "alpha", vaultId = "vault-alpha", generation = 7)
            seedRemainingInboxStatuses(scope = "alpha")
            insert(
                "workspaces",
                "id" to "workspace-alpha-second",
                "vaultId" to "vault-alpha",
                "ownerId" to "member-alpha",
                "name" to "Second workspace",
            )

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    RoomBackupCaptureSource(database, VaultId("vault-alpha")).capture()
                }
            }
        }
    }

    @Test
    fun aRecoveredVaultWithNoJournalStillCapturesEveryRecord() = runBlocking {
        seedVaultGraph(scope = "alpha", vaultId = "vault-alpha", generation = 7)
        seedRemainingInboxStatuses(scope = "alpha")
        // A recovered vault carries no journal by design, so the evidence that
        // normally attributes tombstones and relationless activity entries is
        // gone. One vault owns every row in its own database.
        database.openHelper.writableDatabase.execSQL("DELETE FROM backup_journal")

        val capture = RoomBackupCaptureSource(database, VaultId("vault-alpha")).capture()

        val identities = capture.identitySets()
        expectedIdentities("alpha").forEach { (family, expected) ->
            assertTrue("$family", identities.getValue(family).containsAll(expected))
        }
        BackupSnapshotCodec.encode(BackupSnapshotCodec.fromCapture(capture)).fill(0)
    }

    @Test
    fun aSoleVaultAttributesRowsAJournalWasNeverWrittenFor() = runBlocking {
        seedVaultGraph(scope = "alpha", vaultId = "vault-alpha", generation = 7)
        insertActivity(id = "activity-recovered", taskId = null, projectId = null, scope = "alpha")
        insert(
            "tombstones",
            "objectId" to "tombstone-recovered",
            "objectType" to "task",
            "deletedAtEpochMillis" to 1L,
            "purgeAfterEpochMillis" to 2L,
            "revisionWallMillis" to 3L,
            "revisionLogical" to 0,
            "revisionDeviceId" to "device-alpha",
        )

        val capture = RoomBackupCaptureSource(database, VaultId("vault-alpha")).capture()

        val identities = capture.identitySets()
        assertTrue(
            identities.getValue(BackupRecordFamily.ACTIVITY_ENTRY)
                .contains(listOf("activity-recovered")),
        )
        assertTrue(
            identities.getValue(BackupRecordFamily.TOMBSTONE)
                .contains(listOf("tombstone-recovered", "task")),
        )
    }

    @Test
    fun relationlessActivityWithoutJournalOwnershipIsRejected() {
        runBlocking {
            seedVaultGraph(scope = "alpha", vaultId = "vault-alpha", generation = 7)
        }
        // A second vault is what makes the orphan genuinely unattributable: a
        // database holding one vault owns every row in it.
        insertBareVault("vault-beta")
        insertActivity(
            id = "activity-without-owner",
            taskId = null,
            projectId = null,
            scope = "orphan",
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                RoomBackupCaptureSource(database, VaultId("vault-alpha")).capture()
            }
        }
    }

    @Test
    fun tombstoneWithoutJournalOwnershipIsRejected() {
        runBlocking {
            seedVaultGraph(scope = "alpha", vaultId = "vault-alpha", generation = 7)
        }
        insertBareVault("vault-beta")
        insert(
            "tombstones",
            "objectId" to "tombstone-without-owner",
            "objectType" to "task",
            "deletedAtEpochMillis" to 1L,
            "purgeAfterEpochMillis" to 2L,
            "revisionWallMillis" to 3L,
            "revisionLogical" to 0,
            "revisionDeviceId" to "device-orphan",
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                RoomBackupCaptureSource(database, VaultId("vault-alpha")).capture()
            }
        }
    }

    @Test
    fun relationlessActivityWithJournalOwnershipInTwoVaultsIsRejected() {
        runBlocking {
            seedVaultGraph(scope = "alpha", vaultId = "vault-alpha", generation = 7)
            seedVaultGraph(scope = "beta", vaultId = "vault-beta", generation = 11)
            insertJournalEvidence(
                operationId = "operation-activity-alpha-conflict",
                vaultId = "vault-beta",
                generation = 11,
                sequence = 3,
                objectId = "activity-unlinked-alpha",
                objectType = BackupRecordFamily.ACTIVITY_ENTRY.name,
                payload = activityUpsertPayload("activity-unlinked-alpha"),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                RoomBackupCaptureSource(database, VaultId("vault-alpha")).capture()
            }
        }
    }

    @Test
    fun tombstoneWithJournalOwnershipInTwoVaultsIsRejected() {
        runBlocking {
            seedVaultGraph(scope = "alpha", vaultId = "vault-alpha", generation = 7)
            seedVaultGraph(scope = "beta", vaultId = "vault-beta", generation = 11)
            insertJournalEvidence(
                operationId = "operation-tombstone-alpha-conflict",
                vaultId = "vault-beta",
                generation = 11,
                sequence = 3,
                objectId = "purged-task-alpha",
                objectType = BackupRecordFamily.TASK.name,
                payload = taskDeletePayload("purged-task-alpha"),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                RoomBackupCaptureSource(database, VaultId("vault-alpha")).capture()
            }
        }
    }

    @Test
    fun dependencyWhoseEndpointsBelongToDifferentVaultsIsRejected() {
        runBlocking {
            seedVaultGraph(scope = "alpha", vaultId = "vault-alpha", generation = 7)
            seedVaultGraph(scope = "beta", vaultId = "vault-beta", generation = 11)
        }
        insertDependency(
            taskId = "task-alpha",
            dependsOnTaskId = "prerequisite-beta",
            scope = "cross-vault",
        )

        listOf("vault-alpha", "vault-beta").forEach { vaultId ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    RoomBackupCaptureSource(database, VaultId(vaultId)).capture()
                }
            }
        }
    }

    @Test
    fun taskTagWhoseEndpointsBelongToDifferentVaultsIsRejected() {
        runBlocking {
            seedVaultGraph(scope = "alpha", vaultId = "vault-alpha", generation = 7)
            seedVaultGraph(scope = "beta", vaultId = "vault-beta", generation = 11)
        }
        insertTaskTag(
            taskId = "task-alpha",
            tagId = "tag-beta",
            scope = "cross-vault",
        )

        listOf("vault-alpha", "vault-beta").forEach { vaultId ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    RoomBackupCaptureSource(database, VaultId(vaultId)).capture()
                }
            }
        }
    }

    @Test
    fun automationRuleWithoutWorkspaceIsRejected() = runBlocking {
        seedVaultGraph(scope = "alpha", vaultId = "vault-alpha", generation = 7)
        insert(
            "automation_rules",
            "id" to "rule-without-workspace",
            "workspaceId" to "workspace-missing",
            "type" to "MY_DAY_AUTO_REMOVE",
            "enabled" to 1,
            "projectId" to null,
            "statusId" to null,
            "tagId" to null,
            "dueInDays" to null,
            "thresholdDays" to null,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                RoomBackupCaptureSource(database, VaultId("vault-alpha")).capture()
            }
        }
        Unit
    }

    @Test
    fun unrelatedCrossVaultRelationsStillFailClosedWithAmbiguousInboxOwnership() {
        runBlocking {
            seedVaultGraph(scope = "alpha", vaultId = "vault-alpha", generation = 7)
            seedVaultGraph(scope = "beta", vaultId = "vault-beta", generation = 11)
            seedVaultGraph(scope = "gamma", vaultId = "vault-gamma", generation = 13)
            insertDependency(
                taskId = "task-alpha",
                dependsOnTaskId = "prerequisite-beta",
                scope = "cross-vault",
            )
            insertTaskTag(
                taskId = "task-alpha",
                tagId = "tag-beta",
                scope = "cross-vault",
            )

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    RoomBackupCaptureSource(
                        database = database,
                        vaultId = VaultId("vault-gamma"),
                    ).capture()
                }
            }
        }
    }

    private suspend fun seedVaultGraph(
        scope: String,
        vaultId: String,
        generation: Long,
    ) {
        val workspaceId = "workspace-$scope"
        val memberId = "member-$scope"
        val projectId = "project-$scope"
        val taskId = "task-$scope"
        val prerequisiteId = "prerequisite-$scope"
        val inboxTaskId = "inbox-task-$scope"
        val projectStatusIds = SemanticFixture.entries.associateWith { semantic ->
            "status-${semantic.name.lowercase()}-$scope"
        }
        val globalStatusId = "status-global-$scope"
        val unlinkedActivityId = "activity-unlinked-$scope"
        val tombstoneObjectId = "purged-task-$scope"
        val legacyTombstoneObjectId = "legacy-purged-task-$scope"

        insert(
            "vaults",
            "id" to vaultId,
            "storageMode" to "LOCAL",
            "createdAtEpochMillis" to 1L,
            "schemaVersion" to 6,
            "cryptoVersion" to 1,
            "minimumReaderVersion" to 1,
        )
        insert(
            "members",
            "id" to memberId,
            "displayName" to "Member $scope",
        )
        insert(
            "workspaces",
            "id" to workspaceId,
            "vaultId" to vaultId,
            "ownerId" to memberId,
            "name" to "Workspace $scope",
        )
        insert(
            "projects",
            "id" to projectId,
            "workspaceId" to workspaceId,
            "name" to "Project $scope",
            "summary" to "",
            "health" to "ON_TRACK",
            "dueDate" to null,
            "completedTasks" to 0,
            "totalTasks" to 3,
            "archivedAtEpochMillis" to null,
            "revisionWallMillis" to 1L,
            "revisionLogical" to 0,
            "revisionDeviceId" to "device-$scope",
        )
        SemanticFixture.entries.forEachIndexed { index, semantic ->
            insert(
                "workflow_statuses",
                "id" to projectStatusIds.getValue(semantic),
                "projectId" to projectId,
                "name" to semantic.name,
                "semanticStatus" to semantic.name,
                "rank" to "a$index",
                "archivedAtEpochMillis" to null,
                "revisionWallMillis" to 1L,
                "revisionLogical" to 0,
                "revisionDeviceId" to "device-$scope",
            )
        }
        insert(
            "workflow_statuses",
            "id" to globalStatusId,
            "projectId" to null,
            "name" to "Global $scope",
            "semanticStatus" to "STARTED",
            "rank" to "global-$scope",
            "archivedAtEpochMillis" to null,
            "revisionWallMillis" to 1L,
            "revisionLogical" to 0,
            "revisionDeviceId" to "device-$scope",
        )
        insert(
            "milestones",
            "id" to "milestone-$scope",
            "projectId" to projectId,
            "name" to "Milestone $scope",
            "dueDate" to null,
            "completedAtEpochMillis" to null,
            "revisionWallMillis" to 1L,
            "revisionLogical" to 0,
            "revisionDeviceId" to "device-$scope",
        )
        insertTask(
            id = taskId,
            workspaceId = workspaceId,
            projectId = projectId,
            statusId = projectStatusIds.getValue(SemanticFixture.STARTED),
            semanticStatus = "STARTED",
            milestoneId = "milestone-$scope",
            scope = scope,
        )
        insertTask(
            id = prerequisiteId,
            workspaceId = workspaceId,
            projectId = projectId,
            statusId = projectStatusIds.getValue(SemanticFixture.PLANNED),
            semanticStatus = "PLANNED",
            milestoneId = null,
            scope = scope,
        )
        insertTask(
            id = inboxTaskId,
            workspaceId = workspaceId,
            projectId = null,
            statusId = globalStatusId,
            semanticStatus = "STARTED",
            milestoneId = null,
            scope = scope,
        )
        insert(
            "checklist_items",
            "id" to "check-$scope",
            "taskId" to taskId,
            "text" to "Check $scope",
            "completed" to 0,
            "rank" to "a0",
        )
        insertDependency(taskId, prerequisiteId, scope)
        insert(
            "tags",
            "id" to "tag-$scope",
            "workspaceId" to workspaceId,
            "name" to "Tag $scope",
        )
        insertTaskTag(taskId, "tag-$scope", scope)
        insert(
            "reminders",
            "id" to "reminder:$taskId",
            "taskId" to taskId,
            "triggerAtEpochMillis" to 10L,
            "zoneId" to "UTC",
            "precise" to 0,
        )
        insert(
            "attachments",
            "id" to "attachment-$scope",
            "taskId" to taskId,
            "displayNameCiphertext" to byteArrayOf(1),
            "mimeType" to "text/plain",
            "byteCount" to 1L,
            "contentHash" to "hash-$scope",
            "blobSetId" to null,
            "chunkCount" to 0,
            "deletedAtEpochMillis" to null,
            "revisionWallMillis" to 0L,
            "revisionLogical" to 0,
            "revisionDeviceId" to "device-$scope",
        )
        insertActivity("activity-task-$scope", taskId, projectId, scope)
        insertActivity("activity-project-$scope", null, projectId, scope)
        insertActivity(unlinkedActivityId, null, null, scope)
        insert(
            "time_entries",
            "id" to "time-$scope",
            "taskId" to taskId,
            "deviceId" to "device-$scope",
            "startedAtEpochMillis" to 1L,
            "stoppedAtEpochMillis" to 2L,
            "noteCiphertext" to byteArrayOf(2),
        )
        insert(
            "templates",
            "id" to "template-$scope",
            "workspaceId" to workspaceId,
            "name" to "Template $scope",
            "encryptedPayload" to byteArrayOf(3),
            "revisionWallMillis" to 1L,
            "revisionLogical" to 0,
            "revisionDeviceId" to "device-$scope",
        )
        insert(
            "saved_views",
            "id" to "view-$scope",
            "workspaceId" to workspaceId,
            "name" to "View $scope",
            "encryptedQuery" to byteArrayOf(4),
        )
        insert(
            "tombstones",
            "objectId" to tombstoneObjectId,
            "objectType" to "task",
            "deletedAtEpochMillis" to 1L,
            "purgeAfterEpochMillis" to 2L,
            "revisionWallMillis" to 3L,
            "revisionLogical" to 0,
            "revisionDeviceId" to "device-$scope",
        )
        insert(
            "tombstones",
            "objectId" to legacyTombstoneObjectId,
            "objectType" to "task",
            "deletedAtEpochMillis" to 1L,
            "purgeAfterEpochMillis" to 2L,
            "revisionWallMillis" to 3L,
            "revisionLogical" to 0,
            "revisionDeviceId" to "device-$scope",
        )

        insertJournalEvidence(
            operationId = "operation-activity-$scope",
            vaultId = vaultId,
            generation = generation,
            sequence = 0,
            objectId = unlinkedActivityId,
            objectType = BackupRecordFamily.ACTIVITY_ENTRY.name,
            payload = activityUpsertPayload(unlinkedActivityId),
        )
        insertJournalEvidence(
            operationId = "operation-tombstone-$scope",
            vaultId = vaultId,
            generation = generation,
            sequence = 1,
            objectId = tombstoneObjectId,
            objectType = BackupRecordFamily.TASK.name,
            payload = taskDeletePayload(tombstoneObjectId),
        )
        insertJournalEvidence(
            operationId = "operation-legacy-tombstone-$scope",
            vaultId = vaultId,
            generation = generation,
            sequence = 2,
            objectId = legacyTombstoneObjectId,
            objectType = "task.purge",
            payload = taskDeletePayload(legacyTombstoneObjectId),
        )
        database.backupStateDao().insert(
            defaultBackupState(vaultId).copy(currentGeneration = generation),
        )
    }

    private fun seedRemainingInboxStatuses(scope: String) {
        SemanticFixture.entries
            .filterNot { it == SemanticFixture.STARTED }
            .forEachIndexed { index, semantic ->
                insert(
                    "workflow_statuses",
                    "id" to "status-inbox-${semantic.name.lowercase()}-$scope",
                    "projectId" to null,
                    "name" to "Inbox ${semantic.name}",
                    "semanticStatus" to semantic.name,
                    "rank" to "inbox-$index-$scope",
                    "archivedAtEpochMillis" to null,
                    "revisionWallMillis" to 1L,
                    "revisionLogical" to 0,
                    "revisionDeviceId" to "device-$scope",
                )
            }
    }

    private fun activityUpsertPayload(id: String): ByteArray =
        BackupMutationCodec.encode(
            BackupMutationPayloadV1(
                mutationKind = BackupMutationKind.UPSERT,
                record = ActivityEntryEntity(
                    id = id,
                    taskId = null,
                    projectId = null,
                    kind = "UPDATED",
                    bodyCiphertext = byteArrayOf(5),
                    createdAtEpochMillis = 1,
                ).toBackupRecordV1(),
                deletedFamily = null,
                deletedIdentity = null,
            ),
        )

    private fun taskDeletePayload(id: String): ByteArray =
        BackupMutationCodec.encode(
            BackupMutationPayloadV1(
                mutationKind = BackupMutationKind.DELETE,
                record = null,
                deletedFamily = BackupRecordFamily.TASK,
                deletedIdentity = listOf(id),
            ),
        )

    private fun insertDependency(
        taskId: String,
        dependsOnTaskId: String,
        scope: String,
    ) {
        insert(
            "task_dependencies",
            "taskId" to taskId,
            "dependsOnTaskId" to dependsOnTaskId,
            "revisionWallMillis" to 1L,
            "revisionLogical" to 0,
            "revisionDeviceId" to "device-$scope",
        )
    }

    private fun insertTaskTag(
        taskId: String,
        tagId: String,
        scope: String,
    ) {
        insert(
            "task_tags",
            "taskId" to taskId,
            "tagId" to tagId,
            "present" to 1,
            "revisionWallMillis" to 1L,
            "revisionLogical" to 0,
            "revisionDeviceId" to "device-$scope",
        )
    }

    private fun insertTask(
        id: String,
        workspaceId: String,
        projectId: String?,
        statusId: String,
        semanticStatus: String,
        milestoneId: String?,
        scope: String,
    ) {
        insert(
            "tasks",
            "id" to id,
            "workspaceId" to workspaceId,
            "projectId" to projectId,
            "parentTaskId" to null,
            "statusId" to statusId,
            "semanticStatus" to semanticStatus,
            "title" to "Task $id",
            "descriptionCiphertext" to byteArrayOf(0),
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
            "milestoneId" to milestoneId,
            "completedAtEpochMillis" to null,
            "deletedAtEpochMillis" to null,
            "revisionWallMillis" to 1L,
            "revisionLogical" to 0,
            "revisionDeviceId" to "device-$scope",
        )
    }

    private fun insertActivity(
        id: String,
        taskId: String?,
        projectId: String?,
        scope: String,
    ) {
        insert(
            "activity_entries",
            "id" to id,
            "taskId" to taskId,
            "projectId" to projectId,
            "kind" to "UPDATED",
            "bodyCiphertext" to byteArrayOf(5),
            "createdAtEpochMillis" to 1L,
        )
    }

    /** A vault row alone, which makes single-vault attribution ambiguous. */
    private fun insertBareVault(vaultId: String) {
        insert(
            "vaults",
            "id" to vaultId,
            "storageMode" to "LOCAL",
            "createdAtEpochMillis" to 1L,
            "schemaVersion" to 6,
            "cryptoVersion" to 1,
            "minimumReaderVersion" to 1,
        )
    }

    private fun insertJournalEvidence(
        operationId: String,
        vaultId: String,
        generation: Long,
        sequence: Int,
        objectId: String,
        objectType: String,
        payload: ByteArray,
    ) {
        try {
            insert(
                "backup_journal",
                "operationId" to operationId,
                "vaultId" to vaultId,
                "generation" to generation,
                "sequence" to sequence,
                "payloadFormatVersion" to 1,
                "mutationKind" to BackupMutationCodec.decode(payload).mutationKind.name,
                "objectId" to objectId,
                "objectType" to objectType,
                "payload" to payload,
                "revisionWallMillis" to 3L,
                "revisionLogical" to 0,
                "sourceDeviceId" to "device-$vaultId",
            )
        } finally {
            payload.fill(0)
        }
    }

    private fun insert(
        table: String,
        vararg values: Pair<String, Any?>,
    ) {
        val columns = values.joinToString(separator = ",") { it.first }
        val placeholders = values.joinToString(separator = ",") { "?" }
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO $table ($columns) VALUES ($placeholders)",
            values.map(Pair<String, Any?>::second).toTypedArray(),
        )
    }

    private fun expectedIdentities(scope: String): Map<BackupRecordFamily, Set<List<String>>> =
        linkedMapOf(
            BackupRecordFamily.VAULT to setOf(listOf("vault-$scope")),
            BackupRecordFamily.WORKSPACE to setOf(listOf("workspace-$scope")),
            BackupRecordFamily.MEMBER to setOf(listOf("member-$scope")),
            BackupRecordFamily.PROJECT to setOf(listOf("project-$scope")),
            BackupRecordFamily.WORKFLOW_STATUS to buildSet {
                SemanticFixture.entries.forEach { semantic ->
                    add(listOf("status-${semantic.name.lowercase()}-$scope"))
                }
                add(listOf("status-global-$scope"))
            },
            BackupRecordFamily.MILESTONE to setOf(listOf("milestone-$scope")),
            BackupRecordFamily.TASK to setOf(
                listOf("task-$scope"),
                listOf("prerequisite-$scope"),
                listOf("inbox-task-$scope"),
            ),
            BackupRecordFamily.CHECKLIST_ITEM to setOf(listOf("check-$scope")),
            BackupRecordFamily.TASK_DEPENDENCY to setOf(
                listOf("task-$scope", "prerequisite-$scope"),
            ),
            BackupRecordFamily.TAG to setOf(listOf("tag-$scope")),
            BackupRecordFamily.TASK_TAG to setOf(listOf("task-$scope", "tag-$scope")),
            BackupRecordFamily.REMINDER to setOf(listOf("reminder:task-$scope")),
            BackupRecordFamily.ATTACHMENT to setOf(listOf("attachment-$scope")),
            BackupRecordFamily.ACTIVITY_ENTRY to setOf(
                listOf("activity-task-$scope"),
                listOf("activity-project-$scope"),
                listOf("activity-unlinked-$scope"),
            ),
            BackupRecordFamily.TIME_ENTRY to setOf(listOf("time-$scope")),
            BackupRecordFamily.TEMPLATE to setOf(listOf("template-$scope")),
            BackupRecordFamily.SAVED_VIEW to setOf(listOf("view-$scope")),
            BackupRecordFamily.TOMBSTONE to setOf(
                listOf("purged-task-$scope", "task"),
                listOf("legacy-purged-task-$scope", "task"),
            ),
        )

    private fun StructuredBackupCapture.inboxSemanticStatuses(): Set<String> =
        records.asSequence()
            .filter { it.family == BackupRecordFamily.WORKFLOW_STATUS }
            .filter { record ->
                record.fields.single { it.name == "projectId" }.value == null
            }
            .map { record ->
                checkNotNull(record.fields.single { it.name == "semanticStatus" }.value)
            }
            .toSet()

    private fun StructuredBackupCapture.identitySets() = records.identitySets()

    private fun List<BackupRecordV1>.identitySets(): Map<BackupRecordFamily, Set<List<String>>> =
        groupingBy(BackupRecordV1::family)
            .aggregate { _, accumulator: MutableSet<List<String>>?, record, _ ->
                (accumulator ?: linkedSetOf()).also { it += record.identity }
            }

    private enum class SemanticFixture {
        BACKLOG,
        PLANNED,
        STARTED,
        BLOCKED,
        COMPLETED,
    }
}
