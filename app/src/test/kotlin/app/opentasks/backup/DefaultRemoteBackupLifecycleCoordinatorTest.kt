package app.opentasks.backup

import app.opentasks.backup.drive.AuthorizedDriveSession
import app.opentasks.backup.drive.DriveAuthorizationMode
import app.opentasks.backup.drive.DriveAuthorizationResult
import app.opentasks.backup.drive.GoogleDriveAuthorizationManager
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.backup.OwnershipChainStore
import app.opentasks.core.data.backup.OwnershipClaimCodec
import app.opentasks.core.data.backup.OwnershipClaimCreateResult
import app.opentasks.core.data.backup.OwnershipClaimV1
import app.opentasks.core.data.backup.OwnershipResolution
import app.opentasks.core.data.backup.PublicationCodec
import app.opentasks.core.data.backup.PublicationManifestV1
import app.opentasks.core.data.backup.RecoveryEnvelopeStore
import app.opentasks.core.data.backup.RemoteBackupStateStore
import app.opentasks.core.data.backup.RemoteBackupTransferStore
import app.opentasks.core.data.backup.RemoteInventoryItemV1
import app.opentasks.core.data.backup.VerifiedPortableBackup
import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.data.backup.drive.DriveChunkResult
import app.opentasks.core.data.backup.drive.DriveCreateRequest
import app.opentasks.core.data.backup.drive.DriveCreateResult
import app.opentasks.core.data.backup.drive.DriveDownloadReceipt
import app.opentasks.core.data.backup.drive.DriveFileMetadata
import app.opentasks.core.data.backup.drive.DriveListPage
import app.opentasks.core.data.backup.drive.DriveResumableSession
import app.opentasks.core.domain.BackupWorkScheduler
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.CreateSmallResult
import app.opentasks.core.domain.DeleteObjectResult
import app.opentasks.core.domain.ImmutableDownloadResult
import app.opentasks.core.domain.ImmutableUploadRequest
import app.opentasks.core.domain.ImmutableUploadResult
import app.opentasks.core.domain.LifecycleResult
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.ReadSmallResult
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupConfigurator
import app.opentasks.core.domain.RemoteBackupConnectResult
import app.opentasks.core.domain.RemoteBackupObject
import app.opentasks.core.domain.RemoteBackupOperation
import app.opentasks.core.domain.RemoteListPage
import app.opentasks.core.domain.RemoteListRequest
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OwnershipClaimId
import app.opentasks.core.model.OwnershipClaimRef
import app.opentasks.core.model.OwnershipStateV1
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationId
import app.opentasks.core.model.PublicationRef
import app.opentasks.core.model.PublicationSequence
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.RemoteObjectLifecycle
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WriterEpoch
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRemoteBackupLifecycleCoordinatorTest {
    @Test
    fun disconnectPersistsDormantAndCancelsBeforeRevocationWithoutDriveStorageCalls() =
        runBlocking {
            val events = mutableListOf<String>()
            val stateStore = LifecycleStateStore(configuration()) { events += "dormant" }
            val scheduler = RecordingScheduler { events += "cancel" }
            val transport = NoStorageDriveTransport()
            val authorization = FailingRevocationAuthorization(transport) { events += it }
            val coordinator = DefaultRemoteBackupLifecycleCoordinator(
                vaultId = VAULT_ID,
                crypto = TinkVaultCrypto(),
                recoveryEnvelopeStore = EmptyEnvelopeStore,
                remoteStateStore = stateStore,
                transferStore = EmptyTransferStore,
                scheduler = scheduler,
                authorizationManager = authorization,
                openObjectStore = { error("Disconnect must not open Drive storage") },
                ownershipStore = { error("Disconnect must not resolve ownership") },
                ownershipCodec = errorCodec(),
                publicationCodec = errorPublicationCodec(),
                configurator = UnusedConfigurator,
                now = { NOW },
            )

            val result = coordinator.disconnect()

            assertEquals(LifecycleResult.Disconnected(authorizationRevoked = false), result)
            assertEquals(RemoteBackupLifecycle.DORMANT, stateStore.configuration.lifecycle)
            assertEquals(listOf("dormant", "cancel", "authorize", "revoke"), events)
            assertEquals(0, transport.storageCalls)
            assertTrue(scheduler.cancelled)
        }

    @Test
    fun deletionCreatesAndKeepsTheExactTerminalSuccessorBeforeRemovingClaims() = runBlocking {
        val fixture = DeletionFixture()
        val passphrase = PASSPHRASE.copyOf()

        val result = fixture.coordinator.deleteHistory(passphrase)

        assertEquals(LifecycleResult.HistoryDeleted, result)
        assertEquals(RemoteBackupLifecycle.TERMINATED, fixture.state.configuration.lifecycle)
        assertEquals(TOMBSTONE_PROVIDER, fixture.chain.terminal.claim.providerFileId)
        assertEquals(2L, fixture.chain.terminal.claim.writerEpoch)
        assertEquals(OwnershipStateV1.TERMINATED, fixture.chain.terminal.claim.state)
        assertEquals(null, fixture.chain.terminal.claim.sourceVaultId)
        assertEquals(null, fixture.chain.terminal.claim.activeDeviceId)
        assertEquals(null, fixture.chain.terminal.claim.nextSuccessorProviderFileId)
        assertEquals(null, fixture.chain.terminal.claim.baselinePublicationProviderFileId)
        assertEquals(null, fixture.chain.terminal.claim.recoveryCredentialGeneration)
        assertEquals(listOf(OLD_CLAIM_PROVIDER, ROOT_PROVIDER), fixture.objects.deleted)
        assertTrue(TOMBSTONE_PROVIDER !in fixture.objects.deleted)
        assertTrue(passphrase.all { it == '\u0000' })
        assertEquals(
            listOf(
                "DELETE_INTENT_STORED",
                "TOMBSTONE_ID_STORED",
                "TOMBSTONE_CREATED",
                "TOMBSTONE_VERIFIED",
                "PAYLOAD_CLEANUP",
                "CLAIM_CLEANUP",
                "COMPLETED",
            ),
            fixture.state.operationPhases,
        )
        fixture.close()
    }

    @Test
    fun deletionResumesTheExactTerminalWinnerAfterItsCreatedCheckpointIsLost() = runBlocking {
        val fixture = DeletionFixture()
        fixture.state.failBeforePhase = "TOMBSTONE_CREATED"

        val interrupted = fixture.coordinator.deleteHistory(PASSPHRASE.copyOf())
        val resumed = fixture.coordinator.deleteHistory(PASSPHRASE.copyOf())

        assertTrue(interrupted is LifecycleResult.Failed)
        assertEquals(LifecycleResult.HistoryDeleted, resumed)
        assertEquals(1, fixture.chain.createCalls)
        assertEquals(RemoteBackupLifecycle.TERMINATED, fixture.state.configuration.lifecycle)
        fixture.close()
    }

    @Test
    fun deletionResumeAfterLocalTerminationNeverDeletesTheTerminalMarker() = runBlocking {
        val fixture = DeletionFixture()
        fixture.state.failBeforePhase = "COMPLETED"

        val interrupted = fixture.coordinator.deleteHistory(PASSPHRASE.copyOf())
        val resumed = fixture.coordinator.deleteHistory(PASSPHRASE.copyOf())

        assertTrue(interrupted is LifecycleResult.Failed)
        assertEquals(RemoteBackupLifecycle.TERMINATED, fixture.state.configuration.lifecycle)
        assertEquals(LifecycleResult.HistoryDeleted, resumed)
        assertTrue(TOMBSTONE_PROVIDER !in fixture.objects.deleted)
        fixture.close()
    }

    @Test
    fun deletionDeletesRootLastAndResumesFromTheExactTerminalWithoutIt() = runBlocking {
        val fixture = DeletionFixture()
        fixture.chain.requireRootForResolution = true
        fixture.state.failNextTerminationCas = true

        val interrupted = fixture.coordinator.deleteHistory(PASSPHRASE.copyOf())

        assertEquals(
            LifecycleResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE),
            interrupted,
        )
        assertEquals(ROOT_PROVIDER, fixture.objects.deleted.last())
        assertTrue(TOMBSTONE_PROVIDER !in fixture.objects.deleted)

        val resumed = fixture.coordinator.deleteHistory(PASSPHRASE.copyOf())

        assertEquals(LifecycleResult.HistoryDeleted, resumed)
        assertEquals(RemoteBackupLifecycle.TERMINATED, fixture.state.configuration.lifecycle)
        assertTrue(TOMBSTONE_PROVIDER !in fixture.objects.deleted)
        fixture.close()
    }

    @Test
    fun deletionRemovesAtMostThirtyTwoRecoverablePayloadsAndKeepsYoungResidue() = runBlocking {
        val recoverable = (1..33).map { remoteObject(it, verified = true) }
        val youngResidue = remoteObject(34, verified = false)
        val transfers = RecordingTransferStore(recoverable + youngResidue)
        val fixture = DeletionFixture(transfers)

        val first = fixture.coordinator.deleteHistory(PASSPHRASE.copyOf())

        assertEquals(
            LifecycleResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
            first,
        )
        assertEquals(
            32,
            fixture.objects.deleted.count { it.startsWith("payload-provider-") },
        )
        assertTrue(ROOT_PROVIDER !in fixture.objects.deleted)

        val resumed = fixture.coordinator.deleteHistory(PASSPHRASE.copyOf())

        assertEquals(LifecycleResult.HistoryDeleted, resumed)
        assertEquals(
            33,
            fixture.objects.deleted.count { it.startsWith("payload-provider-") },
        )
        assertTrue(youngResidue.providerObjectId.value !in fixture.objects.deleted)
        assertEquals(listOf(youngResidue), transfers.objectsForLineage(LINEAGE_ID))
        fixture.close()
    }

    @Test
    fun deletionSharesOneBatchBudgetAndReauthenticatesBeforeEachDeletionKind() = runBlocking {
        val fixture = DeletionFixture(
            RecordingTransferStore((1..31).map { remoteObject(it, verified = true) }),
        )

        val result = fixture.coordinator.deleteHistory(PASSPHRASE.copyOf())

        assertEquals(
            LifecycleResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
            result,
        )
        assertEquals(32, fixture.objects.deleted.size)
        assertTrue(
            fixture.objects.readSmallRequests.count { it == TOMBSTONE_PROVIDER } >= 2,
        )
        fixture.close()
    }

    @Test
    fun deletionEnumeratesAndRemovesAuthenticatedRemoteOnlyPayloadHistory() = runBlocking {
        val fixture = DeletionFixture()
        fixture.seedRemoteOnlyPayloadHistory()

        val result = fixture.coordinator.deleteHistory(PASSPHRASE.copyOf())

        assertEquals(LifecycleResult.HistoryDeleted, result)
        assertTrue(REMOTE_PUBLICATION_PROVIDER in fixture.objects.deleted)
        assertTrue(REMOTE_BASE_A_PROVIDER in fixture.objects.deleted)
        assertTrue(REMOTE_BASE_B_PROVIDER in fixture.objects.deleted)
        assertTrue(REMOTE_SEGMENT_PROVIDER in fixture.objects.deleted)
        assertTrue(
            fixture.objects.listRequests.containsAll(
                listOf(
                    RemoteObjectRoleV1.PUBLICATION,
                    RemoteObjectRoleV1.SNAPSHOT,
                    RemoteObjectRoleV1.SEGMENT,
                ),
            ),
        )
        fixture.close()
    }

    @Test
    fun deletionRetainsAuthenticatedPublicationFactsAcrossPayloadBatches() = runBlocking {
        val fixture = DeletionFixture(
            RecordingTransferStore((1..29).map { remoteObject(it, verified = true) }),
        )
        fixture.seedRemoteOnlyPayloadHistory()

        val first = fixture.coordinator.deleteHistory(PASSPHRASE.copyOf())

        assertEquals(
            LifecycleResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
            first,
        )
        assertTrue(REMOTE_PUBLICATION_PROVIDER in fixture.objects.deleted)

        val resumed = fixture.coordinator.deleteHistory(PASSPHRASE.copyOf())

        assertEquals(LifecycleResult.HistoryDeleted, resumed)
        fixture.close()
    }

    @Test
    fun divergentWorkStartsASeparateLineageOnlyFromLocallyKnownOwnershipLoss() = runBlocking {
        val lost = configuration().copy(
            lifecycle = RemoteBackupLifecycle.OWNERSHIP_LOST,
            failureCategory = RemoteBackupFailureCategory.OWNERSHIP_LOST,
        )
        val stateStore = LifecycleStateStore(lost)
        val configurator = RecordingConfigurator()
        val objectStore = RecordingObjectStore()
        val coordinator = DefaultRemoteBackupLifecycleCoordinator(
            vaultId = VAULT_ID,
            crypto = TinkVaultCrypto(),
            recoveryEnvelopeStore = EmptyEnvelopeStore,
            remoteStateStore = stateStore,
            transferStore = EmptyTransferStore,
            scheduler = RecordingScheduler {},
            authorizationManager = SuccessfulAuthorization(NoStorageDriveTransport()),
            openObjectStore = { objectStore },
            ownershipStore = { error("Divergent preservation must not resolve the lost lineage") },
            ownershipCodec = errorCodec(),
            publicationCodec = errorPublicationCodec(),
            configurator = configurator,
            now = { NOW },
        )

        val result = coordinator.preserveDivergentWorkAsNewLineage()

        assertEquals(
            RemoteBackupConnectResult.Connected(NEW_LINEAGE_ID, BackupGeneration(4)),
            result,
        )
        assertTrue(configurator.allowSeparateLineage)
        assertTrue(configurator.objectStore === objectStore)
        assertEquals(RemoteBackupLifecycle.OWNERSHIP_LOST, stateStore.configuration.lifecycle)
        assertEquals(VAULT_ID, stateStore.configuration.vaultId)
    }

    private inner class DeletionFixture(
        transferStore: RemoteBackupTransferStore = EmptyTransferStore,
    ) {
        val crypto = TinkVaultCrypto()
        private val key = crypto.createKey()
        private val envelope = crypto.wrapForRecovery(key, PASSPHRASE.copyOf())
        val codec = OwnershipClaimCodec(DefaultAuthenticatedCloudObjectCodec(crypto))
        private val publicationCodec = PublicationCodec(DefaultAuthenticatedCloudObjectCodec(crypto))
        private val root = verifiedRoot(codec, key)
        val state = LifecycleStateStore(deletionConfiguration(root))
        private val envelopes = HoldingEnvelopeStore(envelope)
        val objects = RecordingObjectStore()
        val chain = TerminalChainStore(root, codec, key, objects)
        private val authorization = SuccessfulAuthorization(NoStorageDriveTransport())
        val coordinator = DefaultRemoteBackupLifecycleCoordinator(
            vaultId = VAULT_ID,
            crypto = crypto,
            recoveryEnvelopeStore = envelopes,
            remoteStateStore = state,
            transferStore = transferStore,
            scheduler = RecordingScheduler {},
            authorizationManager = authorization,
            openObjectStore = { objects },
            ownershipStore = { chain },
            ownershipCodec = codec,
            publicationCodec = publicationCodec,
            configurator = UnusedConfigurator,
            now = { NOW },
            newClaimId = { OwnershipClaimId.parse(TOMBSTONE_ID) },
        )

        fun seedRemoteOnlyPayloadHistory() {
            val inventory = listOf(
                remoteInventory(
                    logicalId = "base-a",
                    providerId = REMOTE_BASE_A_PROVIDER,
                    role = RemoteObjectRoleV1.SNAPSHOT,
                    firstGeneration = 4,
                    lastGeneration = 4,
                ),
                remoteInventory(
                    logicalId = "base-b",
                    providerId = REMOTE_BASE_B_PROVIDER,
                    role = RemoteObjectRoleV1.SNAPSHOT,
                    firstGeneration = 4,
                    lastGeneration = 4,
                ),
                remoteInventory(
                    logicalId = "segment-5",
                    providerId = REMOTE_SEGMENT_PROVIDER,
                    role = RemoteObjectRoleV1.SEGMENT,
                    firstGeneration = 5,
                    lastGeneration = 5,
                ),
            )
            val draft = PublicationManifestV1(
                bootstrapSha256 = ZERO_SHA256,
                lineageId = LINEAGE_ID.value,
                sourceVaultId = VAULT_ID.value,
                writerEpoch = 1,
                activeDeviceId = DEVICE_ID,
                publicationProviderFileId = REMOTE_PUBLICATION_PROVIDER,
                publicationId = REMOTE_PUBLICATION_ID,
                publicationSequence = 1,
                predecessorPublicationProviderFileId = "remote-publication-provider-0",
                predecessorPublicationId = "00000000-0000-4000-8000-000000000010",
                predecessorPublicationSha256 = DIGEST,
                baseline = false,
                plannedClaimProviderFileId = null,
                plannedClaimId = null,
                predecessorClaimProviderFileId = null,
                predecessorClaimId = null,
                predecessorClaimSha256 = null,
                ownershipClaimProviderFileId = ROOT_PROVIDER,
                ownershipClaimId = ROOT_CLAIM_ID,
                ownershipClaimSha256 = root.completeSha256.value,
                localGeneration = 5,
                publicationOperationId = "remote-publication-operation",
                currentBaseObjectId = "base-a",
                fallbackBaseObjectId = "base-b",
                inventory = inventory,
                recoveryCredentialGeneration = 1,
            )
            val manifest = draft.copy(
                bootstrapSha256 = publicationCodec.bootstrapSha256(draft, envelope),
            )
            val encoded = publicationCodec.encode(manifest, envelope, key)
            val verified = publicationCodec.verify(encoded, key)
            objects.smallFiles[REMOTE_PUBLICATION_PROVIDER] = encoded.copyOf()
            encoded.fill(0)
            objects.listedByRole[RemoteObjectRoleV1.PUBLICATION] =
                listOf(remoteListed(REMOTE_PUBLICATION_PROVIDER, RemoteObjectRoleV1.PUBLICATION))
            objects.listedByRole[RemoteObjectRoleV1.SNAPSHOT] = listOf(
                remoteListed(REMOTE_BASE_A_PROVIDER, RemoteObjectRoleV1.SNAPSHOT),
                remoteListed(REMOTE_BASE_B_PROVIDER, RemoteObjectRoleV1.SNAPSHOT),
            )
            objects.listedByRole[RemoteObjectRoleV1.SEGMENT] =
                listOf(remoteListed(REMOTE_SEGMENT_PROVIDER, RemoteObjectRoleV1.SEGMENT))
            state.configuration = state.configuration.copy(
                currentPublication = PublicationRef(
                    providerId = ProviderObjectId.of(REMOTE_PUBLICATION_PROVIDER),
                    logicalId = PublicationId.parse(REMOTE_PUBLICATION_ID),
                    sha256 = verified.completeSha256,
                    sequence = PublicationSequence(1),
                    generation = BackupGeneration(5),
                ),
                lastVerifiedGeneration = BackupGeneration(5),
            )
        }

        fun close() {
            objects.clear()
            envelope.clearOwned()
            envelopes.clear()
            key.close()
        }
    }

    private class LifecycleStateStore(
        var configuration: RemoteBackupConfiguration,
        private val onDormant: () -> Unit = {},
    ) : RemoteBackupStateStore {
        private var operation: RemoteBackupOperation? = null
        val operationPhases = mutableListOf<String>()
        var failBeforePhase: String? = null
        var failNextTerminationCas = false
        override suspend fun active(vaultId: VaultId) =
            configuration.takeIf { it.lifecycle == RemoteBackupLifecycle.ACTIVE }
        override suspend fun configurations(vaultId: VaultId) = listOf(configuration)
        override suspend fun known(lineageId: CloudLineageId) = configuration
        override fun observeActive(vaultId: VaultId): Flow<RemoteBackupConfiguration?> =
            MutableStateFlow(configuration)
        override suspend fun insertConnecting(configuration: RemoteBackupConfiguration) = Unit
        override suspend fun compareAndSet(
            lineageId: CloudLineageId,
            expected: RemoteBackupStateVersion,
            next: RemoteBackupConfiguration,
        ): Boolean {
            if (configuration.stateVersion != expected) return false
            if (next.lifecycle == RemoteBackupLifecycle.TERMINATED && failNextTerminationCas) {
                failNextTerminationCas = false
                return false
            }
            configuration = next
            if (next.lifecycle == RemoteBackupLifecycle.DORMANT) onDormant()
            return true
        }
        override suspend fun operation(operationId: String): RemoteBackupOperation? = operation
        override suspend fun putOperation(operation: RemoteBackupOperation) {
            this.operation = operation
            operationPhases += operation.phase
        }
        override suspend fun transitionOperation(
            operationId: String,
            expectedPhase: String,
            next: RemoteBackupOperation,
        ): Boolean {
            if (operation?.phase != expectedPhase) return false
            if (next.phase == failBeforePhase) {
                failBeforePhase = null
                return false
            }
            val phaseChanged = operation?.phase != next.phase
            operation = next
            if (phaseChanged) operationPhases += next.phase
            return true
        }
    }

    private inner class HoldingEnvelopeStore(initial: VaultKeyEnvelope) : RecoveryEnvelopeStore {
        private var envelope = initial.copyOwned()
        override suspend fun get(vaultId: VaultId) = envelope.copyOwned()
        override suspend fun upsert(vaultId: VaultId, envelope: VaultKeyEnvelope) = Unit
        override suspend fun delete(vaultId: VaultId) = Unit
        override suspend fun commitInitial(
            vaultId: VaultId,
            envelope: VaultKeyEnvelope,
            published: VerifiedPortableBackup,
        ) = null
        fun clear() = envelope.clearOwned()
    }

    private class TerminalChainStore(
        private val root: app.opentasks.core.data.backup.VerifiedOwnershipClaim,
        private val codec: OwnershipClaimCodec,
        private val key: app.opentasks.core.crypto.VaultKey,
        private val objects: RecordingObjectStore,
    ) : OwnershipChainStore {
        lateinit var terminal: app.opentasks.core.data.backup.VerifiedOwnershipClaim
        var createCalls = 0
        var resolveCalls = 0
        var requireRootForResolution = false
        override suspend fun discoverPublicRoots() = error("not used")
        override suspend fun resolve(
            rootProviderId: ProviderObjectId,
            contentKey: app.opentasks.core.crypto.VaultKey,
        ): OwnershipResolution {
            resolveCalls += 1
            if (requireRootForResolution && ROOT_PROVIDER in objects.deleted) {
                return OwnershipResolution.Blocked(
                    RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
                )
            }
            return if (::terminal.isInitialized) {
                OwnershipResolution.Terminated(terminal)
            } else {
                OwnershipResolution.Active(root, root)
            }
        }
        override suspend fun createClaim(
            expectedPredecessor: app.opentasks.core.data.backup.VerifiedOwnershipClaim?,
            encodedClaim: OwnedRemoteBytes,
            contentKey: app.opentasks.core.crypto.VaultKey,
        ): OwnershipClaimCreateResult {
            createCalls += 1
            val bytes = encodedClaim.take()
            return try {
                terminal = codec.verifySuccessor(root, bytes, key)
                objects.smallFiles[terminal.claim.providerFileId] = bytes.copyOf()
                OwnershipClaimCreateResult.Won(terminal)
            } finally {
                bytes.fill(0)
                encodedClaim.close()
            }
        }
    }

    private class RecordingObjectStore : CreateOnlyBackupObjectStore {
        val deleted = mutableListOf<String>()
        val listRequests = mutableListOf<RemoteObjectRoleV1>()
        val readSmallRequests = mutableListOf<String>()
        val listedByRole = mutableMapOf<RemoteObjectRoleV1, List<app.opentasks.core.domain.RemoteListedObject>>()
        val smallFiles = mutableMapOf<String, ByteArray>()
        override suspend fun generateProviderIds(count: Int, role: RemoteObjectRoleV1) =
            error("The terminal must use its predecessor's exact successor slot")
        override suspend fun createSmallIfAbsent(
            providerObjectId: ProviderObjectId,
            lineageId: CloudLineageId,
            metadata: app.opentasks.core.domain.RemoteListedObject,
            bytes: OwnedRemoteBytes,
        ) = CreateSmallResult.Created
        override suspend fun readSmall(
            providerObjectId: ProviderObjectId,
            maximumBytes: Long,
        ): ReadSmallResult {
            readSmallRequests += providerObjectId.value
            return smallFiles[providerObjectId.value]
                ?.let { ReadSmallResult.Found(OwnedBytes(it)) }
                ?: ReadSmallResult.Missing
        }
        override suspend fun list(request: RemoteListRequest): RemoteListPage {
            listRequests += request.role
            return RemoteListPage(
            objects = listedByRole[request.role] ?: if (
                request.role == RemoteObjectRoleV1.OWNERSHIP_CLAIM &&
                OLD_CLAIM_PROVIDER !in deleted
            ) {
                listOf(
                    app.opentasks.core.domain.RemoteListedObject(
                        providerObjectId = ProviderObjectId.of(OLD_CLAIM_PROVIDER),
                        logicalObjectId = OLD_CLAIM_ID,
                        role = RemoteObjectRoleV1.OWNERSHIP_CLAIM,
                        writerEpoch = WriterEpoch(1),
                        ownerDeviceId = CloudDeviceId.parse(DEVICE_ID),
                    ),
                )
            } else {
                emptyList()
            },
            nextPageToken = null,
        )
        }
        override suspend fun uploadImmutable(request: ImmutableUploadRequest) =
            ImmutableUploadResult.UploadedAndVerified
        override suspend fun downloadImmutable(
            providerObjectId: ProviderObjectId,
            maximumBytes: Long,
            expectedSha256: Sha256Digest,
        ) = ImmutableDownloadResult.Missing
        override suspend fun delete(providerObjectId: ProviderObjectId): DeleteObjectResult {
            deleted += providerObjectId.value
            smallFiles.remove(providerObjectId.value)?.fill(0)
            listedByRole.replaceAll { _, values ->
                values.filterNot { it.providerObjectId == providerObjectId }
            }
            return DeleteObjectResult.Deleted
        }

        fun clear() {
            smallFiles.values.forEach { it.fill(0) }
            smallFiles.clear()
        }
    }

    private class OwnedBytes(source: ByteArray) : OwnedRemoteBytes {
        private var bytes: ByteArray? = source.copyOf()
        override val size: Int get() = checkNotNull(bytes).size
        override fun take(): ByteArray = checkNotNull(bytes).also { bytes = null }
        override fun close() {
            bytes?.fill(0)
            bytes = null
        }
    }

    private class SuccessfulAuthorization(
        private val transport: CreateOnlyDriveTransport,
    ) : GoogleDriveAuthorizationManager {
        override suspend fun authorize(mode: DriveAuthorizationMode, expectedAccountDigest: ByteArray?) =
            DriveAuthorizationResult.Authorized(
                AuthorizedDriveSession(transport, ByteArray(32), "token", null),
            )
        override suspend fun acceptResolution(
            data: android.content.Intent,
            expectedAccountDigest: ByteArray?,
        ) = error("not used")
        override suspend fun clearToken(session: AuthorizedDriveSession) = session.close()
        override suspend fun revokeAccess(session: AuthorizedDriveSession) = session.close()
    }

    private class RecordingScheduler(private val onCancel: () -> Unit) : BackupWorkScheduler {
        var cancelled = false
        override fun onPendingGeneration() = Unit
        override fun ensurePeriodic() = Unit
        override fun cancelAll() {
            cancelled = true
            onCancel()
        }
    }

    private class FailingRevocationAuthorization(
        private val transport: CreateOnlyDriveTransport,
        private val event: (String) -> Unit,
    ) : GoogleDriveAuthorizationManager {
        override suspend fun authorize(
            mode: DriveAuthorizationMode,
            expectedAccountDigest: ByteArray?,
        ): DriveAuthorizationResult {
            event("authorize")
            return DriveAuthorizationResult.Authorized(
                AuthorizedDriveSession(transport, ByteArray(32), "token", null),
            )
        }
        override suspend fun acceptResolution(
            data: android.content.Intent,
            expectedAccountDigest: ByteArray?,
        ) = error("not used")
        override suspend fun clearToken(session: AuthorizedDriveSession) = error("not used")
        override suspend fun revokeAccess(session: AuthorizedDriveSession) {
            event("revoke")
            session.close()
            throw IllegalStateException("bounded revocation failure")
        }
    }

    private class NoStorageDriveTransport : CreateOnlyDriveTransport {
        var storageCalls = 0
        override suspend fun readCurrentUserPermissionId() = "unused"
        override suspend fun generateAppDataFileIds(count: Int): List<String> {
            storageCalls += 1
            return emptyList()
        }
        override suspend fun listAppDataFiles(query: String, pageToken: String?, pageSize: Int): DriveListPage {
            storageCalls += 1
            error("unexpected")
        }
        override suspend fun createFileIfAbsent(request: DriveCreateRequest): DriveCreateResult {
            storageCalls += 1
            error("unexpected")
        }
        override suspend fun downloadFile(providerFileId: String, destination: File, maximumBytes: Long): DriveDownloadReceipt {
            storageCalls += 1
            error("unexpected")
        }
        override suspend fun startResumableCreate(metadata: DriveFileMetadata, totalBytes: Long): DriveResumableSession {
            storageCalls += 1
            error("unexpected")
        }
        override suspend fun queryResumableUpload(sessionUri: String, totalBytes: Long): DriveChunkResult {
            storageCalls += 1
            error("unexpected")
        }
        override suspend fun uploadChunk(sessionUri: String, firstByte: Long, totalBytes: Long, content: ByteArray): DriveChunkResult {
            storageCalls += 1
            error("unexpected")
        }
        override suspend fun deleteFile(providerFileId: String): Boolean {
            storageCalls += 1
            error("unexpected")
        }
        override fun close() = Unit
    }

    private object EmptyEnvelopeStore : RecoveryEnvelopeStore {
        override suspend fun get(vaultId: VaultId) = null
        override suspend fun upsert(vaultId: VaultId, envelope: app.opentasks.core.crypto.VaultKeyEnvelope) = Unit
        override suspend fun delete(vaultId: VaultId) = Unit
        override suspend fun commitInitial(
            vaultId: VaultId,
            envelope: app.opentasks.core.crypto.VaultKeyEnvelope,
            published: VerifiedPortableBackup,
        ) = null
    }

    private object EmptyTransferStore : RemoteBackupTransferStore {
        override suspend fun objectState(lineageId: CloudLineageId, logicalObjectId: RemoteLogicalObjectId) = null
        override suspend fun insertObject(value: RemoteBackupObject) = Unit
        override suspend fun compareAndSetObject(expected: RemoteBackupObject, next: RemoteBackupObject) = false
        override suspend fun objectsForLineage(lineageId: CloudLineageId) = emptyList<RemoteBackupObject>()
        override suspend fun removeObjectState(lineageId: CloudLineageId, logicalObjectId: RemoteLogicalObjectId) = false
    }

    private class RecordingTransferStore(initial: List<RemoteBackupObject>) :
        RemoteBackupTransferStore {
        private val objects = initial.associateBy { it.logicalObjectId }.toMutableMap()
        override suspend fun objectState(
            lineageId: CloudLineageId,
            logicalObjectId: RemoteLogicalObjectId,
        ) = objects[logicalObjectId]
        override suspend fun insertObject(value: RemoteBackupObject) {
            objects[value.logicalObjectId] = value
        }
        override suspend fun compareAndSetObject(
            expected: RemoteBackupObject,
            next: RemoteBackupObject,
        ): Boolean = if (objects[expected.logicalObjectId] == expected) {
            objects[expected.logicalObjectId] = next
            true
        } else {
            false
        }
        override suspend fun objectsForLineage(lineageId: CloudLineageId) =
            objects.values.filter { it.lineageId == lineageId }
        override suspend fun removeObjectState(
            lineageId: CloudLineageId,
            logicalObjectId: RemoteLogicalObjectId,
        ) = objects.remove(logicalObjectId) != null
    }

    private object UnusedConfigurator : RemoteBackupConfigurator {
        override suspend fun connect(
            objectStore: app.opentasks.core.domain.CreateOnlyBackupObjectStore,
            accountBindingDigest: ByteArray,
            allowSeparateLineage: Boolean,
        ) = error("not used")
    }

    private class RecordingConfigurator : RemoteBackupConfigurator {
        lateinit var objectStore: CreateOnlyBackupObjectStore
        var allowSeparateLineage = false
        override suspend fun connect(
            objectStore: CreateOnlyBackupObjectStore,
            accountBindingDigest: ByteArray,
            allowSeparateLineage: Boolean,
        ): RemoteBackupConnectResult {
            this.objectStore = objectStore
            this.allowSeparateLineage = allowSeparateLineage
            return RemoteBackupConnectResult.Connected(NEW_LINEAGE_ID, BackupGeneration(4))
        }
    }

    private fun configuration() = RemoteBackupConfiguration(
        lineageId = LINEAGE_ID,
        vaultId = VAULT_ID,
        rootClaimProviderId = ProviderObjectId.of("root-provider"),
        accountBindingDigest = ByteArray(32),
        lifecycle = RemoteBackupLifecycle.ACTIVE,
        activeDeviceId = null,
        writerEpoch = null,
        ownershipClaim = null,
        nextSuccessorProviderId = null,
        currentPublication = null,
        previousPublication = null,
        lastVerifiedGeneration = BackupGeneration(4),
        lastVerifiedAt = NOW,
        recoveryCredentialGeneration = 1,
        failureCategory = null,
        stateVersion = RemoteBackupStateVersion(2),
    )

    private fun remoteObject(index: Int, verified: Boolean) = RemoteBackupObject(
        lineageId = LINEAGE_ID,
        logicalObjectId = RemoteLogicalObjectId.of("payload-$index"),
        providerObjectId = ProviderObjectId.of("payload-provider-$index"),
        role = RemoteObjectRoleV1.SNAPSHOT,
        writerEpoch = WriterEpoch(1),
        ownerDeviceId = CloudDeviceId.parse(DEVICE_ID),
        operationId = "payload-operation-$index",
        firstGeneration = BackupGeneration(4),
        lastGeneration = BackupGeneration(4),
        frameLength = 1,
        frameSha256 = Sha256Digest.of(DIGEST),
        lifecycle = if (verified) RemoteObjectLifecycle.VERIFIED else RemoteObjectLifecycle.PLANNED,
        resumableSessionUri = null,
        uploadedBytes = if (verified) 1 else 0,
        createdAt = NOW,
        verifiedAt = NOW.takeIf { verified },
    )

    private fun remoteInventory(
        logicalId: String,
        providerId: String,
        role: RemoteObjectRoleV1,
        firstGeneration: Long,
        lastGeneration: Long,
    ) = RemoteInventoryItemV1(
        logicalObjectId = logicalId,
        providerFileId = providerId,
        role = role,
        firstGeneration = firstGeneration,
        lastGeneration = lastGeneration,
        frameLength = 1,
        frameSha256 = DIGEST,
    )

    private fun remoteListed(providerId: String, role: RemoteObjectRoleV1) =
        app.opentasks.core.domain.RemoteListedObject(
            providerObjectId = ProviderObjectId.of(providerId),
            logicalObjectId = null,
            role = role,
            writerEpoch = WriterEpoch(1),
            ownerDeviceId = CloudDeviceId.parse(DEVICE_ID),
        )

    private fun errorCodec(): app.opentasks.core.data.backup.OwnershipClaimCodec {
        val crypto = TinkVaultCrypto()
        return app.opentasks.core.data.backup.OwnershipClaimCodec(
            app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec(crypto),
        )
    }

    private fun errorPublicationCodec(): PublicationCodec {
        val crypto = TinkVaultCrypto()
        return PublicationCodec(DefaultAuthenticatedCloudObjectCodec(crypto))
    }

    private fun verifiedRoot(
        codec: OwnershipClaimCodec,
        key: app.opentasks.core.crypto.VaultKey,
    ): app.opentasks.core.data.backup.VerifiedOwnershipClaim {
        val claim = OwnershipClaimV1(
            lineageId = LINEAGE_ID.value,
            writerEpoch = 1,
            state = OwnershipStateV1.ACTIVE,
            predecessorProviderFileId = null,
            predecessorClaimId = null,
            predecessorClaimSha256 = null,
            providerFileId = ROOT_PROVIDER,
            claimId = ROOT_CLAIM_ID,
            predecessorReservedSuccessorProviderFileId = null,
            sourceVaultId = VAULT_ID.value,
            activeDeviceId = DEVICE_ID,
            nextSuccessorProviderFileId = TOMBSTONE_PROVIDER,
            baselinePublicationProviderFileId = "baseline-provider",
            baselinePublicationId = "00000000-0000-4000-8000-000000000010",
            baselinePublicationSha256 = DIGEST,
            recoveryCredentialGeneration = 1,
            creationOperationId = "root-operation",
            tombstoneId = null,
        )
        val encoded = codec.encode(claim, key)
        return try {
            codec.verify(encoded, key)
        } finally {
            encoded.fill(0)
        }
    }

    private fun deletionConfiguration(
        root: app.opentasks.core.data.backup.VerifiedOwnershipClaim,
    ) = RemoteBackupConfiguration(
        lineageId = LINEAGE_ID,
        vaultId = VAULT_ID,
        rootClaimProviderId = ProviderObjectId.of(ROOT_PROVIDER),
        accountBindingDigest = ByteArray(32),
        lifecycle = RemoteBackupLifecycle.ACTIVE,
        activeDeviceId = CloudDeviceId.parse(DEVICE_ID),
        writerEpoch = WriterEpoch(1),
        ownershipClaim = OwnershipClaimRef(
            providerId = ProviderObjectId.of(ROOT_PROVIDER),
            logicalId = OwnershipClaimId.parse(ROOT_CLAIM_ID),
            sha256 = root.completeSha256,
            writerEpoch = WriterEpoch(1),
        ),
        nextSuccessorProviderId = ProviderObjectId.of(TOMBSTONE_PROVIDER),
        currentPublication = null,
        previousPublication = null,
        lastVerifiedGeneration = BackupGeneration(4),
        lastVerifiedAt = NOW,
        recoveryCredentialGeneration = 1,
        failureCategory = null,
        stateVersion = RemoteBackupStateVersion(2),
    )

    private fun VaultKeyEnvelope.copyOwned() = VaultKeyEnvelope(
        formatVersion,
        kdf.copy(salt = kdf.salt.copyOf()),
        nonce.copyOf(),
        wrappedKeyset.copyOf(),
    )

    private fun VaultKeyEnvelope.clearOwned() {
        kdf.salt.fill(0)
        nonce.fill(0)
        wrappedKeyset.fill(0)
    }

    private companion object {
        val VAULT_ID = VaultId("vault-alpha")
        val LINEAGE_ID = CloudLineageId.parse("00000000-0000-4000-8000-000000000001")
        val NEW_LINEAGE_ID = CloudLineageId.parse("00000000-0000-4000-8000-000000000006")
        val NOW = Instant.ofEpochMilli(1_800_000_000_000)
        val PASSPHRASE = "correct passphrase".toCharArray()
        const val ROOT_PROVIDER = "root-provider"
        const val ROOT_CLAIM_ID = "00000000-0000-4000-8000-000000000002"
        const val OLD_CLAIM_PROVIDER = "old-claim-provider"
        const val OLD_CLAIM_ID = "00000000-0000-4000-8000-000000000005"
        const val TOMBSTONE_PROVIDER = "tombstone-provider"
        const val TOMBSTONE_ID = "00000000-0000-4000-8000-000000000003"
        const val REMOTE_PUBLICATION_PROVIDER = "remote-publication-provider-1"
        const val REMOTE_PUBLICATION_ID = "00000000-0000-4000-8000-000000000011"
        const val REMOTE_BASE_A_PROVIDER = "remote-base-a-provider"
        const val REMOTE_BASE_B_PROVIDER = "remote-base-b-provider"
        const val REMOTE_SEGMENT_PROVIDER = "remote-segment-provider"
        const val DEVICE_ID = "00000000-0000-4000-8000-000000000004"
        const val ZERO_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"
        const val DIGEST =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
