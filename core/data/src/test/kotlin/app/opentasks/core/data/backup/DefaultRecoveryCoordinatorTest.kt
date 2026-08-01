package app.opentasks.core.data.backup

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.VaultSlot
import app.opentasks.core.data.VerifiedStagedVault
import app.opentasks.core.data.db.TagEntity
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.domain.RecoveryCandidate
import app.opentasks.core.domain.RecoveryResult
import app.opentasks.core.domain.RecoverySource
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RecoveryFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WriterEpoch
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRecoveryCoordinatorTest {

    // -- Discovery -----------------------------------------------------------------------

    @Test
    fun candidateHandlesAreProcessLocalAndRevealNothingObservable() = runRecoveryTest { fixture ->
        fixture.seedLineage()

        val first = fixture.coordinator.discover(fixture.store, null)
        val second = fixture.coordinator.discover(fixture.store, null)

        assertEquals(1, first.size)
        assertEquals(RecoverySource.GOOGLE_DRIVE, first.single().source)
        val handle = first.single().handle
        assertNotEquals(handle, second.single().handle)
        assertEquals(32, handle.length)
        assertTrue(handle.all { it in '0'..'9' || it in 'a'..'f' })
        listOf(
            RecoveryTestLineage.LINEAGE_ID,
            RecoveryTestLineage.ROOT_PROVIDER_ID,
            RecoveryTestLineage.BASELINE_PROVIDER_ID,
            fixture.vaultId.value,
        ).forEach { secret -> assertFalse(handle.contains(secret)) }
    }

    @Test
    fun aMalformedOwnershipRootBlocksDiscoveryInsteadOfReportingNoBackups() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        fixture.store.put(
            providerObjectId = "provider-root-malformed",
            bytes = ByteArray(64) { 0x5a },
            metadata = RemoteBackupTestFixtures.claimMetadata(
                fixture.rootHeader(),
                providerFileId = "provider-root-malformed",
            ),
            lineageId = CloudLineageId.parse(RecoveryTestLineage.LINEAGE_ID),
        )

        val candidates = fixture.coordinator.discover(fixture.store, null)

        assertEquals(1, candidates.size)
        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE),
            fixture.prepare(candidates.single()),
        )
        assertTrue(fixture.staging.sessions.isEmpty())
    }

    @Test
    fun anAuthenticatedTerminalLineageIsOfferedAndThenRefused() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        fixture.seedTerminalSuccessor()

        val candidates = fixture.coordinator.discover(fixture.store, null)

        assertEquals(1, candidates.size)
        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.TERMINATED),
            fixture.prepare(candidates.single()),
        )
        assertTrue(fixture.staging.sessions.isEmpty())
        assertEquals(0, fixture.crypto.unlockCount)
    }

    // -- Source authentication -----------------------------------------------------------

    @Test
    fun aWrongAccountIsRefusedBeforeAnyLineageAccess() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        val candidate = fixture.discoverDrive()
        fixture.store.callOrder.clear()

        val result = fixture.coordinator.prepare(
            candidate,
            RecoveryTestLineage.PASSPHRASE.copyOf(),
            fixture.store,
            ByteArray(32) { 0x7f },
        )

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.ACCOUNT_MISMATCH),
            result,
        )
        assertTrue(fixture.store.callOrder.isEmpty())
        assertEquals(0, fixture.crypto.unlockCount)
    }

    @Test
    fun anUnauthorizedStoreIsRefusedBeforeAnyLineageAccess() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        val candidate = fixture.discoverDrive()
        fixture.store.callOrder.clear()

        val result = fixture.coordinator.prepare(
            candidate,
            RecoveryTestLineage.PASSPHRASE.copyOf(),
            fixture.store,
            null,
        )

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.AUTHORIZATION_REQUIRED),
            result,
        )
        assertTrue(fixture.store.callOrder.isEmpty())
    }

    @Test
    fun aWrongPassphraseFailsBeforeAnyOwnershipIsRead() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        val candidate = fixture.discoverDrive()

        val result = fixture.coordinator.prepare(
            candidate,
            "not-the-passphrase".toCharArray(),
            fixture.store,
            RecoveryTestLineage.ACCOUNT_DIGEST,
        )

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.WRONG_PASSPHRASE),
            result,
        )
        assertTrue(fixture.staging.sessions.isEmpty())
        assertTrue(fixture.store.createdIds.isEmpty())
    }

    @Test
    fun aWeakenedRecoveryKdfIsRejectedBeforeAnyDerivation() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        fixture.weakenBaselineKdf()
        val candidate = fixture.discoverDrive()

        val result = fixture.prepare(candidate)

        assertEquals(RecoveryResult.Failed(RecoveryFailureCategory.UNSAFE_KDF), result)
        assertEquals(0, fixture.crypto.unlockCount)
        assertTrue(fixture.staging.sessions.isEmpty())
    }

    @Test
    fun aDuplicatePublicationSequenceFailsClosed() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        fixture.seedForkedBaseline()
        val candidate = fixture.discoverDrive()

        val result = fixture.prepare(candidate)

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.AMBIGUOUS_REMOTE_STATE),
            result,
        )
        assertTrue(fixture.staging.sessions.isEmpty())
    }

    @Test
    fun aForeignVaultIdentityIsRefusedBeforeAnythingIsStaged() = runRecoveryTest(
        expectedVaultId = VaultId("vault-primary"),
    ) { fixture ->
        fixture.seedLineage()
        val candidate = fixture.discoverDrive()

        val result = fixture.prepare(candidate)

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE),
            result,
        )
        assertTrue(fixture.staging.sessions.isEmpty())
    }

    // -- Reconstruction ------------------------------------------------------------------

    @Test
    fun theCurrentBaseAndItsRequiredSegmentsRebuildTheRecoveredGeneration() = runRecoveryTest { fixture ->
        fixture.seedLineage()

        val result = fixture.prepare(fixture.discoverDrive())

        assertTrue(result is RecoveryResult.TakeoverConfirmationRequired)
        assertEquals(RecoveryTestLineage.BASE_A_LOGICAL_ID, fixture.coordinator.baseUsed)
        val request = checkNotNull(fixture.staging.sessions.single().request)
        assertEquals(SNAPSHOT_GENERATION, request.snapshot.coveredGeneration)
        assertEquals(1, request.segments.size)
        assertEquals(RECOVERED_GENERATION, request.expectedGeneration.value)
    }

    @Test
    fun damagedCurrentBaseRecoversThroughIndependentFallback() = runRecoveryTest { fixture ->
        val result = fixture.coordinator.prepare(
            fixture.storeWithDamagedCurrentAndValidFallback(),
            RecoveryTestLineage.PASSPHRASE.copyOf(),
            fixture.store,
            RecoveryTestLineage.ACCOUNT_DIGEST,
        )

        assertTrue(result is RecoveryResult.TakeoverConfirmationRequired)
        assertEquals(RecoveryTestLineage.BASE_B_LOGICAL_ID, fixture.coordinator.baseUsed)
    }

    @Test
    fun bothDamagedBasesFailWithoutStagingAnything() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        fixture.damageBase(RecoveryTestLineage.BASE_A_PROVIDER_ID)
        fixture.damageBase(RecoveryTestLineage.BASE_B_PROVIDER_ID)

        val result = fixture.prepare(fixture.discoverDrive())

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.MISSING_REQUIRED_OBJECT),
            result,
        )
        assertTrue(fixture.staging.sessions.isEmpty())
    }

    @Test
    fun aBaseEncryptedUnderAnotherRemoteIdentityIsNeverAdopted() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        fixture.reidentifyBase(
            providerObjectId = RecoveryTestLineage.BASE_A_PROVIDER_ID,
            logicalObjectId = RecoveryTestLineage.BASE_B_LOGICAL_ID,
        )
        fixture.reidentifyBase(
            providerObjectId = RecoveryTestLineage.BASE_B_PROVIDER_ID,
            logicalObjectId = RecoveryTestLineage.BASE_A_LOGICAL_ID,
        )

        val result = fixture.prepare(fixture.discoverDrive())

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.MISSING_REQUIRED_OBJECT),
            result,
        )
        assertTrue(fixture.staging.sessions.isEmpty())
    }

    @Test
    fun aMissingRequiredSegmentFailsWithoutStagingAnything() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        fixture.store.delete(ProviderObjectId.of(RecoveryTestLineage.SEGMENT_PROVIDER_ID))

        val result = fixture.prepare(fixture.discoverDrive())

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.MISSING_REQUIRED_OBJECT),
            result,
        )
        assertTrue(fixture.staging.sessions.isEmpty())
    }

    @Test
    fun aPreparationFailureAfterStagingReleasesTheStagedSlot() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        // The durable phase that records the created baseline does not reach
        // storage: the preparation cannot be completed or resumed.
        fixture.staging.remoteStateStore.failTransitionToPhase = "BASELINE_CREATED"

        val result = fixture.prepare(fixture.discoverDrive())

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.STAGING_INVARIANT),
            result,
        )
        assertEquals(1, fixture.staging.abandoned)
        assertTrue(fixture.staging.sessions.single().closed)
    }

    @Test
    fun ownershipTakenDuringPreparationReleasesTheStagedSlot() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        fixture.takeOwnershipDuringTheBaselineCreate()

        val result = fixture.prepare(fixture.discoverDrive())

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.OWNERSHIP_LOST),
            result,
        )
        assertEquals(1, fixture.staging.abandoned)
        assertTrue(fixture.staging.sessions.single().closed)
    }

    @Test
    fun repeatedFailedPreparationsAccumulateNoStagedSlots() = runRecoveryTest { fixture ->
        fixture.seedLineage()

        repeat(2) {
            fixture.staging.remoteStateStore.failTransitionToPhase = "BASELINE_CREATED"
            fixture.prepare(fixture.discoverDrive())
        }

        assertEquals(2, fixture.staging.sessions.size)
        assertEquals(2, fixture.staging.abandoned)
        assertTrue(fixture.staging.sessions.all { it.closed })
    }

    @Test
    fun insufficientLocalStorageFailsWithoutTakingOwnership() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        fixture.staging.reconstructFailure = { IOException("no space left on device") }

        val result = fixture.prepare(fixture.discoverDrive())

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.INSUFFICIENT_STORAGE),
            result,
        )
        assertTrue(fixture.store.createdIds.isEmpty())
        assertEquals(1, fixture.staging.abandoned)
    }

    // -- Takeover preparation ------------------------------------------------------------

    @Test
    fun twoCompleteBasesAndTheBaselineAreVerifiedBeforeConfirmation() = runRecoveryTest { fixture ->
        fixture.seedLineage()

        val result = fixture.prepare(fixture.discoverDrive())

        assertTrue(result is RecoveryResult.TakeoverConfirmationRequired)
        val baselineCreate = fixture.store.callOrder.indexOfFirst {
            it.startsWith("createSmallIfAbsent:")
        }
        // Both source bases are downloaded, authenticated, and decoded, and both
        // successor-epoch bases are uploaded and read back, before anything is
        // created at the publication slot the claim will bind.
        listOf(
            RecoveryTestLineage.BASE_A_PROVIDER_ID,
            RecoveryTestLineage.BASE_B_PROVIDER_ID,
        ).forEach { providerId ->
            val downloaded = fixture.store.callOrder.indexOf("downloadImmutable:$providerId")
            assertTrue(downloaded in 0 until baselineCreate)
        }
        assertEquals(2, fixture.store.baseRequests.size)
        fixture.store.baseRequests.forEach { uploaded ->
            val slot = uploaded.providerObjectId.value
            assertTrue(fixture.store.callOrder.indexOf("uploadImmutable:$slot") in 0..baselineCreate)
            assertTrue(
                fixture.store.callOrder.indexOf("downloadImmutable:$slot") in 0..baselineCreate,
            )
        }
        assertEquals(
            listOf(
                "SOURCE_AUTHENTICATED",
                "STAGING_RECONSTRUCTED",
                "STAGING_VERIFIED",
                "TAKEOVER_IDENTITIES_STORED",
                "BASE_A_VERIFIED",
                "BASE_B_VERIFIED",
                "BASELINE_CREATED",
                "BASELINE_VERIFIED",
                "PREDECESSOR_RECHECKED",
                "CONFIRMATION_REQUIRED",
            ),
            fixture.coordinator.phases,
        )
    }

    @Test
    fun preparationCreatesNoOwnershipClaimAndActivatesNothing() = runRecoveryTest { fixture ->
        fixture.seedLineage()

        val result = fixture.prepare(fixture.discoverDrive())

        val required = result as RecoveryResult.TakeoverConfirmationRequired
        assertEquals(WriterEpoch(2), required.nextWriterEpoch)
        assertEquals(RECOVERED_GENERATION, required.generation.value)
        assertFalse(fixture.store.contains(RecoveryTestLineage.SUCCESSOR_PROVIDER_ID))
        assertTrue(fixture.staging.activated.isEmpty())
        assertEquals(
            RemoteBackupLifecycle.CONNECTING,
            checkNotNull(fixture.staging.remoteStateStore.stored).lifecycle,
        )
    }

    @Test
    fun theEpochBaselineBindsThisDeviceThePlannedClaimAndThePredecessor() = runRecoveryTest { fixture ->
        fixture.seedLineage()

        fixture.prepare(fixture.discoverDrive())

        val baseline = fixture.plannedBaseline()
        assertTrue(baseline.manifest.baseline)
        assertEquals(0L, baseline.manifest.publicationSequence)
        assertEquals(2L, baseline.manifest.writerEpoch)
        assertEquals(
            RecoveryTestLineage.SUCCESSOR_PROVIDER_ID,
            baseline.manifest.plannedClaimProviderFileId,
        )
        assertEquals(
            RecoveryTestLineage.ROOT_PROVIDER_ID,
            baseline.manifest.predecessorClaimProviderFileId,
        )
        assertEquals(
            fixture.verifiedRoot().completeSha256.value,
            baseline.manifest.predecessorClaimSha256,
        )
        assertNotEquals(
            RemoteBackupTestFixtures.DEVICE_ID,
            baseline.manifest.activeDeviceId,
        )
        assertEquals(RECOVERED_GENERATION, baseline.manifest.localGeneration)
    }

    @Test
    fun bothCompleteBasesAreCreatedFreshUnderIndependentIdentities() = runRecoveryTest { fixture ->
        fixture.seedLineage()

        fixture.prepare(fixture.discoverDrive())

        val baseline = fixture.plannedBaseline()
        val declared = baseline.manifest.inventory
            .filter { it.role == RemoteObjectRoleV1.SNAPSHOT }
        assertEquals(2, declared.size)
        assertEquals(2, fixture.store.baseRequests.size)
        val uploads = fixture.store.baseRequests
        // Nothing the predecessor epoch published is declared again, and the two
        // copies share no identity, provider slot, or ciphertext.
        assertEquals(2, uploads.map { it.logicalObjectId.value }.toSet().size)
        assertEquals(2, uploads.map { it.providerObjectId.value }.toSet().size)
        assertEquals(2, uploads.map { it.frameSha256.value }.toSet().size)
        uploads.forEach { uploaded ->
            assertEquals(WriterEpoch(2), uploaded.writerEpoch)
            assertNotEquals(RecoveryTestLineage.BASE_A_LOGICAL_ID, uploaded.logicalObjectId.value)
            assertNotEquals(RecoveryTestLineage.BASE_B_LOGICAL_ID, uploaded.logicalObjectId.value)
        }
        assertEquals(
            uploads.map { it.logicalObjectId.value }.toSet(),
            declared.map { it.logicalObjectId }.toSet(),
        )
        // The two copies decode to the same recovered content.
        assertEquals(
            fixture.decodeBase(uploads[0].providerObjectId, uploads[0].logicalObjectId.value),
            fixture.decodeBase(uploads[1].providerObjectId, uploads[1].logicalObjectId.value),
        )
    }

    @Test
    fun twoSourceBasesThatDisagreeAtOneGenerationFailClosed() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        fixture.replaceFallbackBaseContent()

        val result = fixture.prepare(fixture.discoverDrive())

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.AMBIGUOUS_REMOTE_STATE),
            result,
        )
        assertTrue(fixture.staging.sessions.isEmpty())
    }

    // -- Takeover confirmation -----------------------------------------------------------

    @Test
    fun confirmationClaimsThePredecessorsExactReservedSlotAtEpochPlusOne() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        val prepared = fixture.prepare(fixture.discoverDrive())
        val operationId = (prepared as RecoveryResult.TakeoverConfirmationRequired).operationId

        val result = fixture.coordinator.confirmTakeover(operationId, fixture.store)

        assertTrue(result is RecoveryResult.Activated)
        val claim = fixture.verifiedClaim(RecoveryTestLineage.SUCCESSOR_PROVIDER_ID)
        assertEquals(2L, claim.claim.writerEpoch)
        assertEquals(RecoveryTestLineage.ROOT_CLAIM_ID, claim.claim.predecessorClaimId)
        assertEquals(
            RecoveryTestLineage.SUCCESSOR_PROVIDER_ID,
            claim.claim.predecessorReservedSuccessorProviderFileId,
        )
        assertEquals(
            fixture.plannedBaseline().completeSha256.value,
            claim.claim.baselinePublicationSha256,
        )
        assertEquals(
            1,
            fixture.store.createdIds.count { it == RecoveryTestLineage.SUCCESSOR_PROVIDER_ID },
        )
    }

    @Test
    fun aWinningTakeoverActivatesTheStagedVaultAndRecordsTheNewEpoch() = runRecoveryTest(
        retentionAdvance = 1,
    ) { fixture ->
        fixture.seedLineage()
        val prepared = fixture.prepare(fixture.discoverDrive())
        val operationId = (prepared as RecoveryResult.TakeoverConfirmationRequired).operationId

        val result = fixture.coordinator.confirmTakeover(operationId, fixture.store)

        assertEquals(
            RecoveryResult.Activated(
                generation = BackupGeneration(RECOVERED_GENERATION + 1),
                lineageId = CloudLineageId.parse(RecoveryTestLineage.LINEAGE_ID),
            ),
            result,
        )
        val staged = fixture.staging.activated.single()
        assertEquals(RECOVERED_GENERATION, staged.recoveredGeneration.value)
        assertEquals(RECOVERED_GENERATION + 1, staged.activationGeneration.value)
        val configuration = checkNotNull(fixture.staging.remoteStateStore.stored)
        assertEquals(RemoteBackupLifecycle.ACTIVE, configuration.lifecycle)
        assertEquals(WriterEpoch(2), configuration.writerEpoch)
        assertEquals(RECOVERED_GENERATION, checkNotNull(configuration.lastVerifiedGeneration).value)
        assertNull(configuration.previousPublication)
        assertEquals("COMPLETED", fixture.staging.remoteStateStore.operationPhases.last())
    }

    @Test
    fun aLostRaceClosesStagingWithoutActivating() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        val prepared = fixture.prepare(fixture.discoverDrive())
        val operationId = (prepared as RecoveryResult.TakeoverConfirmationRequired).operationId
        fixture.seedForeignSuccessorClaim()

        val result = fixture.coordinator.confirmTakeover(operationId, fixture.store)

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.OWNERSHIP_LOST),
            result,
        )
        assertTrue(fixture.staging.activated.isEmpty())
        assertEquals(1, fixture.staging.abandoned)
    }

    @Test
    fun ownershipMovingBetweenPreparationAndConfirmationIsRefused() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        val prepared = fixture.prepare(fixture.discoverDrive())
        val operationId = (prepared as RecoveryResult.TakeoverConfirmationRequired).operationId
        fixture.seedForeignSuccessorChain()

        val result = fixture.coordinator.confirmTakeover(operationId, fixture.store)

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.OWNERSHIP_CHANGED),
            result,
        )
        assertTrue(fixture.staging.activated.isEmpty())
    }

    @Test
    fun aCrashAfterTheClaimResumesTheSameOperationAndNeverClaimsAgain() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        val prepared = fixture.prepare(fixture.discoverDrive())
        val operationId = (prepared as RecoveryResult.TakeoverConfirmationRequired).operationId
        fixture.staging.remoteStateStore.failTransitionToPhase = "CLAIM_VERIFIED"

        val interrupted = fixture.coordinator.confirmTakeover(operationId, fixture.store)

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.STAGING_INVARIANT),
            interrupted,
        )
        assertEquals("CLAIM_CREATED", fixture.staging.remoteStateStore.operationPhases.last())
        val claimedIds = fixture.store.createdIds.toList()
        val generatedBeforeResume = fixture.store.generatedIdCount

        val resumed = fixture.coordinator.confirmTakeover(operationId, fixture.store)

        assertTrue(resumed is RecoveryResult.Activated)
        assertEquals(claimedIds, fixture.store.createdIds)
        assertEquals(generatedBeforeResume, fixture.store.generatedIdCount)
        val claim = fixture.verifiedClaim(RecoveryTestLineage.SUCCESSOR_PROVIDER_ID)
        assertEquals(2L, claim.claim.writerEpoch)
    }

    @Test
    fun confirmingAnAlreadyActivatedRecoveryFailsClosed() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        val prepared = fixture.prepare(fixture.discoverDrive())
        val operationId = (prepared as RecoveryResult.TakeoverConfirmationRequired).operationId
        val first = fixture.coordinator.confirmTakeover(operationId, fixture.store)
        assertTrue(first is RecoveryResult.Activated)
        val createdIds = fixture.store.createdIds.toList()

        // Activation clears the staged-recovery registry, so the operation can
        // no longer be resumed and nothing is created a second time.
        val second = fixture.coordinator.confirmTakeover(operationId, fixture.store)

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.STAGING_INVARIANT),
            second,
        )
        assertEquals(createdIds, fixture.store.createdIds)
        assertEquals(1, fixture.staging.activated.size)
    }

    @Test
    fun aCrashBetweenTheClaimCreateAndItsPhaseAdoptsItsOwnOccupant() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        val prepared = fixture.prepare(fixture.discoverDrive())
        val operationId = (prepared as RecoveryResult.TakeoverConfirmationRequired).operationId
        fixture.staging.remoteStateStore.failTransitionToPhase = "CLAIM_CREATED"

        val interrupted = fixture.coordinator.confirmTakeover(operationId, fixture.store)

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.STAGING_INVARIANT),
            interrupted,
        )
        assertTrue(fixture.store.contains(RecoveryTestLineage.SUCCESSOR_PROVIDER_ID))
        assertEquals(
            "CONFIRMATION_REQUIRED",
            fixture.staging.remoteStateStore.operationPhases.last(),
        )
        val claimed = fixture.store.requireBytes(RecoveryTestLineage.SUCCESSOR_PROVIDER_ID)

        val resumed = fixture.coordinator.confirmTakeover(operationId, fixture.store)

        assertTrue(resumed is RecoveryResult.Activated)
        // Re-encoding produces a fresh nonce, so the occupant is adopted through
        // authentication at the persisted identity rather than replaced.
        assertArrayEquals(
            claimed,
            fixture.store.requireBytes(RecoveryTestLineage.SUCCESSOR_PROVIDER_ID),
        )
        assertEquals(
            1,
            fixture.store.createdIds.count { it == RecoveryTestLineage.SUCCESSOR_PROVIDER_ID },
        )
    }

    @Test
    fun anotherWritersClaimTakenDuringTheCreateIsOwnershipLoss() = runRecoveryTest { fixture ->
        fixture.seedLineage()
        val prepared = fixture.prepare(fixture.discoverDrive())
        val operationId = (prepared as RecoveryResult.TakeoverConfirmationRequired).operationId
        // The only window this takeover can lose in: the reserved slot is taken
        // between the predecessor recheck and the create.
        fixture.takeOwnershipDuringTheSuccessorCreate()

        val result = fixture.coordinator.confirmTakeover(operationId, fixture.store)

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.OWNERSHIP_LOST),
            result,
        )
        assertTrue(fixture.staging.activated.isEmpty())
        assertEquals(1, fixture.staging.abandoned)
    }

    @Test
    fun anUnpreparedOperationCannotBeConfirmed() = runRecoveryTest { fixture ->
        fixture.seedLineage()

        val result = fixture.coordinator.confirmTakeover("recovery:unknown", fixture.store)

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.STAGING_INVARIANT),
            result,
        )
        assertTrue(fixture.store.createdIds.isEmpty())
    }

    // -- Portable ------------------------------------------------------------------------

    @Test
    fun aPortablePackageActivatesWithNoRemoteConfiguration() = runRecoveryTest { fixture ->
        val portable = fixture.writePortablePackage()

        val candidates = fixture.coordinator.discover(null, portable)
        val result = fixture.coordinator.prepare(
            candidates.single(),
            RecoveryTestLineage.PASSPHRASE.copyOf(),
            null,
            null,
        )

        assertEquals(
            RecoveryResult.Activated(
                generation = BackupGeneration(SNAPSHOT_GENERATION),
                lineageId = null,
            ),
            result,
        )
        assertEquals(RecoverySource.ANDROID_BACKUP_PACKAGE, candidates.single().source)
        assertNull(fixture.staging.remoteStateStore.stored)
        assertEquals(1, fixture.staging.activated.size)
    }

    @Test
    fun aPortablePackageWithAWrongPassphraseActivatesNothing() = runRecoveryTest { fixture ->
        val portable = fixture.writePortablePackage()
        val candidates = fixture.coordinator.discover(null, portable)

        val result = fixture.coordinator.prepare(
            candidates.single(),
            "not-the-passphrase".toCharArray(),
            null,
            null,
        )

        assertEquals(
            RecoveryResult.Failed(RecoveryFailureCategory.WRONG_PASSPHRASE),
            result,
        )
        assertTrue(fixture.staging.activated.isEmpty())
    }

    @Test
    fun aPortablePackageUnderAWeakenedKdfIsRejectedBeforeAnyDerivation() = runRecoveryTest { fixture ->
        val portable = fixture.writePortablePackage()
        fixture.weakenPortableKdf(portable)
        val candidates = fixture.coordinator.discover(null, portable)

        val result = fixture.coordinator.prepare(
            candidates.single(),
            RecoveryTestLineage.PASSPHRASE.copyOf(),
            null,
            null,
        )

        assertEquals(RecoveryResult.Failed(RecoveryFailureCategory.UNSAFE_KDF), result)
        assertEquals(0, fixture.crypto.unlockCount)
        assertTrue(fixture.staging.activated.isEmpty())
    }

    private fun runRecoveryTest(
        expectedVaultId: VaultId = VaultId(RecoveryTestLineage.VAULT_ID),
        retentionAdvance: Long = 0,
        block: suspend (RecoveryFixture) -> Unit,
    ) = runBlocking {
        withTimeout(5_000) {
            val root = Files.createTempDirectory("recovery-coordinator-test").toFile()
            val fixture = RecoveryFixture(root, expectedVaultId, retentionAdvance)
            try {
                block(fixture)
            } finally {
                fixture.close()
                root.deleteRecursively()
            }
        }
    }

    private companion object {
        const val SNAPSHOT_GENERATION = 53L
        const val RECOVERED_GENERATION = 55L
    }
}

/** Deterministic identities the seeded Drive lineage publishes under. */
internal object RecoveryTestLineage {
    const val LINEAGE_ID = "00000000-0000-4000-8000-0000000000c1"
    const val ROOT_CLAIM_ID = "00000000-0000-4000-8000-0000000000c2"
    const val BASELINE_PUBLICATION_ID = "00000000-0000-4000-8000-0000000000c3"
    const val FORKED_PUBLICATION_ID = "00000000-0000-4000-8000-0000000000c4"
    const val BASE_A_LOGICAL_ID = "00000000-0000-4000-8000-0000000000d1"
    const val BASE_B_LOGICAL_ID = "00000000-0000-4000-8000-0000000000d2"
    const val SEGMENT_LOGICAL_ID = "00000000-0000-4000-8000-0000000000d3"
    const val VAULT_ID = "vault-alpha"
    const val ROOT_PROVIDER_ID = "provider-recovery-root"
    const val SUCCESSOR_PROVIDER_ID = "provider-recovery-successor"
    const val BASELINE_PROVIDER_ID = "provider-recovery-baseline"
    const val FORKED_PROVIDER_ID = "provider-recovery-forked"
    const val BASE_A_PROVIDER_ID = "provider-recovery-base-a"
    const val BASE_B_PROVIDER_ID = "provider-recovery-base-b"
    const val SEGMENT_PROVIDER_ID = "provider-recovery-segment"
    const val OPERATION_ID = "operation-recovery-source"
    val PASSPHRASE: CharArray = "correct horse battery".toCharArray()
    val ACCOUNT_DIGEST: ByteArray = ByteArray(32) { (it + 3).toByte() }
}

private class RecoveryFixture(
    root: File,
    expectedVaultId: VaultId,
    retentionAdvance: Long,
) {
    private val providerRoot = File(root, "provider").also { it.mkdirs() }
    private val stagingRoot = File(root, "staging").also { it.mkdirs() }
    private val portableRoot = File(root, "portable").also { it.mkdirs() }

    val crypto = RecoveryVaultCrypto(TinkVaultCrypto())
    private val authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto)
    private val ownershipCodec = OwnershipClaimCodec(authenticatedCodec)
    private val publicationCodec = PublicationCodec(authenticatedCodec)
    private val portableCodec = PortableBackupCodec(authenticatedCodec)

    val vaultId = VaultId(RecoveryTestLineage.VAULT_ID)
    val store = FakeCreateOnlyBackupObjectStore(providerRoot)
    val staging = FakeRecoveryStagingFactory(retentionAdvance, crypto::createKey)

    val coordinator = DefaultRecoveryCoordinator(
        expectedVaultId = expectedVaultId,
        crypto = crypto,
        authenticatedCodec = authenticatedCodec,
        ownershipCodec = ownershipCodec,
        publicationCodec = publicationCodec,
        portableCodec = portableCodec,
        staging = staging,
        stagingRoot = stagingRoot,
        expectedAccountBindingDigest = RecoveryTestLineage.ACCOUNT_DIGEST,
    )

    private val snapshot = BackupPayloadTestFixtures.snapshot()
    private val segment = recoverySegment()

    fun close() = crypto.close()

    // -- Seeding -------------------------------------------------------------------------

    fun seedLineage() {
        putBase(RecoveryTestLineage.BASE_A_PROVIDER_ID, RecoveryTestLineage.BASE_A_LOGICAL_ID)
        putBase(RecoveryTestLineage.BASE_B_PROVIDER_ID, RecoveryTestLineage.BASE_B_LOGICAL_ID)
        putSegment()
        republishBaselineInventory()
    }

    /** Publishes the baseline over whatever the inventory objects now hold. */
    private fun republishBaselineInventory() {
        val baseline = encodedBaseline(
            publicationId = RecoveryTestLineage.BASELINE_PUBLICATION_ID,
            providerFileId = RecoveryTestLineage.BASELINE_PROVIDER_ID,
        )
        store.put(
            providerObjectId = RecoveryTestLineage.BASELINE_PROVIDER_ID,
            bytes = baseline,
            metadata = RemoteBackupTestFixtures.publicationMetadata(
                RecoveryTestLineage.BASELINE_PROVIDER_ID,
                1,
            ),
            lineageId = lineageId(),
        )
        val root = ownershipCodec.encode(rootClaim(hexDigestOf(baseline)), key())
        store.put(
            providerObjectId = RecoveryTestLineage.ROOT_PROVIDER_ID,
            bytes = root,
            metadata = RemoteBackupTestFixtures.claimMetadata(
                ownershipCodec.readPublicHeader(root),
            ),
            lineageId = lineageId(),
        )
    }

    /** Seeds a lineage whose declared current base no longer authenticates. */
    suspend fun storeWithDamagedCurrentAndValidFallback(): RecoveryCandidate {
        seedLineage()
        damageBase(RecoveryTestLineage.BASE_A_PROVIDER_ID)
        return discoverDrive()
    }

    fun damageBase(providerObjectId: String) {
        val original = store.requireBytes(providerObjectId)
        store.put(
            providerObjectId = providerObjectId,
            bytes = ByteArray(original.size) { 0x5a },
            metadata = RemoteBackupTestFixtures.publicationMetadata(providerObjectId, 1)
                .copy(role = RemoteObjectRoleV1.SNAPSHOT),
            lineageId = lineageId(),
        )
    }

    /** Re-encrypts a base under another remote logical object of the same lineage. */
    fun reidentifyBase(providerObjectId: String, logicalObjectId: String) {
        val frame = snapshotFrame(logicalObjectId)
        store.put(
            providerObjectId = providerObjectId,
            bytes = frame,
            metadata = RemoteBackupTestFixtures.publicationMetadata(providerObjectId, 1)
                .copy(role = RemoteObjectRoleV1.SNAPSHOT),
            lineageId = lineageId(),
        )
    }

    /** Publishes a second sequence-zero baseline bound to the same ownership root. */
    fun seedForkedBaseline() {
        val forked = encodedBaseline(
            publicationId = RecoveryTestLineage.FORKED_PUBLICATION_ID,
            providerFileId = RecoveryTestLineage.FORKED_PROVIDER_ID,
        )
        store.put(
            providerObjectId = RecoveryTestLineage.FORKED_PROVIDER_ID,
            bytes = forked,
            metadata = RemoteBackupTestFixtures.publicationMetadata(
                RecoveryTestLineage.FORKED_PROVIDER_ID,
                1,
            ),
            lineageId = lineageId(),
        )
    }

    fun seedTerminalSuccessor() {
        val tombstone = ownershipCodec.encode(
            RemoteBackupTestFixtures.tombstoneOf(verifiedRoot()),
            key(),
        )
        store.put(
            providerObjectId = RecoveryTestLineage.SUCCESSOR_PROVIDER_ID,
            bytes = tombstone,
            metadata = RemoteBackupTestFixtures.claimMetadata(
                ownershipCodec.readPublicHeader(tombstone),
            ),
            lineageId = lineageId(),
        )
    }

    /** Occupies the reserved successor slot with another writer's valid claim. */
    fun seedForeignSuccessorClaim() {
        val foreign = ownershipCodec.encode(
            RemoteBackupTestFixtures.successorOf(
                predecessor = verifiedRoot(),
                providerFileId = RecoveryTestLineage.SUCCESSOR_PROVIDER_ID,
                activeDeviceId = RemoteBackupTestFixtures.OTHER_DEVICE_ID,
            ).copy(sourceVaultId = RecoveryTestLineage.VAULT_ID),
            key(),
        )
        store.put(
            providerObjectId = RecoveryTestLineage.SUCCESSOR_PROVIDER_ID,
            bytes = foreign,
            metadata = RemoteBackupTestFixtures.claimMetadata(
                ownershipCodec.readPublicHeader(foreign),
            ),
            lineageId = lineageId(),
        )
    }

    /** Moves the tip past the predecessor this recovery recorded. */
    fun seedForeignSuccessorChain() {
        seedForeignSuccessorClaim()
        val moved = store.requireBytes(RecoveryTestLineage.SUCCESSOR_PROVIDER_ID)
        val verified = ownershipCodec.verify(moved, key())
        val next = ownershipCodec.encode(
            RemoteBackupTestFixtures.successorOf(
                predecessor = verified,
                providerFileId = checkNotNull(verified.claim.nextSuccessorProviderFileId),
                activeDeviceId = RemoteBackupTestFixtures.OTHER_DEVICE_ID,
            ).copy(sourceVaultId = RecoveryTestLineage.VAULT_ID),
            key(),
        )
        store.put(
            providerObjectId = checkNotNull(verified.claim.nextSuccessorProviderFileId),
            bytes = next,
            metadata = RemoteBackupTestFixtures.claimMetadata(
                ownershipCodec.readPublicHeader(next),
            ),
            lineageId = lineageId(),
        )
    }

    /** Republishes the fallback base with content the current base disagrees with. */
    fun replaceFallbackBaseContent() {
        val divergent = snapshot.copy(
            records = snapshot.records.filterNot { record ->
                record.family == BackupRecordFamily.CHECKLIST_ITEM
            },
        )
        val plaintext = BackupSnapshotCodec.encode(divergent)
        val frame = try {
            authenticatedCodec.encrypt(
                identity(CloudObjectFamily.SNAPSHOT, RecoveryTestLineage.BASE_B_LOGICAL_ID),
                plaintext,
                key(),
            )
        } finally {
            plaintext.fill(0)
        }
        store.put(
            providerObjectId = RecoveryTestLineage.BASE_B_PROVIDER_ID,
            bytes = frame,
            metadata = RemoteBackupTestFixtures
                .publicationMetadata(RecoveryTestLineage.BASE_B_PROVIDER_ID, 1)
                .copy(role = RemoteObjectRoleV1.SNAPSHOT),
            lineageId = lineageId(),
        )
        // The publication still declares the bytes actually stored, so the two
        // bases differ only in the content they authenticate to.
        republishBaselineInventory()
    }

    /**
     * Takes the lineage the moment this recovery creates its epoch baseline,
     * which is the only object a preparation creates.
     */
    fun takeOwnershipDuringTheBaselineCreate() {
        store.beforeCreate = {
            store.beforeCreate = null
            seedForeignSuccessorClaim()
        }
    }

    /** Takes the reserved successor slot the moment this recovery creates at it. */
    fun takeOwnershipDuringTheSuccessorCreate() {
        store.beforeCreate = { providerObjectId ->
            if (providerObjectId.value == RecoveryTestLineage.SUCCESSOR_PROVIDER_ID) {
                store.beforeCreate = null
                seedForeignSuccessorClaim()
            }
        }
    }

    /** Rewrites the published baseline's public KDF cost below the supported profile. */
    fun weakenBaselineKdf() {
        val original = store.requireBytes(RecoveryTestLineage.BASELINE_PROVIDER_ID)
        store.put(
            providerObjectId = RecoveryTestLineage.BASELINE_PROVIDER_ID,
            bytes = weakenedKdfBytes(original),
            metadata = RemoteBackupTestFixtures.publicationMetadata(
                RecoveryTestLineage.BASELINE_PROVIDER_ID,
                1,
            ),
            lineageId = lineageId(),
        )
    }

    fun weakenPortableKdf(file: File) {
        file.writeBytes(weakenedKdfBytes(file.readBytes()))
    }

    fun writePortablePackage(): File {
        val envelope = RemoteBackupTestFixtures.envelope()
        val encoded = portableCodec.encode(
            recoveryEnvelope = envelope,
            snapshot = snapshot,
            producedAtEpochMillis = 1_700_000_000_000,
            key = key(),
        )
        return File(portableRoot, "package.otbk").also { it.writeBytes(encoded) }
    }

    // -- Reading -------------------------------------------------------------------------

    suspend fun discoverDrive(): RecoveryCandidate =
        coordinator.discover(store, null).single { it.source == RecoverySource.GOOGLE_DRIVE }

    suspend fun prepare(candidate: RecoveryCandidate): RecoveryResult = coordinator.prepare(
        candidate,
        RecoveryTestLineage.PASSPHRASE.copyOf(),
        store,
        RecoveryTestLineage.ACCOUNT_DIGEST,
    )

    fun rootHeader(): OwnershipPublicHeaderV1 =
        ownershipCodec.readPublicHeader(store.requireBytes(RecoveryTestLineage.ROOT_PROVIDER_ID))

    fun verifiedRoot(): VerifiedOwnershipClaim =
        ownershipCodec.verify(store.requireBytes(RecoveryTestLineage.ROOT_PROVIDER_ID), key())

    fun verifiedClaim(providerObjectId: String): VerifiedOwnershipClaim =
        ownershipCodec.verify(store.requireBytes(providerObjectId), key())

    /** The payload one stored base authenticates to at its remote identity. */
    fun decodeBase(
        providerObjectId: ProviderObjectId,
        logicalObjectId: String,
    ): BackupSnapshotPayloadV1 {
        val bytes = store.requireBytes(providerObjectId.value)
        val decoded = authenticatedCodec.decrypt(bytes.inputStream(), bytes.size.toLong(), key())
        val success = decoded as CloudDecodeResult.Success
        return success.value.use { value ->
            check(value.identity == identity(CloudObjectFamily.SNAPSHOT, logicalObjectId)) {
                "The base is not authenticated at the lineage plus its logical object"
            }
            BackupSnapshotCodec.decodeOwned(value.takePlaintext())
        }
    }

    /** The baseline this recovery reserved a slot for and created. */
    fun plannedBaseline(): VerifiedPublication {
        val providerObjectId = store.createdIds.first { it.startsWith("generated-PUBLICATION") }
        return publicationCodec.verify(store.requireBytes(providerObjectId), key())
    }

    // -- Encoding ------------------------------------------------------------------------

    private fun lineageId(): CloudLineageId = CloudLineageId.parse(RecoveryTestLineage.LINEAGE_ID)

    private fun key(): VaultKey = crypto.createKey()

    private fun putBase(providerObjectId: String, logicalObjectId: String) {
        store.put(
            providerObjectId = providerObjectId,
            bytes = snapshotFrame(logicalObjectId),
            metadata = RemoteBackupTestFixtures.publicationMetadata(providerObjectId, 1)
                .copy(role = RemoteObjectRoleV1.SNAPSHOT),
            lineageId = lineageId(),
        )
    }

    private fun putSegment() {
        store.put(
            providerObjectId = RecoveryTestLineage.SEGMENT_PROVIDER_ID,
            bytes = segmentFrame(),
            metadata = RemoteBackupTestFixtures
                .publicationMetadata(RecoveryTestLineage.SEGMENT_PROVIDER_ID, 1)
                .copy(role = RemoteObjectRoleV1.SEGMENT),
            lineageId = lineageId(),
        )
    }

    private fun snapshotFrame(logicalObjectId: String): ByteArray {
        val plaintext = BackupSnapshotCodec.encode(snapshot)
        return try {
            authenticatedCodec.encrypt(
                identity(CloudObjectFamily.SNAPSHOT, logicalObjectId),
                plaintext,
                key(),
            )
        } finally {
            plaintext.fill(0)
        }
    }

    private fun segmentFrame(): ByteArray {
        val plaintext = BackupOperationSegmentCodec.encode(segment)
        return try {
            authenticatedCodec.encrypt(
                identity(
                    CloudObjectFamily.OPERATION_SEGMENT,
                    RecoveryTestLineage.SEGMENT_LOGICAL_ID,
                ),
                plaintext,
                key(),
            )
        } finally {
            plaintext.fill(0)
        }
    }

    private fun identity(
        family: CloudObjectFamily,
        objectId: String,
    ): CloudHeaderIdentity = CloudHeaderIdentity(
        family = family,
        schemaVersion = 1,
        cryptoVersion = 1,
        minimumReaderVersion = 1,
        vaultId = RecoveryTestLineage.LINEAGE_ID,
        objectId = objectId,
    )

    private fun inventory(): List<RemoteInventoryItemV1> = listOf(
        inventoryItem(
            RecoveryTestLineage.BASE_A_LOGICAL_ID,
            RecoveryTestLineage.BASE_A_PROVIDER_ID,
            RemoteObjectRoleV1.SNAPSHOT,
            53,
            53,
        ),
        inventoryItem(
            RecoveryTestLineage.BASE_B_LOGICAL_ID,
            RecoveryTestLineage.BASE_B_PROVIDER_ID,
            RemoteObjectRoleV1.SNAPSHOT,
            53,
            53,
        ),
        inventoryItem(
            RecoveryTestLineage.SEGMENT_LOGICAL_ID,
            RecoveryTestLineage.SEGMENT_PROVIDER_ID,
            RemoteObjectRoleV1.SEGMENT,
            54,
            55,
        ),
    ).sortedBy(RemoteInventoryItemV1::logicalObjectId)

    private fun inventoryItem(
        logicalObjectId: String,
        providerFileId: String,
        role: RemoteObjectRoleV1,
        firstGeneration: Long,
        lastGeneration: Long,
    ): RemoteInventoryItemV1 {
        val bytes = store.requireBytes(providerFileId)
        return RemoteInventoryItemV1(
            logicalObjectId = logicalObjectId,
            providerFileId = providerFileId,
            role = role,
            firstGeneration = firstGeneration,
            lastGeneration = lastGeneration,
            frameLength = bytes.size.toLong(),
            frameSha256 = hexDigestOf(bytes),
        )
    }

    private fun encodedBaseline(
        publicationId: String,
        providerFileId: String,
    ): ByteArray {
        val envelope = RemoteBackupTestFixtures.envelope()
        val draft = PublicationManifestV1(
            bootstrapSha256 = ZERO_SHA256,
            lineageId = RecoveryTestLineage.LINEAGE_ID,
            sourceVaultId = RecoveryTestLineage.VAULT_ID,
            writerEpoch = 1,
            activeDeviceId = RemoteBackupTestFixtures.DEVICE_ID,
            publicationProviderFileId = providerFileId,
            publicationId = publicationId,
            publicationSequence = 0,
            predecessorPublicationProviderFileId = null,
            predecessorPublicationId = null,
            predecessorPublicationSha256 = null,
            baseline = true,
            plannedClaimProviderFileId = RecoveryTestLineage.ROOT_PROVIDER_ID,
            plannedClaimId = RecoveryTestLineage.ROOT_CLAIM_ID,
            predecessorClaimProviderFileId = null,
            predecessorClaimId = null,
            predecessorClaimSha256 = null,
            ownershipClaimProviderFileId = null,
            ownershipClaimId = null,
            ownershipClaimSha256 = null,
            localGeneration = 55,
            publicationOperationId = RecoveryTestLineage.OPERATION_ID,
            currentBaseObjectId = RecoveryTestLineage.BASE_A_LOGICAL_ID,
            fallbackBaseObjectId = RecoveryTestLineage.BASE_B_LOGICAL_ID,
            inventory = inventory(),
            recoveryCredentialGeneration = 0,
        )
        val manifest = draft.copy(
            bootstrapSha256 = publicationCodec.bootstrapSha256(draft, envelope),
        )
        return publicationCodec.encode(manifest, envelope, key())
    }

    private fun rootClaim(baselineSha256: String): OwnershipClaimV1 =
        RemoteBackupTestFixtures.activeRoot(
            providerFileId = RecoveryTestLineage.ROOT_PROVIDER_ID,
            claimId = RecoveryTestLineage.ROOT_CLAIM_ID,
            lineageId = RecoveryTestLineage.LINEAGE_ID,
            nextSuccessorProviderFileId = RecoveryTestLineage.SUCCESSOR_PROVIDER_ID,
            activeDeviceId = RemoteBackupTestFixtures.DEVICE_ID,
            baselinePublicationProviderFileId = RecoveryTestLineage.BASELINE_PROVIDER_ID,
            baselinePublicationId = RecoveryTestLineage.BASELINE_PUBLICATION_ID,
            baselinePublicationSha256 = baselineSha256,
        )

    /**
     * Rewrites the public KDF cost inside a length-prefixed public JSON prefix,
     * leaving every other byte of the file alone.
     */
    private fun weakenedKdfBytes(source: ByteArray): ByteArray {
        val length = ((source[0].toInt() and 0xff) shl 24) or
            ((source[1].toInt() and 0xff) shl 16) or
            ((source[2].toInt() and 0xff) shl 8) or
            (source[3].toInt() and 0xff)
        val prefix = source.copyOfRange(4, 4 + length).toString(Charsets.UTF_8)
        val weakened = prefix.replace("\"memoryKiB\":65536", "\"memoryKiB\":8")
        check(weakened != prefix) { "The public prefix declared no recovery memory cost" }
        val encoded = weakened.toByteArray(Charsets.UTF_8)
        return ByteArray(4).apply {
            this[0] = (encoded.size ushr 24).toByte()
            this[1] = (encoded.size ushr 16).toByte()
            this[2] = (encoded.size ushr 8).toByte()
            this[3] = encoded.size.toByte()
        } + encoded + source.copyOfRange(4 + length, source.size)
    }

    private companion object {
        const val ZERO_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}

/** One UPSERT bridging generations 54 and 55 after the complete base at 53. */
private fun recoverySegment(): BackupOperationSegmentPayloadV1 {
    val entries = listOf(
        segmentEntry("operation-recovery-1", 54, "tag-recovery-a", "Recovery A"),
        segmentEntry("operation-recovery-2", 55, "tag-recovery-b", "Recovery B"),
    )
    return BackupOperationSegmentPayloadV1(
        vaultId = RecoveryTestLineage.VAULT_ID,
        firstGeneration = 54,
        lastGeneration = 55,
        entries = entries,
        entryCount = entries.size,
    )
}

private fun segmentEntry(
    operationId: String,
    generation: Long,
    tagId: String,
    name: String,
): BackupSegmentEntryV1 {
    val payload = BackupMutationCodec.encode(
        BackupMutationPayloadV1(
            mutationKind = BackupMutationKind.UPSERT,
            record = TagEntity(
                id = tagId,
                workspaceId = "workspace-1",
                name = name,
            ).toBackupRecordV1(),
            deletedFamily = null,
            deletedIdentity = null,
        ),
    )
    return BackupSegmentEntryV1(
        operationId = operationId,
        generation = generation,
        sequence = 0,
        objectId = tagId,
        objectType = BackupRecordFamily.TAG.name,
        revisionWallMillis = generation,
        revisionLogical = 0,
        sourceDeviceId = "device-alpha",
        payloadBase64 = Base64.getEncoder().withoutPadding().encodeToString(payload),
    )
}

/**
 * Addresses one vault's content under every handle it issues, and unlocks only
 * for the one passphrase the seeded lineage was published under.
 *
 * Recovery derives its key through Argon2id, which no unit test can afford to
 * run repeatedly, so derivation is modelled by an exact passphrase comparison
 * while the content itself stays genuinely authenticated encryption.
 */
internal class RecoveryVaultCrypto(private val delegate: VaultCrypto) : VaultCrypto {
    private val established: VaultKey = delegate.createKey()

    var unlockCount = 0
        private set

    override fun createKey(): VaultKey = delegate.createKey()

    override fun wrapForRecovery(
        unlockedKey: VaultKey,
        passphrase: CharArray,
    ): VaultKeyEnvelope = error("Recovery must not wrap a new recovery envelope")

    override fun unlock(
        passphrase: CharArray,
        envelope: VaultKeyEnvelope,
    ): VaultKey {
        unlockCount += 1
        require(passphrase.concatToString() == RecoveryTestLineage.PASSPHRASE.concatToString()) {
            "The recovery passphrase does not unlock this envelope"
        }
        return delegate.createKey()
    }

    override fun changePassphrase(
        unlockedKey: VaultKey,
        newPassphrase: CharArray,
    ): VaultKeyEnvelope = error("Recovery must not rotate a recovery passphrase")

    override fun encryptBytes(
        key: VaultKey,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = delegate.encryptBytes(established, plaintext, associatedData)

    override fun decryptBytes(
        key: VaultKey,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = delegate.decryptBytes(established, ciphertext, associatedData)

    fun close() = established.close()
}

/**
 * A staged slot with no Room behind it.
 *
 * Reconstruction still runs the recovery import plan, so an incoherent
 * snapshot, an out-of-order segment inventory, or a payload that does not reach
 * the recovered generation fails here exactly as it would against a database.
 */
internal class FakeRecoveryStagingFactory(
    private val retentionAdvance: Long,
    private val contentKeys: () -> VaultKey,
) : RecoveryStagingFactory {
    val remoteStateStore = InMemoryRemoteBackupStateStore()
    val sessions = mutableListOf<FakeRecoveryStagingSession>()
    val activated = mutableListOf<VerifiedStagedVault>()
    var abandoned = 0
        private set
    var reconstructFailure: (() -> Throwable)? = null

    /**
     * Operations whose staged-recovery registry record is gone, exactly as
     * `VaultRuntimeManager` leaves them once a slot is published or discarded.
     * Nothing can resume one afterwards.
     */
    private val released = mutableSetOf<String>()

    override suspend fun begin(operationId: String): RecoveryStagingSession =
        FakeRecoveryStagingSession(
            operationId = operationId,
            slot = VaultSlot.new(),
            remoteStateStore = remoteStateStore,
            retentionAdvance = retentionAdvance,
            contentKeys = contentKeys,
            failure = { reconstructFailure?.invoke() },
        ).also { sessions += it }

    override suspend fun resume(operationId: String): RecoveryStagingSession? =
        sessions.lastOrNull { it.operationId == operationId && it.operationId !in released }

    override suspend fun activate(
        session: RecoveryStagingSession,
        staged: VerifiedStagedVault,
    ): RemoteBackupStateStore {
        session.close()
        released += session.operationId
        activated += staged
        return remoteStateStore
    }

    override suspend fun abandon(session: RecoveryStagingSession) {
        session.close()
        released += session.operationId
        abandoned += 1
    }
}

internal class FakeRecoveryStagingSession(
    override val operationId: String,
    override val slot: VaultSlot,
    override val remoteStateStore: RemoteBackupStateStore,
    private val retentionAdvance: Long,
    private val contentKeys: () -> VaultKey,
    private val failure: () -> Throwable?,
) : RecoveryStagingSession {
    var request: RecoveryImportRequest? = null
        private set
    var closed = false
        private set

    override suspend fun reconstruct(
        request: RecoveryImportRequest,
        contentKey: VaultKey,
    ): VerifiedStagedVault {
        failure()?.let { throw it }
        val capture = request.expectedCapture()
        this.request = request
        return VerifiedStagedVault(
            slot = slot,
            vaultId = capture.vaultId,
            recoveredGeneration = capture.generation,
            activationGeneration = BackupGeneration(capture.generation.value + retentionAdvance),
        )
    }

    override fun openContentKey(vaultId: VaultId): VaultKey = contentKeys()

    override fun close() {
        closed = true
    }
}
