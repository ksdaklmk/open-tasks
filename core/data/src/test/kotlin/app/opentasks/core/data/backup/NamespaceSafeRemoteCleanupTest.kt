package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.domain.RemoteBackupObject
import app.opentasks.core.domain.RemoteListedObject
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.RemoteObjectLifecycle
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.WriterEpoch
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NamespaceSafeRemoteCleanupTest {

    @Test
    fun cleanupStopsAtThirtyTwoAndRechecksTip() = runCleanupTest { fixture ->
        fixture.seedEligible(count = 40)

        val result = fixture.runBatch()

        assertEquals(32, result.deletedCount)
        assertEquals(1, fixture.tipChecks)
        assertFalse(result.stoppedForOwnershipChange)
    }

    @Test
    fun aSecondBatchRechecksTheTipAndFinishesTheRemainder() = runCleanupTest { fixture ->
        fixture.seedEligible(count = 40)

        fixture.runBatch()
        val second = fixture.runBatch()

        assertEquals(8, second.deletedCount)
        assertEquals(2, fixture.tipChecks)
        assertEquals(40, fixture.store.deletedIds.size)
    }

    @Test
    fun retainedPublicationsAndTheirInventoriesAreNeverDeleted() = runCleanupTest { fixture ->
        fixture.seedEligible(count = 3)

        fixture.runBatch()

        fixture.retainedProviderIds().forEach { retained ->
            assertTrue(retained, fixture.store.contains(retained))
            assertFalse(retained, fixture.store.deletedIds.contains(retained))
        }
    }

    @Test
    fun bothCompleteBasesAndEveryRequiredSegmentSurviveCleanup() = runCleanupTest { fixture ->
        fixture.seedEligible(count = 2)

        fixture.runBatch()

        assertTrue(fixture.store.contains(CURRENT_BASE_PROVIDER))
        assertTrue(fixture.store.contains(FALLBACK_BASE_PROVIDER))
        assertTrue(fixture.store.contains(SEGMENT_PROVIDER))
    }

    @Test
    fun aChangedTipStopsTheBatchWithoutDeletingAnything() = runCleanupTest { fixture ->
        fixture.seedEligible(count = 4)
        fixture.replaceTipWithAnotherDevice()

        val result = fixture.runBatch()

        assertEquals(0, result.deletedCount)
        assertTrue(result.stoppedForOwnershipChange)
        assertTrue(fixture.store.deletedIds.isEmpty())
    }

    @Test
    fun anUnreadableOwnershipChainStopsTheBatchWithoutDeleting() = runCleanupTest { fixture ->
        fixture.seedEligible(count = 4)
        fixture.store.readFailures[ROOT_PROVIDER] =
            RemoteBackupFailureCategory.RETRYABLE_PROVIDER

        val result = fixture.runBatch()

        assertEquals(0, result.deletedCount)
        assertTrue(result.stoppedForOwnershipChange)
        assertTrue(fixture.store.deletedIds.isEmpty())
    }

    @Test
    fun anAbandonedCandidateYoungerThanTheResidueMinimumRemains() = runCleanupTest { fixture ->
        fixture.seedOrphan("provider-young", firstObservedAt = NOW.minus(Duration.ofDays(6)))

        val result = fixture.runBatch()

        assertEquals(0, result.deletedCount)
        assertEquals(0, result.blockers)
        assertTrue(fixture.store.contains("provider-young"))
    }

    @Test
    fun aRetiredObjectIsPrunedWithoutWaitingForTheResidueMinimum() = runCleanupTest { fixture ->
        // Uploaded and read back by this device, this epoch, and named by
        // neither retained publication: the successor already proves it is
        // unreferenced, so holding it for a week would only starve the
        // bounded per-epoch publication index.
        fixture.seedOrphan("provider-retired", firstObservedAt = NOW, verified = true)

        val result = fixture.runBatch()

        assertEquals(1, result.deletedCount)
        assertEquals(0, result.blockers)
        assertFalse(fixture.store.contains("provider-retired"))
    }

    @Test
    fun aVerifiedOlderEpochObjectStillWaitsForTheResidueMinimum() =
        runCleanupTest(currentEpoch = 2) { fixture ->
            fixture.seedOrphan(
                providerFileId = "provider-old-epoch",
                firstObservedAt = NOW,
                writerEpoch = 1,
                verified = true,
            )

            val result = fixture.runBatch()

            assertEquals(0, result.deletedCount)
            assertTrue(fixture.store.contains("provider-old-epoch"))
        }

    @Test
    fun ageUsesTheLocallyPersistedFirstObservedTimeOnly() = runCleanupTest { fixture ->
        fixture.seedOrphan("provider-young", firstObservedAt = NOW.minus(Duration.ofDays(1)))
        fixture.seedOrphan("provider-old", firstObservedAt = NOW.minus(Duration.ofDays(8)))

        val result = fixture.runBatch()

        assertEquals(1, result.deletedCount)
        assertEquals(listOf("provider-old"), fixture.store.deletedIds)
        assertTrue(fixture.store.contains("provider-young"))
    }

    @Test
    fun anObjectWithoutALocalFirstObservedTimeIsABlocker() = runCleanupTest { fixture ->
        fixture.seedOrphan("provider-unknown", firstObservedAt = null)

        val result = fixture.runBatch()

        assertEquals(0, result.deletedCount)
        assertEquals(1, result.blockers)
        assertTrue(fixture.store.contains("provider-unknown"))
    }

    @Test
    fun aCrossDeviceObjectIsABlockerAndRemains() = runCleanupTest { fixture ->
        fixture.seedOrphan(
            providerFileId = "provider-other-device",
            firstObservedAt = OLD,
            ownerDeviceId = CloudDeviceId.parse(RemoteBackupTestFixtures.OTHER_DEVICE_ID),
        )

        val result = fixture.runBatch()

        assertEquals(0, result.deletedCount)
        assertEquals(1, result.blockers)
        assertTrue(fixture.store.contains("provider-other-device"))
    }

    @Test
    fun anObjectFromANewerEpochIsABlockerAndRemains() = runCleanupTest { fixture ->
        fixture.seedOrphan("provider-too-new", firstObservedAt = OLD, writerEpoch = 2)

        val result = fixture.runBatch()

        assertEquals(0, result.deletedCount)
        assertEquals(1, result.blockers)
        assertTrue(fixture.store.contains("provider-too-new"))
    }

    @Test
    fun malformedListingMetadataIsABlockerAndRemains() = runCleanupTest { fixture ->
        fixture.seedMalformed("provider-malformed")

        val result = fixture.runBatch()

        assertEquals(0, result.deletedCount)
        assertEquals(1, result.blockers)
        assertTrue(fixture.store.contains("provider-malformed"))
    }

    @Test
    fun ownershipClaimsAreNeverListedOrDeleted() = runCleanupTest { fixture ->
        fixture.seedEligible(count = 2)

        fixture.runBatch()

        assertTrue(fixture.store.contains(ROOT_PROVIDER))
        assertFalse(fixture.store.deletedIds.contains(ROOT_PROVIDER))
        assertTrue(
            fixture.store.listRequests.none { request ->
                request.role == RemoteObjectRoleV1.OWNERSHIP_ROOT ||
                    request.role == RemoteObjectRoleV1.OWNERSHIP_CLAIM ||
                    request.role == RemoteObjectRoleV1.OWNERSHIP_TOMBSTONE
            },
        )
    }

    @Test
    fun theEpochBaselineRemainsWhileItIsTheRetainedPrevious() = runCleanupTest { fixture ->
        fixture.seedEligible(count = 2)

        fixture.runBatch()

        assertTrue(fixture.store.contains(BASELINE_PROVIDER))
        assertEquals(
            BASELINE_PROVIDER,
            checkNotNull(fixture.previous).manifest.publicationProviderFileId,
        )
    }

    @Test
    fun aSupersededBaselineIsPrunedAndTheClaimKeepsItsEvidence() = runCleanupTest { fixture ->
        fixture.seedOrphan(
            providerFileId = BASELINE_PROVIDER,
            firstObservedAt = NOW,
            role = RemoteObjectRoleV1.PUBLICATION,
            verified = true,
        )

        val result = fixture.runBatch(previous = null)

        assertEquals(1, result.deletedCount)
        assertFalse(fixture.store.contains(BASELINE_PROVIDER))
        // The ownership claim keeps immutable creation evidence of that baseline.
        assertEquals(
            BASELINE_PROVIDER,
            fixture.ownership.claim.baselinePublicationProviderFileId,
        )
        assertEquals(fixture.baselineSha256, fixture.ownership.claim.baselinePublicationSha256)
    }

    @Test
    fun aSupersededEpochObjectIsDeletedUnderASelfContainedEpoch() =
        runCleanupTest(currentEpoch = 2) { fixture ->
            fixture.seedOrphan("provider-old-epoch", firstObservedAt = OLD, writerEpoch = 1)

            val result = fixture.runBatch()

            assertEquals(1, result.deletedCount)
            assertFalse(fixture.store.contains("provider-old-epoch"))
        }

    @Test
    fun aSupersededEpochObjectRemainsWhenTheEpochIsNotSelfContained() =
        runCleanupTest(currentEpoch = 2, retainedObjectEpoch = 1) { fixture ->
            fixture.seedOrphan("provider-old-epoch", firstObservedAt = OLD, writerEpoch = 1)

            val result = fixture.runBatch()

            assertEquals(0, result.deletedCount)
            assertEquals(1, result.blockers)
            assertTrue(fixture.store.contains("provider-old-epoch"))
        }

    @Test
    fun aDeletedObjectAlsoLosesItsLocalTransferState() = runCleanupTest { fixture ->
        fixture.seedOrphan("provider-old", firstObservedAt = OLD)

        fixture.runBatch()

        assertTrue(
            fixture.transferStore.objectsForLineage(LINEAGE).none {
                it.providerObjectId.value == "provider-old"
            },
        )
    }

    @Test
    fun aProviderDeleteFailureStopsTheBatchAndIsABlocker() = runCleanupTest { fixture ->
        fixture.seedOrphan("provider-old-a", firstObservedAt = OLD)
        fixture.seedOrphan("provider-old-b", firstObservedAt = OLD)
        fixture.store.deleteFailures["provider-old-a"] =
            RemoteBackupFailureCategory.RETRYABLE_PROVIDER

        val result = fixture.runBatch()

        assertEquals(0, result.deletedCount)
        assertEquals(1, result.blockers)
        assertTrue(fixture.store.contains("provider-old-a"))
        assertTrue(fixture.store.contains("provider-old-b"))
    }

    private fun runCleanupTest(
        currentEpoch: Long = 1,
        retainedObjectEpoch: Long = currentEpoch,
        block: suspend (CleanupFixture) -> Unit,
    ) = runBlocking {
        withTimeout(5_000) {
            val root = Files.createTempDirectory("namespace-safe-cleanup-test").toFile()
            try {
                block(CleanupFixture(root, currentEpoch, retainedObjectEpoch))
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private companion object {
        val NOW: Instant = Instant.ofEpochMilli(1_800_000_000_000)
        val OLD: Instant = NOW.minus(Duration.ofDays(30))
        val LINEAGE: CloudLineageId = CloudLineageId.parse(RemoteBackupTestFixtures.LINEAGE_ID)
        const val ROOT_PROVIDER = RemoteBackupTestFixtures.ROOT_PROVIDER_ID
        const val BASELINE_PROVIDER = RemoteBackupTestFixtures.BASELINE_PROVIDER_ID
        const val CURRENT_BASE_PROVIDER = "provider-base-a"
        const val FALLBACK_BASE_PROVIDER = "provider-base-b"
        const val SEGMENT_PROVIDER = "provider-segment-54-55"
    }
}

/**
 * One authenticated epoch holding a retained publication pair, the objects
 * those publications name, and whatever residue a test seeds beside them.
 *
 * The ownership chain is real: cleanup resolves and authenticates it through
 * [DefaultOwnershipChainStore] exactly as production does, and [tipChecks]
 * counts how often it did.
 */
private class CleanupFixture(
    root: File,
    private val currentEpoch: Long,
    retainedObjectEpoch: Long,
) {
    private val codec = RemoteBackupTestFixtures.publicationCodec
    private val ownershipCodec = RemoteBackupTestFixtures.ownershipCodec
    private val key: VaultKey = RemoteBackupTestFixtures.contentKey
    private val envelope: VaultKeyEnvelope = RemoteBackupTestFixtures.envelope()
    private val lineageId = CloudLineageId.parse(RemoteBackupTestFixtures.LINEAGE_ID)
    private val deviceId = CloudDeviceId.parse(RemoteBackupTestFixtures.DEVICE_ID)

    val store = FakeCreateOnlyBackupObjectStore(File(root, "provider").also { it.mkdirs() })
    val transferStore = InMemoryRemoteBackupTransferStore()

    private val baselineBytes: ByteArray = encode(baselineManifest())
    val baselineSha256: String = hexDigestOf(baselineBytes)
    val ownership: VerifiedOwnershipClaim = seedOwnership()
    private val successorBytes: ByteArray = encode(successorManifest())

    val previous: VerifiedPublication? = codec.verify(baselineBytes, key)
    val current: VerifiedPublication = codec.verify(successorBytes, key)

    private val chainStore = CountingOwnershipChainStore(
        DefaultOwnershipChainStore(store, ownershipCodec),
    )

    private val cleanup: NamespaceSafeRemoteCleanup = DefaultNamespaceSafeRemoteCleanup(
        objectStore = store,
        chainStore = chainStore,
        transferStore = transferStore,
        lineageId = lineageId,
        rootClaimProviderId = ProviderObjectId.of(RemoteBackupTestFixtures.ROOT_PROVIDER_ID),
        contentKey = key,
    )

    val tipChecks: Int get() = chainStore.resolveCount

    init {
        seedPublication(RemoteBackupTestFixtures.BASELINE_PROVIDER_ID, baselineBytes)
        seedPublication(RemoteBackupTestFixtures.NEXT_PUBLICATION_PROVIDER_ID, successorBytes)
        retainedInventory().forEach { item ->
            seedObject(
                providerFileId = item.providerFileId,
                logicalObjectId = item.logicalObjectId,
                role = item.role,
                writerEpoch = retainedObjectEpoch,
                ownerDeviceId = deviceId,
            )
        }
    }

    suspend fun runBatch(
        previous: VerifiedPublication? = this.previous,
        now: Instant = NOW,
    ): CleanupBatchResult = cleanup.runBatch(ownership, current, previous, now)

    fun retainedProviderIds(): List<String> = listOf(
        RemoteBackupTestFixtures.BASELINE_PROVIDER_ID,
        RemoteBackupTestFixtures.NEXT_PUBLICATION_PROVIDER_ID,
    ) + retainedInventory().map(RemoteInventoryItemV1::providerFileId)

    /** Seeds [count] provable, old-enough, same-epoch orphans of this device. */
    fun seedEligible(count: Int) {
        repeat(count) { index ->
            seedOrphan("provider-orphan-$index", firstObservedAt = OLD)
        }
    }

    fun seedOrphan(
        providerFileId: String,
        firstObservedAt: Instant?,
        role: RemoteObjectRoleV1 = RemoteObjectRoleV1.SNAPSHOT,
        writerEpoch: Long = currentEpoch,
        ownerDeviceId: CloudDeviceId = deviceId,
        /** False models a candidate whose upload was never proven complete. */
        verified: Boolean = false,
    ) {
        val logicalObjectId = RemoteLogicalObjectId.new()
        seedObject(providerFileId, logicalObjectId.value, role, writerEpoch, ownerDeviceId)
        firstObservedAt?.let { observed ->
            transferStore.seed(
                RemoteBackupObject(
                    lineageId = lineageId,
                    logicalObjectId = logicalObjectId,
                    providerObjectId = ProviderObjectId.of(providerFileId),
                    role = role,
                    writerEpoch = WriterEpoch(writerEpoch),
                    ownerDeviceId = ownerDeviceId,
                    operationId = RemoteBackupTestFixtures.OPERATION_ID,
                    firstGeneration = BackupGeneration(53),
                    lastGeneration = BackupGeneration(53),
                    frameLength = 16,
                    frameSha256 = Sha256Digest.of(RemoteBackupTestFixtures.DIGEST_A),
                    lifecycle = if (verified) {
                        RemoteObjectLifecycle.VERIFIED
                    } else {
                        RemoteObjectLifecycle.PLANNED
                    },
                    resumableSessionUri = null,
                    uploadedBytes = 16,
                    createdAt = observed,
                    verifiedAt = observed.takeIf { verified },
                ),
            )
        }
    }

    /** An object the provider index describes with unusable role metadata. */
    fun seedMalformed(providerFileId: String) = store.put(
        providerObjectId = providerFileId,
        bytes = ByteArray(8) { it.toByte() },
        metadata = RemoteListedObject(
            providerObjectId = ProviderObjectId.of(providerFileId),
            logicalObjectId = null,
            role = RemoteObjectRoleV1.SNAPSHOT,
            writerEpoch = null,
            ownerDeviceId = null,
        ),
        lineageId = lineageId,
    )

    /** Occupies the reserved successor slot so the tip becomes another device. */
    fun replaceTipWithAnotherDevice() {
        val successor = RemoteBackupTestFixtures.successorOf(
            predecessor = ownership,
            activeDeviceId = RemoteBackupTestFixtures.OTHER_DEVICE_ID,
        )
        val encoded = ownershipCodec.encode(successor, key)
        store.put(
            providerObjectId = successor.providerFileId,
            bytes = encoded,
            metadata = RemoteBackupTestFixtures.claimMetadata(
                ownershipCodec.readPublicHeader(encoded),
            ),
            lineageId = lineageId,
        )
    }

    private fun seedOwnership(): VerifiedOwnershipClaim {
        val rootEncoded = ownershipCodec.encode(
            RemoteBackupTestFixtures.activeRoot(baselinePublicationSha256 = baselineSha256),
            key,
        )
        val root = ownershipCodec.verify(rootEncoded, key)
        store.put(
            providerObjectId = root.claim.providerFileId,
            bytes = rootEncoded,
            metadata = RemoteBackupTestFixtures.claimMetadata(root.header),
            lineageId = lineageId,
        )
        if (currentEpoch == 1L) return root
        val successorEncoded = ownershipCodec.encode(
            RemoteBackupTestFixtures.successorOf(root),
            key,
        )
        val successor = ownershipCodec.verifySuccessor(root, successorEncoded, key)
        store.put(
            providerObjectId = successor.claim.providerFileId,
            bytes = successorEncoded,
            metadata = RemoteBackupTestFixtures.claimMetadata(successor.header),
            lineageId = lineageId,
        )
        return successor
    }

    private fun seedPublication(providerFileId: String, bytes: ByteArray) = store.put(
        providerObjectId = providerFileId,
        bytes = bytes,
        metadata = RemoteListedObject(
            providerObjectId = ProviderObjectId.of(providerFileId),
            logicalObjectId = null,
            role = RemoteObjectRoleV1.PUBLICATION,
            writerEpoch = WriterEpoch(currentEpoch),
            ownerDeviceId = deviceId,
        ),
        lineageId = lineageId,
    )

    private fun seedObject(
        providerFileId: String,
        logicalObjectId: String?,
        role: RemoteObjectRoleV1,
        writerEpoch: Long,
        ownerDeviceId: CloudDeviceId,
    ) = store.put(
        providerObjectId = providerFileId,
        bytes = ByteArray(16) { it.toByte() },
        metadata = RemoteListedObject(
            providerObjectId = ProviderObjectId.of(providerFileId),
            logicalObjectId = logicalObjectId,
            role = role,
            writerEpoch = WriterEpoch(writerEpoch),
            ownerDeviceId = ownerDeviceId,
        ),
        lineageId = lineageId,
    )

    private fun encode(manifest: PublicationManifestV1): ByteArray = codec.encode(
        manifest.copy(bootstrapSha256 = codec.bootstrapSha256(manifest, envelope)),
        envelope,
        key,
    )

    private fun retainedInventory(): List<RemoteInventoryItemV1> = listOf(
        RemoteInventoryItemV1(
            logicalObjectId = RemoteBackupTestFixtures.BASE_A_LOGICAL_ID,
            providerFileId = "provider-base-a",
            role = RemoteObjectRoleV1.SNAPSHOT,
            firstGeneration = 53,
            lastGeneration = 53,
            frameLength = 512,
            frameSha256 = RemoteBackupTestFixtures.DIGEST_A,
        ),
        RemoteInventoryItemV1(
            logicalObjectId = RemoteBackupTestFixtures.BASE_B_LOGICAL_ID,
            providerFileId = "provider-base-b",
            role = RemoteObjectRoleV1.SNAPSHOT,
            firstGeneration = 53,
            lastGeneration = 53,
            frameLength = 512,
            frameSha256 = RemoteBackupTestFixtures.DIGEST_A,
        ),
        RemoteInventoryItemV1(
            logicalObjectId = SEGMENT_LOGICAL_ID,
            providerFileId = "provider-segment-54-55",
            role = RemoteObjectRoleV1.SEGMENT,
            firstGeneration = 54,
            lastGeneration = 55,
            frameLength = 256,
            frameSha256 = RemoteBackupTestFixtures.DIGEST_A,
        ),
    ).sortedBy(RemoteInventoryItemV1::logicalObjectId)

    private fun baselineManifest(): PublicationManifestV1 = PublicationManifestV1(
        bootstrapSha256 = ZERO_SHA256,
        lineageId = RemoteBackupTestFixtures.LINEAGE_ID,
        sourceVaultId = RemoteBackupTestFixtures.VAULT_ID,
        writerEpoch = currentEpoch,
        activeDeviceId = RemoteBackupTestFixtures.DEVICE_ID,
        publicationProviderFileId = RemoteBackupTestFixtures.BASELINE_PROVIDER_ID,
        publicationId = RemoteBackupTestFixtures.BASELINE_PUBLICATION_ID,
        publicationSequence = 0,
        predecessorPublicationProviderFileId = null,
        predecessorPublicationId = null,
        predecessorPublicationSha256 = null,
        baseline = true,
        plannedClaimProviderFileId = RemoteBackupTestFixtures.ROOT_PROVIDER_ID,
        plannedClaimId = RemoteBackupTestFixtures.ROOT_CLAIM_ID,
        predecessorClaimProviderFileId = null,
        predecessorClaimId = null,
        predecessorClaimSha256 = null,
        ownershipClaimProviderFileId = null,
        ownershipClaimId = null,
        ownershipClaimSha256 = null,
        localGeneration = 55,
        publicationOperationId = RemoteBackupTestFixtures.OPERATION_ID,
        currentBaseObjectId = RemoteBackupTestFixtures.BASE_A_LOGICAL_ID,
        fallbackBaseObjectId = RemoteBackupTestFixtures.BASE_B_LOGICAL_ID,
        inventory = retainedInventory(),
        recoveryCredentialGeneration = 0,
    )

    private fun successorManifest(): PublicationManifestV1 = baselineManifest().copy(
        publicationProviderFileId = RemoteBackupTestFixtures.NEXT_PUBLICATION_PROVIDER_ID,
        publicationId = RemoteBackupTestFixtures.NEXT_PUBLICATION_ID,
        publicationSequence = 1,
        predecessorPublicationProviderFileId = RemoteBackupTestFixtures.BASELINE_PROVIDER_ID,
        predecessorPublicationId = RemoteBackupTestFixtures.BASELINE_PUBLICATION_ID,
        predecessorPublicationSha256 = baselineSha256,
        baseline = false,
        plannedClaimProviderFileId = null,
        plannedClaimId = null,
        ownershipClaimProviderFileId = ownership.claim.providerFileId,
        ownershipClaimId = ownership.claim.claimId,
        ownershipClaimSha256 = ownership.completeSha256.value,
    )

    private companion object {
        const val SEGMENT_LOGICAL_ID = "00000000-0000-4000-8000-0000000000c3"
        const val ZERO_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"
        val NOW: Instant = Instant.ofEpochMilli(1_800_000_000_000)
        val OLD: Instant = NOW.minus(Duration.ofDays(30))
    }
}

/** Counts how often cleanup authenticated the ownership tip. */
private class CountingOwnershipChainStore(
    private val delegate: OwnershipChainStore,
) : OwnershipChainStore by delegate {
    var resolveCount = 0
        private set

    override suspend fun resolve(
        rootProviderId: ProviderObjectId,
        contentKey: VaultKey,
    ): OwnershipResolution {
        resolveCount += 1
        return delegate.resolve(rootProviderId, contentKey)
    }
}
