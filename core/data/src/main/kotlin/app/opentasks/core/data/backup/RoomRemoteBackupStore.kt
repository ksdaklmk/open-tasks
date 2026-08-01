package app.opentasks.core.data.backup

import androidx.room.withTransaction
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupObject
import app.opentasks.core.domain.RemoteBackupOperation
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OwnershipClaimId
import app.opentasks.core.model.OwnershipClaimRef
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationId
import app.opentasks.core.model.PublicationRef
import app.opentasks.core.model.PublicationSequence
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.RemoteObjectLifecycle
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WriterEpoch
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Transaction-backed Room persistence for create-only remote backup state.
 *
 * A publication is only ever adopted as `currentPublication` once it has
 * been locally verified, so `currentPublication.generation` always equals
 * `lastVerifiedGeneration`; that invariant is enforced on every write.
 * `previousPublication` — identity, sequence (exactly one less than the
 * current sequence, a create-only protocol invariant), and its own
 * `previousPublicationGeneration` column — round-trips exactly; the
 * protocol only guarantees `current.generation >= previous.generation`, so
 * the previous publication's generation is never assumed to equal the
 * current one.
 */
class RoomRemoteBackupStore(
    private val database: VaultDatabase,
) : RemoteBackupStateStore, RemoteBackupTransferStore {

    override suspend fun active(vaultId: VaultId): RemoteBackupConfiguration? =
        database.remoteBackupConfigDao().activeForVault(vaultId.value)?.toDomain()

    override suspend fun configurations(vaultId: VaultId): List<RemoteBackupConfiguration> =
        database.remoteBackupConfigDao().forVault(vaultId.value).map(RemoteBackupConfigEntity::toDomain)

    override suspend fun known(lineageId: CloudLineageId): RemoteBackupConfiguration? =
        database.remoteBackupConfigDao().byLineageId(lineageId.value)?.toDomain()

    override fun observeActive(vaultId: VaultId): Flow<RemoteBackupConfiguration?> =
        database.remoteBackupConfigDao().observeActiveForVault(vaultId.value).map { it?.toDomain() }

    /**
     * Inserts the connecting row, or adopts this lineage's own orphan.
     *
     * The row is written before the durable phase that records it, so a crash
     * in that window leaves a `CONNECTING` row at state version zero with no
     * operation phase behind it. A plain insert would then abort forever and
     * that vault could never finish connecting, so the identical lineage's own
     * orphan is adopted instead. Adoption is exact: same lineage, same vault,
     * still `CONNECTING`, still at the initial state version. Anything else —
     * another vault, an active lineage, or a row that has already moved on —
     * fails closed rather than being overwritten.
     */
    override suspend fun insertConnecting(configuration: RemoteBackupConfiguration) {
        require(configuration.lifecycle == RemoteBackupLifecycle.CONNECTING) {
            "Initial remote backup configuration must start CONNECTING"
        }
        validateConfiguration(configuration)
        val now = System.currentTimeMillis()
        database.withTransaction {
            val dao = database.remoteBackupConfigDao()
            val existing = dao.byLineageId(configuration.lineageId.value)
            if (existing == null) {
                dao.insert(
                    configuration.toEntity(createdAtEpochMillis = now, updatedAtEpochMillis = now),
                )
                return@withTransaction
            }
            require(
                existing.vaultId == configuration.vaultId.value &&
                    existing.lifecycle == RemoteBackupLifecycle.CONNECTING.name &&
                    existing.stateVersion == configuration.stateVersion.value,
            ) {
                "An unrelated remote backup configuration already holds this lineage"
            }
            val entity = configuration.toEntity(
                createdAtEpochMillis = existing.createdAtEpochMillis,
                updatedAtEpochMillis = now,
            )
            val adopted = dao.adoptConnecting(
                lineageId = entity.lineageId,
                vaultId = entity.vaultId,
                expectedStateVersion = entity.stateVersion,
                rootClaimProviderFileId = entity.rootClaimProviderFileId,
                accountBindingDigest = entity.accountBindingDigest,
                updatedAtEpochMillis = entity.updatedAtEpochMillis,
            )
            check(adopted == 1) { "The connecting remote backup configuration changed" }
        }
    }

    override suspend fun compareAndSet(
        lineageId: CloudLineageId,
        expected: RemoteBackupStateVersion,
        next: RemoteBackupConfiguration,
    ): Boolean {
        require(next.lineageId == lineageId) {
            "Configuration mutation changed lineage identity"
        }
        validateConfiguration(next)
        return database.withTransaction {
            val dao = database.remoteBackupConfigDao()
            val current = dao.byLineageId(lineageId.value) ?: return@withTransaction false
            if (current.stateVersion != expected.value) return@withTransaction false
            if (current.vaultId != next.vaultId.value) return@withTransaction false
            val currentLifecycle = RemoteBackupLifecycle.valueOf(current.lifecycle)
            if (currentLifecycle == RemoteBackupLifecycle.TERMINATED &&
                next.lifecycle != RemoteBackupLifecycle.TERMINATED
            ) {
                // TERMINATED is irreversible; only cleanup-progress updates that keep the
                // row TERMINATED are accepted.
                return@withTransaction false
            }
            if (next.lifecycle == RemoteBackupLifecycle.ACTIVE) {
                val activeElsewhere = dao.activeCountForVaultExcluding(
                    next.vaultId.value,
                    lineageId.value,
                )
                if (activeElsewhere > 0) return@withTransaction false
            }
            val entity = next.toEntity(
                createdAtEpochMillis = current.createdAtEpochMillis,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
            val updated = dao.compareAndUpdate(
                lineageId = lineageId.value,
                expectedStateVersion = expected.value,
                rootClaimProviderFileId = entity.rootClaimProviderFileId,
                accountBindingDigest = entity.accountBindingDigest,
                lifecycle = entity.lifecycle,
                activeDeviceId = entity.activeDeviceId,
                writerEpoch = entity.writerEpoch,
                ownershipClaimProviderFileId = entity.ownershipClaimProviderFileId,
                ownershipClaimId = entity.ownershipClaimId,
                ownershipClaimSha256 = entity.ownershipClaimSha256,
                nextSuccessorProviderFileId = entity.nextSuccessorProviderFileId,
                currentPublicationProviderFileId = entity.currentPublicationProviderFileId,
                currentPublicationId = entity.currentPublicationId,
                currentPublicationSha256 = entity.currentPublicationSha256,
                previousPublicationProviderFileId = entity.previousPublicationProviderFileId,
                previousPublicationId = entity.previousPublicationId,
                previousPublicationSha256 = entity.previousPublicationSha256,
                previousPublicationGeneration = entity.previousPublicationGeneration,
                publicationSequence = entity.publicationSequence,
                lastVerifiedGeneration = entity.lastVerifiedGeneration,
                lastVerifiedAtEpochMillis = entity.lastVerifiedAtEpochMillis,
                recoveryCredentialGeneration = entity.recoveryCredentialGeneration,
                failureCategory = entity.failureCategory,
                nextStateVersion = entity.stateVersion,
                updatedAtEpochMillis = entity.updatedAtEpochMillis,
            )
            updated == 1
        }
    }

    override suspend fun promoteRecoveryEnvelope(
        lineageId: CloudLineageId,
        expected: RemoteBackupStateVersion,
        next: RemoteBackupConfiguration,
        envelope: VaultKeyEnvelope,
        operationId: String,
        expectedOperationPhase: String,
        nextOperation: RemoteBackupOperation,
    ): Boolean = database.withTransaction {
        val operation = database.remoteBackupOperationDao().get(operationId)
            ?: return@withTransaction false
        if (operation.phase != expectedOperationPhase) return@withTransaction false
        if (!compareAndSet(lineageId, expected, next)) return@withTransaction false
        val entity = RecoveryEnvelopeCodec.toEntity(next.vaultId, envelope)
        try {
            database.vaultRecoveryEnvelopeDao().upsert(entity)
            check(
                transitionOperation(operationId, expectedOperationPhase, nextOperation),
            ) { "Recovery-envelope operation changed during promotion" }
            true
        } finally {
            entity.salt.fill(0)
            entity.nonce.fill(0)
            entity.wrappedKeyset.fill(0)
        }
    }

    override suspend fun operation(operationId: String): RemoteBackupOperation? =
        database.remoteBackupOperationDao().get(operationId)?.toDomain()

    override suspend fun putOperation(operation: RemoteBackupOperation) {
        validateOperation(operation)
        database.withTransaction {
            database.remoteBackupOperationDao().insert(operation.toEntity())
        }
    }

    override suspend fun transitionOperation(
        operationId: String,
        expectedPhase: String,
        next: RemoteBackupOperation,
    ): Boolean {
        require(next.operationId == operationId) {
            "Operation mutation changed operation identity"
        }
        validateOperation(next)
        return database.withTransaction {
            val dao = database.remoteBackupOperationDao()
            val current = dao.get(operationId) ?: return@withTransaction false
            if (current.phase != expectedPhase) return@withTransaction false
            if (current.lineageId != next.lineageId.value) return@withTransaction false
            val entity = next.toEntity()
            val updated = dao.compareAndUpdate(
                operationId = operationId,
                expectedPhase = expectedPhase,
                phase = entity.phase,
                targetEpoch = entity.targetEpoch,
                targetGeneration = entity.targetGeneration,
                candidateClaimProviderFileId = entity.candidateClaimProviderFileId,
                candidatePublicationProviderFileId = entity.candidatePublicationProviderFileId,
                stateBytes = entity.stateBytes,
                updatedAtEpochMillis = entity.updatedAtEpochMillis,
            )
            updated == 1
        }
    }

    override suspend fun objectState(
        lineageId: CloudLineageId,
        logicalObjectId: RemoteLogicalObjectId,
    ): RemoteBackupObject? =
        database.remoteBackupObjectDao().get(lineageId.value, logicalObjectId.value)?.toDomain()

    override suspend fun insertObject(value: RemoteBackupObject) {
        validateObject(value)
        database.withTransaction {
            database.remoteBackupObjectDao().insert(value.toEntity())
        }
    }

    override suspend fun compareAndSetObject(
        expected: RemoteBackupObject,
        next: RemoteBackupObject,
    ): Boolean {
        require(expected.lineageId == next.lineageId) {
            "Object mutation changed lineage identity"
        }
        require(expected.logicalObjectId == next.logicalObjectId) {
            "Object mutation changed logical object identity"
        }
        validateObject(next)
        return database.withTransaction {
            val dao = database.remoteBackupObjectDao()
            val current = dao.get(expected.lineageId.value, expected.logicalObjectId.value)
                ?: return@withTransaction false
            if (current.toDomain() != expected) return@withTransaction false
            // A resumable upload session may only be cleared once the object has been
            // verified; otherwise in-flight upload progress would be silently discarded.
            if (
                expected.resumableSessionUri != null &&
                next.resumableSessionUri == null &&
                next.verifiedAt == null
            ) {
                return@withTransaction false
            }
            dao.update(next.toEntity()) == 1
        }
    }

    override suspend fun objectsForLineage(lineageId: CloudLineageId): List<RemoteBackupObject> =
        database.remoteBackupObjectDao().forLineage(lineageId.value).map { it.toDomain() }

    override suspend fun removeObjectState(
        lineageId: CloudLineageId,
        logicalObjectId: RemoteLogicalObjectId,
    ): Boolean = database.withTransaction {
        database.remoteBackupObjectDao().delete(lineageId.value, logicalObjectId.value) == 1
    }
}

private fun validateConfiguration(configuration: RemoteBackupConfiguration) {
    require(configuration.recoveryCredentialGeneration >= 0) {
        "Recovery credential generation is negative"
    }
    val claim = configuration.ownershipClaim
    val writerEpoch = configuration.writerEpoch
    if (claim != null) {
        requireNotNull(writerEpoch) { "Ownership claim requires a configuration writer epoch" }
        require(claim.writerEpoch.value == writerEpoch.value) {
            "Ownership claim epoch does not match the configuration writer epoch"
        }
    }
    val current = configuration.currentPublication
    val lastVerifiedGeneration = configuration.lastVerifiedGeneration
    val lastVerifiedAt = configuration.lastVerifiedAt
    if (current != null) {
        requireNotNull(lastVerifiedGeneration) {
            "Current publication requires a last verified generation"
        }
        require(current.generation.value == lastVerifiedGeneration.value) {
            "Current publication generation does not match the last verified generation"
        }
        requireNotNull(lastVerifiedAt) { "Current publication requires a last verified time" }
        require(lastVerifiedAt.toEpochMilli() >= 0) {
            "Last verified time is before the epoch"
        }
    }
    val previous = configuration.previousPublication
    if (previous != null) {
        requireNotNull(current) { "Previous publication requires a current publication" }
        require(previous.sequence.value == current.sequence.value - 1) {
            "Previous publication sequence does not immediately precede the current sequence"
        }
    }
}

private fun validateOperation(operation: RemoteBackupOperation) {
    require(operation.startedAt.toEpochMilli() >= 0) { "Operation start time is before the epoch" }
    require(operation.updatedAt.toEpochMilli() >= 0) { "Operation update time is before the epoch" }
}

private fun validateObject(value: RemoteBackupObject) {
    require(value.frameLength >= 0) { "Object frame length is negative" }
    require(value.uploadedBytes >= 0) { "Object uploaded byte offset is negative" }
    require(value.lastGeneration.value >= value.firstGeneration.value) {
        "Object last generation precedes its first generation"
    }
    require(value.createdAt.toEpochMilli() >= 0) { "Object created time is before the epoch" }
    value.verifiedAt?.let {
        require(it.toEpochMilli() >= 0) { "Object verified time is before the epoch" }
    }
}

private fun RemoteBackupConfiguration.toEntity(
    createdAtEpochMillis: Long,
    updatedAtEpochMillis: Long,
): RemoteBackupConfigEntity {
    val claim = ownershipClaim
    val current = currentPublication
    val previous = previousPublication
    return RemoteBackupConfigEntity(
        lineageId = lineageId.value,
        vaultId = vaultId.value,
        rootClaimProviderFileId = rootClaimProviderId.value,
        accountBindingDigest = accountBindingDigest,
        lifecycle = lifecycle.name,
        activeDeviceId = activeDeviceId?.value,
        writerEpoch = writerEpoch?.value,
        ownershipClaimProviderFileId = claim?.providerId?.value,
        ownershipClaimId = claim?.logicalId?.value,
        ownershipClaimSha256 = claim?.sha256?.value,
        nextSuccessorProviderFileId = nextSuccessorProviderId?.value,
        currentPublicationProviderFileId = current?.providerId?.value,
        currentPublicationId = current?.logicalId?.value,
        currentPublicationSha256 = current?.sha256?.value,
        previousPublicationProviderFileId = previous?.providerId?.value,
        previousPublicationId = previous?.logicalId?.value,
        previousPublicationSha256 = previous?.sha256?.value,
        previousPublicationGeneration = previous?.generation?.value,
        publicationSequence = current?.sequence?.value,
        lastVerifiedGeneration = lastVerifiedGeneration?.value,
        lastVerifiedAtEpochMillis = lastVerifiedAt?.toEpochMilli(),
        recoveryCredentialGeneration = recoveryCredentialGeneration,
        failureCategory = failureCategory?.name,
        stateVersion = stateVersion.value,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

private fun RemoteBackupConfigEntity.toDomain(): RemoteBackupConfiguration {
    val writerEpochValue = writerEpoch?.let(::WriterEpoch)
    val ownershipClaim = if (ownershipClaimProviderFileId != null) {
        OwnershipClaimRef(
            providerId = ProviderObjectId.of(ownershipClaimProviderFileId),
            logicalId = OwnershipClaimId.parse(requireNotNull(ownershipClaimId)),
            sha256 = Sha256Digest.of(requireNotNull(ownershipClaimSha256)),
            writerEpoch = requireNotNull(writerEpochValue),
        )
    } else {
        null
    }
    val currentPublication = if (currentPublicationProviderFileId != null) {
        PublicationRef(
            providerId = ProviderObjectId.of(currentPublicationProviderFileId),
            logicalId = PublicationId.parse(requireNotNull(currentPublicationId)),
            sha256 = Sha256Digest.of(requireNotNull(currentPublicationSha256)),
            sequence = PublicationSequence(requireNotNull(publicationSequence)),
            generation = BackupGeneration(requireNotNull(lastVerifiedGeneration)),
        )
    } else {
        null
    }
    val previousPublication = if (previousPublicationProviderFileId != null) {
        PublicationRef(
            providerId = ProviderObjectId.of(previousPublicationProviderFileId),
            logicalId = PublicationId.parse(requireNotNull(previousPublicationId)),
            sha256 = Sha256Digest.of(requireNotNull(previousPublicationSha256)),
            sequence = PublicationSequence(requireNotNull(publicationSequence) - 1),
            generation = BackupGeneration(requireNotNull(previousPublicationGeneration)),
        )
    } else {
        null
    }
    return RemoteBackupConfiguration(
        lineageId = CloudLineageId.parse(lineageId),
        vaultId = VaultId(vaultId),
        rootClaimProviderId = ProviderObjectId.of(rootClaimProviderFileId),
        accountBindingDigest = accountBindingDigest,
        lifecycle = RemoteBackupLifecycle.valueOf(lifecycle),
        activeDeviceId = activeDeviceId?.let(CloudDeviceId::parse),
        writerEpoch = writerEpochValue,
        ownershipClaim = ownershipClaim,
        nextSuccessorProviderId = nextSuccessorProviderFileId?.let(ProviderObjectId::of),
        currentPublication = currentPublication,
        previousPublication = previousPublication,
        lastVerifiedGeneration = lastVerifiedGeneration?.let(::BackupGeneration),
        lastVerifiedAt = lastVerifiedAtEpochMillis?.let(Instant::ofEpochMilli),
        recoveryCredentialGeneration = recoveryCredentialGeneration,
        failureCategory = failureCategory?.let(RemoteBackupFailureCategory::valueOf),
        stateVersion = RemoteBackupStateVersion(stateVersion),
    )
}

private fun RemoteBackupOperation.toEntity(): RemoteBackupOperationEntity =
    RemoteBackupOperationEntity(
        operationId = operationId,
        lineageId = lineageId.value,
        kind = kind,
        phase = phase,
        targetEpoch = targetEpoch?.value,
        targetGeneration = targetGeneration?.value,
        candidateClaimProviderFileId = candidateClaimProviderId?.value,
        candidatePublicationProviderFileId = candidatePublicationProviderId?.value,
        stateBytes = stateBytes,
        startedAtEpochMillis = startedAt.toEpochMilli(),
        updatedAtEpochMillis = updatedAt.toEpochMilli(),
    )

private fun RemoteBackupOperationEntity.toDomain(): RemoteBackupOperation =
    RemoteBackupOperation(
        operationId = operationId,
        lineageId = CloudLineageId.parse(lineageId),
        kind = kind,
        phase = phase,
        targetEpoch = targetEpoch?.let(::WriterEpoch),
        targetGeneration = targetGeneration?.let(::BackupGeneration),
        candidateClaimProviderId = candidateClaimProviderFileId?.let(ProviderObjectId::of),
        candidatePublicationProviderId =
            candidatePublicationProviderFileId?.let(ProviderObjectId::of),
        stateBytes = stateBytes,
        startedAt = Instant.ofEpochMilli(startedAtEpochMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    )

private fun RemoteBackupObject.toEntity(): RemoteBackupObjectEntity =
    RemoteBackupObjectEntity(
        lineageId = lineageId.value,
        logicalObjectId = logicalObjectId.value,
        providerFileId = providerObjectId.value,
        role = role.name,
        writerEpoch = writerEpoch.value,
        ownerDeviceId = ownerDeviceId.value,
        operationId = operationId,
        firstGeneration = firstGeneration.value,
        lastGeneration = lastGeneration.value,
        frameLength = frameLength,
        frameSha256 = frameSha256.value,
        lifecycle = lifecycle.name,
        resumableSessionUri = resumableSessionUri,
        uploadedBytes = uploadedBytes,
        createdAtEpochMillis = createdAt.toEpochMilli(),
        verifiedAtEpochMillis = verifiedAt?.toEpochMilli(),
    )

private fun RemoteBackupObjectEntity.toDomain(): RemoteBackupObject =
    RemoteBackupObject(
        lineageId = CloudLineageId.parse(lineageId),
        logicalObjectId = RemoteLogicalObjectId.of(logicalObjectId),
        providerObjectId = ProviderObjectId.of(providerFileId),
        role = RemoteObjectRoleV1.valueOf(role),
        writerEpoch = WriterEpoch(writerEpoch),
        ownerDeviceId = CloudDeviceId.parse(ownerDeviceId),
        operationId = operationId,
        firstGeneration = BackupGeneration(firstGeneration),
        lastGeneration = BackupGeneration(lastGeneration),
        frameLength = frameLength,
        frameSha256 = Sha256Digest.of(frameSha256),
        lifecycle = RemoteObjectLifecycle.valueOf(lifecycle),
        resumableSessionUri = resumableSessionUri,
        uploadedBytes = uploadedBytes,
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
        verifiedAt = verifiedAtEpochMillis?.let(Instant::ofEpochMilli),
    )
