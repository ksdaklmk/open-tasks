package app.opentasks.widget

import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayWidgetProjectionTest {
    private val zone: ZoneId = ZoneId.of("Asia/Bangkok")
    private val today: LocalDate = LocalDate.of(2026, 7, 26)

    /** A representative "current instant": noon today, in [zone]. */
    private val now: Instant = today.atTime(12, 0).atZone(zone).toInstant()
    private val revision = Revision(DeviceId("widget-test-device"), 1, 0)

    private fun task(
        id: String,
        title: String,
        priority: Priority = Priority.NONE,
        start: ZonedMoment? = null,
        due: ZonedMoment? = null,
        semanticStatus: SemanticStatus = SemanticStatus.PLANNED,
        deletedAt: Instant? = null,
    ): Task = Task(
        id = TaskId(id),
        workspaceId = OpenTasksFixtures.workspaceId,
        projectId = OpenTasksFixtures.studioProject.id,
        statusId = OpenTasksFixtures.statusId(OpenTasksFixtures.studioProject.id, semanticStatus),
        semanticStatus = semanticStatus,
        title = title,
        priority = priority,
        start = start,
        due = due,
        deletedAt = deletedAt,
        revision = revision,
    )

    private fun snapshotOf(tasks: List<Task>): WorkspaceSnapshot =
        OpenTasksFixtures.snapshot.copy(tasks = tasks)

    private fun momentAt(hour: Int, zoneId: ZoneId = zone): ZonedMoment =
        ZonedMoment(today.atTime(hour, 0).atZone(zoneId).toInstant(), zoneId.id)

    @Test
    fun todayByStartCountsAnOpenTaskStartingToday() {
        val startingToday = task(id = "task-a", title = "Starting today", start = momentAt(9))

        val projection = computeTodayProjection(
            snapshotOf(listOf(startingToday)),
            today,
            zone,
            now,
            titlesPermitted = true,
        )

        assertEquals(1, projection.openTodayCount)
        assertEquals(listOf("Starting today"), projection.focusEntries.map { it.title })
    }

    @Test
    fun fallbackToDueCountsAnOpenTaskWithNoStartDueToday() {
        val dueToday = task(id = "task-b", title = "Due today", due = momentAt(17))

        val projection = computeTodayProjection(
            snapshotOf(listOf(dueToday)),
            today,
            zone,
            now,
            titlesPermitted = true,
        )

        assertEquals(1, projection.openTodayCount)
        assertEquals(listOf("Due today"), projection.focusEntries.map { it.title })
    }

    @Test
    fun binAndCompletedTasksAreExcludedFromTodayAndOverdue() {
        val completedToday = task(
            id = "task-c",
            title = "Completed today",
            due = momentAt(10),
            semanticStatus = SemanticStatus.COMPLETED,
        )
        val binnedToday = task(
            id = "task-d",
            title = "Binned today",
            due = momentAt(11),
            deletedAt = Instant.parse("2026-07-25T00:00:00Z"),
        )

        val projection = computeTodayProjection(
            snapshotOf(listOf(completedToday, binnedToday)),
            today,
            zone,
            now,
            titlesPermitted = true,
        )

        assertEquals(0, projection.openTodayCount)
        assertEquals(0, projection.overdueCount)
        assertTrue(projection.focusEntries.isEmpty())
    }

    @Test
    fun overdueBoundaryAtNowDiscriminatesEarlierFromLaterToday() {
        val dueEarlierToday = task(id = "task-e", title = "Due earlier today", due = momentAt(9))
        val dueLaterToday = task(id = "task-f", title = "Due later today", due = momentAt(17))
        val dueExactlyAtNow = task(
            id = "task-m",
            title = "Due exactly at now",
            due = ZonedMoment(now, zone.id),
        )
        val dueJustBeforeNow = task(
            id = "task-n",
            title = "Due just before now",
            due = ZonedMoment(now.minusSeconds(1), zone.id),
        )

        val projection = computeTodayProjection(
            snapshotOf(listOf(dueEarlierToday, dueLaterToday, dueExactlyAtNow, dueJustBeforeNow)),
            today,
            zone,
            now,
            titlesPermitted = true,
        )

        // Earlier-today and one-second-before-now are strictly before `now`
        // and so overdue; later-today and exactly-at-now are not, even
        // though all four still land in today's count.
        assertEquals(2, projection.overdueCount)
        assertEquals(4, projection.openTodayCount)
    }

    @Test
    fun threeTitleCapAndOrderingByDueThenPriority() {
        val earlyLow = task(
            id = "task-g",
            title = "Early low",
            priority = Priority.LOW,
            due = momentAt(8),
        )
        val earlyHigh = task(
            id = "task-h",
            title = "Early high",
            priority = Priority.HIGH,
            due = momentAt(8),
        )
        val midday = task(
            id = "task-i",
            title = "Midday",
            priority = Priority.MEDIUM,
            due = momentAt(12),
        )
        val evening = task(
            id = "task-j",
            title = "Evening",
            priority = Priority.URGENT,
            due = momentAt(20),
        )

        val projection = computeTodayProjection(
            snapshotOf(listOf(earlyLow, earlyHigh, midday, evening)),
            today,
            zone,
            now,
            titlesPermitted = true,
        )

        assertEquals(4, projection.openTodayCount)
        assertEquals(
            listOf("Early high", "Early low", "Midday"),
            projection.focusEntries.map { it.title },
        )
    }

    @Test
    fun titlesNotPermittedHidesTitlesButKeepsCounts() {
        val dueToday = task(id = "task-k", title = "Secret title", due = momentAt(9))

        val projection = computeTodayProjection(
            snapshotOf(listOf(dueToday)),
            today,
            zone,
            now,
            titlesPermitted = false,
        )

        assertEquals(1, projection.openTodayCount)
        assertTrue(projection.focusEntries.isEmpty())
    }

    @Test
    fun focusEntriesCarryIdsAndBlockedTasksAreNotCompletable() {
        val snapshot = OpenTasksFixtures.snapshot
        val blocked = snapshot.tasks.first { it.isBlocked }.copy(
            start = null,
            due = ZonedMoment(
                Instant.parse("2026-07-26T10:00:00Z"),
                "Asia/Bangkok",
            ),
        )
        val projection = computeTodayProjection(
            snapshot = snapshot.copy(tasks = listOf(blocked)),
            today = LocalDate.of(2026, 7, 26),
            zone = ZoneId.of("Asia/Bangkok"),
            now = Instant.parse("2026-07-26T03:00:00Z"),
            titlesPermitted = true,
        )
        val blockedEntry = projection.focusEntries.single()
        assertEquals(blocked.id.value, blockedEntry.taskId)
        assertFalse(blockedEntry.completable)
    }

    @Test
    fun zoneRespectingDateBucketingExcludesATomorrowLocalStart() {
        // 23:30 UTC on today's date is already tomorrow, 06:30, in the
        // task's own stored zone (Asia/Bangkok, UTC+7) -- bucketing must
        // use that stored zone, not the device zone passed to the function.
        val deviceZone = ZoneId.of("UTC")
        val tomorrowInBangkok = task(
            id = "task-l",
            title = "Tomorrow in Bangkok",
            start = ZonedMoment(Instant.parse("2026-07-26T23:30:00Z"), zone.id),
        )

        val projection = computeTodayProjection(
            snapshotOf(listOf(tomorrowInBangkok)),
            today,
            deviceZone,
            now,
            titlesPermitted = true,
        )

        assertEquals(0, projection.openTodayCount)
        assertTrue(projection.focusEntries.isEmpty())
    }
}
