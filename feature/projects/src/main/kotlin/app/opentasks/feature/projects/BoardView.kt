package app.opentasks.feature.projects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.opentasks.core.designsystem.RootDragPreview
import app.opentasks.core.designsystem.RootDragState
import app.opentasks.core.designsystem.dragTargetAt
import app.opentasks.core.designsystem.rootLongPressDragSource
import app.opentasks.core.model.Priority
import app.opentasks.core.model.BoardColumn
import app.opentasks.core.model.SubtaskRollup
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkflowStatusId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private data class BoardDragPayload(
    val task: Task,
    val sourceStatusId: WorkflowStatusId,
) {
    val taskId: TaskId
        get() = task.id
}

fun moveTargets(
    columns: List<BoardColumn>,
    current: WorkflowStatusId,
): List<WorkflowStatus> = columns.map(BoardColumn::status).filter { it.id != current }

@Composable
fun BoardView(
    columns: List<BoardColumn>,
    columnWidth: Dp,
    onMoveTask: (TaskId, WorkflowStatusId) -> Unit,
    onOpenTask: (TaskId) -> Unit,
    onDuplicateTask: (TaskId) -> Unit = {},
    subtaskRollups: Map<TaskId, SubtaskRollup> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val columnBounds = remember { mutableStateMapOf<WorkflowStatusId, Rect>() }
    var boardBounds by remember { mutableStateOf(Rect.Zero) }
    var dragState by remember { mutableStateOf<RootDragState<BoardDragPayload>?>(null) }
    val currentOnMoveTask by rememberUpdatedState(onMoveTask)
    val density = LocalDensity.current
    val edgeThreshold = with(density) { 48.dp.toPx() }
    val edgeScrollStep = with(density) { 16.dp.toPx() }
    val layoutDirection = LocalLayoutDirection.current
    val scrollDirection = if (layoutDirection == LayoutDirection.Ltr) 1 else -1

    fun dropTarget(drag: RootDragState<BoardDragPayload>): WorkflowStatusId? = dragTargetAt(
        positionInRoot = drag.positionInRoot,
        targets = columns.map { it.status.id },
        bounds = columnBounds,
        eligible = { it != drag.payload.sourceStatusId },
    )

    val hoveredStatusId = dragState?.let(::dropTarget)

    LaunchedEffect(dragState?.positionInRoot, boardBounds, layoutDirection) {
        while (true) {
            val pointerX = dragState?.positionInRoot?.x ?: break
            val scroll = when {
                pointerX < boardBounds.left + edgeThreshold -> -edgeScrollStep
                pointerX > boardBounds.right - edgeThreshold -> edgeScrollStep
                else -> break
            } * scrollDirection
            if (scrollState.scrollBy(scroll) == 0f) break
            withFrameNanos { }
        }
    }

    fun finishDrag() {
        val drag = dragState
        val target = drag?.let(::dropTarget)
        if (drag != null && target != null) {
            currentOnMoveTask(drag.payload.taskId, target)
        }
        dragState = null
    }

    Box(
        modifier = modifier.onGloballyPositioned { boardBounds = it.boundsInRoot() },
    ) {
        Row(
            modifier = Modifier.horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            columns.forEach { column ->
                Surface(
                    modifier = Modifier
                        .width(columnWidth)
                        .height(440.dp)
                        .onGloballyPositioned {
                            columnBounds[column.status.id] = it.boundsInRoot()
                        }
                        .testTag("board-column-${column.status.id.value}"),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.large,
                    border = if (hoveredStatusId == column.status.id) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.secondary)
                    } else {
                        null
                    },
                ) {
                    Column {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                column.status.name,
                                modifier = Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            val limit = column.status.wipLimit
                            Text(
                                text = if (limit == null) {
                                    pluralStringResource(
                                        R.plurals.board_open_task_count,
                                        column.tasks.size,
                                        column.tasks.size,
                                    )
                                } else {
                                    stringResource(
                                        R.string.board_wip_count,
                                        column.tasks.size,
                                        limit,
                                    )
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (limit != null && column.tasks.size > limit) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (column.tasks.isEmpty()) {
                                item {
                                    Text(
                                        stringResource(R.string.board_no_open_tasks),
                                        modifier = Modifier.padding(8.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                items(column.tasks, key = { it.id.value }) { task ->
                                    val taskDrag = dragState?.takeIf {
                                        it.payload.taskId == task.id
                                    }
                                    BoardTaskCard(
                                        task = task,
                                        subtaskRollup = subtaskRollups[task.id],
                                        targets = moveTargets(columns, column.status.id),
                                        isDragging = taskDrag != null,
                                        onDragStart = { positionInRoot, cardBounds ->
                                            dragState = RootDragState(
                                                payload = BoardDragPayload(
                                                    task = task,
                                                    sourceStatusId = column.status.id,
                                                ),
                                                sourceBounds = cardBounds,
                                                startInRoot = positionInRoot,
                                            )
                                        },
                                        onDrag = { dragAmount ->
                                            dragState = dragState?.movedBy(dragAmount)
                                        },
                                        onDragEnd = ::finishDrag,
                                        onDragCancel = { dragState = null },
                                        onMoveTask = onMoveTask,
                                        onOpenTask = onOpenTask,
                                        onDuplicateTask = onDuplicateTask,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        dragState?.let { drag ->
            RootDragPreview(
                state = drag,
                containerBounds = boardBounds,
                modifier = Modifier
                    .zIndex(1f)
                    .testTag("board-drag-preview-${drag.payload.taskId.value}")
                    .clearAndSetSemantics { },
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    shadowElevation = 8.dp,
                ) {
                    BoardTaskContent(
                        task = drag.payload.task,
                        subtaskRollup = subtaskRollups[drag.payload.taskId],
                        trailing = {
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = null)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardTaskCard(
    task: Task,
    subtaskRollup: SubtaskRollup?,
    targets: List<WorkflowStatus>,
    isDragging: Boolean,
    onDragStart: (Offset, Rect) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onMoveTask: (TaskId, WorkflowStatusId) -> Unit,
    onOpenTask: (TaskId) -> Unit,
    onDuplicateTask: (TaskId) -> Unit,
) {
    var menuExpanded by remember(task.id) { mutableStateOf(false) }
    val moveLabels = targets.map { status ->
        status to stringResource(R.string.board_move_to_stage, task.title, status.name)
    }
    val duplicateTaskDescription = stringResource(
        R.string.board_duplicate_task_description,
        task.title,
    )
    Surface(
        onClick = { onOpenTask(task.id) },
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = if (isDragging) 0f else 1f
            }
            .rootLongPressDragSource(
                key = task.id,
                onStart = onDragStart,
                onDrag = onDrag,
                onDrop = onDragEnd,
                onCancel = onDragCancel,
            )
            .testTag("board-card-${task.id.value}")
            .semantics {
                customActions = moveLabels.map { (status, label) ->
                    CustomAccessibilityAction(label) {
                        onMoveTask(task.id, status.id)
                        true
                    }
                }
            },
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        BoardTaskContent(
            task = task,
            subtaskRollup = subtaskRollup,
            trailing = {
                Column {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("board-move-${task.id.value}"),
                    ) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = stringResource(
                                R.string.board_move_task_description,
                                task.title,
                            ),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        targets.forEach { status ->
                            DropdownMenuItem(
                                text = { Text(status.name) },
                                onClick = {
                                    menuExpanded = false
                                    onMoveTask(task.id, status.id)
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag(
                                        "board-move-${task.id.value}-to-${status.id.value}",
                                    ),
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.board_duplicate_task)) },
                            onClick = {
                                menuExpanded = false
                                onDuplicateTask(task.id)
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = duplicateTaskDescription }
                                .testTag("board-duplicate-${task.id.value}"),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun BoardTaskContent(
    task: Task,
    subtaskRollup: SubtaskRollup? = null,
    trailing: @Composable () -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val priorityDescription = stringResource(
        R.string.board_priority_description,
        when (task.priority) {
            Priority.NONE -> stringResource(R.string.board_priority_none)
            Priority.LOW -> stringResource(R.string.board_priority_low)
            Priority.MEDIUM -> stringResource(R.string.board_priority_medium)
            Priority.HIGH -> stringResource(R.string.board_priority_high)
            Priority.URGENT -> stringResource(R.string.board_priority_urgent)
        },
    )
    Row(
        modifier = Modifier
            .heightIn(min = 72.dp)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    priorityGlyph(task.priority),
                    modifier = Modifier.semantics {
                        this.contentDescription = priorityDescription
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                task.due?.let { due ->
                    val date = due.instant.atZone(due.zone()).toLocalDate().format(
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                            .withLocale(locale),
                    )
                    Text(
                        stringResource(R.string.board_due_date, date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                subtaskRollup?.let { rollup ->
                    Text(
                        text = stringResource(
                            R.string.board_subtask_rollup,
                            rollup.completed,
                            rollup.total,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        trailing()
    }
}

private fun priorityGlyph(priority: Priority): String = when (priority) {
    Priority.NONE -> "–"
    Priority.LOW -> "↓"
    Priority.MEDIUM -> "•"
    Priority.HIGH -> "↑"
    Priority.URGENT -> "‼"
}
