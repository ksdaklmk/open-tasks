package app.opentasks.core.domain

import app.opentasks.core.model.ActivityEntry
import app.opentasks.core.model.AutomationRule
import app.opentasks.core.model.AutomationRuleType
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import java.time.Duration
import java.time.Instant

/**
 * Pure stale-task projection. `STALE_BADGE` rows are deliberately
 * projection-only — [evaluateAutomationRules] never fires them from a status
 * transition — so this is their sole consumer, invoked by the UI layer as a
 * single named authority (never re-derived elsewhere).
 *
 * `lastTouched` matches [buildReviewQueue]'s definition: max of the task's
 * own revision wall time and its latest activity entry's `createdAt`, with
 * no activity falling back to [Instant.MIN] rather than the revision time
 * losing to a sentinel that could postdate it.
 *
 * An enabled project-scoped rule overrides an enabled global rule for tasks
 * in that project; multiple enabled rules at the same scope resolve
 * deterministically by lowest rule ID. Completed and binned tasks are never
 * stale.
 */
fun staleTaskIds(
    tasks: List<Task>,
    activityEntries: List<ActivityEntry>,
    rules: List<AutomationRule>,
    now: Instant,
): Set<TaskId> {
    val staleRules = rules.filter {
        it.enabled && it.type == AutomationRuleType.STALE_BADGE && it.thresholdDays != null
    }
    if (staleRules.isEmpty()) return emptySet()
    val global = staleRules.filter { it.projectId == null }
        .minByOrNull { it.id.value }
    val byProject = staleRules.filter { it.projectId != null }
        .groupBy { requireNotNull(it.projectId) }
        .mapValues { (_, matching) -> matching.minBy { it.id.value } }
    val latestActivity = activityEntries
        .filter { it.taskId != null }
        .groupBy { requireNotNull(it.taskId) }
        .mapValues { (_, entries) -> entries.maxOf(ActivityEntry::createdAt) }
    return tasks.asSequence()
        .filter { it.deletedAt == null && !it.isCompleted }
        .mapNotNull { task ->
            val rule = task.projectId?.let(byProject::get) ?: global ?: return@mapNotNull null
            val threshold = Duration.ofDays(requireNotNull(rule.thresholdDays).toLong())
            val touched = maxOf(
                Instant.ofEpochMilli(task.revision.wallTimeMillis),
                latestActivity[task.id] ?: Instant.MIN,
            )
            task.id.takeIf { Duration.between(touched, now) > threshold }
        }
        .toSet()
}
