package app.opentasks.core.domain

import app.opentasks.core.model.ActivityEntry
import app.opentasks.core.model.ActivityKind
import app.opentasks.core.model.AutomationRule
import app.opentasks.core.model.AutomationRuleId
import app.opentasks.core.model.AutomationRuleType
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class StaleRulesTest {
    @Test
    fun staleUsesLastTouchedThresholdsAndScopeOverride() {
        val now = Instant.parse("2026-08-17T12:00:00Z")
        val base = OpenTasksFixtures.tasks.first { it.deletedAt == null && !it.isCompleted }
        fun task(id: String, touchedDaysAgo: Long, projectId: ProjectId? = base.projectId) =
            base.copy(
                id = TaskId(id),
                projectId = projectId,
                revision = base.revision.copy(
                    wallTimeMillis = now.minus(Duration.ofDays(touchedDaysAgo)).toEpochMilli(),
                ),
            )
        fun rule(id: String, days: Int, projectId: ProjectId? = null, enabled: Boolean = true) =
            AutomationRule(
                id = AutomationRuleId(id),
                workspaceId = OpenTasksFixtures.workspaceId,
                type = AutomationRuleType.STALE_BADGE,
                enabled = enabled,
                projectId = projectId,
                thresholdDays = days,
            )
        val global = rule("global", days = 14)
        val scoped = rule("scoped", days = 3, projectId = base.projectId)

        // Global only: 15 days is stale, 13 is not.
        assertEquals(
            setOf(TaskId("old")),
            staleTaskIds(
                listOf(task("old", 15), task("fresh", 13)),
                emptyList(), listOf(global), now,
            ),
        )
        // Project rule overrides global inside its project.
        assertEquals(
            setOf(TaskId("old"), TaskId("fresh")),
            staleTaskIds(
                listOf(task("old", 15), task("fresh", 13)),
                emptyList(), listOf(global, scoped), now,
            ),
        )
        // Recent activity un-stales an old revision.
        val activity = ActivityEntry(
            id = "a-1", taskId = TaskId("old"), projectId = base.projectId,
            kind = ActivityKind.STATUS_CHANGED, body = "",
            createdAt = now.minus(Duration.ofDays(1)),
        )
        assertTrue(
            staleTaskIds(listOf(task("old", 15)), listOf(activity), listOf(global), now)
                .isEmpty(),
        )
        // Completed and binned tasks are never stale, even far past threshold.
        val completed = task("completed", 30).copy(semanticStatus = SemanticStatus.COMPLETED)
        val binned = task("binned", 30).copy(deletedAt = now)
        assertTrue(
            staleTaskIds(listOf(completed, binned), emptyList(), listOf(global), now).isEmpty(),
        )
        // A disabled rule is inert.
        val disabledGlobal = rule("disabled-global", days = 1, enabled = false)
        assertTrue(
            staleTaskIds(listOf(task("old", 15)), emptyList(), listOf(disabledGlobal), now)
                .isEmpty(),
        )
        // Duplicate rules at the same scope resolve by lowest rule id: a
        // 10-day-old task is stale under the 5-day rule but fresh under the
        // 20-day one, so the outcome pins down which rule actually won.
        val globalLow = rule("a-global", days = 5)
        val globalHigh = rule("b-global", days = 20)
        assertEquals(
            setOf(TaskId("global-tie")),
            staleTaskIds(
                listOf(task("global-tie", 10)),
                emptyList(), listOf(globalHigh, globalLow), now,
            ),
        )
        val scopedLow = rule("a-scoped", days = 5, projectId = base.projectId)
        val scopedHigh = rule("b-scoped", days = 20, projectId = base.projectId)
        assertEquals(
            setOf(TaskId("scoped-tie")),
            staleTaskIds(
                listOf(task("scoped-tie", 10)),
                emptyList(), listOf(scopedHigh, scopedLow), now,
            ),
        )
    }
}
