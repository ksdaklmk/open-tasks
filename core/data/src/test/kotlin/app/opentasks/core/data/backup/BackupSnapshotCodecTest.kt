package app.opentasks.core.data.backup

import app.opentasks.core.data.db.ActivityEntryEntity
import app.opentasks.core.data.db.AttachmentEntity
import app.opentasks.core.data.db.ChecklistItemEntity
import app.opentasks.core.data.db.MemberEntity
import app.opentasks.core.data.db.MilestoneEntity
import app.opentasks.core.data.db.ProjectEntity
import app.opentasks.core.data.db.ReminderEntity
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSnapshotCodecTest {
    @Test
    fun canonicalEncodingSortsEveryFamilyAndIdentityWithoutMutatingCallerRecords() {
        val canonical = BackupPayloadTestFixtures.snapshot()
        val reversedRecords = canonical.records.reversed()
        val input = canonical.copy(records = reversedRecords)
        val expectedPrefix = (
            """{"formatVersion":1,"minimumReaderVersion":1,"vaultId":"vault-alpha",""" +
                """"coveredGeneration":53,"records":[{"family":"VAULT","identity":["vault-alpha"]"""
            ).toByteArray()

        val encoded = BackupSnapshotCodec.encode(input)
        val decoded = BackupSnapshotCodec.decode(encoded)

        assertArrayEquals(expectedPrefix, encoded.copyOf(expectedPrefix.size))
        assertEquals(reversedRecords, input.records)
        assertEquals(
            decoded.records.sortedWith(
                compareBy<BackupRecordV1> { it.family.ordinal }
                    .thenComparator { left, right -> compareIdentities(left.identity, right.identity) },
            ),
            decoded.records,
        )
        assertEquals(BackupRecordFamily.VAULT, decoded.records.first().family)
        assertEquals(BackupRecordFamily.TOMBSTONE, decoded.records.last().family)
        assertArrayEquals(encoded, BackupSnapshotCodec.encode(decoded))
    }

    @Test
    fun completeFixturePreservesVaultGenerationEveryFamilyCountAndUnpaddedByteFields() {
        val payload = BackupPayloadTestFixtures.snapshot()

        val decoded = BackupSnapshotCodec.decode(BackupSnapshotCodec.encode(payload))

        assertEquals("vault-alpha", decoded.vaultId)
        assertEquals(53, decoded.coveredGeneration)
        assertEquals(
            linkedMapOf(
                BackupRecordFamily.VAULT to 1,
                BackupRecordFamily.WORKSPACE to 1,
                BackupRecordFamily.MEMBER to 1,
                BackupRecordFamily.PROJECT to 1,
                BackupRecordFamily.WORKFLOW_STATUS to 10,
                BackupRecordFamily.MILESTONE to 1,
                BackupRecordFamily.TASK to 2,
                BackupRecordFamily.CHECKLIST_ITEM to 1,
                BackupRecordFamily.TASK_DEPENDENCY to 1,
                BackupRecordFamily.TAG to 1,
                BackupRecordFamily.TASK_TAG to 1,
                BackupRecordFamily.REMINDER to 1,
                BackupRecordFamily.ATTACHMENT to 1,
                BackupRecordFamily.ACTIVITY_ENTRY to 1,
                BackupRecordFamily.TIME_ENTRY to 1,
                BackupRecordFamily.TEMPLATE to 1,
                BackupRecordFamily.SAVED_VIEW to 1,
                BackupRecordFamily.TOMBSTONE to 1,
            ),
            decoded.records.groupingBy(BackupRecordV1::family).eachCount(),
        )
        val byteValues = decoded.records
            .flatMap(BackupRecordV1::fields)
            .filter { it.type == BackupFieldType.BYTES }
            .map { requireNotNull(it.value) }
        assertEquals(7, byteValues.size)
        assertTrue(byteValues.all { !it.endsWith('=') })
    }

    @Test
    fun snapshotAcceptsOneHundredThousandRecordsAndRejectsOneHundredThousandOne() {
        val base = BackupPayloadTestFixtures.snapshot()
        val additions = (0 until 100_001 - base.records.size).map { index ->
            MemberEntity(
                id = "member-count-${index.toString().padStart(6, '0')}",
                displayName = "M",
            ).toBackupRecordV1()
        }
        val accepted = base.copy(
            records = base.records + additions.dropLast(1),
        )
        val rejected = base.copy(records = base.records + additions)

        val encoded = BackupSnapshotCodec.encode(accepted)
        try {
            assertTrue(encoded.isNotEmpty())
        } finally {
            encoded.fill(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupSnapshotCodec.encode(rejected)
        }
    }

    @Test
    fun snapshotAcceptsExactPlaintextMaximumAndRejectsOneByteOver() {
        val source = BackupPayloadTestFixtures.snapshotBytesAtSize(
            BackupSnapshotCodec.MAX_PLAINTEXT_BYTES,
        )

        assertEquals(BackupSnapshotCodec.MAX_PLAINTEXT_BYTES, source.size)
        assertEquals(
            "vault-alpha",
            BackupSnapshotCodec.decodeOwned(source).vaultId,
        )
        val over = ByteArray(BackupSnapshotCodec.MAX_PLAINTEXT_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            BackupSnapshotCodec.decodeOwned(over)
        }
    }

    @Test
    fun unknownMissingDuplicateAndReorderedPayloadFieldsAreRejected() {
        val canonical = BackupSnapshotCodec.encode(BackupPayloadTestFixtures.snapshot())
            .toString(Charsets.UTF_8)
        val malformed = listOf(
            canonical.replace(
                """"minimumReaderVersion":1""",
                """"minimumReaderVersion":1,"unknown":true""",
            ),
            canonical.replace(""""minimumReaderVersion":1,""", ""),
            canonical.replace(
                """"formatVersion":1""",
                """"formatVersion":1,"formatVersion":1""",
            ),
            canonical.replace(
                """"formatVersion":1,"minimumReaderVersion":1""",
                """"minimumReaderVersion":1,"formatVersion":1""",
            ),
        )

        malformed.forEach { source ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupSnapshotCodec.decode(source.toByteArray())
            }
        }
    }

    @Test
    fun missingDuplicateAndReorderedRecordFieldsAreRejected() {
        val original = BackupPayloadTestFixtures.snapshot()
        val tag = original.record(BackupRecordFamily.TAG, "tag-1")
        val malformed = listOf(
            tag.copy(fields = tag.fields.dropLast(1)),
            tag.copy(fields = tag.fields.dropLast(1) + tag.fields[1]),
            tag.copy(fields = tag.fields.reversed()),
        )

        malformed.forEach { record ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupSnapshotCodec.encode(original.replace(record))
            }
        }
    }

    @Test
    fun duplicateRecordIdentityIsRejectedAcrossCompleteSnapshot() {
        val payload = BackupPayloadTestFixtures.snapshot()
        val duplicate = payload.record(BackupRecordFamily.TAG, "tag-1")

        assertThrows(IllegalArgumentException::class.java) {
            BackupSnapshotCodec.encode(payload.copy(records = payload.records + duplicate))
        }
    }

    @Test
    fun foreignKeysAndOwnershipMustResolveWithinTheCapturedVault() {
        val original = BackupPayloadTestFixtures.snapshot()
        val invalid = listOf(
            original.update(BackupRecordFamily.WORKSPACE, "workspace-1", "vaultId", "vault-other"),
            original.update(BackupRecordFamily.WORKSPACE, "workspace-1", "ownerId", "member-other"),
            original.update(BackupRecordFamily.PROJECT, "project-1", "workspaceId", "workspace-other"),
            original.update(
                BackupRecordFamily.WORKFLOW_STATUS,
                "status-project-backlog",
                "projectId",
                "project-other",
            ),
            original.update(BackupRecordFamily.MILESTONE, "milestone-1", "projectId", "project-other"),
            original.update(BackupRecordFamily.TASK, "task-1", "workspaceId", "workspace-other"),
            original.update(BackupRecordFamily.TASK, "task-1", "statusId", "status-inbox-started"),
            original.update(BackupRecordFamily.TASK, "task-1", "milestoneId", "milestone-other"),
            original.update(BackupRecordFamily.CHECKLIST_ITEM, "check-1", "taskId", "task-other"),
            original.updateComposite(
                BackupRecordFamily.TASK_DEPENDENCY,
                listOf("task-2", "task-1"),
                "dependsOnTaskId",
                "task-other",
            ),
            original.update(BackupRecordFamily.TAG, "tag-1", "workspaceId", "workspace-other"),
            original.updateComposite(
                BackupRecordFamily.TASK_TAG,
                listOf("task-1", "tag-1"),
                "tagId",
                "tag-other",
            ),
            original.update(BackupRecordFamily.REMINDER, "reminder:task-1", "taskId", "task-2"),
            original.update(BackupRecordFamily.ATTACHMENT, "attachment-1", "taskId", "task-other"),
            original.update(
                BackupRecordFamily.ACTIVITY_ENTRY,
                "activity-1",
                "projectId",
                "project-other",
            ),
            original.update(BackupRecordFamily.TIME_ENTRY, "time-1", "taskId", "task-other"),
            original.update(BackupRecordFamily.TEMPLATE, "template-1", "workspaceId", "workspace-other"),
            original.update(BackupRecordFamily.SAVED_VIEW, "view-1", "workspaceId", "workspace-other"),
        )

        invalid.forEachIndexed { index, payload ->
            assertThrows("Invalid value case $index", IllegalArgumentException::class.java) {
                BackupSnapshotCodec.encode(payload)
            }
        }
    }

    @Test
    fun movingChildToInboxDoesNotInvalidateItsExistingParentIdentity() {
        val payload = BackupPayloadTestFixtures.snapshot()
            .update(BackupRecordFamily.TASK, "task-2", "projectId", null)
            .update(
                BackupRecordFamily.TASK,
                "task-2",
                "statusId",
                "status-inbox-planned",
            )

        assertEquals(
            "vault-alpha",
            BackupSnapshotCodec.decode(BackupSnapshotCodec.encode(payload)).vaultId,
        )
    }

    @Test
    fun activityWithNoOptionalTaskOrProjectLinkRemainsRepresentable() {
        val payload = BackupPayloadTestFixtures.snapshot()
            .update(BackupRecordFamily.ACTIVITY_ENTRY, "activity-1", "taskId", null)
            .update(BackupRecordFamily.ACTIVITY_ENTRY, "activity-1", "projectId", null)

        assertEquals(
            "vault-alpha",
            BackupSnapshotCodec.decode(BackupSnapshotCodec.encode(payload)).vaultId,
        )
    }

    @Test
    fun workflowSemanticCoverageRanksAndTaskSemanticsAreValidated() {
        val original = BackupPayloadTestFixtures.snapshot()
        val removedProjectPlanned = original.copy(
            records = original.records.filterNot {
                it.family == BackupRecordFamily.WORKFLOW_STATUS &&
                    it.identity == listOf("status-project-planned")
            },
        )
        val invalid = listOf(
            removedProjectPlanned,
            original.update(
                BackupRecordFamily.WORKFLOW_STATUS,
                "status-project-planned",
                "rank",
                "a0",
            ),
            original.update(
                BackupRecordFamily.TASK,
                "task-1",
                "semanticStatus",
                "COMPLETED",
            ),
            original.update(
                BackupRecordFamily.TASK,
                "task-1",
                "completedAtEpochMillis",
                "10",
                BackupFieldType.LONG,
            ),
        )

        invalid.forEachIndexed { index, payload ->
            assertThrows("Invalid scalar case $index", IllegalArgumentException::class.java) {
                BackupSnapshotCodec.encode(payload)
            }
        }
    }

    @Test
    fun taskParentAndDependencyCyclesAreRejected() {
        val original = BackupPayloadTestFixtures.snapshot()
        val parentCycle = original.update(
            BackupRecordFamily.TASK,
            "task-1",
            "parentTaskId",
            "task-2",
        )
        val reverseDependency = TaskDependencyEntity(
            taskId = "task-1",
            dependsOnTaskId = "task-2",
            revisionWallMillis = 12,
            revisionLogical = 0,
            revisionDeviceId = "device-alpha",
        ).toBackupRecordV1()

        assertThrows(IllegalArgumentException::class.java) {
            BackupSnapshotCodec.encode(parentCycle)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupSnapshotCodec.encode(
                original.copy(records = original.records + reverseDependency),
            )
        }
    }

    @Test
    fun recurrenceDateInstantZoneCountAndSizeValuesAreValidated() {
        val original = BackupPayloadTestFixtures.snapshot()
        val invalid = listOf(
            original.update(BackupRecordFamily.PROJECT, "project-1", "dueDate", "2026-02-30"),
            original.update(
                BackupRecordFamily.TASK,
                "task-1",
                "dueZoneId",
                "Mars/Olympus",
            ),
            original.update(
                BackupRecordFamily.TASK,
                "task-1",
                "recurrenceInterval",
                "0",
                BackupFieldType.INT,
            ),
            original.update(
                BackupRecordFamily.TASK,
                "task-1",
                "recurrenceWeekdays",
                "FRIDAY,MONDAY",
            ),
            original.update(
                BackupRecordFamily.TASK,
                "task-1",
                "recurrenceOccurrenceIndex",
                "-2",
                BackupFieldType.INT,
            ),
            original.update(
                BackupRecordFamily.TASK,
                "task-1",
                "recurrenceEndDate",
                "2026-12-31",
            ),
            original.update(
                BackupRecordFamily.TASK,
                "task-2",
                "recurrenceInterval",
                "1",
                BackupFieldType.INT,
            ),
            original.update(
                BackupRecordFamily.TASK,
                "task-1",
                "recurrenceSeriesId",
                null,
            ),
            original
                .update(
                    BackupRecordFamily.TASK,
                    "task-1",
                    "recurrenceCount",
                    null,
                )
                .update(
                    BackupRecordFamily.TASK,
                    "task-1",
                    "recurrenceEndDate",
                    "1969-12-31",
                ),
            original.update(
                BackupRecordFamily.TASK,
                "task-1",
                "startZoneId",
                null,
            ),
            original.update(
                BackupRecordFamily.PROJECT,
                "project-1",
                "completedTasks",
                "3",
                BackupFieldType.INT,
            ),
            original.update(
                BackupRecordFamily.TIME_ENTRY,
                "time-1",
                "stoppedAtEpochMillis",
                "9",
                BackupFieldType.LONG,
            ),
            original.update(
                BackupRecordFamily.TEMPLATE,
                "template-1",
                "encryptedPayload",
                "AA".repeat(1_398_103),
                BackupFieldType.BYTES,
            ),
        )

        invalid.forEachIndexed { index, payload ->
            assertThrows("Invalid recurrence case $index", IllegalArgumentException::class.java) {
                BackupSnapshotCodec.encode(payload)
            }
        }
    }

    @Test
    fun invalidAttachmentMetadataIsRejectedWithoutAttachmentContentAccess() {
        val original = BackupPayloadTestFixtures.snapshot()
        val invalid = listOf(
            original.update(
                BackupRecordFamily.ATTACHMENT,
                "attachment-1",
                "byteCount",
                "-1",
                BackupFieldType.LONG,
            ),
            original.update(BackupRecordFamily.ATTACHMENT, "attachment-1", "mimeType", ""),
            original.update(
                BackupRecordFamily.ATTACHMENT,
                "attachment-1",
                "contentHash",
                "h".repeat(513),
            ),
        )

        invalid.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupSnapshotCodec.encode(payload)
            }
        }
    }

    @Test
    fun invalidUtf8AndUnsupportedVersionsAreRejected() {
        val canonical = BackupSnapshotCodec.encode(BackupPayloadTestFixtures.snapshot())
        val invalidUtf8 = canonical.copyOf().also {
            val index = it.indexOfSubsequence("Résumé".toByteArray())
            it[index] = 0xc3.toByte()
            it[index + 1] = 0x28
        }
        val futureFormat = canonical.toString(Charsets.UTF_8)
            .replace(""""formatVersion":1""", """"formatVersion":2""")
            .toByteArray()
        val futureReader = canonical.toString(Charsets.UTF_8)
            .replace(""""minimumReaderVersion":1""", """"minimumReaderVersion":2""")
            .toByteArray()

        listOf(invalidUtf8, futureFormat, futureReader).forEach { source ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupSnapshotCodec.decode(source)
            }
        }
    }

    @Test
    fun decoderPreservesCallerInputAndClearsTransferredBuffersOnSuccessAndFailure() {
        val source = BackupSnapshotCodec.encode(BackupPayloadTestFixtures.snapshot())
        val expected = source.copyOf()

        BackupSnapshotCodec.decode(source)
        assertArrayEquals(expected, source)

        val owned = source.copyOf()
        BackupSnapshotCodec.decodeOwned(owned)
        assertArrayEquals(ByteArray(owned.size), owned)

        val invalidOwned = byteArrayOf(0xc3.toByte())
        assertThrows(IllegalArgumentException::class.java) {
            BackupSnapshotCodec.decodeOwned(invalidOwned)
        }
        assertArrayEquals(ByteArray(invalidOwned.size), invalidOwned)
    }
}

internal object BackupPayloadTestFixtures {
    fun snapshot(): BackupSnapshotPayloadV1 {
        val statuses = buildList {
            listOf("BACKLOG", "PLANNED", "STARTED", "BLOCKED", "COMPLETED")
                .forEachIndexed { index, semantic ->
                    add(
                        WorkflowStatusEntity(
                            id = "status-project-${semantic.lowercase()}",
                            projectId = "project-1",
                            name = semantic.lowercase().replaceFirstChar(Char::uppercase),
                            semanticStatus = semantic,
                            rank = "a$index",
                            archivedAtEpochMillis = null,
                            revisionWallMillis = 10,
                            revisionLogical = index,
                            revisionDeviceId = "device-alpha",
                        ).toBackupRecordV1(),
                    )
                    add(
                        WorkflowStatusEntity(
                            id = "status-inbox-${semantic.lowercase()}",
                            projectId = null,
                            name = "Inbox ${semantic.lowercase()}",
                            semanticStatus = semantic,
                            rank = "b$index",
                            archivedAtEpochMillis = null,
                            revisionWallMillis = 10,
                            revisionLogical = index,
                            revisionDeviceId = "device-alpha",
                        ).toBackupRecordV1(),
                    )
                }
        }
        return BackupSnapshotPayloadV1(
            vaultId = "vault-alpha",
            coveredGeneration = 53,
            records = buildList {
                add(VaultEntity("vault-alpha", "LOCAL", 1, 6, 1, 1).toBackupRecordV1())
                add(
                    WorkspaceEntity(
                        "workspace-1",
                        "vault-alpha",
                        "member-1",
                        "Résumé workspace",
                    ).toBackupRecordV1(),
                )
                add(MemberEntity("member-1", "Member One").toBackupRecordV1())
                add(
                    ProjectEntity(
                        "project-1",
                        "workspace-1",
                        "Project",
                        "Summary",
                        "ON_TRACK",
                        "2026-07-29",
                        0,
                        2,
                        null,
                        10,
                        0,
                        "device-alpha",
                    ).toBackupRecordV1(),
                )
                addAll(statuses)
                add(
                    MilestoneEntity(
                        "milestone-1",
                        "project-1",
                        "Ship",
                        "2026-08-01",
                        null,
                        10,
                        0,
                        "device-alpha",
                    ).toBackupRecordV1(),
                )
                add(task("task-1", null, "status-project-started", "STARTED", "milestone-1"))
                add(task("task-2", "task-1", "status-project-planned", "PLANNED", null))
                add(ChecklistItemEntity("check-1", "task-1", "Check", false, "a0").toBackupRecordV1())
                add(
                    TaskDependencyEntity("task-2", "task-1", 11, 0, "device-alpha")
                        .toBackupRecordV1(),
                )
                add(TagEntity("tag-1", "workspace-1", "Urgent").toBackupRecordV1())
                add(TaskTagEntity("task-1", "tag-1", true, 11, 0, "device-alpha").toBackupRecordV1())
                add(ReminderEntity("reminder:task-1", "task-1", 20, "UTC", false).toBackupRecordV1())
                add(
                    AttachmentEntity(
                        "attachment-1",
                        "task-1",
                        byteArrayOf(0, 1),
                        "text/plain",
                        2,
                        "sha256:fixture",
                        false,
                    ).toBackupRecordV1(),
                )
                add(
                    ActivityEntryEntity(
                        "activity-1",
                        "task-1",
                        "project-1",
                        "UPDATED",
                        byteArrayOf(2, 3),
                        21,
                    ).toBackupRecordV1(),
                )
                add(
                    TimeEntryEntity(
                        "time-1",
                        "task-1",
                        "device-alpha",
                        10,
                        20,
                        byteArrayOf(4, 5),
                    ).toBackupRecordV1(),
                )
                add(
                    TemplateEntity(
                        "template-1",
                        "workspace-1",
                        "Template",
                        byteArrayOf(6, 7),
                        10,
                        0,
                        "device-alpha",
                    ).toBackupRecordV1(),
                )
                add(
                    SavedViewEntity("view-1", "workspace-1", "View", byteArrayOf(8, 9))
                        .toBackupRecordV1(),
                )
                add(
                    TombstoneEntity(
                        "gone-task",
                        "task",
                        1,
                        2,
                        10,
                        0,
                        "device-alpha",
                    ).toBackupRecordV1(),
                )
            },
        )
    }

    fun tagMutation(
        tagId: String = "tag-1",
        workspaceId: String = "workspace-1",
        name: String = "Urgent",
    ): ByteArray = BackupMutationCodec.encode(
        BackupMutationPayloadV1(
            mutationKind = app.opentasks.core.domain.BackupMutationKind.UPSERT,
            record = TagEntity(tagId, workspaceId, name).toBackupRecordV1(),
            deletedFamily = null,
            deletedIdentity = null,
        ),
    )

    fun snapshotBytesAtSize(target: Int): ByteArray {
        val base = BackupSnapshotCodec.encode(snapshot())
        val marker = """,{"family":"TOMBSTONE"""".toByteArray()
        val insertion = base.indexOfSubsequence(marker)
        require(insertion >= 0)
        val prefix = base.copyOfRange(0, insertion)
        val suffix = base.copyOfRange(insertion, base.size)
        val recordCount = 25
        val structural = (0 until recordCount).map { index ->
            val id = "view-bound-${index.toString().padStart(2, '0')}"
            val before = (
                """,{"family":"SAVED_VIEW","identity":["$id"],"fields":[""" +
                    """{"name":"id","type":"STRING","value":"$id"},""" +
                    """{"name":"workspaceId","type":"STRING","value":"workspace-1"},""" +
                    """{"name":"name","type":"STRING","value":"N"},""" +
                    """{"name":"encryptedQuery","type":"BYTES","value":""""
                )
            val after = "\"}]}"
            before to after
        }
        val fixedBytes = prefix.size + suffix.size + structural.sumOf { (before, after) ->
            before.toByteArray().size + after.toByteArray().size
        }
        val fillerBytes = target - fixedBytes
        require(fillerBytes > 0)
        val lengths = splitCanonicalBase64Lengths(fillerBytes, recordCount)
        val output = ByteArray(target)
        var offset = 0
        fun append(bytes: ByteArray) {
            bytes.copyInto(output, offset)
            offset += bytes.size
        }
        append(prefix)
        structural.forEachIndexed { index, (before, after) ->
            append(before.toByteArray())
            append(ByteArray(lengths[index]) { 'A'.code.toByte() })
            append(after.toByteArray())
        }
        append(suffix)
        check(offset == target)
        return output
    }

    private fun splitCanonicalBase64Lengths(total: Int, count: Int): IntArray {
        val maximum = 2_796_203
        val lengths = IntArray(count)
        var remaining = total
        for (index in lengths.indices) {
            val slotsAfter = count - index - 1
            val minimumForRest = 0
            var length = minOf(maximum, remaining - minimumForRest)
            while (length % 4 == 1) length -= 1
            lengths[index] = length
            remaining -= length
            if (remaining <= slotsAfter * maximum) continue
            error("Insufficient Base64 capacity")
        }
        if (remaining != 0) {
            for (index in lengths.indices.reversed()) {
                val replacement = lengths[index] + remaining
                if (replacement <= maximum && replacement % 4 != 1) {
                    lengths[index] = replacement
                    remaining = 0
                    break
                }
            }
        }
        require(remaining == 0)
        return lengths
    }

    private fun task(
        id: String,
        parentTaskId: String?,
        statusId: String,
        semanticStatus: String,
        milestoneId: String?,
    ): BackupRecordV1 = TaskEntity(
        id = id,
        workspaceId = "workspace-1",
        projectId = "project-1",
        parentTaskId = parentTaskId,
        statusId = statusId,
        semanticStatus = semanticStatus,
        title = if (id == "task-1") "Résumé task 🚀" else "Child",
        descriptionCiphertext = byteArrayOf(0, 0xff.toByte()),
        priority = "HIGH",
        startEpochMillis = 1,
        startZoneId = "UTC",
        dueEpochMillis = 2,
        dueZoneId = "UTC",
        recurrenceFrequency = if (id == "task-1") "WEEKLY" else null,
        recurrenceInterval = if (id == "task-1") 1 else null,
        recurrenceWeekdays = if (id == "task-1") "MONDAY,FRIDAY" else null,
        recurrenceCount = if (id == "task-1") 2 else null,
        recurrenceEndDate = null,
        recurrenceSeriesId = if (id == "task-1") "series-1" else null,
        recurrenceAnchorEpochMillis = if (id == "task-1") 1 else null,
        recurrenceAnchorZoneId = if (id == "task-1") "UTC" else null,
        recurrenceOccurrenceIndex = if (id == "task-1") 0 else null,
        estimateSeconds = 60,
        milestoneId = milestoneId,
        completedAtEpochMillis = null,
        deletedAtEpochMillis = null,
        revisionWallMillis = 10,
        revisionLogical = 0,
        revisionDeviceId = "device-alpha",
    ).toBackupRecordV1()
}

private fun BackupSnapshotPayloadV1.record(
    family: BackupRecordFamily,
    identity: String,
): BackupRecordV1 = records.single { it.family == family && it.identity == listOf(identity) }

private fun BackupSnapshotPayloadV1.replace(record: BackupRecordV1): BackupSnapshotPayloadV1 =
    copy(records = records.map { existing ->
        if (existing.family == record.family && existing.identity == record.identity) {
            record
        } else {
            existing
        }
    })

private fun BackupSnapshotPayloadV1.update(
    family: BackupRecordFamily,
    identity: String,
    fieldName: String,
    value: String?,
    type: BackupFieldType = if (value == null) BackupFieldType.NULL else BackupFieldType.STRING,
): BackupSnapshotPayloadV1 = updateComposite(
    family = family,
    identity = listOf(identity),
    fieldName = fieldName,
    value = value,
    type = type,
)

private fun BackupSnapshotPayloadV1.updateComposite(
    family: BackupRecordFamily,
    identity: List<String>,
    fieldName: String,
    value: String?,
    type: BackupFieldType = if (value == null) BackupFieldType.NULL else BackupFieldType.STRING,
): BackupSnapshotPayloadV1 {
    val original = records.single { it.family == family && it.identity == identity }
    val updated = original.copy(
        fields = original.fields.map { field ->
            if (field.name == fieldName) BackupFieldV1(fieldName, type, value) else field
        },
    )
    return replace(updated)
}

private fun compareIdentities(left: List<String>, right: List<String>): Int {
    left.zip(right).forEach { (leftPart, rightPart) ->
        val result = leftPart.compareTo(rightPart)
        if (result != 0) return result
    }
    return left.size.compareTo(right.size)
}

internal fun ByteArray.indexOfSubsequence(target: ByteArray): Int {
    if (target.isEmpty()) return 0
    for (start in 0..size - target.size) {
        if (target.indices.all { offset -> this[start + offset] == target[offset] }) {
            return start
        }
    }
    return -1
}
