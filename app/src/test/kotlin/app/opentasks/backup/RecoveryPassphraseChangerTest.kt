package app.opentasks.backup

import app.opentasks.backup.drive.AuthorizedDriveSession
import app.opentasks.backup.drive.DriveAuthorizationMode
import app.opentasks.backup.drive.DriveAuthorizationResult
import app.opentasks.backup.drive.GoogleDriveAuthorizationManager
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.backup.OwnershipClaimV1
import app.opentasks.core.data.backup.OwnershipResolution
import app.opentasks.core.data.backup.OwnershipRootDiscovery
import app.opentasks.core.data.backup.OwnershipChainStore
import app.opentasks.core.data.backup.OwnershipClaimCreateResult
import app.opentasks.core.data.backup.OwnershipClaimCodec
import app.opentasks.core.data.backup.PublicationBootstrapV1
import app.opentasks.core.data.backup.PublicationCandidate
import app.opentasks.core.data.backup.PublicationCandidateDiscovery
import app.opentasks.core.data.backup.PublicationCatalog
import app.opentasks.core.data.backup.PublicationCodec
import app.opentasks.core.data.backup.PublicationCreateResult
import app.opentasks.core.data.backup.PublicationManifestV1
import app.opentasks.core.data.backup.PublicationResolution
import app.opentasks.core.data.backup.RecoveryEnvelopeCodec
import app.opentasks.core.data.backup.RecoveryEnvelopeStore
import app.opentasks.core.data.backup.RemoteBackupStateStore
import app.opentasks.core.data.backup.RemoteBackupTransferStore
import app.opentasks.core.data.backup.RemoteInventoryItemV1
import app.opentasks.core.data.backup.VerifiedOwnershipClaim
import app.opentasks.core.data.backup.VerifiedPortableBackup
import app.opentasks.core.data.backup.VerifiedPublication
import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.CreateSmallResult
import app.opentasks.core.domain.DeleteObjectResult
import app.opentasks.core.domain.ImmutableDownloadResult
import app.opentasks.core.domain.ImmutableUploadRequest
import app.opentasks.core.domain.ImmutableUploadResult
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.PassphraseChangeResult
import app.opentasks.core.domain.PassphraseChangeFailureCategory
import app.opentasks.core.domain.ReadSmallResult
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupObject
import app.opentasks.core.domain.RemoteBackupOperation
import app.opentasks.core.domain.RemoteListPage
import app.opentasks.core.domain.RemoteListRequest
import app.opentasks.core.model.AndroidBackupStatus
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.BackupPackageInfo
import app.opentasks.core.model.BackupUnavailableReason
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
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WriterEpoch
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPassphraseChangerTest {
    @Test
    fun invalidCurrentPassphrasePublishesNothingAndClearsBothInputs() = runBlocking {
        val fixture = RotationFixture()
        val current = "incorrect passphrase".toCharArray()
        val replacement = NEW.copyOf()

        val result = fixture.changer.change(current, replacement)

        assertEquals(
            PassphraseChangeResult.Failed(
                PassphraseChangeFailureCategory.CURRENT_PASSPHRASE_INVALID,
            ),
            result,
        )
        assertEquals(0, fixture.portablePublications)
        assertEquals(0, fixture.catalog.createCalls)
        assertTrue(current.all { it == '\u0000' })
        assertTrue(replacement.all { it == '\u0000' })
        fixture.unlockStored(OLD)
        fixture.close()
    }

    @Test
    fun rotationAdvancesSequenceWithoutAdvancingGenerationOrEpoch() = runBlocking {
        val fixture = RotationFixture()
        val current = OLD.copyOf()
        val replacement = NEW.copyOf()

        val result = fixture.changer.change(current, replacement)

        assertTrue(result.toString(), result is PassphraseChangeResult.Changed)
        assertEquals(8L, fixture.catalog.published.manifest.publicationSequence)
        assertEquals(42L, fixture.catalog.published.manifest.localGeneration)
        assertEquals(3L, fixture.catalog.published.manifest.writerEpoch)
        assertEquals(2L, fixture.catalog.published.manifest.recoveryCredentialGeneration)
        assertTrue(current.all { it == '\u0000' })
        assertTrue(replacement.all { it == '\u0000' })
        assertEquals(1, fixture.portablePublications)
        assertEquals(2L, fixture.store.configuration.recoveryCredentialGeneration)
        fixture.unlockStored(NEW)
        fixture.close()
    }

    @Test
    fun rotationAdoptsItsExactPublicationAfterTheCreatedCheckpointIsLost() = runBlocking {
        val fixture = RotationFixture()
        fixture.store.failBeforePhase = "REMOTE_PUBLICATION_CREATED"

        val interrupted = fixture.changer.change(OLD.copyOf(), NEW.copyOf())
        fixture.unlockStored(OLD)
        val resumed = fixture.changer.change(OLD.copyOf(), NEW.copyOf())

        assertTrue(interrupted is PassphraseChangeResult.Failed)
        assertTrue(resumed is PassphraseChangeResult.Changed)
        assertEquals(1, fixture.catalog.createCalls)
        fixture.unlockStored(NEW)
        fixture.close()
    }

    @Test
    fun rotationFinishesThePriorOperationAfterAtomicLocalPromotion() = runBlocking {
        val fixture = RotationFixture()
        fixture.store.failBeforePhase = "COMPLETED"

        val interrupted = fixture.changer.change(OLD.copyOf(), NEW.copyOf())
        fixture.unlockStored(NEW)
        val resumed = fixture.changer.change(NEW.copyOf(), NEW.copyOf())

        assertTrue(interrupted is PassphraseChangeResult.Failed)
        assertTrue(resumed is PassphraseChangeResult.Changed)
        assertEquals(1, fixture.catalog.createCalls)
        assertEquals(2L, fixture.store.configuration.recoveryCredentialGeneration)
        fixture.unlockStored(NEW)
        fixture.close()
    }

    @Test
    fun rotationResumesAfterEveryPrePromotionCheckpointBoundary() = runBlocking {
        listOf(
            "PORTABLE_VERIFIED",
            "REMOTE_PUBLICATION_CREATED",
            "REMOTE_PUBLICATION_VERIFIED",
            "OWNERSHIP_RECHECKED",
            "LOCAL_ENVELOPE_PROMOTED",
        ).forEach { interruptedPhase ->
            val fixture = RotationFixture()
            fixture.store.failBeforePhase = interruptedPhase

            val interrupted = fixture.changer.change(OLD.copyOf(), NEW.copyOf())
            fixture.unlockStored(OLD)
            val resumed = fixture.changer.change(OLD.copyOf(), NEW.copyOf())

            assertTrue(interruptedPhase, interrupted is PassphraseChangeResult.Failed)
            assertTrue(interruptedPhase, resumed is PassphraseChangeResult.Changed)
            assertTrue(interruptedPhase, fixture.catalog.createCalls <= 1)
            fixture.unlockStored(NEW)
            fixture.close()
        }
    }

    @Test
    fun rotationResumesFromItsPendingEnvelopeCheckpoint() = runBlocking {
        val fixture = RotationFixture()
        fixture.failPortableOnce = true

        val interrupted = fixture.changer.change(OLD.copyOf(), NEW.copyOf())
        fixture.unlockStored(OLD)
        val resumed = fixture.changer.change(OLD.copyOf(), NEW.copyOf())

        assertEquals(
            PassphraseChangeResult.Failed(PassphraseChangeFailureCategory.PORTABLE_PACKAGE),
            interrupted,
        )
        assertTrue(resumed is PassphraseChangeResult.Changed)
        assertEquals(1, fixture.catalog.createCalls)
        fixture.unlockStored(NEW)
        fixture.close()
    }

    @Test
    fun rotationRefusesToCreateWhenItsStoredPredecessorWasSuperseded() = runBlocking {
        val fixture = RotationFixture()
        fixture.failPortableOnce = true

        val interrupted = fixture.changer.change(OLD.copyOf(), NEW.copyOf())
        fixture.advanceOrdinaryPublication()
        val resumed = fixture.changer.change(OLD.copyOf(), NEW.copyOf())

        assertEquals(
            PassphraseChangeResult.Failed(PassphraseChangeFailureCategory.PORTABLE_PACKAGE),
            interrupted,
        )
        assertEquals(
            PassphraseChangeResult.Failed(PassphraseChangeFailureCategory.REMOTE_BACKUP),
            resumed,
        )
        assertEquals(0, fixture.catalog.createCalls)
        fixture.unlockStored(OLD)
        fixture.close()
    }

    @Test
    fun rotationRechecksOwnershipImmediatelyBeforeResumedLocalPromotion() = runBlocking {
        val fixture = RotationFixture()
        fixture.store.failBeforePhase = "LOCAL_ENVELOPE_PROMOTED"

        val interrupted = fixture.changer.change(OLD.copyOf(), NEW.copyOf())
        fixture.ownershipStore.allowOneResolutionBeforeLoss()
        val resumed = fixture.changer.change(OLD.copyOf(), NEW.copyOf())

        assertEquals(
            PassphraseChangeResult.Failed(PassphraseChangeFailureCategory.LOCAL_STORAGE),
            interrupted,
        )
        assertEquals(
            PassphraseChangeResult.Failed(PassphraseChangeFailureCategory.REMOTE_BACKUP),
            resumed,
        )
        assertEquals(1L, fixture.store.configuration.recoveryCredentialGeneration)
        fixture.unlockStored(OLD)
        fixture.close()
    }

    private class RotationFixture {
        val crypto = TinkVaultCrypto()
        private val authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto)
        private val publicationCodec = PublicationCodec(authenticatedCodec)
        private val ownershipCodec = OwnershipClaimCodec(authenticatedCodec)
        private val contentKey = crypto.createKey()
        private val currentEnvelope = crypto.wrapForRecovery(contentKey, OLD.copyOf())
        private val currentPublication = publication(currentEnvelope)
        private val ownership = ownership()
        val ownershipStore = TestOwnershipStore(ownership)
        val envelopes = TestEnvelopeStore(currentEnvelope)
        val store = TestRemoteStore(configuration(currentPublication, ownership), envelopes)
        val catalog = TestPublicationCatalog(publicationCodec, currentPublication)
        var portablePublications = 0
        var failPortableOnce = false
        val changer = DefaultRecoveryPassphraseChanger(
            vaultId = VAULT_ID,
            crypto = crypto,
            recoveryEnvelopeStore = envelopes,
            remoteStateStore = store,
            publishPortable = {
                portablePublications += 1
                if (failPortableOnce) {
                    failPortableOnce = false
                    AndroidBackupStatus.Unavailable(BackupUnavailableReason.FILE_IO)
                } else {
                    AndroidBackupStatus.Ready(
                        BackupPackageInfo(
                            packageGeneration = BackupGeneration(42),
                            currentGeneration = BackupGeneration(42),
                            byteCount = 1,
                            producedAt = NOW,
                        ),
                    )
                }
            },
            authorizationManager = TestAuthorizationManager(),
            openObjectStore = { TestObjectStore },
            ownershipStore = { ownershipStore },
            publicationCatalog = { catalog },
            publicationCodec = publicationCodec,
            now = { NOW },
            newPublicationId = { PublicationId.parse(NEXT_PUBLICATION_ID) },
        )

        fun unlockStored(passphrase: CharArray) {
            val envelope = envelopes.require()
            try {
                crypto.unlock(passphrase.copyOf(), envelope).close()
            } finally {
                envelope.clear()
            }
        }

        fun advanceOrdinaryPublication() {
            val draft = currentPublication.manifest.copy(
                bootstrapSha256 = ZERO_SHA256,
                publicationProviderFileId = ORDINARY_PUBLICATION_PROVIDER,
                publicationId = ORDINARY_PUBLICATION_ID,
                publicationSequence = currentPublication.manifest.publicationSequence + 1,
                predecessorPublicationProviderFileId =
                    currentPublication.manifest.publicationProviderFileId,
                predecessorPublicationId = currentPublication.manifest.publicationId,
                predecessorPublicationSha256 = currentPublication.completeSha256.value,
                publicationOperationId = "ordinary-publication-operation",
            )
            val manifest = draft.copy(
                bootstrapSha256 = publicationCodec.bootstrapSha256(draft, currentEnvelope),
            )
            val encoded = publicationCodec.encode(manifest, currentEnvelope, contentKey)
            val ordinary = try {
                publicationCodec.verify(encoded, contentKey)
            } finally {
                encoded.fill(0)
            }
            catalog.replacePublished(ordinary)
            store.configuration = store.configuration.copy(
                currentPublication = ordinary.ref(),
                previousPublication = currentPublication.ref(),
                lastVerifiedGeneration = BackupGeneration(ordinary.manifest.localGeneration),
                stateVersion = RemoteBackupStateVersion(
                    store.configuration.stateVersion.value + 1,
                ),
            )
        }

        fun close() {
            currentEnvelope.clear()
            envelopes.clear()
            contentKey.close()
        }

        private fun publication(envelope: VaultKeyEnvelope): VerifiedPublication {
            val inventory = listOf(
                inventory("base-a", "provider-base-a"),
                inventory("base-b", "provider-base-b"),
            )
            val draft = PublicationManifestV1(
                bootstrapSha256 = ZERO_SHA256,
                lineageId = LINEAGE_ID,
                sourceVaultId = VAULT_ID.value,
                writerEpoch = 3,
                activeDeviceId = DEVICE_ID,
                publicationProviderFileId = CURRENT_PUBLICATION_PROVIDER,
                publicationId = CURRENT_PUBLICATION_ID,
                publicationSequence = 7,
                predecessorPublicationProviderFileId = "provider-publication-6",
                predecessorPublicationId = "00000000-0000-4000-8000-000000000006",
                predecessorPublicationSha256 = DIGEST,
                baseline = false,
                plannedClaimProviderFileId = null,
                plannedClaimId = null,
                predecessorClaimProviderFileId = null,
                predecessorClaimId = null,
                predecessorClaimSha256 = null,
                ownershipClaimProviderFileId = CLAIM_PROVIDER,
                ownershipClaimId = CLAIM_ID,
                ownershipClaimSha256 = DIGEST,
                localGeneration = 42,
                publicationOperationId = "rotation-source-operation",
                currentBaseObjectId = "base-a",
                fallbackBaseObjectId = "base-b",
                inventory = inventory,
                recoveryCredentialGeneration = 1,
            )
            val manifest = draft.copy(
                bootstrapSha256 = publicationCodec.bootstrapSha256(draft, envelope),
            )
            return publicationCodec.verify(
                publicationCodec.encode(manifest, envelope, contentKey),
                contentKey,
            )
        }

        private fun ownership(): VerifiedOwnershipClaim {
            val claim = OwnershipClaimV1(
                lineageId = LINEAGE_ID,
                writerEpoch = 3,
                state = OwnershipStateV1.ACTIVE,
                predecessorProviderFileId = "provider-claim-2",
                predecessorClaimId = "00000000-0000-4000-8000-000000000002",
                predecessorClaimSha256 = DIGEST,
                providerFileId = CLAIM_PROVIDER,
                claimId = CLAIM_ID,
                predecessorReservedSuccessorProviderFileId = CLAIM_PROVIDER,
                sourceVaultId = VAULT_ID.value,
                activeDeviceId = DEVICE_ID,
                nextSuccessorProviderFileId = "provider-claim-4",
                baselinePublicationProviderFileId = "provider-baseline-3",
                baselinePublicationId = "00000000-0000-4000-8000-000000000030",
                baselinePublicationSha256 = DIGEST,
                recoveryCredentialGeneration = 1,
                creationOperationId = "claim-operation",
                tombstoneId = null,
            )
            val encoded = ownershipCodec.encode(claim, contentKey)
            return try {
                VerifiedOwnershipClaim(
                    header = ownershipCodec.readPublicHeader(encoded),
                    claim = claim,
                    completeSha256 = Sha256Digest.of(DIGEST),
                )
            } finally {
                encoded.fill(0)
            }
        }

        private fun configuration(
            publication: VerifiedPublication,
            ownership: VerifiedOwnershipClaim,
        ): RemoteBackupConfiguration = RemoteBackupConfiguration(
            lineageId = CloudLineageId.parse(LINEAGE_ID),
            vaultId = VAULT_ID,
            rootClaimProviderId = ProviderObjectId.of(ROOT_PROVIDER),
            accountBindingDigest = ByteArray(32) { it.toByte() },
            lifecycle = RemoteBackupLifecycle.ACTIVE,
            activeDeviceId = CloudDeviceId.parse(DEVICE_ID),
            writerEpoch = WriterEpoch(3),
            ownershipClaim = OwnershipClaimRef(
                providerId = ProviderObjectId.of(CLAIM_PROVIDER),
                logicalId = OwnershipClaimId.parse(CLAIM_ID),
                sha256 = ownership.completeSha256,
                writerEpoch = WriterEpoch(3),
            ),
            nextSuccessorProviderId = ProviderObjectId.of("provider-claim-4"),
            currentPublication = publication.ref(),
            previousPublication = null,
            lastVerifiedGeneration = BackupGeneration(42),
            lastVerifiedAt = NOW,
            recoveryCredentialGeneration = 1,
            failureCategory = null,
            stateVersion = RemoteBackupStateVersion(4),
        )

        private fun inventory(logicalId: String, providerId: String) = RemoteInventoryItemV1(
            logicalObjectId = logicalId,
            providerFileId = providerId,
            role = RemoteObjectRoleV1.SNAPSHOT,
            firstGeneration = 42,
            lastGeneration = 42,
            frameLength = 1,
            frameSha256 = DIGEST,
        )
    }

    private class TestEnvelopeStore(initial: VaultKeyEnvelope) : RecoveryEnvelopeStore {
        private var envelope = initial.copyEnvelope()
        override suspend fun get(vaultId: VaultId): VaultKeyEnvelope = envelope.copyEnvelope()
        override suspend fun upsert(vaultId: VaultId, envelope: VaultKeyEnvelope) {
            this.envelope.clear()
            this.envelope = envelope.copyEnvelope()
        }
        override suspend fun delete(vaultId: VaultId) = envelope.clear()
        override suspend fun commitInitial(
            vaultId: VaultId,
            envelope: VaultKeyEnvelope,
            published: VerifiedPortableBackup,
        ) = error("not used")
        fun require(): VaultKeyEnvelope = envelope.copyEnvelope()
        fun clear() = envelope.clear()
    }

    private class TestRemoteStore(
        var configuration: RemoteBackupConfiguration,
        private val envelopeStore: RecoveryEnvelopeStore,
    ) : RemoteBackupStateStore, RemoteBackupTransferStore {
        private var operation: RemoteBackupOperation? = null
        var failBeforePhase: String? = null
        override suspend fun active(vaultId: VaultId) = configuration
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
            configuration = next
            return true
        }
        override suspend fun promoteRecoveryEnvelope(
            lineageId: CloudLineageId,
            expected: RemoteBackupStateVersion,
            next: RemoteBackupConfiguration,
            envelope: VaultKeyEnvelope,
            operationId: String,
            expectedOperationPhase: String,
            nextOperation: RemoteBackupOperation,
        ): Boolean {
            if (configuration.lineageId != lineageId ||
                configuration.stateVersion != expected || operation?.phase != expectedOperationPhase
            ) return false
            if (nextOperation.phase == failBeforePhase) {
                failBeforePhase = null
                return false
            }
            configuration = next
            envelopeStore.upsert(next.vaultId, envelope)
            operation = nextOperation
            return true
        }
        override suspend fun operation(operationId: String) = operation
        override suspend fun putOperation(operation: RemoteBackupOperation) {
            this.operation = operation
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
            operation = next
            return true
        }
        override suspend fun objectState(lineageId: CloudLineageId, logicalObjectId: RemoteLogicalObjectId) = null
        override suspend fun insertObject(value: RemoteBackupObject) = Unit
        override suspend fun compareAndSetObject(expected: RemoteBackupObject, next: RemoteBackupObject) = true
        override suspend fun objectsForLineage(lineageId: CloudLineageId) = emptyList<RemoteBackupObject>()
        override suspend fun removeObjectState(lineageId: CloudLineageId, logicalObjectId: RemoteLogicalObjectId) = true
    }

    private class TestPublicationCatalog(
        private val codec: PublicationCodec,
        initial: VerifiedPublication,
    ) : PublicationCatalog {
        private val initial = initial
        var published = initial
        var createCalls = 0
        fun replacePublished(value: VerifiedPublication) {
            published = value
        }
        override suspend fun discoverBootstraps(
            lineageId: CloudLineageId,
            epoch: WriterEpoch,
            plannedOrActualClaimProviderId: ProviderObjectId,
        ) = PublicationCandidateDiscovery.Discovered(
            listOf(PublicationCandidate(published.manifest.providerId(), published.bootstrap)),
        )
        override suspend fun resolve(
            ownership: VerifiedOwnershipClaim,
            candidates: List<PublicationCandidate>,
            contentKey: VaultKey,
        ) = PublicationResolution.Resolved(
            published,
            initial.takeIf { it.manifest.publicationId != published.manifest.publicationId },
        )
        override suspend fun create(
            providerObjectId: ProviderObjectId,
            encodedPublication: OwnedRemoteBytes,
            contentKey: VaultKey,
        ): PublicationCreateResult {
            createCalls += 1
            val bytes = encodedPublication.take()
            try {
                published = codec.verify(bytes, contentKey)
                return PublicationCreateResult.Created(published)
            } finally {
                bytes.fill(0)
                encodedPublication.close()
            }
        }
    }

    private class TestOwnershipStore(private val ownership: VerifiedOwnershipClaim) : OwnershipChainStore {
        private var successfulResolutionsBeforeLoss = Int.MAX_VALUE

        fun allowOneResolutionBeforeLoss() {
            successfulResolutionsBeforeLoss = 1
        }

        override suspend fun discoverPublicRoots() = OwnershipRootDiscovery.Discovered(emptyList())
        override suspend fun resolve(
            rootProviderId: ProviderObjectId,
            contentKey: VaultKey,
        ): OwnershipResolution = if (successfulResolutionsBeforeLoss > 0) {
            successfulResolutionsBeforeLoss -= 1
            OwnershipResolution.Active(ownership, ownership)
        } else {
            OwnershipResolution.Blocked(
                app.opentasks.core.model.RemoteBackupFailureCategory.OWNERSHIP_LOST,
            )
        }
        override suspend fun createClaim(
            expectedPredecessor: VerifiedOwnershipClaim?,
            encodedClaim: OwnedRemoteBytes,
            contentKey: VaultKey,
        ): OwnershipClaimCreateResult = error("not used")
    }

    private class TestAuthorizationManager : GoogleDriveAuthorizationManager {
        override suspend fun authorize(mode: DriveAuthorizationMode, expectedAccountDigest: ByteArray?) =
            DriveAuthorizationResult.Authorized(
                AuthorizedDriveSession(TestTransport, ByteArray(32), "token", null),
            )
        override suspend fun acceptResolution(data: android.content.Intent, expectedAccountDigest: ByteArray?) =
            error("not used")
        override suspend fun clearToken(session: AuthorizedDriveSession) = session.close()
        override suspend fun revokeAccess(session: AuthorizedDriveSession) = session.close()
    }

    private object TestObjectStore : CreateOnlyBackupObjectStore {
        override suspend fun generateProviderIds(count: Int, role: RemoteObjectRoleV1) =
            listOf(ProviderObjectId.of(NEXT_PUBLICATION_PROVIDER))
        override suspend fun createSmallIfAbsent(providerObjectId: ProviderObjectId, lineageId: CloudLineageId, metadata: app.opentasks.core.domain.RemoteListedObject, bytes: OwnedRemoteBytes) = CreateSmallResult.Created
        override suspend fun readSmall(providerObjectId: ProviderObjectId, maximumBytes: Long): ReadSmallResult =
            ReadSmallResult.Missing
        override suspend fun list(request: RemoteListRequest) = RemoteListPage(emptyList(), null)
        override suspend fun uploadImmutable(request: ImmutableUploadRequest) = ImmutableUploadResult.UploadedAndVerified
        override suspend fun downloadImmutable(providerObjectId: ProviderObjectId, maximumBytes: Long, expectedSha256: Sha256Digest) = ImmutableDownloadResult.Missing
        override suspend fun delete(providerObjectId: ProviderObjectId) = DeleteObjectResult.Missing
    }

    private object TestTransport : CreateOnlyDriveTransport {
        override suspend fun readCurrentUserPermissionId() = "unused"
        override suspend fun generateAppDataFileIds(count: Int) = error("not used")
        override suspend fun listAppDataFiles(query: String, pageToken: String?, pageSize: Int) = error("not used")
        override suspend fun createFileIfAbsent(request: app.opentasks.core.data.backup.drive.DriveCreateRequest) = error("not used")
        override suspend fun downloadFile(providerFileId: String, destination: java.io.File, maximumBytes: Long) = error("not used")
        override suspend fun startResumableCreate(metadata: app.opentasks.core.data.backup.drive.DriveFileMetadata, totalBytes: Long) = error("not used")
        override suspend fun queryResumableUpload(sessionUri: String, totalBytes: Long) = error("not used")
        override suspend fun uploadChunk(sessionUri: String, firstByte: Long, totalBytes: Long, content: ByteArray) = error("not used")
        override suspend fun deleteFile(providerFileId: String) = error("not used")
        override fun close() = Unit
    }

    private companion object {
        val OLD = "old passphrase".toCharArray()
        val NEW = "new passphrase".toCharArray()
        val VAULT_ID = VaultId("vault-alpha")
        val NOW = Instant.ofEpochMilli(1_800_000_000_000)
        const val LINEAGE_ID = "00000000-0000-4000-8000-000000000001"
        const val DEVICE_ID = "00000000-0000-4000-8000-000000000005"
        const val CLAIM_ID = "00000000-0000-4000-8000-000000000003"
        const val CLAIM_PROVIDER = "provider-claim-3"
        const val ROOT_PROVIDER = "provider-root"
        const val CURRENT_PUBLICATION_ID = "00000000-0000-4000-8000-000000000007"
        const val NEXT_PUBLICATION_ID = "00000000-0000-4000-8000-000000000008"
        const val CURRENT_PUBLICATION_PROVIDER = "provider-publication-7"
        const val NEXT_PUBLICATION_PROVIDER = "provider-publication-8"
        const val ORDINARY_PUBLICATION_PROVIDER = "provider-publication-ordinary-8"
        const val ORDINARY_PUBLICATION_ID = "00000000-0000-4000-8000-000000000009"
        const val ZERO_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"
        const val DIGEST =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        fun VaultKeyEnvelope.copyEnvelope() = VaultKeyEnvelope(
            formatVersion,
            kdf.copy(salt = kdf.salt.copyOf()),
            nonce.copyOf(),
            wrappedKeyset.copyOf(),
        )
        fun VaultKeyEnvelope.clear() {
            kdf.salt.fill(0)
            nonce.fill(0)
            wrappedKeyset.fill(0)
        }
        fun VerifiedPublication.ref() = PublicationRef(
            providerId = manifest.providerId(),
            logicalId = PublicationId.parse(manifest.publicationId),
            sha256 = completeSha256,
            sequence = PublicationSequence(manifest.publicationSequence),
            generation = BackupGeneration(manifest.localGeneration),
        )
        fun PublicationManifestV1.providerId() = ProviderObjectId.of(publicationProviderFileId)
    }
}
