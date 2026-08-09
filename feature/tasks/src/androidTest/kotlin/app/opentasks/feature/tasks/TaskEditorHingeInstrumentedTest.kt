package app.opentasks.feature.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskEditorHingeInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editorContentClearsHingeExclusionBand() {
        setContent(hingeExclusionBandDp = 400..440)

        assertTwoPaneFixture()
        val fixtureTop = composeRule.onNodeWithTag("hinge-fixture")
            .getUnclippedBoundsInRoot()
            .top
        val top = composeRule.onNodeWithTag("editorSheetContent")
            .getUnclippedBoundsInRoot()
            .top - fixtureTop

        assertTrue("Editor content top $top should clear the 440 dp hinge band", top >= 440.dp)
    }

    @Test
    fun editorContentIsUnconstrainedWithoutHingeExclusionBand() {
        setContent(hingeExclusionBandDp = null)

        assertTwoPaneFixture()
        val fixtureTop = composeRule.onNodeWithTag("hinge-fixture")
            .getUnclippedBoundsInRoot()
            .top
        val top = composeRule.onNodeWithTag("editorSheetContent")
            .getUnclippedBoundsInRoot()
            .top - fixtureTop

        assertTrue("Editor content top $top should remain above 440 dp", top < 440.dp)
    }

    @Test
    fun editorOnlyPaneKeepsLegacyDetailScrollSemantics() {
        setContent(hingeExclusionBandDp = null, showDetailPane = false)

        composeRule.onNodeWithTag("task-detail-scroll").fetchSemanticsNode()
    }

    private fun assertTwoPaneFixture() {
        composeRule.onNodeWithTag("listPane", useUnmergedTree = true).fetchSemanticsNode()
        composeRule.onNodeWithTag("detailPane", useUnmergedTree = true).fetchSemanticsNode()
    }

    private fun setContent(
        hingeExclusionBandDp: IntRange?,
        showDetailPane: Boolean = true,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            OpenTasksTheme {
                Box(
                    modifier = Modifier
                        .requiredSize(width = 900.dp, height = 840.dp)
                        .testTag("hinge-fixture"),
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
                        showDetailPane = showDetailPane,
                        hingeExclusionBandDp = hingeExclusionBandDp,
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
        composeRule.mainClock.advanceTimeBy(700)
    }
}
