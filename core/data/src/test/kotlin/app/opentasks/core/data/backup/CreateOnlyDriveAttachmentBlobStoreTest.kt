package app.opentasks.core.data.backup

import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.data.backup.drive.DriveChunkResult
import app.opentasks.core.data.backup.drive.DriveCreateRequest
import app.opentasks.core.data.backup.drive.DriveCreateResult
import app.opentasks.core.data.backup.drive.DriveDownloadReceipt
import app.opentasks.core.data.backup.drive.DriveFileMetadata
import app.opentasks.core.data.backup.drive.DriveListPage
import app.opentasks.core.data.backup.drive.DriveListedFile
import app.opentasks.core.data.backup.drive.DriveResumableSession
import app.opentasks.core.domain.AttachmentManifestLookup
import app.opentasks.core.domain.AttachmentObjectResult
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.sync.CloudBounds
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateOnlyDriveAttachmentBlobStoreTest {
    @Test
    fun createMapsOccupiedAndAmbiguousOutcomesAndWritesRequiredTags() = runBlocking {
        val transport = FakeAttachmentDriveTransport().also {
            it.createResults += DriveCreateResult.AlreadyExists
            it.createResults += DriveCreateResult.Ambiguous
        }
        val store = CreateOnlyDriveAttachmentBlobStore(transport, LINEAGE)

        assertEquals(
            AttachmentObjectResult.AlreadyExists,
            store.createChunk(ProviderObjectId.of("chunk-a"), BLOB_SET, 0, 1, byteArrayOf(1)),
        )
        assertEquals(
            AttachmentObjectResult.Ambiguous,
            store.createManifest(ProviderObjectId.of("manifest-a"), BLOB_SET, byteArrayOf(2)),
        )

        val chunk = transport.createCalls[0]
        assertEquals("attachment-chunk", chunk.metadata.name)
        assertEquals("attachment-chunk", chunk.metadata.role)
        assertEquals(
            mapOf(
                "format" to "v1",
                "role" to "attachment-chunk",
                "lineageId" to LINEAGE.value,
                "blobSetId" to BLOB_SET.value,
                "chunkIndex" to "0",
            ),
            chunk.metadata.appProperties,
        )
        assertArrayEquals(byteArrayOf(1), chunk.content)
        assertEquals("attachment-manifest", transport.createCalls[1].metadata.name)
    }

    @Test
    fun objectCeilingsAndChunkTupleAreEnforcedBeforeTransportWork() = runBlocking {
        val transport = FakeAttachmentDriveTransport()
        val store = CreateOnlyDriveAttachmentBlobStore(transport, LINEAGE)
        val chunkCeiling = 4 + CloudBounds.MAX_HEADER_BYTES +
            CloudBounds.MAX_ATTACHMENT_CHUNK_CIPHERTEXT_BYTES_V1
        val manifestCeiling = 4 + CloudBounds.MAX_HEADER_BYTES +
            CloudBounds.MAX_MANIFEST_CIPHERTEXT_BYTES

        store.createChunk(
            ProviderObjectId.of("too-large-chunk"),
            BLOB_SET,
            0,
            1,
            ByteArray((chunkCeiling + 1).toInt()),
        )
        store.createManifest(
            ProviderObjectId.of("too-large-manifest"),
            BLOB_SET,
            ByteArray((manifestCeiling + 1).toInt()),
        )
        assertTrue(transport.createCalls.isEmpty())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.createChunk(
                    ProviderObjectId.of("bad-index"),
                    BLOB_SET,
                    1,
                    1,
                    byteArrayOf(1),
                )
            }
        }
        assertTrue(transport.createCalls.isEmpty())
    }

    @Test
    fun manifestLookupReturnsMissingSingleOrAmbiguous() = runBlocking {
        val transport = FakeAttachmentDriveTransport()
        val store = CreateOnlyDriveAttachmentBlobStore(transport, LINEAGE)

        transport.listPages += DriveListPage(emptyList(), null)
        assertEquals(AttachmentManifestLookup.Missing, store.findManifest(BLOB_SET))

        transport.listPages += DriveListPage(listOf(listed("manifest-a")), null)
        assertEquals(
            AttachmentManifestLookup.Found(ProviderObjectId.of("manifest-a")),
            store.findManifest(BLOB_SET),
        )

        transport.listPages += DriveListPage(
            listOf(listed("manifest-a"), listed("manifest-b")),
            null,
        )
        assertEquals(AttachmentManifestLookup.Ambiguous, store.findManifest(BLOB_SET))
        assertTrue(
            transport.listCalls.last().query.contains(
                "key='blobSetId' and value='${BLOB_SET.value}'",
            ),
        )
        assertTrue(
            transport.listCalls.last().query.contains(
                "key='role' and value='attachment-manifest'",
            ),
        )
    }

    @Test
    fun manifestLookupFailsClosedForUnknownReturnedMetadata() = runBlocking {
        val valid = listed("manifest-a")
        val malformed = listOf(
            valid.copy(name = "future-manifest"),
            valid.copy(role = "future-role"),
            valid.copy(appProperties = valid.appProperties - "format"),
            valid.copy(
                appProperties = valid.appProperties +
                    ("lineageId" to "22222222-2222-2222-8222-222222222222"),
            ),
            valid.copy(
                appProperties = valid.appProperties + ("blobSetId" to "another-blob-set"),
            ),
        )
        val transport = FakeAttachmentDriveTransport().also { fake ->
            malformed.forEach { file ->
                fake.listPages += DriveListPage(listOf(file), null)
            }
        }
        val store = CreateOnlyDriveAttachmentBlobStore(transport, LINEAGE)

        malformed.forEach {
            assertEquals(
                AttachmentManifestLookup.Failed(
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                ),
                store.findManifest(BLOB_SET),
            )
        }
    }

    @Test
    fun manifestLookupPassesLongOpaquePaginationTokenUnchanged() = runBlocking {
        val token = "opaque".repeat(200)
        val transport = FakeAttachmentDriveTransport().also {
            it.listPages += DriveListPage(emptyList(), token)
            it.listPages += DriveListPage(listOf(listed("manifest-a")), null)
        }
        val store = CreateOnlyDriveAttachmentBlobStore(transport, LINEAGE)

        assertEquals(
            AttachmentManifestLookup.Found(ProviderObjectId.of("manifest-a")),
            store.findManifest(BLOB_SET),
        )
        assertEquals(token, transport.listCalls[1].pageToken)
    }

    @Test
    fun manifestLookupEscapesOpaqueBlobSetIdInDriveQuery() = runBlocking {
        val transport = FakeAttachmentDriveTransport().also {
            it.listPages += DriveListPage(emptyList(), null)
        }
        val store = CreateOnlyDriveAttachmentBlobStore(transport, LINEAGE)

        store.findManifest(BlobSetId("blob'set\\part"))

        assertTrue(
            transport.listCalls.single().query.contains(
                "key='blobSetId' and value='blob\\'set\\\\part'",
            ),
        )
    }

    @Test
    fun namespaceListPreservesPaginationAndForeignMetadata() = runBlocking {
        val transport = FakeAttachmentDriveTransport().also {
            it.listPages += DriveListPage(
                listOf(
                    DriveListedFile(
                        providerFileId = "foreign-a",
                        name = "future-object",
                        role = "future-role",
                        appProperties = mapOf(
                            "blobSetId" to "opaque-future-set",
                            "createdAtEpochMillis" to "1234",
                        ),
                    ),
                    DriveListedFile(
                        providerFileId = "foreign-b",
                        name = "unknown",
                        role = null,
                        appProperties = mapOf("createdAtEpochMillis" to "not-a-long"),
                    ),
                ),
                "next-page",
            )
        }
        val store = CreateOnlyDriveAttachmentBlobStore(transport, LINEAGE)

        val (objects, nextPage) = store.listNamespace("current-page")

        assertEquals("next-page", nextPage)
        assertEquals("future-role", objects[0].role)
        assertEquals("opaque-future-set", objects[0].blobSetId)
        assertEquals(1234L, objects[0].createdAtEpochMillis)
        assertEquals(null, objects[1].role)
        assertEquals(null, objects[1].blobSetId)
        assertEquals(null, objects[1].createdAtEpochMillis)
        assertEquals("current-page", transport.listCalls.single().pageToken)
        assertTrue(
            transport.listCalls.single().query.contains(
                "key='lineageId' and value='${LINEAGE.value}'",
            ),
        )
    }

    @Test
    fun namespaceListPassesLongOpaquePaginationTokenUnchanged() = runBlocking {
        val token = "opaque".repeat(200)
        val transport = FakeAttachmentDriveTransport().also {
            it.listPages += DriveListPage(emptyList(), null)
        }
        val store = CreateOnlyDriveAttachmentBlobStore(transport, LINEAGE)

        store.listNamespace(token)

        assertEquals(token, transport.listCalls.single().pageToken)
    }

    @Test
    fun namespaceListAddsExactAttachmentRoleFilter() = runBlocking {
        val transport = FakeAttachmentDriveTransport().also {
            it.listPages += DriveListPage(emptyList(), null)
        }
        val store = CreateOnlyDriveAttachmentBlobStore(transport, LINEAGE)

        store.listNamespace(null, "attachment-chunk")

        assertTrue(
            transport.listCalls.single().query.contains(
                "key='role' and value='attachment-chunk'",
            ),
        )
    }

    private fun listed(id: String) = DriveListedFile(
        providerFileId = id,
        name = "attachment-manifest",
        role = "attachment-manifest",
        appProperties = mapOf(
            "format" to "v1",
            "role" to "attachment-manifest",
            "lineageId" to LINEAGE.value,
            "blobSetId" to BLOB_SET.value,
        ),
    )

    private companion object {
        val LINEAGE = CloudLineageId.parse("11111111-1111-1111-8111-111111111111")
        val BLOB_SET = BlobSetId("blob-set-a")
    }
}

private data class AttachmentListCall(
    val query: String,
    val pageToken: String?,
    val pageSize: Int,
)

private class FakeAttachmentDriveTransport : CreateOnlyDriveTransport {
    val createResults = ArrayDeque<DriveCreateResult>()
    val listPages = ArrayDeque<DriveListPage>()
    val createCalls = mutableListOf<DriveCreateRequest>()
    val listCalls = mutableListOf<AttachmentListCall>()
    val deletedIds = mutableListOf<String>()
    val remoteObjects = mutableMapOf<String, ByteArray>()

    override suspend fun readCurrentUserPermissionId(): String = error("Unexpected call")

    override suspend fun generateAppDataFileIds(count: Int): List<String> =
        (0 until count).map { "generated-$it" }

    override suspend fun listAppDataFiles(
        query: String,
        pageToken: String?,
        pageSize: Int,
    ): DriveListPage {
        listCalls += AttachmentListCall(query, pageToken, pageSize)
        return listPages.removeFirstOrNull() ?: error("Unexpected list call")
    }

    override suspend fun createFileIfAbsent(request: DriveCreateRequest): DriveCreateResult {
        createCalls += request
        return createResults.removeFirstOrNull() ?: error("Unexpected create call")
    }

    override suspend fun downloadFile(
        providerFileId: String,
        destination: File,
        maximumBytes: Long,
    ): DriveDownloadReceipt {
        val bytes = remoteObjects[providerFileId] ?: error("Unexpected download call")
        check(bytes.size <= maximumBytes)
        destination.writeBytes(bytes)
        return DriveDownloadReceipt(bytes.size.toLong())
    }

    override suspend fun startResumableCreate(
        metadata: DriveFileMetadata,
        totalBytes: Long,
    ): DriveResumableSession = error("Unexpected call")

    override suspend fun queryResumableUpload(
        sessionUri: String,
        totalBytes: Long,
    ): DriveChunkResult = error("Unexpected call")

    override suspend fun uploadChunk(
        sessionUri: String,
        firstByte: Long,
        totalBytes: Long,
        content: ByteArray,
    ): DriveChunkResult = error("Unexpected call")

    override suspend fun deleteFile(providerFileId: String): Boolean {
        deletedIds += providerFileId
        return true
    }

    override fun close() = Unit
}
