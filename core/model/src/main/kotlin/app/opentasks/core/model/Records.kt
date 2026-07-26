package app.opentasks.core.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class StorageMode {
    LOCAL,
    DRIVE_PRIMARY,
}

enum class SemanticStatus {
    BACKLOG,
    PLANNED,
    STARTED,
    BLOCKED,
    COMPLETED,
}

enum class Priority {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    URGENT,
}

enum class SyncPhase {
    PREPARING,
    UPLOADING,
    DOWNLOADING,
    MERGING,
    VERIFYING,
}

enum class SyncBlockReason {
    AUTHENTICATION,
    OFFLINE,
    QUOTA,
    CHECKSUM,
    DECRYPTION,
    RETRY_EXHAUSTED,
}

sealed interface SyncState {
    data object LocalOnly : SyncState
    data object Synced : SyncState
    data class Pending(val operations: Int) : SyncState
    data class Running(val phase: SyncPhase, val progress: Float?) : SyncState
    data class Blocked(val reason: SyncBlockReason) : SyncState
}

enum class SyncReason {
    STARTUP,
    EDIT,
    USER_REFRESH,
    PERIODIC,
    PROVIDER_MIGRATION,
}

data class Revision(
    val deviceId: DeviceId,
    val wallTimeMillis: Long,
    val logicalCounter: Int,
)

data class ZonedMoment(
    val instant: Instant,
    val zoneId: String,
) {
    fun zone(): ZoneId = ZoneId.of(zoneId)
}

data class Vault(
    val id: VaultId,
    val storageMode: StorageMode,
    val createdAt: Instant,
    val schemaVersion: Int,
    val cryptoVersion: Int,
    val minimumReaderVersion: Int,
)

data class Workspace(
    val id: WorkspaceId,
    val vaultId: VaultId,
    val ownerId: MemberId,
    val name: String,
)

data class Member(
    val id: MemberId,
    val displayName: String,
)

data class WorkflowStatus(
    val id: WorkflowStatusId,
    val projectId: ProjectId,
    val name: String,
    val semanticStatus: SemanticStatus,
    val rank: String,
    val archivedAt: Instant? = null,
)

data class Project(
    val id: ProjectId,
    val workspaceId: WorkspaceId,
    val name: String,
    val summary: String,
    val status: ProjectHealth,
    val dueDate: LocalDate?,
    val completedTasks: Int,
    val totalTasks: Int,
    val archivedAt: Instant? = null,
) {
    val progress: Float
        get() = if (totalTasks == 0) 0f else completedTasks.toFloat() / totalTasks
}

enum class ProjectHealth {
    ON_TRACK,
    AT_RISK,
    BLOCKED,
    COMPLETE,
}

data class Milestone(
    val id: MilestoneId,
    val projectId: ProjectId,
    val name: String,
    val dueDate: LocalDate?,
    val completedAt: Instant? = null,
)

data class ChecklistItem(
    val id: String,
    val text: String,
    val completed: Boolean,
    val rank: String,
)

data class Task(
    val id: TaskId,
    val workspaceId: WorkspaceId,
    val projectId: ProjectId?,
    val parentTaskId: TaskId? = null,
    val statusId: WorkflowStatusId,
    val semanticStatus: SemanticStatus,
    val title: String,
    val description: String = "",
    val priority: Priority = Priority.NONE,
    val start: ZonedMoment? = null,
    val due: ZonedMoment? = null,
    val recurrence: RecurrenceRule? = null,
    val recurrenceSeriesId: TaskId? = null,
    val recurrenceAnchor: ZonedMoment? = null,
    val recurrenceOccurrenceIndex: Int? = null,
    val estimate: Duration? = null,
    val milestoneId: MilestoneId? = null,
    val tagIds: Set<TagId> = emptySet(),
    val checklist: List<ChecklistItem> = emptyList(),
    val blockedBy: Set<TaskId> = emptySet(),
    val completedAt: Instant? = null,
    val deletedAt: Instant? = null,
    val revision: Revision,
) {
    val isCompleted: Boolean
        get() = semanticStatus == SemanticStatus.COMPLETED

    val isBlocked: Boolean
        get() = blockedBy.isNotEmpty() || semanticStatus == SemanticStatus.BLOCKED
}

data class TaskDependency(
    val taskId: TaskId,
    val dependsOnTaskId: TaskId,
    val revision: Revision,
)

data class Tag(
    val id: TagId,
    val workspaceId: WorkspaceId,
    val name: String,
)

data class Reminder(
    val id: String,
    val taskId: TaskId,
    val triggerAt: ZonedMoment,
    val precise: Boolean,
)

data class Attachment(
    val id: AttachmentId,
    val taskId: TaskId,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val contentHash: String,
    val keepOffline: Boolean,
)

data class ActivityEntry(
    val id: String,
    val taskId: TaskId?,
    val projectId: ProjectId?,
    val kind: String,
    val body: String,
    val createdAt: Instant,
    val immutable: Boolean = true,
)

data class TimeEntry(
    val id: TimeEntryId,
    val taskId: TaskId,
    val deviceId: DeviceId,
    val startedAt: Instant,
    val stoppedAt: Instant?,
    val note: String = "",
) {
    fun duration(now: Instant): Duration = Duration.between(startedAt, stoppedAt ?: now)
}

data class Template(
    val id: TemplateId,
    val workspaceId: WorkspaceId,
    val name: String,
    val project: Project,
    val tasks: List<Task>,
    val milestones: List<Milestone>,
)

data class SavedView(
    val id: SavedViewId,
    val workspaceId: WorkspaceId,
    val name: String,
    val query: SearchQuery,
)

data class Tombstone(
    val objectId: String,
    val objectType: String,
    val deletedAt: Instant,
    val purgeAfter: Instant,
    val revision: Revision,
)

data class SyncOperation(
    val id: String,
    val deviceId: DeviceId,
    val objectId: String,
    val objectType: String,
    val payload: ByteArray,
    val revision: Revision,
)
