package app.opentasks.feature.projects

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkflowStatusId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class BoardColumn(
    val status: WorkflowStatus,
    val tasks: List<Task>,
)

fun boardColumns(
    project: Project,
    statuses: List<WorkflowStatus>,
    tasks: List<Task>,
): List<BoardColumn> = statuses
    .filter { it.projectId == project.id && it.archivedAt == null }
    .sortedBy(WorkflowStatus::rank)
    .map { status ->
        BoardColumn(
            status = status,
            tasks = tasks
                .filter {
                    it.projectId == project.id &&
                        it.statusId == status.id &&
                        it.deletedAt == null &&
                        !it.isCompleted
                }
                .sortedWith(
                    compareByDescending<Task> { it.priority.ordinal }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
                ),
        )
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
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        columns.forEach { column ->
            Surface(
                modifier = Modifier
                    .width(columnWidth)
                    .height(440.dp)
                    .testTag("board-column-${column.status.id.value}"),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large,
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
                                BoardTaskCard(
                                    task = task,
                                    targets = moveTargets(columns, column.status.id),
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
}

@Composable
private fun BoardTaskCard(
    task: Task,
    targets: List<WorkflowStatus>,
    onMoveTask: (TaskId, WorkflowStatusId) -> Unit,
    onOpenTask: (TaskId) -> Unit,
) {
    var menuExpanded by remember(task.id) { mutableStateOf(false) }
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
    val moveLabels = targets.map { status ->
        status to stringResource(R.string.board_move_to_stage, task.title, status.name)
    }
    Surface(
        onClick = { onOpenTask(task.id) },
        modifier = Modifier
            .fillMaxWidth()
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
        }
    }
}

private fun priorityGlyph(priority: Priority): String = when (priority) {
    Priority.NONE -> "–"
    Priority.LOW -> "↓"
    Priority.MEDIUM -> "•"
    Priority.HIGH -> "↑"
    Priority.URGENT -> "‼"
}
