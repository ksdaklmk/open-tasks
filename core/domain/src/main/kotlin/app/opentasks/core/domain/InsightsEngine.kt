package app.opentasks.core.domain

import app.opentasks.core.model.DurationQuality
import app.opentasks.core.model.EstimateActual
import app.opentasks.core.model.InsightsQuality
import app.opentasks.core.model.InsightsSelection
import app.opentasks.core.model.InsightsSnapshot
import app.opentasks.core.model.InstantRange
import app.opentasks.core.model.MetricComparison
import app.opentasks.core.model.MilestoneHealthRow
import app.opentasks.core.model.OverdueRow
import app.opentasks.core.model.ProjectTimeRow
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TagTimeRow
import app.opentasks.core.model.WorkspaceSnapshot
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

interface InsightsEngine {
    fun calculate(
        workspace: WorkspaceSnapshot,
        selection: InsightsSelection,
        now: Instant,
        zoneId: ZoneId,
    ): InsightsSnapshot
}

class DefaultInsightsEngine : InsightsEngine {
    override fun calculate(
        workspace: WorkspaceSnapshot,
        selection: InsightsSelection,
        now: Instant,
        zoneId: ZoneId,
    ): InsightsSnapshot {
        val currentDate = now.atZone(zoneId).toLocalDate()
        val startDate = currentDate.minusDays(selection.range.dayCount - 1)
        val endExclusive = currentDate.plusDays(1).atStartOfDay(zoneId).toInstant()
        val startInclusive = startDate.atStartOfDay(zoneId).toInstant()
        val comparisonStart = startDate
            .minusDays(selection.range.dayCount)
            .atStartOfDay(zoneId)
            .toInstant()

        val selectedTaskIds = workspace.tasks
            .asSequence()
            .filter { task ->
                selection.projectIds.isEmpty() || task.projectId in selection.projectIds
            }
            .filter { task ->
                selection.tagIds.isEmpty() || task.tagIds.any(selection.tagIds::contains)
            }
            .map { it.id }
            .toSet()

        val projectNames = workspace.projects.associate { it.id to it.name }
        val overdue = workspace.tasks
            .asSequence()
            .filter { it.id in selectedTaskIds }
            .filter { it.deletedAt == null }
            .filter { it.semanticStatus != SemanticStatus.COMPLETED }
            .mapNotNull { task ->
                val dueAt = task.due?.instant ?: return@mapNotNull null
                if (!dueAt.isBefore(now)) return@mapNotNull null
                OverdueRow(
                    taskId = task.id,
                    title = task.title,
                    projectName = task.projectId?.let(projectNames::get),
                    dueAt = dueAt,
                )
            }
            .sortedWith(
                compareBy<OverdueRow>(OverdueRow::dueAt)
                    .thenBy { it.title.lowercase(Locale.ROOT) },
            )
            .toList()

        val emptyDuration = DurationQuality(
            trusted = Duration.ZERO,
            conflicted = Duration.ZERO,
        )
        val projectTime = sortProjectTime(emptyList(), selection.includeConflictedTime)
        val tagTime = sortTagTime(emptyList(), selection.includeConflictedTime)
        val milestoneHealth = sortMilestones(emptyList())

        return InsightsSnapshot(
            interval = InstantRange(startInclusive, endExclusive),
            comparisonInterval = InstantRange(comparisonStart, startInclusive),
            completed = MetricComparison(current = 0, previous = 0),
            overdue = overdue,
            estimateActual = EstimateActual(
                estimated = Duration.ZERO,
                actual = emptyDuration,
            ),
            projectTime = projectTime,
            tagTime = tagTime,
            milestoneHealth = milestoneHealth,
            quality = InsightsQuality(recordedTime = emptyDuration),
        )
    }

    private fun sortProjectTime(
        rows: List<ProjectTimeRow>,
        includeConflictedTime: Boolean,
    ): List<ProjectTimeRow> = rows.sortedWith(
        compareByDescending<ProjectTimeRow> {
            it.duration.included(includeConflictedTime)
        }.thenBy { it.displayName.lowercase(Locale.ROOT) },
    )

    private fun sortTagTime(
        rows: List<TagTimeRow>,
        includeConflictedTime: Boolean,
    ): List<TagTimeRow> = rows.sortedWith(
        compareByDescending<TagTimeRow> {
            it.duration.included(includeConflictedTime)
        }.thenBy { it.displayName.lowercase(Locale.ROOT) },
    )

    private fun sortMilestones(rows: List<MilestoneHealthRow>): List<MilestoneHealthRow> =
        rows.sortedWith(
            compareBy<MilestoneHealthRow> { it.dueAt ?: Instant.MAX }
                .thenBy { it.displayName.lowercase(Locale.ROOT) },
        )

    private fun DurationQuality.included(includeConflictedTime: Boolean): Duration =
        if (includeConflictedTime) trusted.plus(conflicted) else trusted
}
