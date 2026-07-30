package app.opentasks.core.data

import android.content.Context
import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.data.backup.DefaultBackupCoordinator
import app.opentasks.core.data.backup.LocalBackupObjectStore
import app.opentasks.core.data.backup.RoomBackupCaptureSource
import app.opentasks.core.data.backup.RoomBackupJournalStore
import app.opentasks.core.data.backup.RoomBackupStateStore
import app.opentasks.core.data.backup.RoomRecoveryEnvelopeStore
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.domain.BackupCoordinator
import app.opentasks.core.domain.BackupJournalEntry
import app.opentasks.core.domain.BackupJournalReader
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.VaultId

/**
 * Constructed local services. Task 8 owns lifecycle, debounce, and requests;
 * this bundle intentionally does not start backup work.
 */
data class LocalVaultRuntime(
    val vaultId: VaultId,
    val repository: VaultRepository,
    val backupJournalReader: BackupJournalReader,
    val backupCaptureSource: RoomBackupCaptureSource,
    val backupStateStore: RoomBackupStateStore,
    val recoveryEnvelopeStore: RoomRecoveryEnvelopeStore,
)

object LocalVaultRepositoryFactory {
    fun createRuntime(context: Context): LocalVaultRuntime {
        val applicationContext = context.applicationContext
        val keyManager = AndroidVaultKeyManager(applicationContext)
        val databaseKey = keyManager.getOrCreateDatabaseKey()
        val database = try {
            VaultDatabase.create(applicationContext, DATABASE_NAME, databaseKey)
        } finally {
            databaseKey.fill(0)
        }
        val repository = RoomVaultRepository(
            database = database,
            deviceId = keyManager.getOrCreateDeviceId(),
        )
        val vaultId = VaultId("vault-primary")
        val captureSource = RoomBackupCaptureSource(database, vaultId)
        val stateStore = RoomBackupStateStore(database)
        val journalStore = RoomBackupJournalStore(database.backupJournalDao())
        val envelopeStore = RoomRecoveryEnvelopeStore(database)
        return LocalVaultRuntime(
            vaultId = vaultId,
            repository = repository,
            backupJournalReader = RoomBackupJournalReader(
                stateStore = stateStore,
                journalStore = journalStore,
            ),
            backupCaptureSource = captureSource,
            backupStateStore = stateStore,
            recoveryEnvelopeStore = envelopeStore,
        )
    }

    fun createBackupCoordinator(
        runtime: LocalVaultRuntime,
        objectStore: LocalBackupObjectStore,
        authenticatedCodec: AuthenticatedCloudObjectCodec,
        contentKeyStore: VaultContentKeyStore,
    ): BackupCoordinator {
        val journalStore = (runtime.backupJournalReader as? RoomBackupJournalReader)
            ?.journalStore
            ?: error("Local runtime journal reader is not backed by Room")
        return DefaultBackupCoordinator(
            vaultId = runtime.vaultId,
            captureSource = runtime.backupCaptureSource,
            stateStore = runtime.backupStateStore,
            journalStore = journalStore,
            objectStore = objectStore,
            authenticatedCodec = authenticatedCodec,
            contentKeyStore = contentKeyStore,
        )
    }

    private const val DATABASE_NAME = "open_tasks.db"
}

private class RoomBackupJournalReader(
    private val stateStore: RoomBackupStateStore,
    val journalStore: RoomBackupJournalStore,
) : BackupJournalReader {
    override suspend fun currentGeneration(vaultId: VaultId) =
        BackupGeneration(
            checkNotNull(stateStore.get(vaultId)).currentGeneration,
        )

    override suspend fun entriesAfter(
        vaultId: VaultId,
        generation: BackupGeneration,
        limit: Int,
    ) = journalStore.after(vaultId, generation.value, limit).map { entity ->
        BackupJournalEntry(
            operationId = entity.operationId,
            vaultId = VaultId(entity.vaultId),
            generation = BackupGeneration(entity.generation),
            sequence = entity.sequence,
            payloadFormatVersion = entity.payloadFormatVersion,
            mutationKind = BackupMutationKind.valueOf(entity.mutationKind),
            objectId = entity.objectId,
            objectType = entity.objectType,
            payload = entity.payload.copyOf(),
            revision = Revision(
                deviceId = DeviceId(entity.sourceDeviceId),
                wallTimeMillis = entity.revisionWallMillis,
                logicalCounter = entity.revisionLogical,
            ),
        )
    }
}
