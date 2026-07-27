package app.opentasks.core.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "vaults", primaryKeys = ["id"])
data class VaultEntity(
    val id: String,
    val storageMode: String,
    val createdAtEpochMillis: Long,
    val schemaVersion: Int,
    val cryptoVersion: Int,
    val minimumReaderVersion: Int,
)

@Entity(tableName = "workspaces", primaryKeys = ["id"])
data class WorkspaceEntity(
    val id: String,
    val vaultId: String,
    val ownerId: String,
    val name: String,
)

@Entity(tableName = "members", primaryKeys = ["id"])
data class MemberEntity(
    val id: String,
    val displayName: String,
)

@Entity(tableName = "projects", primaryKeys = ["id"])
data class ProjectEntity(
    val id: String,
    val workspaceId: String,
    val name: String,
    val summary: String,
    val health: String,
    val dueDate: String?,
    val completedTasks: Int,
    val totalTasks: Int,
    val archivedAtEpochMillis: Long?,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val revisionDeviceId: String,
)

@Entity(
    tableName = "workflow_statuses",
    primaryKeys = ["id"],
    indices = [Index(value = ["projectId", "rank"], unique = true)],
)
data class WorkflowStatusEntity(
    val id: String,
    val projectId: String?,
    val name: String,
    val semanticStatus: String,
    val rank: String,
    val archivedAtEpochMillis: Long?,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val revisionDeviceId: String,
)

@Entity(tableName = "milestones", primaryKeys = ["id"])
data class MilestoneEntity(
    val id: String,
    val projectId: String,
    val name: String,
    val dueDate: String?,
    val completedAtEpochMillis: Long?,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val revisionDeviceId: String,
)

@Entity(
    tableName = "tasks",
    primaryKeys = ["id"],
    indices = [
        Index("workspaceId"),
        Index("projectId"),
        Index("statusId"),
        Index("dueEpochMillis"),
        Index("deletedAtEpochMillis"),
    ],
)
data class TaskEntity(
    val id: String,
    val workspaceId: String,
    val projectId: String?,
    val parentTaskId: String?,
    val statusId: String,
    val semanticStatus: String,
    val title: String,
    val descriptionCiphertext: ByteArray,
    val priority: String,
    val startEpochMillis: Long?,
    val startZoneId: String?,
    val dueEpochMillis: Long?,
    val dueZoneId: String?,
    val recurrenceFrequency: String?,
    val recurrenceInterval: Int?,
    val recurrenceWeekdays: String?,
    val recurrenceCount: Int?,
    val recurrenceEndDate: String?,
    val recurrenceSeriesId: String?,
    val recurrenceAnchorEpochMillis: Long?,
    val recurrenceAnchorZoneId: String?,
    val recurrenceOccurrenceIndex: Int?,
    val estimateSeconds: Long?,
    val milestoneId: String?,
    val completedAtEpochMillis: Long?,
    val deletedAtEpochMillis: Long?,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val revisionDeviceId: String,
)

@Entity(
    tableName = "checklist_items",
    primaryKeys = ["id"],
    indices = [Index("taskId"), Index(value = ["taskId", "rank"], unique = true)],
)
data class ChecklistItemEntity(
    val id: String,
    val taskId: String,
    val text: String,
    val completed: Boolean,
    val rank: String,
)

@Entity(
    tableName = "task_dependencies",
    primaryKeys = ["taskId", "dependsOnTaskId"],
)
data class TaskDependencyEntity(
    val taskId: String,
    val dependsOnTaskId: String,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val revisionDeviceId: String,
)

@Entity(tableName = "tags", primaryKeys = ["id"], indices = [Index("name")])
data class TagEntity(
    val id: String,
    val workspaceId: String,
    val name: String,
)

@Entity(tableName = "task_tags", primaryKeys = ["taskId", "tagId"])
data class TaskTagEntity(
    val taskId: String,
    val tagId: String,
    val present: Boolean,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val revisionDeviceId: String,
)

@Entity(tableName = "reminders", primaryKeys = ["id"], indices = [Index("triggerAtEpochMillis")])
data class ReminderEntity(
    val id: String,
    val taskId: String,
    val triggerAtEpochMillis: Long,
    val zoneId: String,
    val precise: Boolean,
)

@Entity(tableName = "attachments", primaryKeys = ["id"], indices = [Index("taskId")])
data class AttachmentEntity(
    val id: String,
    val taskId: String,
    val displayNameCiphertext: ByteArray,
    val mimeType: String,
    val byteCount: Long,
    val contentHash: String,
    val keepOffline: Boolean,
)

@Entity(tableName = "activity_entries", primaryKeys = ["id"], indices = [Index("taskId")])
data class ActivityEntryEntity(
    val id: String,
    val taskId: String?,
    val projectId: String?,
    val kind: String,
    val bodyCiphertext: ByteArray,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "time_entries", primaryKeys = ["id"], indices = [Index("taskId")])
data class TimeEntryEntity(
    val id: String,
    val taskId: String,
    val deviceId: String,
    val startedAtEpochMillis: Long,
    val stoppedAtEpochMillis: Long?,
    val noteCiphertext: ByteArray,
)

@Entity(tableName = "templates", primaryKeys = ["id"])
data class TemplateEntity(
    val id: String,
    val workspaceId: String,
    val name: String,
    val encryptedPayload: ByteArray,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val revisionDeviceId: String,
)

@Entity(tableName = "saved_views", primaryKeys = ["id"])
data class SavedViewEntity(
    val id: String,
    val workspaceId: String,
    val name: String,
    val encryptedQuery: ByteArray,
)

@Entity(
    tableName = "sync_operations",
    primaryKeys = ["id"],
    indices = [Index("uploadedAtEpochMillis"), Index("revisionWallMillis")],
)
data class SyncOperationEntity(
    val id: String,
    val deviceId: String,
    val objectId: String,
    val objectType: String,
    val encryptedPayload: ByteArray,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val uploadedAtEpochMillis: Long?,
)

@Entity(tableName = "tombstones", primaryKeys = ["objectId", "objectType"])
data class TombstoneEntity(
    val objectId: String,
    val objectType: String,
    val deletedAtEpochMillis: Long,
    val purgeAfterEpochMillis: Long,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val revisionDeviceId: String,
)
