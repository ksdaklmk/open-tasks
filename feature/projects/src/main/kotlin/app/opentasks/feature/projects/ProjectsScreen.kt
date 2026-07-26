package app.opentasks.feature.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.EmptyState
import app.opentasks.core.designsystem.ProjectProgressRow
import app.opentasks.core.designsystem.SectionHeader
import app.opentasks.core.designsystem.readableName
import app.opentasks.core.model.Milestone
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatus
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class ProjectEdit(
    val name: String,
    val summary: String,
    val health: ProjectHealth,
    val dueDate: LocalDate?,
)

@Composable
fun ProjectsScreen(
    projects: List<Project>,
    tasks: List<Task>,
    milestones: List<Milestone>,
    workflowStatuses: List<WorkflowStatus>,
    selectedProjectId: ProjectId?,
    showDetailPane: Boolean,
    onSelectProject: (ProjectId) -> Unit,
    onCloseDetail: () -> Unit,
    onUpdateProject: (ProjectId, ProjectEdit) -> Unit,
    onArchiveProject: (Project) -> Unit,
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeTasks = tasks.filter { it.deletedAt == null }
    val activeProjects = projects
        .filter { it.archivedAt == null }
        .map { project ->
            val projectTasks = activeTasks.filter { it.projectId == project.id }
            project.copy(
                completedTasks = projectTasks.count(Task::isCompleted),
                totalTasks = projectTasks.size,
            )
        }
    val selectedProject = activeProjects.firstOrNull { it.id == selectedProjectId }

    if (!showDetailPane && selectedProject != null) {
        ProjectWorkbench(
            project = selectedProject,
            tasks = tasks,
            milestones = milestones,
            workflowStatuses = workflowStatuses,
            onBack = onCloseDetail,
            onUpdate = { onUpdateProject(selectedProject.id, it) },
            onArchive = { onArchiveProject(selectedProject) },
            onOpenTask = onOpenTask,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    Row(modifier = modifier.fillMaxSize()) {
        ProjectListPane(
            projects = activeProjects,
            milestones = milestones,
            selectedProjectId = selectedProjectId,
            onSelectProject = onSelectProject,
            modifier = if (showDetailPane) Modifier.width(390.dp) else Modifier.fillMaxWidth(),
        )

        if (showDetailPane) {
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp),
            )
            if (selectedProject == null) {
                EmptyState(
                    icon = Icons.Rounded.FolderOpen,
                    title = "Choose a project",
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.CenterVertically),
                )
            } else {
                ProjectWorkbench(
                    project = selectedProject,
                    tasks = tasks,
                    milestones = milestones,
                    workflowStatuses = workflowStatuses,
                    onBack = null,
                    onUpdate = { onUpdateProject(selectedProject.id, it) },
                    onArchive = { onArchiveProject(selectedProject) },
                    onOpenTask = onOpenTask,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ProjectListPane(
    projects: List<Project>,
    milestones: List<Milestone>,
    selectedProjectId: ProjectId?,
    onSelectProject: (ProjectId) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxHeight(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 112.dp,
        ),
    ) {
        item {
            Text(
                "Projects",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(24.dp))
            SectionHeader(
                title = "Active workspace",
                supportingText =
                    "${projects.size} projects • ${projects.count { it.status == ProjectHealth.AT_RISK }} at risk",
            )
            Spacer(Modifier.height(8.dp))
        }

        if (projects.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.FolderOpen,
                    title = "No active projects",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                )
            }
        }

        items(projects, key = { it.id.value }) { project ->
            Surface(
                color = if (selectedProjectId == project.id) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    ProjectProgressRow(
                        project = project,
                        onClick = { onSelectProject(project.id) },
                    )
                    if (project.summary.isNotBlank()) {
                        Text(
                            project.summary,
                            modifier = Modifier.padding(start = 36.dp, end = 8.dp, bottom = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        val activeProjectIds = projects.mapTo(hashSetOf(), Project::id)
        val activeMilestones = milestones.filter { it.projectId in activeProjectIds }
        if (activeMilestones.isNotEmpty()) {
            item {
                Spacer(Modifier.height(32.dp))
                SectionHeader(title = "Next milestones")
                Spacer(Modifier.height(8.dp))
            }
            items(activeMilestones, key = { it.id.value }) { milestone ->
                MilestoneRow(
                    milestone = milestone,
                    projectName =
                        projects.firstOrNull { it.id == milestone.projectId }?.name ?: "Project",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectWorkbench(
    project: Project,
    tasks: List<Task>,
    milestones: List<Milestone>,
    workflowStatuses: List<WorkflowStatus>,
    onBack: (() -> Unit)?,
    onUpdate: (ProjectEdit) -> Unit,
    onArchive: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable(project.id.value) { mutableStateOf(project.name) }
    var summary by rememberSaveable(project.id.value) { mutableStateOf(project.summary) }
    var healthName by rememberSaveable(project.id.value) {
        mutableStateOf(project.status.name)
    }
    var dueEpochMillis by rememberSaveable(project.id.value) {
        mutableStateOf(project.dueDate?.toEpochMillis())
    }
    var showDuePicker by rememberSaveable(project.id.value) { mutableStateOf(false) }
    var lastSubmitted by remember(project.id.value) { mutableStateOf<ProjectEdit?>(null) }

    val editorValue = ProjectEdit(
        name = name.trim(),
        summary = summary.trim(),
        health = ProjectHealth.valueOf(healthName),
        dueDate = dueEpochMillis?.let { LocalDate.ofInstant(Instant.ofEpochMilli(it), ZoneOffset.UTC) },
    )
    val persistedValue = project.toProjectEdit()
    val nameError = when {
        name.isBlank() -> "A project needs a name"
        name.trim().length > 120 -> "Keep project names under 120 characters"
        else -> null
    }
    val summaryError = if (summary.length > 1_000) {
        "Keep project summaries under 1,000 characters"
    } else {
        null
    }
    val hasErrors = nameError != null || summaryError != null
    val projectTasks = tasks.filter { it.projectId == project.id && it.deletedAt == null }
    val projectMilestones = milestones.filter { it.projectId == project.id }
    val completedCount = projectTasks.count(Task::isCompleted)
    val openCount = projectTasks.size - completedCount
    val blockedCount = projectTasks.count(Task::isBlocked)

    LaunchedEffect(project) {
        val incoming = project.toProjectEdit()
        if (incoming != lastSubmitted && incoming != editorValue) {
            name = incoming.name
            summary = incoming.summary
            healthName = incoming.health.name
            dueEpochMillis = incoming.dueDate?.toEpochMillis()
        }
    }

    LaunchedEffect(editorValue, hasErrors) {
        if (hasErrors || editorValue == persistedValue || editorValue == lastSubmitted) {
            return@LaunchedEffect
        }
        delay(PROJECT_SAVE_DEBOUNCE_MILLIS)
        lastSubmitted = editorValue
        onUpdate(editorValue)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .testTag("project-workbench-list")
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.Escape &&
                    onBack != null
                ) {
                    onBack()
                    true
                } else {
                    false
                }
            },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 24.dp,
            top = 16.dp,
            end = 24.dp,
            bottom = 112.dp,
        ),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to projects")
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Project workbench",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        project.name,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                Text(
                    when {
                        hasErrors -> "Fix fields to save"
                        editorValue != persistedValue -> "Saving…"
                        else -> "Saved on this device"
                    },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hasErrors) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("project-name-field"),
                label = { Text("Project name") },
                singleLine = true,
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = summary,
                onValueChange = { summary = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("project-summary-field"),
                label = { Text("Summary") },
                minLines = 3,
                maxLines = 6,
                isError = summaryError != null,
                supportingText = {
                    Text(summaryError ?: "${summary.length}/1,000")
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {}),
            )
            Spacer(Modifier.height(20.dp))

            SectionHeader(title = "Health")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProjectHealth.entries.forEach { health ->
                    FilterChip(
                        selected = editorValue.health == health,
                        onClick = { healthName = health.name },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("project-health-${health.name}"),
                        label = { Text(health.readableName()) },
                        leadingIcon = {
                            Icon(
                                health.icon(),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = { showDuePicker = true },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("project-due-button"),
            ) {
                Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    editorValue.dueDate?.format(PROJECT_DATE_FORMAT) ?: "Add project due date",
                )
            }
            if (editorValue.dueDate != null) {
                TextButton(
                    onClick = { dueEpochMillis = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Clear due date")
                }
            }
            Spacer(Modifier.height(24.dp))

            SectionHeader(
                title = "Progress",
                supportingText = "$openCount open • $completedCount complete • $blockedCount blocked",
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = {
                    if (projectTasks.isEmpty()) 0f else completedCount.toFloat() / projectTasks.size
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(28.dp))

            SectionHeader(title = "Workflow")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                workflowStatuses
                    .filter { it.archivedAt == null }
                    .sortedBy(WorkflowStatus::rank)
                    .forEach { status ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                "${status.name} ${projectTasks.count { it.statusId == status.id }}",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
            }
            Spacer(Modifier.height(28.dp))

            SectionHeader(
                title = "Milestones",
                supportingText = if (projectMilestones.isEmpty()) {
                    "No milestones yet."
                } else {
                    "${projectMilestones.size} planned checkpoints"
                },
            )
            Spacer(Modifier.height(8.dp))
        }

        items(projectMilestones, key = { it.id.value }) { milestone ->
            MilestoneRow(milestone = milestone, projectName = project.name)
        }

        item {
            Spacer(Modifier.height(24.dp))
            SectionHeader(
                title = "Tasks",
                supportingText = if (projectTasks.isEmpty()) {
                    "No active tasks are assigned to this project."
                } else {
                    "${projectTasks.size} active tasks"
                },
            )
            Spacer(Modifier.height(8.dp))
        }

        items(projectTasks, key = { it.id.value }) { task ->
            ProjectTaskRow(task = task, onOpen = { onOpenTask(task.id) })
        }

        item {
            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))
            SectionHeader(title = "Project lifecycle")
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onArchive,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("archive-project"),
            ) {
                Icon(Icons.Rounded.Archive, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Archive project")
            }
        }
    }

    if (showDuePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = dueEpochMillis)
        DatePickerDialog(
            onDismissRequest = { showDuePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dueEpochMillis = datePickerState.selectedDateMillis
                        showDuePicker = false
                    },
                    enabled = datePickerState.selectedDateMillis != null,
                ) {
                    Text("Set date")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDuePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectSheet(
    onDismiss: () -> Unit,
    onCreate: (name: String, summary: String) -> Unit,
    existingProjectNames: Set<String> = emptySet(),
) {
    var name by rememberSaveable { mutableStateOf("") }
    var summary by rememberSaveable { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val trimmedName = name.trim()
    val nameError = when {
        name.isBlank() -> null
        trimmedName.length > MAX_PROJECT_NAME_LENGTH ->
            "Keep project names under $MAX_PROJECT_NAME_LENGTH characters"
        existingProjectNames.any { it.equals(trimmedName, ignoreCase = true) } ->
            "An active project already uses that name"
        else -> null
    }
    val summaryError = summary.length > MAX_PROJECT_SUMMARY_LENGTH

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                "New project",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("new-project-name"),
                label = { Text("Project name") },
                singleLine = true,
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = summary,
                onValueChange = { summary = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("new-project-summary"),
                label = { Text("Summary (optional)") },
                minLines = 2,
                maxLines = 5,
                isError = summaryError,
                supportingText = {
                    Text(
                        if (summaryError) {
                            "Keep project summaries under $MAX_PROJECT_SUMMARY_LENGTH characters"
                        } else {
                            "${summary.length}/$MAX_PROJECT_SUMMARY_LENGTH"
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (trimmedName.isNotEmpty() && nameError == null && !summaryError) {
                            onCreate(trimmedName, summary.trim())
                        }
                    },
                ),
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onCreate(trimmedName, summary.trim()) },
                    enabled = trimmedName.isNotEmpty() && nameError == null && !summaryError,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("create-project"),
                ) {
                    Text("Create project")
                }
            }
        }
    }
}

@Composable
private fun ProjectTaskRow(
    task: Task,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Open ${task.title}",
                    onClick = onOpen,
                )
                .heightIn(min = 64.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (task.isCompleted) {
                    Icons.Rounded.CheckCircle
                } else if (task.isBlocked) {
                    Icons.Rounded.Block
                } else {
                    Icons.Rounded.Schedule
                },
                contentDescription = task.semanticStatus.readableName(),
                tint = if (task.isBlocked) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    task.semanticStatus.readableName(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Open task",
            )
        }
    }
}

@Composable
private fun MilestoneRow(
    milestone: Milestone,
    projectName: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Flag,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(milestone.name, style = MaterialTheme.typography.titleMedium)
            Text(
                projectName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            milestone.dueDate?.format(PROJECT_DATE_FORMAT) ?: "No date",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun Project.toProjectEdit(): ProjectEdit = ProjectEdit(
    name = name,
    summary = summary,
    health = status,
    dueDate = dueDate,
)

private fun ProjectHealth.readableName(): String = when (this) {
    ProjectHealth.ON_TRACK -> "On track"
    ProjectHealth.AT_RISK -> "At risk"
    ProjectHealth.BLOCKED -> "Blocked"
    ProjectHealth.COMPLETE -> "Complete"
}

private fun ProjectHealth.icon() = when (this) {
    ProjectHealth.ON_TRACK,
    ProjectHealth.COMPLETE,
    -> Icons.Rounded.CheckCircle
    ProjectHealth.AT_RISK -> Icons.Rounded.Schedule
    ProjectHealth.BLOCKED -> Icons.Rounded.Block
}

private fun LocalDate.toEpochMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private val PROJECT_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy")
private const val PROJECT_SAVE_DEBOUNCE_MILLIS = 600L
private const val MAX_PROJECT_NAME_LENGTH = 120
private const val MAX_PROJECT_SUMMARY_LENGTH = 1_000
