package app.opentasks.feature.projects

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.BoardColumn
import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.ProjectPresentation
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskGroup
import app.opentasks.core.model.TaskGroupKey
import app.opentasks.core.model.TaskGroupValue
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TaskSortKey
import app.opentasks.core.model.WorkflowStatusId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProjectWorkbenchInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun suppliedWorkbenchGroupsKeepTheirOrderAndListControlsStayStateless() {
        val project = OpenTasksFixtures.studioProject
        val base = OpenTasksFixtures.tasks.first { it.projectId == project.id }.copy(
            completedAt = null,
            deletedAt = null,
        )
        val rawFirst = base.copy(id = TaskId("raw-first"), title = "Raw first")
        val groupedFirst = base.copy(id = TaskId("grouped-first"), title = "Grouped first")
        val flat = base.copy(id = TaskId("flat"), title = "Flat task")
        val selectedSort = AtomicReference<TaskSortKey?>()
        val selectedGroup = AtomicReference<TaskGroupKey?>(TaskGroupKey.PROJECT)

        setWorkbenchContent(
            project = project,
            tasks = listOf(rawFirst, groupedFirst, flat),
            groups = listOf(
                TaskGroup(TaskGroupValue.Due(DueBucket.TODAY), listOf(groupedFirst)),
                TaskGroup(TaskGroupValue.PriorityValue(Priority.HIGH), listOf(rawFirst)),
                TaskGroup(null, listOf(flat)),
            ),
            sort = TaskSortKey.UPDATED,
            groupBy = TaskGroupKey.PRIORITY,
            onSortChange = selectedSort::set,
            onGroupChange = selectedGroup::set,
            boardColumns = boardColumnsFor(project, listOf(rawFirst, groupedFirst, flat)),
        )

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasText(groupedFirst.title))
        val groupedFirstTop = composeRule.onNodeWithText(groupedFirst.title)
            .getUnclippedBoundsInRoot().top
        val rawFirstTop = composeRule.onNodeWithText(rawFirst.title).getUnclippedBoundsInRoot().top
        assertTrue(groupedFirstTop < rawFirstTop)
        composeRule.onNodeWithText(context.getString(R.string.workbench_group_due_today))
            .assert(isHeading())
        composeRule.onNodeWithText(context.getString(R.string.workbench_group_priority_high))
            .assert(isHeading())
        composeRule.onNodeWithText("null").assertDoesNotExist()

        composeRule.onNodeWithTag("workbench-sort-control")
            .assertContentDescriptionEquals("Sort project tasks: Updated")
            .performClick()
        composeRule.onNodeWithTag("workbench-sort-option-updated").assertIsSelected()
        composeRule.onNodeWithTag("workbench-sort-option-due").assertIsNotSelected().performClick()
        assertEquals(TaskSortKey.DUE, selectedSort.get())
        composeRule.onNodeWithTag("workbench-sort-control")
            .assertContentDescriptionEquals("Sort project tasks: Updated")
            .performClick()
        composeRule.onNodeWithTag("workbench-sort-option-updated").assertIsSelected()
        composeRule.onNodeWithTag("workbench-sort-option-due").assertIsNotSelected()
        composeRule.onNodeWithTag("workbench-sort-option-priority").performClick()
        assertEquals(TaskSortKey.PRIORITY, selectedSort.get())
        composeRule.onNodeWithTag("workbench-sort-control").performClick()
        composeRule.onNodeWithTag("workbench-sort-option-title").performClick()
        assertEquals(TaskSortKey.TITLE, selectedSort.get())
        composeRule.onNodeWithTag("workbench-sort-control").performClick()
        composeRule.onNodeWithTag("workbench-sort-option-updated").performClick()
        assertEquals(TaskSortKey.UPDATED, selectedSort.get())

        composeRule.onNodeWithTag("workbench-group-control")
            .assertContentDescriptionEquals("Group project tasks: Priority")
            .performClick()
        composeRule.onNodeWithTag("workbench-group-option-priority").assertIsSelected()
        composeRule.onNodeWithTag("workbench-group-option-none").assertIsNotSelected().performClick()
        assertEquals(null, selectedGroup.get())
        composeRule.onNodeWithTag("workbench-group-control")
            .assertContentDescriptionEquals("Group project tasks: Priority")
            .performClick()
        composeRule.onNodeWithTag("workbench-group-option-priority").assertIsSelected()
        composeRule.onNodeWithTag("workbench-group-option-none").assertIsNotSelected()
        composeRule.onNodeWithTag("workbench-group-option-due_bucket").performClick()
        assertEquals(TaskGroupKey.DUE_BUCKET, selectedGroup.get())
        composeRule.onNodeWithTag("workbench-group-control").performClick()
        composeRule.onNodeWithTag("workbench-group-option-priority").performClick()
        assertEquals(TaskGroupKey.PRIORITY, selectedGroup.get())
        composeRule.onNodeWithTag("workbench-group-control").performClick()
        composeRule.onNodeWithTag("workbench-group-option-project").assertDoesNotExist()

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("workbench-view-board"))
        composeRule.onNodeWithTag("workbench-view-board").performClick()
        composeRule.onNodeWithTag("workbench-task-${groupedFirst.id.value}").assertDoesNotExist()
        // The board lives inside the same outer "project-workbench-list"
        // item as the toggle above it; scrolling to the toggle only proves
        // the toggle is visible. performScrollToNode() stops as soon as a
        // matching node exists anywhere in the tree, which the much taller
        // preceding content already satisfies while the 440.dp-tall board
        // columns below it are still clipped by the outer list on a short
        // compact-profile viewport -- repeating the call is then a no-op.
        // A full-height swipeUp() overshoots straight past the board's
        // on-screen window in one step (confirmed: it reached the list's
        // absolute scroll max with the card still not displayed, now above
        // the fold instead of below it), so nudge the outer list down in
        // small, bounded steps, re-settling the board's own horizontal
        // scroll (board-column) and the column's own card list
        // (board-card) after each one, until the card is actually
        // displayed.
        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("board-card-${groupedFirst.id.value}"))
        var boardCardDisplayed = runCatching {
            composeRule.onNodeWithTag("board-card-${groupedFirst.id.value}").assertIsDisplayed()
        }.isSuccess
        var boardScrollAttempts = 0
        while (!boardCardDisplayed && boardScrollAttempts < 20) {
            composeRule.onNodeWithTag("project-workbench-list").performTouchInput {
                swipeUp(startY = center.y + 300f, endY = center.y - 300f)
            }
            composeRule.onNodeWithTag("board-column-${groupedFirst.statusId.value}")
                .performScrollTo()
            composeRule.onNodeWithTag("board-card-${groupedFirst.id.value}")
                .performScrollTo()
            boardCardDisplayed = runCatching {
                composeRule.onNodeWithTag("board-card-${groupedFirst.id.value}").assertIsDisplayed()
            }.isSuccess
            boardScrollAttempts++
        }
        composeRule.onNodeWithTag("board-column-${groupedFirst.statusId.value}")
            .performScrollTo()
        composeRule.onNodeWithTag("board-card-${groupedFirst.id.value}")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun workbenchIndentsChildTaskRows() {
        val project = OpenTasksFixtures.studioProject
        val base = OpenTasksFixtures.tasks.first { it.projectId == project.id }.copy(
            completedAt = null,
            deletedAt = null,
        )
        val parent = base.copy(id = TaskId("workbench-parent"), title = "Workbench parent")
        val child = base.copy(
            id = TaskId("workbench-child"),
            title = "Workbench child",
            parentTaskId = parent.id,
        )

        setWorkbenchContent(
            project = project,
            tasks = listOf(parent, child),
            groups = listOf(TaskGroup(null, listOf(parent, child))),
            workbenchIndentedTaskIds = setOf(child.id),
        )

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasText(child.title))
        val parentLeft = composeRule.onNodeWithTag("workbench-task-${parent.id.value}")
            .getUnclippedBoundsInRoot().left
        val childLeft = composeRule.onNodeWithTag("workbench-task-${child.id.value}")
            .getUnclippedBoundsInRoot().left
        assertTrue(childLeft > parentLeft)
    }

    @Test
    fun suppliedBoardColumnsKeepCardOrderAndBoardSortControlStaysStateless() {
        val project = OpenTasksFixtures.studioProject
        val base = OpenTasksFixtures.tasks.first { it.projectId == project.id }.copy(
            completedAt = null,
            deletedAt = null,
        )
        val first = base.copy(id = TaskId("board-first"), title = "Zulu")
        val second = base.copy(id = TaskId("board-second"), title = "Alpha")
        val selectedSort = AtomicReference<TaskSortKey?>()
        val columns = boardColumnsFor(project, listOf(first, second))
        val selectedColumn = columns.map { column ->
            if (column.status.id == first.statusId) column.copy(tasks = listOf(first, second)) else column
        }

        setWorkbenchContent(
            project = project,
            tasks = listOf(first, second),
            boardColumns = selectedColumn,
            boardSort = TaskSortKey.TITLE,
            onBoardSortChange = selectedSort::set,
            initialPresentation = ProjectPresentation.BOARD,
        )

        val firstTop = composeRule.onNodeWithTag("board-card-${first.id.value}")
            .getUnclippedBoundsInRoot().top
        val secondTop = composeRule.onNodeWithTag("board-card-${second.id.value}")
            .getUnclippedBoundsInRoot().top
        assertTrue(firstTop < secondTop)

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("board-sort-control"))
        composeRule.onNodeWithTag("board-sort-control")
            .assertContentDescriptionEquals("Sort board cards: Title")
            .performClick()
        composeRule.onNodeWithTag("board-sort-option-title").assertIsSelected()
        composeRule.onNodeWithTag("board-sort-option-updated").assertDoesNotExist()
        composeRule.onNodeWithTag("workbench-group-control").assertDoesNotExist()
        composeRule.onNodeWithTag("workbench-sort-control").assertDoesNotExist()
        composeRule.onNodeWithTag("board-sort-option-priority").assertIsNotSelected().performClick()
        assertEquals(TaskSortKey.PRIORITY, selectedSort.get())
        composeRule.onNodeWithTag("board-sort-control").performClick()
        composeRule.onNodeWithTag("board-sort-option-due").assertIsNotSelected().performClick()
        assertEquals(TaskSortKey.DUE, selectedSort.get())
        composeRule.onNodeWithTag("board-sort-control")
            .assertContentDescriptionEquals("Sort board cards: Title")
            .performClick()
        composeRule.onNodeWithTag("board-sort-option-title").assertIsSelected().performClick()
        assertEquals(TaskSortKey.TITLE, selectedSort.get())
    }

    @Test
    fun workbenchUsesOpaqueLazyKeysForMilestonesGroupsAndTasks() {
        val project = OpenTasksFixtures.studioProject
        val base = OpenTasksFixtures.tasks.first { it.projectId == project.id }.copy(
            completedAt = null,
            deletedAt = null,
        )
        val sharedIdTask = base.copy(id = TaskId("shared-id"), title = "Shared id task")
        val headerIdTask = base.copy(id = TaskId("due:TODAY"), title = "Header id task")
        val inboxProjectTask = base.copy(id = TaskId("inbox-project"), title = "Inbox project task")
        val milestone = OpenTasksFixtures.milestones.first { it.projectId == project.id }.copy(
            id = MilestoneId(sharedIdTask.id.value),
            name = "Shared id milestone",
        )

        setWorkbenchContent(
            project = project,
            tasks = listOf(sharedIdTask, headerIdTask, inboxProjectTask),
            milestones = listOf(milestone),
            groups = listOf(
                TaskGroup(TaskGroupValue.Due(DueBucket.TODAY), listOf(headerIdTask)),
                TaskGroup(TaskGroupValue.Project(null), listOf(sharedIdTask)),
                TaskGroup(
                    TaskGroupValue.Project(ProjectId("inbox")),
                    listOf(inboxProjectTask),
                ),
            ),
        )

        // Each check scrolls to its own target rather than reusing one early
        // scroll: "project-workbench-list" is a real LazyColumn, and on a
        // short compact-profile viewport the header/milestone/group rows
        // between two targets are wide enough apart that an earlier target
        // gets recycled out of composition by the time a later one is
        // brought into view -- the fold profile this was written on has
        // enough viewport height to keep everything composed at once, which
        // is why this only failed on compact.
        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasText(milestone.name))
        composeRule.onNodeWithText(milestone.name).assertIsDisplayed()

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(
                hasText(context.getString(R.string.workbench_group_due_today)) and isHeading(),
            )
        composeRule.onNodeWithText(context.getString(R.string.workbench_group_due_today))
            .assert(isHeading())

        // The two "Project" headings sandwich sharedIdTask's row, so
        // scrolling to that row keeps both simultaneously composed for the
        // single fetchSemanticsNodes() snapshot below.
        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("workbench-task-${sharedIdTask.id.value}"))
        assertEquals(
            2,
            composeRule.onAllNodes(hasText(context.getString(R.string.workbench_group_project)) and isHeading())
                .fetchSemanticsNodes().size,
        )
        listOf(sharedIdTask, headerIdTask, inboxProjectTask).forEach { task ->
            composeRule.onNodeWithTag("workbench-task-${task.id.value}")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun compactWorkbenchAutoSavesAndOpensItsTask() {
        val project = OpenTasksFixtures.studioProject
        val submitted = AtomicReference<Pair<ProjectId, ProjectEdit>?>()
        val openedTask = AtomicReference<TaskId?>()
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            OpenTasksTheme {
                ProjectsScreen(
                    projects = OpenTasksFixtures.snapshot.projects,
                    tasks = OpenTasksFixtures.snapshot.tasks,
                    milestones = OpenTasksFixtures.snapshot.milestones,
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    selectedProjectId = project.id,
                    showDetailPane = false,
                    onSelectProject = {},
                    onCloseDetail = {},
                    onUpdateProject = { projectId, edit ->
                        submitted.set(projectId to edit)
                    },
                    onArchiveProject = {},
                    onOpenTask = openedTask::set,
                )
            }
        }

        composeRule.onNodeWithTag("project-name-field")
            .performTextReplacement("Studio relaunch")
        composeRule.mainClock.advanceTimeBy(700)
        composeRule.waitForIdle()

        assertEquals(project.id, submitted.get()?.first)
        assertEquals("Studio relaunch", submitted.get()?.second?.name)

        val projectTask = OpenTasksFixtures.tasks.first { it.projectId == project.id }
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasText(projectTask.title))
        composeRule.onNodeWithText(projectTask.title).performClick()
        assertEquals(projectTask.id, openedTask.get())
    }

    @Test
    fun expandedWorkbenchKeepsProjectListAndDetailVisible() {
        val project = OpenTasksFixtures.taxProject

        composeRule.setContent {
            OpenTasksTheme {
                ProjectsScreen(
                    projects = OpenTasksFixtures.snapshot.projects,
                    tasks = OpenTasksFixtures.snapshot.tasks,
                    milestones = OpenTasksFixtures.snapshot.milestones,
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    selectedProjectId = project.id,
                    showDetailPane = true,
                    onSelectProject = {},
                    onCloseDetail = {},
                    onUpdateProject = { _, _ -> },
                    onArchiveProject = {},
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithText("Projects").assertIsDisplayed()
        composeRule.onNodeWithText("Project workbench").assertIsDisplayed()
        composeRule.onNodeWithTag("project-name-field").assertIsDisplayed()
    }

    @Test
    fun expandedProjectChooserOmitsDescriptiveSectionCopy() {
        composeRule.setContent {
            OpenTasksTheme {
                ProjectsScreen(
                    projects = OpenTasksFixtures.snapshot.projects,
                    tasks = OpenTasksFixtures.snapshot.tasks,
                    milestones = OpenTasksFixtures.snapshot.milestones,
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    selectedProjectId = null,
                    showDetailPane = true,
                    onSelectProject = {},
                    onCloseDetail = {},
                    onUpdateProject = { _, _ -> },
                    onArchiveProject = {},
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithText("Projects").assertIsDisplayed()
        composeRule.onNodeWithText("Choose a project").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText(
                "Workflows, milestones, dependencies, and time in one place.",
            ).fetchSemanticsNodes().size,
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithText(
                "Its plan, health, milestones, and tasks will stay open here.",
            ).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun workbenchArchiveActionIdentifiesTheSelectedProject() {
        val project = OpenTasksFixtures.studioProject
        val archived = AtomicReference<ProjectId?>()

        composeRule.setContent {
            OpenTasksTheme {
                ProjectsScreen(
                    projects = OpenTasksFixtures.snapshot.projects,
                    tasks = OpenTasksFixtures.snapshot.tasks,
                    milestones = OpenTasksFixtures.snapshot.milestones,
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    selectedProjectId = project.id,
                    showDetailPane = false,
                    onSelectProject = {},
                    onCloseDetail = {},
                    onUpdateProject = { _, _ -> },
                    onArchiveProject = { archived.set(it.id) },
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("archive-project"))
        composeRule.onNodeWithTag("archive-project").performClick()

        assertEquals(project.id, archived.get())
    }

    @Test
    fun workbenchCapturesATrimmedProjectTemplateName() {
        val project = OpenTasksFixtures.studioProject
        val captured = AtomicReference<Pair<ProjectId, String>?>()

        composeRule.setContent {
            OpenTasksTheme {
                ProjectsScreen(
                    projects = OpenTasksFixtures.snapshot.projects,
                    tasks = OpenTasksFixtures.snapshot.tasks,
                    milestones = OpenTasksFixtures.snapshot.milestones,
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    selectedProjectId = project.id,
                    showDetailPane = false,
                    onSelectProject = {},
                    onCloseDetail = {},
                    onUpdateProject = { _, _ -> },
                    onArchiveProject = {},
                    onCaptureTemplate = { projectId, name ->
                        captured.set(projectId to name)
                    },
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("save-project-template"))
        composeRule.onNodeWithTag("save-project-template").performClick()
        composeRule.onNodeWithTag("save-template-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("template-name-field")
            .performTextReplacement("  Client delivery  ")
        composeRule.onNodeWithTag("confirm-save-template").performScrollTo().performClick()

        assertEquals(project.id to "Client delivery", captured.get())
    }

    @Test
    fun workflowEditorExposesAddRenameReorderAndArchiveActions() {
        val project = OpenTasksFixtures.studioProject
        val created = AtomicReference<Triple<ProjectId, String, SemanticStatus>?>()
        val renamed = AtomicReference<Pair<WorkflowStatusId, String>?>()
        val moved = AtomicReference<Pair<WorkflowStatusId, WorkflowMove>?>()
        val archived = AtomicReference<WorkflowStatusId?>()

        composeRule.setContent {
            OpenTasksTheme {
                ProjectsScreen(
                    projects = OpenTasksFixtures.snapshot.projects,
                    tasks = OpenTasksFixtures.snapshot.tasks,
                    milestones = OpenTasksFixtures.snapshot.milestones,
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    selectedProjectId = project.id,
                    showDetailPane = false,
                    onSelectProject = {},
                    onCloseDetail = {},
                    onUpdateProject = { _, _ -> },
                    onArchiveProject = {},
                    onCreateWorkflowStatus = { projectId, name, semantic ->
                        created.set(Triple(projectId, name, semantic))
                    },
                    onRenameWorkflowStatus = { statusId, name ->
                        renamed.set(statusId to name)
                    },
                    onMoveWorkflowStatus = { statusId, direction ->
                        moved.set(statusId to direction)
                    },
                    onArchiveWorkflowStatus = archived::set,
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("manage-workflow"))
        composeRule.onNodeWithTag("manage-workflow").performClick()

        composeRule.onNodeWithTag("workflow-name-${OpenTasksFixtures.planned.value}")
            .performTextReplacement("Ready next")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("save-workflow-name-${OpenTasksFixtures.planned.value}")
            .performScrollTo()
            .performClick()
        assertEquals(OpenTasksFixtures.planned to "Ready next", renamed.get())

        composeRule.onNodeWithTag("move-workflow-later-${OpenTasksFixtures.planned.value}")
            .performScrollTo()
            .performClick()
        assertEquals(OpenTasksFixtures.planned to WorkflowMove.LATER, moved.get())

        composeRule.onNodeWithTag("archive-workflow-${OpenTasksFixtures.planned.value}")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Archive").performClick()
        assertEquals(OpenTasksFixtures.planned, archived.get())

        composeRule.onNodeWithTag("new-workflow-status-name")
            .performScrollTo()
            .performTextInput("Review queue")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("add-workflow-status")
            .performScrollTo()
            .performClick()
        assertEquals(
            Triple(project.id, "Review queue", SemanticStatus.PLANNED),
            created.get(),
        )
    }

    @Test
    fun editorRowHidesLimitFieldForCompletedColumnsAndSavesOthers() {
        val project = OpenTasksFixtures.studioProject
        val saved = AtomicReference<Pair<WorkflowStatusId, Int?>?>()

        composeRule.setContent {
            OpenTasksTheme {
                ProjectsScreen(
                    projects = OpenTasksFixtures.snapshot.projects,
                    tasks = OpenTasksFixtures.snapshot.tasks,
                    milestones = OpenTasksFixtures.snapshot.milestones,
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    selectedProjectId = project.id,
                    showDetailPane = false,
                    onSelectProject = {},
                    onCloseDetail = {},
                    onUpdateProject = { _, _ -> },
                    onArchiveProject = {},
                    onSetWipLimit = { statusId, limit -> saved.set(statusId to limit) },
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("manage-workflow"))
        composeRule.onNodeWithTag("manage-workflow").performClick()

        composeRule.onNodeWithTag("workflow-wip-${OpenTasksFixtures.started.value}")
            .performScrollTo()
            .performTextReplacement("3")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("save-workflow-wip-${OpenTasksFixtures.started.value}")
            .performScrollTo()
            .performClick()
        assertEquals(OpenTasksFixtures.started to 3, saved.get())

        composeRule.onNodeWithTag("workflow-wip-${OpenTasksFixtures.done.value}")
            .assertDoesNotExist()
    }

    @Test
    fun milestoneEditorCreatesUpdatesAndDeletesProjectMilestones() {
        val project = OpenTasksFixtures.studioProject
        val milestone = OpenTasksFixtures.milestones.first { it.projectId == project.id }
        val created = AtomicReference<Triple<ProjectId, String, LocalDate?>?>()
        val updated = AtomicReference<MilestoneUpdate?>()
        val deleted = AtomicReference<MilestoneId?>()

        composeRule.setContent {
            OpenTasksTheme {
                ProjectsScreen(
                    projects = OpenTasksFixtures.snapshot.projects,
                    tasks = OpenTasksFixtures.snapshot.tasks,
                    milestones = OpenTasksFixtures.snapshot.milestones,
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    selectedProjectId = project.id,
                    showDetailPane = false,
                    onSelectProject = {},
                    onCloseDetail = {},
                    onUpdateProject = { _, _ -> },
                    onArchiveProject = {},
                    onCreateMilestone = { projectId, name, dueDate ->
                        created.set(Triple(projectId, name, dueDate))
                    },
                    onUpdateMilestone = { milestoneId, name, dueDate, completedAt ->
                        updated.set(MilestoneUpdate(milestoneId, name, dueDate, completedAt))
                    },
                    onDeleteMilestone = deleted::set,
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("add-milestone"))
        composeRule.onNodeWithTag("add-milestone").performClick()
        composeRule.onNodeWithTag("milestone-name-field").performTextInput("  Beta ready  ")
        composeRule.onNodeWithTag("save-milestone").performScrollTo().performClick()
        assertEquals(Triple(project.id, "Beta ready", null), created.get())

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasText(milestone.name))
        composeRule.onNodeWithText(milestone.name).performClick()
        composeRule.onNodeWithTag("milestone-name-field")
            .performTextReplacement("Public release")
        composeRule.onNodeWithTag("save-milestone").performScrollTo().performClick()
        assertEquals(milestone.id, updated.get()?.id)
        assertEquals("Public release", updated.get()?.name)

        composeRule.onNodeWithText(milestone.name).performClick()
        composeRule.onNodeWithTag("delete-milestone").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete").performClick()
        assertEquals(milestone.id, deleted.get())
    }

    @Test
    fun newProjectSheetSubmitsTrimmedDetails() {
        val submitted = AtomicReference<Pair<String, String>?>()

        composeRule.setContent {
            OpenTasksTheme {
                NewProjectSheet(
                    onDismiss = {},
                    onCreate = { name, summary -> submitted.set(name to summary) },
                )
            }
        }

        composeRule.onNodeWithTag("new-project-name").performTextInput("  Client portal  ")
        composeRule.onNodeWithTag("new-project-summary")
            .performTextInput("  Launch preparation  ")
        composeRule.onNodeWithTag("create-project").performClick()

        assertEquals("Client portal" to "Launch preparation", submitted.get())
    }

    @Test
    fun projectDraftAndWorkbenchScrollRestoreAfterSavedInstanceStateRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        val project = OpenTasksFixtures.studioProject
        val draft = "x".repeat(121)

        restorationTester.setContent {
            OpenTasksTheme {
                ProjectsScreen(
                    projects = OpenTasksFixtures.snapshot.projects,
                    tasks = OpenTasksFixtures.snapshot.tasks,
                    milestones = OpenTasksFixtures.snapshot.milestones,
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    selectedProjectId = project.id,
                    showDetailPane = false,
                    onSelectProject = {},
                    onCloseDetail = {},
                    onUpdateProject = { _, _ -> },
                    onArchiveProject = {},
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("project-name-field")
            .performTextReplacement(draft)
        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("archive-project"))
        composeRule.onNodeWithTag("archive-project").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("archive-project").assertIsDisplayed()
        // Restoration keeps the list scrolled past the name field, which the
        // notes and activity section now pushes further out of composition.
        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("project-name-field"))
        composeRule.onNodeWithTag("project-name-field")
            .assertTextContains(draft, substring = true)
    }

    private fun setWorkbenchContent(
        project: app.opentasks.core.model.Project,
        tasks: List<Task>,
        milestones: List<app.opentasks.core.model.Milestone> =
            OpenTasksFixtures.milestones.filter { it.projectId == project.id },
        groups: List<TaskGroup> = listOf(TaskGroup(null, tasks)),
        sort: TaskSortKey = TaskSortKey.DUE,
        groupBy: TaskGroupKey? = null,
        onSortChange: (TaskSortKey) -> Unit = {},
        onGroupChange: (TaskGroupKey?) -> Unit = {},
        boardColumns: List<BoardColumn> = emptyList(),
        boardSort: TaskSortKey = TaskSortKey.PRIORITY,
        onBoardSortChange: (TaskSortKey) -> Unit = {},
        initialPresentation: ProjectPresentation = ProjectPresentation.LIST,
        workbenchIndentedTaskIds: Set<TaskId> = emptySet(),
    ) {
        composeRule.setContent {
            val presentation = remember { mutableStateOf(initialPresentation) }
            OpenTasksTheme {
                ProjectsScreen(
                    projects = OpenTasksFixtures.snapshot.projects,
                    tasks = tasks,
                    milestones = milestones,
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    selectedProjectId = project.id,
                    showDetailPane = false,
                    presentation = presentation.value,
                    workbenchTaskGroups = groups,
                    workbenchIndentedTaskIds = workbenchIndentedTaskIds,
                    workbenchSort = sort,
                    workbenchGroupBy = groupBy,
                    selectedBoardColumns = boardColumns,
                    boardSort = boardSort,
                    onWorkbenchSortChange = onSortChange,
                    onWorkbenchGroupChange = onGroupChange,
                    onBoardSortChange = onBoardSortChange,
                    onPresentationChange = { presentation.value = it },
                    onSelectProject = {},
                    onCloseDetail = {},
                    onUpdateProject = { _, _ -> },
                    onArchiveProject = {},
                    onOpenTask = {},
                )
            }
        }
    }

    private fun boardColumnsFor(project: app.opentasks.core.model.Project, tasks: List<Task>) =
        OpenTasksFixtures.workflowStatuses
            .filter { it.projectId == project.id && it.archivedAt == null }
            .sortedBy { it.rank }
            .map { status ->
                BoardColumn(
                    status = status,
                    tasks = tasks.filter { it.statusId == status.id },
                )
            }

    private data class MilestoneUpdate(
        val id: MilestoneId,
        val name: String,
        val dueDate: LocalDate?,
        val completedAt: Instant?,
    )
}
