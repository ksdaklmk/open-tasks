package app.opentasks.core.data

import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.BlobSetId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
