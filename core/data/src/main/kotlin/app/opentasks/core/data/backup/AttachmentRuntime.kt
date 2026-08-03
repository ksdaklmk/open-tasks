package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.db.AttachmentTransferDao
import app.opentasks.core.domain.AttachmentBlobStore
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.VaultId
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * One authorized provider session's attachment collaborators.
 *
 * The blob store addresses the attachment namespace of exactly one lineage;
 * the chain store is how that lineage's ownership is authenticated. Closing
 * the session releases the provider handle both were built around.
 */
class AttachmentProviderSession(
    val blobStore: AttachmentBlobStore,
    val ownershipChainStore: OwnershipChainStore,
    private val onClose: () -> Unit,
) : AutoCloseable {
    override fun close() = onClose()
}

sealed interface AttachmentSessionResult {
    data class Opened(val session: AttachmentProviderSession) : AttachmentSessionResult

    /** No session exists to open; only a bounded category is ever reported. */
    data class Unavailable(val reason: RemoteBackupFailureCategory) : AttachmentSessionResult
}

/**
 * Every attachment operation one open vault slot is allowed to perform.
 *
 * Attachment bytes exist only inside the lineage that created them, so no
 * collaborator here is bound to a lineage at construction: each operation
 * reads the active configuration first and builds its store, coordinators, and
 * collector around *that* lineage. A vault whose slot has been replaced, whose
 * lineage is not active, or whose persisted ownership claim is no longer the
 * authenticated tip of the chain is refused before a single provider object is
 * listed, read, created, or deleted.
 *
 * A refusal never touches structured metadata. A disconnected, superseded, or
 * separately preserved vault keeps every attachment record it has — only the
 * bytes become unreachable, and cached bytes go away only when eviction is
 * asked for.
 */
class AttachmentRuntime(
    private val vaultId: VaultId,
    private val repository: VaultRepository,
    private val remoteStateStore: RemoteBackupStateStore,
    private val transferDao: AttachmentTransferDao,
    private val journalDao: BackupJournalDao,
    private val codec: AuthenticatedCloudObjectCodec,
    private val manifestCodec: AttachmentBlobSetManifestCodec,
    private val cache: AttachmentCacheStore,
    private val contentKeyStore: VaultContentKeyStore,
    private val openSession: suspend (RemoteBackupConfiguration) -> AttachmentSessionResult,
    private val minimumRetention: Duration = Duration.ofDays(30),
    private val now: () -> Instant = Instant::now,
) {
    private val stopped = AtomicBoolean()

    /** Refuses every later operation; the slot this runtime was built for is gone. */
    fun stop() {
        stopped.set(true)
    }

    /** The ciphertext frames this installation is currently holding on to. */
    fun cacheUsageBytes(): Long = cache.usageBytes()

    /** Drops one blob set's cached frames; nothing else ever evicts on its behalf. */
    fun evictCachedBytes(blobSetId: BlobSetId) = cache.evict(blobSetId)

    suspend fun intake(
        taskId: TaskId,
        displayName: String,
        mimeType: String,
        source: AttachmentSource,
    ): AttachmentIntakeResult {
        val configuration = activeConfiguration()
            ?: return AttachmentIntakeResult.OwnershipUnavailable
        val key = openContentKey() ?: return AttachmentIntakeResult.OwnershipUnavailable
        return try {
            when (val opened = session(configuration)) {
                is AttachmentSessionResult.Unavailable ->
                    AttachmentIntakeResult.Failed(opened.reason)

                is AttachmentSessionResult.Opened -> opened.session.use { session ->
                    // Intake generates provider identities before it consults
                    // ownership again, so the tip is proved here rather than
                    // reserving names in a namespace this vault has lost.
                    if (!holdsExpectedTip(session, configuration, key)) {
                        AttachmentIntakeResult.OwnershipUnavailable
                    } else {
                        coordinator(configuration, session, key).intake(
                            store = session.blobStore,
                            taskId = taskId,
                            displayName = displayName,
                            mimeType = mimeType,
                            source = source,
                        )
                    }
                }
            }
        } finally {
            key.close()
        }
    }

    /**
     * Streams one attachment's plaintext to [destination].
     *
     * Reading is not a claim of ownership: an installation that still holds the
     * lineage may read even while another has taken over publication. What it
     * may not do is read across lineages, which is why the store is always the
     * active lineage's namespace and a manifest that is not there is absence.
     */
    suspend fun open(attachment: Attachment, destination: OutputStream): AttachmentOpenResult {
        val configuration = activeConfiguration() ?: return AttachmentOpenResult.Unavailable
        val key = openContentKey() ?: return AttachmentOpenResult.Unavailable
        return try {
            when (val opened = session(configuration)) {
                is AttachmentSessionResult.Unavailable ->
                    AttachmentOpenResult.Failed(opened.reason)

                is AttachmentSessionResult.Opened -> opened.session.use { session ->
                    AttachmentOpenCoordinator(
                        cache = cache,
                        manifestCodec = manifestCodec,
                        codec = codec,
                        lineageId = configuration.lineageId,
                        contentKey = { key },
                    ).open(session.blobStore, attachment, destination)
                }
            }
        } finally {
            key.close()
        }
    }

    /**
     * Abandons intake sessions this slot never finished.
     *
     * The coordinator authenticates ownership before it deletes anything, so
     * no tip check is repeated here; what this adds is refusing to authorize at
     * all when there is no unfinished session to expire.
     */
    suspend fun expireStaleSessions(): Int {
        val configuration = activeConfiguration() ?: return 0
        if (!hasUnfinishedSessions()) return 0
        val key = openContentKey() ?: return 0
        return try {
            val opened = session(configuration) as? AttachmentSessionResult.Opened ?: return 0
            opened.session.use { session ->
                coordinator(configuration, session, key)
                    .expireStaleSessions(session.blobStore)
            }
        } finally {
            key.close()
        }
    }

    /**
     * Collects the bytes of blob sets whose tombstone every retained
     * recoverable base already contains.
     *
     * Eligibility is decided entirely from local state first, so a vault with
     * nothing collectable — the ordinary case, including the whole retention
     * window — authorizes nothing, resolves no ownership chain, and lists no
     * namespace. A chain tip this installation no longer holds then stops the
     * pass before the namespace is listed, and what the batch proves it
     * released is recorded so the same records are never offered again.
     */
    suspend fun collectRetiredBytes(): AttachmentGcResult {
        val configuration = activeConfiguration() ?: return NOTHING_COLLECTED
        val retired = collectable(configuration)
        if (retired.isEmpty) return NOTHING_COLLECTED
        val key = openContentKey() ?: return NOTHING_COLLECTED
        val result = try {
            val opened = session(configuration) as? AttachmentSessionResult.Opened
                ?: return NOTHING_COLLECTED
            opened.session.use { session ->
                if (!holdsExpectedTip(session, configuration, key)) {
                    STOPPED_FOR_OWNERSHIP_CHANGE
                } else {
                    AttachmentGarbageCollector(
                        chainStore = session.ownershipChainStore,
                        rootClaimProviderId = configuration.rootClaimProviderId,
                        contentKey = { key },
                        minimumRetention = minimumRetention,
                    ).runBatch(session.blobStore, retired.candidates, now())
                }
            }
        } finally {
            key.close()
        }
        recordCollected(retired.attachmentCandidates, result.collectedBlobSets)
        recordRetiredRowsCollected(retired.retiredRowCandidates, result.collectedBlobSets)
        return result
    }

    /**
     * Records that the lineage holds no attachment content at all.
     *
     * The destructive attachment action removes the whole namespace, so
     * afterwards no record's bytes exist — including those of records nothing
     * has retired, and every durable `retired_blob_sets` row a purge left
     * behind. Recording both is what keeps every one of them from being
     * offered for collection, and paying a provider round trip, for ever: a
     * retired row the action does not release here can never be released by
     * a later `collectRetiredBytes()` pass either, because that pass lists
     * the namespace to confirm a release, and the destructive action already
     * emptied it.
     */
    suspend fun recordAllContentCollected() {
        if (stopped.get()) return
        val workspace = try {
            repository.currentWorkspace()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return
        }
        workspace.attachments.forEach { attachment ->
            if (attachment.blobSetId == null) return@forEach
            try {
                repository.execute(
                    DomainCommand.MarkAttachmentContentCollected(attachment.id, now()),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // A later invocation records what this one could not.
            }
        }
        workspace.retiredBlobSets.forEach { retired ->
            try {
                repository.execute(
                    DomainCommand.MarkRetiredBlobSetCollected(retired.blobSetId, now()),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // A later invocation records what this one could not.
            }
        }
    }

    /**
     * Records that the lineage no longer holds a blob set's bytes.
     *
     * Without this a retired record stays a collection candidate for ever, and
     * every later verified publication would authorize, resolve the ownership
     * chain, and page the whole namespace to delete nothing. Clearing
     * `blobSetId` is also exactly what the open path already reads as "these
     * bytes are gone", so the marker states a fact the product understands
     * rather than inventing bookkeeping beside it.
     *
     * A write that fails leaves the record a candidate, which is the safe
     * direction: the pass is repeated, not skipped.
     */
    private suspend fun recordCollected(
        retired: List<RetiredBlobSet>,
        collectedBlobSets: Set<BlobSetId>,
    ) {
        retired.forEach { entry ->
            if (entry.candidate.blobSetId !in collectedBlobSets) return@forEach
            try {
                repository.execute(
                    DomainCommand.MarkAttachmentContentCollected(entry.attachmentId, now()),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // The bytes are gone either way; a later pass records it again.
            }
        }
    }

    /**
     * Records that the durable retired-blob-set index no longer needs to
     * track a blob set a purge retired: this batch proved the lineage holds
     * none of its bytes.
     *
     * The command is idempotent on an absent row (Task 2), so a repeated
     * write is safe. A write that fails here is not: the batch already
     * deleted every object this blob set had, so a later pass lists nothing
     * for it and never rebuilds `collectedBlobSets` to include it again —
     * a failed release strands the row as a candidate for ever, not just
     * until the next pass.
     */
    private suspend fun recordRetiredRowsCollected(
        retired: List<RetiredBlobSetRow>,
        collectedBlobSets: Set<BlobSetId>,
    ) {
        retired.forEach { entry ->
            if (entry.blobSetId !in collectedBlobSets) return@forEach
            try {
                repository.execute(
                    DomainCommand.MarkRetiredBlobSetCollected(entry.blobSetId, now()),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // The bytes are already gone; see the doc comment above for
                // why a failed write here is not self-healing.
            }
        }
    }

    /**
     * The active lineage this vault may act on, or null for every state that
     * must perform no attachment work at all: a replaced slot, no lineage, an
     * unreadable local state, and any lifecycle other than an active one.
     */
    private suspend fun activeConfiguration(): RemoteBackupConfiguration? {
        if (stopped.get()) return null
        val configuration = try {
            remoteStateStore.active(vaultId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return null
        }
        return configuration?.takeIf { it.lifecycle == RemoteBackupLifecycle.ACTIVE }
    }

    private fun openContentKey(): VaultKey? = try {
        contentKeyStore.openExisting(vaultId)
    } catch (_: Exception) {
        null
    }

    private suspend fun session(
        configuration: RemoteBackupConfiguration,
    ): AttachmentSessionResult = try {
        openSession(configuration)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        AttachmentSessionResult.Unavailable(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
    }

    /**
     * Resolves the chain from the persisted root and requires the tip to be
     * the claim this installation published under. A newer claim, a tombstone,
     * or an unreadable chain all mean this vault no longer writes here.
     */
    private suspend fun holdsExpectedTip(
        session: AttachmentProviderSession,
        configuration: RemoteBackupConfiguration,
        key: VaultKey,
    ): Boolean {
        val expected = configuration.ownershipClaim ?: return false
        return try {
            when (
                val resolution = session.ownershipChainStore.resolve(
                    configuration.rootClaimProviderId,
                    key,
                )
            ) {
                is OwnershipResolution.Active -> resolution.tip.completeSha256 == expected.sha256
                is OwnershipResolution.Terminated -> false
                is OwnershipResolution.Blocked -> false
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
    }

    private fun coordinator(
        configuration: RemoteBackupConfiguration,
        session: AttachmentProviderSession,
        key: VaultKey,
    ) = AttachmentBlobCoordinator(
        repository = repository,
        transferDao = transferDao,
        codec = codec,
        manifestCodec = manifestCodec,
        lineageId = configuration.lineageId,
        contentKey = { key },
        holdsOwnership = { holdsExpectedTip(session, configuration, key) },
        now = now,
    )

    private suspend fun hasUnfinishedSessions(): Boolean = try {
        transferDao.pending().isNotEmpty()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }

    /**
     * The retired records whose bytes may be released right now, from both
     * retirement sources: an attachment row a soft delete tombstoned, and a
     * `retired_blob_sets` row a purge left behind once it removed the
     * attachment row outright.
     *
     * Coverage is decided by the generation each retained base publishes: a
     * tombstone older than both is present in every recoverable base, so
     * removing its bytes can no longer make a recovery incomplete. A lineage
     * that retains only one base is covered by that one. A blob set another
     * live record still names — a replacement edge — or one already recorded
     * as collected, is not offered at all.
     *
     * The final eligibility predicate is applied here rather than left to the
     * collector, because everything it needs is local: deciding it after
     * authorizing would make every publication pay a provider round trip
     * throughout the retention window and for every tombstone a retained base
     * does not yet cover.
     */
    private suspend fun collectable(
        configuration: RemoteBackupConfiguration,
    ): CollectableRetirements {
        val currentGeneration = configuration.currentPublication?.generation?.value
            ?: return CollectableRetirements.EMPTY
        val previousGeneration = configuration.previousPublication?.generation?.value
            ?: currentGeneration
        val workspace = try {
            repository.currentWorkspace()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return CollectableRetirements.EMPTY
        }
        val moment = now()
        val live = workspace.attachments.filter { it.deletedAt == null }
            .mapNotNull { it.blobSetId }
            .toSet()

        val attachmentCandidates = workspace.attachments.mapNotNull { attachment ->
            val blobSetId = attachment.blobSetId ?: return@mapNotNull null
            val deletedAt = attachment.deletedAt ?: return@mapNotNull null
            val tombstoneGeneration = tombstoneGeneration(
                objectType = BackupRecordFamily.ATTACHMENT.name,
                objectId = attachment.id.value,
            ) ?: return@mapNotNull null
            val candidate = GcCandidate(
                blobSetId = blobSetId,
                deletedAt = deletedAt,
                tombstoneGeneration = tombstoneGeneration,
                coveredByCurrentBase = tombstoneGeneration <= currentGeneration,
                coveredByPreviousBase = tombstoneGeneration <= previousGeneration,
                activelyReferenced = blobSetId in live,
            )
            if (!candidate.isCollectable(moment, minimumRetention)) return@mapNotNull null
            RetiredBlobSet(attachmentId = attachment.id, candidate = candidate)
        }

        val retiredRowCandidates = workspace.retiredBlobSets.mapNotNull { retired ->
            val tombstoneGeneration = tombstoneGeneration(
                objectType = BackupRecordFamily.RETIRED_BLOB_SET.name,
                objectId = retired.blobSetId.value,
            ) ?: return@mapNotNull null
            val candidate = GcCandidate(
                blobSetId = retired.blobSetId,
                deletedAt = retired.retiredAt,
                tombstoneGeneration = tombstoneGeneration,
                coveredByCurrentBase = tombstoneGeneration <= currentGeneration,
                coveredByPreviousBase = tombstoneGeneration <= previousGeneration,
                activelyReferenced = retired.blobSetId in live,
            )
            if (!candidate.isCollectable(moment, minimumRetention)) return@mapNotNull null
            RetiredBlobSetRow(blobSetId = retired.blobSetId, candidate = candidate)
        }

        return CollectableRetirements(attachmentCandidates, retiredRowCandidates)
    }

    private suspend fun tombstoneGeneration(objectType: String, objectId: String): Long? = try {
        journalDao.latestGenerationFor(objectType = objectType, objectId = objectId)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    /** A retired attachment row and the collection candidate its blob set makes. */
    private data class RetiredBlobSet(
        val attachmentId: AttachmentId,
        val candidate: GcCandidate,
    )

    /** A durable `retired_blob_sets` row and the collection candidate it makes. */
    private data class RetiredBlobSetRow(
        val blobSetId: BlobSetId,
        val candidate: GcCandidate,
    )

    /** Every retirement source's candidates, ready for one GC batch. */
    private data class CollectableRetirements(
        val attachmentCandidates: List<RetiredBlobSet>,
        val retiredRowCandidates: List<RetiredBlobSetRow>,
    ) {
        val candidates: List<GcCandidate>
            get() = attachmentCandidates.map { it.candidate } +
                retiredRowCandidates.map { it.candidate }

        val isEmpty: Boolean
            get() = attachmentCandidates.isEmpty() && retiredRowCandidates.isEmpty()

        companion object {
            val EMPTY = CollectableRetirements(emptyList(), emptyList())
        }
    }

    private companion object {
        val NOTHING_COLLECTED = AttachmentGcResult(
            deletedObjects = 0,
            stoppedForOwnershipChange = false,
            blockers = 0,
        )
        val STOPPED_FOR_OWNERSHIP_CHANGE = AttachmentGcResult(
            deletedObjects = 0,
            stoppedForOwnershipChange = true,
            blockers = 0,
        )
    }
}
