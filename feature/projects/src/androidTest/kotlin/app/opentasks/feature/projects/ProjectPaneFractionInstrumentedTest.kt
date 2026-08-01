package app.opentasks.feature.projects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
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

    private fun setContent(listPaneFraction: Float) {
        composeRule.setContent {
            OpenTasksTheme {
                Box(
                    modifier = Modifier
                        .requiredWidth(1_000.dp)
                        .testTag("paneHost"),
                ) {
                    ProjectsScreen(
                        projects = OpenTasksFixtures.snapshot.projects,
                        tasks = OpenTasksFixtures.tasks,
                        milestones = OpenTasksFixtures.milestones,
                        workflowStatuses = OpenTasksFixtures.workflowStatuses,
                        selectedProjectId = OpenTasksFixtures.studioProject.id,
                        showDetailPane = true,
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
