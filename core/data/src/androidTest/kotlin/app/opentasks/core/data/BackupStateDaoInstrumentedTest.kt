package app.opentasks.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.crypto.Argon2Metadata
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.backup.BackupStateEntity
import app.opentasks.core.data.backup.BackupStateMutation
import app.opentasks.core.data.backup.RoomBackupStateStore
import app.opentasks.core.data.backup.RoomRecoveryEnvelopeStore
import app.opentasks.core.data.backup.VerifiedPortableBackup
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.model.VaultId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun mutationReadsLatestRowAndChangesOnlyItsOwnedFields() = runBlocking {
        val initial = state(generation = 3, marker = "initial")
        database.backupStateDao().insert(initial)
        val store = RoomBackupStateStore(database)

        val updated = store.mutate(
            VaultId(VAULT_ID),
            BackupStateMutation { latest ->
                latest.copy(
                    currentBaseObjectId = "snapshot:3",
                    lastVerifiedSnapshotGeneration = 3,
                )
            },
        )

        assertEquals("snapshot:3", updated?.currentBaseObjectId)
        assertEquals(3L, updated?.lastVerifiedSnapshotGeneration)
        assertEquals(initial.packageState, updated?.packageState)
        assertEquals(initial.failureCategory, updated?.failureCategory)
    }

    @Test
    fun barrierControlledSameGenerationWritersMergeInEitherOrder() = runBlocking {
        assertMergedWithFirstWriter(
            vaultId = "vault-coordinator-first",
            first = coordinatorCheckpointMutation(),
            second = packageMutation(),
        )
        assertMergedWithFirstWriter(
            vaultId = "vault-package-first",
            first = packageMutation(),
            second = coordinatorCheckpointMutation(),
        )
    }

    @Test
    fun coordinatorCheckpointPreservesRestoredStatusAndFailureCategory() = runBlocking {
        val vaultId = "vault-restored"
        database.backupStateDao().insert(
            state(generation = 7, marker = "initial", vaultId = vaultId).copy(
                packageState = "RESTORED_PACKAGE_DETECTED",
                failureCategory = "PRESERVED",
            ),
        )
        val store = RoomBackupStateStore(database)

        store.mutate(VaultId(vaultId), coordinatorCheckpointMutation())

        val final = database.backupStateDao().require(vaultId)
        assertEquals("snapshot:7", final.currentBaseObjectId)
        assertEquals("RESTORED_PACKAGE_DETECTED", final.packageState)
        assertEquals("PRESERVED", final.failureCategory)
    }

    @Test
    fun recoveryEnvelopeCommitAndCoordinatorCheckpointMergeInEitherOrder() = runBlocking {
        assertRecoveryEnvelopeAndCoordinatorMerge(
            vaultId = "vault-envelope-coordinator-first",
            coordinatorFirst = true,
        )
        assertRecoveryEnvelopeAndCoordinatorMerge(
            vaultId = "vault-envelope-first",
            coordinatorFirst = false,
        )
    }

    private suspend fun assertRecoveryEnvelopeAndCoordinatorMerge(
        vaultId: String,
        coordinatorFirst: Boolean,
    ) = coroutineScope {
        database.backupStateDao().insert(
            state(generation = 7, marker = "initial", vaultId = vaultId).copy(
                portablePackageGeneration = null,
                portablePackageBytes = null,
                portablePackageProducedAtEpochMillis = null,
                packageState = "PREPARING",
                failureCategory = null,
                recoveryEnvelopeReady = false,
            ),
        )
        val stateStore = RoomBackupStateStore(database)
        val envelopeStore = RoomRecoveryEnvelopeStore(database)
        val envelope = envelope()
        val published = VerifiedPortableBackup(
            vaultId = vaultId,
            generation = 7,
            producedAtEpochMillis = 1_234,
            recoveryEnvelopeSha256 = "00".repeat(32),
            totalPackageLength = 4_096,
        )
        try {
            if (coordinatorFirst) {
                val coordinatorEntered = CountDownLatch(1)
                val releaseCoordinator = CountDownLatch(1)
                val envelopeAttempted = CompletableDeferred<Unit>()
                val coordinatorWriter = async(Dispatchers.Default) {
                    stateStore.mutate(
                        VaultId(vaultId),
                        BackupStateMutation { latest ->
                            coordinatorEntered.countDown()
                            check(releaseCoordinator.await(2, TimeUnit.SECONDS))
                            coordinatorCheckpointMutation().apply(latest)
                        },
                    )
                }
                check(coordinatorEntered.await(2, TimeUnit.SECONDS))
                val envelopeWriter = async(Dispatchers.Default) {
                    envelopeAttempted.complete(Unit)
                    envelopeStore.commitInitial(
                        vaultId = VaultId(vaultId),
                        envelope = envelope,
                        published = published,
                    )
                }
                envelopeAttempted.await()
                releaseCoordinator.countDown()
                checkNotNull(coordinatorWriter.await())
                checkNotNull(envelopeWriter.await())
            } else {
                checkNotNull(
                    envelopeStore.commitInitial(
                        vaultId = VaultId(vaultId),
                        envelope = envelope,
                        published = published,
                    ),
                )
                checkNotNull(
                    stateStore.mutate(
                        VaultId(vaultId),
                        coordinatorCheckpointMutation(),
                    ),
                )
            }

            val final = database.backupStateDao().require(vaultId)
            assertEquals("snapshot:7", final.currentBaseObjectId)
            assertEquals(7L, final.lastVerifiedSnapshotGeneration)
            assertEquals(7L, final.portablePackageGeneration)
            assertEquals(4_096L, final.portablePackageBytes)
            assertEquals(1_234L, final.portablePackageProducedAtEpochMillis)
            assertEquals("READY", final.packageState)
            assertEquals(null, final.failureCategory)
            assertEquals(true, final.recoveryEnvelopeReady)

            val persistedEnvelope = checkNotNull(
                database.vaultRecoveryEnvelopeDao().get(vaultId),
            )
            try {
                assertEquals(1, persistedEnvelope.formatVersion)
                assertEquals(65_536, persistedEnvelope.memoryKiB)
            } finally {
                persistedEnvelope.salt.fill(0)
                persistedEnvelope.nonce.fill(0)
                persistedEnvelope.wrappedKeyset.fill(0)
            }
        } finally {
            envelope.kdf.salt.fill(0)
            envelope.nonce.fill(0)
            envelope.wrappedKeyset.fill(0)
        }
    }

    private suspend fun assertMergedWithFirstWriter(
        vaultId: String,
        first: BackupStateMutation,
        second: BackupStateMutation,
    ) = coroutineScope {
        database.backupStateDao().insert(
            state(generation = 7, marker = "initial", vaultId = vaultId),
        )
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAtTransactionBoundary = CompletableDeferred<Unit>()
        val firstStore = RoomBackupStateStore(database)
        val secondStore = RoomBackupStateStore(
            database = database,
            beforeMutationTransaction = {
                secondAtTransactionBoundary.complete(Unit)
            },
        )
        val firstWriter = async(Dispatchers.Default) {
            firstStore.mutate(
                VaultId(vaultId),
                BackupStateMutation { latest ->
                    firstEntered.countDown()
                    check(releaseFirst.await(2, TimeUnit.SECONDS))
                    first.apply(latest)
                },
            )
        }
        check(firstEntered.await(2, TimeUnit.SECONDS))
        val secondWriter = async(Dispatchers.Default) {
            secondStore.mutate(VaultId(vaultId), second)
        }
        secondAtTransactionBoundary.await()
        releaseFirst.countDown()
        firstWriter.await()
        secondWriter.await()

        val final = database.backupStateDao().require(vaultId)
        assertEquals("snapshot:7", final.currentBaseObjectId)
        assertEquals(6L, final.portablePackageGeneration)
        assertEquals(4_096L, final.portablePackageBytes)
        assertEquals(1_234L, final.portablePackageProducedAtEpochMillis)
        assertEquals("UPDATE_PENDING", final.packageState)
        assertEquals("FILE_IO", final.failureCategory)
        assertEquals(true, final.recoveryEnvelopeReady)
    }

    private fun coordinatorCheckpointMutation() = BackupStateMutation { latest ->
        latest.copy(
            lastVerifiedSnapshotGeneration = 7,
            currentBaseObjectId = "snapshot:7",
            previousBaseObjectId = "snapshot:6",
            latestVerifiedSegmentGeneration = 7,
            legacyOutboxCoveredAtGeneration = 7,
            snapshotCreatedAtEpochMillis = 2_000,
        )
    }

    private fun packageMutation() = BackupStateMutation { latest ->
        latest.copy(
            portablePackageGeneration = 6,
            portablePackageBytes = 4_096,
            portablePackageProducedAtEpochMillis = 1_234,
            packageState = "UPDATE_PENDING",
            failureCategory = "FILE_IO",
            recoveryEnvelopeReady = true,
        )
    }

    private fun envelope() = VaultKeyEnvelope(
        formatVersion = 1,
        kdf = Argon2Metadata(
            memoryKiB = 65_536,
            iterations = 3,
            parallelism = 1,
            salt = ByteArray(16) { 1 },
        ),
        nonce = ByteArray(12) { 2 },
        wrappedKeyset = ByteArray(32) { 3 },
    )

    private fun state(
        generation: Long,
        marker: String,
        vaultId: String = VAULT_ID,
    ): BackupStateEntity = BackupStateEntity(
        vaultId = vaultId,
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
