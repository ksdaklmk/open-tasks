package app.opentasks.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.data.backup.BackupMutationCodec
import app.opentasks.core.data.backup.BackupRecordFamily
import app.opentasks.core.data.backup.BackupRecordV1
import app.opentasks.core.data.db.SavedViewEntity
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SavedView
import app.opentasks.core.model.SavedViewId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TagId
import app.opentasks.core.model.TaskSortKey
import app.opentasks.core.model.WorkspaceSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Room persistence parity for the saved-view commands over the previously
 * dormant `saved_views` table: create, rename, query update, delete, and
 * restore must behave identically to `InMemoryVaultRepository`, every
 * accepted mutation must append a `SAVED_VIEW` backup-journal entry in the
 * same transaction, and a malformed dormant payload must be preserved
 * untouched without blocking repository readiness.
 */
@RunWith(AndroidJUnit4::class)
class RoomSavedViewCommandInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var databaseKey: ByteArray
    private var database: VaultDatabase? = null
    private var repository: RoomVaultRepository? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "vault-saved-view-test-${UUID.randomUUID()}.db"
        databaseKey = ByteArray(32) { index -> (index + 1).toByte() }
        database = VaultDatabase.create(context, databaseName, databaseKey)
        repository = RoomVaultRepository(
            database = database!!,
            deviceId = DeviceId("saved-view-instrumented-test-device"),
        )
    }

    @After
    fun tearDown() {
        repository?.close()
        database?.close()
        context.deleteDatabase(databaseName)
        databaseKey.fill(0)
    }

    @Test
    fun createRenameUpdateDeleteRoundTripWithExactUndoAndReadBack() = runBlocking {
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            val id = SavedViewId.new()
            val query = SearchQuery(
                text = "deep work",
                projectIds = setOf(ProjectId("project-roadmap")),
                tagIds = setOf(TagId("tag-focus")),
                includeCompleted = false,
            )

            val created = repository!!.execute(
                DomainCommand.CreateSavedView(id, "Focus", query),
            ) as CommandResult.Success
            val stored = awaitSavedView { it.id == id }
            assertEquals("Focus", stored.name)
            assertEquals(query, stored.query)
            assertEquals(id, (created.undo as DomainCommand.DeleteSavedView).savedViewId)

            val renamed = repository!!.execute(
                DomainCommand.RenameSavedView(id, "Deep focus"),
            ) as CommandResult.Success
            awaitSavedView { it.id == id && it.name == "Deep focus" }
            repository!!.execute(renamed.undo as DomainCommand)
            awaitSavedView { it.id == id && it.name == "Focus" }

            val updated = repository!!.execute(
                DomainCommand.UpdateSavedViewQuery(id, SearchQuery("shallow work")),
            ) as CommandResult.Success
            awaitSavedView { it.id == id && it.query.text == "shallow work" }
            repository!!.execute(updated.undo as DomainCommand)
            assertEquals(query, awaitSavedView { it.id == id && it.query == query }.query)

            val deleted = repository!!.execute(
                DomainCommand.DeleteSavedView(id),
            ) as CommandResult.Success
            awaitWorkspace { snapshot -> snapshot.savedViews.none { it.id == id } }
            assertNull(database!!.workspaceDao().getSavedView(id.value))
            val restoredView = (deleted.undo as DomainCommand.RestoreSavedView).savedView
            assertEquals(stored, restoredView)

            repository!!.execute(deleted.undo as DomainCommand)
            assertEquals(stored, awaitSavedView { it.id == id })
            assertNotNull(database!!.workspaceDao().getSavedView(id.value))
        }
    }

    @Test
    fun v2QuerySurvivesUpdateUndoAndEncryptedRestart() = runBlocking {
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            val query = SearchQuery(
                text = "", dueBuckets = setOf(DueBucket.TODAY),
                priorities = setOf(Priority.URGENT),
                statuses = setOf(SemanticStatus.STARTED), sort = TaskSortKey.UPDATED,
            )
            val id = SavedViewId("saved-view-v2")
            repository!!.execute(DomainCommand.CreateSavedView(id, "V2", query))
            val update = repository!!.execute(
                DomainCommand.UpdateSavedViewQuery(id, SearchQuery("replacement")),
            ) as CommandResult.Success
            repository!!.execute(checkNotNull(update.undo))
            repository!!.close()
            database!!.close()
            repository = null
            database = VaultDatabase.create(context, databaseName, databaseKey)
            repository = RoomVaultRepository(
                database = database!!,
                deviceId = DeviceId("saved-view-instrumented-test-device"),
            )
            assertEquals(query, awaitSavedView { it.id == id }.query)
        }
    }

    @Test
    fun twentyFirstSavedViewIsRejectedWithoutMutation() = runBlocking {
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repeat(20) {
                val result = repository!!.execute(
                    DomainCommand.CreateSavedView(
                        SavedViewId.new(), "View $it", SearchQuery("q$it"),
                    ),
                )
                assertTrue(result is CommandResult.Success)
            }
            val stateBefore = database!!.backupStateDao().require(VAULT_ID)

            val result = repository!!.execute(
                DomainCommand.CreateSavedView(
                    SavedViewId.new(), "One too many", SearchQuery("q"),
                ),
            )

            assertEquals(
                RejectionReason.SAVED_VIEW_LIMIT_REACHED,
                (result as CommandResult.Rejected).reason,
            )
            assertEquals(20, database!!.workspaceDao().savedViewCount())
            assertEquals(
                stateBefore.currentGeneration,
                database!!.backupStateDao().require(VAULT_ID).currentGeneration,
            )
        }
    }

    @Test
    fun renameAndQueryUpdateEachJournalAContentChangedSavedViewUpsert() = runBlocking {
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            val id = SavedViewId.new()
            repository!!.execute(
                DomainCommand.CreateSavedView(id, "Focus", SearchQuery("deep work")),
            )
            val createdRecord = latestSavedViewUpsert(id)

            repository!!.execute(DomainCommand.RenameSavedView(id, "Deep focus"))
            val renamedRecord = latestSavedViewUpsert(id)
            assertEquals("Deep focus", renamedRecord.stringValue("name"))
            assertTrue(renamedRecord != createdRecord)

            repository!!.execute(
                DomainCommand.UpdateSavedViewQuery(id, SearchQuery("shallow work")),
            )
            val updatedRecord = latestSavedViewUpsert(id)
            assertTrue(updatedRecord != renamedRecord)
            assertTrue(
                updatedRecord.stringValue("encryptedQuery") !=
                    renamedRecord.stringValue("encryptedQuery"),
            )
        }
    }

    @Test
    fun malformedDormantRowIsOmittedFromSnapshotsAndPreservedRaw() = runBlocking {
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            val validId = SavedViewId.new()
            repository!!.execute(
                DomainCommand.CreateSavedView(validId, "Valid", SearchQuery("q")),
            )
            awaitSavedView { it.id == validId }

            val malformedBytes = byteArrayOf(0x7B, -1, -2, 0x7D)
            val malformed = SavedViewEntity(
                id = "saved-view-malformed",
                workspaceId = repository!!.currentWorkspace().tasks
                    .first().workspaceId.value,
                name = "Legacy",
                encryptedQuery = malformedBytes,
            )
            database!!.workspaceDao().upsertSavedView(malformed)

            awaitWorkspace { snapshot ->
                snapshot.savedViews.map(SavedView::id) == listOf(validId)
            }
            // Still ready and mutable after observing the bad row.
            val roundTrip = repository!!.execute(
                DomainCommand.RenameSavedView(validId, "Still valid"),
            )
            assertTrue(roundTrip is CommandResult.Success)

            val raw = database!!.workspaceDao().getSavedView("saved-view-malformed")
            assertNotNull(raw)
            assertEquals("Legacy", raw!!.name)
            assertArrayEquals(malformedBytes, raw.encryptedQuery)
        }
    }

    private suspend fun latestSavedViewUpsert(id: SavedViewId): BackupRecordV1 {
        val state = database!!.backupStateDao().require(VAULT_ID)
        val rows = database!!.backupJournalDao().between(
            VAULT_ID,
            state.currentGeneration,
            state.currentGeneration,
        )
        val record = rows
            .sortedBy { it.sequence }
            .mapNotNull { BackupMutationCodec.decode(it.payload).record }
            .last {
                it.family == BackupRecordFamily.SAVED_VIEW &&
                    it.identity == listOf(id.value)
            }
        return record
    }

    private fun BackupRecordV1.stringValue(name: String): String? =
        fields.firstOrNull { it.name == name }?.value

    private suspend fun awaitSavedView(predicate: (SavedView) -> Boolean): SavedView =
        repository!!.observeWorkspace()
            .first { snapshot -> snapshot.savedViews.any(predicate) }
            .savedViews.first(predicate)

    private suspend fun awaitWorkspace(predicate: (WorkspaceSnapshot) -> Boolean) {
        repository!!.observeWorkspace().first(predicate)
    }

    private companion object {
        const val VAULT_ID = "vault-primary"
    }
}
