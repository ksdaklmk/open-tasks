package app.opentasks.core.data.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupObject
import app.opentasks.core.domain.RemoteBackupOperation
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.VaultId
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteBackupConfigDao {
    @Query("SELECT * FROM remote_backup_config WHERE lineageId = :lineageId LIMIT 1")
    suspend fun byLineageId(lineageId: String): RemoteBackupConfigEntity?

    @Query(
        """
        SELECT * FROM remote_backup_config
        WHERE vaultId = :vaultId AND lifecycle = 'ACTIVE'
        LIMIT 1
        """,
    )
    suspend fun activeForVault(vaultId: String): RemoteBackupConfigEntity?

    @Query("SELECT * FROM remote_backup_config WHERE vaultId = :vaultId")
    suspend fun forVault(vaultId: String): List<RemoteBackupConfigEntity>

    @Query(
        """
        SELECT * FROM remote_backup_config
        WHERE vaultId = :vaultId AND lifecycle = 'ACTIVE'
        LIMIT 1
        """,
    )
    fun observeActiveForVault(vaultId: String): Flow<RemoteBackupConfigEntity?>

    @Query(
        """
        SELECT COUNT(*) FROM remote_backup_config
        WHERE vaultId = :vaultId AND lifecycle = 'ACTIVE' AND lineageId != :excludingLineageId
        """,
    )
    suspend fun activeCountForVaultExcluding(vaultId: String, excludingLineageId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RemoteBackupConfigEntity)

    /**
     * Re-points an interrupted setup's own `CONNECTING` row at the provider
     * slots a restart reserved. Every guard is in the statement itself, so a
     * row that has already become active, moved to another vault, or advanced
     * its state version updates nothing.
     */
    @Query(
        """
        UPDATE remote_backup_config SET
            rootClaimProviderFileId = :rootClaimProviderFileId,
            accountBindingDigest = :accountBindingDigest,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE lineageId = :lineageId AND vaultId = :vaultId
            AND lifecycle = 'CONNECTING' AND stateVersion = :expectedStateVersion
        """,
    )
    suspend fun adoptConnecting(
        lineageId: String,
        vaultId: String,
        expectedStateVersion: Long,
        rootClaimProviderFileId: String,
        accountBindingDigest: ByteArray,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE remote_backup_config SET
            rootClaimProviderFileId = :rootClaimProviderFileId,
            accountBindingDigest = :accountBindingDigest,
            lifecycle = :lifecycle,
            activeDeviceId = :activeDeviceId,
            writerEpoch = :writerEpoch,
            ownershipClaimProviderFileId = :ownershipClaimProviderFileId,
            ownershipClaimId = :ownershipClaimId,
            ownershipClaimSha256 = :ownershipClaimSha256,
            nextSuccessorProviderFileId = :nextSuccessorProviderFileId,
            currentPublicationProviderFileId = :currentPublicationProviderFileId,
            currentPublicationId = :currentPublicationId,
            currentPublicationSha256 = :currentPublicationSha256,
            previousPublicationProviderFileId = :previousPublicationProviderFileId,
            previousPublicationId = :previousPublicationId,
            previousPublicationSha256 = :previousPublicationSha256,
            previousPublicationGeneration = :previousPublicationGeneration,
            publicationSequence = :publicationSequence,
            lastVerifiedGeneration = :lastVerifiedGeneration,
            lastVerifiedAtEpochMillis = :lastVerifiedAtEpochMillis,
            recoveryCredentialGeneration = :recoveryCredentialGeneration,
            failureCategory = :failureCategory,
            stateVersion = :nextStateVersion,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE lineageId = :lineageId AND stateVersion = :expectedStateVersion
        """,
    )
    suspend fun compareAndUpdate(
        lineageId: String,
        expectedStateVersion: Long,
        rootClaimProviderFileId: String,
        accountBindingDigest: ByteArray,
        lifecycle: String,
        activeDeviceId: String?,
        writerEpoch: Long?,
        ownershipClaimProviderFileId: String?,
        ownershipClaimId: String?,
        ownershipClaimSha256: String?,
        nextSuccessorProviderFileId: String?,
        currentPublicationProviderFileId: String?,
        currentPublicationId: String?,
        currentPublicationSha256: String?,
        previousPublicationProviderFileId: String?,
        previousPublicationId: String?,
        previousPublicationSha256: String?,
        previousPublicationGeneration: Long?,
        publicationSequence: Long?,
        lastVerifiedGeneration: Long?,
        lastVerifiedAtEpochMillis: Long?,
        recoveryCredentialGeneration: Long,
        failureCategory: String?,
        nextStateVersion: Long,
        updatedAtEpochMillis: Long,
    ): Int
}

@Dao
interface RemoteBackupOperationDao {
    @Query("SELECT * FROM remote_backup_operation WHERE operationId = :operationId LIMIT 1")
    suspend fun get(operationId: String): RemoteBackupOperationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RemoteBackupOperationEntity)

    @Query(
        """
        UPDATE remote_backup_operation SET
            phase = :phase,
            targetEpoch = :targetEpoch,
            targetGeneration = :targetGeneration,
            candidateClaimProviderFileId = :candidateClaimProviderFileId,
            candidatePublicationProviderFileId = :candidatePublicationProviderFileId,
            stateBytes = :stateBytes,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE operationId = :operationId AND phase = :expectedPhase
        """,
    )
    suspend fun compareAndUpdate(
        operationId: String,
        expectedPhase: String,
        phase: String,
        targetEpoch: Long?,
        targetGeneration: Long?,
        candidateClaimProviderFileId: String?,
        candidatePublicationProviderFileId: String?,
        stateBytes: ByteArray,
        updatedAtEpochMillis: Long,
    ): Int
}

@Dao
interface RemoteBackupObjectDao {
    @Query(
        """
        SELECT * FROM remote_backup_object
        WHERE lineageId = :lineageId AND logicalObjectId = :logicalObjectId
        LIMIT 1
        """,
    )
    suspend fun get(lineageId: String, logicalObjectId: String): RemoteBackupObjectEntity?

    @Query(
        """
        SELECT * FROM remote_backup_object
        WHERE lineageId = :lineageId
        ORDER BY logicalObjectId
        """,
    )
    suspend fun forLineage(lineageId: String): List<RemoteBackupObjectEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RemoteBackupObjectEntity)

    @Update
    suspend fun update(entity: RemoteBackupObjectEntity): Int

    @Query(
        """
        DELETE FROM remote_backup_object
        WHERE lineageId = :lineageId AND logicalObjectId = :logicalObjectId
        """,
    )
    suspend fun delete(lineageId: String, logicalObjectId: String): Int
}

/**
 * Provider-independent persisted create-only remote-backup configuration and
 * operation state. Implemented by [RoomRemoteBackupStore] against the
 * additive v7 Room tables; kept in `core:data` beside its DAOs the same way
 * `BackupStateStore` and `RecoveryEnvelopeStore` are, so `core:domain` stays
 * free of persistence framing.
 */
interface RemoteBackupStateStore {
    suspend fun active(vaultId: VaultId): RemoteBackupConfiguration?

    suspend fun configurations(vaultId: VaultId): List<RemoteBackupConfiguration> =
        listOfNotNull(active(vaultId))

    suspend fun known(lineageId: CloudLineageId): RemoteBackupConfiguration?

    fun observeActive(vaultId: VaultId): Flow<RemoteBackupConfiguration?>

    suspend fun insertConnecting(configuration: RemoteBackupConfiguration)

    suspend fun compareAndSet(
        lineageId: CloudLineageId,
        expected: RemoteBackupStateVersion,
        next: RemoteBackupConfiguration,
    ): Boolean

    suspend fun promoteRecoveryEnvelope(
        lineageId: CloudLineageId,
        expected: RemoteBackupStateVersion,
        next: RemoteBackupConfiguration,
        envelope: VaultKeyEnvelope,
        operationId: String,
        expectedOperationPhase: String,
        nextOperation: RemoteBackupOperation,
    ): Boolean = error("Atomic recovery-envelope promotion is unavailable")

    /**
     * The durable record a crash-interrupted operation resumes from. Its
     * phase and state bytes are the only way a restarted process learns which
     * identities were already generated and which remote objects already exist.
     */
    suspend fun operation(operationId: String): RemoteBackupOperation?

    suspend fun putOperation(operation: RemoteBackupOperation)

    suspend fun transitionOperation(
        operationId: String,
        expectedPhase: String,
        next: RemoteBackupOperation,
    ): Boolean
}

/** Provider-independent persisted create-only remote object transfer state. */
interface RemoteBackupTransferStore {
    suspend fun objectState(
        lineageId: CloudLineageId,
        logicalObjectId: RemoteLogicalObjectId,
    ): RemoteBackupObject?

    suspend fun insertObject(value: RemoteBackupObject)

    suspend fun compareAndSetObject(
        expected: RemoteBackupObject,
        next: RemoteBackupObject,
    ): Boolean

    suspend fun objectsForLineage(lineageId: CloudLineageId): List<RemoteBackupObject>

    suspend fun removeObjectState(
        lineageId: CloudLineageId,
        logicalObjectId: RemoteLogicalObjectId,
    ): Boolean
}
