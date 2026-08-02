package app.opentasks.core.data

import app.opentasks.core.data.backup.InMemoryBackupJournal
import app.opentasks.core.data.backup.toBackupRecords
import app.opentasks.core.data.db.TaskTagEntity
import app.opentasks.core.data.db.TombstoneEntity
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DependencyRules
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.domain.RecurrenceSeriesMetadata
import app.opentasks.core.domain.RecurringTaskPlanner
import app.opentasks.core.domain.ProjectTemplatePlanner
import app.opentasks.core.domain.SearchNormalizer
import app.opentasks.core.domain.TrashPolicy
import app.opentasks.core.domain.TimerRules
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.domain.WorkflowMoveDirection
import app.opentasks.core.model.ActiveTimerSnapshot
import app.opentasks.core.model.ActivityEntry
import app.opentasks.core.model.ActivityKind
import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.Milestone
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.Note
import app.opentasks.core.model.NoteId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.Revision
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

class InMemoryVaultRepository internal constructor(
    initial: WorkspaceSnapshot = OpenTasksFixtures.snapshot,
    private val now: () -> Instant = Instant::now,
    private val backupJournal: InMemoryBackupJournal = InMemoryBackupJournal(),
) : VaultRepository {
    private val writeMutex = Mutex()
    private val sourceDeviceId =
        initial.tasks.firstOrNull()?.revision?.deviceId ?: DeviceId("in-memory")
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

    override suspend fun execute(command: DomainCommand): CommandResult = writeMutex.withLock {
        val before = mutableWorkspace.value
        val beforeTombstones = tombstones
        val beforeTaskTags = taskTags
        try {
            val result = dispatch(command)
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
            is DomainCommand.UpdateProject -> updateProject(command)
            is DomainCommand.RestoreProject -> restoreProject(command)
            is DomainCommand.ArchiveProject -> archiveProject(command)
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
            is DomainCommand.InstantiateProjectTemplate -> instantiateProjectTemplate(command)
            is DomainCommand.DeleteTemplate -> deleteTemplate(command)
            is DomainCommand.RestoreTemplate -> restoreTemplate(command)
            is DomainCommand.CreateTask -> createTask(command)
            is DomainCommand.RenameTask -> renameTask(command)
            is DomainCommand.UpdateTask -> updateTaskDetails(command)
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
            is DomainCommand.ReopenTask -> reopenTask(command.taskId)
            is DomainCommand.DeleteTask -> deleteTask(command)
            is DomainCommand.RestoreTask -> restoreTask(command)
            is DomainCommand.PermanentlyDeleteTask -> permanentlyDeleteTask(command)
            is DomainCommand.PurgeExpiredTrash -> purgeExpiredTrash(command)
            is DomainCommand.StartTimer -> startTimer(command)
            DomainCommand.StopTimer -> stopTimer()
            is DomainCommand.AddTimeEntry -> addTimeEntry(command)
            is DomainCommand.UpdateTimeEntry -> updateTimeEntry(command)
            is DomainCommand.DeleteTimeEntry -> deleteTimeEntry(command)
            is DomainCommand.RestoreTimeEntry -> restoreTimeEntry(command)
            is DomainCommand.AddNote -> addNote(command)
            is DomainCommand.UpdateNote -> updateNote(command)
            is DomainCommand.DeleteNote -> deleteNote(command)
            is DomainCommand.RestoreNote -> restoreNote(command)
        }

    override suspend fun search(query: SearchQuery): List<SearchResult> {
        val needle = SearchNormalizer.normalize(query.text)
        if (needle.isBlank()) return emptyList()
        val snapshot = mutableWorkspace.value
        val projectNames = snapshot.projects.associate { it.id to it.name }
        val tagNames = snapshot.tags.associate { it.id to it.name }
        val notesByTask = snapshot.notes.filter { it.taskId != null }.groupBy { it.taskId }
            .mapValues { (_, notes) ->
                notes.sortedWith(compareBy<Note> { it.createdAt }.thenBy { it.id.value })
            }
        val notesByProject = snapshot.notes.filter { it.projectId != null }.groupBy { it.projectId }
            .mapValues { (_, notes) ->
                notes.sortedWith(compareBy<Note> { it.createdAt }.thenBy { it.id.value })
            }
        val attachmentNamesByTask = snapshot.attachments
            .filter { it.deletedAt == null }
            .groupBy { it.taskId }

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
                        notesByTask[task.id].orEmpty().joinToString(" ") { it.body },
                        attachmentNamesByTask[task.id]
                            .orEmpty()
                            .joinToString(" ") { it.displayName },
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
                needle in SearchNormalizer.normalize(
                    "${project.name} ${project.summary} " +
                        notesByProject[project.id].orEmpty().joinToString(" ") { it.body },
                )
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
        val resolvedTasks = resolveDependencyState(current.tasks + tasks)
        val projects = current.projects + created.project
        val activeTasks = resolvedTasks.filter { it.deletedAt == null }
        mutableWorkspace.value = current.copy(
            home = current.home.copy(
                projects = projects.filter { it.archivedAt == null },
                focusTasks = activeTasks.filterNot(Task::isCompleted).take(3),
                upcomingTasks = activeTasks
                    .filter { it.start != null || it.due != null }
                    .take(3),
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
            tasks = resolvedTasks,
            tags = (current.tags + createdTags)
                .sortedBy { it.name.lowercase(Locale.ROOT) },
        )
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
        if (command.template.workspaceId != OpenTasksFixtures.workspaceId) {
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
        val initialStatus = mutableWorkspace.value.workflowStatuses.firstOrNull {
            it.projectId == command.projectId &&
                it.semanticStatus == SemanticStatus.BACKLOG &&
                it.archivedAt == null
        } ?: return CommandResult.Rejected(
            RejectionReason.INVALID_STATE,
            "This project has no active Backlog status.",
        )
        val task = Task(
            id = TaskId.new(),
            workspaceId = OpenTasksFixtures.workspaceId,
            projectId = command.projectId,
            statusId = initialStatus.id,
            semanticStatus = initialStatus.semanticStatus,
            title = title,
            priority = command.priority,
            revision = Revision(DeviceId("local-device"), now().toEpochMilli(), 0),
        )
        val current = mutableWorkspace.value
        publish(current.tasks + task)
        recordActivity(
            taskId = task.id,
            projectId = task.projectId,
            kind = ActivityKind.RECORD_CREATED,
            body = "Created",
            at = now(),
        )
        return CommandResult.Success(
            message = "Task added",
            undo = DomainCommand.DeleteTask(task.id, now()),
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
        val updated = task.copy(
            deletedAt = command.deletedAt,
            revision = nextRevision(task, command.deletedAt),
        )
        publish(
            tasks = current.tasks.replace(updated),
            timeEntries = current.timeEntries.map { entry ->
                if (entry.taskId == task.id && entry.stoppedAt == null) {
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
        return CommandResult.Success(
            message = "Task moved to the Bin",
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
            activityEntries = current.activityEntries.filterNot { it.taskId == task.id },
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
                activityEntries = current.activityEntries.filterNot { it.taskId in expiredIds },
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

    private fun updateTaskDetails(command: DomainCommand.UpdateTask): CommandResult {
        val current = mutableWorkspace.value
        val task = current.tasks.firstOrNull { it.id == command.taskId }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
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
            command.milestoneId != null && requestedMilestone == null ->
                return CommandResult.Rejected(
                    RejectionReason.NOT_FOUND,
                    "That milestone no longer exists.",
                )
            requestedMilestone != null && requestedMilestone.projectId != command.projectId ->
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Choose a milestone from the task's project.",
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
            requestedReminder != null && command.due == null ->
                return CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Add a due date before setting a reminder.",
                )
            requestedReminder != existingReminder &&
                !command.restorePastReminder &&
                requestedReminder?.triggerAt?.instant?.isAfter(now()) == false ->
                return CommandResult.Rejected(
                    RejectionReason.REMINDER_IN_PAST,
                    "Choose a reminder time in the future.",
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
        } ?: return CommandResult.Rejected(
            RejectionReason.INVALID_STATE,
            "The destination workflow has no matching ${task.semanticStatus.readableCategory()} status.",
        )
        if (
            task.title == title &&
            task.description == command.description &&
            task.projectId == command.projectId &&
            task.statusId == targetStatus.id &&
            task.priority == command.priority &&
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
            due = command.due,
            recurrence = recurrence,
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
        publish(current.tasks.replace(updated), current.tags + tag)
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

    private fun restoreNote(command: DomainCommand.RestoreNote): CommandResult {
        val current = mutableWorkspace.value
        if (current.notes.any { it.id == command.note.id }) {
            return CommandResult.Success("Note restored")
        }
        command.note.taskId?.let { taskId ->
            current.tasks.firstOrNull { it.id == taskId && it.deletedAt == null }
                ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        }
        command.note.projectId?.let { projectId ->
            current.projects.firstOrNull { it.id == projectId }
                ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Project no longer exists.")
        }
        mutableWorkspace.value = current.copy(notes = current.notes + command.note)
        return CommandResult.Success(
            message = "Note restored",
            undo = DomainCommand.DeleteNote(command.note.id),
        )
    }

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
        tags: List<Tag> = mutableWorkspace.value.tags,
        reminders: List<Reminder> = mutableWorkspace.value.reminders,
        milestones: List<Milestone> = mutableWorkspace.value.milestones,
        timeEntries: List<TimeEntry> = mutableWorkspace.value.timeEntries,
        notes: List<Note> = mutableWorkspace.value.notes,
        activityEntries: List<ActivityEntry> = mutableWorkspace.value.activityEntries,
        at: Instant = now(),
    ) {
        val current = mutableWorkspace.value
        val resolvedTasks = resolveDependencyState(tasks)
        val activeTasks = resolvedTasks.filter { it.deletedAt == null }
        val home = current.home.copy(
            focusTasks = activeTasks.filterNot(Task::isCompleted).take(3),
            upcomingTasks = activeTasks.filter { it.start != null || it.due != null }.take(3),
        )
        mutableWorkspace.value = current.copy(
            home = home,
            tasks = resolvedTasks,
            tags = tags,
            reminders = reminders.sortedBy { it.triggerAt.instant },
            milestones = milestones.sortedWith(
                compareBy<Milestone> { it.completedAt != null }
                    .thenBy { it.dueDate == null }
                    .thenBy(Milestone::dueDate)
                    .thenBy(Milestone::name),
            ),
            notes = notes,
            activityEntries = activityEntries,
        ).withReconciledTimeState(at = at, entries = timeEntries)
    }

    private fun recordActivity(
        taskId: TaskId?,
        projectId: ProjectId?,
        kind: ActivityKind,
        body: String,
        at: Instant,
    ) {
        val current = mutableWorkspace.value
        val entry = ActivityEntry(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            projectId = projectId,
            kind = kind,
            body = body.take(MAX_ACTIVITY_BODY_LENGTH),
            createdAt = at,
        )
        val entries = current.activityEntries + entry
        val ownerEntries = entries.filter {
            if (taskId != null) it.taskId == taskId else it.taskId == null && it.projectId == projectId
        }
        val idsToRemove = ownerEntries
            .sortedWith(compareBy<ActivityEntry>(ActivityEntry::createdAt).thenBy(ActivityEntry::id))
            .take((ownerEntries.size - MAX_ACTIVITY_ENTRIES_PER_OWNER).coerceAtLeast(0))
            .mapTo(hashSetOf(), ActivityEntry::id)
        mutableWorkspace.value = current.copy(
            activityEntries = entries
                .filterNot { it.id in idsToRemove }
                .sortedWith(compareBy<ActivityEntry>(ActivityEntry::createdAt).thenBy(ActivityEntry::id)),
        )
    }

    private fun Task.toUpdateCommand(
        reminder: Reminder? = null,
    ): DomainCommand.UpdateTask = DomainCommand.UpdateTask(
        taskId = id,
        title = title,
        description = description,
        projectId = projectId,
        priority = priority,
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
        const val MAX_TASK_DEPENDENCIES = 100
        const val MAX_TIME_ENTRY_NOTE_LENGTH = 500
        const val MAX_TIME_ENTRIES_PER_TASK = 10_000
        const val MAX_NOTE_BODY_LENGTH = 10_000
        const val MAX_NOTES_PER_OWNER = 500
        const val MAX_ACTIVITY_ENTRIES_PER_OWNER = 500
        const val MAX_ACTIVITY_BODY_LENGTH = 500
    }
}

private fun WorkspaceSnapshot.withResolvedDependencyState(): WorkspaceSnapshot {
    val resolvedTasks = resolveDependencyState(tasks)
    val tasksById = resolvedTasks.associateBy(Task::id)
    return copy(
        tasks = resolvedTasks,
        home = home.copy(
            focusTasks = home.focusTasks.mapNotNull { tasksById[it.id] },
            upcomingTasks = home.upcomingTasks.mapNotNull { tasksById[it.id] },
        ),
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
                tasksById[dependencyId]?.isCompleted != true
            },
        )
    }
}
