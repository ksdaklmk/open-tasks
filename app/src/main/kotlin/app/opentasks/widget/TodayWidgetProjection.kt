package app.opentasks.widget

import app.opentasks.core.model.Task
import app.opentasks.core.model.WorkspaceSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One focus-task row: its identity for tap-through and completion, its
 * title, and whether it is currently safe to complete from the widget
 * surface. [completable] is `false` exactly when the task is blocked
 * (`Task.isBlocked`), mirroring the reminder notification's own
 * complete-action gating.
 */
data class FocusEntry(
    val taskId: String,
    val title: String,
    val completable: Boolean,
)

/**
 * The Today widget's minimal view of the workspace: how many open tasks
 * land on today, how many are overdue, and up to three focus-task rows.
 *
 * [focusEntries] is capped at three entries and is empty whenever titles are
 * not permitted, even if today has open tasks -- the counts stay intact
 * either way.
 */
data class TodayWidgetProjection(
    val openTodayCount: Int,
    val overdueCount: Int,
    val focusEntries: List<FocusEntry>,
)

private const val MAX_FOCUS_TITLES = 3

/**
 * Projects [snapshot] into the widget's minimal view for [today] in [zone],
 * as of [now].
 *
 * A task counts for today when it is open (`!isCompleted`,
 * `deletedAt == null`) and its `start`, or `due` when there is no `start`,
 * falls on [today] in that moment's own stored zone -- not [zone]. A task is
 * overdue when it is open and its `due` is strictly before [now] -- an
 * instant comparison needing no zone conversion of its own, so a task due
 * earlier today is overdue and one due later today is not, even though both
 * still count for today. [zone] is therefore not read by this function's own
 * overdue check; it remains only because the brief's original signature
 * carried it, and every "today" computation elsewhere in the product is
 * zone-relative. Focus entries are today's open tasks ordered by due date
 * (undated tasks sort last) then by descending priority, capped at
 * [MAX_FOCUS_TITLES], and withheld entirely when [titlesPermitted] is false.
 * Each entry's [FocusEntry.completable] is `!task.isBlocked`.
 */
fun computeTodayProjection(
    snapshot: WorkspaceSnapshot,
    today: LocalDate,
    zone: ZoneId,
    now: Instant,
    titlesPermitted: Boolean,
): TodayWidgetProjection {
    val openTasks = snapshot.tasks.filter { !it.isCompleted && it.deletedAt == null }

    val todayTasks = openTasks.filter { task ->
        val moment = task.start ?: task.due
        moment != null && moment.instant.atZone(moment.zone()).toLocalDate() == today
    }

    val overdueCount = openTasks.count { task ->
        task.due?.instant?.isBefore(now) == true
    }

    val focusEntries = if (titlesPermitted) {
        todayTasks
            .sortedWith(
                compareBy<Task> { it.due?.instant ?: Instant.MAX }
                    .thenByDescending { it.priority.ordinal },
            )
            .take(MAX_FOCUS_TITLES)
            .map { task ->
                FocusEntry(
                    taskId = task.id.value,
                    title = task.title,
                    completable = !task.isBlocked,
                )
            }
    } else {
        emptyList()
    }

    return TodayWidgetProjection(
        openTodayCount = todayTasks.size,
        overdueCount = overdueCount,
        focusEntries = focusEntries,
    )
}
