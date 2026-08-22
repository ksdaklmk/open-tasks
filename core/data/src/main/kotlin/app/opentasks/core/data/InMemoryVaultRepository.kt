package app.opentasks.core.data

import app.opentasks.core.data.backup.InMemoryBackupJournal
import app.opentasks.core.data.backup.BackupSnapshotCodec
import app.opentasks.core.data.backup.BackupSnapshotPayloadV1
import app.opentasks.core.data.backup.toBackupRecords
import app.opentasks.core.data.backup.toBackupRecordV1
import app.opentasks.core.data.db.MemberEntity
import app.opentasks.core.data.db.VaultEntity
import app.opentasks.core.data.db.WorkspaceEntity
import app.opentasks.core.data.db.TaskTagEntity
import app.opentasks.core.data.db.TombstoneEntity
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DependencyRules
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.MAX_MY_DAY_ENTRIES
import app.opentasks.core.domain.MAX_MY_DAY_RANK_LENGTH
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.domain.StatusTransitionTrigger
import app.opentasks.core.domain.evaluateAutomationRules
import app.opentasks.core.domain.automationTransitionedTaskIds
import app.opentasks.core.domain.RecurrenceSeriesMetadata
import app.opentasks.core.domain.RecurringTaskPlanner
import app.opentasks.core.domain.ScheduleMoveFailure
import app.opentasks.core.domain.ProjectTemplatePlanner
import app.opentasks.core.domain.automationRuleConfigRejection
import app.opentasks.core.domain.automationRuleNotFound
import app.opentasks.core.domain.automationRuleWorkspaceRejection
import app.opentasks.core.domain.myDayRankBetween
import app.opentasks.core.domain.myDayRankForIndex
import app.opentasks.core.domain.parentFirstRestoreInverses
import app.opentasks.core.domain.planTaskDuplicate
import app.opentasks.core.domain.searchWorkspace
import app.opentasks.core.domain.SubtaskRules
import app.opentasks.core.domain.subtaskViolationMessage
import app.opentasks.core.domain.toCommandRejection
import app.opentasks.core.domain.TrashPolicy
import app.opentasks.core.domain.TimerRules
import app.opentasks.core.domain.validateTaskScheduleState
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.domain.WorkflowMoveDirection
import app.opentasks.core.domain.wipLimitRejection
import app.opentasks.core.data.export.TasksImportPlan
import app.opentasks.core.data.export.TasksImportPlanResult
import app.opentasks.core.data.export.buildTasksImportPlan
import app.opentasks.core.domain.ImportReceipt
import app.opentasks.core.domain.ImportedProjectReceipt
import app.opentasks.core.domain.ImportedTagReceipt
import app.opentasks.core.domain.ImportedTaskReceipt
import app.opentasks.core.model.ActiveTimerSnapshot
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.ActivityEntry
import app.opentasks.core.model.ActivityKind
import app.opentasks.core.model.AutomationRule
import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.Milestone
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.MyDayEntry
import app.opentasks.core.model.Note
import app.opentasks.core.model.NoteId
import app.opentasks.core.model.PRIMARY_WORKSPACE_ID
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.RetiredBlobSet
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
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.ZonedMoment
import app.opentasks.core.model.openTasksFixtureSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

class InMemoryVaultRepository internal constructor(
    initial: WorkspaceSnapshot = openTasksFixtureSnapshot,
    private val now: () -> Instant = Instant::now,
    private val backupJournal: InMemoryBackupJournal = InMemoryBackupJournal(),
    sourceDeviceId: DeviceId? = null,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) : VaultRepository {
    private val writeMutex = Mutex()
    private val sourceDeviceId = sourceDeviceId
        ?: initial.tasks.firstOrNull()?.revision?.deviceId
        ?: DeviceId("in-memory")
    private var tombstones = emptyList<TombstoneEntity>()
    private var taskTags = initial.tasks
        .flatMap { task ->
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
        .sortedWith(compareBy(TaskTagEntity::taskId, TaskTagEntity::tagId))
    private val mutableWorkspace = MutableStateFlow(
        initial
            .withResolvedDependencyState()
            .withReconciledTimeState(now(), includeLegacyActive = true),
    )

    override fun observeHome(): Flow<HomeSnapshot> =
        mutableWorkspace.map { it.home }.distinctUntilChanged()

    override fun observeWorkspace(): StateFlow<WorkspaceSnapshot> = mutableWorkspace

    override fun observeTask(id: TaskId): Flow<Task?> =
        mutableWorkspace
            .map { snapshot -> snapshot.tasks.firstOrNull { it.id == id } }
            .distinctUntilChanged()

    override suspend fun currentWorkspace(): WorkspaceSnapshot = mutableWorkspace.value

    override suspend fun execute(command: DomainCommand): CommandResult =
        writeMutex.withLock { executeLocked(command) }

    override suspend fun executeAuthorized(
        command: DomainCommand,
        isAuthorized: () -> Boolean,
    ): CommandResult? = writeMutex.withLock {
        if (isAuthorized()) executeLocked(command) else null
    }

    private suspend fun executeLocked(command: DomainCommand): CommandResult {
        val before = mutableWorkspace.value
        val beforeTombstones = tombstones
        val beforeTaskTags = taskTags
        return try {
            val dispatched = dispatch(command)
            // Evaluation sits between the dispatch and the reconciliation so
            // the tag relations and the journal diff both observe the rule
            // outputs — the trigger and its outputs journal as one generation.
            val result = if (dispatched is CommandResult.Success) {
                applyAutomationRules(command, dispatched)
            } else {
                dispatched
            }
            reconcileTaskTags(before, mutableWorkspace.value)
            if (
                mutableWorkspace.value != before ||
                tombstones != beforeTombstones ||
                taskTags != beforeTaskTags
            ) {
                backupJournal.appendChanges(
                    before = before.toBackupRecords(beforeTombstones, beforeTaskTags),
                    after = mutableWorkspace.value.toBackupRecords(tombstones, taskTags),
                    sourceDeviceId = sourceDeviceId,
                )
            }
            result
        } catch (failure: Throwable) {
            mutableWorkspace.value = before
            tombstones = beforeTombstones
            taskTags = beforeTaskTags
            throw failure
        }
    }

    /**
     * Applies the automation rules a status entry matched, inside the caller's
     * execute, and returns the trigger's result with one composed undo.
     *
     * Behavioural twin of `RoomVaultRepository.applyAutomationRules`: the same
     * trigger set — [automationTransitionedTaskIds], shared with the Room
     * engine — the same ascending rule-id order, the same silent skip of a
     * rejected output, and the same flattened undo. Outputs go through the
     * internal [dispatch] — the write mutex is not reentrant, and internal
     * dispatch does not re-evaluate — and a throw from an output is
     * deliberately not caught, so the enclosing execute rolls the whole
     * command back.
     */
    private fun applyAutomationRules(
        command: DomainCommand,
        result: CommandResult.Success,
    ): CommandResult.Success {
        val transitioned = automationTransitionedTaskIds(command, result)
        if (transitioned.isEmpty()) return result
        val rules = mutableWorkspace.value.automationRules.filter { it.enabled }
        // Coupled to the config validator, which forces `statusId == null`
        // exactly for MY_DAY_AUTO_REMOVE and STALE_BADGE: a future type with a
        // statusId must move this guard in lockstep.
        if (rules.none { it.statusId != null }) return result

        val outputUndos = mutableListOf<DomainCommand>()
        for (taskId in transitioned) {
            val current = mutableWorkspace.value
            val task = current.tasks.firstOrNull { it.id == taskId } ?: continue
            val trigger = StatusTransitionTrigger(
                task = task,
                reminder = current.reminders.firstOrNull { it.taskId == taskId },
                myDayMemberIds = current.myDay.mapTo(hashSetOf(), MyDayEntry::taskId),
                today = LocalDate.ofInstant(now(), zoneId()),
                zoneId = zoneId().id,
            )
            evaluateAutomationRules(rules, trigger).forEach { output ->
                val outcome = dispatch(output)
                if (outcome is CommandResult.Success) {
                    outcome.undo?.let(outputUndos::add)
                }
            }
        }
        if (outputUndos.isEmpty()) return result
        // FLATTEN the trigger's undo: `rejectUndoCommand` preflights only a
        // fixed set of undo shapes and fails closed on anything else — a
        // nested UndoBatch would make the whole composed undo unreplayable.
        // CompleteTasks stores its inverses in reverse application order and
        // UndoBatch replays in list order, so splicing them after the output
        // undos preserves exactly the replay the repository intended.
        val triggerInverses: List<DomainCommand> = when (val undo = result.undo) {
            null -> emptyList()
            is DomainCommand.UndoBatch -> undo.commands
            else -> listOf(undo)
        }
        return result.copy(
            undo = DomainCommand.UndoBatch(outputUndos.asReversed() + triggerInverses),
        )
    }

    private fun reconcileTaskTags(
        before: WorkspaceSnapshot,
        after: WorkspaceSnapshot,
    ) {
        val beforeTasks = before.tasks.associateBy { it.id.value }
        val afterTasks = after.tasks.associateBy { it.id.value }
        val retained = taskTags
            .filter { it.taskId in afterTasks }
            .associateBy { it.taskId to it.tagId }
            .toMutableMap()

        afterTasks.values.forEach { task ->
            val beforeTagIds = beforeTasks[task.id.value]
                ?.tagIds
                .orEmpty()
                .mapTo(linkedSetOf()) { it.value }
            val afterTagIds = task.tagIds.mapTo(linkedSetOf()) { it.value }
            (beforeTagIds + afterTagIds).forEach { tagId ->
                val key = task.id.value to tagId
                val shouldBePresent = tagId in afterTagIds
                val existing = retained[key]
                if (existing == null || existing.present != shouldBePresent) {
                    retained[key] = TaskTagEntity(
                        taskId = task.id.value,
                        tagId = tagId,
                        present = shouldBePresent,
                        revisionWallMillis = task.revision.wallTimeMillis,
                        revisionLogical = task.revision.logicalCounter,
                        revisionDeviceId = task.revision.deviceId.value,
                    )
                }
            }
        }
        taskTags = retained.values.sortedWith(compareBy(TaskTagEntity::taskId, TaskTagEntity::tagId))
    }

    private fun dispatch(command: DomainCommand): CommandResult =
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
            is DomainCommand.SetWorkflowStatusWipLimit -> setWorkflowStatusWipLimit(command)
            is DomainCommand.CreateMilestone -> createMilestone(command)
            is DomainCommand.UpdateMilestone -> updateMilestone(command)
            is DomainCommand.DeleteMilestone -> deleteMilestone(command)
            is DomainCommand.RestoreMilestone -> restoreMilestone(command)
            is DomainCommand.CaptureProjectTemplate -> captureProjectTemplate(command)
            is DomainCommand.InstantiateProjectTemplate -> instantiateProjectTemplate(command)
            is DomainCommand.DeleteTemplate -> deleteTemplate(command)
            is DomainCommand.RestoreTemplate -> restoreTemplate(command)
            is DomainCommand.CreateTask -> createTask(command)
            is DomainCommand.DuplicateTask -> duplicateTask(command)
            is DomainCommand.RenameTask -> renameTask(command)
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
            is DomainCommand.SetTaskParent -> setTaskParent(command)
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
            is DomainCommand.AddTaskToMyDay -> addTaskToMyDay(command)
            is DomainCommand.RemoveTaskFromMyDay -> removeTaskFromMyDay(command)
            is DomainCommand.MoveMyDayEntry -> moveMyDayEntry(command)
            is DomainCommand.SweepMyDay -> sweepMyDay(command)
            is DomainCommand.RestoreMyDayEntries -> restoreMyDayEntries(command)
            is DomainCommand.CreateAutomationRule -> createAutomationRule(command)
            is DomainCommand.UpdateAutomationRule -> updateAutomationRule(command)
            is DomainCommand.DeleteAutomationRule -> deleteAutomationRule(command)
        }

    override suspend fun search(query: SearchQuery): List<SearchResult> =
        searchWorkspace(mutableWorkspace.value, query, Clock.fixed(now(), zoneId()))

    private fun importTasks(command: DomainCommand.ImportTasks): CommandResult {
        val current = mutableWorkspace.value
        val at = now()
        val revision = Revision(sourceDeviceId, at.toEpochMilli(), 0)
        val plan = when (
            val result = buildTasksImportPlan(
                rows = command.rows,
                snapshot = current,
                workspaceId = PRIMARY_WORKSPACE_ID,
                revision = revision,
                at = at,
                freshId = { UUID.randomUUID().toString() },
            )
        ) {
            is TasksImportPlanResult.Invalid -> return result.rejection
            is TasksImportPlanResult.Ready -> result.plan
        }
        if (plan.hasIdentityCollision(current)) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "The import could not allocate unique record identifiers.",
            )
        }
        if (!preflightImportBackup(plan)) {
            return CommandResult.Rejected(
                RejectionReason.IMPORT_BACKUP_LIMIT_EXCEEDED,
                "The imported tasks would exceed backup limits.",
            )
        }
        val activityEntries = (
            plan.projects.map { it.activity } +
                plan.tasks.map { it.activity }
            ).fold(current.activityEntries) { entries, activity ->
                entries.appendedActivity(
                    id = activity.id,
                    taskId = activity.taskId,
                    projectId = activity.projectId,
                    kind = activity.kind,
                    body = activity.body,
                    at = activity.createdAt,
                )
            }
        publish(
            tasks = current.tasks + plan.tasks.map { it.task },
            projects = current.projects + plan.projects.map { it.project },
            workflowStatuses = current.workflowStatuses + plan.projects.flatMap { it.statuses },
            tags = current.tags + plan.tags,
            activityEntries = activityEntries,
            at = at,
        )
        val projectReceipts = plan.projects.map { planned ->
            ImportedProjectReceipt(planned.project, planned.statuses, planned.activity.id)
        }
        val taskReceipts = plan.tasks.map { planned ->
            ImportedTaskReceipt(
                taskId = planned.task.id,
                expectedRevision = planned.task.revision,
                expectedTagIds = planned.task.tagIds,
                activityEntryId = planned.activity.id,
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

    private fun preflightImportBackup(plan: TasksImportPlan): Boolean {
        val current = mutableWorkspace.value
        val projected = current.copy(
            tasks = current.tasks + plan.tasks.map { it.task },
            projects = current.projects + plan.projects.map { it.project },
            workflowStatuses = current.workflowStatuses + plan.projects.flatMap { it.statuses },
            tags = current.tags + plan.tags,
            activityEntries = current.activityEntries +
                plan.projects.map { it.activity } + plan.tasks.map { it.activity },
        )
        val plannedTaskTags = plan.tasks.flatMap { planned ->
            planned.task.tagIds.map { tagId ->
                TaskTagEntity(
                    taskId = planned.task.id.value,
                    tagId = tagId.value,
                    present = true,
                    revisionWallMillis = planned.task.revision.wallTimeMillis,
                    revisionLogical = planned.task.revision.logicalCounter,
                    revisionDeviceId = planned.task.revision.deviceId.value,
                )
            }
        }
        return isBackupRepresentable(projected, taskTags + plannedTaskTags)
    }

    private fun isBackupRepresentable(
        snapshot: WorkspaceSnapshot,
        retainedTaskTags: List<TaskTagEntity>,
    ): Boolean {
        val records = buildList {
            add(
                VaultEntity(
                    id = "vault-primary",
                    storageMode = "LOCAL",
                    createdAtEpochMillis = 0,
                    schemaVersion = app.opentasks.core.data.db.VAULT_DATABASE_VERSION,
                    cryptoVersion = 1,
                    minimumReaderVersion = 1,
                ).toBackupRecordV1(),
            )
            add(MemberEntity("member-owner", "You").toBackupRecordV1())
            add(
                WorkspaceEntity(
                    id = PRIMARY_WORKSPACE_ID.value,
                    vaultId = "vault-primary",
                    ownerId = "member-owner",
                    name = "Open Tasks",
                ).toBackupRecordV1(),
            )
            addAll(snapshot.toBackupRecords(tombstones, retainedTaskTags))
        }
        var plaintext: ByteArray? = null
        return try {
            plaintext = BackupSnapshotCodec.encode(
                BackupSnapshotPayloadV1(
                    vaultId = "vault-primary",
                    coveredGeneration = backupJournal.currentGeneration,
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

    private fun TasksImportPlan.hasIdentityCollision(current: WorkspaceSnapshot): Boolean {
        val projectIds = planIds(projects.map { it.project.id.value })
        val statusIds = planIds(projects.flatMap { project -> project.statuses.map { it.id.value } })
        val taskIds = planIds(tasks.map { it.task.id.value })
        val tagIds = planIds(tags.map { it.id.value })
        val activityIds = planIds(
            projects.map { it.activity.id } + tasks.map { it.activity.id },
        )
        return projectIds == null || projectIds.any { id -> current.projects.any { it.id.value == id } } ||
            statusIds == null || statusIds.any { id -> current.workflowStatuses.any { it.id.value == id } } ||
            taskIds == null || taskIds.any { id -> current.tasks.any { it.id.value == id } } ||
            tagIds == null || tagIds.any { id -> current.tags.any { it.id.value == id } } ||
            activityIds == null || activityIds.any { id -> current.activityEntries.any { it.id == id } }
    }

    private fun planIds(ids: List<String>): Set<String>? = ids.toSet().takeIf { it.size == ids.size }

    private fun removeImportedRecords(
        command: DomainCommand.RemoveImportedRecords,
    ): CommandResult {
        val receipt = command.receipt
        val current = mutableWorkspace.value
        if (!canRemoveImportedRecords(receipt, current)) {
            return CommandResult.Rejected(
                RejectionReason.IMPORT_UNDO_CONFLICT,
                "Imported records changed and could not be removed.",
            )
        }
        val taskIds = receipt.tasks.mapTo(hashSetOf()) { it.taskId }
        val projectIds = receipt.projects.mapTo(hashSetOf()) { it.project.id }
        val statusIds = receipt.projects.flatMapTo(hashSetOf()) { project ->
            project.statuses.map { it.id }
        }
        val tagIds = receipt.tags.mapTo(hashSetOf()) { it.tag.id }
        val activityIds = receipt.tasks.mapTo(hashSetOf()) { it.activityEntryId }.also { ids ->
            receipt.projects.mapTo(ids) { it.activityEntryId }
        }
        val taskIdValues = taskIds.mapTo(hashSetOf(), TaskId::value)
        val retainedTaskTags = taskTags.filterNot { it.taskId in taskIdValues }
        val projected = current.copy(
            tasks = current.tasks.filterNot { it.id in taskIds },
            projects = current.projects.filterNot { it.id in projectIds },
            workflowStatuses = current.workflowStatuses.filterNot { it.id in statusIds },
            tags = current.tags.filterNot { it.id in tagIds },
            activityEntries = current.activityEntries.filterNot { it.id in activityIds },
        )
        if (!isBackupRepresentable(projected, retainedTaskTags)) {
            return CommandResult.Rejected(
                RejectionReason.IMPORT_UNDO_CONFLICT,
                "The post-Undo state cannot be backed up.",
            )
        }
        taskTags = retainedTaskTags
        publish(
            tasks = projected.tasks,
            projects = projected.projects,
            workflowStatuses = projected.workflowStatuses,
            tags = projected.tags,
            activityEntries = projected.activityEntries,
        )
        val count = receipt.tasks.size
        return CommandResult.Success("Import removed ($count ${if (count == 1) "task" else "tasks"})")
    }

    private fun canRemoveImportedRecords(
        receipt: ImportReceipt,
        current: WorkspaceSnapshot,
    ): Boolean {
        val taskIds = receipt.tasks.mapTo(hashSetOf()) { it.taskId }
        val projectIds = receipt.projects.mapTo(hashSetOf()) { it.project.id }
        val statusIds = receipt.projects.flatMapTo(hashSetOf()) { it.statuses.map(WorkflowStatus::id) }
        val tagIds = receipt.tags.mapTo(hashSetOf()) { it.tag.id }
        if (receipt.tasks.any { expected ->
            val task = current.tasks.firstOrNull { it.id == expected.taskId } ?: return false
            task.revision != expected.expectedRevision || task.tagIds != expected.expectedTagIds ||
                task.checklist.isNotEmpty() || task.dependencyIds.isNotEmpty() ||
                current.tasks.any { it.parentTaskId == task.id || task.id in it.dependencyIds } ||
                current.reminders.any { it.taskId == task.id } ||
                current.attachments.any { it.taskId == task.id } ||
                current.notes.any { it.taskId == task.id } ||
                current.timeEntries.any { it.taskId == task.id } ||
                current.activityEntries.filter { it.taskId == task.id }.map { it.id } !=
                listOf(expected.activityEntryId)
        }) return false
        if (receipt.projects.any { expected ->
            current.projects.firstOrNull { it.id == expected.project.id } != expected.project ||
                current.workflowStatuses.filter { it.projectId == expected.project.id } != expected.statuses ||
                current.activityEntries.filter {
                    it.taskId == null && it.projectId == expected.project.id
                }.map { it.id } != listOf(expected.activityEntryId)
        }) return false
        if (receipt.tags.any { expected ->
            current.tags.firstOrNull { it.id == expected.tag.id } != expected.tag
        }) return false
        if (current.tasks.any { task ->
            task.id !in taskIds && (
                task.projectId in projectIds || task.statusId in statusIds ||
                    task.tagIds.any(tagIds::contains)
                )
        }) return false
        val taskIdValues = taskIds.mapTo(hashSetOf(), TaskId::value)
        val tagIdValues = tagIds.mapTo(hashSetOf(), TagId::value)
        if (taskTags.any { it.taskId !in taskIdValues && it.tagId in tagIdValues }) return false
        if (current.milestones.any { it.projectId in projectIds }) return false
        if (current.notes.any { it.projectId in projectIds }) return false
        if (current.savedViews.any { view ->
            view.query.projectIds.any(projectIds::contains) || view.query.tagIds.any(tagIds::contains)
        }) return false
        return true
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
            workspaceId = PRIMARY_WORKSPACE_ID,
            name = name,
            summary = summary,
            status = command.health,
            dueDate = command.dueDate,
            completedTasks = 0,
            totalTasks = 0,
        )
        val statuses = WorkflowStatus.defaults(project.id)
        publishProjects(
            projects = current.projects + project,
            workflowStatuses = current.workflowStatuses + statuses,
        )
        recordActivity(
            taskId = null,
            projectId = project.id,
            kind = ActivityKind.RECORD_CREATED,
            body = "Created",
            at = now(),
        )
        return CommandResult.Success("Project created")
    }

    private fun captureProjectTemplate(
        command: DomainCommand.CaptureProjectTemplate,
    ): CommandResult {
        val current = mutableWorkspace.value
        val project = current.projects.firstOrNull {
            it.id == command.projectId && it.archivedAt == null
        } ?: return CommandResult.Rejected(
            RejectionReason.NOT_FOUND,
            "Restore that project before saving it as a template.",
        )
        val name = command.name.trim()
        validateTemplateName(name)?.let { return it }
        if (current.templates.any { it.id == command.templateId }) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That template identifier is already in use.",
            )
        }
        if (current.templates.size >= MAX_TEMPLATES) {
            return CommandResult.Rejected(
                RejectionReason.TEMPLATE_LIMIT_REACHED,
                "A workspace can contain up to $MAX_TEMPLATES templates.",
            )
        }
        val projectTasks = current.tasks.filter {
            it.projectId == project.id && it.deletedAt == null && !it.isCompleted
        }
        if (projectTasks.size > ProjectTemplatePlanner.MAX_TEMPLATE_TASKS) {
            return CommandResult.Rejected(
                RejectionReason.TEMPLATE_TASK_LIMIT_REACHED,
                "A template can contain up to ${ProjectTemplatePlanner.MAX_TEMPLATE_TASKS} tasks.",
            )
        }
        val revision = Revision(
            deviceId = current.tasks.firstOrNull()?.revision?.deviceId ?: DeviceId("in-memory"),
            wallTimeMillis = now().toEpochMilli(),
            logicalCounter = 0,
        )
        val template = try {
            ProjectTemplatePlanner.capture(
                templateId = command.templateId,
                templateName = name,
                project = project,
                workflowStatuses = current.workflowStatuses,
                milestones = current.milestones,
                tasks = current.tasks,
                tags = current.tags,
                revision = revision,
                fallbackAnchor = LocalDate.ofInstant(now(), ZoneId.systemDefault()),
            )
        } catch (_: IllegalArgumentException) {
            return CommandResult.Rejected(
                RejectionReason.TEMPLATE_DATE_RANGE_TOO_LARGE,
                "Template dates must fit within 100 years.",
            )
        }
        mutableWorkspace.value = current.copy(
            templates = (current.templates + template)
                .sortedBy { it.name.lowercase(Locale.ROOT) },
        )
        return CommandResult.Success(
            message = "Template saved",
            undo = DomainCommand.DeleteTemplate(template.id),
        )
    }

    private fun instantiateProjectTemplate(
        command: DomainCommand.InstantiateProjectTemplate,
    ): CommandResult {
        val current = mutableWorkspace.value
        val template = current.templates.firstOrNull { it.id == command.templateId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Template no longer exists.",
            )
        val projectName = command.projectName.trim()
        validateProject(projectName, template.projectSummary)?.let { return it }
        validateUniqueActiveProjectName(projectName)?.let { return it }
        if (current.projects.any { it.id == command.projectId }) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That project identifier is already in use.",
            )
        }
        val revision = Revision(
            deviceId = current.tasks.firstOrNull()?.revision?.deviceId ?: DeviceId("in-memory"),
            wallTimeMillis = now().toEpochMilli(),
            logicalCounter = 0,
        )
        val created = runCatching {
            TemplatePayloadCodec.validate(template)
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
                "This template is damaged and cannot be used.",
            )
        }
        val tagsByNormalisedName = current.tags
            .associateBy { it.name.lowercase(Locale.ROOT) }
            .toMutableMap()
        val createdTags = mutableListOf<Tag>()
        val tasks = created.tasks.map { task ->
            val tagIds = created.tagNamesByTaskId[task.id].orEmpty().mapTo(linkedSetOf()) { name ->
                tagsByNormalisedName.getOrPut(name.lowercase(Locale.ROOT)) {
                    Tag(
                        id = TagId(UUID.randomUUID().toString()),
                        workspaceId = template.workspaceId,
                        name = name,
                    ).also(createdTags::add)
                }.id
            }
            task.copy(tagIds = tagIds)
        }
        val projects = current.projects + created.project
        mutableWorkspace.value = current.copy(
            home = current.home.copy(
                projects = projects.filter { it.archivedAt == null },
            ),
            projects = projects,
            workflowStatuses = (current.workflowStatuses + created.workflowStatuses)
                .sortedWith(
                    compareBy<WorkflowStatus> { it.projectId?.value.orEmpty() }
                        .thenBy(WorkflowStatus::rank),
                ),
            milestones = (current.milestones + created.milestones).sortedWith(
                compareBy<Milestone> { it.completedAt != null }
                    .thenBy { it.dueDate == null }
                    .thenBy(Milestone::dueDate)
                    .thenBy(Milestone::name),
            ),
            tasks = current.tasks + tasks,
            tags = (current.tags + createdTags)
                .sortedBy { it.name.lowercase(Locale.ROOT) },
        ).withResolvedDependencyState(rebuildHomeTaskLists = true)
        return CommandResult.Success("Project created from template")
    }

    private fun deleteTemplate(command: DomainCommand.DeleteTemplate): CommandResult {
        val current = mutableWorkspace.value
        val template = current.templates.firstOrNull { it.id == command.templateId }
            ?: return CommandResult.Success("Template is already deleted")
        mutableWorkspace.value = current.copy(
            templates = current.templates.filterNot { it.id == template.id },
        )
        return CommandResult.Success(
            message = "Template deleted",
            undo = DomainCommand.RestoreTemplate(template),
        )
    }

    private fun restoreTemplate(command: DomainCommand.RestoreTemplate): CommandResult {
        val current = mutableWorkspace.value
        if (current.templates.any { it.id == command.template.id }) {
            return CommandResult.Success("Template restored")
        }
        val name = command.template.name.trim()
        validateTemplateName(name)?.let { return it }
        if (command.template.workspaceId != PRIMARY_WORKSPACE_ID) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That template belongs to a different workspace.",
            )
        }
        val candidate = command.template.copy(name = name)
        runCatching { TemplatePayloadCodec.validate(candidate) }.getOrElse {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "This template contains invalid or oversized content.",
            )
        }
        if (current.templates.size >= MAX_TEMPLATES) {
            return CommandResult.Rejected(
                RejectionReason.TEMPLATE_LIMIT_REACHED,
                "A workspace can contain up to $MAX_TEMPLATES templates.",
            )
        }
        val restored = candidate.copy(
            revision = candidate.revision.copy(
                wallTimeMillis = maxOf(
                    candidate.revision.wallTimeMillis + 1,
                    now().toEpochMilli(),
                ),
                logicalCounter = candidate.revision.logicalCounter + 1,
            ),
        )
        mutableWorkspace.value = current.copy(
            templates = (current.templates + restored)
                .sortedBy { it.name.lowercase(Locale.ROOT) },
        )
        return CommandResult.Success(
            message = "Template restored",
            undo = DomainCommand.DeleteTemplate(restored.id),
        )
    }

    private fun createWorkflowStatus(
        command: DomainCommand.CreateWorkflowStatus,
    ): CommandResult {
        val current = mutableWorkspace.value
        val project = current.projects.firstOrNull {
            it.id == command.projectId && it.archivedAt == null
        } ?: return CommandResult.Rejected(
            RejectionReason.NOT_FOUND,
            "Restore that project before editing its workflow.",
        )
        if (current.workflowStatuses.any { it.id == command.statusId }) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That workflow status identifier is already in use.",
            )
        }
        val name = command.name.trim()
        validateWorkflowStatusName(name, project.id)?.let { return it }
        val projectStatuses = current.workflowStatuses.filter { it.projectId == project.id }
        if (projectStatuses.count { it.archivedAt == null } >= MAX_WORKFLOW_STATUSES) {
            return CommandResult.Rejected(
                RejectionReason.WORKFLOW_STATUS_LIMIT_REACHED,
                "A project can have up to $MAX_WORKFLOW_STATUSES active workflow statuses.",
            )
        }
        val status = WorkflowStatus(
            id = command.statusId,
            projectId = project.id,
            name = name,
            semanticStatus = command.semanticStatus,
            rank = rankAfter(projectStatuses.maxByOrNull(WorkflowStatus::rank)?.rank),
        )
        publishWorkflowStatuses(current.workflowStatuses + status)
        return CommandResult.Success(
            message = "$name added",
            undo = DomainCommand.RemoveWorkflowStatus(status.id),
        )
    }

    private fun renameWorkflowStatus(
        command: DomainCommand.RenameWorkflowStatus,
    ): CommandResult {
        val current = mutableWorkspace.value
        val original = current.workflowStatuses.firstOrNull { it.id == command.statusId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Workflow status no longer exists.",
            )
        if (original.archivedAt != null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Restore that workflow status before renaming it.",
            )
        }
        val name = command.name.trim()
        validateWorkflowStatusName(name, original.projectId, original.id)?.let { return it }
        if (name == original.name) return CommandResult.Success("Workflow status is up to date")
        publishWorkflowStatuses(
            current.workflowStatuses.replace(original.copy(name = name)),
        )
        return CommandResult.Success(
            message = "Workflow status renamed",
            undo = DomainCommand.RestoreWorkflowStatuses(listOf(original)),
        )
    }

    private fun moveWorkflowStatus(
        command: DomainCommand.MoveWorkflowStatus,
    ): CommandResult {
        val current = mutableWorkspace.value
        val original = current.workflowStatuses.firstOrNull {
            it.id == command.statusId && it.archivedAt == null
        } ?: return CommandResult.Rejected(
            RejectionReason.NOT_FOUND,
            "Workflow status is no longer available.",
        )
        val active = current.workflowStatuses
            .filter { it.projectId == original.projectId && it.archivedAt == null }
            .sortedBy(WorkflowStatus::rank)
        val index = active.indexOfFirst { it.id == original.id }
        val otherIndex = when (command.direction) {
            WorkflowMoveDirection.EARLIER -> index - 1
            WorkflowMoveDirection.LATER -> index + 1
        }
        val other = active.getOrNull(otherIndex)
            ?: return CommandResult.Success("Workflow order is unchanged")
        val moved = original.copy(rank = other.rank)
        val displaced = other.copy(rank = original.rank)
        publishWorkflowStatuses(
            current.workflowStatuses
                .replace(moved)
                .replace(displaced),
        )
        return CommandResult.Success(
            message = "Workflow reordered",
            undo = DomainCommand.RestoreWorkflowStatuses(listOf(original, other)),
        )
    }

    private fun archiveWorkflowStatus(
        command: DomainCommand.ArchiveWorkflowStatus,
    ): CommandResult {
        val current = mutableWorkspace.value
        val original = current.workflowStatuses.firstOrNull { it.id == command.statusId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Workflow status no longer exists.",
            )
        if (original.archivedAt != null) {
            return CommandResult.Success("Workflow status is already archived")
        }
        val alternatives = current.workflowStatuses.count {
            it.projectId == original.projectId &&
                it.semanticStatus == original.semanticStatus &&
                it.archivedAt == null
        }
        if (alternatives <= 1) {
            return CommandResult.Rejected(
                RejectionReason.LAST_SEMANTIC_WORKFLOW_STATUS,
                "Add another ${original.semanticStatus.readableCategory()} status before archiving this one.",
            )
        }
        publishWorkflowStatuses(
            current.workflowStatuses.replace(
                original.copy(archivedAt = command.archivedAt),
            ),
        )
        return CommandResult.Success(
            message = "${original.name} archived",
            undo = DomainCommand.RestoreWorkflowStatuses(listOf(original)),
        )
    }

    private fun restoreWorkflowStatuses(
        command: DomainCommand.RestoreWorkflowStatuses,
    ): CommandResult {
        val current = mutableWorkspace.value
        if (command.statuses.any { restored ->
                current.workflowStatuses.none { it.id == restored.id }
            }
        ) {
            return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "A previous workflow status no longer exists.",
            )
        }
        command.statuses
            .filter { it.archivedAt == null }
            .forEach { restored ->
                validateWorkflowStatusName(
                    restored.name,
                    restored.projectId,
                    restored.id,
                )?.let { return it }
            }
        var statuses = current.workflowStatuses
        command.statuses.forEach { statuses = statuses.replace(it) }
        publishWorkflowStatuses(statuses)
        return CommandResult.Success("Workflow change undone")
    }

    private fun restoreArchivedWorkflowStatus(
        command: DomainCommand.RestoreArchivedWorkflowStatus,
    ): CommandResult {
        val current = mutableWorkspace.value
        val original = current.workflowStatuses.firstOrNull { it.id == command.statusId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Workflow status no longer exists.",
            )
        val archivedAt =
            original.archivedAt ?: return CommandResult.Success("Workflow status is already active")
        validateWorkflowStatusName(original.name, original.projectId, original.id)?.let {
            return it
        }
        publishWorkflowStatuses(
            current.workflowStatuses.replace(original.copy(archivedAt = null)),
        )
        return CommandResult.Success(
            message = "${original.name} restored",
            undo = DomainCommand.ArchiveWorkflowStatus(original.id, archivedAt),
        )
    }

    private fun removeWorkflowStatus(
        command: DomainCommand.RemoveWorkflowStatus,
    ): CommandResult {
        val current = mutableWorkspace.value
        val status = current.workflowStatuses.firstOrNull { it.id == command.statusId }
            ?: return CommandResult.Success("Workflow status is already removed")
        if (current.tasks.any { it.statusId == status.id }) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Move tasks out of this status before removing it.",
            )
        }
        if (
            status.archivedAt == null &&
            current.workflowStatuses.count {
                it.projectId == status.projectId &&
                    it.semanticStatus == status.semanticStatus &&
                    it.archivedAt == null
            } <= 1
        ) {
            return CommandResult.Rejected(
                RejectionReason.LAST_SEMANTIC_WORKFLOW_STATUS,
                "Keep at least one active ${status.semanticStatus.readableCategory()} status.",
            )
        }
        publishWorkflowStatuses(current.workflowStatuses.filterNot { it.id == status.id })
        return CommandResult.Success("Workflow status removed")
    }

    private fun setWorkflowStatusWipLimit(
        command: DomainCommand.SetWorkflowStatusWipLimit,
    ): CommandResult {
        val current = mutableWorkspace.value
        val original = current.workflowStatuses.firstOrNull { it.id == command.statusId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Workflow status no longer exists.",
            )
        if (original.archivedAt != null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Restore that workflow status before changing its limit.",
            )
        }
        if (original.semanticStatus == SemanticStatus.COMPLETED) {
            return CommandResult.Rejected(
                RejectionReason.WIP_LIMIT_INVALID,
                "Completed columns do not take a limit.",
            )
        }
        wipLimitRejection(command.wipLimit)?.let { return it }
        if (command.wipLimit == original.wipLimit) {
            return CommandResult.Success("Limit is unchanged")
        }
        publishWorkflowStatuses(
            current.workflowStatuses.replace(original.copy(wipLimit = command.wipLimit)),
        )
        return CommandResult.Success(
            message = if (command.wipLimit == null) "Limit cleared" else "Limit set",
            undo = DomainCommand.RestoreWorkflowStatuses(listOf(original)),
        )
    }

    private fun createMilestone(command: DomainCommand.CreateMilestone): CommandResult {
        val current = mutableWorkspace.value
        val project = current.projects.firstOrNull {
            it.id == command.projectId && it.archivedAt == null
        } ?: return CommandResult.Rejected(
            RejectionReason.NOT_FOUND,
            "Restore that project before adding milestones.",
        )
        if (current.milestones.any { it.id == command.milestoneId }) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That milestone identifier is already in use.",
            )
        }
        val name = command.name.trim()
        validateMilestoneName(name, project.id)?.let { return it }
        if (current.milestones.count { it.projectId == project.id } >= MAX_MILESTONES) {
            return CommandResult.Rejected(
                RejectionReason.MILESTONE_LIMIT_REACHED,
                "A project can have up to $MAX_MILESTONES milestones.",
            )
        }
        val milestone = Milestone(
            id = command.milestoneId,
            projectId = project.id,
            name = name,
            dueDate = command.dueDate,
        )
        publish(
            tasks = current.tasks,
            milestones = current.milestones + milestone,
        )
        return CommandResult.Success(
            message = "$name added",
            undo = DomainCommand.DeleteMilestone(milestone.id),
        )
    }

    private fun updateMilestone(command: DomainCommand.UpdateMilestone): CommandResult {
        val current = mutableWorkspace.value
        val original = current.milestones.firstOrNull { it.id == command.milestoneId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Milestone no longer exists.",
            )
        val name = command.name.trim()
        validateMilestoneName(name, original.projectId, original.id)?.let { return it }
        val updated = original.copy(
            name = name,
            dueDate = command.dueDate,
            completedAt = command.completedAt,
        )
        if (updated == original) return CommandResult.Success("Milestone is up to date")
        publish(
            tasks = current.tasks,
            milestones = current.milestones.replace(updated),
        )
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

    private fun deleteMilestone(command: DomainCommand.DeleteMilestone): CommandResult {
        val current = mutableWorkspace.value
        val milestone = current.milestones.firstOrNull { it.id == command.milestoneId }
            ?: return CommandResult.Success("Milestone is already deleted")
        val assignedTaskIds = current.tasks
            .filter { it.milestoneId == milestone.id }
            .mapTo(linkedSetOf(), Task::id)
        val changedAt = now()
        val tasks = current.tasks.map { task ->
            if (task.id in assignedTaskIds) {
                task.copy(
                    milestoneId = null,
                    revision = nextRevision(task, changedAt),
                )
            } else {
                task
            }
        }
        publish(
            tasks = tasks,
            milestones = current.milestones.filterNot { it.id == milestone.id },
        )
        return CommandResult.Success(
            message = "Milestone deleted",
            undo = DomainCommand.RestoreMilestone(milestone, assignedTaskIds),
        )
    }

    private fun restoreMilestone(command: DomainCommand.RestoreMilestone): CommandResult {
        val current = mutableWorkspace.value
        val project = current.projects.firstOrNull { it.id == command.milestone.projectId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "The milestone project no longer exists.",
            )
        validateMilestoneName(
            command.milestone.name,
            project.id,
            command.milestone.id,
        )?.let { return it }
        val milestones = if (current.milestones.any { it.id == command.milestone.id }) {
            current.milestones.replace(command.milestone)
        } else {
            current.milestones + command.milestone
        }
        val assignedTaskIds = command.assignedTaskIds
        val changedAt = now()
        val tasks = if (assignedTaskIds == null) {
            current.tasks
        } else {
            current.tasks.map { task ->
                if (task.id in assignedTaskIds && task.projectId == project.id) {
                    task.copy(
                        milestoneId = command.milestone.id,
                        revision = nextRevision(task, changedAt),
                    )
                } else {
                    task
                }
            }
        }
        publish(tasks = tasks, milestones = milestones)
        return CommandResult.Success("Milestone restored")
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

    private fun markReviewed(command: DomainCommand.MarkReviewed): CommandResult {
        val current = mutableWorkspace.value
        if ((command.taskId == null) == (command.projectId == null)) {
            return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Review target no longer exists.")
        }
        if (command.taskId != null) {
            val task = current.tasks.firstOrNull { it.id == command.taskId && it.deletedAt == null }
                ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
            recordActivity(task.id, task.projectId, ActivityKind.REVIEWED, "Reviewed", command.reviewedAt)
        } else {
            val project = current.projects.firstOrNull {
                it.id == command.projectId && it.archivedAt == null
            } ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Project no longer exists.")
            recordActivity(null, project.id, ActivityKind.REVIEWED, "Reviewed", command.reviewedAt)
        }
        return CommandResult.Success("Marked as reviewed")
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
        val current = mutableWorkspace.value
        var effectiveProjectId = command.projectId
        val parentTaskId = command.parentTaskId
        if (parentTaskId != null) {
            val parentTask = current.tasks.firstOrNull { it.id == parentTaskId }
                ?: return CommandResult.Rejected(
                    RejectionReason.NOT_FOUND,
                    "That parent task no longer exists.",
                )
            val violation = SubtaskRules.parentViolation(
                tasks = current.tasks,
                // The new task has no id in the snapshot yet, so only
                // PARENT_MISSING_OR_BINNED and PARENT_IS_A_SUBTASK can fire.
                taskId = TaskId(""),
                parentTaskId = parentTaskId,
            )
            if (violation != null) {
                return CommandResult.Rejected(
                    RejectionReason.SUBTASK_PARENT_INVALID,
                    subtaskViolationMessage(violation),
                )
            }
            effectiveProjectId = parentTask.projectId
        }
        if (
            effectiveProjectId != null &&
            current.projects.none {
                it.id == effectiveProjectId && it.archivedAt == null
            }
        ) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Restore that project before assigning new tasks to it.",
            )
        }
        val initialStatus = current.workflowStatuses.firstOrNull {
            it.projectId == effectiveProjectId &&
                it.semanticStatus == SemanticStatus.BACKLOG &&
                it.archivedAt == null
        } ?: return CommandResult.Rejected(
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
        val tagsByName = current.tags.associateBy { tag -> tag.name.lowercase(Locale.ROOT) }
        val existingTags = uniqueTagNames.keys.mapNotNull(tagsByName::get)
        val missingTagNames = uniqueTagNames
            .filterKeys { key -> key !in tagsByName }
            .values
            .toList()

        // No TaskId.new/UUID.randomUUID call is permitted above this line.
        val createdAt = now()
        val taskId = TaskId.new()
        val freshTags = missingTagNames.map { name ->
            Tag(TagId(UUID.randomUUID().toString()), PRIMARY_WORKSPACE_ID, name)
        }
        val base = Task(
            id = taskId,
            workspaceId = PRIMARY_WORKSPACE_ID,
            projectId = effectiveProjectId,
            parentTaskId = parentTaskId,
            statusId = initialStatus.id,
            semanticStatus = initialStatus.semanticStatus,
            title = title,
            priority = command.priority,
            due = command.due,
            estimate = command.estimate,
            recurrence = command.recurrence,
            tagIds = (existingTags.map(Tag::id) + freshTags.map(Tag::id)).toSet(),
            revision = Revision(DeviceId("local-device"), createdAt.toEpochMilli(), 0),
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
        publish(
            tasks = current.tasks + task,
            tags = current.tags + freshTags,
            activityEntries = current.activityEntries.appendedActivity(
                taskId = task.id,
                projectId = task.projectId,
                kind = ActivityKind.RECORD_CREATED,
                body = "Created",
                at = createdAt,
            ),
            at = createdAt,
        )
        return CommandResult.Success(
            message = "Task added",
            undo = DomainCommand.DeleteTask(task.id, createdAt),
        )
    }

    private fun duplicateTask(command: DomainCommand.DuplicateTask): CommandResult {
        val current = mutableWorkspace.value
        val source = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val statuses = current.workflowStatuses.filter { it.projectId == source.projectId }
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
            revision = Revision(DeviceId("local-device"), createdAt.toEpochMilli(), 0),
        )
        publish(
            tasks = current.tasks + duplicate,
            activityEntries = current.activityEntries.appendedActivity(
                taskId = duplicate.id,
                projectId = duplicate.projectId,
                kind = ActivityKind.RECORD_CREATED,
                body = "Created",
                at = createdAt,
            ),
            at = createdAt,
        )
        return CommandResult.Success(
            message = "Task duplicated",
            undo = DomainCommand.DeleteTask(duplicate.id, createdAt),
        )
    }

    private fun completeTask(command: DomainCommand.CompleteTask): CommandResult {
        val task = mutableWorkspace.value.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val completedStatus = mutableWorkspace.value.workflowStatuses
            .firstOrNull {
                it.projectId == task.projectId &&
                    it.semanticStatus == SemanticStatus.COMPLETED &&
                    it.archivedAt == null
            }
            ?: return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "This workspace has no active completion status.",
            )
        val result = changeTaskStatus(
            DomainCommand.ChangeTaskStatus(
                taskId = command.taskId,
                statusId = completedStatus.id,
                acknowledgeBlocked = command.acknowledgeBlocked,
                acknowledgeOpenSubtasks = command.acknowledgeOpenSubtasks,
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

    private fun completeTasks(command: DomainCommand.CompleteTasks): CommandResult {
        val ids = command.taskIds.distinct()
        rejectBulkSelection(ids)?.let { return it }
        val current = mutableWorkspace.value
        val tasksById = current.tasks.associateBy(Task::id)
        val resolved = ids.mapNotNull(tasksById::get)
        if (resolved.isEmpty()) return bulkTasksNotFound()
        val idSet = ids.toHashSet()

        // Full preflight before the first write: the single-task validators,
        // in their order, over the whole resolved set.
        class CompletionPlan(val task: Task, val status: WorkflowStatus)
        val plans = mutableListOf<CompletionPlan>()
        for (task in resolved) {
            val completedStatus = current.workflowStatuses.firstOrNull {
                it.projectId == task.projectId &&
                    it.semanticStatus == SemanticStatus.COMPLETED &&
                    it.archivedAt == null
            } ?: return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "This workspace has no active completion status.",
            )
            if (task.isBlocked && !command.acknowledgeBlocked) {
                return CommandResult.Rejected(
                    RejectionReason.BLOCKED_TASK_WARNING_REQUIRED,
                    "This task still has unfinished dependencies.",
                )
            }
            if (
                !command.acknowledgeOpenSubtasks &&
                current.tasks.any {
                    it.parentTaskId == task.id && it.deletedAt == null &&
                        it.id !in idSet && it.semanticStatus != SemanticStatus.COMPLETED
                }
            ) {
                return CommandResult.Rejected(
                    RejectionReason.OPEN_SUBTASKS_CONFIRM_REQUIRED,
                    "This task still has open subtasks.",
                )
            }
            if (task.statusId == completedStatus.id) continue
            plans += CompletionPlan(task, completedStatus)
        }

        val existingIds = current.tasks.mapTo(hashSetOf(), Task::id)
        val updatedById = linkedMapOf<TaskId, Task>()
        val generatedTasks = mutableListOf<Task>()
        val generatedReminders = mutableListOf<Reminder>()
        var activityEntries = current.activityEntries
        val inverses = mutableListOf<DomainCommand>()
        for (plan in plans) {
            val task = plan.task
            val updated = task.copy(
                statusId = plan.status.id,
                semanticStatus = plan.status.semanticStatus,
                completedAt = command.completedAt,
                revision = nextRevision(task, command.completedAt),
            )
            val generated = if (task.semanticStatus != SemanticStatus.COMPLETED) {
                val nextStatus = current.workflowStatuses.firstOrNull {
                    it.projectId == task.projectId &&
                        it.archivedAt == null &&
                        it.semanticStatus == SemanticStatus.PLANNED
                } ?: current.workflowStatuses.firstOrNull {
                    it.projectId == task.projectId &&
                        it.archivedAt == null &&
                        it.semanticStatus == SemanticStatus.BACKLOG
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
                }?.takeUnless { next -> next.id in existingIds }
            } else {
                null
            }
            generated?.let { next ->
                existingIds += next.id
                generatedTasks += next
                nextOccurrenceReminder(task, next, current.reminders)
                    ?.let(generatedReminders::add)
            }
            updatedById[task.id] = updated
            activityEntries = activityEntries.appendedActivity(
                taskId = task.id,
                projectId = task.projectId,
                kind = ActivityKind.COMPLETED,
                body = "Completed",
                at = command.completedAt,
            )
            inverses += DomainCommand.RestoreTaskStatus(
                taskId = task.id,
                statusId = task.statusId,
                completedAt = task.completedAt,
                generatedOccurrenceId = generated?.id,
            )
        }
        if (plans.isNotEmpty()) {
            publish(
                tasks = current.tasks.map { updatedById[it.id] ?: it } + generatedTasks,
                reminders = current.reminders + generatedReminders,
                activityEntries = activityEntries,
            )
        }
        return CommandResult.Success(
            message = "${bulkTasksLabel(plans.size)} completed",
            undo = DomainCommand.UndoBatch(inverses.asReversed())
                .takeIf { inverses.isNotEmpty() },
        )
    }

    private fun rescheduleTasks(command: DomainCommand.RescheduleTasks): CommandResult {
        val ids = command.taskIds.distinct()
        rejectBulkSelection(ids)?.let { return it }
        val current = mutableWorkspace.value
        val tasksById = current.tasks.associateBy(Task::id)
        val resolved = ids.mapNotNull(tasksById::get)
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
        val updatedById = linkedMapOf<TaskId, Task>()
        val inverses = mutableListOf<DomainCommand>()
        changing.forEach { task ->
            updatedById[task.id] = task.copy(due = due, revision = nextRevision(task))
            inverses += task.toUpdateCommand(
                current.reminders.firstOrNull { it.taskId == task.id },
            )
        }
        if (changing.isNotEmpty()) {
            publish(tasks = current.tasks.map { updatedById[it.id] ?: it })
        }
        return CommandResult.Success(
            message = "${bulkTasksLabel(changing.size)} rescheduled",
            undo = DomainCommand.UndoBatch(inverses.asReversed())
                .takeIf { inverses.isNotEmpty() },
        )
    }

    private fun moveTasksToProject(command: DomainCommand.MoveTasksToProject): CommandResult {
        val listedIds = command.taskIds.distinct()
        rejectBulkSelection(listedIds)?.let { return it }
        val current = mutableWorkspace.value
        val tasksById = current.tasks.associateBy(Task::id)
        val listed = listedIds.mapNotNull(tasksById::get)
        // Listed parents pull their live children into the moved set; the
        // 200-ID bulk bound above applies only to the listed IDs themselves.
        val expandedChildren = listed.flatMap { parent ->
            current.tasks.filter { it.parentTaskId == parent.id && it.deletedAt == null }
        }
        val ids = (listedIds + expandedChildren.map(Task::id)).distinct()
        val resolved = ids.mapNotNull(tasksById::get)
        if (resolved.isEmpty()) return bulkTasksNotFound()
        val resolvedIds = resolved.mapTo(hashSetOf(), Task::id)

        val destination = command.projectId?.let { projectId ->
            current.projects.firstOrNull { it.id == projectId }
                ?: return CommandResult.Rejected(
                    RejectionReason.NOT_FOUND,
                    "That project no longer exists.",
                )
        }
        class MovePlan(val task: Task, val status: WorkflowStatus, val detach: Boolean)
        val plans = mutableListOf<MovePlan>()
        for (task in resolved) {
            if (task.projectId == command.projectId) continue
            if (destination?.archivedAt != null) {
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Restore that project before assigning new tasks to it.",
                )
            }
            val mapped = current.workflowStatuses.firstOrNull {
                it.projectId == command.projectId &&
                    it.semanticStatus == task.semanticStatus &&
                    it.archivedAt == null
            } ?: return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "The destination workflow has no matching ${task.semanticStatus.readableCategory()} status.",
            )
            // A moved task whose parent stayed behind in a different project
            // no longer satisfies the same-project subtask invariant.
            val parentId = task.parentTaskId
            val detach = parentId != null && parentId !in resolvedIds && run {
                val parent = tasksById[parentId]
                parent == null || parent.projectId != command.projectId
            }
            plans += MovePlan(task, mapped, detach)
        }

        val destinationName = destination?.name ?: "Inbox"
        val changedAt = now()
        val updatedById = linkedMapOf<TaskId, Task>()
        var activityEntries = current.activityEntries
        val inverses = mutableListOf<DomainCommand>()
        for (plan in plans) {
            val task = plan.task
            updatedById[task.id] = task.copy(
                projectId = command.projectId,
                statusId = plan.status.id,
                semanticStatus = plan.status.semanticStatus,
                milestoneId = null,
                parentTaskId = if (plan.detach) null else task.parentTaskId,
                revision = nextRevision(task),
            )
            activityEntries = activityEntries.appendedActivity(
                taskId = task.id,
                projectId = command.projectId,
                kind = ActivityKind.PROJECT_MOVED,
                body = "${current.projects.firstOrNull { it.id == task.projectId }?.name ?: "Inbox"} → " +
                    destinationName,
                at = changedAt,
            )
            if (task.milestoneId != null) {
                activityEntries = activityEntries.appendedActivity(
                    taskId = task.id,
                    projectId = command.projectId,
                    kind = ActivityKind.MILESTONE_CHANGED,
                    body = "Milestone: None",
                    at = changedAt,
                )
            }
            inverses += task.toUpdateCommand(
                current.reminders.firstOrNull { it.taskId == task.id },
            )
        }
        if (plans.isNotEmpty()) {
            publish(
                tasks = current.tasks.map { updatedById[it.id] ?: it },
                activityEntries = activityEntries,
            )
        }
        return CommandResult.Success(
            message = "${bulkTasksLabel(plans.size)} moved",
            undo = DomainCommand.UndoBatch(inverses.asReversed())
                .takeIf { inverses.isNotEmpty() },
        )
    }

    private fun setTasksTag(command: DomainCommand.SetTasksTag): CommandResult {
        val ids = command.taskIds.distinct()
        rejectBulkSelection(ids)?.let { return it }
        val current = mutableWorkspace.value
        val tasksById = current.tasks.associateBy(Task::id)
        val resolved = ids.mapNotNull(tasksById::get)
        if (resolved.isEmpty()) return bulkTasksNotFound()

        if (current.tags.none { it.id == command.tagId }) {
            return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Tag no longer exists.")
        }
        val changing = resolved.filter { (command.tagId in it.tagIds) != command.present }
        if (command.present && changing.any { it.tagIds.size >= MAX_TASK_TAGS }) {
            return CommandResult.Rejected(
                RejectionReason.TAG_LIMIT_REACHED,
                "A task can contain up to $MAX_TASK_TAGS tags.",
            )
        }

        val updatedById = linkedMapOf<TaskId, Task>()
        val inverses = mutableListOf<DomainCommand>()
        changing.forEach { task ->
            updatedById[task.id] = task.copy(
                tagIds = if (command.present) {
                    task.tagIds + command.tagId
                } else {
                    task.tagIds - command.tagId
                },
                revision = nextRevision(task),
            )
            inverses += DomainCommand.SetTaskTag(task.id, command.tagId, !command.present)
        }
        if (changing.isNotEmpty()) {
            publish(tasks = current.tasks.map { updatedById[it.id] ?: it })
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

    private fun deleteTasks(command: DomainCommand.DeleteTasks): CommandResult {
        val listedIds = command.taskIds.distinct()
        rejectBulkSelection(listedIds)?.let { return it }
        val current = mutableWorkspace.value
        val tasksById = current.tasks.associateBy(Task::id)
        val listed = listedIds.mapNotNull(tasksById::get)
        // Listed parents pull their live children into the binned set; the
        // 200-ID bulk bound above applies only to the listed IDs themselves.
        val expandedChildren = listed.flatMap { parent ->
            current.tasks.filter { it.parentTaskId == parent.id && it.deletedAt == null }
        }
        val ids = (listedIds + expandedChildren.map(Task::id)).distinct()
        val resolved = ids.mapNotNull(tasksById::get)
        if (resolved.isEmpty()) return bulkTasksNotFound()

        val deleting = resolved.filter { it.deletedAt == null }
        val deletingIds = deleting.mapTo(hashSetOf(), Task::id)
        val updatedById = linkedMapOf<TaskId, Task>()
        var activityEntries = current.activityEntries
        deleting.forEach { task ->
            updatedById[task.id] = task.copy(
                deletedAt = command.deletedAt,
                revision = nextRevision(task, command.deletedAt),
            )
            activityEntries = activityEntries.appendedActivity(
                taskId = task.id,
                projectId = task.projectId,
                kind = ActivityKind.BINNED,
                body = "Moved to Bin",
                at = command.deletedAt,
            )
        }
        if (deleting.isNotEmpty()) {
            publish(
                tasks = current.tasks.map { updatedById[it.id] ?: it },
                timeEntries = current.timeEntries.map { entry ->
                    if (entry.taskId in deletingIds && entry.stoppedAt == null) {
                        entry.copy(stoppedAt = maxOf(command.deletedAt, entry.startedAt))
                    } else {
                        entry
                    }
                },
                activityEntries = activityEntries,
                at = command.deletedAt,
            )
        }
        val inverses = parentFirstRestoreInverses(deleting)
        return CommandResult.Success(
            message = "${bulkTasksLabel(deleting.size)} moved to the Bin",
            undo = DomainCommand.UndoBatch(inverses)
                .takeIf { inverses.isNotEmpty() },
        )
    }

    /**
     * Replays a repository-produced batch undo in its stored order.
     *
     * Every inverse is preflighted against current state before the first
     * write, so a rejected inverse returns that rejection with nothing
     * changed. The apply then replays the stored order against a scratch
     * engine seeded with the live state and publishes its final snapshot
     * once, so observers never see a partial undo — the batch analogue of
     * the composites' single publish. After a full preflight an apply-time
     * rejection is an internal invariant failure thrown across the execute
     * boundary, which restores the pre-batch state; sub-results carry no
     * further undo.
     */
    private fun undoBatch(command: DomainCommand.UndoBatch): CommandResult {
        if (command.commands.isEmpty()) return CommandResult.Success("Undone")
        command.commands.forEach { inverse ->
            rejectUndoCommand(inverse)?.let { return it }
        }
        val scratch = InMemoryVaultRepository(
            initial = mutableWorkspace.value,
            now = now,
            sourceDeviceId = sourceDeviceId,
        )
        // Tombstones are read-modify-write inside restore handlers, so the
        // scratch engine starts from the live list; task-tag relations are
        // reconciled once by the outer execute after this handler returns.
        scratch.tombstones = tombstones
        command.commands.forEach { inverse ->
            val result = scratch.dispatch(inverse)
            check(result !is CommandResult.Rejected) {
                "UndoBatch inverse rejected after preflight"
            }
        }
        mutableWorkspace.value = scratch.mutableWorkspace.value
        tombstones = scratch.tombstones
        return CommandResult.Success("Undone")
    }

    /**
     * Preflights one stored inverse against current state without writing,
     * mirroring the rejection paths of the corresponding handler. The
     * repository only ever stores [DomainCommand.RestoreTaskStatus],
     * [DomainCommand.RestoreTask], [DomainCommand.UpdateTask],
     * [DomainCommand.SetTaskSchedule], [DomainCommand.SetTaskTag], and — from
     * an automation rule's ON_ENTER_ADD_TO_MY_DAY output —
     * [DomainCommand.RemoveTaskFromMyDay]; any other shape fails closed.
     */
    private fun rejectUndoCommand(inverse: DomainCommand): CommandResult.Rejected? =
        when (inverse) {
            // Idempotent: removing an entry that is no longer there succeeds.
            is DomainCommand.RemoveTaskFromMyDay -> null
            is DomainCommand.RestoreTaskStatus -> {
                val current = mutableWorkspace.value
                when {
                    current.tasks.none { it.id == inverse.taskId } ->
                        CommandResult.Rejected(
                            RejectionReason.NOT_FOUND,
                            "Task no longer exists.",
                        )
                    current.workflowStatuses.none { it.id == inverse.statusId } ->
                        CommandResult.Rejected(
                            RejectionReason.NOT_FOUND,
                            "The previous workflow status no longer exists.",
                        )
                    else -> null
                }
            }
            is DomainCommand.RestoreTask ->
                if (mutableWorkspace.value.tasks.none { it.id == inverse.taskId }) {
                    CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
                } else {
                    null
                }
            is DomainCommand.SetTaskTag -> {
                val current = mutableWorkspace.value
                val task = current.tasks.firstOrNull { it.id == inverse.taskId }
                when {
                    task == null -> CommandResult.Rejected(
                        RejectionReason.NOT_FOUND,
                        "Task no longer exists.",
                    )
                    current.tags.none { it.id == inverse.tagId } -> CommandResult.Rejected(
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
                val current = mutableWorkspace.value
                val task = current.tasks.firstOrNull { it.id == inverse.taskId }
                if (task == null) {
                    CommandResult.Rejected(
                        RejectionReason.NOT_FOUND,
                        "Task no longer exists.",
                    )
                } else {
                    rejectTaskSchedule(
                        command = inverse,
                        task = task,
                        existingReminder = current.reminders.firstOrNull {
                            it.taskId == task.id
                        },
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

    private fun changeTaskStatus(
        command: DomainCommand.ChangeTaskStatus,
        activityKind: ActivityKind = ActivityKind.STATUS_CHANGED,
        activityBody: String? = null,
    ): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val status = current.workflowStatuses.firstOrNull {
            it.id == command.statusId &&
                it.projectId == task.projectId &&
                it.archivedAt == null
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
        if (
            status.semanticStatus == SemanticStatus.COMPLETED &&
            !command.acknowledgeOpenSubtasks &&
            current.tasks.any {
                it.parentTaskId == task.id && it.deletedAt == null &&
                    it.semanticStatus != SemanticStatus.COMPLETED
            }
        ) {
            return CommandResult.Rejected(
                RejectionReason.OPEN_SUBTASKS_CONFIRM_REQUIRED,
                "This task still has open subtasks.",
            )
        }
        val limit = status.wipLimit
        if (
            limit != null &&
            status.semanticStatus != SemanticStatus.COMPLETED &&
            task.statusId != status.id &&
            !command.acknowledgeWipLimit &&
            current.tasks.count {
                it.statusId == status.id && it.deletedAt == null && !it.isCompleted
            } + 1 > limit
        ) {
            return CommandResult.Rejected(
                RejectionReason.WIP_LIMIT_CONFIRM_REQUIRED,
                "“${status.name}” is at its limit of $limit open tasks.",
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
                it.projectId == task.projectId &&
                    it.archivedAt == null &&
                    it.semanticStatus == SemanticStatus.PLANNED
            } ?: current.workflowStatuses.firstOrNull {
                it.projectId == task.projectId &&
                    it.archivedAt == null &&
                    it.semanticStatus == SemanticStatus.BACKLOG
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
        val generatedReminder = generated?.let { next ->
            nextOccurrenceReminder(
                currentTask = task,
                nextTask = next,
                reminders = current.reminders,
            )
        }
        publish(
            current.tasks.map { if (it.id == task.id) updated else it } +
                listOfNotNull(generated),
            reminders = current.reminders + listOfNotNull(generatedReminder),
        )
        val previousStatus = current.workflowStatuses.firstOrNull { it.id == task.statusId }
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
        val generatedOccurrenceIds = setOfNotNull(command.generatedOccurrenceId)
        command.generatedOccurrenceId
            ?.let { id -> current.tasks.firstOrNull { it.id == id } }
            ?.let { generated ->
                tombstones = tombstones.upsert(generated.toTombstone(command.restoredAt))
            }
        publish(
            current.tasks
                .filterNot { it.id == command.generatedOccurrenceId }
                .replace(updated),
            reminders = current.reminders.filterNot {
                it.taskId == command.generatedOccurrenceId
            },
            attachments = current.attachments.filterNot { it.taskId in generatedOccurrenceIds },
            retiredBlobSets = current.retiredBlobSets +
                current.attachments.retiredBlobSets(generatedOccurrenceIds, command.restoredAt),
        )
        val previousStatus = current.workflowStatuses.firstOrNull { it.id == task.statusId }
        recordActivity(
            taskId = task.id,
            projectId = task.projectId,
            kind = ActivityKind.STATUS_CHANGED,
            body = "${previousStatus?.name ?: "Unknown"} → ${status.name}",
            at = command.restoredAt,
        )
        return CommandResult.Success("Status restored")
    }

    private fun reopenTask(taskId: TaskId): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val planned = current.workflowStatuses.firstOrNull {
            it.projectId == task.projectId &&
                it.semanticStatus == SemanticStatus.PLANNED &&
                it.archivedAt == null
        } ?: current.workflowStatuses.firstOrNull {
            it.projectId == task.projectId &&
                it.semanticStatus == SemanticStatus.BACKLOG &&
                it.archivedAt == null
        } ?: return CommandResult.Rejected(
            RejectionReason.INVALID_STATE,
            "This workflow has no active status for reopened tasks.",
        )
        publish(
            current.tasks.replace(
                task.copy(
                    semanticStatus = planned.semanticStatus,
                    statusId = planned.id,
                    completedAt = null,
                    revision = nextRevision(task),
                ),
            ),
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

    private fun deleteTask(command: DomainCommand.DeleteTask): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        if (task.deletedAt != null) {
            return CommandResult.Success("Task is already in the Bin")
        }
        // Sorted by id to match Room's `liveChildren` (`ORDER BY id`), so a
        // multi-child subtree's undo-batch and activity-entry order agree
        // between engines.
        val children = current.tasks
            .filter { it.parentTaskId == task.id && it.deletedAt == null }
            .sortedBy { it.id.value }
        val updated = task.copy(
            deletedAt = command.deletedAt,
            revision = nextRevision(task, command.deletedAt),
        )
        val updatedChildren = children.map { child ->
            child.copy(
                deletedAt = command.deletedAt,
                revision = nextRevision(child, command.deletedAt),
            )
        }
        val updatedById = (listOf(updated) + updatedChildren).associateBy(Task::id)
        val binningIds = updatedById.keys
        publish(
            tasks = current.tasks.map { updatedById[it.id] ?: it },
            timeEntries = current.timeEntries.map { entry ->
                if (entry.taskId in binningIds && entry.stoppedAt == null) {
                    entry.copy(stoppedAt = maxOf(command.deletedAt, entry.startedAt))
                } else {
                    entry
                }
            },
            at = command.deletedAt,
        )
        recordActivity(
            taskId = task.id,
            projectId = task.projectId,
            kind = ActivityKind.BINNED,
            body = "Moved to Bin",
            at = command.deletedAt,
        )
        children.forEach { child ->
            recordActivity(
                taskId = child.id,
                projectId = child.projectId,
                kind = ActivityKind.BINNED,
                body = "Moved to Bin",
                at = command.deletedAt,
            )
        }
        return CommandResult.Success(
            message = "Task moved to the Bin",
            undo = if (children.isEmpty()) {
                // No subtree: keep the plain single-command undo shape and
                // its "Task restored" message for the common, high-frequency
                // childless delete.
                DomainCommand.RestoreTask(task.id)
            } else {
                // UndoBatch replays in list order: parent first, then
                // children, so no child ever restores under a still-binned
                // parent and self-detaches.
                DomainCommand.UndoBatch(
                    listOf(DomainCommand.RestoreTask(task.id)) +
                        children.map { DomainCommand.RestoreTask(it.id) },
                )
            },
        )
    }

    private fun restoreTask(command: DomainCommand.RestoreTask): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val deletedAt = task.deletedAt ?: return CommandResult.Success("Task is already restored")
        // A live parent restores its subtree via the parent-first UndoBatch;
        // a still-binned (or already-purged) parent means this child would
        // otherwise pop back up attached to an invisible parent, and a live
        // parent that moved to another project while the child was binned
        // (the child was skipped as no longer live, so it moves without the
        // child) would leave a cross-project link -- SubtaskRules.CROSS_PROJECT
        // -- so detach in either case, explicitly, in this same write.
        val parentId = task.parentTaskId
        val parent = parentId?.let { id -> current.tasks.firstOrNull { it.id == id } }
        val detach = parentId != null && (
            parent == null ||
                parent.deletedAt != null ||
                parent.projectId != task.projectId
            )
        val updated = task.copy(
            deletedAt = null,
            parentTaskId = if (detach) null else task.parentTaskId,
            revision = nextRevision(task),
        )
        publish(current.tasks.replace(updated))
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

    private fun permanentlyDeleteTask(
        command: DomainCommand.PermanentlyDeleteTask,
    ): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        if (task.deletedAt == null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Move the task to the Bin before deleting it permanently.",
            )
        }
        tombstones = tombstones.upsert(task.toTombstone(command.purgedAt))
        publish(
            tasks = current.tasks.mapNotNull { candidate ->
                when {
                    candidate.id == task.id -> null
                    candidate.parentTaskId == task.id -> candidate.copy(
                        parentTaskId = null,
                        revision = nextRevision(candidate, command.purgedAt),
                    )
                    else -> candidate
                }
            },
            reminders = current.reminders.filterNot { it.taskId == task.id },
            timeEntries = current.timeEntries.filterNot { it.taskId == task.id },
            notes = current.notes.filterNot { it.taskId == task.id },
            attachments = current.attachments.filterNot { it.taskId == task.id },
            activityEntries = current.activityEntries.filterNot { it.taskId == task.id },
            myDay = current.myDay.filterNot { it.taskId == task.id },
            retiredBlobSets = current.retiredBlobSets +
                current.attachments.retiredBlobSets(setOf(task.id), command.purgedAt),
        )
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
            expired.forEach { task ->
                tombstones = tombstones.upsert(task.toTombstone(command.now))
            }
            publish(
                tasks = current.tasks.mapNotNull { task ->
                    when {
                        task.id in expiredIds -> null
                        task.parentTaskId in expiredIds -> task.copy(
                            parentTaskId = null,
                            revision = nextRevision(task, command.now),
                        )
                        else -> task
                    }
                },
                reminders = current.reminders.filterNot { it.taskId in expiredIds },
                timeEntries = current.timeEntries.filterNot { it.taskId in expiredIds },
                notes = current.notes.filterNot { it.taskId in expiredIds },
                attachments = current.attachments.filterNot { it.taskId in expiredIds },
                activityEntries = current.activityEntries.filterNot { it.taskId in expiredIds },
                myDay = current.myDay.filterNot { it.taskId in expiredIds },
                retiredBlobSets = current.retiredBlobSets +
                    current.attachments.retiredBlobSets(expiredIds, command.now),
                at = command.now,
            )
        }
        return CommandResult.Success(
            if (expired.isEmpty()) {
                "No expired Bin items"
            } else {
                "${expired.size} expired Bin items deleted"
            },
        )
    }

    private fun Task.toTombstone(purgedAt: Instant): TombstoneEntity {
        val deletedAt = deletedAt ?: purgedAt
        val tombstoneRevision = nextRevision(this, purgedAt)
        return TombstoneEntity(
            objectId = id.value,
            objectType = "task",
            deletedAtEpochMillis = deletedAt.toEpochMilli(),
            purgeAfterEpochMillis = TrashPolicy.purgeAfter(deletedAt).toEpochMilli(),
            revisionWallMillis = tombstoneRevision.wallTimeMillis,
            revisionLogical = tombstoneRevision.logicalCounter,
            revisionDeviceId = tombstoneRevision.deviceId.value,
        )
    }

    private fun List<TombstoneEntity>.upsert(value: TombstoneEntity): List<TombstoneEntity> =
        filterNot {
            it.objectId == value.objectId && it.objectType == value.objectType
        } + value

    private fun List<Attachment>.retiredBlobSets(
        taskIds: Set<TaskId>,
        purgedAt: Instant,
    ): List<RetiredBlobSet> = filter { it.taskId in taskIds && it.blobSetId != null }
        .map { attachment ->
            RetiredBlobSet(
                blobSetId = requireNotNull(attachment.blobSetId),
                chunkCount = attachment.chunkCount,
                retiredAt = purgedAt,
                revision = Revision(
                    deviceId = sourceDeviceId,
                    wallTimeMillis = purgedAt.toEpochMilli(),
                    logicalCounter = 0,
                ),
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
            if (title == task.title) {
                task
            } else {
                task.copy(title = title, revision = nextRevision(task))
            }
        }
    }

    private sealed interface TaskUpdateValidation {
        data class Invalid(val rejection: CommandResult.Rejected) : TaskUpdateValidation

        data class Valid(
            val task: Task,
            val existingReminder: Reminder?,
            val requestedReminder: Reminder?,
            val requestedMilestone: Milestone?,
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
    private fun validateTaskUpdate(
        command: DomainCommand.UpdateTask,
    ): TaskUpdateValidation {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return invalidTaskUpdate(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val existingReminder = current.reminders.firstOrNull { it.taskId == task.id }
        val requestedReminder = command.reminder?.copy(
            id = Reminder.primaryId(task.id),
            taskId = task.id,
        )
        val requestedProject = command.projectId?.let { projectId ->
            current.projects.firstOrNull { it.id == projectId }
        }
        val requestedMilestone = command.milestoneId?.let { milestoneId ->
            current.milestones.firstOrNull { it.id == milestoneId }
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
            requestedProject?.archivedAt != null && task.projectId != requestedProject.id ->
                return invalidTaskUpdate(
                    RejectionReason.INVALID_STATE,
                    "Restore that project before assigning new tasks to it.",
                )
            command.milestoneId != null && requestedMilestone == null ->
                return invalidTaskUpdate(
                    RejectionReason.NOT_FOUND,
                    "That milestone no longer exists.",
                )
            requestedMilestone != null && requestedMilestone.projectId != command.projectId ->
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
        val targetStatus = when {
            command.restoreStatusId != null -> current.workflowStatuses.firstOrNull {
                it.id == command.restoreStatusId && it.projectId == command.projectId
            }
            task.projectId != command.projectId -> current.workflowStatuses.firstOrNull {
                it.projectId == command.projectId &&
                    it.semanticStatus == task.semanticStatus &&
                    it.archivedAt == null
            }
            else -> current.workflowStatuses.firstOrNull { it.id == task.statusId }
        } ?: return invalidTaskUpdate(
            RejectionReason.INVALID_STATE,
            "The destination workflow has no matching ${task.semanticStatus.readableCategory()} status.",
        )
        return TaskUpdateValidation.Valid(
            task = task,
            existingReminder = existingReminder,
            requestedReminder = requestedReminder,
            requestedMilestone = requestedMilestone,
            recurrenceMetadata = recurrenceMetadata,
            targetStatus = targetStatus,
        )
    }

    private fun updateTaskDetails(command: DomainCommand.UpdateTask): CommandResult {
        val plan = when (val validation = validateTaskUpdate(command)) {
            is TaskUpdateValidation.Invalid -> return validation.rejection
            is TaskUpdateValidation.Valid -> validation
        }
        val current = mutableWorkspace.value
        val task = plan.task
        val existingReminder = plan.existingReminder
        val requestedReminder = plan.requestedReminder
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
        publish(
            tasks = current.tasks.map { if (it.id == task.id) updated else it },
            reminders = current.reminders
                .filterNot { it.taskId == task.id } + listOfNotNull(requestedReminder),
        )
        if (task.projectId != updated.projectId) {
            recordActivity(
                taskId = task.id,
                projectId = updated.projectId,
                kind = ActivityKind.PROJECT_MOVED,
                body = "${current.projects.firstOrNull { it.id == task.projectId }?.name ?: "Inbox"} → " +
                    "${current.projects.firstOrNull { it.id == updated.projectId }?.name ?: "Inbox"}",
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

    private fun setTaskSchedule(command: DomainCommand.SetTaskSchedule): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val existingReminder = current.reminders.firstOrNull { it.taskId == task.id }
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
        publish(
            tasks = current.tasks.map { if (it.id == task.id) updated else it },
            reminders = current.reminders.filterNot { it.taskId == task.id } +
                listOfNotNull(command.reminder),
        )
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

    private fun setTaskReminder(command: DomainCommand.SetTaskReminder): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val existing = current.reminders.firstOrNull { it.taskId == task.id }
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
        publish(
            tasks = current.tasks.replace(updated),
            reminders = current.reminders
                .filterNot { it.taskId == task.id } + listOfNotNull(requested),
        )
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
        publish(tasks = current.tasks.replace(updated), tags = current.tags + tag)
        return CommandResult.Success(
            message = "Tag created and added",
            undo = DomainCommand.SetTaskTag(task.id, tag.id, false),
        )
    }

    private fun setTaskDependency(
        command: DomainCommand.SetTaskDependency,
    ): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Task no longer exists.",
            )
        val dependency = current.tasks.firstOrNull { it.id == command.dependsOnTaskId }
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
            val existing = current.tasks.flatMap { candidate ->
                candidate.dependencyIds.map { dependencyId ->
                    TaskDependency(candidate.id, dependencyId, candidate.revision)
                }
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
        val updated = task.copy(
            dependencyIds = if (command.present) {
                task.dependencyIds + dependency.id
            } else {
                task.dependencyIds - dependency.id
            },
            revision = nextRevision(task),
        )
        publish(current.tasks.replace(updated))
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

    private fun setTaskParent(command: DomainCommand.SetTaskParent): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        if (task.parentTaskId == command.parentTaskId) {
            return CommandResult.Success("Subtask link is unchanged")
        }
        val parentId = command.parentTaskId
        if (parentId != null) {
            val violation = SubtaskRules.parentViolation(
                tasks = current.tasks,
                taskId = task.id,
                parentTaskId = parentId,
            )
            if (violation != null) {
                return CommandResult.Rejected(
                    RejectionReason.SUBTASK_PARENT_INVALID,
                    subtaskViolationMessage(violation),
                )
            }
        }
        val updated = task.copy(parentTaskId = parentId, revision = nextRevision(task))
        publish(current.tasks.replace(updated))
        return CommandResult.Success(
            message = if (parentId == null) "Subtask detached" else "Subtask attached",
            undo = DomainCommand.SetTaskParent(task.id, task.parentTaskId),
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
        val entry = TimeEntry(
            id = TimeEntryId.new(),
            taskId = task.id,
            deviceId = current.timeEntries.firstOrNull()?.deviceId ?: DeviceId("in-memory"),
            startedAt = command.startedAt,
            stoppedAt = null,
        )
        mutableWorkspace.value = current.withReconciledTimeState(
            at = command.startedAt,
            entries = current.timeEntries + entry,
        )
        return CommandResult.Success("Timer started")
    }

    private fun stopTimer(): CommandResult {
        val current = mutableWorkspace.value
        val active = current.home.activeTimer
        if (active == null) {
            return CommandResult.Rejected(RejectionReason.INVALID_STATE, "No timer is running.")
        }
        val stoppedAt = maxOf(now(), active.startedAt)
        mutableWorkspace.value = current.withReconciledTimeState(
            at = stoppedAt,
            entries = current.timeEntries.map { entry ->
                if (entry.id == active.entryId) entry.copy(stoppedAt = stoppedAt) else entry
            },
        )
        return CommandResult.Success("Timer stopped")
    }

    // Mirrors RoomVaultRepository.stopTimerIfOwned: the owner check and the
    // stop share one accepted mutation, and a mismatch writes nothing at all.
    private fun stopTimerIfOwned(command: DomainCommand.StopTimerIfOwned): CommandResult {
        val current = mutableWorkspace.value
        val active = current.home.activeTimer
            ?: return CommandResult.Success("No timer is running")
        if (active.taskId != command.taskId) {
            return CommandResult.Rejected(
                RejectionReason.TIMER_OWNERSHIP_CHANGED,
                "Another task owns the running timer.",
            )
        }
        val stoppedAt = maxOf(now(), active.startedAt)
        mutableWorkspace.value = current.withReconciledTimeState(
            at = stoppedAt,
            entries = current.timeEntries.map { entry ->
                if (entry.id == active.entryId) entry.copy(stoppedAt = stoppedAt) else entry
            },
        )
        return CommandResult.Success("Timer stopped")
    }

    private fun addTimeEntry(command: DomainCommand.AddTimeEntry): CommandResult {
        val current = mutableWorkspace.value
        val note = command.note.trim()
        validateTimeEntry(
            taskId = command.taskId,
            startedAt = command.startedAt,
            stoppedAt = command.stoppedAt,
            note = note,
        )?.let { return it }
        if (current.timeEntries.any { it.id == command.entryId }) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That time-entry identifier is already in use.",
            )
        }
        if (current.timeEntries.count { it.taskId == command.taskId } >= MAX_TIME_ENTRIES_PER_TASK) {
            return CommandResult.Rejected(
                RejectionReason.TIME_ENTRY_LIMIT_REACHED,
                "A task can contain up to $MAX_TIME_ENTRIES_PER_TASK time entries.",
            )
        }
        val entry = TimeEntry(
            id = command.entryId,
            taskId = command.taskId,
            deviceId = current.timeEntries.firstOrNull()?.deviceId ?: DeviceId("in-memory"),
            startedAt = command.startedAt,
            stoppedAt = command.stoppedAt,
            note = note,
        )
        val updated = current.withReconciledTimeState(
            at = command.changedAt,
            entries = current.timeEntries + entry,
        )
        mutableWorkspace.value = updated
        return CommandResult.Success(
            message = if (updated.hasConflict(entry.id)) {
                "Time entry added; review the overlap"
            } else {
                "Time entry added"
            },
            undo = DomainCommand.DeleteTimeEntry(entry.id),
        )
    }

    private fun updateTimeEntry(command: DomainCommand.UpdateTimeEntry): CommandResult {
        val current = mutableWorkspace.value
        val existing = current.timeEntries.firstOrNull { it.id == command.entryId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Time entry no longer exists.",
            )
        if (existing.stoppedAt == null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Stop the timer before editing its time entry.",
            )
        }
        val note = command.note.trim()
        validateTimeEntry(
            taskId = existing.taskId,
            startedAt = command.startedAt,
            stoppedAt = command.stoppedAt,
            note = note,
        )?.let { return it }
        val entry = existing.copy(
            startedAt = command.startedAt,
            stoppedAt = command.stoppedAt,
            note = note,
        )
        if (entry == existing) return CommandResult.Success("Time entry is up to date")
        val updated = current.withReconciledTimeState(
            at = command.changedAt,
            entries = current.timeEntries.map { candidate ->
                if (candidate.id == entry.id) entry else candidate
            },
        )
        mutableWorkspace.value = updated
        return CommandResult.Success(
            message = if (updated.hasConflict(entry.id)) {
                "Time entry saved; review the overlap"
            } else {
                "Time entry saved"
            },
            undo = DomainCommand.RestoreTimeEntry(existing),
        )
    }

    private fun deleteTimeEntry(command: DomainCommand.DeleteTimeEntry): CommandResult {
        val current = mutableWorkspace.value
        val existing = current.timeEntries.firstOrNull { it.id == command.entryId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Time entry no longer exists.",
            )
        if (existing.stoppedAt == null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Stop the timer before deleting its time entry.",
            )
        }
        mutableWorkspace.value = current.withReconciledTimeState(
            at = command.deletedAt,
            entries = current.timeEntries.filterNot { it.id == existing.id },
        )
        return CommandResult.Success(
            message = "Time entry deleted",
            undo = DomainCommand.RestoreTimeEntry(existing),
        )
    }

    private fun restoreTimeEntry(command: DomainCommand.RestoreTimeEntry): CommandResult {
        val current = mutableWorkspace.value
        val existing = current.timeEntries.firstOrNull { it.id == command.entry.id }
        if (existing != null && existing.taskId != command.entry.taskId) {
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
        val restored = command.entry.copy(note = note)
        mutableWorkspace.value = current.withReconciledTimeState(
            at = command.restoredAt,
            entries = current.timeEntries.filterNot { it.id == restored.id } + restored,
        )
        return CommandResult.Success(
            message = "Time entry restored",
            undo = existing
                ?.let { DomainCommand.RestoreTimeEntry(it) }
                ?: DomainCommand.DeleteTimeEntry(restored.id),
        )
    }

    private fun addNote(command: DomainCommand.AddNote): CommandResult {
        val current = mutableWorkspace.value
        if ((command.taskId == null) == (command.projectId == null)) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "A note belongs to exactly one task or project.",
            )
        }
        command.taskId?.let { taskId ->
            current.tasks.firstOrNull { it.id == taskId && it.deletedAt == null }
                ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        }
        command.projectId?.let { projectId ->
            current.projects.firstOrNull { it.id == projectId }
                ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Project no longer exists.")
        }
        val body = command.body.trim()
        validateNoteBody(body)?.let { return it }
        val owned = current.notes.count {
            it.taskId == command.taskId && it.projectId == command.projectId
        }
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
            revision = Revision(sourceDeviceId, command.createdAt.toEpochMilli(), 0),
        )
        mutableWorkspace.value = current.copy(notes = current.notes + note)
        return CommandResult.Success("Note added", undo = DomainCommand.DeleteNote(note.id))
    }

    private fun updateNote(command: DomainCommand.UpdateNote): CommandResult {
        val current = mutableWorkspace.value
        val original = current.notes.firstOrNull { it.id == command.noteId }
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
        mutableWorkspace.value = current.copy(
            notes = current.notes.map { if (it.id == updated.id) updated else it },
        )
        return CommandResult.Success(
            message = "Note saved",
            undo = DomainCommand.RestoreNote(original),
        )
    }

    private fun deleteNote(command: DomainCommand.DeleteNote): CommandResult {
        val current = mutableWorkspace.value
        val note = current.notes.firstOrNull { it.id == command.noteId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Note no longer exists.")
        mutableWorkspace.value = current.copy(notes = current.notes.filterNot { it.id == note.id })
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
     * held introduces no reference the record does not already hold. Replay is
     * safe because writing identical content produces no journal diff.
     */
    private fun restoreNote(command: DomainCommand.RestoreNote): CommandResult {
        val current = mutableWorkspace.value
        val existing = current.notes.firstOrNull { it.id == command.note.id }
        if (existing == null) {
            command.note.taskId?.let { taskId ->
                current.tasks.firstOrNull { it.id == taskId && it.deletedAt == null }
                    ?: return CommandResult.Rejected(
                        RejectionReason.NOT_FOUND,
                        "Task no longer exists.",
                    )
            }
            command.note.projectId?.let { projectId ->
                current.projects.firstOrNull { it.id == projectId }
                    ?: return CommandResult.Rejected(
                        RejectionReason.NOT_FOUND,
                        "Project no longer exists.",
                    )
            }
        }
        mutableWorkspace.value = current.copy(
            notes = if (existing == null) {
                current.notes + command.note
            } else {
                current.notes.map { if (it.id == command.note.id) command.note else it }
            },
        )
        return CommandResult.Success(
            message = "Note restored",
            undo = existing?.let(DomainCommand::RestoreNote)
                ?: DomainCommand.DeleteNote(command.note.id),
        )
    }

    private fun registerAttachment(command: DomainCommand.RegisterAttachment): CommandResult {
        val current = mutableWorkspace.value
        val attachment = command.attachment
        current.tasks.firstOrNull { it.id == attachment.taskId && it.deletedAt == null }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        val displayName = attachment.displayName.trim()
        validateAttachment(displayName, attachment)?.let { return it }
        val registered = attachment.copy(displayName = displayName)
        val existing = current.attachments.firstOrNull { it.id == attachment.id }
        if (existing?.hasSameRegistrationAs(registered) == true) {
            return CommandResult.Success("Attachment added")
        }
        val replacingActive = existing?.let {
            it.taskId == attachment.taskId && it.deletedAt == null
        } == true
        if (!replacingActive && current.attachments.count {
                it.taskId == attachment.taskId && it.deletedAt == null
            } >= MAX_ATTACHMENTS_PER_TASK
        ) {
            return CommandResult.Rejected(
                RejectionReason.ATTACHMENT_LIMIT_REACHED,
                "A task can contain up to $MAX_ATTACHMENTS_PER_TASK attachments.",
            )
        }
        mutableWorkspace.value = current.copy(
            attachments = current.attachments.filterNot { it.id == registered.id } + registered,
        )
        recordActivity(
            taskId = registered.taskId,
            projectId = current.tasks.first { it.id == registered.taskId }.projectId,
            kind = ActivityKind.ATTACHMENT_ADDED,
            body = "Added attachment: ${registered.displayName}",
            at = now(),
        )
        return CommandResult.Success("Attachment added")
    }

    private fun deleteAttachment(command: DomainCommand.DeleteAttachment): CommandResult {
        val current = mutableWorkspace.value
        val attachment = current.attachments.firstOrNull { it.id == command.attachmentId }
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
        mutableWorkspace.value = current.copy(
            attachments = current.attachments.map { if (it.id == attachment.id) deleted else it },
        )
        recordActivity(
            taskId = attachment.taskId,
            projectId = current.tasks.firstOrNull { it.id == attachment.taskId }?.projectId,
            kind = ActivityKind.ATTACHMENT_REMOVED,
            body = "Removed attachment: ${attachment.displayName}",
            at = command.deletedAt,
        )
        return CommandResult.Success(
            message = "Attachment removed",
            undo = DomainCommand.RestoreAttachment(attachment),
        )
    }

    private fun restoreAttachment(command: DomainCommand.RestoreAttachment): CommandResult {
        val current = mutableWorkspace.value
        val restored = command.attachment.copy(deletedAt = null)
        if (current.attachments.none { it.id == restored.id }) {
            return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Attachment no longer exists.")
        }
        mutableWorkspace.value = current.copy(
            attachments = current.attachments.map { if (it.id == restored.id) restored else it },
        )
        return CommandResult.Success("Attachment restored")
    }

    private fun markAttachmentContentCollected(
        command: DomainCommand.MarkAttachmentContentCollected,
    ): CommandResult {
        val current = mutableWorkspace.value
        val attachment = current.attachments.firstOrNull { it.id == command.attachmentId }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Attachment no longer exists.",
            )
        // Already recorded: writing again would append a journal entry that
        // changes nothing and advance the backup generation for it.
        if (attachment.blobSetId == null) {
            return CommandResult.Success("Attachment content was already collected")
        }
        val collected = attachment.copy(
            blobSetId = null,
            revision = attachment.revision.copy(
                wallTimeMillis = maxOf(
                    attachment.revision.wallTimeMillis + 1,
                    command.collectedAt.toEpochMilli(),
                ),
                logicalCounter = attachment.revision.logicalCounter + 1,
            ),
        )
        mutableWorkspace.value = current.copy(
            attachments = current.attachments.map {
                if (it.id == collected.id) collected else it
            },
        )
        return CommandResult.Success("Attachment content collected")
    }

    private fun markRetiredBlobSetCollected(
        command: DomainCommand.MarkRetiredBlobSetCollected,
    ): CommandResult {
        val current = mutableWorkspace.value
        if (current.retiredBlobSets.none { it.blobSetId == command.blobSetId }) {
            return CommandResult.Success("Retired blob set was already collected")
        }
        publish(
            tasks = current.tasks,
            retiredBlobSets = current.retiredBlobSets.filterNot {
                it.blobSetId == command.blobSetId
            },
        )
        return CommandResult.Success("Retired blob set collected")
    }

    private fun createSavedView(
        command: DomainCommand.CreateSavedView,
    ): CommandResult {
        val current = mutableWorkspace.value
        val name = command.name.trim()
        validateSavedViewName(name)?.let { return it }
        val query = command.query.copy(text = command.query.text.trim())
        validateSavedViewQueryText(query)?.let { return it }
        if (!savedViewQueryEncodes(query)) {
            return savedViewPayloadTooLarge()
        }
        if (current.savedViews.any { it.id == command.savedViewId }) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That saved search identifier is already in use.",
            )
        }
        if (current.savedViews.size >= MAX_SAVED_VIEWS) {
            return savedViewLimitReached()
        }
        val view = SavedView(
            id = command.savedViewId,
            workspaceId = PRIMARY_WORKSPACE_ID,
            name = name,
            query = query,
        )
        publish(tasks = current.tasks, savedViews = current.savedViews + view)
        return CommandResult.Success(
            message = "Saved search created",
            undo = DomainCommand.DeleteSavedView(view.id),
        )
    }

    private fun renameSavedView(
        command: DomainCommand.RenameSavedView,
    ): CommandResult {
        val current = mutableWorkspace.value
        val existing = current.savedViews.firstOrNull { it.id == command.savedViewId }
            ?: return savedViewNotFound()
        val name = command.name.trim()
        validateSavedViewName(name)?.let { return it }
        if (name != existing.name) {
            publish(
                tasks = current.tasks,
                savedViews = current.savedViews.map {
                    if (it.id == existing.id) it.copy(name = name) else it
                },
            )
        }
        return CommandResult.Success(
            message = "Saved search renamed",
            undo = DomainCommand.RenameSavedView(existing.id, existing.name),
        )
    }

    private fun updateSavedViewQuery(
        command: DomainCommand.UpdateSavedViewQuery,
    ): CommandResult {
        val current = mutableWorkspace.value
        val existing = current.savedViews.firstOrNull { it.id == command.savedViewId }
            ?: return savedViewNotFound()
        val query = command.query.copy(text = command.query.text.trim())
        validateSavedViewQueryText(query)?.let { return it }
        if (!savedViewQueryEncodes(query)) {
            return savedViewPayloadTooLarge()
        }
        publish(
            tasks = current.tasks,
            savedViews = current.savedViews.map {
                if (it.id == existing.id) it.copy(query = query) else it
            },
        )
        return CommandResult.Success(
            message = "Saved search updated",
            undo = DomainCommand.UpdateSavedViewQuery(existing.id, existing.query),
        )
    }

    private fun deleteSavedView(
        command: DomainCommand.DeleteSavedView,
    ): CommandResult {
        val current = mutableWorkspace.value
        val existing = current.savedViews.firstOrNull { it.id == command.savedViewId }
            ?: return CommandResult.Success("Saved search is already deleted")
        publish(
            tasks = current.tasks,
            savedViews = current.savedViews.filterNot { it.id == existing.id },
        )
        return CommandResult.Success(
            message = "Saved search deleted",
            undo = DomainCommand.RestoreSavedView(existing),
        )
    }

    private fun restoreSavedView(
        command: DomainCommand.RestoreSavedView,
    ): CommandResult {
        val current = mutableWorkspace.value
        if (current.savedViews.any { it.id == command.savedView.id }) {
            return CommandResult.Success("Saved search restored")
        }
        val name = command.savedView.name.trim()
        validateSavedViewName(name)?.let { return it }
        if (command.savedView.workspaceId != PRIMARY_WORKSPACE_ID) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That saved search belongs to a different workspace.",
            )
        }
        val query = command.savedView.query.copy(
            text = command.savedView.query.text.trim(),
        )
        validateSavedViewQueryText(query)?.let { return it }
        if (!savedViewQueryEncodes(query)) {
            return savedViewPayloadTooLarge()
        }
        if (current.savedViews.size >= MAX_SAVED_VIEWS) {
            return savedViewLimitReached()
        }
        val restored = command.savedView.copy(name = name, query = query)
        publish(tasks = current.tasks, savedViews = current.savedViews + restored)
        return CommandResult.Success(
            message = "Saved search restored",
            undo = DomainCommand.DeleteSavedView(restored.id),
        )
    }

    private fun addTaskToMyDay(command: DomainCommand.AddTaskToMyDay): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        if (task.deletedAt != null) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Restore that task before planning it.",
            )
        }
        if (current.myDay.any { it.taskId == command.taskId }) {
            return CommandResult.Success("Already on My Day")
        }
        if (current.myDay.size >= MAX_MY_DAY_ENTRIES) {
            return CommandResult.Rejected(
                RejectionReason.MY_DAY_LIMIT_REACHED,
                "My Day holds up to $MAX_MY_DAY_ENTRIES tasks.",
            )
        }
        val last = current.myDay.maxByOrNull(MyDayEntry::rank)?.rank
        val appended = rankAfter(last)
        val entries = if (appended.length <= MAX_MY_DAY_RANK_LENGTH) {
            current.myDay + MyDayEntry(command.taskId, appended)
        } else {
            // Append-bound guard: the sequential ponytail rank would exceed
            // the journal codec's bound and abort the transaction, so full
            // re-rank the existing entries in (rank, taskId) order and place
            // the new entry at the tail index — the move handler's ponytail
            // fallback applied to appends.
            current.myDay.mapIndexed { index, entry ->
                entry.copy(rank = myDayRankForIndex(index))
            } + MyDayEntry(command.taskId, myDayRankForIndex(current.myDay.size))
        }
        publish(tasks = current.tasks, myDay = entries)
        return CommandResult.Success(
            message = "Added to My Day",
            undo = DomainCommand.RemoveTaskFromMyDay(command.taskId),
        )
    }

    private fun removeTaskFromMyDay(
        command: DomainCommand.RemoveTaskFromMyDay,
    ): CommandResult {
        val current = mutableWorkspace.value
        val entry = current.myDay.firstOrNull { it.taskId == command.taskId }
            ?: return CommandResult.Success("Not on My Day")
        publish(
            tasks = current.tasks,
            myDay = current.myDay.filterNot { it.taskId == entry.taskId },
        )
        return CommandResult.Success(
            message = "Removed from My Day",
            undo = DomainCommand.RestoreMyDayEntries(listOf(entry)),
        )
    }

    private fun moveMyDayEntry(command: DomainCommand.MoveMyDayEntry): CommandResult {
        val current = mutableWorkspace.value
        val entries = current.myDay
        val moving = entries.firstOrNull { it.taskId == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Not on My Day.")
        val anchored = entries.filterNot { it.taskId == moving.taskId }
        val anchorIndex = command.afterTaskId?.let { after ->
            anchored.indexOfFirst { it.taskId == after }
                .takeIf { it >= 0 }
                ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Not on My Day.")
        }
        val previousRank = anchorIndex?.let { anchored[it].rank }
        val nextRank = when (anchorIndex) {
            null -> anchored.firstOrNull()?.rank
            else -> anchored.getOrNull(anchorIndex + 1)?.rank
        }
        if (previousRank == moving.rank || nextRank == moving.rank) {
            return CommandResult.Success("My Day order is unchanged")
        }
        val between = myDayRankBetween(previousRank, nextRank)
        return if (between != null) {
            publish(
                tasks = current.tasks,
                myDay = entries.map {
                    if (it.taskId == moving.taskId) moving.copy(rank = between) else it
                },
            )
            CommandResult.Success(
                message = "My Day reordered",
                undo = DomainCommand.RestoreMyDayEntries(listOf(moving)),
            )
        } else {
            // ponytail: midpoint exhausted — deterministic full re-rank in
            // the same command; every row journals once, which is rare
            // and bounded at 200 rows.
            val reordered = buildList {
                addAll(anchored.take(anchorIndex?.plus(1) ?: 0))
                add(moving)
                addAll(anchored.drop(anchorIndex?.plus(1) ?: 0))
            }
            publish(
                tasks = current.tasks,
                myDay = reordered.mapIndexed { index, entry ->
                    entry.copy(rank = myDayRankForIndex(index))
                },
            )
            CommandResult.Success(
                message = "My Day reordered",
                undo = DomainCommand.RestoreMyDayEntries(entries),
            )
        }
    }

    private fun sweepMyDay(command: DomainCommand.SweepMyDay): CommandResult {
        val current = mutableWorkspace.value
        val tasksById = current.tasks.associateBy(Task::id)
        val removed = current.myDay.filter { entry ->
            val task = tasksById[entry.taskId]
            task == null || task.completedAt?.let { it.isBefore(command.before) } == true
        }
        if (removed.isEmpty()) {
            return CommandResult.Success("My Day is up to date")
        }
        val removedIds = removed.mapTo(hashSetOf(), MyDayEntry::taskId)
        publish(
            tasks = current.tasks,
            myDay = current.myDay.filterNot { it.taskId in removedIds },
        )
        return CommandResult.Success(
            message = "My Day tidied",
            undo = DomainCommand.RestoreMyDayEntries(removed),
        )
    }

    private fun restoreMyDayEntries(
        command: DomainCommand.RestoreMyDayEntries,
    ): CommandResult {
        val current = mutableWorkspace.value
        val restoredById = command.entries.associateBy(MyDayEntry::taskId)
        publish(
            tasks = current.tasks,
            myDay = current.myDay.filterNot { it.taskId in restoredById.keys } +
                command.entries,
        )
        return CommandResult.Success("My Day restored")
    }

    private fun automationRuleReferenceRejection(rule: AutomationRule): CommandResult.Rejected? {
        val current = mutableWorkspace.value
        if (rule.statusId != null && current.workflowStatuses.none { it.id == rule.statusId }) {
            return automationRuleNotFound()
        }
        if (rule.tagId != null && current.tags.none { it.id == rule.tagId }) {
            return automationRuleNotFound()
        }
        if (rule.projectId != null && current.projects.none { it.id == rule.projectId }) {
            return automationRuleNotFound()
        }
        return null
    }

    private fun createAutomationRule(
        command: DomainCommand.CreateAutomationRule,
    ): CommandResult {
        val current = mutableWorkspace.value
        val rule = command.rule
        automationRuleWorkspaceRejection(rule)?.let { return it }
        automationRuleConfigRejection(rule)?.let { return it }
        automationRuleReferenceRejection(rule)?.let { return it }
        if (current.automationRules.size >= MAX_AUTOMATION_RULES) {
            return CommandResult.Rejected(
                RejectionReason.AUTOMATION_RULE_LIMIT_REACHED,
                "Up to $MAX_AUTOMATION_RULES automation rules.",
            )
        }
        if (current.automationRules.any { it.id == rule.id }) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That automation rule identifier is already in use.",
            )
        }
        publish(tasks = current.tasks, automationRules = current.automationRules + rule)
        return CommandResult.Success(
            message = "Automation rule created",
            undo = DomainCommand.DeleteAutomationRule(rule.id),
        )
    }

    private fun updateAutomationRule(
        command: DomainCommand.UpdateAutomationRule,
    ): CommandResult {
        val current = mutableWorkspace.value
        val rule = command.rule
        val existing = current.automationRules.firstOrNull { it.id == rule.id }
            ?: return CommandResult.Rejected(
                RejectionReason.NOT_FOUND,
                "Automation rule no longer exists.",
            )
        automationRuleWorkspaceRejection(rule)?.let { return it }
        automationRuleConfigRejection(rule)?.let { return it }
        automationRuleReferenceRejection(rule)?.let { return it }
        publish(
            tasks = current.tasks,
            automationRules = current.automationRules.map { if (it.id == rule.id) rule else it },
        )
        return CommandResult.Success(
            message = "Automation rule updated",
            undo = DomainCommand.UpdateAutomationRule(existing),
        )
    }

    private fun deleteAutomationRule(
        command: DomainCommand.DeleteAutomationRule,
    ): CommandResult {
        val current = mutableWorkspace.value
        val existing = current.automationRules.firstOrNull { it.id == command.ruleId }
            ?: return CommandResult.Success("Rule is already removed")
        publish(
            tasks = current.tasks,
            automationRules = current.automationRules.filterNot { it.id == command.ruleId },
        )
        return CommandResult.Success(
            message = "Automation rule deleted",
            undo = DomainCommand.CreateAutomationRule(existing),
        )
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

    private fun savedViewQueryEncodes(query: SearchQuery): Boolean =
        runCatching { SavedViewPayloadCodec.encode(query) }.isSuccess

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

    private fun validateTimeEntry(
        taskId: TaskId,
        startedAt: Instant,
        stoppedAt: Instant,
        note: String,
    ): CommandResult.Rejected? {
        val task = mutableWorkspace.value.tasks.firstOrNull { it.id == taskId }
        return when {
            task == null || task.deletedAt != null -> CommandResult.Rejected(
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

    private fun validateTemplateName(
        name: String,
        excluding: TemplateId? = null,
    ): CommandResult.Rejected? = when {
        name.isEmpty() -> CommandResult.Rejected(
            RejectionReason.EMPTY_TEMPLATE_NAME,
            "A template needs a name.",
        )
        name.length > MAX_TEMPLATE_NAME_LENGTH -> CommandResult.Rejected(
            RejectionReason.TEMPLATE_NAME_TOO_LONG,
            "Keep template names under $MAX_TEMPLATE_NAME_LENGTH characters.",
        )
        mutableWorkspace.value.templates.any { template ->
            template.id != excluding && template.name.equals(name, ignoreCase = true)
        } -> CommandResult.Rejected(
            RejectionReason.DUPLICATE_TEMPLATE_NAME,
            "This workspace already has a template with that name.",
        )
        else -> null
    }

    private fun rankAfter(rank: String?): String = rank?.plus('m') ?: "a0"

    private fun List<Task>.replace(updated: Task): List<Task> =
        map { task -> if (task.id == updated.id) updated else task }

    private fun List<Project>.replace(updated: Project): List<Project> =
        map { project -> if (project.id == updated.id) updated else project }

    private fun List<WorkflowStatus>.replace(updated: WorkflowStatus): List<WorkflowStatus> =
        map { status -> if (status.id == updated.id) updated else status }

    private fun List<Milestone>.replace(updated: Milestone): List<Milestone> =
        map { milestone -> if (milestone.id == updated.id) updated else milestone }

    private fun publishProjects(
        projects: List<Project>,
        workflowStatuses: List<WorkflowStatus> = mutableWorkspace.value.workflowStatuses,
    ) {
        val current = mutableWorkspace.value
        mutableWorkspace.value = current.copy(
            projects = projects,
            workflowStatuses = workflowStatuses.sortedWith(
                compareBy<WorkflowStatus> { it.projectId?.value.orEmpty() }
                    .thenBy(WorkflowStatus::rank),
            ),
            home = current.home.copy(projects = projects.filter { it.archivedAt == null }),
        )
    }

    private fun publishWorkflowStatuses(statuses: List<WorkflowStatus>) {
        mutableWorkspace.value = mutableWorkspace.value.copy(
            workflowStatuses = statuses.sortedWith(
                compareBy<WorkflowStatus> { it.projectId?.value.orEmpty() }
                    .thenBy(WorkflowStatus::rank),
            ),
        )
    }

    private fun validateWorkflowStatusName(
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
        mutableWorkspace.value.workflowStatuses.any { status ->
            status.projectId == projectId &&
                status.id != excluding &&
                status.archivedAt == null &&
                status.name.equals(name, ignoreCase = true)
        } -> CommandResult.Rejected(
            RejectionReason.DUPLICATE_WORKFLOW_STATUS_NAME,
            "This workflow already has a status with that name.",
        )
        else -> null
    }

    private fun validateMilestoneName(
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
        mutableWorkspace.value.milestones.any { milestone ->
            milestone.projectId == projectId &&
                milestone.id != excluding &&
                milestone.name.equals(name, ignoreCase = true)
        } -> CommandResult.Rejected(
            RejectionReason.DUPLICATE_MILESTONE_NAME,
            "This project already has a milestone with that name.",
        )
        else -> null
    }

    private fun SemanticStatus.readableCategory(): String =
        name.lowercase(Locale.ROOT).replace('_', ' ')

    private fun publish(
        tasks: List<Task>,
        projects: List<Project> = mutableWorkspace.value.projects,
        workflowStatuses: List<WorkflowStatus> = mutableWorkspace.value.workflowStatuses,
        tags: List<Tag> = mutableWorkspace.value.tags,
        reminders: List<Reminder> = mutableWorkspace.value.reminders,
        milestones: List<Milestone> = mutableWorkspace.value.milestones,
        timeEntries: List<TimeEntry> = mutableWorkspace.value.timeEntries,
        notes: List<Note> = mutableWorkspace.value.notes,
        attachments: List<Attachment> = mutableWorkspace.value.attachments,
        activityEntries: List<ActivityEntry> = mutableWorkspace.value.activityEntries,
        retiredBlobSets: List<RetiredBlobSet> = mutableWorkspace.value.retiredBlobSets,
        savedViews: List<SavedView> = mutableWorkspace.value.savedViews,
        automationRules: List<AutomationRule> = mutableWorkspace.value.automationRules,
        myDay: List<MyDayEntry> = mutableWorkspace.value.myDay,
        at: Instant = now(),
    ) {
        val current = mutableWorkspace.value
        val home = current.home.copy(
            projects = projects.filter { it.archivedAt == null },
            myDayTasks = myDay
                .sortedWith(
                    compareBy<MyDayEntry>(MyDayEntry::rank).thenBy { it.taskId.value },
                )
                .mapNotNull { entry -> tasks.firstOrNull { it.id == entry.taskId } }
                .filter { it.deletedAt == null },
        )
        mutableWorkspace.value = current.copy(
            home = home,
            tasks = tasks,
            projects = projects,
            workflowStatuses = workflowStatuses.sortedWith(
                compareBy<WorkflowStatus> { it.projectId?.value.orEmpty() }
                    .thenBy(WorkflowStatus::rank),
            ),
            tags = tags,
            reminders = reminders.sortedBy { it.triggerAt.instant },
            milestones = milestones.sortedWith(
                compareBy<Milestone> { it.completedAt != null }
                    .thenBy { it.dueDate == null }
                    .thenBy(Milestone::dueDate)
                    .thenBy(Milestone::name),
            ),
            notes = notes,
            attachments = attachments,
            activityEntries = activityEntries,
            retiredBlobSets = retiredBlobSets.sortedWith(
                compareBy<RetiredBlobSet> { it.retiredAt }.thenBy { it.blobSetId.value },
            ),
            savedViews = savedViews.sortedWith(
                compareBy<SavedView> { it.name }.thenBy { it.id.value },
            ),
            automationRules = automationRules,
            myDay = myDay.sortedWith(
                compareBy<MyDayEntry>(MyDayEntry::rank).thenBy { it.taskId.value },
            ),
        ).withResolvedDependencyState(rebuildHomeTaskLists = true)
            .withReconciledTimeState(at = at, entries = timeEntries)
    }

    private fun recordActivity(
        taskId: TaskId?,
        projectId: ProjectId?,
        kind: ActivityKind,
        body: String,
        at: Instant,
        id: String = UUID.randomUUID().toString(),
    ): String {
        val current = mutableWorkspace.value
        mutableWorkspace.value = current.copy(
            activityEntries = current.activityEntries.appendedActivity(
                id = id,
                taskId = taskId,
                projectId = projectId,
                kind = kind,
                body = body,
                at = at,
            ),
        )
        return id
    }

    private fun List<ActivityEntry>.appendedActivity(
        id: String = UUID.randomUUID().toString(),
        taskId: TaskId?,
        projectId: ProjectId?,
        kind: ActivityKind,
        body: String,
        at: Instant,
    ): List<ActivityEntry> {
        val entry = ActivityEntry(
            id = id,
            taskId = taskId,
            projectId = projectId,
            kind = kind,
            body = body.take(MAX_ACTIVITY_BODY_LENGTH),
            createdAt = at,
        )
        val entries = this + entry
        val ownerEntries = entries.filter {
            if (taskId != null) it.taskId == taskId else it.taskId == null && it.projectId == projectId
        }
        val idsToRemove = ownerEntries
            .sortedWith(compareBy<ActivityEntry>(ActivityEntry::createdAt).thenBy(ActivityEntry::id))
            .take((ownerEntries.size - MAX_ACTIVITY_ENTRIES_PER_OWNER).coerceAtLeast(0))
            .mapTo(hashSetOf(), ActivityEntry::id)
        return entries
            .filterNot { it.id in idsToRemove }
            .sortedWith(compareBy<ActivityEntry>(ActivityEntry::createdAt).thenBy(ActivityEntry::id))
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
        reminders: List<Reminder>,
    ): Reminder? {
        val reminder = reminders.firstOrNull { it.taskId == currentTask.id } ?: return null
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

    private companion object {
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
        const val MAX_AUTOMATION_RULES = 20
        val CONTENT_HASH_REGEX = Regex("[0-9a-f]{64}")
    }
}

private fun WorkspaceSnapshot.withResolvedDependencyState(
    rebuildHomeTaskLists: Boolean = false,
): WorkspaceSnapshot {
    val resolvedTasks = resolveDependencyState(tasks)
    val tasksById = resolvedTasks.associateBy(Task::id)
    val resolvedMyDayTasks = home.myDayTasks
        .mapNotNull { tasksById[it.id] }
        .filter { it.deletedAt == null }
    val resolvedHome = if (rebuildHomeTaskLists) {
        val activeTasks = resolvedTasks.filter { it.deletedAt == null }
        home.copy(
            focusTasks = activeTasks.filterNot(Task::isCompleted).take(3),
            upcomingTasks = activeTasks.filter { it.start != null || it.due != null }.take(3),
            myDayTasks = resolvedMyDayTasks,
        )
    } else {
        home.copy(
            focusTasks = home.focusTasks.mapNotNull { tasksById[it.id] },
            upcomingTasks = home.upcomingTasks.mapNotNull { tasksById[it.id] },
            myDayTasks = resolvedMyDayTasks,
        )
    }
    return copy(
        home = resolvedHome,
        tasks = resolvedTasks.sortedBy { it.id.value },
    )
}

private fun WorkspaceSnapshot.withReconciledTimeState(
    at: Instant,
    entries: List<TimeEntry> = timeEntries,
    includeLegacyActive: Boolean = false,
): WorkspaceSnapshot {
    val legacyActive = home.activeTimer
    val normalisedEntries = if (
        includeLegacyActive &&
        legacyActive != null &&
        entries.none { it.id == legacyActive.entryId }
    ) {
        entries + TimeEntry(
            id = legacyActive.entryId,
            taskId = legacyActive.taskId,
            deviceId = entries.firstOrNull()?.deviceId ?: DeviceId("in-memory"),
            startedAt = legacyActive.startedAt,
            stoppedAt = null,
        )
    } else {
        entries
    }
    val reconciliation = TimerRules.reconcile(normalisedEntries, at)
    val active = reconciliation.entries
        .filter { it.stoppedAt == null }
        .maxWithOrNull(compareBy(TimeEntry::startedAt).thenBy { it.id.value })
    val task = active?.let { entry -> tasks.firstOrNull { it.id == entry.taskId } }
    val projectName = task
        ?.projectId
        ?.let { projectId -> projects.firstOrNull { it.id == projectId } }
        ?.name
    return copy(
        home = home.copy(
            activeTimer = if (active == null || task == null) {
                null
            } else {
                ActiveTimerSnapshot(
                    entryId = active.id,
                    taskId = task.id,
                    taskTitle = task.title,
                    projectName = projectName,
                    startedAt = active.startedAt,
                    elapsed = Duration.between(active.startedAt, at).coerceAtLeast(Duration.ZERO),
                )
            },
        ),
        timeEntries = reconciliation.entries,
        timeEntryConflicts = reconciliation.conflicts.map { conflict ->
            TimeEntryConflict(
                firstEntryId = conflict.first.id,
                secondEntryId = conflict.second.id,
                overlap = conflict.overlap,
            )
        },
    )
}

private fun WorkspaceSnapshot.hasConflict(entryId: TimeEntryId): Boolean =
    timeEntryConflicts.any { conflict ->
        conflict.firstEntryId == entryId || conflict.secondEntryId == entryId
    }

private fun resolveDependencyState(tasks: List<Task>): List<Task> {
    val tasksById = tasks.associateBy(Task::id)
    return tasks.map { task ->
        val existingDependencies = task.dependencyIds.filterTo(linkedSetOf()) {
            it in tasksById
        }
        task.copy(
            dependencyIds = existingDependencies,
            blockedBy = existingDependencies.filterTo(linkedSetOf()) { dependencyId ->
                tasksById[dependencyId]?.let { !it.isCompleted && it.deletedAt == null } == true
            },
        )
    }
}
