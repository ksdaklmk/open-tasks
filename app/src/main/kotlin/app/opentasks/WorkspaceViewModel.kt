package app.opentasks

import android.os.Trace
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
import app.opentasks.core.model.ProjectPresentation
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskArrangement
import app.opentasks.core.model.TaskSortKey
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TemplateId
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.focus.FocusCoordinator
import app.opentasks.focus.FocusPreset
import app.opentasks.focus.FocusSession
import app.opentasks.focus.FocusSessionStore
import app.opentasks.reminders.ReminderScheduler
import app.opentasks.feature.more.InsightsPresentation
import app.opentasks.feature.more.InsightsProjectOption
import app.opentasks.feature.more.InsightsTagOption
import app.opentasks.feature.more.InsightsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

private const val SEARCH_TRACE = "OpenTasks.Search"
private const val INSIGHTS_TRACE = "OpenTasks.Insights"
private val nextSearchTraceCookie = AtomicInteger()

data class WorkspaceUiState(
    val snapshot: WorkspaceSnapshot,
    val selectedTaskId: TaskId?,
    val pendingBlockedCompletion: PendingBlockedCompletion?,
)

data class PendingBlockedCompletion(
    val task: Task,
    val requestedStatusId: WorkflowStatusId?,
)

data class PendingWipMove(
    val task: Task,
    val statusId: WorkflowStatusId,
)

data class PendingSubtaskCompletion(
    val task: Task,
    val requestedStatusId: WorkflowStatusId?,   // null = CompleteTask
    val acknowledgeBlocked: Boolean,
)

data class DependencyFeedback(
    val taskId: TaskId,
    val message: String,
)

data class SubtaskFeedback(
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
    private val focusSessionStore: FocusSessionStore,
    private val focusCoordinator: FocusCoordinator,
    private val viewArrangementStore: ViewArrangementStore,
    insightsTimeProvider: InsightsTimeProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val selectionState = WorkspaceSelectionState(savedStateHandle)
    private val bulkSelectionState = WorkspaceBulkSelectionState(savedStateHandle)
    private val reviewProgressState = WorkspaceReviewProgressState(savedStateHandle)
    private val projectViewState = WorkspaceProjectViewState(savedStateHandle)
    private val pendingBlocked = MutableStateFlow<PendingBlockedCompletion?>(null)
    private val pendingBlockedBulk = MutableStateFlow(false)
    private val pendingWip = MutableStateFlow<PendingWipMove?>(null)
    private val pendingSubtask = MutableStateFlow<PendingSubtaskCompletion?>(null)
    private val pendingSubtaskBulk = MutableStateFlow(false)
    private val mutableDependencyFeedback = MutableStateFlow<DependencyFeedback?>(null)
    private val mutableSubtaskFeedback = MutableStateFlow<SubtaskFeedback?>(null)
    private val eventChannel = Channel<WorkspaceEvent>(Channel.BUFFERED)
    private val workspaceSearchState = WorkspaceSearchState(repository, viewModelScope)

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
    val timeVersion: StateFlow<Long> = workspaceInsightsState.timeVersion
    val searchResults: StateFlow<List<SearchResult>> = workspaceSearchState.results
    val events = eventChannel.receiveAsFlow()

    val selectedTaskId: StateFlow<String?> = selectionState.selectedTaskId

    val selectedProjectId: StateFlow<String?> = selectionState.selectedProjectId

    val bulkSelection: StateFlow<Set<TaskId>> = bulkSelectionState.selection

    val reviewedTaskIds: StateFlow<Set<TaskId>> = reviewProgressState.reviewedTaskIds

    val reviewedProjectIds: StateFlow<Set<ProjectId>> = reviewProgressState.reviewedProjectIds

    val projectWorkbenchViewState: StateFlow<ProjectWorkbenchViewState> = projectViewState.state

    val reviewActionPending: StateFlow<Boolean> = reviewProgressState.actionPending

    val pendingBlockedBulkCompletion: StateFlow<Boolean> = pendingBlockedBulk.asStateFlow()

    val focusSession: StateFlow<FocusSession?> = focusSessionStore.session

    val viewArrangement: StateFlow<ViewArrangementState> = viewArrangementStore.state

    val pendingBlockedCompletion: StateFlow<PendingBlockedCompletion?> = pendingBlocked.asStateFlow()
    val pendingWipMove: StateFlow<PendingWipMove?> = pendingWip.asStateFlow()
    val pendingSubtaskCompletion: StateFlow<PendingSubtaskCompletion?> = pendingSubtask.asStateFlow()
    val pendingSubtaskBulkCompletion: StateFlow<Boolean> = pendingSubtaskBulk.asStateFlow()
    val dependencyFeedback: StateFlow<DependencyFeedback?> =
        mutableDependencyFeedback.asStateFlow()
    val subtaskFeedback: StateFlow<SubtaskFeedback?> = mutableSubtaskFeedback.asStateFlow()

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
        if (selectedTaskId.value != id.value) {
            mutableDependencyFeedback.value = null
            mutableSubtaskFeedback.value = null
        }
        selectionState.selectTask(id)
    }

    fun closeTask() {
        mutableDependencyFeedback.value = null
        mutableSubtaskFeedback.value = null
        selectionState.closeTask()
    }

    fun selectProject(id: ProjectId) {
        selectionState.selectProject(id)
    }

    fun closeProject() {
        selectionState.closeProject()
    }

    fun setProjectPresentation(projectId: ProjectId, value: ProjectPresentation) {
        projectViewState.setProjectPresentation(projectId, value)
    }

    fun setProjectTimelineFirstDate(projectId: ProjectId, value: LocalDate) {
        projectViewState.setProjectTimelineFirstDate(projectId, value)
    }

    fun setProjectTimelineSelection(projectId: ProjectId, taskId: TaskId?) {
        projectViewState.setProjectTimelineSelection(projectId, taskId)
    }

    fun setTasksArrangement(value: TaskArrangement) = viewArrangementStore.saveTasks(value)

    fun setWorkbenchArrangement(projectId: ProjectId, value: TaskArrangement) =
        viewArrangementStore.saveWorkbench(projectId, value)

    fun setBoardSort(projectId: ProjectId, value: TaskSortKey) =
        viewArrangementStore.saveBoardSort(projectId, value)

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
        if (activeTimer?.taskId == task.id) {
            stopTimer(task.id)
        } else {
            execute(DomainCommand.StartTimer(task.id))
        }
    }

    fun stopActiveTimer() {
        val taskId = snapshot.value.home.activeTimer?.taskId
        if (taskId == null) {
            execute(DomainCommand.StopTimer)
        } else {
            stopTimer(taskId)
        }
    }

    /**
     * Starts a focus cycle on [taskId], but only once its timer is genuinely
     * running: a rejected start leaves no session and no alarm behind, so
     * nothing later reconciles a cycle that never began.
     */
    fun startFocus(taskId: TaskId, preset: FocusPreset) {
        viewModelScope.launch {
            when (val result = focusCoordinator.start(taskId, preset, repository)) {
                is CommandResult.Success -> send(result)
                is CommandResult.Rejected ->
                    eventChannel.send(WorkspaceEvent.Message(result.message))
            }
        }
    }

    /**
     * Ends the focus cycle, and with it the timer its own task owns.
     *
     * Dispatched through the coordinator so clearing the session and the
     * owner-checked repository stop share its gate. An idempotent success stays
     * silent; only a refusal is worth telling anyone about.
     */
    fun stopFocus() {
        viewModelScope.launch {
            val result = focusCoordinator.stop(repository) ?: return@launch
            if (result is CommandResult.Rejected) {
                eventChannel.send(WorkspaceEvent.Message(result.message))
            }
        }
    }

    /**
     * Brings a session that ran while this process was away back onto its
     * current phase, through the same ownership decision the boundary alarm
     * uses. A dead process never retroactively edits elapsed time: the timer
     * is only started or stopped as of now.
     */
    fun reconcileFocus() {
        viewModelScope.launch {
            focusCoordinator.reconcile { repository }
        }
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
                    when (result.reason) {
                        RejectionReason.BLOCKED_TASK_WARNING_REQUIRED ->
                            pendingBlocked.value = PendingBlockedCompletion(task, null)
                        RejectionReason.OPEN_SUBTASKS_CONFIRM_REQUIRED ->
                            pendingSubtask.value = PendingSubtaskCompletion(
                                task = task,
                                requestedStatusId = null,
                                acknowledgeBlocked = false,
                            )
                        else -> eventChannel.send(WorkspaceEvent.Message(result.message))
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

    /**
     * `subtaskError` renders unfiltered by selected task (its subject is
     * the acted-on candidate/child, never necessarily the selected/parent
     * task -- see [SubtaskFeedback]), so unlike [dependencyFeedback] it
     * cannot rely on a race-proof render-time filter. [contextTaskId]
     * captures which task's detail pane initiated this call *before* the
     * suspend point, so a resolution that lands after the person has
     * since selected a different task (or closed the pane) never writes
     * -- or wrongly clears -- [mutableSubtaskFeedback] on their behalf.
     */
    fun setTaskParent(taskId: TaskId, parentTaskId: TaskId?) {
        val contextTaskId = selectedTaskId.value
        viewModelScope.launch {
            when (
                val result = repository.execute(
                    DomainCommand.SetTaskParent(taskId, parentTaskId),
                )
            ) {
                is CommandResult.Success -> {
                    if (selectedTaskId.value == contextTaskId) {
                        mutableSubtaskFeedback.value = null
                    }
                    send(result)
                }
                is CommandResult.Rejected ->
                    if (selectedTaskId.value == contextTaskId) {
                        mutableSubtaskFeedback.value = SubtaskFeedback(taskId, result.message)
                    }
            }
        }
    }

    fun clearSubtaskFeedback() {
        mutableSubtaskFeedback.value = null
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
                    when (result.reason) {
                        RejectionReason.BLOCKED_TASK_WARNING_REQUIRED ->
                            pendingBlocked.value = PendingBlockedCompletion(task, statusId)
                        RejectionReason.WIP_LIMIT_CONFIRM_REQUIRED ->
                            pendingWip.value = PendingWipMove(task, statusId)
                        RejectionReason.OPEN_SUBTASKS_CONFIRM_REQUIRED ->
                            pendingSubtask.value = PendingSubtaskCompletion(
                                task = task,
                                requestedStatusId = statusId,
                                acknowledgeBlocked = false,
                            )
                        else -> eventChannel.send(WorkspaceEvent.Message(result.message))
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

    fun toggleBulkSelection(taskId: TaskId) {
        bulkSelectionState.toggle(taskId)
    }

    fun clearBulkSelection() {
        bulkSelectionState.clear()
    }

    /**
     * Executes a bulk command through the ordinary snackbar/Undo event path.
     * Success clears the selection after that event is published; any
     * rejection keeps the selection so the person can adjust and retry.
     */
    fun executeBulk(command: DomainCommand) {
        execute(command) { result ->
            if (result is CommandResult.Success) clearBulkSelection()
        }
    }

    fun completeBulkSelection() {
        val ids = bulkSelection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            when (val result = repository.execute(DomainCommand.CompleteTasks(ids))) {
                is CommandResult.Success -> {
                    send(result)
                    clearBulkSelection()
                }
                is CommandResult.Rejected -> {
                    when (result.reason) {
                        RejectionReason.BLOCKED_TASK_WARNING_REQUIRED ->
                            pendingBlockedBulk.value = true
                        RejectionReason.OPEN_SUBTASKS_CONFIRM_REQUIRED ->
                            pendingSubtaskBulk.value = true
                        else -> eventChannel.send(WorkspaceEvent.Message(result.message))
                    }
                }
            }
        }
    }

    /**
     * Bypasses [executeBulk]'s generic rejection handling the same way
     * [confirmBlockedCompletion] bypasses [execute]: the per-task bulk
     * preflight checks blocked before open subtasks, so acknowledging
     * blocked here can itself surface [RejectionReason.OPEN_SUBTASKS_CONFIRM_REQUIRED]
     * for a later task in the selection, which must open the subtask-bulk
     * dialog rather than fall through to a dead-end snackbar.
     */
    fun confirmBlockedBulkCompletion() {
        if (!pendingBlockedBulk.value) return
        pendingBlockedBulk.value = false
        viewModelScope.launch {
            val result = repository.execute(
                DomainCommand.CompleteTasks(
                    taskIds = bulkSelection.value.toList(),
                    acknowledgeBlocked = true,
                ),
            )
            when (result) {
                is CommandResult.Success -> {
                    send(result)
                    clearBulkSelection()
                }
                is CommandResult.Rejected -> {
                    if (result.reason == RejectionReason.OPEN_SUBTASKS_CONFIRM_REQUIRED) {
                        pendingSubtaskBulk.value = true
                    } else {
                        eventChannel.send(WorkspaceEvent.Message(result.message))
                    }
                }
            }
        }
    }

    fun dismissBlockedBulkCompletion() {
        pendingBlockedBulk.value = false
    }

    /**
     * Blocked-ack is unconditionally true here: the per-task preflight order
     * (blocked before open subtasks) means this dialog can only appear after
     * any blocked task in the selection was already confirmed via
     * [confirmBlockedBulkCompletion], or no task was blocked at all -- either
     * way the flag is safe to set and is a no-op for a task that was never
     * blocked.
     */
    fun confirmSubtaskBulkCompletion() {
        if (!pendingSubtaskBulk.value) return
        pendingSubtaskBulk.value = false
        executeBulk(
            DomainCommand.CompleteTasks(
                taskIds = bulkSelection.value.toList(),
                acknowledgeBlocked = true,
                acknowledgeOpenSubtasks = true,
            ),
        )
    }

    fun dismissSubtaskBulkCompletion() {
        pendingSubtaskBulk.value = false
    }

    /**
     * Acknowledging the blocked-task dialog can itself surface the open-
     * subtasks rejection on re-dispatch (the repository checks blocked
     * before open subtasks), so this bypasses the generic [execute] and
     * intercepts [RejectionReason.OPEN_SUBTASKS_CONFIRM_REQUIRED] the same
     * way [completeTask] and [changeTaskStatus] do -- recording
     * `acknowledgeBlocked = true` on the resulting [PendingSubtaskCompletion]
     * so [confirmSubtaskCompletion] can carry it through the final dispatch.
     */
    fun confirmBlockedCompletion() {
        val pending = pendingBlocked.value ?: return
        pendingBlocked.value = null
        viewModelScope.launch {
            val command = pending.requestedStatusId?.let { statusId ->
                DomainCommand.ChangeTaskStatus(
                    taskId = pending.task.id,
                    statusId = statusId,
                    acknowledgeBlocked = true,
                )
            } ?: DomainCommand.CompleteTask(
                taskId = pending.task.id,
                acknowledgeBlocked = true,
            )
            when (val result = repository.execute(command)) {
                is CommandResult.Success -> send(result)
                is CommandResult.Rejected -> {
                    if (result.reason == RejectionReason.OPEN_SUBTASKS_CONFIRM_REQUIRED) {
                        pendingSubtask.value = PendingSubtaskCompletion(
                            task = pending.task,
                            requestedStatusId = pending.requestedStatusId,
                            acknowledgeBlocked = true,
                        )
                    } else {
                        eventChannel.send(WorkspaceEvent.Message(result.message))
                    }
                }
            }
        }
    }

    fun dismissBlockedCompletion() {
        pendingBlocked.value = null
    }

    /**
     * Final dispatch once open subtasks are acknowledged, carrying forward
     * whatever [PendingSubtaskCompletion.acknowledgeBlocked] recorded --
     * WIP only ever applies to a non-completing move, so no further
     * confirmation can follow this one.
     */
    fun confirmSubtaskCompletion() {
        val pending = pendingSubtask.value ?: return
        pendingSubtask.value = null
        execute(
            pending.requestedStatusId?.let { statusId ->
                DomainCommand.ChangeTaskStatus(
                    taskId = pending.task.id,
                    statusId = statusId,
                    acknowledgeBlocked = pending.acknowledgeBlocked,
                    acknowledgeOpenSubtasks = true,
                )
            } ?: DomainCommand.CompleteTask(
                taskId = pending.task.id,
                acknowledgeBlocked = pending.acknowledgeBlocked,
                acknowledgeOpenSubtasks = true,
            ),
        )
    }

    fun dismissSubtaskCompletion() {
        pendingSubtask.value = null
    }

    fun confirmWipMove() {
        val pending = pendingWip.value ?: return
        pendingWip.value = null
        execute(
            DomainCommand.ChangeTaskStatus(
                taskId = pending.task.id,
                statusId = pending.statusId,
                acknowledgeWipLimit = true,
            ),
        )
    }

    fun dismissWipMove() {
        pendingWip.value = null
    }

    /**
     * [onResult] runs after the ordinary snackbar event has been published, so
     * a caller that needs to know how a command landed -- starting a focus
     * cycle, say -- adds a reaction without changing what anyone sees, and
     * every existing caller keeps its single-argument form.
     */
    fun execute(command: DomainCommand, onResult: (CommandResult) -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.execute(command)
            when (result) {
                is CommandResult.Success -> send(result)
                is CommandResult.Rejected ->
                    eventChannel.send(WorkspaceEvent.Message(result.message))
            }
            onResult(result)
        }
    }

    fun startReview() {
        reviewProgressState.startReview()
    }

    fun finishReview() {
        reviewProgressState.finishReview()
    }

    fun executeReview(command: DomainCommand, taskId: TaskId? = null, projectId: ProjectId? = null) {
        if (reviewProgressState.actionPending.value) return
        reviewProgressState.setActionPending(true)
        execute(command) { result ->
            if (result is CommandResult.Success) reviewProgressState.markReviewed(taskId, projectId)
            reviewProgressState.setActionPending(false)
        }
    }

    fun search(query: SearchQuery) {
        workspaceSearchState.search(query)
    }

    fun clearSearch() {
        workspaceSearchState.clear()
    }

    private fun stopTimer(taskId: TaskId) {
        viewModelScope.launch {
            when (val result = focusCoordinator.stopTimer(taskId, repository)) {
                is CommandResult.Success -> send(result)
                is CommandResult.Rejected ->
                    eventChannel.send(WorkspaceEvent.Message(result.message))
            }
        }
    }

    private suspend fun send(result: CommandResult.Success) {
        eventChannel.send(WorkspaceEvent.Message(result.message, result.undo))
    }

}

internal class WorkspaceSearchState(
    private val repository: VaultRepository,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutableResults = MutableStateFlow<List<SearchResult>>(emptyList())
    private var searchJob: Job? = null

    val results: StateFlow<List<SearchResult>> = mutableResults.asStateFlow()

    fun search(query: SearchQuery) {
        searchJob?.cancel()
        searchJob = scope.launch {
            val traceCookie = nextSearchTraceCookie.incrementAndGet()
            Trace.beginAsyncSection(SEARCH_TRACE, traceCookie)
            try {
                val result = withContext(dispatcher) { repository.search(query) }
                ensureActive()
                mutableResults.value = result
            } finally {
                Trace.endAsyncSection(SEARCH_TRACE, traceCookie)
            }
        }
    }

    fun clear() {
        searchJob?.cancel()
        mutableResults.value = emptyList()
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
    private val projectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val selectionState = InsightsSelectionSavedState(savedStateHandle)
    private var timeContext = timeProvider.capture()
    private val initialProjection = project(
        workspace = workspace.value,
        selection = selectionState.selection.value,
        presentation = selectionState.presentation.value,
        context = timeContext,
    )
    private val mutableSummary = MutableStateFlow(initialProjection.summary)
    private val mutableUiState = MutableStateFlow(initialProjection.uiState)
    private val mutableTimeVersion = MutableStateFlow(0L)
    private var scheduleJob: Job? = null
    private var projectionJob: Job? = null
    private var foregrounded = false

    val summary: StateFlow<InsightsSnapshot> = mutableSummary.asStateFlow()
    val uiState: StateFlow<InsightsUiState> = mutableUiState.asStateFlow()
    val timeVersion: StateFlow<Long> = mutableTimeVersion.asStateFlow()

    init {
        scope.launch {
            workspace.drop(1).collect {
                if (foregrounded) {
                    refreshInsightsTime()
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
        reproject()
    }

    fun setForegrounded(foregrounded: Boolean) {
        if (this.foregrounded == foregrounded) return
        this.foregrounded = foregrounded
        if (foregrounded) {
            refreshInsightsTime()
        } else {
            scheduleJob?.cancel()
            scheduleJob = null
            projectionJob?.cancel()
            projectionJob = null
        }
    }

    private fun refreshInsightsTime() {
        timeContext = timeProvider.capture()
        mutableTimeVersion.value++
        reproject()
        scheduleNextRefresh()
    }

    private fun reproject() {
        val projectionWorkspace = workspace.value
        val projectionSelection = selectionState.selection.value
        val projectionPresentation = selectionState.presentation.value
        val projectionContext = timeContext
        projectionJob?.cancel()
        projectionJob = scope.launch {
            val projection = withContext(projectionDispatcher) {
                Trace.beginSection(INSIGHTS_TRACE)
                try {
                    project(
                        workspace = projectionWorkspace,
                        selection = projectionSelection,
                        presentation = projectionPresentation,
                        context = projectionContext,
                    )
                } finally {
                    Trace.endSection()
                }
            }
            ensureActive()
            mutableSummary.value = projection.summary
            mutableUiState.value = projection.uiState
        }
    }

    private fun refreshSelection() {
        reproject()
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

    private fun project(
        workspace: WorkspaceSnapshot,
        selection: InsightsSelection,
        presentation: InsightsPresentation,
        context: InsightsTimeContext,
    ): InsightsProjection {
        val projectIds = workspace.projects.mapTo(hashSetOf(), Project::id)
        val tagIds = workspace.tags.mapTo(hashSetOf()) { it.id }
        val effectiveSelection = selection.copy(
            projectIds = selection.projectIds.intersect(projectIds),
            tagIds = selection.tagIds.intersect(tagIds),
        )
        val summary = calculate(workspace, InsightsSelection(), context)
        val selected = if (effectiveSelection == InsightsSelection()) {
            summary
        } else {
            calculate(workspace, effectiveSelection, context)
        }
        return InsightsProjection(
            summary = summary,
            uiState = InsightsUiState(
                snapshot = selected,
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
            ),
        )
    }

    private fun scheduleNextRefresh() {
        scheduleJob?.cancel()
        scheduleJob = null
        if (!foregrounded) return
        val nextRefresh = nextInsightsRefresh(workspace.value, timeContext)
        val nextJob = scope.launch(start = CoroutineStart.LAZY) {
            timeProvider.awaitUntil(nextRefresh)
            if (currentCoroutineContext().isActive && foregrounded) {
                scheduleJob = null
                refreshInsightsTime()
            }
        }
        scheduleJob = nextJob
        nextJob.start()
    }

    private data class InsightsProjection(
        val summary: InsightsSnapshot,
        val uiState: InsightsUiState,
    )
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

internal class WorkspaceReviewProgressState(
    private val savedStateHandle: SavedStateHandle,
) {
    private val mutableReviewedTaskIds = MutableStateFlow(restoredTaskIds())
    private val mutableReviewedProjectIds = MutableStateFlow(restoredProjectIds())
    private val mutableActionPending = MutableStateFlow(false)

    val reviewedTaskIds: StateFlow<Set<TaskId>> = mutableReviewedTaskIds.asStateFlow()
    val reviewedProjectIds: StateFlow<Set<ProjectId>> = mutableReviewedProjectIds.asStateFlow()
    val actionPending: StateFlow<Boolean> = mutableActionPending.asStateFlow()

    fun markReviewed(taskId: TaskId?, projectId: ProjectId?) {
        if (taskId != null) replaceTasks(mutableReviewedTaskIds.value + taskId)
        if (projectId != null) replaceProjects(mutableReviewedProjectIds.value + projectId)
    }

    fun setActionPending(value: Boolean) {
        mutableActionPending.value = value
    }

    fun startReview() = clear()

    fun finishReview() = clear()

    private fun clear() {
        replaceTasks(emptySet())
        replaceProjects(emptySet())
        mutableActionPending.value = false
    }

    private fun replaceTasks(ids: Set<TaskId>) {
        mutableReviewedTaskIds.value = ids
        savedStateHandle[REVIEWED_TASK_IDS] = ids.map(TaskId::value).toCollection(ArrayList())
    }

    private fun replaceProjects(ids: Set<ProjectId>) {
        mutableReviewedProjectIds.value = ids
        savedStateHandle[REVIEWED_PROJECT_IDS] = ids.map(ProjectId::value).toCollection(ArrayList())
    }

    private fun restoredTaskIds(): Set<TaskId> =
        restored(REVIEWED_TASK_IDS).mapTo(linkedSetOf(), ::TaskId)

    private fun restoredProjectIds(): Set<ProjectId> =
        restored(REVIEWED_PROJECT_IDS).mapTo(linkedSetOf(), ::ProjectId)

    private fun restored(key: String): List<String> =
        (savedStateHandle.get<Any?>(key) as? List<*>)
            .orEmpty()
            .mapNotNull { it as? String }
            .filter(String::isNotBlank)
            .distinct()

    internal companion object {
        const val REVIEWED_TASK_IDS = "reviewedTaskIds"
        const val REVIEWED_PROJECT_IDS = "reviewedProjectIds"
    }
}

data class ProjectWorkbenchViewState(
    val presentationByProject: Map<ProjectId, ProjectPresentation> = emptyMap(),
    val timelineFirstDateByProject: Map<ProjectId, LocalDate> = emptyMap(),
    val selectedTimelineTaskByProject: Map<ProjectId, TaskId> = emptyMap(),
)

/**
 * Per-project planning state: presentation (LIST/BOARD/TIMELINE), the Timeline
 * window anchor, and the Timeline dependency selection. `presentationByProject`
 * carries only non-default (BOARD/TIMELINE) entries; an absent project is LIST.
 * [PROJECT_BOARD_MODE_IDS] is retained verbatim so state saved before Timeline
 * existed keeps restoring as BOARD.
 */
internal class WorkspaceProjectViewState(
    private val savedStateHandle: SavedStateHandle,
) {
    private val mutableState = MutableStateFlow(restoredState())

    val state: StateFlow<ProjectWorkbenchViewState> = mutableState.asStateFlow()

    fun setProjectPresentation(projectId: ProjectId, value: ProjectPresentation) {
        val presentationByProject = if (value == ProjectPresentation.LIST) {
            mutableState.value.presentationByProject - projectId
        } else {
            mutableState.value.presentationByProject + (projectId to value)
        }
        replace(mutableState.value.copy(presentationByProject = presentationByProject))
    }

    fun setProjectTimelineFirstDate(projectId: ProjectId, value: LocalDate) {
        if (value.dayOfWeek != DayOfWeek.MONDAY) return
        replace(
            mutableState.value.copy(
                timelineFirstDateByProject =
                    mutableState.value.timelineFirstDateByProject + (projectId to value),
            ),
        )
    }

    fun setProjectTimelineSelection(projectId: ProjectId, taskId: TaskId?) {
        val selectedTimelineTaskByProject = if (taskId == null) {
            mutableState.value.selectedTimelineTaskByProject - projectId
        } else {
            mutableState.value.selectedTimelineTaskByProject + (projectId to taskId)
        }
        replace(
            mutableState.value.copy(selectedTimelineTaskByProject = selectedTimelineTaskByProject),
        )
    }

    private fun replace(value: ProjectWorkbenchViewState) {
        mutableState.value = value
        savedStateHandle[PROJECT_BOARD_MODE_IDS] = value.presentationByProject
            .filterValues { it == ProjectPresentation.BOARD }
            .keys
            .map(ProjectId::value)
            .toCollection(ArrayList())
        savedStateHandle[PROJECT_TIMELINE_MODE_IDS] = value.presentationByProject
            .filterValues { it == ProjectPresentation.TIMELINE }
            .keys
            .map(ProjectId::value)
            .toCollection(ArrayList())
        savedStateHandle[PROJECT_TIMELINE_ANCHORS] = value.timelineFirstDateByProject
            .flatMap { (projectId, date) -> listOf(projectId.value, date.toEpochDay().toString()) }
            .toCollection(ArrayList())
        savedStateHandle[PROJECT_TIMELINE_SELECTIONS] = value.selectedTimelineTaskByProject
            .flatMap { (projectId, taskId) -> listOf(projectId.value, taskId.value) }
            .toCollection(ArrayList())
    }

    private fun restoredState(): ProjectWorkbenchViewState {
        val presentationByProject = linkedMapOf<ProjectId, ProjectPresentation>()
        restoredIds(PROJECT_BOARD_MODE_IDS).forEach {
            presentationByProject[it] = ProjectPresentation.BOARD
        }
        // Timeline is read second so a project corrupted into both lists lands on Timeline.
        restoredIds(PROJECT_TIMELINE_MODE_IDS).forEach {
            presentationByProject[it] = ProjectPresentation.TIMELINE
        }
        return ProjectWorkbenchViewState(
            presentationByProject = presentationByProject,
            timelineFirstDateByProject = restoredAnchors(),
            selectedTimelineTaskByProject = restoredSelections(),
        )
    }

    private fun restoredIds(key: String): List<ProjectId> =
        (savedStateHandle.get<Any?>(key) as? List<*>)
            .orEmpty()
            .mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }
            .distinct()
            .map(::ProjectId)

    private fun restoredAnchors(): Map<ProjectId, LocalDate> {
        val result = linkedMapOf<ProjectId, LocalDate>()
        restoredPairs(PROJECT_TIMELINE_ANCHORS).forEach { (projectIdValue, epochDayValue) ->
            val projectId = ProjectId(projectIdValue)
            if (projectId in result) return@forEach
            val epochDay = epochDayValue.toLongOrNull() ?: return@forEach
            val date = try {
                LocalDate.ofEpochDay(epochDay)
            } catch (invalid: DateTimeException) {
                return@forEach
            }
            if (date.dayOfWeek != DayOfWeek.MONDAY) return@forEach
            result[projectId] = date
        }
        return result
    }

    private fun restoredSelections(): Map<ProjectId, TaskId> {
        val result = linkedMapOf<ProjectId, TaskId>()
        restoredPairs(PROJECT_TIMELINE_SELECTIONS).forEach { (projectIdValue, taskIdValue) ->
            val projectId = ProjectId(projectIdValue)
            if (projectId !in result) result[projectId] = TaskId(taskIdValue)
        }
        return result
    }

    /**
     * Splits an alternating `[projectId, value, projectId, value, ...]` list into
     * pairs, dropping a non-string entry, a blank entry, or a trailing unpaired
     * entry without throwing. Non-string entries stay in position (as `null`) so
     * a single corrupt element does not shift the remaining pairs.
     */
    private fun restoredPairs(key: String): List<Pair<String, String>> =
        (savedStateHandle.get<Any?>(key) as? List<*>)
            .orEmpty()
            .map { it as? String }
            .chunked(2)
            .mapNotNull { chunk ->
                val id = chunk.getOrNull(0)
                val value = chunk.getOrNull(1)
                if (id.isNullOrBlank() || value.isNullOrBlank()) null else id to value
            }

    internal companion object {
        const val PROJECT_BOARD_MODE_IDS = "projectBoardModeIds"
        const val PROJECT_TIMELINE_MODE_IDS = "projectTimelineModeIds"
        const val PROJECT_TIMELINE_ANCHORS = "projectTimelineAnchors"
        const val PROJECT_TIMELINE_SELECTIONS = "projectTimelineSelections"
    }
}

/**
 * Bulk multi-select state. The exposed value is a set of domain ids, but the
 * [SavedStateHandle] stores a plain `List<String>` under [BULK_SELECTION] so
 * the selection survives process death, mirroring [WorkspaceSelectionState].
 */
internal class WorkspaceBulkSelectionState(
    private val savedStateHandle: SavedStateHandle,
) {
    private val mutableSelection = MutableStateFlow(restoredSelection())

    val selection: StateFlow<Set<TaskId>> = mutableSelection.asStateFlow()

    fun toggle(taskId: TaskId) {
        val current = mutableSelection.value
        replace(if (taskId in current) current - taskId else current + taskId)
    }

    fun clear() {
        replace(emptySet())
    }

    private fun replace(value: Set<TaskId>) {
        mutableSelection.value = value
        savedStateHandle[BULK_SELECTION] = value
            .map(TaskId::value)
            .toCollection(ArrayList())
    }

    private fun restoredSelection(): Set<TaskId> =
        (savedStateHandle.get<Any?>(BULK_SELECTION) as? List<*>)
            .orEmpty()
            .mapNotNull { value -> (value as? String)?.takeIf(String::isNotBlank) }
            .distinct()
            .mapTo(linkedSetOf(), ::TaskId)

    internal companion object {
        const val BULK_SELECTION = "bulkSelection"
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
