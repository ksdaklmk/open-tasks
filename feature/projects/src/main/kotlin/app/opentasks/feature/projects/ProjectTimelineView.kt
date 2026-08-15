package app.opentasks.feature.projects

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.DotRunBar
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.PROJECT_TIMELINE_DAY_COUNT
import app.opentasks.core.model.ProjectTimelineDependencyRole
import app.opentasks.core.model.ProjectTimelineMarkerKind
import app.opentasks.core.model.ProjectTimelineMilestoneMarker
import app.opentasks.core.model.ProjectTimelineProjection
import app.opentasks.core.model.ProjectTimelineTaskPlacement
import app.opentasks.core.model.ProjectTimelineTaskRow
import app.opentasks.core.model.ProjectTimelineWindow
import app.opentasks.core.model.ProjectTimelineWindowSide
import app.opentasks.core.model.TaskId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIMELINE_DAY_WIDTH = 28.dp
private val TIMELINE_LABEL_WIDTH = 148.dp
private val TIMELINE_GRID_WIDTH = TIMELINE_DAY_WIDTH * PROJECT_TIMELINE_DAY_COUNT
private val TIMELINE_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
private val TIMELINE_DAY_NUMBER_FORMAT = DateTimeFormatter.ofPattern("d", Locale.UK)
private val TIMELINE_WEEKDAY_FORMAT = DateTimeFormatter.ofPattern("EEE", Locale.UK)

/**
 * Read-only Gantt-lite rendering of [ProjectTimelineProjection]: an 84-day
 * fixed grid of task spans/markers plus in-window milestone diamonds. Row
 * selection only emits [onTaskSelectionChange]; opening a task or milestone
 * is a separate action so the row itself never navigates away. There are no
 * drag modifiers and no write commands here -- Timeline is presentation-only.
 */
@Composable
fun ProjectTimelineView(
    projection: ProjectTimelineProjection,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
    onTaskSelectionChange: (TaskId?) -> Unit,
    onOpenTask: (TaskId) -> Unit,
    onOpenMilestone: (MilestoneId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Column(modifier = modifier.testTag("project-timeline")) {
        TimelineNavigationRow(
            window = projection.window,
            onPrevious = onPrevious,
            onToday = onToday,
            onNext = onNext,
        )
        Spacer(Modifier.height(12.dp))
        if (projection.milestonesBeforeWindow > 0 || projection.milestonesAfterWindow > 0) {
            Text(
                stringResource(
                    R.string.timeline_milestones_outside_window,
                    projection.milestonesBeforeWindow,
                    projection.milestonesAfterWindow,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("timeline-milestone-window-summary"),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (projection.selectedTaskId != null) {
            Text(
                pluralStringResource(
                    R.plurals.timeline_dependency_chain_outside_project,
                    projection.outOfProjectDependencyTaskCount,
                    projection.outOfProjectDependencyTaskCount,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("timeline-dependency-chain-summary"),
            )
            Spacer(Modifier.height(8.dp))
        }

        Row {
            Spacer(Modifier.width(TIMELINE_LABEL_WIDTH))
            Row(
                modifier = Modifier
                    .horizontalScroll(scrollState)
                    .width(TIMELINE_GRID_WIDTH),
            ) {
                repeat(PROJECT_TIMELINE_DAY_COUNT) { index ->
                    DayHeaderCell(projection.window.firstDate.plusDays(index.toLong()))
                }
            }
        }

        if (projection.milestoneMarkers.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(TIMELINE_LABEL_WIDTH)
                        .padding(horizontal = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.timeline_milestones_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Box(
                    modifier = Modifier
                        .horizontalScroll(scrollState)
                        .width(TIMELINE_GRID_WIDTH)
                        .heightIn(min = 48.dp),
                ) {
                    projection.milestoneMarkers.forEach { marker ->
                        TimelineMilestoneDiamond(marker, onOpenMilestone)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp),
        ) {
            items(projection.taskRows, key = { it.task.id.value }) { row ->
                TimelineTaskRow(
                    row = row,
                    onSelectionChange = onTaskSelectionChange,
                    onOpenTask = onOpenTask,
                    scrollState = scrollState,
                )
            }
        }
    }
}

@Composable
private fun TimelineNavigationRow(
    window: ProjectTimelineWindow,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
) {
    Text(
        stringResource(R.string.workbench_view_timeline),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        stringResource(
            R.string.timeline_window_range,
            window.firstDate.format(TIMELINE_DATE_FORMAT),
            window.lastDate.format(TIMELINE_DATE_FORMAT),
        ),
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
                .testTag("timeline-previous"),
        ) {
            Icon(
                Icons.Rounded.ChevronLeft,
                contentDescription = stringResource(R.string.timeline_previous),
            )
        }
        TextButton(
            onClick = onToday,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("timeline-today"),
        ) {
            Text(stringResource(R.string.timeline_today))
        }
        IconButton(
            onClick = onNext,
            modifier = Modifier
                .size(48.dp)
                .testTag("timeline-next"),
        ) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = stringResource(R.string.timeline_next),
            )
        }
    }
}

@Composable
private fun DayHeaderCell(date: LocalDate) {
    Column(
        modifier = Modifier
            .width(TIMELINE_DAY_WIDTH)
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = date.format(TIMELINE_DATE_FORMAT)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            date.format(TIMELINE_DAY_NUMBER_FORMAT),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(
            date.format(TIMELINE_WEEKDAY_FORMAT),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun TimelineTaskRow(
    row: ProjectTimelineTaskRow,
    onSelectionChange: (TaskId?) -> Unit,
    onOpenTask: (TaskId) -> Unit,
    scrollState: ScrollState,
) {
    val task = row.task
    val isSelected = row.dependencyRole == ProjectTimelineDependencyRole.SELECTED
    val description = timelineRowDescription(row)
    val rowBackground = timelineRoleBackground(row.dependencyRole)
    val openLabel = stringResource(R.string.timeline_open_task, task.title)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(rowBackground)
                .clickable(
                    role = Role.Button,
                    onClick = { onSelectionChange(task.id) },
                )
                .testTag("timeline-task-row-${task.id.value}")
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    selected = isSelected
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(TIMELINE_LABEL_WIDTH)
                    .padding(horizontal = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    timelineRoleIcon(row.dependencyRole)?.let { icon ->
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .size(16.dp)
                                .clearAndSetSemantics {},
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
            Box(
                modifier = Modifier
                    .horizontalScroll(scrollState)
                    .width(TIMELINE_GRID_WIDTH)
                    .heightIn(min = 48.dp),
            ) {
                TimelinePlacementContent(row)
            }
        }
        IconButton(
            onClick = { onOpenTask(task.id) },
            modifier = Modifier
                .size(48.dp)
                .testTag("timeline-open-task-${task.id.value}"),
        ) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = openLabel)
        }
    }
}

@Composable
private fun TimelinePlacementContent(row: ProjectTimelineTaskRow) {
    val task = row.task
    when (val placement = row.placement) {
        is ProjectTimelineTaskPlacement.Span -> {
            val visibleCount = (placement.lastVisibleDayIndex - placement.firstVisibleDayIndex + 1)
                .coerceAtLeast(1)
            Box(
                modifier = Modifier
                    .offset(x = TIMELINE_DAY_WIDTH * placement.firstVisibleDayIndex)
                    .width(TIMELINE_DAY_WIDTH * visibleCount)
                    .heightIn(min = 48.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                DotRunBar(
                    progress = 1f,
                    unitCount = visibleCount.toLong(),
                    maxDots = PROJECT_TIMELINE_DAY_COUNT,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp),
                )
                // The continuation chevron and the completed/blocked status
                // icon can both land on the same edge (a completed task
                // clipped at the window start, or a blocked task clipped at
                // the window end) -- each edge is its own Row so the two
                // cues sit in distinct slots instead of stacking on top of
                // each other. Order: chevron outermost (it marks the very
                // edge of the visible window), status icon just inside it.
                if (placement.continuesBefore || task.isCompleted) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterStart),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (placement.continuesBefore) {
                            Icon(
                                Icons.Rounded.ChevronLeft,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clearAndSetSemantics {},
                            )
                        }
                        if (task.isCompleted) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clearAndSetSemantics {},
                            )
                        }
                    }
                }
                if (placement.continuesAfter || task.isBlocked) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (task.isBlocked) {
                            Icon(
                                Icons.Rounded.Block,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clearAndSetSemantics {},
                            )
                        }
                        if (placement.continuesAfter) {
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clearAndSetSemantics {},
                            )
                        }
                    }
                }
            }
        }

        is ProjectTimelineTaskPlacement.Marker -> {
            Box(
                modifier = Modifier
                    .offset(x = TIMELINE_DAY_WIDTH * placement.dayIndex)
                    .width(TIMELINE_DAY_WIDTH)
                    .heightIn(min = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        task.isCompleted -> Icons.Rounded.CheckCircle
                        placement.kind == ProjectTimelineMarkerKind.START -> Icons.Rounded.Event
                        else -> Icons.Rounded.Flag
                    },
                    contentDescription = null,
                    tint = if (task.isBlocked) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    modifier = Modifier
                        .size(18.dp)
                        .clearAndSetSemantics {},
                )
            }
        }

        is ProjectTimelineTaskPlacement.Outside -> {
            Text(
                stringResource(
                    if (placement.side == ProjectTimelineWindowSide.BEFORE) {
                        R.string.timeline_row_outside_before
                    } else {
                        R.string.timeline_row_outside_after
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clearAndSetSemantics {},
            )
        }

        ProjectTimelineTaskPlacement.InvalidRange -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(18.dp)
                        .clearAndSetSemantics {},
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.timeline_row_invalid),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }

        ProjectTimelineTaskPlacement.Unscheduled -> {
            Text(
                stringResource(R.string.timeline_row_unscheduled),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun timelineRoleIcon(role: ProjectTimelineDependencyRole) = when (role) {
    ProjectTimelineDependencyRole.SELECTED -> Icons.Rounded.Bookmark
    ProjectTimelineDependencyRole.PREREQUISITE -> Icons.Rounded.ArrowUpward
    ProjectTimelineDependencyRole.DEPENDANT -> Icons.Rounded.ArrowDownward
    ProjectTimelineDependencyRole.PREREQUISITE_AND_DEPENDANT -> Icons.Rounded.Sync
    ProjectTimelineDependencyRole.NONE -> null
}

@Composable
private fun timelineRoleBackground(role: ProjectTimelineDependencyRole): Color = when (role) {
    ProjectTimelineDependencyRole.SELECTED ->
        MaterialTheme.colorScheme.secondaryContainer
    ProjectTimelineDependencyRole.PREREQUISITE,
    ProjectTimelineDependencyRole.DEPENDANT,
    ProjectTimelineDependencyRole.PREREQUISITE_AND_DEPENDANT,
    -> MaterialTheme.colorScheme.tertiaryContainer
    ProjectTimelineDependencyRole.NONE -> Color.Transparent
}

@Composable
private fun timelineRowDescription(row: ProjectTimelineTaskRow): String {
    val task = row.task
    val parts = mutableListOf(task.title)

    when (val placement = row.placement) {
        is ProjectTimelineTaskPlacement.Span -> {
            parts += stringResource(
                R.string.timeline_row_starts,
                requireNotNull(row.startDate).format(TIMELINE_DATE_FORMAT),
            )
            parts += stringResource(
                R.string.timeline_row_due,
                requireNotNull(row.dueDate).format(TIMELINE_DATE_FORMAT),
            )
            parts += pluralStringResource(
                R.plurals.timeline_row_duration_days,
                placement.totalDayCount.toInt(),
                placement.totalDayCount.toInt(),
            )
            if (placement.continuesBefore) parts += stringResource(R.string.timeline_row_continues_before)
            if (placement.continuesAfter) parts += stringResource(R.string.timeline_row_continues_after)
        }

        is ProjectTimelineTaskPlacement.Marker -> {
            parts += when (placement.kind) {
                ProjectTimelineMarkerKind.START -> stringResource(
                    R.string.timeline_row_starts,
                    requireNotNull(row.startDate).format(TIMELINE_DATE_FORMAT),
                )
                ProjectTimelineMarkerKind.DUE -> stringResource(
                    R.string.timeline_row_due,
                    requireNotNull(row.dueDate).format(TIMELINE_DATE_FORMAT),
                )
            }
        }

        is ProjectTimelineTaskPlacement.Outside -> {
            row.startDate?.let {
                parts += stringResource(R.string.timeline_row_starts, it.format(TIMELINE_DATE_FORMAT))
            }
            row.dueDate?.let {
                parts += stringResource(R.string.timeline_row_due, it.format(TIMELINE_DATE_FORMAT))
            }
            parts += stringResource(
                if (placement.side == ProjectTimelineWindowSide.BEFORE) {
                    R.string.timeline_row_outside_before
                } else {
                    R.string.timeline_row_outside_after
                },
            )
        }

        ProjectTimelineTaskPlacement.InvalidRange -> {
            row.startDate?.let {
                parts += stringResource(R.string.timeline_row_starts, it.format(TIMELINE_DATE_FORMAT))
            }
            row.dueDate?.let {
                parts += stringResource(R.string.timeline_row_due, it.format(TIMELINE_DATE_FORMAT))
            }
            parts += stringResource(R.string.timeline_row_invalid)
        }

        ProjectTimelineTaskPlacement.Unscheduled -> {
            parts += stringResource(R.string.timeline_row_unscheduled)
        }
    }

    parts += stringResource(
        if (task.isCompleted) R.string.timeline_row_completed else R.string.timeline_row_not_completed,
    )
    parts += stringResource(
        if (task.isBlocked) R.string.timeline_row_blocked else R.string.timeline_row_not_blocked,
    )
    timelineRoleDescriptionRes(row.dependencyRole)?.let { parts += stringResource(it) }

    return parts.joinToString(". ")
}

private fun timelineRoleDescriptionRes(role: ProjectTimelineDependencyRole): Int? = when (role) {
    ProjectTimelineDependencyRole.SELECTED -> R.string.timeline_row_role_selected
    ProjectTimelineDependencyRole.PREREQUISITE -> R.string.timeline_row_role_prerequisite
    ProjectTimelineDependencyRole.DEPENDANT -> R.string.timeline_row_role_dependant
    ProjectTimelineDependencyRole.PREREQUISITE_AND_DEPENDANT ->
        R.string.timeline_row_role_prerequisite_and_dependant
    ProjectTimelineDependencyRole.NONE -> null
}

@Composable
private fun TimelineMilestoneDiamond(
    marker: ProjectTimelineMilestoneMarker,
    onOpenMilestone: (MilestoneId) -> Unit,
) {
    val milestone = marker.milestone
    val isComplete = milestone.completedAt != null
    val description = stringResource(
        if (isComplete) R.string.timeline_milestone_complete else R.string.timeline_milestone_open,
        milestone.name,
        requireNotNull(milestone.dueDate).format(TIMELINE_DATE_FORMAT),
    )
    Box(
        modifier = Modifier
            .offset(x = TIMELINE_DAY_WIDTH * marker.dayIndex)
            .size(48.dp)
            .clickable(
                role = Role.Button,
                onClick = { onOpenMilestone(milestone.id) },
            )
            .semantics { contentDescription = description }
            .testTag("timeline-milestone-${milestone.id.value}"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer { rotationZ = 45f }
                .background(
                    color = if (isComplete) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    shape = RoundedCornerShape(2.dp),
                )
                .clearAndSetSemantics {},
        )
        if (isComplete) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .size(10.dp)
                    .clearAndSetSemantics {},
            )
        }
    }
}
