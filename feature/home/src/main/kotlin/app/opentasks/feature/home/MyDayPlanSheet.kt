package app.opentasks.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.SectionHeader
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId

/**
 * The My Day curation surface: reorder/remove today's members and add
 * suggestions with one tap. Mirrors the `WorkflowEditorSheet`-style
 * `ModalBottomSheet` mold used elsewhere in the app.
 *
 * The sheet has no task-open or complete affordance of its own -- it is
 * curation only, not navigation -- so [MyDayRow] is reused with no-op
 * `onOpenTask`/`onCompleteTask` and no drag (root-coordinate long-press
 * drag does not translate into a modal sheet's own composition root).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDayPlanSheet(
    members: List<Task>,
    suggestions: List<Task>,
    projectNames: Map<ProjectId, String>,
    onDismiss: () -> Unit,
    onAddToMyDay: (TaskId) -> Unit,
    onRemoveFromMyDay: (TaskId) -> Unit,
    onMoveMyDayEntry: (TaskId, TaskId?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(
                stringResource(R.string.my_day_plan_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(16.dp))

            if (members.isEmpty()) {
                Text(
                    stringResource(R.string.my_day_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                members.forEach { task ->
                    MyDayRow(
                        task = task,
                        order = members,
                        projectName = projectNames[task.projectId]
                            ?: stringResource(R.string.my_day_inbox),
                        isDragging = false,
                        onOpenTask = {},
                        onCompleteTask = {},
                        onRemoveFromMyDay = onRemoveFromMyDay,
                        onMoveMyDayEntry = onMoveMyDayEntry,
                        onDragStart = { _, _ -> },
                        onDrag = {},
                        onDragEnd = {},
                        onDragCancel = {},
                    )
                }
            }

            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))
                SectionHeader(title = stringResource(R.string.my_day_suggestions))
                Spacer(Modifier.height(8.dp))
                suggestions.forEach { task ->
                    MyDaySuggestionRow(
                        task = task,
                        projectName = projectNames[task.projectId]
                            ?: stringResource(R.string.my_day_inbox),
                        onAddToMyDay = onAddToMyDay,
                    )
                }
            }
        }
    }
}
