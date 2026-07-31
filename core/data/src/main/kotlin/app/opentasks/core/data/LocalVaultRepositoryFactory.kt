package app.opentasks.core.data

import android.content.Context
import app.opentasks.core.crypto.AndroidVaultContentKeyStore
import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultCrypto
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
 * Constructed local services for one vault slot.
 *
 * The runtime owns the SQLCipher handle and the slot-scoped content key store,
 * so closing it releases every file the slot holds before that slot can be
 * replaced. Task 8 owns backup lifecycle; this bundle starts no backup work.
 */
class LocalVaultRuntime internal constructor(
    val slot: VaultSlot,
    val vaultId: VaultId,
    val repository: VaultRepository,
    val backupJournalReader: BackupJournalReader,
    val backupCaptureSource: RoomBackupCaptureSource,
    val backupStateStore: RoomBackupStateStore,
    val recoveryEnvelopeStore: RoomRecoveryEnvelopeStore,
    val contentKeyStore: VaultContentKeyStore,
    private val database: VaultDatabase,
) : AutoCloseable {
    /** Joins repository observation before the SQLCipher handle is released. */
    override fun close() {
        try {
            (repository as? AutoCloseable)?.close()
        } finally {
            database.close()
        }
    }
}

object LocalVaultRepositoryFactory {
    fun openRuntime(
        context: Context,
        slot: VaultSlot,
        crypto: VaultCrypto,
        keyManager: AndroidVaultKeyManager = AndroidVaultKeyManager(context),
    ): LocalVaultRuntime = buildRuntime(context, slot, crypto, keyManager) {
        keyManager.openExistingDatabaseKey(slot)
    }

    fun createRuntime(
        context: Context,
        slot: VaultSlot,
        crypto: VaultCrypto,
        keyManager: AndroidVaultKeyManager = AndroidVaultKeyManager(context),
    ): LocalVaultRuntime = buildRuntime(context, slot, crypto, keyManager) {
        keyManager.createDatabaseKey(slot)
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

    internal fun storageNamespace(slot: VaultSlot): String? =
        if (slot == VaultSlot.LEGACY) null else slot.digest

    private fun buildRuntime(
        context: Context,
        slot: VaultSlot,
        crypto: VaultCrypto,
        keyManager: AndroidVaultKeyManager,
        databaseKey: () -> ByteArray,
    ): LocalVaultRuntime {
        val applicationContext = context.applicationContext
        val key = databaseKey()
        val database = try {
            VaultDatabase.create(
                applicationContext,
                LocalVaultRuntimeFactory.databaseName(slot),
                key,
            )
        } finally {
            key.fill(0)
        }
        return try {
            val vaultId = readVaultId(database) ?: DEFAULT_VAULT_ID
            val captureSource = RoomBackupCaptureSource(database, vaultId)
            val stateStore = RoomBackupStateStore(database)
            val journalStore = RoomBackupJournalStore(database.backupJournalDao())
            LocalVaultRuntime(
                slot = slot,
                vaultId = vaultId,
                repository = RoomVaultRepository(
                    database = database,
                    deviceId = keyManager.getOrCreateDeviceId(),
                ),
                backupJournalReader = RoomBackupJournalReader(
                    stateStore = stateStore,
                    journalStore = journalStore,
                ),
                backupCaptureSource = captureSource,
                backupStateStore = stateStore,
                recoveryEnvelopeStore = RoomRecoveryEnvelopeStore(database),
                contentKeyStore = AndroidVaultContentKeyStore(
                    context = applicationContext,
                    crypto = crypto,
                    storageNamespace = storageNamespace(slot),
                ),
                database = database,
            )
        } catch (failure: Throwable) {
            database.close()
            throw failure
        }
    }

    /**
     * Reads the stored vault identity, which also proves the slot key opens the
     * database before any service is constructed on top of it.
     */
    private fun readVaultId(database: VaultDatabase): VaultId? =
        database.openHelper.readableDatabase
            .query("SELECT id FROM vaults ORDER BY id LIMIT 1")
            .use { cursor ->
                if (cursor.moveToFirst()) VaultId(cursor.getString(0)) else null
            }

    private val DEFAULT_VAULT_ID = VaultId("vault-primary")
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
