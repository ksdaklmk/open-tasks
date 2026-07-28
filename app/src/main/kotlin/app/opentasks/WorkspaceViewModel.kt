package app.opentasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.InsightsEngine
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.InsightsRange
import app.opentasks.core.model.InsightsSelection
import app.opentasks.core.model.InsightsSnapshot
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TemplateId
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.reminders.ReminderScheduler
import app.opentasks.feature.more.InsightsPresentation
import app.opentasks.feature.more.InsightsProjectOption
import app.opentasks.feature.more.InsightsTagOption
import app.opentasks.feature.more.InsightsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

data class WorkspaceUiState(
    val snapshot: WorkspaceSnapshot,
    val selectedTaskId: TaskId?,
    val pendingBlockedCompletion: PendingBlockedCompletion?,
)

data class PendingBlockedCompletion(
    val task: Task,
    val requestedStatusId: WorkflowStatusId?,
)

data class DependencyFeedback(
    val taskId: TaskId,
    val message: String,
)

sealed interface WorkspaceEvent {
    data class Message(
        val text: String,
        val undo: DomainCommand? = null,
    ) : WorkspaceEvent
}

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val reminderScheduler: ReminderScheduler,
    private val insightsEngine: InsightsEngine,
    insightsTimeProvider: InsightsTimeProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val selectionState = WorkspaceSelectionState(savedStateHandle)
    private val pendingBlocked = MutableStateFlow<PendingBlockedCompletion?>(null)
    private val mutableDependencyFeedback = MutableStateFlow<DependencyFeedback?>(null)
    private val eventChannel = Channel<WorkspaceEvent>(Channel.BUFFERED)
    private val mutableSearchResults = MutableStateFlow<List<SearchResult>>(emptyList())

    val snapshot: StateFlow<WorkspaceSnapshot> = repository.observeWorkspace()
    private val workspaceInsightsState = WorkspaceInsightsState(
        workspace = snapshot,
        savedStateHandle = savedStateHandle,
        insightsEngine = insightsEngine,
        timeProvider = insightsTimeProvider,
        scope = viewModelScope,
    )
    val insightsSummary: StateFlow<InsightsSnapshot> = workspaceInsightsState.summary
    val insightsUiState: StateFlow<InsightsUiState> = workspaceInsightsState.uiState
    val searchResults: StateFlow<List<SearchResult>> = mutableSearchResults.asStateFlow()
    val events = eventChannel.receiveAsFlow()

    val selectedTaskId: StateFlow<String?> = selectionState.selectedTaskId

    val selectedProjectId: StateFlow<String?> = selectionState.selectedProjectId

    val pendingBlockedCompletion: StateFlow<PendingBlockedCompletion?> = pendingBlocked.asStateFlow()
    val dependencyFeedback: StateFlow<DependencyFeedback?> =
        mutableDependencyFeedback.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeWorkspace()
                .map { snapshot ->
                    ReminderReconciliationKey(
                        reminders = snapshot.reminders,
                        taskStates = snapshot.tasks.map { task ->
                            ReminderTaskState(
                                id = task.id,
                                completed = task.isCompleted,
                                deleted = task.deletedAt != null,
                            )
                        },
                    )
                }
                .distinctUntilChanged()
                .collect {
                    reminderScheduler.reconcile(repository.observeWorkspace().value)
                }
        }
    }

    fun selectTask(id: TaskId) {
        if (selectedTaskId.value != id.value) mutableDependencyFeedback.value = null
        selectionState.selectTask(id)
    }

    fun closeTask() {
        mutableDependencyFeedback.value = null
        selectionState.closeTask()
    }

    fun selectProject(id: ProjectId) {
        selectionState.selectProject(id)
    }

    fun closeProject() {
        selectionState.closeProject()
    }

    fun setInsightsRange(range: InsightsRange) {
        workspaceInsightsState.setRange(range)
    }

    fun setInsightsProjectFilter(id: ProjectId, selected: Boolean) {
        if (snapshot.value.projects.none { it.id == id }) return
        workspaceInsightsState.setProjectFilter(id, selected)
    }

    fun setInsightsTagFilter(id: TagId, selected: Boolean) {
        if (snapshot.value.tags.none { it.id == id }) return
        workspaceInsightsState.setTagFilter(id, selected)
    }

    fun setInsightsIncludeConflictedTime(include: Boolean) {
        workspaceInsightsState.setIncludeConflictedTime(include)
    }

    fun setInsightsPresentation(presentation: InsightsPresentation) {
        workspaceInsightsState.setPresentation(presentation)
    }

    fun setInsightsForegrounded(foregrounded: Boolean) {
        workspaceInsightsState.setForegrounded(foregrounded)
    }

    fun addTask(title: String) {
        execute(DomainCommand.CreateTask(title))
    }

    fun addProject(name: String, summary: String) {
        val projectId = ProjectId.new()
        viewModelScope.launch {
            when (
                val result = repository.execute(
                    DomainCommand.CreateProject(
                        projectId = projectId,
                        name = name,
                        summary = summary,
                    ),
                )
            ) {
                is CommandResult.Success -> {
                    selectProject(projectId)
                    send(result)
                }
                is CommandResult.Rejected ->
                    eventChannel.send(WorkspaceEvent.Message(result.message))
            }
        }
    }

    fun addProjectFromTemplate(
        templateId: TemplateId,
        name: String,
        anchorDate: LocalDate,
        onCreated: () -> Unit,
    ) {
        val projectId = ProjectId.new()
        viewModelScope.launch {
            when (
                val result = repository.execute(
                    DomainCommand.InstantiateProjectTemplate(
                        templateId = templateId,
                        projectId = projectId,
                        projectName = name,
                        anchorDate = anchorDate,
                    ),
                )
            ) {
                is CommandResult.Success -> {
                    selectProject(projectId)
                    send(result)
                    onCreated()
                }
                is CommandResult.Rejected ->
                    eventChannel.send(WorkspaceEvent.Message(result.message))
            }
        }
    }

    fun archiveProject(project: Project) {
        viewModelScope.launch {
            when (val result = repository.execute(DomainCommand.ArchiveProject(project.id))) {
                is CommandResult.Success -> {
                    closeProject()
                    send(result)
                }
                is CommandResult.Rejected ->
                    eventChannel.send(WorkspaceEvent.Message(result.message))
            }
        }
    }

    fun toggleTimer(task: Task) {
        val activeTimer = snapshot.value.home.activeTimer
        execute(
            if (activeTimer?.taskId == task.id) {
                DomainCommand.StopTimer
            } else {
                DomainCommand.StartTimer(task.id)
            },
        )
    }

    fun stopActiveTimer() {
        execute(DomainCommand.StopTimer)
    }

    fun completeTask(task: Task) {
        if (task.isCompleted) {
            execute(DomainCommand.ReopenTask(task.id))
            return
        }
        viewModelScope.launch {
            when (val result = repository.execute(DomainCommand.CompleteTask(task.id))) {
                is CommandResult.Success -> send(result)
                is CommandResult.Rejected -> {
                    if (result.reason == RejectionReason.BLOCKED_TASK_WARNING_REQUIRED) {
                        pendingBlocked.value = PendingBlockedCompletion(task, null)
                    } else {
                        eventChannel.send(WorkspaceEvent.Message(result.message))
                    }
                }
            }
        }
    }

    fun setTaskDependency(
        taskId: TaskId,
        dependsOnTaskId: TaskId,
        present: Boolean,
    ) {
        viewModelScope.launch {
            when (
                val result = repository.execute(
                    DomainCommand.SetTaskDependency(taskId, dependsOnTaskId, present),
                )
            ) {
                is CommandResult.Success -> {
                    mutableDependencyFeedback.value = null
                    send(result)
                }
                is CommandResult.Rejected -> {
                    mutableDependencyFeedback.value = DependencyFeedback(
                        taskId = taskId,
                        message = result.message,
                    )
                }
            }
        }
    }

    fun clearDependencyFeedback() {
        mutableDependencyFeedback.value = null
    }

    fun changeTaskStatus(task: Task, statusId: WorkflowStatusId) {
        viewModelScope.launch {
            when (
                val result = repository.execute(
                    DomainCommand.ChangeTaskStatus(task.id, statusId),
                )
            ) {
                is CommandResult.Success -> send(result)
                is CommandResult.Rejected -> {
                    if (result.reason == RejectionReason.BLOCKED_TASK_WARNING_REQUIRED) {
                        pendingBlocked.value = PendingBlockedCompletion(task, statusId)
                    } else {
                        eventChannel.send(WorkspaceEvent.Message(result.message))
                    }
                }
            }
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            when (val result = repository.execute(DomainCommand.DeleteTask(task.id))) {
                is CommandResult.Success -> {
                    closeTask()
                    send(result)
                }
                is CommandResult.Rejected ->
                    eventChannel.send(WorkspaceEvent.Message(result.message))
            }
        }
    }

    fun confirmBlockedCompletion() {
        val pending = pendingBlocked.value ?: return
        pendingBlocked.value = null
        execute(
            pending.requestedStatusId?.let { statusId ->
                DomainCommand.ChangeTaskStatus(
                    taskId = pending.task.id,
                    statusId = statusId,
                    acknowledgeBlocked = true,
                )
            } ?: DomainCommand.CompleteTask(
                taskId = pending.task.id,
                acknowledgeBlocked = true,
            ),
        )
    }

    fun dismissBlockedCompletion() {
        pendingBlocked.value = null
    }

    fun execute(command: DomainCommand) {
        viewModelScope.launch {
            when (val result = repository.execute(command)) {
                is CommandResult.Success -> send(result)
                is CommandResult.Rejected ->
                    eventChannel.send(WorkspaceEvent.Message(result.message))
            }
        }
    }

    fun search(text: String) {
        viewModelScope.launch {
            mutableSearchResults.value = if (text.isBlank()) {
                emptyList()
            } else {
                repository.search(SearchQuery(text))
            }
        }
    }

    fun clearSearch() {
        mutableSearchResults.value = emptyList()
    }

    private suspend fun send(result: CommandResult.Success) {
        eventChannel.send(WorkspaceEvent.Message(result.message, result.undo))
    }

}

data class InsightsTimeContext(
    val now: Instant,
    val zoneId: ZoneId,
)

interface InsightsTimeProvider {
    fun capture(): InsightsTimeContext

    suspend fun awaitUntil(instant: Instant)
}

internal class SystemInsightsTimeProvider : InsightsTimeProvider {
    override fun capture(): InsightsTimeContext = InsightsTimeContext(
        now = Instant.now(),
        zoneId = ZoneId.systemDefault(),
    )

    override suspend fun awaitUntil(instant: Instant) {
        val remaining = Duration.between(Instant.now(), instant)
        if (!remaining.isNegative && !remaining.isZero) {
            delay(remaining.toMillis().coerceAtLeast(1L))
        }
    }
}

internal class WorkspaceInsightsState(
    private val workspace: StateFlow<WorkspaceSnapshot>,
    savedStateHandle: SavedStateHandle,
    private val insightsEngine: InsightsEngine,
    private val timeProvider: InsightsTimeProvider,
    private val scope: CoroutineScope,
) {
    private val selectionState = InsightsSelectionSavedState(savedStateHandle)
    private var timeContext = timeProvider.capture()
    private val mutableSummary = MutableStateFlow(
        calculate(workspace.value, InsightsSelection(), timeContext),
    )
    private val mutableUiState = MutableStateFlow(
        buildUiState(
            workspace = workspace.value,
            selection = selectionState.selection.value,
            presentation = selectionState.presentation.value,
            context = timeContext,
        ),
    )
    private var scheduleJob: Job? = null
    private var foregrounded = false

    val summary: StateFlow<InsightsSnapshot> = mutableSummary.asStateFlow()
    val uiState: StateFlow<InsightsUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            workspace.drop(1).collect {
                if (foregrounded) {
                    refreshInsightsTime()
                } else {
                    reproject()
                }
            }
        }
    }

    fun setRange(range: InsightsRange) {
        selectionState.setRange(range)
        refreshSelection()
    }

    fun setProjectFilter(id: ProjectId, selected: Boolean) {
        selectionState.setProjectFilter(id, selected)
        refreshSelection()
    }

    fun setTagFilter(id: TagId, selected: Boolean) {
        selectionState.setTagFilter(id, selected)
        refreshSelection()
    }

    fun setIncludeConflictedTime(include: Boolean) {
        selectionState.setIncludeConflictedTime(include)
        refreshSelection()
    }

    fun setPresentation(presentation: InsightsPresentation) {
        selectionState.setPresentation(presentation)
        mutableUiState.value = mutableUiState.value.copy(presentation = presentation)
    }

    fun setForegrounded(foregrounded: Boolean) {
        if (this.foregrounded == foregrounded) return
        this.foregrounded = foregrounded
        if (foregrounded) {
            refreshInsightsTime()
        } else {
            scheduleJob?.cancel()
            scheduleJob = null
        }
    }

    private fun refreshInsightsTime() {
        timeContext = timeProvider.capture()
        reproject()
        scheduleNextRefresh()
    }

    private fun reproject() {
        val currentWorkspace = workspace.value
        mutableSummary.value = calculate(currentWorkspace, InsightsSelection(), timeContext)
        mutableUiState.value = buildUiState(
            workspace = currentWorkspace,
            selection = selectionState.selection.value,
            presentation = selectionState.presentation.value,
            context = timeContext,
        )
    }

    private fun refreshSelection() {
        mutableUiState.value = buildUiState(
            workspace = workspace.value,
            selection = selectionState.selection.value,
            presentation = selectionState.presentation.value,
            context = timeContext,
        )
    }

    private fun calculate(
        workspace: WorkspaceSnapshot,
        selection: InsightsSelection,
        context: InsightsTimeContext,
    ): InsightsSnapshot = insightsEngine.calculate(
        workspace = workspace,
        selection = selection,
        now = context.now,
        zoneId = context.zoneId,
    )

    private fun buildUiState(
        workspace: WorkspaceSnapshot,
        selection: InsightsSelection,
        presentation: InsightsPresentation,
        context: InsightsTimeContext,
    ): InsightsUiState {
        val projectIds = workspace.projects.mapTo(hashSetOf(), Project::id)
        val tagIds = workspace.tags.mapTo(hashSetOf()) { it.id }
        val effectiveSelection = selection.copy(
            projectIds = selection.projectIds.intersect(projectIds),
            tagIds = selection.tagIds.intersect(tagIds),
        )
        return InsightsUiState(
            snapshot = calculate(workspace, effectiveSelection, context),
            selection = effectiveSelection,
            presentation = presentation,
            projectOptions = workspace.projects
                .map { InsightsProjectOption(it.id, it.name) }
                .sortedWith(
                    compareBy<InsightsProjectOption> {
                        it.displayName.lowercase(Locale.ROOT)
                    }.thenBy { it.id.value },
                ),
            tagOptions = workspace.tags
                .map { InsightsTagOption(it.id, it.name) }
                .sortedWith(
                    compareBy<InsightsTagOption> {
                        it.displayName.lowercase(Locale.ROOT)
                    }.thenBy { it.id.value },
                ),
        )
    }

    private fun scheduleNextRefresh() {
        scheduleJob?.cancel()
        scheduleJob = null
        if (!foregrounded) return
        val nextRefresh = nextInsightsRefresh(workspace.value, timeContext)
        scheduleJob = scope.launch {
            timeProvider.awaitUntil(nextRefresh)
            if (currentCoroutineContext().isActive && foregrounded) {
                scheduleJob = null
                refreshInsightsTime()
            }
        }
    }
}

private fun nextInsightsRefresh(
    workspace: WorkspaceSnapshot,
    context: InsightsTimeContext,
): Instant {
    val midnight = context.now
        .atZone(context.zoneId)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(context.zoneId)
        .toInstant()
    val recheck = context.now.plus(INSIGHTS_TIME_RECHECK)
    val dueBoundary = workspace.tasks
        .asSequence()
        .filter { task ->
            task.deletedAt == null &&
                task.semanticStatus != app.opentasks.core.model.SemanticStatus.COMPLETED
        }
        .mapNotNull { task -> task.due?.instant }
        .filter { dueAt -> !dueAt.isBefore(context.now) }
        .mapNotNull { dueAt -> runCatching { dueAt.plusNanos(1L) }.getOrNull() }
        .minOrNull()
    return listOfNotNull(midnight, recheck, dueBoundary).min()
}

private val INSIGHTS_TIME_RECHECK: Duration = Duration.ofMinutes(15)

internal class InsightsSelectionSavedState(
    private val savedStateHandle: SavedStateHandle,
) {
    val selection = MutableStateFlow(
        InsightsSelection(
            range = restoredEnum(
                savedStateHandle.get<Any?>(INSIGHTS_RANGE),
                InsightsRange.SEVEN_DAYS,
            ),
            projectIds = restoredStrings(INSIGHTS_PROJECT_IDS).mapTo(linkedSetOf(), ::ProjectId),
            tagIds = restoredStrings(INSIGHTS_TAG_IDS).mapTo(linkedSetOf(), ::TagId),
            includeConflictedTime =
                savedStateHandle.get<Any?>(INSIGHTS_INCLUDE_CONFLICTED) as? Boolean ?: false,
        ),
    )
    val presentation = MutableStateFlow(
        restoredEnum(
            savedStateHandle.get<Any?>(INSIGHTS_PRESENTATION),
            InsightsPresentation.CHART,
        ),
    )

    fun setRange(range: InsightsRange) {
        replaceSelection(selection.value.copy(range = range))
    }

    fun setProjectFilter(id: ProjectId, selected: Boolean) {
        replaceSelection(
            selection.value.copy(
                projectIds = selection.value.projectIds.withSelection(id, selected),
            ),
        )
    }

    fun setTagFilter(id: TagId, selected: Boolean) {
        replaceSelection(
            selection.value.copy(
                tagIds = selection.value.tagIds.withSelection(id, selected),
            ),
        )
    }

    fun setIncludeConflictedTime(include: Boolean) {
        replaceSelection(selection.value.copy(includeConflictedTime = include))
    }

    fun setPresentation(value: InsightsPresentation) {
        presentation.value = value
        savedStateHandle[INSIGHTS_PRESENTATION] = value.name
    }

    private fun replaceSelection(value: InsightsSelection) {
        selection.value = value
        savedStateHandle[INSIGHTS_RANGE] = value.range.name
        savedStateHandle[INSIGHTS_PROJECT_IDS] = value.projectIds
            .map(ProjectId::value)
            .sorted()
            .toCollection(ArrayList())
        savedStateHandle[INSIGHTS_TAG_IDS] = value.tagIds
            .map(TagId::value)
            .sorted()
            .toCollection(ArrayList())
        savedStateHandle[INSIGHTS_INCLUDE_CONFLICTED] = value.includeConflictedTime
    }

    private fun restoredStrings(key: String): List<String> =
        (savedStateHandle.get<Any?>(key) as? List<*>)
            .orEmpty()
            .mapNotNull { value -> (value as? String)?.takeIf(String::isNotBlank) }
            .distinct()

    private inline fun <reified T : Enum<T>> restoredEnum(
        storedValue: Any?,
        fallback: T,
    ): T = enumValues<T>().firstOrNull { it.name == storedValue as? String } ?: fallback

    private fun <T> Set<T>.withSelection(value: T, selected: Boolean): Set<T> =
        if (selected) this + value else this - value

    internal companion object {
        const val INSIGHTS_RANGE = "insightsRange"
        const val INSIGHTS_PROJECT_IDS = "insightsProjectIds"
        const val INSIGHTS_TAG_IDS = "insightsTagIds"
        const val INSIGHTS_INCLUDE_CONFLICTED = "insightsIncludeConflicted"
        const val INSIGHTS_PRESENTATION = "insightsPresentation"
    }
}

internal class WorkspaceSelectionState(
    private val savedStateHandle: SavedStateHandle,
) {
    val selectedTaskId: StateFlow<String?> =
        savedStateHandle.getStateFlow(SELECTED_TASK_ID, null)

    val selectedProjectId: StateFlow<String?> =
        savedStateHandle.getStateFlow(SELECTED_PROJECT_ID, null)

    fun selectTask(id: TaskId) {
        savedStateHandle[SELECTED_TASK_ID] = id.value
    }

    fun closeTask() {
        savedStateHandle[SELECTED_TASK_ID] = null
    }

    fun selectProject(id: ProjectId) {
        savedStateHandle[SELECTED_PROJECT_ID] = id.value
    }

    fun closeProject() {
        savedStateHandle[SELECTED_PROJECT_ID] = null
    }

    internal companion object {
        const val SELECTED_TASK_ID = "selectedTaskId"
        const val SELECTED_PROJECT_ID = "selectedProjectId"
    }
}

private data class ReminderReconciliationKey(
    val reminders: List<app.opentasks.core.model.Reminder>,
    val taskStates: List<ReminderTaskState>,
)

private data class ReminderTaskState(
    val id: TaskId,
    val completed: Boolean,
    val deleted: Boolean,
)
