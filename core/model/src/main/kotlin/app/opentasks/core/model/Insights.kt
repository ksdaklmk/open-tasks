package app.opentasks.core.model

import java.time.Duration
import java.time.Instant

enum class InsightsRange(val dayCount: Long) {
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
    NINETY_DAYS(90),
}

data class InsightsSelection(
    val range: InsightsRange = InsightsRange.SEVEN_DAYS,
    val projectIds: Set<ProjectId> = emptySet(),
    val tagIds: Set<TagId> = emptySet(),
    val includeConflictedTime: Boolean = false,
)

data class InstantRange(
    val startInclusive: Instant,
    val endExclusive: Instant,
) {
    fun contains(instant: Instant): Boolean =
        !instant.isBefore(startInclusive) && instant.isBefore(endExclusive)
}

data class MetricComparison(
    val current: Long,
    val previous: Long,
)

data class DurationQuality(
    val trusted: Duration,
    val conflicted: Duration,
)

data class OverdueRow(
    val taskId: TaskId,
    val title: String,
    val projectName: String?,
    val dueAt: Instant,
)

data class EstimateActual(
    val estimated: Duration,
    val actual: DurationQuality,
)

data class ProjectTimeRow(
    val projectId: ProjectId,
    val displayName: String,
    val duration: DurationQuality,
)

data class TagTimeRow(
    val tagId: TagId,
    val displayName: String,
    val duration: DurationQuality,
)

data class MilestoneHealthRow(
    val milestoneId: MilestoneId,
    val displayName: String,
    val projectName: String,
    val dueAt: Instant?,
    val completedTasks: Long,
    val totalTasks: Long,
    val overdueTasks: Long,
)

data class InsightsQuality(
    val recordedTime: DurationQuality,
)

data class InsightsSnapshot(
    val interval: InstantRange,
    val comparisonInterval: InstantRange,
    val completed: MetricComparison,
    val overdue: List<OverdueRow>,
    val estimateActual: EstimateActual,
    val projectTime: List<ProjectTimeRow>,
    val tagTime: List<TagTimeRow>,
    val milestoneHealth: List<MilestoneHealthRow>,
    val quality: InsightsQuality,
)
