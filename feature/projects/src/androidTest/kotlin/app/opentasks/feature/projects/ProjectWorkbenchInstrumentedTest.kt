package app.opentasks.feature.projects

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatusId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProjectWorkbenchInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

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
        composeRule.onNodeWithTag("confirm-save-template").performClick()

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
        composeRule.onNodeWithTag("save-workflow-name-${OpenTasksFixtures.planned.value}")
            .performClick()
        assertEquals(OpenTasksFixtures.planned to "Ready next", renamed.get())

        composeRule.onNodeWithTag("move-workflow-later-${OpenTasksFixtures.planned.value}")
            .performClick()
        assertEquals(OpenTasksFixtures.planned to WorkflowMove.LATER, moved.get())

        composeRule.onNodeWithTag("archive-workflow-${OpenTasksFixtures.planned.value}")
            .performClick()
        composeRule.onNodeWithText("Archive").performClick()
        assertEquals(OpenTasksFixtures.planned, archived.get())

        composeRule.onNodeWithTag("new-workflow-status-name")
            .performScrollTo()
            .performTextInput("Review queue")
        composeRule.onNodeWithTag("add-workflow-status")
            .performScrollTo()
            .performClick()
        assertEquals(
            Triple(project.id, "Review queue", SemanticStatus.PLANNED),
            created.get(),
        )
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
        composeRule.onNodeWithTag("save-milestone").performClick()
        assertEquals(Triple(project.id, "Beta ready", null), created.get())

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasText(milestone.name))
        composeRule.onNodeWithText(milestone.name).performClick()
        composeRule.onNodeWithTag("milestone-name-field")
            .performTextReplacement("Public release")
        composeRule.onNodeWithTag("save-milestone").performClick()
        assertEquals(milestone.id, updated.get()?.id)
        assertEquals("Public release", updated.get()?.name)

        composeRule.onNodeWithText(milestone.name).performClick()
        composeRule.onNodeWithTag("delete-milestone").performClick()
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

    private data class MilestoneUpdate(
        val id: MilestoneId,
        val name: String,
        val dueDate: LocalDate?,
        val completedAt: Instant?,
    )
}
