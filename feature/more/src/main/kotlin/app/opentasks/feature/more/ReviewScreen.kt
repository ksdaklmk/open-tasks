package app.opentasks.feature.more

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.EmptyState
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.ReviewQueue
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ReviewScreen(
    queue: ReviewQueue,
    projectNames: Map<ProjectId, String>,
    reviewedTaskIds: Set<TaskId>,
    reviewedProjectIds: Set<ProjectId>,
    actionPending: Boolean,
    onBack: () -> Unit,
    onCompleteTask: (TaskId, Boolean) -> Unit,
    onRescheduleTask: (TaskId, LocalDate) -> Unit,
    onKeepTask: (TaskId) -> Unit,
    onBinTask: (TaskId) -> Unit,
    onKeepProject: (ProjectId) -> Unit,
    onArchiveProject: (ProjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val task = (queue.overdue + queue.stale + queue.unscheduled)
        .firstOrNull { it.id !in reviewedTaskIds }
    val project = queue.projects.firstOrNull { it.id !in reviewedProjectIds }
    var confirmBlocked by remember { mutableStateOf<Task?>(null) }
    BackHandler(enabled = !actionPending, onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("review-screen"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                enabled = !actionPending,
                modifier = Modifier.size(48.dp).testTag("review-back"),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.review_back),
                )
            }
            Text(
                stringResource(R.string.review_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        Spacer(Modifier.size(20.dp))
        when {
            task != null -> TaskCard(
                task = task,
                projectName = task.projectId?.let(projectNames::get),
                section = when (task) {
                    in queue.overdue -> R.string.review_overdue
                    in queue.stale -> R.string.review_stale
                    else -> R.string.review_unscheduled
                },
                actionPending = actionPending,
                onComplete = { if (task.isBlocked) confirmBlocked = task else onCompleteTask(task.id, false) },
                onReschedule = onRescheduleTask,
                onKeep = onKeepTask,
                onBin = onBinTask,
            )
            project != null -> ProjectCard(
                project = project,
                actionPending = actionPending,
                onKeep = onKeepProject,
                onArchive = onArchiveProject,
            )
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(
                    icon = Icons.Rounded.Check,
                    title = stringResource(R.string.review_done),
                )
                Button(onClick = onBack, modifier = Modifier.testTag("review-finish")) {
                    Text(stringResource(R.string.review_finish))
                }
            }
        }
    }

    confirmBlocked?.let { blocked ->
        AlertDialog(
            onDismissRequest = { confirmBlocked = null },
            title = { Text(stringResource(R.string.review_blocked_title)) },
            text = { Text(stringResource(R.string.review_blocked_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlocked = null
                    onCompleteTask(blocked.id, true)
                }) { Text(stringResource(R.string.review_complete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBlocked = null }) {
                    Text(stringResource(R.string.review_cancel))
                }
            },
        )
    }
}

@Composable
private fun TaskCard(
    task: Task,
    projectName: String?,
    section: Int,
    actionPending: Boolean,
    onComplete: () -> Unit,
    onReschedule: (TaskId, LocalDate) -> Unit,
    onKeep: (TaskId) -> Unit,
    onBin: (TaskId) -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(section), style = MaterialTheme.typography.titleMedium)
        Text(task.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            projectName ?: stringResource(R.string.review_inbox),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            task.due?.let {
                stringResource(
                    R.string.review_due,
                    DateTimeFormatter.ofPattern("d MMM uuuu")
                        .format(it.instant.atZone(ZoneId.systemDefault()).toLocalDate()),
                )
            } ?: stringResource(R.string.review_no_due),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ReviewAction("review-complete", R.string.review_complete, Icons.Rounded.Check, actionPending, onComplete)
            ReviewAction("review-reschedule", R.string.review_reschedule, Icons.Rounded.Event, actionPending) {
                val date = task.due?.instant?.atZone(ZoneId.systemDefault())?.toLocalDate() ?: LocalDate.now()
                DatePickerDialog(context, { _, year, month, day ->
                    onReschedule(task.id, LocalDate.of(year, month + 1, day))
                }, date.year, date.monthValue - 1, date.dayOfMonth).show()
            }
            ReviewAction("review-keep", R.string.review_keep, Icons.Rounded.Bookmark, actionPending) { onKeep(task.id) }
            ReviewAction("review-bin", R.string.review_bin, Icons.Rounded.DeleteOutline, actionPending) { onBin(task.id) }
        }
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    actionPending: Boolean,
    onKeep: (ProjectId) -> Unit,
    onArchive: (ProjectId) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.review_projects), style = MaterialTheme.typography.titleMedium)
        Text(project.name, style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.review_project_summary, project.completedTasks, project.totalTasks),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ReviewAction("review-keep-project", R.string.review_keep, Icons.Rounded.Bookmark, actionPending) {
                onKeep(project.id)
            }
            ReviewAction("review-archive-project", R.string.review_archive, Icons.Rounded.Archive, actionPending) {
                onArchive(project.id)
            }
        }
    }
}

@Composable
private fun ReviewAction(
    tag: String,
    label: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    disabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = !disabled,
        modifier = Modifier.size(48.dp).testTag(tag),
    ) {
        Icon(icon, contentDescription = stringResource(label))
    }
}
