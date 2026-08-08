package app.opentasks.feature.more

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ReviewQueue
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun keepAdvancesToImmediatelyFollowingTaskWhenQueueChanges() {
        val first = task("first", "First")
        val second = task("second", "Second")
        composeRule.setContent {
            var queue by mutableStateOf(ReviewQueue(emptyList(), emptyList(), listOf(first, second), emptyList()))
            var reviewed by mutableStateOf(emptySet<TaskId>())
            OpenTasksTheme {
                ReviewScreen(
                    queue = queue,
                    projectNames = emptyMap(),
                    reviewedTaskIds = reviewed,
                    reviewedProjectIds = emptySet(),
                    actionPending = false,
                    onBack = {},
                    onCompleteTask = { _, _ -> },
                    onRescheduleTask = { _, _ -> },
                    onKeepTask = { id ->
                        reviewed = reviewed + id
                        queue = queue.copy(unscheduled = queue.unscheduled.drop(1))
                    },
                    onBinTask = {},
                    onKeepProject = {},
                    onArchiveProject = {},
                )
            }
        }

        composeRule.onNodeWithText("First").assertIsDisplayed()
        composeRule.onNodeWithTag("review-keep").performClick()
        composeRule.onNodeWithText("Second").assertIsDisplayed()
        composeRule.onNodeWithText("First").assertDoesNotExist()
    }

    @Test
    fun rejectedActionLeavesCurrentCardVisible() {
        val first = task("first", "First")
        composeRule.setContent {
            OpenTasksTheme {
                ReviewScreen(
                    queue = ReviewQueue(emptyList(), emptyList(), listOf(first), emptyList()),
                    projectNames = emptyMap(),
                    reviewedTaskIds = emptySet(),
                    reviewedProjectIds = emptySet(),
                    actionPending = false,
                    onBack = {},
                    onCompleteTask = { _, _ -> },
                    onRescheduleTask = { _, _ -> },
                    onKeepTask = {},
                    onBinTask = {},
                    onKeepProject = {},
                    onArchiveProject = {},
                )
            }
        }

        composeRule.onNodeWithTag("review-keep").performClick()
        composeRule.onNodeWithText("First").assertIsDisplayed()
    }

    @Test
    fun allDoneFinishCallsBack() {
        val task = task("only", "Only")
        var finishCount = 0
        composeRule.setContent {
            var reviewed by mutableStateOf(emptySet<TaskId>())
            OpenTasksTheme {
                ReviewScreen(
                    queue = ReviewQueue(emptyList(), emptyList(), listOf(task), emptyList()),
                    projectNames = emptyMap(),
                    reviewedTaskIds = reviewed,
                    reviewedProjectIds = emptySet(),
                    actionPending = false,
                    onBack = { finishCount++ },
                    onCompleteTask = { _, _ -> },
                    onRescheduleTask = { _, _ -> },
                    onKeepTask = { reviewed = reviewed + it },
                    onBinTask = {},
                    onKeepProject = {},
                    onArchiveProject = {},
                )
            }
        }

        composeRule.onNodeWithTag("review-keep").performClick()
        composeRule.onNodeWithTag("review-finish").performClick()
        assertEquals(1, finishCount)
    }

    private fun task(id: String, title: String): Task =
        OpenTasksFixtures.tasks.first().copy(id = TaskId(id), title = title)
}
