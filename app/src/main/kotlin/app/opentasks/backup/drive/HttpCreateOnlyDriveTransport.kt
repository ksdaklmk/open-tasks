package app.opentasks.backup.drive

import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.data.backup.drive.DriveChunkResult
import app.opentasks.core.data.backup.drive.DriveCreateRequest
import app.opentasks.core.data.backup.drive.DriveCreateResult
import app.opentasks.core.data.backup.drive.DriveDownloadReceipt
import app.opentasks.core.data.backup.drive.DriveFileMetadata
import app.opentasks.core.data.backup.drive.DriveListPage
import app.opentasks.core.data.backup.drive.DriveListedFile
import app.opentasks.core.data.backup.drive.DriveResumableSession
import app.opentasks.core.data.backup.drive.DriveTransportException
import app.opentasks.core.data.backup.drive.DriveTransportFailureCategory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun interface DriveConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

class HttpCreateOnlyDriveTransport(
    accessToken: String,
    private val connectionFactory: DriveConnectionFactory = DriveConnectionFactory { url ->
        url.openConnection() as HttpURLConnection
    },
) : CreateOnlyDriveTransport {
    private var accessToken: String? = accessToken

    internal var recordedFailureText: String = ""
        private set

    internal val patchRequests: Int
        get() = 0

    override suspend fun readCurrentUserPermissionId(): String = withContext(Dispatchers.IO) {
        boundedNetworkOperation {
            val connection = openDrive("GET", "/drive/v3/about?fields=user(permissionId)")
            connection.useResponse { status ->
                requireSuccess(connection, status)
                decode<AboutResponse>(connection).user.permissionId
                    .takeIf(String::isNotEmpty)
                    ?: throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
            }
        }
    }

    override suspend fun generateAppDataFileIds(count: Int): List<String> = withContext(Dispatchers.IO) {
        if (count !in 1..MAX_GENERATED_IDS) {
            throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
        }
        boundedNetworkOperation {
            val connection = openDrive(
                "GET",
                "/drive/v3/files/generateIds?count=$count&space=appDataFolder&type=files",
            )
            connection.useResponse { status ->
                requireSuccess(connection, status)
                decode<GenerateIdsResponse>(connection).let { response ->
                    if (response.space != APP_DATA_SPACE || response.kind != GENERATED_IDS_KIND) {
                        throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
                    }
                    response.ids
                }.also { ids ->
                    if (ids.size != count || ids.any { it.isEmpty() || it.length > MAX_ID_CHARACTERS }) {
                        throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
                    }
                }
            }
        }
    }

    override suspend fun listAppDataFiles(
        query: String,
        pageToken: String?,
        pageSize: Int,
    ): DriveListPage = withContext(Dispatchers.IO) {
        if (
            query.isEmpty() ||
            query.length > MAX_QUERY_CHARACTERS ||
            pageToken != null && pageToken.length !in 1..MAX_PAGE_TOKEN_CHARACTERS ||
            pageSize !in 1..MAX_PAGE_SIZE
        ) {
            throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
        }
        val path = buildString {
            append("/drive/v3/files?spaces=appDataFolder")
            append("&q=").append(encode(query))
            pageToken?.let { append("&pageToken=").append(encode(it)) }
            append("&pageSize=").append(pageSize)
            append("&fields=nextPageToken,files(id,name,appProperties)")
        }
        boundedNetworkOperation {
            val connection = openDrive("GET", path)
            connection.useResponse { status ->
                requireSuccess(connection, status)
                val response = decode<ListFilesResponse>(connection)
                if (response.nextPageToken != null && response.nextPageToken.length !in 1..MAX_PAGE_TOKEN_CHARACTERS) {
                    throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
                }
                DriveListPage(
                    files = response.files.map { file ->
                        if (file.id.isEmpty() || file.name.isEmpty() || !validAppProperties(file.appProperties.orEmpty())) {
                            throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
                        }
                        val properties = file.appProperties.orEmpty()
                        DriveListedFile(
                            providerFileId = file.id,
                            name = file.name,
                            role = properties["role"],
                            appProperties = properties,
                        )
                    },
                    nextPageToken = response.nextPageToken,
                )
            }
        }
    }

    override suspend fun createFileIfAbsent(request: DriveCreateRequest): DriveCreateResult =
        withContext(Dispatchers.IO) {
            val body = createMultipartBody(request)
            var requestMayHaveReachedProvider = false
            try {
                val connection = openDrive("POST", "/upload/drive/v3/files?uploadType=multipart")
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "multipart/related; boundary=$MULTIPART_BOUNDARY",
                )
                try {
                    connection.outputStream.use { output ->
                        requestMayHaveReachedProvider = true
                        output.write(body)
                    }
                    connection.useResponse { status ->
                        when (status) {
                            200, 201 -> DriveCreateResult.Created
                            409 -> DriveCreateResult.AlreadyExists
                            else -> throw mappedFailure(connection, status)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (exception: DriveTransportException) {
                throw exception
            } catch (_: IOException) {
                if (requestMayHaveReachedProvider) {
                    DriveCreateResult.Ambiguous
                } else {
                    throw failure(DriveTransportFailureCategory.RETRYABLE)
                }
            } finally {
                body.fill(0)
            }
        }

    override suspend fun downloadFile(
        providerFileId: String,
        destination: File,
        maximumBytes: Long,
    ): DriveDownloadReceipt = withContext(Dispatchers.IO) {
        if (providerFileId.isEmpty() || maximumBytes < 0) {
            throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
        }
        try {
            boundedNetworkOperation {
                val connection = openDrive("GET", "/drive/v3/files/${encodePath(providerFileId)}?alt=media")
                connection.useResponse { status ->
                    requireSuccess(connection, status)
                    val declaredLength = connection.contentLengthLong
                    if (declaredLength > maximumBytes) {
                        throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
                    }
                    var byteCount = 0L
                    connection.inputStream.use { input ->
                        FileOutputStream(destination, false).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count > maximumBytes - byteCount) {
                                    throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
                                }
                                output.write(buffer, 0, count)
                                byteCount += count
                            }
                            output.fd.sync()
                            buffer.fill(0)
                        }
                    }
                    if (declaredLength >= 0 && byteCount != declaredLength) {
                        throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
                    }
                    DriveDownloadReceipt(byteCount)
                }
            }
        } catch (exception: Exception) {
            destination.delete()
            throw exception
        }
    }

    override suspend fun startResumableCreate(
        metadata: DriveFileMetadata,
        totalBytes: Long,
    ): DriveResumableSession = withContext(Dispatchers.IO) {
        if (totalBytes < 0) throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
        boundedNetworkOperation {
            val body = encodeCreateMetadata(metadata)
            val connection = openDrive("POST", "/upload/drive/v3/files?uploadType=resumable")
            try {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
                connection.setRequestProperty("X-Upload-Content-Length", totalBytes.toString())
                connection.outputStream.use { it.write(body) }
                connection.useResponse { status ->
                    if (status != 200 && status != 201) throw mappedFailure(connection, status)
                    val location = connection.getHeaderField("Location")
                        ?.takeIf(::isAllowedSessionUri)
                        ?: throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
                    DriveResumableSession(location)
                }
            } finally {
                body.fill(0)
                connection.disconnect()
            }
        }
    }

    override suspend fun queryResumableUpload(
        sessionUri: String,
        totalBytes: Long,
    ): DriveChunkResult = withContext(Dispatchers.IO) {
        if (totalBytes < 0) throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
        val connection = openSession("PUT", sessionUri)
        connection.doOutput = true
        connection.setRequestProperty("Content-Length", "0")
        connection.setRequestProperty("Content-Range", "bytes */$totalBytes")
        try {
            chunkResponse(connection)
        } catch (exception: DriveTransportException) {
            throw exception
        } catch (_: IOException) {
            DriveChunkResult.Ambiguous
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun uploadChunk(
        sessionUri: String,
        firstByte: Long,
        totalBytes: Long,
        content: ByteArray,
    ): DriveChunkResult = withContext(Dispatchers.IO) {
        val lastExclusive = firstByte + content.size
        val isFinal = lastExclusive == totalBytes
        if (
            firstByte < 0 ||
            totalBytes <= firstByte ||
            content.isEmpty() ||
            lastExclusive > totalBytes ||
            !isFinal && content.size % RESUMABLE_CHUNK_ALIGNMENT != 0
        ) {
            throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
        }
        val connection = openSession("PUT", sessionUri)
        var requestMayHaveReachedProvider = false
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Content-Length", content.size.toString())
            connection.setRequestProperty(
                "Content-Range",
                "bytes $firstByte-${lastExclusive - 1}/$totalBytes",
            )
            connection.outputStream.use { output ->
                requestMayHaveReachedProvider = true
                output.write(content)
            }
            chunkResponse(connection)
        } catch (exception: DriveTransportException) {
            throw exception
        } catch (_: IOException) {
            if (requestMayHaveReachedProvider) {
                DriveChunkResult.Ambiguous
            } else {
                throw failure(DriveTransportFailureCategory.RETRYABLE)
            }
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun deleteFile(providerFileId: String): Boolean = withContext(Dispatchers.IO) {
        if (providerFileId.isEmpty()) throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
        boundedNetworkOperation {
            val connection = openDrive("DELETE", "/drive/v3/files/${encodePath(providerFileId)}")
            connection.useResponse { status ->
                when {
                    status == 404 -> false
                    status in 200..299 -> true
                    else -> throw mappedFailure(connection, status)
                }
            }
        }
    }

    override fun close() {
        accessToken = null
    }

    private fun createMultipartBody(request: DriveCreateRequest): ByteArray {
        val metadata = encodeCreateMetadata(request.metadata)
        return try {
            multipartBody(MULTIPART_BOUNDARY, metadata, request.content)
        } finally {
            metadata.fill(0)
        }
    }

    private fun encodeCreateMetadata(metadata: DriveFileMetadata): ByteArray {
        if (
            metadata.providerFileId.isEmpty() ||
            metadata.providerFileId.length > MAX_ID_CHARACTERS ||
            metadata.name.isEmpty() ||
            metadata.name.length > MAX_NAME_CHARACTERS ||
            metadata.role.isEmpty()
        ) {
            throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
        }
        val properties = metadata.appProperties.toMutableMap()
        if (properties["role"]?.let { it != metadata.role } == true) {
            throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
        }
        properties["role"] = metadata.role
        if (!validAppProperties(properties)) {
            throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
        }
        return json.encodeToString(
            CreateMetadata.serializer(),
            CreateMetadata(
                id = metadata.providerFileId,
                name = metadata.name,
                appProperties = properties,
            ),
        ).encodeToByteArray()
    }

    private fun validAppProperties(properties: Map<String, String>): Boolean =
        properties.size <= MAX_APP_PROPERTIES &&
            properties.all { (key, value) ->
                key.isNotEmpty() &&
                    key.encodeToByteArray().size <= MAX_APP_PROPERTY_BYTES &&
                    value.encodeToByteArray().size <= MAX_APP_PROPERTY_BYTES
            }

    private fun chunkResponse(connection: HttpURLConnection): DriveChunkResult =
        connection.useResponse { status ->
            when (status) {
                200, 201 -> DriveChunkResult.Complete
                308 -> DriveChunkResult.ResumeAt(nextResumableByte(connection))
                404, 410 -> DriveChunkResult.Expired
                else -> throw mappedFailure(connection, status)
            }
        }

    private fun openDrive(method: String, path: String): HttpURLConnection =
        open(method, URL("$DRIVE_ORIGIN$path"))

    private fun openSession(method: String, sessionUri: String): HttpURLConnection {
        if (!isAllowedSessionUri(sessionUri)) {
            throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
        }
        return open(method, URL(sessionUri))
    }

    private fun isAllowedSessionUri(sessionUri: String): Boolean =
        try {
            val url = URL(sessionUri)
            url.protocol == "https" && url.host == DRIVE_HOST && url.userInfo == null
        } catch (_: IOException) {
            false
        }

    private fun open(method: String, url: URL): HttpURLConnection {
        val token = accessToken ?: throw failure(DriveTransportFailureCategory.AUTHORIZATION)
        return try {
            connectionFactory.open(url).apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
        } catch (exception: DriveTransportException) {
            throw exception
        } catch (_: IOException) {
            throw failure(DriveTransportFailureCategory.RETRYABLE)
        }
    }

    private fun requireSuccess(connection: HttpURLConnection, status: Int) {
        if (status !in 200..299) throw mappedFailure(connection, status)
    }

    private fun mappedFailure(connection: HttpURLConnection, status: Int): DriveTransportException {
        val reason = if (status == 403) boundedErrorReason(connection) else null
        return failure(
            when {
                status == 401 -> DriveTransportFailureCategory.AUTHORIZATION
                status == 404 -> DriveTransportFailureCategory.MISSING
                status == 403 && reason == "storageQuotaExceeded" ->
                    DriveTransportFailureCategory.STORAGE_QUOTA
                status == 403 && reason in RETRYABLE_403_REASONS ->
                    DriveTransportFailureCategory.RETRYABLE
                status == 403 -> DriveTransportFailureCategory.AUTHORIZATION
                status == 429 || status >= 500 -> DriveTransportFailureCategory.RETRYABLE
                status in 400..499 -> DriveTransportFailureCategory.PROVIDER_REJECTED
                else -> DriveTransportFailureCategory.CORRUPT_RESPONSE
            },
        )
    }

    private inline fun <T> boundedNetworkOperation(block: () -> T): T =
        try {
            block()
        } catch (exception: DriveTransportException) {
            throw exception
        } catch (_: IOException) {
            throw failure(DriveTransportFailureCategory.RETRYABLE)
        }

    private fun failure(category: DriveTransportFailureCategory): DriveTransportException {
        recordedFailureText = category.name
        return DriveTransportException(category)
    }

    private fun boundedErrorReason(connection: HttpURLConnection): String? {
        val bytes = connection.errorStream?.use { input -> input.readBounded(MAX_ERROR_BYTES) } ?: return null
        return try {
            errorJson.decodeFromString(ErrorResponse.serializer(), bytes.decodeToString())
                .error.errors.firstOrNull()?.reason
        } catch (_: Exception) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    private inline fun <reified T> decode(connection: HttpURLConnection): T {
        val bytes = connection.inputStream.use { it.readBounded(MAX_JSON_BYTES) }
        return try {
            json.decodeFromString<T>(bytes.decodeToString())
        } catch (_: Exception) {
            throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
        } finally {
            bytes.fill(0)
        }
    }

    private fun nextResumableByte(connection: HttpURLConnection): Long {
        val range = connection.getHeaderField("Range") ?: return 0
        val last = range.removePrefix("bytes=").substringAfter('-', "").toLongOrNull()
            ?: throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
        return last + 1
    }

    private inline fun <T> HttpURLConnection.useResponse(block: (Int) -> T): T =
        try {
            block(responseCode)
        } finally {
            disconnect()
        }

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) {
                throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
            }
            output.write(buffer, 0, count)
        }
        buffer.fill(0)
        return output.toByteArray()
    }

    private fun multipartBody(boundary: String, metadata: ByteArray, content: ByteArray): ByteArray {
        val prefix =
            "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".encodeToByteArray()
        val middle =
            "\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".encodeToByteArray()
        val suffix = "\r\n--$boundary--\r\n".encodeToByteArray()
        return ByteArray(prefix.size + metadata.size + middle.size + content.size + suffix.size).also { result ->
            var offset = 0
            prefix.copyInto(result, offset)
            offset += prefix.size
            metadata.copyInto(result, offset)
            offset += metadata.size
            middle.copyInto(result, offset)
            offset += middle.size
            content.copyInto(result, offset)
            offset += content.size
            suffix.copyInto(result, offset)
            prefix.fill(0)
            middle.fill(0)
            suffix.fill(0)
        }
    }

    @Serializable
    private data class AboutResponse(val user: PermissionUser)

    @Serializable
    private data class PermissionUser(val permissionId: String)

    @Serializable
    private data class GenerateIdsResponse(
        val ids: List<String>,
        val space: String,
        val kind: String,
    )

    @Serializable
    private data class ListFilesResponse(
        val files: List<ListedFile> = emptyList(),
        val nextPageToken: String? = null,
    )

    @Serializable
    private data class ListedFile(
        val id: String,
        val name: String,
        val appProperties: Map<String, String>? = null,
    )

    @Serializable
    private data class CreateMetadata(
        val id: String,
        val name: String,
        val appProperties: Map<String, String>,
        @EncodeDefault
        val parents: List<String> = listOf(APP_DATA_FOLDER_PARENT),
    )

    @Serializable
    private data class ErrorResponse(val error: ErrorDetail)

    @Serializable
    private data class ErrorDetail(val errors: List<ErrorReason> = emptyList())

    @Serializable
    private data class ErrorReason(val reason: String)

    private companion object {
        const val DRIVE_ORIGIN = "https://www.googleapis.com"
        const val DRIVE_HOST = "www.googleapis.com"
        const val APP_DATA_SPACE = "appDataFolder"
        const val APP_DATA_FOLDER_PARENT = "appDataFolder"
        const val GENERATED_IDS_KIND = "drive#generatedIds"
        const val MULTIPART_BOUNDARY = "openTasksDriveBoundary"
        const val BUFFER_SIZE = 8 * 1024
        const val MAX_ERROR_BYTES = 4 * 1024
        const val MAX_JSON_BYTES = 256 * 1024
        const val MAX_GENERATED_IDS = 1000
        const val MAX_PAGE_SIZE = 1000
        const val MAX_PAGE_TOKEN_CHARACTERS = 1024
        const val MAX_QUERY_CHARACTERS = 4096
        const val MAX_ID_CHARACTERS = 1024
        const val MAX_NAME_CHARACTERS = 1024
        const val MAX_APP_PROPERTIES = 100
        const val MAX_APP_PROPERTY_BYTES = 124
        const val RESUMABLE_CHUNK_ALIGNMENT = 256 * 1024
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        val RETRYABLE_403_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")
        val json = Json { ignoreUnknownKeys = false; isLenient = false }
        val errorJson = Json { ignoreUnknownKeys = true; isLenient = false }

        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
        fun encodePath(value: String): String = encode(value).replace("+", "%20")
    }
}
