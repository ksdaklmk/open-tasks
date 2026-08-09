package app.opentasks.feature.projects

import android.view.ViewConfiguration
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatusId
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoardViewInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun moveMenuDispatchesTaskAndTargetStatus() {
        val task = OpenTasksFixtures.tasks
            .first { it.id.value == "task-proposal" }
            .copy(
                statusId = OpenTasksFixtures.backlog,
                semanticStatus = SemanticStatus.BACKLOG,
            )
        val moved = AtomicReference<Pair<TaskId, WorkflowStatusId>?>()

        composeRule.setContent {
            OpenTasksTheme {
                BoardView(
                    columns = boardColumns(
                        project = OpenTasksFixtures.studioProject,
                        statuses = OpenTasksFixtures.workflowStatuses,
                        tasks = listOf(task),
                    ),
                    columnWidth = 272.dp,
                    onMoveTask = { taskId, statusId -> moved.set(taskId to statusId) },
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("board-move-${task.id.value}").performClick()
        composeRule.onNodeWithTag(
            "board-move-${task.id.value}-to-${OpenTasksFixtures.planned.value}",
        ).performClick()

        assertEquals(task.id to OpenTasksFixtures.planned, moved.get())
    }

    @Test
    fun longPressDragDispatchesTaskAndTargetStatus() {
        val task = OpenTasksFixtures.tasks
            .first { it.id.value == "task-proposal" }
            .copy(
                statusId = OpenTasksFixtures.backlog,
                semanticStatus = SemanticStatus.BACKLOG,
            )
        val staleMove = AtomicReference<Pair<TaskId, WorkflowStatusId>?>()
        val moved = AtomicReference<Pair<TaskId, WorkflowStatusId>?>()
        val onMoveTask = mutableStateOf<(TaskId, WorkflowStatusId) -> Unit>(
            { taskId, statusId -> staleMove.set(taskId to statusId) },
        )

        composeRule.setContent {
            OpenTasksTheme {
                BoardView(
                    columns = boardColumns(
                        project = OpenTasksFixtures.studioProject,
                        statuses = OpenTasksFixtures.workflowStatuses,
                        tasks = listOf(task),
                    ),
                    columnWidth = 160.dp,
                    onMoveTask = onMoveTask.value,
                    onOpenTask = {},
                )
            }
        }
        val cardBounds = composeRule
            .onNodeWithTag("board-card-${task.id.value}")
            .fetchSemanticsNode()
            .boundsInRoot
        val targetBounds = composeRule
            .onNodeWithTag("board-column-${OpenTasksFixtures.planned.value}")
            .fetchSemanticsNode()
            .boundsInRoot

        composeRule.onRoot().performTouchInput {
            down(cardBounds.center)
            advanceEventTime(ViewConfiguration.getLongPressTimeout().toLong() + 1)
            moveTo(targetBounds.center)
        }
        composeRule.onNodeWithTag("board-drag-preview-${task.id.value}")
            .assertIsDisplayed()
        composeRule.runOnIdle {
            onMoveTask.value = { taskId, statusId -> moved.set(taskId to statusId) }
        }
        composeRule.onRoot().performTouchInput {
            up()
        }

        assertNull(staleMove.get())
        assertEquals(task.id to OpenTasksFixtures.planned, moved.get())
    }
}
