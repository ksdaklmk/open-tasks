package app.opentasks.core.data.backup

import app.opentasks.core.data.db.ActivityEntryEntity
import app.opentasks.core.data.db.AttachmentEntity
import app.opentasks.core.data.db.ChecklistItemEntity
import app.opentasks.core.data.db.MemberEntity
import app.opentasks.core.data.db.MilestoneEntity
import app.opentasks.core.data.db.NoteEntity
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
import app.opentasks.core.domain.BackupMutationKind
import kotlinx.serialization.Serializable
import java.util.Base64

@Serializable
data class BackupFieldV1(
    val name: String,
    val type: BackupFieldType,
    val value: String?,
)

@Serializable
data class BackupRecordV1(
    val family: BackupRecordFamily,
    val identity: List<String>,
    val fields: List<BackupFieldV1>,
)

@Serializable
data class BackupMutationPayloadV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val mutationKind: BackupMutationKind,
    val record: BackupRecordV1?,
    val deletedFamily: BackupRecordFamily?,
    val deletedIdentity: List<String>?,
)

@Serializable
enum class BackupFieldType {
    STRING,
    LONG,
    INT,
    BOOLEAN,
    BYTES,
    NULL,
}

@Serializable
enum class BackupRecordFamily {
    VAULT,
    WORKSPACE,
    MEMBER,
    PROJECT,
    WORKFLOW_STATUS,
    MILESTONE,
    TASK,
    CHECKLIST_ITEM,
    TASK_DEPENDENCY,
    TAG,
    TASK_TAG,
    REMINDER,
    ATTACHMENT,
    ACTIVITY_ENTRY,
    TIME_ENTRY,
    TEMPLATE,
    SAVED_VIEW,
    NOTE,
    TOMBSTONE,
}

internal fun VaultEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.VAULT,
    identity = listOf(id),
    stringField("id", id),
    longField("createdAtEpochMillis", createdAtEpochMillis),
    intField("schemaVersion", schemaVersion),
    intField("cryptoVersion", cryptoVersion),
    intField("minimumReaderVersion", minimumReaderVersion),
)

internal fun WorkspaceEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.WORKSPACE,
    identity = listOf(id),
    stringField("id", id),
    stringField("vaultId", vaultId),
    stringField("ownerId", ownerId),
    stringField("name", name),
)

internal fun MemberEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.MEMBER,
    identity = listOf(id),
    stringField("id", id),
    stringField("displayName", displayName),
)

internal fun ProjectEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.PROJECT,
    identity = listOf(id),
    stringField("id", id),
    stringField("workspaceId", workspaceId),
    stringField("name", name),
    stringField("summary", summary),
    stringField("health", health),
    nullableStringField("dueDate", dueDate),
    intField("completedTasks", completedTasks),
    intField("totalTasks", totalTasks),
    nullableLongField("archivedAtEpochMillis", archivedAtEpochMillis),
    longField("revisionWallMillis", revisionWallMillis),
    intField("revisionLogical", revisionLogical),
    stringField("revisionDeviceId", revisionDeviceId),
)

internal fun WorkflowStatusEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.WORKFLOW_STATUS,
    identity = listOf(id),
    stringField("id", id),
    nullableStringField("projectId", projectId),
    stringField("name", name),
    stringField("semanticStatus", semanticStatus),
    stringField("rank", rank),
    nullableLongField("archivedAtEpochMillis", archivedAtEpochMillis),
    longField("revisionWallMillis", revisionWallMillis),
    intField("revisionLogical", revisionLogical),
    stringField("revisionDeviceId", revisionDeviceId),
)

internal fun MilestoneEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.MILESTONE,
    identity = listOf(id),
    stringField("id", id),
    stringField("projectId", projectId),
    stringField("name", name),
    nullableStringField("dueDate", dueDate),
    nullableLongField("completedAtEpochMillis", completedAtEpochMillis),
    longField("revisionWallMillis", revisionWallMillis),
    intField("revisionLogical", revisionLogical),
    stringField("revisionDeviceId", revisionDeviceId),
)

internal fun TaskEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.TASK,
    identity = listOf(id),
    stringField("id", id),
    stringField("workspaceId", workspaceId),
    nullableStringField("projectId", projectId),
    nullableStringField("parentTaskId", parentTaskId),
    stringField("statusId", statusId),
    stringField("semanticStatus", semanticStatus),
    stringField("title", title),
    bytesField("descriptionCiphertext", descriptionCiphertext),
    stringField("priority", priority),
    nullableLongField("startEpochMillis", startEpochMillis),
    nullableStringField("startZoneId", startZoneId),
    nullableLongField("dueEpochMillis", dueEpochMillis),
    nullableStringField("dueZoneId", dueZoneId),
    nullableStringField("recurrenceFrequency", recurrenceFrequency),
    nullableIntField("recurrenceInterval", recurrenceInterval),
    nullableStringField("recurrenceWeekdays", recurrenceWeekdays),
    nullableIntField("recurrenceCount", recurrenceCount),
    nullableStringField("recurrenceEndDate", recurrenceEndDate),
    nullableStringField("recurrenceSeriesId", recurrenceSeriesId),
    nullableLongField("recurrenceAnchorEpochMillis", recurrenceAnchorEpochMillis),
    nullableStringField("recurrenceAnchorZoneId", recurrenceAnchorZoneId),
    nullableIntField("recurrenceOccurrenceIndex", recurrenceOccurrenceIndex),
    nullableLongField("estimateSeconds", estimateSeconds),
    nullableStringField("milestoneId", milestoneId),
    nullableLongField("completedAtEpochMillis", completedAtEpochMillis),
    nullableLongField("deletedAtEpochMillis", deletedAtEpochMillis),
    longField("revisionWallMillis", revisionWallMillis),
    intField("revisionLogical", revisionLogical),
    stringField("revisionDeviceId", revisionDeviceId),
)

internal fun ChecklistItemEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.CHECKLIST_ITEM,
    identity = listOf(id),
    stringField("id", id),
    stringField("taskId", taskId),
    stringField("text", text),
    booleanField("completed", completed),
    stringField("rank", rank),
)

internal fun TaskDependencyEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.TASK_DEPENDENCY,
    identity = listOf(taskId, dependsOnTaskId),
    stringField("taskId", taskId),
    stringField("dependsOnTaskId", dependsOnTaskId),
    longField("revisionWallMillis", revisionWallMillis),
    intField("revisionLogical", revisionLogical),
    stringField("revisionDeviceId", revisionDeviceId),
)

internal fun TagEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.TAG,
    identity = listOf(id),
    stringField("id", id),
    stringField("workspaceId", workspaceId),
    stringField("name", name),
)

internal fun TaskTagEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.TASK_TAG,
    identity = listOf(taskId, tagId),
    stringField("taskId", taskId),
    stringField("tagId", tagId),
    booleanField("present", present),
    longField("revisionWallMillis", revisionWallMillis),
    intField("revisionLogical", revisionLogical),
    stringField("revisionDeviceId", revisionDeviceId),
)

internal fun ReminderEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.REMINDER,
    identity = listOf(id),
    stringField("id", id),
    stringField("taskId", taskId),
    longField("triggerAtEpochMillis", triggerAtEpochMillis),
    stringField("zoneId", zoneId),
    booleanField("precise", precise),
)

internal fun AttachmentEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.ATTACHMENT,
    identity = listOf(id),
    stringField("id", id),
    stringField("taskId", taskId),
    bytesField("displayNameCiphertext", displayNameCiphertext),
    stringField("mimeType", mimeType),
    longField("byteCount", byteCount),
    stringField("contentHash", contentHash),
    nullableStringField("blobSetId", blobSetId),
    intField("chunkCount", chunkCount),
    nullableLongField("deletedAtEpochMillis", deletedAtEpochMillis),
    longField("revisionWallMillis", revisionWallMillis),
    intField("revisionLogical", revisionLogical),
    stringField("revisionDeviceId", revisionDeviceId),
)

internal fun ActivityEntryEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.ACTIVITY_ENTRY,
    identity = listOf(id),
    stringField("id", id),
    nullableStringField("taskId", taskId),
    nullableStringField("projectId", projectId),
    stringField("kind", kind),
    bytesField("bodyCiphertext", bodyCiphertext),
    longField("createdAtEpochMillis", createdAtEpochMillis),
)

internal fun TimeEntryEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.TIME_ENTRY,
    identity = listOf(id),
    stringField("id", id),
    stringField("taskId", taskId),
    stringField("deviceId", deviceId),
    longField("startedAtEpochMillis", startedAtEpochMillis),
    nullableLongField("stoppedAtEpochMillis", stoppedAtEpochMillis),
    bytesField("noteCiphertext", noteCiphertext),
)

internal fun TemplateEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.TEMPLATE,
    identity = listOf(id),
    stringField("id", id),
    stringField("workspaceId", workspaceId),
    stringField("name", name),
    bytesField("encryptedPayload", encryptedPayload),
    longField("revisionWallMillis", revisionWallMillis),
    intField("revisionLogical", revisionLogical),
    stringField("revisionDeviceId", revisionDeviceId),
)

internal fun SavedViewEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.SAVED_VIEW,
    identity = listOf(id),
    stringField("id", id),
    stringField("workspaceId", workspaceId),
    stringField("name", name),
    bytesField("encryptedQuery", encryptedQuery),
)

internal fun NoteEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.NOTE,
    identity = listOf(id),
    stringField("id", id),
    nullableStringField("taskId", taskId),
    nullableStringField("projectId", projectId),
    bytesField("bodyCiphertext", bodyCiphertext),
    longField("createdAtEpochMillis", createdAtEpochMillis),
    nullableLongField("editedAtEpochMillis", editedAtEpochMillis),
    longField("revisionWallMillis", revisionWallMillis),
    intField("revisionLogical", revisionLogical),
    stringField("revisionDeviceId", revisionDeviceId),
)

internal fun TombstoneEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.TOMBSTONE,
    identity = listOf(objectId, objectType),
    stringField("objectId", objectId),
    stringField("objectType", objectType),
    longField("deletedAtEpochMillis", deletedAtEpochMillis),
    longField("purgeAfterEpochMillis", purgeAfterEpochMillis),
    longField("revisionWallMillis", revisionWallMillis),
    intField("revisionLogical", revisionLogical),
    stringField("revisionDeviceId", revisionDeviceId),
)

private fun record(
    family: BackupRecordFamily,
    identity: List<String>,
    vararg fields: BackupFieldV1,
): BackupRecordV1 = BackupRecordV1(
    family = family,
    identity = identity,
    fields = fields.toList(),
)

private fun stringField(name: String, value: String): BackupFieldV1 =
    BackupFieldV1(name, BackupFieldType.STRING, value)

private fun nullableStringField(name: String, value: String?): BackupFieldV1 =
    value?.let { stringField(name, it) } ?: nullField(name)

private fun longField(name: String, value: Long): BackupFieldV1 =
    BackupFieldV1(name, BackupFieldType.LONG, value.toString())

private fun nullableLongField(name: String, value: Long?): BackupFieldV1 =
    value?.let { longField(name, it) } ?: nullField(name)

private fun intField(name: String, value: Int): BackupFieldV1 =
    BackupFieldV1(name, BackupFieldType.INT, value.toString())

private fun nullableIntField(name: String, value: Int?): BackupFieldV1 =
    value?.let { intField(name, it) } ?: nullField(name)

private fun booleanField(name: String, value: Boolean): BackupFieldV1 =
    BackupFieldV1(name, BackupFieldType.BOOLEAN, value.toString())

private fun bytesField(name: String, value: ByteArray): BackupFieldV1 =
    BackupFieldV1(
        name = name,
        type = BackupFieldType.BYTES,
        value = Base64.getEncoder().withoutPadding().encodeToString(value),
    )

private fun nullField(name: String): BackupFieldV1 =
    BackupFieldV1(name, BackupFieldType.NULL, null)
