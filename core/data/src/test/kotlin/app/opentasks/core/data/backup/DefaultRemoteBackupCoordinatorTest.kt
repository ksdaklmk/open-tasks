package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.domain.BackupCoordinator
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.CreateSmallResult
import app.opentasks.core.domain.ImmutableUploadRequest
import app.opentasks.core.domain.ImmutableUploadResult
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.RemoteBackupConnectResult
import app.opentasks.core.domain.RemoteBackupObject
import app.opentasks.core.domain.RemoteBackupRunResult
import app.opentasks.core.domain.RemoteListedObject
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.RemoteObjectLifecycle
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.VaultId
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRemoteBackupCoordinatorTest {

    @Test
    fun ownershipChangeAfterPublicationPreventsCheckpoint() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        val checkpointBefore = fixture.currentPublicationId()
        fixture.store.afterPublicationCreate = { fixture.takeOwnershipWithAnotherDevice() }

        val result = fixture.coordinator.run(fixture.store)

        assertEquals(RemoteBackupRunResult.OwnershipLost, result)
        assertEquals(checkpointBefore, fixture.currentPublicationId())
        assertEquals(53L, fixture.lastVerifiedGeneration())
        assertFalse(fixture.remoteStateStore.operationPhases.contains("CHECKPOINTED"))
    }

    @Test
    fun concurrentRunsJoinOneFlightAndPublishOneSuccessor() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.store.beforeUpload = {
            entered.complete(Unit)
            release.await()
        }

        val results = coroutineScope {
            val first = async { fixture.coordinator.run(fixture.store) }
            entered.await()
            val second = async { fixture.coordinator.run(fixture.store) }
            // Let the second caller reach the coordinator's single-flight gate.
            yield()
            release.complete(Unit)
            listOf(first.await(), second.await())
        }

        assertEquals(RemoteBackupRunResult.Verified(BackupGeneration(55)), results[0])
        assertEquals(results[0], results[1])
        assertEquals(2, fixture.publicationsCreated())
        assertEquals(1, fixture.provider.uploadRequests.size)
    }

    @Test
    fun stageTwoIsAskedForALocalGenerationBeforeAnythingIsUploaded() = runPublishTest { fixture ->
        fixture.stage2.onRequest = { fixture.seedLocalSegment(54, 55) }
        val requestsBefore = fixture.stage2.requestCount

        fixture.coordinator.run(fixture.store)

        assertEquals(requestsBefore + 1, fixture.stage2.requestCount)
        assertTrue(
            fixture.provider.callOrder
                .take(fixture.stage2.providerCallsBeforeRequest)
                .none { it.startsWith("uploadImmutable:") },
        )
        assertEquals(1, fixture.provider.uploadRequests.size)
    }

    @Test
    fun anUnchangedGenerationPublishesNothing() = runPublishTest { fixture ->
        val result = fixture.coordinator.run(fixture.store)

        assertEquals(RemoteBackupRunResult.NoChanges, result)
        assertEquals(1, fixture.publicationsCreated())
        assertTrue(fixture.provider.uploadRequests.isEmpty())
    }

    @Test
    fun theOwnershipTipIsAuthenticatedBeforeAnyCandidateIsUploaded() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.provider.callOrder.clear()

        fixture.coordinator.run(fixture.store)

        val rootRead = fixture.provider.callOrder.indexOf("readSmall:" + fixture.rootProviderId())
        val firstUpload =
            fixture.provider.callOrder.indexOfFirst { it.startsWith("uploadImmutable:") }
        assertTrue(rootRead >= 0)
        assertTrue(firstUpload > rootRead)
    }

    @Test
    fun aForeignTipPublishesNothingAndReportsOwnershipLost() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.takeOwnershipWithAnotherDevice()

        val result = fixture.coordinator.run(fixture.store)

        assertEquals(RemoteBackupRunResult.OwnershipLost, result)
        assertTrue(fixture.provider.uploadRequests.isEmpty())
        assertEquals(1, fixture.publicationsCreated())
    }

    @Test
    fun aTerminatedOwnershipChainPublishesNothing() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.terminateOwnership()

        val result = fixture.coordinator.run(fixture.store)

        assertEquals(RemoteBackupRunResult.Terminated, result)
        assertTrue(fixture.provider.uploadRequests.isEmpty())
    }

    @Test
    fun everyNewCandidateIsReadBackBeforeThePublicationIsCreated() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.provider.callOrder.clear()

        fixture.coordinator.run(fixture.store)

        val uploaded = fixture.provider.uploadRequests.map { it.providerObjectId.value }
        assertEquals(1, uploaded.size)
        assertTrue(fixture.provider.downloadIds.containsAll(uploaded))
        val lastDownload = fixture.provider.callOrder.indexOfLast {
            it.startsWith("downloadImmutable:")
        }
        val publicationCreate = fixture.provider.callOrder.indexOfFirst {
            it.startsWith("createSmallIfAbsent:")
        }
        assertTrue(lastDownload >= 0)
        assertTrue(publicationCreate > lastDownload)
    }

    @Test
    fun thePublicationProviderIdIsPreGeneratedAndPersistedFirst() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.provider.callOrder.clear()

        fixture.coordinator.run(fixture.store)

        val generated = fixture.provider.callOrder.indexOf("generateProviderIds:PUBLICATION")
        val created = fixture.provider.callOrder.indexOfFirst {
            it.startsWith("createSmallIfAbsent:")
        }
        assertTrue(generated >= 0)
        assertTrue(created > generated)
        assertEquals(
            fixture.currentPublicationProviderId(),
            checkNotNull(fixture.publishOperation()).candidatePublicationProviderId?.value,
        )
    }

    @Test
    fun thePublishedSuccessorIsResolvedAgainBeforeTheCheckpoint() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)

        fixture.coordinator.run(fixture.store)

        val phases = fixture.remoteStateStore.operationPhases
        assertTrue(phases.indexOf("PUBLICATION_VERIFIED") < phases.indexOf("CHECKPOINTED"))
        assertTrue(phases.indexOf("FINAL_OWNERSHIP_RECHECKED") < phases.indexOf("CHECKPOINTED"))
        assertTrue(phases.indexOf("CHECKPOINTED") < phases.indexOf("CLEANUP_STARTED"))
        assertEquals("COMPLETED", phases.last())
    }

    @Test
    fun thePublicationSequenceIncreasesByExactlyOne() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)

        fixture.coordinator.run(fixture.store)

        val published = fixture.verifiedCurrentPublication()
        assertEquals(1L, published.manifest.publicationSequence)
        assertEquals(55L, published.manifest.localGeneration)
        assertEquals(fixture.baselinePublicationId(), published.manifest.predecessorPublicationId)
        val configuration = checkNotNull(fixture.remoteStateStore.stored)
        assertEquals(1L, checkNotNull(configuration.currentPublication).sequence.value)
        assertEquals(0L, checkNotNull(configuration.previousPublication).sequence.value)
    }

    @Test
    fun aPassphraseRotationPublishesASuccessorAtAnEqualGeneration() = runPublishTest { fixture ->
        fixture.rotateRecoveryCredentialGeneration()

        val result = fixture.coordinator.run(fixture.store)

        assertEquals(RemoteBackupRunResult.Verified(BackupGeneration(53)), result)
        val published = fixture.verifiedCurrentPublication()
        assertEquals(1L, published.manifest.publicationSequence)
        assertEquals(53L, published.manifest.localGeneration)
        assertEquals(1L, published.manifest.recoveryCredentialGeneration)
        assertTrue(fixture.provider.uploadRequests.isEmpty())
    }

    @Test
    fun theInventoryReusesPredecessorObjectsAndUploadsOnlyNewOnes() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        val baselineInventory = fixture.verifiedCurrentPublication().manifest.inventory

        fixture.coordinator.run(fixture.store)

        val published = fixture.verifiedCurrentPublication().manifest
        assertEquals(1, fixture.provider.uploadRequests.size)
        assertEquals(RemoteObjectRoleV1.SEGMENT, fixture.provider.uploadRequests.single().role)
        assertEquals(baselineInventory.size + 1, published.inventory.size)
        assertTrue(published.inventory.containsAll(baselineInventory))
        assertEquals(
            listOf(54L to 55L),
            published.inventory
                .filter { it.role == RemoteObjectRoleV1.SEGMENT }
                .map { it.firstGeneration to it.lastGeneration },
        )
    }

    @Test
    fun aNewCompleteBaseIsPublishedAsTwoIndependentCopies() = runPublishTest { fixture ->
        val baseline = fixture.verifiedCurrentPublication().manifest
        fixture.seedLocalSnapshot(60)

        fixture.coordinator.run(fixture.store)

        val published = fixture.verifiedCurrentPublication().manifest
        assertEquals(60L, published.localGeneration)
        assertEquals(2, fixture.provider.uploadRequests.size)
        assertTrue(
            fixture.provider.uploadRequests.all { it.role == RemoteObjectRoleV1.SNAPSHOT },
        )
        assertNotEquals(published.currentBaseObjectId, published.fallbackBaseObjectId)
        assertNotEquals(baseline.currentBaseObjectId, published.currentBaseObjectId)
        assertNotEquals(baseline.fallbackBaseObjectId, published.fallbackBaseObjectId)
        assertEquals(
            listOf(60L, 60L),
            published.inventory
                .filter { it.logicalObjectId in setOf(
                    published.currentBaseObjectId,
                    published.fallbackBaseObjectId,
                ) }
                .map { it.lastGeneration },
        )
        // The two copies are independently identified and independently framed.
        assertNotEquals(
            fixture.provider.uploadRequests[0].frameSha256,
            fixture.provider.uploadRequests[1].frameSha256,
        )
    }

    @Test
    fun anUploadFailureLeavesNoPublicationAndNoCheckpoint() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.provider.uploadFailure = RemoteBackupFailureCategory.PROVIDER_STORAGE

        val result = fixture.coordinator.run(fixture.store)

        assertEquals(
            RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.PROVIDER_STORAGE),
            result,
        )
        assertEquals(1, fixture.publicationsCreated())
        assertEquals(53L, fixture.lastVerifiedGeneration())
    }

    @Test
    fun aProviderListFailureIsRetryableAndPublishesNothing() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.provider.listFailure = { IllegalStateException("provider list failed") }

        val result = fixture.coordinator.run(fixture.store)

        assertEquals(
            RemoteBackupRunResult.Retryable(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
            result,
        )
        assertEquals(1, fixture.publicationsCreated())
        assertEquals(53L, fixture.lastVerifiedGeneration())
    }

    @Test
    fun anAuthorizationFailurePublishesNothing() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.provider.readFailures[fixture.rootProviderId()] =
            RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED

        val result = fixture.coordinator.run(fixture.store)

        assertEquals(RemoteBackupRunResult.AuthorizationRequired, result)
        assertTrue(fixture.provider.uploadRequests.isEmpty())
    }

    @Test
    fun anAccountMismatchPublishesNothing() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.provider.readFailures[fixture.rootProviderId()] =
            RemoteBackupFailureCategory.ACCOUNT_MISMATCH

        val result = fixture.coordinator.run(fixture.store)

        assertEquals(RemoteBackupRunResult.AccountMismatch, result)
        assertTrue(fixture.provider.uploadRequests.isEmpty())
    }

    @Test
    fun cancellationLeavesNoPublicationAndNoCheckpoint() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        val reached = CompletableDeferred<Unit>()
        fixture.store.beforeUpload = {
            reached.complete(Unit)
            awaitCancellation()
        }

        coroutineScope {
            val job = launch { fixture.coordinator.run(fixture.store) }
            reached.await()
            job.cancelAndJoin()
        }

        assertEquals(1, fixture.publicationsCreated())
        assertEquals(53L, fixture.lastVerifiedGeneration())
        assertFalse(fixture.remoteStateStore.operationPhases.contains("CHECKPOINTED"))
    }

    @Test
    fun aCrashBeforeTheCandidatePhaseAdoptsInsteadOfUploadingTwice() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.remoteStateStore.failTransitionToPhase = "CANDIDATES_VERIFIED"

        val interrupted = fixture.coordinator.run(fixture.store)

        assertEquals(
            RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.LOCAL_STORAGE),
            interrupted,
        )
        assertEquals(1, fixture.provider.uploadRequests.size)

        val resumed = fixture.coordinator.run(fixture.store)

        assertEquals(RemoteBackupRunResult.Verified(BackupGeneration(55)), resumed)
        // The candidate was adopted from the provider rather than uploaded twice.
        assertEquals(1, fixture.provider.uploadRequests.size)
        assertEquals(2, fixture.publicationsCreated())
    }

    @Test
    fun aCrashBetweenThePublicationCreateAndTheCheckpointResumes() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.remoteStateStore.failTransitionToPhase = "PUBLICATION_VERIFIED"

        val interrupted = fixture.coordinator.run(fixture.store)

        assertEquals(
            RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.LOCAL_STORAGE),
            interrupted,
        )
        assertEquals(2, fixture.publicationsCreated())
        assertEquals(53L, fixture.lastVerifiedGeneration())

        val resumed = fixture.coordinator.run(fixture.store)

        assertEquals(RemoteBackupRunResult.Verified(BackupGeneration(55)), resumed)
        assertEquals(2, fixture.publicationsCreated())
        assertEquals(55L, fixture.lastVerifiedGeneration())
    }

    @Test
    fun staleTransferStateIsClearedBeforeACandidateIsReencoded() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.provider.uploadFailure = RemoteBackupFailureCategory.RETRYABLE_PROVIDER

        val interrupted = fixture.coordinator.run(fixture.store)

        assertEquals(
            RemoteBackupRunResult.Retryable(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
            interrupted,
        )
        val stale = checkNotNull(fixture.segmentTransferState())
        assertNull(stale.verifiedAt)

        fixture.provider.uploadFailure = null
        val resumed = fixture.coordinator.run(fixture.store)

        assertEquals(RemoteBackupRunResult.Verified(BackupGeneration(55)), resumed)
        val fresh = checkNotNull(fixture.segmentTransferState())
        // A re-encode always carries a fresh nonce, so the stale expected bytes
        // had to be cleared before the upload could be accepted at all.
        assertNotEquals(stale.frameSha256, fresh.frameSha256)
    }

    @Test
    fun aFrozenPlanIsAbandonedWhenItsSourceIsGoneAndSlotIsEmpty() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.provider.uploadFailure = RemoteBackupFailureCategory.RETRYABLE_PROVIDER

        val interrupted = fixture.coordinator.run(fixture.store)

        assertEquals(
            RemoteBackupRunResult.Retryable(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
            interrupted,
        )
        // Stage 2 rotates its base and drops the segment the frozen plan names.
        fixture.provider.uploadFailure = null
        fixture.seedLocalSnapshot(60)
        fixture.removeLocalObject("segment:54:55")

        val resumed = fixture.coordinator.run(fixture.store)

        assertEquals(RemoteBackupRunResult.Verified(BackupGeneration(60)), resumed)
        val published = fixture.verifiedCurrentPublication().manifest
        assertEquals(60L, published.localGeneration)
        assertEquals(1L, published.publicationSequence)
        assertTrue(published.inventory.none { it.role == RemoteObjectRoleV1.SEGMENT })
    }

    @Test
    fun anOccupiedSlotKeepsItsPlanEvenWhenTheLocalSourceIsGone() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.remoteStateStore.failTransitionToPhase = "CANDIDATES_VERIFIED"

        val interrupted = fixture.coordinator.run(fixture.store)

        assertEquals(
            RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.LOCAL_STORAGE),
            interrupted,
        )
        assertEquals(1, fixture.provider.uploadRequests.size)
        // The local source is gone, but the reserved slot already holds this
        // run's own verified bytes, so the plan stands rather than re-planning.
        fixture.seedLocalSnapshot(60)
        fixture.removeLocalObject("segment:54:55")

        val resumed = fixture.coordinator.run(fixture.store)

        assertEquals(RemoteBackupRunResult.Verified(BackupGeneration(55)), resumed)
        assertEquals(1, fixture.provider.uploadRequests.size)
        val published = fixture.verifiedCurrentPublication().manifest
        assertEquals(55L, published.localGeneration)
        assertEquals(
            listOf(54L to 55L),
            published.inventory
                .filter { it.role == RemoteObjectRoleV1.SEGMENT }
                .map { it.firstGeneration to it.lastGeneration },
        )
    }

    @Test
    fun aRetiredPublicationIsPrunedByTheNextVerifiedRun() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.coordinator.run(fixture.store)
        val retired = fixture.currentPublicationProviderId()

        fixture.seedLocalSegment(56, 56)
        fixture.coordinator.run(fixture.store)
        val previous = fixture.currentPublicationProviderId()
        assertTrue(fixture.provider.contains(retired))

        fixture.seedLocalSegment(57, 57)
        fixture.coordinator.run(fixture.store)

        // Retained: the current publication and its immediate predecessor.
        val current = fixture.currentPublicationProviderId()
        assertTrue(fixture.provider.contains(current))
        assertTrue(fixture.provider.contains(previous))
        // The publication two generations back is proven retired and is gone
        // immediately — no seven-day hold on a superseded same-epoch object.
        assertFalse(fixture.provider.contains(retired))
        assertEquals(listOf(retired), fixture.provider.deletedIds)
        // Every object the retained pair still names survives.
        fixture.verifiedCurrentPublication().manifest.inventory.forEach { item ->
            assertTrue(item.providerFileId, fixture.provider.contains(item.providerFileId))
        }
        // The epoch baseline has no local first-observed record, so it is an
        // unprovable blocker rather than a deletion candidate.
        assertTrue(fixture.provider.contains(fixture.baselineProviderId()))
    }

    @Test
    fun aPublicationFromAnotherWriterEpochIsIgnoredAndNeverDeleted() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.seedForeignEpochPublication("provider-foreign-epoch")

        val result = fixture.coordinator.run(fixture.store)

        assertEquals(RemoteBackupRunResult.Verified(BackupGeneration(55)), result)
        assertTrue(fixture.provider.contains("provider-foreign-epoch"))
        assertFalse(fixture.provider.deletedIds.contains("provider-foreign-epoch"))
    }

    @Test
    fun cleanupRunsAfterTheCheckpointAndSparesTheRetainedPair() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.clock = fixture.clock.plus(Duration.ofDays(30))

        fixture.coordinator.run(fixture.store)

        val phases = fixture.remoteStateStore.operationPhases
        assertTrue(phases.indexOf("CHECKPOINTED") < phases.indexOf("CLEANUP_STARTED"))
        assertTrue(fixture.provider.contains(fixture.currentPublicationProviderId()))
        assertTrue(fixture.provider.contains(fixture.baselineProviderId()))
        fixture.verifiedCurrentPublication().manifest.inventory.forEach { item ->
            assertTrue(item.providerFileId, fixture.provider.contains(item.providerFileId))
        }
    }

    @Test
    fun noRunResultRevealsARemoteIdentifier() = runPublishTest { fixture ->
        fixture.seedLocalSegment(54, 55)
        fixture.provider.listFailure = { IllegalStateException("provider list failed") }

        val result = fixture.coordinator.run(fixture.store)

        assertFalse(result.toString().contains(fixture.rootProviderId()))
        assertFalse(result.toString().contains(RemoteBackupTestFixtures.VAULT_ID))
    }

    private fun runPublishTest(block: suspend (PublishFixture) -> Unit) = runBlocking {
        withTimeout(5_000) {
            val root = Files.createTempDirectory("remote-backup-coordinator-test").toFile()
            val fixture = PublishFixture(root)
            try {
                fixture.connect()
                block(fixture)
            } finally {
                fixture.close()
                root.deleteRecursively()
            }
        }
    }
}

/**
 * A real epoch-one lineage established through the Task 7 configurator, plus
 * the routine coordinator that publishes successors into it.
 *
 * Nothing about the remote authority is stubbed: the ownership chain, the
 * baseline publication, and both complete bases are the exact authenticated
 * objects setup created, so every coordinator assertion runs against the same
 * create-only protocol production sees.
 */
private class PublishFixture(root: File) {
    private val localRoot = File(root, "local").also { it.mkdirs() }
    private val stagingRoot = File(root, "staging").also { it.mkdirs() }
    private val providerRoot = File(root, "provider").also { it.mkdirs() }
    private val crypto = SingleContentVaultCrypto(RemoteBackupTestFixtures.crypto)
    private val authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto)

    private val vaultId = VaultId(RemoteBackupTestFixtures.VAULT_ID)
    private val localObjectStore: LocalBackupObjectStore = DefaultLocalBackupObjectStore(localRoot)
    private val contentKeyStore = IssuedContentKeyStore(crypto)
    private val backupStateStore = InMemoryStage2BackupStateStore(vaultId)
    private val recoveryEnvelopeStore =
        InMemoryRecoveryEnvelopeStore(RemoteBackupTestFixtures.envelope())

    val remoteStateStore = InMemoryRemoteBackupStateStore()
    val transferStore = InMemoryRemoteBackupTransferStore()
    val provider = FakeCreateOnlyBackupObjectStore(providerRoot)
    var clock: Instant = Instant.ofEpochMilli(1_700_000_000_000)
    val store = TransferStateAwareObjectStore(provider, transferStore) { clock }
    val stage2 = ScriptedStage2Coordinator(provider) { seedInitialLocalBase() }

    private val remoteObjectCodec = RemoteObjectCodec(
        authenticatedCodec = authenticatedCodec,
        localObjectStore = localObjectStore,
        stagingRoot = stagingRoot,
    )
    private val ownershipCodec = OwnershipClaimCodec(authenticatedCodec)
    private val publicationCodec = PublicationCodec(authenticatedCodec)

    private val configurator = DefaultRemoteBackupConfigurator(
        vaultId = vaultId,
        backupCoordinator = stage2,
        backupStateStore = backupStateStore,
        recoveryEnvelopeStore = recoveryEnvelopeStore,
        contentKeyStore = contentKeyStore,
        remoteStateStore = remoteStateStore,
        remoteObjectCodec = remoteObjectCodec,
        ownershipCodec = ownershipCodec,
        publicationCodec = publicationCodec,
        now = { clock },
    )

    val coordinator = DefaultRemoteBackupCoordinator(
        vaultId = vaultId,
        backupCoordinator = stage2,
        backupStateStore = backupStateStore,
        recoveryEnvelopeStore = recoveryEnvelopeStore,
        contentKeyStore = contentKeyStore,
        remoteStateStore = remoteStateStore,
        transferStore = transferStore,
        localObjectStore = localObjectStore,
        remoteObjectCodec = remoteObjectCodec,
        ownershipCodec = ownershipCodec,
        publicationCodec = publicationCodec,
        now = { clock },
    )

    private var baselineProviderFileId = ""
    private var baselinePublicationIdentity = ""

    suspend fun connect() {
        val result = configurator.connect(store, ACCOUNT_DIGEST, false)
        check(result is RemoteBackupConnectResult.Connected) {
            "Remote backup setup did not establish epoch one"
        }
        baselineProviderFileId = currentPublicationProviderId()
        baselinePublicationIdentity = currentPublicationId()
        provider.callOrder.clear()
        provider.uploadRequests.clear()
        provider.downloadIds.clear()
        provider.deletedIds.clear()
        stage2.onRequest = {}
    }

    fun close() = crypto.close()

    // -- Remote state readers ------------------------------------------------------------

    fun rootProviderId(): String = checkNotNull(remoteStateStore.stored).rootClaimProviderId.value

    fun baselineProviderId(): String = baselineProviderFileId

    fun baselinePublicationId(): String = baselinePublicationIdentity

    fun currentPublicationProviderId(): String =
        checkNotNull(checkNotNull(remoteStateStore.stored).currentPublication).providerId.value

    fun currentPublicationId(): String =
        checkNotNull(checkNotNull(remoteStateStore.stored).currentPublication).logicalId.value

    fun lastVerifiedGeneration(): Long =
        checkNotNull(checkNotNull(remoteStateStore.stored).lastVerifiedGeneration).value

    suspend fun publishOperation() =
        remoteStateStore.operation("remote-publish:" + RemoteBackupTestFixtures.VAULT_ID)

    fun publicationsCreated(): Int = provider.authorityEvents.count { it == "BASELINE" }

    fun verifiedCurrentPublication(): VerifiedPublication = withContentKey { key ->
        publicationCodec.verify(provider.requireBytes(currentPublicationProviderId()), key)
    }

    suspend fun segmentTransferState(): RemoteBackupObject? =
        transferStore.objectsForLineage(lineageId())
            .firstOrNull { it.role == RemoteObjectRoleV1.SEGMENT }

    // -- Remote state mutators -----------------------------------------------------------

    /** Occupies the reserved successor slot with another device's claim. */
    fun takeOwnershipWithAnotherDevice() = seedClaim(
        RemoteBackupTestFixtures.successorOf(
            predecessor = verifiedRoot(),
            activeDeviceId = RemoteBackupTestFixtures.OTHER_DEVICE_ID,
        ),
    )

    fun terminateOwnership() = seedClaim(RemoteBackupTestFixtures.tombstoneOf(verifiedRoot()))

    /** Publishes a well-formed publication belonging to a different epoch. */
    fun seedForeignEpochPublication(providerFileId: String) = withContentKey { key ->
        val envelope = RemoteBackupTestFixtures.envelope()
        val manifest = verifiedCurrentPublication().manifest.copy(
            writerEpoch = 2,
            publicationProviderFileId = providerFileId,
            publicationId = PublicationId.new().value,
        )
        provider.put(
            providerObjectId = providerFileId,
            bytes = publicationCodec.encode(
                manifest.copy(
                    bootstrapSha256 = publicationCodec.bootstrapSha256(manifest, envelope),
                ),
                envelope,
                key,
            ),
            metadata = RemoteBackupTestFixtures.publicationMetadata(providerFileId, 2),
            lineageId = lineageId(),
        )
    }

    suspend fun rotateRecoveryCredentialGeneration() {
        val stored = checkNotNull(remoteStateStore.stored)
        check(
            remoteStateStore.compareAndSet(
                lineageId = stored.lineageId,
                expected = stored.stateVersion,
                next = stored.copy(
                    recoveryCredentialGeneration = stored.recoveryCredentialGeneration + 1,
                    stateVersion = RemoteBackupStateVersion(stored.stateVersion.value + 1),
                ),
            ),
        ) { "Recovery credential rotation was rejected" }
    }

    // -- Stage 2 local seeding -----------------------------------------------------------

    /** Commits one local segment and advances the local verified generation. */
    fun seedLocalSegment(first: Long, last: Long) {
        val payload = BackupOperationSegmentCodec.fromJournalEntries(
            vaultId,
            (first..last).map { generation ->
                BackupJournalEntity(
                    operationId = "operation-$generation",
                    vaultId = RemoteBackupTestFixtures.VAULT_ID,
                    generation = generation,
                    sequence = 0,
                    payloadFormatVersion = 1,
                    mutationKind = "UPSERT",
                    objectId = "tag-$generation",
                    objectType = "TAG",
                    payload = BackupPayloadTestFixtures.tagMutation("tag-$generation"),
                    revisionWallMillis = generation,
                    revisionLogical = 1,
                    sourceDeviceId = "device-alpha",
                )
            },
        )
        commitLocal("segment:$first:$last", CloudObjectFamily.OPERATION_SEGMENT) {
            BackupOperationSegmentCodec.encode(payload)
        }
        backupStateStore.replace(
            backupStateStore.value.copy(
                currentGeneration = last,
                latestVerifiedSegmentGeneration = last,
            ),
        )
    }

    /** Commits one local complete base and rotates the previous base. */
    fun seedLocalSnapshot(generation: Long) {
        val payload = BackupPayloadTestFixtures.snapshot().copy(coveredGeneration = generation)
        val objectId = "snapshot:$generation"
        commitLocal(objectId, CloudObjectFamily.SNAPSHOT) { BackupSnapshotCodec.encode(payload) }
        backupStateStore.replace(
            backupStateStore.value.copy(
                currentGeneration = generation,
                lastVerifiedSnapshotGeneration = generation,
                currentBaseObjectId = objectId,
                previousBaseObjectId = backupStateStore.value.currentBaseObjectId,
                latestVerifiedSegmentGeneration = generation,
                snapshotCreatedAtEpochMillis = clock.toEpochMilli(),
            ),
        )
    }

    /** Drops one committed local object, as Stage 2 retention would. */
    fun removeLocalObject(objectId: String) {
        val name = if (objectId.startsWith("snapshot:")) {
            "snapshot-${objectId.substringAfter(':')}.otf"
        } else {
            "segment-${objectId.substringAfter(':').replace(':', '-')}.otf"
        }
        val removed = listOf("current", "previous", "segments")
            .map { File(localRoot, it).resolve(name) }
            .count { it.delete() }
        check(removed == 1) { "Expected exactly one committed local object to remove" }
    }

    // -- Internals -----------------------------------------------------------------------

    private fun lineageId(): CloudLineageId = checkNotNull(remoteStateStore.stored).lineageId

    private fun verifiedRoot(): VerifiedOwnershipClaim = withContentKey { key ->
        ownershipCodec.verify(provider.requireBytes(rootProviderId()), key)
    }

    private fun seedClaim(claim: OwnershipClaimV1) = withContentKey { key ->
        val encoded = ownershipCodec.encode(claim, key)
        provider.put(
            providerObjectId = claim.providerFileId,
            bytes = encoded,
            metadata = RemoteBackupTestFixtures.claimMetadata(
                ownershipCodec.readPublicHeader(encoded),
            ),
            lineageId = lineageId(),
        )
    }

    private fun commitLocal(
        objectId: String,
        family: CloudObjectFamily,
        plaintext: () -> ByteArray,
    ) {
        val frame = withContentKey { key ->
            val bytes = plaintext()
            try {
                authenticatedCodec.encrypt(
                    CloudHeaderIdentity(
                        family = family,
                        schemaVersion = 1,
                        cryptoVersion = 1,
                        minimumReaderVersion = 1,
                        vaultId = vaultId.value,
                        objectId = objectId,
                    ),
                    bytes,
                    key,
                )
            } finally {
                bytes.fill(0)
            }
        }
        val candidate = localObjectStore.writeCandidate(objectId, frame)
        if (family == CloudObjectFamily.SNAPSHOT) {
            localObjectStore.commitSnapshot(candidate, backupStateStore.value.currentBaseObjectId)
        } else {
            localObjectStore.commitSegment(candidate)
        }
        candidate.file.delete()
    }

    /** Stage 2 produces exactly one verified complete base covering generation 53. */
    private fun seedInitialLocalBase() {
        if (backupStateStore.value.currentBaseObjectId != null) return
        commitLocal("snapshot:53", CloudObjectFamily.SNAPSHOT) {
            BackupSnapshotCodec.encode(BackupPayloadTestFixtures.snapshot())
        }
        backupStateStore.replace(
            backupStateStore.value.copy(
                currentGeneration = 53,
                lastVerifiedSnapshotGeneration = 53,
                currentBaseObjectId = "snapshot:53",
                latestVerifiedSegmentGeneration = 53,
                snapshotCreatedAtEpochMillis = clock.toEpochMilli(),
            ),
        )
    }

    private fun <T> withContentKey(block: (VaultKey) -> T): T {
        val key = contentKeyStore.openExisting(vaultId)
        return try {
            block(key)
        } finally {
            key.close()
        }
    }

    private companion object {
        val ACCOUNT_DIGEST = ByteArray(32) { (it + 1).toByte() }
    }
}

/** Stage 2 stand-in that records when a coordinator asked for a capture. */
internal class ScriptedStage2Coordinator(
    private val provider: FakeCreateOnlyBackupObjectStore,
    var onRequest: () -> Unit,
) : BackupCoordinator {
    var requestCount = 0
        private set

    /** How many provider calls had happened when the capture was requested. */
    var providerCallsBeforeRequest = 0
        private set

    override suspend fun request() {
        requestCount += 1
        providerCallsBeforeRequest = provider.callOrder.size
        onRequest()
    }
}

/**
 * Models the durable transfer state the provider-backed store keeps beside
 * every immutable upload, including its fail-closed refusal to upload bytes
 * that differ from an already registered expectation.
 */
internal class TransferStateAwareObjectStore(
    private val delegate: FakeCreateOnlyBackupObjectStore,
    private val transferStore: RemoteBackupTransferStore,
    private val now: () -> Instant,
) : CreateOnlyBackupObjectStore by delegate {

    var beforeUpload: (suspend () -> Unit)? = null
    var afterPublicationCreate: (() -> Unit)? = null

    override suspend fun createSmallIfAbsent(
        providerObjectId: ProviderObjectId,
        lineageId: CloudLineageId,
        metadata: RemoteListedObject,
        bytes: OwnedRemoteBytes,
    ): CreateSmallResult {
        val result = delegate.createSmallIfAbsent(providerObjectId, lineageId, metadata, bytes)
        if (metadata.role == RemoteObjectRoleV1.PUBLICATION) afterPublicationCreate?.invoke()
        return result
    }

    override suspend fun uploadImmutable(request: ImmutableUploadRequest): ImmutableUploadResult {
        beforeUpload?.invoke()
        val existing = transferStore.objectState(request.lineageId, request.logicalObjectId)
        val state = if (existing != null) {
            require(existing.providerObjectId == request.providerObjectId) {
                "Existing object state targets a different provider object"
            }
            require(
                existing.frameSha256 == request.frameSha256 &&
                    existing.frameLength == request.frameLength,
            ) {
                "Existing object state targets different expected bytes"
            }
            existing
        } else {
            val fresh = RemoteBackupObject(
                lineageId = request.lineageId,
                logicalObjectId = request.logicalObjectId,
                providerObjectId = request.providerObjectId,
                role = request.role,
                writerEpoch = request.writerEpoch,
                ownerDeviceId = request.ownerDeviceId,
                operationId = request.operationId,
                firstGeneration = request.firstGeneration,
                lastGeneration = request.lastGeneration,
                frameLength = request.frameLength,
                frameSha256 = request.frameSha256,
                lifecycle = RemoteObjectLifecycle.PLANNED,
                resumableSessionUri = null,
                uploadedBytes = 0,
                createdAt = now(),
                verifiedAt = null,
            )
            transferStore.insertObject(fresh)
            fresh
        }
        if (state.verifiedAt != null) return ImmutableUploadResult.UploadedAndVerified
        val result = delegate.uploadImmutable(request)
        if (result == ImmutableUploadResult.UploadedAndVerified ||
            result == ImmutableUploadResult.OccupiedByExpectedBytes
        ) {
            transferStore.compareAndSetObject(
                state,
                state.copy(
                    lifecycle = RemoteObjectLifecycle.VERIFIED,
                    uploadedBytes = request.frameLength,
                    verifiedAt = now(),
                ),
            )
        }
        return result
    }
}
