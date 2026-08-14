package app.opentasks.feature.schedule

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.ScheduleMonthDay
import app.opentasks.core.model.ScheduleMonthProjection
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.ZonedMoment
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactAgendaShowsOnlySelectedDayAndNavigatesToTask() {
        val today = LocalDate.parse("2026-07-27")
        val mondayTask = scheduledTask(
            id = "schedule-monday",
            title = "Monday planning",
            instant = "2026-07-27T08:30:00Z",
        )
        val tuesdayTask = scheduledTask(
            id = "schedule-tuesday",
            title = "Tuesday review",
            instant = "2026-07-28T12:00:00Z",
        )
        val opened = AtomicReference<TaskId?>()
        val reminder = Reminder(
            id = Reminder.primaryId(mondayTask.id),
            taskId = mondayTask.id,
            triggerAt = ZonedMoment(
                instant = Instant.parse("2026-07-27T08:15:00Z"),
                zoneId = "UTC",
            ),
            precise = false,
        )

        composeRule.setContent {
            var selectedDate by remember { mutableStateOf(today) }
            OpenTasksTheme {
                ScheduleScreen(
                    tasks = listOf(mondayTask, tuesdayTask),
                    projectNames = emptyMap(),
                    expanded = false,
                    presentation = SchedulePresentation.WEEK,
                    selectedDate = selectedDate,
                    month = literalMonthProjection(),
                    onPresentationChange = {},
                    onSelectedDateChange = { selectedDate = it },
                    onPrevious = { selectedDate = selectedDate.minusDays(1) },
                    onToday = { selectedDate = today },
                    onNext = { selectedDate = selectedDate.plusDays(1) },
                    reminders = listOf(reminder),
                    today = today,
                    onOpenTask = opened::set,
                )
            }
        }

        composeRule.onNodeWithTag("compact-day-agenda").assertIsDisplayed()
        composeRule.onNodeWithText(mondayTask.title).assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText(tuesdayTask.title)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        composeRule.onNodeWithContentDescription("Reminder set for 08:15")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-previous").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("schedule-today").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("schedule-next").assertHeightIsAtLeast(48.dp)

        composeRule.onNodeWithTag("schedule-next").performClick()
        composeRule.onNodeWithText(tuesdayTask.title).assertIsDisplayed().performClick()
        assertEquals(tuesdayTask.id, opened.get())
    }

    @Test
    fun expandedWeekGroupsRealDatesAndLimitsTheUnscheduledTrayToOpenWork() {
        val monday = scheduledTask(
            id = "week-monday",
            title = "Monday task",
            instant = "2026-07-27T09:00:00Z",
        )
        val wednesday = scheduledTask(
            id = "week-wednesday",
            title = "Wednesday task",
            instant = "2026-07-29T15:00:00Z",
        )
        val openUnscheduled = unscheduledTask("open-unscheduled", "Plan without a date")
        val completeUnscheduled = unscheduledTask(
            id = "complete-unscheduled",
            title = "Already complete",
        ).copy(
            semanticStatus = SemanticStatus.COMPLETED,
            completedAt = Instant.parse("2026-07-26T12:00:00Z"),
        )
        val trashedUnscheduled = unscheduledTask(
            id = "trashed-unscheduled",
            title = "In Bin",
        ).copy(deletedAt = Instant.parse("2026-07-26T12:00:00Z"))
        val today = LocalDate.parse("2026-07-27")

        composeRule.setContent {
            var selectedDate by remember { mutableStateOf(today) }
            OpenTasksTheme {
                ScheduleScreen(
                    tasks = listOf(
                        monday,
                        wednesday,
                        openUnscheduled,
                        completeUnscheduled,
                        trashedUnscheduled,
                    ),
                    projectNames = emptyMap(),
                    expanded = true,
                    presentation = SchedulePresentation.WEEK,
                    selectedDate = selectedDate,
                    month = literalMonthProjection(),
                    onPresentationChange = {},
                    onSelectedDateChange = { selectedDate = it },
                    onPrevious = { selectedDate = selectedDate.minusWeeks(1) },
                    onToday = { selectedDate = today },
                    onNext = { selectedDate = selectedDate.plusWeeks(1) },
                    today = today,
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("expanded-week-schedule").assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-column-2026-07-27").fetchSemanticsNode()
        composeRule.onNodeWithTag("schedule-task-${monday.id.value}").fetchSemanticsNode()
        composeRule.onNodeWithTag("schedule-column-2026-07-29").fetchSemanticsNode()
        composeRule.onNodeWithTag("schedule-task-${wednesday.id.value}").fetchSemanticsNode()
        composeRule.onNodeWithTag("unscheduled-tray").fetchSemanticsNode()
        composeRule.onNodeWithText(openUnscheduled.title).fetchSemanticsNode()
        assertTrue(
            composeRule.onAllNodesWithText(completeUnscheduled.title)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithText(trashedUnscheduled.title)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        composeRule.onNodeWithTag("unscheduled-task-${openUnscheduled.id.value}")
            .assertHeightIsAtLeast(48.dp)

        composeRule.onNodeWithTag("schedule-next")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("schedule-column-2026-08-03").fetchSemanticsNode()
    }

    @Test
    fun weekAndMonthControlsMeetFortyEightDpAndEmitPresentation() {
        val emitted = AtomicReference<SchedulePresentation?>()

        composeRule.setContent {
            OpenTasksTheme {
                ScheduleScreen(
                    tasks = emptyList(),
                    projectNames = emptyMap(),
                    expanded = false,
                    presentation = SchedulePresentation.WEEK,
                    selectedDate = LocalDate.parse("2026-08-17"),
                    month = literalMonthProjection(),
                    onPresentationChange = emitted::set,
                    onSelectedDateChange = {},
                    onPrevious = {},
                    onToday = {},
                    onNext = {},
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("schedule-presentation-week")
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithTag("schedule-presentation-month")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        assertEquals(SchedulePresentation.MONTH, emitted.get())
    }

    @Test
    fun monthShowsFortyTwoSelectableCellsIncludingMutedAdjacentDates() {
        composeRule.setContent {
            OpenTasksTheme {
                ScheduleScreen(
                    tasks = emptyList(),
                    projectNames = emptyMap(),
                    expanded = false,
                    presentation = SchedulePresentation.MONTH,
                    selectedDate = LocalDate.parse("2026-08-17"),
                    month = literalMonthProjection(),
                    onPresentationChange = {},
                    onSelectedDateChange = {},
                    onPrevious = {},
                    onToday = {},
                    onNext = {},
                    onOpenTask = {},
                )
            }
        }

        composeRule.onAllNodes(monthCellMatcher).assertCountEquals(42)
        composeRule.onNodeWithTag("schedule-month-day-2026-07-27")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Adjacent month",
                ),
            )
        composeRule.onNodeWithTag("schedule-month-day-2026-08-17").assertIsSelected()
    }

    @Test
    fun monthCellSemanticsExposeExactCountsAndLabelledOverdue() {
        val date = LocalDate.parse("2026-08-17")
        val day = ScheduleMonthDay(
            date = date,
            inSelectedMonth = true,
            tasks = emptyList(),
            totalCount = 7,
            completedCount = 1,
            overdueCount = 1,
            densityDotCount = 6,
            hasDensityOverflow = true,
        )

        composeRule.setContent {
            OpenTasksTheme {
                ScheduleScreen(
                    tasks = emptyList(),
                    projectNames = emptyMap(),
                    expanded = false,
                    presentation = SchedulePresentation.MONTH,
                    selectedDate = date,
                    month = literalMonthProjection(mapOf(date.toString() to day)),
                    onPresentationChange = {},
                    onSelectedDateChange = {},
                    onPrevious = {},
                    onToday = {},
                    onNext = {},
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("schedule-month-day-2026-08-17")
            .assertContentDescriptionEquals(
                "Monday, 17 August 2026. 7 tasks, 1 completed, 1 overdue.",
            )
        composeRule.onNodeWithText("1 overdue").assertIsDisplayed()
        composeRule.onNodeWithText("6+").assertIsDisplayed()
        val dots = composeRule.onAllNodesWithTag("dot-run-bar", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(dots.isNotEmpty())
        assertTrue(dots.all { SemanticsProperties.ContentDescription !in it.config })
    }

    @Test
    fun selectingMonthCellUpdatesAgendaWithoutOpeningTask() {
        val augustTask = scheduledTask(
            id = "august-agenda",
            title = "August agenda task",
            instant = "2026-08-17T09:00:00Z",
        )
        val adjacentTask = scheduledTask(
            id = "adjacent-agenda",
            title = "Adjacent agenda task",
            instant = "2026-07-27T09:00:00Z",
        )
        val month = literalMonthProjection(
            mapOf(
                "2026-08-17" to literalDay(
                    date = "2026-08-17",
                    inSelectedMonth = true,
                    tasks = listOf(augustTask),
                ),
                "2026-07-27" to literalDay(
                    date = "2026-07-27",
                    inSelectedMonth = false,
                    tasks = listOf(adjacentTask),
                ),
            ),
        )
        val opened = AtomicReference<TaskId?>()

        composeRule.setContent {
            var selectedDate by remember { mutableStateOf(LocalDate.parse("2026-08-17")) }
            OpenTasksTheme {
                ScheduleScreen(
                    tasks = listOf(augustTask, adjacentTask),
                    projectNames = emptyMap(),
                    expanded = false,
                    presentation = SchedulePresentation.MONTH,
                    selectedDate = selectedDate,
                    month = month,
                    onPresentationChange = {},
                    onSelectedDateChange = { selectedDate = it },
                    onPrevious = {},
                    onToday = {},
                    onNext = {},
                    onOpenTask = opened::set,
                )
            }
        }

        composeRule.onNodeWithText(augustTask.title).assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-month-day-2026-07-27").performClick()
        composeRule.onNodeWithText(adjacentTask.title).assertIsDisplayed()
        assertEquals(null, opened.get())
    }

    @Test
    fun selectedAgendaTaskOpensExistingTask() {
        val selectedDate = LocalDate.parse("2026-08-17")
        val task = scheduledTask(
            id = "selected-agenda",
            title = "Open selected task",
            instant = "2026-08-17T09:00:00Z",
        )
        val opened = AtomicReference<TaskId?>()

        composeRule.setContent {
            OpenTasksTheme {
                ScheduleScreen(
                    tasks = listOf(task),
                    projectNames = emptyMap(),
                    expanded = false,
                    presentation = SchedulePresentation.MONTH,
                    selectedDate = selectedDate,
                    month = literalMonthProjection(
                        mapOf(
                            selectedDate.toString() to literalDay(
                                date = selectedDate.toString(),
                                inSelectedMonth = true,
                                tasks = listOf(task),
                            ),
                        ),
                    ),
                    onPresentationChange = {},
                    onSelectedDateChange = {},
                    onPrevious = {},
                    onToday = {},
                    onNext = {},
                    onOpenTask = opened::set,
                )
            }
        }

        composeRule.onNodeWithText(task.title).performClick()
        assertEquals(task.id, opened.get())
    }

    @Test
    fun expandedMonthShowsAgendaAndOpenUnscheduledTray() {
        val date = LocalDate.parse("2026-08-17")
        val openScheduled = scheduledTask(
            id = "expanded-open",
            title = "Open scheduled task",
            instant = "2026-08-17T09:00:00Z",
        )
        val completedScheduled = scheduledTask(
            id = "expanded-complete",
            title = "Completed scheduled task",
            instant = "2026-08-17T10:00:00Z",
        ).copy(
            semanticStatus = SemanticStatus.COMPLETED,
            completedAt = Instant.parse("2026-08-17T11:00:00Z"),
        )
        val openUnscheduled = unscheduledTask("expanded-unscheduled", "Open unscheduled task")
        val completedUnscheduled = unscheduledTask(
            "expanded-complete-unscheduled",
            "Completed unscheduled task",
        ).copy(
            semanticStatus = SemanticStatus.COMPLETED,
            completedAt = Instant.parse("2026-08-17T11:00:00Z"),
        )

        composeRule.setContent {
            OpenTasksTheme {
                ScheduleScreen(
                    tasks = listOf(
                        openScheduled,
                        completedScheduled,
                        openUnscheduled,
                        completedUnscheduled,
                    ),
                    projectNames = emptyMap(),
                    expanded = true,
                    presentation = SchedulePresentation.MONTH,
                    selectedDate = date,
                    month = literalMonthProjection(
                        mapOf(
                            date.toString() to literalDay(
                                date = date.toString(),
                                inSelectedMonth = true,
                                tasks = listOf(openScheduled, completedScheduled),
                            ),
                        ),
                    ),
                    onPresentationChange = {},
                    onSelectedDateChange = {},
                    onPrevious = {},
                    onToday = {},
                    onNext = {},
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("expanded-month-schedule").assertIsDisplayed()
        composeRule.onNodeWithTag("month-selected-agenda").fetchSemanticsNode()
        composeRule.onNodeWithText(openScheduled.title).fetchSemanticsNode()
        composeRule.onNodeWithText(completedScheduled.title).fetchSemanticsNode()
        composeRule.onNodeWithTag("unscheduled-tray").fetchSemanticsNode()
        composeRule.onNodeWithText(openUnscheduled.title).fetchSemanticsNode()
        assertTrue(
            composeRule.onAllNodesWithText(completedUnscheduled.title)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    @Test
    fun compactWeekReschedulePickerEmitsTaskAndDate() {
        val task = scheduledTask(
            id = "compact-reschedule",
            title = "Move compact task",
            instant = "2026-08-17T09:00:00Z",
        )
        val rescheduled = AtomicReference<Pair<TaskId, LocalDate>?>()
        showFallback(
            tasks = listOf(task),
            onRescheduleTask = { taskId, date -> rescheduled.set(taskId to date) },
        )

        assertMinimumTarget("schedule-actions-${task.id.value}").performClick()
        assertMinimumTarget("schedule-reschedule-${task.id.value}").performClick()
        assertMinimumTarget("schedule-reschedule-confirm-${task.id.value}").performClick()

        assertEquals(task.id to FALLBACK_DATE, rescheduled.get())
    }

    @Test
    fun monthAgendaUsesTheSameReschedulePicker() {
        val task = scheduledTask(
            id = "month-reschedule",
            title = "Move month task",
            instant = "2026-08-17T10:00:00Z",
        )
        val rescheduled = AtomicReference<Pair<TaskId, LocalDate>?>()
        showFallback(
            tasks = listOf(task),
            presentation = SchedulePresentation.MONTH,
            onRescheduleTask = { taskId, date -> rescheduled.set(taskId to date) },
        )

        assertMinimumTarget("schedule-actions-${task.id.value}").performClick()
        assertMinimumTarget("schedule-reschedule-${task.id.value}").performClick()
        assertMinimumTarget("schedule-reschedule-confirm-${task.id.value}").performClick()

        assertEquals(task.id to FALLBACK_DATE, rescheduled.get())
    }

    @Test
    fun removeWithoutReminderEmitsImmediately() {
        val task = scheduledTask(
            id = "remove-without-reminder",
            title = "Remove schedule now",
            instant = "2026-08-17T11:00:00Z",
        )
        val removed = AtomicReference<TaskId?>()
        showFallback(tasks = listOf(task), onRemoveTaskSchedule = removed::set)

        assertMinimumTarget("schedule-actions-${task.id.value}").performClick()
        assertMinimumTarget("schedule-remove-${task.id.value}").performClick()

        assertEquals(task.id, removed.get())
    }

    @Test
    fun removeWithReminderRequiresConfirmationAndCancelDoesNothing() {
        val task = scheduledTask(
            id = "remove-with-reminder",
            title = "Confirm reminder loss",
            instant = "2026-08-17T12:00:00Z",
        )
        val reminder = Reminder(
            id = Reminder.primaryId(task.id),
            taskId = task.id,
            triggerAt = ZonedMoment(Instant.parse("2026-08-17T11:30:00Z"), "UTC"),
            precise = false,
        )
        val removed = AtomicReference<TaskId?>()
        showFallback(
            tasks = listOf(task),
            reminders = listOf(reminder),
            onRemoveTaskSchedule = removed::set,
        )

        assertMinimumTarget("schedule-actions-${task.id.value}").performClick()
        assertMinimumTarget("schedule-remove-${task.id.value}").performClick()
        assertMinimumTarget("schedule-remove-cancel-${task.id.value}").performClick()
        assertEquals(null, removed.get())

        assertMinimumTarget("schedule-actions-${task.id.value}").performClick()
        assertMinimumTarget("schedule-remove-${task.id.value}").performClick()
        assertMinimumTarget("schedule-remove-confirm-${task.id.value}").performClick()
        assertEquals(task.id, removed.get())
    }

    @Test
    fun recurringTaskHasNoRemoveAction() {
        val task = scheduledTask(
            id = "recurring-reschedule",
            title = "Recurring task",
            instant = "2026-08-17T13:00:00Z",
        ).copy(recurrence = RecurrenceRule(RecurrenceFrequency.DAILY))
        showFallback(tasks = listOf(task))

        assertMinimumTarget("schedule-actions-${task.id.value}").performClick()
        assertMinimumTarget("schedule-reschedule-${task.id.value}")
        composeRule.onNodeWithTag("schedule-remove-${task.id.value}").assertDoesNotExist()
    }

    @Test
    fun completedAndBinnedTasksExposeNoRescheduleAction() {
        val completed = scheduledTask(
            id = "completed-fallback",
            title = "Completed fallback task",
            instant = "2026-08-17T14:00:00Z",
        ).copy(
            semanticStatus = SemanticStatus.COMPLETED,
            completedAt = Instant.parse("2026-08-17T15:00:00Z"),
        )
        val binned = scheduledTask(
            id = "binned-fallback",
            title = "Binned fallback task",
            instant = "2026-08-17T16:00:00Z",
        ).copy(deletedAt = Instant.parse("2026-08-17T17:00:00Z"))
        showFallback(
            tasks = listOf(completed, binned),
            presentation = SchedulePresentation.MONTH,
        )

        composeRule.onNodeWithText(completed.title).assertIsDisplayed()
        composeRule.onNodeWithText(binned.title).assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-actions-${completed.id.value}").assertDoesNotExist()
        composeRule.onNodeWithTag("schedule-actions-${binned.id.value}").assertDoesNotExist()
    }

    @Test
    fun ordinaryTapStillOpensTask() {
        val task = scheduledTask(
            id = "ordinary-open",
            title = "Open without actions",
            instant = "2026-08-17T18:00:00Z",
        )
        val opened = AtomicReference<TaskId?>()
        showFallback(tasks = listOf(task), onOpenTask = opened::set)

        composeRule.onNodeWithTag("schedule-task-${task.id.value}").performClick()

        assertEquals(task.id, opened.get())
    }

    private fun showFallback(
        tasks: List<Task>,
        presentation: SchedulePresentation = SchedulePresentation.WEEK,
        reminders: List<Reminder> = emptyList(),
        onRescheduleTask: (TaskId, LocalDate) -> Unit = { _, _ -> },
        onRemoveTaskSchedule: (TaskId) -> Unit = {},
        onOpenTask: (TaskId) -> Unit = {},
    ) {
        composeRule.setContent {
            OpenTasksTheme {
                ScheduleScreen(
                    tasks = tasks,
                    projectNames = emptyMap(),
                    expanded = false,
                    presentation = presentation,
                    selectedDate = FALLBACK_DATE,
                    month = literalMonthProjection(
                        mapOf(
                            FALLBACK_DATE.toString() to literalDay(
                                date = FALLBACK_DATE.toString(),
                                inSelectedMonth = true,
                                tasks = tasks,
                            ),
                        ),
                    ),
                    onPresentationChange = {},
                    onSelectedDateChange = {},
                    onPrevious = {},
                    onToday = {},
                    onNext = {},
                    onOpenTask = onOpenTask,
                    reminders = reminders,
                    onRescheduleTask = onRescheduleTask,
                    onRemoveTaskSchedule = onRemoveTaskSchedule,
                )
            }
        }
    }

    private fun assertMinimumTarget(tag: String) = composeRule.onNodeWithTag(tag)
        .assertHeightIsAtLeast(48.dp)
        .assertWidthIsAtLeast(48.dp)

    private fun scheduledTask(
        id: String,
        title: String,
        instant: String,
    ): Task = OpenTasksFixtures.tasks.first().copy(
        id = TaskId(id),
        projectId = null,
        title = title,
        start = null,
        due = ZonedMoment(Instant.parse(instant), "UTC"),
        recurrence = null,
        recurrenceSeriesId = null,
        recurrenceAnchor = null,
        recurrenceOccurrenceIndex = null,
        dependencyIds = emptySet(),
        blockedBy = emptySet(),
        completedAt = null,
        deletedAt = null,
        semanticStatus = SemanticStatus.PLANNED,
    )

    private fun unscheduledTask(
        id: String,
        title: String,
    ): Task = scheduledTask(
        id = id,
        title = title,
        instant = "2026-07-27T17:00:00Z",
    ).copy(due = null)

    private fun literalDay(
        date: String,
        inSelectedMonth: Boolean,
        tasks: List<Task>,
    ) = ScheduleMonthDay(
        date = LocalDate.parse(date),
        inSelectedMonth = inSelectedMonth,
        tasks = tasks,
        totalCount = tasks.size,
        completedCount = tasks.count(Task::isCompleted),
        overdueCount = 0,
        densityDotCount = tasks.size.coerceAtMost(6),
        hasDensityOverflow = tasks.size > 6,
    )

    private fun literalMonthProjection(
        overrides: Map<String, ScheduleMonthDay> = emptyMap(),
    ) = ScheduleMonthProjection(
        month = YearMonth.of(2026, 8),
        days = LITERAL_MONTH_DATES.map { (date, inSelectedMonth) ->
            overrides[date] ?: ScheduleMonthDay(
                date = LocalDate.parse(date),
                inSelectedMonth = inSelectedMonth,
                tasks = emptyList(),
                totalCount = 0,
                completedCount = 0,
                overdueCount = 0,
                densityDotCount = 0,
                hasDensityOverflow = false,
            )
        },
    )

    private val monthCellMatcher = SemanticsMatcher("month calendar cell") { node ->
        SemanticsProperties.TestTag in node.config &&
            node.config[SemanticsProperties.TestTag].startsWith("schedule-month-day-")
    }

    private companion object {
        val FALLBACK_DATE: LocalDate = LocalDate.parse("2026-08-17")

        val LITERAL_MONTH_DATES = listOf(
            "2026-07-27" to false,
            "2026-07-28" to false,
            "2026-07-29" to false,
            "2026-07-30" to false,
            "2026-07-31" to false,
            "2026-08-01" to true,
            "2026-08-02" to true,
            "2026-08-03" to true,
            "2026-08-04" to true,
            "2026-08-05" to true,
            "2026-08-06" to true,
            "2026-08-07" to true,
            "2026-08-08" to true,
            "2026-08-09" to true,
            "2026-08-10" to true,
            "2026-08-11" to true,
            "2026-08-12" to true,
            "2026-08-13" to true,
            "2026-08-14" to true,
            "2026-08-15" to true,
            "2026-08-16" to true,
            "2026-08-17" to true,
            "2026-08-18" to true,
            "2026-08-19" to true,
            "2026-08-20" to true,
            "2026-08-21" to true,
            "2026-08-22" to true,
            "2026-08-23" to true,
            "2026-08-24" to true,
            "2026-08-25" to true,
            "2026-08-26" to true,
            "2026-08-27" to true,
            "2026-08-28" to true,
            "2026-08-29" to true,
            "2026-08-30" to true,
            "2026-08-31" to true,
            "2026-09-01" to false,
            "2026-09-02" to false,
            "2026-09-03" to false,
            "2026-09-04" to false,
            "2026-09-05" to false,
            "2026-09-06" to false,
        )
    }
}
