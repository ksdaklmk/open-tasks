package app.opentasks.feature.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskDetailSubtasksInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    @Test
    fun detailShowsSubtasksWithQuickAddAttachAndDetach() {
        val parent = BASE.copy(id = TaskId("subtask-parent"), title = "Parent task")
        val child = BASE.copy(
            id = TaskId("subtask-child-1"),
            title = "Child one",
            parentTaskId = parent.id,
        )
        val secondChild = BASE.copy(
            id = TaskId("subtask-child-2"),
            title = "Child two",
            parentTaskId = parent.id,
        )
        val candidate = BASE.copy(id = TaskId("subtask-candidate"), title = "Candidate task")
        var added: String? = null
        var attached: TaskId? = null
        var detached: TaskId? = null

        setContent(
            task = parent,
            subtasks = listOf(child, secondChild),
            attachableSubtasks = listOf(candidate),
            onAddSubtask = { added = it },
            onAttachSubtask = { attached = it },
            onDetachSubtask = { detached = it },
        )

        composeRule.onNodeWithTag("subtask-quick-add").performScrollTo().performTextInput("New step")
        composeRule.onNodeWithTag("subtask-quick-add-confirm").performClick()
        assertEquals("New step", added)

        composeRule.onNodeWithTag("subtask-attach").performScrollTo().performClick()
        composeRule.onNodeWithTag("subtask-attach-${candidate.id.value}").performClick()
        assertEquals(candidate.id, attached)

        composeRule.onNodeWithTag("subtask-detach-${child.id.value}")
            .performScrollTo()
            .performClick()
        assertEquals(child.id, detached)
    }

    @Test
    fun subtaskErrorRendersInlineAndClearsOnInput() {
        val parent = BASE.copy(id = TaskId("subtask-parent-2"), title = "Parent two")
        var cleared = false

        setContent(
            task = parent,
            subtaskError = "Subtasks go one level deep — that task is already a subtask.",
            onClearSubtaskError = { cleared = true },
        )

        composeRule.onNodeWithTag("subtask-quick-add").performScrollTo()
        composeRule
            .onNodeWithText("Subtasks go one level deep — that task is already a subtask.")
            .assertIsDisplayed()

        composeRule.onNodeWithTag("subtask-quick-add").performTextInput("x")
        assertTrue(cleared)
    }

    private fun setContent(
        task: Task,
        subtasks: List<Task> = emptyList(),
        attachableSubtasks: List<Task> = emptyList(),
        subtaskError: String? = null,
        onAddSubtask: (String) -> Unit = {},
        onAttachSubtask: (TaskId) -> Unit = {},
        onDetachSubtask: (TaskId) -> Unit = {},
        onClearSubtaskError: () -> Unit = {},
    ) {
        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task),
                    projectNames = emptyMap(),
                    workflowStatuses = OpenTasksFixtures.workflowStatuses,
                    tags = OpenTasksFixtures.tags,
                    selectedTaskId = task.id,
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
                    subtasks = subtasks,
                    attachableSubtasks = attachableSubtasks,
                    subtaskError = subtaskError,
                    onAddSubtask = { _, title -> onAddSubtask(title) },
                    onAttachSubtask = { _, candidateId -> onAttachSubtask(candidateId) },
                    onDetachSubtask = onDetachSubtask,
                    onClearSubtaskError = onClearSubtaskError,
                )
            }
        }
    }

    private companion object {
        val BASE = OpenTasksFixtures.tasks.first().copy(
            checklist = emptyList(),
            completedAt = null,
            deletedAt = null,
        )
    }
}
