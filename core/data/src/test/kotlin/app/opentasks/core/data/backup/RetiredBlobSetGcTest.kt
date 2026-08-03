package app.opentasks.core.data.backup

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.InMemoryVaultRepository
import app.opentasks.core.data.db.AttachmentTransferDao
import app.opentasks.core.data.db.AttachmentTransferEntity
import app.opentasks.core.domain.AttachmentBlobStore
import app.opentasks.core.domain.AttachmentListedObject
import app.opentasks.core.domain.AttachmentManifestLookup
import app.opentasks.core.domain.AttachmentObjectResult
import app.opentasks.core.domain.AttachmentReadResult
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupOperation
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.OwnershipClaimId
import app.opentasks.core.model.OwnershipClaimRef
import app.opentasks.core.model.OwnershipStateV1
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationId
import app.opentasks.core.model.PublicationRef
import app.opentasks.core.model.PublicationSequence
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WriterEpoch
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The durable `retired_blob_sets` index (Room v9) is meant to join the same
 * collection batch every attachment-row tombstone already does: once a purge
 * removes a blob-bearing attachment row outright, that row can no longer
 * drive [AttachmentGarbageCollector.runBatch] itself, so the retired-set row
 * Task 1 leaves behind has to become the [GcCandidate] instead.
 *
 * These tests drive [AttachmentRuntime.collectRetiredBytes] — the same path
 * [AttachmentOwnershipBoundaryTest] exercises for attachment-row tombstones —
 * with a fake [AttachmentBlobStore] copied from
 * [AttachmentGarbageCollectorTest], over a workspace whose only retirement
 * source is the `retired_blob_sets` table a task purge populates.
 */
class RetiredBlobSetGcTest {
    private val crypto: VaultCrypto = TinkVaultCrypto()
    private val codec = DefaultAuthenticatedCloudObjectCodec(crypto)
    private val repository = InMemoryVaultRepository()
    private val journalDao = FakeJournalDao()
    private val cacheRoot: File = Files.createTempDirectory("retired-blob-set-gc").toFile()
    private val cache = AttachmentCacheStore(cacheRoot) { 8L * 1024 * 1024 }
    private var sessionsOpened = 0
    private var sessionsClosed = 0

    @After
    fun cleanUp() {
        cacheRoot.deleteRecursively()
    }

    @Test
    fun retiredCandidateOldEnoughAndCoveredIsDeletedChunksBeforeManifest() = runBlocking {
        withTimeout(5_000) {
            val blobSetId = retireViaPurge(
                taskId = TASK_PROPOSAL,
                retiredAt = NOW.minus(Duration.ofDays(30)),
                tombstoneGeneration = PREVIOUS_GENERATION,
            )
            val store = FakeAttachmentStore(
                listed(blobSetId.value, "manifest", MANIFEST_ROLE),
                listed(blobSetId.value, "chunk-0", CHUNK_ROLE),
            )

            val result = runtime(store, now = NOW).collectRetiredBytes()

            // Deletion is ordered chunks-before-manifest regardless of the
            // order the store listed them in.
            assertEquals(listOf("chunk-0", "manifest"), store.deleted)
            assertEquals(2, result.deletedObjects)
            assertEquals(setOf(blobSetId), result.collectedBlobSets)
            assertEquals(1, sessionsOpened)
            assertEquals(1, sessionsClosed)

            // The batch proved it released the bytes, so the durable index no
            // longer needs to track this blob set.
            assertTrue(repository.currentWorkspace().retiredBlobSets.isEmpty())
        }
    }

    @Test
    fun youngOrUncoveredRetiredCandidateIsRetained() = runBlocking {
        withTimeout(5_000) {
            val young = retireViaPurge(
                taskId = TASK_PROPOSAL,
                retiredAt = NOW.minus(Duration.ofDays(29)),
                tombstoneGeneration = PREVIOUS_GENERATION,
            )
            val uncovered = retireViaPurge(
                taskId = TASK_INVOICES,
                retiredAt = NOW.minus(Duration.ofDays(30)),
                // Covered by the current base but not yet by the fallback
                // base, so it fails coverage alone.
                tombstoneGeneration = CURRENT_GENERATION,
            )
            val store = FakeAttachmentStore(
                listed(young.value, "young-chunk", CHUNK_ROLE),
                listed(uncovered.value, "uncovered-chunk", CHUNK_ROLE),
            )

            val result = runtime(store, now = NOW).collectRetiredBytes()

            assertEquals(NOTHING_COLLECTED, result)
            assertEquals(0, sessionsOpened)
            assertTrue(store.deleted.isEmpty())
            assertEquals(2, repository.currentWorkspace().retiredBlobSets.size)
        }
    }

    /**
     * A blob set a live attachment still names is a replacement edge, not a
     * collection candidate — the runtime's candidate builder excludes it
     * before any session is authorized or any object listed, so this asserts
     * that exclusion rather than anything the collector itself decides.
     */
    @Test
    fun retiredIdStillReferencedByLiveAttachmentIsNeverACandidate() = runBlocking {
        withTimeout(5_000) {
            val blobSetId = retireViaPurge(
                taskId = TASK_PROPOSAL,
                retiredAt = NOW.minus(Duration.ofDays(30)),
                tombstoneGeneration = PREVIOUS_GENERATION,
            )
            val replacementTask = repository.currentWorkspace().tasks
                .single { it.id == TASK_INVOICES }
            assertTrue(
                repository.execute(
                    DomainCommand.RegisterAttachment(attachment(replacementTask, blobSetId)),
                ) is CommandResult.Success,
            )
            val store = FakeAttachmentStore(listed(blobSetId.value, "chunk-0", CHUNK_ROLE))

            val result = runtime(store, now = NOW).collectRetiredBytes()

            assertEquals(NOTHING_COLLECTED, result)
            assertEquals(0, sessionsOpened)
            assertTrue(store.deleted.isEmpty())
            assertEquals(1, repository.currentWorkspace().retiredBlobSets.size)
        }
    }

    /**
     * The destructive "remove all attachment content" action wipes the whole
     * namespace outside `collectRetiredBytes`, so it must release every
     * retired-blob-set row itself: a row it leaves behind can never satisfy
     * `collectedBlobSets` on a later GC pass, because that pass would list an
     * already-empty namespace and find nothing to confirm a release against.
     */
    @Test
    fun destructiveContentReleaseRetiresRetiredRowsAlongsideLiveAttachments() = runBlocking {
        withTimeout(5_000) {
            retireViaPurge(
                taskId = TASK_PROPOSAL,
                retiredAt = NOW.minus(Duration.ofDays(1)),
                tombstoneGeneration = PREVIOUS_GENERATION,
            )
            val liveTask = repository.currentWorkspace().tasks.single { it.id == TASK_INVOICES }
            assertTrue(
                repository.execute(
                    DomainCommand.RegisterAttachment(attachment(liveTask, BlobSetId.new())),
                ) is CommandResult.Success,
            )

            runtime(FakeAttachmentStore(), now = NOW).recordAllContentCollected()

            assertTrue(repository.currentWorkspace().retiredBlobSets.isEmpty())
            val recorded = repository.currentWorkspace().attachments
                .single { it.taskId == liveTask.id }
            assertNull(recorded.blobSetId)
        }
    }

    private fun runtime(store: AttachmentBlobStore, now: Instant) = AttachmentRuntime(
        vaultId = VAULT_ID,
        repository = repository,
        remoteStateStore = FakeStateStore(configuration()),
        transferDao = FakeTransferDao(),
        journalDao = journalDao,
        codec = codec,
        manifestCodec = AttachmentBlobSetManifestCodec(codec),
        cache = cache,
        contentKeyStore = FreshKeyStore(crypto),
        openSession = {
            sessionsOpened += 1
            AttachmentSessionResult.Opened(
                AttachmentProviderSession(
                    blobStore = store,
                    ownershipChainStore = FakeChainStore(),
                ) { sessionsClosed += 1 },
            )
        },
        now = { now },
    )

    /**
     * Registers a blob-bearing attachment on [taskId], purges the task, and
     * fakes the journal generation Task 2 would have recorded for the
     * resulting `retired_blob_sets` upsert.
     */
    private suspend fun retireViaPurge(
        taskId: TaskId,
        retiredAt: Instant,
        tombstoneGeneration: Long,
    ): BlobSetId {
        val blobSetId = BlobSetId.new()
        val task = repository.currentWorkspace().tasks.single { it.id == taskId }
        assertTrue(
            repository.execute(
                DomainCommand.RegisterAttachment(attachment(task, blobSetId)),
            ) is CommandResult.Success,
        )
        assertTrue(
            repository.execute(
                DomainCommand.DeleteTask(taskId, retiredAt),
            ) is CommandResult.Success,
        )
        assertTrue(
            repository.execute(
                DomainCommand.PermanentlyDeleteTask(taskId, retiredAt),
            ) is CommandResult.Success,
        )
        journalDao.generations[blobSetId.value] = tombstoneGeneration
        return blobSetId
    }

    private fun attachment(
        task: Task,
        blobSetId: BlobSetId,
    ) = Attachment(
        id = AttachmentId.new(),
        taskId = task.id,
        displayName = "receipt.pdf",
        mimeType = "application/pdf",
        byteCount = 1_024,
        contentHash = "b".repeat(64),
        blobSetId = blobSetId,
        chunkCount = 1,
        deletedAt = null,
        revision = task.revision,
    )

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
        currentPublication = publication(CURRENT_GENERATION, 2),
        previousPublication = publication(PREVIOUS_GENERATION, 1),
        lastVerifiedGeneration = BackupGeneration(CURRENT_GENERATION),
        lastVerifiedAt = NOW,
        recoveryCredentialGeneration = 0,
        failureCategory = null,
        stateVersion = RemoteBackupStateVersion(1),
    )

    private fun publication(generation: Long, sequence: Long) = PublicationRef(
        providerId = ProviderObjectId.of("publication-$sequence"),
        logicalId = PublicationId.parse("55555555-5555-4555-8555-55555555555$sequence"),
        sha256 = Sha256Digest.of("c".repeat(64)),
        sequence = PublicationSequence(sequence),
        generation = BackupGeneration(generation),
    )

    private class FakeAttachmentStore(
        vararg initial: AttachmentListedObject,
    ) : AttachmentBlobStore {
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
        override suspend fun listNamespace(
            pageToken: String?,
            exactRole: String?,
        ): Pair<List<AttachmentListedObject>, String?> {
            val listed = objects.filter { exactRole == null || it.role == exactRole }
            return listed to null
        }
        override suspend fun delete(providerObjectId: ProviderObjectId): Boolean {
            deleted += providerObjectId.value
            objects.removeAll { it.providerObjectId == providerObjectId }
            return true
        }
    }

    private class FakeChainStore : OwnershipChainStore {
        override suspend fun discoverPublicRoots() = error("not used")
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
                baselinePublicationProviderFileId = "publication-2",
                baselinePublicationId = "55555555-5555-4555-8555-555555555552",
                baselinePublicationSha256 = "c".repeat(64),
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
        ) = error("not used")
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
            error("not used")
        override suspend fun compareAndSet(
            lineageId: CloudLineageId,
            expected: RemoteBackupStateVersion,
            next: RemoteBackupConfiguration,
        ): Boolean = error("not used")
        override suspend fun operation(operationId: String): RemoteBackupOperation? = null
        override suspend fun putOperation(operation: RemoteBackupOperation) = error("not used")
        override suspend fun transitionOperation(
            operationId: String,
            expectedPhase: String,
            next: RemoteBackupOperation,
        ): Boolean = error("not used")
    }

    private class FakeTransferDao : AttachmentTransferDao {
        override suspend fun upsert(value: AttachmentTransferEntity) = error("not used")
        override suspend fun pending(): List<AttachmentTransferEntity> = emptyList()
        override suspend fun stale(beforeEpochMillis: Long): List<AttachmentTransferEntity> =
            emptyList()
        override suspend fun delete(blobSetId: String): Int = 0
    }

    private class FakeJournalDao : BackupJournalDao {
        val generations = mutableMapOf<String, Long>()
        override suspend fun insert(entity: BackupJournalEntity) = error("not used")
        override suspend fun latestGenerationFor(objectType: String, objectId: String): Long? =
            generations[objectId]?.takeIf { objectType == BackupRecordFamily.RETIRED_BLOB_SET.name }
        override suspend fun after(
            vaultId: String,
            generation: Long,
            limit: Int,
        ): List<BackupJournalEntity> = error("not used")
        override suspend fun between(
            vaultId: String,
            firstGeneration: Long,
            lastGeneration: Long,
        ): List<BackupJournalEntity> = error("not used")
        override suspend fun countThrough(
            vaultId: String,
            afterGeneration: Long,
            throughGeneration: Long,
            limit: Int,
        ): Int = error("not used")
    }

    private class FreshKeyStore(private val crypto: VaultCrypto) : VaultContentKeyStore {
        override fun getOrCreate(vaultId: VaultId): VaultKey = openExisting(vaultId)
        override fun openExisting(vaultId: VaultId): VaultKey = crypto.createKey()
        override fun replace(vaultId: VaultId, key: VaultKey) = error("not used")
        override fun delete(vaultId: VaultId) = error("not used")
    }

    private companion object {
        const val CHUNK_ROLE = "attachment-chunk"
        const val MANIFEST_ROLE = "attachment-manifest"
        const val CURRENT_GENERATION = 8L
        const val PREVIOUS_GENERATION = 7L
        const val EXPECTED_TIP =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val VAULT_ID = VaultId("11111111-1111-4111-8111-111111111111")
        val LINEAGE_ID = CloudLineageId.parse("22222222-2222-4222-8222-222222222222")
        val ROOT_CLAIM = ProviderObjectId.of("root-claim")
        val TASK_PROPOSAL = OpenTasksFixtures.tasks[0].id
        val TASK_INVOICES = OpenTasksFixtures.tasks[1].id
        val NOW: Instant = Instant.parse("2026-08-02T00:00:00Z")
        val NOTHING_COLLECTED = AttachmentGcResult(0, false, 0)

        fun listed(blobSetId: String, providerId: String, role: String) =
            AttachmentListedObject(
                providerObjectId = ProviderObjectId.of(providerId),
                role = role,
                blobSetId = blobSetId,
                createdAtEpochMillis = null,
            )
    }
}
