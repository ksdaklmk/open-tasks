package app.opentasks.core.data.backup

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.InMemoryVaultRepository
import app.opentasks.core.data.db.AttachmentTransferDao
import app.opentasks.core.data.db.AttachmentTransferEntity
import app.opentasks.core.domain.AttachmentBlobStore
import app.opentasks.core.domain.AttachmentListedObject
import app.opentasks.core.domain.AttachmentManifestLookup
import app.opentasks.core.domain.AttachmentObjectResult
import app.opentasks.core.domain.AttachmentReadResult
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupOperation
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.OwnershipClaimId
import app.opentasks.core.model.OwnershipClaimRef
import app.opentasks.core.model.OwnershipStateV1
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WriterEpoch
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `AttachmentBlobCoordinator.resume()` shipped in Stage 4 with no product
 * caller. This is that caller: an interrupted intake session resumes
 * silently, through the same active-lineage and ownership-tip boundary every
 * other attachment operation crosses.
 */
class AttachmentResumeRuntimeTest {
    private val crypto: VaultCrypto = TinkVaultCrypto()
    private val codec = DefaultAuthenticatedCloudObjectCodec(crypto)
    private val repository = InMemoryVaultRepository()
    private val transferDao = FakeTransferDao()
    private val store = FakeAttachmentBlobStore()
    private val contentKeyStore = ReusableKeyStore(crypto)
    private val cacheRoot: File = Files.createTempDirectory("attachment-resume").toFile()
    private val cache = AttachmentCacheStore(cacheRoot) { 8L * 1024 * 1024 }
    private var sessionsOpened = 0

    @After
    fun cleanUp() {
        cacheRoot.deleteRecursively()
    }

    @Test
    fun interruptedSessionResumesAndRegistersWithoutNewIntake() = runBlocking {
        withTimeout(5_000) {
            store.readFailure = RemoteBackupFailureCategory.RETRYABLE_PROVIDER
            val runtime = runtime()

            val interrupted = runtime.intake(
                OpenTasksFixtures.tasks.first().id,
                "receipt.pdf",
                "application/pdf",
                source(),
            )

            assertEquals(
                AttachmentIntakeResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
                interrupted,
            )
            assertEquals("UPLOADING", transferDao.rows.values.single().phase)
            val chunkCreatesBeforeResume = store.chunkCreateCalls.size
            store.readFailure = null

            assertEquals(1, runtime.resumeInterruptedSessions())

            assertEquals(chunkCreatesBeforeResume, store.chunkCreateCalls.size)
            assertEquals(1, store.manifestCreateCalls.size)
            assertEquals(1, repository.currentWorkspace().attachments.size)
            assertEquals("REGISTERED", transferDao.rows.values.single().phase)
        }
    }

    @Test
    fun unavailableProviderSessionMakesZeroStoreCalls() = runBlocking {
        withTimeout(5_000) {
            // Seeded first, exactly as test one does: an unfinished session
            // must exist, or the local pending-session guard refuses before
            // ever consulting openSession, and this test would stop
            // exercising the Unavailable path it claims to.
            store.readFailure = RemoteBackupFailureCategory.RETRYABLE_PROVIDER
            runtime().intake(
                OpenTasksFixtures.tasks.first().id,
                "receipt.pdf",
                "application/pdf",
                source(),
            )
            assertEquals("UPLOADING", transferDao.rows.values.single().phase)
            store.readFailure = null
            val callsBeforeResume = store.totalCalls
            val runtime = runtime(
                openSession = {
                    AttachmentSessionResult.Unavailable(
                        RemoteBackupFailureCategory.RETRYABLE_PROVIDER,
                    )
                },
            )

            assertEquals(0, runtime.resumeInterruptedSessions())

            assertEquals(callsBeforeResume, store.totalCalls)
        }
    }

    @Test
    fun stoppedRuntimeRefusesResume() = runBlocking {
        withTimeout(5_000) {
            val runtime = runtime()
            runtime.stop()

            assertEquals(0, runtime.resumeInterruptedSessions())

            assertEquals(0, store.totalCalls)
            assertEquals(0, sessionsOpened)
        }
    }

    private fun runtime(
        openSession: (suspend (RemoteBackupConfiguration) -> AttachmentSessionResult)? = null,
    ) = AttachmentRuntime(
        vaultId = VAULT_ID,
        repository = repository,
        remoteStateStore = FakeStateStore(configuration()),
        transferDao = transferDao,
        journalDao = FakeJournalDao(),
        codec = codec,
        manifestCodec = AttachmentBlobSetManifestCodec(codec),
        cache = cache,
        contentKeyStore = contentKeyStore,
        openSession = openSession ?: {
            sessionsOpened += 1
            AttachmentSessionResult.Opened(
                AttachmentProviderSession(
                    blobStore = store,
                    ownershipChainStore = FakeChainStore,
                ) {},
            )
        },
    )

    private fun source() = object : AttachmentSource {
        override val declaredByteCount: Long = 4
        override fun open(): InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
    }

    private fun configuration() = RemoteBackupConfiguration(
        lineageId = LINEAGE_ID,
        vaultId = VAULT_ID,
        rootClaimProviderId = ROOT_CLAIM,
        accountBindingDigest = ByteArray(32) { (it + 1).toByte() },
        lifecycle = RemoteBackupLifecycle.ACTIVE,
        activeDeviceId = CloudDeviceId.parse("33333333-3333-4333-8333-333333333333"),
        writerEpoch = WriterEpoch(1),
        ownershipClaim = OwnershipClaimRef(
            providerId = ROOT_CLAIM,
            logicalId = OwnershipClaimId.parse("44444444-4444-4444-8444-444444444444"),
            sha256 = Sha256Digest.of(EXPECTED_TIP),
            writerEpoch = WriterEpoch(1),
        ),
        nextSuccessorProviderId = null,
        currentPublication = null,
        previousPublication = null,
        lastVerifiedGeneration = null,
        lastVerifiedAt = null,
        recoveryCredentialGeneration = 0,
        failureCategory = null,
        stateVersion = RemoteBackupStateVersion(1),
    )

    /**
     * A content-key store that survives being closed after every runtime
     * call: it wraps one keyset once and unlocks a fresh [VaultKey] instance
     * from that same envelope on every [openExisting] call, exactly as a
     * persisted store would, so a chunk encrypted during an interrupted
     * intake still decrypts during a later, separate resume call.
     */
    private class ReusableKeyStore(
        private val crypto: VaultCrypto,
        private val passphrase: CharArray = "attachment-resume-test".toCharArray(),
    ) : VaultContentKeyStore {
        private val envelope: VaultKeyEnvelope = crypto.createKey().let { key ->
            try {
                crypto.wrapForRecovery(key, passphrase)
            } finally {
                key.close()
            }
        }

        override fun getOrCreate(vaultId: VaultId): VaultKey = openExisting(vaultId)

        override fun openExisting(vaultId: VaultId): VaultKey = crypto.unlock(passphrase, envelope)

        override fun replace(vaultId: VaultId, key: VaultKey) =
            error("This test never rotates the content key")

        override fun delete(vaultId: VaultId) =
            error("This test never deletes the content key")
    }

    private class FakeTransferDao : AttachmentTransferDao {
        val rows = linkedMapOf<String, AttachmentTransferEntity>()

        override suspend fun upsert(value: AttachmentTransferEntity) {
            rows[value.blobSetId] = value
        }

        override suspend fun pending(): List<AttachmentTransferEntity> =
            rows.values.filter { it.phase != "REGISTERED" }

        override suspend fun stale(beforeEpochMillis: Long): List<AttachmentTransferEntity> =
            pending().filter { it.updatedAtEpochMillis < beforeEpochMillis }

        override suspend fun delete(blobSetId: String): Int =
            if (rows.remove(blobSetId) != null) 1 else 0
    }

    private class FakeAttachmentBlobStore : AttachmentBlobStore {
        val objects = linkedMapOf<ProviderObjectId, ByteArray>()
        val chunkCreateCalls = mutableListOf<ProviderObjectId>()
        val manifestCreateCalls = mutableListOf<ProviderObjectId>()
        val readCalls = mutableListOf<ProviderObjectId>()
        var readFailure: RemoteBackupFailureCategory? = null
        var totalCalls = 0
        private var nextId = 0

        override suspend fun generateObjectIds(count: Int): List<ProviderObjectId> {
            totalCalls += 1
            return List(count) { ProviderObjectId.of("provider-${nextId++}") }
        }

        override suspend fun createChunk(
            providerObjectId: ProviderObjectId,
            blobSetId: BlobSetId,
            chunkIndex: Int,
            chunkCount: Int,
            frameBytes: ByteArray,
        ): AttachmentObjectResult {
            totalCalls += 1
            chunkCreateCalls += providerObjectId
            if (objects.containsKey(providerObjectId)) return AttachmentObjectResult.AlreadyExists
            objects[providerObjectId] = frameBytes.copyOf()
            return AttachmentObjectResult.Created
        }

        override suspend fun readObject(
            providerObjectId: ProviderObjectId,
            maximumBytes: Long,
        ): AttachmentReadResult {
            totalCalls += 1
            readCalls += providerObjectId
            readFailure?.let { return AttachmentReadResult.Failed(it) }
            val bytes = objects[providerObjectId] ?: return AttachmentReadResult.Missing
            return AttachmentReadResult.Found(bytes.copyOf())
        }

        override suspend fun createManifest(
            providerObjectId: ProviderObjectId,
            blobSetId: BlobSetId,
            frameBytes: ByteArray,
        ): AttachmentObjectResult {
            totalCalls += 1
            manifestCreateCalls += providerObjectId
            if (objects.containsKey(providerObjectId)) return AttachmentObjectResult.AlreadyExists
            objects[providerObjectId] = frameBytes.copyOf()
            return AttachmentObjectResult.Created
        }

        override suspend fun findManifest(blobSetId: BlobSetId): AttachmentManifestLookup {
            totalCalls += 1
            return AttachmentManifestLookup.Missing
        }

        override suspend fun listNamespace(
            pageToken: String?,
            exactRole: String?,
        ): Pair<List<AttachmentListedObject>, String?> {
            totalCalls += 1
            return emptyList<AttachmentListedObject>() to null
        }

        override suspend fun delete(providerObjectId: ProviderObjectId): Boolean {
            totalCalls += 1
            return objects.remove(providerObjectId) != null
        }
    }

    private object FakeChainStore : OwnershipChainStore {
        override suspend fun discoverPublicRoots() =
            error("This boundary never discovers a root")

        override suspend fun resolve(
            rootProviderId: ProviderObjectId,
            contentKey: VaultKey,
        ): OwnershipResolution {
            val claim = OwnershipClaimV1(
                lineageId = LINEAGE_ID.value,
                writerEpoch = 1,
                state = OwnershipStateV1.ACTIVE,
                predecessorProviderFileId = null,
                predecessorClaimId = null,
                predecessorClaimSha256 = null,
                providerFileId = ROOT_CLAIM.value,
                claimId = "44444444-4444-4444-8444-444444444444",
                predecessorReservedSuccessorProviderFileId = null,
                sourceVaultId = VAULT_ID.value,
                activeDeviceId = "33333333-3333-4333-8333-333333333333",
                nextSuccessorProviderFileId = null,
                baselinePublicationProviderFileId = null,
                baselinePublicationId = null,
                baselinePublicationSha256 = null,
                recoveryCredentialGeneration = 0,
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
                    encryptedFrameSha256 = "c".repeat(64),
                ),
                claim = claim,
                completeSha256 = Sha256Digest.of(EXPECTED_TIP),
            )
            return OwnershipResolution.Active(verified, verified)
        }

        override suspend fun createClaim(
            expectedPredecessor: VerifiedOwnershipClaim?,
            encodedClaim: OwnedRemoteBytes,
            contentKey: VaultKey,
        ) = error("This boundary never creates a claim")
    }

    private class FakeStateStore(
        private val current: RemoteBackupConfiguration?,
    ) : RemoteBackupStateStore {
        override suspend fun active(vaultId: VaultId): RemoteBackupConfiguration? =
            current?.takeIf { it.vaultId == vaultId }

        override suspend fun known(lineageId: CloudLineageId): RemoteBackupConfiguration? =
            current?.takeIf { it.lineageId == lineageId }

        override fun observeActive(vaultId: VaultId): Flow<RemoteBackupConfiguration?> =
            flowOf(current)

        override suspend fun insertConnecting(configuration: RemoteBackupConfiguration) =
            error("This test never configures a lineage")

        override suspend fun compareAndSet(
            lineageId: CloudLineageId,
            expected: RemoteBackupStateVersion,
            next: RemoteBackupConfiguration,
        ): Boolean = error("This test never writes configuration")

        override suspend fun operation(operationId: String): RemoteBackupOperation? = null

        override suspend fun putOperation(operation: RemoteBackupOperation) =
            error("This test never records an operation")

        override suspend fun transitionOperation(
            operationId: String,
            expectedPhase: String,
            next: RemoteBackupOperation,
        ): Boolean = error("This test never records an operation")
    }

    private class FakeJournalDao : BackupJournalDao {
        override suspend fun insert(entity: BackupJournalEntity) =
            error("This test appends no journal entry")

        override suspend fun latestGenerationFor(objectType: String, objectId: String): Long? =
            null

        override suspend fun after(
            vaultId: String,
            generation: Long,
            limit: Int,
        ): List<BackupJournalEntity> = error("This test reads no journal page")

        override suspend fun between(
            vaultId: String,
            firstGeneration: Long,
            lastGeneration: Long,
        ): List<BackupJournalEntity> = error("This test reads no journal range")

        override suspend fun countThrough(
            vaultId: String,
            afterGeneration: Long,
            throughGeneration: Long,
            limit: Int,
        ): Int = error("This test counts no journal entry")
    }

    private companion object {
        val VAULT_ID = VaultId("11111111-1111-4111-8111-111111111111")
        val LINEAGE_ID = CloudLineageId.parse("22222222-2222-4222-8222-222222222222")
        val ROOT_CLAIM = ProviderObjectId.of("root-claim")
        const val EXPECTED_TIP =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
