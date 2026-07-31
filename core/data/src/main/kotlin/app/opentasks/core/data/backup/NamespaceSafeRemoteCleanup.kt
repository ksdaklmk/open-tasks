package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.DeleteObjectResult
import app.opentasks.core.domain.RemoteBackupObject
import app.opentasks.core.domain.RemoteListRequest
import app.opentasks.core.domain.RemoteListedObject
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteObjectRoleV1
import java.time.Duration
import java.time.Instant

/**
 * What one bounded cleanup batch did.
 *
 * [blockers] counts objects this installation refused to delete because it
 * could not prove they were safe to remove. They remain untouched; a blocker
 * is a reason to stop, never a reason to guess.
 */
data class CleanupBatchResult(
    val deletedCount: Int,
    val stoppedForOwnershipChange: Boolean,
    val blockers: Int,
)

interface NamespaceSafeRemoteCleanup {
    suspend fun runBatch(
        ownership: VerifiedOwnershipClaim,
        current: VerifiedPublication,
        previous: VerifiedPublication?,
        now: Instant,
    ): CleanupBatchResult
}

/**
 * Bounded retention pruning inside one authenticated ownership epoch.
 *
 * Every batch re-resolves and re-authenticates the ownership tip immediately
 * before it deletes anything and requires it to be exactly the claim the
 * caller published under; a changed tip stops the batch with nothing deleted.
 * The retained set is built from the *authenticated* publications rather than
 * from local cache, so an object is deleted only after it has been proven
 * absent from both retained publications.
 *
 * Age comes from locally persisted first-observed time — the transfer-state
 * row this installation wrote before the object's first network mutation —
 * and never from provider metadata, which another writer controls. An object
 * with no local record cannot be aged and is therefore a blocker.
 *
 * Ownership claims are never listed and never deleted: the chain is permanent
 * creation evidence, and a claim keeps naming and digesting its epoch baseline
 * long after that baseline file has been pruned.
 */
class DefaultNamespaceSafeRemoteCleanup(
    private val objectStore: CreateOnlyBackupObjectStore,
    private val chainStore: OwnershipChainStore,
    private val transferStore: RemoteBackupTransferStore,
    private val lineageId: CloudLineageId,
    private val rootClaimProviderId: ProviderObjectId,
    private val contentKey: VaultKey,
    private val minimumAge: Duration = MINIMUM_RESIDUE_AGE,
    private val maximumDeletesPerBatch: Int = MAX_DELETES_PER_BATCH,
) : NamespaceSafeRemoteCleanup {

    override suspend fun runBatch(
        ownership: VerifiedOwnershipClaim,
        current: VerifiedPublication,
        previous: VerifiedPublication?,
        now: Instant,
    ): CleanupBatchResult {
        if (!holdsExpectedTip(ownership)) return STOPPED_FOR_OWNERSHIP_CHANGE
        val retained = retainedProviderFileIds(current, previous)
        val listed = try {
            listCandidates()
        } catch (_: BoundedRemoteFailure) {
            return BLOCKED_INDEX
        }
        val locallyKnown = transferStore.objectsForLineage(lineageId)
            .associateBy { it.providerObjectId }
        val epoch = ownership.claim.writerEpoch
        val selfContained = isEpochSelfContained(epoch, current, previous, listed, retained)

        var deleted = 0
        var blockers = 0
        for (candidate in listed) {
            if (deleted >= maximumDeletesPerBatch) break
            if (candidate.providerObjectId.value in retained) continue
            val local = locallyKnown[candidate.providerObjectId]
            if (!isDeletable(candidate, local, ownership, selfContained)) {
                blockers += 1
                continue
            }
            checkNotNull(local)
            // Provable, but not yet old enough to count as abandoned residue.
            if (now.isBefore(local.createdAt.plus(minimumAge))) continue
            when (objectStore.delete(candidate.providerObjectId)) {
                DeleteObjectResult.Deleted -> {
                    deleted += 1
                    transferStore.removeObjectState(lineageId, local.logicalObjectId)
                }

                DeleteObjectResult.Missing ->
                    transferStore.removeObjectState(lineageId, local.logicalObjectId)

                is DeleteObjectResult.Failed -> return CleanupBatchResult(
                    deletedCount = deleted,
                    stoppedForOwnershipChange = false,
                    blockers = blockers + 1,
                )
            }
        }
        return CleanupBatchResult(
            deletedCount = deleted,
            stoppedForOwnershipChange = false,
            blockers = blockers,
        )
    }

    /**
     * Resolves the chain from the persisted root and requires the tip to be
     * byte-identical to the claim the caller published under. Anything else —
     * a newer claim, a tombstone, or an unreadable chain — stops the batch.
     */
    private suspend fun holdsExpectedTip(ownership: VerifiedOwnershipClaim): Boolean =
        when (val resolution = chainStore.resolve(rootClaimProviderId, contentKey)) {
            is OwnershipResolution.Active ->
                resolution.tip.completeSha256 == ownership.completeSha256
            is OwnershipResolution.Terminated -> false
            is OwnershipResolution.Blocked -> false
        }

    /**
     * Both retained publications and every object either of them names. An
     * object still referenced by the retained predecessor is required for the
     * fallback recovery path and is never a deletion candidate.
     */
    private fun retainedProviderFileIds(
        current: VerifiedPublication,
        previous: VerifiedPublication?,
    ): Set<String> = buildSet {
        listOfNotNull(current, previous).forEach { publication ->
            add(publication.manifest.publicationProviderFileId)
            publication.manifest.inventory.forEach { add(it.providerFileId) }
        }
    }

    /**
     * A current epoch is self-contained when nothing it retains belongs to an
     * older epoch. Only then may superseded-epoch residue be removed, because
     * only then is no retained object reachable through that older epoch.
     */
    private fun isEpochSelfContained(
        epoch: Long,
        current: VerifiedPublication,
        previous: VerifiedPublication?,
        listed: List<RemoteListedObject>,
        retained: Set<String>,
    ): Boolean {
        if (current.manifest.writerEpoch != epoch) return false
        if (previous != null && previous.manifest.writerEpoch != epoch) return false
        return listed
            .filter { it.providerObjectId.value in retained }
            .all { it.writerEpoch?.value == epoch }
    }

    /**
     * Only an object this installation can fully account for is deletable:
     * complete index metadata, this lineage, this device, and either the
     * current epoch or a superseded one under a self-contained current epoch.
     */
    private fun isDeletable(
        candidate: RemoteListedObject,
        local: RemoteBackupObject?,
        ownership: VerifiedOwnershipClaim,
        selfContained: Boolean,
    ): Boolean {
        val role = candidate.role ?: return false
        if (role !in DELETABLE_ROLES) return false
        val epoch = candidate.writerEpoch?.value ?: return false
        val ownerDeviceId = candidate.ownerDeviceId ?: return false
        if (ownerDeviceId.value != ownership.claim.activeDeviceId) return false
        val currentEpoch = ownership.claim.writerEpoch
        if (epoch > currentEpoch) return false
        if (epoch < currentEpoch && !selfContained) return false
        if (local == null) return false
        return local.lineageId == lineageId
    }

    private suspend fun listCandidates(): List<RemoteListedObject> =
        DELETABLE_ROLES.flatMap { role ->
            listAllBounded(
                objectStore,
                RemoteListRequest(
                    lineageId = lineageId,
                    role = role,
                    writerEpoch = null,
                    ownerDeviceId = null,
                    pageToken = null,
                    pageSize = PAGE_SIZE,
                ),
                maximum = MAX_CANDIDATES_PER_ROLE,
            )
        }

    private companion object {
        /**
         * Residue must survive this long by locally persisted first-observed
         * time before automatic cleanup may remove it, so an interrupted
         * upload or a slow peer is never mistaken for an abandoned object.
         */
        val MINIMUM_RESIDUE_AGE: Duration = Duration.ofDays(7)
        const val MAX_DELETES_PER_BATCH = 32
        const val MAX_CANDIDATES_PER_ROLE = 4_096
        const val PAGE_SIZE = 100

        val DELETABLE_ROLES = listOf(
            RemoteObjectRoleV1.PUBLICATION,
            RemoteObjectRoleV1.SNAPSHOT,
            RemoteObjectRoleV1.SEGMENT,
        )

        val STOPPED_FOR_OWNERSHIP_CHANGE = CleanupBatchResult(
            deletedCount = 0,
            stoppedForOwnershipChange = true,
            blockers = 0,
        )

        val BLOCKED_INDEX = CleanupBatchResult(
            deletedCount = 0,
            stoppedForOwnershipChange = false,
            blockers = 1,
        )
    }
}
