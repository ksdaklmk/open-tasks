package app.opentasks.core.data.backup

import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.data.backup.drive.DriveCreateRequest
import app.opentasks.core.data.backup.drive.DriveCreateResult
import app.opentasks.core.data.backup.drive.DriveFileMetadata
import app.opentasks.core.data.backup.drive.DriveListedFile
import app.opentasks.core.data.backup.drive.DriveTransportException
import app.opentasks.core.data.backup.drive.DriveTransportFailureCategory
import app.opentasks.core.domain.AttachmentBlobStore
import app.opentasks.core.domain.AttachmentListedObject
import app.opentasks.core.domain.AttachmentManifestLookup
import app.opentasks.core.domain.AttachmentObjectResult
import app.opentasks.core.domain.AttachmentReadResult
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.sync.CloudBounds
import java.io.File
import java.io.IOException

class CreateOnlyDriveAttachmentBlobStore(
    private val transport: CreateOnlyDriveTransport,
    private val lineageId: CloudLineageId,
) : AttachmentBlobStore {
    override suspend fun generateObjectIds(count: Int): List<ProviderObjectId> {
        require(count in 1..MAX_GENERATED_IDS) { "Generated ID count is outside its bound" }
        return transport.generateAppDataFileIds(count).also {
            check(it.size == count) { "Provider returned an unexpected generated ID count" }
        }.map(ProviderObjectId::of)
    }

    override suspend fun createChunk(
        providerObjectId: ProviderObjectId,
        blobSetId: BlobSetId,
        chunkIndex: Int,
        chunkCount: Int,
        frameBytes: ByteArray,
    ): AttachmentObjectResult {
        require(chunkCount in 1..AttachmentBlobSetManifestCodec.MAX_BLOB_SET_CHUNKS) {
            "Attachment chunk count is outside its bound"
        }
        require(chunkIndex in 0 until chunkCount) {
            "Attachment chunk index is outside its bound"
        }
        if (frameBytes.isEmpty() || frameBytes.size > MAX_CHUNK_FRAME_BYTES) {
            return AttachmentObjectResult.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
        }
        return create(
            providerObjectId,
            blobSetId,
            CHUNK_ROLE,
            frameBytes,
            chunkIndex,
        )
    }

    override suspend fun readObject(
        providerObjectId: ProviderObjectId,
        maximumBytes: Long,
    ): AttachmentReadResult {
        require(maximumBytes in 0..MAX_CHUNK_FRAME_BYTES) {
            "Maximum bytes is outside its bound"
        }
        val destination = try {
            File.createTempFile("attachment-read-", ".otr")
        } catch (_: IOException) {
            return AttachmentReadResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        }
        return try {
            transport.downloadFile(providerObjectId.value, destination, maximumBytes)
            AttachmentReadResult.Found(destination.readBytes())
        } catch (failure: DriveTransportException) {
            if (failure.category == DriveTransportFailureCategory.MISSING) {
                AttachmentReadResult.Missing
            } else {
                AttachmentReadResult.Failed(failure.category.toRemoteFailure())
            }
        } catch (_: IOException) {
            AttachmentReadResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        } finally {
            destination.delete()
        }
    }

    override suspend fun createManifest(
        providerObjectId: ProviderObjectId,
        blobSetId: BlobSetId,
        frameBytes: ByteArray,
    ): AttachmentObjectResult {
        if (frameBytes.isEmpty() || frameBytes.size > MAX_MANIFEST_FRAME_BYTES) {
            return AttachmentObjectResult.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
        }
        return create(providerObjectId, blobSetId, MANIFEST_ROLE, frameBytes, null)
    }

    override suspend fun findManifest(blobSetId: BlobSetId): AttachmentManifestLookup {
        val query = buildString {
            appendProperty(PROPERTY_BLOB_SET, blobSetId.value)
            append(" and ")
            appendProperty(PROPERTY_ROLE, MANIFEST_ROLE)
            append(" and ")
            appendProperty(PROPERTY_FORMAT, FORMAT_V1)
            append(" and ")
            appendProperty(PROPERTY_LINEAGE, lineageId.value)
        }
        val matches = mutableListOf<String>()
        val seenTokens = mutableSetOf<String>()
        var token: String? = null
        do {
            val page = try {
                transport.listAppDataFiles(query, token, 2)
            } catch (failure: DriveTransportException) {
                return AttachmentManifestLookup.Failed(failure.category.toRemoteFailure())
            }
            if (page.files.any { !it.isExpectedManifest(blobSetId) }) {
                return AttachmentManifestLookup.Failed(
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
            }
            matches += page.files.map { it.providerFileId }
            if (matches.size > 1) return AttachmentManifestLookup.Ambiguous
            token = page.nextPageToken
            if (token != null && !seenTokens.add(token)) {
                return AttachmentManifestLookup.Failed(
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
            }
        } while (token != null)
        val providerId = matches.singleOrNull() ?: return AttachmentManifestLookup.Missing
        return try {
            AttachmentManifestLookup.Found(ProviderObjectId.of(providerId))
        } catch (_: IllegalArgumentException) {
            AttachmentManifestLookup.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        }
    }

    override suspend fun listNamespace(
        pageToken: String?,
        exactRole: String?,
    ): Pair<List<AttachmentListedObject>, String?> {
        require(exactRole == null || exactRole == CHUNK_ROLE || exactRole == MANIFEST_ROLE) {
            "Unsupported attachment role filter"
        }
        val query = buildString {
            appendProperty(PROPERTY_FORMAT, FORMAT_V1)
            append(" and ")
            appendProperty(PROPERTY_LINEAGE, lineageId.value)
            if (exactRole != null) {
                append(" and ")
                appendProperty(PROPERTY_ROLE, exactRole)
            }
        }
        val page = transport.listAppDataFiles(query, pageToken, MAX_PAGE_SIZE)
        return page.files.map { file ->
            AttachmentListedObject(
                providerObjectId = ProviderObjectId.of(file.providerFileId),
                role = file.role,
                blobSetId = file.appProperties[PROPERTY_BLOB_SET],
                createdAtEpochMillis =
                    file.appProperties[PROPERTY_CREATED_AT]?.toLongOrNull(),
            )
        } to page.nextPageToken
    }

    private fun DriveListedFile.isExpectedManifest(
        blobSetId: BlobSetId,
    ): Boolean =
        name == MANIFEST_ROLE &&
            role == MANIFEST_ROLE &&
            appProperties[PROPERTY_FORMAT] == FORMAT_V1 &&
            appProperties[PROPERTY_ROLE] == MANIFEST_ROLE &&
            appProperties[PROPERTY_LINEAGE] == lineageId.value &&
            appProperties[PROPERTY_BLOB_SET] == blobSetId.value

    override suspend fun delete(providerObjectId: ProviderObjectId): Boolean = try {
        transport.deleteFile(providerObjectId.value)
    } catch (_: DriveTransportException) {
        false
    }

    private suspend fun create(
        providerObjectId: ProviderObjectId,
        blobSetId: BlobSetId,
        role: String,
        frameBytes: ByteArray,
        chunkIndex: Int?,
    ): AttachmentObjectResult {
        val properties = buildMap {
            put(PROPERTY_FORMAT, FORMAT_V1)
            put(PROPERTY_ROLE, role)
            put(PROPERTY_LINEAGE, lineageId.value)
            put(PROPERTY_BLOB_SET, blobSetId.value)
            chunkIndex?.let { put(PROPERTY_CHUNK_INDEX, it.toString()) }
        }
        val result = try {
            transport.createFileIfAbsent(
                DriveCreateRequest(
                    DriveFileMetadata(
                        providerFileId = providerObjectId.value,
                        name = role,
                        role = role,
                        appProperties = properties,
                    ),
                    frameBytes,
                ),
            )
        } catch (failure: DriveTransportException) {
            return AttachmentObjectResult.Failed(failure.category.toRemoteFailure())
        }
        return when (result) {
            DriveCreateResult.Created -> AttachmentObjectResult.Created
            DriveCreateResult.AlreadyExists -> AttachmentObjectResult.AlreadyExists
            DriveCreateResult.Ambiguous -> AttachmentObjectResult.Ambiguous
        }
    }

    private fun StringBuilder.appendProperty(key: String, value: String) {
        append("appProperties has { key='").append(key)
        append("' and value='")
        value.forEach { character ->
            if (character == '\\' || character == '\'') append('\\')
            append(character)
        }
        append("' }")
    }

    private companion object {
        const val CHUNK_ROLE = "attachment-chunk"
        const val MANIFEST_ROLE = "attachment-manifest"
        const val FORMAT_V1 = "v1"
        const val PROPERTY_FORMAT = "format"
        const val PROPERTY_ROLE = "role"
        const val PROPERTY_LINEAGE = "lineageId"
        const val PROPERTY_BLOB_SET = "blobSetId"
        const val PROPERTY_CHUNK_INDEX = "chunkIndex"
        const val PROPERTY_CREATED_AT = "createdAtEpochMillis"
        const val MAX_GENERATED_IDS = 100
        const val MAX_PAGE_SIZE = 100
        val HEADER_AND_PREFIX_BYTES = 4L + CloudBounds.MAX_HEADER_BYTES
        val MAX_CHUNK_FRAME_BYTES =
            HEADER_AND_PREFIX_BYTES + CloudBounds.MAX_ATTACHMENT_CHUNK_CIPHERTEXT_BYTES_V1
        val MAX_MANIFEST_FRAME_BYTES =
            HEADER_AND_PREFIX_BYTES + CloudBounds.MAX_MANIFEST_CIPHERTEXT_BYTES
    }
}
