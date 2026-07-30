package app.opentasks.core.data.backup

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Additive Room v7 persistence for create-only Google Drive remote backup
 * state. Every table is additive over the v6 schema; see
 * [app.opentasks.core.data.db.VaultDatabase.MIGRATION_6_7].
 */
@Entity(
    tableName = "remote_backup_config",
    indices = [Index("vaultId"), Index("lifecycle")],
)
data class RemoteBackupConfigEntity(
    @PrimaryKey val lineageId: String,
    val vaultId: String,
    val rootClaimProviderFileId: String,
    val accountBindingDigest: ByteArray,
    val lifecycle: String,
    val activeDeviceId: String?,
    val writerEpoch: Long?,
    val ownershipClaimProviderFileId: String?,
    val ownershipClaimId: String?,
    val ownershipClaimSha256: String?,
    val nextSuccessorProviderFileId: String?,
    val currentPublicationProviderFileId: String?,
    val currentPublicationId: String?,
    val currentPublicationSha256: String?,
    val previousPublicationProviderFileId: String?,
    val previousPublicationId: String?,
    val previousPublicationSha256: String?,
    val previousPublicationGeneration: Long?,
    val publicationSequence: Long?,
    val lastVerifiedGeneration: Long?,
    val lastVerifiedAtEpochMillis: Long?,
    val recoveryCredentialGeneration: Long,
    val failureCategory: String?,
    val stateVersion: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "remote_backup_object",
    primaryKeys = ["lineageId", "logicalObjectId"],
    indices = [Index("providerFileId", unique = true), Index("operationId")],
)
data class RemoteBackupObjectEntity(
    val lineageId: String,
    val logicalObjectId: String,
    val providerFileId: String,
    val role: String,
    val writerEpoch: Long,
    val ownerDeviceId: String,
    val operationId: String,
    val firstGeneration: Long,
    val lastGeneration: Long,
    val frameLength: Long,
    val frameSha256: String,
    val lifecycle: String,
    val resumableSessionUri: String?,
    val uploadedBytes: Long,
    val createdAtEpochMillis: Long,
    val verifiedAtEpochMillis: Long?,
)

@Entity(
    tableName = "remote_backup_operation",
    indices = [Index("lineageId"), Index("kind"), Index("phase")],
)
data class RemoteBackupOperationEntity(
    @PrimaryKey val operationId: String,
    val lineageId: String,
    val kind: String,
    val phase: String,
    val targetEpoch: Long?,
    val targetGeneration: Long?,
    val candidateClaimProviderFileId: String?,
    val candidatePublicationProviderFileId: String?,
    val stateBytes: ByteArray,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
