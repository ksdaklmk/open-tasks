package app.opentasks.core.domain

import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OwnershipClaimRef
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationRef
import app.opentasks.core.model.RecoveryFailureCategory
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.RemoteObjectLifecycle
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WriterEpoch
import java.io.File
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

/**
 * Provider-independent create-only remote object storage contract.
 *
 * [CreateOnlyDriveObjectStore] in `core:data` is the sole implementation,
 * composing [app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport]
 * with the Task 3 [app.opentasks.core.data.backup.RemoteBackupTransferStore].
 * This interface lives here — rather than beside its implementation — so
 * later ownership, publication, and configurator work in `core:data` can
 * depend on it without depending on a concrete provider.
 *
 * A held [OwnedRemoteBytes] or [OwnedRemoteFile] owns private, staged bytes:
 * [OwnedRemoteBytes.take] transfers its buffer exactly once, and closing
 * either type makes every later access fail rather than silently return
 * stale or cleared content. Ownership direction differs by call, though:
 * bytes passed into [CreateOnlyBackupObjectStore.createSmallIfAbsent] are
 * store-owned for that single call — the store always closes them before
 * returning, whatever the outcome. [ImmutableUploadRequest.frame] is
 * caller-owned instead: an upload reads it across multiple resumable
 * chunks and may be retried by the caller with the same request after a
 * failure, so the store never closes it; the caller closes it once the
 * whole upload for that frame is finished, successfully or not. A value
 * the store *returns* to the caller (a [ReadSmallResult.Found]'s `bytes`,
 * or an [ImmutableDownloadResult.Downloaded]'s `frame`) is caller-owned
 * from the moment it is returned.
 */
interface OwnedRemoteBytes : AutoCloseable {
    val size: Int
    fun take(): ByteArray
}

interface OwnedRemoteFile : AutoCloseable {
    val file: File
    val length: Long
}

/**
 * A bounded provider index query.
 *
 * [lineageId] is null only for account-wide ownership-root discovery, which
 * must see roots belonging to lineages this installation has never held.
 * Every other listing names its exact lineage.
 */
data class RemoteListRequest(
    val lineageId: CloudLineageId?,
    val role: RemoteObjectRoleV1,
    val writerEpoch: WriterEpoch?,
    val ownerDeviceId: CloudDeviceId?,
    val pageToken: String?,
    val pageSize: Int,
)

data class RemoteListedObject(
    val providerObjectId: ProviderObjectId,
    val logicalObjectId: String?,
    val role: RemoteObjectRoleV1?,
    val writerEpoch: WriterEpoch?,
    val ownerDeviceId: CloudDeviceId?,
)

data class RemoteListPage(
    val objects: List<RemoteListedObject>,
    val nextPageToken: String?,
)

sealed interface CreateSmallResult {
    data object Created : CreateSmallResult
    data object AlreadyExists : CreateSmallResult
    data object Ambiguous : CreateSmallResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        CreateSmallResult
}

sealed interface ReadSmallResult {
    data class Found(val bytes: OwnedRemoteBytes) : ReadSmallResult
    data object Missing : ReadSmallResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        ReadSmallResult
}

data class ImmutableUploadRequest(
    val lineageId: CloudLineageId,
    val writerEpoch: WriterEpoch,
    val ownerDeviceId: CloudDeviceId,
    val operationId: String,
    val logicalObjectId: RemoteLogicalObjectId,
    val providerObjectId: ProviderObjectId,
    val role: RemoteObjectRoleV1,
    val firstGeneration: BackupGeneration,
    val lastGeneration: BackupGeneration,
    val frameLength: Long,
    val frameSha256: Sha256Digest,
    val frame: OwnedRemoteFile,
)

sealed interface ImmutableUploadResult {
    data object UploadedAndVerified : ImmutableUploadResult
    data object OccupiedByExpectedBytes : ImmutableUploadResult
    data object OccupiedByDifferentBytes : ImmutableUploadResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        ImmutableUploadResult
}

sealed interface ImmutableDownloadResult {
    data class Downloaded(val frame: OwnedRemoteFile) :
        ImmutableDownloadResult
    data object Missing : ImmutableDownloadResult
    data object Corrupt : ImmutableDownloadResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        ImmutableDownloadResult
}

sealed interface DeleteObjectResult {
    data object Deleted : DeleteObjectResult
    data object Missing : DeleteObjectResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        DeleteObjectResult
}

sealed interface RemoteBackupConnectResult {
    /**
     * The account already holds ownership roots, so setup stopped before it
     * created anything. Only an explicit separate-lineage choice may continue.
     */
    data class ExistingBackupsFound(val count: Int) : RemoteBackupConnectResult

    data class Connected(
        val lineageId: CloudLineageId,
        val generation: BackupGeneration,
    ) : RemoteBackupConnectResult

    data class Failed(val reason: RemoteBackupFailureCategory) : RemoteBackupConnectResult
}

/**
 * Establishes epoch one of a create-only remote backup lineage.
 *
 * The implementation lives in `core:data` because it composes the ownership
 * and publication codecs with Stage 2 local capture; this contract stays
 * provider-independent so scheduling and product code never depend on Drive.
 */
interface RemoteBackupConfigurator {
    suspend fun connect(
        objectStore: CreateOnlyBackupObjectStore,
        accountBindingDigest: ByteArray,
        allowSeparateLineage: Boolean,
    ): RemoteBackupConnectResult
}

/**
 * The bounded outcome of one routine remote-backup run.
 *
 * Every case carries a redacted category at most: no provider message,
 * identifier, digest, or account detail ever reaches a caller, so a result may
 * be surfaced in product state without further filtering.
 */
sealed interface RemoteBackupRunResult {
    /** A successor publication was created, read back, and checkpointed. */
    data class Verified(val generation: BackupGeneration) : RemoteBackupRunResult

    /** The remote lineage already publishes this local generation. */
    data object NoChanges : RemoteBackupRunResult

    data object AuthorizationRequired : RemoteBackupRunResult
    data object AccountMismatch : RemoteBackupRunResult
    data object OwnershipLost : RemoteBackupRunResult
    data object Terminated : RemoteBackupRunResult
    data object AmbiguousRemoteState : RemoteBackupRunResult

    data class Retryable(val reason: RemoteBackupFailureCategory) : RemoteBackupRunResult
    data class Blocked(val reason: RemoteBackupFailureCategory) : RemoteBackupRunResult
}

/**
 * Publishes routine immutable successors inside an already-established
 * ownership epoch.
 *
 * One process-scoped run per lineage executes at a time; concurrent callers
 * join the run in flight and observe its result, its failure, or its
 * cancellation. The implementation lives in `core:data` beside the codecs it
 * composes, so scheduling and product code never depend on a provider.
 */
interface RemoteBackupCoordinator {
    suspend fun run(objectStore: CreateOnlyBackupObjectStore): RemoteBackupRunResult
}

/**
 * Provider- and framework-independent background scheduling for routine
 * remote backup.
 *
 * The implementation lives in `:app` because only that module may depend on
 * WorkManager; no scheduling type, work name, or worker input reaches this
 * contract, so product and data code never learn how work is scheduled.
 * Every scheduled unit is identified by a constant name alone: an
 * implementation must place no lineage, provider identity, account digest,
 * token, claim, publication, session URI, or task text in a work name or in
 * worker input.
 */
interface BackupWorkScheduler {
    /** Debounces one routine run for a local generation the lineage has not published. */
    fun onPendingGeneration()

    /** Ensures the recurring safety-net check exists, without disturbing a scheduled one. */
    fun ensurePeriodic()

    /** Cancels the ordinary debounced and recurring work this scheduler owns. */
    fun cancelAll()
}

/**
 * One routine remote-backup attempt, from authorization through publication.
 *
 * Exactly one run executes at a time for a vault, whatever asks for it: a
 * background worker and a manual request share a single instance, so no two
 * runs can ever drive the same [RemoteBackupCoordinator] concurrently.
 * A run never launches authorization UI; a resolution it cannot satisfy
 * silently becomes [RemoteBackupRunResult.AuthorizationRequired] and is
 * persisted as an action-required state instead.
 */
interface RemoteBackupRunner {
    suspend fun run(): RemoteBackupRunResult
}

enum class RecoverySource {
    GOOGLE_DRIVE,
    ANDROID_BACKUP_PACKAGE,
}

/**
 * One offer a recovery may be attempted from.
 *
 * [handle] is random, process-local, and bounded: it is minted per discovery
 * pass and resolves to nothing outside the coordinator that issued it, so it
 * reveals no lineage, provider identity, timestamp, content, generation, or
 * account. A handle from an earlier pass, or from another coordinator, is
 * simply unknown.
 */
data class RecoveryCandidate(
    val handle: String,
    val source: RecoverySource,
)

/**
 * The bounded outcome of one recovery step.
 *
 * Every case carries a redacted category at most, so a result may be surfaced
 * in product state without further filtering.
 */
sealed interface RecoveryResult {
    /**
     * The source is authenticated, the staged vault is verified, and the epoch
     * baseline that [nextWriterEpoch] will bind exists — but no ownership has
     * been taken. Only an explicit confirmation may continue.
     *
     * [generation] is the generation the authenticated backup describes.
     */
    data class TakeoverConfirmationRequired(
        val operationId: String,
        val generation: BackupGeneration,
        val nextWriterEpoch: WriterEpoch,
    ) : RecoveryResult

    /**
     * The recovered vault is the live vault. [generation] is what it actually
     * holds, which may be one ahead of the recovered payload when opening it
     * purged retention-expired trash. [lineageId] is null for a portable
     * package, which carries no remote lineage and must be connected later.
     */
    data class Activated(
        val generation: BackupGeneration,
        val lineageId: CloudLineageId?,
    ) : RecoveryResult

    data class Failed(val reason: RecoveryFailureCategory) : RecoveryResult
}

/**
 * The only path from backup data back into a live vault.
 *
 * Recovery never mutates the running vault: it reconstructs a separate
 * inactive staging slot, proves it, takes ownership of the remote lineage at
 * an exact reserved successor, and only then publishes the slot. The
 * implementation lives in `core:data` beside the codecs and the staged-vault
 * verifier it composes, so product code never depends on a provider.
 */
interface RecoveryCoordinator {
    /**
     * Lists what this account and this device offer, without deriving a key,
     * downloading an object, or creating anything. Drive discovery lists both
     * ownership roots and terminal tombstones, so an authenticated terminal
     * lineage is offered and then refused rather than silently recreated.
     */
    suspend fun discover(
        objectStore: CreateOnlyBackupObjectStore?,
        portablePackage: File?,
    ): List<RecoveryCandidate>

    /**
     * Authenticates one candidate, reconstructs and verifies staging, and — for
     * a Drive lineage — prepares the successor epoch up to the point where
     * ownership would change. A portable package has no lineage to take over
     * and activates here.
     */
    suspend fun prepare(
        candidate: RecoveryCandidate,
        passphrase: CharArray,
        objectStore: CreateOnlyBackupObjectStore?,
        accountBindingDigest: ByteArray?,
    ): RecoveryResult

    /** Creates the exact reserved successor claim and, only if it wins, activates. */
    suspend fun confirmTakeover(
        operationId: String,
        objectStore: CreateOnlyBackupObjectStore,
    ): RecoveryResult
}

interface CreateOnlyBackupObjectStore {
    suspend fun generateProviderIds(
        count: Int,
        role: RemoteObjectRoleV1,
    ): List<ProviderObjectId>
    suspend fun createSmallIfAbsent(
        providerObjectId: ProviderObjectId,
        lineageId: CloudLineageId,
        metadata: RemoteListedObject,
        bytes: OwnedRemoteBytes,
    ): CreateSmallResult
    suspend fun readSmall(
        providerObjectId: ProviderObjectId,
        maximumBytes: Long,
    ): ReadSmallResult
    suspend fun list(request: RemoteListRequest): RemoteListPage
    suspend fun uploadImmutable(
        request: ImmutableUploadRequest,
    ): ImmutableUploadResult
    suspend fun downloadImmutable(
        providerObjectId: ProviderObjectId,
        maximumBytes: Long,
        expectedSha256: Sha256Digest,
    ): ImmutableDownloadResult
    suspend fun delete(
        providerObjectId: ProviderObjectId,
    ): DeleteObjectResult
}
