package app.opentasks.core.data.backup

import app.opentasks.core.crypto.Argon2Metadata
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.CreateSmallResult
import app.opentasks.core.domain.DeleteObjectResult
import app.opentasks.core.domain.ImmutableDownloadResult
import app.opentasks.core.domain.ImmutableUploadRequest
import app.opentasks.core.domain.ImmutableUploadResult
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.OwnedRemoteFile
import app.opentasks.core.domain.ReadSmallResult
import app.opentasks.core.domain.RemoteListPage
import app.opentasks.core.domain.RemoteListRequest
import app.opentasks.core.domain.RemoteListedObject
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OwnershipClaimId
import app.opentasks.core.model.OwnershipStateV1
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.WriterEpoch
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnershipChainStoreTest {

    @Test
    fun discoveryReadsEveryListedRootThroughItsExactProviderIdentity() = runChainTest { store ->
        val root = store.objectStore.seedRoot()

        val discovery = store.discoverPublicRoots()

        val roots = (discovery as OwnershipRootDiscovery.Discovered).roots
        assertEquals(1, roots.size)
        assertEquals(RemoteBackupTestFixtures.ROOT_PROVIDER_ID, roots.single().providerFileId)
        assertEquals(root.header, roots.single())
        assertEquals(
            listOf(RemoteBackupTestFixtures.ROOT_PROVIDER_ID),
            store.objectStore.readIds,
        )
        assertEquals(0, store.generatedAlternateIds)
    }

    @Test
    fun discoveryRejectsMoreThanSixtyFourOwnershipRoots() = runChainTest { store ->
        repeat(DefaultOwnershipChainStore.MAX_OWNERSHIP_ROOTS + 1) { index ->
            store.objectStore.seedRoot(
                providerFileId = "provider-root-$index",
                claimId = OwnershipClaimId.new().value,
                lineageId = CloudLineageId.new().value,
            )
        }

        val discovery = store.discoverPublicRoots()

        assertEquals(
            OwnershipRootDiscovery.Blocked(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            ),
            discovery,
        )
    }

    @Test
    fun discoveryTranslatesEveryListFailureIntoABoundedBlockedResult() = runChainTest { store ->
        store.objectStore.seedRoot()
        store.objectStore.listFailure = { IllegalStateException("provider list failed") }

        val discovery = store.discoverPublicRoots()

        assertEquals(
            OwnershipRootDiscovery.Blocked(
                RemoteBackupFailureCategory.RETRYABLE_PROVIDER,
            ),
            discovery,
        )
    }

    @Test
    fun discoveryTranslatesBoundedReadFailuresIntoABlockedResult() = runChainTest { store ->
        store.objectStore.seedRoot()
        store.objectStore.readFailures[RemoteBackupTestFixtures.ROOT_PROVIDER_ID] =
            RemoteBackupFailureCategory.PROVIDER_STORAGE

        val discovery = store.discoverPublicRoots()

        assertEquals(
            OwnershipRootDiscovery.Blocked(RemoteBackupFailureCategory.PROVIDER_STORAGE),
            discovery,
        )
    }

    @Test
    fun discoveryFailsClosedOnARootWhoseHeaderNamesAnotherProviderFile() = runChainTest { store ->
        val root = store.objectStore.seedRoot()
        store.objectStore.put(
            providerObjectId = "provider-root-copy",
            bytes = store.objectStore.requireBytes(RemoteBackupTestFixtures.ROOT_PROVIDER_ID),
            metadata = RemoteBackupTestFixtures.claimMetadata(
                root.header,
                "provider-root-copy",
            ),
            lineageId = CloudLineageId.parse(root.claim.lineageId),
        )

        val discovery = store.discoverPublicRoots()

        assertEquals(
            OwnershipRootDiscovery.Blocked(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            ),
            discovery,
        )
    }

    @Test
    fun resolveFollowsOnlyTheExactReservedSuccessorSlotFromEachPublicHeader() = runChainTest { store ->
        val chain = store.objectStore.seedChain(length = 3)
        // A decoy claim carrying a higher writer epoch sits outside every reserved slot.
        store.objectStore.seedDecoyClaim(providerFileId = "provider-decoy", writerEpoch = 99)

        val result = store.resolve(RemoteBackupTestFixtures.chainProviderId(0), CONTENT_KEY)

        val active = result as OwnershipResolution.Active
        assertEquals(chain.first().completeSha256, active.root.completeSha256)
        assertEquals(chain.last().completeSha256, active.tip.completeSha256)
        assertEquals(3L, active.tip.claim.writerEpoch)
        assertEquals(
            listOf(
                RemoteBackupTestFixtures.chainProviderId(0).value,
                RemoteBackupTestFixtures.chainProviderId(1).value,
                RemoteBackupTestFixtures.chainProviderId(2).value,
                RemoteBackupTestFixtures.chainProviderId(3).value,
            ),
            store.objectStore.readIds,
        )
        assertFalse(store.objectStore.readIds.contains("provider-decoy"))
    }

    @Test
    fun missingSuccessorSlotMakesItsPredecessorTheAuthenticatedTip() = runChainTest { store ->
        store.objectStore.seedChain(length = 2)

        val result = store.resolve(RemoteBackupTestFixtures.chainProviderId(0), CONTENT_KEY)

        val active = result as OwnershipResolution.Active
        assertEquals(2L, active.tip.claim.writerEpoch)
        assertEquals(
            RemoteBackupTestFixtures.chainProviderId(2).value,
            active.tip.claim.nextSuccessorProviderFileId,
        )
    }

    @Test
    fun resolveAuthenticatesTheRootBeforeFollowingAnySuccessor() = runChainTest { store ->
        store.objectStore.seedChain(length = 2)

        val result = store.resolve(RemoteBackupTestFixtures.chainProviderId(0), OTHER_KEY)

        assertEquals(
            OwnershipResolution.Blocked(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            ),
            result,
        )
        assertEquals(
            listOf(RemoteBackupTestFixtures.chainProviderId(0).value),
            store.objectStore.readIds,
        )
    }

    @Test
    fun resolveRejectsARootServedFromAnotherProviderFile() = runChainTest { store ->
        val root = store.objectStore.seedRoot()
        store.objectStore.put(
            providerObjectId = "provider-root-copy",
            bytes = store.objectStore.requireBytes(RemoteBackupTestFixtures.ROOT_PROVIDER_ID),
            metadata = RemoteBackupTestFixtures.claimMetadata(root.header, "provider-root-copy"),
            lineageId = CloudLineageId.parse(root.claim.lineageId),
        )

        val result = store.resolve(ProviderObjectId.of("provider-root-copy"), CONTENT_KEY)

        assertEquals(
            OwnershipResolution.Blocked(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            ),
            result,
        )
    }

    @Test
    fun invalidOccupiedSuccessorBlocksWithoutAlternateSlot() = runChainTest { store ->
        store.objectStore.seedChainWithOccupiedSuccessor(INVALID_BYTES)

        val result = store.resolve(ROOT_ID, CONTENT_KEY)

        assertEquals(
            OwnershipResolution.Blocked(
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            ),
            result,
        )
        assertEquals(0, store.generatedAlternateIds)
    }

    @Test
    fun terminalTipResolvesAsTerminatedAndFollowsNoFurtherSlot() = runChainTest { store ->
        store.objectStore.seedTerminatedChain()

        val result = store.resolve(RemoteBackupTestFixtures.chainProviderId(0), CONTENT_KEY)

        val terminated = result as OwnershipResolution.Terminated
        assertEquals(OwnershipStateV1.TERMINATED, terminated.tombstone.claim.state)
        assertEquals(
            RemoteObjectRoleV1.OWNERSHIP_TOMBSTONE,
            terminated.tombstone.header.role,
        )
        assertNull(terminated.tombstone.claim.nextSuccessorProviderFileId)
    }

    @Test
    fun chainLongerThanOneThousandTwentyFourClaimsFailsClosed() = runChainTest { store ->
        store.objectStore.seedChain(
            length = DefaultOwnershipChainStore.MAX_CLAIMS_PER_LINEAGE + 1,
        )

        val result = store.resolve(RemoteBackupTestFixtures.chainProviderId(0), CONTENT_KEY)

        assertEquals(
            OwnershipResolution.Blocked(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            ),
            result,
        )
    }

    @Test
    fun resolveTranslatesBoundedReadFailuresIntoABlockedResult() = runChainTest { store ->
        store.objectStore.seedChain(length = 2)
        store.objectStore.readFailures[RemoteBackupTestFixtures.chainProviderId(1).value] =
            RemoteBackupFailureCategory.RETRYABLE_PROVIDER

        val result = store.resolve(RemoteBackupTestFixtures.chainProviderId(0), CONTENT_KEY)

        assertEquals(
            OwnershipResolution.Blocked(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
            result,
        )
    }

    @Test
    fun epochOverflowFailsClosedWithoutTouchingTheProvider() = runChainTest { store ->
        // A maximum-epoch predecessor cannot reserve a successor: incrementing
        // its writer epoch is not representable, so no candidate can be encoded.
        val predecessor = RemoteBackupTestFixtures.verifiedClaimAtEpoch(Long.MAX_VALUE)

        val result = store.createClaim(
            predecessor,
            remoteBytesOf(RemoteBackupTestFixtures.encodedRoot()),
            CONTENT_KEY,
        )

        assertEquals(
            OwnershipClaimCreateResult.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            ),
            result,
        )
        assertTrue(store.objectStore.createdIds.isEmpty())
    }

    @Test
    fun createClaimWinsAtTheReservedSlotAndAuthenticatesItsOwnBytes() = runChainTest { store ->
        val encoded = RemoteBackupTestFixtures.encodedRoot()

        val result = store.createClaim(null, remoteBytesOf(encoded), CONTENT_KEY)

        val won = result as OwnershipClaimCreateResult.Won
        assertEquals(RemoteBackupTestFixtures.ROOT_CLAIM_ID, won.claim.claim.claimId)
        assertEquals(
            listOf(RemoteBackupTestFixtures.ROOT_PROVIDER_ID),
            store.objectStore.createdIds,
        )
    }

    @Test
    fun createClaimLosesToAnAuthenticatedOccupantOfTheSameSlot() = runChainTest { store ->
        val winner = store.objectStore.seedRoot()
        val loser = RemoteBackupTestFixtures.encodedRoot(
            claimId = RemoteBackupTestFixtures.SUCCESSOR_CLAIM_ID,
        )

        val result = store.createClaim(null, remoteBytesOf(loser), CONTENT_KEY)

        val lost = result as OwnershipClaimCreateResult.Lost
        assertEquals(winner.completeSha256, lost.winner.completeSha256)
    }

    @Test
    fun createClaimTreatsAnIdenticalOccupantAsAResumedWin() = runChainTest { store ->
        val existing = store.objectStore.seedRoot()

        val result = store.createClaim(
            null,
            remoteBytesOf(store.objectStore.requireBytes(RemoteBackupTestFixtures.ROOT_PROVIDER_ID)),
            CONTENT_KEY,
        )

        val won = result as OwnershipClaimCreateResult.Won
        assertEquals(existing.completeSha256, won.claim.completeSha256)
    }

    @Test
    fun createClaimReportsAmbiguityWhenTheOccupantCannotBeAuthenticated() = runChainTest { store ->
        store.objectStore.put(
            providerObjectId = RemoteBackupTestFixtures.ROOT_PROVIDER_ID,
            bytes = INVALID_BYTES,
            metadata = RemoteListedObject(
                providerObjectId = ProviderObjectId.of(RemoteBackupTestFixtures.ROOT_PROVIDER_ID),
                logicalObjectId = null,
                role = RemoteObjectRoleV1.OWNERSHIP_ROOT,
                writerEpoch = WriterEpoch(1),
                ownerDeviceId = null,
            ),
            lineageId = CloudLineageId.parse(RemoteBackupTestFixtures.LINEAGE_ID),
        )

        val result = store.createClaim(
            null,
            remoteBytesOf(RemoteBackupTestFixtures.encodedRoot()),
            CONTENT_KEY,
        )

        assertEquals(OwnershipClaimCreateResult.AmbiguousRemoteState, result)
    }

    @Test
    fun createClaimSurfacesBoundedProviderFailures() = runChainTest { store ->
        store.objectStore.createFailures[RemoteBackupTestFixtures.ROOT_PROVIDER_ID] =
            RemoteBackupFailureCategory.PROVIDER_STORAGE

        val result = store.createClaim(
            null,
            remoteBytesOf(RemoteBackupTestFixtures.encodedRoot()),
            CONTENT_KEY,
        )

        assertEquals(
            OwnershipClaimCreateResult.Failed(RemoteBackupFailureCategory.PROVIDER_STORAGE),
            result,
        )
    }

    @Test
    fun createClaimRejectsACandidateOutsideThePredecessorReservedSlot() = runChainTest { store ->
        val root = RemoteBackupTestFixtures.verifiedRoot()
        val strayEncoded = RemoteBackupTestFixtures.encodedSuccessorOf(
            predecessor = root,
            providerFileId = "provider-elsewhere",
        )

        val result = store.createClaim(root, remoteBytesOf(strayEncoded), CONTENT_KEY)

        assertEquals(
            OwnershipClaimCreateResult.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            ),
            result,
        )
        assertTrue(store.objectStore.createdIds.isEmpty())
    }

    private fun runChainTest(block: suspend (ChainStoreUnderTest) -> Unit) = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            val root = Files.createTempDirectory("ownership-chain-store-test").toFile()
            try {
                val objectStore = FakeCreateOnlyBackupObjectStore(root)
                block(
                    ChainStoreUnderTest(
                        DefaultOwnershipChainStore(
                            objectStore,
                            RemoteBackupTestFixtures.ownershipCodec,
                        ),
                        objectStore,
                    ),
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
        val CONTENT_KEY: VaultKey = RemoteBackupTestFixtures.contentKey
        val OTHER_KEY: VaultKey = RemoteBackupTestFixtures.otherKey
        val ROOT_ID: ProviderObjectId =
            ProviderObjectId.of(RemoteBackupTestFixtures.CHAIN_PROVIDER_PREFIX + "0")
        val INVALID_BYTES = ByteArray(64) { (it + 1).toByte() }
    }
}

/**
 * Exposes the fake provider's generated-ID counter beside the production
 * contract so a resolution can assert that failing closed never reserved an
 * alternate successor slot.
 */
internal class ChainStoreUnderTest(
    delegate: OwnershipChainStore,
    val objectStore: FakeCreateOnlyBackupObjectStore,
) : OwnershipChainStore by delegate {
    val generatedAlternateIds: Int get() = objectStore.generatedIdCount
}

internal fun remoteBytesOf(bytes: ByteArray): OwnedRemoteBytes = object : OwnedRemoteBytes {
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

internal fun remoteFileOf(bytes: ByteArray, directory: File): OwnedRemoteFile {
    directory.mkdirs()
    val backing = File.createTempFile("frame-", ".bin", directory)
    backing.writeBytes(bytes)
    return object : OwnedRemoteFile {
        override val file: File = backing
        override val length: Long get() = backing.length()
        override fun close() {
            backing.delete()
        }
    }
}

internal fun hexDigestOf(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        "%02x".format(byte)
    }

/**
 * Deterministic ownership and publication fixtures shared by the Stage 3
 * chain, catalog, remote-object, and configurator tests.
 */
internal object RemoteBackupTestFixtures {
    const val LINEAGE_ID = "00000000-0000-4000-8000-000000000001"
    const val OTHER_LINEAGE_ID = "00000000-0000-4000-8000-0000000000ff"
    const val ROOT_CLAIM_ID = "00000000-0000-4000-8000-000000000002"
    const val SUCCESSOR_CLAIM_ID = "00000000-0000-4000-8000-000000000003"
    const val TOMBSTONE_CLAIM_ID = "00000000-0000-4000-8000-000000000004"
    const val DEVICE_ID = "00000000-0000-4000-8000-000000000005"
    const val OTHER_DEVICE_ID = "00000000-0000-4000-8000-000000000006"
    const val BASELINE_PUBLICATION_ID = "00000000-0000-4000-8000-000000000007"
    const val NEXT_PUBLICATION_ID = "00000000-0000-4000-8000-000000000008"
    const val TOMBSTONE_ID = "00000000-0000-4000-8000-00000000000d"
    const val VAULT_ID = "vault-alpha"
    const val ROOT_PROVIDER_ID = "provider-root"
    const val SUCCESSOR_PROVIDER_ID = "provider-successor"
    const val BASELINE_PROVIDER_ID = "provider-baseline"
    const val NEXT_PUBLICATION_PROVIDER_ID = "provider-publication-1"
    const val OPERATION_ID = "operation-connect"
    const val CHAIN_PROVIDER_PREFIX = "provider-claim-"
    const val BASE_A_LOGICAL_ID = "00000000-0000-4000-8000-0000000000a1"
    const val BASE_B_LOGICAL_ID = "00000000-0000-4000-8000-0000000000b2"
    const val DIGEST_A = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"

    val crypto = TinkVaultCrypto()
    val authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto)
    val ownershipCodec = OwnershipClaimCodec(authenticatedCodec)
    val publicationCodec = PublicationCodec(authenticatedCodec)
    val contentKey: VaultKey = crypto.createKey()
    val otherKey: VaultKey = crypto.createKey()

    fun chainProviderId(index: Int): ProviderObjectId =
        ProviderObjectId.of(CHAIN_PROVIDER_PREFIX + index)

    fun envelope(): VaultKeyEnvelope = VaultKeyEnvelope(
        formatVersion = 1,
        kdf = Argon2Metadata(ByteArray(16) { it.toByte() }),
        nonce = ByteArray(12) { (it + 1).toByte() },
        wrappedKeyset = ByteArray(48) { (it + 2).toByte() },
    )

    fun activeRoot(
        providerFileId: String = ROOT_PROVIDER_ID,
        claimId: String = ROOT_CLAIM_ID,
        lineageId: String = LINEAGE_ID,
        nextSuccessorProviderFileId: String = SUCCESSOR_PROVIDER_ID,
        activeDeviceId: String = DEVICE_ID,
        baselinePublicationProviderFileId: String = BASELINE_PROVIDER_ID,
        baselinePublicationId: String = BASELINE_PUBLICATION_ID,
        baselinePublicationSha256: String = DIGEST_A,
    ): OwnershipClaimV1 = OwnershipClaimV1(
        lineageId = lineageId,
        writerEpoch = 1,
        state = OwnershipStateV1.ACTIVE,
        predecessorProviderFileId = null,
        predecessorClaimId = null,
        predecessorClaimSha256 = null,
        providerFileId = providerFileId,
        claimId = claimId,
        predecessorReservedSuccessorProviderFileId = null,
        sourceVaultId = VAULT_ID,
        activeDeviceId = activeDeviceId,
        nextSuccessorProviderFileId = nextSuccessorProviderFileId,
        baselinePublicationProviderFileId = baselinePublicationProviderFileId,
        baselinePublicationId = baselinePublicationId,
        baselinePublicationSha256 = baselinePublicationSha256,
        recoveryCredentialGeneration = 0,
        creationOperationId = OPERATION_ID,
        tombstoneId = null,
    )

    fun successorOf(
        predecessor: VerifiedOwnershipClaim,
        providerFileId: String = checkNotNull(predecessor.claim.nextSuccessorProviderFileId),
        claimId: String = OwnershipClaimId.new().value,
        nextSuccessorProviderFileId: String = OwnershipClaimId.new().value,
        activeDeviceId: String = checkNotNull(predecessor.claim.activeDeviceId),
    ): OwnershipClaimV1 = OwnershipClaimV1(
        lineageId = predecessor.claim.lineageId,
        writerEpoch = predecessor.claim.writerEpoch + 1,
        state = OwnershipStateV1.ACTIVE,
        predecessorProviderFileId = predecessor.claim.providerFileId,
        predecessorClaimId = predecessor.claim.claimId,
        predecessorClaimSha256 = predecessor.completeSha256.value,
        providerFileId = providerFileId,
        claimId = claimId,
        predecessorReservedSuccessorProviderFileId = providerFileId,
        sourceVaultId = VAULT_ID,
        activeDeviceId = activeDeviceId,
        nextSuccessorProviderFileId = nextSuccessorProviderFileId,
        baselinePublicationProviderFileId = BASELINE_PROVIDER_ID,
        baselinePublicationId = PublicationId.new().value,
        baselinePublicationSha256 = DIGEST_A,
        recoveryCredentialGeneration = 0,
        creationOperationId = OPERATION_ID,
        tombstoneId = null,
    )

    fun tombstoneOf(predecessor: VerifiedOwnershipClaim): OwnershipClaimV1 = OwnershipClaimV1(
        lineageId = predecessor.claim.lineageId,
        writerEpoch = predecessor.claim.writerEpoch + 1,
        state = OwnershipStateV1.TERMINATED,
        predecessorProviderFileId = predecessor.claim.providerFileId,
        predecessorClaimId = predecessor.claim.claimId,
        predecessorClaimSha256 = predecessor.completeSha256.value,
        providerFileId = checkNotNull(predecessor.claim.nextSuccessorProviderFileId),
        claimId = TOMBSTONE_CLAIM_ID,
        predecessorReservedSuccessorProviderFileId =
            checkNotNull(predecessor.claim.nextSuccessorProviderFileId),
        sourceVaultId = null,
        activeDeviceId = null,
        nextSuccessorProviderFileId = null,
        baselinePublicationProviderFileId = null,
        baselinePublicationId = null,
        baselinePublicationSha256 = null,
        recoveryCredentialGeneration = null,
        creationOperationId = OPERATION_ID,
        tombstoneId = TOMBSTONE_ID,
    )

    fun encodedRoot(
        providerFileId: String = ROOT_PROVIDER_ID,
        claimId: String = ROOT_CLAIM_ID,
    ): ByteArray = ownershipCodec.encode(
        activeRoot(providerFileId = providerFileId, claimId = claimId),
        contentKey,
    )

    fun verifiedRoot(providerFileId: String = ROOT_PROVIDER_ID): VerifiedOwnershipClaim =
        ownershipCodec.verify(encodedRoot(providerFileId = providerFileId), contentKey)

    fun encodedSuccessorOf(
        predecessor: VerifiedOwnershipClaim,
        providerFileId: String = checkNotNull(predecessor.claim.nextSuccessorProviderFileId),
    ): ByteArray = ownershipCodec.encode(
        successorOf(predecessor, providerFileId = providerFileId),
        contentKey,
    )

    /** A claim already sitting at the maximum representable writer epoch. */
    fun verifiedClaimAtEpoch(epoch: Long): VerifiedOwnershipClaim {
        val claim = OwnershipClaimV1(
            lineageId = LINEAGE_ID,
            writerEpoch = epoch,
            state = OwnershipStateV1.ACTIVE,
            predecessorProviderFileId = ROOT_PROVIDER_ID,
            predecessorClaimId = ROOT_CLAIM_ID,
            predecessorClaimSha256 = DIGEST_A,
            providerFileId = SUCCESSOR_PROVIDER_ID,
            claimId = SUCCESSOR_CLAIM_ID,
            predecessorReservedSuccessorProviderFileId = SUCCESSOR_PROVIDER_ID,
            sourceVaultId = VAULT_ID,
            activeDeviceId = DEVICE_ID,
            nextSuccessorProviderFileId = "provider-overflow",
            baselinePublicationProviderFileId = BASELINE_PROVIDER_ID,
            baselinePublicationId = BASELINE_PUBLICATION_ID,
            baselinePublicationSha256 = DIGEST_A,
            recoveryCredentialGeneration = 0,
            creationOperationId = OPERATION_ID,
            tombstoneId = null,
        )
        return ownershipCodec.verify(ownershipCodec.encode(claim, contentKey), contentKey)
    }

    fun claimMetadata(
        header: OwnershipPublicHeaderV1,
        providerFileId: String = header.providerFileId,
    ): RemoteListedObject = RemoteListedObject(
        providerObjectId = ProviderObjectId.of(providerFileId),
        logicalObjectId = header.claimId,
        role = header.role,
        writerEpoch = WriterEpoch(header.writerEpoch),
        ownerDeviceId = null,
    )

    fun publicationMetadata(
        providerFileId: String,
        epoch: Long,
    ): RemoteListedObject = RemoteListedObject(
        providerObjectId = ProviderObjectId.of(providerFileId),
        logicalObjectId = null,
        role = RemoteObjectRoleV1.PUBLICATION,
        writerEpoch = WriterEpoch(epoch),
        ownerDeviceId = null,
    )
}

/**
 * In-memory create-only object store. Every provider file is addressed by its
 * exact generated ID; listing is an index over the same map, and each failure
 * hook returns the bounded result family the production contract declares —
 * except [listFailure], which throws exactly as the Drive-backed store does.
 */
internal class FakeCreateOnlyBackupObjectStore(
    private val stagingRoot: File,
) : CreateOnlyBackupObjectStore {

    private class Entry(
        val bytes: ByteArray,
        val metadata: RemoteListedObject,
        val lineageId: CloudLineageId?,
    )

    private val entries = LinkedHashMap<String, Entry>()

    val listRequests = mutableListOf<RemoteListRequest>()
    val readIds = mutableListOf<String>()
    val createdIds = mutableListOf<String>()
    val uploadRequests = mutableListOf<ImmutableUploadRequest>()
    val baseRequests = mutableListOf<ImmutableUploadRequest>()
    val downloadIds = mutableListOf<String>()
    val deletedIds = mutableListOf<String>()
    val authorityEvents = mutableListOf<String>()
    val callOrder = mutableListOf<String>()

    var generatedIdCount = 0
        private set
    var generatedIdPrefix = "generated"

    /** Simulates a provider that hands back the same generated ID twice. */
    var repeatGeneratedIds = false
    var listFailure: (() -> Throwable)? = null
    var listPageSize = 32
    val readFailures = mutableMapOf<String, RemoteBackupFailureCategory>()
    val createFailures = mutableMapOf<String, RemoteBackupFailureCategory>()
    val deleteFailures = mutableMapOf<String, RemoteBackupFailureCategory>()
    val ambiguousCreates = mutableSetOf<String>()
    var uploadFailure: RemoteBackupFailureCategory? = null

    /** Fails the next bounded read of a stored object holding this role exactly once. */
    var failNextReadOfRole: RemoteObjectRoleV1? = null

    /**
     * Runs immediately before a create is decided, so a test can model another
     * writer taking the exact slot this one is about to create at.
     */
    var beforeCreate: (suspend (ProviderObjectId) -> Unit)? = null

    fun put(
        providerObjectId: String,
        bytes: ByteArray,
        metadata: RemoteListedObject,
        lineageId: CloudLineageId?,
    ) {
        entries[providerObjectId] = Entry(bytes.copyOf(), metadata, lineageId)
    }

    fun requireBytes(providerObjectId: String): ByteArray =
        checkNotNull(entries[providerObjectId]) { "No provider object" }.bytes.copyOf()

    fun bytesOrNull(providerObjectId: String): ByteArray? = entries[providerObjectId]?.bytes?.copyOf()

    fun contains(providerObjectId: String): Boolean = providerObjectId in entries

    fun seedRoot(
        providerFileId: String = RemoteBackupTestFixtures.ROOT_PROVIDER_ID,
        claimId: String = RemoteBackupTestFixtures.ROOT_CLAIM_ID,
        lineageId: String = RemoteBackupTestFixtures.LINEAGE_ID,
    ): VerifiedOwnershipClaim {
        val encoded = RemoteBackupTestFixtures.ownershipCodec.encode(
            RemoteBackupTestFixtures.activeRoot(
                providerFileId = providerFileId,
                claimId = claimId,
                lineageId = lineageId,
            ),
            RemoteBackupTestFixtures.contentKey,
        )
        val verified = RemoteBackupTestFixtures.ownershipCodec.verify(
            encoded,
            RemoteBackupTestFixtures.contentKey,
        )
        put(
            providerObjectId = providerFileId,
            bytes = encoded,
            metadata = RemoteBackupTestFixtures.claimMetadata(verified.header),
            lineageId = CloudLineageId.parse(lineageId),
        )
        return verified
    }

    /** Seeds [length] chained claims at `provider-claim-0 … provider-claim-(length-1)`. */
    fun seedChain(length: Int): List<VerifiedOwnershipClaim> {
        val codec = RemoteBackupTestFixtures.ownershipCodec
        val key = RemoteBackupTestFixtures.contentKey
        val chain = mutableListOf<VerifiedOwnershipClaim>()
        val rootEncoded = codec.encode(
            RemoteBackupTestFixtures.activeRoot(
                providerFileId = RemoteBackupTestFixtures.chainProviderId(0).value,
                nextSuccessorProviderFileId = RemoteBackupTestFixtures.chainProviderId(1).value,
            ),
            key,
        )
        var current = codec.verify(rootEncoded, key)
        chain += current
        put(
            providerObjectId = current.claim.providerFileId,
            bytes = rootEncoded,
            metadata = RemoteBackupTestFixtures.claimMetadata(current.header),
            lineageId = CloudLineageId.parse(current.claim.lineageId),
        )
        for (index in 1 until length) {
            val encoded = codec.encode(
                RemoteBackupTestFixtures.successorOf(
                    predecessor = current,
                    providerFileId = RemoteBackupTestFixtures.chainProviderId(index).value,
                    nextSuccessorProviderFileId =
                        RemoteBackupTestFixtures.chainProviderId(index + 1).value,
                ),
                key,
            )
            current = codec.verifySuccessor(current, encoded, key)
            chain += current
            put(
                providerObjectId = current.claim.providerFileId,
                bytes = encoded,
                metadata = RemoteBackupTestFixtures.claimMetadata(current.header),
                lineageId = CloudLineageId.parse(current.claim.lineageId),
            )
        }
        return chain
    }

    fun seedTerminatedChain(): VerifiedOwnershipClaim {
        val chain = seedChain(length = 1)
        val codec = RemoteBackupTestFixtures.ownershipCodec
        val key = RemoteBackupTestFixtures.contentKey
        val encoded = codec.encode(RemoteBackupTestFixtures.tombstoneOf(chain.last()), key)
        val tombstone = codec.verifySuccessor(chain.last(), encoded, key)
        put(
            providerObjectId = tombstone.claim.providerFileId,
            bytes = encoded,
            metadata = RemoteBackupTestFixtures.claimMetadata(tombstone.header),
            lineageId = CloudLineageId.parse(tombstone.claim.lineageId),
        )
        return tombstone
    }

    fun seedChainWithOccupiedSuccessor(occupantBytes: ByteArray) {
        val chain = seedChain(length = 1)
        put(
            providerObjectId = checkNotNull(chain.last().claim.nextSuccessorProviderFileId),
            bytes = occupantBytes,
            metadata = RemoteListedObject(
                providerObjectId = RemoteBackupTestFixtures.chainProviderId(1),
                logicalObjectId = null,
                role = RemoteObjectRoleV1.OWNERSHIP_CLAIM,
                writerEpoch = WriterEpoch(2),
                ownerDeviceId = null,
            ),
            lineageId = CloudLineageId.parse(RemoteBackupTestFixtures.LINEAGE_ID),
        )
    }

    fun seedDecoyClaim(providerFileId: String, writerEpoch: Long) {
        val codec = RemoteBackupTestFixtures.ownershipCodec
        val key = RemoteBackupTestFixtures.contentKey
        val decoy = RemoteBackupTestFixtures.verifiedClaimAtEpoch(writerEpoch)
        val encoded = codec.encode(
            decoy.claim.copy(
                providerFileId = providerFileId,
                predecessorReservedSuccessorProviderFileId = providerFileId,
            ),
            key,
        )
        put(
            providerObjectId = providerFileId,
            bytes = encoded,
            metadata = RemoteBackupTestFixtures.claimMetadata(
                codec.readPublicHeader(encoded),
            ),
            lineageId = CloudLineageId.parse(RemoteBackupTestFixtures.LINEAGE_ID),
        )
    }

    override suspend fun generateProviderIds(
        count: Int,
        role: RemoteObjectRoleV1,
    ): List<ProviderObjectId> {
        callOrder += "generateProviderIds:${role.name}"
        return (1..count).map {
            generatedIdCount += 1
            if (repeatGeneratedIds) {
                ProviderObjectId.of("$generatedIdPrefix-repeated")
            } else {
                ProviderObjectId.of("$generatedIdPrefix-${role.name}-$generatedIdCount")
            }
        }
    }

    override suspend fun createSmallIfAbsent(
        providerObjectId: ProviderObjectId,
        lineageId: CloudLineageId,
        metadata: RemoteListedObject,
        bytes: OwnedRemoteBytes,
    ): CreateSmallResult {
        callOrder += "createSmallIfAbsent:${providerObjectId.value}"
        beforeCreate?.invoke(providerObjectId)
        val content = bytes.take()
        try {
            createFailures[providerObjectId.value]?.let { return CreateSmallResult.Failed(it) }
            if (providerObjectId.value in ambiguousCreates) return CreateSmallResult.Ambiguous
            if (providerObjectId.value in entries) return CreateSmallResult.AlreadyExists
            put(providerObjectId.value, content, metadata, lineageId)
            createdIds += providerObjectId.value
            metadata.role?.let { role -> authorityEvents += authorityLabel(role) }
            return CreateSmallResult.Created
        } finally {
            content.fill(0)
            bytes.close()
        }
    }

    override suspend fun readSmall(
        providerObjectId: ProviderObjectId,
        maximumBytes: Long,
    ): ReadSmallResult {
        callOrder += "readSmall:${providerObjectId.value}"
        readIds += providerObjectId.value
        readFailures[providerObjectId.value]?.let { return ReadSmallResult.Failed(it) }
        val entry = entries[providerObjectId.value] ?: return ReadSmallResult.Missing
        if (entry.metadata.role != null && entry.metadata.role == failNextReadOfRole) {
            failNextReadOfRole = null
            return ReadSmallResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
        }
        if (entry.bytes.size > maximumBytes) {
            return ReadSmallResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        }
        return ReadSmallResult.Found(remoteBytesOf(entry.bytes))
    }

    override suspend fun list(request: RemoteListRequest): RemoteListPage {
        callOrder += "list:${request.role.name}"
        listRequests += request
        listFailure?.let { throw it() }
        val matching = entries.entries.filter { (_, entry) ->
            entry.metadata.role == request.role &&
                (request.lineageId == null || entry.lineageId == request.lineageId) &&
                (request.writerEpoch == null || entry.metadata.writerEpoch == request.writerEpoch) &&
                (
                    request.ownerDeviceId == null ||
                        entry.metadata.ownerDeviceId == request.ownerDeviceId
                    )
        }.map { (id, entry) ->
            entry.metadata.copy(providerObjectId = ProviderObjectId.of(id))
        }
        val offset = request.pageToken?.toIntOrNull() ?: 0
        val pageSize = minOf(request.pageSize, listPageSize)
        val page = matching.drop(offset).take(pageSize)
        val nextOffset = offset + page.size
        return RemoteListPage(
            objects = page,
            nextPageToken = if (nextOffset < matching.size) nextOffset.toString() else null,
        )
    }

    override suspend fun uploadImmutable(
        request: ImmutableUploadRequest,
    ): ImmutableUploadResult {
        callOrder += "uploadImmutable:${request.providerObjectId.value}"
        uploadRequests += request
        if (request.role == RemoteObjectRoleV1.SNAPSHOT) baseRequests += request
        uploadFailure?.let { return ImmutableUploadResult.Failed(it) }
        val content = request.frame.file.readBytes()
        if (hexDigestOf(content) != request.frameSha256.value ||
            content.size.toLong() != request.frameLength
        ) {
            return ImmutableUploadResult.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
        }
        val existing = entries[request.providerObjectId.value]
        if (existing != null) {
            return if (existing.bytes.contentEquals(content)) {
                ImmutableUploadResult.OccupiedByExpectedBytes
            } else {
                ImmutableUploadResult.OccupiedByDifferentBytes
            }
        }
        put(
            providerObjectId = request.providerObjectId.value,
            bytes = content,
            metadata = RemoteListedObject(
                providerObjectId = request.providerObjectId,
                logicalObjectId = request.logicalObjectId.value,
                role = request.role,
                writerEpoch = request.writerEpoch,
                ownerDeviceId = request.ownerDeviceId,
            ),
            lineageId = request.lineageId,
        )
        authorityEvents += if (request.role == RemoteObjectRoleV1.SNAPSHOT) {
            "BASE_" + ('A' + (baseRequests.size - 1))
        } else {
            authorityLabel(request.role)
        }
        return ImmutableUploadResult.UploadedAndVerified
    }

    override suspend fun downloadImmutable(
        providerObjectId: ProviderObjectId,
        maximumBytes: Long,
        expectedSha256: Sha256Digest,
    ): ImmutableDownloadResult {
        callOrder += "downloadImmutable:${providerObjectId.value}"
        downloadIds += providerObjectId.value
        val entry = entries[providerObjectId.value] ?: return ImmutableDownloadResult.Missing
        if (entry.bytes.size > maximumBytes) {
            return ImmutableDownloadResult.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
        }
        if (hexDigestOf(entry.bytes) != expectedSha256.value) return ImmutableDownloadResult.Corrupt
        return ImmutableDownloadResult.Downloaded(remoteFileOf(entry.bytes, stagingRoot))
    }

    override suspend fun delete(providerObjectId: ProviderObjectId): DeleteObjectResult {
        callOrder += "delete:${providerObjectId.value}"
        deleteFailures[providerObjectId.value]?.let { return DeleteObjectResult.Failed(it) }
        deletedIds += providerObjectId.value
        return if (entries.remove(providerObjectId.value) != null) {
            DeleteObjectResult.Deleted
        } else {
            DeleteObjectResult.Missing
        }
    }

    private fun authorityLabel(role: RemoteObjectRoleV1): String = when (role) {
        RemoteObjectRoleV1.OWNERSHIP_ROOT -> "ROOT"
        RemoteObjectRoleV1.OWNERSHIP_CLAIM -> "CLAIM"
        RemoteObjectRoleV1.OWNERSHIP_TOMBSTONE -> "TOMBSTONE"
        RemoteObjectRoleV1.PUBLICATION -> "BASELINE"
        RemoteObjectRoleV1.SNAPSHOT -> "BASE"
        RemoteObjectRoleV1.SEGMENT -> "SEGMENT"
    }
}
