package app.opentasks.core.data

import android.content.Context
import app.opentasks.core.crypto.AndroidVaultContentKeyStore
import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.backup.BackupJournalDao
import app.opentasks.core.data.backup.DefaultBackupCoordinator
import app.opentasks.core.data.backup.DefaultRecoveryCoordinator
import app.opentasks.core.data.backup.DefaultRemoteBackupConfigurator
import app.opentasks.core.data.backup.DefaultStagedVaultVerifier
import app.opentasks.core.data.backup.LocalBackupObjectStore
import app.opentasks.core.data.backup.OwnershipClaimCodec
import app.opentasks.core.data.backup.PortableBackupCodec
import app.opentasks.core.data.backup.PublicationCodec
import app.opentasks.core.data.backup.RecoveryImportRequest
import app.opentasks.core.data.backup.RecoveryStagingFactory
import app.opentasks.core.data.backup.RecoveryStagingSession
import app.opentasks.core.data.backup.RemoteBackupStateStore
import app.opentasks.core.data.backup.RemoteObjectCodec
import app.opentasks.core.data.backup.RoomBackupCaptureSource
import app.opentasks.core.data.backup.RoomBackupJournalStore
import app.opentasks.core.data.backup.RoomBackupRecordImporter
import app.opentasks.core.data.backup.RoomBackupStateStore
import app.opentasks.core.data.backup.RoomRecoveryEnvelopeStore
import app.opentasks.core.data.backup.RoomRemoteBackupStore
import app.opentasks.core.data.backup.expectedCapture
import app.opentasks.core.data.db.AttachmentTransferDao
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.domain.BackupCoordinator
import app.opentasks.core.domain.BackupJournalEntry
import app.opentasks.core.domain.BackupJournalReader
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.domain.RecoveryCoordinator
import app.opentasks.core.domain.RemoteBackupConfigurator
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.VaultId
import java.io.File

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
    val remoteBackupStore: RoomRemoteBackupStore,
    val contentKeyStore: VaultContentKeyStore,
    /** Durable attachment intake state; attachment work resumes from these rows. */
    val attachmentTransferDao: AttachmentTransferDao,
    /**
     * The ordered mutation journal. Attachment collection needs the generation
     * a retirement was recorded in to know which retained bases contain it.
     */
    val backupJournalDao: BackupJournalDao,
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

    /**
     * Wires initial create-only remote setup for one open runtime.
     *
     * The provider object store is supplied per call rather than held here,
     * because authorization is established outside this factory and no remote
     * work may start from opening a vault slot.
     */
    fun createRemoteBackupConfigurator(
        runtime: LocalVaultRuntime,
        backupCoordinator: BackupCoordinator,
        localObjectStore: LocalBackupObjectStore,
        authenticatedCodec: AuthenticatedCloudObjectCodec,
        remoteStagingRoot: File,
    ): RemoteBackupConfigurator = DefaultRemoteBackupConfigurator(
        vaultId = runtime.vaultId,
        backupCoordinator = backupCoordinator,
        backupStateStore = runtime.backupStateStore,
        recoveryEnvelopeStore = runtime.recoveryEnvelopeStore,
        contentKeyStore = runtime.contentKeyStore,
        remoteStateStore = runtime.remoteBackupStore,
        remoteObjectCodec = RemoteObjectCodec(
            authenticatedCodec = authenticatedCodec,
            localObjectStore = localObjectStore,
            stagingRoot = remoteStagingRoot,
        ),
        ownershipCodec = OwnershipClaimCodec(authenticatedCodec),
        publicationCodec = PublicationCodec(authenticatedCodec),
    )

    /**
     * Wires the only path from backup data back into a live vault.
     *
     * The provider object store is supplied per call rather than held here,
     * because authorization is established outside this factory and no remote
     * work may start from opening a vault slot. [expectedAccountBindingDigest]
     * is the account a lineage this installation already knows is bound to, so
     * a recovery of a known lineage refuses a different account before it
     * touches it; it is null when nothing is known.
     */
    fun createRecoveryCoordinator(
        context: Context,
        crypto: VaultCrypto,
        runtimeManager: VaultRuntimeManager,
        authenticatedCodec: AuthenticatedCloudObjectCodec,
        recoveryStagingRoot: File,
        expectedAccountBindingDigest: ByteArray? = null,
        keyManager: AndroidVaultKeyManager = AndroidVaultKeyManager(context),
    ): RecoveryCoordinator = DefaultRecoveryCoordinator(
        expectedVaultId = RoomVaultRepository.VAULT_ID,
        crypto = crypto,
        authenticatedCodec = authenticatedCodec,
        ownershipCodec = OwnershipClaimCodec(authenticatedCodec),
        publicationCodec = PublicationCodec(authenticatedCodec),
        portableCodec = PortableBackupCodec(authenticatedCodec),
        staging = LocalRecoveryStagingFactory(
            context = context,
            crypto = crypto,
            runtimeManager = runtimeManager,
            keyManager = keyManager,
        ),
        stagingRoot = recoveryStagingRoot,
        expectedAccountBindingDigest = expectedAccountBindingDigest,
    )

    /**
     * Creates a staged slot's database under a brand-new SQLCipher key.
     *
     * No repository, journal, or content-key service is built on top: only the
     * recovery importer writes here, and the slot stays inactive until the
     * staged-vault verifier has proved it.
     */
    internal fun createStagingDatabase(
        context: Context,
        slot: VaultSlot,
        keyManager: AndroidVaultKeyManager = AndroidVaultKeyManager(context),
    ): VaultDatabase = openStagingDatabase(context, slot) { keyManager.createDatabaseKey(slot) }

    /** Reopens a staged slot's database for verification, creating no key. */
    internal fun openStagingDatabase(
        context: Context,
        slot: VaultSlot,
        keyManager: AndroidVaultKeyManager = AndroidVaultKeyManager(context),
    ): VaultDatabase =
        openStagingDatabase(context, slot) { keyManager.openExistingDatabaseKey(slot) }

    private fun openStagingDatabase(
        context: Context,
        slot: VaultSlot,
        databaseKey: () -> ByteArray,
    ): VaultDatabase {
        val key = databaseKey()
        return try {
            VaultDatabase.create(
                context.applicationContext,
                LocalVaultRuntimeFactory.databaseName(slot),
                key,
            )
        } finally {
            key.fill(0)
        }
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
                remoteBackupStore = RoomRemoteBackupStore(database),
                contentKeyStore = AndroidVaultContentKeyStore(
                    context = applicationContext,
                    crypto = crypto,
                    storageNamespace = storageNamespace(slot),
                ),
                attachmentTransferDao = database.attachmentTransferDao(),
                backupJournalDao = database.backupJournalDao(),
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

/**
 * The staged slot a recovery reconstructs into, backed by this device.
 *
 * A staged slot is a complete vault database under its own SQLCipher key and
 * its own content-key namespace, so the takeover's durable identities live in
 * the vault that will keep them and a device holding no vault at all still has
 * somewhere crash-safe to record what it reserved.
 */
internal class LocalRecoveryStagingFactory(
    context: Context,
    private val crypto: VaultCrypto,
    private val runtimeManager: VaultRuntimeManager,
    private val keyManager: AndroidVaultKeyManager,
) : RecoveryStagingFactory {
    private val context = context.applicationContext
    private val registry = RecoveryRegistry(this.context)

    override suspend fun begin(operationId: String): RecoveryStagingSession =
        LocalRecoveryStagingSession(
            context = context,
            operationId = operationId,
            slot = runtimeManager.beginRecovery(operationId),
            crypto = crypto,
            keyManager = keyManager,
            database = null,
        )

    override suspend fun resume(operationId: String): RecoveryStagingSession? {
        val record = try {
            registry.readOrDiscard()
        } catch (_: Throwable) {
            null
        } ?: return null
        if (record.operationId != operationId) return null
        val database = runCatching {
            LocalVaultRepositoryFactory.openStagingDatabase(context, record.stagedSlot, keyManager)
        }.getOrNull() ?: return null
        return LocalRecoveryStagingSession(
            context = context,
            operationId = operationId,
            slot = record.stagedSlot,
            crypto = crypto,
            keyManager = keyManager,
            database = database,
        )
    }

    override suspend fun activate(
        session: RecoveryStagingSession,
        staged: VerifiedStagedVault,
    ): RemoteBackupStateStore {
        session.close()
        runtimeManager.activate(staged)
        return runtimeManager.requireActive().remoteBackupStore
    }

    override suspend fun abandon(session: RecoveryStagingSession) {
        session.close()
        runtimeManager.abandonRecovery(session.operationId)
    }
}

internal class LocalRecoveryStagingSession(
    context: Context,
    override val operationId: String,
    override val slot: VaultSlot,
    private val crypto: VaultCrypto,
    private val keyManager: AndroidVaultKeyManager,
    private var database: VaultDatabase?,
) : RecoveryStagingSession {
    private val context = context.applicationContext
    private val contentKeys = AndroidVaultContentKeyStore(
        context = this.context,
        crypto = crypto,
        storageNamespace = LocalVaultRepositoryFactory.storageNamespace(slot),
    )

    override val remoteStateStore: RemoteBackupStateStore
        get() = RoomRemoteBackupStore(
            checkNotNull(database) { "The staging slot is not open" },
        )

    override suspend fun reconstruct(
        request: RecoveryImportRequest,
        contentKey: VaultKey,
    ): VerifiedStagedVault {
        check(database == null) { "The staging slot is already open" }
        val vaultId = VaultId(request.snapshot.vaultId)
        val created = LocalVaultRepositoryFactory.createStagingDatabase(context, slot, keyManager)
        try {
            // The records and the recovery envelope are sealed under this key,
            // so the slot must be able to open it before it can be proved.
            contentKeys.replace(vaultId, contentKey)
            RoomBackupRecordImporter(created, created.recoveryImportDao())
                .importInto(created, request)
        } finally {
            // Verification opens this slot several times and builds a runtime
            // over it, so no handle of ours may still hold the file.
            created.close()
        }
        val verified = DefaultStagedVaultVerifier(context, crypto, keyManager).verify(
            slot = slot,
            expectedVaultId = vaultId,
            expectedGeneration = request.expectedGeneration,
            expectedCapture = request.expectedCapture(),
        )
        database = LocalVaultRepositoryFactory.openStagingDatabase(context, slot, keyManager)
        return verified
    }

    override fun openContentKey(vaultId: VaultId): VaultKey = contentKeys.openExisting(vaultId)

    override fun close() {
        database?.close()
        database = null
    }
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
