package app.opentasks.core.data.backup

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.domain.AttachmentBlobStore
import app.opentasks.core.domain.AttachmentListedObject
import app.opentasks.core.domain.AttachmentManifestLookup
import app.opentasks.core.domain.AttachmentObjectResult
import app.opentasks.core.domain.AttachmentReadResult
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.OwnershipStateV1
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentGarbageCollectorTest {
    private val crypto = TinkVaultCrypto()
    private val key = crypto.createKey()

    @After
    fun closeKey() = key.close()

    @Test
    fun eachEligibilityPreconditionIndividuallyBlocksCollection() = runBlocking {
        withTimeout(5_000) {
            val store = FakeAttachmentStore(
                listed("eligible", "eligible-chunk", CHUNK_ROLE),
                listed("active", "active-chunk", CHUNK_ROLE),
                listed("young", "young-chunk", CHUNK_ROLE),
                listed("current", "current-chunk", CHUNK_ROLE),
                listed("previous", "previous-chunk", CHUNK_ROLE),
            )

            val result = collector().runBatch(
                store,
                listOf(
                    candidate("eligible"),
                    candidate("active", activelyReferenced = true),
                    candidate("young", deletedAt = NOW.minus(Duration.ofDays(29))),
                    candidate("current", coveredByCurrentBase = false),
                    candidate("previous", coveredByPreviousBase = false),
                ),
                NOW,
            )

            assertEquals(listOf("eligible-chunk"), store.deleted)
            assertEquals(1, result.deletedObjects)
            assertEquals(0, result.blockers)
            assertFalse(result.stoppedForOwnershipChange)
        }
    }

    @Test
    fun ownershipChangeStopsBeforeAnyDelete() = runBlocking {
        withTimeout(5_000) {
            val chain = FakeChainStore(ArrayDeque(listOf(active(TIP_A), active(TIP_B))))
            val store = FakeAttachmentStore(listed("eligible", "chunk", CHUNK_ROLE))

            val result = collector(chain = chain).runBatch(
                store,
                listOf(candidate("eligible")),
                NOW,
            )

            assertTrue(result.stoppedForOwnershipChange)
            assertEquals(0, result.deletedObjects)
            assertTrue(store.deleted.isEmpty())
        }
    }

    @Test
    fun unassociatedAndUnknownObjectsAreBlockersAndRemainUntouched() = runBlocking {
        withTimeout(5_000) {
            val store = FakeAttachmentStore(
                listed("eligible", "eligible-chunk", CHUNK_ROLE),
                listed("foreign", "foreign-chunk", CHUNK_ROLE),
                listed("eligible", "unknown-role", "future-role"),
                AttachmentListedObject(
                    providerObjectId = ProviderObjectId.of("role-less"),
                    role = null,
                    blobSetId = "eligible",
                    createdAtEpochMillis = null,
                ),
            )

            val result = collector().runBatch(
                store,
                listOf(candidate("eligible")),
                NOW,
            )

            assertEquals(listOf("eligible-chunk"), store.deleted)
            assertEquals(3, result.blockers)
        }
    }

    @Test
    fun budgetExhaustionResumesFromRemainingImmutableObjects() = runBlocking {
        withTimeout(5_000) {
            val store = FakeAttachmentStore(
                listed("eligible", "manifest", MANIFEST_ROLE),
                listed("eligible", "chunk-0", CHUNK_ROLE),
                listed("eligible", "chunk-1", CHUNK_ROLE),
                listed("eligible", "chunk-2", CHUNK_ROLE),
            )
            val collector = collector(maximumDeletes = 2)

            val first = collector.runBatch(store, listOf(candidate("eligible")), NOW)
            val second = collector.runBatch(store, listOf(candidate("eligible")), NOW)

            assertEquals(2, first.deletedObjects)
            assertEquals(2, second.deletedObjects)
            assertEquals(listOf("chunk-0", "chunk-1", "chunk-2", "manifest"), store.deleted)
        }
    }

    @Test
    fun chunksDeleteBeforeManifestRegardlessOfListingOrder() = runBlocking {
        withTimeout(5_000) {
            val store = FakeAttachmentStore(
                listed("eligible", "manifest", MANIFEST_ROLE),
                listed("eligible", "chunk-1", CHUNK_ROLE),
                listed("eligible", "chunk-0", CHUNK_ROLE),
            )

            collector().runBatch(store, listOf(candidate("eligible")), NOW)

            assertEquals(listOf("chunk-1", "chunk-0", "manifest"), store.deleted)
        }
    }

    private fun collector(
        chain: OwnershipChainStore = FakeChainStore(),
        maximumDeletes: Int = 32,
    ) = AttachmentGarbageCollector(
        chainStore = chain,
        rootClaimProviderId = ROOT_PROVIDER,
        contentKey = { key },
        maximumDeletesPerBatch = maximumDeletes,
    )

    private fun candidate(
        blobSetId: String,
        deletedAt: Instant = NOW.minus(Duration.ofDays(30)),
        coveredByCurrentBase: Boolean = true,
        coveredByPreviousBase: Boolean = true,
        activelyReferenced: Boolean = false,
    ) = GcCandidate(
        blobSetId = BlobSetId(blobSetId),
        deletedAt = deletedAt,
        tombstoneGeneration = 10,
        coveredByCurrentBase = coveredByCurrentBase,
        coveredByPreviousBase = coveredByPreviousBase,
        activelyReferenced = activelyReferenced,
    )

    private class FakeChainStore(
        private val resolutions: ArrayDeque<OwnershipResolution> =
            ArrayDeque(listOf(active(TIP_A), active(TIP_A))),
    ) : OwnershipChainStore {
        private var latest = resolutions.last()
        override suspend fun discoverPublicRoots() = error("not used")
        override suspend fun resolve(
            rootProviderId: ProviderObjectId,
            contentKey: app.opentasks.core.crypto.VaultKey,
        ): OwnershipResolution = if (resolutions.isEmpty()) latest else resolutions.removeFirst().also {
            latest = it
        }
        override suspend fun createClaim(
            expectedPredecessor: VerifiedOwnershipClaim?,
            encodedClaim: OwnedRemoteBytes,
            contentKey: app.opentasks.core.crypto.VaultKey,
        ) = error("not used")
    }

    private class FakeAttachmentStore(vararg initial: AttachmentListedObject) :
        AttachmentBlobStore {
        private val objects = initial.toMutableList()
        val deleted = mutableListOf<String>()
        override suspend fun generateObjectIds(count: Int) = error("not used")
        override suspend fun createChunk(
            providerObjectId: ProviderObjectId,
            blobSetId: BlobSetId,
            chunkIndex: Int,
            chunkCount: Int,
            frameBytes: ByteArray,
        ): AttachmentObjectResult = error("not used")
        override suspend fun readObject(
            providerObjectId: ProviderObjectId,
            maximumBytes: Long,
        ): AttachmentReadResult = error("not used")
        override suspend fun createManifest(
            providerObjectId: ProviderObjectId,
            blobSetId: BlobSetId,
            frameBytes: ByteArray,
        ): AttachmentObjectResult = error("not used")
        override suspend fun findManifest(blobSetId: BlobSetId): AttachmentManifestLookup =
            error("not used")
        override suspend fun listNamespace(pageToken: String?) = objects.toList() to null
        override suspend fun delete(providerObjectId: ProviderObjectId): Boolean {
            deleted += providerObjectId.value
            objects.removeAll { it.providerObjectId == providerObjectId }
            return true
        }
    }

    private companion object {
        val NOW = Instant.parse("2026-08-02T00:00:00Z")
        val ROOT_PROVIDER = ProviderObjectId.of("root")
        const val CHUNK_ROLE = "attachment-chunk"
        const val MANIFEST_ROLE = "attachment-manifest"
        const val TIP_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TIP_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        fun active(digest: String): OwnershipResolution.Active {
            val claim = OwnershipClaimV1(
                lineageId = "00000000-0000-4000-8000-000000000001",
                writerEpoch = 1,
                state = OwnershipStateV1.ACTIVE,
                predecessorProviderFileId = null,
                predecessorClaimId = null,
                predecessorClaimSha256 = null,
                providerFileId = ROOT_PROVIDER.value,
                claimId = "00000000-0000-4000-8000-000000000002",
                predecessorReservedSuccessorProviderFileId = null,
                sourceVaultId = "vault",
                activeDeviceId = "00000000-0000-4000-8000-000000000003",
                nextSuccessorProviderFileId = "next",
                baselinePublicationProviderFileId = "publication",
                baselinePublicationId = "00000000-0000-4000-8000-000000000004",
                baselinePublicationSha256 = TIP_A,
                recoveryCredentialGeneration = 1,
                creationOperationId = "operation",
                tombstoneId = null,
            )
            val verified = VerifiedOwnershipClaim(
                header = OwnershipPublicHeaderV1(
                    lineageId = claim.lineageId,
                    claimId = claim.claimId,
                    writerEpoch = claim.writerEpoch,
                    state = claim.state,
                    role = RemoteObjectRoleV1.OWNERSHIP_ROOT,
                    providerFileId = claim.providerFileId,
                    nextSuccessorProviderFileId = claim.nextSuccessorProviderFileId,
                    encryptedFrameLength = 1,
                    encryptedFrameSha256 = TIP_A,
                ),
                claim = claim,
                completeSha256 = Sha256Digest.of(digest),
            )
            return OwnershipResolution.Active(verified, verified)
        }

        fun listed(blobSetId: String, providerId: String, role: String) =
            AttachmentListedObject(
                providerObjectId = ProviderObjectId.of(providerId),
                role = role,
                blobSetId = blobSetId,
                createdAtEpochMillis = null,
            )
    }
}
