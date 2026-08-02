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
import app.opentasks.core.model.DeviceId
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
import app.opentasks.core.model.Revision
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WriterEpoch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one boundary every attachment operation crosses: a live slot, an active
 * lineage, and a chain whose authenticated tip is still the claim this
 * installation published under.
 */
class AttachmentOwnershipBoundaryTest {
    private val crypto: VaultCrypto = TinkVaultCrypto()
    private val codec = DefaultAuthenticatedCloudObjectCodec(crypto)
    private val repository = InMemoryVaultRepository()
    private val transferDao = FakeTransferDao()
    private val journalDao = FakeJournalDao()
    private val store = RecordingBlobStore()
    private val cacheRoot: File = Files.createTempDirectory("attachment-boundary").toFile()
    private val cache = AttachmentCacheStore(cacheRoot) { 8L * 1024 * 1024 }
    private var chainTip = EXPECTED_TIP
    private var sessionsOpened = 0
    private var sessionsClosed = 0

    @After
    fun cleanUp() {
        cacheRoot.deleteRecursively()
    }

    @Test
    fun aStoppedSlotRefusesEveryAttachmentOperationWithoutOpeningASession() = runBlocking {
        withTimeout(5_000) {
            retire(BLOB_SET, DELETED_AT)
            val runtime = runtime(now = COLLECTABLE_AT)

            runtime.stop()

            assertEquals(
                AttachmentIntakeResult.OwnershipUnavailable,
                runtime.intake(TASK_ID, "receipt.pdf", "application/pdf", source()),
            )
            assertEquals(
                AttachmentOpenResult.Unavailable,
                runtime.open(attachment(blobSetId = BLOB_SET), ByteArrayOutputStream()),
            )
            assertEquals(0, runtime.expireStaleSessions())
            assertEquals(NOTHING_COLLECTED, runtime.collectRetiredBytes())
            assertEquals(0, sessionsOpened)
            assertEquals(emptyList<String>(), store.calls)
        }
    }

    @Test
    fun everyLineageThatIsNotActivePerformsNoAttachmentStoreCall() = runBlocking {
        withTimeout(5_000) {
            retire(BLOB_SET, DELETED_AT)
            val dormantAndWorse = RemoteBackupLifecycle.entries
                .filterNot { it == RemoteBackupLifecycle.ACTIVE }

            dormantAndWorse.forEach { lifecycle ->
                val runtime = runtime(lifecycle = lifecycle, now = COLLECTABLE_AT)

                assertEquals(
                    "$lifecycle refused intake",
                    AttachmentIntakeResult.OwnershipUnavailable,
                    runtime.intake(TASK_ID, "receipt.pdf", "application/pdf", source()),
                )
                assertEquals(
                    "$lifecycle refused open",
                    AttachmentOpenResult.Unavailable,
                    runtime.open(attachment(blobSetId = BLOB_SET), ByteArrayOutputStream()),
                )
                assertEquals("$lifecycle expired nothing", 0, runtime.expireStaleSessions())
                assertEquals(
                    "$lifecycle collected nothing",
                    NOTHING_COLLECTED,
                    runtime.collectRetiredBytes(),
                )
            }

            val unconfigured = runtime(configured = false, now = COLLECTABLE_AT)
            assertEquals(
                AttachmentIntakeResult.OwnershipUnavailable,
                unconfigured.intake(TASK_ID, "receipt.pdf", "application/pdf", source()),
            )
            assertEquals(
                AttachmentOpenResult.Unavailable,
                unconfigured.open(attachment(blobSetId = BLOB_SET), ByteArrayOutputStream()),
            )
            assertEquals(0, unconfigured.expireStaleSessions())
            assertEquals(NOTHING_COLLECTED, unconfigured.collectRetiredBytes())

            assertEquals(0, sessionsOpened)
            assertEquals(emptyList<String>(), store.calls)
        }
    }

    /** Disconnecting is what a dormant lineage is; it discards nothing. */
    @Test
    fun aDormantLineageKeepsItsRecordsAndCachedFramesUntilEvictionIsAsked() = runBlocking {
        withTimeout(5_000) {
            retire(BLOB_SET, DELETED_AT)
            cache.write(BLOB_SET, 0, byteArrayOf(1, 2, 3))
            val runtime = runtime(
                lifecycle = RemoteBackupLifecycle.DORMANT,
                now = COLLECTABLE_AT,
            )

            assertEquals(
                AttachmentOpenResult.Unavailable,
                runtime.open(attachment(blobSetId = BLOB_SET), ByteArrayOutputStream()),
            )

            assertEquals(3L, runtime.cacheUsageBytes())
            assertEquals(1, repository.currentWorkspace().attachments.size)

            runtime.evictCachedBytes(BLOB_SET)

            assertEquals(0L, runtime.cacheUsageBytes())
            assertEquals(1, repository.currentWorkspace().attachments.size)
        }
    }

    @Test
    fun aChainTipThisInstallationNoLongerHoldsRefusesIntakeAndCollection() = runBlocking {
        withTimeout(5_000) {
            retire(BLOB_SET, DELETED_AT)
            store.publish(BLOB_SET, "chunk-0", CHUNK_ROLE)
            store.publish(BLOB_SET, "manifest", MANIFEST_ROLE)
            chainTip = SUCCESSOR_TIP
            val runtime = runtime(now = COLLECTABLE_AT)

            assertEquals(
                AttachmentIntakeResult.OwnershipUnavailable,
                runtime.intake(TASK_ID, "receipt.pdf", "application/pdf", source()),
            )
            assertEquals(STOPPED_FOR_OWNERSHIP_CHANGE, runtime.collectRetiredBytes())

            assertEquals(emptyList<String>(), store.calls)
            assertEquals(sessionsOpened, sessionsClosed)
        }
    }

    /**
     * Collection releases the bytes and records that it did, so the same
     * record is never offered — or paid a provider round trip for — again.
     */
    @Test
    fun collectingARetiredBlobSetReleasesItsBytesAndRetiresItAsACandidate() = runBlocking {
        withTimeout(5_000) {
            val attachmentId = retire(BLOB_SET, DELETED_AT)
            store.publish(BLOB_SET, "manifest", MANIFEST_ROLE)
            store.publish(BLOB_SET, "chunk-0", CHUNK_ROLE)
            val runtime = runtime(now = COLLECTABLE_AT)

            val result = runtime.collectRetiredBytes()

            assertEquals(2, result.deletedObjects)
            assertEquals(listOf("chunk-0", "manifest"), store.deleted)
            assertEquals(1, sessionsOpened)
            assertEquals(1, sessionsClosed)

            // The record survives collection; only its link to the released
            // bytes is gone, which is what stops it qualifying again.
            val collected = repository.currentWorkspace().attachments.single()
            assertEquals(attachmentId, collected.id)
            assertNull(collected.blobSetId)
            assertNotNull(collected.deletedAt)
            assertEquals("b".repeat(64), collected.contentHash)
            assertEquals("receipt.pdf", collected.displayName)

            assertEquals(NOTHING_COLLECTED, runtime.collectRetiredBytes())
            assertEquals(1, sessionsOpened)
        }
    }

    /**
     * A blob set the lineage never listed was not released by this batch — a
     * separately preserved lineage still holds those bytes under its own
     * namespace tag.
     */
    @Test
    fun aBlobSetThisLineageNeverHeldIsNeverRecordedAsCollected() = runBlocking {
        withTimeout(5_000) {
            retire(BLOB_SET, DELETED_AT)
            val foreign = retire(FOREIGN_BLOB_SET, DELETED_AT)
            store.publish(BLOB_SET, "chunk-0", CHUNK_ROLE)
            store.publish(BLOB_SET, "manifest", MANIFEST_ROLE)

            val result = runtime(now = COLLECTABLE_AT).collectRetiredBytes()

            assertEquals(2, result.deletedObjects)
            assertEquals(setOf(BLOB_SET), result.collectedBlobSets)
            val untouched = repository.currentWorkspace().attachments.single { it.id == foreign }
            assertEquals(FOREIGN_BLOB_SET, untouched.blobSetId)
        }
    }

    @Test
    fun aTombstoneTheFallbackBaseDoesNotYetCoverOpensNoSession() = runBlocking {
        withTimeout(5_000) {
            retire(BLOB_SET, DELETED_AT)
            store.publish(BLOB_SET, "chunk-0", CHUNK_ROLE)

            val result = runtime(
                previousGeneration = TOMBSTONE_GENERATION - 1,
                now = COLLECTABLE_AT,
            ).collectRetiredBytes()

            assertEquals(NOTHING_COLLECTED, result)
            assertEquals(0, sessionsOpened)
            assertEquals(emptyList<String>(), store.deleted)
        }
    }

    /** The whole retention window costs no authorization and no listing. */
    @Test
    fun aRetiredBlobSetInsideItsRetentionWindowOpensNoSession() = runBlocking {
        withTimeout(5_000) {
            retire(BLOB_SET, DELETED_AT)
            store.publish(BLOB_SET, "chunk-0", CHUNK_ROLE)

            val result = runtime(
                now = DELETED_AT.plus(Duration.ofDays(29)),
            ).collectRetiredBytes()

            assertEquals(NOTHING_COLLECTED, result)
            assertEquals(0, sessionsOpened)
            assertEquals(emptyList<String>(), store.calls)
        }
    }

    @Test
    fun aBlobSetAnotherLiveRecordStillReferencesOpensNoSession() = runBlocking {
        withTimeout(5_000) {
            retire(BLOB_SET, DELETED_AT)
            assertTrue(
                repository.execute(
                    DomainCommand.RegisterAttachment(attachment(blobSetId = BLOB_SET)),
                ) is CommandResult.Success,
            )
            store.publish(BLOB_SET, "chunk-0", CHUNK_ROLE)

            val result = runtime(now = COLLECTABLE_AT).collectRetiredBytes()

            assertEquals(NOTHING_COLLECTED, result)
            assertEquals(0, sessionsOpened)
            assertEquals(emptyList<String>(), store.deleted)
        }
    }

    private fun runtime(
        lifecycle: RemoteBackupLifecycle = RemoteBackupLifecycle.ACTIVE,
        configured: Boolean = true,
        previousGeneration: Long = TOMBSTONE_GENERATION,
        now: Instant = DELETED_AT,
    ) = AttachmentRuntime(
        vaultId = VAULT_ID,
        repository = repository,
        remoteStateStore = FakeStateStore(
            if (configured) configuration(lifecycle, previousGeneration) else null,
        ),
        transferDao = transferDao,
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
                    ownershipChainStore = FakeChainStore { chainTip },
                ) { sessionsClosed += 1 },
            )
        },
        now = { now },
    )

    /** Registers an attachment for [blobSetId] and retires it at [deletedAt]. */
    private suspend fun retire(blobSetId: BlobSetId, deletedAt: Instant): AttachmentId {
        val registered = attachment(blobSetId = blobSetId)
        assertTrue(
            repository.execute(
                DomainCommand.RegisterAttachment(registered),
            ) is CommandResult.Success,
        )
        assertTrue(
            repository.execute(
                DomainCommand.DeleteAttachment(registered.id, deletedAt),
            ) is CommandResult.Success,
        )
        journalDao.generations[registered.id.value] = TOMBSTONE_GENERATION
        return registered.id
    }

    private fun attachment(
        id: AttachmentId = AttachmentId.new(),
        blobSetId: BlobSetId,
    ) = Attachment(
        id = id,
        taskId = TASK_ID,
        displayName = "receipt.pdf",
        mimeType = "application/pdf",
        byteCount = 1_024,
        contentHash = "b".repeat(64),
        blobSetId = blobSetId,
        chunkCount = 1,
        deletedAt = null,
        revision = Revision(
            deviceId = DeviceId("attachment-boundary"),
            wallTimeMillis = 1,
            logicalCounter = 0,
        ),
    )

    private fun source() = object : AttachmentSource {
        override val declaredByteCount: Long = 4
        override fun open(): InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
    }

    private fun configuration(
        lifecycle: RemoteBackupLifecycle,
        previousGeneration: Long,
    ) = RemoteBackupConfiguration(
        lineageId = LINEAGE_ID,
        vaultId = VAULT_ID,
        rootClaimProviderId = ROOT_CLAIM,
        accountBindingDigest = ByteArray(32) { (it + 1).toByte() },
        lifecycle = lifecycle,
        activeDeviceId = CloudDeviceId.parse("33333333-3333-4333-8333-333333333333"),
        writerEpoch = WriterEpoch(1),
        ownershipClaim = OwnershipClaimRef(
            providerId = ROOT_CLAIM,
            logicalId = OwnershipClaimId.parse("44444444-4444-4444-8444-444444444444"),
            sha256 = Sha256Digest.of(EXPECTED_TIP),
            writerEpoch = WriterEpoch(1),
        ),
        nextSuccessorProviderId = null,
        currentPublication = publication(TOMBSTONE_GENERATION, 2),
        previousPublication = publication(previousGeneration, 1),
        lastVerifiedGeneration = BackupGeneration(TOMBSTONE_GENERATION),
        lastVerifiedAt = DELETED_AT,
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

    private class RecordingBlobStore : AttachmentBlobStore {
        private val objects = mutableListOf<AttachmentListedObject>()
        val calls = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        fun publish(blobSetId: BlobSetId, providerId: String, role: String) {
            objects += AttachmentListedObject(
                providerObjectId = ProviderObjectId.of(providerId),
                role = role,
                blobSetId = blobSetId.value,
                createdAtEpochMillis = null,
            )
        }

        override suspend fun generateObjectIds(count: Int): List<ProviderObjectId> {
            calls += "generateObjectIds"
            return (0 until count).map { ProviderObjectId.of("generated-$it") }
        }

        override suspend fun createChunk(
            providerObjectId: ProviderObjectId,
            blobSetId: BlobSetId,
            chunkIndex: Int,
            chunkCount: Int,
            frameBytes: ByteArray,
        ): AttachmentObjectResult {
            calls += "createChunk"
            return AttachmentObjectResult.Created
        }

        override suspend fun readObject(
            providerObjectId: ProviderObjectId,
            maximumBytes: Long,
        ): AttachmentReadResult {
            calls += "readObject"
            return AttachmentReadResult.Missing
        }

        override suspend fun createManifest(
            providerObjectId: ProviderObjectId,
            blobSetId: BlobSetId,
            frameBytes: ByteArray,
        ): AttachmentObjectResult {
            calls += "createManifest"
            return AttachmentObjectResult.Created
        }

        override suspend fun findManifest(blobSetId: BlobSetId): AttachmentManifestLookup {
            calls += "findManifest"
            return AttachmentManifestLookup.Missing
        }

        override suspend fun listNamespace(
            pageToken: String?,
            exactRole: String?,
        ): Pair<List<AttachmentListedObject>, String?> {
            calls += "listNamespace"
            return objects.filter { exactRole == null || it.role == exactRole } to null
        }

        override suspend fun delete(providerObjectId: ProviderObjectId): Boolean {
            calls += "delete"
            deleted += providerObjectId.value
            objects.removeAll { it.providerObjectId == providerObjectId }
            return true
        }
    }

    private class FakeChainStore(
        private val tip: () -> String,
    ) : OwnershipChainStore {
        override suspend fun discoverPublicRoots() = error("Discovery is not part of this boundary")

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
                completeSha256 = Sha256Digest.of(tip()),
            )
            return OwnershipResolution.Active(verified, verified)
        }

        override suspend fun createClaim(
            expectedPredecessor: VerifiedOwnershipClaim?,
            encodedClaim: OwnedRemoteBytes,
            contentKey: VaultKey,
        ) = error("Claim creation is not part of this boundary")
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
            error("The boundary never configures a lineage")

        override suspend fun compareAndSet(
            lineageId: CloudLineageId,
            expected: RemoteBackupStateVersion,
            next: RemoteBackupConfiguration,
        ): Boolean = error("The boundary never writes configuration")

        override suspend fun operation(operationId: String): RemoteBackupOperation? = null

        override suspend fun putOperation(operation: RemoteBackupOperation) =
            error("The boundary records no operation")

        override suspend fun transitionOperation(
            operationId: String,
            expectedPhase: String,
            next: RemoteBackupOperation,
        ): Boolean = error("The boundary records no operation")
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

    private class FakeJournalDao : BackupJournalDao {
        val generations = mutableMapOf<String, Long>()

        override suspend fun insert(entity: BackupJournalEntity) =
            error("The boundary appends no journal entry")

        override suspend fun latestGenerationFor(objectType: String, objectId: String): Long? =
            generations[objectId]
                ?.takeIf { objectType == BackupRecordFamily.ATTACHMENT.name }

        override suspend fun after(
            vaultId: String,
            generation: Long,
            limit: Int,
        ): List<BackupJournalEntity> = error("The boundary reads no journal page")

        override suspend fun between(
            vaultId: String,
            firstGeneration: Long,
            lastGeneration: Long,
        ): List<BackupJournalEntity> = error("The boundary reads no journal range")

        override suspend fun countThrough(
            vaultId: String,
            afterGeneration: Long,
            throughGeneration: Long,
            limit: Int,
        ): Int = error("The boundary counts no journal entry")
    }

    private class FreshKeyStore(private val crypto: VaultCrypto) : VaultContentKeyStore {
        override fun getOrCreate(vaultId: VaultId): VaultKey = openExisting(vaultId)

        override fun openExisting(vaultId: VaultId): VaultKey = crypto.createKey()

        override fun replace(vaultId: VaultId, key: VaultKey) =
            error("The boundary never replaces a content key")

        override fun delete(vaultId: VaultId) =
            error("The boundary never deletes a content key")
    }

    private companion object {
        const val CHUNK_ROLE = "attachment-chunk"
        const val MANIFEST_ROLE = "attachment-manifest"
        const val TOMBSTONE_GENERATION = 7L
        const val EXPECTED_TIP =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SUCCESSOR_TIP =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        val VAULT_ID = VaultId("11111111-1111-4111-8111-111111111111")
        val LINEAGE_ID = CloudLineageId.parse("22222222-2222-4222-8222-222222222222")
        val ROOT_CLAIM = ProviderObjectId.of("root-claim")
        val BLOB_SET = BlobSetId("66666666-6666-4666-8666-666666666666")
        val FOREIGN_BLOB_SET = BlobSetId("77777777-7777-4777-8777-777777777777")
        val TASK_ID = OpenTasksFixtures.tasks.first().id
        val DELETED_AT: Instant = Instant.parse("2026-06-01T00:00:00Z")
        val COLLECTABLE_AT: Instant = DELETED_AT.plus(Duration.ofDays(31))
        val NOTHING_COLLECTED = AttachmentGcResult(0, false, 0)
        val STOPPED_FOR_OWNERSHIP_CHANGE = AttachmentGcResult(0, true, 0)
    }
}
