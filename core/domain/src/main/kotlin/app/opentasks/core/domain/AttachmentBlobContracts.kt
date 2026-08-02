package app.opentasks.core.domain

import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.Sha256Digest

data class AttachmentChunkRef(
    val index: Int,
    val providerObjectId: ProviderObjectId,
    val ciphertextSha256: Sha256Digest,
    val plaintextByteCount: Int,
)

data class AttachmentBlobSetManifest(
    val blobSetId: BlobSetId,
    val contentSha256: Sha256Digest,
    val totalByteCount: Long,
    val chunks: List<AttachmentChunkRef>,
)

sealed interface AttachmentObjectResult {
    data object Created : AttachmentObjectResult
    data object AlreadyExists : AttachmentObjectResult
    data object Ambiguous : AttachmentObjectResult
    data class Failed(val reason: RemoteBackupFailureCategory) : AttachmentObjectResult
}

sealed interface AttachmentReadResult {
    data class Found(val bytes: ByteArray) : AttachmentReadResult
    data object Missing : AttachmentReadResult
    data class Failed(val reason: RemoteBackupFailureCategory) : AttachmentReadResult
}

sealed interface AttachmentManifestLookup {
    data class Found(val providerObjectId: ProviderObjectId) : AttachmentManifestLookup
    data object Missing : AttachmentManifestLookup
    data object Ambiguous : AttachmentManifestLookup
    data class Failed(val reason: RemoteBackupFailureCategory) : AttachmentManifestLookup
}

data class AttachmentListedObject(
    val providerObjectId: ProviderObjectId,
    val role: String?,
    val blobSetId: String?,
    val createdAtEpochMillis: Long?,
)

interface AttachmentBlobStore {
    suspend fun generateObjectIds(count: Int): List<ProviderObjectId>
    suspend fun createChunk(
        providerObjectId: ProviderObjectId,
        blobSetId: BlobSetId,
        chunkIndex: Int,
        chunkCount: Int,
        frameBytes: ByteArray,
    ): AttachmentObjectResult
    suspend fun readObject(
        providerObjectId: ProviderObjectId,
        maximumBytes: Long,
    ): AttachmentReadResult
    suspend fun createManifest(
        providerObjectId: ProviderObjectId,
        blobSetId: BlobSetId,
        frameBytes: ByteArray,
    ): AttachmentObjectResult
    suspend fun findManifest(blobSetId: BlobSetId): AttachmentManifestLookup
    suspend fun listNamespace(
        pageToken: String?,
        exactRole: String? = null,
    ): Pair<List<AttachmentListedObject>, String?>
    suspend fun delete(providerObjectId: ProviderObjectId): Boolean
}
