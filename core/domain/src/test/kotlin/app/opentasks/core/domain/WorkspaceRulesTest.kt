package app.opentasks.core.domain

import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TimeEntry
import app.opentasks.core.model.TimeEntryId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class WorkspaceRulesTest {
    @Test
    fun progressExcludesTrash() {
        val now = Instant.parse("2026-07-26T10:00:00Z")
        val tasks = OpenTasksFixtures.tasks + OpenTasksFixtures.tasks.first().copy(
            id = TaskId("trashed"),
            deletedAt = now,
        )
        val summary = ProgressRules.forTasks(tasks)

        assertEquals(5, summary.total)
        assertEquals(1, summary.completed)
        assertEquals(0.2f, summary.ratio)
    }

    @Test
    fun trashPurgesAtThirtyDaysNotBefore() {
        val deleted = Instant.parse("2026-01-01T00:00:00Z")
        assertFalse(TrashPolicy.isEligibleForPurge(deleted, deleted.plus(Duration.ofDays(29))))
        assertTrue(TrashPolicy.isEligibleForPurge(deleted, deleted.plus(Duration.ofDays(30))))
    }

    @Test
    fun overlappingTimersArePreservedAndFlagged() {
        val first = TimeEntry(
            TimeEntryId("first"),
            TaskId("one"),
            DeviceId("phone"),
            Instant.parse("2026-07-26T08:00:00Z"),
            Instant.parse("2026-07-26T09:00:00Z"),
        )
        val second = TimeEntry(
            TimeEntryId("second"),
            TaskId("two"),
            DeviceId("tablet"),
            Instant.parse("2026-07-26T08:30:00Z"),
            Instant.parse("2026-07-26T09:30:00Z"),
        )

        val result = TimerRules.reconcile(
            listOf(first, second),
            Instant.parse("2026-07-26T10:00:00Z"),
        )

        assertEquals(listOf(first, second), result.entries)
        assertEquals(Duration.ofMinutes(30), result.conflicts.single().overlap)
    }

    @Test
    fun searchNormalizationIsAccentAndWhitespaceInsensitive() {
        assertEquals("resume review", SearchNormalizer.normalize("  Résumé   REVIEW "))
    }
}
