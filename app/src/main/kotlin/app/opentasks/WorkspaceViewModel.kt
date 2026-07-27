package app.opentasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TemplateId
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.reminders.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.time.LocalDate

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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val selectionState = WorkspaceSelectionState(savedStateHandle)
    private val pendingBlocked = MutableStateFlow<PendingBlockedCompletion?>(null)
    private val mutableDependencyFeedback = MutableStateFlow<DependencyFeedback?>(null)
    private val eventChannel = Channel<WorkspaceEvent>(Channel.BUFFERED)
    private val mutableSearchResults = MutableStateFlow<List<SearchResult>>(emptyList())

    val snapshot: StateFlow<WorkspaceSnapshot> = repository.observeWorkspace()
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
