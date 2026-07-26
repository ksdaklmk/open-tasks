package app.opentasks.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.EmptyState
import app.opentasks.core.designsystem.SectionHeader
import app.opentasks.core.designsystem.TaskRow
import app.opentasks.core.designsystem.readableName
import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
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

data class TaskEdit(
    val title: String,
    val description: String,
    val projectId: ProjectId?,
    val priority: Priority,
    val due: ZonedMoment?,
    val recurrence: RecurrenceRule?,
    val estimate: Duration?,
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
    projectNames: Map<ProjectId, String>,
    workflowStatuses: List<WorkflowStatus>,
    tags: List<Tag>,
    selectedTaskId: TaskId?,
    showDetailPane: Boolean,
    onSelectTask: (TaskId) -> Unit,
    onCloseDetail: () -> Unit,
    onCompleteTask: (Task) -> Unit,
    onChangeTaskStatus: (Task, WorkflowStatusId) -> Unit,
    onDeleteTask: (Task) -> Unit,
    activeTimerTaskId: TaskId?,
    onToggleTimer: (Task) -> Unit,
    onUpdateTask: (TaskId, TaskEdit) -> Unit,
    onAddChecklistItem: (TaskId, String) -> Unit,
    onUpdateChecklistItem: (TaskId, ChecklistItem) -> Unit,
    onDeleteChecklistItem: (TaskId, String) -> Unit,
    onSetTaskTag: (TaskId, TagId, Boolean) -> Unit,
    onCreateAndAssignTag: (TaskId, String) -> Unit,
    modifier: Modifier = Modifier,
    activeProjectIds: Set<ProjectId> = projectNames.keys,
) {
    var filter by rememberSaveable { mutableStateOf(TaskFilter.ALL) }
    val visibleTasks = when (filter) {
        TaskFilter.INBOX -> tasks.filter { it.projectId == null && it.deletedAt == null }
        TaskFilter.TODAY -> tasks.filter { it.due != null && !it.isCompleted && it.deletedAt == null }
        TaskFilter.UPCOMING -> tasks.filter { it.start != null && !it.isCompleted && it.deletedAt == null }
        TaskFilter.OVERDUE -> tasks.filter { it.priority >= app.opentasks.core.model.Priority.HIGH && !it.isCompleted }
        TaskFilter.ALL -> tasks.filter { it.deletedAt == null }
    }
    val selectedTask = tasks.firstOrNull { it.id == selectedTaskId }

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
                onChangeStatus = { onChangeTaskStatus(selectedTask, it) },
                onMoveToTrash = { onDeleteTask(selectedTask) },
                onUpdate = { onUpdateTask(selectedTask.id, it) },
                onAddChecklistItem = { onAddChecklistItem(selectedTask.id, it) },
                onUpdateChecklistItem = { onUpdateChecklistItem(selectedTask.id, it) },
                onDeleteChecklistItem = { onDeleteChecklistItem(selectedTask.id, it) },
                onSetTaskTag = { tagId, present ->
                    onSetTaskTag(selectedTask.id, tagId, present)
                },
                onCreateAndAssignTag = { onCreateAndAssignTag(selectedTask.id, it) },
            )
        }
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val listPaneWidth = when {
            maxWidth >= 840.dp -> 390.dp
            maxWidth >= 720.dp -> 360.dp
            else -> (maxWidth * 0.5f).coerceIn(300.dp, 340.dp)
        }
        Row(modifier = Modifier.fillMaxSize()) {
            TaskListPane(
                tasks = visibleTasks,
                projectNames = projectNames,
                selectedTaskId = selectedTaskId,
                filter = filter,
                onFilterChange = { filter = it },
                onSelectTask = onSelectTask,
                onCompleteTask = onCompleteTask,
                modifier = if (showDetailPane) {
                    Modifier.width(listPaneWidth)
                } else {
                    Modifier.fillMaxWidth()
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
                            .weight(1f)
                            .align(Alignment.CenterVertically),
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
                        onChangeStatus = { onChangeTaskStatus(selectedTask, it) },
                        onMoveToTrash = { onDeleteTask(selectedTask) },
                        onUpdate = { onUpdateTask(selectedTask.id, it) },
                        onAddChecklistItem = { onAddChecklistItem(selectedTask.id, it) },
                        onUpdateChecklistItem = { onUpdateChecklistItem(selectedTask.id, it) },
                        onDeleteChecklistItem = { onDeleteChecklistItem(selectedTask.id, it) },
                        onSetTaskTag = { tagId, present ->
                            onSetTaskTag(selectedTask.id, tagId, present)
                        },
                        onCreateAndAssignTag = { onCreateAndAssignTag(selectedTask.id, it) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskListPane(
    tasks: List<Task>,
    projectNames: Map<ProjectId, String>,
    selectedTaskId: TaskId?,
    filter: TaskFilter,
    onFilterChange: (TaskFilter) -> Unit,
    onSelectTask: (TaskId) -> Unit,
    onCompleteTask: (Task) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxHeight()) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
            Text(
                "Tasks",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "${tasks.count { !it.isCompleted }} open • ${tasks.count(Task::isBlocked)} blocked",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TaskFilter.entries.forEach { candidate ->
                    FilterChip(
                        selected = filter == candidate,
                        onClick = { onFilterChange(candidate) },
                        modifier = Modifier.heightIn(min = 48.dp),
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

        if (tasks.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.CheckCircle,
                title = "This view is clear",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    bottom = 104.dp,
                ),
            ) {
                items(tasks, key = { it.id.value }) { task ->
                    TaskRow(
                        task = task,
                        projectName = projectNames[task.projectId] ?: "Inbox",
                        selected = selectedTaskId == task.id,
                        onSelect = { onSelectTask(task.id) },
                        onComplete = { onCompleteTask(task) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailPane(
    task: Task,
    onBack: (() -> Unit)?,
    onComplete: () -> Unit,
    timerRunning: Boolean,
    onToggleTimer: () -> Unit,
    projectNames: Map<ProjectId, String>,
    activeProjectIds: Set<ProjectId>,
    workflowStatuses: List<WorkflowStatus>,
    tags: List<Tag>,
    onChangeStatus: (WorkflowStatusId) -> Unit,
    onMoveToTrash: () -> Unit,
    onUpdate: (TaskEdit) -> Unit,
    onAddChecklistItem: (String) -> Unit,
    onUpdateChecklistItem: (ChecklistItem) -> Unit,
    onDeleteChecklistItem: (String) -> Unit,
    onSetTaskTag: (TagId, Boolean) -> Unit,
    onCreateAndAssignTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
    var newTagName by rememberSaveable(task.id.value) { mutableStateOf("") }
    var newChecklistText by rememberSaveable(task.id.value) { mutableStateOf("") }
    var showDuePicker by rememberSaveable(task.id.value) { mutableStateOf(false) }
    var showRecurrenceEndPicker by rememberSaveable(task.id.value) {
        mutableStateOf(false)
    }
    var showProjectMenu by rememberSaveable(task.id.value) { mutableStateOf(false) }
    var showStatusMenu by rememberSaveable(task.id.value) { mutableStateOf(false) }
    var lastSubmitted by remember(task.id.value) { mutableStateOf<TaskEdit?>(null) }
    val activeWorkflowStatuses = workflowStatuses
        .filter { it.archivedAt == null }
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
    val editorValue = TaskEdit(
        title = title.trim(),
        description = description,
        projectId = projectIdValue?.let(::ProjectId),
        priority = Priority.valueOf(priorityName),
        due = editorDue,
        recurrence = editorRecurrence,
        estimate = estimateMinutes?.let(Duration::ofMinutes),
    )
    val persistedValue = task.toTaskEdit()
    val titleError = when {
        title.isBlank() -> "A task needs a title"
        title.trim().length > MAX_TASK_TITLE_LENGTH ->
            "Keep the title under $MAX_TASK_TITLE_LENGTH characters"
        else -> null
    }
    val descriptionError = description.length > MAX_TASK_DESCRIPTION_LENGTH
    val valid = titleError == null && !descriptionError && recurrenceError == null
    val dirty = editorValue != persistedValue

    LaunchedEffect(task.revision) {
        if (lastSubmitted == persistedValue) {
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
        }
    }

    LaunchedEffect(task.id, editorValue, valid) {
        if (!valid || !dirty) return@LaunchedEffect
        delay(AUTO_SAVE_DELAY_MILLIS)
        lastSubmitted = editorValue
        onUpdate(editorValue)
    }

    val latestEditor by rememberUpdatedState(editorValue)
    val latestTask by rememberUpdatedState(task)
    val latestValid by rememberUpdatedState(valid)
    val latestSubmitted by rememberUpdatedState(lastSubmitted)
    val latestOnUpdate by rememberUpdatedState(onUpdate)
    DisposableEffect(task.id) {
        onDispose {
            if (
                latestValid &&
                latestEditor != latestTask.toTaskEdit() &&
                latestEditor != latestSubmitted
            ) {
                latestOnUpdate(latestEditor)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
            onValueChange = { title = it },
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
            onValueChange = { description = it },
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
        SectionHeader("Organization")
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
                            projectIdValue = id.value
                            showProjectMenu = false
                        },
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
            onValueChange = { newTagName = it },
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
                        .testTag("recurrence-frequency-${frequency.name.lowercase()}"),
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
                    recurrenceIntervalText = value.filter(Char::isDigit)
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
                                    "recurrence-weekday-${weekday.name.lowercase()}",
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
                            .testTag("recurrence-end-${mode.name.lowercase()}"),
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
                            recurrenceCountText = value.filter(Char::isDigit)
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
        SectionHeader(
            title = "Checklist",
            supportingText = "${task.checklist.count { it.completed }}/${task.checklist.size} complete",
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = newChecklistText,
            onValueChange = { newChecklistText = it },
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
                        "This task has unfinished dependencies. You can still complete it after confirming the warning.",
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
            OutlinedButton(
                onClick = onToggleTimer,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.Timer, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (timerRunning) "Stop timer" else "Start timer")
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
                Text("Move to Trash", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(80.dp))
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
            onValueChange = { text = it },
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

private fun Task.toTaskEdit(): TaskEdit = TaskEdit(
    title = title,
    description = description,
    projectId = projectId,
    priority = priority,
    due = due,
    recurrence = recurrence,
    estimate = estimate,
)

private fun Priority.readableName(): String = name
    .lowercase()
    .replaceFirstChar(Char::uppercase)

private fun RecurrenceFrequency.readableName(): String = name
    .lowercase()
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
    name.lowercase().take(3).replaceFirstChar(Char::uppercase)

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

private const val MAX_TASK_TITLE_LENGTH = 240
private const val MAX_TASK_DESCRIPTION_LENGTH = 20_000
private const val MAX_CHECKLIST_ITEM_LENGTH = 500
private const val MAX_TAG_NAME_LENGTH = 64
private const val MAX_RECURRENCE_INTERVAL = 999
private const val MAX_RECURRENCE_COUNT = 9_999
private const val AUTO_SAVE_DELAY_MILLIS = 650L
private val DUE_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
private val ESTIMATE_OPTIONS = listOf<Long?>(null, 15, 30, 45, 60, 120, 180, 240)
