package app.opentasks.feature.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskPaneFractionInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    @Test
    fun listPaneOccupiesProvidedFraction() {
        setContent(listPaneFraction = 0.38f)

        assertPaneWidths(listWidthDp = 380f, detailWidthDp = 620f)
    }

    @Test
    fun listPaneOccupiesHingeSnapFraction() {
        setContent(listPaneFraction = 0.5f)

        assertPaneWidths(listWidthDp = 500f, detailWidthDp = 500f)
    }

    private fun assertPaneWidths(listWidthDp: Float, detailWidthDp: Float) {
        val listWidth = composeRule.onNodeWithTag("listPane", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .width
            .value
        val detailWidth = composeRule.onNodeWithTag("detailPane", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .width
            .value
        assertEquals(listWidthDp, listWidth, 1f)
        assertEquals(detailWidthDp, detailWidth, 1f)
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
