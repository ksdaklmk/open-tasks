package app.opentasks.core.data.backup.drive

import java.io.File
import java.io.IOException

data class DriveFileMetadata(
    val providerFileId: String,
    val name: String,
    val role: String,
    val appProperties: Map<String, String>,
)

data class DriveCreateRequest(
    val metadata: DriveFileMetadata,
    val content: ByteArray,
)

data class DriveDownloadReceipt(val byteCount: Long)

sealed interface DriveCreateResult {
    data object Created : DriveCreateResult
    data object AlreadyExists : DriveCreateResult
    data object Ambiguous : DriveCreateResult
}

data class DriveListedFile(
    val providerFileId: String,
    val name: String,
    val role: String?,
    val appProperties: Map<String, String>,
)

data class DriveListPage(
    val files: List<DriveListedFile>,
    val nextPageToken: String?,
)

data class DriveResumableSession(val sessionUri: String)

sealed interface DriveChunkResult {
    data class ResumeAt(val nextByte: Long) : DriveChunkResult
    data object Complete : DriveChunkResult
    data object Expired : DriveChunkResult
    data object Ambiguous : DriveChunkResult
}

enum class DriveTransportFailureCategory {
    AUTHORIZATION,
    MISSING,
    STORAGE_QUOTA,
    RETRYABLE,
    CORRUPT_RESPONSE,
    PROVIDER_REJECTED,
}

class DriveTransportException(
    val category: DriveTransportFailureCategory,
) : IOException(category.name)

interface CreateOnlyDriveTransport : AutoCloseable {
    suspend fun readCurrentUserPermissionId(): String
    suspend fun generateAppDataFileIds(count: Int): List<String>
    suspend fun listAppDataFiles(
        query: String,
        pageToken: String?,
        pageSize: Int,
    ): DriveListPage
    suspend fun createFileIfAbsent(
        request: DriveCreateRequest,
    ): DriveCreateResult
    suspend fun downloadFile(
        providerFileId: String,
        destination: File,
        maximumBytes: Long,
    ): DriveDownloadReceipt
    suspend fun startResumableCreate(
        metadata: DriveFileMetadata,
        totalBytes: Long,
    ): DriveResumableSession
    suspend fun queryResumableUpload(
        sessionUri: String,
        totalBytes: Long,
    ): DriveChunkResult
    suspend fun uploadChunk(
        sessionUri: String,
        firstByte: Long,
        totalBytes: Long,
        content: ByteArray,
    ): DriveChunkResult
    suspend fun deleteFile(providerFileId: String): Boolean
}
