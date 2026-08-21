package app.opentasks.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.SectionHeader
import app.opentasks.core.model.AutomationRule
import app.opentasks.core.model.AutomationRuleId
import app.opentasks.core.model.AutomationRuleType
import app.opentasks.core.model.PRIMARY_WORKSPACE_ID
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkflowStatusId
import java.util.Locale

/**
 * The automations editor: rules are rendered sorted by `id.value` ascending,
 * matching `evaluateAutomationRules`' deterministic application order, so the
 * displayed order is the order rules apply. Client-side per-type gating on
 * the add sheet's confirm button is pre-validation only -- the repository
 * remains the sole enforcer of rule config and the 20-rule bound, so a
 * rejection (bound reached, invalid config, missing refs) always surfaces
 * through the ordinary command snackbar.
 */
@Composable
fun AutomationsSection(
    rules: List<AutomationRule>,
    projects: List<Project>,
    workflowStatuses: List<WorkflowStatus>,
    tags: List<Tag>,
    onCreateRule: (AutomationRule) -> Unit,
    onUpdateRule: (AutomationRule) -> Unit,
    onDeleteRule: (AutomationRuleId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val sortedRules = rules.sortedBy { it.id.value }

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.automations_heading),
            supportingText = pluralStringResource(
                R.plurals.automations_count,
                MAX_AUTOMATION_RULES,
                rules.size,
                MAX_AUTOMATION_RULES,
            ),
            action = {
                TextButton(
                    onClick = { showAddSheet = true },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("automation-add"),
                ) {
                    Text(stringResource(R.string.automation_add))
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        sortedRules.forEach { rule ->
            AutomationRuleRow(
                rule = rule,
                projects = projects,
                workflowStatuses = workflowStatuses,
                tags = tags,
                onToggle = { onUpdateRule(rule.copy(enabled = !rule.enabled)) },
                onDelete = { pendingDeleteId = rule.id.value },
            )
            HorizontalDivider()
        }
    }

    if (showAddSheet) {
        AddAutomationRuleSheet(
            projects = projects,
            workflowStatuses = workflowStatuses,
            tags = tags,
            onDismiss = { showAddSheet = false },
            onCreate = { rule ->
                onCreateRule(rule)
                showAddSheet = false
            },
        )
    }

    pendingDeleteId?.let { ruleId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.automation_delete_title)) },
            text = { Text(stringResource(R.string.automation_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteId = null
                        onDeleteRule(AutomationRuleId(ruleId))
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("automation-delete-confirm"),
                ) {
                    Text(
                        stringResource(R.string.automation_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.automation_cancel))
                }
            },
        )
    }
}

@Composable
private fun AutomationRuleRow(
    rule: AutomationRule,
    projects: List<Project>,
    workflowStatuses: List<WorkflowStatus>,
    tags: List<Tag>,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val broken = rule.isBroken(projects, workflowStatuses, tags)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(automationTypeLabel(rule.type), style = MaterialTheme.typography.titleMedium)
            if (broken) {
                Text(
                    stringResource(R.string.automation_broken),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                val summary = automationConfigSummary(rule, projects, workflowStatuses, tags)
                if (summary.isNotEmpty()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Switch(
            checked = rule.enabled,
            onCheckedChange = { onToggle() },
            modifier = Modifier.testTag("automation-enabled-${rule.id.value}"),
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .size(48.dp)
                .testTag("automation-delete-${rule.id.value}"),
        ) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.automation_delete_action),
            )
        }
    }
}

/** A rule is broken only when a set (non-null) reference fails to resolve. */
private fun AutomationRule.isBroken(
    projects: List<Project>,
    workflowStatuses: List<WorkflowStatus>,
    tags: List<Tag>,
): Boolean {
    val status = statusId
    if (status != null && workflowStatuses.none { it.id == status }) return true
    val tag = tagId
    if (tag != null && tags.none { it.id == tag }) return true
    val project = projectId
    if (project != null && projects.none { it.id == project }) return true
    return false
}

@Composable
private fun automationTypeLabel(type: AutomationRuleType): String = when (type) {
    AutomationRuleType.ON_ENTER_ADD_TAG -> stringResource(R.string.automation_type_add_tag)
    AutomationRuleType.ON_ENTER_ADD_TO_MY_DAY -> stringResource(R.string.automation_type_add_my_day)
    AutomationRuleType.ON_ENTER_SET_DUE -> stringResource(R.string.automation_type_set_due)
    AutomationRuleType.MY_DAY_AUTO_REMOVE -> stringResource(R.string.automation_type_sweep)
    AutomationRuleType.STALE_BADGE -> stringResource(R.string.automation_type_stale)
}

@Composable
private fun statusLabel(
    id: WorkflowStatusId,
    workflowStatuses: List<WorkflowStatus>,
    projects: List<Project>,
    inboxLabel: String,
): String? {
    val status = workflowStatuses.firstOrNull { it.id == id } ?: return null
    val projectName = status.projectId
        ?.let { pid -> projects.firstOrNull { it.id == pid }?.name }
        ?: inboxLabel
    return "$projectName · ${status.name}"
}

@Composable
private fun automationConfigSummary(
    rule: AutomationRule,
    projects: List<Project>,
    workflowStatuses: List<WorkflowStatus>,
    tags: List<Tag>,
): String {
    val inboxLabel = stringResource(R.string.automation_inbox_label)
    return when (rule.type) {
        AutomationRuleType.ON_ENTER_ADD_TAG -> {
            val statusPart = rule.statusId
                ?.let { statusLabel(it, workflowStatuses, projects, inboxLabel) }
            val tagPart = rule.tagId
                ?.let { id -> tags.firstOrNull { it.id == id }?.name }
                ?.let { "#$it" }
            listOfNotNull(statusPart, tagPart).joinToString(" · ")
        }
        AutomationRuleType.ON_ENTER_ADD_TO_MY_DAY ->
            rule.statusId
                ?.let { statusLabel(it, workflowStatuses, projects, inboxLabel) }
                .orEmpty()
        AutomationRuleType.ON_ENTER_SET_DUE -> {
            val statusPart = rule.statusId
                ?.let { statusLabel(it, workflowStatuses, projects, inboxLabel) }
            val duePart = rule.dueInDays
                ?.let { pluralStringResource(R.plurals.automation_due_in_days, it, it) }
            listOfNotNull(statusPart, duePart).joinToString(" · ")
        }
        AutomationRuleType.MY_DAY_AUTO_REMOVE -> ""
        AutomationRuleType.STALE_BADGE -> {
            val thresholdPart = rule.thresholdDays
                ?.let { pluralStringResource(R.plurals.automation_after_days, it, it) }
            val scopePart = rule.projectId
                ?.let { id -> projects.firstOrNull { it.id == id }?.name }
            listOfNotNull(thresholdPart, scopePart).joinToString(" · ")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddAutomationRuleSheet(
    projects: List<Project>,
    workflowStatuses: List<WorkflowStatus>,
    tags: List<Tag>,
    onDismiss: () -> Unit,
    onCreate: (AutomationRule) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedType by rememberSaveable { mutableStateOf(AutomationRuleType.entries.first()) }
    // Keyed on the selected type so switching FilterChips always leaves the
    // other types' config behind -- the created rule's non-relevant fields
    // stay null without per-field gating in the emitted AutomationRule.
    var selectedStatusId by rememberSaveable(selectedType) { mutableStateOf<String?>(null) }
    var selectedTagId by rememberSaveable(selectedType) { mutableStateOf<String?>(null) }
    var selectedProjectId by rememberSaveable(selectedType) { mutableStateOf<String?>(null) }
    var dueDaysText by rememberSaveable(selectedType) { mutableStateOf("") }
    var staleDaysText by rememberSaveable(selectedType) { mutableStateOf("") }

    val activeStatuses = workflowStatuses.filter { it.archivedAt == null }
    val activeProjects = projects.filter { it.archivedAt == null }
    val dueDays = dueDaysText.trim().toIntOrNull()
    val dueDaysInvalid = dueDaysText.isNotBlank() && (dueDays == null || dueDays !in 0..365)
    val staleDays = staleDaysText.trim().toIntOrNull()
    val staleDaysInvalid = staleDaysText.isNotBlank() && (staleDays == null || staleDays !in 1..365)

    val confirmEnabled = when (selectedType) {
        AutomationRuleType.ON_ENTER_ADD_TAG -> selectedStatusId != null && selectedTagId != null
        AutomationRuleType.ON_ENTER_ADD_TO_MY_DAY -> selectedStatusId != null
        AutomationRuleType.ON_ENTER_SET_DUE ->
            selectedStatusId != null && dueDays != null && dueDays in 0..365
        AutomationRuleType.MY_DAY_AUTO_REMOVE -> true
        AutomationRuleType.STALE_BADGE -> staleDays != null && staleDays in 1..365
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("automation-add-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(
                stringResource(R.string.automation_add_heading),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(20.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AutomationRuleType.entries.forEach { type ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        label = { Text(automationTypeLabel(type)) },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("automation-type-${type.name.lowercase(Locale.ROOT)}"),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            when (selectedType) {
                AutomationRuleType.ON_ENTER_ADD_TAG -> {
                    StatusPicker(
                        statuses = activeStatuses,
                        projects = activeProjects,
                        selectedId = selectedStatusId,
                        onSelect = { selectedStatusId = it },
                    )
                    Spacer(Modifier.height(16.dp))
                    TagPicker(
                        tags = tags,
                        selectedId = selectedTagId,
                        onSelect = { selectedTagId = it },
                    )
                }
                AutomationRuleType.ON_ENTER_ADD_TO_MY_DAY -> {
                    StatusPicker(
                        statuses = activeStatuses,
                        projects = activeProjects,
                        selectedId = selectedStatusId,
                        onSelect = { selectedStatusId = it },
                    )
                }
                AutomationRuleType.ON_ENTER_SET_DUE -> {
                    StatusPicker(
                        statuses = activeStatuses,
                        projects = activeProjects,
                        selectedId = selectedStatusId,
                        onSelect = { selectedStatusId = it },
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = dueDaysText,
                        onValueChange = { dueDaysText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("automation-due-days"),
                        label = { Text(stringResource(R.string.automation_due_days_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = dueDaysInvalid,
                        singleLine = true,
                    )
                }
                AutomationRuleType.MY_DAY_AUTO_REMOVE -> Unit
                AutomationRuleType.STALE_BADGE -> {
                    OutlinedTextField(
                        value = staleDaysText,
                        onValueChange = { staleDaysText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("automation-threshold-days"),
                        label = { Text(stringResource(R.string.automation_threshold_days_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = staleDaysInvalid,
                        singleLine = true,
                    )
                    Spacer(Modifier.height(16.dp))
                    ProjectScopePicker(
                        projects = activeProjects,
                        selectedId = selectedProjectId,
                        onSelect = { selectedProjectId = it },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.automation_cancel))
                }
                TextButton(
                    onClick = {
                        onCreate(
                            AutomationRule(
                                id = AutomationRuleId.new(),
                                workspaceId = PRIMARY_WORKSPACE_ID,
                                type = selectedType,
                                enabled = true,
                                projectId = selectedProjectId?.let(::ProjectId),
                                statusId = selectedStatusId?.let(::WorkflowStatusId),
                                tagId = selectedTagId?.let(::TagId),
                                dueInDays = dueDays,
                                thresholdDays = staleDays,
                            ),
                        )
                    },
                    enabled = confirmEnabled,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("automation-create-confirm"),
                ) {
                    Text(stringResource(R.string.automation_create_confirm))
                }
            }
        }
    }
}

@Composable
private fun StatusPicker(
    statuses: List<WorkflowStatus>,
    projects: List<Project>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val inboxLabel = stringResource(R.string.automation_inbox_label)
    val selected = statuses.firstOrNull { it.id.value == selectedId }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("automation-status-picker"),
        ) {
            Text(
                selected?.let { status ->
                    val projectName = status.projectId
                        ?.let { pid -> projects.firstOrNull { it.id == pid }?.name }
                        ?: inboxLabel
                    "$projectName · ${status.name}"
                } ?: stringResource(R.string.automation_status_placeholder),
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            statuses.forEach { status ->
                val projectName = status.projectId
                    ?.let { pid -> projects.firstOrNull { it.id == pid }?.name }
                    ?: inboxLabel
                DropdownMenuItem(
                    text = { Text("$projectName · ${status.name}") },
                    onClick = {
                        expanded = false
                        onSelect(status.id.value)
                    },
                    modifier = Modifier.testTag("automation-status-option-${status.id.value}"),
                )
            }
        }
    }
}

@Composable
private fun TagPicker(
    tags: List<Tag>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = tags.firstOrNull { it.id.value == selectedId }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("automation-tag-picker"),
        ) {
            Text(
                selected?.name ?: stringResource(R.string.automation_tag_placeholder),
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            tags.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(tag.name) },
                    onClick = {
                        expanded = false
                        onSelect(tag.id.value)
                    },
                    modifier = Modifier.testTag("automation-tag-option-${tag.id.value}"),
                )
            }
        }
    }
}

@Composable
private fun ProjectScopePicker(
    projects: List<Project>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = projects.firstOrNull { it.id.value == selectedId }?.name
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("automation-scope-picker"),
        ) {
            Text(
                selectedName ?: stringResource(R.string.automation_scope_all),
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_scope_all)) },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
                modifier = Modifier.testTag("automation-scope-option-all"),
            )
            projects.forEach { project ->
                DropdownMenuItem(
                    text = { Text(project.name) },
                    onClick = {
                        expanded = false
                        onSelect(project.id.value)
                    },
                    modifier = Modifier.testTag("automation-scope-option-${project.id.value}"),
                )
            }
        }
    }
}

private const val MAX_AUTOMATION_RULES = 20
