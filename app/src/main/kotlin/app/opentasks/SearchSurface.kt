package app.opentasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SavedView
import app.opentasks.core.model.SavedViewId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TagId
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TaskSortKey
import app.opentasks.feature.tasks.R as TasksR
import kotlinx.coroutines.delay
import java.util.Locale

private val SearchQuerySaver = listSaver<SearchQuery, Any>(
    save = { query ->
        listOf(
            query.text,
            ArrayList(query.projectIds.map(ProjectId::value).sorted()),
            ArrayList(query.tagIds.map(TagId::value).sorted()),
            query.includeCompleted,
            query.includeTrash,
            ArrayList(query.dueBuckets.map { it.name }.sorted()),
            ArrayList(query.priorities.map { it.name }.sorted()),
            ArrayList(query.statuses.map { it.name }.sorted()),
            query.sort?.name.orEmpty(),
        )
    },
    restore = { values ->
        fun names(index: Int) = (values[index] as? List<*>)
            .orEmpty().mapNotNull { it as? String }
        SearchQuery(
            text = values[0] as String,
            projectIds = names(1).mapTo(linkedSetOf(), ::ProjectId),
            tagIds = names(2).mapTo(linkedSetOf(), ::TagId),
            includeCompleted = values[3] as Boolean,
            includeTrash = values[4] as Boolean,
            dueBuckets = names(5).mapNotNull { name ->
                DueBucket.entries.firstOrNull { it.name == name }
            }.toSet(),
            priorities = names(6).mapNotNull { name ->
                Priority.entries.firstOrNull { it.name == name }
            }.toSet(),
            statuses = names(7).mapNotNull { name ->
                SemanticStatus.entries.firstOrNull { it.name == name }
            }.toSet(),
            sort = (values[8] as? String)?.let { name ->
                TaskSortKey.entries.firstOrNull { it.name == name }
            },
        )
    },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchSurface(
    results: List<SearchResult>,
    onQueryChange: (SearchQuery) -> Unit,
    onDismiss: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    onOpenProject: (ProjectId) -> Unit,
    savedViews: List<SavedView> = emptyList(),
    onSaveView: ((String, SearchQuery) -> Unit)? = null,
    onRenameView: (SavedViewId, String) -> Unit = { _, _ -> },
    onDeleteView: (SavedViewId) -> Unit = {},
) {
    var query by rememberSaveable(stateSaver = SearchQuerySaver) {
        mutableStateOf(SearchQuery(""))
    }
    var selectedSavedViewId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var dueMenuExpanded by remember { mutableStateOf(false) }
    var priorityMenuExpanded by remember { mutableStateOf(false) }
    var statusMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val hasV2Criterion = query.dueBuckets.isNotEmpty() || query.priorities.isNotEmpty() ||
        query.statuses.isNotEmpty() || query.sort != null
    val activeView = savedViews.firstOrNull { it.id.value == selectedSavedViewId }
    val savingDisabled = savedViews.size >= MAX_SAVED_VIEWS

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 720.dp),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                Column {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = query.text,
                            onValueChange = { value ->
                                query = query.copy(
                                    text = value.take(MAX_SEARCH_QUERY_LENGTH),
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .testTag("workspace-search-query"),
                            placeholder = { Text("Search tasks, projects, notes, and tags") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Search, contentDescription = null)
                            },
                            supportingText = if (onSaveView != null && savingDisabled) {
                                { Text(stringResource(R.string.saved_search_limit)) }
                            } else {
                                null
                            },
                            singleLine = true,
                        )
                        if (onSaveView != null && (query.text.isNotBlank() || hasV2Criterion)) {
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { showSaveDialog = true },
                                enabled = !savingDisabled,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("save-search"),
                            ) {
                                Icon(
                                    Icons.Rounded.BookmarkAdd,
                                    contentDescription =
                                        stringResource(R.string.saved_search_save_description),
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close search")
                        }
                    }
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box {
                            IconButton(
                                onClick = { dueMenuExpanded = true },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag(DUE_FILTER_TAG),
                            ) {
                                Icon(
                                    Icons.Rounded.Event,
                                    contentDescription =
                                        stringResource(R.string.saved_search_filter_due),
                                )
                            }
                            DropdownMenu(
                                expanded = dueMenuExpanded,
                                onDismissRequest = { dueMenuExpanded = false },
                            ) {
                                DueBucket.entries.forEach { bucket ->
                                    val selectedOption = bucket in query.dueBuckets
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    when (bucket) {
                                                        DueBucket.OVERDUE ->
                                                            TasksR.string.tasks_group_due_overdue
                                                        DueBucket.TODAY ->
                                                            TasksR.string.tasks_group_due_today
                                                        DueBucket.THIS_WEEK ->
                                                            TasksR.string.tasks_group_due_this_week
                                                        DueBucket.LATER ->
                                                            TasksR.string.tasks_group_due_later
                                                        DueBucket.NO_DATE ->
                                                            TasksR.string.tasks_group_due_no_date
                                                    },
                                                ),
                                            )
                                        },
                                        onClick = {
                                            query = query.copy(
                                                dueBuckets = query.dueBuckets.toggled(bucket),
                                            )
                                            dueMenuExpanded = false
                                        },
                                        modifier = Modifier
                                            .testTag(
                                                "search-due-${bucket.name.lowercase(Locale.ROOT)}",
                                            )
                                            .semantics {
                                                role = Role.Checkbox
                                                selected = selectedOption
                                            },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { priorityMenuExpanded = true },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag(PRIORITY_FILTER_TAG),
                            ) {
                                Icon(
                                    Icons.Rounded.Flag,
                                    contentDescription =
                                        stringResource(R.string.saved_search_filter_priority),
                                )
                            }
                            DropdownMenu(
                                expanded = priorityMenuExpanded,
                                onDismissRequest = { priorityMenuExpanded = false },
                            ) {
                                Priority.entries.forEach { priority ->
                                    val selectedOption = priority in query.priorities
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    when (priority) {
                                                        Priority.NONE ->
                                                            TasksR.string.tasks_group_priority_none
                                                        Priority.LOW ->
                                                            TasksR.string.tasks_group_priority_low
                                                        Priority.MEDIUM ->
                                                            TasksR.string.tasks_group_priority_medium
                                                        Priority.HIGH ->
                                                            TasksR.string.tasks_group_priority_high
                                                        Priority.URGENT ->
                                                            TasksR.string.tasks_group_priority_urgent
                                                    },
                                                ),
                                            )
                                        },
                                        onClick = {
                                            query = query.copy(
                                                priorities = query.priorities.toggled(priority),
                                            )
                                            priorityMenuExpanded = false
                                        },
                                        modifier = Modifier
                                            .testTag(
                                                "search-priority-" +
                                                    priority.name.lowercase(Locale.ROOT),
                                            )
                                            .semantics {
                                                role = Role.Checkbox
                                                selected = selectedOption
                                            },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { statusMenuExpanded = true },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag(STATUS_FILTER_TAG),
                            ) {
                                Icon(
                                    Icons.Rounded.Checklist,
                                    contentDescription =
                                        stringResource(R.string.saved_search_filter_status),
                                )
                            }
                            DropdownMenu(
                                expanded = statusMenuExpanded,
                                onDismissRequest = { statusMenuExpanded = false },
                            ) {
                                SemanticStatus.entries.forEach { status ->
                                    val selectedOption = status in query.statuses
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    when (status) {
                                                        SemanticStatus.BACKLOG ->
                                                            R.string.saved_search_status_backlog
                                                        SemanticStatus.PLANNED ->
                                                            R.string.saved_search_status_planned
                                                        SemanticStatus.STARTED ->
                                                            R.string.saved_search_status_started
                                                        SemanticStatus.BLOCKED ->
                                                            R.string.saved_search_status_blocked
                                                        SemanticStatus.COMPLETED ->
                                                            R.string.saved_search_status_completed
                                                    },
                                                ),
                                            )
                                        },
                                        onClick = {
                                            query = query.copy(
                                                statuses = query.statuses.toggled(status),
                                            )
                                            statusMenuExpanded = false
                                        },
                                        modifier = Modifier
                                            .testTag(
                                                "search-status-${status.name.lowercase(Locale.ROOT)}",
                                            )
                                            .semantics {
                                                role = Role.Checkbox
                                                selected = selectedOption
                                            },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { sortMenuExpanded = true },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag(SORT_FILTER_TAG),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Sort,
                                    contentDescription =
                                        stringResource(R.string.saved_search_sort),
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false },
                            ) {
                                (listOf<TaskSortKey?>(null) + TaskSortKey.entries).forEach { sort ->
                                    val selectedOption = query.sort == sort
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    when (sort) {
                                                        null -> R.string.saved_search_relevance
                                                        TaskSortKey.DUE ->
                                                            TasksR.string.tasks_sort_due_label
                                                        TaskSortKey.PRIORITY ->
                                                            TasksR.string.tasks_sort_priority_label
                                                        TaskSortKey.TITLE ->
                                                            TasksR.string.tasks_sort_title_label
                                                        TaskSortKey.UPDATED ->
                                                            TasksR.string.tasks_sort_updated_label
                                                    },
                                                ),
                                            )
                                        },
                                        onClick = {
                                            query = query.copy(sort = sort)
                                            sortMenuExpanded = false
                                        },
                                        modifier = Modifier
                                            .testTag(
                                                sort?.let {
                                                    "search-sort-${it.name.lowercase(Locale.ROOT)}"
                                                } ?: "search-sort-relevance",
                                            )
                                            .semantics {
                                                role = Role.RadioButton
                                                selected = selectedOption
                                            },
                                    )
                                }
                            }
                        }
                    }
                    if (activeView != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .testTag("$ACTIVE_VIEW_TAG-${activeView.id.value}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.saved_search_active_view, activeView.name),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    selectedSavedViewId = null
                                    query = SearchQuery(query.text)
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag(CLEAR_ACTIVE_VIEW_TAG),
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(
                                        R.string.saved_search_clear_active_view,
                                    ),
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    if (query.text.isBlank() && savedViews.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            savedViews.forEach { savedView ->
                                SavedViewChip(
                                    savedView = savedView,
                                    onSelect = {
                                        query = savedView.query
                                        selectedSavedViewId = savedView.id.value
                                    },
                                    onRename = { name -> onRenameView(savedView.id, name) },
                                    onDelete = { onDeleteView(savedView.id) },
                                )
                            }
                        }
                    }
                    when {
                        query.text.isBlank() && !hasV2Criterion -> {
                            SearchHint()
                        }
                        results.isEmpty() -> {
                            Column(Modifier.padding(28.dp)) {
                                Text(
                                    "No matching work",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    "Try fewer words or search a project name.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        else -> {
                            LazyColumn {
                                items(
                                    results,
                                    key = {
                                        when (it) {
                                            is SearchResult.TaskResult -> "task-${it.task.id.value}"
                                            is SearchResult.ProjectResult -> "project-${it.project.id.value}"
                                        }
                                    },
                                ) { result ->
                                    SearchResultRow(
                                        result = result,
                                        onClick = {
                                            when (result) {
                                                is SearchResult.TaskResult -> onOpenTask(result.task.id)
                                                is SearchResult.ProjectResult -> onOpenProject(result.project.id)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(query) {
        delay(150)
        onQueryChange(query)
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    if (showSaveDialog) {
        SavedViewNameDialog(
            titleRes = R.string.saved_search_save_title,
            confirmRes = R.string.saved_search_save_confirm,
            nameFieldTag = "save-search-name",
            confirmButtonTag = "save-search-confirm",
            initialName = "",
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                onSaveView?.invoke(name, query)
                showSaveDialog = false
            },
        )
    }
}

private const val ACTIVE_VIEW_TAG = "active-saved-view"
private const val CLEAR_ACTIVE_VIEW_TAG = "clear-active-saved-view"
private const val DUE_FILTER_TAG = "search-filter-due"
private const val PRIORITY_FILTER_TAG = "search-filter-priority"
private const val STATUS_FILTER_TAG = "search-filter-status"
private const val SORT_FILTER_TAG = "search-sort"
private const val MAX_SEARCH_QUERY_LENGTH = 500
private const val MAX_SAVED_VIEWS = 20

private fun <T> Set<T>.toggled(value: T): Set<T> =
    if (value in this) this - value else this + value

@Composable
private fun SearchHint() {
    Column(Modifier.padding(28.dp)) {
        Text("Search the whole workspace", style = MaterialTheme.typography.titleLarge)
        Text(
            "Shortcut: Ctrl+K or /",
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedViewChip(
    savedView: SavedView,
    onSelect: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    // Keyed on identity, not just remembered positionally: an un-keyed
    // remember here would let an open-menu (or open-rename-dialog) flag
    // attach to the wrong chip once `savedViews` reorders under this
    // un-keyed `forEach` -- e.g. after a delete shifts every later chip
    // one slot earlier. Same idiom as `WorkflowStatusEditorRow`'s
    // `rememberSaveable(status.id.value)` in ProjectsScreen.kt.
    var menuExpanded by remember(savedView.id.value) { mutableStateOf(false) }
    var showRenameDialog by remember(savedView.id.value) { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = onSelect,
            label = { Text(savedView.name) },
            modifier = Modifier.testTag("saved-view-chip-${savedView.id.value}"),
        )
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("saved-view-menu-${savedView.id.value}"),
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(
                        R.string.saved_search_chip_menu_description,
                        savedView.name,
                    ),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.saved_search_rename_action)) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = stringResource(
                                R.string.saved_search_rename_description,
                                savedView.name,
                            ),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        showRenameDialog = true
                    },
                    modifier = Modifier.testTag("saved-view-rename-${savedView.id.value}"),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.saved_search_delete_action)) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = stringResource(
                                R.string.saved_search_delete_description,
                                savedView.name,
                            ),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("saved-view-delete-${savedView.id.value}"),
                )
            }
        }
    }

    if (showRenameDialog) {
        SavedViewNameDialog(
            titleRes = R.string.saved_search_rename_title,
            confirmRes = R.string.saved_search_rename_confirm,
            nameFieldTag = "rename-saved-view-name-${savedView.id.value}",
            confirmButtonTag = "rename-saved-view-confirm-${savedView.id.value}",
            initialName = savedView.name,
            onDismiss = { showRenameDialog = false },
            onConfirm = { name ->
                onRename(name)
                showRenameDialog = false
            },
        )
    }
}

@Composable
private fun SavedViewNameDialog(
    titleRes: Int,
    confirmRes: Int,
    nameFieldTag: String,
    confirmButtonTag: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val trimmedName = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_SAVED_VIEW_NAME_LENGTH) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(nameFieldTag),
                label = { Text(stringResource(R.string.saved_search_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmedName) },
                enabled = trimmedName.isNotEmpty(),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(confirmButtonTag),
            ) {
                Text(stringResource(confirmRes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.saved_search_cancel_action))
            }
        },
    )
}

private const val MAX_SAVED_VIEW_NAME_LENGTH = 64

@Composable
private fun SearchResultRow(
    result: SearchResult,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (result) {
                is SearchResult.TaskResult -> Icons.Rounded.TaskAlt
                is SearchResult.ProjectResult -> Icons.Rounded.FolderOpen
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            Text(result.title, style = MaterialTheme.typography.titleMedium)
            Text(
                result.context,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
