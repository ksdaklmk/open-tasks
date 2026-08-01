package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.domain.BackupCoordinator
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupConnectResult
import app.opentasks.core.domain.RemoteBackupOperation
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OwnershipClaimId
import app.opentasks.core.model.OwnershipStateV1
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.VaultId
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRemoteBackupConfiguratorTest {

    @Test
    fun initialSetupCreatesTwoIndependentBasesBeforeRoot() = runConnectTest { fixture ->
        val result = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertTrue(result is RemoteBackupConnectResult.Connected)
        assertNotEquals(
            fixture.store.baseRequests[0].logicalObjectId,
            fixture.store.baseRequests[1].logicalObjectId,
        )
        assertEquals(
            listOf("BASE_A", "BASE_B", "BASELINE", "ROOT"),
            fixture.store.authorityEvents,
        )
    }

    @Test
    fun everyRootIsDiscoveredBeforeAnyProviderIdentityIsGenerated() = runConnectTest { fixture ->
        fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        val firstList = fixture.store.callOrder.indexOfFirst { it.startsWith("list:") }
        val firstGenerate =
            fixture.store.callOrder.indexOfFirst { it.startsWith("generateProviderIds:") }
        assertTrue(firstList >= 0)
        assertTrue(firstList < firstGenerate)
        assertEquals(
            RemoteObjectRoleV1.OWNERSHIP_ROOT,
            fixture.store.listRequests.first().role,
        )
        assertNull(fixture.store.listRequests.first().lineageId)
    }

    @Test
    fun anExistingOwnershipRootStopsSetupWithoutCreatingAnything() = runConnectTest { fixture ->
        fixture.store.seedRoot()

        val result = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertEquals(RemoteBackupConnectResult.ExistingBackupsFound(1), result)
        assertEquals(0, fixture.store.generatedIdCount)
        assertTrue(fixture.store.createdIds.isEmpty())
        assertTrue(fixture.store.uploadRequests.isEmpty())
        assertNull(fixture.remoteStateStore.stored)
    }

    @Test
    fun anExplicitlySeparateLineageMayJoinAnAccountThatAlreadyHoldsRoots() = runConnectTest { fixture ->
        fixture.store.seedRoot()

        val result = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, true)

        assertTrue(result is RemoteBackupConnectResult.Connected)
        assertNotEquals(
            CloudLineageId.parse(RemoteBackupTestFixtures.LINEAGE_ID),
            (result as RemoteBackupConnectResult.Connected).lineageId,
        )
    }

    @Test
    fun aSeparateLineageDoesNotAdoptACompletedOrdinaryConnectionOperation() =
        runConnectTest { fixture ->
            val original = fixture.configurator.connect(
                fixture.store,
                ACCOUNT_DIGEST,
                false,
            ) as RemoteBackupConnectResult.Connected
            fixture.remoteStateStore.dropStoredConfiguration()

            val separate = fixture.configurator.connect(
                fixture.store,
                ACCOUNT_DIGEST,
                true,
            ) as RemoteBackupConnectResult.Connected

            assertNotEquals(original.lineageId, separate.lineageId)
        }

    @Test
    fun bothBasesReauthenticateTheSameCompleteCaptureUnderIndependentIdentities() = runConnectTest { fixture ->
        fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        val first = fixture.store.baseRequests[0]
        val second = fixture.store.baseRequests[1]
        assertNotEquals(first.providerObjectId, second.providerObjectId)
        assertNotEquals(first.frameSha256, second.frameSha256)
        assertEquals(RemoteObjectRoleV1.SNAPSHOT, first.role)
        assertEquals(RemoteObjectRoleV1.SNAPSHOT, second.role)
        val decodedFirst = fixture.decodeBase(first.providerObjectId, first.logicalObjectId.value)
        val decodedSecond = fixture.decodeBase(second.providerObjectId, second.logicalObjectId.value)
        assertEquals(decodedFirst.coveredGeneration, decodedSecond.coveredGeneration)
        assertEquals(SNAPSHOT_GENERATION, decodedFirst.coveredGeneration)
        // Both bases are downloaded and authenticated before ownership is claimed.
        assertTrue(fixture.store.downloadIds.containsAll(
            listOf(first.providerObjectId.value, second.providerObjectId.value),
        ))
    }

    @Test
    fun theBaselineIsSequenceZeroAndBoundToThePlannedOwnershipRoot() = runConnectTest { fixture ->
        fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        val baseline = fixture.verifiedBaseline()
        val rootProviderId = fixture.rootProviderId()
        assertTrue(baseline.manifest.baseline)
        assertEquals(0L, baseline.manifest.publicationSequence)
        assertEquals(rootProviderId.value, baseline.manifest.plannedClaimProviderFileId)
        assertNull(baseline.manifest.ownershipClaimProviderFileId)
        assertEquals(
            fixture.store.baseRequests.map { it.logicalObjectId.value }.sorted(),
            baseline.manifest.inventory.map { it.logicalObjectId }.sorted(),
        )
        assertEquals(SNAPSHOT_GENERATION, baseline.manifest.localGeneration)
    }

    @Test
    fun theOwnershipRootBindsTheVerifiedBaselinePublication() = runConnectTest { fixture ->
        fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        val baseline = fixture.verifiedBaseline()
        val root = fixture.verifiedRoot()
        assertEquals(1L, root.claim.writerEpoch)
        assertEquals(
            baseline.manifest.publicationProviderFileId,
            root.claim.baselinePublicationProviderFileId,
        )
        assertEquals(baseline.manifest.publicationId, root.claim.baselinePublicationId)
        assertEquals(baseline.completeSha256.value, root.claim.baselinePublicationSha256)
        assertEquals(baseline.manifest.plannedClaimId, root.claim.claimId)
    }

    @Test
    fun remoteBackupBecomesActiveOnlyAfterRootReadbackAndFullReresolution() = runConnectTest { fixture ->
        val result = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        val connected = result as RemoteBackupConnectResult.Connected
        val configuration = checkNotNull(fixture.remoteStateStore.stored)
        assertEquals(RemoteBackupLifecycle.ACTIVE, configuration.lifecycle)
        assertEquals(connected.lineageId, configuration.lineageId)
        assertEquals(SNAPSHOT_GENERATION, checkNotNull(configuration.lastVerifiedGeneration).value)
        assertEquals(
            fixture.rootProviderId(),
            checkNotNull(configuration.ownershipClaim).providerId,
        )
        assertNull(configuration.previousPublication)
        assertEquals("COMPLETED", fixture.remoteStateStore.operationPhases.last())
        assertTrue(
            fixture.remoteStateStore.operationPhases.indexOf("ROOT_VERIFIED") <
                fixture.remoteStateStore.operationPhases.indexOf("COMPLETED"),
        )
    }

    @Test
    fun crashAfterRootCreationResumesWithoutCreatingASecondRoot() = runConnectTest { fixture ->
        fixture.store.failNextReadOfRole = RemoteObjectRoleV1.OWNERSHIP_ROOT

        val interrupted = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertEquals(
            RemoteBackupConnectResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
            interrupted,
        )
        assertEquals("ROOT_CREATED", fixture.remoteStateStore.operationPhases.last())
        assertEquals(
            RemoteBackupLifecycle.CONNECTING,
            checkNotNull(fixture.remoteStateStore.stored).lifecycle,
        )
        val generatedBeforeResume = fixture.store.generatedIdCount

        val resumed = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertTrue(resumed is RemoteBackupConnectResult.Connected)
        assertEquals(generatedBeforeResume, fixture.store.generatedIdCount)
        assertEquals(1, fixture.store.authorityEvents.count { it == "ROOT" })
        assertEquals(1, fixture.store.authorityEvents.count { it == "BASELINE" })
        assertEquals(2, fixture.store.baseRequests.size)
        assertEquals(
            RemoteBackupLifecycle.ACTIVE,
            checkNotNull(fixture.remoteStateStore.stored).lifecycle,
        )
    }

    @Test
    fun crashBetweenTheBaseUploadAndItsPhaseResumesWithoutASecondUpload() = runConnectTest { fixture ->
        fixture.remoteStateStore.failTransitionToPhase = "BASE_A_VERIFIED"

        val interrupted = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertEquals(
            RemoteBackupConnectResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE),
            interrupted,
        )
        assertEquals(listOf("BASE_A"), fixture.store.authorityEvents)
        assertEquals(1, fixture.store.baseRequests.size)
        assertFalse(fixture.remoteStateStore.operationPhases.contains("BASE_A_VERIFIED"))

        val resumed = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertTrue(resumed is RemoteBackupConnectResult.Connected)
        // Base A was adopted from the provider, so only base B was uploaded.
        assertEquals(2, fixture.store.baseRequests.size)
        assertEquals(
            listOf("BASE_A", "BASE_B", "BASELINE", "ROOT"),
            fixture.store.authorityEvents,
        )
        assertEquals(
            RemoteBackupLifecycle.ACTIVE,
            checkNotNull(fixture.remoteStateStore.stored).lifecycle,
        )
    }

    @Test
    fun crashBetweenTheConnectingRowAndItsPhaseResumesAndCompletes() = runConnectTest { fixture ->
        // The durable configuration row is written before the phase that
        // records it. A crash in that window must not leave an orphan that
        // rejects every later attempt to connect this vault.
        fixture.remoteStateStore.failTransitionToPhase = "IDENTITIES_STORED"

        val interrupted = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertEquals(
            RemoteBackupConnectResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE),
            interrupted,
        )
        assertEquals(
            RemoteBackupLifecycle.CONNECTING,
            checkNotNull(fixture.remoteStateStore.stored).lifecycle,
        )
        assertFalse(fixture.remoteStateStore.operationPhases.contains("IDENTITIES_STORED"))
        assertTrue(fixture.store.createdIds.isEmpty())

        val resumed = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertTrue(resumed is RemoteBackupConnectResult.Connected)
        assertEquals(
            RemoteBackupLifecycle.ACTIVE,
            checkNotNull(fixture.remoteStateStore.stored).lifecycle,
        )
        assertEquals(
            fixture.rootProviderId(),
            checkNotNull(checkNotNull(fixture.remoteStateStore.stored).ownershipClaim).providerId,
        )
    }

    @Test
    fun anotherVaultsConnectingRowIsNeverAdoptedAtThisLineage() = runConnectTest { fixture ->
        fixture.remoteStateStore.failTransitionToPhase = "IDENTITIES_STORED"
        fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)
        fixture.remoteStateStore.reassignStoredVault(VaultId("vault-other"))

        val resumed = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertEquals(
            RemoteBackupConnectResult.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            ),
            resumed,
        )
        assertTrue(fixture.store.createdIds.isEmpty())
    }

    @Test
    fun crashBetweenTheBaselineCreateAndItsPhaseResumesWithoutASecondBaseline() = runConnectTest { fixture ->
        fixture.remoteStateStore.failTransitionToPhase = "BASELINE_CREATED"

        val interrupted = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertEquals(
            RemoteBackupConnectResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE),
            interrupted,
        )
        assertEquals(listOf("BASE_A", "BASE_B", "BASELINE"), fixture.store.authorityEvents)

        val resumed = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertTrue(resumed is RemoteBackupConnectResult.Connected)
        assertEquals(1, fixture.store.authorityEvents.count { it == "BASELINE" })
        assertEquals(
            listOf("BASE_A", "BASE_B", "BASELINE", "ROOT"),
            fixture.store.authorityEvents,
        )
        // The root binds whichever baseline bytes actually survived.
        assertEquals(
            fixture.verifiedBaseline().completeSha256.value,
            fixture.verifiedRoot().claim.baselinePublicationSha256,
        )
    }

    @Test
    fun crashBetweenTheRootCreateAndItsPhaseResumesWithoutASecondRoot() = runConnectTest { fixture ->
        fixture.remoteStateStore.failTransitionToPhase = "ROOT_CREATED"

        val interrupted = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertEquals(
            RemoteBackupConnectResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE),
            interrupted,
        )
        assertEquals(listOf("BASE_A", "BASE_B", "BASELINE", "ROOT"), fixture.store.authorityEvents)
        assertEquals(
            RemoteBackupLifecycle.CONNECTING,
            checkNotNull(fixture.remoteStateStore.stored).lifecycle,
        )

        val resumed = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertTrue(resumed is RemoteBackupConnectResult.Connected)
        assertEquals(1, fixture.store.authorityEvents.count { it == "ROOT" })
        assertEquals(
            RemoteBackupLifecycle.ACTIVE,
            checkNotNull(fixture.remoteStateStore.stored).lifecycle,
        )
    }

    @Test
    fun anotherWritersRootAtTheReservedSlotIsStillOwnershipLost() = runConnectTest { fixture ->
        fixture.remoteStateStore.failTransitionToPhase = "ROOT_CREATED"
        fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)
        // Replace this connection's own root with a well-formed foreign root
        // occupying the same reserved provider slot.
        fixture.replaceRootWithForeignClaim()

        val resumed = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertEquals(
            RemoteBackupConnectResult.Failed(RemoteBackupFailureCategory.OWNERSHIP_LOST),
            resumed,
        )
        assertEquals(
            RemoteBackupLifecycle.CONNECTING,
            checkNotNull(fixture.remoteStateStore.stored).lifecycle,
        )
    }

    @Test
    fun anotherWritersBaselineAtTheReservedSlotIsStillAmbiguous() = runConnectTest { fixture ->
        fixture.remoteStateStore.failTransitionToPhase = "BASELINE_CREATED"
        fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)
        fixture.replaceBaselineWithForeignPublication()

        val resumed = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertEquals(
            RemoteBackupConnectResult.Failed(
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            ),
            resumed,
        )
    }

    @Test
    fun aCompletedConnectionIsIdempotent() = runConnectTest { fixture ->
        val first = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)
        val createdIds = fixture.store.createdIds.toList()

        val second = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertEquals(first, second)
        assertEquals(createdIds, fixture.store.createdIds)
    }

    @Test
    fun aRepeatedGeneratedProviderIdentityFailsClosedBeforeAnyCreate() = runConnectTest { fixture ->
        fixture.store.repeatGeneratedIds = true

        val result = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertEquals(
            RemoteBackupConnectResult.Failed(
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            ),
            result,
        )
        assertTrue(fixture.store.createdIds.isEmpty())
        assertTrue(fixture.store.uploadRequests.isEmpty())
    }

    @Test
    fun anUndecodableRecordedOperationIsNeverRestartedFromScratch() = runConnectTest { fixture ->
        fixture.remoteStateStore.putOperation(
            RemoteBackupOperation(
                operationId = "remote-connect:" + RemoteBackupTestFixtures.VAULT_ID,
                lineageId = CloudLineageId.parse(RemoteBackupTestFixtures.LINEAGE_ID),
                kind = "CONNECT",
                phase = "DISCOVERY_COMPLETED",
                targetEpoch = null,
                targetGeneration = null,
                candidateClaimProviderId = null,
                candidatePublicationProviderId = null,
                stateBytes = ByteArray(8) { (it + 1).toByte() },
                startedAt = Instant.ofEpochMilli(0),
                updatedAt = Instant.ofEpochMilli(0),
            ),
        )

        val result = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertEquals(
            RemoteBackupConnectResult.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            ),
            result,
        )
        assertEquals(0, fixture.store.generatedIdCount)
        assertTrue(fixture.store.createdIds.isEmpty())
    }

    @Test
    fun aDiscoveryListFailureFailsWithABoundedCategoryAndCreatesNothing() = runConnectTest { fixture ->
        fixture.store.listFailure = { IllegalStateException("provider list failed") }

        val result = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertEquals(
            RemoteBackupConnectResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
            result,
        )
        assertEquals(0, fixture.store.generatedIdCount)
        assertTrue(fixture.store.createdIds.isEmpty())
    }

    @Test
    fun anUploadFailureLeavesNoOwnershipClaimAtAll() = runConnectTest { fixture ->
        fixture.store.uploadFailure = RemoteBackupFailureCategory.PROVIDER_STORAGE

        val result = fixture.configurator.connect(fixture.store, ACCOUNT_DIGEST, false)

        assertEquals(
            RemoteBackupConnectResult.Failed(RemoteBackupFailureCategory.PROVIDER_STORAGE),
            result,
        )
        assertTrue(fixture.store.createdIds.isEmpty())
        assertFalse(fixture.remoteStateStore.operationPhases.contains("ROOT_CREATED"))
        assertEquals(
            RemoteBackupLifecycle.CONNECTING,
            checkNotNull(fixture.remoteStateStore.stored).lifecycle,
        )
    }

    private fun runConnectTest(block: suspend (ConnectFixture) -> Unit) = runBlocking {
        withTimeout(5_000) {
            val root = Files.createTempDirectory("remote-backup-configurator-test").toFile()
            val fixture = ConnectFixture(root)
            try {
                block(fixture)
            } finally {
                fixture.close()
                root.deleteRecursively()
            }
        }
    }

    private companion object {
        val ACCOUNT_DIGEST = ByteArray(32) { (it + 1).toByte() }
        const val SNAPSHOT_GENERATION = 53L
    }
}

private class ConnectFixture(root: File) {
    private val localRoot = File(root, "local").also { it.mkdirs() }
    private val stagingRoot = File(root, "staging").also { it.mkdirs() }
    private val providerRoot = File(root, "provider").also { it.mkdirs() }
    private val crypto = SingleContentVaultCrypto(RemoteBackupTestFixtures.crypto)
    private val authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto)

    val vaultId = VaultId(RemoteBackupTestFixtures.VAULT_ID)
    val localObjectStore: LocalBackupObjectStore = DefaultLocalBackupObjectStore(localRoot)
    val contentKeyStore = IssuedContentKeyStore(crypto)
    val backupStateStore = InMemoryStage2BackupStateStore(vaultId)
    val recoveryEnvelopeStore = InMemoryRecoveryEnvelopeStore(RemoteBackupTestFixtures.envelope())
    val remoteStateStore = InMemoryRemoteBackupStateStore()
    val store = FakeCreateOnlyBackupObjectStore(providerRoot)

    private val backupCoordinator = object : BackupCoordinator {
        override suspend fun request() = seedCompleteLocalBase()
    }

    val configurator = DefaultRemoteBackupConfigurator(
        vaultId = vaultId,
        backupCoordinator = backupCoordinator,
        backupStateStore = backupStateStore,
        recoveryEnvelopeStore = recoveryEnvelopeStore,
        contentKeyStore = contentKeyStore,
        remoteStateStore = remoteStateStore,
        remoteObjectCodec = RemoteObjectCodec(
            authenticatedCodec = authenticatedCodec,
            localObjectStore = localObjectStore,
            stagingRoot = stagingRoot,
        ),
        ownershipCodec = OwnershipClaimCodec(authenticatedCodec),
        publicationCodec = PublicationCodec(authenticatedCodec),
    )

    fun close() {
        crypto.close()
    }

    fun rootProviderId(): ProviderObjectId = ProviderObjectId.of(
        checkNotNull(remoteStateStore.stored).rootClaimProviderId.value,
    )

    private suspend fun publicationProviderId(): ProviderObjectId = checkNotNull(
        checkNotNull(
            remoteStateStore.operation("remote-connect:" + RemoteBackupTestFixtures.VAULT_ID),
        ).candidatePublicationProviderId,
    )

    private fun lineageId(): CloudLineageId = checkNotNull(remoteStateStore.stored).lineageId

    /** Puts a well-formed root belonging to another writer at the reserved slot. */
    fun replaceRootWithForeignClaim() {
        val providerObjectId = rootProviderId()
        val foreign = OwnershipClaimV1(
            lineageId = lineageId().value,
            writerEpoch = 1,
            state = OwnershipStateV1.ACTIVE,
            predecessorProviderFileId = null,
            predecessorClaimId = null,
            predecessorClaimSha256 = null,
            providerFileId = providerObjectId.value,
            claimId = OwnershipClaimId.new().value,
            predecessorReservedSuccessorProviderFileId = null,
            sourceVaultId = RemoteBackupTestFixtures.VAULT_ID,
            activeDeviceId = CloudDeviceId.new().value,
            nextSuccessorProviderFileId = "provider-foreign-successor",
            baselinePublicationProviderFileId = "provider-foreign-baseline",
            baselinePublicationId = PublicationId.new().value,
            baselinePublicationSha256 = RemoteBackupTestFixtures.DIGEST_A,
            recoveryCredentialGeneration = 0,
            creationOperationId = "operation-foreign",
            tombstoneId = null,
        )
        withContentKey { key ->
            val codec = OwnershipClaimCodec(authenticatedCodec)
            val encoded = codec.encode(foreign, key)
            store.put(
                providerObjectId = providerObjectId.value,
                bytes = encoded,
                metadata = RemoteBackupTestFixtures.claimMetadata(
                    codec.readPublicHeader(encoded),
                ),
                lineageId = lineageId(),
            )
        }
    }

    /** Puts a well-formed publication of another identity at the reserved slot. */
    suspend fun replaceBaselineWithForeignPublication() {
        val providerObjectId = publicationProviderId()
        val envelope = checkNotNull(recoveryEnvelopeStore.get(vaultId))
        withContentKey { key ->
            val codec = PublicationCodec(authenticatedCodec)
            val existing = codec.verify(store.requireBytes(providerObjectId.value), key)
            val foreign = existing.manifest.copy(publicationId = PublicationId.new().value)
            store.put(
                providerObjectId = providerObjectId.value,
                bytes = codec.encode(foreign, envelope, key),
                metadata = RemoteBackupTestFixtures.publicationMetadata(
                    providerObjectId.value,
                    1,
                ),
                lineageId = lineageId(),
            )
        }
    }

    fun verifiedRoot(): VerifiedOwnershipClaim = withContentKey { key ->
        OwnershipClaimCodec(authenticatedCodec).verify(
            store.requireBytes(rootProviderId().value),
            key,
        )
    }

    fun verifiedBaseline(): VerifiedPublication = withContentKey { key ->
        val publicationProviderId =
            checkNotNull(checkNotNull(remoteStateStore.stored).currentPublication).providerId
        PublicationCodec(authenticatedCodec).verify(
            store.requireBytes(publicationProviderId.value),
            key,
        )
    }

    fun decodeBase(
        providerObjectId: ProviderObjectId,
        logicalObjectId: String,
    ): BackupSnapshotPayloadV1 = withContentKey { key ->
        val bytes = store.requireBytes(providerObjectId.value)
        val decoded = authenticatedCodec.decrypt(
            bytes.inputStream(),
            bytes.size.toLong(),
            key,
        )
        val success = decoded as CloudDecodeResult.Success
        success.value.use { value ->
            check(
                value.identity == CloudHeaderIdentity(
                    family = CloudObjectFamily.SNAPSHOT,
                    schemaVersion = 1,
                    cryptoVersion = 1,
                    minimumReaderVersion = 1,
                    vaultId = checkNotNull(remoteStateStore.stored).lineageId.value,
                    objectId = logicalObjectId,
                ),
            ) { "Remote base identity is not the lineage plus its remote logical object" }
            BackupSnapshotCodec.decodeOwned(value.takePlaintext())
        }
    }

    private fun <T> withContentKey(block: (VaultKey) -> T): T {
        val key = contentKeyStore.openExisting(vaultId)
        return try {
            block(key)
        } finally {
            key.close()
        }
    }

    /** Stage 2 produces exactly one verified complete base covering generation 53. */
    private fun seedCompleteLocalBase() {
        if (backupStateStore.value.currentBaseObjectId != null) return
        val payload = BackupPayloadTestFixtures.snapshot()
        val plaintext = BackupSnapshotCodec.encode(payload)
        val key = contentKeyStore.openExisting(vaultId)
        val frame = try {
            authenticatedCodec.encrypt(
                CloudHeaderIdentity(
                    family = CloudObjectFamily.SNAPSHOT,
                    schemaVersion = 1,
                    cryptoVersion = 1,
                    minimumReaderVersion = 1,
                    vaultId = vaultId.value,
                    objectId = "snapshot:53",
                ),
                plaintext,
                key,
            )
        } finally {
            plaintext.fill(0)
            key.close()
        }
        val candidate = localObjectStore.writeCandidate("snapshot:53", frame)
        localObjectStore.commitSnapshot(candidate, null)
        candidate.file.delete()
        backupStateStore.replace(
            backupStateStore.value.copy(
                currentGeneration = 53,
                lastVerifiedSnapshotGeneration = 53,
                currentBaseObjectId = "snapshot:53",
                latestVerifiedSegmentGeneration = 53,
                snapshotCreatedAtEpochMillis = 1_700_000_000_000,
            ),
        )
    }
}

/**
 * Addresses one vault's content under every handle it issues.
 *
 * A caller that opens a content key owns and closes it, so a test that must
 * re-read what setup encrypted cannot reuse a single closed [VaultKey]. Every
 * handle therefore stands for the same established vault content, and the
 * Argon2id recovery derivation stays out of a unit test's time budget.
 */
internal class SingleContentVaultCrypto(private val delegate: VaultCrypto) : VaultCrypto {
    private val established: VaultKey = delegate.createKey()

    override fun createKey(): VaultKey = delegate.createKey()

    override fun wrapForRecovery(
        unlockedKey: VaultKey,
        passphrase: CharArray,
    ): VaultKeyEnvelope = error("Remote setup must not wrap a recovery envelope")

    override fun unlock(
        passphrase: CharArray,
        envelope: VaultKeyEnvelope,
    ): VaultKey = error("Remote setup must not unlock a recovery envelope")

    override fun changePassphrase(
        unlockedKey: VaultKey,
        newPassphrase: CharArray,
    ): VaultKeyEnvelope = error("Remote setup must not rotate a recovery passphrase")

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

internal class IssuedContentKeyStore(private val crypto: VaultCrypto) : VaultContentKeyStore {
    var openCount = 0
        private set

    override fun getOrCreate(vaultId: VaultId): VaultKey =
        error("Remote setup must not create a content key")

    override fun openExisting(vaultId: VaultId): VaultKey {
        openCount += 1
        return crypto.createKey()
    }

    override fun replace(vaultId: VaultId, key: VaultKey) = error("Unsupported")

    override fun delete(vaultId: VaultId) = error("Unsupported")
}

internal class InMemoryStage2BackupStateStore(vaultId: VaultId) : BackupStateStore {
    private val flow = MutableStateFlow(
        BackupStateEntity(
            vaultId = vaultId.value,
            currentGeneration = 53,
            lastVerifiedSnapshotGeneration = null,
            currentBaseObjectId = null,
            previousBaseObjectId = null,
            latestVerifiedSegmentGeneration = null,
            portablePackageGeneration = null,
            portablePackageBytes = null,
            portablePackageProducedAtEpochMillis = null,
            packageState = "IDLE",
            failureCategory = null,
            recoveryEnvelopeReady = true,
            legacyOutboxCoveredAtGeneration = null,
            snapshotCreatedAtEpochMillis = null,
        ),
    )

    val value: BackupStateEntity get() = flow.value

    fun replace(entity: BackupStateEntity) {
        flow.value = entity
    }

    override fun observe(vaultId: VaultId): Flow<BackupStateEntity> = flow

    override suspend fun get(vaultId: VaultId): BackupStateEntity? =
        flow.value.takeIf { it.vaultId == vaultId.value }

    override suspend fun mutate(
        vaultId: VaultId,
        mutation: BackupStateMutation,
    ): BackupStateEntity? {
        val updated = mutation.apply(flow.value) ?: return null
        flow.value = updated
        return updated
    }
}

internal class InMemoryRecoveryEnvelopeStore(
    private var envelope: VaultKeyEnvelope?,
) : RecoveryEnvelopeStore {
    /** Copies like the Room-backed store, so a caller may clear what it reads. */
    override suspend fun get(vaultId: VaultId): VaultKeyEnvelope? = envelope?.let { source ->
        source.copy(
            kdf = source.kdf.copy(salt = source.kdf.salt.copyOf()),
            nonce = source.nonce.copyOf(),
            wrappedKeyset = source.wrappedKeyset.copyOf(),
        )
    }

    override suspend fun upsert(vaultId: VaultId, envelope: VaultKeyEnvelope) {
        this.envelope = envelope
    }

    override suspend fun delete(vaultId: VaultId) {
        envelope = null
    }

    override suspend fun commitInitial(
        vaultId: VaultId,
        envelope: VaultKeyEnvelope,
        published: VerifiedPortableBackup,
    ): BackupStateEntity? = error("Unsupported")
}

internal class InMemoryRemoteBackupStateStore : RemoteBackupStateStore {
    var stored: RemoteBackupConfiguration? = null
        private set
    private val operations = mutableMapOf<String, RemoteBackupOperation>()
    val operationPhases = mutableListOf<String>()

    override suspend fun active(vaultId: VaultId): RemoteBackupConfiguration? =
        stored?.takeIf {
            it.vaultId == vaultId && it.lifecycle == RemoteBackupLifecycle.ACTIVE
        }

    override suspend fun known(lineageId: CloudLineageId): RemoteBackupConfiguration? =
        stored?.takeIf { it.lineageId == lineageId }

    override fun observeActive(vaultId: VaultId): Flow<RemoteBackupConfiguration?> =
        MutableStateFlow(stored)

    /** Models [RoomRemoteBackupStore.insertConnecting]'s insert-or-adopt rule. */
    override suspend fun insertConnecting(configuration: RemoteBackupConfiguration) {
        require(configuration.lifecycle == RemoteBackupLifecycle.CONNECTING) {
            "Initial remote backup configuration must start CONNECTING"
        }
        val current = stored
        if (current != null) {
            require(
                current.lineageId == configuration.lineageId &&
                    current.vaultId == configuration.vaultId &&
                    current.lifecycle == RemoteBackupLifecycle.CONNECTING &&
                    current.stateVersion == configuration.stateVersion,
            ) {
                "An unrelated remote backup configuration already exists"
            }
        }
        stored = configuration
    }

    /** Rewrites the stored row as if it belonged to a different vault. */
    fun reassignStoredVault(vaultId: VaultId) {
        stored = checkNotNull(stored).copy(vaultId = vaultId)
    }

    fun dropStoredConfiguration() {
        stored = null
    }

    override suspend fun compareAndSet(
        lineageId: CloudLineageId,
        expected: RemoteBackupStateVersion,
        next: RemoteBackupConfiguration,
    ): Boolean {
        val current = stored ?: return false
        if (current.lineageId != lineageId || current.stateVersion != expected) return false
        stored = next
        return true
    }

    override suspend fun operation(operationId: String): RemoteBackupOperation? =
        operations[operationId]

    override suspend fun putOperation(operation: RemoteBackupOperation) {
        operations[operation.operationId] = operation
        operationPhases += operation.phase
    }

    /**
     * Drops the next transition that would enter this phase, exactly once.
     *
     * That is the crash window the create-only protocol cares about: the
     * remote object is already durable, but the local phase recording it never
     * reached storage.
     */
    var failTransitionToPhase: String? = null

    override suspend fun transitionOperation(
        operationId: String,
        expectedPhase: String,
        next: RemoteBackupOperation,
    ): Boolean {
        val current = operations[operationId] ?: return false
        if (current.phase != expectedPhase) return false
        if (next.phase == failTransitionToPhase && next.phase != expectedPhase) {
            failTransitionToPhase = null
            return false
        }
        operations[operationId] = next
        operationPhases += next.phase
        return true
    }
}
