package app.opentasks.feature.projects

import android.view.ViewConfiguration
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.BoardColumn
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatus
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
    fun duplicateMenuEmitsTaskIdAndKeepsMoveTargets() {
        val task = OpenTasksFixtures.tasks.first { it.id.value == "task-proposal" }
        val duplicated = AtomicReference<TaskId?>()
        val moved = AtomicReference<Pair<TaskId, WorkflowStatusId>?>()
        val backlog = OpenTasksFixtures.workflowStatuses.single {
            it.id == OpenTasksFixtures.backlog
        }
        val planned = OpenTasksFixtures.workflowStatuses.single {
            it.id == OpenTasksFixtures.planned
        }

        composeRule.setContent {
            OpenTasksTheme {
                BoardView(
                    columns = listOf(
                        BoardColumn(
                            status = backlog,
                            tasks = listOf(
                                task.copy(
                                    projectId = OpenTasksFixtures.studioProject.id,
                                    statusId = backlog.id,
                                    semanticStatus = backlog.semanticStatus,
                                ),
                            ),
                        ),
                        BoardColumn(status = planned, tasks = emptyList()),
                    ),
                    columnWidth = 272.dp,
                    onMoveTask = { taskId, statusId -> moved.set(taskId to statusId) },
                    onOpenTask = {},
                    onDuplicateTask = duplicated::set,
                )
            }
        }

        composeRule.onNodeWithTag("board-move-${task.id.value}").performClick()
        composeRule.onNodeWithTag("board-duplicate-${task.id.value}")
            .assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals("Duplicate ${task.title}")
            .performClick()
        assertEquals(task.id, duplicated.get())

        composeRule.onNodeWithTag("board-duplicate-${task.id.value}").assertDoesNotExist()
        composeRule.onNodeWithTag("board-move-${task.id.value}").performClick()
        composeRule.onNodeWithTag("board-duplicate-${task.id.value}").assertIsDisplayed()
        composeRule.onNodeWithTag(
            "board-move-${task.id.value}-to-${OpenTasksFixtures.planned.value}",
        ).performClick()
        assertEquals(task.id to OpenTasksFixtures.planned, moved.get())
    }

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
                    columns = columnsFor(task),
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
    @OptIn(ExperimentalTestApi::class)
    fun customAccessibilityActionDispatchesTaskAndTargetStatus() {
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
                    columns = columnsFor(task),
                    columnWidth = 272.dp,
                    onMoveTask = { taskId, statusId -> moved.set(taskId to statusId) },
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("board-card-${task.id.value}")
            .performCustomAccessibilityActionWithLabel(
                "Move ${task.title} to Planned",
            )

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
                    columns = columnsFor(task),
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
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput {
            up()
        }

        assertNull(staleMove.get())
        assertEquals(task.id to OpenTasksFixtures.planned, moved.get())
    }

    private fun columnsFor(task: Task): List<BoardColumn> =
        OpenTasksFixtures.workflowStatuses
            .filter {
                it.projectId == OpenTasksFixtures.studioProject.id && it.archivedAt == null
            }
            .sortedBy(WorkflowStatus::rank)
            .map { status ->
                BoardColumn(
                    status = status,
                    tasks = listOf(task).filter { it.statusId == status.id },
                )
            }
}
