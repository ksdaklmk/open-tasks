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
