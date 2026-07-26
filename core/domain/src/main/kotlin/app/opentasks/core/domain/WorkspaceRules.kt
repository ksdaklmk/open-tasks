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
        val sorted = entries.sortedBy(TimeEntry::startedAt)
        val conflicts = buildList {
            sorted.forEachIndexed { index, first ->
                val firstEnd = first.stoppedAt ?: now
                sorted.drop(index + 1).forEach { second ->
                    if (!second.startedAt.isBefore(firstEnd)) return@forEach
                    val secondEnd = second.stoppedAt ?: now
                    val overlapEnd = minOf(firstEnd, secondEnd)
                    if (overlapEnd.isAfter(second.startedAt)) {
                        add(
                            TimerConflict(
                                first,
                                second,
                                Duration.between(second.startedAt, overlapEnd),
                            ),
                        )
                    }
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
