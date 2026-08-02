package app.opentasks.core.data.backup

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.InMemoryVaultRepository
import app.opentasks.core.data.db.AttachmentTransferDao
import app.opentasks.core.data.db.AttachmentTransferEntity
import app.opentasks.core.domain.AttachmentBlobStore
import app.opentasks.core.domain.AttachmentObjectResult
import app.opentasks.core.domain.AttachmentReadResult
import app.opentasks.core.domain.AttachmentManifestLookup
import app.opentasks.core.domain.AttachmentListedObject
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentIntakeTest {
    private val crypto = TinkVaultCrypto()
    private val key = crypto.createKey()
    private val authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto)
    private val repository = InMemoryVaultRepository()
    private val dao = FakeAttachmentTransferDao()
    private val store = FakeAttachmentBlobStore()
    private var clock = Instant.parse("2026-08-02T00:00:00Z")
    private val ownership = ArrayDeque<Boolean>()
    private val coordinator = AttachmentBlobCoordinator(
        repository = repository,
        transferDao = dao,
        codec = authenticatedCodec,
        manifestCodec = AttachmentBlobSetManifestCodec(authenticatedCodec),
        lineageId = LINEAGE,
        contentKey = { key },
        holdsOwnership = { ownership.pollFirst() ?: true },
        now = { clock },
    )

    @After
    fun closeKey() = key.close()

    @Test
    fun nineMiBSourceRegistersThreeVerifiedChunksAndManifest() = runBlocking {
        withTimeout(5_000) {
            val bytes = bytes(9 * MIB)

            val result = coordinator.intake(
                store,
                OpenTasksFixtures.tasks.first().id,
                "receipt.pdf",
                "application/pdf",
                ByteArrayAttachmentSource(bytes),
            )

            assertTrue(result is AttachmentIntakeResult.Registered)
            assertEquals(3, store.chunkCreateCalls.size)
            assertEquals(1, store.manifestCreateCalls.size)
            assertEquals(4, store.readCalls.size)
            val attachment = repository.currentWorkspace().attachments.single()
            assertEquals("receipt.pdf", attachment.displayName)
            assertEquals(bytes.size.toLong(), attachment.byteCount)
            assertEquals(sha256(bytes), attachment.contentHash)
            assertEquals(3, attachment.chunkCount)
            assertEquals(
                listOf("PLANNED", "UPLOADING", "CHUNKS_VERIFIED", "MANIFEST_CREATED", "REGISTERED"),
                dao.distinctPhases,
            )
        }
    }

    @Test
    fun registeredSessionIsNeverExpiredAsFailedTransferResidue() = runBlocking {
        withTimeout(5_000) {
            val result = coordinator.intake(
                store,
                OpenTasksFixtures.tasks.first().id,
                "kept.bin",
                "application/octet-stream",
                ByteArrayAttachmentSource(bytes(MIB)),
            )
            assertTrue(result is AttachmentIntakeResult.Registered)
            clock = clock.plus(Duration.ofHours(25))

            assertEquals(0, coordinator.expireStaleSessions(store))

            assertTrue(store.deleteCalls.isEmpty())
            assertEquals("REGISTERED", dao.rows.values.single().phase)
        }
    }

    @Test
    fun streamLargerThanDeclaredFailsAndRemainsNonPublishableUntilExpiry() = runBlocking {
        withTimeout(5_000) {
            val result = coordinator.intake(
                store,
                OpenTasksFixtures.tasks.first().id,
                "lying.bin",
                "application/octet-stream",
                ByteArrayAttachmentSource(byteArrayOf(1, 2), declaredByteCount = 1),
            )

            assertEquals(
                AttachmentIntakeResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE),
                result,
            )
            assertTrue(repository.currentWorkspace().attachments.isEmpty())
            assertEquals(1, dao.rows.size)
            assertEquals(0, coordinator.resume(store))

            clock = clock.plus(Duration.ofHours(25))
            assertEquals(1, coordinator.expireStaleSessions(store))
            assertTrue(dao.rows.isEmpty())
        }
    }

    @Test
    fun sourceAboveOneHundredMiBIsRefusedBeforeSourceOrStoreCall() = runBlocking {
        withTimeout(5_000) {
            val source = ByteArrayAttachmentSource(
                byteArrayOf(1),
                declaredByteCount = 100L * MIB + 1,
            )

            assertEquals(
                AttachmentIntakeResult.TooLarge,
                coordinator.intake(
                    store,
                    OpenTasksFixtures.tasks.first().id,
                    "large.bin",
                    "application/octet-stream",
                    source,
                ),
            )

            assertEquals(0, source.openCalls)
            assertEquals(0, store.totalCalls)
            assertTrue(dao.rows.isEmpty())
        }
    }

    @Test
    fun mismatchedSecondChunkReadbackFailsClosedBeforeManifestOrRegistration() = runBlocking {
        withTimeout(5_000) {
            store.corruptReadIds += ProviderObjectId.of("provider-1")

            val result = coordinator.intake(
                store,
                OpenTasksFixtures.tasks.first().id,
                "archive.bin",
                "application/octet-stream",
                ByteArrayAttachmentSource(bytes(5 * MIB)),
            )

            assertEquals(
                AttachmentIntakeResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE),
                result,
            )
            assertEquals(2, store.chunkCreateCalls.size)
            assertTrue(store.manifestCreateCalls.isEmpty())
            assertTrue(repository.currentWorkspace().attachments.isEmpty())
            assertEquals("UPLOADING", dao.rows.values.single().phase)
        }
    }

    @Test
    fun resumeAdoptsMatchingOccupiedChunksWithoutDuplicateCreates() = runBlocking {
        withTimeout(5_000) {
            store.readFailure = RemoteBackupFailureCategory.RETRYABLE_PROVIDER
            val intakeResult = coordinator.intake(
                store,
                OpenTasksFixtures.tasks.first().id,
                "resume.bin",
                "application/octet-stream",
                ByteArrayAttachmentSource(bytes(5 * MIB)),
            )
            assertEquals(
                AttachmentIntakeResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
                intakeResult,
            )
            assertEquals("UPLOADING", dao.rows.values.single().phase)
            val createsBeforeResume = store.chunkCreateCalls.size
            store.readFailure = null

            assertEquals(1, coordinator.resume(store))

            assertEquals(createsBeforeResume, store.chunkCreateCalls.size)
            assertEquals(1, store.manifestCreateCalls.size)
            assertEquals(1, repository.currentWorkspace().attachments.size)
            assertEquals("REGISTERED", dao.rows.values.single().phase)
        }
    }

    @Test
    fun resumeReconstructsMissingContentHashAfterFinalChunkCreateCrash() = runBlocking {
        withTimeout(5_000) {
            val content = bytes(MIB)
            store.crashAfterChunkCreate = 1
            try {
                coordinator.intake(
                    store,
                    OpenTasksFixtures.tasks.first().id,
                    "hash-crash.bin",
                    "application/octet-stream",
                    ByteArrayAttachmentSource(content),
                )
                fail("Expected simulated process death")
            } catch (_: SimulatedCrash) {
                // Expected: remote create committed before local hash persistence.
            }
            assertEquals(null, dao.rows.values.single().contentHash)
            assertEquals(1, store.objects.size)
            store.crashAfterChunkCreate = null

            assertEquals(1, coordinator.resume(store))

            assertEquals(sha256(content), repository.currentWorkspace().attachments.single().contentHash)
            assertEquals(1, store.chunkCreateCalls.size)
        }
    }

    @Test
    fun lyingSourceIsRejectedBeforeFinalChunkCreateCanCommit() = runBlocking {
        withTimeout(5_000) {
            store.crashAfterChunkCreate = 1

            val result = try {
                coordinator.intake(
                    store,
                    OpenTasksFixtures.tasks.first().id,
                    "lying-crash.bin",
                    "application/octet-stream",
                    ByteArrayAttachmentSource(byteArrayOf(1, 2), declaredByteCount = 1),
                )
            } catch (_: SimulatedCrash) {
                fail("Final chunk committed before the declared-length proof")
                return@withTimeout
            }

            assertEquals(
                AttachmentIntakeResult.Failed(
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                ),
                result,
            )
            assertTrue(store.chunkCreateCalls.isEmpty())
            assertEquals(0, coordinator.resume(store))
        }
    }

    @Test
    fun registrationReplayAfterDaoCrashDoesNotDuplicateAttachmentActivity() = runBlocking {
        withTimeout(5_000) {
            val activitiesBefore = repository.currentWorkspace().activityEntries.size
            dao.crashOnPhase = "REGISTERED"
            try {
                coordinator.intake(
                    store,
                    OpenTasksFixtures.tasks.first().id,
                    "register-crash.bin",
                    "application/octet-stream",
                    ByteArrayAttachmentSource(bytes(MIB)),
                )
                fail("Expected simulated durable-state failure")
            } catch (_: SimulatedCrash) {
                // Expected: repository commit succeeded before phase persistence.
            }
            assertEquals("MANIFEST_CREATED", dao.rows.values.single().phase)
            assertEquals(activitiesBefore + 1, repository.currentWorkspace().activityEntries.size)
            val registered = repository.currentWorkspace().attachments.single()
            dao.crashOnPhase = null

            assertEquals(1, coordinator.resume(store))

            assertEquals(activitiesBefore + 1, repository.currentWorkspace().activityEntries.size)
            assertEquals(registered, repository.currentWorkspace().attachments.single())
            assertEquals("REGISTERED", dao.rows.values.single().phase)
        }
    }

    @Test
    fun ownershipLossBeforeManifestAbandonsSessionWithoutPublishingMetadata() = runBlocking {
        withTimeout(5_000) {
            ownership += true
            ownership += false

            val result = coordinator.intake(
                store,
                OpenTasksFixtures.tasks.first().id,
                "owned.bin",
                "application/octet-stream",
                ByteArrayAttachmentSource(bytes(MIB)),
            )

            assertEquals(AttachmentIntakeResult.OwnershipUnavailable, result)
            assertEquals(1, store.chunkCreateCalls.size)
            assertTrue(store.manifestCreateCalls.isEmpty())
            assertTrue(repository.currentWorkspace().attachments.isEmpty())
            assertEquals("CHUNKS_VERIFIED", dao.rows.values.single().phase)
        }
    }

    @Test
    fun expiryDeletesOnlyAuthenticatedExactSessionIdsAndNeverForeignObjects() = runBlocking {
        withTimeout(5_000) {
            store.failManifestReadback = true
            assertEquals(
                AttachmentIntakeResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
                coordinator.intake(
                    store,
                    OpenTasksFixtures.tasks.first().id,
                    "stale.bin",
                    "application/octet-stream",
                    ByteArrayAttachmentSource(bytes(MIB)),
                ),
            )
            val sessionIds = store.objects.keys.toSet()
            val foreignId = ProviderObjectId.of("foreign-object")
            store.objects[foreignId] = byteArrayOf(9)
            store.failManifestReadback = false
            clock = clock.plus(Duration.ofHours(25))

            assertEquals(1, coordinator.expireStaleSessions(store))

            assertEquals(sessionIds, store.deleteCalls.toSet())
            assertTrue(store.objects.containsKey(foreignId))
            assertFalse(store.deleteCalls.contains(foreignId))
            assertTrue(dao.rows.isEmpty())
        }
    }

    @Test
    fun expiryFailsClosedForMalformedStateOrMismatchedOwnedBytes() = runBlocking {
        withTimeout(5_000) {
            val stale = clock.minus(Duration.ofHours(25)).toEpochMilli()
            dao.upsert(
                transfer(
                    blobSetId = "malformed",
                    chunkStateEncoded = "not-valid-state",
                    createdAtEpochMillis = stale,
                    updatedAtEpochMillis = stale,
                ),
            )
            store.corruptReadIds += ProviderObjectId.of("provider-0")
            store.failManifestReadback = true
            coordinator.intake(
                store,
                OpenTasksFixtures.tasks.first().id,
                "mismatch.bin",
                "application/octet-stream",
                ByteArrayAttachmentSource(bytes(MIB)),
            )
            clock = clock.plus(Duration.ofHours(25))

            assertEquals(0, coordinator.expireStaleSessions(store))

            assertTrue(store.deleteCalls.isEmpty())
            assertEquals(2, dao.rows.size)
        }
    }

    @Test
    fun expiryFailsClosedForMalformedPersistedContentDigest() = runBlocking {
        withTimeout(5_000) {
            store.failManifestReadback = true
            coordinator.intake(
                store,
                OpenTasksFixtures.tasks.first().id,
                "digest.bin",
                "application/octet-stream",
                ByteArrayAttachmentSource(bytes(MIB)),
            )
            val row = dao.rows.values.single()
            dao.upsert(row.copy(contentHash = "not-a-digest"))
            store.failManifestReadback = false
            clock = clock.plus(Duration.ofHours(25))

            assertEquals(0, coordinator.expireStaleSessions(store))

            assertTrue(store.deleteCalls.isEmpty())
            assertEquals(1, dao.rows.size)
        }
    }

    @Test
    fun expiryRejectsUnknownPersistedPhaseWithoutRemoteWork() = runBlocking {
        withTimeout(5_000) {
            ownership += true
            ownership += false
            coordinator.intake(
                store,
                OpenTasksFixtures.tasks.first().id,
                "future.bin",
                "application/octet-stream",
                ByteArrayAttachmentSource(bytes(MIB)),
            )
            dao.upsert(dao.rows.values.single().copy(phase = "FUTURE_PHASE"))
            store.readCalls.clear()
            clock = clock.plus(Duration.ofHours(25))

            assertEquals(0, coordinator.expireStaleSessions(store))

            assertTrue(store.readCalls.isEmpty())
            assertTrue(store.deleteCalls.isEmpty())
            assertEquals(1, dao.rows.size)
        }
    }

    @Test
    fun expiryRejectsPhaseStateThatSkipsDurableSequence() = runBlocking {
        withTimeout(5_000) {
            ownership += true
            ownership += false
            coordinator.intake(
                store,
                OpenTasksFixtures.tasks.first().id,
                "impossible.bin",
                "application/octet-stream",
                ByteArrayAttachmentSource(bytes(MIB)),
            )
            dao.upsert(dao.rows.values.single().copy(phase = "PLANNED"))
            store.readCalls.clear()
            clock = clock.plus(Duration.ofHours(25))

            assertEquals(0, coordinator.expireStaleSessions(store))

            assertTrue(store.readCalls.isEmpty())
            assertTrue(store.deleteCalls.isEmpty())
            assertEquals(1, dao.rows.size)
        }
    }

    @Test
    fun traversalControlsWhitespaceAndLengthAreSanitizedExactly() {
        assertEquals("etc_passwd", sanitizeAttachmentDisplayName("../../etc/passwd"))
        assertEquals("hidden.txt", sanitizeAttachmentDisplayName("   ...hidden.txt"))
        assertEquals("my file_name", sanitizeAttachmentDisplayName("...my\u0000   file\\name"))
        assertEquals("attachment", sanitizeAttachmentDisplayName(".../\\\u0000\t"))
        assertEquals("x".repeat(255), sanitizeAttachmentDisplayName("x".repeat(300)))
    }

    @Test
    fun unavailableOrShortSourceNeverRegistersMetadata() = runBlocking {
        withTimeout(5_000) {
            val unavailable = ByteArrayAttachmentSource(byteArrayOf(1), unavailable = true)
            assertEquals(
                AttachmentIntakeResult.SourceUnavailable,
                coordinator.intake(
                    store,
                    OpenTasksFixtures.tasks.first().id,
                    "missing.bin",
                    "application/octet-stream",
                    unavailable,
                ),
            )
            assertEquals(0, store.totalCalls)

            assertEquals(
                AttachmentIntakeResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE),
                coordinator.intake(
                    store,
                    OpenTasksFixtures.tasks.first().id,
                    "short.bin",
                    "application/octet-stream",
                    ByteArrayAttachmentSource(byteArrayOf(1), declaredByteCount = 2),
                ),
            )
            assertTrue(repository.currentWorkspace().attachments.isEmpty())
        }
    }

    @Test
    fun closeFailureReturnsSourceUnavailableInsteadOfEscaping() = runBlocking {
        withTimeout(5_000) {
            val result = try {
                coordinator.intake(
                    store,
                    OpenTasksFixtures.tasks.first().id,
                    "close.bin",
                    "application/octet-stream",
                    CloseFailingAttachmentSource(bytes(MIB)),
                )
            } catch (_: IOException) {
                fail("Source close failure escaped the sealed result API")
                return@withTimeout
            }

            assertEquals(AttachmentIntakeResult.SourceUnavailable, result)
        }
    }

    @Test
    fun lateLengthProbeFailureReturnsSourceUnavailableInsteadOfEscaping() = runBlocking {
        withTimeout(5_000) {
            val result = try {
                coordinator.intake(
                    store,
                    OpenTasksFixtures.tasks.first().id,
                    "probe.bin",
                    "application/octet-stream",
                    ProbeFailingAttachmentSource(),
                )
            } catch (_: IOException) {
                fail("Late source probe failure escaped the sealed result API")
                return@withTimeout
            }

            assertEquals(AttachmentIntakeResult.SourceUnavailable, result)
            assertTrue(repository.currentWorkspace().attachments.isEmpty())
        }
    }

    private fun transfer(
        blobSetId: String,
        chunkStateEncoded: String,
        createdAtEpochMillis: Long,
        updatedAtEpochMillis: Long,
    ) = AttachmentTransferEntity(
        blobSetId = blobSetId,
        attachmentId = "attachment-$blobSetId",
        taskId = OpenTasksFixtures.tasks.first().id.value,
        phase = "UPLOADING",
        displayNameCiphertext = "file.bin".toByteArray(),
        mimeType = "application/octet-stream",
        declaredByteCount = 1,
        contentHash = null,
        chunkCount = 1,
        chunkStateEncoded = chunkStateEncoded,
        manifestProviderFileId = "manifest-$blobSetId",
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private companion object {
        const val MIB = 1024 * 1024
        val LINEAGE = CloudLineageId.parse("11111111-1111-1111-8111-111111111111")
    }
}

private class ByteArrayAttachmentSource(
    private val bytes: ByteArray,
    override val declaredByteCount: Long = bytes.size.toLong(),
    private val unavailable: Boolean = false,
) : AttachmentSource {
    var openCalls = 0

    override fun open() = if (unavailable) {
        openCalls += 1
        throw IOException("unavailable")
    } else {
        openCalls += 1
        ByteArrayInputStream(bytes)
    }
}

private class CloseFailingAttachmentSource(
    private val bytes: ByteArray,
) : AttachmentSource {
    override val declaredByteCount = bytes.size.toLong()

    override fun open() = object : ByteArrayInputStream(bytes) {
        override fun close() = throw IOException("close failed")
    }
}

private class ProbeFailingAttachmentSource : AttachmentSource {
    override val declaredByteCount = 1L

    override fun open() = object : ByteArrayInputStream(byteArrayOf(1, 2)) {
        private var bulkReads = 0

        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            bulkReads += 1
            if (bulkReads == 2) throw IOException("late probe failed")
            return super.read(destination, offset, length)
        }

        override fun skip(byteCount: Long): Long = throw IOException("late probe failed")
    }
}

private class SimulatedCrash : RuntimeException()

private class FakeAttachmentTransferDao : AttachmentTransferDao {
    val rows = linkedMapOf<String, AttachmentTransferEntity>()
    val distinctPhases = mutableListOf<String>()
    var crashOnPhase: String? = null

    override suspend fun upsert(value: AttachmentTransferEntity) {
        if (value.phase == crashOnPhase) throw SimulatedCrash()
        rows[value.blobSetId] = value.copy(displayNameCiphertext = value.displayNameCiphertext.copyOf())
        if (distinctPhases.lastOrNull() != value.phase) distinctPhases += value.phase
    }

    override suspend fun pending(): List<AttachmentTransferEntity> =
        rows.values.filter { it.phase != "REGISTERED" }

    override suspend fun stale(beforeEpochMillis: Long): List<AttachmentTransferEntity> =
        rows.values.filter {
            it.phase != "REGISTERED" && it.updatedAtEpochMillis < beforeEpochMillis
        }

    override suspend fun delete(blobSetId: String): Int = if (rows.remove(blobSetId) != null) 1 else 0
}

private class FakeAttachmentBlobStore : AttachmentBlobStore {
    data class ChunkCreateCall(
        val providerObjectId: ProviderObjectId,
        val blobSetId: BlobSetId,
        val chunkIndex: Int,
        val chunkCount: Int,
    )

    val objects = linkedMapOf<ProviderObjectId, ByteArray>()
    val chunkCreateCalls = mutableListOf<ChunkCreateCall>()
    val manifestCreateCalls = mutableListOf<ProviderObjectId>()
    val readCalls = mutableListOf<ProviderObjectId>()
    val deleteCalls = mutableListOf<ProviderObjectId>()
    val corruptReadIds = mutableSetOf<ProviderObjectId>()
    var readFailure: RemoteBackupFailureCategory? = null
    var failManifestReadback = false
    var crashAfterChunkCreate: Int? = null
    var totalCalls = 0
    private var nextId = 0
    private val manifestIds = mutableSetOf<ProviderObjectId>()

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
        chunkCreateCalls += ChunkCreateCall(providerObjectId, blobSetId, chunkIndex, chunkCount)
        if (objects.containsKey(providerObjectId)) return AttachmentObjectResult.AlreadyExists
        objects[providerObjectId] = frameBytes.copyOf()
        if (chunkCreateCalls.size == crashAfterChunkCreate) throw SimulatedCrash()
        return AttachmentObjectResult.Created
    }

    override suspend fun readObject(
        providerObjectId: ProviderObjectId,
        maximumBytes: Long,
    ): AttachmentReadResult {
        totalCalls += 1
        readCalls += providerObjectId
        readFailure?.let { return AttachmentReadResult.Failed(it) }
        if (failManifestReadback && providerObjectId in manifestIds) {
            return AttachmentReadResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
        }
        val bytes = objects[providerObjectId] ?: return AttachmentReadResult.Missing
        val returned = bytes.copyOf()
        if (providerObjectId in corruptReadIds && returned.isNotEmpty()) {
            returned[returned.lastIndex] = (returned.last().toInt() xor 1).toByte()
        }
        return AttachmentReadResult.Found(returned)
    }

    override suspend fun createManifest(
        providerObjectId: ProviderObjectId,
        blobSetId: BlobSetId,
        frameBytes: ByteArray,
    ): AttachmentObjectResult {
        totalCalls += 1
        manifestCreateCalls += providerObjectId
        manifestIds += providerObjectId
        if (objects.containsKey(providerObjectId)) return AttachmentObjectResult.AlreadyExists
        objects[providerObjectId] = frameBytes.copyOf()
        return AttachmentObjectResult.Created
    }

    override suspend fun findManifest(blobSetId: BlobSetId): AttachmentManifestLookup =
        AttachmentManifestLookup.Missing

    override suspend fun listNamespace(
        pageToken: String?,
    ): Pair<List<AttachmentListedObject>, String?> = emptyList<AttachmentListedObject>() to null

    override suspend fun delete(providerObjectId: ProviderObjectId): Boolean {
        totalCalls += 1
        deleteCalls += providerObjectId
        return objects.remove(providerObjectId) != null
    }
}

private fun bytes(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        "%02x".format(byte)
    }
