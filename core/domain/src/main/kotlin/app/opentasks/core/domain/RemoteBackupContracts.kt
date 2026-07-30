package app.opentasks.core.domain

import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OwnershipClaimRef
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationRef
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

/**
 * Provider-independent persisted create-only remote-backup state.
 *
 * Byte arrays are copied on the way in and on the way out so no caller shares
 * a mutable buffer with persisted state, and identity comparison stays by
 * value. [toString] never reveals identifiers, digests, or buffer content.
 */
class RemoteBackupConfiguration(
    val lineageId: CloudLineageId,
    val vaultId: VaultId,
    val rootClaimProviderId: ProviderObjectId,
    accountBindingDigest: ByteArray,
    val lifecycle: RemoteBackupLifecycle,
    val activeDeviceId: CloudDeviceId?,
    val writerEpoch: WriterEpoch?,
    val ownershipClaim: OwnershipClaimRef?,
    val nextSuccessorProviderId: ProviderObjectId?,
    val currentPublication: PublicationRef?,
    val previousPublication: PublicationRef?,
    val lastVerifiedGeneration: BackupGeneration?,
    val lastVerifiedAt: Instant?,
    val recoveryCredentialGeneration: Long,
    val failureCategory: RemoteBackupFailureCategory?,
    val stateVersion: RemoteBackupStateVersion,
) {
    private val accountBindingDigestBytes: ByteArray = accountBindingDigest.copyOf()

    val accountBindingDigest: ByteArray
        get() = accountBindingDigestBytes.copyOf()

    init {
        require(recoveryCredentialGeneration >= 0) {
            "Recovery credential generation is negative"
        }
    }

    fun copy(
        lineageId: CloudLineageId = this.lineageId,
        vaultId: VaultId = this.vaultId,
        rootClaimProviderId: ProviderObjectId = this.rootClaimProviderId,
        accountBindingDigest: ByteArray = this.accountBindingDigest,
        lifecycle: RemoteBackupLifecycle = this.lifecycle,
        activeDeviceId: CloudDeviceId? = this.activeDeviceId,
        writerEpoch: WriterEpoch? = this.writerEpoch,
        ownershipClaim: OwnershipClaimRef? = this.ownershipClaim,
        nextSuccessorProviderId: ProviderObjectId? = this.nextSuccessorProviderId,
        currentPublication: PublicationRef? = this.currentPublication,
        previousPublication: PublicationRef? = this.previousPublication,
        lastVerifiedGeneration: BackupGeneration? = this.lastVerifiedGeneration,
        lastVerifiedAt: Instant? = this.lastVerifiedAt,
        recoveryCredentialGeneration: Long = this.recoveryCredentialGeneration,
        failureCategory: RemoteBackupFailureCategory? = this.failureCategory,
        stateVersion: RemoteBackupStateVersion = this.stateVersion,
    ): RemoteBackupConfiguration = RemoteBackupConfiguration(
        lineageId = lineageId,
        vaultId = vaultId,
        rootClaimProviderId = rootClaimProviderId,
        accountBindingDigest = accountBindingDigest,
        lifecycle = lifecycle,
        activeDeviceId = activeDeviceId,
        writerEpoch = writerEpoch,
        ownershipClaim = ownershipClaim,
        nextSuccessorProviderId = nextSuccessorProviderId,
        currentPublication = currentPublication,
        previousPublication = previousPublication,
        lastVerifiedGeneration = lastVerifiedGeneration,
        lastVerifiedAt = lastVerifiedAt,
        recoveryCredentialGeneration = recoveryCredentialGeneration,
        failureCategory = failureCategory,
        stateVersion = stateVersion,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteBackupConfiguration) return false
        return lineageId == other.lineageId &&
            vaultId == other.vaultId &&
            rootClaimProviderId == other.rootClaimProviderId &&
            accountBindingDigestBytes.contentEquals(other.accountBindingDigestBytes) &&
            lifecycle == other.lifecycle &&
            activeDeviceId == other.activeDeviceId &&
            writerEpoch == other.writerEpoch &&
            ownershipClaim == other.ownershipClaim &&
            nextSuccessorProviderId == other.nextSuccessorProviderId &&
            currentPublication == other.currentPublication &&
            previousPublication == other.previousPublication &&
            lastVerifiedGeneration == other.lastVerifiedGeneration &&
            lastVerifiedAt == other.lastVerifiedAt &&
            recoveryCredentialGeneration == other.recoveryCredentialGeneration &&
            failureCategory == other.failureCategory &&
            stateVersion == other.stateVersion
    }

    override fun hashCode(): Int {
        var result = lineageId.hashCode()
        result = 31 * result + vaultId.hashCode()
        result = 31 * result + rootClaimProviderId.hashCode()
        result = 31 * result + accountBindingDigestBytes.contentHashCode()
        result = 31 * result + lifecycle.hashCode()
        result = 31 * result + activeDeviceId.hashCode()
        result = 31 * result + writerEpoch.hashCode()
        result = 31 * result + ownershipClaim.hashCode()
        result = 31 * result + nextSuccessorProviderId.hashCode()
        result = 31 * result + currentPublication.hashCode()
        result = 31 * result + previousPublication.hashCode()
        result = 31 * result + lastVerifiedGeneration.hashCode()
        result = 31 * result + lastVerifiedAt.hashCode()
        result = 31 * result + recoveryCredentialGeneration.hashCode()
        result = 31 * result + failureCategory.hashCode()
        result = 31 * result + stateVersion.hashCode()
        return result
    }

    override fun toString(): String =
        "RemoteBackupConfiguration(lifecycle=$lifecycle, stateVersion=$stateVersion)"
}

class RemoteBackupOperation(
    val operationId: String,
    val lineageId: CloudLineageId,
    val kind: String,
    val phase: String,
    val targetEpoch: WriterEpoch?,
    val targetGeneration: BackupGeneration?,
    val candidateClaimProviderId: ProviderObjectId?,
    val candidatePublicationProviderId: ProviderObjectId?,
    stateBytes: ByteArray,
    val startedAt: Instant,
    val updatedAt: Instant,
) {
    private val ownedStateBytes: ByteArray = stateBytes.copyOf()

    val stateBytes: ByteArray
        get() = ownedStateBytes.copyOf()

    fun copy(
        operationId: String = this.operationId,
        lineageId: CloudLineageId = this.lineageId,
        kind: String = this.kind,
        phase: String = this.phase,
        targetEpoch: WriterEpoch? = this.targetEpoch,
        targetGeneration: BackupGeneration? = this.targetGeneration,
        candidateClaimProviderId: ProviderObjectId? = this.candidateClaimProviderId,
        candidatePublicationProviderId: ProviderObjectId? =
            this.candidatePublicationProviderId,
        stateBytes: ByteArray = this.stateBytes,
        startedAt: Instant = this.startedAt,
        updatedAt: Instant = this.updatedAt,
    ): RemoteBackupOperation = RemoteBackupOperation(
        operationId = operationId,
        lineageId = lineageId,
        kind = kind,
        phase = phase,
        targetEpoch = targetEpoch,
        targetGeneration = targetGeneration,
        candidateClaimProviderId = candidateClaimProviderId,
        candidatePublicationProviderId = candidatePublicationProviderId,
        stateBytes = stateBytes,
        startedAt = startedAt,
        updatedAt = updatedAt,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteBackupOperation) return false
        return operationId == other.operationId &&
            lineageId == other.lineageId &&
            kind == other.kind &&
            phase == other.phase &&
            targetEpoch == other.targetEpoch &&
            targetGeneration == other.targetGeneration &&
            candidateClaimProviderId == other.candidateClaimProviderId &&
            candidatePublicationProviderId == other.candidatePublicationProviderId &&
            ownedStateBytes.contentEquals(other.ownedStateBytes) &&
            startedAt == other.startedAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = operationId.hashCode()
        result = 31 * result + lineageId.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + phase.hashCode()
        result = 31 * result + targetEpoch.hashCode()
        result = 31 * result + targetGeneration.hashCode()
        result = 31 * result + candidateClaimProviderId.hashCode()
        result = 31 * result + candidatePublicationProviderId.hashCode()
        result = 31 * result + ownedStateBytes.contentHashCode()
        result = 31 * result + startedAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }

    override fun toString(): String =
        "RemoteBackupOperation(kind=$kind, phase=$phase)"
}

data class RemoteBackupObject(
    val lineageId: CloudLineageId,
    val logicalObjectId: RemoteLogicalObjectId,
    val providerObjectId: ProviderObjectId,
    val role: RemoteObjectRoleV1,
    val writerEpoch: WriterEpoch,
    val ownerDeviceId: CloudDeviceId,
    val operationId: String,
    val firstGeneration: BackupGeneration,
    val lastGeneration: BackupGeneration,
    val frameLength: Long,
    val frameSha256: Sha256Digest,
    val lifecycle: RemoteObjectLifecycle,
    val resumableSessionUri: String?,
    val uploadedBytes: Long,
    val createdAt: Instant,
    val verifiedAt: Instant?,
) {
    override fun toString(): String =
        "RemoteBackupObject(role=$role, lifecycle=$lifecycle)"
}
