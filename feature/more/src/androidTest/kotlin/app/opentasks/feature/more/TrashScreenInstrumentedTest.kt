package app.opentasks.feature.more

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.Template
import app.opentasks.core.model.TemplateId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TrashScreenInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

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

    @Test
    fun templateLibraryCreatesANamedProjectFromTheSelectedAnchorDateAndDeletes() {
        val template = Template(
            id = TemplateId("client-delivery"),
            workspaceId = OpenTasksFixtures.workspaceId,
            name = "Client delivery",
            projectName = "Client launch",
            projectSummary = "Reusable launch structure",
            projectDueOffsetDays = 14,
            workflowStatuses = emptyList(),
            milestones = emptyList(),
            tasks = emptyList(),
            revision = Revision(
                deviceId = DeviceId("device-test"),
                wallTimeMillis = 1,
                logicalCounter = 0,
            ),
        )
        val today = LocalDate.of(2026, 9, 7)
        val used = AtomicReference<TemplateUse?>()
        val deleted = AtomicReference<TemplateId?>()

        composeRule.setContent {
            OpenTasksTheme {
                MoreScreen(
                    tasks = emptyList(),
                    projects = OpenTasksFixtures.snapshot.projects,
                    templates = listOf(template),
                    today = today,
                    onRestoreProject = {},
                    onRestoreTask = {},
                    onPermanentlyDeleteTask = {},
                    onUseTemplate = { templateId, name, anchorDate ->
                        used.set(TemplateUse(templateId, name, anchorDate))
                    },
                    onDeleteTemplate = deleted::set,
                )
            }
        }

        composeRule.onNodeWithTag("open-templates")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("templates-screen").assertIsDisplayed()
        composeRule.onNodeWithText(template.name).assertIsDisplayed()
        composeRule.onNodeWithTag("use-template-${template.id.value}").performClick()
        composeRule.onNodeWithTag("use-template-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("template-project-name")
            .performTextReplacement("Autumn client launch")
        composeRule.onNodeWithTag("confirm-use-template").performClick()

        assertEquals(
            TemplateUse(template.id, "Autumn client launch", today),
            used.get(),
        )

        composeRule.onNodeWithTag("delete-template-${template.id.value}").performClick()
        assertEquals(template.id, deleted.get())
    }

    private data class TemplateUse(
        val templateId: TemplateId,
        val projectName: String,
        val anchorDate: LocalDate,
    )
}
