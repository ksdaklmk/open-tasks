package app.opentasks.widget

import app.opentasks.core.model.Task
import app.opentasks.core.model.WorkspaceSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Today widget's minimal view of the workspace: how many open tasks
 * land on today, how many are overdue, and up to three focus-task titles.
 *
 * [focusTitles] is capped at three entries and is empty whenever titles are
 * not permitted, even if today has open tasks -- the counts stay intact
 * either way.
 */
data class TodayWidgetProjection(
    val openTodayCount: Int,
    val overdueCount: Int,
    val focusTitles: List<String>,
)

private const val MAX_FOCUS_TITLES = 3

/**
 * Projects [snapshot] into the widget's minimal view for [today] in [zone].
 *
 * A task counts for today when it is open (`!isCompleted`,
 * `deletedAt == null`) and its `start`, or `due` when there is no `start`,
 * falls on [today] in that moment's own stored zone -- not [zone]. Due
 * instants need no zone conversion to compare against another instant, so
 * [zone] is used only to anchor the overdue boundary, the start of [today];
 * a task is overdue when it is open and its `due` is before that boundary.
 * Focus titles are today's open tasks ordered by due date (undated tasks
 * sort last) then by descending priority, capped at [MAX_FOCUS_TITLES], and
 * withheld entirely when [titlesPermitted] is false.
 */
fun computeTodayProjection(
    snapshot: WorkspaceSnapshot,
    today: LocalDate,
    zone: ZoneId,
    titlesPermitted: Boolean,
): TodayWidgetProjection {
    val openTasks = snapshot.tasks.filter { !it.isCompleted && it.deletedAt == null }

    val todayTasks = openTasks.filter { task ->
        val moment = task.start ?: task.due
        moment != null && moment.instant.atZone(moment.zone()).toLocalDate() == today
    }

    val overdueBoundary = today.atStartOfDay(zone).toInstant()
    val overdueCount = openTasks.count { task ->
        task.due?.instant?.isBefore(overdueBoundary) == true
    }

    val focusTitles = if (titlesPermitted) {
        todayTasks
            .sortedWith(
                compareBy<Task> { it.due?.instant ?: Instant.MAX }
                    .thenByDescending { it.priority.ordinal },
            )
            .take(MAX_FOCUS_TITLES)
            .map(Task::title)
    } else {
        emptyList()
    }

    return TodayWidgetProjection(
        openTodayCount = todayTasks.size,
        overdueCount = overdueCount,
        focusTitles = focusTitles,
    )
}
