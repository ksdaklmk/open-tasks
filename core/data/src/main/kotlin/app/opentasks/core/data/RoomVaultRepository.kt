package app.opentasks.core.data

import androidx.room.withTransaction
import app.opentasks.core.data.backup.BackupJournalAppendBoundary
import app.opentasks.core.data.backup.BackupMutationCodec
import app.opentasks.core.data.backup.BackupRecordFamily
import app.opentasks.core.data.backup.BackupRecordV1
import app.opentasks.core.data.backup.BackupSnapshotCodec
import app.opentasks.core.data.backup.BackupSnapshotPayloadV1
import app.opentasks.core.data.backup.RoomBackupJournalSession
import app.opentasks.core.data.backup.allRecords
import app.opentasks.core.data.backup.defaultBackupState
import app.opentasks.core.data.backup.snapshots
import app.opentasks.core.data.backup.toBackupRecordV1
import app.opentasks.core.data.db.ActivityEntryEntity
import app.opentasks.core.data.db.AttachmentEntity
import app.opentasks.core.data.db.AutomationRuleEntity
import app.opentasks.core.data.db.ChecklistItemEntity
import app.opentasks.core.data.db.MilestoneEntity
import app.opentasks.core.data.db.MyDayEntryEntity
import app.opentasks.core.data.db.NoteEntity
import app.opentasks.core.data.db.ProjectEntity
import app.opentasks.core.data.db.ReminderEntity
import app.opentasks.core.data.db.RetiredBlobSetEntity
import app.opentasks.core.data.db.SavedViewEntity
import app.opentasks.core.data.db.TagEntity
import app.opentasks.core.data.db.TaskDependencyEntity
import app.opentasks.core.data.db.TaskEntity
import app.opentasks.core.data.db.TaskTagEntity
import app.opentasks.core.data.db.TemplateEntity
import app.opentasks.core.data.db.TimeEntryEntity
import app.opentasks.core.data.db.TombstoneEntity
import app.opentasks.core.data.db.VAULT_DATABASE_VERSION
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
import app.opentasks.core.domain.DependencyRules
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.domain.RecurrenceSeriesMetadata
import app.opentasks.core.domain.RecurringTaskPlanner
import app.opentasks.core.domain.ScheduleMoveFailure
import app.opentasks.core.domain.ProjectTemplatePlanner
import app.opentasks.core.domain.planTaskDuplicate
import app.opentasks.core.domain.searchWorkspace
import app.opentasks.core.domain.toCommandRejection
import app.opentasks.core.domain.TrashPolicy
import app.opentasks.core.domain.TimerRules
import app.opentasks.core.domain.validateTaskScheduleState
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.domain.WorkflowMoveDirection
import app.opentasks.core.data.export.TasksImportPlan
import app.opentasks.core.data.export.TasksImportPlanResult
import app.opentasks.core.data.export.buildTasksImportPlan
import app.opentasks.core.domain.ImportReceipt
import app.opentasks.core.domain.ImportedProjectReceipt
import app.opentasks.core.domain.ImportedTagReceipt
import app.opentasks.core.domain.ImportedTaskReceipt
import app.opentasks.core.model.ActiveTimerSnapshot
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.ActivityKind
import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.Milestone
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.Note
import app.opentasks.core.model.NoteId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SavedView
import app.opentasks.core.model.SavedViewId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskDependency
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.Template
import app.opentasks.core.model.TemplateId
import app.opentasks.core.model.TimeEntry
import app.opentasks.core.model.TimeEntryConflict
import app.opentasks.core.model.TimeEntryId
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.ZonedMoment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

class RoomVaultRepository(
    private val database: VaultDatabase,
    private val deviceId: DeviceId,
    private val now: () -> Instant = Instant::now,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val seedSnapshot: WorkspaceSnapshot = OpenTasksFixtures.snapshot,
    private val backupJournalAppendBoundary: BackupJournalAppendBoundary =
        BackupJournalAppendBoundary { dao, entity -> dao.insert(entity) },
) : VaultRepository, AutoCloseable {
    private val repositoryJob = SupervisorJob()
    private val repositoryScope = CoroutineScope(repositoryJob + Dispatchers.IO)
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

    override suspend fun currentWorkspace(): WorkspaceSnapshot {
        ready.await()
        return mutableWorkspace.value
    }

    init {
        repositoryScope.launch {
            try {
                seedIfEmpty()
                purgeExpiredTrashRecordsAtomically(now())
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
            database.withTransaction {
                val mutationDao = database.backupMutationDao()
                val before = mutationDao.snapshots()
                val session = RoomBackupJournalSession(
                    vaultId = VAULT_ID,
                    stateDao = database.backupStateDao(),
                    journalDao = database.backupJournalDao(),
                    mutationDao = mutationDao,
                    mutationCodec = BackupMutationCodec,
                    operationId = { UUID.randomUUID().toString() },
                    sourceDeviceId = deviceId.value,
                    appendBoundary = backupJournalAppendBoundary,
                )
                val result = dispatch(command)
                session.appendChanges(
                    before = before,
                    after = mutationDao.snapshots(),
                )
                result
            }
        }
    }

    private suspend fun dispatch(command: DomainCommand): CommandResult =
        when (command) {
                is DomainCommand.CreateProject -> createProject(command)
                is DomainCommand.ImportTasks -> importTasks(command)
                is DomainCommand.RemoveImportedRecords -> removeImportedRecords(command)
                is DomainCommand.UpdateProject -> updateProject(command)
                is DomainCommand.RestoreProject -> restoreProject(command)
                is DomainCommand.ArchiveProject -> archiveProject(command)
                is DomainCommand.MarkReviewed -> markReviewed(command)
                is DomainCommand.RestoreArchivedProject -> restoreArchivedProject(command)
                is DomainCommand.CreateWorkflowStatus -> createWorkflowStatus(command)
                is DomainCommand.RenameWorkflowStatus -> renameWorkflowStatus(command)
                is DomainCommand.MoveWorkflowStatus -> moveWorkflowStatus(command)
                is DomainCommand.ArchiveWorkflowStatus -> archiveWorkflowStatus(command)
                is DomainCommand.RestoreArchivedWorkflowStatus ->
                    restoreArchivedWorkflowStatus(command)
                is DomainCommand.RestoreWorkflowStatuses -> restoreWorkflowStatuses(command)
                is DomainCommand.RemoveWorkflowStatus -> removeWorkflowStatus(command)
                is DomainCommand.CreateMilestone -> createMilestone(command)
                is DomainCommand.UpdateMilestone -> updateMilestone(command)
                is DomainCommand.DeleteMilestone -> deleteMilestone(command)
                is DomainCommand.RestoreMilestone -> restoreMilestone(command)
                is DomainCommand.CaptureProjectTemplate -> captureProjectTemplate(command)
                is DomainCommand.InstantiateProjectTemplate ->
                    instantiateProjectTemplate(command)
                is DomainCommand.DeleteTemplate -> deleteTemplate(command)
                is DomainCommand.RestoreTemplate -> restoreTemplate(command)
                is DomainCommand.CreateTask -> createTask(command)
                is DomainCommand.DuplicateTask -> duplicateTask(command)
                is DomainCommand.RenameTask -> updateTask(command.taskId) { task ->
                    val title = command.title.trim()
                    if (title.isEmpty()) return@updateTask null
                    if (title == task.title) {
                        task
                    } else {
                        task.copy(title = title, revision = nextRevision(task))
                    }
                }
                is DomainCommand.UpdateTask -> updateTaskDetails(command)
                is DomainCommand.SetTaskSchedule -> setTaskSchedule(command)
                is DomainCommand.SetTaskReminder -> setTaskReminder(command)
                is DomainCommand.AddChecklistItem -> addChecklistItem(command)
                is DomainCommand.UpdateChecklistItem -> updateChecklistItem(command)
                is DomainCommand.DeleteChecklistItem -> deleteChecklistItem(command)
                is DomainCommand.RestoreChecklistItem -> restoreChecklistItem(command)
                is DomainCommand.SetTaskTag -> setTaskTag(command)
                is DomainCommand.CreateAndAssignTag -> createAndAssignTag(command)
                is DomainCommand.SetTaskDependency -> setTaskDependency(command)
                is DomainCommand.ChangeTaskStatus -> changeTaskStatus(command)
                is DomainCommand.RestoreTaskStatus -> restoreTaskStatus(command)
                is DomainCommand.CompleteTask -> completeTask(command)
                is DomainCommand.CompleteTasks -> completeTasks(command)
                is DomainCommand.RescheduleTasks -> rescheduleTasks(command)
                is DomainCommand.MoveTasksToProject -> moveTasksToProject(command)
                is DomainCommand.SetTasksTag -> setTasksTag(command)
                is DomainCommand.DeleteTasks -> deleteTasks(command)
                is DomainCommand.UndoBatch -> undoBatch(command)
                is DomainCommand.ReopenTask -> reopenTask(command.taskId)
                is DomainCommand.DeleteTask -> deleteTask(command)
                is DomainCommand.RestoreTask -> restoreTask(command)
                is DomainCommand.PermanentlyDeleteTask -> permanentlyDeleteTask(command)
                is DomainCommand.PurgeExpiredTrash -> purgeExpiredTrash(command)
                is DomainCommand.StartTimer -> startTimer(command)
                DomainCommand.StopTimer -> stopTimer()
                is DomainCommand.StopTimerIfOwned -> stopTimerIfOwned(command)
                is DomainCommand.AddTimeEntry -> addTimeEntry(command)
                is DomainCommand.UpdateTimeEntry -> updateTimeEntry(command)
                is DomainCommand.DeleteTimeEntry -> deleteTimeEntry(command)
                is DomainCommand.RestoreTimeEntry -> restoreTimeEntry(command)
                is DomainCommand.AddNote -> addNote(command)
                is DomainCommand.UpdateNote -> updateNote(command)
                is DomainCommand.DeleteNote -> deleteNote(command)
                is DomainCommand.RestoreNote -> restoreNote(command)
                is DomainCommand.RegisterAttachment -> registerAttachment(command)
                is DomainCommand.DeleteAttachment -> deleteAttachment(command)
                is DomainCommand.RestoreAttachment -> restoreAttachment(command)
                is DomainCommand.MarkAttachmentContentCollected ->
                    markAttachmentContentCollected(command)
                is DomainCommand.MarkRetiredBlobSetCollected ->
                    markRetiredBlobSetCollected(command)
                is DomainCommand.CreateSavedView -> createSavedView(command)
                is DomainCommand.RenameSavedView -> renameSavedView(command)
                is DomainCommand.UpdateSavedViewQuery -> updateSavedViewQuery(command)
                is DomainCommand.DeleteSavedView -> deleteSavedView(command)
                is DomainCommand.RestoreSavedView -> restoreSavedView(command)
            }

    override suspend fun search(query: SearchQuery): List<SearchResult> {
        ready.await()
        return searchWorkspace(mutableWorkspace.value, query, Clock.fixed(now(), zoneId()))
    }

    private suspend fun importTasks(command: DomainCommand.ImportTasks): CommandResult {
        val captureDao = database.backupCaptureDao()
        val current = mutableWorkspace.value.copy(
            projects = captureDao.projects(VAULT_ID.value).map(ProjectEntity::toModel),
            workflowStatuses = captureDao.workflowStatuses(VAULT_ID.value)
                .map(WorkflowStatusEntity::toModel),
            tags = captureDao.tags(VAULT_ID.value).map(TagEntity::toModel),
        )
        val at = now()
        val revision = Revision(deviceId, at.toEpochMilli(), 0)
        val plan = when (
            val result = buildTasksImportPlan(
                rows = command.rows,
                snapshot = current,
                workspaceId = OpenTasksFixtures.workspaceId,
                revision = revision,
                at = at,
                freshId = { UUID.randomUUID().toString() },
            )
        ) {
            is TasksImportPlanResult.Invalid -> return result.rejection
            is TasksImportPlanResult.Ready -> result.plan
        }
        val currentRecords = captureDao.allRecords(VAULT_ID.value)
        if (plan.hasIdentityCollision(currentRecords)) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "The import could not allocate unique record identifiers.",
            )
        }
        if (!preflightImportBackup(plan, revision, currentRecords)) {
            return CommandResult.Rejected(
                RejectionReason.IMPORT_BACKUP_LIMIT_EXCEEDED,
                "The imported tasks would exceed backup limits.",
            )
        }
        val workspaceDao = database.workspaceDao()
        plan.projects.forEach { planned ->
            workspaceDao.upsertProject(planned.project.toEntity(revision))
            planned.statuses.forEach { workspaceDao.upsertWorkflowStatus(it.toEntity(revision)) }
        }
        plan.tags.forEach { workspaceDao.upsertTag(it.toEntity()) }
        plan.tasks.forEach { planned ->
            database.taskDao().upsert(planned.task.toEntity())
            planned.task.tagEntities().forEach { workspaceDao.upsertTaskTag(it) }
        }
        val projectReceipts = plan.projects.map { planned ->
            val activityId = recordActivity(
                taskId = null,
                projectId = planned.project.id,
                kind = ActivityKind.RECORD_CREATED,
                body = "Created",
                at = at,
                id = planned.activity.id,
            )
            ImportedProjectReceipt(planned.project, planned.statuses, activityId)
        }
        val taskReceipts = plan.tasks.map { planned ->
            val activityId = recordActivity(
                taskId = planned.task.id,
                projectId = planned.task.projectId,
                kind = ActivityKind.RECORD_CREATED,
                body = "Created",
                at = at,
                id = planned.activity.id,
            )
            ImportedTaskReceipt(
                taskId = planned.task.id,
                expectedRevision = planned.task.revision,
                expectedTagIds = planned.task.tagIds,
                activityEntryId = activityId,
            )
        }
        val count = plan.tasks.size
        return CommandResult.Success(
            message = "$count ${if (count == 1) "task" else "tasks"} imported",
            undo = DomainCommand.RemoveImportedRecords(
                ImportReceipt(
                    tasks = taskReceipts,
                    projects = projectReceipts,
                    tags = plan.tags.map(::ImportedTagReceipt),
                ),
            ),
        )
    }

    private fun TasksImportPlan.hasIdentityCollision(records: List<BackupRecordV1>): Boolean {
        fun collides(family: BackupRecordFamily, ids: List<String>): Boolean =
            ids.toSet().size != ids.size || ids.any { id ->
                records.any { it.family == family && it.identity == listOf(id) }
            }
        return collides(BackupRecordFamily.PROJECT, projects.map { it.project.id.value }) ||
            collides(
                BackupRecordFamily.WORKFLOW_STATUS,
                projects.flatMap { it.statuses }.map { it.id.value },
            ) ||
            collides(BackupRecordFamily.TASK, tasks.map { it.task.id.value }) ||
            collides(BackupRecordFamily.TAG, tags.map { it.id.value }) ||
            collides(
                BackupRecordFamily.ACTIVITY_ENTRY,
                projects.map { it.activity.id } + tasks.map { it.activity.id },
            )
    }

    private suspend fun preflightImportBackup(
        plan: TasksImportPlan,
        revision: Revision,
        currentRecords: List<BackupRecordV1>,
    ): Boolean {
        val records = buildList {
            addAll(currentRecords)
            plan.projects.forEach { planned ->
                add(planned.project.toEntity(revision).toBackupRecordV1())
                planned.statuses.mapTo(this) { it.toEntity(revision).toBackupRecordV1() }
                add(planned.activity.toEntity().toBackupRecordV1())
            }
            plan.tags.mapTo(this) { it.toEntity().toBackupRecordV1() }
            plan.tasks.forEach { planned ->
                add(planned.task.toEntity().toBackupRecordV1())
                planned.task.tagEntities().mapTo(this) { it.toBackupRecordV1() }
                add(planned.activity.toEntity().toBackupRecordV1())
            }
        }
        return isBackupRepresentable(records)
    }

    private suspend fun isBackupRepresentable(records: List<BackupRecordV1>): Boolean {
        var plaintext: ByteArray? = null
        return try {
            plaintext = BackupSnapshotCodec.encode(
                BackupSnapshotPayloadV1(
                    vaultId = VAULT_ID.value,
                    coveredGeneration = database.backupStateDao().require(VAULT_ID.value)
                        .currentGeneration,
                    records = records,
                ),
            )
            true
        } catch (_: IllegalArgumentException) {
            false
        } finally {
            plaintext?.fill(0)
        }
    }

    private suspend fun removeImportedRecords(
        command: DomainCommand.RemoveImportedRecords,
    ): CommandResult {
        val receipt = command.receipt
        if (!canRemoveImportedRecords(receipt)) {
            return CommandResult.Rejected(
                RejectionReason.IMPORT_UNDO_CONFLICT,
                "Imported records changed and could not be removed.",
            )
        }
        if (!preflightImportUndoBackup(receipt)) {
            return CommandResult.Rejected(
                RejectionReason.IMPORT_UNDO_CONFLICT,
                "The post-Undo state cannot be backed up.",
            )
        }
        val workspaceDao = database.workspaceDao()
        receipt.tasks.forEach { imported ->
            workspaceDao.deleteTagsForTask(imported.taskId.value)
            workspaceDao.deleteActivityEntry(imported.activityEntryId)
            database.taskDao().deleteById(imported.taskId.value)
        }
        receipt.projects.forEach { imported ->
            workspaceDao.deleteActivityEntry(imported.activityEntryId)
            imported.statuses.forEach { workspaceDao.deleteWorkflowStatus(it.id.value) }
            workspaceDao.deleteProject(imported.project.id.value)
        }
        receipt.tags.forEach { workspaceDao.deleteTag(it.tag.id.value) }
        val count = receipt.tasks.size
        return CommandResult.Success("Import removed ($count ${if (count == 1) "task" else "tasks"})")
    }

    private suspend fun preflightImportUndoBackup(receipt: ImportReceipt): Boolean {
        val taskIds = receipt.tasks.mapTo(hashSetOf()) { it.taskId.value }
        val projectIds = receipt.projects.mapTo(hashSetOf()) { it.project.id.value }
        val statusIds = receipt.projects.flatMapTo(hashSetOf()) { project ->
            project.statuses.map { it.id.value }
        }
        val tagIds = receipt.tags.mapTo(hashSetOf()) { it.tag.id.value }
        val activityIds = receipt.tasks.mapTo(hashSetOf()) { it.activityEntryId }.also { ids ->
            receipt.projects.mapTo(ids) { it.activityEntryId }
        }
        val retainedRecords = database.backupCaptureDao().allRecords(VAULT_ID.value).filterNot {
            when (it.family) {
                BackupRecordFamily.TASK -> it.identity.firstOrNull() in taskIds
                BackupRecordFamily.TASK_TAG -> it.identity.firstOrNull() in taskIds
                BackupRecordFamily.ACTIVITY_ENTRY -> it.identity.firstOrNull() in activityIds
                BackupRecordFamily.PROJECT -> it.identity.firstOrNull() in projectIds
                BackupRecordFamily.WORKFLOW_STATUS -> it.identity.firstOrNull() in statusIds
                BackupRecordFamily.TAG -> it.identity.firstOrNull() in tagIds
                else -> false
            }
        }
        return isBackupRepresentable(retainedRecords)
    }

    private suspend fun canRemoveImportedRecords(receipt: ImportReceipt): Boolean {
        val workspaceDao = database.workspaceDao()
        val receiptTaskIds = receipt.tasks.map { it.taskId.value }
        for (expected in receipt.tasks) {
            val task = currentTask(expected.taskId) ?: return false
            if (
                task.revision != expected.expectedRevision ||
                task.tagIds != expected.expectedTagIds ||
                workspaceDao.getActivityEntriesForTask(task.id.value).map { it.id } !=
                listOf(expected.activityEntryId) ||
                database.taskDao().importUndoChildCount(task.id.value) != 0
            ) return false
        }
        for (expected in receipt.projects) {
            if (workspaceDao.getProjectById(expected.project.id.value)?.toModel() != expected.project) {
                return false
            }
            val statuses = workspaceDao.getWorkflowStatuses(expected.project.id.value)
                .map(WorkflowStatusEntity::toModel)
            if (statuses != expected.statuses) return false
            if (
                workspaceDao.getActivityEntriesForProject(expected.project.id.value).map { it.id } !=
                listOf(expected.activityEntryId)
            ) return false
            if (
                workspaceDao.importUndoExternalProjectReferenceCount(
                    projectId = expected.project.id.value,
                    statusIds = expected.statuses.map { it.id.value },
                    receiptTaskIds = receiptTaskIds,
                ) != 0
            ) return false
        }
        for (expected in receipt.tags) {
            if (workspaceDao.getTagById(expected.tag.id.value)?.toModel() != expected.tag) return false
            if (
                workspaceDao.importUndoExternalTagReferenceCount(
                    expected.tag.id.value,
                    receiptTaskIds,
                ) != 0
            ) return false
        }
        val createdProjectIds = receipt.projects.mapTo(hashSetOf()) { it.project.id }
        val createdTagIds = receipt.tags.mapTo(hashSetOf()) { it.tag.id }
        val savedViews = database.backupCaptureDao().savedViews(VAULT_ID.value)
            .mapNotNull { runCatching { it.toModel() }.getOrNull() }
        return savedViews.none { view ->
            view.query.projectIds.any(createdProjectIds::contains) ||
                view.query.tagIds.any(createdTagIds::contains)
        }
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
        val revision = Revision(deviceId, createdAt.toEpochMilli(), 0)
        val statuses = WorkflowStatus.defaults(project.id)
        database.withTransaction {
            database.workspaceDao().upsertProject(project.toEntity(revision))
            statuses.forEach { status ->
                database.workspaceDao().upsertWorkflowStatus(status.toEntity(revision))
            }
        }
        recordActivity(
            taskId = null,
            projectId = project.id,
            kind = ActivityKind.RECORD_CREATED,
            body = "Created",
            at = createdAt,
        )
        return CommandResult.Success("Project created")
    }

    private suspend fun captureProjectTemplate(
        command: DomainCommand.CaptureProjectTemplate,
    ): CommandResult {
        val projectEntity = database.workspaceDao().getProjectById(command.projectId.value)
        val project = projectEntity
            ?.takeIf { it.archivedAtEpochMillis == null }
            ?.toModel()
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Restore that project before saving it as a template.",
            )
        val name = command.name.trim()
        validateTemplateName(name)?.let { return it }
        if (database.workspaceDao().getTemplate(command.templateId.value) != null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That template identifier is already in use.",
            )
        }
        if (
            database.workspaceDao().templateCount(OpenTasksFixtures.workspaceId.value) >=
            MAX_TEMPLATES
        ) {
            return CommandResult.Rejected(
                RejectionReason.TEMPLATE_LIMIT_REACHED,
                "A workspace can contain up to $MAX_TEMPLATES templates.",
            )
        }
        val snapshot = mutableWorkspace.value
        val projectTasks = snapshot.tasks.filter {
            it.projectId == project.id && it.deletedAt == null && !it.isCompleted
        }
        if (projectTasks.size > ProjectTemplatePlanner.MAX_TEMPLATE_TASKS) {
            return CommandResult.Rejected(
                RejectionReason.TEMPLATE_TASK_LIMIT_REACHED,
                "A template can contain up to ${ProjectTemplatePlanner.MAX_TEMPLATE_TASKS} tasks.",
            )
        }
        val revision = Revision(deviceId, now().toEpochMilli(), 0)
        val template = try {
            ProjectTemplatePlanner.capture(
                templateId = command.templateId,
                templateName = name,
                project = project,
                workflowStatuses = snapshot.workflowStatuses,
                milestones = snapshot.milestones,
                tasks = snapshot.tasks,
                tags = snapshot.tags,
                revision = revision,
                fallbackAnchor = LocalDate.ofInstant(now(), zoneId()),
            )
        } catch (_: IllegalArgumentException) {
            return CommandResult.Rejected(
                RejectionReason.TEMPLATE_DATE_RANGE_TOO_LARGE,
                "Template dates must fit within 100 years.",
            )
        }
        val entity = try {
            template.toTemplateEntity()
        } catch (_: IllegalArgumentException) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "This project contains too much template content to save safely.",
            )
        }
        database.withTransaction {
            database.workspaceDao().upsertTemplate(entity)
        }
        return CommandResult.Success(
            message = "Template saved",
            undo = DomainCommand.DeleteTemplate(template.id),
        )
    }

    private suspend fun instantiateProjectTemplate(
        command: DomainCommand.InstantiateProjectTemplate,
    ): CommandResult {
        val templateEntity = database.workspaceDao().getTemplate(command.templateId.value)
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Template no longer exists.",
            )
        val template = runCatching { TemplatePayloadCodec.decode(templateEntity) }
            .getOrElse {
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "This template is damaged and cannot be used.",
                )
            }
        val projectName = command.projectName.trim()
        validateProject(projectName, template.projectSummary)?.let { return it }
        validateUniqueActiveProjectName(projectName)?.let { return it }
        if (database.workspaceDao().getProjectById(command.projectId.value) != null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That project identifier is already in use.",
            )
        }

        val revision = Revision(deviceId, now().toEpochMilli(), 0)
        val created = runCatching {
            ProjectTemplatePlanner.instantiate(
                template = template,
                projectId = command.projectId,
                projectName = projectName,
                anchorDate = command.anchorDate,
                revision = revision,
            )
        }.getOrElse {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "This template contains an invalid relative date.",
            )
        }
        val requestedTagNames = created.tagNamesByTaskId.values.flatten()
            .distinctBy { it.lowercase(Locale.ROOT) }
        val tagsByName = linkedMapOf<String, Tag>()
        val newTags = mutableListOf<Tag>()
        requestedTagNames.forEach { tagName ->
            val existing = database.workspaceDao().findTagByName(
                OpenTasksFixtures.workspaceId.value,
                tagName,
            )?.toModel()
            val tag = existing ?: Tag(
                id = TagId(UUID.randomUUID().toString()),
                workspaceId = template.workspaceId,
                name = tagName,
            ).also(newTags::add)
            tagsByName[tagName.lowercase(Locale.ROOT)] = tag
        }
        val tasks = created.tasks.map { task ->
            task.copy(
                tagIds = created.tagNamesByTaskId[task.id].orEmpty()
                    .mapTo(linkedSetOf()) { tagName ->
                        tagsByName.getValue(tagName.lowercase(Locale.ROOT)).id
                    },
            )
        }

        database.withTransaction {
            database.workspaceDao().upsertProject(created.project.toEntity(revision))
            created.workflowStatuses.forEach { status ->
                database.workspaceDao().upsertWorkflowStatus(status.toEntity(revision))
            }
            created.milestones.forEach { milestone ->
                database.workspaceDao().upsertMilestone(milestone.toEntity(revision))
            }
            newTags.forEach { tag ->
                database.workspaceDao().upsertTag(tag.toEntity())
            }
            tasks.forEach { task ->
                database.taskDao().upsert(task.toEntity())
                database.workspaceDao().insertChecklistItems(task.checklistEntities())
                task.tagEntities().forEach { database.workspaceDao().upsertTaskTag(it) }
                task.dependencyEntities().forEach {
                    database.workspaceDao().upsertDependency(it)
                }
            }
        }
        return CommandResult.Success("Project created from template")
    }

    private suspend fun deleteTemplate(
        command: DomainCommand.DeleteTemplate,
    ): CommandResult {
        val entity = database.workspaceDao().getTemplate(command.templateId.value)
            ?: return CommandResult.Success("Template is already deleted")
        val template = runCatching { TemplatePayloadCodec.decode(entity) }.getOrNull()
        val revision = nextTemplateRevision(entity)
        database.withTransaction {
            database.workspaceDao().deleteTemplate(entity.id)
        }
        return CommandResult.Success(
            message = if (template == null) {
                "Damaged template deleted"
            } else {
                "Template deleted"
            },
            undo = template?.let {
                DomainCommand.RestoreTemplate(it.copy(revision = revision))
            },
        )
    }

    private suspend fun restoreTemplate(
        command: DomainCommand.RestoreTemplate,
    ): CommandResult {
        if (database.workspaceDao().getTemplate(command.template.id.value) != null) {
            return CommandResult.Success("Template restored")
        }
        val name = command.template.name.trim()
        validateTemplateName(name)?.let { return it }
        if (command.template.workspaceId != OpenTasksFixtures.workspaceId) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That template belongs to a different workspace.",
            )
        }
        if (
            database.workspaceDao().templateCount(OpenTasksFixtures.workspaceId.value) >=
            MAX_TEMPLATES
        ) {
            return CommandResult.Rejected(
                RejectionReason.TEMPLATE_LIMIT_REACHED,
                "A workspace can contain up to $MAX_TEMPLATES templates.",
            )
        }
        val revision = Revision(
            deviceId = deviceId,
            wallTimeMillis = maxOf(
                command.template.revision.wallTimeMillis + 1,
                now().toEpochMilli(),
            ),
            logicalCounter = command.template.revision.logicalCounter + 1,
        )
        val restored = command.template.copy(name = name, revision = revision)
        val entity = runCatching { restored.toTemplateEntity() }.getOrElse {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "This template contains invalid or oversized content.",
            )
        }
        database.withTransaction {
            database.workspaceDao().upsertTemplate(entity)
        }
        return CommandResult.Success(
            message = "Template restored",
            undo = DomainCommand.DeleteTemplate(restored.id),
        )
    }

    private suspend fun createWorkflowStatus(
        command: DomainCommand.CreateWorkflowStatus,
    ): CommandResult {
        val project = database.workspaceDao().getProjectById(command.projectId.value)
        if (project == null || project.archivedAtEpochMillis != null) {
            return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Restore that project before editing its workflow.",
            )
        }
        if (database.workspaceDao().getWorkflowStatus(command.statusId.value) != null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That workflow status identifier is already in use.",
            )
        }
        val name = command.name.trim()
        validateWorkflowStatusName(name, command.projectId)?.let { return it }
        val projectStatuses = database.workspaceDao()
            .getWorkflowStatuses(command.projectId.value)
            .map(WorkflowStatusEntity::toModel)
        if (projectStatuses.count { it.archivedAt == null } >= MAX_WORKFLOW_STATUSES) {
            return CommandResult.Rejected(
                RejectionReason.WORKFLOW_STATUS_LIMIT_REACHED,
                "A project can have up to $MAX_WORKFLOW_STATUSES active workflow statuses.",
            )
        }
        val status = WorkflowStatus(
            id = command.statusId,
            projectId = command.projectId,
            name = name,
            semanticStatus = command.semanticStatus,
            rank = rankAfter(projectStatuses.maxByOrNull(WorkflowStatus::rank)?.rank),
        )
        val revision = workflowRevision()
        database.withTransaction {
            database.workspaceDao().upsertWorkflowStatus(status.toEntity(revision))
        }
        return CommandResult.Success(
            message = "$name added",
            undo = DomainCommand.RemoveWorkflowStatus(status.id),
        )
    }

    private suspend fun renameWorkflowStatus(
        command: DomainCommand.RenameWorkflowStatus,
    ): CommandResult {
        val entity = database.workspaceDao().getWorkflowStatus(command.statusId.value)
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Workflow status no longer exists.",
            )
        val original = entity.toModel()
        if (original.archivedAt != null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Restore that workflow status before renaming it.",
            )
        }
        val name = command.name.trim()
        validateWorkflowStatusName(name, original.projectId, original.id)?.let { return it }
        if (name == original.name) return CommandResult.Success("Workflow status is up to date")
        persistWorkflowStatuses(listOf(original.copy(name = name)), listOf(entity))
        return CommandResult.Success(
            message = "Workflow status renamed",
            undo = DomainCommand.RestoreWorkflowStatuses(listOf(original)),
        )
    }

    private suspend fun moveWorkflowStatus(
        command: DomainCommand.MoveWorkflowStatus,
    ): CommandResult {
        val entity = database.workspaceDao().getWorkflowStatus(command.statusId.value)
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Workflow status no longer exists.",
            )
        val original = entity.toModel()
        if (original.archivedAt != null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Restore that workflow status before reordering it.",
            )
        }
        val activeEntities = database.workspaceDao()
            .getWorkflowStatuses(entity.projectId)
            .filter { it.archivedAtEpochMillis == null }
            .sortedBy(WorkflowStatusEntity::rank)
        val index = activeEntities.indexOfFirst { it.id == entity.id }
        val otherIndex = when (command.direction) {
            WorkflowMoveDirection.EARLIER -> index - 1
            WorkflowMoveDirection.LATER -> index + 1
        }
        val otherEntity = activeEntities.getOrNull(otherIndex)
            ?: return CommandResult.Success("Workflow order is unchanged")
        val other = otherEntity.toModel()
        persistWorkflowStatuses(
            statuses = listOf(
                original.copy(rank = other.rank),
                other.copy(rank = original.rank),
            ),
            previousEntities = listOf(entity, otherEntity),
        )
        return CommandResult.Success(
            message = "Workflow reordered",
            undo = DomainCommand.RestoreWorkflowStatuses(listOf(original, other)),
        )
    }

    private suspend fun archiveWorkflowStatus(
        command: DomainCommand.ArchiveWorkflowStatus,
    ): CommandResult {
        val entity = database.workspaceDao().getWorkflowStatus(command.statusId.value)
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Workflow status no longer exists.",
            )
        val original = entity.toModel()
        if (original.archivedAt != null) {
            return CommandResult.Success("Workflow status is already archived")
        }
        val alternatives = database.workspaceDao()
            .getWorkflowStatuses(entity.projectId)
            .count {
                it.archivedAtEpochMillis == null &&
                    it.semanticStatus == original.semanticStatus.name
            }
        if (alternatives <= 1) {
            return CommandResult.Rejected(
                RejectionReason.LAST_SEMANTIC_WORKFLOW_STATUS,
                "Add another ${original.semanticStatus.readableCategory()} status before archiving this one.",
            )
        }
        persistWorkflowStatuses(
            statuses = listOf(original.copy(archivedAt = command.archivedAt)),
            previousEntities = listOf(entity),
        )
        return CommandResult.Success(
            message = "${original.name} archived",
            undo = DomainCommand.RestoreWorkflowStatuses(listOf(original)),
        )
    }

    private suspend fun restoreWorkflowStatuses(
        command: DomainCommand.RestoreWorkflowStatuses,
    ): CommandResult {
        val currentEntities = command.statuses.map { restored ->
            database.workspaceDao().getWorkflowStatus(restored.id.value)
                ?: return CommandResult.Rejected(
                    RejectionReason.NOT_FOUND,
                    "A previous workflow status no longer exists.",
                )
        }
        command.statuses.filter { it.archivedAt == null }.forEach { restored ->
            validateWorkflowStatusName(
                restored.name,
                restored.projectId,
                restored.id,
            )?.let { return it }
        }
        persistWorkflowStatuses(command.statuses, currentEntities)
        return CommandResult.Success("Workflow change undone")
    }

    private suspend fun restoreArchivedWorkflowStatus(
        command: DomainCommand.RestoreArchivedWorkflowStatus,
    ): CommandResult {
        val entity = database.workspaceDao().getWorkflowStatus(command.statusId.value)
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Workflow status no longer exists.",
            )
        val original = entity.toModel()
        val archivedAt =
            original.archivedAt ?: return CommandResult.Success("Workflow status is already active")
        validateWorkflowStatusName(original.name, original.projectId, original.id)?.let {
            return it
        }
        persistWorkflowStatuses(
            statuses = listOf(original.copy(archivedAt = null)),
            previousEntities = listOf(entity),
        )
        return CommandResult.Success(
            message = "${original.name} restored",
            undo = DomainCommand.ArchiveWorkflowStatus(original.id, archivedAt),
        )
    }

    private suspend fun removeWorkflowStatus(
        command: DomainCommand.RemoveWorkflowStatus,
    ): CommandResult {
        val entity = database.workspaceDao().getWorkflowStatus(command.statusId.value)
            ?: return CommandResult.Success("Workflow status is already removed")
        if (database.workspaceDao().taskCountForStatus(entity.id) > 0) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Move tasks out of this status before removing it.",
            )
        }
        val status = entity.toModel()
        if (
            status.archivedAt == null &&
            database.workspaceDao().getWorkflowStatuses(entity.projectId).count {
                it.semanticStatus == entity.semanticStatus &&
                    it.archivedAtEpochMillis == null
            } <= 1
        ) {
            return CommandResult.Rejected(
                RejectionReason.LAST_SEMANTIC_WORKFLOW_STATUS,
                "Keep at least one active ${status.semanticStatus.readableCategory()} status.",
            )
        }
        database.withTransaction {
            database.workspaceDao().deleteWorkflowStatus(entity.id)
        }
        return CommandResult.Success("Workflow status removed")
    }

    private suspend fun createMilestone(
        command: DomainCommand.CreateMilestone,
    ): CommandResult {
        val project = database.workspaceDao().getProjectById(command.projectId.value)
        if (project == null || project.archivedAtEpochMillis != null) {
            return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Restore that project before adding milestones.",
            )
        }
        if (database.workspaceDao().getMilestone(command.milestoneId.value) != null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That milestone identifier is already in use.",
            )
        }
        val name = command.name.trim()
        validateMilestoneName(name, command.projectId)?.let { return it }
        if (database.workspaceDao().getMilestones(project.id).size >= MAX_MILESTONES) {
            return CommandResult.Rejected(
                RejectionReason.MILESTONE_LIMIT_REACHED,
                "A project can have up to $MAX_MILESTONES milestones.",
            )
        }
        val milestone = Milestone(
            id = command.milestoneId,
            projectId = command.projectId,
            name = name,
            dueDate = command.dueDate,
        )
        val revision = milestoneRevision()
        database.withTransaction {
            database.workspaceDao().upsertMilestone(milestone.toEntity(revision))
        }
        return CommandResult.Success(
            message = "$name added",
            undo = DomainCommand.DeleteMilestone(milestone.id),
        )
    }

    private suspend fun updateMilestone(
        command: DomainCommand.UpdateMilestone,
    ): CommandResult {
        val entity = database.workspaceDao().getMilestone(command.milestoneId.value)
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Milestone no longer exists.",
            )
        val original = entity.toModel()
        val name = command.name.trim()
        validateMilestoneName(name, original.projectId, original.id)?.let { return it }
        val updated = original.copy(
            name = name,
            dueDate = command.dueDate,
            completedAt = command.completedAt,
        )
        if (updated == original) return CommandResult.Success("Milestone is up to date")
        persistMilestone(updated, nextMilestoneRevision(entity), deleted = false)
        return CommandResult.Success(
            message = if (updated.completedAt != null && original.completedAt == null) {
                "Milestone completed"
            } else if (updated.completedAt == null && original.completedAt != null) {
                "Milestone reopened"
            } else {
                "Milestone saved"
            },
            undo = DomainCommand.RestoreMilestone(original),
        )
    }

    private suspend fun deleteMilestone(
        command: DomainCommand.DeleteMilestone,
    ): CommandResult {
        val entity = database.workspaceDao().getMilestone(command.milestoneId.value)
            ?: return CommandResult.Success("Milestone is already deleted")
        val milestone = entity.toModel()
        val assignedTasks = database.taskDao().getByMilestoneId(entity.id)
            .mapNotNull { currentTask(TaskId(it.id)) }
        val changedAt = now()
        val updatedTasks = assignedTasks.map { task ->
            task.copy(
                milestoneId = null,
                revision = nextRevision(task, changedAt),
            )
        }
        database.withTransaction {
            updatedTasks.forEach { task ->
                database.taskDao().upsert(task.toEntity())
            }
            database.workspaceDao().deleteMilestone(entity.id)
        }
        return CommandResult.Success(
            message = "Milestone deleted",
            undo = DomainCommand.RestoreMilestone(
                milestone = milestone,
                assignedTaskIds = assignedTasks.mapTo(linkedSetOf(), Task::id),
            ),
        )
    }

    private suspend fun restoreMilestone(
        command: DomainCommand.RestoreMilestone,
    ): CommandResult {
        val project = database.workspaceDao()
            .getProjectById(command.milestone.projectId.value)
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "The milestone project no longer exists.",
            )
        val existing = database.workspaceDao().getMilestone(command.milestone.id.value)
        validateMilestoneName(
            command.milestone.name,
            command.milestone.projectId,
            command.milestone.id,
        )?.let { return it }
        val revision = existing?.let(::nextMilestoneRevision) ?: milestoneRevision()
        val changedAt = now()
        val updatedTasks = buildList {
            command.assignedTaskIds.orEmpty().forEach { taskId ->
                currentTask(taskId)
                    ?.takeIf { it.projectId?.value == project.id }
                    ?.let { task ->
                        add(
                            task.copy(
                                milestoneId = command.milestone.id,
                                revision = nextRevision(task, changedAt),
                            ),
                        )
                    }
            }
        }
        database.withTransaction {
            database.workspaceDao().upsertMilestone(command.milestone.toEntity(revision))
            updatedTasks.forEach { task ->
                database.taskDao().upsert(task.toEntity())
            }
        }
        return CommandResult.Success("Milestone restored")
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

    private suspend fun markReviewed(command: DomainCommand.MarkReviewed): CommandResult {
        val taskId = command.taskId
        val projectId = command.projectId
        if ((taskId == null) == (projectId == null)) {
            return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Review target no longer exists.")
        }
        if (taskId != null) {
            val task = currentTask(taskId)
                ?.takeIf { it.deletedAt == null }
                ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
            recordActivity(task.id, task.projectId, ActivityKind.REVIEWED, "Reviewed", command.reviewedAt)
        } else {
            val project = database.workspaceDao().getProjectById(projectId!!.value)
                ?.toModel()
                ?.takeIf { it.archivedAt == null }
                ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Project no longer exists.")
            recordActivity(null, project.id, ActivityKind.REVIEWED, "Reviewed", command.reviewedAt)
        }
        return CommandResult.Success("Marked as reviewed")
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
        runBlocking {
            repositoryJob.cancelAndJoin()
        }
    }

    private suspend fun createTask(command: DomainCommand.CreateTask): CommandResult {
        command.projectId?.let { projectId ->
            val project = database.workspaceDao().getProjectById(projectId.value)
            if (project == null || project.archivedAtEpochMillis != null) {
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Restore that project before assigning new tasks to it.",
                )
            }
        }
        val initialStatus = database.workspaceDao()
            .getWorkflowStatuses(command.projectId?.value)
            .firstOrNull {
                it.semanticStatus == SemanticStatus.BACKLOG.name &&
                    it.archivedAtEpochMillis == null
            }
            ?.toModel() ?: return CommandResult.Rejected(
            RejectionReason.INVALID_STATE,
            "This project has no active Backlog status.",
        )
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
        }
        val uniqueTagNames = linkedMapOf<String, String>()
        for (rawName in command.tagNames) {
            val name = rawName.trim()
            validateTagName(name)?.let { return it }
            uniqueTagNames.putIfAbsent(name.lowercase(Locale.ROOT), name)
        }
        if (uniqueTagNames.size > MAX_TASK_TAGS) {
            return CommandResult.Rejected(
                RejectionReason.TAG_LIMIT_REACHED,
                "A task can contain up to $MAX_TASK_TAGS tags.",
            )
        }
        val recurrence = command.recurrence
        val dueLocalDate = command.due?.let { due ->
            due.instant.atZone(ZoneId.of(due.zoneId)).toLocalDate()
        }
        when {
            command.estimate?.let { it.isZero || it.isNegative } == true ->
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Estimate must be greater than zero.",
                )
            recurrence != null && command.due == null ->
                return CommandResult.Rejected(
                    RejectionReason.RECURRENCE_REQUIRES_DUE,
                    "Add a due date before repeating this task.",
                )
            recurrence != null && recurrence.interval > MAX_RECURRENCE_INTERVAL ->
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Repeat interval is too large.",
                )
            recurrence?.count != null && recurrence.endDate != null ->
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Choose either an occurrence count or an end date.",
                )
            recurrence?.endDate?.let { endDate ->
                dueLocalDate != null && endDate.isBefore(dueLocalDate)
            } == true ->
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "The repeat end date cannot be before the due date.",
                )
        }
        val existingTags = mutableListOf<Tag>()
        val missingTagNames = mutableListOf<String>()
        for (name in uniqueTagNames.values) {
            val existing = database.workspaceDao()
                .findTagByName(OpenTasksFixtures.workspaceId.value, name)
                ?.toModel()
            if (existing == null) missingTagNames += name else existingTags += existing
        }

        // No TaskId.new/UUID.randomUUID call is permitted above this line.
        val createdAt = now()
        val taskId = TaskId.new()
        val freshTags = missingTagNames.map { name ->
            Tag(TagId(UUID.randomUUID().toString()), OpenTasksFixtures.workspaceId, name)
        }
        val base = Task(
            id = taskId,
            workspaceId = OpenTasksFixtures.workspaceId,
            projectId = command.projectId,
            statusId = initialStatus.id,
            semanticStatus = initialStatus.semanticStatus,
            title = title,
            priority = command.priority,
            due = command.due,
            estimate = command.estimate,
            recurrence = command.recurrence,
            tagIds = (existingTags.map(Tag::id) + freshTags.map(Tag::id)).toSet(),
            revision = Revision(deviceId, createdAt.toEpochMilli(), 0),
        )
        val metadata = RecurringTaskPlanner.metadataForUpdate(
            base,
            base.start,
            base.due,
            base.recurrence,
        )
        val task = base.copy(
            recurrenceSeriesId = metadata?.seriesId,
            recurrenceAnchor = metadata?.anchor,
            recurrenceOccurrenceIndex = metadata?.occurrenceIndex,
        )
        freshTags.forEach { database.workspaceDao().upsertTag(it.toEntity()) }
        persistTask(task, "create")
        task.tagEntities().forEach { database.workspaceDao().upsertTaskTag(it) }
        recordActivity(
            taskId = task.id,
            projectId = task.projectId,
            kind = ActivityKind.RECORD_CREATED,
            body = "Created",
            at = createdAt,
        )
        return CommandResult.Success(
            message = "Task added",
            undo = DomainCommand.DeleteTask(task.id, createdAt),
        )
    }

    private suspend fun duplicateTask(command: DomainCommand.DuplicateTask): CommandResult {
        val source = currentTask(command.taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val statuses = database.workspaceDao()
            .getWorkflowStatuses(source.projectId?.value)
            .map(WorkflowStatusEntity::toModel)
        if (source.deletedAt != null) {
            return CommandResult.Rejected(RejectionReason.INVALID_STATE, "Restore that task first.")
        }
        val targetStatus = if (!source.isCompleted) {
            statuses.firstOrNull { it.id == source.statusId }
        } else {
            statuses.asSequence()
                .filter {
                    it.projectId == source.projectId &&
                        it.semanticStatus == SemanticStatus.BACKLOG &&
                        it.archivedAt == null
                }
                .minByOrNull { it.rank }
        } ?: return CommandResult.Rejected(
            RejectionReason.INVALID_STATE,
            "This task has no available destination status.",
        )

        val createdAt = now()
        val duplicate = planTaskDuplicate(
            source = source,
            targetStatus = targetStatus,
            duplicateId = TaskId.new(),
            checklistItemIds = source.checklist.map { UUID.randomUUID().toString() },
            revision = Revision(deviceId, createdAt.toEpochMilli(), 0),
        )
        database.taskDao().upsert(duplicate.toEntity())
        duplicate.checklistEntities().forEach { database.workspaceDao().upsertChecklistItem(it) }
        duplicate.tagEntities().forEach { database.workspaceDao().upsertTaskTag(it) }
        duplicate.dependencyEntities().forEach { database.workspaceDao().upsertDependency(it) }
        recordActivity(
            taskId = duplicate.id,
            projectId = duplicate.projectId,
            kind = ActivityKind.RECORD_CREATED,
            body = "Created",
            at = createdAt,
        )
        return CommandResult.Success(
            message = "Task duplicated",
            undo = DomainCommand.DeleteTask(duplicate.id, createdAt),
        )
    }

    private suspend fun completeTask(command: DomainCommand.CompleteTask): CommandResult {
        val task = currentTask(command.taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val completedStatus = database.workspaceDao()
            .getWorkflowStatuses(task.projectId?.value)
            .firstOrNull {
                it.semanticStatus == SemanticStatus.COMPLETED.name &&
                    it.archivedAtEpochMillis == null
            }
            ?.toModel()
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
            activityKind = ActivityKind.COMPLETED,
            activityBody = "Completed",
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

    private suspend fun completeTasks(command: DomainCommand.CompleteTasks): CommandResult {
        val ids = command.taskIds.distinct()
        rejectBulkSelection(ids)?.let { return it }
        val resolved = ids.mapNotNull { currentTask(it) }
        if (resolved.isEmpty()) return bulkTasksNotFound()

        // Full preflight before the first write: the single-task validators,
        // in their order, over the whole resolved set. Writes happen only
        // afterwards, inside the outer execute transaction, so a rejection
        // leaves records and journal untouched.
        class CompletionPlan(
            val task: Task,
            val updated: Task,
            val generated: Task?,
            val generatedReminder: Reminder?,
        )
        val plans = mutableListOf<CompletionPlan>()
        val plannedGeneratedIds = hashSetOf<TaskId>()
        for (task in resolved) {
            val completedStatus = database.workspaceDao()
                .getWorkflowStatuses(task.projectId?.value)
                .firstOrNull {
                    it.semanticStatus == SemanticStatus.COMPLETED.name &&
                        it.archivedAtEpochMillis == null
                }
                ?.toModel()
                ?: return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "This workspace has no active completion status.",
                )
            if (task.isBlocked && !command.acknowledgeBlocked) {
                return CommandResult.Rejected(
                    RejectionReason.BLOCKED_TASK_WARNING_REQUIRED,
                    "This task still has unfinished dependencies.",
                )
            }
            if (task.statusId == completedStatus.id) continue
            val updated = task.copy(
                statusId = completedStatus.id,
                semanticStatus = completedStatus.semanticStatus,
                completedAt = command.completedAt,
                revision = nextRevision(task, command.completedAt),
            )
            val generatedCandidate = if (task.semanticStatus != SemanticStatus.COMPLETED) {
                val workflow = database.workspaceDao()
                    .getWorkflowStatuses(task.projectId?.value)
                    .filter { it.archivedAtEpochMillis == null }
                val nextStatus = workflow.firstOrNull {
                    it.semanticStatus == SemanticStatus.PLANNED.name
                }?.toModel() ?: workflow.firstOrNull {
                    it.semanticStatus == SemanticStatus.BACKLOG.name
                }?.toModel()
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
                it.id !in plannedGeneratedIds &&
                    database.taskDao().getById(it.id.value) == null
            }
            generated?.let { plannedGeneratedIds += it.id }
            val generatedReminder = generated?.let { next ->
                nextOccurrenceReminder(
                    currentTask = task,
                    nextTask = next,
                    reminder = database.workspaceDao()
                        .getReminderForTask(task.id.value)
                        ?.toModel(),
                )
            }
            plans += CompletionPlan(task, updated, generated, generatedReminder)
        }

        plans.forEach { plan ->
            database.taskDao().upsert(plan.updated.toEntity())
            plan.generated?.let { generated ->
                database.taskDao().upsert(generated.toEntity())
                database.workspaceDao().insertTaskTags(generated.tagEntities())
                database.workspaceDao().insertChecklistItems(generated.checklistEntities())
            }
            plan.generatedReminder?.let { reminder ->
                database.workspaceDao().upsertReminder(reminder.toEntity())
            }
            recordActivity(
                taskId = plan.task.id,
                projectId = plan.task.projectId,
                kind = ActivityKind.COMPLETED,
                body = "Completed",
                at = command.completedAt,
            )
        }
        val inverses = plans.map<CompletionPlan, DomainCommand> { plan ->
            DomainCommand.RestoreTaskStatus(
                taskId = plan.task.id,
                statusId = plan.task.statusId,
                completedAt = plan.task.completedAt,
                generatedOccurrenceId = plan.generated?.id,
            )
        }
        return CommandResult.Success(
            message = "${bulkTasksLabel(plans.size)} completed",
            undo = DomainCommand.UndoBatch(inverses.asReversed())
                .takeIf { inverses.isNotEmpty() },
        )
    }

    private suspend fun rescheduleTasks(command: DomainCommand.RescheduleTasks): CommandResult {
        val ids = command.taskIds.distinct()
        rejectBulkSelection(ids)?.let { return it }
        val resolved = ids.mapNotNull { currentTask(it) }
        if (resolved.isEmpty()) return bulkTasksNotFound()

        val due = command.due
        for (task in resolved) {
            when {
                task.recurrence != null && due == null && task.start == null ->
                    return CommandResult.Rejected(
                        RejectionReason.INVALID_STATE,
                        "Add a due date before repeating this task.",
                    )
                task.recurrence?.endDate?.let { endDate ->
                    due?.let {
                        endDate.isBefore(
                            it.instant.atZone(ZoneId.of(it.zoneId)).toLocalDate(),
                        )
                    }
                } == true ->
                    return CommandResult.Rejected(
                        RejectionReason.INVALID_STATE,
                        "The repeat end date cannot be before the due date.",
                    )
            }
        }

        val changing = resolved.filter { it.due != due }
        val inverses = changing.map<Task, DomainCommand> { task ->
            task.toUpdateCommand(
                database.workspaceDao().getReminderForTask(task.id.value)?.toModel(),
            )
        }
        changing.forEach { task ->
            database.taskDao().upsert(
                task.copy(due = due, revision = nextRevision(task)).toEntity(),
            )
        }
        return CommandResult.Success(
            message = "${bulkTasksLabel(changing.size)} rescheduled",
            undo = DomainCommand.UndoBatch(inverses.asReversed())
                .takeIf { inverses.isNotEmpty() },
        )
    }

    private suspend fun moveTasksToProject(
        command: DomainCommand.MoveTasksToProject,
    ): CommandResult {
        val ids = command.taskIds.distinct()
        rejectBulkSelection(ids)?.let { return it }
        val resolved = ids.mapNotNull { currentTask(it) }
        if (resolved.isEmpty()) return bulkTasksNotFound()

        val destination = command.projectId?.let { projectId ->
            database.workspaceDao().getProjectById(projectId.value)
                ?: return CommandResult.Rejected(
                    RejectionReason.NOT_FOUND,
                    "That project no longer exists.",
                )
        }
        class MovePlan(val task: Task, val status: WorkflowStatus, val reminder: Reminder?)
        val plans = mutableListOf<MovePlan>()
        for (task in resolved) {
            if (task.projectId == command.projectId) continue
            if (destination?.archivedAtEpochMillis != null) {
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Restore that project before assigning new tasks to it.",
                )
            }
            val mapped = database.workspaceDao()
                .getWorkflowStatuses(command.projectId?.value)
                .firstOrNull {
                    it.semanticStatus == task.semanticStatus.name &&
                        it.archivedAtEpochMillis == null
                }
                ?.toModel()
                ?: return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "The destination workflow has no matching ${task.semanticStatus.readableCategory()} status.",
                )
            plans += MovePlan(
                task = task,
                status = mapped,
                reminder = database.workspaceDao()
                    .getReminderForTask(task.id.value)
                    ?.toModel(),
            )
        }

        val destinationName = destination?.name ?: "Inbox"
        val changedAt = now()
        plans.forEach { plan ->
            val task = plan.task
            database.taskDao().upsert(
                task.copy(
                    projectId = command.projectId,
                    statusId = plan.status.id,
                    semanticStatus = plan.status.semanticStatus,
                    milestoneId = null,
                    revision = nextRevision(task),
                ).toEntity(),
            )
            recordActivity(
                taskId = task.id,
                projectId = command.projectId,
                kind = ActivityKind.PROJECT_MOVED,
                body = "${database.workspaceDao().getProjectById(task.projectId?.value.orEmpty())?.name ?: "Inbox"} → " +
                    destinationName,
                at = changedAt,
            )
            if (task.milestoneId != null) {
                recordActivity(
                    taskId = task.id,
                    projectId = command.projectId,
                    kind = ActivityKind.MILESTONE_CHANGED,
                    body = "Milestone: None",
                    at = changedAt,
                )
            }
        }
        val inverses = plans.map<MovePlan, DomainCommand> { plan ->
            plan.task.toUpdateCommand(plan.reminder)
        }
        return CommandResult.Success(
            message = "${bulkTasksLabel(plans.size)} moved",
            undo = DomainCommand.UndoBatch(inverses.asReversed())
                .takeIf { inverses.isNotEmpty() },
        )
    }

    private suspend fun setTasksTag(command: DomainCommand.SetTasksTag): CommandResult {
        val ids = command.taskIds.distinct()
        rejectBulkSelection(ids)?.let { return it }
        val resolved = ids.mapNotNull { currentTask(it) }
        if (resolved.isEmpty()) return bulkTasksNotFound()

        if (database.workspaceDao().getTagById(command.tagId.value) == null) {
            return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Tag no longer exists.")
        }
        val changing = resolved.filter { (command.tagId in it.tagIds) != command.present }
        if (command.present && changing.any { it.tagIds.size >= MAX_TASK_TAGS }) {
            return CommandResult.Rejected(
                RejectionReason.TAG_LIMIT_REACHED,
                "A task can contain up to $MAX_TASK_TAGS tags.",
            )
        }

        changing.forEach { task ->
            val updated = task.copy(
                tagIds = if (command.present) {
                    task.tagIds + command.tagId
                } else {
                    task.tagIds - command.tagId
                },
                revision = nextRevision(task),
            )
            database.taskDao().upsert(updated.toEntity())
            database.workspaceDao().upsertTaskTag(
                TaskTagEntity(
                    taskId = task.id.value,
                    tagId = command.tagId.value,
                    present = command.present,
                    revisionWallMillis = updated.revision.wallTimeMillis,
                    revisionLogical = updated.revision.logicalCounter,
                    revisionDeviceId = updated.revision.deviceId.value,
                ),
            )
        }
        val inverses = changing.map<Task, DomainCommand> { task ->
            DomainCommand.SetTaskTag(task.id, command.tagId, !command.present)
        }
        return CommandResult.Success(
            message = if (command.present) {
                "Tag added to ${bulkTasksLabel(changing.size)}"
            } else {
                "Tag removed from ${bulkTasksLabel(changing.size)}"
            },
            undo = DomainCommand.UndoBatch(inverses.asReversed())
                .takeIf { inverses.isNotEmpty() },
        )
    }

    private suspend fun deleteTasks(command: DomainCommand.DeleteTasks): CommandResult {
        val ids = command.taskIds.distinct()
        rejectBulkSelection(ids)?.let { return it }
        val resolved = ids.mapNotNull { currentTask(it) }
        if (resolved.isEmpty()) return bulkTasksNotFound()

        val deleting = resolved.filter { it.deletedAt == null }
        val deletingIds = deleting.mapTo(hashSetOf(), Task::id)
        deleting.forEach { task ->
            database.taskDao().upsert(
                task.copy(
                    deletedAt = command.deletedAt,
                    revision = nextRevision(task, command.deletedAt),
                ).toEntity(),
            )
        }
        if (deleting.isNotEmpty()) {
            database.timeEntryDao().getActive()
                ?.takeIf { active -> TaskId(active.taskId) in deletingIds }
                ?.let { active ->
                    database.timeEntryDao().stop(
                        active.id,
                        maxOf(command.deletedAt.toEpochMilli(), active.startedAtEpochMillis),
                    )
                }
        }
        deleting.forEach { task ->
            recordActivity(
                taskId = task.id,
                projectId = task.projectId,
                kind = ActivityKind.BINNED,
                body = "Moved to Bin",
                at = command.deletedAt,
            )
        }
        val inverses = deleting.map<Task, DomainCommand> { task ->
            DomainCommand.RestoreTask(task.id)
        }
        return CommandResult.Success(
            message = "${bulkTasksLabel(deleting.size)} moved to the Bin",
            undo = DomainCommand.UndoBatch(inverses.asReversed())
                .takeIf { inverses.isNotEmpty() },
        )
    }

    /**
     * Replays a repository-produced batch undo in its stored order inside the
     * outer execute transaction. Every inverse is preflighted against current
     * state before the first write, so a rejected inverse returns that
     * rejection with no record or journal change — the same all-or-nothing
     * rule the composites follow. After a full preflight an apply-time
     * rejection is an internal invariant failure thrown across the
     * transaction boundary, which rolls the whole batch back; sub-results
     * carry no further undo.
     */
    private suspend fun undoBatch(command: DomainCommand.UndoBatch): CommandResult {
        command.commands.forEach { inverse ->
            rejectUndoCommand(inverse)?.let { return it }
        }
        command.commands.forEach { inverse ->
            val result = dispatch(inverse)
            check(result !is CommandResult.Rejected) {
                "UndoBatch inverse rejected after preflight"
            }
        }
        return CommandResult.Success("Undone")
    }

    /**
     * Preflights one stored inverse against current state without writing,
     * mirroring the rejection paths of the corresponding handler. The
     * repository only ever stores [DomainCommand.RestoreTaskStatus],
     * [DomainCommand.RestoreTask], [DomainCommand.UpdateTask],
     * [DomainCommand.SetTaskSchedule], and [DomainCommand.SetTaskTag]; any
     * other shape fails closed.
     */
    private suspend fun rejectUndoCommand(inverse: DomainCommand): CommandResult.Rejected? =
        when (inverse) {
            is DomainCommand.RestoreTaskStatus -> when {
                database.taskDao().getById(inverse.taskId.value) == null ->
                    CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
                database.workspaceDao().getWorkflowStatus(inverse.statusId.value) == null ->
                    CommandResult.Rejected(
                        RejectionReason.NOT_FOUND,
                        "The previous workflow status no longer exists.",
                    )
                else -> null
            }
            is DomainCommand.RestoreTask ->
                if (database.taskDao().getById(inverse.taskId.value) == null) {
                    CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
                } else {
                    null
                }
            is DomainCommand.SetTaskTag -> {
                val task = currentTask(inverse.taskId)
                when {
                    task == null -> CommandResult.Rejected(
                        RejectionReason.NOT_FOUND,
                        "Task no longer exists.",
                    )
                    database.workspaceDao().getTagById(inverse.tagId.value) == null ->
                        CommandResult.Rejected(
                            RejectionReason.NOT_FOUND,
                            "Tag no longer exists.",
                        )
                    inverse.present &&
                        inverse.tagId !in task.tagIds &&
                        task.tagIds.size >= MAX_TASK_TAGS -> CommandResult.Rejected(
                        RejectionReason.TAG_LIMIT_REACHED,
                        "A task can contain up to $MAX_TASK_TAGS tags.",
                    )
                    else -> null
                }
            }
            is DomainCommand.UpdateTask ->
                (validateTaskUpdate(inverse) as? TaskUpdateValidation.Invalid)?.rejection
            is DomainCommand.SetTaskSchedule -> {
                val task = currentTask(inverse.taskId)
                if (task == null) {
                    CommandResult.Rejected(
                        RejectionReason.NOT_FOUND,
                        "Task no longer exists.",
                    )
                } else {
                    rejectTaskSchedule(
                        command = inverse,
                        task = task,
                        existingReminder = database.workspaceDao()
                            .getReminderForTask(task.id.value)
                            ?.toModel(),
                    )
                }
            }
            else -> CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "This undo step cannot be replayed.",
            )
        }

    private fun rejectBulkSelection(ids: List<TaskId>): CommandResult.Rejected? = when {
        ids.isEmpty() -> CommandResult.Rejected(
            RejectionReason.EMPTY_BULK_SELECTION,
            "Select at least one task.",
        )
        ids.size > MAX_BULK_TASKS -> CommandResult.Rejected(
            RejectionReason.BULK_SELECTION_TOO_LARGE,
            "Bulk actions are limited to $MAX_BULK_TASKS tasks.",
        )
        else -> null
    }

    private fun bulkTasksNotFound(): CommandResult.Rejected = CommandResult.Rejected(
        RejectionReason.NOT_FOUND,
        "Those tasks no longer exist.",
    )

    private fun bulkTasksLabel(count: Int): String =
        if (count == 1) "1 task" else "$count tasks"

    private suspend fun changeTaskStatus(
        command: DomainCommand.ChangeTaskStatus,
        activityKind: ActivityKind = ActivityKind.STATUS_CHANGED,
        activityBody: String? = null,
    ): CommandResult {
        val task = currentTask(command.taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val status = database.workspaceDao().getWorkflowStatus(command.statusId.value)
            ?.takeIf {
                it.projectId == task.projectId?.value && it.archivedAtEpochMillis == null
            }
            ?.toModel() ?: return CommandResult.Rejected(
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
            val workflow = database.workspaceDao()
                .getWorkflowStatuses(task.projectId?.value)
                .filter { it.archivedAtEpochMillis == null }
            val nextStatus = workflow.firstOrNull {
                it.semanticStatus == SemanticStatus.PLANNED.name
            }?.toModel() ?: workflow.firstOrNull {
                it.semanticStatus == SemanticStatus.BACKLOG.name
            }?.toModel()
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
        val currentReminder = database.workspaceDao()
            .getReminderForTask(task.id.value)
            ?.toModel()
        val generatedReminder = generated?.let { next ->
            nextOccurrenceReminder(task, next, currentReminder)
        }
        val operationKind =
            if (status.semanticStatus == SemanticStatus.COMPLETED) "complete" else "status"
        if (generated == null) {
            persistTask(updated, operationKind)
        } else {
            database.withTransaction {
                database.taskDao().upsert(updated.toEntity())
                database.taskDao().upsert(generated.toEntity())
                database.workspaceDao().insertTaskTags(generated.tagEntities())
                database.workspaceDao().insertChecklistItems(generated.checklistEntities())
                generatedReminder?.let { reminder ->
                    database.workspaceDao().upsertReminder(reminder.toEntity())
                }
            }
        }
        val previousStatus = database.workspaceDao().getWorkflowStatus(task.statusId.value)?.toModel()
        recordActivity(
            taskId = task.id,
            projectId = task.projectId,
            kind = activityKind,
            body = activityBody ?: "${previousStatus?.name ?: "Unknown"} → ${status.name}",
            at = command.changedAt,
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

    private suspend fun restoreTaskStatus(
        command: DomainCommand.RestoreTaskStatus,
    ): CommandResult {
        val task = currentTask(command.taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val status = database.workspaceDao().getWorkflowStatus(command.statusId.value)
            ?.toModel() ?: return CommandResult.Rejected(
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
                val generatedId = generatedEntity.id
                database.workspaceDao().deleteChecklistForTask(generatedId)
                database.workspaceDao().deleteTagsForTask(generatedId)
                database.workspaceDao().deleteDependenciesForTask(generatedId)
                database.workspaceDao().deleteRemindersForTask(generatedId)
                database.workspaceDao().getAttachmentsWithBlobSetForTask(generatedId)
                    .forEach { attachment ->
                        database.workspaceDao().upsertRetiredBlobSet(
                            RetiredBlobSetEntity(
                                blobSetId = requireNotNull(attachment.blobSetId),
                                chunkCount = attachment.chunkCount,
                                retiredAtEpochMillis = command.restoredAt.toEpochMilli(),
                                revisionWallMillis = command.restoredAt.toEpochMilli(),
                                revisionLogical = 0,
                                revisionDeviceId = deviceId.value,
                            ),
                        )
                    }
                database.workspaceDao().deleteAttachmentsForTask(generatedId)
                database.workspaceDao().deleteActivityForTask(generatedId)
                database.workspaceDao().deleteTimeForTask(generatedId)
                database.taskDao().deleteById(generatedId)
                database.workspaceDao().upsertTombstone(tombstone)
            }
        }
        val previousStatus = database.workspaceDao().getWorkflowStatus(task.statusId.value)?.toModel()
        recordActivity(
            taskId = task.id,
            projectId = task.projectId,
            kind = ActivityKind.STATUS_CHANGED,
            body = "${previousStatus?.name ?: "Unknown"} → ${status.name}",
            at = command.restoredAt,
        )
        return CommandResult.Success("Status restored")
    }

    private suspend fun reopenTask(taskId: TaskId): CommandResult {
        val task = currentTask(taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val workflow = database.workspaceDao()
            .getWorkflowStatuses(task.projectId?.value)
            .filter { it.archivedAtEpochMillis == null }
        val status = workflow.firstOrNull {
            it.semanticStatus == SemanticStatus.PLANNED.name
        }?.toModel() ?: workflow.firstOrNull {
            it.semanticStatus == SemanticStatus.BACKLOG.name
        }?.toModel() ?: return CommandResult.Rejected(
            RejectionReason.INVALID_STATE,
            "This workflow has no active status for reopened tasks.",
        )
        persistTask(
            task.copy(
                statusId = status.id,
                semanticStatus = status.semanticStatus,
                completedAt = null,
                revision = nextRevision(task),
            ),
            "reopen",
        )
        recordActivity(
            taskId = task.id,
            projectId = task.projectId,
            kind = ActivityKind.REOPENED,
            body = "Reopened",
            at = now(),
        )
        return CommandResult.Success("Task reopened")
    }

    private suspend fun deleteTask(command: DomainCommand.DeleteTask): CommandResult {
        val task = currentTask(command.taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        if (task.deletedAt != null) {
            return CommandResult.Success("Task is already in the Bin")
        }
        val updated = task.copy(
            deletedAt = command.deletedAt,
            revision = nextRevision(task, command.deletedAt),
        )
        database.withTransaction {
            database.taskDao().upsert(updated.toEntity())
            database.timeEntryDao().getActive()
                ?.takeIf { active -> active.taskId == task.id.value }
                ?.let { active ->
                    val stoppedAt = maxOf(
                        command.deletedAt.toEpochMilli(),
                        active.startedAtEpochMillis,
                    )
                    database.timeEntryDao().stop(active.id, stoppedAt)
                }
        }
        recordActivity(
            taskId = task.id,
            projectId = task.projectId,
            kind = ActivityKind.BINNED,
            body = "Moved to Bin",
            at = command.deletedAt,
        )
        return CommandResult.Success(
            message = "Task moved to the Bin",
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
        recordActivity(
            taskId = task.id,
            projectId = task.projectId,
            kind = ActivityKind.RESTORED,
            body = "Restored from Bin",
            at = now(),
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
                "Move the task to the Bin before deleting it permanently.",
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
            if (count == 0) "No expired Bin items" else "$count expired Bin items deleted",
        )
    }

    private suspend fun purgeExpiredTrashRecords(at: Instant): Int {
        val cutoff = at.minusSeconds(TrashPolicy.RETENTION_DAYS * SECONDS_PER_DAY)
        val expired = database.taskDao().getDeletedAtOrBefore(cutoff.toEpochMilli())
        expired.forEach { entity -> purgeTaskEntity(entity, at) }
        return expired.size
    }

    private suspend fun purgeExpiredTrashRecordsAtomically(at: Instant) {
        database.withTransaction {
            val mutationDao = database.backupMutationDao()
            val before = mutationDao.snapshots()
            purgeExpiredTrashRecords(at)
            RoomBackupJournalSession(
                vaultId = VAULT_ID,
                stateDao = database.backupStateDao(),
                journalDao = database.backupJournalDao(),
                mutationDao = mutationDao,
                mutationCodec = BackupMutationCodec,
                operationId = { UUID.randomUUID().toString() },
                sourceDeviceId = deviceId.value,
                appendBoundary = backupJournalAppendBoundary,
            ).appendChanges(
                before = before,
                after = mutationDao.snapshots(),
            )
        }
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
        database.purgeTask(
            taskId = entity.id,
            tombstone = tombstone,
            revisionWallMillis = purgedAt.toEpochMilli(),
            revisionDeviceId = deviceId.value,
        )
    }

    private sealed interface TaskUpdateValidation {
        data class Invalid(val rejection: CommandResult.Rejected) : TaskUpdateValidation

        data class Valid(
            val task: Task,
            val existingReminder: Reminder?,
            val requestedReminder: Reminder?,
            val requestedProject: ProjectEntity?,
            val requestedMilestone: MilestoneEntity?,
            val recurrenceMetadata: RecurrenceSeriesMetadata?,
            val targetStatus: WorkflowStatus,
        ) : TaskUpdateValidation
    }

    private fun invalidTaskUpdate(
        reason: RejectionReason,
        message: String,
    ): TaskUpdateValidation.Invalid =
        TaskUpdateValidation.Invalid(CommandResult.Rejected(reason, message))

    /**
     * Resolves and validates one [DomainCommand.UpdateTask] against current
     * state without writing. Shared by [updateTaskDetails] and the
     * [DomainCommand.UndoBatch] preflight so both paths reject identically.
     */
    private suspend fun validateTaskUpdate(
        command: DomainCommand.UpdateTask,
    ): TaskUpdateValidation {
        val task = currentTask(command.taskId)
            ?: return invalidTaskUpdate(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val existingReminder = database.workspaceDao()
            .getReminderForTask(task.id.value)
            ?.toModel()
        val requestedReminder = command.reminder?.copy(
            id = Reminder.primaryId(task.id),
            taskId = task.id,
        )
        val requestedProject = command.projectId?.let { projectId ->
            database.workspaceDao().getProjectById(projectId.value)
        }
        val requestedMilestone = command.milestoneId?.let { milestoneId ->
            database.workspaceDao().getMilestone(milestoneId.value)
        }
        val recurrence = command.recurrence
        val title = command.title.trim()
        when {
            title.isEmpty() -> return invalidTaskUpdate(
                RejectionReason.EMPTY_TITLE,
                "A task needs a title.",
            )
            title.length > MAX_TASK_TITLE_LENGTH -> return invalidTaskUpdate(
                RejectionReason.TITLE_TOO_LONG,
                "Keep the task title under $MAX_TASK_TITLE_LENGTH characters.",
            )
            command.description.length > MAX_TASK_DESCRIPTION_LENGTH ->
                return invalidTaskUpdate(
                    RejectionReason.DESCRIPTION_TOO_LONG,
                    "Keep the description under $MAX_TASK_DESCRIPTION_LENGTH characters.",
                )
            command.projectId != null && requestedProject == null ->
                return invalidTaskUpdate(
                    RejectionReason.NOT_FOUND,
                    "That project no longer exists.",
                )
            requestedProject?.archivedAtEpochMillis != null &&
                task.projectId?.value != requestedProject.id ->
                return invalidTaskUpdate(
                    RejectionReason.INVALID_STATE,
                    "Restore that project before assigning new tasks to it.",
                )
            command.milestoneId != null && requestedMilestone == null ->
                return invalidTaskUpdate(
                    RejectionReason.NOT_FOUND,
                    "That milestone no longer exists.",
                )
            requestedMilestone != null &&
                requestedMilestone.projectId != command.projectId?.value ->
                return invalidTaskUpdate(
                    RejectionReason.INVALID_STATE,
                    "Choose a milestone from the task's project.",
                )
            command.estimate?.isNegative == true || command.estimate?.isZero == true ->
                return invalidTaskUpdate(
                    RejectionReason.INVALID_STATE,
                    "Estimate must be greater than zero.",
                )
        }
        validateTaskScheduleState(
            taskId = task.id,
            start = command.start,
            due = command.due,
            recurrence = recurrence,
            reminder = requestedReminder,
            now = now(),
            allowPastReminder = command.restorePastReminder ||
                requestedReminder == existingReminder,
        )?.toCommandRejection()?.let(TaskUpdateValidation::Invalid)?.let { return it }
        val recurrenceMetadata = if (recurrence == null) {
            null
        } else {
            command.recurrenceMetadata ?: RecurringTaskPlanner.metadataForUpdate(
                task = task,
                start = command.start,
                due = command.due,
                rule = recurrence,
            )
        }
        val restoreStatusId = command.restoreStatusId
        val targetStatus = when {
            restoreStatusId != null ->
                database.workspaceDao().getWorkflowStatus(restoreStatusId.value)
                    ?.takeIf { it.projectId == command.projectId?.value }
                    ?.toModel()
            task.projectId != command.projectId ->
                database.workspaceDao().getWorkflowStatuses(command.projectId?.value)
                    .firstOrNull {
                        it.semanticStatus == task.semanticStatus.name &&
                            it.archivedAtEpochMillis == null
                    }
                    ?.toModel()
            else -> database.workspaceDao().getWorkflowStatus(task.statusId.value)?.toModel()
        } ?: return invalidTaskUpdate(
            RejectionReason.INVALID_STATE,
            "The destination workflow has no matching ${task.semanticStatus.readableCategory()} status.",
        )
        return TaskUpdateValidation.Valid(
            task = task,
            existingReminder = existingReminder,
            requestedReminder = requestedReminder,
            requestedProject = requestedProject,
            requestedMilestone = requestedMilestone,
            recurrenceMetadata = recurrenceMetadata,
            targetStatus = targetStatus,
        )
    }

    private suspend fun updateTaskDetails(command: DomainCommand.UpdateTask): CommandResult {
        val plan = when (val validation = validateTaskUpdate(command)) {
            is TaskUpdateValidation.Invalid -> return validation.rejection
            is TaskUpdateValidation.Valid -> validation
        }
        val task = plan.task
        val existingReminder = plan.existingReminder
        val requestedReminder = plan.requestedReminder
        val requestedProject = plan.requestedProject
        val requestedMilestone = plan.requestedMilestone
        val recurrenceMetadata = plan.recurrenceMetadata
        val targetStatus = plan.targetStatus
        val title = command.title.trim()
        if (
            task.title == title &&
            task.description == command.description &&
            task.projectId == command.projectId &&
            task.statusId == targetStatus.id &&
            task.priority == command.priority &&
            task.start == command.start &&
            task.due == command.due &&
            task.recurrence == command.recurrence &&
            task.recurrenceSeriesId == recurrenceMetadata?.seriesId &&
            task.recurrenceAnchor == recurrenceMetadata?.anchor &&
            task.recurrenceOccurrenceIndex == recurrenceMetadata?.occurrenceIndex &&
            task.estimate == command.estimate &&
            task.milestoneId == command.milestoneId &&
            existingReminder == requestedReminder
        ) {
            return CommandResult.Success("Changes saved")
        }
        val updated = task.copy(
            title = title,
            description = command.description,
            projectId = command.projectId,
            statusId = targetStatus.id,
            semanticStatus = targetStatus.semanticStatus,
            priority = command.priority,
            start = command.start,
            due = command.due,
            recurrence = command.recurrence,
            recurrenceSeriesId = recurrenceMetadata?.seriesId,
            recurrenceAnchor = recurrenceMetadata?.anchor,
            recurrenceOccurrenceIndex = recurrenceMetadata?.occurrenceIndex,
            estimate = command.estimate,
            milestoneId = command.milestoneId,
            revision = nextRevision(task),
        )
        if (existingReminder == requestedReminder) {
            persistTask(updated, "update")
        } else {
            database.withTransaction {
                database.taskDao().upsert(updated.toEntity())
                persistReminderChange(
                    previous = existingReminder,
                    requested = requestedReminder,
                )
            }
        }
        if (task.projectId != updated.projectId) {
            recordActivity(
                taskId = task.id,
                projectId = updated.projectId,
                kind = ActivityKind.PROJECT_MOVED,
                body = "${database.workspaceDao().getProjectById(task.projectId?.value.orEmpty())?.name ?: "Inbox"} → " +
                    "${requestedProject?.name ?: "Inbox"}",
                at = now(),
            )
        }
        if (task.milestoneId != updated.milestoneId) {
            recordActivity(
                taskId = task.id,
                projectId = updated.projectId,
                kind = ActivityKind.MILESTONE_CHANGED,
                body = "Milestone: ${requestedMilestone?.name ?: "None"}",
                at = now(),
            )
        }
        return CommandResult.Success(
            message = "Changes saved",
            undo = task.toUpdateCommand(existingReminder),
        )
    }

    private suspend fun setTaskSchedule(
        command: DomainCommand.SetTaskSchedule,
    ): CommandResult {
        val task = currentTask(command.taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val existingReminder = database.workspaceDao()
            .getReminderForTask(task.id.value)
            ?.toModel()
        rejectTaskSchedule(command, task, existingReminder)?.let { return it }
        if (
            task.start == command.start &&
            task.due == command.due &&
            existingReminder == command.reminder
        ) {
            return CommandResult.Success("Schedule updated")
        }
        val updated = task.copy(
            start = command.start,
            due = command.due,
            revision = nextRevision(task),
        )
        database.withTransaction {
            database.taskDao().upsert(updated.toEntity())
            persistReminderChange(existingReminder, command.reminder)
        }
        return CommandResult.Success(
            message = "Schedule updated",
            undo = DomainCommand.SetTaskSchedule(
                taskId = task.id,
                start = task.start,
                due = task.due,
                reminder = existingReminder,
                restorePastReminder = true,
            ),
        )
    }

    private fun rejectTaskSchedule(
        command: DomainCommand.SetTaskSchedule,
        task: Task,
        existingReminder: Reminder?,
    ): CommandResult.Rejected? {
        if (task.isCompleted || task.deletedAt != null) {
            return ScheduleMoveFailure.TASK_NOT_MOVABLE.toCommandRejection()
        }
        if (
            task.start == command.start &&
            task.due == command.due &&
            existingReminder == command.reminder
        ) {
            return null
        }
        return validateTaskScheduleState(
            taskId = task.id,
            start = command.start,
            due = command.due,
            recurrence = task.recurrence,
            reminder = command.reminder,
            now = now(),
            allowPastReminder = command.restorePastReminder,
        )?.toCommandRejection()
    }

    private suspend fun setTaskReminder(
        command: DomainCommand.SetTaskReminder,
    ): CommandResult {
        val task = currentTask(command.taskId)
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val existing = database.workspaceDao()
            .getReminderForTask(task.id.value)
            ?.toModel()
        val triggerAt = command.triggerAt
        if (triggerAt != null) {
            if (task.deletedAt != null || task.isCompleted) {
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Restore or reopen the task before setting a reminder.",
                )
            }
            if (!command.restorePastReminder && !triggerAt.instant.isAfter(now())) {
                return CommandResult.Rejected(
                    RejectionReason.REMINDER_IN_PAST,
                    "Choose a reminder time in the future.",
                )
            }
        }
        val requested = triggerAt?.let {
            Reminder(
                id = Reminder.primaryId(task.id),
                taskId = task.id,
                triggerAt = it,
                precise = command.precise,
            )
        }
        if (existing == requested) {
            return CommandResult.Success(
                if (requested == null) "Reminder is already off" else "Reminder is up to date",
            )
        }
        val updated = task.copy(revision = nextRevision(task))
        database.withTransaction {
            database.taskDao().upsert(updated.toEntity())
            persistReminderChange(
                previous = existing,
                requested = requested,
            )
        }
        return CommandResult.Success(
            message = if (requested == null) "Reminder removed" else "Reminder scheduled",
            undo = DomainCommand.SetTaskReminder(
                taskId = task.id,
                triggerAt = existing?.triggerAt,
                precise = existing?.precise ?: false,
                restorePastReminder = true,
            ),
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
        }
        return CommandResult.Success(
            message = "Tag created and added",
            undo = DomainCommand.SetTaskTag(task.id, tag.id, present = false),
        )
    }

    private suspend fun setTaskDependency(
        command: DomainCommand.SetTaskDependency,
    ): CommandResult {
        val task = currentTask(command.taskId)
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Task no longer exists.",
            )
        val dependency = currentTask(command.dependsOnTaskId)
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "That dependency no longer exists.",
            )
        val alreadyPresent = dependency.id in task.dependencyIds
        if (alreadyPresent == command.present) {
            return CommandResult.Success(
                if (command.present) "Dependency already added" else "Dependency already removed",
            )
        }
        if (command.present) {
            if (task.deletedAt != null || dependency.deletedAt != null) {
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Restore both tasks before linking them.",
                )
            }
            if (task.dependencyIds.size >= MAX_TASK_DEPENDENCIES) {
                return CommandResult.Rejected(
                    RejectionReason.DEPENDENCY_LIMIT_REACHED,
                    "A task can have up to $MAX_TASK_DEPENDENCIES dependencies.",
                )
            }
            val existing = database.workspaceDao().getDependencies().map { entity ->
                TaskDependency(
                    taskId = TaskId(entity.taskId),
                    dependsOnTaskId = TaskId(entity.dependsOnTaskId),
                    revision = Revision(
                        deviceId = DeviceId(entity.revisionDeviceId),
                        wallTimeMillis = entity.revisionWallMillis,
                        logicalCounter = entity.revisionLogical,
                    ),
                )
            }
            if (
                DependencyRules.wouldCreateCycle(
                    existing = existing,
                    taskId = task.id,
                    dependsOn = dependency.id,
                )
            ) {
                return CommandResult.Rejected(
                    RejectionReason.DEPENDENCY_CYCLE,
                    "That link would create a dependency cycle.",
                )
            }
        }
        val revision = nextRevision(task)
        val updatedDependencyIds = if (command.present) {
            task.dependencyIds + dependency.id
        } else {
            task.dependencyIds - dependency.id
        }
        val dependencyEntities = database.taskDao()
            .getByIds(updatedDependencyIds.map(TaskId::value))
        val blockingDependencyIds = dependencyEntities
            .filter {
                it.semanticStatus != SemanticStatus.COMPLETED.name &&
                    it.deletedAtEpochMillis == null
            }
            .mapTo(hashSetOf()) { TaskId(it.id) }
        val updated = task.copy(
            dependencyIds = updatedDependencyIds,
            blockedBy = blockingDependencyIds,
            revision = revision,
        )
        database.withTransaction {
            database.taskDao().upsert(updated.toEntity())
            if (command.present) {
                database.workspaceDao().upsertDependency(
                    TaskDependencyEntity(
                        taskId = task.id.value,
                        dependsOnTaskId = dependency.id.value,
                        revisionWallMillis = revision.wallTimeMillis,
                        revisionLogical = revision.logicalCounter,
                        revisionDeviceId = revision.deviceId.value,
                    ),
                )
            } else {
                database.workspaceDao().deleteDependency(
                    taskId = task.id.value,
                    dependsOnTaskId = dependency.id.value,
                )
            }
        }
        recordActivity(
            taskId = task.id,
            projectId = task.projectId,
            kind = if (command.present) {
                ActivityKind.DEPENDENCY_ADDED
            } else {
                ActivityKind.DEPENDENCY_REMOVED
            },
            body = "Depends on: ${dependency.title}",
            at = now(),
        )
        return CommandResult.Success(
            message = if (command.present) "Dependency added" else "Dependency removed",
            undo = command.copy(present = !command.present),
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
        val workspaceDao = database.workspaceDao()
        val storedDependencyIds = workspaceDao.getDependencyIds(taskId.value)
            .mapTo(linkedSetOf(), ::TaskId)
        val dependencyEntities = database.taskDao()
            .getByIds(storedDependencyIds.map(TaskId::value))
        val dependencyIds = dependencyEntities
            .mapTo(linkedSetOf()) { TaskId(it.id) }
        val blockingDependencyIds = dependencyEntities
            .filter {
                it.semanticStatus != SemanticStatus.COMPLETED.name &&
                    it.deletedAtEpochMillis == null
            }
            .mapTo(hashSetOf()) { TaskId(it.id) }
        return database.taskDao().getById(taskId.value)?.toModel(
            tagIds = workspaceDao.getTaskTags(taskId.value)
                .mapTo(linkedSetOf()) { TagId(it.tagId) },
            checklist = workspaceDao.getChecklistItems(taskId.value)
                .map(ChecklistItemEntity::toModel),
            dependencyIds = dependencyIds,
            blockedBy = blockingDependencyIds,
        )
    }

    private suspend fun persistTask(task: Task, operationKind: String) {
        database.taskDao().upsert(task.toEntity())
    }

    private suspend fun recordActivity(
        taskId: TaskId?,
        projectId: ProjectId?,
        kind: ActivityKind,
        body: String,
        at: Instant,
        id: String = UUID.randomUUID().toString(),
    ): String {
        val workspaceDao = database.workspaceDao()
        workspaceDao.upsertActivityEntry(
            ActivityEntryEntity(
                id = id,
                taskId = taskId?.value,
                projectId = projectId?.value,
                kind = kind.name,
                bodyCiphertext = body.take(MAX_ACTIVITY_BODY_LENGTH).toByteArray(Charsets.UTF_8),
                createdAtEpochMillis = at.toEpochMilli(),
            ),
        )
        val excess = workspaceDao.activityEntryCountForOwner(taskId?.value, projectId?.value) -
            MAX_ACTIVITY_ENTRIES_PER_OWNER
        if (excess > 0) {
            workspaceDao.deleteOldestActivityEntries(taskId?.value, projectId?.value, excess)
        }
        return id
    }

    private suspend fun persistProject(
        project: Project,
        revision: Revision,
        operationKind: String,
    ) {
        database.workspaceDao().upsertProject(project.toEntity(revision))
    }

    private suspend fun persistWorkflowStatuses(
        statuses: List<WorkflowStatus>,
        previousEntities: List<WorkflowStatusEntity>,
    ) {
        require(statuses.size == previousEntities.size)
        val revisions = previousEntities.map(::nextWorkflowRevision)
        database.withTransaction {
            previousEntities.forEachIndexed { index, entity ->
                val revision = revisions[index]
                database.workspaceDao().upsertWorkflowStatus(
                    entity.copy(
                        rank = "__workflow_tmp__:${entity.id}:$index",
                        revisionWallMillis = revision.wallTimeMillis,
                        revisionLogical = revision.logicalCounter,
                        revisionDeviceId = revision.deviceId.value,
                    ),
                )
            }
            statuses.forEachIndexed { index, status ->
                val revision = revisions[index]
                database.workspaceDao().upsertWorkflowStatus(status.toEntity(revision))
            }
        }
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
            CommandResult.Success("Timer started")
        }

    private suspend fun stopTimer(): CommandResult = database.withTransaction {
        val active = database.timeEntryDao().getActive()
            ?: return@withTransaction CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "No timer is running.",
            )
        val stoppedAt = maxOf(now().toEpochMilli(), active.startedAtEpochMillis)
        database.timeEntryDao().stop(active.id, stoppedAt)
        CommandResult.Success("Timer stopped")
    }

    // The owner check and the stop share this transaction, so a timer started
    // on another task between them cannot be stopped by an automated caller.
    private suspend fun stopTimerIfOwned(
        command: DomainCommand.StopTimerIfOwned,
    ): CommandResult = database.withTransaction {
        val active = database.timeEntryDao().getActive()
            ?: return@withTransaction CommandResult.Success("No timer is running")
        if (active.taskId != command.taskId.value) {
            return@withTransaction CommandResult.Rejected(
                RejectionReason.TIMER_OWNERSHIP_CHANGED,
                "Another task owns the running timer.",
            )
        }
        val stoppedAt = maxOf(now().toEpochMilli(), active.startedAtEpochMillis)
        database.timeEntryDao().stop(active.id, stoppedAt)
        CommandResult.Success("Timer stopped")
    }

    private suspend fun addTimeEntry(
        command: DomainCommand.AddTimeEntry,
    ): CommandResult {
        val note = command.note.trim()
        validateTimeEntry(
            taskId = command.taskId,
            startedAt = command.startedAt,
            stoppedAt = command.stoppedAt,
            note = note,
        )?.let { return it }
        if (database.timeEntryDao().getById(command.entryId.value) != null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That time-entry identifier is already in use.",
            )
        }
        if (database.timeEntryDao().countForTask(command.taskId.value) >= MAX_TIME_ENTRIES_PER_TASK) {
            return CommandResult.Rejected(
                RejectionReason.TIME_ENTRY_LIMIT_REACHED,
                "A task can contain up to $MAX_TIME_ENTRIES_PER_TASK time entries.",
            )
        }
        val entry = TimeEntryEntity(
            id = command.entryId.value,
            taskId = command.taskId.value,
            deviceId = deviceId.value,
            startedAtEpochMillis = command.startedAt.toEpochMilli(),
            stoppedAtEpochMillis = command.stoppedAt.toEpochMilli(),
            noteCiphertext = note.toByteArray(Charsets.UTF_8),
        )
        val overlaps = wouldOverlap(entry, excluding = null, at = command.changedAt)
        database.withTransaction {
            database.timeEntryDao().insert(entry)
        }
        return CommandResult.Success(
            message = if (overlaps) {
                "Time entry added; review the overlap"
            } else {
                "Time entry added"
            },
            undo = DomainCommand.DeleteTimeEntry(entryId = command.entryId),
        )
    }

    private suspend fun updateTimeEntry(
        command: DomainCommand.UpdateTimeEntry,
    ): CommandResult {
        val existing = database.timeEntryDao().getById(command.entryId.value)
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Time entry no longer exists.",
            )
        if (existing.stoppedAtEpochMillis == null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Stop the timer before editing its time entry.",
            )
        }
        val taskId = TaskId(existing.taskId)
        val note = command.note.trim()
        validateTimeEntry(
            taskId = taskId,
            startedAt = command.startedAt,
            stoppedAt = command.stoppedAt,
            note = note,
        )?.let { return it }
        val updated = existing.copy(
            startedAtEpochMillis = command.startedAt.toEpochMilli(),
            stoppedAtEpochMillis = command.stoppedAt.toEpochMilli(),
            noteCiphertext = note.toByteArray(Charsets.UTF_8),
        )
        if (
            updated.startedAtEpochMillis == existing.startedAtEpochMillis &&
            updated.stoppedAtEpochMillis == existing.stoppedAtEpochMillis &&
            updated.noteCiphertext.contentEquals(existing.noteCiphertext)
        ) {
            return CommandResult.Success("Time entry is up to date")
        }
        val overlaps = wouldOverlap(updated, excluding = existing.id, at = command.changedAt)
        database.withTransaction {
            database.timeEntryDao().update(updated)
        }
        return CommandResult.Success(
            message = if (overlaps) {
                "Time entry saved; review the overlap"
            } else {
                "Time entry saved"
            },
            undo = DomainCommand.RestoreTimeEntry(existing.toModel()),
        )
    }

    private suspend fun deleteTimeEntry(
        command: DomainCommand.DeleteTimeEntry,
    ): CommandResult {
        val existing = database.timeEntryDao().getById(command.entryId.value)
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Time entry no longer exists.",
            )
        if (existing.stoppedAtEpochMillis == null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Stop the timer before deleting its time entry.",
            )
        }
        database.withTransaction {
            database.timeEntryDao().delete(existing.id)
        }
        return CommandResult.Success(
            message = "Time entry deleted",
            undo = DomainCommand.RestoreTimeEntry(existing.toModel()),
        )
    }

    private suspend fun restoreTimeEntry(
        command: DomainCommand.RestoreTimeEntry,
    ): CommandResult {
        val existing = database.timeEntryDao().getById(command.entry.id.value)
        if (existing != null && existing.taskId != command.entry.taskId.value) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That time-entry identifier belongs to another task.",
            )
        }
        val stoppedAt = command.entry.stoppedAt
            ?: return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Running timers cannot be restored as manual entries.",
            )
        val note = command.entry.note.trim()
        validateTimeEntry(
            taskId = command.entry.taskId,
            startedAt = command.entry.startedAt,
            stoppedAt = stoppedAt,
            note = note,
        )?.let { return it }
        val restored = command.entry.copy(note = note).toEntity()
        database.withTransaction {
            if (existing == null) {
                database.timeEntryDao().insert(restored)
            } else {
                database.timeEntryDao().update(restored)
            }
        }
        return CommandResult.Success(
            message = "Time entry restored",
            undo = existing
                ?.toModel()
                ?.let { DomainCommand.RestoreTimeEntry(it) }
                ?: DomainCommand.DeleteTimeEntry(command.entry.id),
        )
    }

    private suspend fun addNote(command: DomainCommand.AddNote): CommandResult {
        if ((command.taskId == null) == (command.projectId == null)) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "A note belongs to exactly one task or project.",
            )
        }
        command.taskId?.let { taskId ->
            val task = database.taskDao().getById(taskId.value)
            if (task == null || task.deletedAtEpochMillis != null) {
                return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
            }
        }
        command.projectId?.let { projectId ->
            database.workspaceDao().getProjectById(projectId.value)
                ?: return CommandResult.Rejected(
                    RejectionReason.NOT_FOUND,
                    "Project no longer exists.",
                )
        }
        val body = command.body.trim()
        validateNoteBody(body)?.let { return it }
        val owned = database.workspaceDao().countNotesForOwner(
            taskId = command.taskId?.value,
            projectId = command.projectId?.value,
        )
        if (owned >= MAX_NOTES_PER_OWNER) {
            return CommandResult.Rejected(
                RejectionReason.NOTE_LIMIT_REACHED,
                "A task or project can hold up to $MAX_NOTES_PER_OWNER notes.",
            )
        }
        val note = Note(
            id = NoteId.new(),
            taskId = command.taskId,
            projectId = command.projectId,
            body = body,
            createdAt = command.createdAt,
            editedAt = null,
            revision = Revision(deviceId, command.createdAt.toEpochMilli(), 0),
        )
        database.withTransaction {
            database.workspaceDao().upsertNote(note.toEntity())
        }
        return CommandResult.Success("Note added", undo = DomainCommand.DeleteNote(note.id))
    }

    private suspend fun updateNote(command: DomainCommand.UpdateNote): CommandResult {
        val original = database.workspaceDao().getNoteById(command.noteId.value)?.toModel()
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Note no longer exists.")
        val body = command.body.trim()
        validateNoteBody(body)?.let { return it }
        val updated = original.copy(
            body = body,
            editedAt = command.editedAt,
            revision = Revision(
                deviceId = original.revision.deviceId,
                wallTimeMillis = maxOf(
                    original.revision.wallTimeMillis + 1,
                    command.editedAt.toEpochMilli(),
                ),
                logicalCounter = original.revision.logicalCounter + 1,
            ),
        )
        database.withTransaction {
            database.workspaceDao().upsertNote(updated.toEntity())
        }
        return CommandResult.Success(
            message = "Note saved",
            undo = DomainCommand.RestoreNote(original),
        )
    }

    private suspend fun deleteNote(command: DomainCommand.DeleteNote): CommandResult {
        val note = database.workspaceDao().getNoteById(command.noteId.value)?.toModel()
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Note no longer exists.")
        database.withTransaction {
            database.workspaceDao().deleteNote(note.id.value)
        }
        return CommandResult.Success(
            message = "Note deleted",
            undo = DomainCommand.RestoreNote(note),
        )
    }

    /**
     * Puts one note's content back, whether or not the identity still exists.
     *
     * Undoing an edit restores over a note that is still present, so an
     * existing identity cannot mean the work is done — that would claim a
     * restore and change nothing. Owners are only re-checked when the record
     * has to be re-created: writing the prior body of a note that is already
     * stored introduces no reference the row does not already hold. Replay is
     * safe because writing identical content produces no journal diff.
     */
    private suspend fun restoreNote(command: DomainCommand.RestoreNote): CommandResult {
        val existing = database.workspaceDao().getNoteById(command.note.id.value)?.toModel()
        if (existing == null) {
            command.note.taskId?.let { taskId ->
                val task = database.taskDao().getById(taskId.value)
                if (task == null || task.deletedAtEpochMillis != null) {
                    return CommandResult.Rejected(
                        RejectionReason.NOT_FOUND,
                        "Task no longer exists.",
                    )
                }
            }
            command.note.projectId?.let { projectId ->
                database.workspaceDao().getProjectById(projectId.value)
                    ?: return CommandResult.Rejected(
                        RejectionReason.NOT_FOUND,
                        "Project no longer exists.",
                    )
            }
        }
        database.withTransaction {
            database.workspaceDao().upsertNote(command.note.toEntity())
        }
        return CommandResult.Success(
            message = "Note restored",
            undo = existing?.let(DomainCommand::RestoreNote)
                ?: DomainCommand.DeleteNote(command.note.id),
        )
    }

    private suspend fun registerAttachment(command: DomainCommand.RegisterAttachment): CommandResult {
        val attachment = command.attachment
        val task = database.taskDao().getById(attachment.taskId.value)
        if (task == null || task.deletedAtEpochMillis != null) {
            return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        }
        val displayName = attachment.displayName.trim()
        validateAttachment(displayName, attachment)?.let { return it }
        val registered = attachment.copy(displayName = displayName)
        val existing = database.workspaceDao().getAttachmentById(attachment.id.value)
        if (existing?.toModel()?.hasSameRegistrationAs(registered) == true) {
            return CommandResult.Success("Attachment added")
        }
        if (
            (existing == null ||
                existing.taskId != attachment.taskId.value ||
                existing.deletedAtEpochMillis != null) &&
            database.workspaceDao().activeAttachmentCountForTask(attachment.taskId.value) >=
            MAX_ATTACHMENTS_PER_TASK
        ) {
            return CommandResult.Rejected(
                RejectionReason.ATTACHMENT_LIMIT_REACHED,
                "A task can contain up to $MAX_ATTACHMENTS_PER_TASK attachments.",
            )
        }
        database.workspaceDao().upsertAttachment(registered.toEntity())
        recordActivity(
            taskId = registered.taskId,
            projectId = task.projectId?.let(::ProjectId),
            kind = ActivityKind.ATTACHMENT_ADDED,
            body = "Added attachment: ${registered.displayName}",
            at = now(),
        )
        return CommandResult.Success("Attachment added")
    }

    private suspend fun deleteAttachment(command: DomainCommand.DeleteAttachment): CommandResult {
        val attachment = database.workspaceDao().getAttachmentById(command.attachmentId.value)?.toModel()
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Attachment no longer exists.")
        val deleted = attachment.copy(
            deletedAt = command.deletedAt,
            revision = attachment.revision.copy(
                wallTimeMillis = maxOf(
                    attachment.revision.wallTimeMillis + 1,
                    command.deletedAt.toEpochMilli(),
                ),
                logicalCounter = attachment.revision.logicalCounter + 1,
            ),
        )
        database.workspaceDao().upsertAttachment(deleted.toEntity())
        recordActivity(
            taskId = attachment.taskId,
            projectId = database.taskDao().getById(attachment.taskId.value)?.projectId?.let(::ProjectId),
            kind = ActivityKind.ATTACHMENT_REMOVED,
            body = "Removed attachment: ${attachment.displayName}",
            at = command.deletedAt,
        )
        return CommandResult.Success(
            message = "Attachment removed",
            undo = DomainCommand.RestoreAttachment(attachment),
        )
    }

    private suspend fun restoreAttachment(command: DomainCommand.RestoreAttachment): CommandResult {
        if (database.workspaceDao().getAttachmentById(command.attachment.id.value) == null) {
            return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Attachment no longer exists.")
        }
        database.workspaceDao().upsertAttachment(command.attachment.copy(deletedAt = null).toEntity())
        return CommandResult.Success("Attachment restored")
    }

    private suspend fun markAttachmentContentCollected(
        command: DomainCommand.MarkAttachmentContentCollected,
    ): CommandResult {
        val attachment = database.workspaceDao()
            .getAttachmentById(command.attachmentId.value)
            ?.toModel()
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Attachment no longer exists.",
            )
        // Already recorded: writing again would append a journal entry that
        // changes nothing and advance the backup generation for it.
        if (attachment.blobSetId == null) {
            return CommandResult.Success("Attachment content was already collected")
        }
        database.workspaceDao().upsertAttachment(
            attachment.copy(
                blobSetId = null,
                revision = attachment.revision.copy(
                    wallTimeMillis = maxOf(
                        attachment.revision.wallTimeMillis + 1,
                        command.collectedAt.toEpochMilli(),
                    ),
                    logicalCounter = attachment.revision.logicalCounter + 1,
                ),
            ).toEntity(),
        )
        return CommandResult.Success("Attachment content collected")
    }

    private suspend fun markRetiredBlobSetCollected(
        command: DomainCommand.MarkRetiredBlobSetCollected,
    ): CommandResult {
        val deleted = database.workspaceDao().deleteRetiredBlobSet(command.blobSetId.value)
        return if (deleted == 0) {
            CommandResult.Success("Retired blob set was already collected")
        } else {
            CommandResult.Success("Retired blob set collected")
        }
    }

    private suspend fun createSavedView(
        command: DomainCommand.CreateSavedView,
    ): CommandResult {
        val name = command.name.trim()
        validateSavedViewName(name)?.let { return it }
        val query = command.query.copy(text = command.query.text.trim())
        validateSavedViewQueryText(query)?.let { return it }
        val encoded = encodeSavedViewQuery(query)
            ?: return savedViewPayloadTooLarge()
        if (database.workspaceDao().getSavedView(command.savedViewId.value) != null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That saved search identifier is already in use.",
            )
        }
        if (database.workspaceDao().savedViewCount() >= MAX_SAVED_VIEWS) {
            return savedViewLimitReached()
        }
        val view = SavedView(
            id = command.savedViewId,
            workspaceId = OpenTasksFixtures.workspaceId,
            name = name,
            query = query,
        )
        database.workspaceDao().upsertSavedView(view.toEntity(encoded))
        return CommandResult.Success(
            message = "Saved search created",
            undo = DomainCommand.DeleteSavedView(view.id),
        )
    }

    private suspend fun renameSavedView(
        command: DomainCommand.RenameSavedView,
    ): CommandResult {
        val (entity, existing) = requireReadableSavedView(command.savedViewId)
            ?: return savedViewNotFound()
        val name = command.name.trim()
        validateSavedViewName(name)?.let { return it }
        if (name != existing.name) {
            database.workspaceDao().upsertSavedView(entity.copy(name = name))
        }
        return CommandResult.Success(
            message = "Saved search renamed",
            undo = DomainCommand.RenameSavedView(existing.id, existing.name),
        )
    }

    private suspend fun updateSavedViewQuery(
        command: DomainCommand.UpdateSavedViewQuery,
    ): CommandResult {
        val (entity, existing) = requireReadableSavedView(command.savedViewId)
            ?: return savedViewNotFound()
        val query = command.query.copy(text = command.query.text.trim())
        validateSavedViewQueryText(query)?.let { return it }
        val encoded = encodeSavedViewQuery(query)
            ?: return savedViewPayloadTooLarge()
        database.workspaceDao().upsertSavedView(entity.copy(encryptedQuery = encoded))
        return CommandResult.Success(
            message = "Saved search updated",
            undo = DomainCommand.UpdateSavedViewQuery(existing.id, existing.query),
        )
    }

    private suspend fun deleteSavedView(
        command: DomainCommand.DeleteSavedView,
    ): CommandResult {
        val entity = database.workspaceDao().getSavedView(command.savedViewId.value)
            ?: return CommandResult.Success("Saved search is already deleted")
        val existing = runCatching { entity.toModel() }.getOrNull()
            ?: return savedViewNotFound()
        database.workspaceDao().deleteSavedView(existing.id.value)
        return CommandResult.Success(
            message = "Saved search deleted",
            undo = DomainCommand.RestoreSavedView(existing),
        )
    }

    private suspend fun restoreSavedView(
        command: DomainCommand.RestoreSavedView,
    ): CommandResult {
        if (database.workspaceDao().getSavedView(command.savedView.id.value) != null) {
            return CommandResult.Success("Saved search restored")
        }
        val name = command.savedView.name.trim()
        validateSavedViewName(name)?.let { return it }
        if (command.savedView.workspaceId != OpenTasksFixtures.workspaceId) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That saved search belongs to a different workspace.",
            )
        }
        val query = command.savedView.query.copy(
            text = command.savedView.query.text.trim(),
        )
        validateSavedViewQueryText(query)?.let { return it }
        val encoded = encodeSavedViewQuery(query)
            ?: return savedViewPayloadTooLarge()
        if (database.workspaceDao().savedViewCount() >= MAX_SAVED_VIEWS) {
            return savedViewLimitReached()
        }
        val restored = command.savedView.copy(name = name, query = query)
        database.workspaceDao().upsertSavedView(restored.toEntity(encoded))
        return CommandResult.Success(
            message = "Saved search restored",
            undo = DomainCommand.DeleteSavedView(restored.id),
        )
    }

    /**
     * Resolves a saved view the product can actually see. A physically
     * present row whose payload fails the strict codec stays invisible to
     * commands and is preserved untouched, so both engines expose the same
     * mutable set.
     */
    private suspend fun requireReadableSavedView(
        id: SavedViewId,
    ): Pair<SavedViewEntity, SavedView>? {
        val entity = database.workspaceDao().getSavedView(id.value) ?: return null
        val model = runCatching { entity.toModel() }.getOrNull() ?: return null
        return entity to model
    }

    private fun validateSavedViewName(name: String): CommandResult.Rejected? = when {
        name.isEmpty() -> CommandResult.Rejected(
            RejectionReason.SAVED_VIEW_NAME_INVALID,
            "A saved search needs a name.",
        )
        name.length > MAX_SAVED_VIEW_NAME_LENGTH -> CommandResult.Rejected(
            RejectionReason.SAVED_VIEW_NAME_INVALID,
            "Keep saved search names under $MAX_SAVED_VIEW_NAME_LENGTH characters.",
        )
        else -> null
    }

    private fun validateSavedViewQueryText(query: SearchQuery): CommandResult.Rejected? =
        if (query.text.length > MAX_SAVED_VIEW_QUERY_LENGTH) {
            CommandResult.Rejected(
                RejectionReason.SAVED_VIEW_QUERY_TOO_LONG,
                "Keep search text under $MAX_SAVED_VIEW_QUERY_LENGTH characters.",
            )
        } else {
            null
        }

    private fun encodeSavedViewQuery(query: SearchQuery): ByteArray? =
        runCatching { SavedViewPayloadCodec.encode(query) }.getOrNull()

    private fun savedViewNotFound(): CommandResult.Rejected = CommandResult.Rejected(
        RejectionReason.NOT_FOUND,
        "Saved search no longer exists.",
    )

    private fun savedViewLimitReached(): CommandResult.Rejected = CommandResult.Rejected(
        RejectionReason.SAVED_VIEW_LIMIT_REACHED,
        "A workspace can contain up to $MAX_SAVED_VIEWS saved searches.",
    )

    private fun savedViewPayloadTooLarge(): CommandResult.Rejected = CommandResult.Rejected(
        RejectionReason.SAVED_VIEW_PAYLOAD_TOO_LARGE,
        "This saved search is too large to store safely.",
    )

    private fun validateNoteBody(body: String): CommandResult.Rejected? = when {
        body.isEmpty() -> CommandResult.Rejected(
            RejectionReason.EMPTY_NOTE,
            "A note needs text.",
        )
        body.length > MAX_NOTE_BODY_LENGTH -> CommandResult.Rejected(
            RejectionReason.NOTE_TOO_LONG,
            "Keep notes under $MAX_NOTE_BODY_LENGTH characters.",
        )
        else -> null
    }

    private fun validateAttachment(
        displayName: String,
        attachment: Attachment,
    ): CommandResult.Rejected? = when {
        displayName.isEmpty() -> CommandResult.Rejected(
            RejectionReason.EMPTY_ATTACHMENT_NAME,
            "An attachment needs a name.",
        )
        displayName.length > MAX_ATTACHMENT_NAME_LENGTH -> CommandResult.Rejected(
            RejectionReason.ATTACHMENT_NAME_TOO_LONG,
            "Keep attachment names under $MAX_ATTACHMENT_NAME_LENGTH characters.",
        )
        attachment.mimeType.length > MAX_ATTACHMENT_MIME_LENGTH ||
            attachment.byteCount !in 1..MAX_ATTACHMENT_BYTES ||
            attachment.chunkCount !in 1..MAX_ATTACHMENT_CHUNK_COUNT ||
            attachment.chunkCount.toLong() !=
            (attachment.byteCount + ATTACHMENT_CHUNK_BYTES - 1) / ATTACHMENT_CHUNK_BYTES ||
            !attachment.contentHash.matches(CONTENT_HASH_REGEX) -> CommandResult.Rejected(
            RejectionReason.INVALID_ATTACHMENT_METADATA,
            "Attachment metadata is invalid.",
        )
        else -> null
    }

    private suspend fun validateTimeEntry(
        taskId: TaskId,
        startedAt: Instant,
        stoppedAt: Instant,
        note: String,
    ): CommandResult.Rejected? {
        val task = database.taskDao().getById(taskId.value)
        return when {
            task == null || task.deletedAtEpochMillis != null ->
            CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Restore the task before logging time.",
            )
        !stoppedAt.isAfter(startedAt) -> CommandResult.Rejected(
            RejectionReason.INVALID_TIME_ENTRY_RANGE,
            "The end time must be after the start time.",
        )
        note.length > MAX_TIME_ENTRY_NOTE_LENGTH -> CommandResult.Rejected(
            RejectionReason.TIME_ENTRY_NOTE_TOO_LONG,
            "Keep time-entry notes under $MAX_TIME_ENTRY_NOTE_LENGTH characters.",
        )
        else -> null
        }
    }

    private suspend fun wouldOverlap(
        candidate: TimeEntryEntity,
        excluding: String?,
        at: Instant,
    ): Boolean {
        val entries = database.timeEntryDao()
            .getAll()
            .filterNot { it.id == excluding }
            .map(TimeEntryEntity::toModel) + candidate.toModel()
        return TimerRules.reconcile(entries, at).conflicts.any { conflict ->
            conflict.first.id.value == candidate.id || conflict.second.id.value == candidate.id
        }
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

    private fun workflowRevision(at: Instant = now()): Revision =
        Revision(deviceId, at.toEpochMilli(), 0)

    private fun nextWorkflowRevision(
        status: WorkflowStatusEntity,
        at: Instant = now(),
    ): Revision = Revision(
        deviceId = deviceId,
        wallTimeMillis = maxOf(status.revisionWallMillis + 1, at.toEpochMilli()),
        logicalCounter = status.revisionLogical + 1,
    )

    private fun milestoneRevision(at: Instant = now()): Revision =
        Revision(deviceId, at.toEpochMilli(), 0)

    private fun nextMilestoneRevision(
        milestone: MilestoneEntity,
        at: Instant = now(),
    ): Revision = Revision(
        deviceId = deviceId,
        wallTimeMillis = maxOf(milestone.revisionWallMillis + 1, at.toEpochMilli()),
        logicalCounter = milestone.revisionLogical + 1,
    )

    private fun nextTemplateRevision(
        template: TemplateEntity,
        at: Instant = now(),
    ): Revision = Revision(
        deviceId = deviceId,
        wallTimeMillis = maxOf(template.revisionWallMillis + 1, at.toEpochMilli()),
        logicalCounter = template.revisionLogical + 1,
    )

    private fun Template.toTemplateEntity(): TemplateEntity = TemplateEntity(
        id = id.value,
        workspaceId = workspaceId.value,
        name = name,
        encryptedPayload = TemplatePayloadCodec.encode(this),
        revisionWallMillis = revision.wallTimeMillis,
        revisionLogical = revision.logicalCounter,
        revisionDeviceId = revision.deviceId.value,
    )

    private suspend fun persistMilestone(
        milestone: Milestone,
        revision: Revision,
        deleted: Boolean,
    ) {
        database.withTransaction {
            if (deleted) {
                database.workspaceDao().deleteMilestone(milestone.id.value)
            } else {
                database.workspaceDao().upsertMilestone(milestone.toEntity(revision))
            }
        }
    }

    private suspend fun persistReminderChange(
        previous: Reminder?,
        requested: Reminder?,
    ) {
        if (requested == null) {
            previous?.let { reminder ->
                database.workspaceDao().deleteReminder(reminder.id)
            }
        } else {
            database.workspaceDao().upsertReminder(requested.toEntity())
        }
    }

    private fun Task.toUpdateCommand(
        reminder: Reminder? = null,
    ): DomainCommand.UpdateTask = DomainCommand.UpdateTask(
        taskId = id,
        title = title,
        description = description,
        projectId = projectId,
        priority = priority,
        start = start,
        due = due,
        recurrence = recurrence,
        estimate = estimate,
        milestoneId = milestoneId,
        restoreStatusId = statusId,
        reminder = reminder,
        restorePastReminder = true,
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

    private fun nextOccurrenceReminder(
        currentTask: Task,
        nextTask: Task,
        reminder: Reminder?,
    ): Reminder? {
        reminder ?: return null
        val currentDue = currentTask.due ?: return null
        val nextDue = nextTask.due ?: return null
        val leadTime = Duration.between(reminder.triggerAt.instant, currentDue.instant)
        return Reminder(
            id = Reminder.primaryId(nextTask.id),
            taskId = nextTask.id,
            triggerAt = ZonedMoment(
                instant = nextDue.instant.minus(leadTime),
                zoneId = nextDue.zoneId,
            ),
            precise = reminder.precise,
        )
    }

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

    private suspend fun validateWorkflowStatusName(
        name: String,
        projectId: ProjectId?,
        excluding: WorkflowStatusId? = null,
    ): CommandResult.Rejected? = when {
        name.isEmpty() -> CommandResult.Rejected(
            RejectionReason.EMPTY_WORKFLOW_STATUS_NAME,
            "A workflow status needs a name.",
        )
        name.length > MAX_WORKFLOW_STATUS_NAME_LENGTH -> CommandResult.Rejected(
            RejectionReason.WORKFLOW_STATUS_NAME_TOO_LONG,
            "Keep workflow status names under $MAX_WORKFLOW_STATUS_NAME_LENGTH characters.",
        )
        database.workspaceDao().getWorkflowStatuses(projectId?.value).any { status ->
            status.id != excluding?.value &&
                status.archivedAtEpochMillis == null &&
                status.name.equals(name, ignoreCase = true)
        } -> CommandResult.Rejected(
            RejectionReason.DUPLICATE_WORKFLOW_STATUS_NAME,
            "This workflow already has a status with that name.",
        )
        else -> null
    }

    private suspend fun validateMilestoneName(
        name: String,
        projectId: ProjectId,
        excluding: MilestoneId? = null,
    ): CommandResult.Rejected? = when {
        name.isEmpty() -> CommandResult.Rejected(
            RejectionReason.EMPTY_MILESTONE_NAME,
            "A milestone needs a name.",
        )
        name.length > MAX_MILESTONE_NAME_LENGTH -> CommandResult.Rejected(
            RejectionReason.MILESTONE_NAME_TOO_LONG,
            "Keep milestone names under $MAX_MILESTONE_NAME_LENGTH characters.",
        )
        database.workspaceDao().getMilestones(projectId.value).any { milestone ->
            milestone.id != excluding?.value &&
                milestone.name.equals(name, ignoreCase = true)
        } -> CommandResult.Rejected(
            RejectionReason.DUPLICATE_MILESTONE_NAME,
            "This project already has a milestone with that name.",
        )
        else -> null
    }
    private fun SemanticStatus.readableCategory(): String =
        name.lowercase(Locale.ROOT).replace('_', ' ')

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

    private suspend fun validateTemplateName(
        name: String,
        excluding: TemplateId? = null,
    ): CommandResult.Rejected? {
        if (name.isEmpty()) {
            return CommandResult.Rejected(
                RejectionReason.EMPTY_TEMPLATE_NAME,
                "A template needs a name.",
            )
        }
        if (name.length > MAX_TEMPLATE_NAME_LENGTH) {
            return CommandResult.Rejected(
                RejectionReason.TEMPLATE_NAME_TOO_LONG,
                "Keep template names under $MAX_TEMPLATE_NAME_LENGTH characters.",
            )
        }
        val existing = database.workspaceDao().findTemplateByName(
            OpenTasksFixtures.workspaceId.value,
            name,
        )
        return if (existing != null && existing.id != excluding?.value) {
            CommandResult.Rejected(
                RejectionReason.DUPLICATE_TEMPLATE_NAME,
                "This workspace already has a template with that name.",
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
            combine(
                base,
                workspaceDao.observeTemplates(OpenTasksFixtures.workspaceId.value),
            ) { rows, templates ->
                rows.copy(
                    templates = templates.mapNotNull { entity ->
                        runCatching { TemplatePayloadCodec.decode(entity) }.getOrNull()
                    },
                )
            },
            workspaceDao.observeWorkflowStatuses(),
        ) { rows, workflowStatuses ->
            rows.copy(workflowStatuses = workflowStatuses)
        }
        val relations = combine(
            combine(
                combine(
                    combine(
                        combine(
                            combine(
                                combine(
                                    workspaceDao.observeTaskTags(),
                                    workspaceDao.observeChecklistItems(),
                                    workspaceDao.observeReminders(),
                                    observeTimeEntriesWithClock(),
                                    workspaceDao.observeNotes(),
                                ) { taskTags, checklist, reminders, timeEntries, notes ->
                                    RelationRows(taskTags, checklist, reminders, timeEntries, notes)
                                },
                                workspaceDao.observeAttachments(),
                            ) { rows, attachments ->
                                rows.copy(attachments = attachments)
                            },
                            workspaceDao.observeActivityEntries(),
                        ) { rows, activityEntries ->
                            rows.copy(activityEntries = activityEntries)
                        },
                        workspaceDao.observeRetiredBlobSets(),
                    ) { rows, retiredBlobSets ->
                        rows.copy(retiredBlobSets = retiredBlobSets)
                    },
                    workspaceDao.observeSavedViews(),
                ) { rows, savedViews ->
                    rows.copy(savedViews = savedViews)
                },
                workspaceDao.observeAutomationRules(),
            ) { rows, automationRules ->
                rows.copy(automationRules = automationRules)
            },
            workspaceDao.observeMyDayEntries(),
        ) { rows, myDayEntries ->
            rows.copy(myDayEntries = myDayEntries)
        }
        return combine(baseWithWorkflow, relations, ::buildSnapshot)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeTimeEntriesWithClock(): Flow<TimedTimeEntries> =
        database.timeEntryDao().observeAll().flatMapLatest { entries ->
            if (entries.none { it.stoppedAtEpochMillis == null }) {
                flowOf(TimedTimeEntries(entries, now()))
            } else {
                flow {
                    while (currentCoroutineContext().isActive) {
                        emit(TimedTimeEntries(entries, now()))
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
        val taskIds = base.tasks.mapTo(hashSetOf()) { TaskId(it.id) }
        val blockingTaskIds = base.tasks
            .filter {
                it.semanticStatus != SemanticStatus.COMPLETED.name &&
                    it.deletedAtEpochMillis == null
            }
            .mapTo(hashSetOf()) { TaskId(it.id) }
        val tasks = base.tasks.map { entity ->
            val dependencyIds = dependencies[entity.id].orEmpty().filterTo(linkedSetOf()) {
                it in taskIds
            }
            entity.toModel(
                tagIds = tagIds[entity.id].orEmpty(),
                checklist = checklist[entity.id].orEmpty(),
                dependencyIds = dependencyIds,
                blockedBy = dependencyIds.filterTo(linkedSetOf()) { it in blockingTaskIds },
            )
        }
        val projects = base.projects.map(ProjectEntity::toModel)
        val projectNames = projects.associateBy(Project::id)
        val timeEntries = relations.timeEntries.entries.map(TimeEntryEntity::toModel)
        val activeEntry = timeEntries
            .filter { it.stoppedAt == null }
            .maxWithOrNull(compareBy(TimeEntry::startedAt).thenBy { it.id.value })
        val activeTimer = activeEntry?.let { entry ->
            val task = tasks.firstOrNull { it.id == entry.taskId }
            task?.let {
                ActiveTimerSnapshot(
                    entryId = entry.id,
                    taskId = it.id,
                    taskTitle = it.title,
                    projectName = it.projectId?.let(projectNames::get)?.name,
                    startedAt = entry.startedAt,
                    elapsed = Duration.between(
                        entry.startedAt,
                        relations.timeEntries.at,
                    ).coerceAtLeast(Duration.ZERO),
                )
            }
        }
        val currentTime = relations.timeEntries.at
        val reconciliation = TimerRules.reconcile(timeEntries, currentTime)
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
            reminders = relations.reminders.map(ReminderEntity::toModel),
            templates = base.templates,
            timeEntries = reconciliation.entries,
            timeEntryConflicts = reconciliation.conflicts.map { conflict ->
                TimeEntryConflict(
                    firstEntryId = conflict.first.id,
                    secondEntryId = conflict.second.id,
                    overlap = conflict.overlap,
                )
            },
            notes = relations.notes.map(NoteEntity::toModel),
            attachments = relations.attachments.map(AttachmentEntity::toModel),
            activityEntries = relations.activityEntries.mapNotNull(ActivityEntryEntity::toModel),
            retiredBlobSets = relations.retiredBlobSets.map(RetiredBlobSetEntity::toModel),
            // A malformed legacy or recovered dormant-family payload is
            // omitted without deleting, rewriting, or logging the raw row;
            // one bad row must not prevent repository readiness.
            savedViews = relations.savedViews.mapNotNull { entity ->
                runCatching { entity.toModel() }.getOrNull()
            },
            // A malformed persisted AutomationRuleType name (recovered
            // foreign data) must not break repository readiness either;
            // same fail-closed precedent as savedViews above.
            automationRules = relations.automationRules.mapNotNull { entity ->
                runCatching { entity.toModel() }.getOrNull()
            },
            myDay = relations.myDayEntries.map(MyDayEntryEntity::toModel),
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
                    schemaVersion = VAULT_DATABASE_VERSION,
                    cryptoVersion = 1,
                    minimumReaderVersion = 1,
                ),
            )
            if (database.backupStateDao().get(VAULT_ID.value) == null) {
                database.backupStateDao().insert(defaultBackupState(VAULT_ID.value))
            }
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
            workspaceDao.insertWorkflowStatuses(
                seedSnapshot.workflowStatuses.map { it.toEntity(seedRevision) },
            )
            workspaceDao.insertMilestones(
                seedSnapshot.milestones.map { it.toEntity(seedRevision) },
            )
            workspaceDao.insertTags(seedSnapshot.tags.map { it.toEntity() })
            workspaceDao.insertTasks(seedSnapshot.tasks.map(Task::toEntity))
            workspaceDao.insertDependencies(seedSnapshot.tasks.flatMap(Task::dependencyEntities))
            workspaceDao.insertTaskTags(seedSnapshot.tasks.flatMap(Task::tagEntities))
            workspaceDao.insertChecklistItems(seedSnapshot.tasks.flatMap(Task::checklistEntities))
            seedSnapshot.reminders.forEach { reminder ->
                workspaceDao.upsertReminder(reminder.toEntity())
            }
            seedSnapshot.notes.forEach { note ->
                workspaceDao.upsertNote(note.toEntity())
            }
            seedSnapshot.timeEntries.forEach { entry ->
                workspaceDao.insertTimeEntry(entry.toEntity())
            }
            seedSnapshot.attachments.forEach { attachment ->
                workspaceDao.upsertAttachment(attachment.toEntity())
            }
            seedSnapshot.activityEntries.forEach { entry ->
                workspaceDao.upsertActivityEntry(entry.toEntity())
            }
            seedSnapshot.home.activeTimer
                ?.takeUnless { timer ->
                    seedSnapshot.timeEntries.any { it.id == timer.entryId }
                }
                ?.let { timer ->
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

    private fun emptySnapshot(): WorkspaceSnapshot = WorkspaceSnapshot(
        home = HomeSnapshot(
            today = LocalDate.ofInstant(now(), zoneId()),
            focusTasks = emptyList(),
            upcomingTasks = emptyList(),
            projects = emptyList(),
            activeTimer = null,
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
        val templates: List<Template> = emptyList(),
    )

    private data class RelationRows(
        val taskTags: List<TaskTagEntity>,
        val checklist: List<ChecklistItemEntity>,
        val reminders: List<ReminderEntity>,
        val timeEntries: TimedTimeEntries,
        val notes: List<NoteEntity> = emptyList(),
        val attachments: List<AttachmentEntity> = emptyList(),
        val activityEntries: List<ActivityEntryEntity> = emptyList(),
        val retiredBlobSets: List<RetiredBlobSetEntity> = emptyList(),
        val savedViews: List<SavedViewEntity> = emptyList(),
        val automationRules: List<AutomationRuleEntity> = emptyList(),
        val myDayEntries: List<MyDayEntryEntity> = emptyList(),
    )

    private data class TimedTimeEntries(
        val entries: List<TimeEntryEntity>,
        val at: Instant,
    )

    internal companion object {
        const val OWNER_ID = "member-owner"
        const val HOME_TASK_LIMIT = 3
        const val MAX_TASK_TITLE_LENGTH = 240
        const val MAX_TASK_DESCRIPTION_LENGTH = 20_000
        const val MAX_PROJECT_NAME_LENGTH = 120
        const val MAX_PROJECT_SUMMARY_LENGTH = 1_000
        const val MAX_WORKFLOW_STATUS_NAME_LENGTH = 64
        const val MAX_WORKFLOW_STATUSES = 20
        const val MAX_MILESTONE_NAME_LENGTH = 120
        const val MAX_MILESTONES = 100
        const val MAX_TEMPLATE_NAME_LENGTH = 120
        const val MAX_TEMPLATES = 100
        const val MAX_CHECKLIST_ITEM_LENGTH = 500
        const val MAX_CHECKLIST_ITEMS = 200
        const val MAX_TAG_NAME_LENGTH = 64
        const val MAX_TASK_TAGS = 50
        const val MAX_RECURRENCE_INTERVAL = 999
        const val MAX_TASK_DEPENDENCIES = 100
        const val MAX_BULK_TASKS = 200
        const val MAX_IMPORT_ROWS = 5_000
        const val MAX_TIME_ENTRY_NOTE_LENGTH = 500
        const val MAX_TIME_ENTRIES_PER_TASK = 10_000
        const val MAX_NOTE_BODY_LENGTH = 10_000
        const val MAX_NOTES_PER_OWNER = 500
        const val MAX_ATTACHMENT_NAME_LENGTH = 255
        const val MAX_ATTACHMENT_MIME_LENGTH = 255
        const val MAX_ATTACHMENTS_PER_TASK = 100
        const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024
        const val MAX_ATTACHMENT_CHUNK_COUNT = 25
        const val ATTACHMENT_CHUNK_BYTES = 4L * 1024 * 1024
        const val MAX_ACTIVITY_ENTRIES_PER_OWNER = 500
        const val MAX_ACTIVITY_BODY_LENGTH = 500
        const val MAX_SAVED_VIEWS = 20
        const val MAX_SAVED_VIEW_NAME_LENGTH = 64
        const val MAX_SAVED_VIEW_QUERY_LENGTH = 500
        val CONTENT_HASH_REGEX = Regex("[0-9a-f]{64}")
        const val TIMER_TICK_MILLIS = 1_000L
        const val SECONDS_PER_DAY = 86_400L
        val VAULT_ID = VaultId("vault-primary")
        const val TASK_OBJECT_TYPE = "task"
    }
}

internal fun Attachment.hasSameRegistrationAs(other: Attachment): Boolean =
    id == other.id &&
        taskId == other.taskId &&
        displayName == other.displayName &&
        mimeType == other.mimeType &&
        byteCount == other.byteCount &&
        contentHash == other.contentHash &&
        blobSetId == other.blobSetId &&
        chunkCount == other.chunkCount &&
        deletedAt == other.deletedAt
