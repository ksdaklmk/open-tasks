package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.domain.AttachmentBlobSetManifest
import app.opentasks.core.domain.AttachmentBlobStore
import app.opentasks.core.domain.AttachmentManifestLookup
import app.opentasks.core.domain.AttachmentReadResult
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.sync.CloudBounds
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

sealed interface AttachmentOpenResult {
    data class Opened(val byteCount: Long) : AttachmentOpenResult
    data object Unavailable : AttachmentOpenResult
    data class Failed(val reason: RemoteBackupFailureCategory) : AttachmentOpenResult
}

class AttachmentOpenCoordinator(
    private val cache: AttachmentCacheStore,
    private val manifestCodec: AttachmentBlobSetManifestCodec,
    private val codec: AuthenticatedCloudObjectCodec,
    private val lineageId: CloudLineageId,
    private val contentKey: () -> VaultKey,
) {
    /**
     * Streams authenticated chunks to [destination]. Any result other than [AttachmentOpenResult.Opened]
     * may leave partial bytes; the caller must discard or delete the destination in that case.
     */
    suspend fun open(
        store: AttachmentBlobStore,
        attachment: Attachment,
        destination: OutputStream,
    ): AttachmentOpenResult = stream(store, attachment) { _, plaintext ->
        destination.write(plaintext)
    }

    /**
     * Streams the same verified chunks as [open], delivering plaintext through
     * [onChunk] in ascending chunk-index order instead of an [OutputStream]
     * sink. A result other than [AttachmentOpenResult.Opened] means [onChunk]
     * may not have been called for every chunk.
     */
    suspend fun openChunks(
        store: AttachmentBlobStore,
        attachment: Attachment,
        onChunk: suspend (chunkIndex: Int, plaintext: ByteArray) -> Unit,
    ): AttachmentOpenResult = stream(store, attachment) { index, plaintext ->
        onChunk(index, plaintext)
    }

    private suspend fun stream(
        store: AttachmentBlobStore,
        attachment: Attachment,
        sink: suspend (chunkIndex: Int, plaintext: ByteArray) -> Unit,
    ): AttachmentOpenResult {
        val blobSetId = attachment.blobSetId ?: return AttachmentOpenResult.Unavailable
        val manifest = when (val result = findManifest(store, blobSetId)) {
            is ManifestResult.Found -> result.manifest
            ManifestResult.Unavailable -> return AttachmentOpenResult.Unavailable
            is ManifestResult.Failed -> return AttachmentOpenResult.Failed(result.reason)
        }
        if (
            manifest.totalByteCount != attachment.byteCount ||
            manifest.contentSha256.value != attachment.contentHash ||
            manifest.chunks.size != attachment.chunkCount
        ) {
            return corrupt()
        }

        val digest = MessageDigest.getInstance(SHA_256)
        var total = 0L
        manifest.chunks.forEach { chunk ->
            var frame = cache.read(blobSetId, chunk.index)
            val cacheHit = frame != null
            if (frame == null) {
                frame = when (val read = readObject(store, chunk.providerObjectId, MAX_CHUNK_FRAME_BYTES)) {
                    is AttachmentReadResult.Found -> read.bytes
                    AttachmentReadResult.Missing -> return AttachmentOpenResult.Unavailable
                    is AttachmentReadResult.Failed -> return AttachmentOpenResult.Failed(read.reason)
                }
            }
            try {
                if (
                    frame.size.toLong() !in 1..MAX_CHUNK_FRAME_BYTES ||
                    sha256(frame) != chunk.ciphertextSha256.value
                ) {
                    cache.evict(blobSetId)
                    return corrupt()
                }
                if (!cacheHit) {
                    try {
                        cache.write(blobSetId, chunk.index, frame)
                    } catch (_: IOException) {
                        // Cache persistence is optional; the verified frame remains usable for this open.
                    }
                }
                val decoded = codec.decrypt(
                    ByteArrayInputStream(frame),
                    frame.size.toLong(),
                    contentKey(),
                )
                if (decoded !is CloudDecodeResult.Success) {
                    cache.evict(blobSetId)
                    return corrupt()
                }
                val plaintext = decoded.value.use { value ->
                    if (value.identity != chunkIdentity(blobSetId, chunk.index, manifest.chunks.size)) {
                        cache.evict(blobSetId)
                        return corrupt()
                    }
                    value.takePlaintext()
                }
                try {
                    if (plaintext.size != chunk.plaintextByteCount) {
                        cache.evict(blobSetId)
                        return corrupt()
                    }
                    digest.update(plaintext)
                    try {
                        sink(chunk.index, plaintext)
                    } catch (_: IOException) {
                        return AttachmentOpenResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
                    }
                    total += plaintext.size
                } finally {
                    plaintext.fill(0)
                }
            } finally {
                frame.fill(0)
            }
        }

        return if (total == attachment.byteCount && digest.digest().toHex() == attachment.contentHash) {
            AttachmentOpenResult.Opened(total)
        } else {
            corrupt()
        }
    }

    private suspend fun findManifest(
        store: AttachmentBlobStore,
        blobSetId: BlobSetId,
    ): ManifestResult {
        val providerId = when (val lookup = try {
            store.findManifest(blobSetId)
        } catch (_: IOException) {
            return ManifestResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
        }) {
            is AttachmentManifestLookup.Found -> lookup.providerObjectId
            AttachmentManifestLookup.Missing -> return ManifestResult.Unavailable
            AttachmentManifestLookup.Ambiguous ->
                return ManifestResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
            is AttachmentManifestLookup.Failed -> return ManifestResult.Failed(lookup.reason)
        }
        val bytes = when (
            val read = readObject(
                store,
                providerId,
                AttachmentBlobSetManifestCodec.MAX_FRAME_BYTES.toLong(),
            )
        ) {
            is AttachmentReadResult.Found -> read.bytes
            AttachmentReadResult.Missing -> return ManifestResult.Unavailable
            is AttachmentReadResult.Failed -> return ManifestResult.Failed(read.reason)
        }
        return try {
            ManifestResult.Found(manifestCodec.decode(bytes, lineageId, blobSetId, contentKey()))
        } catch (_: IllegalArgumentException) {
            ManifestResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun readObject(
        store: AttachmentBlobStore,
        providerId: app.opentasks.core.model.ProviderObjectId,
        maximumBytes: Long,
    ): AttachmentReadResult = try {
        store.readObject(providerId, maximumBytes)
    } catch (_: IOException) {
        AttachmentReadResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
    }

    private fun chunkIdentity(blobSetId: BlobSetId, index: Int, count: Int) = CloudHeaderIdentity(
        family = CloudObjectFamily.ATTACHMENT_CHUNK,
        schemaVersion = 1,
        cryptoVersion = 1,
        minimumReaderVersion = 1,
        vaultId = lineageId.value,
        objectId = "attachment-chunk:${blobSetId.value}",
        chunkIndex = index,
        chunkCount = count,
    )

    private fun corrupt() = AttachmentOpenResult.Failed(
        RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
    )

    private sealed interface ManifestResult {
        data class Found(val manifest: AttachmentBlobSetManifest) : ManifestResult
        data object Unavailable : ManifestResult
        data class Failed(val reason: RemoteBackupFailureCategory) : ManifestResult
    }

    private companion object {
        const val SHA_256 = "SHA-256"
        const val MAX_CHUNK_FRAME_BYTES =
            4L + CloudBounds.MAX_HEADER_BYTES +
                CloudBounds.MAX_ATTACHMENT_CHUNK_CIPHERTEXT_BYTES_V1
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String =
    joinToString("") { byte ->
        "%02x".format(byte)
    }
