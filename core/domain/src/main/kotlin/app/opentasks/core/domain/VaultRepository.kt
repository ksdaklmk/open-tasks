package app.opentasks.core.domain

import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.SyncReason
import app.opentasks.core.model.SyncState
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

sealed interface DomainCommand {
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

    data class RestoreArchivedProject(
        val projectId: ProjectId,
    ) : DomainCommand

    data class CreateTask(
        val title: String,
        val projectId: ProjectId? = null,
        val priority: Priority = Priority.NONE,
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
        val recurrenceMetadata: RecurrenceSeriesMetadata? = null,
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
    EMPTY_CHECKLIST_ITEM,
    CHECKLIST_ITEM_TOO_LONG,
    CHECKLIST_LIMIT_REACHED,
    EMPTY_TAG_NAME,
    TAG_NAME_TOO_LONG,
    TAG_LIMIT_REACHED,
    BLOCKED_TASK_WARNING_REQUIRED,
    DEPENDENCY_CYCLE,
    INVALID_STATE,
}

interface VaultRepository {
    fun observeHome(): Flow<HomeSnapshot>
    fun observeWorkspace(): StateFlow<WorkspaceSnapshot>
    fun observeTask(id: TaskId): Flow<Task?>
    suspend fun execute(command: DomainCommand): CommandResult
    suspend fun search(query: SearchQuery): List<SearchResult>
}

interface SyncCoordinator {
    val state: StateFlow<SyncState>
    suspend fun requestSync(reason: SyncReason)
}
