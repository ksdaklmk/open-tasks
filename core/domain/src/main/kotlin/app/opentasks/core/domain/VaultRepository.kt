package app.opentasks.core.domain

import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.Milestone
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.Note
import app.opentasks.core.model.NoteId
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.SavedView
import app.opentasks.core.model.SavedViewId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.Template
import app.opentasks.core.model.TemplateId
import app.opentasks.core.model.TimeEntry
import app.opentasks.core.model.TimeEntryId
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class ImportedTaskRow(
    val sourceRowNumber: Int,
    val title: String,
    val projectName: String?,
    val statusName: String?,
    val priority: Priority,
    val start: ZonedMoment?,
    val due: ZonedMoment?,
    val completedAt: Instant?,
    val estimateMinutes: Long?,
    val tagNames: List<String>,
    val description: String,
)

data class ImportedTaskReceipt(
    val taskId: TaskId,
    val expectedRevision: Revision,
    val expectedTagIds: Set<TagId>,
    val activityEntryId: String,
)

data class ImportedProjectReceipt(
    val project: Project,
    val statuses: List<WorkflowStatus>,
    val activityEntryId: String,
)

data class ImportedTagReceipt(val tag: Tag)

data class ImportReceipt(
    val tasks: List<ImportedTaskReceipt>,
    val projects: List<ImportedProjectReceipt>,
    val tags: List<ImportedTagReceipt>,
)

sealed interface DomainCommand {
    data class ImportTasks(val rows: List<ImportedTaskRow>) : DomainCommand

    /** Repository-produced Undo only; never constructed by UI code. */
    data class RemoveImportedRecords(val receipt: ImportReceipt) : DomainCommand

    data class CreateProject(
        val projectId: ProjectId,
        val name: String,
        val summary: String = "",
        val health: ProjectHealth = ProjectHealth.ON_TRACK,
        val dueDate: LocalDate? = null,
    ) : DomainCommand

    data class UpdateProject(
        val projectId: ProjectId,
        val name: String,
        val summary: String,
        val health: ProjectHealth,
        val dueDate: LocalDate?,
    ) : DomainCommand

    data class RestoreProject(
        val project: Project,
    ) : DomainCommand

    data class ArchiveProject(
        val projectId: ProjectId,
        val archivedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class MarkReviewed(
        val taskId: TaskId? = null,
        val projectId: ProjectId? = null,
        val reviewedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class RestoreArchivedProject(
        val projectId: ProjectId,
    ) : DomainCommand

    data class CreateWorkflowStatus(
        val statusId: WorkflowStatusId,
        val projectId: ProjectId,
        val name: String,
        val semanticStatus: SemanticStatus,
    ) : DomainCommand

    data class RenameWorkflowStatus(
        val statusId: WorkflowStatusId,
        val name: String,
    ) : DomainCommand

    data class MoveWorkflowStatus(
        val statusId: WorkflowStatusId,
        val direction: WorkflowMoveDirection,
    ) : DomainCommand

    data class ArchiveWorkflowStatus(
        val statusId: WorkflowStatusId,
        val archivedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class RestoreArchivedWorkflowStatus(
        val statusId: WorkflowStatusId,
    ) : DomainCommand

    data class RestoreWorkflowStatuses(
        val statuses: List<WorkflowStatus>,
    ) : DomainCommand

    data class RemoveWorkflowStatus(
        val statusId: WorkflowStatusId,
    ) : DomainCommand

    data class CreateMilestone(
        val milestoneId: MilestoneId,
        val projectId: ProjectId,
        val name: String,
        val dueDate: LocalDate?,
    ) : DomainCommand

    data class UpdateMilestone(
        val milestoneId: MilestoneId,
        val name: String,
        val dueDate: LocalDate?,
        val completedAt: Instant?,
    ) : DomainCommand

    data class DeleteMilestone(
        val milestoneId: MilestoneId,
    ) : DomainCommand

    data class RestoreMilestone(
        val milestone: Milestone,
        val assignedTaskIds: Set<TaskId>? = null,
    ) : DomainCommand

    data class CaptureProjectTemplate(
        val templateId: TemplateId,
        val projectId: ProjectId,
        val name: String,
    ) : DomainCommand

    data class InstantiateProjectTemplate(
        val templateId: TemplateId,
        val projectId: ProjectId,
        val projectName: String,
        val anchorDate: LocalDate,
    ) : DomainCommand

    data class DeleteTemplate(
        val templateId: TemplateId,
    ) : DomainCommand

    data class RestoreTemplate(
        val template: Template,
    ) : DomainCommand

    data class CreateTask(
        val title: String,
        val projectId: ProjectId? = null,
        val priority: Priority = Priority.NONE,
        val due: ZonedMoment? = null,
        val tagNames: List<String> = emptyList(),
        val estimate: Duration? = null,
        val recurrence: RecurrenceRule? = null,
    ) : DomainCommand

    data class RenameTask(
        val taskId: TaskId,
        val title: String,
    ) : DomainCommand

    data class UpdateTask(
        val taskId: TaskId,
        val title: String,
        val description: String,
        val projectId: ProjectId?,
        val priority: Priority,
        val due: ZonedMoment?,
        val recurrence: RecurrenceRule?,
        val estimate: Duration?,
        val milestoneId: MilestoneId? = null,
        val recurrenceMetadata: RecurrenceSeriesMetadata? = null,
        val restoreStatusId: WorkflowStatusId? = null,
        val reminder: Reminder? = null,
        val restorePastReminder: Boolean = false,
    ) : DomainCommand

    data class SetTaskReminder(
        val taskId: TaskId,
        val triggerAt: ZonedMoment?,
        val precise: Boolean = false,
        val restorePastReminder: Boolean = false,
    ) : DomainCommand

    data class AddChecklistItem(
        val taskId: TaskId,
        val text: String,
    ) : DomainCommand

    data class UpdateChecklistItem(
        val taskId: TaskId,
        val itemId: String,
        val text: String,
        val completed: Boolean,
    ) : DomainCommand

    data class DeleteChecklistItem(
        val taskId: TaskId,
        val itemId: String,
    ) : DomainCommand

    data class RestoreChecklistItem(
        val taskId: TaskId,
        val item: ChecklistItem,
    ) : DomainCommand

    data class SetTaskTag(
        val taskId: TaskId,
        val tagId: TagId,
        val present: Boolean,
    ) : DomainCommand

    data class CreateAndAssignTag(
        val taskId: TaskId,
        val name: String,
    ) : DomainCommand

    data class SetTaskDependency(
        val taskId: TaskId,
        val dependsOnTaskId: TaskId,
        val present: Boolean,
    ) : DomainCommand

    data class ChangeTaskStatus(
        val taskId: TaskId,
        val statusId: WorkflowStatusId,
        val acknowledgeBlocked: Boolean = false,
        val changedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class RestoreTaskStatus(
        val taskId: TaskId,
        val statusId: WorkflowStatusId,
        val completedAt: Instant?,
        val generatedOccurrenceId: TaskId? = null,
        val restoredAt: Instant = Instant.now(),
    ) : DomainCommand

    data class CompleteTask(
        val taskId: TaskId,
        val acknowledgeBlocked: Boolean = false,
        val completedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class CompleteTasks(
        val taskIds: List<TaskId>,
        val acknowledgeBlocked: Boolean = false,
        val completedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class RescheduleTasks(
        val taskIds: List<TaskId>,
        val due: ZonedMoment?,          // null clears the due moment
    ) : DomainCommand

    data class MoveTasksToProject(
        val taskIds: List<TaskId>,
        val projectId: ProjectId?,      // null moves to Inbox
    ) : DomainCommand

    data class SetTasksTag(
        val taskIds: List<TaskId>,
        val tagId: TagId,
        val present: Boolean,
    ) : DomainCommand

    data class DeleteTasks(
        val taskIds: List<TaskId>,
        val deletedAt: Instant = Instant.now(),
    ) : DomainCommand

    /** Repository-produced batch undo. Commands are stored in reverse
     *  application order and replayed in list order inside one transaction.
     *  Never constructed by UI code. */
    data class UndoBatch(val commands: List<DomainCommand>) : DomainCommand

    data class ReopenTask(val taskId: TaskId) : DomainCommand

    data class RestoreTask(val taskId: TaskId) : DomainCommand

    data class DeleteTask(
        val taskId: TaskId,
        val deletedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class PermanentlyDeleteTask(
        val taskId: TaskId,
        val purgedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class PurgeExpiredTrash(
        val now: Instant = Instant.now(),
    ) : DomainCommand

    data class StartTimer(
        val taskId: TaskId,
        val startedAt: Instant = Instant.now(),
    ) : DomainCommand

    data object StopTimer : DomainCommand

    /**
     * Stops the running timer only when [taskId] still owns it.
     *
     * The comparison and the stop happen inside one repository transaction, so
     * an automated caller -- a focus-cycle boundary, say -- can never stop a
     * timer that a person started on a different task in between. No running
     * timer at all is an idempotent success; a timer another task owns is
     * rejected with [RejectionReason.TIMER_OWNERSHIP_CHANGED] and writes
     * nothing.
     */
    data class StopTimerIfOwned(val taskId: TaskId) : DomainCommand

    data class AddTimeEntry(
        val entryId: TimeEntryId,
        val taskId: TaskId,
        val startedAt: Instant,
        val stoppedAt: Instant,
        val note: String = "",
        val changedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class UpdateTimeEntry(
        val entryId: TimeEntryId,
        val startedAt: Instant,
        val stoppedAt: Instant,
        val note: String = "",
        val changedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class DeleteTimeEntry(
        val entryId: TimeEntryId,
        val deletedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class RestoreTimeEntry(
        val entry: TimeEntry,
        val restoredAt: Instant = Instant.now(),
    ) : DomainCommand

    data class AddNote(
        val taskId: TaskId?,
        val projectId: ProjectId?,
        val body: String,
        val createdAt: Instant = Instant.now(),
    ) : DomainCommand

    data class UpdateNote(
        val noteId: NoteId,
        val body: String,
        val editedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class DeleteNote(val noteId: NoteId) : DomainCommand

    data class RestoreNote(val note: Note) : DomainCommand

    data class RegisterAttachment(val attachment: Attachment) : DomainCommand

    data class DeleteAttachment(
        val attachmentId: AttachmentId,
        val deletedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class RestoreAttachment(val attachment: Attachment) : DomainCommand

    /**
     * Records that a retired attachment's cloud bytes have been released.
     *
     * Only the link to bytes that no longer exist is cleared; the record, its
     * tombstone, and everything that describes what the content *was* are
     * kept, so a retired attachment still reads as a retired attachment. There
     * is nothing to undo — the bytes are already gone — and nothing to tell a
     * person about, so this raises no activity entry either.
     */
    data class MarkAttachmentContentCollected(
        val attachmentId: AttachmentId,
        val collectedAt: Instant = Instant.now(),
    ) : DomainCommand

    /**
     * Records that a retired blob set's cloud bytes have been collected.
     *
     * The retired-blob-set row exists solely to drive that collection, so once
     * it is done there is nothing left to keep: deleting an absent row is an
     * idempotent no-op, and deleting a present one removes it outright. There
     * is nothing to undo and nothing to tell a person about.
     */
    data class MarkRetiredBlobSetCollected(
        val blobSetId: BlobSetId,
        val collectedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class CreateSavedView(
        val savedViewId: SavedViewId,
        val name: String,
        val query: SearchQuery,
    ) : DomainCommand

    data class RenameSavedView(
        val savedViewId: SavedViewId,
        val name: String,
    ) : DomainCommand

    data class UpdateSavedViewQuery(
        val savedViewId: SavedViewId,
        val query: SearchQuery,
    ) : DomainCommand

    data class DeleteSavedView(val savedViewId: SavedViewId) : DomainCommand

    /** Undo of [DeleteSavedView] only; never constructed by UI code. */
    data class RestoreSavedView(val savedView: SavedView) : DomainCommand
}

enum class WorkflowMoveDirection {
    EARLIER,
    LATER,
}

sealed interface CommandResult {
    data class Success(
        val message: String,
        val undo: DomainCommand? = null,
    ) : CommandResult

    data class Rejected(
        val reason: RejectionReason,
        val message: String,
    ) : CommandResult
}

enum class RejectionReason {
    NOT_FOUND,
    EMPTY_TITLE,
    TITLE_TOO_LONG,
    DESCRIPTION_TOO_LONG,
    EMPTY_PROJECT_NAME,
    PROJECT_NAME_TOO_LONG,
    PROJECT_SUMMARY_TOO_LONG,
    DUPLICATE_PROJECT_NAME,
    EMPTY_WORKFLOW_STATUS_NAME,
    WORKFLOW_STATUS_NAME_TOO_LONG,
    DUPLICATE_WORKFLOW_STATUS_NAME,
    WORKFLOW_STATUS_LIMIT_REACHED,
    LAST_SEMANTIC_WORKFLOW_STATUS,
    EMPTY_MILESTONE_NAME,
    MILESTONE_NAME_TOO_LONG,
    DUPLICATE_MILESTONE_NAME,
    MILESTONE_LIMIT_REACHED,
    EMPTY_TEMPLATE_NAME,
    TEMPLATE_NAME_TOO_LONG,
    DUPLICATE_TEMPLATE_NAME,
    TEMPLATE_LIMIT_REACHED,
    TEMPLATE_TASK_LIMIT_REACHED,
    TEMPLATE_DATE_RANGE_TOO_LARGE,
    EMPTY_CHECKLIST_ITEM,
    CHECKLIST_ITEM_TOO_LONG,
    CHECKLIST_LIMIT_REACHED,
    EMPTY_TAG_NAME,
    TAG_NAME_TOO_LONG,
    TAG_LIMIT_REACHED,
    DEPENDENCY_LIMIT_REACHED,
    BLOCKED_TASK_WARNING_REQUIRED,
    DEPENDENCY_CYCLE,
    INVALID_TIME_ENTRY_RANGE,
    TIMER_OWNERSHIP_CHANGED,
    TIME_ENTRY_NOTE_TOO_LONG,
    TIME_ENTRY_LIMIT_REACHED,
    RECURRENCE_REQUIRES_DUE,
    INVALID_STATE,
    REMINDER_IN_PAST,
    EMPTY_NOTE,
    NOTE_TOO_LONG,
    NOTE_LIMIT_REACHED,
    EMPTY_ATTACHMENT_NAME,
    ATTACHMENT_NAME_TOO_LONG,
    ATTACHMENT_LIMIT_REACHED,
    INVALID_ATTACHMENT_METADATA,
    SAVED_VIEW_LIMIT_REACHED,
    SAVED_VIEW_NAME_INVALID,
    SAVED_VIEW_QUERY_TOO_LONG,
    SAVED_VIEW_PAYLOAD_TOO_LARGE,
    EMPTY_BULK_SELECTION,
    BULK_SELECTION_TOO_LARGE,
    IMPORT_TOO_LARGE,
    IMPORT_EMPTY,
    IMPORT_NAME_COLLISION,
    IMPORT_STATUS_CONFLICT,
    IMPORT_BACKUP_LIMIT_EXCEEDED,
    IMPORT_UNDO_CONFLICT,
}

interface VaultRepository {
    fun observeHome(): Flow<HomeSnapshot>
    fun observeWorkspace(): StateFlow<WorkspaceSnapshot>
    fun observeTask(id: TaskId): Flow<Task?>
    suspend fun currentWorkspace(): WorkspaceSnapshot
    suspend fun execute(command: DomainCommand): CommandResult
    suspend fun search(query: SearchQuery): List<SearchResult>
}
