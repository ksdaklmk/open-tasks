package app.opentasks.feature.tasks

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskGroup
import app.opentasks.core.model.TaskGroupKey
import app.opentasks.core.model.TaskGroupValue
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TaskSortKey
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TasksArrangementInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun suppliedGroupsKeepTheirOrderFilterWithinGroupsAndUseOpaqueKeys() {
        val base = OpenTasksFixtures.tasks.first().copy(
            completedAt = null,
            deletedAt = null,
        )
        val today = base.copy(id = TaskId("today"), title = "Today arranged")
        val week = base.copy(id = TaskId("week"), title = "Week arranged")
        val laterFirst = base.copy(id = TaskId("later-first"), title = "Later first arranged")
        val laterSecond = base.copy(id = TaskId("later-second"), title = "Later second arranged")
        val overdue = base.copy(id = TaskId("overdue"), title = "Overdue arranged")
        val inbox = base.copy(id = TaskId("inbox"), title = "Inbox arranged", projectId = null)
        val namedInbox = base.copy(
            id = TaskId("project:inbox"),
            title = "Named inbox arranged",
            projectId = ProjectId("inbox"),
        )
        val flat = base.copy(id = TaskId("flat"), title = "Flat arranged")
        val high = base.copy(id = TaskId("high"), title = "High arranged", priority = Priority.HIGH)
        val groups = listOf(
            TaskGroup(TaskGroupValue.Due(DueBucket.TODAY), listOf(today)),
            TaskGroup(TaskGroupValue.Due(DueBucket.LATER), listOf(laterFirst, laterSecond)),
            TaskGroup(TaskGroupValue.Due(DueBucket.THIS_WEEK), listOf(week)),
            TaskGroup(TaskGroupValue.Due(DueBucket.OVERDUE), listOf(overdue)),
            TaskGroup(TaskGroupValue.Project(null), listOf(inbox)),
            TaskGroup(TaskGroupValue.Project(ProjectId("inbox")), listOf(namedInbox)),
            TaskGroup(null, listOf(flat)),
            TaskGroup(TaskGroupValue.PriorityValue(Priority.HIGH), listOf(high)),
        )

        setContent(
            tasks = listOf(
                high,
                flat,
                namedInbox,
                inbox,
                overdue,
                week,
                laterSecond,
                laterFirst,
                today,
            ),
            taskGroups = groups,
            dueBuckets = mapOf(
                today.id to DueBucket.TODAY,
                week.id to DueBucket.THIS_WEEK,
                laterFirst.id to DueBucket.LATER,
                laterSecond.id to DueBucket.LATER,
                overdue.id to DueBucket.OVERDUE,
                inbox.id to DueBucket.NO_DATE,
                namedInbox.id to DueBucket.NO_DATE,
                high.id to DueBucket.NO_DATE,
            ),
            projectNames = mapOf(ProjectId("inbox") to "Named inbox project"),
        )

        composeRule.onNodeWithText(context.getString(R.string.tasks_group_due_today))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.tasks_group_inbox))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Named inbox project").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.tasks_group_priority_high),
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(today.title).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(namedInbox.title).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(flat.title).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("null").assertDoesNotExist()

        composeRule.onNodeWithTag("task-filter-today").performClick()
        composeRule.onNodeWithText(today.title).assertIsDisplayed()
        composeRule.onNodeWithText(inbox.title).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.tasks_group_inbox))
            .assertDoesNotExist()

        composeRule.onNodeWithTag("task-filter-upcoming").performClick()
        val laterFirstTop = composeRule.onNodeWithText(laterFirst.title)
            .getUnclippedBoundsInRoot().top
        val laterSecondTop = composeRule.onNodeWithText(laterSecond.title)
            .getUnclippedBoundsInRoot().top
        val weekTop = composeRule.onNodeWithText(week.title).getUnclippedBoundsInRoot().top
        assertTrue(laterFirstTop < laterSecondTop)
        assertTrue(laterSecondTop < weekTop)
        composeRule.onNodeWithText(week.title).assertIsDisplayed()
        composeRule.onNodeWithText(laterFirst.title).assertIsDisplayed()
        composeRule.onNodeWithText(laterSecond.title).assertIsDisplayed()
        composeRule.onNodeWithText(overdue.title).assertDoesNotExist()

        composeRule.onNodeWithTag("task-filter-overdue").performClick()
        composeRule.onNodeWithText(overdue.title).assertIsDisplayed()
        composeRule.onNodeWithText(week.title).assertDoesNotExist()

        composeRule.onNodeWithTag("task-filter-inbox").performClick()
        composeRule.onNodeWithText(inbox.title).assertIsDisplayed()
        composeRule.onNodeWithText(namedInbox.title).assertDoesNotExist()
    }

    @Test
    fun suppliedGroupOrderOverridesRawTaskOrder() {
        val base = OpenTasksFixtures.tasks.first().copy(completedAt = null, deletedAt = null)
        val firstInSnapshot = base.copy(id = TaskId("snapshot-first"), title = "Snapshot first")
        val firstInGroups = base.copy(id = TaskId("group-first"), title = "Group first")

        setContent(
            tasks = listOf(firstInSnapshot, firstInGroups),
            taskGroups = listOf(
                TaskGroup(TaskGroupValue.Due(DueBucket.LATER), listOf(firstInGroups)),
                TaskGroup(TaskGroupValue.Due(DueBucket.TODAY), listOf(firstInSnapshot)),
            ),
        )

        val groupFirstTop = composeRule.onNodeWithText(firstInGroups.title)
            .getUnclippedBoundsInRoot().top
        val snapshotFirstTop = composeRule.onNodeWithText(firstInSnapshot.title)
            .getUnclippedBoundsInRoot().top
        assertTrue(groupFirstTop < snapshotFirstTop)
    }

    @Test
    fun controlsAreStatelessAndReportEveryArrangementChoice() {
        val selectedSort = AtomicReference<TaskSortKey?>()
        val selectedGroup = AtomicReference<TaskGroupKey?>()

        setContent(
            tasks = listOf(OpenTasksFixtures.tasks.first()),
            taskSort = TaskSortKey.UPDATED,
            taskGroupBy = TaskGroupKey.PROJECT,
            onTaskSortChange = selectedSort::set,
            onTaskGroupChange = selectedGroup::set,
        )

        composeRule.onNodeWithTag("tasks-sort-control")
            .assertContentDescriptionEquals("Sort tasks: Updated")
            .performClick()
        composeRule.onNodeWithTag("tasks-sort-option-updated").assertIsSelected()
        composeRule.onNodeWithTag("tasks-sort-option-due").performClick()
        assertEquals(TaskSortKey.DUE, selectedSort.get())
        composeRule.onNodeWithTag("tasks-sort-control")
            .assertContentDescriptionEquals("Sort tasks: Updated")
            .performClick()
        composeRule.onNodeWithTag("tasks-sort-option-updated").assertIsSelected()
        composeRule.onNodeWithTag("tasks-sort-option-due").assertIsNotSelected()
        composeRule.onNodeWithTag("tasks-sort-option-updated").performClick()
        TaskSortKey.entries.forEach { sort ->
            composeRule.onNodeWithTag("tasks-sort-control").performClick()
            composeRule.onNodeWithTag("tasks-sort-option-${sort.name.lowercase()}")
                .performClick()
            assertEquals(sort, selectedSort.get())
        }

        composeRule.onNodeWithTag("tasks-group-control")
            .assertContentDescriptionEquals("Group tasks: Project")
            .performClick()
        composeRule.onNodeWithTag("tasks-group-option-project").assertIsSelected()
        composeRule.onNodeWithTag("tasks-group-option-none").performClick()
        assertEquals(null, selectedGroup.get())
        composeRule.onNodeWithTag("tasks-group-control")
            .assertContentDescriptionEquals("Group tasks: Project")
            .performClick()
        composeRule.onNodeWithTag("tasks-group-option-project").assertIsSelected()
        composeRule.onNodeWithTag("tasks-group-option-none").assertIsNotSelected()
        composeRule.onNodeWithTag("tasks-group-option-project").performClick()
        listOf(null, TaskGroupKey.DUE_BUCKET, TaskGroupKey.PROJECT, TaskGroupKey.PRIORITY)
            .forEach { groupBy ->
                val tag = when (groupBy) {
                    null -> "tasks-group-option-none"
                    else -> "tasks-group-option-${groupBy.name.lowercase()}"
                }
                composeRule.onNodeWithTag("tasks-group-control").performClick()
                composeRule.onNodeWithTag(tag).performClick()
                assertEquals(groupBy, selectedGroup.get())
            }
    }

    private fun setContent(
        tasks: List<Task>,
        taskGroups: List<TaskGroup> = listOf(TaskGroup(null, tasks)),
        dueBuckets: Map<TaskId, DueBucket> = emptyMap(),
        projectNames: Map<ProjectId, String> = emptyMap(),
        taskSort: TaskSortKey = TaskSortKey.DUE,
        taskGroupBy: TaskGroupKey? = null,
        onTaskSortChange: (TaskSortKey) -> Unit = {},
        onTaskGroupChange: (TaskGroupKey?) -> Unit = {},
    ) {
        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = tasks,
                    taskGroups = taskGroups,
                    taskSort = taskSort,
                    taskGroupBy = taskGroupBy,
                    onTaskSortChange = onTaskSortChange,
                    onTaskGroupChange = onTaskGroupChange,
                    dueBucketsByTaskId = dueBuckets,
                    projectNames = projectNames,
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
                )
            }
        }
    }
}
