package app.opentasks.core.domain

import app.opentasks.core.model.ReviewQueue
import app.opentasks.core.model.Task
import app.opentasks.core.model.WorkspaceSnapshot
import java.time.Duration
import java.time.Instant
import java.util.Locale

fun buildReviewQueue(
    snapshot: WorkspaceSnapshot,
    now: Instant,
    staleAfter: Duration = Duration.ofDays(14),
): ReviewQueue {
    val latestActivity = snapshot.activityEntries
        .filter { it.taskId != null }
        .groupBy { it.taskId!! }
        .mapValues { (_, entries) -> entries.maxOf { it.createdAt } }
    val lastTouched = { task: Task ->
        maxOf(
            Instant.ofEpochMilli(task.revision.wallTimeMillis),
            latestActivity[task.id] ?: Instant.MIN,
        )
    }
    val openTasks = snapshot.tasks.filter { it.deletedAt == null && !it.isCompleted }
    val overdue = openTasks.filter { it.due?.instant?.isBefore(now) == true }
    val stale = openTasks.filter { it !in overdue && lastTouched(it).isBefore(now.minus(staleAfter)) }
    val unscheduled = openTasks.filter {
        it !in stale && it.start == null && it.due == null
    }

    return ReviewQueue(
        overdue = overdue.sortedWith(compareBy({ it.due!!.instant }, { it.title }, { it.id.value })),
        stale = stale.sortedWith(compareBy(lastTouched, { it.title }, { it.id.value })),
        unscheduled = unscheduled.sortedWith(compareBy({ it.title }, { it.id.value })),
        projects = snapshot.projects.filter { it.archivedAt == null }.sortedWith(
            compareBy({ it.name.lowercase(Locale.ROOT) }, { it.id.value }),
        ),
    )
}
