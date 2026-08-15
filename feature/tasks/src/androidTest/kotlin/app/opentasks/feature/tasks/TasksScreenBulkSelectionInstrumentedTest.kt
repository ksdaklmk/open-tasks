package app.opentasks.feature.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TasksScreenBulkSelectionInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    @Test
    fun longPressSelectsTapExtendsSelectionAndCompleteFiresOnce() {
        val tasks = OpenTasksFixtures.tasks.filter { it.deletedAt == null && !it.isCompleted }
        val first = tasks[0]
        val second = tasks[1]
        val openCalls = AtomicInteger(0)
        val completeCalls = AtomicInteger(0)
        var selection by mutableStateOf(emptySet<TaskId>())

        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = tasks,
                    projectNames = OpenTasksFixtures.snapshot.projects.associate {
                        it.id to it.name
                    },
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = OpenTasksFixtures.snapshot.tags,
                    selectedTaskId = null,
                    showDetailPane = false,
                    onSelectTask = { openCalls.incrementAndGet() },
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
                    selectedBulkIds = selection,
                    onToggleBulkSelection = { taskId ->
                        selection = if (taskId in selection) {
                            selection - taskId
                        } else {
                            selection + taskId
                        }
                    },
                    onBulkComplete = { completeCalls.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithText(first.title).performTouchInput { longClick() }

        composeRule.onNodeWithTag("bulk-clear").assertIsDisplayed()
        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeRule.onNodeWithTag("bulk-check-${first.id.value}").assertIsOn()

        composeRule.onNodeWithText(second.title).performClick()

        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeRule.onNodeWithTag("bulk-check-${second.id.value}").assertIsOn()
        assertEquals(0, openCalls.get())

        composeRule.onNodeWithTag("bulk-complete").performClick()

        assertEquals(1, completeCalls.get())
    }
}
