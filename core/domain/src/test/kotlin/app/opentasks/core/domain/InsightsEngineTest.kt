package app.opentasks.core.domain

import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.DurationQuality
import app.opentasks.core.model.InsightsRange
import app.opentasks.core.model.InsightsSelection
import app.opentasks.core.model.Milestone
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.OverdueBand
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TimeEntry
import app.opentasks.core.model.TimeEntryConflict
import app.opentasks.core.model.TimeEntryId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class InsightsEngineTest {
    private val engine: InsightsEngine = DefaultInsightsEngine()

    @Test
    fun sevenThirtyAndNinetyDayRangesUseLocalCalendarBoundaries() {
        val cases = listOf(
            Triple(
                InsightsRange.SEVEN_DAYS,
                Instant.parse("2026-07-20T17:00:00Z"),
                Instant.parse("2026-07-27T17:00:00Z"),
            ),
            Triple(
                InsightsRange.THIRTY_DAYS,
                Instant.parse("2026-06-27T17:00:00Z"),
                Instant.parse("2026-07-27T17:00:00Z"),
            ),
            Triple(
                InsightsRange.NINETY_DAYS,
                Instant.parse("2026-04-28T17:00:00Z"),
                Instant.parse("2026-07-27T17:00:00Z"),
            ),
        )

        cases.forEach { (range, expectedStart, expectedEnd) ->
            val result = engine.calculate(
                workspace = OpenTasksFixtures.snapshot,
                selection = InsightsSelection(range = range),
                now = Instant.parse("2026-07-27T12:00:00Z"),
                zoneId = ZoneId.of("Asia/Bangkok"),
            )

            assertEquals(expectedStart, result.interval.startInclusive)
            assertEquals(expectedEnd, result.interval.endExclusive)
            assertEquals(expectedStart, result.comparisonInterval.endExclusive)
        }
    }

    @Test
    fun springDstRangeEndsAtTheNextLocalMidnight() {
        val result = engine.calculate(
            workspace = OpenTasksFixtures.snapshot,
            selection = InsightsSelection(),
            now = Instant.parse("2026-03-08T16:00:00Z"),
            zoneId = ZoneId.of("America/New_York"),
        )

        assertEquals(Instant.parse("2026-03-02T05:00:00Z"), result.interval.startInclusive)
        assertEquals(Instant.parse("2026-03-09T04:00:00Z"), result.interval.endExclusive)
        assertEquals(
            Instant.parse("2026-02-23T05:00:00Z"),
            result.comparisonInterval.startInclusive,
        )
    }

    @Test
    fun fallDstRangeEndsAtTheNextLocalMidnight() {
        val result = engine.calculate(
            workspace = OpenTasksFixtures.snapshot,
            selection = InsightsSelection(),
            now = Instant.parse("2026-11-01T17:00:00Z"),
            zoneId = ZoneId.of("America/New_York"),
        )

        assertEquals(Instant.parse("2026-10-26T04:00:00Z"), result.interval.startInclusive)
        assertEquals(Instant.parse("2026-11-02T05:00:00Z"), result.interval.endExclusive)
        assertEquals(
            Instant.parse("2026-10-19T04:00:00Z"),
            result.comparisonInterval.startInclusive,
        )
    }

    @Test
    fun intervalExcludesItsExactEnd() {
        val result = engine.calculate(
            workspace = OpenTasksFixtures.snapshot,
            selection = InsightsSelection(),
            now = Instant.parse("2026-07-27T12:00:00Z"),
            zoneId = ZoneId.of("Asia/Bangkok"),
        )

        assertTrue(result.interval.contains(Instant.parse("2026-07-27T16:59:59.999999999Z")))
        assertFalse(result.interval.contains(Instant.parse("2026-07-27T17:00:00Z")))
    }

    @Test
    fun overdueRowsRespectSelectionAndExcludeCompletedOrBinTasks() {
        val now = Instant.parse("2026-07-27T12:00:00Z")
        val base = OpenTasksFixtures.tasks.first()
        val matching = base.copy(
            id = TaskId("matching"),
            due = base.due!!.copy(instant = now.minusSeconds(1)),
        )
        val otherTag = matching.copy(
            id = TaskId("other-tag"),
            tagIds = setOf(TagId("tag-admin")),
        )
        val completed = matching.copy(
            id = TaskId("completed"),
            semanticStatus = SemanticStatus.COMPLETED,
            completedAt = now.minusSeconds(2),
        )
        val inBin = matching.copy(
            id = TaskId("in-bin"),
            deletedAt = now.minusSeconds(2),
        )
        val workspace = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(matching, otherTag, completed, inBin),
        )

        val result = engine.calculate(
            workspace = workspace,
            selection = InsightsSelection(
                projectIds = setOf(OpenTasksFixtures.studioProject.id),
                tagIds = setOf(TagId("tag-deep-work")),
            ),
            now = now,
            zoneId = ZoneId.of("Asia/Bangkok"),
        )

        assertEquals(listOf(TaskId("matching")), result.overdue.map { it.taskId })
    }

    @Test
    fun completedCountsUseCurrentAndPreviousHalfOpenIntervalsIncludingBinHistory() {
        val current = completedTask(
            id = "current-start",
            completedAt = Instant.parse("2026-07-21T00:00:00Z"),
        ).copy(deletedAt = Instant.parse("2026-07-22T00:00:00Z"))
        val previous = completedTask(
            id = "previous-start",
            completedAt = Instant.parse("2026-07-14T00:00:00Z"),
        )
        val currentEnd = completedTask(
            id = "current-end",
            completedAt = Instant.parse("2026-07-28T00:00:00Z"),
        )
        val workspace = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(current, previous, currentEnd),
        )

        val result = calculate(workspace)

        assertEquals(1L, result.completed.current)
        assertEquals(1L, result.completed.previous)
    }

    @Test
    fun overdueBandsUseExactLocalCalendarBoundaries() {
        val cases = listOf(
            "same-day" to Instant.parse("2026-07-27T08:00:00Z"),
            "seven-days" to Instant.parse("2026-07-20T12:00:00Z"),
            "eight-days" to Instant.parse("2026-07-19T12:00:00Z"),
            "thirty-days" to Instant.parse("2026-06-27T12:00:00Z"),
            "thirty-one-days" to Instant.parse("2026-06-26T12:00:00Z"),
        )
        val workspace = OpenTasksFixtures.snapshot.copy(
            tasks = cases.map { (id, dueAt) -> overdueTask(id, dueAt) },
        )

        val rows = calculate(workspace).overdue.associateBy { it.taskId }

        assertEquals(OverdueBand.ONE_TO_SEVEN_DAYS, rows.getValue(TaskId("same-day")).band)
        assertEquals(OverdueBand.ONE_TO_SEVEN_DAYS, rows.getValue(TaskId("seven-days")).band)
        assertEquals(OverdueBand.EIGHT_TO_THIRTY_DAYS, rows.getValue(TaskId("eight-days")).band)
        assertEquals(OverdueBand.EIGHT_TO_THIRTY_DAYS, rows.getValue(TaskId("thirty-days")).band)
        assertEquals(
            OverdueBand.THIRTY_ONE_DAYS_OR_MORE,
            rows.getValue(TaskId("thirty-one-days")).band,
        )
    }

    @Test
    fun estimateActualUsesCurrentCompletedTasksAndExposesMissingDenominators() {
        val estimatedWithActual = completedTask(
            id = "estimated-with-actual",
            completedAt = Instant.parse("2026-07-25T12:00:00Z"),
            estimate = Duration.ofHours(2),
        )
        val unestimatedWithActual = completedTask(
            id = "unestimated-with-actual",
            completedAt = Instant.parse("2026-07-26T12:00:00Z"),
            estimate = null,
        )
        val estimatedWithoutActual = completedTask(
            id = "estimated-without-actual",
            completedAt = Instant.parse("2026-07-27T12:00:00Z"),
            estimate = Duration.ofHours(1),
        )
        val previous = completedTask(
            id = "previous",
            completedAt = Instant.parse("2026-07-20T12:00:00Z"),
            estimate = Duration.ofHours(9),
        )
        val open = OpenTasksFixtures.tasks.first().copy(
            id = TaskId("open"),
            estimate = Duration.ofHours(8),
        )
        val workspace = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(
                estimatedWithActual,
                unestimatedWithActual,
                estimatedWithoutActual,
                previous,
                open,
            ),
            timeEntries = listOf(
                timeEntry(
                    id = "estimated-actual",
                    taskId = estimatedWithActual.id,
                    start = Instant.parse("2026-07-25T10:00:00Z"),
                    end = Instant.parse("2026-07-25T10:30:00Z"),
                ),
                timeEntry(
                    id = "unestimated-actual",
                    taskId = unestimatedWithActual.id,
                    start = Instant.parse("2026-07-26T10:00:00Z"),
                    end = Instant.parse("2026-07-26T10:20:00Z"),
                ),
                timeEntry(
                    id = "previous-actual",
                    taskId = previous.id,
                    start = Instant.parse("2026-07-20T10:00:00Z"),
                    end = Instant.parse("2026-07-20T11:00:00Z"),
                ),
                timeEntry(
                    id = "open-actual",
                    taskId = open.id,
                    start = Instant.parse("2026-07-26T11:00:00Z"),
                    end = Instant.parse("2026-07-26T11:40:00Z"),
                ),
            ),
        )

        val result = calculate(workspace).estimateActual

        assertEquals(Duration.ofHours(3), result.estimated)
        assertEquals(2L, result.estimatedTaskCount)
        assertEquals(1L, result.unestimatedTaskCount)
        assertEquals(Duration.ofMinutes(50), result.actual.trusted)
        assertEquals(Duration.ZERO, result.actual.conflicted)
        assertEquals(2L, result.actualTaskCount)
    }

    @Test
    fun recordedTimeClipsBothIntervalBoundariesAndIgnoresActiveEntries() {
        val task = OpenTasksFixtures.tasks.first().copy(id = TaskId("clipped"))
        val workspace = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(task),
            timeEntries = listOf(
                timeEntry(
                    id = "crosses-start",
                    taskId = task.id,
                    start = Instant.parse("2026-07-20T23:00:00Z"),
                    end = Instant.parse("2026-07-21T01:00:00Z"),
                ),
                timeEntry(
                    id = "crosses-end",
                    taskId = task.id,
                    start = Instant.parse("2026-07-27T23:00:00Z"),
                    end = Instant.parse("2026-07-28T01:00:00Z"),
                ),
                timeEntry(
                    id = "starts-at-end",
                    taskId = task.id,
                    start = Instant.parse("2026-07-28T00:00:00Z"),
                    end = Instant.parse("2026-07-28T01:00:00Z"),
                ),
                TimeEntry(
                    id = TimeEntryId("active"),
                    taskId = task.id,
                    deviceId = DeviceId("test-device"),
                    startedAt = Instant.parse("2026-07-26T00:00:00Z"),
                    stoppedAt = null,
                ),
            ),
        )

        val result = calculate(workspace)

        assertEquals(
            DurationQuality(Duration.ofHours(2), Duration.ZERO),
            result.quality.recordedTime,
        )
        assertEquals(
            DurationQuality(Duration.ofHours(2), Duration.ZERO),
            result.projectTime.single().duration,
        )
    }

    @Test
    fun inboxAndEveryAssignedTagReceiveTimeWhileOverallQualityDeduplicatesEntries() {
        val deepWork = OpenTasksFixtures.tags.first { it.id == TagId("tag-deep-work") }
        val admin = OpenTasksFixtures.tags.first { it.id == TagId("tag-admin") }
        val inbox = OpenTasksFixtures.tasks.first().copy(
            id = TaskId("inbox"),
            projectId = null,
            tagIds = setOf(deepWork.id, admin.id),
        )
        val project = OpenTasksFixtures.tasks.first().copy(
            id = TaskId("project"),
            tagIds = setOf(deepWork.id),
        )
        val workspace = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(inbox, project),
            tags = listOf(deepWork, admin),
            timeEntries = listOf(
                timeEntry(
                    id = "inbox-time",
                    taskId = inbox.id,
                    start = Instant.parse("2026-07-25T10:00:00Z"),
                    end = Instant.parse("2026-07-25T11:00:00Z"),
                ),
                timeEntry(
                    id = "project-time",
                    taskId = project.id,
                    start = Instant.parse("2026-07-26T10:00:00Z"),
                    end = Instant.parse("2026-07-26T10:30:00Z"),
                ),
            ),
        )

        val result = calculate(workspace)
        val projects = result.projectTime.associateBy { it.projectId }
        val tags = result.tagTime.associateBy { it.tagId }

        assertEquals("Inbox", projects.getValue(null).displayName)
        assertEquals(Duration.ofHours(1), projects.getValue(null).duration.trusted)
        assertEquals(
            Duration.ofMinutes(30),
            projects.getValue(OpenTasksFixtures.studioProject.id).duration.trusted,
        )
        assertEquals(Duration.ofMinutes(90), tags.getValue(deepWork.id).duration.trusted)
        assertEquals(Duration.ofHours(1), tags.getValue(admin.id).duration.trusted)
        assertEquals(Duration.ofMinutes(90), result.quality.recordedTime.trusted)
    }

    @Test
    fun openMilestonesCarryTaskCountsAndExplicitProjectHealth() {
        val project = OpenTasksFixtures.taxProject.copy(status = ProjectHealth.AT_RISK)
        val milestone = Milestone(
            id = MilestoneId("open"),
            projectId = project.id,
            name = "Open milestone",
            dueDate = LocalDate.of(2026, 7, 31),
        )
        val completed = completedTask(
            id = "milestone-completed-task",
            completedAt = Instant.parse("2026-07-25T12:00:00Z"),
        ).copy(projectId = project.id, milestoneId = milestone.id)
        val overdue = overdueTask(
            id = "milestone-overdue-task",
            dueAt = Instant.parse("2026-07-26T12:00:00Z"),
        ).copy(projectId = project.id, milestoneId = milestone.id)
        val open = OpenTasksFixtures.tasks.first().copy(
            id = TaskId("milestone-open-task"),
            projectId = project.id,
            milestoneId = milestone.id,
            due = ZonedMoment(Instant.parse("2026-07-28T12:00:00Z"), "UTC"),
        )
        val workspace = OpenTasksFixtures.snapshot.copy(
            projects = listOf(project),
            milestones = listOf(milestone),
            tasks = listOf(completed, overdue, open),
        )

        val row = calculate(workspace).milestoneHealth.single()

        assertEquals(3L, row.totalTasks)
        assertEquals(1L, row.completedTasks)
        assertEquals(1L, row.overdueTasks)
        assertEquals(ProjectHealth.AT_RISK, row.projectHealth)
        assertEquals(Instant.parse("2026-07-31T00:00:00Z"), row.dueAt)
    }

    @Test
    fun milestoneRowsExcludeCompletedAndSortByDueInstantThenRootName() {
        val project = OpenTasksFixtures.studioProject
        val milestones = listOf(
            Milestone(
                id = MilestoneId("no-due"),
                projectId = project.id,
                name = "No due",
                dueDate = null,
            ),
            Milestone(
                id = MilestoneId("beta"),
                projectId = project.id,
                name = "Beta",
                dueDate = LocalDate.of(2026, 7, 31),
            ),
            Milestone(
                id = MilestoneId("alpha"),
                projectId = project.id,
                name = "alpha",
                dueDate = LocalDate.of(2026, 7, 31),
            ),
            Milestone(
                id = MilestoneId("earlier"),
                projectId = project.id,
                name = "Earlier",
                dueDate = LocalDate.of(2026, 7, 30),
            ),
            Milestone(
                id = MilestoneId("completed"),
                projectId = project.id,
                name = "Completed",
                dueDate = LocalDate.of(2026, 7, 29),
                completedAt = Instant.parse("2026-07-20T00:00:00Z"),
            ),
        )
        val workspace = OpenTasksFixtures.snapshot.copy(
            projects = listOf(project),
            milestones = milestones,
            tasks = emptyList(),
        )

        val result = calculate(workspace)

        assertEquals(
            listOf(
                MilestoneId("earlier"),
                MilestoneId("alpha"),
                MilestoneId("beta"),
                MilestoneId("no-due"),
            ),
            result.milestoneHealth.map { it.milestoneId },
        )
    }

    @Test
    fun conflictedEntriesStaySeparateAndOnlyAffectIncludedSortWhenRequested() {
        val alphaProject = OpenTasksFixtures.studioProject.copy(
            id = ProjectId("alpha-project"),
            name = "Alpha",
        )
        val betaProject = OpenTasksFixtures.taxProject.copy(
            id = ProjectId("beta-project"),
            name = "Beta",
        )
        val alphaTag = Tag(TagId("alpha-tag"), OpenTasksFixtures.workspaceId, "Alpha")
        val betaTag = Tag(TagId("beta-tag"), OpenTasksFixtures.workspaceId, "Beta")
        val alphaTask = OpenTasksFixtures.tasks.first().copy(
            id = TaskId("alpha-task"),
            projectId = alphaProject.id,
            tagIds = setOf(alphaTag.id),
        )
        val betaTask = OpenTasksFixtures.tasks.first().copy(
            id = TaskId("beta-task"),
            projectId = betaProject.id,
            tagIds = setOf(betaTag.id),
        )
        val alphaTrusted = timeEntry(
            id = "alpha-trusted",
            taskId = alphaTask.id,
            start = Instant.parse("2026-07-25T10:00:00Z"),
            end = Instant.parse("2026-07-25T11:00:00Z"),
        )
        val betaTrusted = timeEntry(
            id = "beta-trusted",
            taskId = betaTask.id,
            start = Instant.parse("2026-07-25T12:00:00Z"),
            end = Instant.parse("2026-07-25T12:30:00Z"),
        )
        val betaConflictFirst = timeEntry(
            id = "beta-conflict-first",
            taskId = betaTask.id,
            start = Instant.parse("2026-07-26T10:00:00Z"),
            end = Instant.parse("2026-07-26T11:00:00Z"),
        )
        val betaConflictSecond = timeEntry(
            id = "beta-conflict-second",
            taskId = betaTask.id,
            start = Instant.parse("2026-07-26T10:30:00Z"),
            end = Instant.parse("2026-07-26T11:30:00Z"),
        )
        val workspace = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(alphaTask, betaTask),
            projects = listOf(alphaProject, betaProject),
            tags = listOf(alphaTag, betaTag),
            timeEntries = listOf(
                alphaTrusted,
                betaTrusted,
                betaConflictFirst,
                betaConflictSecond,
            ),
            timeEntryConflicts = listOf(
                TimeEntryConflict(
                    firstEntryId = betaConflictFirst.id,
                    secondEntryId = betaConflictSecond.id,
                    overlap = Duration.ofMinutes(30),
                ),
            ),
        )

        val trustedOnly = calculate(workspace)
        val withConflicts = calculate(
            workspace,
            selection = InsightsSelection(includeConflictedTime = true),
        )
        val betaDuration = trustedOnly.projectTime
            .first { it.projectId == betaProject.id }
            .duration

        assertEquals(
            listOf(alphaProject.id, betaProject.id),
            trustedOnly.projectTime.map { it.projectId },
        )
        assertEquals(
            listOf(alphaTag.id, betaTag.id),
            trustedOnly.tagTime.map { it.tagId },
        )
        assertEquals(DurationQuality(Duration.ofMinutes(30), Duration.ofHours(2)), betaDuration)
        assertEquals(
            DurationQuality(Duration.ofMinutes(90), Duration.ofHours(2)),
            trustedOnly.quality.recordedTime,
        )
        assertEquals(
            listOf(betaProject.id, alphaProject.id),
            withConflicts.projectTime.map { it.projectId },
        )
        assertEquals(
            listOf(betaTag.id, alphaTag.id),
            withConflicts.tagTime.map { it.tagId },
        )
    }

    @Test
    fun includedDisplayTotalsAndActualTaskCountFollowConflictSelection() {
        val project = OpenTasksFixtures.studioProject
        val tag = OpenTasksFixtures.tags.first { it.id == TagId("tag-deep-work") }
        val trustedTask = completedTask(
            id = "trusted-task",
            completedAt = Instant.parse("2026-07-25T12:00:00Z"),
        ).copy(projectId = project.id, tagIds = setOf(tag.id))
        val conflictOnlyTask = completedTask(
            id = "conflict-only-task",
            completedAt = Instant.parse("2026-07-26T12:00:00Z"),
        ).copy(projectId = project.id, tagIds = setOf(tag.id))
        val trusted = timeEntry(
            id = "trusted",
            taskId = trustedTask.id,
            start = Instant.parse("2026-07-25T10:00:00Z"),
            end = Instant.parse("2026-07-25T10:30:00Z"),
        )
        val conflictFirst = timeEntry(
            id = "conflict-first",
            taskId = conflictOnlyTask.id,
            start = Instant.parse("2026-07-26T10:00:00Z"),
            end = Instant.parse("2026-07-26T10:20:00Z"),
        )
        val conflictSecond = timeEntry(
            id = "conflict-second",
            taskId = conflictOnlyTask.id,
            start = Instant.parse("2026-07-26T10:10:00Z"),
            end = Instant.parse("2026-07-26T10:50:00Z"),
        )
        val workspace = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(trustedTask, conflictOnlyTask),
            projects = listOf(project),
            tags = listOf(tag),
            timeEntries = listOf(trusted, conflictFirst, conflictSecond),
            timeEntryConflicts = listOf(
                TimeEntryConflict(
                    firstEntryId = conflictFirst.id,
                    secondEntryId = conflictSecond.id,
                    overlap = Duration.ofMinutes(10),
                ),
            ),
        )

        val trustedOnly = calculate(workspace)
        val withConflicts = calculate(
            workspace,
            selection = InsightsSelection(includeConflictedTime = true),
        )
        val trustedOnlyDisplayTotals = listOf(
            trustedOnly.quality.recordedTime,
            trustedOnly.projectTime.single().duration,
            trustedOnly.tagTime.single().duration,
            trustedOnly.estimateActual.actual,
        )
        val withConflictDisplayTotals = listOf(
            withConflicts.quality.recordedTime,
            withConflicts.projectTime.single().duration,
            withConflicts.tagTime.single().duration,
            withConflicts.estimateActual.actual,
        )

        trustedOnlyDisplayTotals.forEach { duration ->
            assertEquals(Duration.ofMinutes(30), duration.trusted)
            assertEquals(Duration.ofHours(1), duration.conflicted)
            assertEquals(Duration.ofMinutes(30), duration.included)
        }
        withConflictDisplayTotals.forEach { duration ->
            assertEquals(Duration.ofMinutes(30), duration.trusted)
            assertEquals(Duration.ofHours(1), duration.conflicted)
            assertEquals(Duration.ofMinutes(90), duration.included)
        }
        assertEquals(1L, trustedOnly.estimateActual.actualTaskCount)
        assertEquals(2L, withConflicts.estimateActual.actualTaskCount)
    }

    @Test
    fun unresolvedProjectAndTagDimensionsAreOmittedWithoutLosingQualifiedTime() {
        val knownProject = OpenTasksFixtures.studioProject
        val knownTag = OpenTasksFixtures.tags.first { it.id == TagId("tag-deep-work") }
        val missingProjectTask = OpenTasksFixtures.tasks.first().copy(
            id = TaskId("missing-project-task"),
            projectId = ProjectId("missing-project"),
            tagIds = setOf(knownTag.id),
        )
        val missingTagTask = OpenTasksFixtures.tasks.first().copy(
            id = TaskId("missing-tag-task"),
            projectId = knownProject.id,
            tagIds = setOf(TagId("missing-tag")),
        )
        val workspace = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(missingProjectTask, missingTagTask),
            projects = listOf(knownProject),
            tags = listOf(knownTag),
            timeEntries = listOf(
                timeEntry(
                    id = "missing-project-time",
                    taskId = missingProjectTask.id,
                    start = Instant.parse("2026-07-25T10:00:00Z"),
                    end = Instant.parse("2026-07-25T11:00:00Z"),
                ),
                timeEntry(
                    id = "missing-tag-time",
                    taskId = missingTagTask.id,
                    start = Instant.parse("2026-07-26T10:00:00Z"),
                    end = Instant.parse("2026-07-26T10:30:00Z"),
                ),
            ),
        )

        val result = calculate(workspace)

        assertEquals(Duration.ofMinutes(90), result.quality.recordedTime.trusted)
        assertEquals(listOf(knownProject.id), result.projectTime.map { it.projectId })
        assertEquals(Duration.ofMinutes(30), result.projectTime.single().duration.trusted)
        assertEquals(listOf(knownTag.id), result.tagTime.map { it.tagId })
        assertEquals(Duration.ofHours(1), result.tagTime.single().duration.trusted)
        assertFalse(result.projectTime.any { it.projectId == null })
    }

    @Test
    fun projectAndTagFiltersCombineAndDriveEveryMetricFromTheSameTaskSelection() {
        val projectOne = OpenTasksFixtures.studioProject.copy(id = ProjectId("project-one"))
        val projectTwo = OpenTasksFixtures.taxProject.copy(id = ProjectId("project-two"))
        val tagA = Tag(TagId("tag-a"), OpenTasksFixtures.workspaceId, "A")
        val tagB = Tag(TagId("tag-b"), OpenTasksFixtures.workspaceId, "B")
        val completedMatch = completedTask(
            id = "completed-match",
            completedAt = Instant.parse("2026-07-25T12:00:00Z"),
        ).copy(projectId = projectOne.id, tagIds = setOf(tagA.id))
        val overdueMatch = overdueTask(
            id = "overdue-match",
            dueAt = Instant.parse("2026-07-26T12:00:00Z"),
        ).copy(projectId = projectOne.id, tagIds = setOf(tagA.id))
        val wrongTag = completedTask(
            id = "wrong-tag",
            completedAt = Instant.parse("2026-07-25T12:00:00Z"),
        ).copy(projectId = projectOne.id, tagIds = setOf(tagB.id))
        val wrongProject = completedTask(
            id = "wrong-project",
            completedAt = Instant.parse("2026-07-25T12:00:00Z"),
        ).copy(projectId = projectTwo.id, tagIds = setOf(tagA.id))
        val workspace = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(completedMatch, overdueMatch, wrongTag, wrongProject),
            projects = listOf(projectOne, projectTwo),
            tags = listOf(tagA, tagB),
            timeEntries = listOf(
                timeEntry(
                    "completed-match-time",
                    completedMatch.id,
                    Instant.parse("2026-07-25T10:00:00Z"),
                    Instant.parse("2026-07-25T11:00:00Z"),
                ),
                timeEntry(
                    "overdue-match-time",
                    overdueMatch.id,
                    Instant.parse("2026-07-26T10:00:00Z"),
                    Instant.parse("2026-07-26T10:30:00Z"),
                ),
                timeEntry(
                    "wrong-tag-time",
                    wrongTag.id,
                    Instant.parse("2026-07-25T10:00:00Z"),
                    Instant.parse("2026-07-25T12:00:00Z"),
                ),
                timeEntry(
                    "wrong-project-time",
                    wrongProject.id,
                    Instant.parse("2026-07-25T10:00:00Z"),
                    Instant.parse("2026-07-25T13:00:00Z"),
                ),
            ),
        )

        val result = calculate(
            workspace,
            selection = InsightsSelection(
                projectIds = setOf(projectOne.id),
                tagIds = setOf(tagA.id),
            ),
        )

        assertEquals(1L, result.completed.current)
        assertEquals(listOf(overdueMatch.id), result.overdue.map { it.taskId })
        assertEquals(Duration.ofMinutes(90), result.quality.recordedTime.trusted)
        assertEquals(listOf(projectOne.id), result.projectTime.map { it.projectId })
        assertEquals(listOf(tagA.id), result.tagTime.map { it.tagId })
    }

    @Test
    fun emptyProjectAndTagSelectionsApplyNoConstraint() {
        val projectOne = OpenTasksFixtures.studioProject.copy(id = ProjectId("project-one"))
        val projectTwo = OpenTasksFixtures.taxProject.copy(id = ProjectId("project-two"))
        val tagA = Tag(TagId("tag-a"), OpenTasksFixtures.workspaceId, "A")
        val tagB = Tag(TagId("tag-b"), OpenTasksFixtures.workspaceId, "B")
        val first = completedTask(
            id = "first",
            completedAt = Instant.parse("2026-07-25T12:00:00Z"),
        ).copy(projectId = projectOne.id, tagIds = setOf(tagA.id))
        val second = completedTask(
            id = "second",
            completedAt = Instant.parse("2026-07-26T12:00:00Z"),
        ).copy(projectId = projectTwo.id, tagIds = setOf(tagB.id))
        val workspace = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(first, second),
            projects = listOf(projectOne, projectTwo),
            tags = listOf(tagA, tagB),
            timeEntries = listOf(
                timeEntry(
                    "first-time",
                    first.id,
                    Instant.parse("2026-07-25T10:00:00Z"),
                    Instant.parse("2026-07-25T11:00:00Z"),
                ),
                timeEntry(
                    "second-time",
                    second.id,
                    Instant.parse("2026-07-26T10:00:00Z"),
                    Instant.parse("2026-07-26T12:00:00Z"),
                ),
            ),
        )

        val result = calculate(
            workspace,
            selection = InsightsSelection(
                projectIds = emptySet(),
                tagIds = emptySet(),
            ),
        )

        assertEquals(2L, result.completed.current)
        assertEquals(Duration.ofHours(3), result.quality.recordedTime.trusted)
        assertEquals(
            setOf(projectOne.id, projectTwo.id),
            result.projectTime.map { it.projectId }.toSet(),
        )
        assertEquals(setOf(tagA.id, tagB.id), result.tagTime.map { it.tagId }.toSet())
    }

    @Test
    fun completionTrendContainsEverySelectedLocalDay() {
        val now = Instant.parse("2026-08-10T12:00:00Z")
        InsightsRange.entries.forEach { range ->
            val snapshot = engine.calculate(
                OpenTasksFixtures.snapshot.copy(tasks = emptyList()),
                InsightsSelection(range = range),
                now,
                ZoneId.of("UTC"),
            )
            assertEquals(range.dayCount.toInt(), snapshot.completionTrend.size)
            assertEquals(LocalDate.of(2026, 8, 10), snapshot.completionTrend.last().date)
            assertTrue(snapshot.completionTrend.zipWithNext().all { (a, b) ->
                b.date == a.date.plusDays(1)
            })
            assertTrue(snapshot.completionTrend.all { it.completed == 0L })
        }
    }

    @Test
    fun completionTrendUsesHalfOpenZoneAwareDaysAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 3, 9, 12, 0, 0, 0, zone).toInstant()
        val start = LocalDate.of(2026, 3, 3).atStartOfDay(zone).toInstant()
        val end = LocalDate.of(2026, 3, 10).atStartOfDay(zone).toInstant()
        val moments = listOf(
            start.minusNanos(1),
            start,
            LocalDate.of(2026, 3, 8).atTime(23, 30).atZone(zone).toInstant(),
            end.minusNanos(1),
            end,
        )
        val tasks = moments.mapIndexed { index, instant ->
            OpenTasksFixtures.tasks.first().copy(
                id = TaskId("trend-$index"),
                completedAt = instant,
            )
        }
        val trend = engine.calculate(
            OpenTasksFixtures.snapshot.copy(tasks = tasks),
            InsightsSelection(InsightsRange.SEVEN_DAYS),
            now,
            zone,
        ).completionTrend

        assertEquals(3L, trend.sumOf { it.completed })
        assertEquals(1L, trend.single { it.date == LocalDate.of(2026, 3, 8) }.completed)
        assertEquals(1L, trend.first().completed)
        assertEquals(1L, trend.last().completed)
    }

    @Test
    fun completionTrendMatchesFilteredCompletedCountIncludingBinHistory() {
        val now = Instant.parse("2026-08-10T12:00:00Z")
        val projectId = OpenTasksFixtures.studioProject.id
        val otherProjectId = OpenTasksFixtures.taxProject.id
        val tagId = TagId("tag-deep-work")
        val otherTagId = TagId("tag-admin")
        val matching = completedTask(
            id = "matching",
            completedAt = Instant.parse("2026-08-04T00:00:00Z"),
        ).copy(projectId = projectId, tagIds = setOf(tagId))
        val matchingDeleted = completedTask(
            id = "matching-deleted",
            completedAt = Instant.parse("2026-08-10T11:00:00Z"),
        ).copy(
            projectId = projectId,
            tagIds = setOf(tagId),
            deletedAt = Instant.parse("2026-08-10T11:30:00Z"),
        )
        val wrongProject = completedTask(
            id = "wrong-project",
            completedAt = Instant.parse("2026-08-05T12:00:00Z"),
        ).copy(projectId = otherProjectId, tagIds = setOf(tagId))
        val wrongTag = completedTask(
            id = "wrong-tag",
            completedAt = Instant.parse("2026-08-06T12:00:00Z"),
        ).copy(projectId = projectId, tagIds = setOf(otherTagId))
        val outOfRange = completedTask(
            id = "out-of-range",
            completedAt = Instant.parse("2026-08-03T23:59:59.999999999Z"),
        ).copy(projectId = projectId, tagIds = setOf(tagId))

        val snapshot = engine.calculate(
            OpenTasksFixtures.snapshot.copy(
                tasks = listOf(
                    matching,
                    matchingDeleted,
                    wrongProject,
                    wrongTag,
                    outOfRange,
                ),
            ),
            InsightsSelection(
                range = InsightsRange.SEVEN_DAYS,
                projectIds = setOf(projectId),
                tagIds = setOf(tagId),
            ),
            now,
            ZoneId.of("UTC"),
        )

        assertEquals(2L, snapshot.completed.current)
        assertEquals(2L, snapshot.completionTrend.sumOf { it.completed })
    }

    private fun calculate(
        workspace: WorkspaceSnapshot,
        selection: InsightsSelection = InsightsSelection(),
    ) = engine.calculate(
        workspace = workspace,
        selection = selection,
        now = Instant.parse("2026-07-27T12:00:00Z"),
        zoneId = ZoneId.of("UTC"),
    )

    private fun completedTask(
        id: String,
        completedAt: Instant,
        estimate: Duration? = null,
    ) = OpenTasksFixtures.tasks.first().copy(
        id = TaskId(id),
        semanticStatus = SemanticStatus.COMPLETED,
        completedAt = completedAt,
        estimate = estimate,
        deletedAt = null,
    )

    private fun overdueTask(
        id: String,
        dueAt: Instant,
    ) = OpenTasksFixtures.tasks.first().copy(
        id = TaskId(id),
        semanticStatus = SemanticStatus.PLANNED,
        completedAt = null,
        due = ZonedMoment(dueAt, "UTC"),
        deletedAt = null,
    )

    private fun timeEntry(
        id: String,
        taskId: TaskId,
        start: Instant,
        end: Instant,
    ) = TimeEntry(
        id = TimeEntryId(id),
        taskId = taskId,
        deviceId = DeviceId("test-device"),
        startedAt = start,
        stoppedAt = end,
    )
}
