package app.opentasks.core.data

import app.opentasks.core.data.backup.BackupRecordFamily
import app.opentasks.core.data.backup.InMemoryBackupJournal
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SavedViewId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TaskSortKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemorySavedViewCommandTest {

    private val journal = InMemoryBackupJournal()
    private val repository = InMemoryVaultRepository(backupJournal = journal)

    @Test
    fun createRenameAndDeleteRoundTripWithExactUndo() = runBlocking {
        withTimeout(5_000) {
            val id = SavedViewId.new()
            val query = SearchQuery(text = "deep work")
            val created = repository.execute(
                DomainCommand.CreateSavedView(id, "Focus", query),
            ) as CommandResult.Success
            assertEquals(
                "Focus",
                repository.currentWorkspace().savedViews.single().name,
            )

            val renamed = repository.execute(
                DomainCommand.RenameSavedView(id, "Deep focus"),
            ) as CommandResult.Success
            repository.execute(renamed.undo!!)
            assertEquals(
                "Focus",
                repository.currentWorkspace().savedViews.single().name,
            )

            repository.execute(created.undo!!)
            assertTrue(repository.currentWorkspace().savedViews.isEmpty())
        }
    }

    @Test
    fun renameJournalsAnUpsertForTheSavedViewFamily() = runBlocking {
        withTimeout(5_000) {
            val id = SavedViewId.new()
            repository.execute(
                DomainCommand.CreateSavedView(id, "Focus", SearchQuery("q")),
            )
            val before = journal.entries.size
            repository.execute(DomainCommand.RenameSavedView(id, "Later"))
            val appended = journal.entries.drop(before)
            assertTrue(
                appended.any {
                    it.objectType == BackupRecordFamily.SAVED_VIEW.name
                },
            )
        }
    }

    @Test
    fun twentyFirstSavedViewIsRejectedFailClosed() = runBlocking {
        withTimeout(5_000) {
            repeat(20) {
                repository.execute(
                    DomainCommand.CreateSavedView(
                        SavedViewId.new(), "View $it", SearchQuery("q$it"),
                    ),
                )
            }
            val result = repository.execute(
                DomainCommand.CreateSavedView(
                    SavedViewId.new(), "One too many", SearchQuery("q"),
                ),
            )
            assertTrue(result is CommandResult.Rejected)
            assertEquals(20, repository.currentWorkspace().savedViews.size)
        }
    }

    @Test
    fun updateQueryRoundTripsWithExactUndo() = runBlocking {
        withTimeout(5_000) {
            val id = SavedViewId.new()
            val original = SearchQuery(text = "deep work", includeCompleted = false)
            repository.execute(DomainCommand.CreateSavedView(id, "Focus", original))

            val updated = repository.execute(
                DomainCommand.UpdateSavedViewQuery(id, SearchQuery(text = "shallow work")),
            ) as CommandResult.Success
            assertEquals(
                "shallow work",
                repository.currentWorkspace().savedViews.single().query.text,
            )

            repository.execute(updated.undo!!)
            assertEquals(original, repository.currentWorkspace().savedViews.single().query)
        }
    }

    @Test
    fun v2QueryUpdateUndoRestoresEveryField() = runBlocking {
        withTimeout(5_000) {
            val query = SearchQuery(
                text = "", dueBuckets = setOf(DueBucket.TODAY),
                priorities = setOf(Priority.URGENT),
                statuses = setOf(SemanticStatus.STARTED), sort = TaskSortKey.UPDATED,
            )
            val id = SavedViewId("saved-view-v2")
            repository.execute(DomainCommand.CreateSavedView(id, "V2", query))
            val update = repository.execute(
                DomainCommand.UpdateSavedViewQuery(id, SearchQuery("replacement")),
            ) as CommandResult.Success
            repository.execute(checkNotNull(update.undo))
            assertEquals(query, repository.currentWorkspace().savedViews.single { it.id == id }.query)
        }
    }

    @Test
    fun deleteUndoRestoresTheExactSavedView() = runBlocking {
        withTimeout(5_000) {
            val id = SavedViewId.new()
            repository.execute(
                DomainCommand.CreateSavedView(
                    id,
                    "Focus",
                    SearchQuery("deep work", includeTrash = true),
                ),
            )
            val view = repository.currentWorkspace().savedViews.single()

            val deleted = repository.execute(
                DomainCommand.DeleteSavedView(id),
            ) as CommandResult.Success
            assertTrue(repository.currentWorkspace().savedViews.isEmpty())

            repository.execute(deleted.undo!!)
            assertEquals(view, repository.currentWorkspace().savedViews.single())
        }
    }

    @Test
    fun oversizedEncodedFilterIdsRejectPayloadTooLargeWithoutMutation() = runBlocking {
        withTimeout(5_000) {
            val before = repository.currentWorkspace()
            val journalSizeBefore = journal.entries.size
            val hugeFilter = (0 until 30_000).mapTo(linkedSetOf()) {
                ProjectId("project-$it-" + "x".repeat(80))
            }
            val result = repository.execute(
                DomainCommand.CreateSavedView(
                    SavedViewId.new(),
                    "Everything",
                    SearchQuery("q", projectIds = hugeFilter),
                ),
            )
            assertTrue(result is CommandResult.Rejected)
            assertEquals(
                RejectionReason.SAVED_VIEW_PAYLOAD_TOO_LARGE,
                (result as CommandResult.Rejected).reason,
            )
            assertEquals(before, repository.currentWorkspace())
            assertEquals(journalSizeBefore, journal.entries.size)
        }
    }
}
