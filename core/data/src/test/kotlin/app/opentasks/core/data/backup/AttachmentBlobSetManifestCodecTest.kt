package app.opentasks.core.data.backup

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.domain.AttachmentBlobSetManifest
import app.opentasks.core.domain.AttachmentChunkRef
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.sync.CloudBounds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AttachmentBlobSetManifestCodecTest {
    private val crypto = TinkVaultCrypto()
    private val key = crypto.createKey()
    private val codec = AttachmentBlobSetManifestCodec(
        DefaultAuthenticatedCloudObjectCodec(crypto),
    )

    @After
    fun closeKey() = key.close()

    @Test
    fun canonicalManifestRoundTripsThroughItsAuthenticatedIdentity() {
        val manifest = manifest()

        val encoded = codec.encode(manifest, LINEAGE, key)

        assertEquals(manifest, codec.decode(encoded, LINEAGE, BLOB_SET, key))
    }

    @Test
    fun decodeRejectsAnotherLineageOrBlobSetIdentity() {
        val encoded = codec.encode(manifest(), LINEAGE, key)

        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(encoded, OTHER_LINEAGE, BLOB_SET, key)
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(encoded, LINEAGE, BlobSetId("other-blob-set"), key)
        }
    }

    @Test
    fun manifestRejectsChunkCountAndNonCanonicalIndexes() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(manifest(chunks = chunks(26)), LINEAGE, key)
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(
                manifest(chunks = chunks(2).mapIndexed { index, chunk ->
                    chunk.copy(index = index + 1)
                }),
                LINEAGE,
                key,
            )
        }
    }

    @Test
    fun manifestRejectsInvalidChunkSizesAndTotalSize() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(
                manifest(chunks = chunks(1).map { it.copy(plaintextByteCount = 0) }),
                LINEAGE,
                key,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(
                manifest(
                    totalByteCount = CloudBounds.MAX_ATTACHMENT_CHUNK_PLAINTEXT_BYTES + 1,
                    chunks = chunks(1).map {
                        it.copy(
                            plaintextByteCount =
                                (CloudBounds.MAX_ATTACHMENT_CHUNK_PLAINTEXT_BYTES + 1).toInt(),
                        )
                    },
                ),
                LINEAGE,
                key,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(
                manifest(totalByteCount = 2, chunks = chunks(1)),
                LINEAGE,
                key,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(
                manifest(
                    totalByteCount = 100L * 1024 * 1024 + 1,
                    chunks = chunks(25).map {
                        it.copy(plaintextByteCount = 4 * 1024 * 1024)
                    }.toMutableList().also { chunks ->
                        chunks[chunks.lastIndex] = chunks.last().copy(
                            plaintextByteCount = 4 * 1024 * 1024 + 1,
                        )
                    },
                ),
                LINEAGE,
                key,
            )
        }
    }

    private fun manifest(
        totalByteCount: Long = 1,
        chunks: List<AttachmentChunkRef> = chunks(1),
    ) = AttachmentBlobSetManifest(
        blobSetId = BLOB_SET,
        contentSha256 = digest('a'),
        totalByteCount = totalByteCount,
        chunks = chunks,
    )

    private fun chunks(count: Int): List<AttachmentChunkRef> =
        (0 until count).map { index ->
            AttachmentChunkRef(
                index = index,
                providerObjectId = ProviderObjectId.of("chunk-$index"),
                ciphertextSha256 = digest(('b'.code + index % 5).toChar()),
                plaintextByteCount = 1,
            )
        }

    private fun digest(character: Char) = Sha256Digest.of(character.toString().repeat(64))

    private companion object {
        val LINEAGE = CloudLineageId.parse("11111111-1111-1111-8111-111111111111")
        val OTHER_LINEAGE = CloudLineageId.parse("22222222-2222-2222-8222-222222222222")
        val BLOB_SET = BlobSetId("blob-set-a")
    }
}
