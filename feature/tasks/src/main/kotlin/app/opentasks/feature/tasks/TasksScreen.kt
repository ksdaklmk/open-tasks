package app.opentasks.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.EmptyState
import app.opentasks.core.designsystem.SectionHeader
import app.opentasks.core.designsystem.TaskRow
import app.opentasks.core.designsystem.readableName
import app.opentasks.core.model.ActivityEntry
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.Milestone
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.Note
import app.opentasks.core.model.NoteId
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskGroup
import app.opentasks.core.model.TaskGroupKey
import app.opentasks.core.model.TaskGroupValue
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TaskSortKey
import app.opentasks.core.model.TimeEntry
import app.opentasks.core.model.TimeEntryConflict
import app.opentasks.core.model.TimeEntryId
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.ZonedMoment
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

data class TaskEdit(
    val title: String,
    val description: String,
    val projectId: ProjectId?,
    val priority: Priority,
    val due: ZonedMoment?,
    val recurrence: RecurrenceRule?,
    val estimate: Duration?,
    val milestoneId: MilestoneId?,
    val reminder: Reminder? = null,
)

data class TimeEntryEdit(
    val startedAt: Instant,
    val stoppedAt: Instant,
    val note: String,
)

/**
 * The focus cycles this screen can ask for.
 *
 * A module-owned twin of `:app`'s own preset enum, on the `LockDelayOption`
 * precedent: `feature:tasks` depends only on `:core:model` and
 * `:core:designsystem`, so `:app` maps between the two.
 */
enum class FocusPresetOption {
    TWENTY_FIVE_FIVE,
    FIFTY_TEN,
}

private val FOCUS_PRESET_OPTIONS = listOf(
    FocusPresetOption.TWENTY_FIVE_FIVE to R.string.task_focus_preset_twenty_five_five,
    FocusPresetOption.FIFTY_TEN to R.string.task_focus_preset_fifty_ten,
)

private enum class TaskFilter(val label: String) {
    INBOX("Inbox"),
    TODAY("Today"),
    UPCOMING("Upcoming"),
    OVERDUE("Overdue"),
    ALL("All"),
}

private enum class RecurrenceEndMode(val label: String) {
    NEVER("Never"),
    COUNT("After"),
    DATE("On date"),
}

@Composable
fun TasksScreen(
    tasks: List<Task>,
    dueBucketsByTaskId: Map<TaskId, DueBucket> = emptyMap(),
    taskGroups: List<TaskGroup> = listOf(TaskGroup(value = null, tasks = tasks)),
    taskSort: TaskSortKey = TaskSortKey.DUE,
    taskGroupBy: TaskGroupKey? = null,
    onTaskSortChange: (TaskSortKey) -> Unit = {},
    onTaskGroupChange: (TaskGroupKey?) -> Unit = {},
    reminders: List<Reminder> = emptyList(),
    projectNames: Map<ProjectId, String>,
    workflowStatuses: List<WorkflowStatus>,
    tags: List<Tag>,
    selectedTaskId: TaskId?,
    showDetailPane: Boolean,
    listPaneFraction: Float = 0.42f,
    hingeExclusionBandDp: IntRange? = null,
    onSelectTask: (TaskId) -> Unit,
    onCloseDetail: () -> Unit,
    onCompleteTask: (Task) -> Unit,
    onChangeTaskStatus: (Task, WorkflowStatusId) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onDuplicateTask: (TaskId) -> Unit = {},
    activeTimerTaskId: TaskId?,
    onToggleTimer: (Task) -> Unit,
    onUpdateTask: (TaskId, TaskEdit) -> Unit,
    onAddChecklistItem: (TaskId, String) -> Unit,
    onUpdateChecklistItem: (TaskId, ChecklistItem) -> Unit,
    onDeleteChecklistItem: (TaskId, String) -> Unit,
    onSetTaskTag: (TaskId, TagId, Boolean) -> Unit,
    onCreateAndAssignTag: (TaskId, String) -> Unit,
    milestones: List<Milestone> = emptyList(),
    modifier: Modifier = Modifier,
    activeProjectIds: Set<ProjectId> = projectNames.keys,
    notificationsEnabled: Boolean = true,
    preciseRemindersAvailable: Boolean = false,
    onEnableNotifications: () -> Unit = {},
    onEnablePreciseReminders: () -> Unit = {},
    dependencyError: String? = null,
    onSetTaskDependency: (TaskId, TaskId, Boolean) -> Unit = { _, _, _ -> },
    onClearDependencyError: () -> Unit = {},
    timeEntries: List<TimeEntry> = emptyList(),
    timeEntryConflicts: List<TimeEntryConflict> = emptyList(),
    onAddTimeEntry: (TaskId, TimeEntryEdit) -> Unit = { _, _ -> },
    onUpdateTimeEntry: (TimeEntryId, TimeEntryEdit) -> Unit = { _, _ -> },
    onDeleteTimeEntry: (TimeEntryId) -> Unit = {},
    notes: List<Note> = emptyList(),
    activityEntries: List<ActivityEntry> = emptyList(),
    onAddNote: (TaskId, String) -> Unit = { _, _ -> },
    onUpdateNote: (NoteId, String) -> Unit = { _, _ -> },
    onDeleteNote: (NoteId) -> Unit = {},
    attachments: List<Attachment> = emptyList(),
    attachmentStates: Map<AttachmentId, AttachmentRowState> = emptyMap(),
    attachmentSetupRequired: Boolean = false,
    onAddAttachmentFromPhotos: (TaskId) -> Unit = {},
    onAddAttachmentFromFiles: (TaskId) -> Unit = {},
    onOpenAttachment: (AttachmentId) -> Unit = {},
    onShareAttachment: (AttachmentId) -> Unit = {},
    onDeleteAttachment: (AttachmentId) -> Unit = {},
    onRetryAttachment: (AttachmentId) -> Unit = {},
    onOpenAttachmentSetup: () -> Unit = {},
    onAddToCalendar: (() -> Unit)? = null,
    onStartFocus: ((FocusPresetOption) -> Unit)? = null,
    selectedBulkIds: Set<TaskId> = emptySet(),
    onToggleBulkSelection: (TaskId) -> Unit = {},
    onClearBulkSelection: () -> Unit = {},
    onBulkComplete: () -> Unit = {},
    onBulkReschedule: (LocalDate) -> Unit = {},
    onBulkMoveToProject: (ProjectId?) -> Unit = {},
    onBulkSetTag: (TagId, Boolean) -> Unit = { _, _ -> },
    onBulkDelete: () -> Unit = {},
) {
    var filter by rememberSaveable { mutableStateOf(TaskFilter.ALL) }
    val visibleTaskGroups = taskGroups.mapNotNull { group ->
        val visible = group.tasks.filter { task ->
            when (filter) {
                TaskFilter.INBOX -> task.projectId == null && task.deletedAt == null
                TaskFilter.TODAY -> !task.isCompleted && task.deletedAt == null &&
                    dueBucketsByTaskId[task.id] == DueBucket.TODAY
                TaskFilter.UPCOMING -> !task.isCompleted && task.deletedAt == null &&
                    dueBucketsByTaskId[task.id] in setOf(DueBucket.THIS_WEEK, DueBucket.LATER)
                TaskFilter.OVERDUE -> !task.isCompleted && task.deletedAt == null &&
                    dueBucketsByTaskId[task.id] == DueBucket.OVERDUE
                TaskFilter.ALL -> task.deletedAt == null
            }
        }
        group.copy(tasks = visible).takeIf { visible.isNotEmpty() }
    }
    val selectedTask = tasks.firstOrNull { it.id == selectedTaskId }
    val selectedReminder = reminders.firstOrNull { it.taskId == selectedTaskId }
    val selectedTimeEntries = timeEntries.filter { it.taskId == selectedTaskId }
    val selectedTimeEntryIds = selectedTimeEntries.mapTo(hashSetOf(), TimeEntry::id)
    val selectedTimeEntryConflicts = timeEntryConflicts.filter { conflict ->
        conflict.firstEntryId in selectedTimeEntryIds ||
            conflict.secondEntryId in selectedTimeEntryIds
    }
    val selectedNotes = notes.filter { it.taskId == selectedTaskId }
    val selectedActivity = activityEntries.filter { it.taskId == selectedTaskId }
    val selectedAttachments = attachments.filter { it.taskId == selectedTaskId }

    if (!showDetailPane && selectedTask != null) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            TaskDetailPane(
                task = selectedTask,
                onBack = onCloseDetail,
                onComplete = { onCompleteTask(selectedTask) },
                timerRunning = activeTimerTaskId == selectedTask.id,
                onToggleTimer = { onToggleTimer(selectedTask) },
                projectNames = projectNames,
                activeProjectIds = activeProjectIds,
                workflowStatuses = workflowStatuses,
                tags = tags,
                milestones = milestones,
                allTasks = tasks,
                dependencyError = dependencyError,
                reminder = selectedReminder,
                notificationsEnabled = notificationsEnabled,
                preciseRemindersAvailable = preciseRemindersAvailable,
                onEnableNotifications = onEnableNotifications,
                onEnablePreciseReminders = onEnablePreciseReminders,
                onChangeStatus = { onChangeTaskStatus(selectedTask, it) },
                onMoveToTrash = { onDeleteTask(selectedTask) },
                onDuplicateTask = onDuplicateTask,
                onUpdate = { onUpdateTask(selectedTask.id, it) },
                onAddChecklistItem = { onAddChecklistItem(selectedTask.id, it) },
                onUpdateChecklistItem = { onUpdateChecklistItem(selectedTask.id, it) },
                onDeleteChecklistItem = { onDeleteChecklistItem(selectedTask.id, it) },
                onSetTaskTag = { tagId, present ->
                    onSetTaskTag(selectedTask.id, tagId, present)
                },
                onCreateAndAssignTag = { onCreateAndAssignTag(selectedTask.id, it) },
                onSetTaskDependency = { dependencyId, present ->
                    onSetTaskDependency(selectedTask.id, dependencyId, present)
                },
                onClearDependencyError = onClearDependencyError,
                timeEntries = selectedTimeEntries,
                timeEntryConflicts = selectedTimeEntryConflicts,
                onAddTimeEntry = { onAddTimeEntry(selectedTask.id, it) },
                onUpdateTimeEntry = onUpdateTimeEntry,
                onDeleteTimeEntry = onDeleteTimeEntry,
                notes = selectedNotes,
                activity = selectedActivity,
                onAddNote = { onAddNote(selectedTask.id, it) },
                onUpdateNote = onUpdateNote,
                onDeleteNote = onDeleteNote,
                attachments = selectedAttachments,
                attachmentStates = attachmentStates,
                attachmentSetupRequired = attachmentSetupRequired,
                onAddAttachmentFromPhotos = { onAddAttachmentFromPhotos(selectedTask.id) },
                onAddAttachmentFromFiles = { onAddAttachmentFromFiles(selectedTask.id) },
                onOpenAttachment = onOpenAttachment,
                onShareAttachment = onShareAttachment,
                onDeleteAttachment = onDeleteAttachment,
                onRetryAttachment = onRetryAttachment,
                onOpenAttachmentSetup = onOpenAttachmentSetup,
                hingeExclusionBandDp = hingeExclusionBandDp,
                onAddToCalendar = onAddToCalendar,
                onStartFocus = onStartFocus,
            )
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            TaskListPane(
                taskGroups = visibleTaskGroups,
                projectNames = projectNames,
                activeProjectIds = activeProjectIds,
                tags = tags,
                selectedTaskId = selectedTaskId,
                selectedBulkIds = selectedBulkIds,
                selectedBulkTasks = tasks.filter { it.id in selectedBulkIds },
                filter = filter,
                onFilterChange = { filter = it },
                taskSort = taskSort,
                taskGroupBy = taskGroupBy,
                onTaskSortChange = onTaskSortChange,
                onTaskGroupChange = onTaskGroupChange,
                onSelectTask = onSelectTask,
                onCompleteTask = onCompleteTask,
                onToggleBulkSelection = onToggleBulkSelection,
                onClearBulkSelection = onClearBulkSelection,
                onBulkComplete = onBulkComplete,
                onBulkReschedule = onBulkReschedule,
                onBulkMoveToProject = onBulkMoveToProject,
                onBulkSetTag = onBulkSetTag,
                onBulkDelete = onBulkDelete,
                modifier = if (showDetailPane) {
                    Modifier
                        .weight(listPaneFraction)
                        .testTag("listPane")
                } else {
                    Modifier
                        .fillMaxWidth()
                        .testTag("listPane")
                },
            )

            if (showDetailPane) {
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp),
                )
                if (selectedTask == null) {
                    EmptyState(
                        icon = Icons.Rounded.Description,
                        title = "Choose a task",
                        modifier = Modifier
                            .weight(1f - listPaneFraction)
                            .align(Alignment.CenterVertically)
                            .testTag("detailPane"),
                    )
                } else {
                    TaskDetailPane(
                        task = selectedTask,
                        onBack = null,
                        onComplete = { onCompleteTask(selectedTask) },
                        timerRunning = activeTimerTaskId == selectedTask.id,
                        onToggleTimer = { onToggleTimer(selectedTask) },
                        projectNames = projectNames,
                        activeProjectIds = activeProjectIds,
                        workflowStatuses = workflowStatuses,
                        tags = tags,
                        milestones = milestones,
                        allTasks = tasks,
                        dependencyError = dependencyError,
                        reminder = selectedReminder,
                        notificationsEnabled = notificationsEnabled,
                        preciseRemindersAvailable = preciseRemindersAvailable,
                        onEnableNotifications = onEnableNotifications,
                        onEnablePreciseReminders = onEnablePreciseReminders,
                        onChangeStatus = { onChangeTaskStatus(selectedTask, it) },
                        onMoveToTrash = { onDeleteTask(selectedTask) },
                        onDuplicateTask = onDuplicateTask,
                        onUpdate = { onUpdateTask(selectedTask.id, it) },
                        onAddChecklistItem = { onAddChecklistItem(selectedTask.id, it) },
                        onUpdateChecklistItem = { onUpdateChecklistItem(selectedTask.id, it) },
                        onDeleteChecklistItem = { onDeleteChecklistItem(selectedTask.id, it) },
                        onSetTaskTag = { tagId, present ->
                            onSetTaskTag(selectedTask.id, tagId, present)
                        },
                        onCreateAndAssignTag = { onCreateAndAssignTag(selectedTask.id, it) },
                        onSetTaskDependency = { dependencyId, present ->
                            onSetTaskDependency(selectedTask.id, dependencyId, present)
                        },
                        onClearDependencyError = onClearDependencyError,
                        timeEntries = selectedTimeEntries,
                        timeEntryConflicts = selectedTimeEntryConflicts,
                        onAddTimeEntry = { onAddTimeEntry(selectedTask.id, it) },
                        onUpdateTimeEntry = onUpdateTimeEntry,
                        onDeleteTimeEntry = onDeleteTimeEntry,
                        notes = selectedNotes,
                        activity = selectedActivity,
                        onAddNote = { onAddNote(selectedTask.id, it) },
                        onUpdateNote = onUpdateNote,
                        onDeleteNote = onDeleteNote,
                        attachments = selectedAttachments,
                        attachmentStates = attachmentStates,
                        attachmentSetupRequired = attachmentSetupRequired,
                        onAddAttachmentFromPhotos = {
                            onAddAttachmentFromPhotos(selectedTask.id)
                        },
                        onAddAttachmentFromFiles = { onAddAttachmentFromFiles(selectedTask.id) },
                        onOpenAttachment = onOpenAttachment,
                        onShareAttachment = onShareAttachment,
                        onDeleteAttachment = onDeleteAttachment,
                        onRetryAttachment = onRetryAttachment,
                        onOpenAttachmentSetup = onOpenAttachmentSetup,
                        hingeExclusionBandDp = hingeExclusionBandDp,
                        onAddToCalendar = onAddToCalendar,
                        onStartFocus = onStartFocus,
                        modifier = Modifier
                            .weight(1f - listPaneFraction)
                            .testTag("detailPane"),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskListPane(
    taskGroups: List<TaskGroup>,
    projectNames: Map<ProjectId, String>,
    activeProjectIds: Set<ProjectId>,
    tags: List<Tag>,
    selectedTaskId: TaskId?,
    selectedBulkIds: Set<TaskId>,
    selectedBulkTasks: List<Task>,
    filter: TaskFilter,
    onFilterChange: (TaskFilter) -> Unit,
    taskSort: TaskSortKey,
    taskGroupBy: TaskGroupKey?,
    onTaskSortChange: (TaskSortKey) -> Unit,
    onTaskGroupChange: (TaskGroupKey?) -> Unit,
    onSelectTask: (TaskId) -> Unit,
    onCompleteTask: (Task) -> Unit,
    onToggleBulkSelection: (TaskId) -> Unit,
    onClearBulkSelection: () -> Unit,
    onBulkComplete: () -> Unit,
    onBulkReschedule: (LocalDate) -> Unit,
    onBulkMoveToProject: (ProjectId?) -> Unit,
    onBulkSetTag: (TagId, Boolean) -> Unit,
    onBulkDelete: () -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    val selectionMode = selectedBulkIds.isNotEmpty()

    Column(modifier = modifier.fillMaxHeight()) {
        if (selectionMode) {
            BulkSelectionBar(
                selectedCount = selectedBulkIds.size,
                selectedTasks = selectedBulkTasks,
                projectNames = projectNames,
                activeProjectIds = activeProjectIds,
                tags = tags,
                onClear = onClearBulkSelection,
                onComplete = onBulkComplete,
                onReschedule = onBulkReschedule,
                onMoveToProject = onBulkMoveToProject,
                onSetTag = onBulkSetTag,
                onDelete = onBulkDelete,
            )
        } else {
            Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Tasks",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            "${taskGroups.sumOf { group -> group.tasks.count { !it.isCompleted } }} open • " +
                                "${taskGroups.sumOf { group -> group.tasks.count(Task::isBlocked) }} blocked",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TaskArrangementControls(
                        taskSort = taskSort,
                        taskGroupBy = taskGroupBy,
                        onTaskSortChange = onTaskSortChange,
                        onTaskGroupChange = onTaskGroupChange,
                    )
                }
                Spacer(Modifier.height(16.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TaskFilter.entries.forEach { candidate ->
                        FilterChip(
                            selected = filter == candidate,
                            onClick = { onFilterChange(candidate) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("task-filter-${candidate.name.lowercase(Locale.ROOT)}"),
                            label = { Text(candidate.label) },
                            leadingIcon = if (candidate == TaskFilter.INBOX) {
                                {
                                    Icon(
                                        Icons.Rounded.Inbox,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (taskGroups.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.CheckCircle,
                title = "This view is clear",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.testTag("task-list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    bottom = 104.dp,
                ),
            ) {
                taskGroups.forEach { group ->
                    group.value?.let { value ->
                        item(key = "header:${value.stableKey()}") {
                            SectionHeader(
                                title = taskGroupLabel(value, projectNames),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                            )
                        }
                    }
                    items(group.tasks, key = { "task:${it.id.value}" }) { task ->
                        if (selectionMode) {
                            val checked = task.id in selectedBulkIds
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.semantics { selected = checked },
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onToggleBulkSelection(task.id) },
                                    modifier = Modifier.testTag("bulk-check-${task.id.value}"),
                                )
                                TaskRow(
                                    task = task,
                                    projectName = projectNames[task.projectId] ?: "Inbox",
                                    selected = checked,
                                    onSelect = { onToggleBulkSelection(task.id) },
                                    onComplete = { onCompleteTask(task) },
                                    onLongPress = { onToggleBulkSelection(task.id) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else {
                            TaskRow(
                                task = task,
                                projectName = projectNames[task.projectId] ?: "Inbox",
                                selected = selectedTaskId == task.id,
                                onSelect = { onSelectTask(task.id) },
                                onComplete = { onCompleteTask(task) },
                                onLongPress = { onToggleBulkSelection(task.id) },
                                modifier = Modifier.semantics {
                                    selected = selectedTaskId == task.id
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskArrangementControls(
    taskSort: TaskSortKey,
    taskGroupBy: TaskGroupKey?,
    onTaskSortChange: (TaskSortKey) -> Unit,
    onTaskGroupChange: (TaskGroupKey?) -> Unit,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var groupExpanded by remember { mutableStateOf(false) }
    val sortControlDescription = stringResource(
        R.string.tasks_sort_control,
        taskSortLabel(taskSort),
    )
    val groupControlDescription = stringResource(
        R.string.tasks_group_control,
        taskGroupLabel(taskGroupBy),
    )

    Box {
        IconButton(
            onClick = { sortExpanded = true },
            modifier = Modifier
                .size(48.dp)
                .testTag("tasks-sort-control")
                .semantics { contentDescription = sortControlDescription },
        ) {
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = sortExpanded,
            onDismissRequest = { sortExpanded = false },
        ) {
            TaskSortKey.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(taskSortLabel(candidate)) },
                    onClick = {
                        sortExpanded = false
                        onTaskSortChange(candidate)
                    },
                    modifier = Modifier
                        .semantics { selected = taskSort == candidate }
                        .testTag("tasks-sort-option-${candidate.name.lowercase(Locale.ROOT)}"),
                )
            }
        }
    }
    Box {
        IconButton(
            onClick = { groupExpanded = true },
            modifier = Modifier
                .size(48.dp)
                .testTag("tasks-group-control")
                .semantics { contentDescription = groupControlDescription },
        ) {
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = groupExpanded,
            onDismissRequest = { groupExpanded = false },
        ) {
            listOf(null, TaskGroupKey.DUE_BUCKET, TaskGroupKey.PROJECT, TaskGroupKey.PRIORITY)
                .forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(taskGroupLabel(candidate)) },
                        onClick = {
                            groupExpanded = false
                            onTaskGroupChange(candidate)
                        },
                        modifier = Modifier
                            .semantics { selected = taskGroupBy == candidate }
                            .testTag(
                                "tasks-group-option-" +
                                    (candidate?.name?.lowercase(Locale.ROOT) ?: "none"),
                            ),
                    )
                }
        }
    }
}

@Composable
private fun taskSortLabel(value: TaskSortKey): String = stringResource(
    when (value) {
        TaskSortKey.DUE -> R.string.tasks_sort_due_label
        TaskSortKey.PRIORITY -> R.string.tasks_sort_priority_label
        TaskSortKey.TITLE -> R.string.tasks_sort_title_label
        TaskSortKey.UPDATED -> R.string.tasks_sort_updated_label
    },
)

@Composable
private fun taskGroupLabel(value: TaskGroupKey?): String = stringResource(
    when (value) {
        null -> R.string.tasks_group_none_label
        TaskGroupKey.DUE_BUCKET -> R.string.tasks_group_due_label
        TaskGroupKey.PROJECT -> R.string.tasks_group_project_label
        TaskGroupKey.PRIORITY -> R.string.tasks_group_priority_label
    },
)

private fun TaskGroupValue.stableKey(): String = when (this) {
    is TaskGroupValue.Due -> "due:${bucket.name}"
    is TaskGroupValue.Project -> projectId?.let { "project:id:${it.value}" } ?: "project:inbox"
    is TaskGroupValue.PriorityValue -> "priority:${priority.name}"
}

@Composable
private fun taskGroupLabel(
    value: TaskGroupValue,
    projectNames: Map<ProjectId, String>,
): String = when (value) {
    is TaskGroupValue.Due -> stringResource(
        when (value.bucket) {
            DueBucket.OVERDUE -> R.string.tasks_group_due_overdue
            DueBucket.TODAY -> R.string.tasks_group_due_today
            DueBucket.THIS_WEEK -> R.string.tasks_group_due_this_week
            DueBucket.LATER -> R.string.tasks_group_due_later
            DueBucket.NO_DATE -> R.string.tasks_group_due_no_date
        },
    )
    is TaskGroupValue.Project -> value.projectId?.let { projectId ->
        projectNames[projectId] ?: stringResource(R.string.tasks_group_project)
    } ?: stringResource(R.string.tasks_group_inbox)
    is TaskGroupValue.PriorityValue -> stringResource(
        when (value.priority) {
            Priority.URGENT -> R.string.tasks_group_priority_urgent
            Priority.HIGH -> R.string.tasks_group_priority_high
            Priority.MEDIUM -> R.string.tasks_group_priority_medium
            Priority.LOW -> R.string.tasks_group_priority_low
            Priority.NONE -> R.string.tasks_group_priority_none
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BulkSelectionBar(
    selectedCount: Int,
    selectedTasks: List<Task>,
    projectNames: Map<ProjectId, String>,
    activeProjectIds: Set<ProjectId>,
    tags: List<Tag>,
    onClear: () -> Unit,
    onComplete: () -> Unit,
    onReschedule: (LocalDate) -> Unit,
    onMoveToProject: (ProjectId?) -> Unit,
    onSetTag: (TagId, Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showMoveMenu by remember { mutableStateOf(false) }
    var showTagMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 16.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onClear,
            modifier = Modifier.testTag("bulk-clear"),
        ) {
            Icon(
                Icons.Rounded.Clear,
                contentDescription = stringResource(R.string.bulk_clear_action),
            )
        }
        Text(
            text = stringResource(R.string.bulk_selected_count, selectedCount),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onComplete,
            modifier = Modifier.testTag("bulk-complete"),
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = stringResource(R.string.bulk_complete_action),
            )
        }
        IconButton(
            onClick = { showDatePicker = true },
            modifier = Modifier.testTag("bulk-reschedule"),
        ) {
            Icon(
                Icons.Rounded.Schedule,
                contentDescription = stringResource(R.string.bulk_reschedule_action),
            )
        }
        Box {
            IconButton(
                onClick = { showMoveMenu = true },
                modifier = Modifier.testTag("bulk-move"),
            ) {
                Icon(
                    Icons.Rounded.FolderOpen,
                    contentDescription = stringResource(R.string.bulk_move_action),
                )
            }
            DropdownMenu(
                expanded = showMoveMenu,
                onDismissRequest = { showMoveMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bulk_move_inbox)) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Inbox, contentDescription = null)
                    },
                    onClick = {
                        showMoveMenu = false
                        onMoveToProject(null)
                    },
                    modifier = Modifier.testTag("bulk-move-inbox"),
                )
                projectNames
                    .filterKeys { it in activeProjectIds }
                    .toList()
                    .sortedBy { (_, name) -> name.lowercase(Locale.ROOT) }
                    .forEach { (projectId, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                showMoveMenu = false
                                onMoveToProject(projectId)
                            },
                            modifier = Modifier.testTag("bulk-move-${projectId.value}"),
                        )
                    }
            }
        }
        Box {
            IconButton(
                onClick = { showTagMenu = true },
                modifier = Modifier.testTag("bulk-tag"),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Label,
                    contentDescription = stringResource(R.string.bulk_tag_action),
                )
            }
            DropdownMenu(
                expanded = showTagMenu,
                onDismissRequest = { showTagMenu = false },
            ) {
                tags.forEach { tag ->
                    val everySelectedHasTag = selectedTasks.isNotEmpty() &&
                        selectedTasks.all { tag.id in it.tagIds }
                    DropdownMenuItem(
                        text = { Text(tag.name) },
                        leadingIcon = {
                            Checkbox(
                                checked = everySelectedHasTag,
                                onCheckedChange = null,
                            )
                        },
                        onClick = {
                            showTagMenu = false
                            onSetTag(tag.id, !everySelectedHasTag)
                        },
                        modifier = Modifier.testTag("bulk-tag-${tag.id.value}"),
                    )
                }
            }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.testTag("bulk-delete"),
        ) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.bulk_delete_action),
            )
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selected ->
                            onReschedule(
                                Instant.ofEpochMilli(selected)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate(),
                            )
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(R.string.bulk_reschedule_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.bulk_reschedule_cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailPane(
    task: Task,
    reminder: Reminder?,
    notificationsEnabled: Boolean,
    preciseRemindersAvailable: Boolean,
    onEnableNotifications: () -> Unit,
    onEnablePreciseReminders: () -> Unit,
    onBack: (() -> Unit)?,
    onComplete: () -> Unit,
    timerRunning: Boolean,
    onToggleTimer: () -> Unit,
    projectNames: Map<ProjectId, String>,
    activeProjectIds: Set<ProjectId>,
    workflowStatuses: List<WorkflowStatus>,
    tags: List<Tag>,
    milestones: List<Milestone>,
    allTasks: List<Task>,
    dependencyError: String?,
    onChangeStatus: (WorkflowStatusId) -> Unit,
    onMoveToTrash: () -> Unit,
    onDuplicateTask: (TaskId) -> Unit,
    onUpdate: (TaskEdit) -> Unit,
    onAddChecklistItem: (String) -> Unit,
    onUpdateChecklistItem: (ChecklistItem) -> Unit,
    onDeleteChecklistItem: (String) -> Unit,
    onSetTaskTag: (TagId, Boolean) -> Unit,
    onCreateAndAssignTag: (String) -> Unit,
    onSetTaskDependency: (TaskId, Boolean) -> Unit,
    onClearDependencyError: () -> Unit,
    timeEntries: List<TimeEntry>,
    timeEntryConflicts: List<TimeEntryConflict>,
    onAddTimeEntry: (TimeEntryEdit) -> Unit,
    onUpdateTimeEntry: (TimeEntryId, TimeEntryEdit) -> Unit,
    onDeleteTimeEntry: (TimeEntryId) -> Unit,
    notes: List<Note>,
    activity: List<ActivityEntry>,
    onAddNote: (String) -> Unit,
    onUpdateNote: (NoteId, String) -> Unit,
    onDeleteNote: (NoteId) -> Unit,
    attachments: List<Attachment>,
    attachmentStates: Map<AttachmentId, AttachmentRowState>,
    attachmentSetupRequired: Boolean,
    onAddAttachmentFromPhotos: () -> Unit,
    onAddAttachmentFromFiles: () -> Unit,
    onOpenAttachment: (AttachmentId) -> Unit,
    onShareAttachment: (AttachmentId) -> Unit,
    onDeleteAttachment: (AttachmentId) -> Unit,
    onRetryAttachment: (AttachmentId) -> Unit,
    onOpenAttachmentSetup: () -> Unit,
    hingeExclusionBandDp: IntRange?,
    onAddToCalendar: (() -> Unit)? = null,
    onStartFocus: ((FocusPresetOption) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sheetTopPaddingDp = hingeExclusionBandDp?.last ?: 0
    var title by rememberSaveable(task.id.value) { mutableStateOf(task.title) }
    var description by rememberSaveable(task.id.value) { mutableStateOf(task.description) }
    var projectIdValue by rememberSaveable(task.id.value) {
        mutableStateOf(task.projectId?.value)
    }
    var priorityName by rememberSaveable(task.id.value) {
        mutableStateOf(task.priority.name)
    }
    var dueEpochMillis by rememberSaveable(task.id.value) {
        mutableStateOf(task.due?.instant?.toEpochMilli())
    }
    var dueZoneId by rememberSaveable(task.id.value) {
        mutableStateOf(task.due?.zoneId)
    }
    var recurrenceFrequencyName by rememberSaveable(task.id.value) {
        mutableStateOf(task.recurrence?.frequency?.name)
    }
    var recurrenceIntervalText by rememberSaveable(task.id.value) {
        mutableStateOf(task.recurrence?.interval?.toString() ?: "1")
    }
    var recurrenceWeekdaysCsv by rememberSaveable(task.id.value) {
        mutableStateOf(
            task.recurrence
                ?.weekdays
                ?.sortedBy(DayOfWeek::getValue)
                ?.joinToString(",") { it.name }
                .orEmpty(),
        )
    }
    var recurrenceEndModeName by rememberSaveable(task.id.value) {
        mutableStateOf(
            when {
                task.recurrence?.count != null -> RecurrenceEndMode.COUNT.name
                task.recurrence?.endDate != null -> RecurrenceEndMode.DATE.name
                else -> RecurrenceEndMode.NEVER.name
            },
        )
    }
    var recurrenceCountText by rememberSaveable(task.id.value) {
        mutableStateOf(task.recurrence?.count?.toString() ?: "10")
    }
    var recurrenceEndDateEpochDay by rememberSaveable(task.id.value) {
        mutableStateOf(task.recurrence?.endDate?.toEpochDay())
    }
    var estimateMinutes by rememberSaveable(task.id.value) {
        mutableStateOf(task.estimate?.toMinutes())
    }
    var milestoneIdValue by rememberSaveable(task.id.value) {
        mutableStateOf(task.milestoneId?.value)
    }
    var reminderLeadSeconds by rememberSaveable(task.id.value) {
        mutableStateOf(reminderLeadSeconds(task, reminder))
    }
    var reminderPrecise by rememberSaveable(task.id.value) {
        mutableStateOf(reminder?.precise ?: false)
    }
    var newTagName by rememberSaveable(task.id.value) { mutableStateOf("") }
    var newChecklistText by rememberSaveable(task.id.value) { mutableStateOf("") }
    var showDuePicker by rememberSaveable(task.id.value) { mutableStateOf(false) }
    var showRecurrenceEndPicker by rememberSaveable(task.id.value) {
        mutableStateOf(false)
    }
    var showProjectMenu by rememberSaveable(task.id.value) { mutableStateOf(false) }
    var showMilestoneMenu by rememberSaveable(task.id.value) { mutableStateOf(false) }
    var showStatusMenu by rememberSaveable(task.id.value) { mutableStateOf(false) }
    var showDependencyEditor by rememberSaveable(task.id.value) { mutableStateOf(false) }
    var showTimeEntries by rememberSaveable(task.id.value) { mutableStateOf(false) }
    var lastSubmitted by remember(task.id.value) { mutableStateOf<TaskEdit?>(null) }
    var skipInitialRepositorySync by remember(task.id.value) { mutableStateOf(true) }
    val activeWorkflowStatuses = workflowStatuses
        .filter { it.projectId == task.projectId && it.archivedAt == null }
        .sortedBy(WorkflowStatus::rank)
    val currentStatus = workflowStatuses.firstOrNull { it.id == task.statusId }
    val currentStatusName = currentStatus?.name ?: task.semanticStatus.readableName()

    val editorDue = dueEpochMillis?.let { epoch ->
        ZonedMoment(
            instant = Instant.ofEpochMilli(epoch),
            zoneId = dueZoneId ?: ZoneId.systemDefault().id,
        )
    }
    val recurrenceFrequency = recurrenceFrequencyName?.let(RecurrenceFrequency::valueOf)
    val recurrenceInterval = recurrenceIntervalText.toIntOrNull()
    val recurrenceEndMode = RecurrenceEndMode.valueOf(recurrenceEndModeName)
    val recurrenceCount = recurrenceCountText.toIntOrNull()
    val recurrenceEndDate = recurrenceEndDateEpochDay?.let(LocalDate::ofEpochDay)
    val recurrenceWeekdays = recurrenceWeekdaysCsv
        .split(',')
        .filter(String::isNotBlank)
        .mapTo(linkedSetOf(), DayOfWeek::valueOf)
    val dueDate = editorDue
        ?.instant
        ?.atZone(ZoneId.of(editorDue.zoneId))
        ?.toLocalDate()
    val recurrenceError = when {
        recurrenceFrequency == null -> null
        editorDue == null -> "Choose a due date before adding a repeat"
        recurrenceInterval == null || recurrenceInterval !in 1..MAX_RECURRENCE_INTERVAL ->
            "Use an interval from 1 to $MAX_RECURRENCE_INTERVAL"
        recurrenceFrequency == RecurrenceFrequency.WEEKLY && recurrenceWeekdays.isEmpty() ->
            "Choose at least one weekday"
        recurrenceEndMode == RecurrenceEndMode.COUNT &&
            (recurrenceCount == null || recurrenceCount !in 1..MAX_RECURRENCE_COUNT) ->
            "Use an occurrence count from 1 to $MAX_RECURRENCE_COUNT"
        recurrenceEndMode == RecurrenceEndMode.DATE && recurrenceEndDate == null ->
            "Choose an end date"
        recurrenceEndMode == RecurrenceEndMode.DATE &&
            dueDate != null &&
            recurrenceEndDate != null &&
            recurrenceEndDate.isBefore(dueDate) ->
            "The end date cannot be before the due date"
        else -> null
    }
    val editorRecurrence = if (recurrenceFrequency != null && recurrenceError == null) {
        RecurrenceRule(
            frequency = recurrenceFrequency,
            interval = checkNotNull(recurrenceInterval),
            weekdays = recurrenceWeekdays.takeIf {
                recurrenceFrequency == RecurrenceFrequency.WEEKLY
            }.orEmpty(),
            count = recurrenceCount.takeIf {
                recurrenceEndMode == RecurrenceEndMode.COUNT
            },
            endDate = recurrenceEndDate.takeIf {
                recurrenceEndMode == RecurrenceEndMode.DATE
            },
        )
    } else {
        null
    }
    val editorReminder = if (editorDue != null && reminderLeadSeconds != null) {
        Reminder(
            id = Reminder.primaryId(task.id),
            taskId = task.id,
            triggerAt = ZonedMoment(
                instant = editorDue.instant.minusSeconds(checkNotNull(reminderLeadSeconds)),
                zoneId = editorDue.zoneId,
            ),
            precise = reminderPrecise,
        )
    } else {
        null
    }
    val editorValue = TaskEdit(
        title = title.trim(),
        description = description,
        projectId = projectIdValue?.let(::ProjectId),
        priority = Priority.valueOf(priorityName),
        due = editorDue,
        recurrence = editorRecurrence,
        estimate = estimateMinutes?.let(Duration::ofMinutes),
        milestoneId = milestoneIdValue?.let(::MilestoneId),
        reminder = editorReminder,
    )
    val persistedValue = task.toTaskEdit(reminder)
    val titleError = when {
        title.isBlank() -> "A task needs a title"
        title.trim().length > MAX_TASK_TITLE_LENGTH ->
            "Keep the title under $MAX_TASK_TITLE_LENGTH characters"
        else -> null
    }
    val descriptionError = description.length > MAX_TASK_DESCRIPTION_LENGTH
    val valid = titleError == null && !descriptionError && recurrenceError == null
    val dirty = editorValue != persistedValue

    LaunchedEffect(persistedValue) {
        if (skipInitialRepositorySync) {
            skipInitialRepositorySync = false
        } else if (lastSubmitted == persistedValue) {
            lastSubmitted = null
        } else {
            title = task.title
            description = task.description
            projectIdValue = task.projectId?.value
            priorityName = task.priority.name
            dueEpochMillis = task.due?.instant?.toEpochMilli()
            dueZoneId = task.due?.zoneId
            recurrenceFrequencyName = task.recurrence?.frequency?.name
            recurrenceIntervalText = task.recurrence?.interval?.toString() ?: "1"
            recurrenceWeekdaysCsv = task.recurrence
                ?.weekdays
                ?.sortedBy(DayOfWeek::getValue)
                ?.joinToString(",") { it.name }
                .orEmpty()
            recurrenceEndModeName = when {
                task.recurrence?.count != null -> RecurrenceEndMode.COUNT.name
                task.recurrence?.endDate != null -> RecurrenceEndMode.DATE.name
                else -> RecurrenceEndMode.NEVER.name
            }
            recurrenceCountText = task.recurrence?.count?.toString() ?: "10"
            recurrenceEndDateEpochDay = task.recurrence?.endDate?.toEpochDay()
            estimateMinutes = task.estimate?.toMinutes()
            milestoneIdValue = task.milestoneId?.value
            reminderLeadSeconds = reminderLeadSeconds(task, reminder)
            reminderPrecise = reminder?.precise ?: false
        }
    }

    LaunchedEffect(task.id, editorValue, valid) {
        if (!valid || !dirty) return@LaunchedEffect
        delay(AUTO_SAVE_DELAY_MILLIS)
        lastSubmitted = editorValue
        onUpdate(editorValue)
    }

    val latestEditor by rememberUpdatedState(editorValue)
    val latestPersisted by rememberUpdatedState(persistedValue)
    val latestValid by rememberUpdatedState(valid)
    val latestSubmitted by rememberUpdatedState(lastSubmitted)
    val latestOnUpdate by rememberUpdatedState(onUpdate)
    DisposableEffect(task.id) {
        onDispose {
            if (
                latestValid &&
                latestEditor != latestPersisted &&
                latestEditor != latestSubmitted
            ) {
                latestOnUpdate(latestEditor)
            }
        }
    }

    val editingStateDescription = stringResource(R.string.task_editing_state_description, title)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("task-detail-scroll")
            .semantics { stateDescription = editingStateDescription }
            .padding(top = sheetTopPaddingDp.dp),
    ) {
        Column(
            modifier = Modifier
                .testTag("editorSheetContent")
                .padding(24.dp),
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to tasks")
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Box {
                    OutlinedButton(
                        onClick = { showStatusMenu = true },
                        enabled = activeWorkflowStatuses.isNotEmpty(),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("task-status-button")
                            .semantics {
                                stateDescription = "Current status: $currentStatusName"
                            },
                    ) {
                        Icon(
                            task.semanticStatus.statusIcon(),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            currentStatusName,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = showStatusMenu,
                        onDismissRequest = { showStatusMenu = false },
                    ) {
                        activeWorkflowStatuses.forEach { status ->
                            DropdownMenuItem(
                                text = { Text(status.name) },
                                leadingIcon = {
                                    Icon(
                                        status.semanticStatus.statusIcon(),
                                        contentDescription = null,
                                    )
                                },
                                trailingIcon = if (status.id == task.statusId) {
                                    {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = "Current status",
                                        )
                                    }
                                } else {
                                    null
                                },
                                onClick = {
                                    showStatusMenu = false
                                    if (status.id != task.statusId) {
                                        onChangeStatus(status.id)
                                    }
                                },
                                modifier = Modifier.testTag(
                                    "task-status-option-${status.id.value}",
                                ),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SaveStatus(valid = valid, dirty = dirty)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Task details",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = title,
            onValueChange = {
                title = it.take(MAX_TASK_TITLE_LENGTH + 1)
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("task-title-field"),
            label = { Text("Task title") },
            supportingText = {
                Text(titleError ?: "${title.trim().length}/$MAX_TASK_TITLE_LENGTH")
            },
            isError = titleError != null,
            singleLine = false,
            maxLines = 3,
        )
        Spacer(Modifier.height(24.dp))
        SectionHeader(title = "Context")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = description,
            onValueChange = {
                description = it.take(MAX_TASK_DESCRIPTION_LENGTH + 1)
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("task-description-field"),
            label = { Text("Description") },
            placeholder = { Text("Add context, decisions, or a CommonMark note") },
            supportingText = {
                Text(
                    if (descriptionError) {
                        "Keep the description under $MAX_TASK_DESCRIPTION_LENGTH characters"
                    } else {
                        "${description.length}/$MAX_TASK_DESCRIPTION_LENGTH"
                    },
                )
            },
            isError = descriptionError,
            minLines = 4,
            maxLines = 10,
        )

        Spacer(Modifier.height(28.dp))
        TaskAttachmentsSection(
            attachments = attachments,
            states = attachmentStates,
            onAddFromPhotos = onAddAttachmentFromPhotos,
            onAddFromFiles = onAddAttachmentFromFiles,
            onOpen = onOpenAttachment,
            onShare = onShareAttachment,
            onDelete = onDeleteAttachment,
            onRetry = onRetryAttachment,
            setupRequired = attachmentSetupRequired,
            onOpenBackupSetup = onOpenAttachmentSetup,
        )

        Spacer(Modifier.height(28.dp))
        SectionHeader("Organisation")
        Spacer(Modifier.height(12.dp))
        Text(
            "Project",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Box {
            OutlinedButton(
                onClick = { showProjectMenu = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(
                    if (projectIdValue == null) Icons.Rounded.Inbox else Icons.Rounded.FolderOpen,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    projectIdValue?.let { selected ->
                        projectNames[ProjectId(selected)]
                    } ?: "Inbox",
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = showProjectMenu,
                onDismissRequest = { showProjectMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Inbox") },
                    leadingIcon = { Icon(Icons.Rounded.Inbox, contentDescription = null) },
                    onClick = {
                        if (projectIdValue != null) milestoneIdValue = null
                        projectIdValue = null
                        showProjectMenu = false
                    },
                )
                projectNames
                    .filterKeys { id ->
                        id in activeProjectIds || id.value == projectIdValue
                    }
                    .forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        leadingIcon = {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                        },
                        onClick = {
                            if (projectIdValue != id.value) milestoneIdValue = null
                            projectIdValue = id.value
                            showProjectMenu = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        val dependencyTasks = task.dependencyIds.mapNotNull { dependencyId ->
            allTasks.firstOrNull { it.id == dependencyId }
        }
        SectionHeader(
            title = "Dependencies",
            supportingText = if (dependencyTasks.isEmpty()) {
                "No prerequisites"
            } else {
                "${task.blockedBy.size} unfinished • ${dependencyTasks.size} total"
            },
            action = {
                TextButton(
                    onClick = {
                        onClearDependencyError()
                        showDependencyEditor = true
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("manage-task-dependencies"),
                ) {
                    Text("Manage")
                }
            },
        )
        if (dependencyTasks.isEmpty()) {
            Text(
                "Link work that must finish before this task.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(8.dp))
            dependencyTasks.take(3).forEach { dependency ->
                val unfinished = dependency.id in task.blockedBy
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (unfinished) Icons.Rounded.Block else Icons.Rounded.CheckCircle,
                        contentDescription = if (unfinished) "Unfinished" else "Complete",
                        modifier = Modifier.size(20.dp),
                        tint = if (unfinished) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            dependency.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                        )
                        Text(
                            projectNames[dependency.projectId] ?: "Inbox",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (dependencyTasks.size > 3) {
                Text(
                    "+${dependencyTasks.size - 3} more",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Milestone",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        val availableMilestones = milestones
            .filter {
                it.projectId.value == projectIdValue &&
                    (it.completedAt == null || it.id.value == milestoneIdValue)
            }
            .sortedWith(
                compareBy<Milestone> { it.completedAt != null }
                    .thenBy { it.dueDate == null }
                    .thenBy(Milestone::dueDate),
            )
        Box {
            OutlinedButton(
                onClick = { showMilestoneMenu = true },
                enabled = projectIdValue != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("task-milestone-button"),
            ) {
                Icon(Icons.Rounded.Flag, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    availableMilestones
                        .firstOrNull { it.id.value == milestoneIdValue }
                        ?.name
                        ?: if (projectIdValue == null) {
                            "Move to a project first"
                        } else {
                            "No milestone"
                        },
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = showMilestoneMenu,
                onDismissRequest = { showMilestoneMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("No milestone") },
                    onClick = {
                        milestoneIdValue = null
                        showMilestoneMenu = false
                    },
                    trailingIcon = if (milestoneIdValue == null) {
                        { Icon(Icons.Rounded.Check, contentDescription = "Selected") }
                    } else {
                        null
                    },
                )
                availableMilestones.forEach { milestone ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(milestone.name)
                                milestone.dueDate?.let { dueDate ->
                                    Text(
                                        dueDate.format(MILESTONE_DATE_FORMAT),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            milestoneIdValue = milestone.id.value
                            showMilestoneMenu = false
                        },
                        trailingIcon = if (milestone.id.value == milestoneIdValue) {
                            { Icon(Icons.Rounded.Check, contentDescription = "Selected") }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag(
                            "task-milestone-option-${milestone.id.value}",
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Priority",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Priority.entries.forEach { priority ->
                FilterChip(
                    selected = priorityName == priority.name,
                    onClick = { priorityName = priority.name },
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(priority.readableName()) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader(
            title = "Tags",
            supportingText = task.tagIds
                .takeIf(Set<TagId>::isNotEmpty)
                ?.let { "${it.size} selected" },
        )
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.forEach { tag ->
                    val selected = tag.id in task.tagIds
                    FilterChip(
                        selected = selected,
                        onClick = { onSetTaskTag(tag.id, !selected) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("task-tag-${tag.id.value}"),
                        label = { Text(tag.name) },
                        leadingIcon = {
                            Icon(
                                if (selected) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.Label,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = newTagName,
            onValueChange = {
                newTagName = it.take(MAX_TAG_NAME_LENGTH + 1)
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("new-tag-field"),
            label = { Text("Create or add tag") },
            placeholder = { Text("For example: Waiting") },
            supportingText = {
                Text(
                    if (newTagName.length > MAX_TAG_NAME_LENGTH) {
                        "Keep tag names under $MAX_TAG_NAME_LENGTH characters"
                    } else {
                        "${newTagName.length}/$MAX_TAG_NAME_LENGTH"
                    },
                )
            },
            isError = newTagName.length > MAX_TAG_NAME_LENGTH,
            singleLine = true,
            trailingIcon = {
                IconButton(
                    onClick = {
                        onCreateAndAssignTag(newTagName.trim())
                        newTagName = ""
                    },
                    enabled = newTagName.isNotBlank() &&
                        newTagName.trim().length <= MAX_TAG_NAME_LENGTH,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Create and add tag")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (
                        newTagName.isNotBlank() &&
                        newTagName.trim().length <= MAX_TAG_NAME_LENGTH
                    ) {
                        onCreateAndAssignTag(newTagName.trim())
                        newTagName = ""
                    }
                },
            ),
        )

        Spacer(Modifier.height(28.dp))
        SectionHeader("Planning")
        Spacer(Modifier.height(12.dp))
        Text(
            "Due date",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { showDuePicker = true },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    editorValue.due?.let { due ->
                        DUE_DATE_FORMAT.format(due.instant.atZone(ZoneId.of(due.zoneId)))
                    } ?: "Choose date",
                )
            }
            if (editorValue.due != null) {
                IconButton(
                    onClick = {
                        dueEpochMillis = null
                        dueZoneId = null
                        recurrenceFrequencyName = null
                        reminderLeadSeconds = null
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Rounded.Clear, contentDescription = "Clear due date")
                }
            }
        }
        Text(
            "Existing times are preserved; new dates use 17:00.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "Reminder",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("reminder-options"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            REMINDER_PRESETS.forEach { preset ->
                val triggerAt = preset.leadSeconds?.let { lead ->
                    editorDue?.instant?.minusSeconds(lead)
                }
                FilterChip(
                    selected = reminderLeadSeconds == preset.leadSeconds,
                    onClick = {
                        reminderLeadSeconds = preset.leadSeconds
                        if (preset.leadSeconds != null && !notificationsEnabled) {
                            onEnableNotifications()
                        }
                    },
                    enabled = preset.leadSeconds == null ||
                        triggerAt?.isAfter(Instant.now()) == true,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("reminder-${preset.testTag}"),
                    label = { Text(preset.label) },
                    leadingIcon = if (reminderLeadSeconds == preset.leadSeconds) {
                        {
                            Icon(
                                if (preset.leadSeconds == null) {
                                    Icons.Rounded.NotificationsOff
                                } else {
                                    Icons.Rounded.Notifications
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        when {
            editorDue == null -> {
                Text(
                    "Choose a due date to set a reminder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            editorReminder != null -> {
                val reminderPassed = !editorReminder.triggerAt.instant.isAfter(Instant.now())
                val reminderTime = REMINDER_DATE_TIME_FORMAT.format(
                    editorReminder.triggerAt.instant.atZone(
                        ZoneId.of(editorReminder.triggerAt.zoneId),
                    ),
                )
                Text(
                    text = if (reminderPassed) {
                        "That reminder time has passed. Choose another option."
                    } else {
                        "Scheduled for $reminderTime."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (reminderPassed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    "Delivery",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !reminderPrecise,
                        onClick = { reminderPrecise = false },
                        enabled = !reminderPassed,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("reminder-delivery-flexible"),
                        label = { Text("Flexible") },
                    )
                    FilterChip(
                        selected = reminderPrecise,
                        onClick = { reminderPrecise = true },
                        enabled = !reminderPassed,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("reminder-delivery-precise"),
                        label = { Text("Precise") },
                    )
                }

                if (!notificationsEnabled) {
                    Text(
                        "Notifications are off. The reminder is saved, but Android cannot show it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = onEnableNotifications,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("enable-notifications"),
                    ) {
                        Text("Allow notifications")
                    }
                }

                if (reminderPrecise && !preciseRemindersAvailable) {
                    Text(
                        "Precise timing is off. Android will deliver this after the chosen time, " +
                            "usually within an hour.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onEnablePreciseReminders,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("enable-precise-reminders"),
                    ) {
                        Text("Allow precise timing")
                    }
                } else if (!reminderPrecise) {
                    Text(
                        "Flexible timing uses less battery and may arrive after the chosen time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Repeat",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("recurrence-frequency-row"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = recurrenceFrequency == null,
                onClick = { recurrenceFrequencyName = null },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("recurrence-frequency-none"),
                label = { Text("None") },
            )
            RecurrenceFrequency.entries.forEach { frequency ->
                FilterChip(
                    selected = recurrenceFrequency == frequency,
                    onClick = {
                        recurrenceFrequencyName = frequency.name
                        if (
                            frequency == RecurrenceFrequency.WEEKLY &&
                            recurrenceWeekdaysCsv.isBlank()
                        ) {
                            recurrenceWeekdaysCsv = dueDate
                                ?.dayOfWeek
                                ?.name
                                .orEmpty()
                        }
                    },
                    enabled = editorDue != null,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(
                            "recurrence-frequency-${frequency.name.lowercase(Locale.ROOT)}",
                        ),
                    label = { Text(frequency.readableName()) },
                    leadingIcon = if (recurrenceFrequency == frequency) {
                        {
                            Icon(
                                Icons.Rounded.Repeat,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        if (recurrenceFrequency != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = recurrenceIntervalText,
                onValueChange = { value ->
                    recurrenceIntervalText = value
                        .filter(Char::isDigit)
                        .take(MAX_RECURRENCE_INTERVAL.toString().length + 1)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recurrence-interval-field"),
                label = { Text("Repeat every") },
                suffix = {
                    Text(
                        recurrenceFrequency.intervalUnit(
                            recurrenceInterval ?: 0,
                        ),
                    )
                },
                supportingText = if (
                    recurrenceInterval == null ||
                    recurrenceInterval !in 1..MAX_RECURRENCE_INTERVAL
                ) {
                    { Text("Use a number from 1 to $MAX_RECURRENCE_INTERVAL") }
                } else {
                    null
                },
                isError = recurrenceInterval == null ||
                    recurrenceInterval !in 1..MAX_RECURRENCE_INTERVAL,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )

            if (recurrenceFrequency == RecurrenceFrequency.WEEKLY) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Days",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DayOfWeek.entries.forEach { weekday ->
                        val selected = weekday in recurrenceWeekdays
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val updated = recurrenceWeekdays
                                    .toMutableSet()
                                    .apply {
                                        if (selected) remove(weekday) else add(weekday)
                                    }
                                recurrenceWeekdaysCsv = updated
                                    .sortedBy { it.value }
                                    .joinToString(",") { it.name }
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag(
                                    "recurrence-weekday-${weekday.name.lowercase(Locale.ROOT)}",
                                ),
                            label = { Text(weekday.shortName()) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Ends",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recurrence-end-row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RecurrenceEndMode.entries.forEach { mode ->
                    FilterChip(
                        selected = recurrenceEndMode == mode,
                        onClick = {
                            recurrenceEndModeName = mode.name
                            if (
                                mode == RecurrenceEndMode.DATE &&
                                recurrenceEndDateEpochDay == null
                            ) {
                                recurrenceEndDateEpochDay = dueDate?.toEpochDay()
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("recurrence-end-${mode.name.lowercase(Locale.ROOT)}"),
                        label = { Text(mode.label) },
                    )
                }
            }

            when (recurrenceEndMode) {
                RecurrenceEndMode.NEVER -> Unit
                RecurrenceEndMode.COUNT -> {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = recurrenceCountText,
                        onValueChange = { value ->
                            recurrenceCountText = value
                                .filter(Char::isDigit)
                                .take(MAX_RECURRENCE_COUNT.toString().length + 1)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("recurrence-count-field"),
                        label = { Text("Occurrences") },
                        supportingText = if (
                            recurrenceCount == null ||
                            recurrenceCount !in 1..MAX_RECURRENCE_COUNT
                        ) {
                            { Text("Use a number from 1 to $MAX_RECURRENCE_COUNT") }
                        } else {
                            null
                        },
                        isError = recurrenceCount == null ||
                            recurrenceCount !in 1..MAX_RECURRENCE_COUNT,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
                RecurrenceEndMode.DATE -> {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showRecurrenceEndPicker = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("recurrence-end-date-button"),
                    ) {
                        Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            recurrenceEndDate?.format(DUE_DATE_FORMAT) ?: "Choose end date",
                        )
                    }
                }
            }

            val recurrenceSummary = editorRecurrence?.let(::formatRecurrence)
            if (recurrenceError != null || recurrenceSummary != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = recurrenceError ?: checkNotNull(recurrenceSummary),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (recurrenceError == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("recurrence-supporting-text"),
                )
            }
        } else if (editorDue == null) {
            Text(
                "Choose a due date to set a repeat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Estimate",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ESTIMATE_OPTIONS.forEach { minutes ->
                FilterChip(
                    selected = estimateMinutes == minutes,
                    onClick = { estimateMinutes = minutes },
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(minutes?.let(::formatEstimate) ?: "None") },
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        val completedTime = timeEntries
            .mapNotNull { entry ->
                entry.stoppedAt?.let { stoppedAt ->
                    Duration.between(entry.startedAt, stoppedAt).takeUnless(Duration::isNegative)
                }
            }
            .fold(Duration.ZERO, Duration::plus)
        SectionHeader(
            title = "Time",
            supportingText = buildString {
                append(formatLoggedDuration(completedTime))
                append(" logged")
                if (timeEntries.any { it.stoppedAt == null }) append(" • timer running")
            },
            action = {
                TextButton(
                    onClick = { showTimeEntries = true },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("manage-time-entries"),
                ) {
                    Text(if (timeEntries.isEmpty()) "Add entry" else "Review")
                }
            },
        )
        if (timeEntryConflicts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("time-overlap-warning")
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Warning, contentDescription = null)
                    Text(
                        if (timeEntryConflicts.size == 1) {
                            "Two entries overlap. Review their times to avoid double-counting."
                        } else {
                            "${timeEntryConflicts.size} overlaps need review to avoid " +
                                "double-counting."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else if (timeEntries.isEmpty()) {
            Text(
                "Track work with the timer or add time after the fact.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val latest = timeEntries.maxByOrNull(TimeEntry::startedAt)
            latest?.let { entry ->
                Text(
                    "Latest: ${formatTimeEntryRange(entry)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionHeader(
            title = "Checklist",
            supportingText = "${task.checklist.count { it.completed }}/${task.checklist.size} complete",
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = newChecklistText,
            onValueChange = {
                newChecklistText = it.take(MAX_CHECKLIST_ITEM_LENGTH + 1)
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("new-checklist-item-field"),
            label = { Text("New checklist item") },
            placeholder = { Text("Add one concrete step") },
            supportingText = {
                Text(
                    if (newChecklistText.length > MAX_CHECKLIST_ITEM_LENGTH) {
                        "Keep checklist items under $MAX_CHECKLIST_ITEM_LENGTH characters"
                    } else {
                        "${newChecklistText.length}/$MAX_CHECKLIST_ITEM_LENGTH"
                    },
                )
            },
            isError = newChecklistText.length > MAX_CHECKLIST_ITEM_LENGTH,
            minLines = 1,
            maxLines = 3,
            trailingIcon = {
                IconButton(
                    onClick = {
                        onAddChecklistItem(newChecklistText.trim())
                        newChecklistText = ""
                    },
                    enabled = newChecklistText.isNotBlank() &&
                        newChecklistText.trim().length <= MAX_CHECKLIST_ITEM_LENGTH,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add checklist item")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (
                        newChecklistText.isNotBlank() &&
                        newChecklistText.trim().length <= MAX_CHECKLIST_ITEM_LENGTH
                    ) {
                        onAddChecklistItem(newChecklistText.trim())
                        newChecklistText = ""
                    }
                },
            ),
        )
        if (task.checklist.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            task.checklist.forEach { item ->
                TaskChecklistRow(
                    taskId = task.id,
                    item = item,
                    onUpdate = onUpdateChecklistItem,
                    onDelete = { onDeleteChecklistItem(item.id) },
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        // The detail pane is one call site for every task, so the in-progress
        // note is scoped to the selected one: selecting another task must not
        // leave a draft — or an open edit — pointing at the previous task's
        // note.
        key(task.id.value) {
            NotesActivitySection(
                notes = notes,
                activity = activity,
                onAddNote = onAddNote,
                onUpdateNote = onUpdateNote,
                onDeleteNote = onDeleteNote,
            )
        }

        if (task.isBlocked) {
            Spacer(Modifier.height(24.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Rounded.Block, contentDescription = null)
                    Text(
                        if (task.blockedBy.isEmpty()) {
                            "This task is in a blocked workflow state. You can still complete it " +
                                "after confirming the warning."
                        } else {
                            "This task has ${task.blockedBy.size} unfinished " +
                                "${if (task.blockedBy.size == 1) "dependency" else "dependencies"}. " +
                                "You can still complete it after confirming the warning."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onComplete,
                enabled = !task.isCompleted,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (task.isCompleted) "Completed" else "Complete task")
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onToggleTimer,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.Timer, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (timerRunning) "Stop timer" else "Start timer")
                }
                if (onStartFocus != null && !timerRunning) {
                    FocusPresetMenu(onStartFocus = onStartFocus)
                }
            }
            if (onAddToCalendar != null) {
                OutlinedButton(
                    onClick = onAddToCalendar,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("add-task-to-calendar"),
                ) {
                    Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.task_add_to_calendar))
                }
            }
            OutlinedButton(
                onClick = { onDuplicateTask(task.id) },
                enabled = valid && !dirty,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("duplicate-task"),
            ) {
                Text(stringResource(R.string.task_duplicate))
            }
            TextButton(
                onClick = onMoveToTrash,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("move-task-to-trash"),
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text("Move to Bin", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(80.dp))
        }
    }

    if (showDependencyEditor) {
        DependencyEditorSheet(
            task = task,
            tasks = allTasks,
            projectNames = projectNames,
            error = dependencyError,
            onDismiss = {
                showDependencyEditor = false
                onClearDependencyError()
            },
            onToggle = onSetTaskDependency,
            onClearError = onClearDependencyError,
        )
    }

    if (showTimeEntries) {
        TimeEntriesSheet(
            taskTitle = task.title,
            entries = timeEntries,
            conflicts = timeEntryConflicts,
            onDismiss = { showTimeEntries = false },
            onAdd = onAddTimeEntry,
            onUpdate = onUpdateTimeEntry,
            onDelete = onDeleteTimeEntry,
        )
    }

    if (showDuePicker) {
        val zone = editorValue.due
            ?.zoneId
            ?.let(ZoneId::of)
            ?: ZoneId.systemDefault()
        val selectedDateMillis = editorValue.due
            ?.instant
            ?.atZone(ZoneId.of(editorValue.due.zoneId))
            ?.toLocalDate()
            ?.atStartOfDay(ZoneOffset.UTC)
            ?.toInstant()
            ?.toEpochMilli()
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDateMillis,
        )
        DatePickerDialog(
            onDismissRequest = { showDuePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selected ->
                            val selectedDate = Instant.ofEpochMilli(selected)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            val localTime = editorValue.due
                                ?.instant
                                ?.atZone(ZoneId.of(editorValue.due.zoneId))
                                ?.toLocalTime()
                                ?: LocalTime.of(17, 0)
                            val due = selectedDate.atTime(localTime).atZone(zone)
                            dueEpochMillis = due.toInstant().toEpochMilli()
                            dueZoneId = zone.id
                        }
                        showDuePicker = false
                    },
                ) {
                    Text("Use date")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDuePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showRecurrenceEndPicker) {
        val selectedDateMillis = recurrenceEndDate
            ?.atStartOfDay(ZoneOffset.UTC)
            ?.toInstant()
            ?.toEpochMilli()
            ?: dueDate
                ?.atStartOfDay(ZoneOffset.UTC)
                ?.toInstant()
                ?.toEpochMilli()
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDateMillis,
        )
        DatePickerDialog(
            onDismissRequest = { showRecurrenceEndPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selected ->
                            recurrenceEndDateEpochDay = Instant.ofEpochMilli(selected)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toEpochDay()
                        }
                        showRecurrenceEndPicker = false
                    },
                ) {
                    Text("Use date")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecurrenceEndPicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun FocusPresetMenu(onStartFocus: (FocusPresetOption) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(48.dp)
                .testTag("focus-preset-menu"),
        ) {
            Icon(
                Icons.Rounded.ArrowDropDown,
                contentDescription = stringResource(R.string.task_focus_start),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FOCUS_PRESET_OPTIONS.forEach { (option, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = {
                        expanded = false
                        onStartFocus(option)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DependencyEditorSheet(
    task: Task,
    tasks: List<Task>,
    projectNames: Map<ProjectId, String>,
    error: String?,
    onDismiss: () -> Unit,
    onToggle: (TaskId, Boolean) -> Unit,
    onClearError: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable(task.id.value) { mutableStateOf("") }
    val candidates = tasks
        .filter { candidate ->
            candidate.id != task.id &&
                (candidate.deletedAt == null || candidate.id in task.dependencyIds) &&
                (
                    query.isBlank() ||
                        candidate.title.contains(query.trim(), ignoreCase = true) ||
                        (projectNames[candidate.projectId] ?: "Inbox")
                            .contains(query.trim(), ignoreCase = true)
                )
        }
        .sortedWith(
            compareByDescending<Task> { it.id in task.dependencyIds }
                .thenBy(Task::isCompleted)
                .thenBy { projectNames[it.projectId] ?: "Inbox" }
                .thenBy(Task::title),
        )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("dependency-editor"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(
                "Dependencies",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Choose tasks that must finish before “${task.title}”. " +
                    "Completed links stay visible but no longer block completion.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (error != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dependency-error")
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Block, contentDescription = null)
                        Text(error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it.take(MAX_DEPENDENCY_QUERY_LENGTH)
                    onClearError()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dependency-search"),
                label = { Text("Find a task") },
                placeholder = { Text("Search title or project") },
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "${task.dependencyIds.size}/$MAX_TASK_DEPENDENCIES selected",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (candidates.isEmpty()) {
                Text(
                    if (query.isBlank()) {
                        "No other active tasks are available."
                    } else {
                        "No tasks match that search."
                    },
                    modifier = Modifier.padding(vertical = 24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                ) {
                    items(candidates, key = { it.id.value }) { candidate ->
                        val selected = candidate.id in task.dependencyIds
                        val unfinished = candidate.id in task.blockedBy
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable {
                                    onClearError()
                                    onToggle(candidate.id, !selected)
                                }
                                .testTag("dependency-option-${candidate.id.value}")
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(candidate.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    buildString {
                                        append(
                                            when {
                                                candidate.deletedAt != null -> "In Bin"
                                                candidate.isCompleted -> "Complete"
                                                unfinished -> "Unfinished"
                                                else -> "Open"
                                            },
                                        )
                                        append(" • ")
                                        append(projectNames[candidate.projectId] ?: "Inbox")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (unfinished) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("Done")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeEntriesSheet(
    taskTitle: String,
    entries: List<TimeEntry>,
    conflicts: List<TimeEntryConflict>,
    onDismiss: () -> Unit,
    onAdd: (TimeEntryEdit) -> Unit,
    onUpdate: (TimeEntryId, TimeEntryEdit) -> Unit,
    onDelete: (TimeEntryId) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showEditor by rememberSaveable(taskTitle) { mutableStateOf(false) }
    var editingEntryId by rememberSaveable(taskTitle) { mutableStateOf<String?>(null) }
    val editingEntry = editingEntryId?.let { id ->
        entries.firstOrNull { it.id.value == id }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("time-entries-sheet"),
    ) {
        if (showEditor) {
            TimeEntryEditor(
                entry = editingEntry,
                onCancel = {
                    showEditor = false
                    editingEntryId = null
                },
                onSave = { edit ->
                    if (editingEntry == null) {
                        onAdd(edit)
                    } else {
                        onUpdate(editingEntry.id, edit)
                    }
                    showEditor = false
                    editingEntryId = null
                },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            ) {
                Text(
                    "Time entries",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    taskTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        editingEntryId = null
                        showEditor = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("add-time-entry"),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add time entry")
                }
                if (conflicts.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("time-sheet-overlap-warning"),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Warning, contentDescription = null)
                            Text(
                                if (conflicts.size == 1) {
                                    "1 overlap needs review"
                                } else {
                                    "${conflicts.size} overlaps need review"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (entries.isEmpty()) {
                    Text(
                        "No time has been logged yet. Add an entry for work completed " +
                            "without the timer.",
                        modifier = Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp),
                    ) {
                        items(
                            entries.sortedByDescending(TimeEntry::startedAt),
                            key = { it.id.value },
                        ) { entry ->
                            val entryConflicts = conflicts.filter { conflict ->
                                conflict.firstEntryId == entry.id ||
                                    conflict.secondEntryId == entry.id
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 72.dp)
                                    .testTag("time-entry-${entry.id.value}")
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        formatTimeEntryRange(entry),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        buildString {
                                            append(
                                                entry.stoppedAt?.let { stoppedAt ->
                                                    formatLoggedDuration(
                                                        Duration.between(
                                                            entry.startedAt,
                                                            stoppedAt,
                                                        ),
                                                    )
                                                } ?: "Running",
                                            )
                                            entry.note.takeIf(String::isNotBlank)?.let { note ->
                                                append(" • ")
                                                append(note)
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                    if (entryConflicts.isNotEmpty()) {
                                        Text(
                                            if (entryConflicts.size == 1) {
                                                "Overlaps another entry"
                                            } else {
                                                "Overlaps ${entryConflicts.size} entries"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                if (entry.stoppedAt != null) {
                                    IconButton(
                                        onClick = {
                                            editingEntryId = entry.id.value
                                            showEditor = true
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("edit-time-entry-${entry.id.value}"),
                                    ) {
                                        Icon(
                                            Icons.Rounded.Edit,
                                            contentDescription = "Edit time entry",
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDelete(entry.id) },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("delete-time-entry-${entry.id.value}"),
                                    ) {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = "Delete time entry",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeEntryEditor(
    entry: TimeEntry?,
    onCancel: () -> Unit,
    onSave: (TimeEntryEdit) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val initialStart = remember(entry?.id?.value) {
        entry
            ?.startedAt
            ?.atZone(zone)
            ?: java.time.ZonedDateTime.now(zone)
                .withSecond(0)
                .withNano(0)
                .minusHours(1)
    }
    val initialDurationMinutes = remember(entry?.id?.value) {
        entry
            ?.stoppedAt
            ?.let { Duration.between(entry.startedAt, it).toMinutes() }
            ?.coerceAtLeast(1)
            ?: 60
    }
    var dateEpochDay by rememberSaveable(entry?.id?.value) {
        mutableStateOf(initialStart.toLocalDate().toEpochDay())
    }
    var startTimeText by rememberSaveable(entry?.id?.value) {
        mutableStateOf(initialStart.toLocalTime().format(TIME_ENTRY_TIME_FORMAT))
    }
    var durationText by rememberSaveable(entry?.id?.value) {
        mutableStateOf(initialDurationMinutes.toString())
    }
    var note by rememberSaveable(entry?.id?.value) {
        mutableStateOf(entry?.note.orEmpty())
    }
    var showDatePicker by rememberSaveable(entry?.id?.value) { mutableStateOf(false) }

    val date = LocalDate.ofEpochDay(dateEpochDay)
    val startTime = runCatching {
        LocalTime.parse(startTimeText, STRICT_TIME_ENTRY_TIME_FORMAT)
    }.getOrNull()
    val durationMinutes = durationText.toLongOrNull()
    val validationMessage = when {
        startTime == null -> "Use a 24-hour start time such as 09:30"
        durationMinutes == null || durationMinutes !in 1..MAX_MANUAL_DURATION_MINUTES ->
            "Use a duration from 1 to $MAX_MANUAL_DURATION_MINUTES minutes"
        note.length > MAX_TIME_ENTRY_NOTE_LENGTH ->
            "Keep the note under $MAX_TIME_ENTRY_NOTE_LENGTH characters"
        else -> null
    }
    val edit = if (validationMessage == null) {
        val startedAt = date
            .atTime(checkNotNull(startTime))
            .atZone(zone)
            .toInstant()
        TimeEntryEdit(
            startedAt = startedAt,
            stoppedAt = startedAt.plus(Duration.ofMinutes(checkNotNull(durationMinutes))),
            note = note.trim(),
        )
    } else {
        null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
    ) {
        Text(
            if (entry == null) "Add time entry" else "Edit time entry",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Times use ${zone.id}. Overlaps are kept visible until you correct them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { showDatePicker = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("time-entry-date"),
        ) {
            Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(date.format(TIME_ENTRY_DATE_FORMAT))
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = startTimeText,
                onValueChange = { startTimeText = it.take(5) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("time-entry-start"),
                label = { Text("Start (24-hour)") },
                placeholder = { Text("09:30") },
                singleLine = true,
            )
            OutlinedTextField(
                value = durationText,
                onValueChange = {
                    durationText = it.filter(Char::isDigit).take(5)
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("time-entry-duration"),
                label = { Text("Minutes") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(MAX_TIME_ENTRY_NOTE_LENGTH + 1) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("time-entry-note"),
            label = { Text("Note (optional)") },
            supportingText = {
                Text(
                    validationMessage.takeIf {
                        note.length > MAX_TIME_ENTRY_NOTE_LENGTH
                    } ?: "${note.length}/$MAX_TIME_ENTRY_NOTE_LENGTH",
                )
            },
            isError = note.length > MAX_TIME_ENTRY_NOTE_LENGTH,
            minLines = 2,
            maxLines = 4,
        )
        validationMessage
            ?.takeUnless { note.length > MAX_TIME_ENTRY_NOTE_LENGTH }
            ?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("time-entry-error"),
                )
            }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { edit?.let(onSave) },
            enabled = edit != null,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("save-time-entry"),
        ) {
            Text(if (entry == null) "Add entry" else "Save entry")
        }
        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("Cancel")
        }
    }

    if (showDatePicker) {
        val selectedDateMillis = date
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDateMillis,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selected ->
                            dateEpochDay = Instant.ofEpochMilli(selected)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toEpochDay()
                        }
                        showDatePicker = false
                    },
                ) {
                    Text("Use date")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun TaskChecklistRow(
    taskId: TaskId,
    item: ChecklistItem,
    onUpdate: (ChecklistItem) -> Unit,
    onDelete: () -> Unit,
) {
    var text by rememberSaveable(taskId.value, item.id) { mutableStateOf(item.text) }
    var lastSubmitted by remember(taskId.value, item.id) { mutableStateOf<String?>(null) }
    val normalizedText = text.trim()
    val error = when {
        normalizedText.isEmpty() -> "A checklist item needs text"
        normalizedText.length > MAX_CHECKLIST_ITEM_LENGTH ->
            "Keep checklist items under $MAX_CHECKLIST_ITEM_LENGTH characters"
        else -> null
    }
    val dirty = normalizedText != item.text

    LaunchedEffect(item.text) {
        if (lastSubmitted == item.text) {
            lastSubmitted = null
        } else {
            text = item.text
        }
    }

    LaunchedEffect(item.id, item.completed, normalizedText, error, dirty) {
        if (error != null || !dirty) return@LaunchedEffect
        delay(AUTO_SAVE_DELAY_MILLIS)
        lastSubmitted = normalizedText
        onUpdate(item.copy(text = normalizedText))
    }

    val latestText by rememberUpdatedState(normalizedText)
    val latestItem by rememberUpdatedState(item)
    val latestError by rememberUpdatedState(error)
    val latestSubmitted by rememberUpdatedState(lastSubmitted)
    val latestOnUpdate by rememberUpdatedState(onUpdate)
    DisposableEffect(taskId, item.id) {
        onDispose {
            if (
                latestError == null &&
                latestText != latestItem.text &&
                latestText != latestSubmitted
            ) {
                latestOnUpdate(latestItem.copy(text = latestText))
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Checkbox(
                checked = item.completed,
                onCheckedChange = { completed ->
                    onUpdate(item.copy(completed = completed))
                },
                modifier = Modifier.testTag("checklist-toggle-${item.id}"),
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it.take(MAX_CHECKLIST_ITEM_LENGTH + 1)
            },
            modifier = Modifier
                .weight(1f)
                .testTag("checklist-item-${item.id}"),
            label = { Text("Checklist item") },
            supportingText = when {
                error != null -> {
                    { Text(error) }
                }
                dirty -> {
                    { Text("Saving…") }
                }
                else -> null
            },
            isError = error != null,
            minLines = 1,
            maxLines = 3,
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .size(48.dp)
                .testTag("delete-checklist-item-${item.id}"),
        ) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = "Delete checklist item ${item.text}",
            )
        }
    }
}

@Composable
private fun SaveStatus(
    valid: Boolean,
    dirty: Boolean,
) {
    val (icon, label, tint) = when {
        !valid -> Triple(
            Icons.Rounded.Block,
            "Fix fields to save",
            MaterialTheme.colorScheme.error,
        )
        dirty -> Triple(
            Icons.Rounded.Schedule,
            "Saving…",
            MaterialTheme.colorScheme.secondary,
        )
        else -> Triple(
            Icons.Rounded.CheckCircle,
            "Saved on this device",
            MaterialTheme.colorScheme.tertiary,
        )
    }
    Row(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

private fun Task.toTaskEdit(reminder: Reminder?): TaskEdit = TaskEdit(
    title = title,
    description = description,
    projectId = projectId,
    priority = priority,
    due = due,
    recurrence = recurrence,
    estimate = estimate,
    milestoneId = milestoneId,
    reminder = reminder,
)

private fun reminderLeadSeconds(task: Task, reminder: Reminder?): Long? {
    val due = task.due ?: return null
    val triggerAt = reminder?.triggerAt ?: return null
    return Duration.between(triggerAt.instant, due.instant).seconds
}

private data class ReminderPreset(
    val leadSeconds: Long?,
    val label: String,
    val testTag: String,
)

private fun Priority.readableName(): String = name
    .lowercase(Locale.UK)
    .replaceFirstChar(Char::uppercase)

private fun RecurrenceFrequency.readableName(): String = name
    .lowercase(Locale.UK)
    .replaceFirstChar(Char::uppercase)

private fun RecurrenceFrequency.intervalUnit(interval: Int): String {
    val singular = when (this) {
        RecurrenceFrequency.DAILY -> "day"
        RecurrenceFrequency.WEEKLY -> "week"
        RecurrenceFrequency.MONTHLY -> "month"
        RecurrenceFrequency.YEARLY -> "year"
    }
    return if (interval == 1) singular else "${singular}s"
}

private fun DayOfWeek.shortName(): String =
    name.lowercase(Locale.UK).take(3).replaceFirstChar(Char::uppercase)

private fun formatRecurrence(rule: RecurrenceRule): String {
    val cadence = if (rule.interval == 1) {
        when (rule.frequency) {
            RecurrenceFrequency.DAILY -> "Every day"
            RecurrenceFrequency.WEEKLY -> "Every week"
            RecurrenceFrequency.MONTHLY -> "Every month"
            RecurrenceFrequency.YEARLY -> "Every year"
        }
    } else {
        "Every ${rule.interval} ${rule.frequency.intervalUnit(rule.interval)}"
    }
    val weekdays = rule.weekdays
        .sortedBy { it.value }
        .takeIf(List<DayOfWeek>::isNotEmpty)
        ?.joinToString(", ") { it.shortName() }
        ?.let { " on $it" }
        .orEmpty()
    val count = rule.count
    val endDate = rule.endDate
    val ending = when {
        count != null -> " • $count occurrences"
        endDate != null -> " • until ${endDate.format(DUE_DATE_FORMAT)}"
        else -> ""
    }
    return cadence + weekdays + ending
}

private fun SemanticStatus.statusIcon(): ImageVector = when (this) {
    SemanticStatus.BACKLOG -> Icons.Rounded.Inbox
    SemanticStatus.PLANNED -> Icons.Rounded.CalendarMonth
    SemanticStatus.STARTED -> Icons.Rounded.Schedule
    SemanticStatus.BLOCKED -> Icons.Rounded.Block
    SemanticStatus.COMPLETED -> Icons.Rounded.CheckCircle
}

private fun formatEstimate(minutes: Long): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60L == 0L -> "${minutes / 60} hr"
    else -> "${minutes / 60} hr ${minutes % 60} min"
}

private fun formatLoggedDuration(duration: Duration): String {
    val safe = duration.coerceAtLeast(Duration.ZERO)
    val hours = safe.toHours()
    val minutes = safe.toMinutesPart()
    return when {
        hours == 0L -> "$minutes min"
        minutes == 0 -> "$hours hr"
        else -> "$hours hr $minutes min"
    }
}

private fun formatTimeEntryRange(entry: TimeEntry): String {
    val zone = ZoneId.systemDefault()
    val start = entry.startedAt.atZone(zone)
    val stoppedAt = entry.stoppedAt
    if (stoppedAt == null) {
        return "${start.format(TIME_ENTRY_DATE_FORMAT)} • " +
            "${start.format(TIME_ENTRY_TIME_FORMAT)}–running"
    }
    val end = stoppedAt.atZone(zone)
    return if (start.toLocalDate() == end.toLocalDate()) {
        "${start.format(TIME_ENTRY_DATE_FORMAT)} • " +
            "${start.format(TIME_ENTRY_TIME_FORMAT)}–${end.format(TIME_ENTRY_TIME_FORMAT)}"
    } else {
        "${start.format(TIME_ENTRY_DATE_TIME_FORMAT)}–" +
            end.format(TIME_ENTRY_DATE_TIME_FORMAT)
    }
}

private const val MAX_TASK_TITLE_LENGTH = 240
private const val MAX_TASK_DESCRIPTION_LENGTH = 20_000
private const val MAX_CHECKLIST_ITEM_LENGTH = 500
private const val MAX_TAG_NAME_LENGTH = 64
private const val MAX_TASK_DEPENDENCIES = 100
private const val MAX_DEPENDENCY_QUERY_LENGTH = 500
private const val MAX_TIME_ENTRY_NOTE_LENGTH = 500
private const val MAX_MANUAL_DURATION_MINUTES = 10_080L
private val MILESTONE_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, d MMM", Locale.UK)
private const val MAX_RECURRENCE_INTERVAL = 999
private const val MAX_RECURRENCE_COUNT = 9_999
private const val AUTO_SAVE_DELAY_MILLIS = 650L
private val DUE_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.UK)
private val REMINDER_DATE_TIME_FORMAT =
    DateTimeFormatter.ofPattern("EEE, d MMM, HH:mm", Locale.UK)
private val TIME_ENTRY_DATE_FORMAT =
    DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.UK)
private val TIME_ENTRY_DATE_TIME_FORMAT =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)
private val TIME_ENTRY_TIME_FORMAT =
    DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val STRICT_TIME_ENTRY_TIME_FORMAT =
    DateTimeFormatterBuilder()
        .appendPattern("HH:mm")
        .toFormatter(Locale.UK)
        .withResolverStyle(ResolverStyle.STRICT)
private val ESTIMATE_OPTIONS = listOf<Long?>(null, 15, 30, 45, 60, 120, 180, 240)
private val REMINDER_PRESETS = listOf(
    ReminderPreset(null, "None", "none"),
    ReminderPreset(0, "At time", "at-time"),
    ReminderPreset(Duration.ofMinutes(15).seconds, "15 min before", "15-minutes"),
    ReminderPreset(Duration.ofHours(1).seconds, "1 hr before", "1-hour"),
    ReminderPreset(Duration.ofDays(1).seconds, "1 day before", "1-day"),
)
