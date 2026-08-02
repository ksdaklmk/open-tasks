package app.opentasks.core.data.backup

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.domain.AttachmentBlobSetManifest
import app.opentasks.core.domain.AttachmentBlobStore
import app.opentasks.core.domain.AttachmentChunkRef
import app.opentasks.core.domain.AttachmentListedObject
import app.opentasks.core.domain.AttachmentManifestLookup
import app.opentasks.core.domain.AttachmentObjectResult
import app.opentasks.core.domain.AttachmentReadResult
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.Revision
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentOpenCoordinatorTest {
    private val crypto = TinkVaultCrypto()
    private val key = crypto.createKey()
    private val codec = DefaultAuthenticatedCloudObjectCodec(crypto)
    private val manifestCodec = AttachmentBlobSetManifestCodec(codec)

    @After
    fun closeKey() = key.close()

    @Test
    fun validManifestAndChunksStreamTheAttachment() = runOpenTest { cache ->
        val fixture = fixture(listOf("hello ".toByteArray(), "world".toByteArray()))
        val output = ByteArrayOutputStream()

        val result = coordinator(cache).open(fixture.store, fixture.attachment, output)

        assertEquals(AttachmentOpenResult.Opened(11), result)
        assertArrayEquals("hello world".toByteArray(), output.toByteArray())
    }

    @Test
    fun cachedChunkDigestMismatchFailsClosedAndEvictsEntry() = runOpenTest { cache ->
        val fixture = fixture(listOf("trusted".toByteArray()))
        cache.write(BLOB_SET, 0, "corrupt".toByteArray())
        val output = ByteArrayOutputStream()

        val result = coordinator(cache).open(fixture.store, fixture.attachment, output)

        assertEquals(corrupt(), result)
        assertNull(cache.read(BLOB_SET, 0))
        assertEquals(0, output.size())
    }

    @Test
    fun missingManifestIsUnavailable() = runOpenTest { cache ->
        val store = FakeOpenBlobStore()
        val attachment = attachment(1, sha256(byteArrayOf(1)), 1)

        val result = coordinator(cache).open(store, attachment, ByteArrayOutputStream())

        assertEquals(AttachmentOpenResult.Unavailable, result)
    }

    @Test
    fun attachmentLengthMismatchFailsBeforeStreaming() = runOpenTest { cache ->
        val fixture = fixture(listOf("bytes".toByteArray()))
        val output = ByteArrayOutputStream()

        val result = coordinator(cache).open(
            fixture.store,
            fixture.attachment.copy(byteCount = 6),
            output,
        )

        assertEquals(corrupt(), result)
        assertEquals(0, output.size())
    }

    @Test
    fun cacheHitAvoidsChunkObjectRead() = runOpenTest { cache ->
        val fixture = fixture(listOf("cached".toByteArray()))
        cache.write(BLOB_SET, 0, fixture.chunkFrames.single())

        val result = coordinator(cache).open(
            fixture.store,
            fixture.attachment,
            ByteArrayOutputStream(),
        )

        assertEquals(AttachmentOpenResult.Opened(6), result)
        assertEquals(1, fixture.store.readCalls[MANIFEST_ID])
        assertNull(fixture.store.readCalls[CHUNK_IDS[0]])
    }

    @Test
    fun lateUnavailableResultLeavesPartialDestinationForCallerToDiscard() =
        runOpenTest { cache ->
            val fixture = fixture(listOf("first".toByteArray(), "missing".toByteArray()))
            fixture.store.objects.remove(CHUNK_IDS[1])
            val output = ByteArrayOutputStream()

            val result = coordinator(cache).open(fixture.store, fixture.attachment, output)

            assertEquals(AttachmentOpenResult.Unavailable, result)
            assertArrayEquals("first".toByteArray(), output.toByteArray())
        }

    private fun runOpenTest(block: suspend (AttachmentCacheStore) -> Unit) = runBlocking {
        withTimeout(5_000) {
            val root = Files.createTempDirectory("attachment-open-test").toFile()
            try {
                block(AttachmentCacheStore(root) { Long.MAX_VALUE })
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun coordinator(cache: AttachmentCacheStore) = AttachmentOpenCoordinator(
        cache = cache,
        manifestCodec = manifestCodec,
        codec = codec,
        lineageId = LINEAGE,
        contentKey = { key },
    )

    private fun fixture(chunks: List<ByteArray>): OpenFixture {
        val frames = chunks.mapIndexed { index, plaintext ->
            codec.encrypt(chunkIdentity(index, chunks.size), plaintext, key)
        }
        val content = chunks.fold(ByteArray(0)) { result, bytes -> result + bytes }
        val manifest = AttachmentBlobSetManifest(
            blobSetId = BLOB_SET,
            contentSha256 = Sha256Digest.of(sha256(content)),
            totalByteCount = content.size.toLong(),
            chunks = frames.mapIndexed { index, frame ->
                AttachmentChunkRef(
                    index = index,
                    providerObjectId = CHUNK_IDS[index],
                    ciphertextSha256 = Sha256Digest.of(sha256(frame)),
                    plaintextByteCount = chunks[index].size,
                )
            },
        )
        val store = FakeOpenBlobStore().apply {
            manifestId = MANIFEST_ID
            objects[MANIFEST_ID] = manifestCodec.encode(manifest, LINEAGE, key)
            frames.forEachIndexed { index, frame -> objects[CHUNK_IDS[index]] = frame.copyOf() }
        }
        return OpenFixture(
            store = store,
            attachment = attachment(content.size.toLong(), sha256(content), chunks.size),
            chunkFrames = frames,
        )
    }

    private fun attachment(byteCount: Long, contentHash: String, chunkCount: Int) = Attachment(
        id = AttachmentId("attachment-a"),
        taskId = OpenTasksFixtures.tasks.first().id,
        displayName = "file.bin",
        mimeType = "application/octet-stream",
        byteCount = byteCount,
        contentHash = contentHash,
        blobSetId = BLOB_SET,
        chunkCount = chunkCount,
        deletedAt = null,
        revision = Revision(DeviceId("device-a"), 1, 0),
    )

    private fun chunkIdentity(index: Int, count: Int) = CloudHeaderIdentity(
        family = CloudObjectFamily.ATTACHMENT_CHUNK,
        schemaVersion = 1,
        cryptoVersion = 1,
        minimumReaderVersion = 1,
        vaultId = LINEAGE.value,
        objectId = "attachment-chunk:${BLOB_SET.value}",
        chunkIndex = index,
        chunkCount = count,
    )

    private fun corrupt() = AttachmentOpenResult.Failed(
        RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
    )

    private data class OpenFixture(
        val store: FakeOpenBlobStore,
        val attachment: Attachment,
        val chunkFrames: List<ByteArray>,
    )

    private companion object {
        val LINEAGE = CloudLineageId.parse("11111111-1111-1111-8111-111111111111")
        val BLOB_SET = BlobSetId("blob-set-a")
        val MANIFEST_ID = ProviderObjectId.of("manifest-a")
        val CHUNK_IDS = List(2) { ProviderObjectId.of("chunk-$it") }
    }
}

private class FakeOpenBlobStore : AttachmentBlobStore {
    val objects = linkedMapOf<ProviderObjectId, ByteArray>()
    val readCalls = linkedMapOf<ProviderObjectId, Int>()
    var manifestId: ProviderObjectId? = null

    override suspend fun generateObjectIds(count: Int) = error("not used")

    override suspend fun createChunk(
        providerObjectId: ProviderObjectId,
        blobSetId: BlobSetId,
        chunkIndex: Int,
        chunkCount: Int,
        frameBytes: ByteArray,
    ) = error("not used")

    override suspend fun readObject(
        providerObjectId: ProviderObjectId,
        maximumBytes: Long,
    ): AttachmentReadResult {
        readCalls[providerObjectId] = (readCalls[providerObjectId] ?: 0) + 1
        return objects[providerObjectId]?.copyOf()?.let(AttachmentReadResult::Found)
            ?: AttachmentReadResult.Missing
    }

    override suspend fun createManifest(
        providerObjectId: ProviderObjectId,
        blobSetId: BlobSetId,
        frameBytes: ByteArray,
    ): AttachmentObjectResult = error("not used")

    override suspend fun findManifest(blobSetId: BlobSetId): AttachmentManifestLookup =
        manifestId?.let(AttachmentManifestLookup::Found) ?: AttachmentManifestLookup.Missing

    override suspend fun listNamespace(
        pageToken: String?,
    ): Pair<List<AttachmentListedObject>, String?> = emptyList<AttachmentListedObject>() to null

    override suspend fun delete(providerObjectId: ProviderObjectId) = error("not used")
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        "%02x".format(byte)
    }
