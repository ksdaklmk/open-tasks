package app.opentasks.core.data.backup

import app.opentasks.core.data.db.AttachmentEntity
import app.opentasks.core.data.db.RetiredBlobSetEntity
import app.opentasks.core.data.db.TaskEntity
import app.opentasks.core.data.db.TombstoneEntity
import app.opentasks.core.domain.TrashPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Exercises `accountForRetentionPurge` directly, without Room or SQLCipher.
 *
 * The function is a pure computation over [BackupRecordV1] lists, so the
 * settle-step drift accounting a staged-vault verification depends on is
 * unit-testable here; `BackupRecordImporterInstrumentedTest` covers the same
 * rule end-to-end against a real staged database.
 */
class RetentionPurgeAccountingTest {

    private val now = { Instant.parse("2026-08-03T00:00:00Z") }

    // Comfortably older than the retention window, so the task itself is a
    // legitimate retention-purge target at [now].
    private val deletedAt = now().minus(TrashPolicy.RETENTION_DAYS + 5, ChronoUnit.DAYS)

    // Well after the task's retention eligibility and strictly before [now].
    private val purgedAt = now().minusSeconds(10).toEpochMilli()

    @Test
    fun retiredBlobSetMatchingAPurgedAttachmentIsAcceptedDrift() {
        val verified = listOf(task(), attachment(blobSetId = "blob-1", chunkCount = 3))
        val actual = listOf(
            tombstone(),
            retiredBlobSet(blobSetId = "blob-1", chunkCount = 3, at = purgedAt),
        )

        val accounting = accountForRetentionPurge(
            verified = verified,
            actual = actual,
            journalEntryCount = verified.size + actual.size,
            now = now,
        )

        assertEquals(1, accounting.purgedTaskCount)
        assertEquals(verified.size, accounting.removedRecordCount)
        assertEquals(verified.size + actual.size, accounting.journalEntryCount)
    }

    @Test
    fun retiredBlobSetNotMatchingAnyPurgedAttachmentFailsClosed() {
        val verified = listOf(task(), attachment(blobSetId = "blob-1", chunkCount = 3))
        val actual = listOf(
            tombstone(),
            // No purged attachment carries this identity.
            retiredBlobSet(blobSetId = "blob-unrelated", chunkCount = 3, at = purgedAt),
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            accountForRetentionPurge(
                verified = verified,
                actual = actual,
                journalEntryCount = verified.size + actual.size,
                now = now,
            )
        }
        assertEquals(
            "The staged vault gained a record its retention purge did not write",
            failure.message,
        )
    }

    @Test
    fun retiredBlobSetWithAMismatchedChunkCountFailsClosed() {
        val verified = listOf(task(), attachment(blobSetId = "blob-1", chunkCount = 3))
        val actual = listOf(
            tombstone(),
            retiredBlobSet(blobSetId = "blob-1", chunkCount = 1, at = purgedAt),
        )

        assertThrows(IllegalStateException::class.java) {
            accountForRetentionPurge(
                verified = verified,
                actual = actual,
                journalEntryCount = verified.size + actual.size,
                now = now,
            )
        }
    }

    @Test
    fun retiredBlobSetWithARevisedHistoryFailsClosed() {
        val verified = listOf(task(), attachment(blobSetId = "blob-1", chunkCount = 3))
        val actual = listOf(
            tombstone(),
            RetiredBlobSetEntity(
                blobSetId = "blob-1",
                chunkCount = 3,
                retiredAtEpochMillis = purgedAt,
                revisionWallMillis = purgedAt,
                revisionLogical = 1,
                revisionDeviceId = "device-1",
            ).toBackupRecordV1(),
        )

        assertThrows(IllegalStateException::class.java) {
            accountForRetentionPurge(
                verified = verified,
                actual = actual,
                journalEntryCount = verified.size + actual.size,
                now = now,
            )
        }
    }

    @Test
    fun retiredBlobSetTooEarlyForItsOwningTasksEligibilityFailsClosed() {
        val verified = listOf(task(), attachment(blobSetId = "blob-1", chunkCount = 3))
        val actual = listOf(
            tombstone(),
            // Retired the moment the task was binned, long before its
            // retention window elapsed.
            retiredBlobSet(blobSetId = "blob-1", chunkCount = 3, at = deletedAt.toEpochMilli()),
        )

        assertThrows(IllegalStateException::class.java) {
            accountForRetentionPurge(
                verified = verified,
                actual = actual,
                journalEntryCount = verified.size + actual.size,
                now = now,
            )
        }
    }

    @Test
    fun retiredBlobSetFromTheFutureFailsClosed() {
        val verified = listOf(task(), attachment(blobSetId = "blob-1", chunkCount = 3))
        val actual = listOf(
            tombstone(),
            retiredBlobSet(
                blobSetId = "blob-1",
                chunkCount = 3,
                at = now().plusSeconds(60).toEpochMilli(),
            ),
        )

        assertThrows(IllegalStateException::class.java) {
            accountForRetentionPurge(
                verified = verified,
                actual = actual,
                journalEntryCount = verified.size + actual.size,
                now = now,
            )
        }
    }

    private fun task(): BackupRecordV1 = TaskEntity(
        id = "task-1",
        workspaceId = "workspace-1",
        projectId = null,
        parentTaskId = null,
        statusId = "status-1",
        semanticStatus = "PLANNED",
        title = "Task",
        descriptionCiphertext = ByteArray(0),
        priority = "MEDIUM",
        startEpochMillis = null,
        startZoneId = null,
        dueEpochMillis = null,
        dueZoneId = null,
        recurrenceFrequency = null,
        recurrenceInterval = null,
        recurrenceWeekdays = null,
        recurrenceCount = null,
        recurrenceEndDate = null,
        recurrenceSeriesId = null,
        recurrenceAnchorEpochMillis = null,
        recurrenceAnchorZoneId = null,
        recurrenceOccurrenceIndex = null,
        estimateSeconds = null,
        milestoneId = null,
        completedAtEpochMillis = null,
        deletedAtEpochMillis = deletedAt.toEpochMilli(),
        revisionWallMillis = 1,
        revisionLogical = 0,
        revisionDeviceId = "device-1",
    ).toBackupRecordV1()

    private fun attachment(blobSetId: String, chunkCount: Int): BackupRecordV1 = AttachmentEntity(
        id = "attachment-1",
        taskId = "task-1",
        displayNameCiphertext = ByteArray(0),
        mimeType = "application/pdf",
        byteCount = 1,
        contentHash = "hash",
        blobSetId = blobSetId,
        chunkCount = chunkCount,
        deletedAtEpochMillis = null,
        revisionWallMillis = 1,
        revisionLogical = 0,
        revisionDeviceId = "device-1",
    ).toBackupRecordV1()

    private fun tombstone(): BackupRecordV1 = TombstoneEntity(
        objectId = "task-1",
        objectType = "task",
        deletedAtEpochMillis = deletedAt.toEpochMilli(),
        purgeAfterEpochMillis = deletedAt.toEpochMilli(),
        revisionWallMillis = 2,
        revisionLogical = 1,
        revisionDeviceId = "device-1",
    ).toBackupRecordV1()

    private fun retiredBlobSet(
        blobSetId: String,
        chunkCount: Int,
        at: Long,
    ): BackupRecordV1 = RetiredBlobSetEntity(
        blobSetId = blobSetId,
        chunkCount = chunkCount,
        retiredAtEpochMillis = at,
        revisionWallMillis = at,
        revisionLogical = 0,
        revisionDeviceId = "device-1",
    ).toBackupRecordV1()
}
