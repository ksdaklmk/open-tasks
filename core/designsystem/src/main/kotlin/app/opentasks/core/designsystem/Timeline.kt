package app.opentasks.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One entry of a record's history, already reduced to what the eye reads.
 *
 * The list primitive stays model-free on purpose: a task timeline and a
 * project timeline present the same shape from different records, and neither
 * kind of entry is styled by colour alone — [iconKind] always travels with a
 * readable label.
 */
data class TimelineItem(
    val key: String,
    val timestampLabel: String,
    val body: String,
    val editable: Boolean,
    val iconKind: TimelineIconKind,
)

enum class TimelineIconKind { NOTE, EVENT }

/**
 * A record's notes and generated activity, presented as one history.
 *
 * Both product surfaces that own notes present exactly this, so the behaviour
 * lives here rather than beside either record type: the cap, the ordering
 * rule, the timestamp format, and the copy are single-sourced, and the
 * features supply only the mapping from their own records.
 *
 * The in-progress note is state, and this composable knows nothing about
 * which record it belongs to. **Callers must scope it to one record** — wrap
 * the call in `key(recordId) { … }` — or a draft written against one record
 * would still be in the field, and would save against the first record, after
 * a different one is selected.
 */
@Composable
fun NotesTimelineSection(
    items: List<TimelineItem>,
    noteCount: Int,
    activityCount: Int,
    onAddNote: (String) -> Unit,
    onUpdateNote: (key: String, body: String) -> Unit,
    onDeleteNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    val tooLong = draft.trim().length > MAX_NOTE_BODY_LENGTH

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.notes_heading),
            supportingText = stringResource(
                R.string.notes_supporting,
                pluralStringResource(R.plurals.notes_note_count, noteCount, noteCount),
                pluralStringResource(R.plurals.notes_activity_count, activityCount, activityCount),
            ),
        )
        Spacer(Modifier.height(12.dp))
        NoteField(
            value = draft,
            editing = editingKey != null,
            tooLong = tooLong,
            submittable = draft.isNotBlank() && !tooLong,
            onValueChange = { draft = it.take(MAX_NOTE_BODY_LENGTH + 1) },
            onSubmit = {
                val body = draft.trim()
                if (body.isNotEmpty() && body.length <= MAX_NOTE_BODY_LENGTH) {
                    val edited = editingKey
                    if (edited == null) onAddNote(body) else onUpdateNote(edited, body)
                    draft = ""
                    editingKey = null
                }
            },
            onCancel = {
                draft = ""
                editingKey = null
            },
        )
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.notes_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TimelineList(
                items = items,
                onEdit = { key ->
                    items.firstOrNull { it.key == key && it.editable }?.let { item ->
                        editingKey = key
                        draft = item.body
                    }
                },
                onDelete = { key ->
                    if (editingKey == key) {
                        editingKey = null
                        draft = ""
                    }
                    onDeleteNote(key)
                },
            )
        }
    }
}

/** The one field that both adds and edits, so no two note editors compete. */
@Composable
private fun NoteField(
    value: String,
    editing: Boolean,
    tooLong: Boolean,
    submittable: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("note-field"),
        label = {
            Text(
                stringResource(
                    if (editing) R.string.notes_edit_label else R.string.notes_add_label,
                ),
            )
        },
        placeholder = { Text(stringResource(R.string.notes_placeholder)) },
        supportingText = {
            Text(
                if (tooLong) {
                    stringResource(R.string.notes_length_error, MAX_NOTE_BODY_LENGTH)
                } else {
                    stringResource(
                        R.string.notes_length_counter,
                        value.trim().length,
                        MAX_NOTE_BODY_LENGTH,
                    )
                },
            )
        },
        isError = tooLong,
        minLines = 2,
        maxLines = 6,
        trailingIcon = {
            IconButton(
                onClick = onSubmit,
                enabled = submittable,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("add-note"),
            ) {
                Icon(
                    if (editing) Icons.Rounded.Check else Icons.Rounded.Add,
                    contentDescription = stringResource(
                        if (editing) R.string.notes_save_action else R.string.notes_add_action,
                    ),
                )
            }
        },
    )
    if (editing) {
        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("cancel-note-edit"),
        ) {
            Text(stringResource(R.string.notes_cancel_action))
        }
    }
}

/**
 * The label one timeline entry shows for when it happened.
 *
 * An edited note is stamped with the edit, and says so, because "when this was
 * written" and "what it now says" stopped being the same fact.
 */
@Composable
fun timelineTimestampLabel(createdAt: Instant, editedAt: Instant? = null): String {
    val stamp = TIMELINE_FORMAT.format((editedAt ?: createdAt).atZone(ZoneId.systemDefault()))
    return if (editedAt == null) stamp else stringResource(R.string.notes_edited_label, stamp)
}

/**
 * Newest first, ties broken by key so two entries sharing an instant keep a
 * stable order instead of shuffling between recompositions.
 */
fun sortedTimelineItems(entries: List<Pair<Instant, TimelineItem>>): List<TimelineItem> = entries
    .sortedWith(
        compareByDescending<Pair<Instant, TimelineItem>> { it.first }.thenBy { it.second.key },
    )
    .map { it.second }

/** Mirrors the repository's own cap, so the field refuses what a write would. */
const val MAX_NOTE_BODY_LENGTH = 10_000

/**
 * Renders [items] in the order given, newest first by convention of the caller.
 *
 * This is a plain column rather than a lazy list so it can be embedded in the
 * scrolling detail panes that own it; the callers cap what they pass.
 */
@Composable
fun TimelineList(
    items: List<TimelineItem>,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            TimelineRow(item = item, onEdit = onEdit, onDelete = onDelete)
        }
    }
}

@Composable
private fun TimelineRow(
    item: TimelineItem,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val kindLabel = stringResource(
        when (item.iconKind) {
            TimelineIconKind.NOTE -> R.string.timeline_kind_note
            TimelineIconKind.EVENT -> R.string.timeline_kind_event
        },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = when (item.iconKind) {
                TimelineIconKind.NOTE -> Icons.Rounded.Description
                TimelineIconKind.EVENT -> Icons.Rounded.History
            },
            contentDescription = null,
            modifier = Modifier
                .padding(top = 12.dp)
                .size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
                .testTag("timeline-item")
                .semantics(mergeDescendants = true) { },
        ) {
            Text(
                stringResource(
                    R.string.timeline_entry_summary,
                    kindLabel,
                    item.timestampLabel,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(item.body, style = MaterialTheme.typography.bodyMedium)
        }
        if (item.editable) {
            IconButton(
                onClick = { onEdit(item.key) },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("timeline-edit-${item.key}"),
            ) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.timeline_edit_note),
                )
            }
            IconButton(
                onClick = { onDelete(item.key) },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("timeline-delete-${item.key}"),
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.timeline_delete_note),
                )
            }
        }
    }
}

private val TIMELINE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)
