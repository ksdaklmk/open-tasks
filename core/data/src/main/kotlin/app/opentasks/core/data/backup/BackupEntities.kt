package app.opentasks.core.data.backup

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "backup_journal",
    indices = [
        Index(
            value = ["vaultId", "generation", "sequence"],
            unique = true,
        ),
    ],
)
data class BackupJournalEntity(
    @PrimaryKey val operationId: String,
    val vaultId: String,
    val generation: Long,
    val sequence: Int,
    val payloadFormatVersion: Int,
    val mutationKind: String,
    val objectId: String,
    val objectType: String,
    val payload: ByteArray,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val sourceDeviceId: String,
)

@Entity(tableName = "backup_state")
data class BackupStateEntity(
    @PrimaryKey val vaultId: String,
    val currentGeneration: Long,
    val lastVerifiedSnapshotGeneration: Long?,
    val currentBaseObjectId: String?,
    val previousBaseObjectId: String?,
    val latestVerifiedSegmentGeneration: Long?,
    val portablePackageGeneration: Long?,
    val portablePackageBytes: Long?,
    val portablePackageProducedAtEpochMillis: Long?,
    val packageState: String,
    val failureCategory: String?,
    val recoveryEnvelopeReady: Boolean,
    val legacyOutboxCoveredAtGeneration: Long?,
    val snapshotCreatedAtEpochMillis: Long?,
)

@Entity(tableName = "vault_recovery_envelope")
data class VaultRecoveryEnvelopeEntity(
    @PrimaryKey val vaultId: String,
    val formatVersion: Int,
    val kdfAlgorithm: String,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val salt: ByteArray,
    val nonce: ByteArray,
    val wrappedKeyset: ByteArray,
)
