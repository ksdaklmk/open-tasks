package app.opentasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.QuickAddTokenKind
import app.opentasks.core.domain.QuickAddTokenMatch
import app.opentasks.core.domain.QuickAddTokenValue
import app.opentasks.core.domain.SearchNormalizer
import app.opentasks.core.domain.parseQuickAdd
import app.opentasks.core.domain.stripQuickAddToken
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Tag
import app.opentasks.core.model.ZonedMoment
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class QuickAddDraft(
    val title: String,
    val projectId: String? = null,
    val priority: String = Priority.NONE.name,
    val dueEpochMillis: Long? = null,
    val dueZoneId: String? = null,
    val dueIsExplicit: Boolean = false,
    val tagNames: List<String> = emptyList(),
    val estimateSeconds: Long? = null,
    val recurrenceFrequency: String? = null,
    val recurrenceInterval: Int = 1,
    val recurrenceWeekdays: List<String> = emptyList(),
    val dismissedTokenKeys: Set<String> = emptySet(),
) {
    fun editTitle(value: String) = copy(title = value, dismissedTokenKeys = emptySet())

    fun dismiss(
        match: QuickAddTokenMatch,
        matches: List<QuickAddTokenMatch>,
    ): QuickAddDraft {
        require(match in matches)
        return copy(
            dismissedTokenKeys = dismissedTokenKeys + match.tokenKey(title, matches),
        )
    }

    fun confirm(
        match: QuickAddTokenMatch,
        matches: List<QuickAddTokenMatch>,
    ): QuickAddDraft {
        require(match in matches)
        if (!canConfirm(match)) return this
        val dismissedMatches = matches.filter {
            it.tokenKey(title, matches) in dismissedTokenKeys
        }
        val matchedDismissedKeys = dismissedMatches.mapTo(mutableSetOf()) {
            it.tokenKey(title, matches)
        }
        val remainingMatches = matches.filterNot { it == match }
        val rebasedDismissed = (dismissedTokenKeys - matchedDismissedKeys) +
            dismissedMatches
                .filterNot { it == match }
                .map { it.tokenKey(title, remainingMatches) }
        val base = copy(
            title = stripQuickAddToken(title, match),
            dismissedTokenKeys = rebasedDismissed.toSet(),
        )
        return when (val value = match.value) {
            is QuickAddTokenValue.ProjectValue -> base.copy(projectId = value.projectId.value)
            is QuickAddTokenValue.TagValue -> base.copy(
                tagNames = (tagNames + value.name)
                    .distinctBy { it.lowercase(Locale.ROOT) }
                    .take(MAX_QUICK_ADD_TAGS),
            )
            is QuickAddTokenValue.PriorityValue -> base.copy(priority = value.priority.name)
            is QuickAddTokenValue.DueValue -> base.copy(
                dueEpochMillis = value.due.instant.toEpochMilli(),
                dueZoneId = value.due.zoneId,
                dueIsExplicit = true,
            )
            is QuickAddTokenValue.RecurrenceValue -> {
                val preserveDue = dueIsExplicit &&
                    dueEpochMillis != null &&
                    dueZoneId?.let { runCatching { ZoneId.of(it) }.isSuccess } == true
                base.copy(
                    dueEpochMillis = if (preserveDue) {
                        dueEpochMillis
                    } else {
                        value.due.instant.toEpochMilli()
                    },
                    dueZoneId = if (preserveDue) dueZoneId else value.due.zoneId,
                    dueIsExplicit = preserveDue,
                    recurrenceFrequency = value.rule.frequency.name,
                    recurrenceInterval = value.rule.interval,
                    recurrenceWeekdays = value.rule.weekdays.map(DayOfWeek::name).sorted(),
                )
            }
            is QuickAddTokenValue.EstimateValue ->
                base.copy(estimateSeconds = value.duration.seconds)
        }
    }

    fun canConfirm(match: QuickAddTokenMatch): Boolean {
        val value = match.value as? QuickAddTokenValue.TagValue ?: return true
        val applied = tagNames.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
        return value.name.lowercase(Locale.ROOT) in applied || applied.size < MAX_QUICK_ADD_TAGS
    }

    fun clear(kind: QuickAddTokenKind, tagName: String? = null): QuickAddDraft = when (kind) {
        QuickAddTokenKind.PROJECT -> copy(projectId = null)
        QuickAddTokenKind.TAG -> copy(
            tagNames = tagNames.filterNot { it.equals(tagName, ignoreCase = true) },
        )
        QuickAddTokenKind.PRIORITY -> copy(priority = Priority.NONE.name)
        QuickAddTokenKind.DATE -> copy(
            dueEpochMillis = null,
            dueZoneId = null,
            dueIsExplicit = false,
            recurrenceFrequency = null,
            recurrenceInterval = 1,
            recurrenceWeekdays = emptyList(),
        )
        QuickAddTokenKind.RECURRENCE -> copy(
            recurrenceFrequency = null,
            recurrenceInterval = 1,
            recurrenceWeekdays = emptyList(),
            dueIsExplicit = dueEpochMillis != null &&
                dueZoneId?.let { runCatching { ZoneId.of(it) }.isSuccess } == true,
        )
        QuickAddTokenKind.ESTIMATE -> copy(estimateSeconds = null)
    }

    fun toCommand() = DomainCommand.CreateTask(
        title = title.trim(),
        projectId = projectId?.let(::ProjectId),
        priority = Priority.valueOf(priority),
        due = dueEpochMillis?.let { epoch ->
            dueZoneId?.let { zone -> ZonedMoment(Instant.ofEpochMilli(epoch), zone) }
        },
        tagNames = tagNames,
        estimate = estimateSeconds?.let(Duration::ofSeconds),
        recurrence = recurrenceFrequency?.let { frequency ->
            RecurrenceRule(
                frequency = RecurrenceFrequency.valueOf(frequency),
                interval = recurrenceInterval,
                weekdays = recurrenceWeekdays.mapTo(linkedSetOf(), DayOfWeek::valueOf),
            )
        },
    )
}

internal val QuickAddDraftSaver = mapSaver(
    save = { draft ->
        mapOf(
            "title" to draft.title,
            "project" to draft.projectId,
            "priority" to draft.priority,
            "dueEpoch" to draft.dueEpochMillis,
            "dueZone" to draft.dueZoneId,
            "dueExplicit" to draft.dueIsExplicit,
            "tags" to ArrayList(draft.tagNames),
            "estimate" to draft.estimateSeconds,
            "frequency" to draft.recurrenceFrequency,
            "interval" to draft.recurrenceInterval,
            "weekdays" to ArrayList(draft.recurrenceWeekdays),
            "dismissed" to ArrayList(draft.dismissedTokenKeys),
        )
    },
    restore = { saved ->
        val priority = (saved["priority"] as? String)
            ?.let { raw -> Priority.entries.firstOrNull { it.name == raw } }
            ?.name ?: Priority.NONE.name
        val frequency = (saved["frequency"] as? String)
            ?.takeIf { raw -> RecurrenceFrequency.entries.any { it.name == raw } }
        val dueEpoch = saved["dueEpoch"] as? Long
        val dueZone = saved["dueZone"] as? String
        val dueIsValid = dueEpoch != null &&
            dueZone?.let { runCatching { ZoneId.of(it) }.isSuccess } == true
        QuickAddDraft(
            title = saved["title"] as? String ?: "",
            projectId = saved["project"] as? String,
            priority = priority,
            dueEpochMillis = dueEpoch.takeIf { dueIsValid },
            dueZoneId = dueZone.takeIf { dueIsValid },
            dueIsExplicit = (saved["dueExplicit"] as? Boolean) == true && dueIsValid,
            tagNames = (saved["tags"] as? ArrayList<*>)
                ?.filterIsInstance<String>().orEmpty(),
            estimateSeconds = saved["estimate"] as? Long,
            recurrenceFrequency = frequency,
            recurrenceInterval = (saved["interval"] as? Int)
                ?.takeIf { it in 1..999 } ?: 1,
            recurrenceWeekdays = (saved["weekdays"] as? ArrayList<*>)
                ?.filterIsInstance<String>()
                ?.filter { raw -> DayOfWeek.entries.any { it.name == raw } }
                .orEmpty(),
            dismissedTokenKeys = (saved["dismissed"] as? ArrayList<*>)
                ?.filterIsInstance<String>().orEmpty().toSet(),
        )
    },
)

internal fun QuickAddTokenMatch.tokenKey(
    text: String,
    matches: List<QuickAddTokenMatch>,
): String {
    val claimed = SearchNormalizer.normalize(text.substring(startIndex, endIndex))
    val peers = matches.filter { peer ->
        peer.kind == kind &&
            SearchNormalizer.normalize(
                text.substring(peer.startIndex, peer.endIndex),
            ) == claimed
    }
    val ordinal = peers.indexOf(this)
    require(ordinal >= 0) { "Quick Add token is absent from the current parse" }
    return "${kind.name.lowercase(Locale.ROOT)}:$claimed:$ordinal"
}

internal fun suggestionTag(match: QuickAddTokenMatch) =
    "quick-add-suggestion-${match.kind.name.lowercase(Locale.ROOT)}-" +
        "${match.startIndex}-${match.endIndex}"

internal fun dismissTag(match: QuickAddTokenMatch) =
    "quick-add-dismiss-${match.kind.name.lowercase(Locale.ROOT)}-" +
        "${match.startIndex}-${match.endIndex}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    onDismiss: () -> Unit,
    onAdd: (DomainCommand.CreateTask) -> Unit,
    initialTitle: String = "",
    projects: List<Project> = emptyList(),
    tags: List<Tag> = emptyList(),
    clock: Clock = Clock.systemDefaultZone(),
) {
    var draft by rememberSaveable(stateSaver = QuickAddDraftSaver) {
        mutableStateOf(QuickAddDraft(initialTitle))
    }
    val parseNow = remember(clock) { clock.instant() }
    val matches = parseQuickAdd(draft.title, parseNow, clock.zone, projects, tags)
    val visibleMatches = matches.filterNot {
        it.tokenKey(draft.title, matches) in draft.dismissedTokenKeys
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun submit() {
        if (draft.title.trim().length in 1..MAX_QUICK_ADD_TITLE_LENGTH) {
            onAdd(draft.toCommand())
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .onPreviewKeyEvent { event ->
                    if (
                        event.type == KeyEventType.KeyDown &&
                        event.key == Key.Escape &&
                        !event.isAltPressed &&
                        !event.isCtrlPressed &&
                        !event.isMetaPressed &&
                        !event.isShiftPressed
                    ) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text("Quick add", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = draft.title,
                onValueChange = {
                    draft = draft.editTitle(it.take(MAX_QUICK_ADD_TITLE_LENGTH + 1))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag("quick-add-title"),
                label = { Text("Task title") },
                supportingText = {
                    Text(
                        if (draft.title.length > MAX_QUICK_ADD_TITLE_LENGTH) {
                            "Keep task titles under $MAX_QUICK_ADD_TITLE_LENGTH characters"
                        } else {
                            "${draft.title.length}/$MAX_QUICK_ADD_TITLE_LENGTH"
                        },
                    )
                },
                isError = draft.title.length > MAX_QUICK_ADD_TITLE_LENGTH,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )

            visibleMatches.forEach { match ->
                val confirmable = draft.canConfirm(match)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = { draft = draft.confirm(match, matches) },
                        label = { Text(suggestionLabel(match, draft, confirmable)) },
                        enabled = confirmable,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .height(48.dp)
                            .testTag(suggestionTag(match)),
                    )
                    IconButton(
                        onClick = { draft = draft.dismiss(match, matches) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag(dismissTag(match)),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(
                                R.string.quick_add_dismiss_suggestion,
                                draft.title.substring(match.startIndex, match.endIndex),
                            ),
                        )
                    }
                }
            }

            draft.projectId?.let { projectId ->
                val name = projects.firstOrNull { it.id.value == projectId }?.name ?: projectId
                AppliedQuickAddChip(
                    label = stringResource(R.string.quick_add_project_suggestion, name),
                    chipTag = "quick-add-applied-project",
                    clearTag = "quick-add-clear-project",
                    onClear = { draft = draft.clear(QuickAddTokenKind.PROJECT) },
                )
            }
            if (draft.priority != Priority.NONE.name) {
                AppliedQuickAddChip(
                    label = stringResource(
                        R.string.quick_add_priority_suggestion,
                        Priority.valueOf(draft.priority).displayName(),
                    ),
                    chipTag = "quick-add-applied-priority",
                    clearTag = "quick-add-clear-priority",
                    onClear = { draft = draft.clear(QuickAddTokenKind.PRIORITY) },
                )
            }
            draft.dueMoment()?.let { due ->
                AppliedQuickAddChip(
                    label = stringResource(
                        R.string.quick_add_date_suggestion,
                        DATE_SUGGESTION_FORMATTER.format(due.instant.atZone(due.zone())),
                    ),
                    chipTag = "quick-add-date-chip",
                    clearTag = "quick-add-date-clear",
                    onClear = { draft = draft.clear(QuickAddTokenKind.DATE) },
                )
            }
            draft.recurrenceFrequency?.let { frequency ->
                AppliedQuickAddChip(
                    label = stringResource(
                        R.string.quick_add_recurrence_suggestion,
                        frequency.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercase),
                    ),
                    chipTag = "quick-add-applied-recurrence",
                    clearTag = "quick-add-clear-recurrence",
                    onClear = { draft = draft.clear(QuickAddTokenKind.RECURRENCE) },
                )
            }
            draft.estimateSeconds?.let { seconds ->
                AppliedQuickAddChip(
                    label = stringResource(
                        R.string.quick_add_estimate_suggestion,
                        Duration.ofSeconds(seconds).displayName(),
                    ),
                    chipTag = "quick-add-applied-estimate",
                    clearTag = "quick-add-clear-estimate",
                    onClear = { draft = draft.clear(QuickAddTokenKind.ESTIMATE) },
                )
            }
            draft.tagNames.forEach { name ->
                val suffix = SearchNormalizer.normalize(name).replace(' ', '-')
                AppliedQuickAddChip(
                    label = stringResource(R.string.quick_add_existing_tag_suggestion, name),
                    chipTag = "quick-add-applied-tag-$suffix",
                    clearTag = "quick-add-clear-tag-$suffix",
                    onClear = { draft = draft.clear(QuickAddTokenKind.TAG, name) },
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = ::submit,
                    enabled = draft.title.trim().length in 1..MAX_QUICK_ADD_TITLE_LENGTH,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text("Add task")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
}

@Composable
private fun suggestionLabel(
    match: QuickAddTokenMatch,
    draft: QuickAddDraft,
    confirmable: Boolean,
): String =
    when (val value = match.value) {
        is QuickAddTokenValue.ProjectValue -> stringResource(
            R.string.quick_add_project_suggestion,
            value.projectName,
        )
        is QuickAddTokenValue.TagValue -> {
            val suggestion = stringResource(
                if (value.existingTagId == null) {
                    R.string.quick_add_new_tag_suggestion
                } else {
                    R.string.quick_add_existing_tag_suggestion
                },
                value.name,
            )
            if (confirmable) {
                suggestion
            } else {
                stringResource(R.string.quick_add_tag_limit_suggestion, suggestion)
            }
        }
        is QuickAddTokenValue.PriorityValue -> stringResource(
            R.string.quick_add_priority_suggestion,
            value.priority.displayName(),
        )
        is QuickAddTokenValue.DueValue -> stringResource(
            R.string.quick_add_date_suggestion,
            DATE_SUGGESTION_FORMATTER.format(value.due.instant.atZone(value.due.zone())),
        )
        is QuickAddTokenValue.RecurrenceValue -> {
            val effectiveDue = draft.dueMoment().takeIf { draft.dueIsExplicit } ?: value.due
            stringResource(
                R.string.quick_add_recurrence_due_suggestion,
                draft.title.substring(match.startIndex, match.endIndex),
                DATE_SUGGESTION_FORMATTER.format(
                    effectiveDue.instant.atZone(effectiveDue.zone()),
                ),
            )
        }
        is QuickAddTokenValue.EstimateValue -> stringResource(
            R.string.quick_add_estimate_suggestion,
            value.duration.displayName(),
        )
    }

@Composable
private fun AppliedQuickAddChip(
    label: String,
    chipTag: String,
    clearTag: String,
    onClear: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onClear,
            label = { Text(label) },
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = 48.dp)
                .testTag(chipTag),
        )
        IconButton(
            onClick = onClear,
            modifier = Modifier
                .size(48.dp)
                .testTag(clearTag),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.quick_add_clear_applied, label),
            )
        }
    }
}

private fun QuickAddDraft.dueMoment(): ZonedMoment? = dueEpochMillis?.let { epoch ->
    dueZoneId?.let { zone -> ZonedMoment(Instant.ofEpochMilli(epoch), zone) }
}

private fun Priority.displayName() = when (this) {
    Priority.URGENT -> "Urgent"
    Priority.HIGH -> "High"
    Priority.MEDIUM -> "Medium"
    Priority.LOW -> "Low"
    Priority.NONE -> "None"
}

private fun Duration.displayName(): String {
    val minutes = toMinutes()
    val hours = minutes / 60
    val remaining = minutes % 60
    return when {
        hours == 0L -> "${minutes}m"
        remaining == 0L -> "${hours}h"
        else -> "${hours}h ${remaining}m"
    }
}

private const val MAX_QUICK_ADD_TITLE_LENGTH = 240
private const val MAX_QUICK_ADD_TAGS = 50
private val DATE_SUGGESTION_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.UK)
