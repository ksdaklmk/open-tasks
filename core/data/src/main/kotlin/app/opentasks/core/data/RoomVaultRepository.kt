package app.opentasks.core.data

import androidx.room.withTransaction
import app.opentasks.core.data.db.ChecklistItemEntity
import app.opentasks.core.data.db.MilestoneEntity
import app.opentasks.core.data.db.ProjectEntity
import app.opentasks.core.data.db.SyncOperationEntity
import app.opentasks.core.data.db.TagEntity
import app.opentasks.core.data.db.TaskDependencyEntity
import app.opentasks.core.data.db.TaskEntity
import app.opentasks.core.data.db.TaskTagEntity
import app.opentasks.core.data.db.TimeEntryEntity
import app.opentasks.core.data.db.TombstoneEntity
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.data.db.VaultEntity
import app.opentasks.core.data.db.MemberEntity
import app.opentasks.core.data.db.WorkspaceEntity
import app.opentasks.core.data.db.WorkflowStatusEntity
import app.opentasks.core.data.db.checklistEntities
import app.opentasks.core.data.db.dependencyEntities
import app.opentasks.core.data.db.tagEntities
import app.opentasks.core.data.db.toEntity
import app.opentasks.core.data.db.toModel
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
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.SyncState
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TimeEntryId
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WorkspaceSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class RoomVaultRepository(
    private val database: VaultDatabase,
    private val deviceId: DeviceId,
    private val now: () -> Instant = Instant::now,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val seedSnapshot: WorkspaceSnapshot = OpenTasksFixtures.snapshot,
) : VaultRepository, AutoCloseable {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val mutableWorkspace = MutableStateFlow(emptySnapshot())

    override fun observeHome(): Flow<HomeSnapshot> =
        mutableWorkspace.map { it.home }.distinctUntilChanged()

    override fun observeWorkspace(): StateFlow<WorkspaceSnapshot> = mutableWorkspace

    override fun observeTask(id: TaskId): Flow<Task?> =
        mutableWorkspace
            .map { snapshot -> snapshot.tasks.firstOrNull { it.id == id } }
            .distinctUntilChanged()

    init {
        repositoryScope.launch {
            try {
                seedIfEmpty()
                purgeExpiredTrashRecords(now())
                observeDatabase().collect { snapshot ->
                    mutableWorkspace.emit(snapshot)
                    if (!ready.isCompleted) ready.complete(Unit)
                }
            } catch (failure: Throwable) {
                if (!ready.isCompleted) ready.completeExceptionally(failure)
                throw failure
            }
        }
    }

    override suspend fun execute(command: DomainCommand): CommandResult {
        ready.await()
        return writeMutex.withLock {
            when (command) {
                is DomainCommand.CreateProject -> createProject(command)
                is DomainCommand.UpdateProject -> updateProject(command)
                is DomainCommand.RestoreProject -> restoreProject(command)
                is DomainCommand.ArchiveProject -> archiveProject(command)
                is DomainCommand.RestoreArchivedProject -> restoreArchivedProject(command)
                is DomainCommand.CreateTask -> createTask(command)
                is DomainCommand.RenameTask -> updateTask(command.taskId) { task ->
                    val title = command.title.trim()
                    if (title.isEmpty()) return@updateTask null
                    task.copy(title = title, revision = nextRevision(task))
                }
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
    }

    override suspend fun search(query: SearchQuery): List<SearchResult> {
        ready.await()
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
                needle in SearchNormalizer.normalize(
                    listOfNotNull(
                        task.title,
                        task.description,
                        projectNames[task.projectId],
                        task.checklist.joinToString(" ", transform = ChecklistItem::text),
                        task.tagIds.mapNotNull(tagNames::get).joinToString(" "),
                    ).joinToString(" "),
                )
            }
            .map { task ->
                SearchResult.TaskResult(task, projectNames[task.projectId] ?: "Inbox")
            }

        val projectResults = snapshot.projects
            .asSequence()
            .filter { it.archivedAt == null }
            .filter { project ->
                needle in SearchNormalizer.normalize("${project.name} ${project.summary}")
            }
            .map { project -> SearchResult.ProjectResult(project, "Project") }

        return (taskResults + projectResults).take(MAX_SEARCH_RESULTS).toList()
    }

    private suspend fun createProject(
        command: DomainCommand.CreateProject,
    ): CommandResult {
        val name = command.name.trim()
        val summary = command.summary.trim()
        validateProject(name, summary)?.let { return it }
        if (database.workspaceDao().getProjectById(command.projectId.value) != null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That project identifier is already in use.",
            )
        }
        validateUniqueActiveProjectName(name)?.let { return it }
        val createdAt = now()
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
        persistProject(
            project = project,
            revision = Revision(deviceId, createdAt.toEpochMilli(), 0),
            operationKind = "create",
        )
        return CommandResult.Success("Project created")
    }

    private suspend fun updateProject(command: DomainCommand.UpdateProject): CommandResult {
        val name = command.name.trim()
        val summary = command.summary.trim()
        validateProject(name, summary)?.let { return it }
        val currentEntity = database.workspaceDao().getProjectById(command.projectId.value)
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Project no longer exists.",
            )
        val original = currentEntity.toModel()
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

        persistProject(
            project = updated,
            revision = nextProjectRevision(currentEntity),
            operationKind = "update",
        )
        return CommandResult.Success(
            message = "Project changes saved",
            undo = DomainCommand.RestoreProject(original),
        )
    }

    private suspend fun restoreProject(command: DomainCommand.RestoreProject): CommandResult {
        val currentEntity = database.workspaceDao().getProjectById(command.project.id.value)
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Project no longer exists.",
            )
        persistProject(
            project = command.project,
            revision = nextProjectRevision(currentEntity),
            operationKind = "restore",
        )
        return CommandResult.Success("Project changes undone")
    }

    private suspend fun archiveProject(
        command: DomainCommand.ArchiveProject,
    ): CommandResult {
        val currentEntity = database.workspaceDao().getProjectById(command.projectId.value)
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Project no longer exists.",
            )
        val project = currentEntity.toModel()
        if (project.archivedAt != null) {
            return CommandResult.Success("Project is already archived")
        }
        persistProject(
            project = project.copy(archivedAt = command.archivedAt),
            revision = nextProjectRevision(currentEntity, command.archivedAt),
            operationKind = "archive",
        )
        return CommandResult.Success(
            message = "Project archived",
            undo = DomainCommand.RestoreArchivedProject(project.id),
        )
    }

    private suspend fun restoreArchivedProject(
        command: DomainCommand.RestoreArchivedProject,
    ): CommandResult {
        val currentEntity = database.workspaceDao().getProjectById(command.projectId.value)
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Project no longer exists.",
            )
        val project = currentEntity.toModel()
        val archivedAt =
            project.archivedAt ?: return CommandResult.Success("Project is already active")
        validateUniqueActiveProjectName(project.name, excluding = project.id)?.let { return it }
        persistProject(
            project = project.copy(archivedAt = null),
            revision = nextProjectRevision(currentEntity),
            operationKind = "unarchive",
        )
        return CommandResult.Success(
            message = "Project restored",
            undo = DomainCommand.ArchiveProject(project.id, archivedAt),
        )
    }

    override fun close() {
        repositoryScope.cancel()
    }

    private suspend fun createTask(command: DomainCommand.CreateTask): CommandResult {
        val title = command.title.trim()
        if (title.isEmpty()) {
            return CommandResult.Rejected(RejectionReason.EMPTY_TITLE, "A task needs a title.")
        }
        command.projectId?.let { projectId ->
            val project = database.workspaceDao().getProjectById(projectId.value)
            if (project == null || project.archivedAtEpochMillis != null) {
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Restore that project before assigning new tasks to it.",
                )
            }
        }
        val createdAt = now()
        val task = Task(
            id = TaskId.new(),
            workspaceId = OpenTasksFixtures.workspaceId,
            projectId = command.projectId,
            statusId = OpenTasksFixtures.backlog,
            semanticStatus = SemanticStatus.BACKLOG,
            title = title,
            priority = command.priority,
            revision = Revision(deviceId, createdAt.toEpochMilli(), 0),
        )
        persistTask(task, "create")
        return CommandResult.Success(
            message = "Task added",
            undo = DomainCommand.DeleteTask(task.id, createdAt),
        )
    }

    private suspend fun completeTask(command: DomainCommand.CompleteTask): CommandResult {
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

    private suspend fun changeTaskStatus(
        command: DomainCommand.ChangeTaskStatus,
    ): CommandResult {
        val task = currentTask(command.taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val status = mutableWorkspace.value.workflowStatuses.firstOrNull {
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
        val generatedCandidate = if (
            status.semanticStatus == SemanticStatus.COMPLETED &&
            task.semanticStatus != SemanticStatus.COMPLETED
        ) {
            val nextStatus = mutableWorkspace.value.workflowStatuses.firstOrNull {
                it.archivedAt == null && it.semanticStatus == SemanticStatus.PLANNED
            } ?: mutableWorkspace.value.workflowStatuses.firstOrNull {
                it.archivedAt == null && it.semanticStatus == SemanticStatus.BACKLOG
            }
            nextStatus?.let {
                RecurringTaskPlanner.next(
                    current = task,
                    nextStatusId = it.id,
                    nextSemanticStatus = it.semanticStatus,
                    revision = Revision(
                        deviceId = deviceId,
                        wallTimeMillis = updated.revision.wallTimeMillis,
                        logicalCounter = 0,
                    ),
                )
            }
        } else {
            null
        }
        val generated = generatedCandidate?.takeIf {
            database.taskDao().getById(it.id.value) == null
        }
        val operationKind =
            if (status.semanticStatus == SemanticStatus.COMPLETED) "complete" else "status"
        if (generated == null) {
            persistTask(updated, operationKind)
        } else {
            database.withTransaction {
                database.taskDao().upsert(updated.toEntity())
                database.syncOperationDao().append(taskOperation(updated, operationKind))
                database.taskDao().upsert(generated.toEntity())
                database.workspaceDao().insertTaskTags(generated.tagEntities())
                database.workspaceDao().insertChecklistItems(generated.checklistEntities())
                database.syncOperationDao().append(
                    taskOperation(generated, "recurrence.create"),
                )
            }
        }
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

    private suspend fun restoreTaskStatus(
        command: DomainCommand.RestoreTaskStatus,
    ): CommandResult {
        val task = currentTask(command.taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val status = mutableWorkspace.value.workflowStatuses.firstOrNull {
            it.id == command.statusId
        } ?: return CommandResult.Rejected(
            RejectionReason.NOT_FOUND,
            "The previous workflow status no longer exists.",
        )
        val restored = task.copy(
            statusId = status.id,
            semanticStatus = status.semanticStatus,
            completedAt = command.completedAt,
            revision = nextRevision(task, command.restoredAt),
        )
        val generatedEntity = command.generatedOccurrenceId?.let { generatedId ->
            database.taskDao().getById(generatedId.value)
        }
        if (generatedEntity == null) {
            persistTask(restored, "status.restore")
        } else {
            val tombstoneRevision = Revision(
                deviceId = deviceId,
                wallTimeMillis = maxOf(
                    generatedEntity.revisionWallMillis + 1,
                    command.restoredAt.toEpochMilli(),
                ),
                logicalCounter = generatedEntity.revisionLogical + 1,
            )
            val tombstone = TombstoneEntity(
                objectId = generatedEntity.id,
                objectType = TASK_OBJECT_TYPE,
                deletedAtEpochMillis = command.restoredAt.toEpochMilli(),
                purgeAfterEpochMillis = TrashPolicy
                    .purgeAfter(command.restoredAt)
                    .toEpochMilli(),
                revisionWallMillis = tombstoneRevision.wallTimeMillis,
                revisionLogical = tombstoneRevision.logicalCounter,
                revisionDeviceId = tombstoneRevision.deviceId.value,
            )
            database.withTransaction {
                database.taskDao().upsert(restored.toEntity())
                database.syncOperationDao().append(
                    taskOperation(restored, "status.restore"),
                )
                val generatedId = generatedEntity.id
                database.workspaceDao().deleteChecklistForTask(generatedId)
                database.workspaceDao().deleteTagsForTask(generatedId)
                database.workspaceDao().deleteDependenciesForTask(generatedId)
                database.workspaceDao().deleteRemindersForTask(generatedId)
                database.workspaceDao().deleteAttachmentsForTask(generatedId)
                database.workspaceDao().deleteActivityForTask(generatedId)
                database.workspaceDao().deleteTimeForTask(generatedId)
                database.taskDao().deleteById(generatedId)
                database.workspaceDao().upsertTombstone(tombstone)
                database.syncOperationDao().append(tombstoneOperation(tombstone))
            }
        }
        return CommandResult.Success("Status restored")
    }

    private suspend fun deleteTask(command: DomainCommand.DeleteTask): CommandResult {
        val task = currentTask(command.taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        if (task.deletedAt != null) {
            return CommandResult.Success("Task is already in Trash")
        }
        val updated = task.copy(
            deletedAt = command.deletedAt,
            revision = nextRevision(task, command.deletedAt),
        )
        database.withTransaction {
            database.taskDao().upsert(updated.toEntity())
            database.syncOperationDao().append(taskOperation(updated, "trash"))
            database.timeEntryDao().getActive()
                ?.takeIf { active -> active.taskId == task.id.value }
                ?.let { active ->
                    val stoppedAt = maxOf(
                        command.deletedAt.toEpochMilli(),
                        active.startedAtEpochMillis,
                    )
                    database.timeEntryDao().stop(active.id, stoppedAt)
                    database.syncOperationDao().append(
                        timeOperation(active.copy(stoppedAtEpochMillis = stoppedAt), "stop"),
                    )
                }
        }
        return CommandResult.Success(
            message = "Task moved to Trash",
            undo = DomainCommand.RestoreTask(task.id),
        )
    }

    private suspend fun restoreTask(command: DomainCommand.RestoreTask): CommandResult {
        val task = currentTask(command.taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val deletedAt = task.deletedAt ?: return CommandResult.Success("Task is already restored")
        persistTask(
            task.copy(
                deletedAt = null,
                revision = nextRevision(task),
            ),
            "restore",
        )
        return CommandResult.Success(
            message = "Task restored",
            undo = DomainCommand.DeleteTask(task.id, deletedAt),
        )
    }

    private suspend fun permanentlyDeleteTask(
        command: DomainCommand.PermanentlyDeleteTask,
    ): CommandResult {
        val entity = database.taskDao().getById(command.taskId.value)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        if (entity.deletedAtEpochMillis == null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Move the task to Trash before deleting it permanently.",
            )
        }
        purgeTaskEntity(entity, command.purgedAt)
        return CommandResult.Success("Task permanently deleted")
    }

    private suspend fun purgeExpiredTrash(
        command: DomainCommand.PurgeExpiredTrash,
    ): CommandResult {
        val count = purgeExpiredTrashRecords(command.now)
        return CommandResult.Success(
            if (count == 0) "No expired Trash items" else "$count expired Trash items deleted",
        )
    }

    private suspend fun purgeExpiredTrashRecords(at: Instant): Int {
        val cutoff = at.minusSeconds(TrashPolicy.RETENTION_DAYS * SECONDS_PER_DAY)
        val expired = database.taskDao().getDeletedAtOrBefore(cutoff.toEpochMilli())
        expired.forEach { entity -> purgeTaskEntity(entity, at) }
        return expired.size
    }

    private suspend fun purgeTaskEntity(
        entity: TaskEntity,
        purgedAt: Instant,
    ) {
        val deletedAt = Instant.ofEpochMilli(requireNotNull(entity.deletedAtEpochMillis))
        val revision = Revision(
            deviceId = deviceId,
            wallTimeMillis = maxOf(entity.revisionWallMillis + 1, purgedAt.toEpochMilli()),
            logicalCounter = entity.revisionLogical + 1,
        )
        val purgeAfter = TrashPolicy.purgeAfter(deletedAt)
        val tombstone = TombstoneEntity(
            objectId = entity.id,
            objectType = TASK_OBJECT_TYPE,
            deletedAtEpochMillis = deletedAt.toEpochMilli(),
            purgeAfterEpochMillis = purgeAfter.toEpochMilli(),
            revisionWallMillis = revision.wallTimeMillis,
            revisionLogical = revision.logicalCounter,
            revisionDeviceId = revision.deviceId.value,
        )
        database.purgeTaskAndAppendOperation(
            taskId = entity.id,
            tombstone = tombstone,
            operation = tombstoneOperation(tombstone),
        )
    }

    private suspend fun updateTaskDetails(command: DomainCommand.UpdateTask): CommandResult {
        val task = currentTask(command.taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val requestedProject = command.projectId?.let { projectId ->
            database.workspaceDao().getProjectById(projectId.value)
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
            requestedProject?.archivedAtEpochMillis != null &&
                task.projectId?.value != requestedProject.id ->
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
                        due.instant.atZone(ZoneId.of(due.zoneId)).toLocalDate(),
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
        persistTask(updated, "update")
        return CommandResult.Success(
            message = "Changes saved",
            undo = task.toUpdateCommand(),
        )
    }

    private suspend fun addChecklistItem(
        command: DomainCommand.AddChecklistItem,
    ): CommandResult {
        val task = currentTask(command.taskId)
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
        database.withTransaction {
            database.taskDao().upsert(updated.toEntity())
            database.workspaceDao().upsertChecklistItem(item.toEntity(task.id))
            database.syncOperationDao().append(taskOperation(updated, "checklist.add"))
        }
        return CommandResult.Success(
            message = "Checklist item added",
            undo = DomainCommand.DeleteChecklistItem(task.id, item.id),
        )
    }

    private suspend fun updateChecklistItem(
        command: DomainCommand.UpdateChecklistItem,
    ): CommandResult {
        val task = currentTask(command.taskId)
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
        database.withTransaction {
            database.taskDao().upsert(updated.toEntity())
            database.workspaceDao().upsertChecklistItem(updatedItem.toEntity(task.id))
            database.syncOperationDao().append(taskOperation(updated, "checklist.update"))
        }
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

    private suspend fun deleteChecklistItem(
        command: DomainCommand.DeleteChecklistItem,
    ): CommandResult {
        val task = currentTask(command.taskId)
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
        database.withTransaction {
            database.taskDao().upsert(updated.toEntity())
            database.workspaceDao().deleteChecklistItem(task.id.value, existing.id)
            database.syncOperationDao().append(taskOperation(updated, "checklist.delete"))
        }
        return CommandResult.Success(
            message = "Checklist item deleted",
            undo = DomainCommand.RestoreChecklistItem(task.id, existing),
        )
    }

    private suspend fun restoreChecklistItem(
        command: DomainCommand.RestoreChecklistItem,
    ): CommandResult {
        val task = currentTask(command.taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        if (task.checklist.any { it.id == command.item.id }) {
            return CommandResult.Success("Checklist item restored")
        }
        validateChecklistText(command.item.text)?.let { return it }
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
        database.withTransaction {
            database.taskDao().upsert(updated.toEntity())
            database.workspaceDao().upsertChecklistItem(restored.toEntity(task.id))
            database.syncOperationDao().append(taskOperation(updated, "checklist.restore"))
        }
        return CommandResult.Success(
            message = "Checklist item restored",
            undo = DomainCommand.DeleteChecklistItem(task.id, restored.id),
        )
    }

    private suspend fun setTaskTag(command: DomainCommand.SetTaskTag): CommandResult {
        val task = currentTask(command.taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        if (database.workspaceDao().getTagById(command.tagId.value) == null) {
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
        val relation = TaskTagEntity(
            taskId = task.id.value,
            tagId = command.tagId.value,
            present = command.present,
            revisionWallMillis = updated.revision.wallTimeMillis,
            revisionLogical = updated.revision.logicalCounter,
            revisionDeviceId = updated.revision.deviceId.value,
        )
        database.withTransaction {
            database.taskDao().upsert(updated.toEntity())
            database.workspaceDao().upsertTaskTag(relation)
            database.syncOperationDao().append(taskOperation(updated, "tags.update"))
        }
        return CommandResult.Success(
            message = if (command.present) "Tag added" else "Tag removed",
            undo = command.copy(present = !command.present),
        )
    }

    private suspend fun createAndAssignTag(
        command: DomainCommand.CreateAndAssignTag,
    ): CommandResult {
        val task = currentTask(command.taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val name = command.name.trim()
        validateTagName(name)?.let { return it }
        database.workspaceDao()
            .findTagByName(task.workspaceId.value, name)
            ?.let { existing ->
                return setTaskTag(
                    DomainCommand.SetTaskTag(task.id, TagId(existing.id), present = true),
                )
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
        val relation = TaskTagEntity(
            taskId = task.id.value,
            tagId = tag.id.value,
            present = true,
            revisionWallMillis = updated.revision.wallTimeMillis,
            revisionLogical = updated.revision.logicalCounter,
            revisionDeviceId = updated.revision.deviceId.value,
        )
        database.withTransaction {
            database.workspaceDao().upsertTag(tag.toEntity())
            database.taskDao().upsert(updated.toEntity())
            database.workspaceDao().upsertTaskTag(relation)
            database.syncOperationDao().append(tagOperation(tag, updated.revision))
            database.syncOperationDao().append(taskOperation(updated, "tags.create_assign"))
        }
        return CommandResult.Success(
            message = "Tag created and added",
            undo = DomainCommand.SetTaskTag(task.id, tag.id, present = false),
        )
    }

    private suspend fun updateTask(
        taskId: TaskId,
        transform: (Task) -> Task?,
    ): CommandResult {
        val task = currentTask(taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val updated = transform(task)
            ?: return CommandResult.Rejected(RejectionReason.EMPTY_TITLE, "A task needs a title.")
        persistTask(updated, "update")
        return CommandResult.Success("Changes saved")
    }

    private suspend fun currentTask(taskId: TaskId): Task? {
        val observed = mutableWorkspace.value.tasks.firstOrNull { it.id == taskId }
        val workspaceDao = database.workspaceDao()
        return database.taskDao().getById(taskId.value)?.toModel(
            tagIds = workspaceDao.getTaskTags(taskId.value)
                .mapTo(linkedSetOf()) { TagId(it.tagId) },
            checklist = workspaceDao.getChecklistItems(taskId.value)
                .map(ChecklistItemEntity::toModel),
            blockedBy = observed?.blockedBy.orEmpty(),
        )
    }

    private suspend fun persistTask(task: Task, operationKind: String) {
        val operation = taskOperation(task, operationKind)
        database.upsertTaskAndAppendOperation(task.toEntity(), operation)
    }

    private suspend fun persistProject(
        project: Project,
        revision: Revision,
        operationKind: String,
    ) {
        database.upsertProjectAndAppendOperation(
            project = project.toEntity(revision),
            operation = projectOperation(project, revision, operationKind),
        )
    }

    private suspend fun startTimer(command: DomainCommand.StartTimer): CommandResult =
        database.withTransaction {
            if (database.timeEntryDao().getActive() != null) {
                return@withTransaction CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Stop the active timer before starting another one.",
                )
            }
            val task = database.taskDao().getById(command.taskId.value)
            if (task == null || task.deletedAtEpochMillis != null) {
                return@withTransaction CommandResult.Rejected(
                    RejectionReason.NOT_FOUND,
                    "Task no longer exists.",
                )
            }
            val entry = TimeEntryEntity(
                id = TimeEntryId.new().value,
                taskId = command.taskId.value,
                deviceId = deviceId.value,
                startedAtEpochMillis = command.startedAt.toEpochMilli(),
                stoppedAtEpochMillis = null,
                noteCiphertext = ByteArray(0),
            )
            database.timeEntryDao().insert(entry)
            database.syncOperationDao().append(timeOperation(entry, "start"))
            CommandResult.Success("Timer started")
        }

    private suspend fun stopTimer(): CommandResult = database.withTransaction {
        val active = database.timeEntryDao().getActive()
            ?: return@withTransaction CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "No timer is running.",
            )
        val stoppedAt = now().toEpochMilli()
        database.timeEntryDao().stop(active.id, stoppedAt)
        database.syncOperationDao().append(
            timeOperation(active.copy(stoppedAtEpochMillis = stoppedAt), "stop"),
        )
        CommandResult.Success("Timer stopped")
    }

    private fun nextRevision(
        task: Task,
        at: Instant = now(),
    ): Revision {
        val wallTime = maxOf(task.revision.wallTimeMillis + 1, at.toEpochMilli())
        return Revision(
            deviceId = deviceId,
            wallTimeMillis = wallTime,
            logicalCounter = task.revision.logicalCounter + 1,
        )
    }

    private fun nextProjectRevision(
        project: ProjectEntity,
        at: Instant = now(),
    ): Revision = Revision(
        deviceId = deviceId,
        wallTimeMillis = maxOf(project.revisionWallMillis + 1, at.toEpochMilli()),
        logicalCounter = project.revisionLogical + 1,
    )

    private fun taskOperation(task: Task, kind: String): SyncOperationEntity =
        SyncOperationEntity(
            id = UUID.randomUUID().toString(),
            deviceId = deviceId.value,
            objectId = task.id.value,
            objectType = "task.$kind",
            encryptedPayload = buildString {
                append("v2")
                append('\u0000')
                append(task.id.value)
                append('\u0000')
                append(task.statusId.value)
                append('\u0000')
                append(task.semanticStatus.name)
                append('\u0000')
                append(task.completedAt?.toEpochMilli() ?: -1)
                append('\u0000')
                append(task.title)
                append('\u0000')
                append(task.description)
                append('\u0000')
                append(task.projectId?.value.orEmpty())
                append('\u0000')
                append(task.priority.name)
                append('\u0000')
                append(task.due?.instant?.toEpochMilli() ?: -1)
                append('\u0000')
                append(task.due?.zoneId.orEmpty())
                append('\u0000')
                append(task.recurrence?.frequency?.name.orEmpty())
                append('\u0000')
                append(task.recurrence?.interval ?: -1)
                append('\u0000')
                append(
                    task.recurrence
                        ?.weekdays
                        ?.sortedBy { it.value }
                        ?.joinToString(",") { it.name }
                        .orEmpty(),
                )
                append('\u0000')
                append(task.recurrence?.count ?: -1)
                append('\u0000')
                append(task.recurrence?.endDate?.toString().orEmpty())
                append('\u0000')
                append(task.recurrenceSeriesId?.value.orEmpty())
                append('\u0000')
                append(task.recurrenceAnchor?.instant?.toEpochMilli() ?: -1)
                append('\u0000')
                append(task.recurrenceAnchor?.zoneId.orEmpty())
                append('\u0000')
                append(task.recurrenceOccurrenceIndex ?: -1)
                append('\u0000')
                append(task.estimate?.seconds ?: -1)
                append('\u0000')
                append(task.deletedAt?.toEpochMilli() ?: -1)
                append('\u0000')
                append(task.tagIds.sortedBy(TagId::value).joinToString(",") { it.value })
                task.checklist.sortedBy(ChecklistItem::rank).forEach { item ->
                    append('\u0000')
                    append(item.id.length)
                    append(':')
                    append(item.id)
                    append(item.text.length)
                    append(':')
                    append(item.text)
                    append(if (item.completed) '1' else '0')
                    append(item.rank.length)
                    append(':')
                    append(item.rank)
                }
            }.toByteArray(Charsets.UTF_8),
            revisionWallMillis = task.revision.wallTimeMillis,
            revisionLogical = task.revision.logicalCounter,
            uploadedAtEpochMillis = null,
        )

    private fun tagOperation(tag: Tag, revision: Revision): SyncOperationEntity =
        SyncOperationEntity(
            id = UUID.randomUUID().toString(),
            deviceId = deviceId.value,
            objectId = tag.id.value,
            objectType = "tag.create",
            encryptedPayload = buildString {
                append("v1")
                append('\u0000')
                append(tag.id.value)
                append('\u0000')
                append(tag.workspaceId.value)
                append('\u0000')
                append(tag.name)
            }.toByteArray(Charsets.UTF_8),
            revisionWallMillis = revision.wallTimeMillis,
            revisionLogical = revision.logicalCounter,
            uploadedAtEpochMillis = null,
        )

    private fun projectOperation(
        project: Project,
        revision: Revision,
        kind: String,
    ): SyncOperationEntity = SyncOperationEntity(
        id = UUID.randomUUID().toString(),
        deviceId = deviceId.value,
        objectId = project.id.value,
        objectType = "project.$kind",
        encryptedPayload = buildString {
            append("v1")
            append('\u0000')
            append(project.id.value)
            append('\u0000')
            append(project.workspaceId.value)
            append('\u0000')
            append(project.name)
            append('\u0000')
            append(project.summary)
            append('\u0000')
            append(project.status.name)
            append('\u0000')
            append(project.dueDate?.toString().orEmpty())
            append('\u0000')
            append(project.archivedAt?.toEpochMilli() ?: -1)
        }.toByteArray(Charsets.UTF_8),
        revisionWallMillis = revision.wallTimeMillis,
        revisionLogical = revision.logicalCounter,
        uploadedAtEpochMillis = null,
    )

    private fun timeOperation(entry: TimeEntryEntity, kind: String): SyncOperationEntity {
        val revisionWallTime = entry.stoppedAtEpochMillis ?: entry.startedAtEpochMillis
        return SyncOperationEntity(
            id = UUID.randomUUID().toString(),
            deviceId = deviceId.value,
            objectId = entry.id,
            objectType = "time_entry.$kind",
            encryptedPayload = buildString {
                append("v1")
                append('\u0000')
                append(entry.id)
                append('\u0000')
                append(entry.taskId)
                append('\u0000')
                append(entry.startedAtEpochMillis)
                append('\u0000')
                append(entry.stoppedAtEpochMillis ?: -1)
            }.toByteArray(Charsets.UTF_8),
            revisionWallMillis = revisionWallTime,
            revisionLogical = 0,
            uploadedAtEpochMillis = null,
        )
    }

    private fun tombstoneOperation(tombstone: TombstoneEntity): SyncOperationEntity =
        SyncOperationEntity(
            id = UUID.randomUUID().toString(),
            deviceId = deviceId.value,
            objectId = tombstone.objectId,
            objectType = "task.purge",
            encryptedPayload = buildString {
                append("v1")
                append('\u0000')
                append(tombstone.objectId)
                append('\u0000')
                append(tombstone.deletedAtEpochMillis)
                append('\u0000')
                append(tombstone.purgeAfterEpochMillis)
            }.toByteArray(Charsets.UTF_8),
            revisionWallMillis = tombstone.revisionWallMillis,
            revisionLogical = tombstone.revisionLogical,
            uploadedAtEpochMillis = null,
        )

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

    private fun ChecklistItem.toEntity(taskId: TaskId): ChecklistItemEntity =
        ChecklistItemEntity(
            id = id,
            taskId = taskId.value,
            text = text,
            completed = completed,
            rank = rank,
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

    private suspend fun validateUniqueActiveProjectName(
        name: String,
        excluding: ProjectId? = null,
    ): CommandResult.Rejected? {
        val existing = database.workspaceDao().findActiveProjectByName(
            workspaceId = OpenTasksFixtures.workspaceId.value,
            name = name,
        )
        return if (existing != null && existing.id != excluding?.value) {
            CommandResult.Rejected(
                RejectionReason.DUPLICATE_PROJECT_NAME,
                "An active project already uses that name.",
            )
        } else {
            null
        }
    }

    private fun rankAfter(rank: String?): String = rank?.plus('m') ?: "a0"

    private fun observeDatabase(): Flow<WorkspaceSnapshot> {
        val taskDao = database.taskDao()
        val workspaceDao = database.workspaceDao()
        val base = combine(
            taskDao.observeAll(OpenTasksFixtures.workspaceId.value),
            workspaceDao.observeProjects(OpenTasksFixtures.workspaceId.value),
            workspaceDao.observeMilestones(),
            workspaceDao.observeTags(OpenTasksFixtures.workspaceId.value),
            workspaceDao.observeDependencies(),
        ) { tasks, projects, milestones, tags, dependencies ->
            BaseRows(tasks, projects, milestones, tags, dependencies)
        }
        val baseWithWorkflow = combine(
            base,
            workspaceDao.observeWorkflowStatuses(),
        ) { rows, workflowStatuses ->
            rows.copy(workflowStatuses = workflowStatuses)
        }
        val relations = combine(
            workspaceDao.observeTaskTags(),
            workspaceDao.observeChecklistItems(),
            observeActiveTimerWithClock(),
        ) { taskTags, checklist, activeTimer ->
            RelationRows(taskTags, checklist, activeTimer)
        }
        return combine(baseWithWorkflow, relations, ::buildSnapshot)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeActiveTimerWithClock(): Flow<TimedActiveEntry?> =
        database.timeEntryDao().observeActive().flatMapLatest { active ->
            if (active == null) {
                flowOf(null)
            } else {
                flow {
                    while (currentCoroutineContext().isActive) {
                        emit(TimedActiveEntry(active, now()))
                        delay(TIMER_TICK_MILLIS)
                    }
                }
            }
        }

    private fun buildSnapshot(
        base: BaseRows,
        relations: RelationRows,
    ): WorkspaceSnapshot {
        val tagIds = relations.taskTags
            .groupBy(TaskTagEntity::taskId)
            .mapValues { (_, values) -> values.mapTo(linkedSetOf()) { TagId(it.tagId) } }
        val checklist = relations.checklist
            .groupBy(ChecklistItemEntity::taskId)
            .mapValues { (_, values) -> values.map(ChecklistItemEntity::toModel) }
        val dependencies = base.dependencies
            .groupBy(TaskDependencyEntity::taskId)
            .mapValues { (_, values) -> values.mapTo(linkedSetOf()) { TaskId(it.dependsOnTaskId) } }
        val tasks = base.tasks.map { entity ->
            entity.toModel(
                tagIds = tagIds[entity.id].orEmpty(),
                checklist = checklist[entity.id].orEmpty(),
                blockedBy = dependencies[entity.id].orEmpty(),
            )
        }
        val projects = base.projects.map(ProjectEntity::toModel)
        val projectNames = projects.associateBy(Project::id)
        val activeTimer = relations.activeTimer?.let { timed ->
            val task = tasks.firstOrNull { it.id.value == timed.entry.taskId }
            task?.let {
                ActiveTimerSnapshot(
                    entryId = TimeEntryId(timed.entry.id),
                    taskId = it.id,
                    taskTitle = it.title,
                    projectName = it.projectId?.let(projectNames::get)?.name,
                    startedAt = Instant.ofEpochMilli(timed.entry.startedAtEpochMillis),
                    elapsed = Duration.between(
                        Instant.ofEpochMilli(timed.entry.startedAtEpochMillis),
                        timed.at,
                    ).coerceAtLeast(Duration.ZERO),
                )
            }
        }
        val currentTime = relations.activeTimer?.at ?: now()
        val activeTasks = tasks.filter { it.deletedAt == null }
        val openTasks = activeTasks.filterNot(Task::isCompleted)
        val home = HomeSnapshot(
            today = LocalDate.ofInstant(currentTime, zoneId()),
            focusTasks = openTasks
                .sortedWith(compareByDescending<Task>(Task::priority).thenBy { it.due?.instant })
                .take(HOME_TASK_LIMIT),
            upcomingTasks = openTasks
                .filter { it.start != null || it.due != null }
                .sortedBy { it.due?.instant ?: it.start?.instant }
                .take(HOME_TASK_LIMIT),
            projects = projects.filter { it.archivedAt == null },
            activeTimer = activeTimer,
            syncState = SyncState.LocalOnly,
            overdueCount = openTasks.count { task ->
                task.due?.instant?.isBefore(currentTime) == true
            },
        )
        return WorkspaceSnapshot(
            home = home,
            tasks = tasks,
            projects = projects,
            workflowStatuses = base.workflowStatuses.map(WorkflowStatusEntity::toModel),
            milestones = base.milestones.map(MilestoneEntity::toModel),
            tags = base.tags.map(TagEntity::toModel),
        )
    }

    private suspend fun seedIfEmpty() {
        database.withTransaction {
            val workspaceDao = database.workspaceDao()
            if (workspaceDao.workspaceCount() > 0) return@withTransaction

            val seedRevision = seedSnapshot.tasks.firstOrNull()?.revision
                ?: Revision(deviceId, now().toEpochMilli(), 0)
            workspaceDao.insertVault(
                VaultEntity(
                    id = VAULT_ID.value,
                    storageMode = "LOCAL",
                    createdAtEpochMillis = now().toEpochMilli(),
                    schemaVersion = 2,
                    cryptoVersion = 1,
                    minimumReaderVersion = 1,
                ),
            )
            workspaceDao.insertMember(MemberEntity(OWNER_ID, "You"))
            workspaceDao.insertWorkspace(
                WorkspaceEntity(
                    id = OpenTasksFixtures.workspaceId.value,
                    vaultId = VAULT_ID.value,
                    ownerId = OWNER_ID,
                    name = "Open Tasks",
                ),
            )
            workspaceDao.insertProjects(seedSnapshot.projects.map { it.toEntity(seedRevision) })
            workspaceDao.insertWorkflowStatuses(defaultWorkflowStatuses())
            workspaceDao.insertMilestones(seedSnapshot.milestones.map { it.toEntity() })
            workspaceDao.insertTags(seedSnapshot.tags.map { it.toEntity() })
            workspaceDao.insertTasks(seedSnapshot.tasks.map(Task::toEntity))
            workspaceDao.insertDependencies(seedSnapshot.tasks.flatMap(Task::dependencyEntities))
            workspaceDao.insertTaskTags(seedSnapshot.tasks.flatMap(Task::tagEntities))
            workspaceDao.insertChecklistItems(seedSnapshot.tasks.flatMap(Task::checklistEntities))
            seedSnapshot.home.activeTimer?.let { timer ->
                workspaceDao.insertTimeEntry(
                    TimeEntryEntity(
                        id = timer.entryId.value,
                        taskId = timer.taskId.value,
                        deviceId = deviceId.value,
                        startedAtEpochMillis = timer.startedAt.toEpochMilli(),
                        stoppedAtEpochMillis = null,
                        noteCiphertext = ByteArray(0),
                    ),
                )
            }
        }
    }

    private fun defaultWorkflowStatuses(): List<WorkflowStatusEntity> {
        val projectId = seedSnapshot.projects.firstOrNull()?.id?.value ?: "workspace-default"
        return listOf(
            WorkflowStatusEntity(
                OpenTasksFixtures.backlog.value,
                projectId,
                "Backlog",
                SemanticStatus.BACKLOG.name,
                "a0",
                null,
            ),
            WorkflowStatusEntity(
                OpenTasksFixtures.planned.value,
                projectId,
                "Planned",
                SemanticStatus.PLANNED.name,
                "a1",
                null,
            ),
            WorkflowStatusEntity(
                OpenTasksFixtures.started.value,
                projectId,
                "In progress",
                SemanticStatus.STARTED.name,
                "a2",
                null,
            ),
            WorkflowStatusEntity(
                OpenTasksFixtures.blocked.value,
                projectId,
                "Blocked",
                SemanticStatus.BLOCKED.name,
                "a3",
                null,
            ),
            WorkflowStatusEntity(
                OpenTasksFixtures.done.value,
                projectId,
                "Done",
                SemanticStatus.COMPLETED.name,
                "a4",
                null,
            ),
        )
    }

    private fun emptySnapshot(): WorkspaceSnapshot = WorkspaceSnapshot(
        home = HomeSnapshot(
            today = LocalDate.ofInstant(now(), zoneId()),
            focusTasks = emptyList(),
            upcomingTasks = emptyList(),
            projects = emptyList(),
            activeTimer = null,
            syncState = SyncState.LocalOnly,
            overdueCount = 0,
        ),
        tasks = emptyList(),
        projects = emptyList(),
        workflowStatuses = emptyList(),
        milestones = emptyList(),
        tags = emptyList(),
    )

    private data class BaseRows(
        val tasks: List<TaskEntity>,
        val projects: List<ProjectEntity>,
        val milestones: List<MilestoneEntity>,
        val tags: List<TagEntity>,
        val dependencies: List<TaskDependencyEntity>,
        val workflowStatuses: List<WorkflowStatusEntity> = emptyList(),
    )

    private data class RelationRows(
        val taskTags: List<TaskTagEntity>,
        val checklist: List<ChecklistItemEntity>,
        val activeTimer: TimedActiveEntry?,
    )

    private data class TimedActiveEntry(
        val entry: TimeEntryEntity,
        val at: Instant,
    )

    private companion object {
        const val OWNER_ID = "member-owner"
        const val HOME_TASK_LIMIT = 3
        const val MAX_SEARCH_RESULTS = 50
        const val MAX_TASK_TITLE_LENGTH = 240
        const val MAX_TASK_DESCRIPTION_LENGTH = 20_000
        const val MAX_PROJECT_NAME_LENGTH = 120
        const val MAX_PROJECT_SUMMARY_LENGTH = 1_000
        const val MAX_CHECKLIST_ITEM_LENGTH = 500
        const val MAX_CHECKLIST_ITEMS = 200
        const val MAX_TAG_NAME_LENGTH = 64
        const val MAX_TASK_TAGS = 50
        const val TIMER_TICK_MILLIS = 1_000L
        const val SECONDS_PER_DAY = 86_400L
        val VAULT_ID = VaultId("vault-primary")
        const val TASK_OBJECT_TYPE = "task"
    }
}
