package app.opentasks.core.domain

import app.opentasks.core.model.InsightsRange
import app.opentasks.core.model.InsightsSelection
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TagId
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class InsightsEngineTest {
    private val engine: InsightsEngine = DefaultInsightsEngine()

    @Test
    fun sevenThirtyAndNinetyDayRangesUseLocalCalendarBoundaries() {
        val cases = listOf(
            Triple(
                InsightsRange.SEVEN_DAYS,
                Instant.parse("2026-07-20T17:00:00Z"),
                Instant.parse("2026-07-27T17:00:00Z"),
            ),
            Triple(
                InsightsRange.THIRTY_DAYS,
                Instant.parse("2026-06-27T17:00:00Z"),
                Instant.parse("2026-07-27T17:00:00Z"),
            ),
            Triple(
                InsightsRange.NINETY_DAYS,
                Instant.parse("2026-04-28T17:00:00Z"),
                Instant.parse("2026-07-27T17:00:00Z"),
            ),
        )

        cases.forEach { (range, expectedStart, expectedEnd) ->
            val result = engine.calculate(
                workspace = OpenTasksFixtures.snapshot,
                selection = InsightsSelection(range = range),
                now = Instant.parse("2026-07-27T12:00:00Z"),
                zoneId = ZoneId.of("Asia/Bangkok"),
            )

            assertEquals(expectedStart, result.interval.startInclusive)
            assertEquals(expectedEnd, result.interval.endExclusive)
            assertEquals(expectedStart, result.comparisonInterval.endExclusive)
        }
    }

    @Test
    fun springDstRangeEndsAtTheNextLocalMidnight() {
        val result = engine.calculate(
            workspace = OpenTasksFixtures.snapshot,
            selection = InsightsSelection(),
            now = Instant.parse("2026-03-08T16:00:00Z"),
            zoneId = ZoneId.of("America/New_York"),
        )

        assertEquals(Instant.parse("2026-03-02T05:00:00Z"), result.interval.startInclusive)
        assertEquals(Instant.parse("2026-03-09T04:00:00Z"), result.interval.endExclusive)
        assertEquals(
            Instant.parse("2026-02-23T05:00:00Z"),
            result.comparisonInterval.startInclusive,
        )
    }

    @Test
    fun fallDstRangeEndsAtTheNextLocalMidnight() {
        val result = engine.calculate(
            workspace = OpenTasksFixtures.snapshot,
            selection = InsightsSelection(),
            now = Instant.parse("2026-11-01T17:00:00Z"),
            zoneId = ZoneId.of("America/New_York"),
        )

        assertEquals(Instant.parse("2026-10-26T04:00:00Z"), result.interval.startInclusive)
        assertEquals(Instant.parse("2026-11-02T05:00:00Z"), result.interval.endExclusive)
        assertEquals(
            Instant.parse("2026-10-19T04:00:00Z"),
            result.comparisonInterval.startInclusive,
        )
    }

    @Test
    fun intervalExcludesItsExactEnd() {
        val result = engine.calculate(
            workspace = OpenTasksFixtures.snapshot,
            selection = InsightsSelection(),
            now = Instant.parse("2026-07-27T12:00:00Z"),
            zoneId = ZoneId.of("Asia/Bangkok"),
        )

        assertTrue(result.interval.contains(Instant.parse("2026-07-27T16:59:59.999999999Z")))
        assertFalse(result.interval.contains(Instant.parse("2026-07-27T17:00:00Z")))
    }

    @Test
    fun overdueRowsRespectSelectionAndExcludeCompletedOrBinTasks() {
        val now = Instant.parse("2026-07-27T12:00:00Z")
        val base = OpenTasksFixtures.tasks.first()
        val matching = base.copy(
            id = TaskId("matching"),
            due = base.due!!.copy(instant = now.minusSeconds(1)),
        )
        val otherTag = matching.copy(
            id = TaskId("other-tag"),
            tagIds = setOf(TagId("tag-admin")),
        )
        val completed = matching.copy(
            id = TaskId("completed"),
            semanticStatus = SemanticStatus.COMPLETED,
            completedAt = now.minusSeconds(2),
        )
        val inBin = matching.copy(
            id = TaskId("in-bin"),
            deletedAt = now.minusSeconds(2),
        )
        val workspace = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(matching, otherTag, completed, inBin),
        )

        val result = engine.calculate(
            workspace = workspace,
            selection = InsightsSelection(
                projectIds = setOf(OpenTasksFixtures.studioProject.id),
                tagIds = setOf(TagId("tag-deep-work")),
            ),
            now = now,
            zoneId = ZoneId.of("Asia/Bangkok"),
        )

        assertEquals(listOf(TaskId("matching")), result.overdue.map { it.taskId })
    }
}
