package app.opentasks.feature.projects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.Milestone
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ProjectPresentation
import app.opentasks.core.model.ProjectTimelineDependencyRole
import app.opentasks.core.model.ProjectTimelineMarkerKind
import app.opentasks.core.model.ProjectTimelineMilestoneMarker
import app.opentasks.core.model.ProjectTimelineProjection
import app.opentasks.core.model.ProjectTimelineTaskPlacement
import app.opentasks.core.model.ProjectTimelineTaskRow
import app.opentasks.core.model.ProjectTimelineWindow
import app.opentasks.core.model.ProjectTimelineWindowSide
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectTimelineViewInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val project = OpenTasksFixtures.researchProject
    // `task-interviews` (the first research-project fixture) is BLOCKED with
    // a non-empty `blockedBy`; both are cleared here so tests that don't
    // deliberately exercise blocked/completed state get a clean baseline.
    private val baseTask = OpenTasksFixtures.tasks
        .first { it.projectId == project.id }
        .copy(
            completedAt = null,
            deletedAt = null,
            blockedBy = emptySet(),
            semanticStatus = SemanticStatus.PLANNED,
        )
    private val window = ProjectTimelineWindow(LocalDate.of(2026, 8, 3))

    private fun projectionOf(
        taskRows: List<ProjectTimelineTaskRow>,
        milestoneMarkers: List<ProjectTimelineMilestoneMarker> = emptyList(),
        milestonesBeforeWindow: Int = 0,
        milestonesAfterWindow: Int = 0,
        selectedTaskId: TaskId? = null,
    ) = ProjectTimelineProjection(
        projectId = project.id,
        window = window,
        taskRows = taskRows,
        milestoneMarkers = milestoneMarkers,
        milestonesBeforeWindow = milestonesBeforeWindow,
        milestonesAfterWindow = milestonesAfterWindow,
        selectedTaskId = selectedTaskId,
        outOfProjectDependencyTaskCount = 0,
    )

    @Test
    fun thirdPresentationAndBoundedNavigationEmitStatelessCallbacks() {
        val previousCount = AtomicInteger(0)
        val todayCount = AtomicInteger(0)
        val nextCount = AtomicInteger(0)
        val row = ProjectTimelineTaskRow(
            task = baseTask,
            startDate = null,
            dueDate = null,
            placement = ProjectTimelineTaskPlacement.Unscheduled,
            dependencyRole = ProjectTimelineDependencyRole.NONE,
        )
        val projection = projectionOf(listOf(row))

        composeRule.setContent {
            var presentation by remember { mutableStateOf(ProjectPresentation.LIST) }
            OpenTasksTheme {
                ProjectsScreen(
                    projects = OpenTasksFixtures.snapshot.projects,
                    tasks = OpenTasksFixtures.snapshot.tasks,
                    milestones = OpenTasksFixtures.snapshot.milestones,
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    selectedProjectId = project.id,
                    showDetailPane = false,
                    presentation = presentation,
                    timelineProjection = projection,
                    onPresentationChange = { presentation = it },
                    onTimelinePrevious = { previousCount.incrementAndGet() },
                    onTimelineToday = { todayCount.incrementAndGet() },
                    onTimelineNext = { nextCount.incrementAndGet() },
                    onSelectProject = {},
                    onCloseDetail = {},
                    onUpdateProject = { _, _ -> },
                    onArchiveProject = {},
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("workbench-view-timeline"))
        composeRule.onNodeWithTag("workbench-view-timeline").performClick()

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("timeline-previous"))

        listOf("timeline-previous", "timeline-today", "timeline-next").forEach { tag ->
            composeRule.onNodeWithTag(tag)
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }

        composeRule.onNodeWithTag("timeline-previous").performClick()
        composeRule.onNodeWithTag("timeline-today").performClick()
        composeRule.onNodeWithTag("timeline-next").performClick()

        assertEquals(1, previousCount.get())
        assertEquals(1, todayCount.get())
        assertEquals(1, nextCount.get())
    }

    @Test
    fun spansMarkersClippingAndInvalidRangesExposeMergedNonColourSemantics() {
        val spanTask = baseTask.copy(
            id = TaskId("timeline-span"),
            title = "Design and build",
            semanticStatus = SemanticStatus.STARTED,
        )
        val startMarkerTask = baseTask.copy(
            id = TaskId("timeline-start-marker"),
            title = "Kickoff call",
            semanticStatus = SemanticStatus.PLANNED,
        )
        val dueMarkerTask = baseTask.copy(
            id = TaskId("timeline-due-marker"),
            title = "Ship checklist",
            semanticStatus = SemanticStatus.PLANNED,
        )
        val invalidTask = baseTask.copy(
            id = TaskId("timeline-invalid"),
            title = "Broken range",
            semanticStatus = SemanticStatus.PLANNED,
        )
        val outsideTask = baseTask.copy(
            id = TaskId("timeline-outside"),
            title = "Earlier follow-up",
            semanticStatus = SemanticStatus.PLANNED,
        )

        val spanStart = LocalDate.of(2026, 7, 20)
        val spanDue = LocalDate.of(2026, 10, 30)
        val startMarkerDate = LocalDate.of(2026, 8, 5)
        val dueMarkerDate = LocalDate.of(2026, 8, 12)
        val invalidStart = LocalDate.of(2026, 8, 20)
        val invalidDue = LocalDate.of(2026, 8, 10)
        val outsideDue = LocalDate.of(2026, 6, 1)

        val rows = listOf(
            ProjectTimelineTaskRow(
                task = spanTask,
                startDate = spanStart,
                dueDate = spanDue,
                placement = ProjectTimelineTaskPlacement.Span(
                    firstVisibleDayIndex = 0,
                    lastVisibleDayIndex = 83,
                    totalDayCount = 103,
                    continuesBefore = true,
                    continuesAfter = true,
                ),
                dependencyRole = ProjectTimelineDependencyRole.NONE,
            ),
            ProjectTimelineTaskRow(
                task = startMarkerTask,
                startDate = startMarkerDate,
                dueDate = null,
                placement = ProjectTimelineTaskPlacement.Marker(
                    dayIndex = 2,
                    kind = ProjectTimelineMarkerKind.START,
                ),
                dependencyRole = ProjectTimelineDependencyRole.NONE,
            ),
            ProjectTimelineTaskRow(
                task = dueMarkerTask,
                startDate = null,
                dueDate = dueMarkerDate,
                placement = ProjectTimelineTaskPlacement.Marker(
                    dayIndex = 9,
                    kind = ProjectTimelineMarkerKind.DUE,
                ),
                dependencyRole = ProjectTimelineDependencyRole.NONE,
            ),
            ProjectTimelineTaskRow(
                task = invalidTask,
                startDate = invalidStart,
                dueDate = invalidDue,
                placement = ProjectTimelineTaskPlacement.InvalidRange,
                dependencyRole = ProjectTimelineDependencyRole.NONE,
            ),
            ProjectTimelineTaskRow(
                task = outsideTask,
                startDate = null,
                dueDate = outsideDue,
                placement = ProjectTimelineTaskPlacement.Outside(ProjectTimelineWindowSide.BEFORE),
                dependencyRole = ProjectTimelineDependencyRole.NONE,
            ),
        )
        val projection = projectionOf(rows)

        composeRule.setContent {
            OpenTasksTheme {
                ProjectTimelineView(
                    projection = projection,
                    onPrevious = {},
                    onToday = {},
                    onNext = {},
                    onTaskSelectionChange = {},
                    onOpenTask = {},
                    onOpenMilestone = {},
                )
            }
        }

        val dateFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.UK)
        val duration = context.resources.getQuantityString(
            R.plurals.timeline_row_duration_days,
            103,
            103,
        )
        composeRule.onNodeWithTag("timeline-task-row-${spanTask.id.value}").assertContentDescriptionEquals(
            listOf(
                spanTask.title,
                context.getString(R.string.timeline_row_starts, spanStart.format(dateFormat)),
                context.getString(R.string.timeline_row_due, spanDue.format(dateFormat)),
                duration,
                context.getString(R.string.timeline_row_continues_before),
                context.getString(R.string.timeline_row_continues_after),
                context.getString(R.string.timeline_row_not_completed),
                context.getString(R.string.timeline_row_not_blocked),
            ).joinToString(". "),
        )

        composeRule.onNodeWithTag("timeline-task-row-${startMarkerTask.id.value}")
            .assertContentDescriptionEquals(
                listOf(
                    startMarkerTask.title,
                    context.getString(R.string.timeline_row_starts, startMarkerDate.format(dateFormat)),
                    context.getString(R.string.timeline_row_not_completed),
                    context.getString(R.string.timeline_row_not_blocked),
                ).joinToString(". "),
            )

        composeRule.onNodeWithTag("timeline-task-row-${dueMarkerTask.id.value}")
            .assertContentDescriptionEquals(
                listOf(
                    dueMarkerTask.title,
                    context.getString(R.string.timeline_row_due, dueMarkerDate.format(dateFormat)),
                    context.getString(R.string.timeline_row_not_completed),
                    context.getString(R.string.timeline_row_not_blocked),
                ).joinToString(". "),
            )

        composeRule.onNodeWithTag("timeline-task-row-${invalidTask.id.value}")
            .assertContentDescriptionEquals(
                listOf(
                    invalidTask.title,
                    context.getString(R.string.timeline_row_starts, invalidStart.format(dateFormat)),
                    context.getString(R.string.timeline_row_due, invalidDue.format(dateFormat)),
                    context.getString(R.string.timeline_row_invalid),
                    context.getString(R.string.timeline_row_not_completed),
                    context.getString(R.string.timeline_row_not_blocked),
                ).joinToString(". "),
            )

        composeRule.onNodeWithTag("timeline-task-row-${outsideTask.id.value}")
            .assertContentDescriptionEquals(
                listOf(
                    outsideTask.title,
                    context.getString(R.string.timeline_row_due, outsideDue.format(dateFormat)),
                    context.getString(R.string.timeline_row_outside_before),
                    context.getString(R.string.timeline_row_not_completed),
                    context.getString(R.string.timeline_row_not_blocked),
                ).joinToString(". "),
            )

        // Decorative dots/tracks/chevrons/icons never surface their own
        // announcement: each `assertContentDescriptionEquals` call above is
        // an exact match, so if any decorative child had leaked its own
        // description into the merged row, these assertions would already
        // have failed on the extra text.
    }

    @Test
    fun rowSelectionHighlightsChainWhileSeparateOpenActionOpensTask() {
        val selected = baseTask.copy(id = TaskId("timeline-selected"), title = "Selected task")
        val prerequisite = baseTask.copy(id = TaskId("timeline-prereq"), title = "Prerequisite task")
        val dependant = baseTask.copy(id = TaskId("timeline-dependant"), title = "Dependant task")
        val both = baseTask.copy(id = TaskId("timeline-both"), title = "Both roles task")
        val unrelated = baseTask.copy(id = TaskId("timeline-unrelated"), title = "Unrelated task")

        fun rowFor(task: Task, role: ProjectTimelineDependencyRole) = ProjectTimelineTaskRow(
            task = task,
            startDate = null,
            dueDate = null,
            placement = ProjectTimelineTaskPlacement.Unscheduled,
            dependencyRole = role,
        )

        val projection = projectionOf(
            listOf(
                rowFor(selected, ProjectTimelineDependencyRole.SELECTED),
                rowFor(prerequisite, ProjectTimelineDependencyRole.PREREQUISITE),
                rowFor(dependant, ProjectTimelineDependencyRole.DEPENDANT),
                rowFor(both, ProjectTimelineDependencyRole.PREREQUISITE_AND_DEPENDANT),
                rowFor(unrelated, ProjectTimelineDependencyRole.NONE),
            ),
            selectedTaskId = selected.id,
        )
        val selectedResult = AtomicReference<TaskId?>()
        val openedResult = AtomicReference<TaskId?>()

        composeRule.setContent {
            OpenTasksTheme {
                ProjectTimelineView(
                    projection = projection,
                    onPrevious = {},
                    onToday = {},
                    onNext = {},
                    onTaskSelectionChange = selectedResult::set,
                    onOpenTask = openedResult::set,
                    onOpenMilestone = {},
                )
            }
        }

        composeRule.onNodeWithTag("timeline-task-row-${selected.id.value}")
            .assertContentDescriptionEquals(
                listOf(
                    selected.title,
                    context.getString(R.string.timeline_row_unscheduled),
                    context.getString(R.string.timeline_row_not_completed),
                    context.getString(R.string.timeline_row_not_blocked),
                    context.getString(R.string.timeline_row_role_selected),
                ).joinToString(". "),
            )
        composeRule.onNodeWithTag("timeline-task-row-${prerequisite.id.value}")
            .assertContentDescriptionEquals(
                listOf(
                    prerequisite.title,
                    context.getString(R.string.timeline_row_unscheduled),
                    context.getString(R.string.timeline_row_not_completed),
                    context.getString(R.string.timeline_row_not_blocked),
                    context.getString(R.string.timeline_row_role_prerequisite),
                ).joinToString(". "),
            )
        composeRule.onNodeWithTag("timeline-task-row-${dependant.id.value}")
            .assertContentDescriptionEquals(
                listOf(
                    dependant.title,
                    context.getString(R.string.timeline_row_unscheduled),
                    context.getString(R.string.timeline_row_not_completed),
                    context.getString(R.string.timeline_row_not_blocked),
                    context.getString(R.string.timeline_row_role_dependant),
                ).joinToString(". "),
            )
        composeRule.onNodeWithTag("timeline-task-row-${both.id.value}")
            .assertContentDescriptionEquals(
                listOf(
                    both.title,
                    context.getString(R.string.timeline_row_unscheduled),
                    context.getString(R.string.timeline_row_not_completed),
                    context.getString(R.string.timeline_row_not_blocked),
                    context.getString(R.string.timeline_row_role_prerequisite_and_dependant),
                ).joinToString(". "),
            )
        composeRule.onNodeWithTag("timeline-task-row-${unrelated.id.value}")
            .assertContentDescriptionEquals(
                listOf(
                    unrelated.title,
                    context.getString(R.string.timeline_row_unscheduled),
                    context.getString(R.string.timeline_row_not_completed),
                    context.getString(R.string.timeline_row_not_blocked),
                ).joinToString(". "),
            )

        composeRule.onNodeWithTag("timeline-task-row-${prerequisite.id.value}").performClick()
        assertEquals(prerequisite.id, selectedResult.get())
        assertNull(openedResult.get())

        composeRule.onNodeWithTag("timeline-open-task-${prerequisite.id.value}")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        composeRule.onNodeWithTag("timeline-open-task-${prerequisite.id.value}").performClick()
        assertEquals(prerequisite.id, openedResult.get())
        assertEquals(prerequisite.id, selectedResult.get())
    }

    @Test
    fun milestoneDiamondsCountsAndActivationUseExistingEditor() {
        val openMilestone = Milestone(
            id = MilestoneId("timeline-milestone-open"),
            projectId = project.id,
            name = "Beta review",
            dueDate = LocalDate.of(2026, 8, 10),
        )
        val completeMilestone = Milestone(
            id = MilestoneId("timeline-milestone-complete"),
            projectId = project.id,
            name = "Kickoff",
            dueDate = LocalDate.of(2026, 8, 5),
            completedAt = Instant.parse("2026-08-05T09:00:00Z"),
        )
        val projection = projectionOf(
            taskRows = emptyList(),
            milestoneMarkers = listOf(
                ProjectTimelineMilestoneMarker(openMilestone, dayIndex = 7),
                ProjectTimelineMilestoneMarker(completeMilestone, dayIndex = 2),
            ),
            milestonesBeforeWindow = 2,
            milestonesAfterWindow = 1,
        )
        val opened = AtomicReference<MilestoneId?>()

        composeRule.setContent {
            OpenTasksTheme {
                ProjectTimelineView(
                    projection = projection,
                    onPrevious = {},
                    onToday = {},
                    onNext = {},
                    onTaskSelectionChange = {},
                    onOpenTask = {},
                    onOpenMilestone = opened::set,
                )
            }
        }

        composeRule.onNodeWithTag("timeline-milestone-window-summary").assertTextEquals(
            context.getString(R.string.timeline_milestones_outside_window, 2, 1),
        )

        listOf(openMilestone, completeMilestone).forEach { milestone ->
            composeRule.onNodeWithTag("timeline-milestone-${milestone.id.value}")
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }

        composeRule.onNodeWithTag("timeline-milestone-${openMilestone.id.value}").performClick()
        assertEquals(openMilestone.id, opened.get())

        composeRule.onNodeWithTag("timeline-milestone-${completeMilestone.id.value}").performClick()
        assertEquals(completeMilestone.id, opened.get())
    }

    @Test
    fun completedBlockedAndUnscheduledRowsRemainVisible() {
        // `completedTask` and `blockedTask` are Spans clipped at the edge
        // where their own status icon also lands (completed -> CenterStart,
        // same edge as a `continuesBefore` chevron; blocked -> CenterEnd,
        // same edge as a `continuesAfter` chevron) -- this is the icon
        // co-occurrence the fix round covers: both cues must stay legible
        // (distinct slots) and both must still be named in the row's
        // merged content description.
        val completedTask = baseTask.copy(
            id = TaskId("timeline-completed"),
            title = "Wrapped up",
            semanticStatus = SemanticStatus.COMPLETED,
            completedAt = Instant.parse("2026-08-01T09:00:00Z"),
        )
        val blockedTask = baseTask.copy(
            id = TaskId("timeline-blocked"),
            title = "Stuck task",
            semanticStatus = SemanticStatus.BLOCKED,
            blockedBy = setOf(TaskId("timeline-blocker")),
        )
        val unscheduledTask = baseTask.copy(
            id = TaskId("timeline-unscheduled"),
            title = "No dates yet",
            semanticStatus = SemanticStatus.BACKLOG,
        )

        val completedStart = LocalDate.of(2026, 7, 20)
        val completedDue = LocalDate.of(2026, 8, 5)
        val blockedStart = LocalDate.of(2026, 8, 6)
        val blockedDue = LocalDate.of(2026, 11, 1)

        val rows = listOf(
            ProjectTimelineTaskRow(
                task = completedTask,
                startDate = completedStart,
                dueDate = completedDue,
                placement = ProjectTimelineTaskPlacement.Span(
                    firstVisibleDayIndex = 0,
                    lastVisibleDayIndex = 2,
                    totalDayCount = 17,
                    continuesBefore = true,
                    continuesAfter = false,
                ),
                dependencyRole = ProjectTimelineDependencyRole.NONE,
            ),
            ProjectTimelineTaskRow(
                task = blockedTask,
                startDate = blockedStart,
                dueDate = blockedDue,
                placement = ProjectTimelineTaskPlacement.Span(
                    firstVisibleDayIndex = 3,
                    lastVisibleDayIndex = 83,
                    totalDayCount = 88,
                    continuesBefore = false,
                    continuesAfter = true,
                ),
                dependencyRole = ProjectTimelineDependencyRole.NONE,
            ),
            ProjectTimelineTaskRow(
                task = unscheduledTask,
                startDate = null,
                dueDate = null,
                placement = ProjectTimelineTaskPlacement.Unscheduled,
                dependencyRole = ProjectTimelineDependencyRole.NONE,
            ),
        )
        val projection = projectionOf(rows)

        composeRule.setContent {
            OpenTasksTheme {
                ProjectTimelineView(
                    projection = projection,
                    onPrevious = {},
                    onToday = {},
                    onNext = {},
                    onTaskSelectionChange = {},
                    onOpenTask = {},
                    onOpenMilestone = {},
                )
            }
        }

        composeRule.onNodeWithTag("timeline-task-row-${completedTask.id.value}").assertIsDisplayed()
        composeRule.onNodeWithTag("timeline-task-row-${blockedTask.id.value}").assertIsDisplayed()
        composeRule.onNodeWithTag("timeline-task-row-${unscheduledTask.id.value}").assertIsDisplayed()

        val dateFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.UK)
        // Completed + clipped at the window start: both the "Completed" and
        // the "continues from before this window" fragments must survive
        // in the merged description even though their icons now share one
        // edge of the bar.
        composeRule.onNodeWithTag("timeline-task-row-${completedTask.id.value}")
            .assertContentDescriptionEquals(
                listOf(
                    completedTask.title,
                    context.getString(R.string.timeline_row_starts, completedStart.format(dateFormat)),
                    context.getString(R.string.timeline_row_due, completedDue.format(dateFormat)),
                    context.resources.getQuantityString(R.plurals.timeline_row_duration_days, 17, 17),
                    context.getString(R.string.timeline_row_continues_before),
                    context.getString(R.string.timeline_row_completed),
                    context.getString(R.string.timeline_row_not_blocked),
                ).joinToString(". "),
            )
        // Blocked + clipped at the window end: same coexistence check on
        // the opposite edge ("Blocked" and "continues after this window").
        composeRule.onNodeWithTag("timeline-task-row-${blockedTask.id.value}")
            .assertContentDescriptionEquals(
                listOf(
                    blockedTask.title,
                    context.getString(R.string.timeline_row_starts, blockedStart.format(dateFormat)),
                    context.getString(R.string.timeline_row_due, blockedDue.format(dateFormat)),
                    context.resources.getQuantityString(R.plurals.timeline_row_duration_days, 88, 88),
                    context.getString(R.string.timeline_row_continues_after),
                    context.getString(R.string.timeline_row_not_completed),
                    context.getString(R.string.timeline_row_blocked),
                ).joinToString(". "),
            )
        composeRule.onNodeWithTag("timeline-task-row-${unscheduledTask.id.value}")
            .assertContentDescriptionEquals(
                listOf(
                    unscheduledTask.title,
                    context.getString(R.string.timeline_row_unscheduled),
                    context.getString(R.string.timeline_row_not_completed),
                    context.getString(R.string.timeline_row_not_blocked),
                ).joinToString(". "),
            )
    }
}
