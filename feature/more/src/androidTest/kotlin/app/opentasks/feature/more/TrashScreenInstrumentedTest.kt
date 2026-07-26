package app.opentasks.feature.more

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TrashScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun trashOpensAndRequiresConfirmationBeforePermanentDelete() {
        val task = OpenTasksFixtures.tasks.first().copy(
            deletedAt = Instant.parse("2026-07-26T10:05:00Z"),
        )
        val restored = AtomicReference<TaskId?>()
        val permanentlyDeleted = AtomicReference<TaskId?>()

        composeRule.setContent {
            OpenTasksTheme {
                MoreScreen(
                    tasks = listOf(task),
                    projects = OpenTasksFixtures.snapshot.projects,
                    onRestoreProject = {},
                    onRestoreTask = restored::set,
                    onPermanentlyDeleteTask = permanentlyDeleted::set,
                )
            }
        }

        composeRule.onNodeWithTag("open-trash")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("trash-screen").assertIsDisplayed()
        composeRule.onNodeWithText(task.title).assertIsDisplayed()

        composeRule.onNodeWithTag("restore-task-${task.id.value}").performClick()
        assertEquals(task.id, restored.get())

        composeRule.onNodeWithTag("permanently-delete-task-${task.id.value}")
            .performClick()
        composeRule.onNodeWithText("Delete permanently?").assertIsDisplayed()
        assertEquals(null, permanentlyDeleted.get())

        composeRule.onNodeWithTag("confirm-permanent-delete").performClick()
        assertEquals(task.id, permanentlyDeleted.get())
    }

    @Test
    fun archiveOpensAndRestoresAProjectWithoutRemovingItsTaskContext() {
        val project = OpenTasksFixtures.studioProject.copy(
            archivedAt = Instant.parse("2026-07-26T11:00:00Z"),
        )
        val projectTask = OpenTasksFixtures.tasks.first { it.projectId == project.id }
        val restored = AtomicReference<ProjectId?>()

        composeRule.setContent {
            OpenTasksTheme {
                MoreScreen(
                    tasks = listOf(projectTask),
                    projects = listOf(project),
                    onRestoreProject = restored::set,
                    onRestoreTask = {},
                    onPermanentlyDeleteTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("open-archive")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("archive-screen").assertIsDisplayed()
        composeRule.onNodeWithText(project.name).assertIsDisplayed()
        composeRule.onNodeWithText("1 open • 0 complete • archived 26 Jul 2026")
            .assertIsDisplayed()

        composeRule.onNodeWithTag("restore-project-${project.id.value}").performClick()
        assertEquals(project.id, restored.get())
    }
}
