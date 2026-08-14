package app.opentasks.core.domain

import app.opentasks.core.model.Milestone
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.PROJECT_TIMELINE_DAY_COUNT
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.ProjectTimelineDependencyRole
import app.opentasks.core.model.ProjectTimelineMarkerKind
import app.opentasks.core.model.ProjectTimelineTaskPlacement
import app.opentasks.core.model.ProjectTimelineWindow
import app.opentasks.core.model.ProjectTimelineWindowSide
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.ZonedMoment
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectTimelineProjectionTest {
    private val projectId = OpenTasksFixtures.studioProject.id
    private val otherProjectId = OpenTasksFixtures.taxProject.id
    private val window = ProjectTimelineWindow(LocalDate.of(2026, 8, 3))

    @Test
    fun windowIsMondayAlignedAndAlwaysContainsExactlyTwelveWeeks() {
        assertEquals(84, PROJECT_TIMELINE_DAY_COUNT)
        assertEquals(LocalDate.of(2026, 10, 25), window.lastDate)
        assertEquals(84, ChronoUnit.DAYS.between(window.firstDate, window.lastDate).toInt() + 1)

        try {
            ProjectTimelineWindow(LocalDate.of(2026, 8, 2))
            fail("A timeline window must start on Monday")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun projectFilteringKeepsCompletedAndExcludesBinAndOtherProjects() {
        val open = task("open")
        val completed = task("completed").copy(semanticStatus = SemanticStatus.COMPLETED)
        val binned = task("binned").copy(deletedAt = Instant.parse("2026-08-01T00:00:00Z"))
        val other = task("other", otherProjectId)

        val rows = project(open, completed, binned, other).taskRows

        assertEquals(listOf(open.id, completed.id), rows.map { it.task.id })
        assertEquals(listOf(open, completed), rows.map { it.task })
    }

    @Test
    fun storedZoneDatesDriveInclusiveSpansAndStartDueMarkers() {
        val span = task(
            "span",
            start = moment("2026-08-04T00:30:00Z", "America/Los_Angeles"),
            due = moment("2026-08-05T18:00:00Z", "Asia/Bangkok"),
        )
        val startOnly = task(
            "start-only",
            start = moment("2026-08-04T00:30:00Z", "America/Los_Angeles"),
        )
        val dueOnly = task(
            "due-only",
            due = moment("2026-08-05T18:00:00Z", "Asia/Bangkok"),
        )
        val unscheduled = task("unscheduled")

        val rows = project(span, startOnly, dueOnly, unscheduled).taskRows.associateBy { it.task.id }

        assertEquals(LocalDate.of(2026, 8, 3), rows.getValue(span.id).startDate)
        assertEquals(LocalDate.of(2026, 8, 6), rows.getValue(span.id).dueDate)
        assertEquals(ProjectTimelineTaskPlacement.Span(0, 3, 4, false, false), rows.getValue(span.id).placement)
        assertEquals(ProjectTimelineTaskPlacement.Marker(0, ProjectTimelineMarkerKind.START), rows.getValue(startOnly.id).placement)
        assertEquals(ProjectTimelineTaskPlacement.Marker(3, ProjectTimelineMarkerKind.DUE), rows.getValue(dueOnly.id).placement)
        assertEquals(ProjectTimelineTaskPlacement.Unscheduled, rows.getValue(unscheduled.id).placement)
    }

    @Test
    fun spansClipAtEitherOrBothEdgesAndOutsideDatesStayOutside() {
        val clipsBefore = task("clips-before", start = utc("2026-07-31T12:00:00Z"), due = utc("2026-08-04T12:00:00Z"))
        val clipsAfter = task("clips-after", start = utc("2026-10-24T12:00:00Z"), due = utc("2026-10-28T12:00:00Z"))
        val clipsBoth = task("clips-both", start = utc("2026-07-31T12:00:00Z"), due = utc("2026-10-28T12:00:00Z"))
        val spanBefore = task("span-before", start = utc("2026-07-30T12:00:00Z"), due = utc("2026-08-01T12:00:00Z"))
        val spanAfter = task("span-after", start = utc("2026-10-26T12:00:00Z"), due = utc("2026-10-27T12:00:00Z"))
        val markerBefore = task("marker-before", start = utc("2026-08-02T12:00:00Z"))
        val markerAfter = task("marker-after", due = utc("2026-10-26T12:00:00Z"))

        val rows = project(
            clipsBefore,
            clipsAfter,
            clipsBoth,
            spanBefore,
            spanAfter,
            markerBefore,
            markerAfter,
        ).taskRows.associateBy { it.task.id }

        assertEquals(ProjectTimelineTaskPlacement.Span(0, 1, 5, true, false), rows.getValue(clipsBefore.id).placement)
        assertEquals(ProjectTimelineTaskPlacement.Span(82, 83, 5, false, true), rows.getValue(clipsAfter.id).placement)
        assertEquals(ProjectTimelineTaskPlacement.Span(0, 83, 90, true, true), rows.getValue(clipsBoth.id).placement)
        assertEquals(ProjectTimelineTaskPlacement.Outside(ProjectTimelineWindowSide.BEFORE), rows.getValue(spanBefore.id).placement)
        assertEquals(ProjectTimelineTaskPlacement.Outside(ProjectTimelineWindowSide.AFTER), rows.getValue(spanAfter.id).placement)
        assertEquals(ProjectTimelineTaskPlacement.Outside(ProjectTimelineWindowSide.BEFORE), rows.getValue(markerBefore.id).placement)
        assertEquals(ProjectTimelineTaskPlacement.Outside(ProjectTimelineWindowSide.AFTER), rows.getValue(markerAfter.id).placement)
    }

    @Test
    fun instantOrLocalDateReversalProducesInvalidRange() {
        val reversedInstant = task(
            "reversed-instant",
            start = utc("2026-08-05T10:00:00Z"),
            due = utc("2026-08-05T09:00:00Z"),
        )
        val reversedStoredDate = task(
            "reversed-date",
            start = moment("2026-08-05T23:30:00Z", "Asia/Tokyo"),
            due = moment("2026-08-06T00:00:00Z", "America/Los_Angeles"),
        )

        val rows = project(reversedInstant, reversedStoredDate).taskRows.associateBy { it.task.id }

        assertEquals(ProjectTimelineTaskPlacement.InvalidRange, rows.getValue(reversedInstant.id).placement)
        assertEquals(ProjectTimelineTaskPlacement.InvalidRange, rows.getValue(reversedStoredDate.id).placement)
    }

    @Test
    fun milestonesUseInclusiveEdgesAndExactDatedBeforeAfterCounts() {
        val first = milestone("first", window.firstDate)
        val last = milestone("last", window.lastDate)
        val before = milestone("before", window.firstDate.minusDays(1))
        val after = milestone("after", window.lastDate.plusDays(1))
        val undated = milestone("undated", null)
        val other = milestone("other", window.firstDate, otherProjectId)
        val milestones = listOf(first, last, before, after, undated, other)

        val projection = computeProjectTimelineProjection(
            OpenTasksFixtures.snapshot.copy(tasks = emptyList(), milestones = milestones),
            projectId,
            window,
        )

        assertEquals(listOf(first.id to 0, last.id to 83), projection.milestoneMarkers.map { it.milestone.id to it.dayIndex })
        assertEquals(1, projection.milestonesBeforeWindow)
        assertEquals(1, projection.milestonesAfterWindow)
        assertTrue(undated in milestones)
        assertNull(milestones.single { it.id == undated.id }.dueDate)
    }

    @Test
    fun dependencyContextTraversesBothDirectionsAcrossProjectsUniquely() {
        val prerequisiteRoot = task("prerequisite-root")
        val external = task("external", otherProjectId)
        val prerequisiteLeft = task("prerequisite-left", dependencyIds = setOf(prerequisiteRoot.id, external.id))
        val prerequisiteRight = task("prerequisite-right", dependencyIds = setOf(prerequisiteRoot.id, external.id))
        val selected = task("selected", dependencyIds = setOf(prerequisiteLeft.id, prerequisiteRight.id))
        val dependantLeft = task("dependant-left", dependencyIds = setOf(selected.id))
        val dependantRight = task("dependant-right", dependencyIds = setOf(selected.id))
        val dependantLeaf = task("dependant-leaf", dependencyIds = setOf(dependantLeft.id, dependantRight.id))
        val unrelated = task("unrelated")
        val tasks = listOf(
            prerequisiteRoot,
            external,
            prerequisiteLeft,
            prerequisiteRight,
            selected,
            dependantLeft,
            dependantRight,
            dependantLeaf,
            unrelated,
        )

        val projection = project(*tasks.toTypedArray(), selectedTaskId = selected.id)
        val roles = projection.taskRows.associate { it.task.id to it.dependencyRole }

        assertEquals(ProjectTimelineDependencyRole.SELECTED, roles.getValue(selected.id))
        assertEquals(ProjectTimelineDependencyRole.PREREQUISITE, roles.getValue(prerequisiteRoot.id))
        assertEquals(ProjectTimelineDependencyRole.PREREQUISITE, roles.getValue(prerequisiteLeft.id))
        assertEquals(ProjectTimelineDependencyRole.PREREQUISITE, roles.getValue(prerequisiteRight.id))
        assertEquals(ProjectTimelineDependencyRole.DEPENDANT, roles.getValue(dependantLeft.id))
        assertEquals(ProjectTimelineDependencyRole.DEPENDANT, roles.getValue(dependantRight.id))
        assertEquals(ProjectTimelineDependencyRole.DEPENDANT, roles.getValue(dependantLeaf.id))
        assertEquals(ProjectTimelineDependencyRole.NONE, roles.getValue(unrelated.id))
        assertEquals(1, projection.outOfProjectDependencyTaskCount)
        assertEquals(selected.id, projection.selectedTaskId)
    }

    @Test(timeout = 1_000)
    fun dependencyContextTerminatesOnDefensiveCycleAtSnapshotTaskBound() {
        val selected = task("selected")
        val first = task("first", dependencyIds = setOf(selected.id))
        val second = task("second", dependencyIds = setOf(first.id))
        val cycle = selected.copy(dependencyIds = setOf(second.id))

        val projection = project(cycle, first, second, selectedTaskId = selected.id)
        val roles = projection.taskRows.associate { it.task.id to it.dependencyRole }

        assertEquals(ProjectTimelineDependencyRole.SELECTED, roles.getValue(selected.id))
        assertEquals(ProjectTimelineDependencyRole.PREREQUISITE_AND_DEPENDANT, roles.getValue(first.id))
        assertEquals(ProjectTimelineDependencyRole.PREREQUISITE_AND_DEPENDANT, roles.getValue(second.id))
        assertEquals(0, projection.outOfProjectDependencyTaskCount)
    }

    @Test
    fun missingOrOutOfProjectSelectionProducesNoDependencyContext() {
        val target = task("target", dependencyIds = setOf(TaskId("external")))
        val external = task("external", otherProjectId)
        val missing = project(target, external, selectedTaskId = TaskId("missing"))
        val outOfProject = project(target, external, selectedTaskId = external.id)

        listOf(missing, outOfProject).forEach { projection ->
            assertNull(projection.selectedTaskId)
            assertEquals(0, projection.outOfProjectDependencyTaskCount)
            assertTrue(projection.taskRows.all { it.dependencyRole == ProjectTimelineDependencyRole.NONE })
        }
    }

    private fun project(
        vararg tasks: Task,
        selectedTaskId: TaskId? = null,
    ) = computeProjectTimelineProjection(
        OpenTasksFixtures.snapshot.copy(tasks = tasks.toList(), milestones = emptyList()),
        projectId,
        window,
        selectedTaskId,
    )

    private fun task(
        id: String,
        projectId: ProjectId = this.projectId,
        start: ZonedMoment? = null,
        due: ZonedMoment? = null,
        dependencyIds: Set<TaskId> = emptySet(),
    ) = OpenTasksFixtures.tasks.first().copy(
        id = TaskId(id),
        projectId = projectId,
        title = id,
        start = start,
        due = due,
        dependencyIds = dependencyIds,
        blockedBy = emptySet(),
        completedAt = null,
        deletedAt = null,
    )

    private fun milestone(
        id: String,
        dueDate: LocalDate?,
        projectId: ProjectId = this.projectId,
    ) = Milestone(MilestoneId(id), projectId, id, dueDate)

    private fun utc(value: String) = moment(value, "UTC")

    private fun moment(value: String, zone: String) = ZonedMoment(Instant.parse(value), zone)
}
