package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.CreateSmallResult
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.ReadSmallResult
import app.opentasks.core.domain.RemoteListRequest
import app.opentasks.core.domain.RemoteListedObject
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.WriterEpoch

/**
 * One bounded publication candidate paired with the exact provider object it
 * was read from.
 *
 * [PublicationBootstrapV1] carries recovery material, not provider identity,
 * so the provider object ID must travel beside it for resolution to download
 * and authenticate the complete file at its exact identity.
 */
data class PublicationCandidate(
    val providerObjectId: ProviderObjectId,
    val bootstrap: PublicationBootstrapV1,
)

sealed interface PublicationCandidateDiscovery {
    data class Discovered(val candidates: List<PublicationCandidate>) :
        PublicationCandidateDiscovery

    data class Blocked(val reason: RemoteBackupFailureCategory) : PublicationCandidateDiscovery
}

sealed interface PublicationResolution {
    data class Resolved(
        val current: VerifiedPublication,
        val previous: VerifiedPublication?,
    ) : PublicationResolution

    data class Failed(val reason: RemoteBackupFailureCategory) : PublicationResolution
}

sealed interface PublicationCreateResult {
    data class Created(val publication: VerifiedPublication) : PublicationCreateResult
    data class OccupiedByExpected(val publication: VerifiedPublication) : PublicationCreateResult
    data object OccupiedByDifferent : PublicationCreateResult
    data class Failed(val reason: RemoteBackupFailureCategory) : PublicationCreateResult
}

interface PublicationCatalog {
    suspend fun discoverBootstraps(
        lineageId: CloudLineageId,
        epoch: WriterEpoch,
        plannedOrActualClaimProviderId: ProviderObjectId,
    ): PublicationCandidateDiscovery

    suspend fun resolve(
        ownership: VerifiedOwnershipClaim,
        candidates: List<PublicationCandidate>,
        contentKey: VaultKey,
    ): PublicationResolution

    suspend fun create(
        providerObjectId: ProviderObjectId,
        encodedPublication: OwnedRemoteBytes,
        contentKey: VaultKey,
    ): PublicationCreateResult
}

/**
 * Bounded publication authentication and unique-tip resolution.
 *
 * Resolution rejects candidates bound to another lineage, epoch, device, or
 * ownership claim, authenticates every remaining candidate, and then fails
 * closed on any duplicate sequence, fork, gap, missing retained predecessor,
 * generation regression, or competing highest publication. Older candidates
 * are bounded orphans and can never outrank the retained pair.
 *
 * As in [DefaultOwnershipChainStore], a [CreateOnlyBackupObjectStore.list]
 * failure is caught and translated into
 * [PublicationCandidateDiscovery.Blocked]; nothing escapes as control flow.
 */
class DefaultPublicationCatalog(
    private val objectStore: CreateOnlyBackupObjectStore,
    private val codec: PublicationCodec,
) : PublicationCatalog {

    override suspend fun discoverBootstraps(
        lineageId: CloudLineageId,
        epoch: WriterEpoch,
        plannedOrActualClaimProviderId: ProviderObjectId,
    ): PublicationCandidateDiscovery {
        val listed = try {
            listAllBounded(
                objectStore,
                RemoteListRequest(
                    lineageId = lineageId,
                    role = RemoteObjectRoleV1.PUBLICATION,
                    writerEpoch = epoch,
                    ownerDeviceId = null,
                    pageToken = null,
                    pageSize = PAGE_SIZE,
                ),
                maximum = MAX_CANDIDATES_PER_EPOCH,
            )
        } catch (failure: BoundedRemoteFailure) {
            return PublicationCandidateDiscovery.Blocked(failure.reason)
        }
        val candidates = mutableListOf<PublicationCandidate>()
        listed.forEach { listedObject ->
            val bytes = when (val read = readSmall(listedObject.providerObjectId)) {
                is ReadSmallResult.Found -> read.bytes
                ReadSmallResult.Missing -> return@forEach
                is ReadSmallResult.Failed ->
                    return PublicationCandidateDiscovery.Blocked(read.reason)
            }
            val bootstrap = bytes.useOwned { source ->
                runBounded { codec.readBootstrap(source) }
            } ?: return PublicationCandidateDiscovery.Blocked(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
            if (bootstrap.lineageId != lineageId.value || bootstrap.writerEpoch != epoch.value) {
                return@forEach
            }
            // A baseline names the claim it plans; a normal publication names
            // none publicly and is filtered by its authenticated manifest.
            val planned = bootstrap.plannedClaimProviderFileId
            if (planned != null && planned != plannedOrActualClaimProviderId.value) {
                return@forEach
            }
            candidates += PublicationCandidate(listedObject.providerObjectId, bootstrap)
        }
        return PublicationCandidateDiscovery.Discovered(candidates)
    }

    override suspend fun resolve(
        ownership: VerifiedOwnershipClaim,
        candidates: List<PublicationCandidate>,
        contentKey: VaultKey,
    ): PublicationResolution {
        if (candidates.isEmpty() || candidates.size > MAX_CANDIDATES_PER_EPOCH) {
            return PublicationResolution.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
        }
        val retained = mutableListOf<VerifiedPublication>()
        candidates.forEach { candidate ->
            val bytes = when (val read = readSmall(candidate.providerObjectId)) {
                is ReadSmallResult.Found -> read.bytes
                ReadSmallResult.Missing -> return@forEach
                is ReadSmallResult.Failed -> return PublicationResolution.Failed(read.reason)
            }
            val verified = bytes.useOwned { source ->
                runBounded { codec.verify(source, contentKey) }
            } ?: return PublicationResolution.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
            if (verified.manifest.publicationProviderFileId !=
                candidate.providerObjectId.value
            ) {
                return PublicationResolution.Failed(
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
            }
            if (boundToClaim(verified, ownership)) retained += verified
        }
        if (retained.isEmpty()) {
            return PublicationResolution.Failed(
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            )
        }
        val sequences = retained.map { it.manifest.publicationSequence }
        if (sequences.size != sequences.toSet().size) {
            return PublicationResolution.Failed(
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            )
        }
        val predecessors = retained.mapNotNull { it.manifest.predecessorPublicationId }
        if (predecessors.size != predecessors.toSet().size) {
            return PublicationResolution.Failed(
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            )
        }
        val current = retained.maxBy { it.manifest.publicationSequence }
        val previous = if (current.manifest.baseline) {
            null
        } else {
            retained.firstOrNull {
                it.manifest.publicationSequence == current.manifest.publicationSequence - 1
            } ?: return PublicationResolution.Failed(
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            )
        }
        runBounded {
            codec.requireRetainedPair(current, previous, ownership)
        } ?: return PublicationResolution.Failed(
            RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
        )
        return PublicationResolution.Resolved(current, previous)
    }

    override suspend fun create(
        providerObjectId: ProviderObjectId,
        encodedPublication: OwnedRemoteBytes,
        contentKey: VaultKey,
    ): PublicationCreateResult {
        val candidate = encodedPublication.take()
        try {
            val verified = runBounded { codec.verify(candidate, contentKey) }
                ?: return PublicationCreateResult.Failed(
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
            if (verified.manifest.publicationProviderFileId != providerObjectId.value) {
                return PublicationCreateResult.Failed(
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
            }
            val lineageId = runBounded { CloudLineageId.parse(verified.manifest.lineageId) }
                ?: return PublicationCreateResult.Failed(
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
            val ownerDeviceId = runBounded {
                CloudDeviceId.parse(verified.manifest.activeDeviceId)
            } ?: return PublicationCreateResult.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
            return when (
                val created = objectStore.createSmallIfAbsent(
                    providerObjectId = providerObjectId,
                    lineageId = lineageId,
                    metadata = RemoteListedObject(
                        providerObjectId = providerObjectId,
                        // The publication's logical identity lives only inside
                        // its authenticated frame and is never published as
                        // provider metadata.
                        logicalObjectId = null,
                        role = RemoteObjectRoleV1.PUBLICATION,
                        writerEpoch = WriterEpoch(verified.manifest.writerEpoch),
                        ownerDeviceId = ownerDeviceId,
                    ),
                    bytes = ownedCopyOf(candidate),
                )
            ) {
                CreateSmallResult.Created -> PublicationCreateResult.Created(verified)
                CreateSmallResult.AlreadyExists, CreateSmallResult.Ambiguous ->
                    resolveOccupied(providerObjectId, candidate, verified)

                is CreateSmallResult.Failed -> PublicationCreateResult.Failed(created.reason)
            }
        } finally {
            candidate.fill(0)
            encodedPublication.close()
        }
    }

    private suspend fun resolveOccupied(
        providerObjectId: ProviderObjectId,
        candidate: ByteArray,
        verified: VerifiedPublication,
    ): PublicationCreateResult {
        val occupantBytes = when (val read = readSmall(providerObjectId)) {
            is ReadSmallResult.Found -> read.bytes
            ReadSmallResult.Missing -> return PublicationCreateResult.Failed(
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            )

            is ReadSmallResult.Failed -> return PublicationCreateResult.Failed(read.reason)
        }
        return occupantBytes.useOwned { occupant ->
            if (occupant.contentEquals(candidate)) {
                PublicationCreateResult.OccupiedByExpected(verified)
            } else {
                PublicationCreateResult.OccupiedByDifferent
            }
        }
    }

    /**
     * Rejects a candidate bound to another lineage, epoch, device, or
     * ownership claim. Exact digest agreement is left to
     * [PublicationCodec.requireRetainedPair] so a same-claim duplicate is
     * reported as ambiguity rather than silently discarded.
     */
    private fun boundToClaim(
        publication: VerifiedPublication,
        ownership: VerifiedOwnershipClaim,
    ): Boolean {
        val manifest = publication.manifest
        val claim = ownership.claim
        if (manifest.lineageId != claim.lineageId) return false
        if (manifest.writerEpoch != claim.writerEpoch) return false
        if (manifest.activeDeviceId != claim.activeDeviceId) return false
        if (manifest.sourceVaultId != claim.sourceVaultId) return false
        return if (manifest.baseline) {
            manifest.plannedClaimProviderFileId == claim.providerFileId &&
                manifest.plannedClaimId == claim.claimId
        } else {
            manifest.ownershipClaimProviderFileId == claim.providerFileId &&
                manifest.ownershipClaimId == claim.claimId
        }
    }

    private suspend fun readSmall(providerObjectId: ProviderObjectId): ReadSmallResult =
        readSmallBounded(objectStore, providerObjectId, MAX_PUBLICATION_FILE_BYTES)

    companion object {
        const val MAX_CANDIDATES_PER_EPOCH = 128
        const val PAGE_SIZE = 100
        val MAX_PUBLICATION_FILE_BYTES: Long = REMOTE_FILE_LENGTH_PREFIX_BYTES +
            PublicationCodec.MAX_BOOTSTRAP_BYTES +
            PublicationCodec.MAX_FRAME_BYTES
    }
}
