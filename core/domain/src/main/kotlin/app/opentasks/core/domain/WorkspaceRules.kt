package app.opentasks.core.domain

import app.opentasks.core.model.Project
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TimeEntry
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

data class ProgressSummary(
    val completed: Int,
    val total: Int,
    val ratio: Float,
)

object ProgressRules {
    fun forTasks(tasks: Collection<Task>): ProgressSummary {
        val active = tasks.filter { it.deletedAt == null }
        val completed = active.count { it.semanticStatus == SemanticStatus.COMPLETED }
        return ProgressSummary(
            completed = completed,
            total = active.size,
            ratio = if (active.isEmpty()) 0f else completed.toFloat() / active.size,
        )
    }

    fun forProject(project: Project, tasks: Collection<Task>): ProgressSummary =
        forTasks(tasks.filter { it.projectId == project.id })

    /**
     * Restates every project's [Project.completedTasks] and [Project.totalTasks]
     * from [tasks]. The stored columns are legacy: they are written once at
     * creation, only a backup import ever sets them, and no command maintains
     * them. Both repositories call this so the workspace snapshot is the single
     * authority for project progress.
     */
    fun withTaskCounts(projects: List<Project>, tasks: Collection<Task>): List<Project> {
        val byProject = tasks.filter { it.deletedAt == null }.groupBy(Task::projectId)
        return projects.map { project ->
            val projectTasks = byProject[project.id].orEmpty()
            project.copy(
                completedTasks = projectTasks.count(Task::isCompleted),
                totalTasks = projectTasks.size,
            )
        }
    }
}

object TrashPolicy {
    const val RETENTION_DAYS: Long = 30

    fun purgeAfter(deletedAt: Instant): Instant =
        deletedAt.plus(RETENTION_DAYS, ChronoUnit.DAYS)

    fun isEligibleForPurge(deletedAt: Instant, now: Instant): Boolean =
        !now.isBefore(purgeAfter(deletedAt))
}

data class TimerConflict(
    val first: TimeEntry,
    val second: TimeEntry,
    val overlap: Duration,
)

data class TimerReconciliation(
    val entries: List<TimeEntry>,
    val conflicts: List<TimerConflict>,
)

object TimerRules {
    fun reconcile(entries: Collection<TimeEntry>, now: Instant): TimerReconciliation {
        val sorted = entries.sortedWith(
            compareBy(TimeEntry::startedAt).thenBy { it.id.value },
        )
        val conflicts = buildList {
            var anchor: TimeEntry? = null
            var anchorEnd: Instant? = null
            sorted.forEach { entry ->
                val entryEnd = entry.stoppedAt ?: now
                if (!entryEnd.isAfter(entry.startedAt)) return@forEach
                val previous = anchor
                val previousEnd = anchorEnd
                if (
                    previous != null &&
                    previousEnd != null &&
                    entry.startedAt.isBefore(previousEnd)
                ) {
                    add(
                        TimerConflict(
                            previous,
                            entry,
                            Duration.between(
                                entry.startedAt,
                                minOf(previousEnd, entryEnd),
                            ),
                        ),
                    )
                }
                if (previousEnd == null || entryEnd.isAfter(previousEnd)) {
                    anchor = entry
                    anchorEnd = entryEnd
                }
            }
        }
        return TimerReconciliation(sorted, conflicts)
    }
}

object SearchNormalizer {
    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
        return decomposed
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .trim()
            .replace("\\s+".toRegex(), " ")
    }
}
