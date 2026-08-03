package app.opentasks.core.data.backup

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
import app.opentasks.core.data.db.VaultEntity
import app.opentasks.core.data.db.WorkflowStatusEntity
import app.opentasks.core.data.db.WorkspaceEntity
import app.opentasks.core.domain.BackupMutationKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupMutationCodecTest {
    @Test
    fun canonicalUpsertEncodingMatchesHandDerivedBytes() {
        val payload = BackupMutationPayloadV1(
            mutationKind = BackupMutationKind.UPSERT,
            record = TagEntity(
                id = "tag-1",
                workspaceId = "workspace-1",
                name = "Urgent",
            ).toBackupRecordV1(),
            deletedFamily = null,
            deletedIdentity = null,
        )
        val expected = (
            """{"formatVersion":1,"minimumReaderVersion":1,"mutationKind":"UPSERT",""" +
                """"record":{"family":"TAG","identity":["tag-1"],"fields":[""" +
                """{"name":"id","type":"STRING","value":"tag-1"},""" +
                """{"name":"workspaceId","type":"STRING","value":"workspace-1"},""" +
                """{"name":"name","type":"STRING","value":"Urgent"}]},""" +
                """"deletedFamily":null,"deletedIdentity":null}"""
            ).toByteArray()

        val encoded = BackupMutationCodec.encode(payload)

        assertArrayEquals(expected, encoded)
        assertEquals(payload, BackupMutationCodec.decode(encoded))
    }

    @Test
    fun canonicalDeletionEncodingMatchesHandDerivedBytes() {
        val payload = BackupMutationPayloadV1(
            mutationKind = BackupMutationKind.DELETE,
            record = null,
            deletedFamily = BackupRecordFamily.TASK_DEPENDENCY,
            deletedIdentity = listOf("task-1", "task-2"),
        )
        val expected = (
            """{"formatVersion":1,"minimumReaderVersion":1,"mutationKind":"DELETE",""" +
                """"record":null,"deletedFamily":"TASK_DEPENDENCY",""" +
                """"deletedIdentity":["task-1","task-2"]}"""
            ).toByteArray()

        val encoded = BackupMutationCodec.encode(payload)

        assertArrayEquals(expected, encoded)
        assertEquals(payload, BackupMutationCodec.decode(encoded))
    }

    @Test
    fun nonCanonicalFieldOrderIsRejected() {
        val source = canonicalTagJson().replace(
            """{"name":"id","type":"STRING","value":"tag-1"},""" +
                """{"name":"workspaceId","type":"STRING","value":"workspace-1"}""",
            """{"name":"workspaceId","type":"STRING","value":"workspace-1"},""" +
                """{"name":"id","type":"STRING","value":"tag-1"}""",
        ).toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.decode(source)
        }
    }

    @Test
    fun duplicateJsonKeyIsRejected() {
        val source = canonicalTagJson().replace(
            """"formatVersion":1""",
            """"formatVersion":1,"formatVersion":1""",
        ).toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.decode(source)
        }
    }

    @Test
    fun duplicateRecordFieldIsRejected() {
        val source = canonicalTagJson().replace(
            """{"name":"name","type":"STRING","value":"Urgent"}""",
            """{"name":"workspaceId","type":"STRING","value":"workspace-1"}""",
        ).toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.decode(source)
        }
    }

    @Test
    fun unknownJsonKeyIsRejected() {
        val source = canonicalTagJson().replace(
            """"minimumReaderVersion":1""",
            """"minimumReaderVersion":1,"unknown":true""",
        ).toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.decode(source)
        }
    }

    @Test
    fun invalidUtf8IsRejected() {
        val source = canonicalTagJson().toByteArray()
        source[canonicalTagJson().indexOf("Urgent")] = 0xc3.toByte()

        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.decode(source)
        }
    }

    @Test
    fun paddedBase64IsRejected() {
        val canonical = BackupMutationCodec.encode(
            BackupMutationPayloadV1(
                mutationKind = BackupMutationKind.UPSERT,
                record = SavedViewEntity(
                    id = "view-1",
                    workspaceId = "workspace-1",
                    name = "Mine",
                    encryptedQuery = byteArrayOf(1),
                ).toBackupRecordV1(),
                deletedFamily = null,
                deletedIdentity = null,
            ),
        ).toString(Charsets.UTF_8)
        val source = canonical.replace(
            """"value":"AQ"""",
            """"value":"AQ=="""",
        ).toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.decode(source)
        }
    }

    @Test
    fun noteRecordRoundTripsCanonically() {
        val payload = BackupMutationPayloadV1(
            mutationKind = BackupMutationKind.UPSERT,
            record = NoteEntity(
                id = "note-1",
                taskId = "task-1",
                projectId = null,
                bodyCiphertext = byteArrayOf(1, 2, 3),
                createdAtEpochMillis = 10,
                editedAtEpochMillis = 20,
                revisionWallMillis = 1,
                revisionLogical = 0,
                revisionDeviceId = "device-1",
            ).toBackupRecordV1(),
            deletedFamily = null,
            deletedIdentity = null,
        )
        val encoded = BackupMutationCodec.encode(payload)

        val decoded = BackupMutationCodec.decodeOwned(encoded.copyOf())

        assertEquals(payload, decoded)
        assertArrayEquals(encoded, BackupMutationCodec.encode(decoded))
    }

    @Test
    fun noteWithBothOwnersIsRejected() {
        fun note(taskId: String?, projectId: String?): BackupRecordV1 = NoteEntity(
            id = "note-1",
            taskId = taskId,
            projectId = projectId,
            bodyCiphertext = byteArrayOf(1),
            createdAtEpochMillis = 10,
            editedAtEpochMillis = null,
            revisionWallMillis = 1,
            revisionLogical = 0,
            revisionDeviceId = "device-1",
        ).toBackupRecordV1()

        listOf(
            note(taskId = "task-1", projectId = "project-1"),
            note(taskId = null, projectId = null),
        ).forEach { record ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupMutationCodec.encode(
                    BackupMutationPayloadV1(
                        mutationKind = BackupMutationKind.UPSERT,
                        record = record,
                        deletedFamily = null,
                        deletedIdentity = null,
                    ),
                )
            }
        }
    }

    @Test
    fun noteBodyCiphertextOverBoundIsRejected() {
        val record = NoteEntity(
            id = "note-1",
            taskId = "task-1",
            projectId = null,
            bodyCiphertext = ByteArray(40_001),
            createdAtEpochMillis = 10,
            editedAtEpochMillis = null,
            revisionWallMillis = 1,
            revisionLogical = 0,
            revisionDeviceId = "device-1",
        ).toBackupRecordV1()

        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.encode(
                BackupMutationPayloadV1(
                    mutationKind = BackupMutationKind.UPSERT,
                    record = record,
                    deletedFamily = null,
                    deletedIdentity = null,
                ),
            )
        }
    }

    @Test
    fun attachmentRecordCarriesBlobIdentityAndNoKeepOffline() {
        val payload = BackupMutationPayloadV1(
            mutationKind = BackupMutationKind.UPSERT,
            record = AttachmentEntity(
                id = "attachment-1",
                taskId = "task-1",
                displayNameCiphertext = byteArrayOf(1),
                mimeType = "text/plain",
                byteCount = 10,
                contentHash = "hash",
                blobSetId = "blob-1",
                chunkCount = 3,
                deletedAtEpochMillis = 100,
                revisionWallMillis = 1,
                revisionLogical = 0,
                revisionDeviceId = "device-1",
            ).toBackupRecordV1(),
            deletedFamily = null,
            deletedIdentity = null,
        )
        val encoded = BackupMutationCodec.encode(payload)

        assertEquals(payload, BackupMutationCodec.decode(encoded))

        val recordWithKeepOffline = payload.record!!.copy(
            fields = payload.record.fields +
                BackupFieldV1("keepOffline", BackupFieldType.BOOLEAN, "true"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.encode(payload.copy(record = recordWithKeepOffline))
        }
    }

    @Test
    fun futureFormatIsRejected() {
        val source = canonicalTagJson()
            .replace(""""formatVersion":1""", """"formatVersion":2""")
            .toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.decode(source)
        }
    }

    @Test
    fun weakenedMinimumReaderIsRejected() {
        val source = canonicalTagJson()
            .replace(""""minimumReaderVersion":1""", """"minimumReaderVersion":0""")
            .toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.decode(source)
        }
    }

    @Test
    fun identityThatDisagreesWithRecordIsRejected() {
        val source = canonicalTagJson()
            .replace(""""identity":["tag-1"]""", """"identity":["tag-other"]""")
            .toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.decode(source)
        }
    }

    @Test
    fun oversizedPayloadIsRejectedBeforeParsing() {
        val source = ByteArray(BackupMutationCodec.MAX_PAYLOAD_BYTES + 1) { ' '.code.toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.decode(source)
        }
    }

    @Test
    fun decodePreservesCallerOwnedInput() {
        val source = canonicalTagJson().toByteArray()
        val expected = source.copyOf()

        BackupMutationCodec.decode(source)

        assertArrayEquals(expected, source)
    }

    @Test
    fun decodeOwnedClearsTransferredInputAfterSuccessAndFailure() {
        val valid = canonicalTagJson().toByteArray()
        val invalid = byteArrayOf(0xc3.toByte())

        BackupMutationCodec.decodeOwned(valid)
        assertArrayEquals(ByteArray(valid.size), valid)
        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.decodeOwned(invalid)
        }
        assertArrayEquals(ByteArray(invalid.size), invalid)
    }

    @Test
    fun everyRoomRecordFamilyUsesItsExactIdentityAndOrderedFields() {
        val records = roomRecords().associateBy(BackupRecordV1::family)
        val expectedFields = linkedMapOf(
            BackupRecordFamily.VAULT to listOf(
                "id",
                "createdAtEpochMillis",
                "schemaVersion",
                "cryptoVersion",
                "minimumReaderVersion",
            ),
            BackupRecordFamily.WORKSPACE to listOf("id", "vaultId", "ownerId", "name"),
            BackupRecordFamily.MEMBER to listOf("id", "displayName"),
            BackupRecordFamily.PROJECT to listOf(
                "id",
                "workspaceId",
                "name",
                "summary",
                "health",
                "dueDate",
                "completedTasks",
                "totalTasks",
                "archivedAtEpochMillis",
                "revisionWallMillis",
                "revisionLogical",
                "revisionDeviceId",
            ),
            BackupRecordFamily.WORKFLOW_STATUS to listOf(
                "id",
                "projectId",
                "name",
                "semanticStatus",
                "rank",
                "archivedAtEpochMillis",
                "revisionWallMillis",
                "revisionLogical",
                "revisionDeviceId",
            ),
            BackupRecordFamily.MILESTONE to listOf(
                "id",
                "projectId",
                "name",
                "dueDate",
                "completedAtEpochMillis",
                "revisionWallMillis",
                "revisionLogical",
                "revisionDeviceId",
            ),
            BackupRecordFamily.TASK to listOf(
                "id",
                "workspaceId",
                "projectId",
                "parentTaskId",
                "statusId",
                "semanticStatus",
                "title",
                "descriptionCiphertext",
                "priority",
                "startEpochMillis",
                "startZoneId",
                "dueEpochMillis",
                "dueZoneId",
                "recurrenceFrequency",
                "recurrenceInterval",
                "recurrenceWeekdays",
                "recurrenceCount",
                "recurrenceEndDate",
                "recurrenceSeriesId",
                "recurrenceAnchorEpochMillis",
                "recurrenceAnchorZoneId",
                "recurrenceOccurrenceIndex",
                "estimateSeconds",
                "milestoneId",
                "completedAtEpochMillis",
                "deletedAtEpochMillis",
                "revisionWallMillis",
                "revisionLogical",
                "revisionDeviceId",
            ),
            BackupRecordFamily.CHECKLIST_ITEM to
                listOf("id", "taskId", "text", "completed", "rank"),
            BackupRecordFamily.TASK_DEPENDENCY to listOf(
                "taskId",
                "dependsOnTaskId",
                "revisionWallMillis",
                "revisionLogical",
                "revisionDeviceId",
            ),
            BackupRecordFamily.TAG to listOf("id", "workspaceId", "name"),
            BackupRecordFamily.TASK_TAG to listOf(
                "taskId",
                "tagId",
                "present",
                "revisionWallMillis",
                "revisionLogical",
                "revisionDeviceId",
            ),
            BackupRecordFamily.REMINDER to
                listOf("id", "taskId", "triggerAtEpochMillis", "zoneId", "precise"),
            BackupRecordFamily.ATTACHMENT to listOf(
                "id",
                "taskId",
                "displayNameCiphertext",
                "mimeType",
                "byteCount",
                "contentHash",
                "blobSetId",
                "chunkCount",
                "deletedAtEpochMillis",
                "revisionWallMillis",
                "revisionLogical",
                "revisionDeviceId",
            ),
            BackupRecordFamily.ACTIVITY_ENTRY to listOf(
                "id",
                "taskId",
                "projectId",
                "kind",
                "bodyCiphertext",
                "createdAtEpochMillis",
            ),
            BackupRecordFamily.TIME_ENTRY to listOf(
                "id",
                "taskId",
                "deviceId",
                "startedAtEpochMillis",
                "stoppedAtEpochMillis",
                "noteCiphertext",
            ),
            BackupRecordFamily.TEMPLATE to listOf(
                "id",
                "workspaceId",
                "name",
                "encryptedPayload",
                "revisionWallMillis",
                "revisionLogical",
                "revisionDeviceId",
            ),
            BackupRecordFamily.SAVED_VIEW to
                listOf("id", "workspaceId", "name", "encryptedQuery"),
            BackupRecordFamily.NOTE to listOf(
                "id",
                "taskId",
                "projectId",
                "bodyCiphertext",
                "createdAtEpochMillis",
                "editedAtEpochMillis",
                "revisionWallMillis",
                "revisionLogical",
                "revisionDeviceId",
            ),
            BackupRecordFamily.RETIRED_BLOB_SET to listOf(
                "blobSetId",
                "chunkCount",
                "retiredAtEpochMillis",
                "revisionWallMillis",
                "revisionLogical",
                "revisionDeviceId",
            ),
            BackupRecordFamily.TOMBSTONE to listOf(
                "objectId",
                "objectType",
                "deletedAtEpochMillis",
                "purgeAfterEpochMillis",
                "revisionWallMillis",
                "revisionLogical",
                "revisionDeviceId",
            ),
        )
        val expectedIdentities = linkedMapOf(
            BackupRecordFamily.VAULT to listOf("vault-1"),
            BackupRecordFamily.WORKSPACE to listOf("workspace-1"),
            BackupRecordFamily.MEMBER to listOf("member-1"),
            BackupRecordFamily.PROJECT to listOf("project-1"),
            BackupRecordFamily.WORKFLOW_STATUS to listOf("status-1"),
            BackupRecordFamily.MILESTONE to listOf("milestone-1"),
            BackupRecordFamily.TASK to listOf("task-1"),
            BackupRecordFamily.CHECKLIST_ITEM to listOf("check-1"),
            BackupRecordFamily.TASK_DEPENDENCY to listOf("task-1", "task-2"),
            BackupRecordFamily.TAG to listOf("tag-1"),
            BackupRecordFamily.TASK_TAG to listOf("task-1", "tag-1"),
            BackupRecordFamily.REMINDER to listOf("reminder-1"),
            BackupRecordFamily.ATTACHMENT to listOf("attachment-1"),
            BackupRecordFamily.ACTIVITY_ENTRY to listOf("activity-1"),
            BackupRecordFamily.TIME_ENTRY to listOf("time-1"),
            BackupRecordFamily.TEMPLATE to listOf("template-1"),
            BackupRecordFamily.SAVED_VIEW to listOf("view-1"),
            BackupRecordFamily.NOTE to listOf("note-1"),
            BackupRecordFamily.RETIRED_BLOB_SET to listOf("blob-set-1"),
            BackupRecordFamily.TOMBSTONE to listOf("task-1", "task"),
        )

        assertEquals(BackupRecordFamily.entries.toSet(), records.keys)
        expectedFields.forEach { (family, fields) ->
            assertEquals(fields, records.getValue(family).fields.map(BackupFieldV1::name))
            assertEquals(expectedIdentities.getValue(family), records.getValue(family).identity)
            BackupMutationCodec.encode(
                BackupMutationPayloadV1(
                    mutationKind = BackupMutationKind.UPSERT,
                    record = records.getValue(family),
                    deletedFamily = null,
                    deletedIdentity = null,
                ),
            )
        }
    }

    @Test
    fun nonWeeklyRecurrenceAllowsCanonicalEmptyWeekdayEncoding() {
        val task = roomRecords()
            .single { it.family == BackupRecordFamily.TASK }
            .copy(
                fields = roomRecords()
                    .single { it.family == BackupRecordFamily.TASK }
                    .fields
                    .map { field ->
                        when (field.name) {
                            "recurrenceFrequency" ->
                                BackupFieldV1(field.name, BackupFieldType.STRING, "DAILY")
                            "recurrenceInterval" ->
                                BackupFieldV1(field.name, BackupFieldType.INT, "1")
                            "recurrenceWeekdays" ->
                                BackupFieldV1(field.name, BackupFieldType.STRING, "")
                            else -> field
                        }
                    },
            )

        BackupMutationCodec.encode(
            BackupMutationPayloadV1(
                mutationKind = BackupMutationKind.UPSERT,
                record = task,
                deletedFamily = null,
                deletedIdentity = null,
            ),
        )
    }

    @Test
    fun weeklyRecurrenceAllowsEmptyWeekdaysWhenDomainUsesDueDateFallback() {
        val task = roomRecords()
            .single { it.family == BackupRecordFamily.TASK }
            .copy(
                fields = roomRecords()
                    .single { it.family == BackupRecordFamily.TASK }
                    .fields
                    .map { field ->
                        when (field.name) {
                            "recurrenceFrequency" ->
                                BackupFieldV1(field.name, BackupFieldType.STRING, "WEEKLY")
                            "recurrenceInterval" ->
                                BackupFieldV1(field.name, BackupFieldType.INT, "1")
                            "recurrenceWeekdays" ->
                                BackupFieldV1(field.name, BackupFieldType.STRING, "")
                            else -> field
                        }
                    },
            )

        BackupMutationCodec.encode(
            BackupMutationPayloadV1(
                mutationKind = BackupMutationKind.UPSERT,
                record = task,
                deletedFamily = null,
                deletedIdentity = null,
            ),
        )
    }

    @Test
    fun weeklyRecurrenceAllowsPendingFirstOccurrenceSentinel() {
        val task = weeklyTaskWithOccurrenceIndex(-1)

        BackupMutationCodec.encode(
            BackupMutationPayloadV1(
                mutationKind = BackupMutationKind.UPSERT,
                record = task,
                deletedFamily = null,
                deletedIdentity = null,
            ),
        )
    }

    @Test
    fun recurrenceOccurrenceIndexBelowPendingSentinelIsRejected() {
        val task = weeklyTaskWithOccurrenceIndex(-2)

        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.encode(
                BackupMutationPayloadV1(
                    mutationKind = BackupMutationKind.UPSERT,
                    record = task,
                    deletedFamily = null,
                    deletedIdentity = null,
                ),
            )
        }
    }

    @Test
    fun immediatelyStoppedTimerAllowsEqualStartAndStopMillis() {
        val record = TimeEntryEntity(
            id = "time-immediate",
            taskId = "task-1",
            deviceId = "device-1",
            startedAtEpochMillis = 10,
            stoppedAtEpochMillis = 10,
            noteCiphertext = ByteArray(0),
        ).toBackupRecordV1()

        BackupMutationCodec.encode(
            BackupMutationPayloadV1(
                mutationKind = BackupMutationKind.UPSERT,
                record = record,
                deletedFamily = null,
                deletedIdentity = null,
            ),
        )
    }

    @Test
    fun canonicalSignedEpochMillisRemainCompatibleWithPre1970DomainInstants() {
        val signedEpochRecords = listOf(
            VaultEntity("vault-negative", "LOCAL", -1, 6, 1, 1).toBackupRecordV1(),
            ProjectEntity(
                "project-negative",
                "workspace-1",
                "Project",
                "",
                "ON_TRACK",
                null,
                0,
                0,
                -1,
                1,
                0,
                "device-1",
            ).toBackupRecordV1(),
            ReminderEntity("reminder-negative", "task-1", -1, "UTC", false)
                .toBackupRecordV1(),
            ActivityEntryEntity(
                "activity-negative",
                "task-1",
                null,
                "UPDATED",
                ByteArray(0),
                -1,
            ).toBackupRecordV1(),
            TimeEntryEntity(
                "time-negative",
                "task-1",
                "device-1",
                -2,
                -1,
                ByteArray(0),
            ).toBackupRecordV1(),
            TombstoneEntity("task-negative", "task", -2, -1, 1, 0, "device-1")
                .toBackupRecordV1(),
        )

        signedEpochRecords.forEach { record ->
            BackupMutationCodec.encode(
                BackupMutationPayloadV1(
                    mutationKind = BackupMutationKind.UPSERT,
                    record = record,
                    deletedFamily = null,
                    deletedIdentity = null,
                ),
            )
        }
    }

    private fun canonicalTagJson(): String =
        """{"formatVersion":1,"minimumReaderVersion":1,"mutationKind":"UPSERT",""" +
            """"record":{"family":"TAG","identity":["tag-1"],"fields":[""" +
            """{"name":"id","type":"STRING","value":"tag-1"},""" +
            """{"name":"workspaceId","type":"STRING","value":"workspace-1"},""" +
            """{"name":"name","type":"STRING","value":"Urgent"}]},""" +
            """"deletedFamily":null,"deletedIdentity":null}"""

    private fun weeklyTaskWithOccurrenceIndex(index: Int): BackupRecordV1 {
        val task = roomRecords().single { it.family == BackupRecordFamily.TASK }
        return task.copy(
            fields = task.fields.map { field ->
                val replacement = when (field.name) {
                    "recurrenceFrequency" -> "WEEKLY" to BackupFieldType.STRING
                    "recurrenceInterval" -> "1" to BackupFieldType.INT
                    "recurrenceWeekdays" -> "MONDAY" to BackupFieldType.STRING
                    "recurrenceSeriesId" -> "series-1" to BackupFieldType.STRING
                    "recurrenceAnchorEpochMillis" -> "1" to BackupFieldType.LONG
                    "recurrenceAnchorZoneId" -> "UTC" to BackupFieldType.STRING
                    "recurrenceOccurrenceIndex" -> index.toString() to BackupFieldType.INT
                    else -> null
                }
                if (replacement == null) {
                    field
                } else {
                    field.copy(type = replacement.second, value = replacement.first)
                }
            },
        )
    }

    private fun roomRecords(): List<BackupRecordV1> = listOf(
        VaultEntity("vault-1", "LOCAL", 1, 6, 1, 1).toBackupRecordV1(),
        WorkspaceEntity("workspace-1", "vault-1", "member-1", "Workspace")
            .toBackupRecordV1(),
        MemberEntity("member-1", "Member").toBackupRecordV1(),
        ProjectEntity(
            "project-1",
            "workspace-1",
            "Project",
            "",
            "ON_TRACK",
            "2026-07-29",
            1,
            2,
            null,
            1,
            0,
            "device-1",
        ).toBackupRecordV1(),
        WorkflowStatusEntity(
            "status-1",
            "project-1",
            "Started",
            "STARTED",
            "a0",
            null,
            1,
            0,
            "device-1",
        ).toBackupRecordV1(),
        MilestoneEntity(
            "milestone-1",
            "project-1",
            "Ship",
            "2026-07-29",
            null,
            1,
            0,
            "device-1",
        ).toBackupRecordV1(),
        TaskEntity(
            id = "task-1",
            workspaceId = "workspace-1",
            projectId = "project-1",
            parentTaskId = null,
            statusId = "status-1",
            semanticStatus = "STARTED",
            title = "Task",
            descriptionCiphertext = byteArrayOf(1),
            priority = "HIGH",
            startEpochMillis = null,
            startZoneId = null,
            dueEpochMillis = 1,
            dueZoneId = "UTC",
            recurrenceFrequency = null,
            recurrenceInterval = null,
            recurrenceWeekdays = null,
            recurrenceCount = null,
            recurrenceEndDate = null,
            recurrenceSeriesId = null,
            recurrenceAnchorEpochMillis = null,
            recurrenceAnchorZoneId = null,
            recurrenceOccurrenceIndex = null,
            estimateSeconds = 60,
            milestoneId = "milestone-1",
            completedAtEpochMillis = null,
            deletedAtEpochMillis = null,
            revisionWallMillis = 1,
            revisionLogical = 0,
            revisionDeviceId = "device-1",
        ).toBackupRecordV1(),
        ChecklistItemEntity("check-1", "task-1", "Check", false, "a0")
            .toBackupRecordV1(),
        TaskDependencyEntity("task-1", "task-2", 1, 0, "device-1")
            .toBackupRecordV1(),
        TagEntity("tag-1", "workspace-1", "Urgent").toBackupRecordV1(),
        TaskTagEntity("task-1", "tag-1", true, 1, 0, "device-1")
            .toBackupRecordV1(),
        ReminderEntity("reminder-1", "task-1", 1, "UTC", false).toBackupRecordV1(),
        AttachmentEntity(
            "attachment-1",
            "task-1",
            byteArrayOf(1),
            "text/plain",
            1,
            "hash",
            null,
            0,
            null,
            1,
            0,
            "device-1",
        ).toBackupRecordV1(),
        ActivityEntryEntity(
            "activity-1",
            "task-1",
            "project-1",
            "UPDATED",
            byteArrayOf(1),
            1,
        ).toBackupRecordV1(),
        TimeEntryEntity("time-1", "task-1", "device-1", 1, 2, byteArrayOf(1))
            .toBackupRecordV1(),
        TemplateEntity(
            "template-1",
            "workspace-1",
            "Template",
            byteArrayOf(1),
            1,
            0,
            "device-1",
        ).toBackupRecordV1(),
        SavedViewEntity("view-1", "workspace-1", "View", byteArrayOf(1))
            .toBackupRecordV1(),
        NoteEntity(
            "note-1",
            "task-1",
            null,
            byteArrayOf(1),
            1,
            null,
            1,
            0,
            "device-1",
        ).toBackupRecordV1(),
        RetiredBlobSetEntity("blob-set-1", 3, 1, 1, 0, "device-1")
            .toBackupRecordV1(),
        TombstoneEntity("task-1", "task", 1, 2, 1, 0, "device-1")
            .toBackupRecordV1(),
    )
}
