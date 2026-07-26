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
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.WorkspaceSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
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

sealed interface WorkspaceEvent {
    data class Message(
        val text: String,
        val undo: DomainCommand? = null,
    ) : WorkspaceEvent
}

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val pendingBlocked = MutableStateFlow<PendingBlockedCompletion?>(null)
    private val eventChannel = Channel<WorkspaceEvent>(Channel.BUFFERED)
    private val mutableSearchResults = MutableStateFlow<List<SearchResult>>(emptyList())

    val snapshot: StateFlow<WorkspaceSnapshot> = repository.observeWorkspace()
    val searchResults: StateFlow<List<SearchResult>> = mutableSearchResults.asStateFlow()
    val events = eventChannel.receiveAsFlow()

    val selectedTaskId: StateFlow<String?> =
        savedStateHandle.getStateFlow(SELECTED_TASK_ID, null)

    val selectedProjectId: StateFlow<String?> =
        savedStateHandle.getStateFlow(SELECTED_PROJECT_ID, null)

    val pendingBlockedCompletion: StateFlow<PendingBlockedCompletion?> = pendingBlocked.asStateFlow()

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

    private companion object {
        const val SELECTED_TASK_ID = "selectedTaskId"
        const val SELECTED_PROJECT_ID = "selectedProjectId"
    }
}
