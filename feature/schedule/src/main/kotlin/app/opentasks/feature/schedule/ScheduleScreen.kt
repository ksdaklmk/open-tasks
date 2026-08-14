package app.opentasks.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.EmptyState
import app.opentasks.core.designsystem.SectionHeader
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.ScheduleMonthProjection
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.ZonedMoment
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class SchedulePresentation { WEEK, MONTH }

@Composable
fun ScheduleScreen(
    tasks: List<Task>,
    projectNames: Map<ProjectId, String>,
    expanded: Boolean,
    presentation: SchedulePresentation,
    selectedDate: LocalDate,
    month: ScheduleMonthProjection,
    onPresentationChange: (SchedulePresentation) -> Unit,
    onSelectedDateChange: (LocalDate) -> Unit,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
    reminders: List<Reminder> = emptyList(),
    today: LocalDate = LocalDate.now(),
    calendarEligibleTaskIds: Set<TaskId> = emptySet(),
    onAddToCalendar: (TaskId) -> Unit = {},
    onRescheduleTask: (TaskId, LocalDate) -> Unit = { _, _ -> },
    onRemoveTaskSchedule: (TaskId) -> Unit = {},
) {
    val activeTasks = tasks.filter { it.deletedAt == null }
    val scheduled = activeTasks.filter { it.scheduleMoment() != null }
    val unscheduled = activeTasks
        .filter { it.start == null && it.due == null && !it.isCompleted }
        .sortedWith(
            compareByDescending<Task> { it.priority.ordinal }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    val remindersByTask = reminders.associateBy(Reminder::taskId)

    when (presentation) {
        SchedulePresentation.MONTH -> MonthCalendar(
            month = month,
            selectedDate = selectedDate,
            expanded = expanded,
            unscheduled = unscheduled,
            projectNames = projectNames,
            remindersByTask = remindersByTask,
            presentation = presentation,
            onPresentationChange = onPresentationChange,
            onSelectedDateChange = onSelectedDateChange,
            onPrevious = onPrevious,
            onToday = onToday,
            onNext = onNext,
            onOpenTask = onOpenTask,
            calendarEligibleTaskIds = calendarEligibleTaskIds,
            onAddToCalendar = onAddToCalendar,
            onRescheduleTask = onRescheduleTask,
            onRemoveTaskSchedule = onRemoveTaskSchedule,
            modifier = modifier,
        )

        SchedulePresentation.WEEK -> if (expanded) {
            ExpandedWeek(
                tasks = scheduled,
                unscheduled = unscheduled,
                projectNames = projectNames,
                remindersByTask = remindersByTask,
                presentation = presentation,
                selectedDate = selectedDate,
                today = today,
                onPresentationChange = onPresentationChange,
                onPrevious = onPrevious,
                onToday = onToday,
                onNext = onNext,
                onOpenTask = onOpenTask,
                calendarEligibleTaskIds = calendarEligibleTaskIds,
                onAddToCalendar = onAddToCalendar,
                onRescheduleTask = onRescheduleTask,
                onRemoveTaskSchedule = onRemoveTaskSchedule,
                modifier = modifier,
            )
        } else {
            CompactAgenda(
                tasks = scheduled,
                unscheduledCount = unscheduled.size,
                projectNames = projectNames,
                remindersByTask = remindersByTask,
                presentation = presentation,
                selectedDate = selectedDate,
                onPresentationChange = onPresentationChange,
                onSelectDate = onSelectedDateChange,
                onPrevious = onPrevious,
                onToday = onToday,
                onNext = onNext,
                onOpenTask = onOpenTask,
                calendarEligibleTaskIds = calendarEligibleTaskIds,
                onAddToCalendar = onAddToCalendar,
                onRescheduleTask = onRescheduleTask,
                onRemoveTaskSchedule = onRemoveTaskSchedule,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun CompactAgenda(
    tasks: List<Task>,
    unscheduledCount: Int,
    projectNames: Map<ProjectId, String>,
    remindersByTask: Map<TaskId, Reminder>,
    presentation: SchedulePresentation,
    selectedDate: LocalDate,
    onPresentationChange: (SchedulePresentation) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    calendarEligibleTaskIds: Set<TaskId>,
    onAddToCalendar: (TaskId) -> Unit,
    onRescheduleTask: (TaskId, LocalDate) -> Unit,
    onRemoveTaskSchedule: (TaskId) -> Unit,
    modifier: Modifier,
) {
    val selectedTasks = tasks
        .filter { it.scheduleDate() == selectedDate }
        .sortedWith(scheduleTaskComparator)
    val weekStart = selectedDate.startOfWeek()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("compact-day-agenda"),
    ) {
        ScheduleTitle(
            dateLabel = selectedDate.format(FULL_DATE_FORMAT),
            presentation = presentation,
            onPresentationChange = onPresentationChange,
            previousLabel = "Previous day",
            nextLabel = "Next day",
            onPrevious = onPrevious,
            onToday = onToday,
            onNext = onNext,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("schedule-day-picker"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (0L..6L).forEach { dayOffset ->
                val date = weekStart.plusDays(dayOffset)
                FilterChip(
                    selected = date == selectedDate,
                    onClick = { onSelectDate(date) },
                    label = {
                        Text(
                            date.format(DAY_CHIP_FORMAT),
                            maxLines = 2,
                        )
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("schedule-day-$date"),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        SectionHeader(
            "Day agenda",
            supportingText = itemCountLabel(selectedTasks.size, "scheduled item"),
        )
        Spacer(Modifier.height(8.dp))
        if (selectedTasks.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.CalendarMonth,
                title = "No dated work",
            )
        } else {
            selectedTasks.forEach { task ->
                AgendaRow(
                    task = task,
                    projectName = projectNames[task.projectId] ?: "Inbox",
                    reminder = remindersByTask[task.id],
                    initialDate = selectedDate,
                    onClick = { onOpenTask(task.id) },
                    onAddToCalendar = { onAddToCalendar(task.id) }
                        .takeIf { task.id in calendarEligibleTaskIds },
                    onRescheduleTask = onRescheduleTask,
                    onRemoveTaskSchedule = onRemoveTaskSchedule,
                )
            }
        }
        if (unscheduledCount > 0) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                itemCountLabel(unscheduledCount, "open unscheduled task"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExpandedWeek(
    tasks: List<Task>,
    unscheduled: List<Task>,
    projectNames: Map<ProjectId, String>,
    remindersByTask: Map<TaskId, Reminder>,
    presentation: SchedulePresentation,
    selectedDate: LocalDate,
    today: LocalDate,
    onPresentationChange: (SchedulePresentation) -> Unit,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    calendarEligibleTaskIds: Set<TaskId>,
    onAddToCalendar: (TaskId) -> Unit,
    onRescheduleTask: (TaskId, LocalDate) -> Unit,
    onRemoveTaskSchedule: (TaskId) -> Unit,
    modifier: Modifier,
) {
    val weekStart = selectedDate.startOfWeek()
    val weekDates = (0L..6L).map(weekStart::plusDays)

    Row(
        modifier = modifier
            .fillMaxSize()
            .testTag("expanded-week-schedule"),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 24.dp, top = 24.dp, end = 20.dp, bottom = 24.dp),
        ) {
            ScheduleTitle(
                dateLabel = weekRangeLabel(weekStart),
                presentation = presentation,
                onPresentationChange = onPresentationChange,
                previousLabel = "Previous week",
                nextLabel = "Next week",
                onPrevious = onPrevious,
                onToday = onToday,
                onNext = onNext,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .testTag("week-timeline"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                weekDates.forEach { date ->
                    DayColumn(
                        date = date,
                        today = today,
                        tasks = tasks
                            .filter { it.scheduleDate() == date }
                            .sortedWith(scheduleTaskComparator),
                        projectNames = projectNames,
                        remindersByTask = remindersByTask,
                        onOpenTask = onOpenTask,
                        calendarEligibleTaskIds = calendarEligibleTaskIds,
                        onAddToCalendar = onAddToCalendar,
                        onRescheduleTask = onRescheduleTask,
                        onRemoveTaskSchedule = onRemoveTaskSchedule,
                    )
                }
            }
        }
        VerticalDivider(modifier = Modifier.fillMaxHeight())
        UnscheduledTray(
            tasks = unscheduled,
            projectNames = projectNames,
            onOpenTask = onOpenTask,
            initialDate = selectedDate,
            onRescheduleTask = onRescheduleTask,
            onRemoveTaskSchedule = onRemoveTaskSchedule,
        )
    }
}

@Composable
internal fun ScheduleTitle(
    dateLabel: String,
    presentation: SchedulePresentation,
    onPresentationChange: (SchedulePresentation) -> Unit,
    previousLabel: String,
    nextLabel: String,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
) {
    Text(
        "Schedule",
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.semantics { heading() },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SchedulePresentation.entries.forEach { option ->
            FilterChip(
                selected = presentation == option,
                onClick = { onPresentationChange(option) },
                label = {
                    Text(
                        stringResource(
                            when (option) {
                                SchedulePresentation.WEEK -> R.string.schedule_week
                                SchedulePresentation.MONTH -> R.string.schedule_month
                            },
                        ),
                    )
                },
                modifier = Modifier
                    .widthIn(min = 48.dp)
                    .heightIn(min = 48.dp)
                    .testTag("schedule-presentation-${option.name.lowercase(Locale.ROOT)}"),
            )
        }
    }
    Text(
        dateLabel,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = onPrevious,
            modifier = Modifier
                .size(48.dp)
                .testTag("schedule-previous"),
        ) {
            Icon(Icons.Rounded.ChevronLeft, contentDescription = previousLabel)
        }
        TextButton(
            onClick = onToday,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("schedule-today"),
        ) {
            Text("Today")
        }
        IconButton(
            onClick = onNext,
            modifier = Modifier
                .size(48.dp)
                .testTag("schedule-next"),
        ) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = nextLabel)
        }
    }
}

@Composable
private fun DayColumn(
    date: LocalDate,
    today: LocalDate,
    tasks: List<Task>,
    projectNames: Map<ProjectId, String>,
    remindersByTask: Map<TaskId, Reminder>,
    onOpenTask: (TaskId) -> Unit,
    calendarEligibleTaskIds: Set<TaskId>,
    onAddToCalendar: (TaskId) -> Unit,
    onRescheduleTask: (TaskId, LocalDate) -> Unit,
    onRemoveTaskSchedule: (TaskId) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(176.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .testTag("schedule-column-$date"),
    ) {
        Surface(
            color = if (date == today) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    date.format(WEEKDAY_FORMAT),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (date == today) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    date.format(DAY_MONTH_FORMAT),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        if (tasks.isEmpty()) {
            Text(
                "No items",
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            tasks.forEach { task ->
                TimelineTask(
                    task = task,
                    projectName = projectNames[task.projectId] ?: "Inbox",
                    reminder = remindersByTask[task.id],
                    initialDate = date,
                    onClick = { onOpenTask(task.id) },
                    onAddToCalendar = { onAddToCalendar(task.id) }
                        .takeIf { task.id in calendarEligibleTaskIds },
                    onRescheduleTask = onRescheduleTask,
                    onRemoveTaskSchedule = onRemoveTaskSchedule,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TimelineTask(
    task: Task,
    projectName: String,
    reminder: Reminder?,
    initialDate: LocalDate,
    onClick: () -> Unit,
    onAddToCalendar: (() -> Unit)? = null,
    onRescheduleTask: (TaskId, LocalDate) -> Unit,
    onRemoveTaskSchedule: (TaskId) -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .testTag("schedule-task-${task.id.value}"),
        color = if (task.isCompleted) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    task.scheduleLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    reminder?.let {
                        Icon(
                            Icons.Rounded.NotificationsActive,
                            contentDescription = it.reminderDescription(),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (onAddToCalendar != null) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = onAddToCalendar,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("schedule-add-to-calendar-${task.id.value}"),
                        ) {
                            Icon(
                                Icons.Rounded.CalendarMonth,
                                contentDescription = stringResource(R.string.schedule_add_to_calendar),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    ScheduleTaskActions(
                        task = task,
                        reminder = reminder,
                        initialDate = initialDate,
                        onRescheduleTask = onRescheduleTask,
                        onRemoveTaskSchedule = onRemoveTaskSchedule,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(task.title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                projectName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (task.isCompleted || task.isBlocked) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (task.isCompleted) "Complete" else "Blocked",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (task.isBlocked) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
internal fun UnscheduledTray(
    tasks: List<Task>,
    projectNames: Map<ProjectId, String>,
    onOpenTask: (TaskId) -> Unit,
    initialDate: LocalDate,
    onRescheduleTask: (TaskId, LocalDate) -> Unit,
    onRemoveTaskSchedule: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(min = 280.dp, max = 340.dp)
            .fillMaxHeight()
            .padding(20.dp)
            .testTag("unscheduled-tray"),
    ) {
        SectionHeader(
            title = "Unscheduled",
            supportingText = itemCountLabel(tasks.size, "open task"),
        )
        Spacer(Modifier.height(12.dp))
        if (tasks.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.CalendarMonth,
                title = "Everything has a date",
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tasks, key = { it.id.value }) { task ->
                    Surface(
                        onClick = { onOpenTask(task.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .testTag("unscheduled-task-${task.id.value}")
                            .semantics { role = Role.Button },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(task.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    projectNames[task.projectId] ?: "Inbox",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            ScheduleTaskActions(
                                task = task,
                                reminder = null,
                                initialDate = initialDate,
                                onRescheduleTask = onRescheduleTask,
                                onRemoveTaskSchedule = onRemoveTaskSchedule,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AgendaRow(
    task: Task,
    projectName: String,
    reminder: Reminder?,
    initialDate: LocalDate,
    onClick: () -> Unit,
    onAddToCalendar: (() -> Unit)? = null,
    onRescheduleTask: (TaskId, LocalDate) -> Unit,
    onRemoveTaskSchedule: (TaskId) -> Unit,
) {
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag("schedule-task-${task.id.value}"),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.width(80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = when {
                        task.isCompleted -> Icons.Rounded.CheckCircle
                        task.isBlocked -> Icons.Rounded.Block
                        else -> Icons.Rounded.Schedule
                    },
                    contentDescription = when {
                        task.isCompleted -> "Complete"
                        task.isBlocked -> "Blocked"
                        else -> null
                    },
                    tint = when {
                        task.isBlocked -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.secondary
                    },
                )
                Text(
                    task.scheduleLabel(),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Box(
                Modifier
                    .width(1.dp)
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            ) {
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    projectName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            reminder?.let {
                Icon(
                    Icons.Rounded.NotificationsActive,
                    contentDescription = it.reminderDescription(),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(24.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
            if (onAddToCalendar != null) {
                IconButton(
                    onClick = onAddToCalendar,
                    modifier = Modifier.testTag("schedule-add-to-calendar-${task.id.value}"),
                ) {
                    Icon(
                        Icons.Rounded.CalendarMonth,
                        contentDescription = stringResource(R.string.schedule_add_to_calendar),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            ScheduleTaskActions(
                task = task,
                reminder = reminder,
                initialDate = initialDate,
                onRescheduleTask = onRescheduleTask,
                onRemoveTaskSchedule = onRemoveTaskSchedule,
            )
        }
    }
}

private fun LocalDate.startOfWeek(): LocalDate =
    with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

private fun Task.scheduleMoment(): ZonedMoment? = start ?: due

private fun Task.scheduleDate(): LocalDate? =
    scheduleMoment()?.let { moment ->
        moment.instant.atZone(ZoneId.of(moment.zoneId)).toLocalDate()
    }

private fun Task.scheduleTime(): LocalTime? =
    scheduleMoment()?.let { moment ->
        moment.instant.atZone(ZoneId.of(moment.zoneId)).toLocalTime()
    }

private fun Task.scheduleLabel(): String {
    val time = scheduleTime()?.format(TIME_FORMAT) ?: return "Any time"
    return if (start == null && due != null) "Due $time" else time
}

private fun Reminder.reminderDescription(): String {
    val time = triggerAt.instant
        .atZone(ZoneId.of(triggerAt.zoneId))
        .format(TIME_FORMAT)
    return "Reminder set for $time"
}

internal fun itemCountLabel(count: Int, singular: String): String =
    "$count $singular${if (count == 1) "" else "s"}"

private fun weekRangeLabel(start: LocalDate): String {
    val end = start.plusDays(6)
    return if (start.month == end.month) {
        "${start.dayOfMonth}–${end.format(DAY_MONTH_YEAR_FORMAT)}"
    } else {
        "${start.format(DAY_MONTH_FORMAT)} – ${end.format(DAY_MONTH_YEAR_FORMAT)}"
    }
}

private val scheduleTaskComparator = compareBy<Task>(
    { it.scheduleTime() ?: LocalTime.MAX },
    { it.title.lowercase(Locale.UK) },
)

private val FULL_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.UK)
private val DAY_CHIP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE\nd", Locale.UK)
private val WEEKDAY_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE", Locale.UK)
private val DAY_MONTH_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", Locale.UK)
private val DAY_MONTH_YEAR_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM uuuu", Locale.UK)
private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
