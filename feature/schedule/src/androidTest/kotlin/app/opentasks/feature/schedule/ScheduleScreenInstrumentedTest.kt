package app.opentasks.feature.schedule

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.ZonedMoment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

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
            OpenTasksTheme {
                ScheduleScreen(
                    tasks = listOf(mondayTask, tuesdayTask),
                    projectNames = emptyMap(),
                    expanded = false,
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

        composeRule.setContent {
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
                    today = LocalDate.parse("2026-07-27"),
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
}
