package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.WriterEpoch
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationCatalogTest {

    @Test
    fun discoveryReturnsOnlyCandidatesForTheExactLineageEpochAndClaim() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()
        store.seedPublication(BASELINE_PROVIDER, fixture.baselineBytes)
        store.seedPublication(OTHER_CLAIM_PROVIDER, fixture.foreignClaimBaselineBytes)

        val discovery = catalog.discoverBootstraps(LINEAGE, EPOCH, ROOT_PROVIDER)

        val candidates = (discovery as PublicationCandidateDiscovery.Discovered).candidates
        assertEquals(1, candidates.size)
        assertEquals(BASELINE_PROVIDER, candidates.single().providerObjectId)
        assertEquals(
            RemoteBackupTestFixtures.ROOT_PROVIDER_ID,
            candidates.single().bootstrap.plannedClaimProviderFileId,
        )
    }

    @Test
    fun discoveryRejectsMoreThanOneHundredTwentyEightCandidates() = runCatalogTest { catalog, store ->
        repeat(DefaultPublicationCatalog.MAX_CANDIDATES_PER_EPOCH + 1) { index ->
            store.put(
                providerObjectId = "provider-publication-$index",
                bytes = ByteArray(8) { it.toByte() },
                metadata = RemoteBackupTestFixtures.publicationMetadata(
                    "provider-publication-$index",
                    EPOCH.value,
                ),
                lineageId = LINEAGE,
            )
        }

        val discovery = catalog.discoverBootstraps(LINEAGE, EPOCH, ROOT_PROVIDER)

        assertEquals(
            PublicationCandidateDiscovery.Blocked(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            ),
            discovery,
        )
    }

    @Test
    fun discoveryTranslatesEveryListFailureIntoABoundedBlockedResult() = runCatalogTest { catalog, store ->
        store.listFailure = { IllegalStateException("provider list failed") }

        val discovery = catalog.discoverBootstraps(LINEAGE, EPOCH, ROOT_PROVIDER)

        assertEquals(
            PublicationCandidateDiscovery.Blocked(
                RemoteBackupFailureCategory.RETRYABLE_PROVIDER,
            ),
            discovery,
        )
    }

    @Test
    fun discoveryTranslatesBoundedReadFailuresIntoABlockedResult() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()
        store.seedPublication(BASELINE_PROVIDER, fixture.baselineBytes)
        store.readFailures[BASELINE_PROVIDER.value] = RemoteBackupFailureCategory.PROVIDER_STORAGE

        val discovery = catalog.discoverBootstraps(LINEAGE, EPOCH, ROOT_PROVIDER)

        assertEquals(
            PublicationCandidateDiscovery.Blocked(
                RemoteBackupFailureCategory.PROVIDER_STORAGE,
            ),
            discovery,
        )
    }

    @Test
    fun retainedPairAgreementResolvesTheCurrentAndPreviousPublications() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()
        store.seedPublication(BASELINE_PROVIDER, fixture.baselineBytes)
        store.seedPublication(NEXT_PROVIDER, fixture.successorBytes)

        val result = catalog.resolve(
            fixture.ownership,
            store.candidates(BASELINE_PROVIDER, NEXT_PROVIDER),
            CONTENT_KEY,
        )

        val resolved = result as PublicationResolution.Resolved
        assertEquals(1L, resolved.current.manifest.publicationSequence)
        assertEquals(0L, checkNotNull(resolved.previous).manifest.publicationSequence)
        assertEquals(
            fixture.baselineSha256,
            checkNotNull(resolved.previous).completeSha256.value,
        )
    }

    @Test
    fun aLoneBaselineResolvesWithoutARetainedPredecessor() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()
        store.seedPublication(BASELINE_PROVIDER, fixture.baselineBytes)

        val result = catalog.resolve(
            fixture.ownership,
            store.candidates(BASELINE_PROVIDER),
            CONTENT_KEY,
        )

        val resolved = result as PublicationResolution.Resolved
        assertTrue(resolved.current.manifest.baseline)
        assertNull(resolved.previous)
    }

    @Test
    fun duplicatePublicationSequenceFailsClosed() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()
        store.seedPublication(BASELINE_PROVIDER, fixture.baselineBytes)
        store.seedPublication(
            DUPLICATE_BASELINE_PROVIDER,
            fixture.duplicateBaselineBytes,
        )

        val result = catalog.resolve(
            fixture.ownership,
            store.candidates(BASELINE_PROVIDER, DUPLICATE_BASELINE_PROVIDER),
            CONTENT_KEY,
        )

        assertEquals(
            PublicationResolution.Failed(
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            ),
            result,
        )
    }

    @Test
    fun twoChildrenOfOneRetainedPredecessorFailClosed() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()
        store.seedPublication(BASELINE_PROVIDER, fixture.baselineBytes)
        store.seedPublication(NEXT_PROVIDER, fixture.successorBytes)
        store.seedPublication(FORK_PROVIDER, fixture.forkedSuccessorBytes)

        val result = catalog.resolve(
            fixture.ownership,
            store.candidates(BASELINE_PROVIDER, NEXT_PROVIDER, FORK_PROVIDER),
            CONTENT_KEY,
        )

        assertEquals(
            PublicationResolution.Failed(
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            ),
            result,
        )
    }

    @Test
    fun competingHighestPublicationFailsClosed() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()
        store.seedPublication(BASELINE_PROVIDER, fixture.baselineBytes)
        store.seedPublication(NEXT_PROVIDER, fixture.successorBytes)
        store.seedPublication(FORK_PROVIDER, fixture.competingHighestBytes)

        val result = catalog.resolve(
            fixture.ownership,
            store.candidates(BASELINE_PROVIDER, NEXT_PROVIDER, FORK_PROVIDER),
            CONTENT_KEY,
        )

        assertEquals(
            PublicationResolution.Failed(
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            ),
            result,
        )
    }

    @Test
    fun sequenceGapFailsClosed() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()
        store.seedPublication(BASELINE_PROVIDER, fixture.baselineBytes)
        store.seedPublication(GAP_PROVIDER, fixture.gappedSuccessorBytes)

        val result = catalog.resolve(
            fixture.ownership,
            store.candidates(BASELINE_PROVIDER, GAP_PROVIDER),
            CONTENT_KEY,
        )

        assertEquals(
            PublicationResolution.Failed(
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            ),
            result,
        )
    }

    @Test
    fun missingRetainedPredecessorFailsClosed() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()
        store.seedPublication(NEXT_PROVIDER, fixture.successorBytes)

        val result = catalog.resolve(
            fixture.ownership,
            store.candidates(NEXT_PROVIDER),
            CONTENT_KEY,
        )

        assertEquals(
            PublicationResolution.Failed(
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            ),
            result,
        )
    }

    @Test
    fun localGenerationRegressionAcrossTheRetainedPairFailsClosed() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()
        store.seedPublication(BASELINE_PROVIDER, fixture.baselineBytes)
        store.seedPublication(NEXT_PROVIDER, fixture.regressedSuccessorBytes)

        val result = catalog.resolve(
            fixture.ownership,
            store.candidates(BASELINE_PROVIDER, NEXT_PROVIDER),
            CONTENT_KEY,
        )

        assertEquals(
            PublicationResolution.Failed(
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            ),
            result,
        )
    }

    @Test
    fun candidatesNamingAnotherClaimOrDeviceCannotOutrankTheRetainedPair() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()
        store.seedPublication(BASELINE_PROVIDER, fixture.baselineBytes)
        store.seedPublication(FORK_PROVIDER, fixture.otherDeviceSuccessorBytes)

        val result = catalog.resolve(
            fixture.ownership,
            store.candidates(BASELINE_PROVIDER, FORK_PROVIDER),
            CONTENT_KEY,
        )

        val resolved = result as PublicationResolution.Resolved
        assertTrue(resolved.current.manifest.baseline)
        assertNull(resolved.previous)
    }

    @Test
    fun resolutionFailsClosedWhenACandidateCannotBeAuthenticated() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()
        store.seedPublication(BASELINE_PROVIDER, fixture.baselineBytes)

        val result = catalog.resolve(
            fixture.ownership,
            store.candidates(BASELINE_PROVIDER),
            RemoteBackupTestFixtures.otherKey,
        )

        assertEquals(
            PublicationResolution.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            ),
            result,
        )
    }

    @Test
    fun createPublishesAtTheExactPreGeneratedProviderIdentity() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()

        val result = catalog.create(
            BASELINE_PROVIDER,
            remoteBytesOf(fixture.baselineBytes),
            CONTENT_KEY,
        )

        val created = result as PublicationCreateResult.Created
        assertEquals(0L, created.publication.manifest.publicationSequence)
        assertEquals(listOf(BASELINE_PROVIDER.value), store.createdIds)
        assertEquals(listOf("BASELINE"), store.authorityEvents)
    }

    @Test
    fun createReportsAnIdenticalOccupantAsOccupiedByExpectedBytes() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()
        store.seedPublication(BASELINE_PROVIDER, fixture.baselineBytes)

        val result = catalog.create(
            BASELINE_PROVIDER,
            remoteBytesOf(fixture.baselineBytes),
            CONTENT_KEY,
        )

        val occupied = result as PublicationCreateResult.OccupiedByExpected
        assertEquals(fixture.baselineSha256, occupied.publication.completeSha256.value)
    }

    @Test
    fun createReportsADifferentOccupantWithoutReplacingIt() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()
        store.seedPublication(BASELINE_PROVIDER, fixture.duplicateBaselineBytes)

        val result = catalog.create(
            BASELINE_PROVIDER,
            remoteBytesOf(fixture.baselineBytes),
            CONTENT_KEY,
        )

        assertEquals(PublicationCreateResult.OccupiedByDifferent, result)
        assertTrue(
            store.requireBytes(BASELINE_PROVIDER.value)
                .contentEquals(fixture.duplicateBaselineBytes),
        )
    }

    @Test
    fun createSurfacesBoundedProviderFailures() = runCatalogTest { catalog, store ->
        val fixture = EpochOnePublicationFixture()
        store.createFailures[BASELINE_PROVIDER.value] =
            RemoteBackupFailureCategory.RETRYABLE_PROVIDER

        val result = catalog.create(
            BASELINE_PROVIDER,
            remoteBytesOf(fixture.baselineBytes),
            CONTENT_KEY,
        )

        assertEquals(
            PublicationCreateResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
            result,
        )
    }

    private fun runCatalogTest(
        block: suspend (PublicationCatalog, FakeCreateOnlyBackupObjectStore) -> Unit,
    ) = runBlocking {
        withTimeout(5_000) {
            val root = Files.createTempDirectory("publication-catalog-test").toFile()
            try {
                val store = FakeCreateOnlyBackupObjectStore(root)
                block(
                    DefaultPublicationCatalog(store, RemoteBackupTestFixtures.publicationCodec),
                    store,
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private companion object {
        val CONTENT_KEY: VaultKey = RemoteBackupTestFixtures.contentKey
        val LINEAGE: CloudLineageId =
            CloudLineageId.parse(RemoteBackupTestFixtures.LINEAGE_ID)
        val EPOCH = WriterEpoch(1)
        val ROOT_PROVIDER: ProviderObjectId =
            ProviderObjectId.of(RemoteBackupTestFixtures.ROOT_PROVIDER_ID)
        val BASELINE_PROVIDER: ProviderObjectId =
            ProviderObjectId.of(RemoteBackupTestFixtures.BASELINE_PROVIDER_ID)
        val NEXT_PROVIDER: ProviderObjectId =
            ProviderObjectId.of(RemoteBackupTestFixtures.NEXT_PUBLICATION_PROVIDER_ID)
        val DUPLICATE_BASELINE_PROVIDER: ProviderObjectId =
            ProviderObjectId.of("provider-baseline-duplicate")
        val FORK_PROVIDER: ProviderObjectId = ProviderObjectId.of("provider-publication-fork")
        val GAP_PROVIDER: ProviderObjectId = ProviderObjectId.of("provider-publication-gap")
        val OTHER_CLAIM_PROVIDER: ProviderObjectId =
            ProviderObjectId.of("provider-publication-other-claim")
    }
}

private fun FakeCreateOnlyBackupObjectStore.seedPublication(
    providerObjectId: ProviderObjectId,
    bytes: ByteArray,
) = put(
    providerObjectId = providerObjectId.value,
    bytes = bytes,
    metadata = RemoteBackupTestFixtures.publicationMetadata(providerObjectId.value, 1),
    lineageId = CloudLineageId.parse(RemoteBackupTestFixtures.LINEAGE_ID),
)

private fun FakeCreateOnlyBackupObjectStore.candidates(
    vararg providerObjectIds: ProviderObjectId,
): List<PublicationCandidate> = providerObjectIds.map { providerObjectId ->
    PublicationCandidate(
        providerObjectId = providerObjectId,
        bootstrap = RemoteBackupTestFixtures.publicationCodec.readBootstrap(
            requireBytes(providerObjectId.value),
        ),
    )
}

/**
 * A baseline publication, the ownership root that digests it, and the deviant
 * successors each ambiguity rule must reject. The baseline is encoded before
 * the root so the root can bind the baseline digest without a cycle.
 */
private class EpochOnePublicationFixture {
    private val codec = RemoteBackupTestFixtures.publicationCodec
    private val key = RemoteBackupTestFixtures.contentKey
    val envelope: VaultKeyEnvelope = RemoteBackupTestFixtures.envelope()

    val baselineBytes: ByteArray = encode(baselineManifest())
    val baselineSha256: String = hexDigestOf(baselineBytes)

    val ownership: VerifiedOwnershipClaim = RemoteBackupTestFixtures.ownershipCodec.let { chain ->
        chain.verify(
            chain.encode(
                RemoteBackupTestFixtures.activeRoot(
                    baselinePublicationSha256 = baselineSha256,
                ),
                key,
            ),
            key,
        )
    }

    val successorBytes: ByteArray = encode(successorManifest())

    val duplicateBaselineBytes: ByteArray = encode(
        baselineManifest(
            publicationId = RemoteBackupTestFixtures.NEXT_PUBLICATION_ID,
            providerFileId = "provider-baseline-duplicate",
        ),
    )

    val forkedSuccessorBytes: ByteArray = encode(
        successorManifest(
            publicationId = FORK_PUBLICATION_ID,
            providerFileId = "provider-publication-fork",
        ),
    )

    val competingHighestBytes: ByteArray = encode(
        successorManifest(
            publicationId = FORK_PUBLICATION_ID,
            providerFileId = "provider-publication-fork",
            predecessorPublicationId = RemoteBackupTestFixtures.NEXT_PUBLICATION_ID,
            predecessorProviderFileId = RemoteBackupTestFixtures.NEXT_PUBLICATION_PROVIDER_ID,
            predecessorSha256 = RemoteBackupTestFixtures.DIGEST_A,
        ),
    )

    val gappedSuccessorBytes: ByteArray = encode(
        successorManifest(
            sequence = 2,
            publicationId = FORK_PUBLICATION_ID,
            providerFileId = "provider-publication-gap",
            predecessorPublicationId = RemoteBackupTestFixtures.NEXT_PUBLICATION_ID,
            predecessorProviderFileId = RemoteBackupTestFixtures.NEXT_PUBLICATION_PROVIDER_ID,
            predecessorSha256 = RemoteBackupTestFixtures.DIGEST_A,
        ),
    )

    val regressedSuccessorBytes: ByteArray = encode(successorManifest(localGeneration = 40))

    val otherDeviceSuccessorBytes: ByteArray = encode(
        successorManifest(
            publicationId = FORK_PUBLICATION_ID,
            providerFileId = "provider-publication-fork",
            activeDeviceId = RemoteBackupTestFixtures.OTHER_DEVICE_ID,
        ),
    )

    val foreignClaimBaselineBytes: ByteArray = encode(
        baselineManifest(
            publicationId = FORK_PUBLICATION_ID,
            providerFileId = "provider-publication-other-claim",
            plannedClaimProviderFileId = "provider-other-root",
        ),
    )

    private fun encode(manifest: PublicationManifestV1): ByteArray = codec.encode(
        manifest.copy(bootstrapSha256 = codec.bootstrapSha256(manifest, envelope)),
        envelope,
        key,
    )

    private fun baselineManifest(
        publicationId: String = RemoteBackupTestFixtures.BASELINE_PUBLICATION_ID,
        providerFileId: String = RemoteBackupTestFixtures.BASELINE_PROVIDER_ID,
        plannedClaimProviderFileId: String = RemoteBackupTestFixtures.ROOT_PROVIDER_ID,
    ): PublicationManifestV1 = PublicationManifestV1(
        bootstrapSha256 = ZERO_SHA256,
        lineageId = RemoteBackupTestFixtures.LINEAGE_ID,
        sourceVaultId = RemoteBackupTestFixtures.VAULT_ID,
        writerEpoch = 1,
        activeDeviceId = RemoteBackupTestFixtures.DEVICE_ID,
        publicationProviderFileId = providerFileId,
        publicationId = publicationId,
        publicationSequence = 0,
        predecessorPublicationProviderFileId = null,
        predecessorPublicationId = null,
        predecessorPublicationSha256 = null,
        baseline = true,
        plannedClaimProviderFileId = plannedClaimProviderFileId,
        plannedClaimId = RemoteBackupTestFixtures.ROOT_CLAIM_ID,
        predecessorClaimProviderFileId = null,
        predecessorClaimId = null,
        predecessorClaimSha256 = null,
        ownershipClaimProviderFileId = null,
        ownershipClaimId = null,
        ownershipClaimSha256 = null,
        localGeneration = LOCAL_GENERATION,
        publicationOperationId = RemoteBackupTestFixtures.OPERATION_ID,
        currentBaseObjectId = RemoteBackupTestFixtures.BASE_A_LOGICAL_ID,
        fallbackBaseObjectId = RemoteBackupTestFixtures.BASE_B_LOGICAL_ID,
        inventory = inventory(LOCAL_GENERATION),
        recoveryCredentialGeneration = 0,
    )

    private fun successorManifest(
        sequence: Long = 1,
        publicationId: String = RemoteBackupTestFixtures.NEXT_PUBLICATION_ID,
        providerFileId: String = RemoteBackupTestFixtures.NEXT_PUBLICATION_PROVIDER_ID,
        predecessorPublicationId: String = RemoteBackupTestFixtures.BASELINE_PUBLICATION_ID,
        predecessorProviderFileId: String = RemoteBackupTestFixtures.BASELINE_PROVIDER_ID,
        predecessorSha256: String = baselineSha256,
        localGeneration: Long = LOCAL_GENERATION,
        activeDeviceId: String = RemoteBackupTestFixtures.DEVICE_ID,
    ): PublicationManifestV1 = PublicationManifestV1(
        bootstrapSha256 = ZERO_SHA256,
        lineageId = RemoteBackupTestFixtures.LINEAGE_ID,
        sourceVaultId = RemoteBackupTestFixtures.VAULT_ID,
        writerEpoch = 1,
        activeDeviceId = activeDeviceId,
        publicationProviderFileId = providerFileId,
        publicationId = publicationId,
        publicationSequence = sequence,
        predecessorPublicationProviderFileId = predecessorProviderFileId,
        predecessorPublicationId = predecessorPublicationId,
        predecessorPublicationSha256 = predecessorSha256,
        baseline = false,
        plannedClaimProviderFileId = null,
        plannedClaimId = null,
        predecessorClaimProviderFileId = null,
        predecessorClaimId = null,
        predecessorClaimSha256 = null,
        ownershipClaimProviderFileId = RemoteBackupTestFixtures.ROOT_PROVIDER_ID,
        ownershipClaimId = RemoteBackupTestFixtures.ROOT_CLAIM_ID,
        ownershipClaimSha256 = ownership.completeSha256.value,
        localGeneration = localGeneration,
        publicationOperationId = RemoteBackupTestFixtures.OPERATION_ID,
        currentBaseObjectId = RemoteBackupTestFixtures.BASE_A_LOGICAL_ID,
        fallbackBaseObjectId = RemoteBackupTestFixtures.BASE_B_LOGICAL_ID,
        inventory = inventory(localGeneration),
        recoveryCredentialGeneration = 1,
    )

    private fun inventory(generation: Long): List<RemoteInventoryItemV1> = listOf(
        RemoteInventoryItemV1(
            logicalObjectId = RemoteBackupTestFixtures.BASE_A_LOGICAL_ID,
            providerFileId = "provider-base-a",
            role = RemoteObjectRoleV1.SNAPSHOT,
            firstGeneration = generation,
            lastGeneration = generation,
            frameLength = 512,
            frameSha256 = RemoteBackupTestFixtures.DIGEST_A,
        ),
        RemoteInventoryItemV1(
            logicalObjectId = RemoteBackupTestFixtures.BASE_B_LOGICAL_ID,
            providerFileId = "provider-base-b",
            role = RemoteObjectRoleV1.SNAPSHOT,
            firstGeneration = generation,
            lastGeneration = generation,
            frameLength = 512,
            frameSha256 = RemoteBackupTestFixtures.DIGEST_A,
        ),
    )

}

private const val LOCAL_GENERATION = 42L
private const val ZERO_SHA256 =
    "0000000000000000000000000000000000000000000000000000000000000000"
private val FORK_PUBLICATION_ID = PublicationId.new().value
