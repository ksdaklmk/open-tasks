package app.opentasks.core.data.backup

import app.opentasks.core.data.TemplatePayloadCodec
import app.opentasks.core.data.db.ChecklistItemEntity
import app.opentasks.core.data.db.RetiredBlobSetEntity
import app.opentasks.core.data.db.TaskDependencyEntity
import app.opentasks.core.data.db.TaskTagEntity
import app.opentasks.core.data.db.TemplateEntity
import app.opentasks.core.data.db.TimeEntryEntity
import app.opentasks.core.data.db.TombstoneEntity
import app.opentasks.core.data.db.toEntity
import app.opentasks.core.domain.BackupJournalEntry
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.Task
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WorkspaceSnapshot
import java.util.UUID

internal fun interface InMemoryJournalAppendBoundary {
    suspend fun append(entry: BackupJournalEntry)
}

internal class InMemoryBackupJournal(
    private val operationId: () -> String = { UUID.randomUUID().toString() },
    private val appendBoundary: InMemoryJournalAppendBoundary =
        InMemoryJournalAppendBoundary {},
) {
    private val mutableEntries = mutableListOf<BackupJournalEntry>()

    var currentGeneration: Long = 0
        private set

    val entries: List<BackupJournalEntry>
        get() = mutableEntries.toList()

    suspend fun appendChanges(
        before: List<BackupRecordV1>,
        after: List<BackupRecordV1>,
        sourceDeviceId: DeviceId,
    ) {
        val changes = diffBackupRecords(before, after)
        if (changes.isEmpty()) return
        val nextGeneration = Math.addExact(currentGeneration, 1)
        val staged = changes.mapIndexed { sequence, change ->
            val payload = when (change) {
                is BackupRecordChange.Upsert -> BackupMutationPayloadV1(
                    mutationKind = BackupMutationKind.UPSERT,
                    record = change.record,
                    deletedFamily = null,
                    deletedIdentity = null,
                )
                is BackupRecordChange.Delete -> BackupMutationPayloadV1(
                    mutationKind = BackupMutationKind.DELETE,
                    record = null,
                    deletedFamily = change.family,
                    deletedIdentity = change.identity,
                )
            }
            val revisionRecord = when (change) {
                is BackupRecordChange.Upsert -> change.record
                is BackupRecordChange.Delete -> change.previous
            }
            val revision = revisionRecord.journalRevision(sourceDeviceId)
            BackupJournalEntry(
                operationId = operationId(),
                vaultId = VaultId("vault-primary"),
                generation = BackupGeneration(nextGeneration),
                sequence = sequence,
                payloadFormatVersion = 1,
                mutationKind = payload.mutationKind,
                objectId = change.identity.toInMemoryObjectId(),
                objectType = change.family.name,
                payload = BackupMutationCodec.encode(payload),
                revision = revision,
            )
        }
        staged.forEach { entry -> appendBoundary.append(entry) }
        mutableEntries += staged
        currentGeneration = nextGeneration
    }
}

internal fun WorkspaceSnapshot.toBackupRecords(
    tombstones: List<TombstoneEntity> = emptyList(),
    retainedTaskTags: List<TaskTagEntity>? = null,
): List<BackupRecordV1> {
    val syntheticRevision = Revision(
        deviceId = DeviceId("in-memory"),
        wallTimeMillis = 0,
        logicalCounter = 0,
    )
    return buildList {
        projects.mapTo(this) { it.toEntity(syntheticRevision).toBackupRecordV1() }
        workflowStatuses.mapTo(this) {
            it.toEntity(syntheticRevision).toBackupRecordV1()
        }
        milestones.mapTo(this) { it.toEntity(syntheticRevision).toBackupRecordV1() }
        tasks.mapTo(this) { it.toEntity().toBackupRecordV1() }
        tasks.flatMapTo(this) { task ->
            task.checklist.map { item ->
                ChecklistItemEntity(
                    id = item.id,
                    taskId = task.id.value,
                    text = item.text,
                    completed = item.completed,
                    rank = item.rank,
                ).toBackupRecordV1()
            }
        }
        tasks.flatMapTo(this) { task ->
            task.dependencyIds.map { dependencyId ->
                TaskDependencyEntity(
                    taskId = task.id.value,
                    dependsOnTaskId = dependencyId.value,
                    revisionWallMillis = 0,
                    revisionLogical = 0,
                    revisionDeviceId = "in-memory",
                ).toBackupRecordV1()
            }
        }
        tags.mapTo(this) { it.toEntity().toBackupRecordV1() }
        (
            retainedTaskTags ?: tasks.flatMap { task ->
                task.tagIds.map { tagId ->
                    TaskTagEntity(
                        taskId = task.id.value,
                        tagId = tagId.value,
                        present = true,
                        revisionWallMillis = task.revision.wallTimeMillis,
                        revisionLogical = task.revision.logicalCounter,
                        revisionDeviceId = task.revision.deviceId.value,
                    )
                }
            }
            ).mapTo(this) { it.toBackupRecordV1() }
        reminders.mapTo(this) { it.toEntity().toBackupRecordV1() }
        timeEntries.mapTo(this) {
            TimeEntryEntity(
                id = it.id.value,
                taskId = it.taskId.value,
                deviceId = it.deviceId.value,
                startedAtEpochMillis = it.startedAt.toEpochMilli(),
                stoppedAtEpochMillis = it.stoppedAt?.toEpochMilli(),
                noteCiphertext = it.note.toByteArray(Charsets.UTF_8),
            ).toBackupRecordV1()
        }
        templates.mapTo(this) { template ->
            TemplateEntity(
                id = template.id.value,
                workspaceId = template.workspaceId.value,
                name = template.name,
                encryptedPayload = TemplatePayloadCodec.encode(template),
                revisionWallMillis = template.revision.wallTimeMillis,
                revisionLogical = template.revision.logicalCounter,
                revisionDeviceId = template.revision.deviceId.value,
            ).toBackupRecordV1()
        }
        notes.mapTo(this) { it.toEntity().toBackupRecordV1() }
        attachments.mapTo(this) { it.toEntity().toBackupRecordV1() }
        activityEntries.mapTo(this) { it.toEntity().toBackupRecordV1() }
        retiredBlobSets.mapTo(this) { retired ->
            RetiredBlobSetEntity(
                blobSetId = retired.blobSetId.value,
                chunkCount = retired.chunkCount,
                retiredAtEpochMillis = retired.retiredAt.toEpochMilli(),
                revisionWallMillis = retired.revision.wallTimeMillis,
                revisionLogical = retired.revision.logicalCounter,
                revisionDeviceId = retired.revision.deviceId.value,
            ).toBackupRecordV1()
        }
        tombstones.mapTo(this) { it.toBackupRecordV1() }
    }
}

private fun BackupRecordV1.journalRevision(fallback: DeviceId): Revision {
    val values = fields.associate { it.name to it.value }
    return Revision(
        deviceId = DeviceId(values["revisionDeviceId"] ?: fallback.value),
        wallTimeMillis = values["revisionWallMillis"]?.toLong() ?: 0,
        logicalCounter = values["revisionLogical"]?.toInt() ?: 0,
    )
}

private fun List<String>.toInMemoryObjectId(): String =
    if (size == 1) {
        single()
    } else {
        joinToString(separator = "|") { component -> "${component.length}:$component" }
    }
