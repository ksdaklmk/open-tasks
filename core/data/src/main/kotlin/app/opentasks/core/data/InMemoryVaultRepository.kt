package app.opentasks.core.data

import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.domain.RecurrenceSeriesMetadata
import app.opentasks.core.domain.RecurringTaskPlanner
import app.opentasks.core.domain.SearchNormalizer
import app.opentasks.core.domain.TrashPolicy
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.ActiveTimerSnapshot
import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TimeEntryId
import app.opentasks.core.model.WorkspaceSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class InMemoryVaultRepository(
    initial: WorkspaceSnapshot = OpenTasksFixtures.snapshot,
    private val now: () -> Instant = Instant::now,
) : VaultRepository {
    private val writeMutex = Mutex()
    private val mutableWorkspace = MutableStateFlow(initial)

    override fun observeHome(): Flow<HomeSnapshot> =
        mutableWorkspace.map { it.home }.distinctUntilChanged()

    override fun observeWorkspace(): StateFlow<WorkspaceSnapshot> = mutableWorkspace

    override fun observeTask(id: TaskId): Flow<Task?> =
        mutableWorkspace
            .map { snapshot -> snapshot.tasks.firstOrNull { it.id == id } }
            .distinctUntilChanged()

    override suspend fun execute(command: DomainCommand): CommandResult = writeMutex.withLock {
        when (command) {
            is DomainCommand.CreateProject -> createProject(command)
            is DomainCommand.UpdateProject -> updateProject(command)
            is DomainCommand.RestoreProject -> restoreProject(command)
            is DomainCommand.ArchiveProject -> archiveProject(command)
            is DomainCommand.RestoreArchivedProject -> restoreArchivedProject(command)
            is DomainCommand.CreateTask -> createTask(command)
            is DomainCommand.RenameTask -> renameTask(command)
            is DomainCommand.UpdateTask -> updateTaskDetails(command)
            is DomainCommand.AddChecklistItem -> addChecklistItem(command)
            is DomainCommand.UpdateChecklistItem -> updateChecklistItem(command)
            is DomainCommand.DeleteChecklistItem -> deleteChecklistItem(command)
            is DomainCommand.RestoreChecklistItem -> restoreChecklistItem(command)
            is DomainCommand.SetTaskTag -> setTaskTag(command)
            is DomainCommand.CreateAndAssignTag -> createAndAssignTag(command)
            is DomainCommand.ChangeTaskStatus -> changeTaskStatus(command)
            is DomainCommand.RestoreTaskStatus -> restoreTaskStatus(command)
            is DomainCommand.CompleteTask -> completeTask(command)
            is DomainCommand.ReopenTask -> updateTask(command.taskId) { task ->
                task.copy(
                    semanticStatus = SemanticStatus.PLANNED,
                    statusId = OpenTasksFixtures.planned,
                    completedAt = null,
                    revision = nextRevision(task),
                )
            }
            is DomainCommand.DeleteTask -> deleteTask(command)
            is DomainCommand.RestoreTask -> restoreTask(command)
            is DomainCommand.PermanentlyDeleteTask -> permanentlyDeleteTask(command)
            is DomainCommand.PurgeExpiredTrash -> purgeExpiredTrash(command)
            is DomainCommand.StartTimer -> startTimer(command)
            DomainCommand.StopTimer -> stopTimer()
        }
    }

    override suspend fun search(query: SearchQuery): List<SearchResult> {
        val needle = SearchNormalizer.normalize(query.text)
        if (needle.isBlank()) return emptyList()
        val snapshot = mutableWorkspace.value
        val projectNames = snapshot.projects.associate { it.id to it.name }
        val tagNames = snapshot.tags.associate { it.id to it.name }

        val taskResults = snapshot.tasks
            .asSequence()
            .filter { query.includeTrash || it.deletedAt == null }
            .filter { query.includeCompleted || !it.isCompleted }
            .filter { query.projectIds.isEmpty() || it.projectId in query.projectIds }
            .filter { query.tagIds.isEmpty() || it.tagIds.any(query.tagIds::contains) }
            .filter { task ->
                val searchable = SearchNormalizer.normalize(
                    listOfNotNull(
                        task.title,
                        task.description,
                        projectNames[task.projectId],
                        task.checklist.joinToString(" ", transform = ChecklistItem::text),
                        task.tagIds.mapNotNull(tagNames::get).joinToString(" "),
                    ).joinToString(" "),
                )
                needle in searchable
            }
            .map { task ->
                SearchResult.TaskResult(
                    task,
                    projectNames[task.projectId] ?: "Inbox",
                )
            }

        val projectResults = snapshot.projects
            .asSequence()
            .filter { it.archivedAt == null }
            .filter { project ->
                needle in SearchNormalizer.normalize("${project.name} ${project.summary}")
            }
            .map { project -> SearchResult.ProjectResult(project, "Project") }

        return (taskResults + projectResults).take(50).toList()
    }

    private fun createProject(command: DomainCommand.CreateProject): CommandResult {
        val name = command.name.trim()
        val summary = command.summary.trim()
        validateProject(name, summary)?.let { return it }
        val current = mutableWorkspace.value
        if (current.projects.any { it.id == command.projectId }) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That project identifier is already in use.",
            )
        }
        validateUniqueActiveProjectName(name)?.let { return it }
        val project = Project(
            id = command.projectId,
            workspaceId = OpenTasksFixtures.workspaceId,
            name = name,
            summary = summary,
            status = command.health,
            dueDate = command.dueDate,
            completedTasks = 0,
            totalTasks = 0,
        )
        publishProjects(current.projects + project)
        return CommandResult.Success("Project created")
    }

    private fun updateProject(command: DomainCommand.UpdateProject): CommandResult {
        val name = command.name.trim()
        val summary = command.summary.trim()
        validateProject(name, summary)?.let { return it }
        val current = mutableWorkspace.value
        val original = current.projects.firstOrNull { it.id == command.projectId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Project no longer exists.",
            )
        if (original.archivedAt == null) {
            validateUniqueActiveProjectName(name, excluding = original.id)?.let { return it }
        }
        val updated = original.copy(
            name = name,
            summary = summary,
            status = command.health,
            dueDate = command.dueDate,
        )
        if (updated == original) return CommandResult.Success("Project is up to date")
        publishProjects(current.projects.replace(updated))
        return CommandResult.Success(
            message = "Project changes saved",
            undo = DomainCommand.RestoreProject(original),
        )
    }

    private fun restoreProject(command: DomainCommand.RestoreProject): CommandResult {
        val current = mutableWorkspace.value
        if (current.projects.none { it.id == command.project.id }) {
            return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Project no longer exists.",
            )
        }
        publishProjects(current.projects.replace(command.project))
        return CommandResult.Success("Project changes undone")
    }

    private fun archiveProject(command: DomainCommand.ArchiveProject): CommandResult {
        val current = mutableWorkspace.value
        val project = current.projects.firstOrNull { it.id == command.projectId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Project no longer exists.",
            )
        if (project.archivedAt != null) {
            return CommandResult.Success("Project is already archived")
        }
        publishProjects(
            current.projects.replace(project.copy(archivedAt = command.archivedAt)),
        )
        return CommandResult.Success(
            message = "Project archived",
            undo = DomainCommand.RestoreArchivedProject(project.id),
        )
    }

    private fun restoreArchivedProject(
        command: DomainCommand.RestoreArchivedProject,
    ): CommandResult {
        val current = mutableWorkspace.value
        val project = current.projects.firstOrNull { it.id == command.projectId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Project no longer exists.",
            )
        val archivedAt =
            project.archivedAt ?: return CommandResult.Success("Project is already active")
        validateUniqueActiveProjectName(project.name, excluding = project.id)?.let { return it }
        publishProjects(current.projects.replace(project.copy(archivedAt = null)))
        return CommandResult.Success(
            message = "Project restored",
            undo = DomainCommand.ArchiveProject(project.id, archivedAt),
        )
    }

    private fun createTask(command: DomainCommand.CreateTask): CommandResult {
        val title = command.title.trim()
        if (title.isEmpty()) {
            return CommandResult.Rejected(RejectionReason.EMPTY_TITLE, "A task needs a title.")
        }
        if (
            command.projectId != null &&
            mutableWorkspace.value.projects.none {
                it.id == command.projectId && it.archivedAt == null
            }
        ) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Restore that project before assigning new tasks to it.",
            )
        }
        val task = Task(
            id = TaskId.new(),
            workspaceId = OpenTasksFixtures.workspaceId,
            projectId = command.projectId,
            statusId = OpenTasksFixtures.backlog,
            semanticStatus = SemanticStatus.BACKLOG,
            title = title,
            priority = command.priority,
            revision = Revision(DeviceId("local-device"), now().toEpochMilli(), 0),
        )
        val current = mutableWorkspace.value
        publish(current.tasks + task)
        return CommandResult.Success(
            message = "Task added",
            undo = DomainCommand.DeleteTask(task.id, now()),
        )
    }

    private fun completeTask(command: DomainCommand.CompleteTask): CommandResult {
        val completedStatus = mutableWorkspace.value.workflowStatuses
            .firstOrNull { it.semanticStatus == SemanticStatus.COMPLETED && it.archivedAt == null }
            ?: return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "This workspace has no active completion status.",
            )
        val result = changeTaskStatus(
            DomainCommand.ChangeTaskStatus(
                taskId = command.taskId,
                statusId = completedStatus.id,
                acknowledgeBlocked = command.acknowledgeBlocked,
                changedAt = command.completedAt,
            ),
        )
        return if (result is CommandResult.Success) {
            val generated = (result.undo as? DomainCommand.RestoreTaskStatus)
                ?.generatedOccurrenceId
            result.copy(
                message = if (generated == null) {
                    "Task completed"
                } else {
                    "Task completed • next occurrence scheduled"
                },
            )
        } else {
            result
        }
    }

    private fun changeTaskStatus(command: DomainCommand.ChangeTaskStatus): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val status = current.workflowStatuses.firstOrNull {
            it.id == command.statusId && it.archivedAt == null
        } ?: return CommandResult.Rejected(
            RejectionReason.NOT_FOUND,
            "That workflow status is no longer available.",
        )
        if (
            status.semanticStatus == SemanticStatus.COMPLETED &&
            task.isBlocked &&
            !command.acknowledgeBlocked
        ) {
            return CommandResult.Rejected(
                RejectionReason.BLOCKED_TASK_WARNING_REQUIRED,
                "This task still has unfinished dependencies.",
            )
        }
        if (task.statusId == status.id) {
            return CommandResult.Success("Status is already ${status.name}")
        }
        val updated = task.copy(
            statusId = status.id,
            semanticStatus = status.semanticStatus,
            completedAt = command.changedAt.takeIf {
                status.semanticStatus == SemanticStatus.COMPLETED
            },
            revision = nextRevision(task, command.changedAt),
        )
        val generated = if (
            status.semanticStatus == SemanticStatus.COMPLETED &&
            task.semanticStatus != SemanticStatus.COMPLETED
        ) {
            val nextStatus = current.workflowStatuses.firstOrNull {
                it.archivedAt == null && it.semanticStatus == SemanticStatus.PLANNED
            } ?: current.workflowStatuses.firstOrNull {
                it.archivedAt == null && it.semanticStatus == SemanticStatus.BACKLOG
            }
            nextStatus?.let {
                RecurringTaskPlanner.next(
                    current = task,
                    nextStatusId = it.id,
                    nextSemanticStatus = it.semanticStatus,
                    revision = Revision(
                        deviceId = DeviceId("local-device"),
                        wallTimeMillis = updated.revision.wallTimeMillis,
                        logicalCounter = 0,
                    ),
                )
            }?.takeUnless { next ->
                current.tasks.any { it.id == next.id }
            }
        } else {
            null
        }
        publish(
            current.tasks.map { if (it.id == task.id) updated else it } +
                listOfNotNull(generated),
        )
        return CommandResult.Success(
            message = "Moved to ${status.name}",
            undo = DomainCommand.RestoreTaskStatus(
                taskId = task.id,
                statusId = task.statusId,
                completedAt = task.completedAt,
                generatedOccurrenceId = generated?.id,
            ),
        )
    }

    private fun restoreTaskStatus(command: DomainCommand.RestoreTaskStatus): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val status = current.workflowStatuses.firstOrNull { it.id == command.statusId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "The previous workflow status no longer exists.",
            )
        val updated = task.copy(
            statusId = status.id,
            semanticStatus = status.semanticStatus,
            completedAt = command.completedAt,
            revision = nextRevision(task),
        )
        publish(
            current.tasks
                .filterNot { it.id == command.generatedOccurrenceId }
                .replace(updated),
        )
        return CommandResult.Success("Status restored")
    }

    private fun deleteTask(command: DomainCommand.DeleteTask): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        if (task.deletedAt != null) {
            return CommandResult.Success("Task is already in Trash")
        }
        if (current.home.activeTimer?.taskId == task.id) {
            mutableWorkspace.value = current.copy(
                home = current.home.copy(activeTimer = null),
            )
        }
        val updated = task.copy(
            deletedAt = command.deletedAt,
            revision = nextRevision(task, command.deletedAt),
        )
        publish(mutableWorkspace.value.tasks.replace(updated))
        return CommandResult.Success(
            message = "Task moved to Trash",
            undo = DomainCommand.RestoreTask(task.id),
        )
    }

    private fun restoreTask(command: DomainCommand.RestoreTask): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val deletedAt = task.deletedAt ?: return CommandResult.Success("Task is already restored")
        val updated = task.copy(deletedAt = null, revision = nextRevision(task))
        publish(current.tasks.replace(updated))
        return CommandResult.Success(
            message = "Task restored",
            undo = DomainCommand.DeleteTask(task.id, deletedAt),
        )
    }

    private fun permanentlyDeleteTask(
        command: DomainCommand.PermanentlyDeleteTask,
    ): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        if (task.deletedAt == null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Move the task to Trash before deleting it permanently.",
            )
        }
        publish(current.tasks.filterNot { it.id == task.id })
        return CommandResult.Success("Task permanently deleted")
    }

    private fun purgeExpiredTrash(
        command: DomainCommand.PurgeExpiredTrash,
    ): CommandResult {
        val current = mutableWorkspace.value
        val expired = current.tasks.filter { task ->
            task.deletedAt?.let { TrashPolicy.isEligibleForPurge(it, command.now) } == true
        }
        if (expired.isNotEmpty()) {
            val expiredIds = expired.mapTo(hashSetOf(), Task::id)
            publish(current.tasks.filterNot { it.id in expiredIds })
        }
        return CommandResult.Success(
            if (expired.isEmpty()) {
                "No expired Trash items"
            } else {
                "${expired.size} expired Trash items deleted"
            },
        )
    }

    private fun renameTask(command: DomainCommand.RenameTask): CommandResult {
        val title = command.title.trim()
        if (title.isEmpty()) {
            return CommandResult.Rejected(
                RejectionReason.EMPTY_TITLE,
                "A task needs a title.",
            )
        }
        return updateTask(command.taskId) { task ->
            task.copy(title = title, revision = nextRevision(task))
        }
    }

    private fun updateTaskDetails(command: DomainCommand.UpdateTask): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val requestedProject = command.projectId?.let { projectId ->
            current.projects.firstOrNull { it.id == projectId }
        }
        val recurrence = command.recurrence
        val title = command.title.trim()
        when {
            title.isEmpty() -> return CommandResult.Rejected(
                RejectionReason.EMPTY_TITLE,
                "A task needs a title.",
            )
            title.length > MAX_TASK_TITLE_LENGTH -> return CommandResult.Rejected(
                RejectionReason.TITLE_TOO_LONG,
                "Keep the task title under $MAX_TASK_TITLE_LENGTH characters.",
            )
            command.description.length > MAX_TASK_DESCRIPTION_LENGTH ->
                return CommandResult.Rejected(
                    RejectionReason.DESCRIPTION_TOO_LONG,
                    "Keep the description under $MAX_TASK_DESCRIPTION_LENGTH characters.",
                )
            command.projectId != null && requestedProject == null ->
                return CommandResult.Rejected(
                    RejectionReason.NOT_FOUND,
                    "That project no longer exists.",
                )
            requestedProject?.archivedAt != null && task.projectId != requestedProject.id ->
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Restore that project before assigning new tasks to it.",
                )
            recurrence != null && command.due == null && task.start == null ->
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Add a due date before repeating this task.",
                )
            recurrence?.count != null && recurrence.endDate != null ->
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Choose either an occurrence count or an end date.",
                )
            recurrence?.endDate?.let { endDate ->
                command.due?.let { due ->
                    endDate.isBefore(
                        due.instant
                            .atZone(java.time.ZoneId.of(due.zoneId))
                            .toLocalDate(),
                    )
                }
            } == true ->
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "The repeat end date cannot be before the due date.",
                )
            command.estimate?.isNegative == true || command.estimate?.isZero == true ->
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Estimate must be greater than zero.",
                )
        }
        val recurrenceMetadata = if (recurrence == null) {
            null
        } else {
            command.recurrenceMetadata ?: RecurringTaskPlanner.metadataForUpdate(
                task = task,
                due = command.due,
                rule = recurrence,
            )
        }
        if (
            task.title == title &&
            task.description == command.description &&
            task.projectId == command.projectId &&
            task.priority == command.priority &&
            task.due == command.due &&
            task.recurrence == command.recurrence &&
            task.recurrenceSeriesId == recurrenceMetadata?.seriesId &&
            task.recurrenceAnchor == recurrenceMetadata?.anchor &&
            task.recurrenceOccurrenceIndex == recurrenceMetadata?.occurrenceIndex &&
            task.estimate == command.estimate
        ) {
            return CommandResult.Success("Changes saved")
        }
        val updated = task.copy(
            title = title,
            description = command.description,
            projectId = command.projectId,
            priority = command.priority,
            due = command.due,
            recurrence = recurrence,
            recurrenceSeriesId = recurrenceMetadata?.seriesId,
            recurrenceAnchor = recurrenceMetadata?.anchor,
            recurrenceOccurrenceIndex = recurrenceMetadata?.occurrenceIndex,
            estimate = command.estimate,
            revision = nextRevision(task),
        )
        publish(current.tasks.map { if (it.id == task.id) updated else it })
        return CommandResult.Success(
            message = "Changes saved",
            undo = task.toUpdateCommand(),
        )
    }

    private fun addChecklistItem(command: DomainCommand.AddChecklistItem): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val text = command.text.trim()
        validateChecklistText(text)?.let { return it }
        if (task.checklist.size >= MAX_CHECKLIST_ITEMS) {
            return CommandResult.Rejected(
                RejectionReason.CHECKLIST_LIMIT_REACHED,
                "A task can contain up to $MAX_CHECKLIST_ITEMS checklist items.",
            )
        }
        val item = ChecklistItem(
            id = UUID.randomUUID().toString(),
            text = text,
            completed = false,
            rank = rankAfter(task.checklist.maxByOrNull(ChecklistItem::rank)?.rank),
        )
        val updated = task.copy(
            checklist = task.checklist + item,
            revision = nextRevision(task),
        )
        publish(current.tasks.replace(updated))
        return CommandResult.Success(
            message = "Checklist item added",
            undo = DomainCommand.DeleteChecklistItem(task.id, item.id),
        )
    }

    private fun updateChecklistItem(command: DomainCommand.UpdateChecklistItem): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val existing = task.checklist.firstOrNull { it.id == command.itemId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Checklist item no longer exists.",
            )
        val text = command.text.trim()
        validateChecklistText(text)?.let { return it }
        if (existing.text == text && existing.completed == command.completed) {
            return CommandResult.Success("Checklist saved")
        }
        val updatedItem = existing.copy(text = text, completed = command.completed)
        val updated = task.copy(
            checklist = task.checklist.map { item ->
                if (item.id == existing.id) updatedItem else item
            },
            revision = nextRevision(task),
        )
        publish(current.tasks.replace(updated))
        return CommandResult.Success(
            message = if (command.completed) "Checklist item completed" else "Checklist saved",
            undo = DomainCommand.UpdateChecklistItem(
                taskId = task.id,
                itemId = existing.id,
                text = existing.text,
                completed = existing.completed,
            ),
        )
    }

    private fun deleteChecklistItem(command: DomainCommand.DeleteChecklistItem): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val existing = task.checklist.firstOrNull { it.id == command.itemId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Checklist item no longer exists.",
            )
        val updated = task.copy(
            checklist = task.checklist.filterNot { it.id == existing.id },
            revision = nextRevision(task),
        )
        publish(current.tasks.replace(updated))
        return CommandResult.Success(
            message = "Checklist item deleted",
            undo = DomainCommand.RestoreChecklistItem(task.id, existing),
        )
    }

    private fun restoreChecklistItem(command: DomainCommand.RestoreChecklistItem): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        if (task.checklist.any { it.id == command.item.id }) {
            return CommandResult.Success("Checklist item restored")
        }
        if (task.checklist.size >= MAX_CHECKLIST_ITEMS) {
            return CommandResult.Rejected(
                RejectionReason.CHECKLIST_LIMIT_REACHED,
                "A task can contain up to $MAX_CHECKLIST_ITEMS checklist items.",
            )
        }
        val restored = if (task.checklist.any { it.rank == command.item.rank }) {
            command.item.copy(
                rank = rankAfter(task.checklist.maxByOrNull(ChecklistItem::rank)?.rank),
            )
        } else {
            command.item
        }
        val updated = task.copy(
            checklist = (task.checklist + restored).sortedBy(ChecklistItem::rank),
            revision = nextRevision(task),
        )
        publish(current.tasks.replace(updated))
        return CommandResult.Success(
            message = "Checklist item restored",
            undo = DomainCommand.DeleteChecklistItem(task.id, restored.id),
        )
    }

    private fun setTaskTag(command: DomainCommand.SetTaskTag): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        if (current.tags.none { it.id == command.tagId }) {
            return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Tag no longer exists.")
        }
        val currentlyPresent = command.tagId in task.tagIds
        if (currentlyPresent == command.present) {
            return CommandResult.Success(if (command.present) "Tag added" else "Tag removed")
        }
        if (command.present && task.tagIds.size >= MAX_TASK_TAGS) {
            return CommandResult.Rejected(
                RejectionReason.TAG_LIMIT_REACHED,
                "A task can contain up to $MAX_TASK_TAGS tags.",
            )
        }
        val updatedTags = if (command.present) {
            task.tagIds + command.tagId
        } else {
            task.tagIds - command.tagId
        }
        val updated = task.copy(tagIds = updatedTags, revision = nextRevision(task))
        publish(current.tasks.replace(updated))
        return CommandResult.Success(
            message = if (command.present) "Tag added" else "Tag removed",
            undo = command.copy(present = !command.present),
        )
    }

    private fun createAndAssignTag(command: DomainCommand.CreateAndAssignTag): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val name = command.name.trim()
        validateTagName(name)?.let { return it }
        current.tags.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { existing ->
            return setTaskTag(DomainCommand.SetTaskTag(task.id, existing.id, true))
        }
        if (task.tagIds.size >= MAX_TASK_TAGS) {
            return CommandResult.Rejected(
                RejectionReason.TAG_LIMIT_REACHED,
                "A task can contain up to $MAX_TASK_TAGS tags.",
            )
        }
        val tag = Tag(
            id = TagId(UUID.randomUUID().toString()),
            workspaceId = task.workspaceId,
            name = name,
        )
        val updated = task.copy(
            tagIds = task.tagIds + tag.id,
            revision = nextRevision(task),
        )
        publish(current.tasks.replace(updated), current.tags + tag)
        return CommandResult.Success(
            message = "Tag created and added",
            undo = DomainCommand.SetTaskTag(task.id, tag.id, false),
        )
    }

    private fun startTimer(command: DomainCommand.StartTimer): CommandResult {
        val current = mutableWorkspace.value
        if (current.home.activeTimer != null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Stop the active timer before starting another one.",
            )
        }
        val task = current.tasks.firstOrNull { it.id == command.taskId && it.deletedAt == null }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val projectName = current.projects.firstOrNull { it.id == task.projectId }?.name
        mutableWorkspace.value = current.copy(
            home = current.home.copy(
                activeTimer = ActiveTimerSnapshot(
                    entryId = TimeEntryId.new(),
                    taskId = task.id,
                    taskTitle = task.title,
                    projectName = projectName,
                    startedAt = command.startedAt,
                    elapsed = Duration.ZERO,
                ),
            ),
        )
        return CommandResult.Success("Timer started")
    }

    private fun stopTimer(): CommandResult {
        val current = mutableWorkspace.value
        if (current.home.activeTimer == null) {
            return CommandResult.Rejected(RejectionReason.INVALID_STATE, "No timer is running.")
        }
        mutableWorkspace.value = current.copy(home = current.home.copy(activeTimer = null))
        return CommandResult.Success("Timer stopped")
    }

    private fun updateTask(
        taskId: TaskId,
        transform: (Task) -> Task,
    ): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        publish(current.tasks.map { if (it.id == taskId) transform(it) else it })
        return CommandResult.Success("Changes saved")
    }

    private fun nextRevision(
        task: Task,
        at: Instant = now(),
    ): Revision = task.revision.copy(
        wallTimeMillis = maxOf(task.revision.wallTimeMillis + 1, at.toEpochMilli()),
        logicalCounter = task.revision.logicalCounter + 1,
    )

    private fun validateChecklistText(text: String): CommandResult.Rejected? = when {
        text.isEmpty() -> CommandResult.Rejected(
            RejectionReason.EMPTY_CHECKLIST_ITEM,
            "A checklist item needs text.",
        )
        text.length > MAX_CHECKLIST_ITEM_LENGTH -> CommandResult.Rejected(
            RejectionReason.CHECKLIST_ITEM_TOO_LONG,
            "Keep checklist items under $MAX_CHECKLIST_ITEM_LENGTH characters.",
        )
        else -> null
    }

    private fun validateTagName(name: String): CommandResult.Rejected? = when {
        name.isEmpty() -> CommandResult.Rejected(
            RejectionReason.EMPTY_TAG_NAME,
            "A tag needs a name.",
        )
        name.length > MAX_TAG_NAME_LENGTH -> CommandResult.Rejected(
            RejectionReason.TAG_NAME_TOO_LONG,
            "Keep tag names under $MAX_TAG_NAME_LENGTH characters.",
        )
        else -> null
    }

    private fun validateProject(
        name: String,
        summary: String,
    ): CommandResult.Rejected? = when {
        name.isEmpty() -> CommandResult.Rejected(
            RejectionReason.EMPTY_PROJECT_NAME,
            "A project needs a name.",
        )
        name.length > MAX_PROJECT_NAME_LENGTH -> CommandResult.Rejected(
            RejectionReason.PROJECT_NAME_TOO_LONG,
            "Keep project names under $MAX_PROJECT_NAME_LENGTH characters.",
        )
        summary.length > MAX_PROJECT_SUMMARY_LENGTH -> CommandResult.Rejected(
            RejectionReason.PROJECT_SUMMARY_TOO_LONG,
            "Keep project summaries under $MAX_PROJECT_SUMMARY_LENGTH characters.",
        )
        else -> null
    }

    private fun validateUniqueActiveProjectName(
        name: String,
        excluding: ProjectId? = null,
    ): CommandResult.Rejected? =
        if (
            mutableWorkspace.value.projects.any { project ->
                project.archivedAt == null &&
                    project.id != excluding &&
                    project.name.equals(name, ignoreCase = true)
            }
        ) {
            CommandResult.Rejected(
                RejectionReason.DUPLICATE_PROJECT_NAME,
                "An active project already uses that name.",
            )
        } else {
            null
        }

    private fun rankAfter(rank: String?): String = rank?.plus('m') ?: "a0"

    private fun List<Task>.replace(updated: Task): List<Task> =
        map { task -> if (task.id == updated.id) updated else task }

    private fun List<Project>.replace(updated: Project): List<Project> =
        map { project -> if (project.id == updated.id) updated else project }

    private fun publishProjects(projects: List<Project>) {
        val current = mutableWorkspace.value
        mutableWorkspace.value = current.copy(
            projects = projects,
            home = current.home.copy(projects = projects.filter { it.archivedAt == null }),
        )
    }

    private fun publish(
        tasks: List<Task>,
        tags: List<Tag> = mutableWorkspace.value.tags,
    ) {
        val current = mutableWorkspace.value
        val activeTasks = tasks.filter { it.deletedAt == null }
        val home = current.home.copy(
            focusTasks = activeTasks.filterNot(Task::isCompleted).take(3),
            upcomingTasks = activeTasks.filter { it.start != null || it.due != null }.take(3),
        )
        mutableWorkspace.value = current.copy(home = home, tasks = tasks, tags = tags)
    }

    private fun Task.toUpdateCommand(): DomainCommand.UpdateTask = DomainCommand.UpdateTask(
        taskId = id,
        title = title,
        description = description,
        projectId = projectId,
        priority = priority,
        due = due,
        recurrence = recurrence,
        estimate = estimate,
        recurrenceMetadata = recurrence?.let {
            val seriesId = recurrenceSeriesId
            val anchor = recurrenceAnchor
            val occurrenceIndex = recurrenceOccurrenceIndex
            if (seriesId != null && anchor != null && occurrenceIndex != null) {
                RecurrenceSeriesMetadata(seriesId, anchor, occurrenceIndex)
            } else {
                null
            }
        },
    )

    private companion object {
        const val MAX_TASK_TITLE_LENGTH = 240
        const val MAX_TASK_DESCRIPTION_LENGTH = 20_000
        const val MAX_PROJECT_NAME_LENGTH = 120
        const val MAX_PROJECT_SUMMARY_LENGTH = 1_000
        const val MAX_CHECKLIST_ITEM_LENGTH = 500
        const val MAX_CHECKLIST_ITEMS = 200
        const val MAX_TAG_NAME_LENGTH = 64
        const val MAX_TASK_TAGS = 50
    }
}
