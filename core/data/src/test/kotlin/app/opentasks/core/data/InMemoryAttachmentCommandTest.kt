package app.opentasks.core.data

import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.model.ActivityKind
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InMemoryAttachmentCommandTest {

    private val repository = InMemoryVaultRepository()

    @Test
    fun registerAttachmentSanitizesNameAppearsInSnapshotAndAddsActivity() = runBlocking {
        withTimeout(5_000) {
            val attachment = attachment(repository.currentWorkspace().tasks.first(), displayName = "  venue.pdf  ")

            val result = repository.execute(DomainCommand.RegisterAttachment(attachment))

            assertTrue(result is CommandResult.Success)
            assertNull((result as CommandResult.Success).undo)
            assertEquals("venue.pdf", repository.currentWorkspace().attachments.single().displayName)
            assertEquals(
                ActivityKind.ATTACHMENT_ADDED,
                repository.currentWorkspace().activityEntries.last().kind,
            )
        }
    }

    @Test
    fun registerAttachmentRejectsInvalidOwnerNameHashAndChunkMetadata() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            val missingOwner = attachment(task, taskId = TaskId("missing"))
            val blankName = attachment(task, displayName = "   ")
            val longName = attachment(task, displayName = "a".repeat(256))
            val malformedHash = attachment(task, contentHash = "A".repeat(64))
            val badChunks = attachment(task, byteCount = 4L * 1024 * 1024 + 1, chunkCount = 1)

            assertEquals(RejectionReason.NOT_FOUND, rejectionOf(missingOwner).reason)
            assertEquals(RejectionReason.EMPTY_ATTACHMENT_NAME, rejectionOf(blankName).reason)
            assertEquals(RejectionReason.ATTACHMENT_NAME_TOO_LONG, rejectionOf(longName).reason)
            assertEquals(RejectionReason.INVALID_ATTACHMENT_METADATA, rejectionOf(malformedHash).reason)
            assertEquals(RejectionReason.INVALID_ATTACHMENT_METADATA, rejectionOf(badChunks).reason)
        }
    }

    @Test
    fun registerAttachmentRejectsTheHundredAndFirstActiveAttachment() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            repeat(100) { index ->
                assertTrue(repository.execute(DomainCommand.RegisterAttachment(attachment(task, id = "attachment-$index"))) is CommandResult.Success)
            }

            assertEquals(
                RejectionReason.ATTACHMENT_LIMIT_REACHED,
                rejectionOf(attachment(task, id = "attachment-over-cap")).reason,
            )
        }
    }

    @Test
    fun registerAttachmentDoesNotMoveActiveAttachmentToTaskAtCapacity() = runBlocking {
        withTimeout(5_000) {
            val sourceTask = repository.currentWorkspace().tasks.first()
            val targetTask = repository.currentWorkspace().tasks.first { it.id != sourceTask.id }
            repository.execute(
                DomainCommand.RegisterAttachment(attachment(sourceTask, id = "shared")),
            )
            repeat(100) { index ->
                repository.execute(
                    DomainCommand.RegisterAttachment(attachment(targetTask, id = "target-$index")),
                )
            }

            val result = repository.execute(
                DomainCommand.RegisterAttachment(attachment(targetTask, id = "shared")),
            )

            assertTrue(result is CommandResult.Rejected)
            assertEquals(
                RejectionReason.ATTACHMENT_LIMIT_REACHED,
                (result as CommandResult.Rejected).reason,
            )
            assertEquals(
                100,
                repository.currentWorkspace().attachments.count { it.taskId == targetTask.id },
            )
            assertEquals(
                sourceTask.id,
                repository.currentWorkspace().attachments.single { it.id.value == "shared" }.taskId,
            )
        }
    }

    @Test
    fun deleteTombstonesAttachmentProvidesExactRestoreUndoAndRestoreDoesNotAddActivity() = runBlocking {
        withTimeout(5_000) {
            val original = attachment(repository.currentWorkspace().tasks.first())
            repository.execute(DomainCommand.RegisterAttachment(original))
            val registered = repository.currentWorkspace().attachments.single()
            val activitiesBeforeDelete = repository.currentWorkspace().activityEntries.size
            val deletedAt = Instant.parse("2026-08-02T00:00:00Z")

            val result = repository.execute(DomainCommand.DeleteAttachment(registered.id, deletedAt))
                as CommandResult.Success

            val tombstone = repository.currentWorkspace().attachments.single()
            assertEquals(deletedAt, tombstone.deletedAt)
            assertEquals(registered, (result.undo as DomainCommand.RestoreAttachment).attachment)
            assertTrue(
                repository.currentWorkspace().activityEntries.any {
                    it.kind == ActivityKind.ATTACHMENT_REMOVED
                },
            )
            repository.execute(result.undo as DomainCommand)
            assertEquals(registered, repository.currentWorkspace().attachments.single())
            assertEquals(activitiesBeforeDelete + 1, repository.currentWorkspace().activityEntries.size)
        }
    }

    private suspend fun rejectionOf(attachment: Attachment): CommandResult.Rejected =
        repository.execute(DomainCommand.RegisterAttachment(attachment)) as CommandResult.Rejected

    private fun attachment(
        task: Task,
        id: String = "attachment",
        taskId: TaskId = task.id,
        displayName: String = "receipt.pdf",
        byteCount: Long = 1,
        contentHash: String = "a".repeat(64),
        chunkCount: Int = 1,
    ) = Attachment(
        id = AttachmentId(id),
        taskId = taskId,
        displayName = displayName,
        mimeType = "application/pdf",
        byteCount = byteCount,
        contentHash = contentHash,
        blobSetId = null,
        chunkCount = chunkCount,
        deletedAt = null,
        revision = Revision(task.revision.deviceId, 1, 0),
    )
}
