package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.domain.AttachmentBlobStore
import app.opentasks.core.domain.AttachmentListedObject
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.ProviderObjectId
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException

data class AttachmentGcResult(
    val deletedObjects: Int,
    val stoppedForOwnershipChange: Boolean,
    val blockers: Int,
)

data class GcCandidate(
    val blobSetId: BlobSetId,
    val deletedAt: Instant,
    val tombstoneGeneration: Long,
    val coveredByCurrentBase: Boolean,
    val coveredByPreviousBase: Boolean,
    val activelyReferenced: Boolean,
)

class AttachmentGarbageCollector(
    private val chainStore: OwnershipChainStore,
    private val rootClaimProviderId: ProviderObjectId,
    private val contentKey: () -> VaultKey,
    private val minimumRetention: Duration = Duration.ofDays(30),
    private val maximumDeletesPerBatch: Int = 32,
) {
    init {
        require(!minimumRetention.isNegative) { "Attachment retention is negative" }
        require(maximumDeletesPerBatch > 0) { "Attachment delete budget must be positive" }
    }

    suspend fun runBatch(
        store: AttachmentBlobStore,
        candidates: List<GcCandidate>,
        now: Instant,
    ): AttachmentGcResult {
        val key = contentKey()
        val expected = activeTip(key) ?: return STOPPED_FOR_OWNERSHIP_CHANGE
        val candidatesByBlobSet = candidates.groupBy { it.blobSetId.value }
        val eligibleBlobSets = candidatesByBlobSet
            .filterValues { values -> values.size == 1 && eligible(values.single(), now) }
            .keys
        var deleted = 0
        var blockers = 0
        for (role in listOf(CHUNK_ROLE, MANIFEST_ROLE)) {
            val scan = scanRole(
                store = store,
                role = role,
                candidatesByBlobSet = candidatesByBlobSet,
                eligibleBlobSets = eligibleBlobSets,
                maximumTargets = maximumDeletesPerBatch - deleted,
                countBlockers = role == CHUNK_ROLE,
            ) ?: return AttachmentGcResult(deleted, false, blockers + 1)
            blockers += scan.blockers
            if (role == MANIFEST_ROLE && scan.eligibleChunkFound) break
            if (scan.targets.isEmpty()) continue
            val current = activeTip(key)
            if (current?.completeSha256 != expected.completeSha256) {
                return AttachmentGcResult(deleted, true, blockers)
            }
            for (remote in scan.targets) {
                val removed = try {
                    store.delete(remote.providerObjectId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    false
                }
                if (!removed) {
                    return AttachmentGcResult(deleted, false, blockers + 1)
                }
                deleted += 1
            }
            if (deleted == maximumDeletesPerBatch) break
        }
        return AttachmentGcResult(deleted, false, blockers)
    }

    private suspend fun activeTip(key: VaultKey): VerifiedOwnershipClaim? = try {
        (chainStore.resolve(rootClaimProviderId, key) as? OwnershipResolution.Active)?.tip
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    private suspend fun scanRole(
        store: AttachmentBlobStore,
        role: String,
        candidatesByBlobSet: Map<String, List<GcCandidate>>,
        eligibleBlobSets: Set<String>,
        maximumTargets: Int,
        countBlockers: Boolean,
    ): RoleScan? {
        val targets = ArrayList<AttachmentListedObject>(maximumTargets)
        val seenTokens = mutableSetOf<String>()
        var token: String? = null
        var blockers = 0
        var eligibleChunkFound = false
        do {
            val page = try {
                store.listNamespace(token)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return null
            }
            if (page.first.size > MAX_PAGE_SIZE) return null
            for (remote in page.first) {
                val understoodRole = remote.role == CHUNK_ROLE || remote.role == MANIFEST_ROLE
                val associated = remote.blobSetId in candidatesByBlobSet
                if (!understoodRole || !associated) {
                    if (countBlockers) blockers += 1
                    continue
                }
                val eligible = remote.blobSetId in eligibleBlobSets
                if (eligible && remote.role == CHUNK_ROLE) eligibleChunkFound = true
                if (eligible && remote.role == role && targets.size < maximumTargets) {
                    targets += remote
                }
            }
            token = page.second
            if (token != null && !seenTokens.add(token)) return null
        } while (token != null)
        return RoleScan(targets, blockers, eligibleChunkFound)
    }

    private fun eligible(candidate: GcCandidate, now: Instant): Boolean =
        !candidate.activelyReferenced &&
            candidate.coveredByCurrentBase &&
            candidate.coveredByPreviousBase &&
            try {
                !now.isBefore(candidate.deletedAt.plus(minimumRetention))
            } catch (_: DateTimeException) {
                false
            }

    private companion object {
        const val CHUNK_ROLE = "attachment-chunk"
        const val MANIFEST_ROLE = "attachment-manifest"
        const val MAX_PAGE_SIZE = 100
        val STOPPED_FOR_OWNERSHIP_CHANGE = AttachmentGcResult(0, true, 0)
    }

    private data class RoleScan(
        val targets: List<AttachmentListedObject>,
        val blockers: Int,
        val eligibleChunkFound: Boolean,
    )
}
