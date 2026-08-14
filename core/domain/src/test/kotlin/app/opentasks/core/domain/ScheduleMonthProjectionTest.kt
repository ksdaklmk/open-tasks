package app.opentasks.core.domain

import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.ZonedMoment
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleMonthProjectionTest {
    private val displayZone = ZoneId.of("Asia/Bangkok")
    private val clock = Clock.fixed(Instant.parse("2026-02-15T03:00:00Z"), displayZone)

    @Test
    fun monthIsMondayFirstAndAlwaysContainsFortyTwoDays() {
        val days = project(YearMonth.of(2026, 2)).days
        assertEquals(42, days.size)
        assertEquals(YearMonth.of(2026, 2), project(YearMonth.of(2026, 2)).month)
        assertEquals(java.time.LocalDate.of(2026, 1, 26), days.first().date)
        assertEquals(java.time.LocalDate.of(2026, 3, 8), days.last().date)
        assertEquals(6, days.chunked(7).size)
        assertEquals(java.time.DayOfWeek.MONDAY, days.first().date.dayOfWeek)
        assertEquals(java.time.DayOfWeek.SUNDAY, days[6].date.dayOfWeek)
    }

    @Test
    fun startPrecedesDueAndPlacementUsesItsStoredZone() {
        val task = task("start", start = moment("2026-02-01T00:30:00Z", "America/Los_Angeles"), due = moment("2026-02-02T10:00:00Z", "UTC"))
        val days = project(YearMonth.of(2026, 2), task).days
        assertEquals(listOf(task), days.single { it.date.toString() == "2026-01-31" }.tasks)
    }

    @Test
    fun dueFallbackUsesStoredZoneAcrossDisplayBoundary() {
        val task = task("due", start = null, due = moment("2026-02-01T00:30:00Z", "America/Los_Angeles"))
        val days = project(YearMonth.of(2026, 2), task).days
        assertEquals(listOf(task), days.single { it.date.toString() == "2026-01-31" }.tasks)
    }

    @Test
    fun completedRemainsAndBinnedIsExcluded() {
        val completed = task("done").copy(semanticStatus = SemanticStatus.COMPLETED)
        val binned = task("binned").copy(deletedAt = clock.instant())
        val cell = project(YearMonth.of(2026, 2), completed, binned).days.single { it.date.toString() == "2026-02-01" }
        assertEquals(listOf(completed), cell.tasks)
        assertEquals(1, cell.completedCount)
    }

    @Test
    fun countsCompletedAndExactOpenOverdueWhileDensityCapsAtSix() {
        val tasks = (1..7).map { task("task-$it", start = null, due = moment("2026-02-01T0${it}:00:00Z", "UTC")) }
            .mapIndexed { index, task -> if (index == 0) task.copy(semanticStatus = SemanticStatus.COMPLETED) else task }
        val cell = computeScheduleMonthProjection(
            OpenTasksFixtures.snapshot.copy(tasks = tasks),
            YearMonth.of(2026, 2),
            Clock.fixed(Instant.parse("2026-02-01T03:00:00Z"), displayZone),
            displayZone,
        ).days.single { it.date.toString() == "2026-02-01" }
        assertEquals(7, cell.totalCount)
        assertEquals(1, cell.completedCount)
        assertEquals(1, cell.overdueCount)
        assertEquals(6, cell.densityDotCount)
        assertTrue(cell.hasDensityOverflow)
    }

    @Test
    fun tasksInEachCellSortByPlacementLocalTimeThenTitle() {
        val late = task("late", title = "Alpha", start = moment("2026-02-01T11:00:00Z", "UTC"))
        val early = task("early", title = "Zulu", start = moment("2026-02-01T09:00:00Z", "UTC"))
        val tieB = task("tie-b", title = "beta", start = moment("2026-02-01T10:00:00Z", "UTC"))
        val tieA = task("tie-a", title = "Alpha", start = moment("2026-02-01T10:00:00Z", "UTC"))
        val tasks = project(YearMonth.of(2026, 2), late, early, tieB, tieA).days.single { it.date.toString() == "2026-02-01" }.tasks
        assertEquals(listOf("early", "tie-a", "tie-b", "late"), tasks.map { it.id.value })
    }

    private fun project(month: YearMonth, vararg tasks: Task) =
        computeScheduleMonthProjection(OpenTasksFixtures.snapshot.copy(tasks = tasks.toList()), month, clock, displayZone)

    private fun task(id: String, title: String = id, start: ZonedMoment? = moment("2026-02-01T10:00:00Z", "UTC"), due: ZonedMoment? = null) =
        OpenTasksFixtures.tasks.first().copy(id = TaskId(id), title = title, start = start, due = due)

    private fun moment(value: String, zone: String) = ZonedMoment(Instant.parse(value), zone)
}
