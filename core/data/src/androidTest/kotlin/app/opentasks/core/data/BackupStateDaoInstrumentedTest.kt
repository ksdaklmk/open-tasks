package app.opentasks.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.data.backup.BackupStateEntity
import app.opentasks.core.data.backup.RoomBackupStateStore
import app.opentasks.core.data.db.VaultDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupStateDaoInstrumentedTest {
    private lateinit var database: VaultDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            VaultDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun compareAndUpdateWithMatchingGenerationReplacesFullState() = runBlocking {
        val initial = state(generation = 3, marker = "initial")
        val updated = state(generation = 4, marker = "updated")
        database.backupStateDao().insert(initial)
        val store = RoomBackupStateStore(database.backupStateDao())

        val updatedRows = store.compareAndUpdate(
            entity = updated,
            expectedCurrentGeneration = 3,
        )

        assertEquals(1, updatedRows)
        assertEquals(updated, database.backupStateDao().require(VAULT_ID))
    }

    @Test
    fun compareAndUpdateWithStaleGenerationPreservesNewerState() = runBlocking {
        val newer = state(generation = 7, marker = "newer")
        val stale = state(generation = 8, marker = "stale")
        database.backupStateDao().insert(newer)
        val store = RoomBackupStateStore(database.backupStateDao())

        val updatedRows = store.compareAndUpdate(
            entity = stale,
            expectedCurrentGeneration = 6,
        )

        assertEquals(0, updatedRows)
        assertEquals(newer, database.backupStateDao().require(VAULT_ID))
    }

    private fun state(
        generation: Long,
        marker: String,
    ): BackupStateEntity = BackupStateEntity(
        vaultId = VAULT_ID,
        currentGeneration = generation,
        lastVerifiedSnapshotGeneration = generation - 1,
        currentBaseObjectId = "current-$marker",
        previousBaseObjectId = "previous-$marker",
        latestVerifiedSegmentGeneration = generation - 2,
        portablePackageGeneration = generation - 1,
        portablePackageBytes = generation * 1_000,
        portablePackageProducedAtEpochMillis = generation * 2_000,
        packageState = "STATE_$marker",
        failureCategory = "FAILURE_$marker",
        recoveryEnvelopeReady = marker != "initial",
        legacyOutboxCoveredAtGeneration = generation - 3,
        snapshotCreatedAtEpochMillis = generation * 3_000,
    )

    private companion object {
        const val VAULT_ID = "vault-cas"
    }
}
