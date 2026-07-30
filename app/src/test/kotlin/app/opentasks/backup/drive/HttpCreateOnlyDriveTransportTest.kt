package app.opentasks.backup.drive

import app.opentasks.core.data.backup.drive.DriveChunkResult
import app.opentasks.core.data.backup.drive.DriveCreateRequest
import app.opentasks.core.data.backup.drive.DriveCreateResult
import app.opentasks.core.data.backup.drive.DriveFileMetadata
import app.opentasks.core.data.backup.drive.DriveTransportException
import app.opentasks.core.data.backup.drive.DriveTransportFailureCategory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCreateOnlyDriveTransportTest {
    @Test
    fun occupiedGeneratedIdReturnsAlreadyExistsWithoutReplacement() = runBlocking {
        val transport = transportReturning(
            status = 409,
            responseBody = """{"error":{"message":"private"}}""",
        )

        assertEquals(
            DriveCreateResult.AlreadyExists,
            transport.createFileIfAbsent(createRequest("generated-a", byteArrayOf(1))),
        )
        assertFalse(transport.recordedFailureText.contains("private"))
        assertEquals(0, transport.patchRequests)
    }

    @Test
    fun lostCreateResponseReturnsAmbiguousForExactIdResolution() = runBlocking {
        val transport = HttpCreateOnlyDriveTransport(
            "token",
            QueueConnectionFactory(FakeConnection(201, responseFailure = IOException("private response"))),
        )

        assertEquals(
            DriveCreateResult.Ambiguous,
            transport.createFileIfAbsent(createRequest("generated-a", byteArrayOf(1))),
        )
    }

    @Test
    fun definitePreRequestCreateFailureUsesBoundedRetryableCategory() = runBlocking {
        val transport = HttpCreateOnlyDriveTransport(
            "token",
            DriveConnectionFactory { throw IOException("private endpoint diagnostic") },
        )

        val failure = assertTransportFailure {
            transport.createFileIfAbsent(createRequest("generated-a", byteArrayOf(1)))
        }

        assertEquals(DriveTransportFailureCategory.RETRYABLE, failure.category)
        assertFalse(failure.toString().contains("private"))
    }

    @Test
    fun multipartAndResumableCreatesUseExactAppDataMetadataAndGeneratedId() = runBlocking {
        val factory = QueueConnectionFactory(
            FakeConnection(201),
            FakeConnection(200, headers = mapOf("Location" to "https://www.googleapis.com/upload/session")),
        )
        val transport = HttpCreateOnlyDriveTransport("token", factory)
        val metadata = DriveFileMetadata(
            providerFileId = "fixed-id",
            name = "qualification",
            role = "claim",
            appProperties = mapOf("epoch" to "2", "claimId" to "claim-a"),
        )

        assertEquals(
            DriveCreateResult.Created,
            transport.createFileIfAbsent(DriveCreateRequest(metadata, byteArrayOf(9))),
        )
        assertEquals(
            "https://www.googleapis.com/upload/session",
            transport.startResumableCreate(metadata, totalBytes = 1).sessionUri,
        )

        val expectedMetadata =
            """{"id":"fixed-id","name":"qualification","appProperties":{"epoch":"2","claimId":"claim-a","role":"claim"},"parents":["appDataFolder"]}"""
        assertTrue(factory.opened[0].requestBytes().decodeToString().contains(expectedMetadata))
        assertEquals(expectedMetadata, factory.opened[1].requestBytes().decodeToString())
        assertEquals("/upload/drive/v3/files", factory.openedUrls[0].path)
        assertEquals("uploadType=multipart", factory.openedUrls[0].query)
        assertEquals("/upload/drive/v3/files", factory.openedUrls[1].path)
        assertEquals("uploadType=resumable", factory.openedUrls[1].query)
    }

    @Test
    fun appPropertiesMustBeBoundedAndCannotOverrideRole() = runBlocking {
        val oversizedValue = "x".repeat(125)
        listOf(
            DriveFileMetadata("id", "name", "claim", mapOf("role" to "other")),
            DriveFileMetadata("id", "name", "claim", mapOf("key" to oversizedValue)),
            DriveFileMetadata("id", "name", "claim", (1..100).associate { "k$it" to "v" }),
        ).forEach { metadata ->
            val failure = assertTransportFailure {
                transportReturning(201).createFileIfAbsent(DriveCreateRequest(metadata, byteArrayOf(1)))
            }
            assertEquals(DriveTransportFailureCategory.CORRUPT_RESPONSE, failure.category)
        }
    }

    @Test
    fun appDataLookupUsesExactSpaceQueryPageTokenAndPageSize() = runBlocking {
        val factory = QueueConnectionFactory(
            FakeConnection(
                200,
                """{"files":[{"id":"file-a","name":"one","appProperties":{"role":"claim","epoch":"2"}}],"nextPageToken":"next-a"}""",
            ),
            FakeConnection(200, """{"files":[]}"""),
        )
        val transport = HttpCreateOnlyDriveTransport("token", factory)

        val first = transport.listAppDataFiles(
            query = "appProperties has { key='role' and value='claim' }",
            pageToken = "page-a",
            pageSize = 17,
        )
        val second = transport.listAppDataFiles(query = "name = 'none'", pageToken = null, pageSize = 1)

        assertEquals("file-a", first.files.single().providerFileId)
        assertEquals("claim", first.files.single().role)
        assertEquals(mapOf("role" to "claim", "epoch" to "2"), first.files.single().appProperties)
        assertEquals("next-a", first.nextPageToken)
        assertTrue(factory.openedUrls[0].query.contains("spaces=appDataFolder"))
        assertTrue(factory.openedUrls[0].query.contains("pageToken=page-a"))
        assertTrue(factory.openedUrls[0].query.contains("pageSize=17"))
        assertTrue(factory.openedUrls[0].query.contains("q="))
        assertNull(second.nextPageToken)
        assertFalse(factory.openedUrls[1].query.contains("pageToken="))
    }

    @Test
    fun emptyOrOversizedPageTokensAreRejectedBeforeNetwork() = runBlocking {
        listOf("", "x".repeat(1025)).forEach { pageToken ->
            val failure = assertTransportFailure {
                transportReturning(200).listAppDataFiles("name = 'x'", pageToken, 1)
            }
            assertEquals(DriveTransportFailureCategory.CORRUPT_RESPONSE, failure.category)
        }
    }

    @Test
    fun aboutAndGenerateIdsUseDocumentedAppDataContracts() = runBlocking {
        val factory = QueueConnectionFactory(
            FakeConnection(200, """{"user":{"permissionId":"permission-a"}}"""),
            FakeConnection(
                200,
                """{"ids":["one","two"],"space":"appDataFolder","kind":"drive#generatedIds"}""",
            ),
        )
        val transport = HttpCreateOnlyDriveTransport("token", factory)

        assertEquals("permission-a", transport.readCurrentUserPermissionId())
        assertEquals(listOf("one", "two"), transport.generateAppDataFileIds(2))
        assertEquals("/drive/v3/about", factory.openedUrls[0].path)
        assertEquals("fields=user(permissionId)", factory.openedUrls[0].query)
        assertEquals("/drive/v3/files/generateIds", factory.openedUrls[1].path)
        assertEquals("count=2&space=appDataFolder&type=files", factory.openedUrls[1].query)
    }

    @Test
    fun malformedGenerateIdsAndListResponsesAreCorrupt() = runBlocking {
        val bodies = listOf(
            """{"ids":["one"],"space":"drive","kind":"drive#generatedIds"}""",
            """{"ids":["one"],"space":"appDataFolder","kind":"drive#fileList"}""",
            """{"files":[{"id":"","name":"name","appProperties":{}}]}""",
        )

        bodies.forEachIndexed { index, body ->
            val failure = assertTransportFailure {
                if (index < 2) {
                    transportReturning(200, body).generateAppDataFileIds(1)
                } else {
                    transportReturning(200, body).listAppDataFiles("name = 'x'", null, 1)
                }
            }
            assertEquals(DriveTransportFailureCategory.CORRUPT_RESPONSE, failure.category)
        }
    }

    @Test
    fun downloadStreamsOnlyWithinDeclaredAndObservedBounds() = runBlocking {
        val destination = File.createTempFile("create-only-drive", ".bin")
        try {
            val receipt = transportReturning(
                FakeConnection(200, media = byteArrayOf(7, 8), declaredLength = 2),
            ).downloadFile("id", destination, maximumBytes = 2)

            assertEquals(2, receipt.byteCount)
            assertArrayEquals(byteArrayOf(7, 8), destination.readBytes())

            val failure = assertTransportFailure {
                transportReturning(
                    FakeConnection(200, media = byteArrayOf(1, 2, 3), declaredLength = 3),
                ).downloadFile("id", destination, maximumBytes = 2)
            }
            assertEquals(DriveTransportFailureCategory.CORRUPT_RESPONSE, failure.category)
            assertFalse(destination.exists())
        } finally {
            destination.delete()
        }
    }

    @Test
    fun resumableChunksRequire256KiBForEveryNonFinalChunk() = runBlocking {
        val chunk = ByteArray(256 * 1024)
        val factory = QueueConnectionFactory(
            FakeConnection(308, headers = mapOf("Range" to "bytes=0-${chunk.lastIndex}")),
            FakeConnection(201),
        )
        val transport = HttpCreateOnlyDriveTransport("token", factory)

        assertEquals(
            DriveChunkResult.ResumeAt(chunk.size.toLong()),
            transport.uploadChunk(
                "https://www.googleapis.com/upload/session",
                firstByte = 0,
                totalBytes = chunk.size + 1L,
                content = chunk,
            ),
        )
        assertEquals(
            DriveChunkResult.Complete,
            transport.uploadChunk(
                "https://www.googleapis.com/upload/session",
                firstByte = chunk.size.toLong(),
                totalBytes = chunk.size + 1L,
                content = byteArrayOf(1),
            ),
        )

        val failure = assertTransportFailure {
            transportReturning(308).uploadChunk(
                "https://www.googleapis.com/upload/session",
                firstByte = 0,
                totalBytes = 2,
                content = byteArrayOf(1),
            )
        }
        assertEquals(DriveTransportFailureCategory.CORRUPT_RESPONSE, failure.category)
    }

    @Test
    fun resumableQueryAndUploadMapExpiredCompleteAndIndeterminate() = runBlocking {
        assertEquals(
            DriveChunkResult.Expired,
            transportReturning(404).queryResumableUpload(
                "https://www.googleapis.com/upload/session",
                totalBytes = 1,
            ),
        )
        assertEquals(
            DriveChunkResult.Complete,
            transportReturning(200).queryResumableUpload(
                "https://www.googleapis.com/upload/session",
                totalBytes = 1,
            ),
        )
        val transport = HttpCreateOnlyDriveTransport(
            "token",
            QueueConnectionFactory(FakeConnection(200, responseFailure = IOException("private"))),
        )
        assertEquals(
            DriveChunkResult.Ambiguous,
            transport.uploadChunk(
                "https://www.googleapis.com/upload/session",
                firstByte = 0,
                totalBytes = 1,
                content = byteArrayOf(1),
            ),
        )
    }

    @Test
    fun authorizationMissingQuotaRetryableRejectedOccupiedAndCorruptMappingsAreBounded() = runBlocking {
        assertFailure(401, DriveTransportFailureCategory.AUTHORIZATION)
        assertFailure(404, DriveTransportFailureCategory.MISSING)
        assertFailure(403, DriveTransportFailureCategory.AUTHORIZATION, "authError")
        assertFailure(403, DriveTransportFailureCategory.STORAGE_QUOTA, "storageQuotaExceeded")
        assertFailure(403, DriveTransportFailureCategory.RETRYABLE, "rateLimitExceeded")
        assertFailure(403, DriveTransportFailureCategory.RETRYABLE, "userRateLimitExceeded")
        assertFailure(429, DriveTransportFailureCategory.RETRYABLE)
        assertFailure(500, DriveTransportFailureCategory.RETRYABLE)
        assertFailure(503, DriveTransportFailureCategory.RETRYABLE)
        assertFailure(400, DriveTransportFailureCategory.PROVIDER_REJECTED)
        assertFailure(200, DriveTransportFailureCategory.CORRUPT_RESPONSE, responseBody = "{}")
    }

    @Test
    fun closeDropsTheOnlyTokenReferenceAndFailuresExposeNoPrivateTransportData() = runBlocking {
        val privateToken = "private-token"
        val privateId = "private-file-id"
        val transport = HttpCreateOnlyDriveTransport(privateToken, QueueConnectionFactory())
        transport.close()

        val failure = assertTransportFailure {
            transport.downloadFile(privateId, File("unused"), 1)
        }

        assertEquals(DriveTransportFailureCategory.AUTHORIZATION, failure.category)
        val visible = listOf(failure.message.orEmpty(), failure.toString(), transport.recordedFailureText).joinToString()
        assertFalse(visible.contains(privateToken))
        assertFalse(visible.contains(privateId))
        assertFalse(visible.contains("https://"))
    }

    private suspend fun assertFailure(
        status: Int,
        category: DriveTransportFailureCategory,
        reason: String? = null,
        responseBody: String = "private provider body",
    ) {
        val body = reason?.let {
            """{"error":{"errors":[{"reason":"$it"}],"message":"private"}}"""
        } ?: responseBody
        val failure = assertTransportFailure {
            transportReturning(status, body).readCurrentUserPermissionId()
        }
        assertEquals(category, failure.category)
        assertFalse(failure.toString().contains("private"))
    }

    private suspend fun assertTransportFailure(block: suspend () -> Unit): DriveTransportException =
        try {
            block()
            throw AssertionError("Expected DriveTransportException")
        } catch (exception: DriveTransportException) {
            exception
        }

    private fun createRequest(id: String, content: ByteArray) = DriveCreateRequest(
        DriveFileMetadata(id, "qualification", "claim", mapOf("epoch" to "2")),
        content,
    )

    private fun transportReturning(
        status: Int,
        responseBody: String = "",
    ): HttpCreateOnlyDriveTransport = transportReturning(FakeConnection(status, responseBody))

    private fun transportReturning(connection: FakeConnection): HttpCreateOnlyDriveTransport =
        HttpCreateOnlyDriveTransport("token", QueueConnectionFactory(connection))

    private class QueueConnectionFactory(vararg connections: FakeConnection) : DriveConnectionFactory {
        private val queued = ArrayDeque(connections.toList())
        val opened = mutableListOf<FakeConnection>()
        val openedUrls = mutableListOf<URL>()

        override fun open(url: URL): HttpURLConnection {
            val connection = checkNotNull(queued.removeFirstOrNull()) { "Unexpected connection" }
            opened += connection
            openedUrls += url
            return connection
        }
    }

    private class FakeConnection(
        private val status: Int,
        private val responseBody: String = "",
        private val media: ByteArray = responseBody.encodeToByteArray(),
        private val headers: Map<String, String> = emptyMap(),
        private val responseFailure: IOException? = null,
        private val declaredLength: Long = -1,
    ) : HttpURLConnection(URL("https://unused.invalid")) {
        val requestProperties = linkedMapOf<String, String>()
        private val output = ByteArrayOutputStream()
        private val error = ByteArrayInputStream(responseBody.encodeToByteArray())

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun setRequestMethod(method: String) {
            this.method = method
        }
        override fun getResponseCode(): Int = responseFailure?.let { throw it } ?: status
        override fun getInputStream() = ByteArrayInputStream(media)
        override fun getErrorStream() = error
        override fun getHeaderField(name: String): String? = headers[name]
        override fun getContentLengthLong(): Long = declaredLength
        override fun setRequestProperty(key: String, value: String) {
            requestProperties[key] = value
        }
        override fun getOutputStream() = output
        fun requestBytes(): ByteArray = output.toByteArray()
    }
}
