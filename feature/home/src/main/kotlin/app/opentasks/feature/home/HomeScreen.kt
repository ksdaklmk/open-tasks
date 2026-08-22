package app.opentasks.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.opentasks.core.designsystem.OpenTasksColors
import app.opentasks.core.designsystem.ProjectProgressRow
import app.opentasks.core.designsystem.RootDragPreview
import app.opentasks.core.designsystem.RootDragState
import app.opentasks.core.designsystem.SectionHeader
import app.opentasks.core.designsystem.TaskRow
import app.opentasks.core.designsystem.dragTargetAt
import app.opentasks.core.designsystem.rootLongPressDragSource
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.InsightsSnapshot
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    snapshot: HomeSnapshot,
    projectNames: Map<ProjectId, String>,
    onOpenSearch: () -> Unit,
    onPlanToday: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    onCompleteTask: (Task) -> Unit,
    onOpenProject: (ProjectId) -> Unit,
    insightsSummary: InsightsSnapshot,
    onOpenInsights: () -> Unit,
    onToggleTimer: () -> Unit,
    onRemoveFromMyDay: (TaskId) -> Unit,
    onMoveMyDayEntry: (taskId: TaskId, afterTaskId: TaskId?) -> Unit,
    suggestions: List<Task>,
    onAddToMyDay: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
    clock: Clock = Clock.systemDefaultZone(),
) {
    val myDayRowBounds = remember { mutableStateMapOf<TaskId, Rect>() }
    var myDayBounds by remember { mutableStateOf(Rect.Zero) }
    var myDayDrag by remember { mutableStateOf<RootDragState<Task>?>(null) }
    val currentOnMoveMyDayEntry by rememberUpdatedState(onMoveMyDayEntry)

    fun myDayDropTarget(drag: RootDragState<Task>): TaskId? = dragTargetAt(
        positionInRoot = drag.positionInRoot,
        targets = snapshot.myDayTasks.map { it.id },
        bounds = myDayRowBounds,
        eligible = { it != drag.payload.id },
    )

    fun finishMyDayDrag() {
        val drag = myDayDrag
        val target = drag?.let(::myDayDropTarget)
        if (drag != null && target != null) {
            val hoveredIndex = snapshot.myDayTasks.indexOfFirst { it.id == target }
            val afterTaskId = snapshot.myDayTasks.getOrNull(hoveredIndex - 1)?.id
            // Hovering the dragged row's own immediate successor resolves
            // afterTaskId back to the dragged row itself (it was that row's
            // predecessor before the drag started). MoveMyDayEntry rejects a
            // self-referential afterTaskId as NOT_FOUND, so treat this as
            // the same-slot no-op it visually is rather than dispatch it.
            if (afterTaskId != drag.payload.id) {
                currentOnMoveMyDayEntry(drag.payload.id, afterTaskId)
            }
        }
        myDayDrag = null
    }

    Box(modifier = modifier.onGloballyPositioned { myDayBounds = it.boundsInRoot() }) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                HomeHeader(snapshot, onOpenSearch)
            }

            item {
                Spacer(Modifier.height(16.dp))
                SectionHeader(
                    title = stringResource(R.string.my_day_heading),
                    supportingText = snapshot.overdueCount
                        .takeIf { it > 0 }
                        ?.let { count ->
                            pluralStringResource(R.plurals.my_day_overdue, count, count)
                        },
                    action = {
                        TextButton(onClick = onPlanToday) {
                            Text(stringResource(R.string.my_day_plan))
                        }
                    },
                )
            }
            if (snapshot.myDayTasks.isEmpty()) {
                if (suggestions.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.my_day_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("my-day-empty"),
                        )
                    }
                } else {
                    items(
                        suggestions,
                        key = { "my-day-empty-suggestion-${it.id.value}" },
                    ) { task ->
                        MyDaySuggestionRow(
                            task = task,
                            projectName = projectNames[task.projectId]
                                ?: stringResource(R.string.my_day_inbox),
                            onAddToMyDay = onAddToMyDay,
                        )
                    }
                }
            }
            items(snapshot.myDayTasks, key = { "my-day-${it.id.value}" }) { task ->
                MyDayRow(
                    task = task,
                    order = snapshot.myDayTasks,
                    projectName = projectNames[task.projectId]
                        ?: stringResource(R.string.my_day_inbox),
                    isDragging = myDayDrag?.payload?.id == task.id,
                    onOpenTask = onOpenTask,
                    onCompleteTask = onCompleteTask,
                    onRemoveFromMyDay = onRemoveFromMyDay,
                    onMoveMyDayEntry = onMoveMyDayEntry,
                    onDragStart = { positionInRoot, bounds ->
                        myDayDrag = RootDragState(
                            payload = task,
                            sourceBounds = bounds,
                            startInRoot = positionInRoot,
                        )
                    },
                    onDrag = { delta -> myDayDrag = myDayDrag?.movedBy(delta) },
                    onDragEnd = ::finishMyDayDrag,
                    onDragCancel = { myDayDrag = null },
                    modifier = Modifier.onGloballyPositioned {
                        myDayRowBounds[task.id] = it.boundsInRoot()
                    },
                )
            }

            snapshot.activeTimer?.let { timer ->
                item {
                    val elapsed = rememberRunningElapsed(timer.startedAt, clock)
                    Spacer(Modifier.height(16.dp))
                    ActiveTimer(
                        title = timer.taskTitle,
                        project = timer.projectName,
                        elapsed = buildString {
                            append("%02d".format(elapsed.toHours()))
                            append(':')
                            append("%02d".format(elapsed.toMinutesPart()))
                            append(':')
                            append("%02d".format(elapsed.toSecondsPart()))
                        },
                        onToggle = onToggleTimer,
                    )
                }
            }

            item {
                Spacer(Modifier.height(20.dp))
                SectionHeader(title = "Projects in motion")
            }

            items(snapshot.projects, key = { it.id.value }) { project ->
                ProjectProgressRow(
                    project = project,
                    onClick = { onOpenProject(project.id) },
                )
            }

            item {
                Spacer(Modifier.height(20.dp))
                HomeInsightsSummary(
                    snapshot = insightsSummary,
                    onOpenInsights = onOpenInsights,
                )
            }

            item {
                Spacer(Modifier.height(20.dp))
                SectionHeader(title = "Coming up")
            }

            items(snapshot.upcomingTasks, key = { "upcoming-${it.id.value}" }) { task ->
                UpcomingRow(task, projectNames[task.projectId] ?: "Inbox") {
                    onOpenTask(task.id)
                }
            }
        }
        myDayDrag?.let { drag ->
            RootDragPreview(
                state = drag,
                containerBounds = myDayBounds,
                modifier = Modifier
                    .zIndex(1f)
                    .testTag("my-day-drag-preview-${drag.payload.id.value}")
                    .clearAndSetSemantics { },
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    shadowElevation = 8.dp,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TaskRow(
                            task = drag.payload,
                            projectName = projectNames[drag.payload.projectId]
                                ?: stringResource(R.string.my_day_inbox),
                            selected = false,
                            onSelect = {},
                            onComplete = {},
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MyDayRow(
    task: Task,
    order: List<Task>,
    projectName: String,
    isDragging: Boolean,
    onOpenTask: (TaskId) -> Unit,
    onCompleteTask: (Task) -> Unit,
    onRemoveFromMyDay: (TaskId) -> Unit,
    onMoveMyDayEntry: (TaskId, TaskId?) -> Unit,
    onDragStart: (Offset, Rect) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember(task.id) { mutableStateOf(false) }
    val index = order.indexOfFirst { it.id == task.id }
    val menuDescription = stringResource(R.string.my_day_menu_description, task.title)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (isDragging) 0f else 1f }
            .rootLongPressDragSource(
                key = task.id,
                onStart = onDragStart,
                onDrag = onDrag,
                onDrop = onDragEnd,
                onCancel = onDragCancel,
            )
            .testTag("my-day-row-${task.id.value}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskRow(
            task = task,
            projectName = projectName,
            selected = false,
            onSelect = { onOpenTask(task.id) },
            onComplete = { onCompleteTask(task) },
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = if (task.isCompleted) 0.5f else 1f },
        )
        Column {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("my-day-menu-${task.id.value}"),
            ) {
                Icon(Icons.Rounded.MoreVert, contentDescription = menuDescription)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.my_day_move_up)) },
                    onClick = {
                        menuExpanded = false
                        onMoveMyDayEntry(task.id, order.getOrNull(index - 2)?.id)
                    },
                    enabled = index > 0,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("my-day-move-up-${task.id.value}"),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.my_day_move_down)) },
                    onClick = {
                        menuExpanded = false
                        onMoveMyDayEntry(task.id, order.getOrNull(index + 1)?.id)
                    },
                    enabled = index in 0 until order.lastIndex,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("my-day-move-down-${task.id.value}"),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.my_day_remove)) },
                    onClick = {
                        menuExpanded = false
                        onRemoveFromMyDay(task.id)
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("my-day-remove-${task.id.value}"),
                )
            }
        }
    }
}

/**
 * A one-tap suggestion row: title, project, and a trailing 48 dp add
 * button. Shared by [HomeScreen]'s empty-state fallback and
 * [MyDayPlanSheet] so both surfaces render suggestions identically.
 */
@Composable
fun MyDaySuggestionRow(
    task: Task,
    projectName: String,
    onAddToMyDay: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val addDescription = stringResource(R.string.my_day_suggestion_add_description, task.title)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.titleMedium)
            Text(
                projectName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = { onAddToMyDay(task.id) },
            modifier = Modifier
                .size(48.dp)
                .testTag("my-day-suggestion-add-${task.id.value}"),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = addDescription)
        }
    }
}

@Composable
private fun HomeInsightsSummary(
    snapshot: InsightsSnapshot,
    onOpenInsights: () -> Unit,
) {
    Column {
        SectionHeader(
            title = stringResource(R.string.home_insights_heading),
            action = {
                TextButton(
                    onClick = onOpenInsights,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("home-open-insights"),
                ) {
                    Text(stringResource(R.string.home_insights_open))
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.home_insights_summary,
                snapshot.completed.current,
                homeDurationText(snapshot.quality.recordedTime.included),
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun homeDurationText(duration: Duration): String {
    val minutes = duration.toMinutes().coerceAtLeast(0)
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        duration > Duration.ZERO && minutes == 0L ->
            stringResource(R.string.home_insights_less_than_minute)
        hours == 0L -> stringResource(R.string.home_insights_minutes, remainingMinutes)
        remainingMinutes == 0L -> stringResource(R.string.home_insights_hours, hours)
        else -> stringResource(
            R.string.home_insights_hours_minutes,
            hours,
            remainingMinutes,
        )
    }
}

@Composable
private fun rememberRunningElapsed(
    startedAt: Instant,
    clock: Clock,
): Duration {
    fun currentElapsed(): Duration = Duration.between(startedAt, clock.instant())
        .coerceAtLeast(Duration.ZERO)

    var elapsed by remember(startedAt, clock) { mutableStateOf(currentElapsed()) }
    LaunchedEffect(startedAt, clock) {
        while (true) {
            delay(1_000)
            elapsed = currentElapsed()
        }
    }
    return elapsed
}

@Composable
private fun HomeHeader(
    snapshot: HomeSnapshot,
    onOpenSearch: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = snapshot.today.format(HOME_DATE_FORMAT),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = "Good afternoon",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            IconButton(onClick = onOpenSearch, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Search, contentDescription = "Search workspace")
            }
        }
    }
}

@Composable
private fun ActiveTimer(
    title: String,
    project: String?,
    elapsed: String,
    onToggle: () -> Unit,
) {
    Surface(
        color = OpenTasksColors.LightInk,
        contentColor = Color.White,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(OpenTasksColors.LightEmber, CircleShape),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Active timer",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.72f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                if (project != null) {
                    Text(
                        project,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    elapsed,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                    color = OpenTasksColors.LightEmber,
                )
                IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Pause, contentDescription = "Pause timer")
                }
            }
        }
    }
}

@Composable
private fun UpcomingRow(
    task: Task,
    projectName: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                task.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                projectName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            task.due?.let {
                UPCOMING_DATE_FORMAT.format(
                    it.instant.atZone(java.time.ZoneId.of(it.zoneId)),
                )
            } ?: "Unscheduled",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

private val HOME_DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.UK)
private val UPCOMING_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
