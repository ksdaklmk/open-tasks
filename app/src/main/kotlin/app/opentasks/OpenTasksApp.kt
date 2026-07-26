package app.opentasks

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.feature.home.HomeScreen
import app.opentasks.feature.more.MoreScreen
import app.opentasks.feature.projects.NewProjectSheet
import app.opentasks.feature.projects.ProjectEdit
import app.opentasks.feature.projects.ProjectsScreen
import app.opentasks.feature.schedule.ScheduleScreen
import app.opentasks.feature.tasks.TaskEdit
import app.opentasks.feature.tasks.TasksScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
sealed interface WorkspaceRoute : NavKey

@Serializable
data object HomeRoute : WorkspaceRoute

@Serializable
data object TasksRoute : WorkspaceRoute

@Serializable
data object ProjectsRoute : WorkspaceRoute

@Serializable
data object ScheduleRoute : WorkspaceRoute

@Serializable
data object MoreRoute : WorkspaceRoute

private data class NavigationDestination(
    val route: WorkspaceRoute,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    NavigationDestination(HomeRoute, "Home", Icons.Rounded.Home),
    NavigationDestination(TasksRoute, "Tasks", Icons.Rounded.CheckCircle),
    NavigationDestination(ProjectsRoute, "Projects", Icons.Rounded.FolderOpen),
    NavigationDestination(ScheduleRoute, "Schedule", Icons.Rounded.CalendarMonth),
    NavigationDestination(MoreRoute, "More", Icons.Rounded.MoreHoriz),
)

internal data class SnackbarPresentation(
    val duration: SnackbarDuration,
    val withDismissAction: Boolean,
    val timeoutMillis: Long?,
)

internal const val UNDO_SNACKBAR_TIMEOUT_MILLIS = 8_000L

internal fun shouldShowNavigationLabels(fontScale: Float): Boolean = fontScale < 1.5f

internal fun snackbarPresentation(hasUndo: Boolean): SnackbarPresentation =
    SnackbarPresentation(
        duration = if (hasUndo) SnackbarDuration.Indefinite else SnackbarDuration.Short,
        withDismissAction = false,
        timeoutMillis = UNDO_SNACKBAR_TIMEOUT_MILLIS.takeIf { hasUndo },
    )

@Composable
fun OpenTasksApp(
    activity: Activity,
    quickAddSignal: Int,
    viewModel: WorkspaceViewModel = viewModel(),
) {
    OpenTasksTheme {
        val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
        val selectedTaskValue by viewModel.selectedTaskId.collectAsStateWithLifecycle()
        val selectedProjectValue by viewModel.selectedProjectId.collectAsStateWithLifecycle()
        val pendingBlocked by viewModel.pendingBlockedCompletion.collectAsStateWithLifecycle()
        val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }
        val accessibilityManager = LocalAccessibilityManager.current
        val showNavigationLabels =
            shouldShowNavigationLabels(LocalDensity.current.fontScale)
        var showQuickAdd by rememberSaveable { mutableStateOf(false) }
        var showNewProject by rememberSaveable { mutableStateOf(false) }
        var showSearch by rememberSaveable { mutableStateOf(false) }
        var hasSeparatingFold by remember { mutableStateOf(false) }

        val selectedTaskId = selectedTaskValue?.let(::TaskId)
        val selectedProjectId = selectedProjectValue?.let(::ProjectId)
        val projectNames = snapshot.projects.associate { it.id to it.name }
        val backStack = rememberNavBackStack(HomeRoute)
        val currentRoute = backStack.lastOrNull() ?: HomeRoute

        fun navigate(route: WorkspaceRoute) {
            if (backStack.lastOrNull() == route) return
            backStack.clear()
            backStack.add(route)
            if (route != TasksRoute) viewModel.closeTask()
            if (route != ProjectsRoute) viewModel.closeProject()
        }

        LaunchedEffect(activity) {
            WindowInfoTracker.getOrCreate(activity)
                .windowLayoutInfo(activity)
                .collect { layout ->
                    hasSeparatingFold = layout.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .any(FoldingFeature::isSeparating)
                }
        }

        LaunchedEffect(quickAddSignal) {
            if (quickAddSignal > 0) showQuickAdd = true
        }

        LaunchedEffect(viewModel, accessibilityManager) {
            viewModel.events.collect { event ->
                when (event) {
                    is WorkspaceEvent.Message -> {
                        val presentation = snackbarPresentation(hasUndo = event.undo != null)
                        val showMessage = suspend {
                            snackbarHostState.showSnackbar(
                                message = event.text,
                                actionLabel = event.undo?.let { "Undo" },
                                withDismissAction = presentation.withDismissAction,
                                duration = presentation.duration,
                            )
                        }
                        val timeoutMillis = presentation.timeoutMillis?.let { baseTimeout ->
                            accessibilityManager?.calculateRecommendedTimeoutMillis(
                                originalTimeoutMillis = baseTimeout,
                                containsIcons = false,
                                containsText = true,
                                containsControls = event.undo != null,
                            ) ?: baseTimeout
                        }
                        val result =
                            if (timeoutMillis == null || timeoutMillis == Long.MAX_VALUE) {
                                showMessage()
                            } else {
                                withTimeoutOrNull(timeoutMillis) { showMessage() }
                            }
                        if (
                            result == androidx.compose.material3.SnackbarResult.ActionPerformed &&
                            event.undo != null
                        ) {
                            viewModel.execute(event.undo)
                        }
                    }
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val opensSearch =
                        (event.isCtrlPressed && event.key == Key.K) ||
                            (!event.isCtrlPressed && event.key == Key.Slash)
                    if (opensSearch) showSearch = true
                    opensSearch
                }
                .focusable(),
        ) {
            val layout = WorkspaceLayoutPolicy.calculate(
                widthDp = maxWidth.value.toInt().coerceAtLeast(1),
                hasSeparatingFold = hasSeparatingFold,
            )
            val compact = layout.windowClass == WorkspaceWindowClass.COMPACT
            val expanded =
                layout.windowClass == WorkspaceWindowClass.EXPANDED ||
                    layout.windowClass == WorkspaceWindowClass.EXTRA_WIDE
            val showDetailPane = layout.showDetailPane

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (compact) {
                        NavigationBar {
                            destinations.forEach { destination ->
                                NavigationBarItem(
                                    selected = currentRoute == destination.route,
                                    onClick = { navigate(destination.route) },
                                    icon = {
                                        Icon(
                                            destination.icon,
                                            contentDescription = destination.label
                                                .takeUnless { showNavigationLabels },
                                        )
                                    },
                                    label = if (showNavigationLabels) {
                                        { Text(destination.label) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                },
                floatingActionButton = {
                    if (selectedTaskId == null && selectedProjectId == null) {
                        val addsProject = currentRoute == ProjectsRoute
                        val actionLabel = if (addsProject) "New project" else "Quick add"
                        val actionContentDescription =
                            if (addsProject) "Create a new project" else "Quick add task"
                        val onAction = {
                            if (addsProject) {
                                showNewProject = true
                            } else {
                                showQuickAdd = true
                            }
                        }
                        if (layout.useExtendedQuickAdd) {
                            ExtendedFloatingActionButton(
                                onClick = onAction,
                                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                text = { Text(actionLabel) },
                            )
                        } else {
                            FloatingActionButton(onClick = onAction) {
                                Icon(
                                    Icons.Rounded.Add,
                                    contentDescription = actionContentDescription,
                                )
                            }
                        }
                    }
                },
            ) { contentPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .consumeWindowInsets(contentPadding),
                ) {
                    if (layout.showNavigationRail) {
                        NavigationRail {
                            destinations.forEach { destination ->
                                NavigationRailItem(
                                    selected = currentRoute == destination.route,
                                    onClick = { navigate(destination.route) },
                                    icon = {
                                        Icon(
                                            destination.icon,
                                            contentDescription = destination.label
                                                .takeUnless { showNavigationLabels },
                                        )
                                    },
                                    label = if (showNavigationLabels) {
                                        { Text(destination.label) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }

                    NavDisplay(
                        backStack = backStack,
                        modifier = Modifier.fillMaxSize(),
                        onBack = {
                            when {
                                selectedTaskId != null -> viewModel.closeTask()
                                selectedProjectId != null -> viewModel.closeProject()
                            }
                        },
                        entryProvider = entryProvider {
                            entry<HomeRoute> {
                                HomeScreen(
                                    snapshot = snapshot.home,
                                    projectNames = projectNames,
                                    onOpenSearch = { showSearch = true },
                                    onPlanToday = { navigate(TasksRoute) },
                                    onOpenTask = { taskId ->
                                        viewModel.selectTask(taskId)
                                        navigate(TasksRoute)
                                    },
                                    onCompleteTask = viewModel::completeTask,
                                    onOpenProject = { projectId ->
                                        viewModel.selectProject(projectId)
                                        navigate(ProjectsRoute)
                                    },
                                    onToggleTimer = viewModel::stopActiveTimer,
                                )
                            }
                            entry<TasksRoute> {
                                TasksScreen(
                                    tasks = snapshot.tasks,
                                    projectNames = projectNames,
                                    activeProjectIds = snapshot.projects
                                        .filter { it.archivedAt == null }
                                        .mapTo(hashSetOf()) { it.id },
                                    workflowStatuses = snapshot.workflowStatuses,
                                    tags = snapshot.tags,
                                    selectedTaskId = selectedTaskId,
                                    showDetailPane = showDetailPane,
                                    onSelectTask = viewModel::selectTask,
                                    onCloseDetail = viewModel::closeTask,
                                    onCompleteTask = viewModel::completeTask,
                                    onChangeTaskStatus = viewModel::changeTaskStatus,
                                    onDeleteTask = viewModel::deleteTask,
                                    activeTimerTaskId = snapshot.home.activeTimer?.taskId,
                                    onToggleTimer = viewModel::toggleTimer,
                                    onUpdateTask = { taskId, edit ->
                                        viewModel.execute(edit.toCommand(taskId))
                                    },
                                    onAddChecklistItem = { taskId, text ->
                                        viewModel.execute(
                                            DomainCommand.AddChecklistItem(taskId, text),
                                        )
                                    },
                                    onUpdateChecklistItem = { taskId, item ->
                                        viewModel.execute(
                                            DomainCommand.UpdateChecklistItem(
                                                taskId = taskId,
                                                itemId = item.id,
                                                text = item.text,
                                                completed = item.completed,
                                            ),
                                        )
                                    },
                                    onDeleteChecklistItem = { taskId, itemId ->
                                        viewModel.execute(
                                            DomainCommand.DeleteChecklistItem(taskId, itemId),
                                        )
                                    },
                                    onSetTaskTag = { taskId, tagId, present ->
                                        viewModel.execute(
                                            DomainCommand.SetTaskTag(taskId, tagId, present),
                                        )
                                    },
                                    onCreateAndAssignTag = { taskId, name ->
                                        viewModel.execute(
                                            DomainCommand.CreateAndAssignTag(taskId, name),
                                        )
                                    },
                                )
                            }
                            entry<ProjectsRoute> {
                                ProjectsScreen(
                                    projects = snapshot.projects,
                                    tasks = snapshot.tasks,
                                    milestones = snapshot.milestones,
                                    workflowStatuses = snapshot.workflowStatuses,
                                    selectedProjectId = selectedProjectId,
                                    showDetailPane = showDetailPane,
                                    onSelectProject = viewModel::selectProject,
                                    onCloseDetail = viewModel::closeProject,
                                    onUpdateProject = { projectId, edit ->
                                        viewModel.execute(edit.toCommand(projectId))
                                    },
                                    onArchiveProject = viewModel::archiveProject,
                                    onOpenTask = { taskId ->
                                        viewModel.selectTask(taskId)
                                        navigate(TasksRoute)
                                    },
                                )
                            }
                            entry<ScheduleRoute> {
                                ScheduleScreen(
                                    tasks = snapshot.tasks,
                                    projectNames = projectNames,
                                    expanded = expanded,
                                    onOpenTask = { taskId ->
                                        viewModel.selectTask(taskId)
                                        navigate(TasksRoute)
                                    },
                                )
                            }
                            entry<MoreRoute> {
                                MoreScreen(
                                    tasks = snapshot.tasks,
                                    projects = snapshot.projects,
                                    onRestoreProject = { projectId ->
                                        viewModel.execute(
                                            DomainCommand.RestoreArchivedProject(projectId),
                                        )
                                    },
                                    onRestoreTask = { taskId ->
                                        viewModel.execute(DomainCommand.RestoreTask(taskId))
                                    },
                                    onPermanentlyDeleteTask = { taskId ->
                                        viewModel.execute(
                                            DomainCommand.PermanentlyDeleteTask(taskId),
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }

            BackHandler(
                enabled = (selectedTaskId != null || selectedProjectId != null) && compact,
            ) {
                when {
                    selectedTaskId != null -> viewModel.closeTask()
                    selectedProjectId != null -> viewModel.closeProject()
                }
            }
        }

        if (showQuickAdd) {
            QuickAddSheet(
                onDismiss = { showQuickAdd = false },
                onAdd = { title ->
                    viewModel.addTask(title)
                    showQuickAdd = false
                },
            )
        }

        if (showNewProject) {
            NewProjectSheet(
                onDismiss = { showNewProject = false },
                onCreate = { name, summary ->
                    viewModel.addProject(name, summary)
                    showNewProject = false
                },
                existingProjectNames = snapshot.projects
                    .filter { it.archivedAt == null }
                    .mapTo(linkedSetOf()) { it.name },
            )
        }

        if (showSearch) {
            SearchSurface(
                results = searchResults,
                onQueryChange = viewModel::search,
                onDismiss = {
                    showSearch = false
                    viewModel.clearSearch()
                },
                onOpenTask = { id ->
                    showSearch = false
                    viewModel.clearSearch()
                    viewModel.selectTask(id)
                    navigate(TasksRoute)
                },
                onOpenProject = { projectId ->
                    showSearch = false
                    viewModel.clearSearch()
                    viewModel.selectProject(projectId)
                    navigate(ProjectsRoute)
                },
            )
        }

        if (pendingBlocked != null) {
            AlertDialog(
                onDismissRequest = viewModel::dismissBlockedCompletion,
                icon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
                title = { Text("Complete blocked task?") },
                text = {
                    Text(
                        "“${pendingBlocked?.task?.title}” still has unfinished dependencies. " +
                            "Completing it will preserve those links for review.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmBlockedCompletion) {
                        Text("Complete anyway")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissBlockedCompletion) {
                        Text("Keep open")
                    }
                },
            )
        }
    }
}

private fun TaskEdit.toCommand(taskId: TaskId): DomainCommand.UpdateTask =
    DomainCommand.UpdateTask(
        taskId = taskId,
        title = title,
        description = description,
        projectId = projectId,
        priority = priority,
        due = due,
        recurrence = recurrence,
        estimate = estimate,
    )

private fun ProjectEdit.toCommand(projectId: ProjectId): DomainCommand.UpdateProject =
    DomainCommand.UpdateProject(
        projectId = projectId,
        name = name,
        summary = summary,
        health = health,
        dueDate = dueDate,
    )
