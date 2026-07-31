package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.backup.drive.DriveTransportException
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.CreateSmallResult
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.ReadSmallResult
import app.opentasks.core.domain.RemoteListRequest
import app.opentasks.core.domain.RemoteListedObject
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OwnershipStateV1
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.WriterEpoch
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * The bounded set of ownership roots one account holds.
 *
 * Discovery reads a provider index, so it must be able to fail closed: an
 * unreadable page, an unreadable root, or a namespace holding more roots than
 * the protocol accepts is never reported as "no backups exist".
 */
sealed interface OwnershipRootDiscovery {
    data class Discovered(val roots: List<OwnershipPublicHeaderV1>) : OwnershipRootDiscovery
    data class Blocked(val reason: RemoteBackupFailureCategory) : OwnershipRootDiscovery
}

sealed interface OwnershipResolution {
    data class Active(
        val root: VerifiedOwnershipClaim,
        val tip: VerifiedOwnershipClaim,
    ) : OwnershipResolution

    data class Terminated(
        val tombstone: VerifiedOwnershipClaim,
    ) : OwnershipResolution

    data class Blocked(
        val reason: RemoteBackupFailureCategory,
    ) : OwnershipResolution
}

sealed interface OwnershipClaimCreateResult {
    data class Won(val claim: VerifiedOwnershipClaim) : OwnershipClaimCreateResult
    data class Lost(val winner: VerifiedOwnershipClaim) : OwnershipClaimCreateResult
    data object AmbiguousRemoteState : OwnershipClaimCreateResult
    data class Failed(val reason: RemoteBackupFailureCategory) : OwnershipClaimCreateResult
}

interface OwnershipChainStore {
    suspend fun discoverPublicRoots(): OwnershipRootDiscovery

    suspend fun resolve(
        rootProviderId: ProviderObjectId,
        contentKey: VaultKey,
    ): OwnershipResolution

    suspend fun createClaim(
        expectedPredecessor: VerifiedOwnershipClaim?,
        encodedClaim: OwnedRemoteBytes,
        contentKey: VaultKey,
    ): OwnershipClaimCreateResult
}

/**
 * Bounded exact-successor traversal over immutable create-only claims.
 *
 * Navigation uses only the reserved successor provider ID a claim's public
 * header names; authority comes from [OwnershipClaimCodec], which requires
 * every public header to restate its authenticated claim exactly, so a
 * navigated tip and an authenticated tip cannot diverge. There is no search
 * by timestamp, name, list order, or highest epoch, and no alternate slot is
 * ever reserved when a reserved slot holds bytes that fail authentication:
 * that is ambiguity and it fails closed.
 *
 * Every provider outcome — including a [CreateOnlyBackupObjectStore.list]
 * failure, which the provider-backed store raises rather than returning —
 * becomes a bounded [OwnershipRootDiscovery.Blocked],
 * [OwnershipResolution.Blocked], or [OwnershipClaimCreateResult.Failed]
 * carrying only a redacted failure category.
 */
class DefaultOwnershipChainStore(
    private val objectStore: CreateOnlyBackupObjectStore,
    private val codec: OwnershipClaimCodec,
) : OwnershipChainStore {

    override suspend fun discoverPublicRoots(): OwnershipRootDiscovery {
        val listed = try {
            listAll(
                RemoteListRequest(
                    lineageId = null,
                    role = RemoteObjectRoleV1.OWNERSHIP_ROOT,
                    writerEpoch = null,
                    ownerDeviceId = null,
                    pageToken = null,
                    pageSize = PAGE_SIZE,
                ),
                maximum = MAX_OWNERSHIP_ROOTS,
            )
        } catch (failure: BoundedRemoteFailure) {
            return OwnershipRootDiscovery.Blocked(failure.reason)
        }
        val roots = mutableListOf<OwnershipPublicHeaderV1>()
        listed.forEach { candidate ->
            val bytes = when (val read = readSmall(candidate.providerObjectId)) {
                is ReadSmallResult.Found -> read.bytes
                ReadSmallResult.Missing -> return@forEach
                is ReadSmallResult.Failed -> return OwnershipRootDiscovery.Blocked(read.reason)
            }
            val header = bytes.useOwned { source ->
                runBounded {
                    codec.readPublicHeader(source)
                }
            } ?: return OwnershipRootDiscovery.Blocked(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
            if (header.role != RemoteObjectRoleV1.OWNERSHIP_ROOT ||
                header.providerFileId != candidate.providerObjectId.value
            ) {
                return OwnershipRootDiscovery.Blocked(
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
            }
            roots += header
        }
        return OwnershipRootDiscovery.Discovered(roots)
    }

    override suspend fun resolve(
        rootProviderId: ProviderObjectId,
        contentKey: VaultKey,
    ): OwnershipResolution {
        val rootBytes = when (val read = readSmall(rootProviderId)) {
            is ReadSmallResult.Found -> read.bytes
            ReadSmallResult.Missing -> return OwnershipResolution.Blocked(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )

            is ReadSmallResult.Failed -> return OwnershipResolution.Blocked(read.reason)
        }
        val root = rootBytes.useOwned { source ->
            runBounded {
                codec.verify(source, contentKey)
            }
        } ?: return OwnershipResolution.Blocked(
            RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
        )
        if (root.header.role != RemoteObjectRoleV1.OWNERSHIP_ROOT ||
            root.claim.providerFileId != rootProviderId.value
        ) {
            return OwnershipResolution.Blocked(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
        }

        var current = root
        var followed = 0
        while (true) {
            if (current.claim.state == OwnershipStateV1.TERMINATED) {
                return OwnershipResolution.Terminated(current)
            }
            if (current.claim.writerEpoch == Long.MAX_VALUE) {
                return OwnershipResolution.Blocked(
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
            }
            val reserved = current.claim.nextSuccessorProviderFileId
                ?: return OwnershipResolution.Active(root, current)
            if (followed >= MAX_CLAIMS_PER_LINEAGE) {
                return OwnershipResolution.Blocked(
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
            }
            val reservedId = runBounded {
                ProviderObjectId.of(reserved)
            } ?: return OwnershipResolution.Blocked(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
            val successorBytes = when (val read = readSmall(reservedId)) {
                is ReadSmallResult.Found -> read.bytes
                // An empty reserved slot is the end of the chain, never a
                // reason to look for another candidate.
                ReadSmallResult.Missing -> return OwnershipResolution.Active(root, current)
                is ReadSmallResult.Failed -> return OwnershipResolution.Blocked(read.reason)
            }
            val predecessor = current
            val successor = successorBytes.useOwned { source ->
                runBounded {
                    codec.verifySuccessor(predecessor, source, contentKey)
                }
            } ?: return OwnershipResolution.Blocked(
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            )
            current = successor
            followed += 1
        }
    }

    override suspend fun createClaim(
        expectedPredecessor: VerifiedOwnershipClaim?,
        encodedClaim: OwnedRemoteBytes,
        contentKey: VaultKey,
    ): OwnershipClaimCreateResult {
        val candidate = encodedClaim.take()
        try {
            // Epoch overflow fails closed before anything is verified or sent:
            // a maximum-epoch predecessor can never reserve a successor.
            if (expectedPredecessor != null &&
                expectedPredecessor.claim.writerEpoch == Long.MAX_VALUE
            ) {
                return OwnershipClaimCreateResult.Failed(
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
            }
            val verified = runBounded {
                if (expectedPredecessor == null) {
                    codec.verify(candidate, contentKey)
                } else {
                    codec.verifySuccessor(expectedPredecessor, candidate, contentKey)
                }
            } ?: return OwnershipClaimCreateResult.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
            val target = runBounded {
                ProviderObjectId.of(verified.claim.providerFileId)
            } ?: return OwnershipClaimCreateResult.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
            val lineageId = runBounded {
                CloudLineageId.parse(verified.claim.lineageId)
            } ?: return OwnershipClaimCreateResult.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
            return when (
                val created = objectStore.createSmallIfAbsent(
                    providerObjectId = target,
                    lineageId = lineageId,
                    metadata = RemoteListedObject(
                        providerObjectId = target,
                        logicalObjectId = verified.header.claimId,
                        role = verified.header.role,
                        writerEpoch = WriterEpoch(verified.header.writerEpoch),
                        ownerDeviceId = null,
                    ),
                    bytes = ownedCopyOf(candidate),
                )
            ) {
                CreateSmallResult.Created -> OwnershipClaimCreateResult.Won(verified)
                CreateSmallResult.AlreadyExists, CreateSmallResult.Ambiguous ->
                    resolveOccupiedSlot(
                        target = target,
                        candidate = candidate,
                        verified = verified,
                        expectedPredecessor = expectedPredecessor,
                        contentKey = contentKey,
                    )

                is CreateSmallResult.Failed -> OwnershipClaimCreateResult.Failed(created.reason)
            }
        } finally {
            candidate.fill(0)
            encodedClaim.close()
        }
    }

    /**
     * Reads the exact occupied slot and decides ownership from authenticated
     * bytes only. Identical bytes mean this claim already won and the create
     * is being resumed; different authenticated bytes mean another writer won;
     * bytes that fail authentication are ambiguity, never a reason to replace.
     */
    private suspend fun resolveOccupiedSlot(
        target: ProviderObjectId,
        candidate: ByteArray,
        verified: VerifiedOwnershipClaim,
        expectedPredecessor: VerifiedOwnershipClaim?,
        contentKey: VaultKey,
    ): OwnershipClaimCreateResult {
        val occupantBytes = when (val read = readSmall(target)) {
            is ReadSmallResult.Found -> read.bytes
            ReadSmallResult.Missing -> return OwnershipClaimCreateResult.AmbiguousRemoteState
            is ReadSmallResult.Failed -> return OwnershipClaimCreateResult.Failed(read.reason)
        }
        return occupantBytes.useOwned { occupant ->
            if (occupant.contentEquals(candidate)) return@useOwned OwnershipClaimCreateResult.Won(
                verified,
            )
            val winner = runBounded {
                if (expectedPredecessor == null) {
                    codec.verify(occupant, contentKey).also {
                        require(it.header.role == RemoteObjectRoleV1.OWNERSHIP_ROOT) {
                            "An ownership root slot holds another role"
                        }
                        require(it.claim.providerFileId == target.value) {
                            "An ownership root slot holds another provider identity"
                        }
                    }
                } else {
                    codec.verifySuccessor(expectedPredecessor, occupant, contentKey)
                }
            } ?: return@useOwned OwnershipClaimCreateResult.AmbiguousRemoteState
            OwnershipClaimCreateResult.Lost(winner)
        }
    }

    private suspend fun readSmall(providerObjectId: ProviderObjectId): ReadSmallResult = try {
        objectStore.readSmall(providerObjectId, MAX_CLAIM_FILE_BYTES)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        ReadSmallResult.Failed(failure.toBoundedReason())
    }

    /**
     * Pages a bounded provider index. [CreateOnlyBackupObjectStore.list] has no
     * failure result family, so a provider or local failure arrives as an
     * exception here and is translated into [BoundedRemoteFailure] rather than
     * escaping this class.
     */
    private suspend fun listAll(
        request: RemoteListRequest,
        maximum: Int,
    ): List<RemoteListedObject> {
        val accumulated = mutableListOf<RemoteListedObject>()
        var pageToken: String? = request.pageToken
        var pages = 0
        while (true) {
            val page = try {
                objectStore.list(request.copy(pageToken = pageToken))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                throw BoundedRemoteFailure(failure.toBoundedReason())
            }
            accumulated += page.objects
            if (accumulated.size > maximum) {
                throw BoundedRemoteFailure(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }
            pages += 1
            pageToken = page.nextPageToken ?: return accumulated
            if (pages >= MAX_PAGES) {
                throw BoundedRemoteFailure(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }
        }
    }

    companion object {
        const val MAX_OWNERSHIP_ROOTS = 64
        const val MAX_CLAIMS_PER_LINEAGE = 1_024
        const val PAGE_SIZE = 100
        internal const val MAX_PAGES = 64
        val MAX_CLAIM_FILE_BYTES: Long = REMOTE_FILE_LENGTH_PREFIX_BYTES +
            OwnershipClaimCodec.MAX_PUBLIC_HEADER_BYTES +
            OwnershipClaimCodec.MAX_CLAIM_FRAME_BYTES
    }
}

/** A provider or decoding failure already reduced to a bounded category. */
internal class BoundedRemoteFailure(
    val reason: RemoteBackupFailureCategory,
) : RuntimeException("Bounded remote failure")

internal const val REMOTE_FILE_LENGTH_PREFIX_BYTES = 4L

/**
 * Runs a codec or validation step whose only failure mode is rejected input.
 * Returns null instead of propagating, so an ambiguity never escapes as
 * control flow and no message from the failure ever reaches a caller.
 * Coroutine cancellation is never swallowed.
 */
internal inline fun <T> runBounded(block: () -> T): T? = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: IllegalArgumentException) {
    null
} catch (_: IllegalStateException) {
    null
}

/** Consumes owned remote bytes exactly once, clearing them afterwards. */
internal inline fun <T> OwnedRemoteBytes.useOwned(block: (ByteArray) -> T): T {
    val source = take()
    return try {
        block(source)
    } finally {
        source.fill(0)
        close()
    }
}

internal fun ownedCopyOf(bytes: ByteArray): OwnedRemoteBytes = object : OwnedRemoteBytes {
    private var owned: ByteArray? = bytes.copyOf()
    override val size: Int = bytes.size

    override fun take(): ByteArray {
        val current = checkNotNull(owned) { "Remote bytes were already taken or closed" }
        owned = null
        return current
    }

    override fun close() {
        owned?.fill(0)
        owned = null
    }
}

/**
 * Reduces an unexpected provider or local failure to a bounded category. The
 * throwable itself is never logged or rethrown, so no provider identifier or
 * message escapes. Coroutine cancellation is not a bounded failure and must be
 * rethrown by every caller before this runs.
 */
internal fun Exception.toBoundedReason(): RemoteBackupFailureCategory = when (this) {
    is BoundedRemoteFailure -> reason
    is DriveTransportException -> category.toRemoteFailure()
    is IllegalArgumentException -> RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE
    is IOException -> RemoteBackupFailureCategory.RETRYABLE_PROVIDER
    else -> RemoteBackupFailureCategory.RETRYABLE_PROVIDER
}
