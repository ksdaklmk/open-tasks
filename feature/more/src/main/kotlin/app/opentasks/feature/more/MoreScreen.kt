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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.EmptyState
import app.opentasks.core.designsystem.SectionHeader
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.InsightsRange
import app.opentasks.core.model.InsightsSnapshot
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.Template
import app.opentasks.core.model.TemplateId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
fun MoreScreen(
    tasks: List<Task>,
    projects: List<Project>,
    templates: List<Template> = emptyList(),
    insightsState: InsightsUiState = emptyInsightsUiState(),
    insightsSummary: InsightsSnapshot = insightsState.snapshot,
    openInsights: Boolean = false,
    onInsightsClosed: () -> Unit = {},
    onInsightsRangeChange: (InsightsRange) -> Unit = {},
    onInsightsProjectFilter: (ProjectId, Boolean) -> Unit = { _, _ -> },
    onInsightsTagFilter: (TagId, Boolean) -> Unit = { _, _ -> },
    onInsightsIncludeConflictedTimeChange: (Boolean) -> Unit = {},
    onInsightsPresentationChange: (InsightsPresentation) -> Unit = {},
    onRestoreProject: (ProjectId) -> Unit,
    onRestoreTask: (TaskId) -> Unit,
    onPermanentlyDeleteTask: (TaskId) -> Unit,
    onUseTemplate: (TemplateId, String, LocalDate) -> Unit = { _, _, _ -> },
    onDeleteTemplate: (TemplateId) -> Unit = {},
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    var destination by rememberSaveable { mutableStateOf(MoreDestination.OVERVIEW) }
    LaunchedEffect(openInsights) {
        if (openInsights) {
            destination = MoreDestination.INSIGHTS
        }
    }
    val closeInsights = {
        destination = MoreDestination.OVERVIEW
        onInsightsClosed()
    }

    when (destination) {
        MoreDestination.INSIGHTS -> {
            BackHandler(onBack = closeInsights)
            InsightsScreen(
                state = insightsState,
                onRangeChange = onInsightsRangeChange,
                onProjectFilter = onInsightsProjectFilter,
                onTagFilter = onInsightsTagFilter,
                onIncludeConflictedTimeChange = onInsightsIncludeConflictedTimeChange,
                onPresentationChange = onInsightsPresentationChange,
                onBack = closeInsights,
                modifier = modifier,
            )
            return
        }
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
        MoreDestination.TEMPLATES -> {
            BackHandler { destination = MoreDestination.OVERVIEW }
            TemplatesScreen(
                templates = templates,
                existingProjectNames = projects
                    .filter { it.archivedAt == null }
                    .mapTo(linkedSetOf(), Project::name),
                today = today,
                onBack = { destination = MoreDestination.OVERVIEW },
                onUseTemplate = onUseTemplate,
                onDeleteTemplate = onDeleteTemplate,
                modifier = modifier,
            )
            return
        }
        MoreDestination.OVERVIEW -> Unit
    }

    val activeProjects = projects.filter { it.archivedAt == null }
    val archivedProjectCount = projects.size - activeProjects.size
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("more-overview"),
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
            InsightSummary(insightsSummary)
            Spacer(Modifier.height(32.dp))
            SectionHeader("Workspace")
            Spacer(Modifier.height(8.dp))
        }

        item {
            DestinationRow(
                icon = Icons.Rounded.BarChart,
                title = stringResource(R.string.insights_title),
                supportingText = stringResource(R.string.insights_open),
                onClick = { destination = MoreDestination.INSIGHTS },
                modifier = Modifier.testTag("open-insights"),
            )
            DestinationRow(
                icon = Icons.Rounded.Description,
                title = "Templates",
                supportingText = if (templates.size == 1) {
                    "1 reusable project structure"
                } else {
                    "${templates.size} reusable project structures"
                },
                onClick = { destination = MoreDestination.TEMPLATES },
                modifier = Modifier.testTag("open-templates"),
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
                title = "Bin",
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
private fun TemplatesScreen(
    templates: List<Template>,
    existingProjectNames: Set<String>,
    today: LocalDate,
    onBack: () -> Unit,
    onUseTemplate: (TemplateId, String, LocalDate) -> Unit,
    onDeleteTemplate: (TemplateId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedTemplate = templates.firstOrNull { it.id.value == selectedTemplateId }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("templates-screen"),
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
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                Text(
                    "Templates",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Create a fresh project while keeping relative dates, workflow stages, " +
                    "open milestones and open task structure.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
        }

        if (templates.isEmpty()) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        icon = Icons.Rounded.Description,
                        title = "No templates yet",
                        modifier = Modifier.padding(top = 32.dp),
                    )
                    Text(
                        "Open a project and choose Save as template.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(templates, key = { it.id.value }) { template ->
                TemplateRow(
                    template = template,
                    onUse = { selectedTemplateId = template.id.value },
                    onDelete = { onDeleteTemplate(template.id) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    selectedTemplate?.let { template ->
        UseTemplateSheet(
            template = template,
            existingProjectNames = existingProjectNames,
            today = today,
            onDismiss = { selectedTemplateId = null },
            onUse = { name, anchorDate ->
                onUseTemplate(template.id, name, anchorDate)
                selectedTemplateId = null
            },
        )
    }
}

@Composable
private fun TemplateRow(
    template: Template,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(template.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${countLabel(template.tasks.size, "task")} • " +
                    "${countLabel(template.milestones.size, "milestone")} • " +
                    countLabel(template.workflowStatuses.size, "stage"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onUse,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("use-template-${template.id.value}"),
        ) {
            Text("Use")
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .size(48.dp)
                .testTag("delete-template-${template.id.value}"),
        ) {
            Icon(Icons.Rounded.Delete, contentDescription = "Delete ${template.name}")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UseTemplateSheet(
    template: Template,
    existingProjectNames: Set<String>,
    today: LocalDate,
    onDismiss: () -> Unit,
    onUse: (String, LocalDate) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable(template.id.value) {
        mutableStateOf(template.projectName.take(MAX_PROJECT_NAME_LENGTH))
    }
    var anchorEpochDay by rememberSaveable(template.id.value) {
        mutableStateOf(today.toEpochDay())
    }
    var showDatePicker by rememberSaveable(template.id.value) { mutableStateOf(false) }
    val anchorDate = LocalDate.ofEpochDay(anchorEpochDay)
    val trimmedName = name.trim()
    val duplicateName = existingProjectNames.any { it.equals(trimmedName, ignoreCase = true) }
    val nameError = when {
        trimmedName.isEmpty() -> "Enter a project name"
        name.length > MAX_PROJECT_NAME_LENGTH ->
            "Keep project names under $MAX_PROJECT_NAME_LENGTH characters"
        duplicateName -> "An active project already uses that name"
        else -> null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("use-template-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                "Use ${template.name}",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "The earliest saved date becomes the project start below; every other " +
                    "date keeps its relative offset.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(MAX_PROJECT_NAME_LENGTH + 1)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("template-project-name"),
                label = { Text("New project name") },
                supportingText = nameError?.let { { Text(it) } },
                isError = nameError != null,
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("template-start-date"),
            ) {
                Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Start ${anchorDate.format(TEMPLATE_DATE_FORMAT)}")
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onUse(trimmedName, anchorDate) },
                    enabled = nameError == null,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("confirm-use-template"),
                ) {
                    Text("Create project")
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = anchorDate
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selected ->
                            anchorEpochDay = Instant.ofEpochMilli(selected)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toEpochDay()
                        }
                        showDatePicker = false
                    },
                    enabled = datePickerState.selectedDateMillis != null,
                ) {
                    Text("Set date")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
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
                    "Bin",
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
                    title = "Bin is empty",
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
private fun InsightSummary(snapshot: InsightsSnapshot) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(
                    R.string.insights_last_days,
                    InsightsRange.SEVEN_DAYS.dayCount,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.insights_summary,
                    snapshot.completed.current,
                    durationText(snapshot.quality.recordedTime.included),
                ),
                style = MaterialTheme.typography.bodyLarge,
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

private val TRASH_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
private val ARCHIVE_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
private val TEMPLATE_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.UK)

private fun countLabel(count: Int, singular: String): String =
    "$count $singular${if (count == 1) "" else "s"}"

private const val MAX_PROJECT_NAME_LENGTH = 120

private enum class MoreDestination {
    OVERVIEW,
    INSIGHTS,
    ARCHIVE,
    TRASH,
    TEMPLATES,
}
