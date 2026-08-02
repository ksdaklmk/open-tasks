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
        val listed = listAll(store) ?: return BLOCKED_LISTING
        val candidatesByBlobSet = candidates.groupBy { it.blobSetId.value }
        val eligibleBlobSets = candidatesByBlobSet
            .filterValues { values -> values.size == 1 && eligible(values.single(), now) }
            .keys
        var blockers = 0
        val deletable = listed.filter { remote ->
            val understoodRole = remote.role == CHUNK_ROLE || remote.role == MANIFEST_ROLE
            val associated = remote.blobSetId in candidatesByBlobSet
            if (!understoodRole || !associated) {
                blockers += 1
                false
            } else {
                remote.blobSetId in eligibleBlobSets
            }
        }.sortedBy { if (it.role == CHUNK_ROLE) 0 else 1 }

        if (deletable.isEmpty()) return AttachmentGcResult(0, false, blockers)
        val current = activeTip(key)
        if (current?.completeSha256 != expected.completeSha256) {
            return STOPPED_FOR_OWNERSHIP_CHANGE
        }

        var deleted = 0
        for (remote in deletable) {
            if (deleted == maximumDeletesPerBatch) break
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
        return AttachmentGcResult(deleted, false, blockers)
    }

    private suspend fun activeTip(key: VaultKey): VerifiedOwnershipClaim? = try {
        (chainStore.resolve(rootClaimProviderId, key) as? OwnershipResolution.Active)?.tip
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    private suspend fun listAll(store: AttachmentBlobStore): List<AttachmentListedObject>? {
        val listed = mutableListOf<AttachmentListedObject>()
        val seenTokens = mutableSetOf<String>()
        var token: String? = null
        do {
            val page = try {
                store.listNamespace(token)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return null
            }
            if (listed.size + page.first.size > MAX_LISTED_OBJECTS) return null
            listed += page.first
            token = page.second
            if (token != null && !seenTokens.add(token)) return null
        } while (token != null)
        return listed
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
        const val MAX_LISTED_OBJECTS = 4_096
        val STOPPED_FOR_OWNERSHIP_CHANGE = AttachmentGcResult(0, true, 0)
        val BLOCKED_LISTING = AttachmentGcResult(0, false, 1)
    }
}
