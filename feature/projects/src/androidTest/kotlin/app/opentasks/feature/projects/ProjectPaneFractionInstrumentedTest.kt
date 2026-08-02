package app.opentasks.feature.projects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectPaneFractionInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listPaneOccupiesProvidedFraction() {
        setContent(listPaneFraction = 0.38f)

        composeRule.onNodeWithTag("listPane", useUnmergedTree = true)
            .assertWidthIsAtLeast(370.dp)
        composeRule.onNodeWithTag("detailPane", useUnmergedTree = true).assertExists()
    }

    @Test
    fun listPaneOccupiesHingeSnapFraction() {
        setContent(listPaneFraction = 0.5f)

        composeRule.onNodeWithTag("listPane", useUnmergedTree = true)
            .assertWidthIsAtLeast(490.dp)
        composeRule.onNodeWithTag("detailPane", useUnmergedTree = true).assertExists()
    }

    @Test
    fun fold8MainLargeTextKeepsProjectTitleClearOfProgressCount() {
        setContent(
            listPaneFraction = 0.42f,
            hostWidth = 743.dp,
            fontScale = 2f,
            projects = listOf(OpenTasksFixtures.researchProject),
        )

        val title = composeRule.onNodeWithText("Client research", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val count = composeRule.onNodeWithText("0/2", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "At Fold 8 main width and 200% text, the title must end before the count",
            title.right < count.left,
        )
    }

    @Test
    fun fold8CoverLargeTextStacksSaveStatusBelowProjectHeading() {
        setContent(
            listPaneFraction = 0.42f,
            hostWidth = 411.dp,
            fontScale = 2f,
            projects = listOf(OpenTasksFixtures.researchProject),
            selectedProjectId = OpenTasksFixtures.researchProject.id,
            showDetailPane = false,
        )

        val heading = composeRule.onNode(
            hasText("Client research") and isHeading(),
            useUnmergedTree = true,
        )
            .getUnclippedBoundsInRoot()
        val status = composeRule.onNodeWithText("Saved on this device", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "At Fold 8 cover width and 200% text, save status must follow the project heading",
            status.top >= heading.bottom,
        )
    }

    private fun setContent(
        listPaneFraction: Float,
        hostWidth: Dp = 1_000.dp,
        fontScale: Float = 1f,
        projects: List<Project> = OpenTasksFixtures.snapshot.projects,
        selectedProjectId: ProjectId = OpenTasksFixtures.studioProject.id,
        showDetailPane: Boolean = true,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                OpenTasksTheme {
                    Box(
                        modifier = Modifier
                            .requiredWidth(hostWidth)
                            .testTag("paneHost"),
                    ) {
                        ProjectsScreen(
                            projects = projects,
                            tasks = OpenTasksFixtures.tasks,
                            milestones = OpenTasksFixtures.milestones,
                            workflowStatuses = OpenTasksFixtures.workflowStatuses,
                            selectedProjectId = selectedProjectId,
                            showDetailPane = showDetailPane,
                            listPaneFraction = listPaneFraction,
                            onSelectProject = {},
                            onCloseDetail = {},
                            onUpdateProject = { _, _ -> },
                            onArchiveProject = {},
                            onOpenTask = {},
                        )
                    }
                }
            }
        }
    }
}
