package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.domain.AttachmentBlobSetManifest
import app.opentasks.core.domain.AttachmentChunkRef
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.sync.CloudBounds
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class AttachmentBlobSetManifestCodec(
    private val authenticatedCodec: AuthenticatedCloudObjectCodec,
) {
    fun encode(
        manifest: AttachmentBlobSetManifest,
        lineageId: CloudLineageId,
        contentKey: VaultKey,
    ): ByteArray {
        validate(manifest)
        val plaintext = canonicalBytes(manifest.toPayload())
        return try {
            authenticatedCodec.encrypt(identity(lineageId, manifest.blobSetId), plaintext, contentKey)
        } finally {
            plaintext.fill(0)
        }
    }

    fun decode(
        bytes: ByteArray,
        lineageId: CloudLineageId,
        blobSetId: BlobSetId,
        contentKey: VaultKey,
    ): AttachmentBlobSetManifest {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FRAME_BYTES) {
            "Attachment manifest frame is outside its bound"
        }
        val result = authenticatedCodec.decrypt(
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
            contentKey,
        )
        require(result is CloudDecodeResult.Success) {
            "Attachment manifest authentication failed"
        }
        return result.value.use { decrypted ->
            require(decrypted.identity == identity(lineageId, blobSetId)) {
                "Attachment manifest frame identity mismatch"
            }
            val plaintext = decrypted.takePlaintext()
            try {
                val payload = decodeCanonical(plaintext)
                val manifest = payload.toDomain()
                validate(manifest)
                require(manifest.blobSetId == blobSetId) {
                    "Attachment manifest names another blob set"
                }
                manifest
            } finally {
                plaintext.fill(0)
            }
        }
    }

    private fun decodeCanonical(bytes: ByteArray): AttachmentBlobSetManifestPayload {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PLAINTEXT_BYTES) {
            "Attachment manifest payload is outside its bound"
        }
        val payload = try {
            STRICT_JSON.decodeFromString(
                AttachmentBlobSetManifestPayload.serializer(),
                strictUtf8(bytes),
            )
        } catch (failure: SerializationException) {
            throw IllegalArgumentException("Invalid attachment manifest", failure)
        }
        val canonical = canonicalBytes(payload)
        try {
            require(bytes.contentEquals(canonical)) {
                "Attachment manifest is not canonical"
            }
        } finally {
            canonical.fill(0)
        }
        return payload
    }

    private fun canonicalBytes(payload: AttachmentBlobSetManifestPayload): ByteArray =
        STRICT_JSON.encodeToString(AttachmentBlobSetManifestPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
            .also {
                require(it.size <= MAX_PLAINTEXT_BYTES) {
                    "Attachment manifest payload exceeds its bound"
                }
            }

    private fun validate(manifest: AttachmentBlobSetManifest) {
        require(manifest.blobSetId.value.isNotBlank()) { "Blob set identifier is blank" }
        require(manifest.chunks.size in 1..MAX_BLOB_SET_CHUNKS) {
            "Attachment chunk count is outside its bound"
        }
        var total = 0L
        manifest.chunks.forEachIndexed { index, chunk ->
            require(chunk.index == index) { "Attachment chunk indexes are not canonical" }
            require(
                chunk.plaintextByteCount in
                    1..CloudBounds.MAX_ATTACHMENT_CHUNK_PLAINTEXT_BYTES.toInt(),
            ) {
                "Attachment chunk plaintext size is outside its bound"
            }
            total += chunk.plaintextByteCount
        }
        require(total == manifest.totalByteCount) {
            "Attachment chunk sizes do not sum to the declared total"
        }
        require(manifest.totalByteCount in 1..MAX_TOTAL_BYTES) {
            "Attachment total size is outside its bound"
        }
    }

    private fun identity(
        lineageId: CloudLineageId,
        blobSetId: BlobSetId,
    ) = CloudHeaderIdentity(
        family = CloudObjectFamily.MANIFEST,
        schemaVersion = 1,
        cryptoVersion = 1,
        minimumReaderVersion = 1,
        vaultId = lineageId.value,
        objectId = "attachment-manifest:${blobSetId.value}",
    )

    companion object {
        const val MAX_BLOB_SET_CHUNKS = 25
        const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
        val MAX_PLAINTEXT_BYTES =
            (CloudBounds.MAX_MANIFEST_CIPHERTEXT_BYTES -
                CloudBounds.AES_GCM_V1_CIPHERTEXT_OVERHEAD_BYTES).toInt()
        val MAX_FRAME_BYTES =
            4 + CloudBounds.MAX_HEADER_BYTES + CloudBounds.MAX_MANIFEST_CIPHERTEXT_BYTES.toInt()
    }
}

@Serializable
private data class AttachmentBlobSetManifestPayload(
    val blobSetId: String,
    val contentSha256: String,
    val totalByteCount: Long,
    val chunks: List<AttachmentChunkRefPayload>,
)

@Serializable
private data class AttachmentChunkRefPayload(
    val index: Int,
    val providerObjectId: String,
    val ciphertextSha256: String,
    val plaintextByteCount: Int,
)

private fun AttachmentBlobSetManifest.toPayload() = AttachmentBlobSetManifestPayload(
    blobSetId = blobSetId.value,
    contentSha256 = contentSha256.value,
    totalByteCount = totalByteCount,
    chunks = chunks.map { chunk ->
        AttachmentChunkRefPayload(
            index = chunk.index,
            providerObjectId = chunk.providerObjectId.value,
            ciphertextSha256 = chunk.ciphertextSha256.value,
            plaintextByteCount = chunk.plaintextByteCount,
        )
    },
)

private fun AttachmentBlobSetManifestPayload.toDomain() = AttachmentBlobSetManifest(
    blobSetId = BlobSetId(blobSetId),
    contentSha256 = Sha256Digest.of(contentSha256),
    totalByteCount = totalByteCount,
    chunks = chunks.map { chunk ->
        AttachmentChunkRef(
            index = chunk.index,
            providerObjectId = ProviderObjectId.of(chunk.providerObjectId),
            ciphertextSha256 = Sha256Digest.of(chunk.ciphertextSha256),
            plaintextByteCount = chunk.plaintextByteCount,
        )
    },
)

@OptIn(ExperimentalSerializationApi::class)
private val STRICT_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    allowTrailingComma = false
}

private fun strictUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (failure: Exception) {
    throw IllegalArgumentException("Attachment manifest is not valid UTF-8", failure)
}
