package app.opentasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.TaskId
import kotlinx.coroutines.delay

@Composable
fun SearchSurface(
    results: List<SearchResult>,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    onOpenProject: (ProjectId) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

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
                            value = query,
                            onValueChange = {
                                query = it.take(MAX_SEARCH_QUERY_LENGTH)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .testTag("workspace-search-query"),
                            placeholder = { Text("Search tasks, projects, notes, and tags") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Search, contentDescription = null)
                            },
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close search")
                        }
                    }
                    HorizontalDivider()
                    when {
                        query.isBlank() -> {
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
}

private const val MAX_SEARCH_QUERY_LENGTH = 500

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
