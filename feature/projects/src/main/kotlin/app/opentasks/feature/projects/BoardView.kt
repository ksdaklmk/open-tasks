package app.opentasks.feature.projects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.absoluteOffset
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
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.opentasks.core.model.Priority
import app.opentasks.core.model.BoardColumn
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkflowStatusId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

private data class BoardDragState(
    val task: Task,
    val sourceStatusId: WorkflowStatusId,
    val startInRoot: Offset,
    val cardBounds: Rect,
    val accumulatedOffset: Offset = Offset.Zero,
) {
    val taskId: TaskId
        get() = task.id

    val positionInRoot: Offset
        get() = startInRoot + accumulatedOffset
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
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val columnBounds = remember { mutableStateMapOf<WorkflowStatusId, Rect>() }
    var boardBounds by remember { mutableStateOf(Rect.Zero) }
    var dragState by remember { mutableStateOf<BoardDragState?>(null) }
    val currentOnMoveTask by rememberUpdatedState(onMoveTask)
    val density = LocalDensity.current
    val edgeThreshold = with(density) { 48.dp.toPx() }
    val edgeScrollStep = with(density) { 16.dp.toPx() }
    val layoutDirection = LocalLayoutDirection.current
    val scrollDirection = if (layoutDirection == LayoutDirection.Ltr) 1 else -1

    fun dropTarget(drag: BoardDragState): WorkflowStatusId? =
        columns.firstOrNull { column ->
            column.status.id != drag.sourceStatusId &&
                columnBounds[column.status.id]?.contains(drag.positionInRoot) == true
        }?.status?.id

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
            currentOnMoveTask(drag.taskId, target)
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
                            Text(
                                pluralStringResource(
                                    R.plurals.board_open_task_count,
                                    column.tasks.size,
                                    column.tasks.size,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    val taskDrag = dragState?.takeIf { it.taskId == task.id }
                                    BoardTaskCard(
                                        task = task,
                                        targets = moveTargets(columns, column.status.id),
                                        isDragging = taskDrag != null,
                                        onDragStart = { positionInRoot, cardBounds ->
                                            dragState = BoardDragState(
                                                task = task,
                                                sourceStatusId = column.status.id,
                                                startInRoot = positionInRoot,
                                                cardBounds = cardBounds,
                                            )
                                        },
                                        onDrag = { dragAmount ->
                                            dragState = dragState?.let {
                                                it.copy(
                                                    accumulatedOffset =
                                                        it.accumulatedOffset + dragAmount,
                                                )
                                            }
                                        },
                                        onDragEnd = ::finishDrag,
                                        onDragCancel = { dragState = null },
                                        onMoveTask = onMoveTask,
                                        onOpenTask = onOpenTask,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        dragState?.let { drag ->
            Surface(
                modifier = Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .absoluteOffset {
                        val x = drag.cardBounds.left - boardBounds.left +
                            drag.accumulatedOffset.x
                        val y = drag.cardBounds.top - boardBounds.top +
                            drag.accumulatedOffset.y
                        IntOffset(
                            x = x.roundToInt(),
                            y = y.roundToInt(),
                        )
                    }
                    .width(with(density) { drag.cardBounds.width.toDp() })
                    .height(with(density) { drag.cardBounds.height.toDp() })
                    .zIndex(1f)
                    .testTag("board-drag-preview-${drag.taskId.value}")
                    .clearAndSetSemantics { },
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 8.dp,
            ) {
                BoardTaskContent(
                    task = drag.task,
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

@Composable
private fun BoardTaskCard(
    task: Task,
    targets: List<WorkflowStatus>,
    isDragging: Boolean,
    onDragStart: (Offset, Rect) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onMoveTask: (TaskId, WorkflowStatusId) -> Unit,
    onOpenTask: (TaskId) -> Unit,
) {
    var menuExpanded by remember(task.id) { mutableStateOf(false) }
    var bounds by remember(task.id) { mutableStateOf(Rect.Zero) }
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val moveLabels = targets.map { status ->
        status to stringResource(R.string.board_move_to_stage, task.title, status.name)
    }
    Surface(
        onClick = { onOpenTask(task.id) },
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = if (isDragging) 0f else 1f
            }
            .onGloballyPositioned {
                bounds = Rect(
                    offset = it.positionInRoot(),
                    size = Size(it.size.width.toFloat(), it.size.height.toFloat()),
                )
            }
            .pointerInput(task.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        currentOnDragStart(bounds.topLeft + it, bounds)
                    },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragCancel() },
                    onDrag = { _, dragAmount -> currentOnDrag(dragAmount) },
                )
            }
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
                    }
                }
            },
        )
    }
}

@Composable
private fun BoardTaskContent(
    task: Task,
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
