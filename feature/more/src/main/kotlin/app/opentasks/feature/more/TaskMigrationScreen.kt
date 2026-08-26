package app.opentasks.feature.more

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import app.opentasks.core.model.TaskCsvBlockingIssue
import app.opentasks.core.model.TaskCsvDateOrder
import app.opentasks.core.model.TaskCsvEstimateUnit
import app.opentasks.core.model.TaskCsvField
import app.opentasks.core.model.TaskCsvPriorityChoice
import app.opentasks.core.model.TaskCsvStatusChoice
import app.opentasks.core.model.TaskCsvTagMode
import app.opentasks.core.model.TaskCsvWarning
import app.opentasks.core.model.TaskCsvWarningReason

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskMigrationScreen(
    state: TaskMigrationUiState,
    onMapField: (TaskCsvField, Int?) -> Unit,
    onStatusChoice: (String, TaskCsvStatusChoice) -> Unit,
    onPriorityChoice: (String, TaskCsvPriorityChoice) -> Unit,
    onDateOrder: (TaskCsvDateOrder) -> Unit,
    onEstimateUnit: (TaskCsvEstimateUnit) -> Unit,
    onTagMode: (TaskCsvTagMode) -> Unit,
    onImport: () -> Unit,
    onChooseAnother: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isCommitting = (state as? TaskMigrationUiState.Review)?.isCommitting == true
    BackHandler { if (!isCommitting) onCancel() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .testTag("task-migration-screen"),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(16.dp, 24.dp, 16.dp, 64.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                is TaskMigrationUiState.LoadFailure -> loadFailureContent(
                    state = state,
                    onChooseAnother = onChooseAnother,
                    onCancel = onCancel,
                )

                is TaskMigrationUiState.Review -> reviewContent(
                    state = state,
                    onMapField = onMapField,
                    onStatusChoice = onStatusChoice,
                    onPriorityChoice = onPriorityChoice,
                    onDateOrder = onDateOrder,
                    onEstimateUnit = onEstimateUnit,
                    onTagMode = onTagMode,
                    onImport = onImport,
                    onChooseAnother = onChooseAnother,
                    onCancel = onCancel,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.loadFailureContent(
    state: TaskMigrationUiState.LoadFailure,
    onChooseAnother: () -> Unit,
    onCancel: () -> Unit,
) {
    item {
        Text(
            stringResource(R.string.migration_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
    }
    state.fileName?.let { fileName ->
        item { Text(fileName, style = MaterialTheme.typography.bodyLarge) }
    }
    item {
        val message = loadFailureMessage(state)
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { error(message) },
        )
    }
    item {
        Button(
            onClick = onChooseAnother,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("migration-choose-another"),
        ) { Text(stringResource(R.string.migration_choose_another)) }
    }
    item {
        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("migration-cancel"),
        ) { Text(stringResource(R.string.migration_cancel)) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.reviewContent(
    state: TaskMigrationUiState.Review,
    onMapField: (TaskCsvField, Int?) -> Unit,
    onStatusChoice: (String, TaskCsvStatusChoice) -> Unit,
    onPriorityChoice: (String, TaskCsvPriorityChoice) -> Unit,
    onDateOrder: (TaskCsvDateOrder) -> Unit,
    onEstimateUnit: (TaskCsvEstimateUnit) -> Unit,
    onTagMode: (TaskCsvTagMode) -> Unit,
    onImport: () -> Unit,
    onChooseAnother: () -> Unit,
    onCancel: () -> Unit,
) {
    item {
        Text(
            stringResource(R.string.migration_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    R.string.migration_source_summary,
                    state.fileName,
                    state.sourceRowCount,
                    state.sourceColumnCount,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.migration_create_only),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.migration_zone_disclosure, state.capturedZoneId),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item { MigrationHeading(R.string.migration_map_columns) }
    items(TaskCsvField.entries, key = { it.name }) { field ->
        MappingSelector(
            field = field,
            selectedIndex = state.mapping.columns[field],
            columns = state.columns,
            enabled = !state.isCommitting,
            onMapField = onMapField,
        )
    }
    item {
        Column(
            modifier = Modifier.testTag("migration-ignored-columns"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MigrationHeading(R.string.migration_ignored_columns)
            Text(
                state.ignoredHeaders.joinToString(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (state.statusValues.isNotEmpty()) {
        item { MigrationHeading(R.string.migration_status_heading) }
        items(state.statusValues, key = { it }) { value ->
            StatusChoices(
                value = value,
                selected = state.mapping.statusChoices[value],
                enabled = !state.isCommitting,
                index = state.statusValues.indexOf(value),
                onStatusChoice = onStatusChoice,
            )
        }
    }
    if (state.priorityValues.isNotEmpty()) {
        item { MigrationHeading(R.string.migration_priority_heading) }
        items(state.priorityValues, key = { it }) { value ->
            PriorityChoices(
                value = value,
                selected = state.mapping.priorityChoices[value],
                enabled = !state.isCommitting,
                index = state.priorityValues.indexOf(value),
                onPriorityChoice = onPriorityChoice,
            )
        }
    }
    if (state.ambiguousDatesPresent) {
        item {
            ChoiceGroup(
                heading = R.string.migration_date_order_heading,
                tag = "migration-date-order",
                choices = TaskCsvDateOrder.entries.map { it to dateOrderLabel(it) },
                selected = state.mapping.dateOrder,
                enabled = !state.isCommitting,
                optionTag = { "migration-date-order-${if (it == TaskCsvDateOrder.DAY_MONTH_YEAR) "dmy" else "mdy"}" },
                onSelect = onDateOrder,
            )
        }
    }
    if (state.estimateValuesPresent) {
        item {
            ChoiceGroup(
                heading = R.string.migration_estimate_unit_heading,
                tag = "migration-estimate-unit",
                choices = TaskCsvEstimateUnit.entries.map { it to estimateUnitLabel(it) },
                selected = state.mapping.estimateUnit,
                enabled = !state.isCommitting,
                optionTag = { "migration-estimate-${if (it == TaskCsvEstimateUnit.MINUTES) "minutes" else "hours"}" },
                onSelect = onEstimateUnit,
            )
        }
    }
    if (state.tagValuesPresent) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceGroup(
                    heading = R.string.migration_tag_mode_heading,
                    tag = "migration-tag-mode",
                    choices = TaskCsvTagMode.entries.map { it to tagModeLabel(it) },
                    selected = state.mapping.tagMode,
                    enabled = !state.isCommitting,
                    optionTag = { "migration-tag-${it.name.lowercase()}" },
                    onSelect = onTagMode,
                )
                if (state.tagSamples.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.migration_resulting_tags,
                            state.tagSamples.joinToString(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MigrationHeading(R.string.migration_preview_heading)
            PreviewCounts(state)
        }
    }
    if (state.blockingIssues.isNotEmpty()) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MigrationHeading(R.string.migration_blocking_messages)
                TaskCsvBlockingIssue.entries
                    .filter(state.blockingIssues::contains)
                    .forEach { issue ->
                        val message = blockingMessage(issue, state.blockingMessage)
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { error(message) },
                        )
                    }
            }
        }
    }
    if (state.warnings.isNotEmpty()) {
        item { MigrationHeading(R.string.migration_warnings_heading) }
        itemsIndexed(state.warnings, key = { index, _ -> index }) { index, warning ->
            val text = warningMessage(warning, state.capturedZoneId)
            Text(
                text,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .testTag("migration-warning-$index")
                    .semantics { error(text) },
            )
        }
    }
    item {
        Button(
            onClick = onImport,
            enabled = state.canImport,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("migration-import"),
        ) {
            Text(
                when {
                    state.isCommitting -> stringResource(R.string.migration_importing)
                    state.warnings.isNotEmpty() -> stringResource(
                        R.string.migration_import_anyway,
                        state.summary.readyTaskCount,
                    )
                    else -> stringResource(R.string.migration_import, state.summary.readyTaskCount)
                },
            )
        }
    }
    item {
        OutlinedButton(
            onClick = onChooseAnother,
            enabled = !state.isCommitting,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("migration-choose-another"),
        ) { Text(stringResource(R.string.migration_choose_another)) }
    }
    item {
        TextButton(
            onClick = onCancel,
            enabled = !state.isCommitting,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("migration-cancel"),
        ) { Text(stringResource(R.string.migration_cancel)) }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MappingSelector(
    field: TaskCsvField,
    selectedIndex: Int?,
    columns: List<TaskMigrationColumnUi>,
    enabled: Boolean,
    onMapField: (TaskCsvField, Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = columns.firstOrNull { it.index == selectedIndex }
    val fieldTag = "migration-field-${field.name.lowercase()}"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = it },
        ) {
            OutlinedTextField(
                value = selected?.let { stringResource(R.string.migration_column, it.index + 1, it.header) }
                    ?: stringResource(R.string.migration_not_mapped),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(fieldLabel(field)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(fieldTag),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.migration_not_mapped)) },
                    onClick = {
                        expanded = false
                        onMapField(field, null)
                    },
                    enabled = enabled,
                    modifier = Modifier.testTag("$fieldTag-option-none"),
                )
                columns.forEach { column ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.migration_column, column.index + 1, column.header),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            onMapField(field, column.index)
                        },
                        enabled = enabled,
                        modifier = Modifier.testTag("$fieldTag-option-${column.index}"),
                    )
                }
            }
        }
        selected?.samples?.take(3)?.takeIf(List<String>::isNotEmpty)?.let { samples ->
            Text(
                stringResource(R.string.migration_samples, samples.joinToString()),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusChoices(
    value: String,
    selected: TaskCsvStatusChoice?,
    enabled: Boolean,
    index: Int,
    onStatusChoice: (String, TaskCsvStatusChoice) -> Unit,
) = ChoiceGroup(
    heading = null,
    tag = "migration-status-$index",
    choices = TaskCsvStatusChoice.entries.map { it to statusLabel(it) },
    selected = selected,
    enabled = enabled,
    optionTag = { "migration-status-$index-${it.name.lowercase()}" },
    onSelect = { onStatusChoice(value, it) },
    value = value,
)

@Composable
private fun PriorityChoices(
    value: String,
    selected: TaskCsvPriorityChoice?,
    enabled: Boolean,
    index: Int,
    onPriorityChoice: (String, TaskCsvPriorityChoice) -> Unit,
) = ChoiceGroup(
    heading = null,
    tag = "migration-priority-$index",
    choices = TaskCsvPriorityChoice.entries.map { it to priorityLabel(it) },
    selected = selected,
    enabled = enabled,
    optionTag = { "migration-priority-$index-${it.name.lowercase()}" },
    onSelect = { onPriorityChoice(value, it) },
    value = value,
)

@Composable
private fun <T> ChoiceGroup(
    heading: Int?,
    tag: String,
    choices: List<Pair<T, String>>,
    selected: T?,
    enabled: Boolean,
    optionTag: (T) -> String,
    onSelect: (T) -> Unit,
    value: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics { selectableGroup() },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        heading?.let { MigrationHeading(it) }
        value?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        choices.forEach { (choice, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(optionTag(choice))
                    .selectable(
                        selected = choice == selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(choice) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = choice == selected, onClick = null, enabled = enabled)
                Text(label, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun PreviewCounts(state: TaskMigrationUiState.Review) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            pluralStringResource(
                R.plurals.migration_ready_task_count,
                state.summary.readyTaskCount,
                state.summary.readyTaskCount,
            ),
        )
        Text(
            pluralStringResource(
                R.plurals.migration_skipped_task_count,
                state.summary.skippedTaskCount,
                state.summary.skippedTaskCount,
            ),
        )
        Text(
            pluralStringResource(
                R.plurals.migration_omitted_value_count,
                state.summary.omittedValueCount,
                state.summary.omittedValueCount,
            ),
        )
        Text(
            pluralStringResource(
                R.plurals.migration_new_project_count,
                state.summary.newProjectCount,
                state.summary.newProjectCount,
            ),
        )
        Text(
            pluralStringResource(
                R.plurals.migration_new_tag_count,
                state.summary.newTagCount,
                state.summary.newTagCount,
            ),
        )
    }
}

@Composable
private fun MigrationHeading(resource: Int) {
    Text(
        stringResource(resource),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun fieldLabel(field: TaskCsvField): String = stringResource(
    when (field) {
        TaskCsvField.TITLE -> R.string.migration_field_title
        TaskCsvField.PROJECT -> R.string.migration_field_project
        TaskCsvField.STATUS -> R.string.migration_field_status
        TaskCsvField.PRIORITY -> R.string.migration_field_priority
        TaskCsvField.START -> R.string.migration_field_start
        TaskCsvField.DUE -> R.string.migration_field_due
        TaskCsvField.COMPLETION -> R.string.migration_field_completion
        TaskCsvField.ESTIMATE -> R.string.migration_field_estimate
        TaskCsvField.TAGS -> R.string.migration_field_tags
        TaskCsvField.DESCRIPTION -> R.string.migration_field_description
    },
)

@Composable
private fun statusLabel(choice: TaskCsvStatusChoice): String = stringResource(
    when (choice) {
        TaskCsvStatusChoice.BACKLOG -> R.string.migration_status_backlog
        TaskCsvStatusChoice.IN_PROGRESS -> R.string.migration_status_in_progress
        TaskCsvStatusChoice.DONE -> R.string.migration_status_done
        TaskCsvStatusChoice.IGNORE -> R.string.migration_status_ignore
    },
)

@Composable
private fun priorityLabel(choice: TaskCsvPriorityChoice): String = stringResource(
    when (choice) {
        TaskCsvPriorityChoice.NONE -> R.string.migration_priority_none
        TaskCsvPriorityChoice.LOW -> R.string.migration_priority_low
        TaskCsvPriorityChoice.MEDIUM -> R.string.migration_priority_medium
        TaskCsvPriorityChoice.HIGH -> R.string.migration_priority_high
        TaskCsvPriorityChoice.URGENT -> R.string.migration_priority_urgent
        TaskCsvPriorityChoice.IGNORE -> R.string.migration_priority_ignore
    },
)

@Composable
private fun dateOrderLabel(choice: TaskCsvDateOrder): String = stringResource(
    if (choice == TaskCsvDateOrder.DAY_MONTH_YEAR) {
        R.string.migration_date_order_dmy
    } else {
        R.string.migration_date_order_mdy
    },
)

@Composable
private fun estimateUnitLabel(choice: TaskCsvEstimateUnit): String = stringResource(
    if (choice == TaskCsvEstimateUnit.MINUTES) {
        R.string.migration_estimate_minutes
    } else {
        R.string.migration_estimate_hours
    },
)

@Composable
private fun tagModeLabel(choice: TaskCsvTagMode): String = stringResource(
    when (choice) {
        TaskCsvTagMode.COMMA -> R.string.migration_tag_comma
        TaskCsvTagMode.SEMICOLON -> R.string.migration_tag_semicolon
        TaskCsvTagMode.PIPE -> R.string.migration_tag_pipe
        TaskCsvTagMode.SINGLE -> R.string.migration_tag_single
    },
)

@Composable
private fun loadFailureMessage(state: TaskMigrationUiState.LoadFailure): String = when (state.reason) {
    TaskMigrationLoadFailure.UNREADABLE -> stringResource(R.string.migration_failure_unreadable)
    TaskMigrationLoadFailure.TOO_LARGE -> stringResource(R.string.migration_failure_too_large)
    TaskMigrationLoadFailure.INVALID_UTF8 -> stringResource(R.string.migration_failure_invalid_utf8)
    TaskMigrationLoadFailure.MALFORMED -> state.rowNumber?.let {
        stringResource(R.string.migration_failure_malformed_row, it)
    } ?: stringResource(R.string.migration_failure_malformed)
    TaskMigrationLoadFailure.TOO_MANY_ROWS -> stringResource(R.string.migration_failure_too_many_rows)
    TaskMigrationLoadFailure.TOO_MANY_COLUMNS -> stringResource(R.string.migration_failure_too_many_columns)
    TaskMigrationLoadFailure.MISSING_HEADER -> stringResource(R.string.migration_failure_missing_header)
    TaskMigrationLoadFailure.ROW_WIDER_THAN_HEADER -> stringResource(
        R.string.migration_failure_row_wider,
        checkNotNull(state.rowNumber),
    )
}

@Composable
private fun blockingMessage(issue: TaskCsvBlockingIssue, targetMessage: String?): String = when (issue) {
    TaskCsvBlockingIssue.TITLE_MAPPING_REQUIRED -> stringResource(R.string.migration_blocking_title_mapping)
    TaskCsvBlockingIssue.COLUMN_MAPPING_INVALID -> stringResource(R.string.migration_blocking_column_mapping)
    TaskCsvBlockingIssue.STATUS_CHOICES_REQUIRED -> stringResource(R.string.migration_blocking_status_choices)
    TaskCsvBlockingIssue.PRIORITY_CHOICES_REQUIRED -> stringResource(R.string.migration_blocking_priority_choices)
    TaskCsvBlockingIssue.DATE_ORDER_REQUIRED -> stringResource(R.string.migration_blocking_date_order)
    TaskCsvBlockingIssue.ESTIMATE_UNIT_REQUIRED -> stringResource(R.string.migration_blocking_estimate_unit)
    TaskCsvBlockingIssue.TAG_MODE_REQUIRED -> stringResource(R.string.migration_blocking_tag_mode)
    TaskCsvBlockingIssue.NO_VALID_TASKS -> stringResource(R.string.migration_blocking_no_valid_tasks)
    TaskCsvBlockingIssue.TOO_MANY_NEW_PROJECTS -> stringResource(R.string.migration_blocking_too_many_projects)
    TaskCsvBlockingIssue.TOO_MANY_NEW_TAGS -> stringResource(R.string.migration_blocking_too_many_tags)
    TaskCsvBlockingIssue.TARGET_REJECTED -> targetMessage
        ?: stringResource(R.string.migration_blocking_target_rejected)
}

@Composable
private fun warningMessage(warning: TaskCsvWarning, zoneId: String): String = stringResource(
    R.string.migration_warning_row,
    warning.rowNumber,
    warning.field?.let { fieldLabel(it) } ?: stringResource(R.string.migration_field_file),
    warningReason(warning.reason, zoneId),
)

@Composable
private fun warningReason(reason: TaskCsvWarningReason, zoneId: String): String = stringResource(
    when (reason) {
        TaskCsvWarningReason.EMPTY_ROW -> R.string.migration_warning_empty_row
        TaskCsvWarningReason.TITLE_BLANK -> R.string.migration_warning_title_blank
        TaskCsvWarningReason.TITLE_TOO_LONG -> R.string.migration_warning_title_too_long
        TaskCsvWarningReason.PROJECT_OMITTED -> R.string.migration_warning_project_omitted
        TaskCsvWarningReason.PROJECT_CASE_MERGED -> R.string.migration_warning_project_case_merged
        TaskCsvWarningReason.STATUS_OMITTED -> R.string.migration_warning_status_omitted
        TaskCsvWarningReason.STATUS_FALLBACK -> R.string.migration_warning_status_fallback
        TaskCsvWarningReason.PRIORITY_OMITTED -> R.string.migration_warning_priority_omitted
        TaskCsvWarningReason.START_OMITTED,
        TaskCsvWarningReason.DUE_OMITTED,
        TaskCsvWarningReason.COMPLETION_OMITTED,
        -> R.string.migration_warning_value_omitted
        TaskCsvWarningReason.START_TIME_INFERRED -> R.string.migration_warning_start_time_inferred
        TaskCsvWarningReason.DUE_TIME_INFERRED -> R.string.migration_warning_due_time_inferred
        TaskCsvWarningReason.START_ZONE_INFERRED,
        TaskCsvWarningReason.DUE_ZONE_INFERRED,
        TaskCsvWarningReason.COMPLETION_ZONE_INFERRED,
        -> R.string.migration_warning_zone_inferred
        TaskCsvWarningReason.COMPLETION_TIME_INFERRED,
        TaskCsvWarningReason.COMPLETION_INFERRED,
        -> R.string.migration_warning_completion_omitted
        TaskCsvWarningReason.COMPLETION_OVERRIDES_STATUS ->
            R.string.migration_warning_completion_overrides_status
        TaskCsvWarningReason.ESTIMATE_OMITTED -> R.string.migration_warning_estimate_omitted
        TaskCsvWarningReason.TAG_BLANK_OMITTED -> R.string.migration_warning_tag_blank
        TaskCsvWarningReason.TAG_TOO_LONG_OMITTED -> R.string.migration_warning_tag_too_long
        TaskCsvWarningReason.TAG_DUPLICATE_OMITTED -> R.string.migration_warning_tag_duplicate
        TaskCsvWarningReason.TAG_LIMIT_OMITTED -> R.string.migration_warning_tag_limit
        TaskCsvWarningReason.TAG_CASE_MERGED -> R.string.migration_warning_tag_case_merged
        TaskCsvWarningReason.DESCRIPTION_OMITTED -> R.string.migration_warning_description_omitted
    },
    zoneId,
)
