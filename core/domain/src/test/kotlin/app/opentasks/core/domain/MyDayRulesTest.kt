package app.opentasks.core.domain

import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.ZonedMoment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class MyDayRulesTest {
    @Test
    fun rankBetweenOrdersStrictlyAndFallsBackToNull() {
        val mid = requireNotNull(myDayRankBetween("a0", "a1"))
        assertTrue("a0" < mid && mid < "a1")
        val head = requireNotNull(myDayRankBetween(null, "a0"))
        assertTrue(head < "a0")
        val tail = requireNotNull(myDayRankBetween("a1", null))
        assertTrue(tail > "a1")
        assertNull(myDayRankBetween("m".repeat(MAX_MY_DAY_RANK_LENGTH), "m".repeat(199) + "n"))
    }

    @Test
    fun rankForIndexIsStrictlyIncreasing() {
        assertTrue(myDayRankForIndex(0) < myDayRankForIndex(1))
        assertTrue(myDayRankForIndex(199) < myDayRankForIndex(200))
    }

    @Test
    fun suggestionsAreOverdueThenTodayOpenNonMembersCappedAtTen() {
        val today = LocalDate.of(2026, 7, 26)
        val overdue = openTask("overdue", "Overdue filing", due = moment(2026, 7, 25, 9))
        val startsToday = openTask("starts-today", "Starts today", start = moment(2026, 7, 26, 8))
        val dueToday = openTask("due-today", "Due today", due = moment(2026, 7, 26, 9))
        val filled = (2..13).map { index ->
            openTask("today-$index", "Today $index", due = moment(2026, 7, 26, 8 + index))
        }
        val completed = openTask("completed", "Completed today", due = moment(2026, 7, 26, 10))
            .copy(
                semanticStatus = SemanticStatus.COMPLETED,
                completedAt = Instant.parse("2026-07-26T01:00:00Z"),
            )
        val binned = openTask("binned", "Binned today", due = moment(2026, 7, 26, 11))
            .copy(deletedAt = Instant.parse("2026-07-26T01:00:00Z"))
        val member = openTask("member", "Already planned", due = moment(2026, 7, 26, 12))
        val tasks = listOf(overdue, startsToday, dueToday) + filled +
            listOf(completed, binned, member)

        val suggestions = myDaySuggestions(
            tasks = tasks,
            memberIds = setOf(member.id),
            today = today,
            zoneId = ZoneId.of("Asia/Bangkok"),
        )

        assertEquals(
            listOf(overdue, startsToday, dueToday) + filled.take(7),
            suggestions,
        )
        assertEquals(10, suggestions.size)
    }

    private fun openTask(
        id: String,
        title: String,
        due: ZonedDateTime? = null,
        start: ZonedDateTime? = null,
    ): Task {
        val template = OpenTasksFixtures.tasks.first()
        return Task(
            id = TaskId(id),
            workspaceId = template.workspaceId,
            projectId = template.projectId,
            statusId = template.statusId,
            semanticStatus = template.semanticStatus,
            title = title,
            due = due?.let { ZonedMoment(it.toInstant(), it.zone.id) },
            start = start?.let { ZonedMoment(it.toInstant(), it.zone.id) },
            revision = template.revision,
        )
    }

    private fun moment(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
    ): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, ZoneId.of("Asia/Bangkok"))
}
