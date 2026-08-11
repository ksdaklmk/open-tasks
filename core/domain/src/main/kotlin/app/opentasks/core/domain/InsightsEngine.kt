package app.opentasks.core.domain

import app.opentasks.core.model.DurationQuality
import app.opentasks.core.model.EstimateActual
import app.opentasks.core.model.CompletionTrendPoint
import app.opentasks.core.model.InsightsQuality
import app.opentasks.core.model.InsightsSelection
import app.opentasks.core.model.InsightsSnapshot
import app.opentasks.core.model.InstantRange
import app.opentasks.core.model.MetricComparison
import app.opentasks.core.model.MilestoneHealthRow
import app.opentasks.core.model.OverdueBand
import app.opentasks.core.model.OverdueRow
import app.opentasks.core.model.ProjectTimeRow
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TagTimeRow
import app.opentasks.core.model.Task
import app.opentasks.core.model.WorkspaceSnapshot
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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
        val interval = InstantRange(startInclusive, endExclusive)
        val comparisonInterval = InstantRange(comparisonStart, startInclusive)

        val selectedTasks = workspace.tasks
            .filter { task ->
                selection.projectIds.isEmpty() || task.projectId in selection.projectIds
            }
            .filter { task ->
                selection.tagIds.isEmpty() || task.tagIds.any(selection.tagIds::contains)
            }
        val selectedTaskIds = selectedTasks
            .asSequence()
            .map(Task::id)
            .toSet()

        val projectNames = workspace.projects.associate { it.id to it.name }
        val overdue = selectedTasks
            .asSequence()
            .filter { it.deletedAt == null }
            .filter { it.semanticStatus != SemanticStatus.COMPLETED }
            .mapNotNull { task ->
                val dueAt = task.due?.instant ?: return@mapNotNull null
                if (!dueAt.isBefore(now)) return@mapNotNull null
                val overdueDays = ChronoUnit.DAYS.between(
                    dueAt.atZone(zoneId).toLocalDate(),
                    currentDate,
                ).coerceAtLeast(1)
                OverdueRow(
                    taskId = task.id,
                    title = task.title,
                    projectName = task.projectId?.let(projectNames::get),
                    dueAt = dueAt,
                    band = when {
                        overdueDays <= 7 -> OverdueBand.ONE_TO_SEVEN_DAYS
                        overdueDays <= 30 -> OverdueBand.EIGHT_TO_THIRTY_DAYS
                        else -> OverdueBand.THIRTY_ONE_DAYS_OR_MORE
                    },
                )
            }
            .sortedWith(
                compareBy<OverdueRow>(OverdueRow::dueAt)
                    .thenBy { it.title.lowercase(Locale.ROOT) },
            )
            .toList()

        val conflictedEntryIds = workspace.timeEntryConflicts
            .flatMap { conflict ->
                listOf(conflict.firstEntryId, conflict.secondEntryId)
            }
            .toSet()
        val tasksById = workspace.tasks.associateBy(Task::id)
        val qualifiedTime = workspace.timeEntries.mapNotNull { entry ->
            if (entry.taskId !in selectedTaskIds) return@mapNotNull null
            val task = tasksById[entry.taskId] ?: return@mapNotNull null
            val endedAt = entry.stoppedAt ?: return@mapNotNull null
            val clippedStart = maxOf(entry.startedAt, interval.startInclusive)
            val clippedEnd = minOf(endedAt, interval.endExclusive)
            if (!clippedStart.isBefore(clippedEnd)) return@mapNotNull null
            QualifiedTime(
                task = task,
                duration = Duration.between(clippedStart, clippedEnd),
                conflicted = entry.id in conflictedEntryIds,
            )
        }
        val recordedTime = durationQuality(
            entries = qualifiedTime,
            includeConflictedTime = selection.includeConflictedTime,
        )

        val projectTime = qualifiedTime
            .filter { entry ->
                entry.task.projectId == null || entry.task.projectId in projectNames
            }
            .groupBy { it.task.projectId }
            .map { (projectId, entries) ->
                ProjectTimeRow(
                    projectId = projectId,
                    displayName = projectId?.let(projectNames::getValue) ?: "Inbox",
                    duration = durationQuality(
                        entries = entries,
                        includeConflictedTime = selection.includeConflictedTime,
                    ),
                )
            }
            .let(::sortProjectTime)

        val tagNames = workspace.tags.associate { it.id to it.name }
        val tagTime = qualifiedTime
            .flatMap { entry ->
                entry.task.tagIds.mapNotNull { tagId ->
                    if (tagId in tagNames) tagId to entry else null
                }
            }
            .groupBy(
                keySelector = { it.first },
                valueTransform = { it.second },
            )
            .map { (tagId, entries) ->
                TagTimeRow(
                    tagId = tagId,
                    displayName = tagNames.getValue(tagId),
                    duration = durationQuality(
                        entries = entries,
                        includeConflictedTime = selection.includeConflictedTime,
                    ),
                )
            }
            .let(::sortTagTime)

        val currentCompletedTasks = selectedTasks.filter { task ->
            task.completedAt?.let(interval::contains) == true
        }
        val completionCounts = selectedTasks.asSequence()
            .mapNotNull(Task::completedAt)
            .filter(interval::contains)
            .groupingBy { it.atZone(zoneId).toLocalDate() }
            .eachCount()
        val completionTrend = generateSequence(startDate) { date ->
            date.plusDays(1).takeUnless { it.isAfter(currentDate) }
        }.map { date ->
            CompletionTrendPoint(date, completionCounts[date]?.toLong() ?: 0L)
        }.toList()
        val currentCompletedTaskIds = currentCompletedTasks
            .asSequence()
            .map(Task::id)
            .toSet()
        val estimatedTasks = currentCompletedTasks.filter { it.estimate != null }
        val actualTime = qualifiedTime.filter { it.task.id in currentCompletedTaskIds }
        val estimateActual = EstimateActual(
            estimated = estimatedTasks.fold(Duration.ZERO) { total, task ->
                total.plus(task.estimate)
            },
            actual = durationQuality(
                entries = actualTime,
                includeConflictedTime = selection.includeConflictedTime,
            ),
            estimatedTaskCount = estimatedTasks.size.toLong(),
            unestimatedTaskCount = currentCompletedTasks.count { it.estimate == null }.toLong(),
            actualTaskCount = actualTime
                .asSequence()
                .filter { selection.includeConflictedTime || !it.conflicted }
                .map { it.task.id }
                .distinct()
                .count()
                .toLong(),
        )

        val milestoneHealth = workspace.milestones
            .asSequence()
            .filter { it.completedAt == null }
            .mapNotNull { milestone ->
                val project = workspace.projects
                    .firstOrNull { it.id == milestone.projectId }
                    ?: return@mapNotNull null
                val tasks = selectedTasks.filter { it.milestoneId == milestone.id }
                MilestoneHealthRow(
                    milestoneId = milestone.id,
                    displayName = milestone.name,
                    projectName = project.name,
                    dueAt = milestone.dueDate?.atStartOfDay(zoneId)?.toInstant(),
                    projectHealth = project.status,
                    completedTasks = tasks.count {
                        it.semanticStatus == SemanticStatus.COMPLETED
                    }.toLong(),
                    totalTasks = tasks.size.toLong(),
                    overdueTasks = tasks.count { task ->
                        task.deletedAt == null &&
                            task.semanticStatus != SemanticStatus.COMPLETED &&
                            task.due?.instant?.isBefore(now) == true
                    }.toLong(),
                )
            }
            .toList()
            .let(::sortMilestones)

        return InsightsSnapshot(
            interval = interval,
            comparisonInterval = comparisonInterval,
            completed = MetricComparison(
                current = selectedTasks.count { task ->
                    task.completedAt?.let(interval::contains) == true
                }.toLong(),
                previous = selectedTasks.count { task ->
                    task.completedAt?.let(comparisonInterval::contains) == true
                }.toLong(),
            ),
            overdue = overdue,
            estimateActual = estimateActual,
            projectTime = projectTime,
            tagTime = tagTime,
            milestoneHealth = milestoneHealth,
            quality = InsightsQuality(recordedTime = recordedTime),
            completionTrend = completionTrend,
        )
    }

    private fun durationQuality(
        entries: List<QualifiedTime>,
        includeConflictedTime: Boolean,
    ): DurationQuality {
        val trusted = entries
            .asSequence()
            .filterNot(QualifiedTime::conflicted)
            .fold(Duration.ZERO) { total, entry -> total.plus(entry.duration) }
        val conflicted = entries
            .asSequence()
            .filter(QualifiedTime::conflicted)
            .fold(Duration.ZERO) { total, entry -> total.plus(entry.duration) }
        return DurationQuality(
            trusted = trusted,
            conflicted = conflicted,
            included = if (includeConflictedTime) trusted.plus(conflicted) else trusted,
        )
    }

    private fun sortProjectTime(rows: List<ProjectTimeRow>): List<ProjectTimeRow> =
        rows.sortedWith(
            compareByDescending<ProjectTimeRow> {
                it.duration.included
            }.thenBy { it.displayName.lowercase(Locale.ROOT) },
        )

    private fun sortTagTime(rows: List<TagTimeRow>): List<TagTimeRow> =
        rows.sortedWith(
            compareByDescending<TagTimeRow> {
                it.duration.included
            }.thenBy { it.displayName.lowercase(Locale.ROOT) },
        )

    private fun sortMilestones(rows: List<MilestoneHealthRow>): List<MilestoneHealthRow> =
        rows.sortedWith(
            compareBy<MilestoneHealthRow> { it.dueAt ?: Instant.MAX }
                .thenBy { it.displayName.lowercase(Locale.ROOT) },
        )

    private data class QualifiedTime(
        val task: Task,
        val duration: Duration,
        val conflicted: Boolean,
    )
}
