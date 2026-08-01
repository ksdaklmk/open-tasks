package app.opentasks.feature.tasks

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
class TaskPaneFractionInstrumentedTest {
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
                    TasksScreen(
                        tasks = OpenTasksFixtures.tasks,
                        reminders = emptyList(),
                        projectNames = emptyMap(),
                        activeProjectIds = emptySet(),
                        workflowStatuses = OpenTasksFixtures.workflowStatuses,
                        tags = emptyList(),
                        milestones = emptyList(),
                        selectedTaskId = OpenTasksFixtures.tasks.first().id,
                        showDetailPane = true,
                        listPaneFraction = listPaneFraction,
                        onSelectTask = {},
                        onCloseDetail = {},
                        onCompleteTask = {},
                        onChangeTaskStatus = { _, _ -> },
                        onDeleteTask = {},
                        activeTimerTaskId = null,
                        onToggleTimer = {},
                        onUpdateTask = { _, _ -> },
                        onAddChecklistItem = { _, _ -> },
                        onUpdateChecklistItem = { _, _ -> },
                        onDeleteChecklistItem = { _, _ -> },
                        onSetTaskTag = { _, _, _ -> },
                        onCreateAndAssignTag = { _, _ -> },
                    )
                }
            }
        }
    }
}
