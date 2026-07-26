package app.opentasks.feature.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.WorkflowStatusId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TaskEditorInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun validTitleAutoSavesAfterDebounce() {
        val task = OpenTasksFixtures.tasks.first()
        val submitted = AtomicReference<TaskEdit?>()
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task),
                    projectNames = OpenTasksFixtures.snapshot.projects.associate {
                        it.id to it.name
                    },
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = OpenTasksFixtures.snapshot.tags,
                    selectedTaskId = task.id,
                    showDetailPane = false,
                    onSelectTask = {},
                    onCloseDetail = {},
                    onCompleteTask = {},
                    onChangeTaskStatus = { _, _ -> },
                    onDeleteTask = {},
                    activeTimerTaskId = null,
                    onToggleTimer = {},
                    onUpdateTask = { _, edit -> submitted.set(edit) },
                    onAddChecklistItem = { _, _ -> },
                    onUpdateChecklistItem = { _, _ -> },
                    onDeleteChecklistItem = { _, _ -> },
                    onSetTaskTag = { _, _, _ -> },
                    onCreateAndAssignTag = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Saved on this device").assertIsDisplayed()
        composeRule.onNodeWithTag("task-title-field")
            .performTextReplacement("Review launch proposal")
        composeRule.mainClock.advanceTimeBy(700)
        composeRule.waitForIdle()

        assertEquals("Review launch proposal", submitted.get()?.title)
    }

    @Test
    fun emptyTitleShowsErrorAndDoesNotSubmit() {
        val task = OpenTasksFixtures.tasks.first()
        val submitted = AtomicReference<TaskEdit?>()
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task),
                    projectNames = emptyMap(),
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = OpenTasksFixtures.snapshot.tags,
                    selectedTaskId = task.id,
                    showDetailPane = false,
                    onSelectTask = {},
                    onCloseDetail = {},
                    onCompleteTask = {},
                    onChangeTaskStatus = { _, _ -> },
                    onDeleteTask = {},
                    activeTimerTaskId = null,
                    onToggleTimer = {},
                    onUpdateTask = { _, edit -> submitted.set(edit) },
                    onAddChecklistItem = { _, _ -> },
                    onUpdateChecklistItem = { _, _ -> },
                    onDeleteChecklistItem = { _, _ -> },
                    onSetTaskTag = { _, _, _ -> },
                    onCreateAndAssignTag = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("task-title-field").performTextReplacement("")
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("A task needs a title").assertIsDisplayed()
        composeRule.onNodeWithText("Fix fields to save").assertIsDisplayed()
        assertNull(submitted.get())
    }

    @Test
    fun weeklyRecurrenceBuilderAutoSavesCompleteRule() {
        val task = OpenTasksFixtures.tasks.first { it.due != null }
        val submitted = AtomicReference<TaskEdit?>()

        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task),
                    projectNames = OpenTasksFixtures.snapshot.projects.associate {
                        it.id to it.name
                    },
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = OpenTasksFixtures.snapshot.tags,
                    selectedTaskId = task.id,
                    showDetailPane = false,
                    onSelectTask = {},
                    onCloseDetail = {},
                    onCompleteTask = {},
                    onChangeTaskStatus = { _, _ -> },
                    onDeleteTask = {},
                    activeTimerTaskId = null,
                    onToggleTimer = {},
                    onUpdateTask = { _, edit -> submitted.set(edit) },
                    onAddChecklistItem = { _, _ -> },
                    onUpdateChecklistItem = { _, _ -> },
                    onDeleteChecklistItem = { _, _ -> },
                    onSetTaskTag = { _, _, _ -> },
                    onCreateAndAssignTag = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("recurrence-frequency-row")
            .performScrollTo()
            .performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("recurrence-frequency-weekly")
            .performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("recurrence-interval-field")
            .performScrollTo()
            .performTextReplacement("2")
        composeRule.onNodeWithTag("recurrence-weekday-sunday")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("recurrence-weekday-monday")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("recurrence-weekday-thursday")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("recurrence-end-row")
            .performScrollTo()
            .performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("recurrence-end-count")
            .performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("recurrence-count-field")
            .performScrollTo()
            .performTextReplacement("3")

        composeRule.mainClock.advanceTimeBy(700)
        composeRule.waitForIdle()

        assertEquals(
            RecurrenceRule(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 2,
                weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
                count = 3,
            ),
            submitted.get()?.recurrence,
        )
    }

    @Test
    fun remainingRecurrenceFrequenciesAutoSaveWithIntervals() {
        val task = OpenTasksFixtures.tasks.first { it.due != null }
        val submitted = AtomicReference<TaskEdit?>()

        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task),
                    projectNames = emptyMap(),
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = OpenTasksFixtures.snapshot.tags,
                    selectedTaskId = task.id,
                    showDetailPane = false,
                    onSelectTask = {},
                    onCloseDetail = {},
                    onCompleteTask = {},
                    onChangeTaskStatus = { _, _ -> },
                    onDeleteTask = {},
                    activeTimerTaskId = null,
                    onToggleTimer = {},
                    onUpdateTask = { _, edit -> submitted.set(edit) },
                    onAddChecklistItem = { _, _ -> },
                    onUpdateChecklistItem = { _, _ -> },
                    onDeleteChecklistItem = { _, _ -> },
                    onSetTaskTag = { _, _, _ -> },
                    onCreateAndAssignTag = { _, _ -> },
                )
            }
        }

        listOf(
            RecurrenceFrequency.DAILY,
            RecurrenceFrequency.MONTHLY,
            RecurrenceFrequency.YEARLY,
        ).forEach { frequency ->
            submitted.set(null)
            composeRule.onNodeWithTag(
                "recurrence-frequency-${frequency.name.lowercase()}",
            ).performSemanticsAction(SemanticsActions.OnClick)
            composeRule.onNodeWithTag("recurrence-interval-field")
                .performScrollTo()
                .performTextReplacement("2")
            composeRule.mainClock.advanceTimeBy(700)
            composeRule.waitForIdle()

            assertEquals(
                RecurrenceRule(
                    frequency = frequency,
                    interval = 2,
                ),
                submitted.get()?.recurrence,
            )
        }
    }

    @Test
    fun recurrenceControlsRemainOperableAtTwoHundredPercentText() {
        val task = OpenTasksFixtures.tasks.first { it.due != null }

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                OpenTasksTheme {
                    TasksScreen(
                        tasks = listOf(task),
                        projectNames = emptyMap(),
                        workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                        tags = OpenTasksFixtures.snapshot.tags,
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
                    )
                }
            }
        }

        val frequencyTags = listOf(
            "recurrence-frequency-none",
            "recurrence-frequency-daily",
            "recurrence-frequency-weekly",
            "recurrence-frequency-monthly",
            "recurrence-frequency-yearly",
        )
        frequencyTags.forEach { tag ->
            composeRule.onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
        }

        composeRule.onNodeWithTag("recurrence-frequency-weekly").performClick()
        composeRule.waitForIdle()
        DayOfWeek.entries.forEach { weekday ->
            composeRule.onNodeWithTag(
                "recurrence-weekday-${weekday.name.lowercase()}",
            )
                .performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
        }
        listOf("never", "count", "date").forEach { mode ->
            composeRule.onNodeWithTag("recurrence-end-$mode")
                .performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun keyboardCanFocusAndActivateRecurrenceFrequency() {
        val task = OpenTasksFixtures.tasks.first { it.due != null }
        val submitted = AtomicReference<TaskEdit?>()

        composeRule.setContent {
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(inputModeManager) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
            }
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task),
                    projectNames = emptyMap(),
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = OpenTasksFixtures.snapshot.tags,
                    selectedTaskId = task.id,
                    showDetailPane = false,
                    onSelectTask = {},
                    onCloseDetail = {},
                    onCompleteTask = {},
                    onChangeTaskStatus = { _, _ -> },
                    onDeleteTask = {},
                    activeTimerTaskId = null,
                    onToggleTimer = {},
                    onUpdateTask = { _, edit -> submitted.set(edit) },
                    onAddChecklistItem = { _, _ -> },
                    onUpdateChecklistItem = { _, _ -> },
                    onDeleteChecklistItem = { _, _ -> },
                    onSetTaskTag = { _, _, _ -> },
                    onCreateAndAssignTag = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("recurrence-frequency-daily")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.mainClock.advanceTimeBy(700)
        composeRule.waitForIdle()

        assertEquals(RecurrenceFrequency.DAILY, submitted.get()?.recurrence?.frequency)
    }

    @Test
    fun checklistAndTagControlsEmitAccessibleGranularActions() {
        val task = OpenTasksFixtures.tasks.first { it.checklist.isNotEmpty() }
        val checklistUpdate = AtomicReference<ChecklistItem?>()
        val checklistAdd = AtomicReference<String?>()
        val tagUpdate = AtomicReference<Pair<TagId, Boolean>?>()
        val tagCreate = AtomicReference<String?>()
        val unselectedTag = OpenTasksFixtures.snapshot.tags.first { it.id !in task.tagIds }

        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task),
                    projectNames = OpenTasksFixtures.snapshot.projects.associate {
                        it.id to it.name
                    },
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = OpenTasksFixtures.snapshot.tags,
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
                    onAddChecklistItem = { _, text -> checklistAdd.set(text) },
                    onUpdateChecklistItem = { _, item -> checklistUpdate.set(item) },
                    onDeleteChecklistItem = { _, _ -> },
                    onSetTaskTag = { _, tagId, present ->
                        tagUpdate.set(tagId to present)
                    },
                    onCreateAndAssignTag = { _, name -> tagCreate.set(name) },
                )
            }
        }

        composeRule.onNodeWithTag("task-tag-${unselectedTag.id.value}")
            .performScrollTo()
            .performClick()
        assertEquals(unselectedTag.id to true, tagUpdate.get())

        composeRule.onNodeWithTag("new-tag-field")
            .performScrollTo()
            .performTextReplacement("Waiting")
        composeRule.onNodeWithContentDescription("Create and add tag").performClick()
        assertEquals("Waiting", tagCreate.get())

        composeRule.onNodeWithTag("new-checklist-item-field")
            .performScrollTo()
            .performTextReplacement("Call reviewer")
        composeRule.onNodeWithContentDescription("Add checklist item").performClick()
        assertEquals("Call reviewer", checklistAdd.get())

        val firstItem = task.checklist.first()
        composeRule.onNodeWithTag("checklist-toggle-${firstItem.id}")
            .performScrollTo()
            .performClick()
        assertEquals(firstItem.copy(completed = !firstItem.completed), checklistUpdate.get())
    }

    @Test
    fun statusSelectorShowsWorkflowAndEmitsSelectedStatus() {
        val task = OpenTasksFixtures.tasks.first()
        val requested = AtomicReference<Pair<Task, WorkflowStatusId>?>()

        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task),
                    projectNames = OpenTasksFixtures.snapshot.projects.associate {
                        it.id to it.name
                    },
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = OpenTasksFixtures.snapshot.tags,
                    selectedTaskId = task.id,
                    showDetailPane = false,
                    onSelectTask = {},
                    onCloseDetail = {},
                    onCompleteTask = {},
                    onChangeTaskStatus = { selectedTask, statusId ->
                        requested.set(selectedTask to statusId)
                    },
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

        composeRule.onNodeWithTag("task-status-button").performClick()
        composeRule.onNodeWithTag("task-status-option-${OpenTasksFixtures.backlog.value}")
            .performClick()

        assertEquals(task to OpenTasksFixtures.backlog, requested.get())
    }

    @Test
    fun moveToTrashEmitsSelectedTask() {
        val task = OpenTasksFixtures.tasks.first()
        val deleted = AtomicReference<Task?>()

        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task),
                    projectNames = OpenTasksFixtures.snapshot.projects.associate {
                        it.id to it.name
                    },
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = OpenTasksFixtures.snapshot.tags,
                    selectedTaskId = task.id,
                    showDetailPane = false,
                    onSelectTask = {},
                    onCloseDetail = {},
                    onCompleteTask = {},
                    onChangeTaskStatus = { _, _ -> },
                    onDeleteTask = deleted::set,
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

        composeRule.onNodeWithTag("move-task-to-trash")
            .performScrollTo()
            .performClick()

        assertEquals(task, deleted.get())
    }
}
