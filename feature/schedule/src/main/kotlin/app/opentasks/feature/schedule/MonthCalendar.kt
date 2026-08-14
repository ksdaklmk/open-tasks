package app.opentasks.feature.schedule

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.DotRunBar
import app.opentasks.core.designsystem.EmptyState
import app.opentasks.core.designsystem.SectionHeader
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.ScheduleMonthDay
import app.opentasks.core.model.ScheduleMonthProjection
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun MonthCalendar(
    month: ScheduleMonthProjection,
    selectedDate: LocalDate,
    expanded: Boolean,
    unscheduled: List<Task>,
    projectNames: Map<ProjectId, String>,
    remindersByTask: Map<TaskId, Reminder>,
    presentation: SchedulePresentation,
    onPresentationChange: (SchedulePresentation) -> Unit,
    onSelectedDateChange: (LocalDate) -> Unit,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    calendarEligibleTaskIds: Set<TaskId>,
    onAddToCalendar: (TaskId) -> Unit,
    onRescheduleTask: (TaskId, LocalDate) -> Unit,
    onRemoveTaskSchedule: (TaskId) -> Unit,
    dragBinding: ScheduleDragBinding?,
    modifier: Modifier,
) {
    if (expanded) {
        ExpandedMonth(
            month = month,
            selectedDate = selectedDate,
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
            dragBinding = dragBinding,
            modifier = modifier,
        )
    } else {
        CompactMonth(
            month = month,
            selectedDate = selectedDate,
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
            dragBinding = dragBinding,
            modifier = modifier,
        )
    }
}

@Composable
private fun CompactMonth(
    month: ScheduleMonthProjection,
    selectedDate: LocalDate,
    projectNames: Map<ProjectId, String>,
    remindersByTask: Map<TaskId, Reminder>,
    presentation: SchedulePresentation,
    onPresentationChange: (SchedulePresentation) -> Unit,
    onSelectedDateChange: (LocalDate) -> Unit,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    calendarEligibleTaskIds: Set<TaskId>,
    onAddToCalendar: (TaskId) -> Unit,
    onRescheduleTask: (TaskId, LocalDate) -> Unit,
    onRemoveTaskSchedule: (TaskId) -> Unit,
    dragBinding: ScheduleDragBinding?,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("compact-month-schedule"),
    ) {
        MonthTitle(
            month = month,
            presentation = presentation,
            onPresentationChange = onPresentationChange,
            onPrevious = onPrevious,
            onToday = onToday,
            onNext = onNext,
        )
        Spacer(Modifier.height(16.dp))
        MonthGrid(
            month = month,
            selectedDate = selectedDate,
            onSelectedDateChange = onSelectedDateChange,
            dragBinding = dragBinding,
        )
        Spacer(Modifier.height(24.dp))
        MonthAgenda(
            selectedDate = selectedDate,
            tasks = month.tasksFor(selectedDate),
            projectNames = projectNames,
            remindersByTask = remindersByTask,
            onOpenTask = onOpenTask,
            calendarEligibleTaskIds = calendarEligibleTaskIds,
            onAddToCalendar = onAddToCalendar,
            onRescheduleTask = onRescheduleTask,
            onRemoveTaskSchedule = onRemoveTaskSchedule,
            dragBinding = dragBinding,
            lazy = false,
        )
    }
}

@Composable
private fun ExpandedMonth(
    month: ScheduleMonthProjection,
    selectedDate: LocalDate,
    unscheduled: List<Task>,
    projectNames: Map<ProjectId, String>,
    remindersByTask: Map<TaskId, Reminder>,
    presentation: SchedulePresentation,
    onPresentationChange: (SchedulePresentation) -> Unit,
    onSelectedDateChange: (LocalDate) -> Unit,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    calendarEligibleTaskIds: Set<TaskId>,
    onAddToCalendar: (TaskId) -> Unit,
    onRescheduleTask: (TaskId, LocalDate) -> Unit,
    onRemoveTaskSchedule: (TaskId) -> Unit,
    dragBinding: ScheduleDragBinding?,
    modifier: Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .testTag("expanded-month-schedule"),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, top = 24.dp, end = 20.dp, bottom = 24.dp),
        ) {
            MonthTitle(
                month = month,
                presentation = presentation,
                onPresentationChange = onPresentationChange,
                onPrevious = onPrevious,
                onToday = onToday,
                onNext = onNext,
            )
            Spacer(Modifier.height(20.dp))
            MonthGrid(
                month = month,
                selectedDate = selectedDate,
                onSelectedDateChange = onSelectedDateChange,
                dragBinding = dragBinding,
            )
        }
        VerticalDivider(modifier = Modifier.fillMaxHeight())
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 340.dp)
                .fillMaxHeight(),
        ) {
            MonthAgenda(
                selectedDate = selectedDate,
                tasks = month.tasksFor(selectedDate),
                projectNames = projectNames,
                remindersByTask = remindersByTask,
                onOpenTask = onOpenTask,
                calendarEligibleTaskIds = calendarEligibleTaskIds,
                onAddToCalendar = onAddToCalendar,
                onRescheduleTask = onRescheduleTask,
                onRemoveTaskSchedule = onRemoveTaskSchedule,
                dragBinding = dragBinding,
                lazy = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp),
            )
            HorizontalDivider()
            UnscheduledTray(
                tasks = unscheduled,
                projectNames = projectNames,
                onOpenTask = onOpenTask,
                initialDate = selectedDate,
                onRescheduleTask = onRescheduleTask,
                onRemoveTaskSchedule = onRemoveTaskSchedule,
                dragBinding = dragBinding,
                isDropTarget = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MonthTitle(
    month: ScheduleMonthProjection,
    presentation: SchedulePresentation,
    onPresentationChange: (SchedulePresentation) -> Unit,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
) {
    ScheduleTitle(
        dateLabel = month.month.format(MONTH_FORMAT),
        presentation = presentation,
        onPresentationChange = onPresentationChange,
        previousLabel = stringResource(R.string.schedule_previous_month),
        nextLabel = stringResource(R.string.schedule_next_month),
        onPrevious = onPrevious,
        onToday = onToday,
        onNext = onNext,
    )
}

@Composable
private fun MonthGrid(
    month: ScheduleMonthProjection,
    selectedDate: LocalDate,
    onSelectedDateChange: (LocalDate) -> Unit,
    dragBinding: ScheduleDragBinding?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cellWidth = maxOf(48.dp, maxWidth / 7)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("schedule-month-grid"),
        ) {
            Row(Modifier.width(cellWidth * 7)) {
                WEEKDAYS.forEach { day ->
                    Text(
                        day.getDisplayName(java.time.format.TextStyle.SHORT, Locale.UK),
                        modifier = Modifier
                            .width(cellWidth)
                            .padding(vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            month.days.chunked(7).forEach { week ->
                Row(Modifier.width(cellWidth * 7)) {
                    week.forEach { day ->
                        MonthDayCell(
                            day = day,
                            selected = day.date == selectedDate,
                            onClick = { onSelectedDateChange(day.date) },
                            dragBinding = dragBinding,
                            modifier = Modifier.width(cellWidth),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthDayCell(
    day: ScheduleMonthDay,
    selected: Boolean,
    onClick: () -> Unit,
    dragBinding: ScheduleDragBinding?,
    modifier: Modifier = Modifier,
) {
    val fullDate = day.date.format(FULL_DATE_FORMAT)
    val taskCount = pluralStringResource(
        R.plurals.schedule_month_task_count,
        day.totalCount,
        day.totalCount,
    )
    val description = stringResource(
        R.string.schedule_month_day_description,
        fullDate,
        taskCount,
        day.completedCount,
        day.overdueCount,
    )
    val adjacentDescription = stringResource(R.string.schedule_adjacent_month)

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(64.dp)
            .onGloballyPositioned {
                dragBinding?.onTargetBounds(
                    ScheduleDropTarget.Day(day.date),
                    it.boundsInRoot(),
                )
            }
            .testTag("schedule-month-day-${day.date}")
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
                role = Role.Button
                if (!day.inSelectedMonth) stateDescription = adjacentDescription
            },
        color = when {
            selected -> MaterialTheme.colorScheme.secondaryContainer
            day.inSelectedMonth -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    day.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        selected -> MaterialTheme.colorScheme.onSecondaryContainer
                        day.inSelectedMonth -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (day.hasDensityOverflow) {
                    Text(
                        stringResource(R.string.schedule_month_overflow),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (day.densityDotCount > 0) {
                DotRunBar(
                    progress = 1f,
                    unitCount = day.densityDotCount.toLong(),
                    maxDots = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clearAndSetSemantics {},
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (day.completedCount > 0) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        day.completedCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (day.overdueCount > 0) {
                    Icon(
                        Icons.Rounded.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        stringResource(
                            R.string.schedule_month_overdue_count,
                            day.overdueCount,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthAgenda(
    selectedDate: LocalDate,
    tasks: List<Task>,
    projectNames: Map<ProjectId, String>,
    remindersByTask: Map<TaskId, Reminder>,
    onOpenTask: (TaskId) -> Unit,
    calendarEligibleTaskIds: Set<TaskId>,
    onAddToCalendar: (TaskId) -> Unit,
    onRescheduleTask: (TaskId, LocalDate) -> Unit,
    onRemoveTaskSchedule: (TaskId) -> Unit,
    dragBinding: ScheduleDragBinding?,
    lazy: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("month-selected-agenda")) {
        SectionHeader(
            title = stringResource(R.string.schedule_month_agenda),
            supportingText = "${selectedDate.format(FULL_DATE_FORMAT)} · " +
                itemCountLabel(tasks.size, "scheduled item"),
        )
        Spacer(Modifier.height(8.dp))
        if (tasks.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.CalendarMonth,
                title = stringResource(R.string.schedule_month_no_dated_work),
            )
        } else if (lazy) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(tasks, key = { it.id.value }) { task ->
                    MonthAgendaRow(
                        task = task,
                        projectNames = projectNames,
                        remindersByTask = remindersByTask,
                        initialDate = selectedDate,
                        onOpenTask = onOpenTask,
                        calendarEligibleTaskIds = calendarEligibleTaskIds,
                        onAddToCalendar = onAddToCalendar,
                        onRescheduleTask = onRescheduleTask,
                        onRemoveTaskSchedule = onRemoveTaskSchedule,
                        dragBinding = dragBinding,
                    )
                }
            }
        } else {
            tasks.forEach { task ->
                MonthAgendaRow(
                    task = task,
                    projectNames = projectNames,
                    remindersByTask = remindersByTask,
                    initialDate = selectedDate,
                    onOpenTask = onOpenTask,
                    calendarEligibleTaskIds = calendarEligibleTaskIds,
                    onAddToCalendar = onAddToCalendar,
                    onRescheduleTask = onRescheduleTask,
                    onRemoveTaskSchedule = onRemoveTaskSchedule,
                    dragBinding = dragBinding,
                )
            }
        }
    }
}

@Composable
private fun MonthAgendaRow(
    task: Task,
    projectNames: Map<ProjectId, String>,
    remindersByTask: Map<TaskId, Reminder>,
    initialDate: LocalDate,
    onOpenTask: (TaskId) -> Unit,
    calendarEligibleTaskIds: Set<TaskId>,
    onAddToCalendar: (TaskId) -> Unit,
    onRescheduleTask: (TaskId, LocalDate) -> Unit,
    onRemoveTaskSchedule: (TaskId) -> Unit,
    dragBinding: ScheduleDragBinding?,
) {
    AgendaRow(
        task = task,
        projectName = projectNames[task.projectId] ?: "Inbox",
        reminder = remindersByTask[task.id],
        initialDate = initialDate,
        onClick = { onOpenTask(task.id) },
        onAddToCalendar = { onAddToCalendar(task.id) }
            .takeIf { task.id in calendarEligibleTaskIds },
        onRescheduleTask = onRescheduleTask,
        onRemoveTaskSchedule = onRemoveTaskSchedule,
        dragBinding = dragBinding,
        dragSource = ScheduleDropTarget.Day(initialDate),
    )
}

private fun ScheduleMonthProjection.tasksFor(date: LocalDate): List<Task> =
    days.firstOrNull { it.date == date }?.tasks.orEmpty()

private val WEEKDAYS = listOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY,
    DayOfWeek.SUNDAY,
)
private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM uuuu", Locale.UK)
private val FULL_DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM uuuu", Locale.UK)
