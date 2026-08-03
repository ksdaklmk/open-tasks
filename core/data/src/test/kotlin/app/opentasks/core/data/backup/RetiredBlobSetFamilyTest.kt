package app.opentasks.core.data.backup

import app.opentasks.core.data.InMemoryVaultRepository
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.Task
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredBlobSetFamilyTest {

    private val journal = InMemoryBackupJournal()
    private val repository = InMemoryVaultRepository(backupJournal = journal)

    @Test
    fun purgeJournalsRetiredRowAndCollectionJournalsDelete() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            val blobSetId = BlobSetId.new()
            repository.execute(
                DomainCommand.RegisterAttachment(
                    attachment(task, blobSetId = blobSetId, chunkCount = 2),
                ),
            )
            repository.execute(DomainCommand.DeleteTask(task.id))

            repository.execute(DomainCommand.PermanentlyDeleteTask(task.id))

            val upserts = journal.entries.filter {
                it.objectType == "RETIRED_BLOB_SET" && it.mutationKind == BackupMutationKind.UPSERT
            }
            assertEquals(blobSetId.value, upserts.last().objectId)
            assertEquals(blobSetId, repository.currentWorkspace().retiredBlobSets.single().blobSetId)

            val collect = repository.execute(
                DomainCommand.MarkRetiredBlobSetCollected(blobSetId),
            )

            assertTrue(collect is CommandResult.Success)
            assertTrue(repository.currentWorkspace().retiredBlobSets.isEmpty())
            val deletes = journal.entries.filter {
                it.objectType == "RETIRED_BLOB_SET" && it.mutationKind == BackupMutationKind.DELETE
            }
            assertEquals(blobSetId.value, deletes.single().objectId)

            val entriesAfterFirstCollection = journal.entries.size
            val again = repository.execute(
                DomainCommand.MarkRetiredBlobSetCollected(blobSetId),
            )

            assertTrue(again is CommandResult.Success)
            assertEquals(entriesAfterFirstCollection, journal.entries.size)
        }
    }

    private fun attachment(
        task: Task,
        blobSetId: BlobSetId?,
        chunkCount: Int,
    ): Attachment = Attachment(
        id = AttachmentId.new(),
        taskId = task.id,
        displayName = "site-plan.pdf",
        mimeType = "application/pdf",
        // Chunk count must equal ceil(byteCount / 4 MiB); one byte into the
        // requested chunk keeps this consistent for any chunkCount >= 1.
        byteCount = (chunkCount - 1) * ATTACHMENT_CHUNK_BYTES + 1,
        contentHash = "ab".repeat(32),
        blobSetId = blobSetId,
        chunkCount = chunkCount,
        deletedAt = null,
        revision = task.revision,
    )

    private companion object {
        const val ATTACHMENT_CHUNK_BYTES = 4L * 1024 * 1024
    }
}
