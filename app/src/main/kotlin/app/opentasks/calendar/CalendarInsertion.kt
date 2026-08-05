package app.opentasks.calendar

import android.content.Intent
import android.provider.CalendarContract
import app.opentasks.core.model.Task

/**
 * Everything the calendar provider's insert screen needs, already resolved
 * to plain values -- no [Task], no zone, no Android type. [beginEpochMillis]
 * and [endEpochMillis] are both instants in UTC millis; the caller that
 * builds the preview dialog keeps the originating zone separately for
 * display, since a provider event has no stored zone of its own.
 */
data class CalendarEventDraft(
    val title: String,
    val beginEpochMillis: Long,
    val endEpochMillis: Long?,
    val description: String,
)

/**
 * Null for a task with neither a start nor a due moment -- there is nothing
 * to insert. Otherwise: a start moment always wins as the begin time, with
 * the due moment supplying an end time only when it falls strictly after
 * start; a due-only task begins at due with no end time at all.
 */
fun calendarEventDraft(task: Task, projectName: String?): CalendarEventDraft? {
    val start = task.start
    val due = task.due
    val beginInstant = start?.instant ?: due?.instant ?: return null
    val endInstant = due?.instant?.takeIf { start != null && it.isAfter(beginInstant) }
    return CalendarEventDraft(
        title = task.title,
        beginEpochMillis = beginInstant.toEpochMilli(),
        endEpochMillis = endInstant?.toEpochMilli(),
        description = projectName?.let { "Project: $it" }.orEmpty(),
    )
}

/**
 * One-way hand-off only: `ACTION_INSERT` with exactly the begin time, an
 * end time when the draft has one, the title, and the description. No
 * calendar permission, no stored event id, and no result contract -- the
 * provider's own insert screen owns everything from here.
 */
fun calendarInsertIntent(draft: CalendarEventDraft): Intent =
    Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, draft.beginEpochMillis)
        draft.endEpochMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        putExtra(CalendarContract.Events.TITLE, draft.title)
        putExtra(CalendarContract.Events.DESCRIPTION, draft.description)
    }
