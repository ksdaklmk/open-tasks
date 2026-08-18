package app.opentasks.feature.tasks

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.designsystem.R as DesignSystemR
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.TaskGroup
import app.opentasks.core.model.TaskId
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskStaleBadgeInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun staleTaskIdsMembershipExposesTheBadgeOnlyForMatchingRows() {
        val base = OpenTasksFixtures.tasks.first().copy(completedAt = null, deletedAt = null)
        val stale = base.copy(id = TaskId("stale-task"), title = "Stale task")
        val fresh = base.copy(id = TaskId("fresh-task"), title = "Fresh task")

        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(stale, fresh),
                    taskGroups = listOf(TaskGroup(null, listOf(stale, fresh))),
                    projectNames = emptyMap(),
                    workflowStatuses = OpenTasksFixtures.workflowStatuses,
                    tags = OpenTasksFixtures.tags,
                    selectedTaskId = null,
                    showDetailPane = false,
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
                    staleTaskIds = setOf(stale.id),
                )
            }
        }

        composeRule
            .onAllNodesWithContentDescription(
                context.getString(DesignSystemR.string.task_stale_description),
            )
            .assertCountEquals(1)
    }
}
