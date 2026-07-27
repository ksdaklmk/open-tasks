package app.opentasks.feature.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.AlertDialog
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
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkflowStatusId
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ProjectEdit(
    val name: String,
    val summary: String,
    val health: ProjectHealth,
    val dueDate: LocalDate?,
)

enum class WorkflowMove {
    EARLIER,
    LATER,
}

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
    onCreateWorkflowStatus: (ProjectId, String, SemanticStatus) -> Unit = { _, _, _ -> },
    onRenameWorkflowStatus: (WorkflowStatusId, String) -> Unit = { _, _ -> },
    onMoveWorkflowStatus: (WorkflowStatusId, WorkflowMove) -> Unit = { _, _ -> },
    onArchiveWorkflowStatus: (WorkflowStatusId) -> Unit = {},
    onRestoreWorkflowStatus: (WorkflowStatusId) -> Unit = {},
    onCreateMilestone: (ProjectId, String, LocalDate?) -> Unit = { _, _, _ -> },
    onUpdateMilestone: (MilestoneId, String, LocalDate?, Instant?) -> Unit =
        { _, _, _, _ -> },
    onDeleteMilestone: (MilestoneId) -> Unit = {},
    onCaptureTemplate: (ProjectId, String) -> Unit = { _, _ -> },
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
            onCreateWorkflowStatus = onCreateWorkflowStatus,
            onRenameWorkflowStatus = onRenameWorkflowStatus,
            onMoveWorkflowStatus = onMoveWorkflowStatus,
            onArchiveWorkflowStatus = onArchiveWorkflowStatus,
            onRestoreWorkflowStatus = onRestoreWorkflowStatus,
            onCreateMilestone = onCreateMilestone,
            onUpdateMilestone = onUpdateMilestone,
            onDeleteMilestone = onDeleteMilestone,
            onCaptureTemplate = onCaptureTemplate,
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
                    onCreateWorkflowStatus = onCreateWorkflowStatus,
                    onRenameWorkflowStatus = onRenameWorkflowStatus,
                    onMoveWorkflowStatus = onMoveWorkflowStatus,
                    onArchiveWorkflowStatus = onArchiveWorkflowStatus,
                    onRestoreWorkflowStatus = onRestoreWorkflowStatus,
                    onCreateMilestone = onCreateMilestone,
                    onUpdateMilestone = onUpdateMilestone,
                    onDeleteMilestone = onDeleteMilestone,
                    onCaptureTemplate = onCaptureTemplate,
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
        val activeMilestones = milestones.filter {
            it.projectId in activeProjectIds && it.completedAt == null
        }
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
    onCreateWorkflowStatus: (ProjectId, String, SemanticStatus) -> Unit,
    onRenameWorkflowStatus: (WorkflowStatusId, String) -> Unit,
    onMoveWorkflowStatus: (WorkflowStatusId, WorkflowMove) -> Unit,
    onArchiveWorkflowStatus: (WorkflowStatusId) -> Unit,
    onRestoreWorkflowStatus: (WorkflowStatusId) -> Unit,
    onCreateMilestone: (ProjectId, String, LocalDate?) -> Unit,
    onUpdateMilestone: (MilestoneId, String, LocalDate?, Instant?) -> Unit,
    onDeleteMilestone: (MilestoneId) -> Unit,
    onCaptureTemplate: (ProjectId, String) -> Unit,
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
    var showWorkflowEditor by rememberSaveable(project.id.value) { mutableStateOf(false) }
    var milestoneEditorKey by rememberSaveable(project.id.value) {
        mutableStateOf<String?>(null)
    }
    var showTemplateCapture by rememberSaveable(project.id.value) { mutableStateOf(false) }
    var lastSubmitted by remember(project.id.value) { mutableStateOf<ProjectEdit?>(null) }
    var skipInitialRepositorySync by remember(project.id.value) { mutableStateOf(true) }

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

    LaunchedEffect(persistedValue) {
        if (skipInitialRepositorySync) {
            skipInitialRepositorySync = false
        } else {
            val incoming = project.toProjectEdit()
            if (incoming != lastSubmitted && incoming != editorValue) {
                name = incoming.name
                summary = incoming.summary
                healthName = incoming.health.name
                dueEpochMillis = incoming.dueDate?.toEpochMillis()
            }
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
                onValueChange = {
                    name = it.take(MAX_PROJECT_NAME_LENGTH + 1)
                },
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
                onValueChange = {
                    summary = it.take(MAX_PROJECT_SUMMARY_LENGTH + 1)
                },
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

            SectionHeader(
                title = "Workflow",
                supportingText = "Custom stages for this project",
                action = {
                    TextButton(
                        onClick = { showWorkflowEditor = true },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("manage-workflow"),
                    ) {
                        Text("Manage")
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                workflowStatuses
                    .filter { it.projectId == project.id && it.archivedAt == null }
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
                    "${projectMilestones.count { it.completedAt == null }} open • " +
                        "${projectMilestones.count { it.completedAt != null }} complete"
                },
                action = {
                    TextButton(
                        onClick = { milestoneEditorKey = NEW_MILESTONE_KEY },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("add-milestone"),
                    ) {
                        Icon(Icons.Rounded.Flag, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add")
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
        }

        items(projectMilestones, key = { it.id.value }) { milestone ->
            MilestoneRow(
                milestone = milestone,
                projectName = project.name,
                onClick = { milestoneEditorKey = milestone.id.value },
            )
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
            SectionHeader(
                title = "Template",
                supportingText = "Reuse this workflow, open milestones and open task structure.",
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showTemplateCapture = true },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("save-project-template"),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save as template")
            }
            Spacer(Modifier.height(28.dp))
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

    if (showWorkflowEditor) {
        WorkflowEditorSheet(
            project = project,
            statuses = workflowStatuses.filter { it.projectId == project.id },
            tasks = projectTasks,
            onDismiss = { showWorkflowEditor = false },
            onCreate = onCreateWorkflowStatus,
            onRename = onRenameWorkflowStatus,
            onMove = onMoveWorkflowStatus,
            onArchive = onArchiveWorkflowStatus,
            onRestore = onRestoreWorkflowStatus,
        )
    }

    milestoneEditorKey?.let { key ->
        val milestone = projectMilestones.firstOrNull { it.id.value == key }
        if (key == NEW_MILESTONE_KEY || milestone != null) {
            MilestoneEditorSheet(
                project = project,
                milestone = milestone,
                assignedTaskCount = milestone?.let { selected ->
                    projectTasks.count { it.milestoneId == selected.id }
                } ?: 0,
                existingNames = projectMilestones
                    .filterNot { it.id == milestone?.id }
                    .mapTo(linkedSetOf()) { it.name },
                onDismiss = { milestoneEditorKey = null },
                onCreate = onCreateMilestone,
                onUpdate = onUpdateMilestone,
                onDelete = onDeleteMilestone,
            )
        }
    }

    if (showTemplateCapture) {
        SaveTemplateSheet(
            project = project,
            openTaskCount = projectTasks.count { !it.isCompleted },
            openMilestoneCount = projectMilestones.count { it.completedAt == null },
            workflowCount = workflowStatuses.count {
                it.projectId == project.id && it.archivedAt == null
            },
            onDismiss = { showTemplateCapture = false },
            onSave = { name ->
                onCaptureTemplate(project.id, name)
                showTemplateCapture = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveTemplateSheet(
    project: Project,
    openTaskCount: Int,
    openMilestoneCount: Int,
    workflowCount: Int,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable(project.id.value) {
        mutableStateOf("${project.name} template".take(MAX_TEMPLATE_NAME_LENGTH))
    }
    val trimmedName = name.trim()
    val nameError = when {
        trimmedName.isEmpty() -> "Enter a template name"
        name.length > MAX_TEMPLATE_NAME_LENGTH ->
            "Keep template names under $MAX_TEMPLATE_NAME_LENGTH characters"
        else -> null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("save-template-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                "Save as template",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${countLabel(workflowCount, "workflow stage")} • " +
                    "${countLabel(openMilestoneCount, "open milestone")} • " +
                    countLabel(openTaskCount, "open task"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Completed work and Bin items are left out. Dates will shift from the " +
                    "earliest saved date when you use the template.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(MAX_TEMPLATE_NAME_LENGTH + 1)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("template-name-field"),
                label = { Text("Template name") },
                supportingText = {
                    Text(nameError ?: "${name.length}/$MAX_TEMPLATE_NAME_LENGTH")
                },
                isError = nameError != null,
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onSave(trimmedName) },
                    enabled = nameError == null,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("confirm-save-template"),
                ) {
                    Text("Save template")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MilestoneEditorSheet(
    project: Project,
    milestone: Milestone?,
    assignedTaskCount: Int,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (ProjectId, String, LocalDate?) -> Unit,
    onUpdate: (MilestoneId, String, LocalDate?, Instant?) -> Unit,
    onDelete: (MilestoneId) -> Unit,
) {
    val stateKey = milestone?.id?.value ?: NEW_MILESTONE_KEY
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable(stateKey) { mutableStateOf(milestone?.name.orEmpty()) }
    var dueEpochMillis by rememberSaveable(stateKey) {
        mutableStateOf(milestone?.dueDate?.toEpochMillis())
    }
    var showDatePicker by rememberSaveable(stateKey) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(stateKey) { mutableStateOf(false) }
    val trimmedName = name.trim()
    val duplicate = existingNames.any { it.equals(trimmedName, ignoreCase = true) }
    val nameError = when {
        name.length > MAX_MILESTONE_NAME_LENGTH ->
            "Keep milestone names under $MAX_MILESTONE_NAME_LENGTH characters"
        trimmedName.isNotEmpty() && duplicate ->
            "This project already uses that milestone name"
        else -> null
    }
    val dueDate = dueEpochMillis?.let {
        LocalDate.ofInstant(Instant.ofEpochMilli(it), ZoneOffset.UTC)
    }
    val canSave = trimmedName.isNotEmpty() && nameError == null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(
                if (milestone == null) "New milestone" else "Edit milestone",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                project.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (milestone != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (milestone.completedAt == null) {
                        "$assignedTaskCount assigned tasks"
                    } else {
                        "Completed • $assignedTaskCount assigned tasks"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(MAX_MILESTONE_NAME_LENGTH + 1)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("milestone-name-field"),
                label = { Text("Milestone name") },
                singleLine = true,
                isError = nameError != null,
                supportingText = {
                    Text(nameError ?: "${name.length}/$MAX_MILESTONE_NAME_LENGTH")
                },
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("milestone-due-button"),
                ) {
                    Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(dueDate?.format(PROJECT_DATE_FORMAT) ?: "Add due date")
                }
                if (dueDate != null) {
                    IconButton(
                        onClick = { dueEpochMillis = null },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Clear due date")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (milestone == null) {
                        onCreate(project.id, trimmedName, dueDate)
                    } else {
                        onUpdate(
                            milestone.id,
                            trimmedName,
                            dueDate,
                            milestone.completedAt,
                        )
                    }
                    onDismiss()
                },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("save-milestone"),
            ) {
                Text(if (milestone == null) "Add milestone" else "Save changes")
            }

            if (milestone != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        onUpdate(
                            milestone.id,
                            trimmedName,
                            dueDate,
                            if (milestone.completedAt == null) Instant.now() else null,
                        )
                        onDismiss()
                    },
                    enabled = canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("toggle-milestone-completion"),
                ) {
                    Icon(
                        if (milestone.completedAt == null) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.Schedule
                        },
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (milestone.completedAt == null) {
                            "Mark complete"
                        } else {
                            "Reopen milestone"
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("delete-milestone"),
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete milestone")
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = dueEpochMillis,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dueEpochMillis = datePickerState.selectedDateMillis
                        showDatePicker = false
                    },
                    enabled = datePickerState.selectedDateMillis != null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Set date")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDatePicker = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (confirmDelete && milestone != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${milestone.name}?") },
            text = {
                Text(
                    if (assignedTaskCount == 0) {
                        "This removes the milestone. You can undo immediately afterwards."
                    } else {
                        "$assignedTaskCount assigned tasks will keep their project but lose " +
                            "this milestone. You can undo immediately afterwards."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete(milestone.id)
                        onDismiss()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDelete = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WorkflowEditorSheet(
    project: Project,
    statuses: List<WorkflowStatus>,
    tasks: List<Task>,
    onDismiss: () -> Unit,
    onCreate: (ProjectId, String, SemanticStatus) -> Unit,
    onRename: (WorkflowStatusId, String) -> Unit,
    onMove: (WorkflowStatusId, WorkflowMove) -> Unit,
    onArchive: (WorkflowStatusId) -> Unit,
    onRestore: (WorkflowStatusId) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeStatuses = statuses
        .filter { it.archivedAt == null }
        .sortedBy(WorkflowStatus::rank)
    val archivedStatuses = statuses
        .filter { it.archivedAt != null }
        .sortedBy(WorkflowStatus::rank)
    var newName by rememberSaveable(project.id.value) { mutableStateOf("") }
    var newSemanticName by rememberSaveable(project.id.value) {
        mutableStateOf(SemanticStatus.PLANNED.name)
    }
    var pendingArchiveId by rememberSaveable(project.id.value) {
        mutableStateOf<String?>(null)
    }
    val trimmedNewName = newName.trim()
    val duplicateNewName = activeStatuses.any {
        it.name.equals(trimmedNewName, ignoreCase = true)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(
                "Workflow",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                project.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Names are project-specific. Reporting still uses the category shown under each status.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            activeStatuses.forEachIndexed { index, status ->
                WorkflowStatusEditorRow(
                    status = status,
                    taskCount = tasks.count { it.statusId == status.id },
                    canMoveEarlier = index > 0,
                    canMoveLater = index < activeStatuses.lastIndex,
                    onRename = onRename,
                    onMove = onMove,
                    onArchive = { pendingArchiveId = status.id.value },
                )
                if (index < activeStatuses.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            Spacer(Modifier.height(28.dp))
            SectionHeader(
                title = "Add status",
                supportingText = "The category keeps progress and reports consistent.",
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = newName,
                onValueChange = {
                    newName = it.take(MAX_WORKFLOW_STATUS_NAME_LENGTH + 1)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("new-workflow-status-name"),
                label = { Text("Status name") },
                singleLine = true,
                isError = newName.length > MAX_WORKFLOW_STATUS_NAME_LENGTH ||
                    (trimmedNewName.isNotEmpty() && duplicateNewName),
                supportingText = {
                    Text(
                        when {
                            newName.length > MAX_WORKFLOW_STATUS_NAME_LENGTH ->
                                "Keep status names under $MAX_WORKFLOW_STATUS_NAME_LENGTH characters"
                            trimmedNewName.isNotEmpty() && duplicateNewName ->
                                "This workflow already uses that name"
                            else -> "${newName.length}/$MAX_WORKFLOW_STATUS_NAME_LENGTH"
                        },
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
            Text("Reporting category", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SemanticStatus.entries.forEach { semantic ->
                    FilterChip(
                        selected = newSemanticName == semantic.name,
                        onClick = { newSemanticName = semantic.name },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text(semantic.readableName()) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onCreate(
                        project.id,
                        trimmedNewName,
                        SemanticStatus.valueOf(newSemanticName),
                    )
                    newName = ""
                },
                enabled = trimmedNewName.isNotEmpty() &&
                    newName.length <= MAX_WORKFLOW_STATUS_NAME_LENGTH &&
                    !duplicateNewName,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("add-workflow-status"),
            ) {
                Text("Add status")
            }

            if (archivedStatuses.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                SectionHeader(
                    title = "Archived",
                    supportingText = "Assigned tasks keep their previous status until moved.",
                )
                Spacer(Modifier.height(8.dp))
                archivedStatuses.forEach { status ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(status.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${status.semanticStatus.readableName()} • " +
                                    "${tasks.count { it.statusId == status.id }} tasks",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { onRestore(status.id) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("restore-workflow-${status.id.value}"),
                        ) {
                            Icon(
                                Icons.Rounded.Unarchive,
                                contentDescription = "Restore ${status.name}",
                            )
                        }
                    }
                }
            }
        }
    }

    pendingArchiveId?.let { rawId ->
        val status = statuses.firstOrNull { it.id.value == rawId }
        if (status != null) {
            val taskCount = tasks.count { it.statusId == status.id }
            AlertDialog(
                onDismissRequest = { pendingArchiveId = null },
                title = { Text("Archive ${status.name}?") },
                text = {
                    Text(
                        if (taskCount == 0) {
                            "It will no longer be available when moving tasks."
                        } else {
                            "$taskCount assigned tasks will keep this status until you move them."
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingArchiveId = null
                            onArchive(status.id)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Archive")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingArchiveId = null },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun WorkflowStatusEditorRow(
    status: WorkflowStatus,
    taskCount: Int,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onRename: (WorkflowStatusId, String) -> Unit,
    onMove: (WorkflowStatusId, WorkflowMove) -> Unit,
    onArchive: () -> Unit,
) {
    var name by rememberSaveable(status.id.value) { mutableStateOf(status.name) }
    LaunchedEffect(status.name) {
        if (name == status.name || name.trim() == status.name) name = status.name
    }
    val trimmedName = name.trim()
    val invalid = trimmedName.isEmpty() || name.length > MAX_WORKFLOW_STATUS_NAME_LENGTH

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.take(MAX_WORKFLOW_STATUS_NAME_LENGTH + 1)
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("workflow-name-${status.id.value}"),
            label = { Text("Status name") },
            singleLine = true,
            isError = invalid,
            supportingText = {
                Text("${status.semanticStatus.readableName()} • $taskCount tasks")
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onRename(status.id, trimmedName) },
                enabled = !invalid && trimmedName != status.name,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("save-workflow-name-${status.id.value}"),
            ) {
                Text("Save name")
            }
            IconButton(
                onClick = { onMove(status.id, WorkflowMove.EARLIER) },
                enabled = canMoveEarlier,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("move-workflow-earlier-${status.id.value}"),
            ) {
                Icon(
                    Icons.Rounded.ArrowUpward,
                    contentDescription = "Move ${status.name} earlier",
                )
            }
            IconButton(
                onClick = { onMove(status.id, WorkflowMove.LATER) },
                enabled = canMoveLater,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("move-workflow-later-${status.id.value}"),
            ) {
                Icon(
                    Icons.Rounded.ArrowDownward,
                    contentDescription = "Move ${status.name} later",
                )
            }
            IconButton(
                onClick = onArchive,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("archive-workflow-${status.id.value}"),
            ) {
                Icon(Icons.Rounded.Archive, contentDescription = "Archive ${status.name}")
            }
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
                onValueChange = {
                    name = it.take(MAX_PROJECT_NAME_LENGTH + 1)
                },
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
                onValueChange = {
                    summary = it.take(MAX_PROJECT_SUMMARY_LENGTH + 1)
                },
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
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(
            role = Role.Button,
            onClickLabel = "Edit ${milestone.name}",
            onClick = onClick,
        )
    }
    Row(
        modifier = rowModifier
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
                if (milestone.completedAt == null) projectName else "$projectName • Complete",
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

private const val NEW_MILESTONE_KEY = "__new_milestone__"
private const val MAX_MILESTONE_NAME_LENGTH = 120

private fun countLabel(count: Int, singular: String): String =
    "$count $singular${if (count == 1) "" else "s"}"

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

private val PROJECT_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
private const val PROJECT_SAVE_DEBOUNCE_MILLIS = 600L
private const val MAX_PROJECT_NAME_LENGTH = 120
private const val MAX_PROJECT_SUMMARY_LENGTH = 1_000
private const val MAX_WORKFLOW_STATUS_NAME_LENGTH = 64
private const val MAX_TEMPLATE_NAME_LENGTH = 120
