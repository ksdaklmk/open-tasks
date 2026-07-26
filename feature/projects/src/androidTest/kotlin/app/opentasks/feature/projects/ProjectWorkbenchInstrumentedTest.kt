package app.opentasks.feature.projects

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
}
