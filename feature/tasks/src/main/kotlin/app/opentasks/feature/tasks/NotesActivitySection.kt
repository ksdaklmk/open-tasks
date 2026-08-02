package app.opentasks.feature.tasks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.opentasks.core.designsystem.NotesTimelineSection
import app.opentasks.core.designsystem.TimelineIconKind
import app.opentasks.core.designsystem.TimelineItem
import app.opentasks.core.designsystem.sortedTimelineItems
import app.opentasks.core.designsystem.timelineTimestampLabel
import app.opentasks.core.model.ActivityEntry
import app.opentasks.core.model.Note
import app.opentasks.core.model.NoteId

/**
 * A task's notes and its generated activity, newest first.
 *
 * Everything this surface *does* — the cap, the ordering, the timestamp
 * format, the one field that both adds and edits — belongs to the shared
 * section, so the project surface cannot drift from it. What is left here is
 * the part that is genuinely about tasks: turning task-owned records into
 * timeline entries, and identities back into note commands.
 *
 * The in-progress note is scoped to one task by the caller; see the `key`
 * around the call in `TaskDetailPane`.
 */
@Composable
fun NotesActivitySection(
    notes: List<Note>,
    activity: List<ActivityEntry>,
    onAddNote: (String) -> Unit,
    onUpdateNote: (NoteId, String) -> Unit,
    onDeleteNote: (NoteId) -> Unit,
    modifier: Modifier = Modifier,
) {
    NotesTimelineSection(
        items = noteTimelineItems(notes, activity),
        noteCount = notes.size,
        activityCount = activity.size,
        onAddNote = onAddNote,
        onUpdateNote = { key, body -> onUpdateNote(NoteId(key), body) },
        onDeleteNote = { key -> onDeleteNote(NoteId(key)) },
        modifier = modifier,
    )
}

@Composable
private fun noteTimelineItems(
    notes: List<Note>,
    activity: List<ActivityEntry>,
): List<TimelineItem> = sortedTimelineItems(
    notes.map { note ->
        (note.editedAt ?: note.createdAt) to TimelineItem(
            key = note.id.value,
            timestampLabel = timelineTimestampLabel(note.createdAt, note.editedAt),
            body = note.body,
            editable = true,
            iconKind = TimelineIconKind.NOTE,
        )
    } + activity.map { entry ->
        entry.createdAt to TimelineItem(
            key = entry.id,
            timestampLabel = timelineTimestampLabel(entry.createdAt),
            body = entry.body,
            editable = false,
            iconKind = TimelineIconKind.EVENT,
        )
    },
)
