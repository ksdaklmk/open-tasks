package app.opentasks.feature.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TaskId
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TasksDateFilterInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dateChipsUseOnlyTheSuppliedSemanticBuckets() {
        val base = OpenTasksFixtures.tasks.first().copy(
            projectId = OpenTasksFixtures.studioProject.id,
            completedAt = null,
            deletedAt = null,
        )
        val today = base.copy(id = TaskId("date-today"), title = "Today ruled")
        val week = base.copy(id = TaskId("date-week"), title = "Week ruled")
        val later = base.copy(id = TaskId("date-later"), title = "Later ruled")
        val overdue = base.copy(id = TaskId("date-overdue"), title = "Overdue ruled")
        val completed = base.copy(
            id = TaskId("date-completed"),
            title = "Completed overdue",
            semanticStatus = SemanticStatus.COMPLETED,
            completedAt = Instant.parse("2026-08-08T03:00:00Z"),
        )
        val deleted = base.copy(
            id = TaskId("date-deleted"),
            title = "Deleted overdue",
            deletedAt = Instant.parse("2026-08-08T03:00:00Z"),
        )
        val inbox = base.copy(
            id = TaskId("date-inbox"),
            title = "Inbox ruled",
            projectId = null,
        )
        val tasks = listOf(today, week, later, overdue, completed, deleted, inbox)
        val buckets = mapOf(
            today.id to DueBucket.TODAY,
            week.id to DueBucket.THIS_WEEK,
            later.id to DueBucket.LATER,
            overdue.id to DueBucket.OVERDUE,
            completed.id to DueBucket.OVERDUE,
            deleted.id to DueBucket.OVERDUE,
            inbox.id to DueBucket.NO_DATE,
        )

        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = tasks,
                    projectNames = OpenTasksFixtures.snapshot.projects.associate {
                        it.id to it.name
                    },
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
                    dueBucketsByTaskId = buckets,
                )
            }
        }

        composeRule.onNodeWithTag("task-filter-today").performClick()
        composeRule.onNodeWithText(today.title).assertIsDisplayed()
        composeRule.onNodeWithText(overdue.title).assertDoesNotExist()

        composeRule.onNodeWithTag("task-filter-upcoming").performClick()
        composeRule.onNodeWithText(week.title).assertIsDisplayed()
        composeRule.onNodeWithText(later.title).assertIsDisplayed()

        composeRule.onNodeWithTag("task-filter-overdue").performClick()
        composeRule.onNodeWithText(overdue.title).assertIsDisplayed()
        composeRule.onNodeWithText(completed.title).assertDoesNotExist()
        composeRule.onNodeWithText(deleted.title).assertDoesNotExist()

        composeRule.onNodeWithTag("task-filter-inbox").performClick()
        composeRule.onNodeWithText(inbox.title).assertIsDisplayed()
        composeRule.onNodeWithTag("task-filter-all").performClick()
        composeRule.onNodeWithText(deleted.title).assertDoesNotExist()
    }
}
