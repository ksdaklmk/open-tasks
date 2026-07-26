package app.opentasks.feature.more

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.EmptyState
import app.opentasks.core.designsystem.SectionHeader
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun MoreScreen(
    tasks: List<Task>,
    projects: List<Project>,
    onRestoreProject: (ProjectId) -> Unit,
    onRestoreTask: (TaskId) -> Unit,
    onPermanentlyDeleteTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var destination by rememberSaveable { mutableStateOf(MoreDestination.OVERVIEW) }

    when (destination) {
        MoreDestination.ARCHIVE -> {
            BackHandler { destination = MoreDestination.OVERVIEW }
            ArchiveScreen(
                projects = projects,
                tasks = tasks,
                onBack = { destination = MoreDestination.OVERVIEW },
                onRestoreProject = onRestoreProject,
                modifier = modifier,
            )
            return
        }
        MoreDestination.TRASH -> {
            BackHandler { destination = MoreDestination.OVERVIEW }
            TrashScreen(
                tasks = tasks,
                projectNames = projects.associate { it.id to it.name },
                onBack = { destination = MoreDestination.OVERVIEW },
                onRestoreTask = onRestoreTask,
                onPermanentlyDeleteTask = onPermanentlyDeleteTask,
                modifier = modifier,
            )
            return
        }
        MoreDestination.OVERVIEW -> Unit
    }

    val activeTasks = tasks.filter { it.deletedAt == null }
    val activeProjects = projects.filter { it.archivedAt == null }
    val archivedProjectCount = projects.size - activeProjects.size
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 112.dp,
        ),
    ) {
        item {
            Text(
                "More",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(28.dp))
            SectionHeader("This week")
            Spacer(Modifier.height(12.dp))
            InsightSummary(activeTasks, activeProjects)
            Spacer(Modifier.height(32.dp))
            SectionHeader("Workspace")
            Spacer(Modifier.height(8.dp))
        }

        item {
            DestinationRow(
                Icons.Rounded.BarChart,
                "Insights",
            )
            DestinationRow(
                Icons.Rounded.Description,
                "Templates",
            )
            DestinationRow(
                icon = Icons.Rounded.Archive,
                title = "Archive",
                supportingText = if (archivedProjectCount == 1) {
                    "1 project outside active views"
                } else {
                    "$archivedProjectCount projects outside active views"
                },
                onClick = { destination = MoreDestination.ARCHIVE },
                modifier = Modifier.testTag("open-archive"),
            )
            DestinationRow(
                icon = Icons.Rounded.DeleteOutline,
                title = "Trash",
                supportingText = "${tasks.count { it.deletedAt != null }} deleted • kept for 30 days",
                onClick = { destination = MoreDestination.TRASH },
                modifier = Modifier.testTag("open-trash"),
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            DestinationRow(
                Icons.Rounded.Settings,
                "Settings",
            )
            DestinationRow(
                Icons.Rounded.Lock,
                "Privacy & recovery",
            )
        }
    }
}

@Composable
fun ArchiveScreen(
    projects: List<Project>,
    tasks: List<Task>,
    onBack: () -> Unit,
    onRestoreProject: (ProjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val archivedProjects = projects
        .filter { it.archivedAt != null }
        .sortedByDescending(Project::archivedAt)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("archive-screen"),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = 112.dp,
        ),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to More",
                    )
                }
                Text(
                    "Archive",
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        if (archivedProjects.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Archive,
                    title = "Archive is empty",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp),
                )
            }
        } else {
            item {
                SectionHeader(
                    title = if (archivedProjects.size == 1) {
                        "1 archived project"
                    } else {
                        "${archivedProjects.size} archived projects"
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
            items(archivedProjects, key = { project -> project.id.value }) { project ->
                val projectTasks = tasks.filter {
                    it.projectId == project.id && it.deletedAt == null
                }
                ArchiveProjectRow(
                    project = project,
                    activeTaskCount = projectTasks.count { !it.isCompleted },
                    completedTaskCount = projectTasks.count(Task::isCompleted),
                    onRestore = { onRestoreProject(project.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ArchiveProjectRow(
    project: Project,
    activeTaskCount: Int,
    completedTaskCount: Int,
    onRestore: () -> Unit,
) {
    val archivedAt = requireNotNull(project.archivedAt)
    val zone = ZoneId.systemDefault()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Rounded.Archive,
            contentDescription = null,
            modifier = Modifier
                .padding(top = 12.dp)
                .size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium)
            if (project.summary.isNotBlank()) {
                Text(
                    project.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "$activeTaskCount open • $completedTaskCount complete • archived " +
                    ARCHIVE_DATE_FORMAT.format(archivedAt.atZone(zone)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = onRestore,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("restore-project-${project.id.value}"),
                ) {
                    Icon(Icons.Rounded.Unarchive, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Restore")
                }
            }
        }
    }
}

@Composable
fun TrashScreen(
    tasks: List<Task>,
    projectNames: Map<ProjectId, String>,
    onBack: () -> Unit,
    onRestoreTask: (TaskId) -> Unit,
    onPermanentlyDeleteTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trashedTasks = tasks
        .filter { it.deletedAt != null }
        .sortedByDescending(Task::deletedAt)
    var pendingPermanentDelete by remember { mutableStateOf<Task?>(null) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("trash-screen"),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = 112.dp,
        ),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to More",
                    )
                }
                Text(
                    "Trash",
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        if (trashedTasks.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.RestoreFromTrash,
                    title = "Trash is empty",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp),
                )
            }
        } else {
            item {
                SectionHeader(
                    title = if (trashedTasks.size == 1) {
                        "1 deleted task"
                    } else {
                        "${trashedTasks.size} deleted tasks"
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
            items(trashedTasks, key = { task -> task.id.value }) { task ->
                TrashTaskRow(
                    task = task,
                    projectName = task.projectId?.let(projectNames::get) ?: "Inbox",
                    onRestore = { onRestoreTask(task.id) },
                    onPermanentlyDelete = { pendingPermanentDelete = task },
                )
                HorizontalDivider()
            }
        }
    }

    pendingPermanentDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingPermanentDelete = null },
            icon = {
                Icon(
                    Icons.Rounded.DeleteForever,
                    contentDescription = null,
                )
            },
            title = { Text("Delete permanently?") },
            text = {
                Text(
                    "“${task.title}” and its related local data will be removed. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingPermanentDelete = null
                        onPermanentlyDeleteTask(task.id)
                    },
                    modifier = Modifier.testTag("confirm-permanent-delete"),
                ) {
                    Text(
                        "Delete permanently",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPermanentDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun TrashTaskRow(
    task: Task,
    projectName: String,
    onRestore: () -> Unit,
    onPermanentlyDelete: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val deletedAt = requireNotNull(task.deletedAt)
    val purgeAfter = deletedAt.plus(30, ChronoUnit.DAYS)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Rounded.DeleteOutline,
            contentDescription = null,
            modifier = Modifier
                .padding(top = 12.dp)
                .size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "$projectName • deleted ${TRASH_DATE_FORMAT.format(deletedAt.atZone(zone))}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Automatic deletion ${TRASH_DATE_FORMAT.format(purgeAfter.atZone(zone))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onRestore,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("restore-task-${task.id.value}"),
                ) {
                    Icon(Icons.Rounded.RestoreFromTrash, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Restore")
                }
                Spacer(Modifier.size(8.dp))
                IconButton(
                    onClick = onPermanentlyDelete,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("permanently-delete-task-${task.id.value}"),
                ) {
                    Icon(
                        Icons.Rounded.DeleteForever,
                        contentDescription = "Delete ${task.title} permanently",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun InsightSummary(tasks: List<Task>, projects: List<Project>) {
    val complete = tasks.count(Task::isCompleted)
    val atRiskProjects = projects.count { it.status.name == "AT_RISK" }
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "$complete of ${tasks.size} tasks complete",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${tasks.count(Task::isBlocked)} blocked • " +
                    if (atRiskProjects == 1) {
                        "1 project at risk"
                    } else {
                        "$atRiskProjects projects at risk"
                    },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.76f),
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                listOf(0.35f, 0.58f, 0.42f, 0.78f, 0.62f, 0.88f, 0.72f).forEach { value ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height((20 + value * 44).dp),
                        color = MaterialTheme.colorScheme.secondary,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {}
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Daily completions: 2, 4, 3, 6, 5, 7, 5",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.76f),
            )
        }
    }
}

@Composable
private fun DestinationRow(
    icon: ImageVector,
    title: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                supportingText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (onClick == null) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
            color = Color.Transparent,
            content = content,
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
            color = Color.Transparent,
            content = content,
        )
    }
}

private val TRASH_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM")
private val ARCHIVE_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy")

private enum class MoreDestination {
    OVERVIEW,
    ARCHIVE,
    TRASH,
}
