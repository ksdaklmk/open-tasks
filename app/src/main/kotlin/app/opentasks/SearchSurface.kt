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
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SavedView
import app.opentasks.core.model.SavedViewId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.TaskId
import kotlinx.coroutines.delay

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
    var queryText by rememberSaveable { mutableStateOf("") }
    var selectedSavedViewId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Only trusted while the field still reads exactly what the saved view
    // stored -- any further typing (or a rename that changes the underlying
    // text) drops back to a plain, filter-less query for the typed text.
    val currentQuery = savedViews
        .firstOrNull { it.id.value == selectedSavedViewId && it.query.text == queryText }
        ?.query
        ?: SearchQuery(queryText)
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
                            value = queryText,
                            onValueChange = {
                                queryText = it.take(MAX_SEARCH_QUERY_LENGTH)
                                selectedSavedViewId = null
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
                        if (onSaveView != null && queryText.isNotBlank()) {
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
                    HorizontalDivider()
                    if (queryText.isBlank() && savedViews.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            savedViews.forEach { savedView ->
                                SavedViewChip(
                                    savedView = savedView,
                                    onSelect = {
                                        queryText = savedView.query.text
                                        selectedSavedViewId = savedView.id.value
                                    },
                                    onRename = { name -> onRenameView(savedView.id, name) },
                                    onDelete = { onDeleteView(savedView.id) },
                                )
                            }
                        }
                    }
                    when {
                        queryText.isBlank() -> {
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

    LaunchedEffect(currentQuery) {
        delay(150)
        onQueryChange(currentQuery)
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
                onSaveView?.invoke(name, currentQuery)
                showSaveDialog = false
            },
        )
    }
}

private const val MAX_SEARCH_QUERY_LENGTH = 500
private const val MAX_SAVED_VIEWS = 20

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
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

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
