package app.opentasks.core.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

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
    val projectId: ProjectId?,
    val name: String,
    val semanticStatus: SemanticStatus,
    val rank: String,
    val archivedAt: Instant? = null,
) {
    companion object {
        fun defaultId(
            projectId: ProjectId?,
            semanticStatus: SemanticStatus,
        ): WorkflowStatusId = WorkflowStatusId(
            "workflow:${projectId?.value ?: "inbox"}:" +
                semanticStatus.name.lowercase(Locale.ROOT),
        )

        fun defaults(projectId: ProjectId?): List<WorkflowStatus> =
            listOf(
                SemanticStatus.BACKLOG to "Backlog",
                SemanticStatus.PLANNED to "Planned",
                SemanticStatus.STARTED to "In progress",
                SemanticStatus.BLOCKED to "Blocked",
                SemanticStatus.COMPLETED to "Done",
            ).mapIndexed { index, (semanticStatus, name) ->
                WorkflowStatus(
                    id = defaultId(projectId, semanticStatus),
                    projectId = projectId,
                    name = name,
                    semanticStatus = semanticStatus,
                    rank = "a$index",
                )
            }
    }
}

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
    val dependencyIds: Set<TaskId> = emptySet(),
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
) {
    companion object {
        fun primaryId(taskId: TaskId): String = "reminder:${taskId.value}"
    }
}

data class Attachment(
    val id: AttachmentId,
    val taskId: TaskId,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val contentHash: String,
    val blobSetId: BlobSetId?,
    val chunkCount: Int,
    val deletedAt: Instant?,
    val revision: Revision,
)

data class RetiredBlobSet(
    val blobSetId: BlobSetId,
    val chunkCount: Int,
    val retiredAt: Instant,
    val revision: Revision,
)

data class Note(
    val id: NoteId,
    val taskId: TaskId?,
    val projectId: ProjectId?,
    val body: String,
    val createdAt: Instant,
    val editedAt: Instant?,
    val revision: Revision,
)

enum class ActivityKind {
    RECORD_CREATED,
    STATUS_CHANGED,
    COMPLETED,
    REOPENED,
    PROJECT_MOVED,
    MILESTONE_CHANGED,
    DEPENDENCY_ADDED,
    DEPENDENCY_REMOVED,
    BINNED,
    RESTORED,
    ATTACHMENT_ADDED,
    ATTACHMENT_REMOVED,
    REVIEWED,
}

data class ActivityEntry(
    val id: String,
    val taskId: TaskId?,
    val projectId: ProjectId?,
    val kind: ActivityKind,
    val body: String,
    val createdAt: Instant,
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
    val projectName: String,
    val projectSummary: String,
    val projectDueOffsetDays: Long?,
    val workflowStatuses: List<TemplateWorkflowStatus>,
    val milestones: List<TemplateMilestone>,
    val tasks: List<TemplateTask>,
    val revision: Revision,
)

data class TemplateWorkflowStatus(
    val key: String,
    val name: String,
    val semanticStatus: SemanticStatus,
    val rank: String,
)

data class TemplateMilestone(
    val key: String,
    val name: String,
    val dueOffsetDays: Long?,
)

data class RelativeZonedMoment(
    val dayOffset: Long,
    val secondOfDay: Int,
    val zoneId: String,
)

data class TemplateRecurrence(
    val frequency: RecurrenceFrequency,
    val interval: Int,
    val weekdays: Set<java.time.DayOfWeek>,
    val count: Int?,
    val endOffsetDays: Long?,
)

data class TemplateChecklistItem(
    val key: String,
    val text: String,
    val rank: String,
)

data class TemplateTask(
    val key: String,
    val parentKey: String?,
    val statusKey: String,
    val title: String,
    val description: String,
    val priority: Priority,
    val start: RelativeZonedMoment?,
    val due: RelativeZonedMoment?,
    val recurrence: TemplateRecurrence?,
    val estimateSeconds: Long?,
    val milestoneKey: String?,
    val tagNames: Set<String>,
    val checklist: List<TemplateChecklistItem>,
    val dependencyKeys: Set<String>,
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
