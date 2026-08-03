package app.opentasks.core.data

import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.TrashPolicy
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.ZonedMoment
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class RetiredBlobSetTest {

    private val repository = InMemoryVaultRepository()

    @Test
    fun permanentTaskDeletionRetiresReferencedBlobSets() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            val blobSetId = BlobSetId.new()
            val attachment = Attachment(
                id = AttachmentId.new(), taskId = task.id,
                displayName = "site-plan.pdf", mimeType = "application/pdf",
                byteCount = 9_000_000L,
                contentHash = "ab".repeat(32),
                blobSetId = blobSetId, chunkCount = 3, deletedAt = null,
                revision = task.revision,
            )
            repository.execute(DomainCommand.RegisterAttachment(attachment))
            repository.execute(DomainCommand.DeleteTask(task.id))

            repository.execute(DomainCommand.PermanentlyDeleteTask(task.id))

            val retired = repository.currentWorkspace().retiredBlobSets.single()
            assertEquals(blobSetId, retired.blobSetId)
            assertEquals(3, retired.chunkCount)
            assertTrue(repository.currentWorkspace().attachments.none { it.taskId == task.id })
        }
    }

    @Test
    fun tombstoneDeleteAndBloblessPurgeWriteNoRetiredRow() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            val blobless = Attachment(
                id = AttachmentId.new(), taskId = task.id,
                displayName = "pending.bin", mimeType = "application/octet-stream",
                byteCount = 10L, contentHash = "cd".repeat(32),
                blobSetId = null, chunkCount = 0, deletedAt = null,
                revision = task.revision,
            )
            repository.execute(DomainCommand.RegisterAttachment(blobless))

            repository.execute(DomainCommand.DeleteAttachment(blobless.id))
            repository.execute(DomainCommand.DeleteTask(task.id))
            repository.execute(DomainCommand.PermanentlyDeleteTask(task.id))

            assertTrue(repository.currentWorkspace().retiredBlobSets.isEmpty())
        }
    }

    @Test
    fun tombstonedAttachmentWithBlobSetIsStillRetiredOnPermanentDelete() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            val blobSetId = BlobSetId.new()
            val attachment = Attachment(
                id = AttachmentId.new(), taskId = task.id,
                displayName = "superseded.pdf", mimeType = "application/pdf",
                byteCount = 5_000_000L, contentHash = "12".repeat(32),
                blobSetId = blobSetId, chunkCount = 2, deletedAt = null,
                revision = task.revision,
            )
            repository.execute(DomainCommand.RegisterAttachment(attachment))

            // Tombstoning the attachment still leaves its blobSetId set; purging its
            // owner task must retire the blob set exactly as an active attachment would.
            repository.execute(DomainCommand.DeleteAttachment(attachment.id))
            repository.execute(DomainCommand.DeleteTask(task.id))
            repository.execute(DomainCommand.PermanentlyDeleteTask(task.id))

            val retired = repository.currentWorkspace().retiredBlobSets.single()
            assertEquals(blobSetId, retired.blobSetId)
            assertEquals(2, retired.chunkCount)
        }
    }

    @Test
    fun trashPurgeRetiresReferencedBlobSets() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            val blobSetId = BlobSetId.new()
            val attachment = Attachment(
                id = AttachmentId.new(), taskId = task.id,
                displayName = "deck.pdf", mimeType = "application/pdf",
                byteCount = 2_000_000L, contentHash = "ef".repeat(32),
                blobSetId = blobSetId, chunkCount = 1, deletedAt = null,
                revision = task.revision,
            )
            repository.execute(DomainCommand.RegisterAttachment(attachment))
            val deletedAt = Instant.parse("2026-01-01T00:00:00Z")
            repository.execute(DomainCommand.DeleteTask(task.id, deletedAt))

            repository.execute(
                DomainCommand.PurgeExpiredTrash(
                    deletedAt.plus(TrashPolicy.RETENTION_DAYS + 1, ChronoUnit.DAYS),
                ),
            )

            val retired = repository.currentWorkspace().retiredBlobSets.single()
            assertEquals(blobSetId, retired.blobSetId)
            assertEquals(1, retired.chunkCount)
            assertTrue(repository.currentWorkspace().tasks.none { it.id == task.id })
            assertTrue(repository.currentWorkspace().attachments.none { it.taskId == task.id })
        }
    }

    @Test
    fun undoingGeneratedOccurrenceCompletionRetiresItsBlobBearingAttachments() = runBlocking {
        withTimeout(5_000) {
            val original = repository.currentWorkspace().tasks.first()
            val due = ZonedMoment(
                instant = Instant.parse("2026-07-31T09:30:00Z"),
                zoneId = "Asia/Bangkok",
            )
            repository.execute(
                DomainCommand.UpdateTask(
                    taskId = original.id,
                    title = original.title,
                    description = original.description,
                    projectId = original.projectId,
                    priority = original.priority,
                    due = due,
                    recurrence = RecurrenceRule(RecurrenceFrequency.MONTHLY, count = 3),
                    estimate = original.estimate,
                ),
            )
            val completed = repository.execute(
                DomainCommand.CompleteTask(
                    taskId = original.id,
                    completedAt = Instant.parse("2026-07-31T10:00:00Z"),
                ),
            ) as CommandResult.Success
            val generated = repository.currentWorkspace().tasks.single {
                it.recurrenceSeriesId == original.id && it.id != original.id
            }
            val blobSetId = BlobSetId.new()
            repository.execute(
                DomainCommand.RegisterAttachment(
                    Attachment(
                        id = AttachmentId.new(), taskId = generated.id,
                        displayName = "occurrence-notes.pdf", mimeType = "application/pdf",
                        byteCount = 1_000_000L, contentHash = "34".repeat(32),
                        blobSetId = blobSetId, chunkCount = 1, deletedAt = null,
                        revision = generated.revision,
                    ),
                ),
            )

            repository.execute(checkNotNull(completed.undo))

            val afterUndo = repository.currentWorkspace()
            assertTrue(afterUndo.tasks.none { it.id == generated.id })
            assertTrue(afterUndo.attachments.none { it.taskId == generated.id })
            assertEquals(blobSetId, afterUndo.retiredBlobSets.single().blobSetId)
        }
    }
}
