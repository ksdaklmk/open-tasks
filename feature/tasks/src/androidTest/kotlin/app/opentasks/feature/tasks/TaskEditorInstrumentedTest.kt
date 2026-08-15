package app.opentasks.feature.tasks

import android.view.View
import android.widget.TimePicker
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TimeEntry
import app.opentasks.core.model.TimeEntryConflict
import app.opentasks.core.model.TimeEntryId
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.ZonedMoment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.hamcrest.Matcher
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TaskEditorInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    private fun showEditor(task: Task, onUpdate: (TaskEdit) -> Unit) {
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
                    onUpdateTask = { _, edit -> onUpdate(edit) },
                    onAddChecklistItem = { _, _ -> },
                    onUpdateChecklistItem = { _, _ -> },
                    onDeleteChecklistItem = { _, _ -> },
                    onSetTaskTag = { _, _, _ -> },
                    onCreateAndAssignTag = { _, _ -> },
                )
            }
        }
    }

    private fun selectTime(tag: String, hour: Int, minute: Int) {
        composeRule.onNodeWithTag(tag)
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        onView(isAssignableFrom(TimePicker::class.java))
            .inRoot(isDialog())
            .perform(
                object : ViewAction {
                    override fun getConstraints(): Matcher<View> = isDisplayed()

                    override fun getDescription() = "set time to %02d:%02d".format(hour, minute)

                    override fun perform(uiController: UiController, view: View) {
                        (view as TimePicker).apply {
                            this.hour = hour
                            this.minute = minute
                        }
                        uiController.loopMainThreadUntilIdle()
                    }
                },
            )
        onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
        composeRule.waitForIdle()
    }

    @Test
    fun startDateAndTimeDefaultToNineAndAutoSaveTogether() {
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted }.copy(start = null, due = null)
        val submitted = AtomicReference<TaskEdit?>()
        val saveCount = AtomicInteger()
        composeRule.mainClock.autoAdvance = false
        showEditor(task) {
            saveCount.incrementAndGet()
            submitted.set(it)
        }

        composeRule.onNodeWithTag("task-start-date-button")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.mainClock.advanceTimeByFrame()
        val useDate = composeRule.onNodeWithText("Use date")
        useDate.performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("task-start-time-button")
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("task-title-field")
            .performTextReplacement("Plan together")
        composeRule.mainClock.advanceTimeBy(700)
        composeRule.waitForIdle()

        val edit = submitted.get()
        assertEquals(1, saveCount.get())
        assertEquals("Plan together", edit?.title)
        assertEquals(9, edit?.start?.instant?.atZone(ZoneId.of(edit.start.zoneId))?.hour)
        assertEquals(0, edit?.start?.instant?.atZone(ZoneId.of(edit.start.zoneId))?.minute)
    }

    @Test
    fun existingStartAndDueControlsPreserveStoredZones() {
        val startZone = ZoneId.of("America/New_York")
        val dueZone = ZoneId.of("Asia/Bangkok")
        val start = ZonedMoment(
            ZonedDateTime.ofLocal(
                LocalDate.of(2026, 11, 1).atTime(1, 30),
                startZone,
                ZoneOffset.ofHours(-5),
            ).toInstant(),
            startZone.id,
        )
        val due = ZonedMoment(
            LocalDate.of(2026, 11, 2).atTime(17, 0).atZone(dueZone).toInstant(),
            dueZone.id,
        )
        val submitted = AtomicReference<TaskEdit?>()
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted }.copy(start = start, due = due)
        showEditor(task, submitted::set)

        listOf("task-start-date-button", "task-start-time-button", "task-due-date-button", "task-due-time-button")
            .forEach { tag ->
                composeRule.onNodeWithTag(tag)
                    .performScrollTo()
                    .assertHeightIsAtLeast(48.dp)
            }
        selectTime("task-start-time-button", 1, 45)
        selectTime("task-due-time-button", 18, 15)
        composeRule.waitUntil(timeoutMillis = 5_000) { submitted.get()?.due != due }

        assertEquals(startZone.id, submitted.get()?.start?.zoneId)
        assertEquals(dueZone.id, submitted.get()?.due?.zoneId)
        assertEquals(
            ZoneOffset.ofHours(-5),
            submitted.get()?.start?.instant?.atZone(startZone)?.offset,
        )
        assertEquals(LocalDate.of(2026, 11, 1), submitted.get()?.start?.instant?.atZone(startZone)?.toLocalDate())
        assertEquals(LocalDate.of(2026, 11, 2), submitted.get()?.due?.instant?.atZone(dueZone)?.toLocalDate())
    }

    @Test
    fun newDueDateStillDefaultsToSeventeenAndTimeCanBeChanged() {
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted }.copy(start = null, due = null)
        val submitted = AtomicReference<TaskEdit?>()
        showEditor(task, submitted::set)

        composeRule.onNodeWithTag("task-due-date-button")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithText("Use date").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { submitted.get()?.due != null }
        val defaultDue = checkNotNull(submitted.get()?.due)
        assertEquals(17, defaultDue.instant.atZone(ZoneId.of(defaultDue.zoneId)).hour)

        selectTime("task-due-time-button", 19, 25)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            submitted.get()?.due?.instant?.atZone(ZoneId.of(defaultDue.zoneId))?.hour == 19
        }
        assertEquals(25, submitted.get()?.due?.instant?.atZone(ZoneId.of(defaultDue.zoneId))?.minute)
    }

    @Test
    fun dueBeforeStartShowsWarningAndSuppressesAutoSave() {
        val zone = ZoneId.of("Europe/London")
        val date = LocalDate.of(2026, 10, 10)
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted }.copy(
            start = ZonedMoment(date.atTime(16, 0).atZone(zone).toInstant(), zone.id),
            due = ZonedMoment(date.atTime(17, 0).atZone(zone).toInstant(), zone.id),
        )
        val submitted = AtomicReference<TaskEdit?>()
        showEditor(task, submitted::set)

        selectTime("task-due-time-button", 15, 0)
        composeRule.onNodeWithTag("task-schedule-warning")
            .performScrollTo()
            .assertTextContains(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(
                    R.string.task_schedule_due_before_start,
                ),
            )
            .assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        assertNull(submitted.get())
    }

    @Test
    fun legacyInvalidScheduleRemainsVisibleUntilCorrected() {
        val zone = ZoneId.of("Europe/London")
        val date = LocalDate.of(2026, 10, 10)
        val start = ZonedMoment(date.atTime(16, 0).atZone(zone).toInstant(), zone.id)
        val due = ZonedMoment(date.atTime(15, 0).atZone(zone).toInstant(), zone.id)
        val submitted = AtomicReference<TaskEdit?>()
        val saveCount = AtomicInteger()
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted }.copy(start = start, due = due)
        showEditor(task) {
            saveCount.incrementAndGet()
            submitted.set(it)
        }

        composeRule.onNodeWithTag("task-start-time-button")
            .performScrollTo()
            .assertTextContains("16:00")
        composeRule.onNodeWithTag("task-due-time-button")
            .assertTextContains("15:00")
        composeRule.onNodeWithTag("task-schedule-warning")
            .performScrollTo()
            .assertTextContains(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(
                    R.string.task_schedule_due_before_start,
                ),
            )
            .assertIsDisplayed()
        assertNull(submitted.get())

        selectTime("task-due-time-button", 17, 0)
        composeRule.waitUntil(timeoutMillis = 5_000) { saveCount.get() == 1 }
        assertEquals(start, submitted.get()?.start)
        assertEquals(17, submitted.get()?.due?.instant?.atZone(zone)?.hour)
    }

    @Test
    fun duplicateActionEmitsTaskId() {
        val task = OpenTasksFixtures.tasks.first()
        val duplicated = AtomicReference<TaskId?>()
        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task),
                    projectNames = OpenTasksFixtures.snapshot.projects.associate {
                        it.id to it.name
                    },
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
                    onDuplicateTask = duplicated::set,
                )
            }
        }

        composeRule.onNodeWithTag("duplicate-task")
            .performScrollTo()
            .assertTextEquals("Duplicate task")
            .assertHeightIsAtLeast(48.dp)
            .assertIsEnabled()
            .performClick()
        assertEquals(task.id, duplicated.get())
    }

    @Test
    fun duplicateActionRequiresValidPersistedTask() {
        val task = OpenTasksFixtures.tasks.first()
        val renderedTask = mutableStateOf(task)
        val duplicated = AtomicReference<TaskId?>()
        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(renderedTask.value),
                    projectNames = OpenTasksFixtures.snapshot.projects.associate {
                        it.id to it.name
                    },
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
                    onUpdateTask = { _, edit ->
                        renderedTask.value = renderedTask.value.copy(
                            title = edit.title,
                            description = edit.description,
                            projectId = edit.projectId,
                            priority = edit.priority,
                            due = edit.due,
                            recurrence = edit.recurrence,
                            estimate = edit.estimate,
                            milestoneId = edit.milestoneId,
                        )
                    },
                    onAddChecklistItem = { _, _ -> },
                    onUpdateChecklistItem = { _, _ -> },
                    onDeleteChecklistItem = { _, _ -> },
                    onSetTaskTag = { _, _, _ -> },
                    onCreateAndAssignTag = { _, _ -> },
                    onDuplicateTask = duplicated::set,
                )
            }
        }

        val duplicateAction = composeRule.onNodeWithTag("duplicate-task").performScrollTo()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("task-title-field").performTextReplacement("Updated title")
        composeRule.mainClock.advanceTimeByFrame()
        duplicateAction.assertIsNotEnabled()
        composeRule.mainClock.advanceTimeBy(650)
        composeRule.mainClock.autoAdvance = true
        duplicateAction.assertIsEnabled()
        composeRule.onNodeWithTag("task-title-field").performTextReplacement("")
        duplicateAction.assertIsNotEnabled()
        assertNull(duplicated.get())
    }

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
    fun reminderRequestsPermissionInContextAndAutoSavesFallbackChoice() {
        val dueInstant = Instant.ofEpochMilli(
            Instant.now().plus(Duration.ofDays(2)).toEpochMilli(),
        )
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted }.copy(
            due = ZonedMoment(dueInstant, "UTC"),
        )
        val submitted = AtomicReference<TaskEdit?>()
        val notificationRequests = AtomicInteger()
        val preciseRequests = AtomicInteger()

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
                    notificationsEnabled = false,
                    preciseRemindersAvailable = false,
                    onEnableNotifications = { notificationRequests.incrementAndGet() },
                    onEnablePreciseReminders = { preciseRequests.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithTag("reminder-1-hour")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("reminder-delivery-precise")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("enable-notifications")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag("enable-precise-reminders")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            submitted.get()?.reminder?.precise == true
        }

        assertTrue(notificationRequests.get() >= 2)
        assertEquals(1, preciseRequests.get())
        assertEquals(
            dueInstant.minus(Duration.ofHours(1)),
            submitted.get()?.reminder?.triggerAt?.instant,
        )
        assertEquals(true, submitted.get()?.reminder?.precise)
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
        assertEquals(
            0,
            composeRule.onAllNodesWithTag(
            "task-status-option-${
                OpenTasksFixtures.statusId(
                    OpenTasksFixtures.taxProject.id,
                    SemanticStatus.BACKLOG,
                ).value
            }",
            ).fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithTag("task-status-option-${OpenTasksFixtures.backlog.value}")
            .performClick()

        assertEquals(task to OpenTasksFixtures.backlog, requested.get())
    }

    @Test
    fun milestoneSelectorIsProjectScopedAndAutoSavesMembership() {
        val task = OpenTasksFixtures.tasks.first {
            it.projectId == OpenTasksFixtures.studioProject.id && !it.isCompleted
        }
        val localMilestone = OpenTasksFixtures.milestones.first {
            it.projectId == task.projectId
        }
        val foreignMilestone = OpenTasksFixtures.milestones.first {
            it.projectId != task.projectId
        }
        val submitted = AtomicReference<TaskEdit?>()
        composeRule.mainClock.autoAdvance = true

        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task),
                    projectNames = OpenTasksFixtures.snapshot.projects.associate {
                        it.id to it.name
                    },
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = OpenTasksFixtures.snapshot.tags,
                    milestones = OpenTasksFixtures.snapshot.milestones,
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

        composeRule.onNodeWithTag("task-milestone-button")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(
            0,
            composeRule.onAllNodesWithTag(
                "task-milestone-option-${foreignMilestone.id.value}",
            ).fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithTag("task-milestone-option-${localMilestone.id.value}")
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            submitted.get()?.milestoneId == localMilestone.id
        }

        assertEquals(localMilestone.id, submitted.get()?.milestoneId)
    }

    @Test
    fun dependencyEditorShowsCandidatesAndEmitsSelection() {
        val task = OpenTasksFixtures.tasks.first {
            !it.isCompleted && it.deletedAt == null && it.dependencyIds.isEmpty()
        }
        val dependency = OpenTasksFixtures.tasks.first {
            it.id != task.id && it.deletedAt == null
        }
        val requested = AtomicReference<Triple<TaskId, TaskId, Boolean>?>()

        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task, dependency),
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
                    onAddChecklistItem = { _, _ -> },
                    onUpdateChecklistItem = { _, _ -> },
                    onDeleteChecklistItem = { _, _ -> },
                    onSetTaskTag = { _, _, _ -> },
                    onCreateAndAssignTag = { _, _ -> },
                    onSetTaskDependency = { taskId, dependencyId, present ->
                        requested.set(Triple(taskId, dependencyId, present))
                    },
                )
            }
        }

        composeRule.onNodeWithTag("manage-task-dependencies")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("dependency-editor").assertIsDisplayed()
        composeRule.onNodeWithTag("dependency-option-${dependency.id.value}")
            .performClick()

        assertEquals(Triple(task.id, dependency.id, true), requested.get())
    }

    @Test
    fun dependencyCycleFeedbackIsVisibleInsideEditor() {
        val task = OpenTasksFixtures.tasks.first {
            !it.isCompleted && it.deletedAt == null && it.dependencyIds.isEmpty()
        }
        val dependency = OpenTasksFixtures.tasks.first {
            it.id != task.id && it.deletedAt == null
        }

        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task, dependency),
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
                    onAddChecklistItem = { _, _ -> },
                    onUpdateChecklistItem = { _, _ -> },
                    onDeleteChecklistItem = { _, _ -> },
                    onSetTaskTag = { _, _, _ -> },
                    onCreateAndAssignTag = { _, _ -> },
                    dependencyError = "That link would create a dependency cycle.",
                )
            }
        }

        composeRule.onNodeWithTag("manage-task-dependencies")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("dependency-error").assertIsDisplayed()
        composeRule.onNodeWithText("That link would create a dependency cycle.")
            .assertIsDisplayed()
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

    @Test
    fun taskFilterRestoresAfterSavedInstanceStateRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        val inboxTask = OpenTasksFixtures.tasks.first().copy(
            id = TaskId("restoration-inbox"),
            title = "Restoration inbox task",
            projectId = null,
            deletedAt = null,
        )
        val projectTask = OpenTasksFixtures.tasks.last().copy(
            id = TaskId("restoration-project"),
            title = "Restoration project task",
            projectId = OpenTasksFixtures.studioProject.id,
            deletedAt = null,
        )

        restorationTester.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(inboxTask, projectTask),
                    projectNames = mapOf(
                        OpenTasksFixtures.studioProject.id to
                            OpenTasksFixtures.studioProject.name,
                    ),
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = OpenTasksFixtures.snapshot.tags,
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

        composeRule.onNodeWithTag("task-filter-inbox").performClick()
        composeRule.onNodeWithText(inboxTask.title).assertIsDisplayed()
        composeRule.onNodeWithText(projectTask.title).assertDoesNotExist()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("task-filter-inbox").assertIsSelected()
        composeRule.onNodeWithText(inboxTask.title).assertIsDisplayed()
        composeRule.onNodeWithText(projectTask.title).assertDoesNotExist()
    }

    @Test
    fun taskListScrollRestoresAfterSavedInstanceStateRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        val tasks = List(30) { index ->
            OpenTasksFixtures.tasks.first().copy(
                id = TaskId("restoration-list-$index"),
                title = "Restoration task $index",
                deletedAt = null,
            )
        }

        restorationTester.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = tasks,
                    projectNames = emptyMap(),
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = OpenTasksFixtures.snapshot.tags,
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

        composeRule.onNodeWithTag("task-list").performScrollToIndex(20)
        composeRule.onNodeWithText("Restoration task 20").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Restoration task 20").assertIsDisplayed()
    }

    @Test
    fun taskDraftAndEditorScrollRestoreAfterSavedInstanceStateRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        val task = OpenTasksFixtures.tasks.first()
        val draft = "x".repeat(241)

        restorationTester.setContent {
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
                    onAddChecklistItem = { _, _ -> },
                    onUpdateChecklistItem = { _, _ -> },
                    onDeleteChecklistItem = { _, _ -> },
                    onSetTaskTag = { _, _, _ -> },
                    onCreateAndAssignTag = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("task-title-field")
            .performTextReplacement(draft)
        composeRule.onNodeWithTag("move-task-to-trash")
            .performScrollTo()
            .assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("task-title-field")
            .assertTextContains(draft, substring = true)
        composeRule.onNodeWithTag("move-task-to-trash").assertIsDisplayed()
    }

    @Test
    fun manualTimeEntryCanBeAddedFromTaskDetails() {
        val task = OpenTasksFixtures.tasks.first()
        val submitted = AtomicReference<TimeEntryEdit?>()
        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task),
                    projectNames = emptyMap(),
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = emptyList(),
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
                    onAddTimeEntry = { _, edit -> submitted.set(edit) },
                )
            }
        }

        composeRule.onNodeWithTag("manage-time-entries")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("add-time-entry").performClick()
        composeRule.onNodeWithTag("time-entry-start").performTextReplacement("09:30")
        composeRule.onNodeWithTag("time-entry-duration").performTextReplacement("45")
        composeRule.onNodeWithTag("time-entry-note").performTextReplacement("Client review")
        composeRule.onNodeWithTag("save-time-entry").performScrollTo().performClick()

        assertEquals(Duration.ofMinutes(45), submitted.get()?.let {
            Duration.between(it.startedAt, it.stoppedAt)
        })
        assertEquals("Client review", submitted.get()?.note)
    }

    @Test
    fun overlapWarningLinksToReversibleTimeEntryActions() {
        val task = OpenTasksFixtures.tasks.first()
        val first = TimeEntry(
            id = TimeEntryId("compose-time-first"),
            taskId = task.id,
            deviceId = DeviceId("phone"),
            startedAt = Instant.parse("2026-07-26T08:00:00Z"),
            stoppedAt = Instant.parse("2026-07-26T09:00:00Z"),
            note = "Planning",
        )
        val second = TimeEntry(
            id = TimeEntryId("compose-time-second"),
            taskId = task.id,
            deviceId = DeviceId("tablet"),
            startedAt = Instant.parse("2026-07-26T08:30:00Z"),
            stoppedAt = Instant.parse("2026-07-26T09:30:00Z"),
        )
        val deleted = AtomicReference<TimeEntryId?>()
        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task),
                    projectNames = emptyMap(),
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = emptyList(),
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
                    timeEntries = listOf(first, second),
                    timeEntryConflicts = listOf(
                        TimeEntryConflict(
                            firstEntryId = first.id,
                            secondEntryId = second.id,
                            overlap = Duration.ofMinutes(30),
                        ),
                    ),
                    onDeleteTimeEntry = deleted::set,
                )
            }
        }

        composeRule.onNodeWithTag("time-overlap-warning")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("manage-time-entries").performClick()
        composeRule.onNodeWithTag("time-sheet-overlap-warning").assertIsDisplayed()
        composeRule.onNodeWithTag("delete-time-entry-${first.id.value}").performClick()

        assertEquals(first.id, deleted.get())
    }
}
