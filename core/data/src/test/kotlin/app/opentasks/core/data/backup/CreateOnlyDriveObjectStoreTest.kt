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
import app.opentasks.core.data.backup.drive.DriveTransportException
import app.opentasks.core.data.backup.drive.DriveTransportFailureCategory
import app.opentasks.core.domain.CreateSmallResult
import app.opentasks.core.domain.DeleteObjectResult
import app.opentasks.core.domain.ImmutableDownloadResult
import app.opentasks.core.domain.ImmutableUploadRequest
import app.opentasks.core.domain.ImmutableUploadResult
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.OwnedRemoteFile
import app.opentasks.core.domain.ReadSmallResult
import app.opentasks.core.domain.RemoteBackupObject
import app.opentasks.core.domain.RemoteListRequest
import app.opentasks.core.domain.RemoteListedObject
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.RemoteObjectLifecycle
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.WriterEpoch
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateOnlyDriveObjectStoreTest {

    @Test
    fun pageRequestsRejectOversizedPageTokenAndOutOfBoundsPageSize() = runStoreTest { store, _, _, _ ->
        val oversizedToken = "x".repeat(1_025)
        val oversizedError = assertFailure { store.list(listRequest(pageToken = oversizedToken)) }
        assertTrue(oversizedError is IllegalArgumentException)

        val zeroSizeError = assertFailure { store.list(listRequest(pageSize = 0)) }
        assertTrue(zeroSizeError is IllegalArgumentException)

        val overCapError = assertFailure { store.list(listRequest(pageSize = 101)) }
        assertTrue(overCapError is IllegalArgumentException)

        // A request at the exact bound is accepted.
        val page = store.list(listRequest(pageSize = 100, pageToken = "x".repeat(1_024)))
        assertTrue(page.objects.isEmpty())
    }

    @Test
    fun listBuildsBoundedQueryAndMapsAppPropertiesToListedObjects() = runStoreTest { store, transport, _, _ ->
        transport.listPage = DriveListPage(
            files = listOf(
                DriveListedFile(
                    providerFileId = "listed-a",
                    name = "segment",
                    role = "SEGMENT",
                    appProperties = mapOf(
                        "format" to "v1",
                        "role" to "SEGMENT",
                        "lineageId" to LINEAGE.value,
                        "logicalObjectId" to "logical-a",
                        "epoch" to "3",
                        "ownerDeviceId" to DEVICE.value,
                    ),
                ),
                DriveListedFile(
                    providerFileId = "listed-b",
                    name = "segment",
                    role = "not-a-real-role",
                    appProperties = mapOf("epoch" to "not-a-number"),
                ),
            ),
            nextPageToken = "next-token",
        )

        val page = store.list(
            RemoteListRequest(
                lineageId = LINEAGE,
                role = RemoteObjectRoleV1.SEGMENT,
                writerEpoch = WriterEpoch(3),
                ownerDeviceId = DEVICE,
                pageToken = "page-token",
                pageSize = 42,
            ),
        )

        assertEquals(1, transport.listCalls.size)
        val call = transport.listCalls.single()
        assertEquals("page-token", call.pageToken)
        assertEquals(42, call.pageSize)
        assertTrue(call.query.contains("key='role' and value='SEGMENT'"))
        assertTrue(call.query.contains("key='lineageId' and value='${LINEAGE.value}'"))
        assertTrue(call.query.contains("key='epoch' and value='3'"))
        assertTrue(call.query.contains("key='ownerDeviceId' and value='${DEVICE.value}'"))

        assertEquals("next-token", page.nextPageToken)
        assertEquals(
            RemoteListedObject(
                providerObjectId = ProviderObjectId.of("listed-a"),
                logicalObjectId = "logical-a",
                role = RemoteObjectRoleV1.SEGMENT,
                writerEpoch = WriterEpoch(3),
                ownerDeviceId = DEVICE,
            ),
            page.objects[0],
        )
        // Malformed advisory metadata degrades to null fields instead of throwing;
        // Drive JSON is an index only, never authority.
        assertEquals(
            RemoteListedObject(
                providerObjectId = ProviderObjectId.of("listed-b"),
                logicalObjectId = null,
                role = null,
                writerEpoch = null,
                ownerDeviceId = null,
            ),
            page.objects[1],
        )
    }

    @Test
    fun generatedIdsDelegateToTransportAndBoundSingletonRolesToExactlyOne() = runStoreTest { store, transport, _, _ ->
        transport.generatedIds.addAll(listOf("id-1", "id-2"))

        val ids = store.generateProviderIds(2, RemoteObjectRoleV1.SEGMENT)

        assertEquals(listOf(ProviderObjectId.of("id-1"), ProviderObjectId.of("id-2")), ids)

        val singletonError = assertFailure {
            store.generateProviderIds(2, RemoteObjectRoleV1.OWNERSHIP_CLAIM)
        }
        assertTrue(singletonError is IllegalArgumentException)

        val zeroError = assertFailure { store.generateProviderIds(0, RemoteObjectRoleV1.SEGMENT) }
        assertTrue(zeroError is IllegalArgumentException)
    }

    @Test
    fun smallCreateMapsTransportResultsOneForOneAndTransfersBytesExactlyOnce() = runStoreTest { store, transport, _, _ ->
        val cases = listOf(
            DriveCreateResult.Created to CreateSmallResult.Created,
            DriveCreateResult.AlreadyExists to CreateSmallResult.AlreadyExists,
            DriveCreateResult.Ambiguous to CreateSmallResult.Ambiguous,
        )
        cases.forEach { (transportResult, expected) ->
            transport.createResults.add(transportResult)
            val bytes = ownedBytes("small-object".toByteArray())

            val result = store.createSmallIfAbsent(
                ProviderObjectId.of("small-id"),
                RemoteListedObject(
                    providerObjectId = ProviderObjectId.of("small-id"),
                    logicalObjectId = "logical-small",
                    role = RemoteObjectRoleV1.PUBLICATION,
                    writerEpoch = null,
                    ownerDeviceId = DEVICE,
                ),
                bytes,
            )

            assertEquals(expected, result)
            assertThrows(IllegalStateException::class.java) { bytes.take() }
        }
        assertEquals(3, transport.createCalls.size)
        assertFalse(transport.createCalls[0].metadata.appProperties.containsKey("role"))
    }

    @Test
    fun smallCreateRejectsMismatchedMetadataAndMapsTransportFailures() = runStoreTest { store, transport, _, _ ->
        val mismatchError = assertFailure {
            store.createSmallIfAbsent(
                ProviderObjectId.of("small-id"),
                RemoteListedObject(
                    providerObjectId = ProviderObjectId.of("different-id"),
                    logicalObjectId = null,
                    role = RemoteObjectRoleV1.PUBLICATION,
                    writerEpoch = null,
                    ownerDeviceId = null,
                ),
                ownedBytes("content".toByteArray()),
            )
        }
        assertTrue(mismatchError is IllegalArgumentException)

        val missingRoleError = assertFailure {
            store.createSmallIfAbsent(
                ProviderObjectId.of("small-id"),
                RemoteListedObject(ProviderObjectId.of("small-id"), null, null, null, null),
                ownedBytes("content".toByteArray()),
            )
        }
        assertTrue(missingRoleError is IllegalArgumentException)

        transport.createResults.add(
            DriveTransportException(DriveTransportFailureCategory.AUTHORIZATION),
        )
        val result = store.createSmallIfAbsent(
            ProviderObjectId.of("small-id"),
            RemoteListedObject(
                providerObjectId = ProviderObjectId.of("small-id"),
                logicalObjectId = null,
                role = RemoteObjectRoleV1.PUBLICATION,
                writerEpoch = null,
                ownerDeviceId = null,
            ),
            ownedBytes("content".toByteArray()),
        )
        assertEquals(
            CreateSmallResult.Failed(RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED),
            result,
        )
    }

    @Test
    fun readSmallReturnsExactBytesAndCleansPrivateStaging() = runStoreTest { store, transport, _, stagingRoot ->
        val expected = "recovered-small-object".toByteArray()
        transport.remoteObjects["small-id"] = expected

        val result = store.readSmall(ProviderObjectId.of("small-id"), maximumBytes = 1_024)

        assertTrue(result is ReadSmallResult.Found)
        val found = result as ReadSmallResult.Found
        assertEquals(expected.size, found.bytes.size)
        assertArrayEquals(expected, found.bytes.take())
        assertThrows(IllegalStateException::class.java) { found.bytes.take() }
        assertNoStagedFilesRemain(stagingRoot)
    }

    @Test
    fun readSmallMapsMissingAndFailedCategories() = runStoreTest { store, transport, _, _ ->
        val missing = store.readSmall(ProviderObjectId.of("absent-id"), maximumBytes = 1_024)
        assertEquals(ReadSmallResult.Missing, missing)

        transport.downloadOverrides.add(failure(DriveTransportFailureCategory.RETRYABLE))
        val failed = store.readSmall(ProviderObjectId.of("any-id"), maximumBytes = 1_024)
        assertEquals(ReadSmallResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER), failed)
    }

    @Test
    fun multipartUploadPersistsBeforeNetworkMutationThenVerifiesLengthAndDigest() = runBlocking {
        withTimeout(5_000) {
            val root = Files.createTempDirectory("create-only-drive-object-store-test").toFile()
            try {
                val events = mutableListOf<String>()
                val transport = FakeCreateOnlyDriveTransport(events)
                val transferStore = InMemoryRemoteBackupTransferStore(events)
                val store = CreateOnlyDriveObjectStore(transport, transferStore, root)
                val bytes = ByteArray(1_024) { it.toByte() }
                val request = uploadRequest(bytes, root)
                transport.createResults.add(DriveCreateResult.Created)
                transport.remoteObjects[request.providerObjectId.value] = bytes

                val result = store.uploadImmutable(request)

                assertEquals(ImmutableUploadResult.UploadedAndVerified, result)
                assertEquals(
                    listOf(
                        "insert:${request.logicalObjectId.value}",
                        "create:${request.providerObjectId.value}",
                    ),
                    events,
                )
                val persisted = checkNotNull(
                    transferStore.objectState(request.lineageId, request.logicalObjectId),
                )
                assertEquals(RemoteObjectLifecycle.VERIFIED, persisted.lifecycle)
                assertTrue(persisted.verifiedAt != null)
                assertNoStagedFilesRemain(root)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun resumableUploadAboveThresholdUsesTwoHundredFiftySixKibNonFinalChunks() = runStoreTest { store, transport, _, root ->
        val bytes = ByteArray(MULTIPART_THRESHOLD_BYTES.toInt() + 100 * 1024) {
            (it % 251).toByte()
        }
        val request = uploadRequest(bytes, root)
        transport.resumableSessions.add(DriveResumableSession("session-a"))
        transport.remoteObjects[request.providerObjectId.value] = bytes

        val result = store.uploadImmutable(request)

        assertEquals(ImmutableUploadResult.UploadedAndVerified, result)
        assertTrue(transport.chunkCalls.isNotEmpty())
        val nonFinal = transport.chunkCalls.dropLast(1)
        val final = transport.chunkCalls.last()
        assertTrue(nonFinal.all { it.size == CHUNK_SIZE_BYTES.toInt() })
        assertEquals(bytes.size - nonFinal.size * CHUNK_SIZE_BYTES.toInt(), final.size)
        // Offsets form a contiguous, gap-free cover of the whole frame.
        var expectedOffset = 0L
        transport.chunkCalls.forEach { call ->
            assertEquals(expectedOffset, call.firstByte)
            expectedOffset += call.size
        }
        assertEquals(bytes.size.toLong(), expectedOffset)
    }

    @Test
    fun resumableUploadResumesFromProviderConfirmedOffsetNotLocalCache() = runStoreTest { store, transport, transferStore, root ->
        val bytes = ByteArray(MULTIPART_THRESHOLD_BYTES.toInt() + 50 * 1024) { (it % 199).toByte() }
        val request = uploadRequest(bytes, root)
        val staleLocalOffset = 20_000L
        val providerConfirmedOffset = 400_000L
        transferStore.insertObject(
            plannedObject(request).copy(
                resumableSessionUri = "existing-session",
                uploadedBytes = staleLocalOffset,
                lifecycle = RemoteObjectLifecycle.UPLOADING,
            ),
        )
        transport.queryResults.add(DriveChunkResult.ResumeAt(providerConfirmedOffset))
        transport.remoteObjects[request.providerObjectId.value] = bytes

        val result = store.uploadImmutable(request)

        assertEquals(ImmutableUploadResult.UploadedAndVerified, result)
        assertEquals(listOf("existing-session"), transport.queryCalls)
        assertTrue(transport.resumableStartCalls.isEmpty())
        assertEquals(providerConfirmedOffset, transport.chunkCalls.first().firstByte)
    }

    @Test
    fun expiredResumableSessionRestartsWithTheSameGeneratedId() = runStoreTest { store, transport, transferStore, root ->
        val bytes = ByteArray(MULTIPART_THRESHOLD_BYTES.toInt() + 10 * 1024) { (it % 173).toByte() }
        val request = uploadRequest(bytes, root)
        transferStore.insertObject(
            plannedObject(request).copy(
                resumableSessionUri = "expired-session",
                uploadedBytes = 40_000L,
                lifecycle = RemoteObjectLifecycle.UPLOADING,
            ),
        )
        transport.queryResults.add(DriveChunkResult.Expired)
        transport.resumableSessions.add(DriveResumableSession("restarted-session"))
        transport.remoteObjects[request.providerObjectId.value] = bytes

        val result = store.uploadImmutable(request)

        assertEquals(ImmutableUploadResult.UploadedAndVerified, result)
        assertEquals(listOf("expired-session"), transport.queryCalls)
        assertEquals(1, transport.resumableStartCalls.size)
        assertEquals(
            request.providerObjectId.value,
            transport.resumableStartCalls.single().providerFileId,
        )
        assertEquals("restarted-session", transport.chunkCalls.first().sessionUri)
        assertEquals(0L, transport.chunkCalls.first().firstByte)
    }

    @Test
    fun ambiguousCreateResolvesOnlyThroughExactIdBytes() = runStoreTest { store, transport, _, root ->
        val expectedBytes = "expected-exact-bytes".toByteArray()
        val request = uploadRequest(expectedBytes, root)
        transport.createResults.add(DriveCreateResult.Ambiguous)
        transport.remoteObjects[request.providerObjectId.value] = expectedBytes

        val result = store.uploadImmutable(request)

        assertEquals(ImmutableUploadResult.OccupiedByExpectedBytes, result)
        assertEquals(listOf(request.providerObjectId.value), transport.downloadCalls)
    }

    @Test
    fun occupiedByDifferentBytesIsNeverOverwritten() = runStoreTest { store, transport, transferStore, root ->
        val requestedBytes = "our-expected-bytes".toByteArray()
        val request = uploadRequest(requestedBytes, root)
        // Same exact length as the request (so the bounded download succeeds) but
        // different content, so the SHA-256 comparison — not the length check — is
        // what proves this is occupied by someone else's bytes.
        val differentBytes = requestedBytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
        transport.createResults.add(DriveCreateResult.AlreadyExists)
        transport.remoteObjects[request.providerObjectId.value] = differentBytes

        val result = store.uploadImmutable(request)

        assertEquals(ImmutableUploadResult.OccupiedByDifferentBytes, result)
        val persisted = checkNotNull(
            transferStore.objectState(request.lineageId, request.logicalObjectId),
        )
        assertNull(persisted.verifiedAt)
        assertEquals(RemoteObjectLifecycle.PLANNED, persisted.lifecycle)
    }

    @Test
    fun downloadImmutableVerifiesExactDigestAndReturnsCorruptOnMismatch() = runStoreTest { store, transport, _, root ->
        val bytes = "downloadable-frame".toByteArray()
        val expectedDigest = Sha256Digest.of(sha256Hex(bytes))
        transport.remoteObjects["matching-id"] = bytes
        transport.remoteObjects["mismatched-id"] = "different-content".toByteArray()

        val downloaded =
            store.downloadImmutable(ProviderObjectId.of("matching-id"), 1_024, expectedDigest)
        assertTrue(downloaded is ImmutableDownloadResult.Downloaded)
        val frame = (downloaded as ImmutableDownloadResult.Downloaded).frame
        val stagedFile = frame.file
        assertArrayEquals(bytes, stagedFile.readBytes())
        frame.close()
        assertFalse(stagedFile.exists())
        assertThrows(IllegalStateException::class.java) { frame.file }

        val corrupt =
            store.downloadImmutable(ProviderObjectId.of("mismatched-id"), 1_024, expectedDigest)
        assertEquals(ImmutableDownloadResult.Corrupt, corrupt)
        assertNoStagedFilesRemain(root)
    }

    @Test
    fun downloadImmutableMapsMissingAndFailedCategories() = runStoreTest { store, transport, _, _ ->
        val digest = Sha256Digest.of(sha256Hex("unused".toByteArray()))
        val missing = store.downloadImmutable(ProviderObjectId.of("absent-id"), 1_024, digest)
        assertEquals(ImmutableDownloadResult.Missing, missing)

        transport.downloadOverrides.add(failure(DriveTransportFailureCategory.STORAGE_QUOTA))
        val failed = store.downloadImmutable(ProviderObjectId.of("any-id"), 1_024, digest)
        assertEquals(
            ImmutableDownloadResult.Failed(RemoteBackupFailureCategory.PROVIDER_STORAGE),
            failed,
        )
    }

    @Test
    fun deletePermanentlyRemovesAndMapsMissingAndFailed() = runStoreTest { store, transport, _, _ ->
        transport.deleteResults.add(true)
        assertEquals(DeleteObjectResult.Deleted, store.delete(ProviderObjectId.of("present-id")))

        transport.deleteResults.add(false)
        assertEquals(DeleteObjectResult.Missing, store.delete(ProviderObjectId.of("absent-id")))

        transport.deleteResults.add(failure(DriveTransportFailureCategory.RETRYABLE))
        assertEquals(
            DeleteObjectResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
            store.delete(ProviderObjectId.of("flaky-id")),
        )
        assertEquals(listOf("present-id", "absent-id", "flaky-id"), transport.deleteCalls)
    }

    @Test
    fun transportCancellationPropagatesWithoutBecomingAFailureResult() = runStoreTest { store, transport, _, _ ->
        transport.downloadOverrides.add(CancellationException("private-cancellation-detail"))

        var caught: Throwable? = null
        try {
            store.readSmall(ProviderObjectId.of("any-id"), 1_024)
        } catch (cancellation: CancellationException) {
            caught = cancellation
        }
        assertTrue(caught is CancellationException)
    }

    @Test
    fun alreadyVerifiedObjectShortCircuitsWithoutNetworkAccess() = runStoreTest { store, transport, transferStore, root ->
        val bytes = "already-verified".toByteArray()
        val request = uploadRequest(bytes, root)
        transferStore.insertObject(
            plannedObject(request).copy(
                lifecycle = RemoteObjectLifecycle.VERIFIED,
                verifiedAt = java.time.Instant.now(),
            ),
        )

        val result = store.uploadImmutable(request)

        assertEquals(ImmutableUploadResult.UploadedAndVerified, result)
        assertTrue(transport.createCalls.isEmpty())
        assertTrue(transport.resumableStartCalls.isEmpty())
        assertTrue(transport.downloadCalls.isEmpty())
    }

    private fun assertNoStagedFilesRemain(stagingRoot: File) {
        val remaining = stagingRoot.listFiles()?.filter { it.name.endsWith(".otr") }.orEmpty()
        assertTrue(remaining.isEmpty())
    }

    private fun plannedObject(
        request: ImmutableUploadRequest,
    ): RemoteBackupObject = RemoteBackupObject(
        lineageId = request.lineageId,
        logicalObjectId = request.logicalObjectId,
        providerObjectId = request.providerObjectId,
        role = request.role,
        writerEpoch = request.writerEpoch,
        ownerDeviceId = request.ownerDeviceId,
        operationId = request.operationId,
        firstGeneration = request.firstGeneration,
        lastGeneration = request.lastGeneration,
        frameLength = request.frameLength,
        frameSha256 = request.frameSha256,
        lifecycle = RemoteObjectLifecycle.PLANNED,
        resumableSessionUri = null,
        uploadedBytes = 0,
        createdAt = java.time.Instant.now(),
        verifiedAt = null,
    )

    private fun listRequest(
        pageToken: String? = null,
        pageSize: Int = 10,
    ): RemoteListRequest = RemoteListRequest(
        lineageId = LINEAGE,
        role = RemoteObjectRoleV1.SEGMENT,
        writerEpoch = null,
        ownerDeviceId = null,
        pageToken = pageToken,
        pageSize = pageSize,
    )

    private fun uploadRequest(
        bytes: ByteArray,
        frameDir: File,
        lineageId: CloudLineageId = LINEAGE,
        providerObjectId: ProviderObjectId = ProviderObjectId.of("exact-provider-id"),
        logicalObjectId: RemoteLogicalObjectId = RemoteLogicalObjectId.new(),
        operationId: String = "operation-a",
    ): ImmutableUploadRequest = ImmutableUploadRequest(
        lineageId = lineageId,
        writerEpoch = WriterEpoch(1),
        ownerDeviceId = DEVICE,
        operationId = operationId,
        logicalObjectId = logicalObjectId,
        providerObjectId = providerObjectId,
        role = RemoteObjectRoleV1.SEGMENT,
        firstGeneration = BackupGeneration(1),
        lastGeneration = BackupGeneration(1),
        frameLength = bytes.size.toLong(),
        frameSha256 = Sha256Digest.of(sha256Hex(bytes)),
        frame = ownedFile(bytes, frameDir),
    )

    private fun runStoreTest(
        block: suspend (
            store: CreateOnlyDriveObjectStore,
            transport: FakeCreateOnlyDriveTransport,
            transferStore: InMemoryRemoteBackupTransferStore,
            stagingRoot: File,
        ) -> Unit,
    ) = runBlocking {
        withTimeout(5_000) {
            val root = Files.createTempDirectory("create-only-drive-object-store-test").toFile()
            try {
                val transport = FakeCreateOnlyDriveTransport()
                val transferStore = InMemoryRemoteBackupTransferStore()
                val store = CreateOnlyDriveObjectStore(transport, transferStore, root)
                block(store, transport, transferStore, root)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private suspend fun assertFailure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (expected: Throwable) {
            return expected
        }
        throw AssertionError("Expected an exception")
    }

    private companion object {
        val LINEAGE = CloudLineageId.new()
        val DEVICE = CloudDeviceId.new()
        const val MULTIPART_THRESHOLD_BYTES = 5L * 1024 * 1024
        const val CHUNK_SIZE_BYTES = 256L * 1024
    }
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

private fun failure(category: DriveTransportFailureCategory): DriveTransportException =
    DriveTransportException(category)

private fun ownedFile(bytes: ByteArray, directory: File): OwnedRemoteFile {
    directory.mkdirs()
    val backing = File.createTempFile("frame-", ".bin", directory)
    backing.writeBytes(bytes)
    return object : OwnedRemoteFile {
        override val file: File = backing
        override val length: Long get() = backing.length()
        override fun close() {
            backing.delete()
        }
    }
}

private fun ownedBytes(bytes: ByteArray): OwnedRemoteBytes = object : OwnedRemoteBytes {
    private var owned: ByteArray? = bytes
    override val size: Int = bytes.size

    override fun take(): ByteArray {
        val current = checkNotNull(owned) { "already taken" }
        owned = null
        return current
    }

    override fun close() {
        owned?.fill(0)
        owned = null
    }
}

private data class ListCall(val query: String, val pageToken: String?, val pageSize: Int)
private data class ChunkCall(val sessionUri: String, val firstByte: Long, val size: Int)

private inline fun <reified T> ArrayDeque<Any>.nextOrFail(): T {
    val next = removeFirstOrNull() ?: throw AssertionError("Unexpected fake transport call")
    if (next is Throwable) throw next
    return next as T
}

private class FakeCreateOnlyDriveTransport(
    private val events: MutableList<String>? = null,
) : CreateOnlyDriveTransport {
    val remoteObjects = mutableMapOf<String, ByteArray>()
    val createCalls = mutableListOf<DriveCreateRequest>()
    val downloadCalls = mutableListOf<String>()
    val resumableStartCalls = mutableListOf<DriveFileMetadata>()
    val queryCalls = mutableListOf<String>()
    val chunkCalls = mutableListOf<ChunkCall>()
    val deleteCalls = mutableListOf<String>()
    val listCalls = mutableListOf<ListCall>()
    var closed = false
        private set

    val generatedIds = ArrayDeque<Any>()
    val createResults = ArrayDeque<Any>()
    val downloadOverrides = ArrayDeque<Any>()
    val resumableSessions = ArrayDeque<Any>()
    val queryResults = ArrayDeque<Any>()
    val chunkResults = ArrayDeque<Any>()
    val deleteResults = ArrayDeque<Any>()
    var listPage: DriveListPage = DriveListPage(emptyList(), null)

    override suspend fun readCurrentUserPermissionId(): String = "unused-permission-id"

    override suspend fun generateAppDataFileIds(count: Int): List<String> =
        (1..count).map { index ->
            if (generatedIds.isEmpty()) "generated-$index" else generatedIds.nextOrFail()
        }

    override suspend fun listAppDataFiles(
        query: String,
        pageToken: String?,
        pageSize: Int,
    ): DriveListPage {
        listCalls += ListCall(query, pageToken, pageSize)
        return listPage
    }

    override suspend fun createFileIfAbsent(request: DriveCreateRequest): DriveCreateResult {
        createCalls += request
        events?.add("create:${request.metadata.providerFileId}")
        return createResults.nextOrFail()
    }

    override suspend fun downloadFile(
        providerFileId: String,
        destination: File,
        maximumBytes: Long,
    ): DriveDownloadReceipt {
        downloadCalls += providerFileId
        if (downloadOverrides.isNotEmpty()) {
            val override = downloadOverrides.removeFirst()
            if (override is Throwable) throw override
        }
        val bytes = remoteObjects[providerFileId]
            ?: throw failure(DriveTransportFailureCategory.MISSING)
        if (bytes.size > maximumBytes) throw failure(DriveTransportFailureCategory.CORRUPT_RESPONSE)
        destination.writeBytes(bytes)
        return DriveDownloadReceipt(bytes.size.toLong())
    }

    override suspend fun startResumableCreate(
        metadata: DriveFileMetadata,
        totalBytes: Long,
    ): DriveResumableSession {
        resumableStartCalls += metadata
        events?.add("startResumable:${metadata.providerFileId}")
        return resumableSessions.nextOrFail()
    }

    override suspend fun queryResumableUpload(
        sessionUri: String,
        totalBytes: Long,
    ): DriveChunkResult {
        queryCalls += sessionUri
        return queryResults.nextOrFail()
    }

    override suspend fun uploadChunk(
        sessionUri: String,
        firstByte: Long,
        totalBytes: Long,
        content: ByteArray,
    ): DriveChunkResult {
        chunkCalls += ChunkCall(sessionUri, firstByte, content.size)
        if (chunkResults.isNotEmpty()) {
            val override = chunkResults.removeFirst()
            if (override is Throwable) throw override
            @Suppress("UNCHECKED_CAST")
            return override as DriveChunkResult
        }
        val nextByte = firstByte + content.size
        return if (nextByte >= totalBytes) {
            DriveChunkResult.Complete
        } else {
            DriveChunkResult.ResumeAt(nextByte)
        }
    }

    override suspend fun deleteFile(providerFileId: String): Boolean {
        deleteCalls += providerFileId
        return deleteResults.nextOrFail()
    }

    override fun close() {
        closed = true
    }
}

private class InMemoryRemoteBackupTransferStore(
    private val events: MutableList<String>? = null,
) : RemoteBackupTransferStore {
    private val objects = mutableMapOf<Pair<String, String>, RemoteBackupObject>()
    val insertCalls = mutableListOf<RemoteBackupObject>()

    override suspend fun objectState(
        lineageId: CloudLineageId,
        logicalObjectId: RemoteLogicalObjectId,
    ): RemoteBackupObject? = objects[lineageId.value to logicalObjectId.value]

    override suspend fun insertObject(value: RemoteBackupObject) {
        val key = value.lineageId.value to value.logicalObjectId.value
        check(key !in objects) { "Object already exists" }
        objects[key] = value
        insertCalls += value
        events?.add("insert:${value.logicalObjectId.value}")
    }

    override suspend fun compareAndSetObject(
        expected: RemoteBackupObject,
        next: RemoteBackupObject,
    ): Boolean {
        val key = expected.lineageId.value to expected.logicalObjectId.value
        if (objects[key] != expected) return false
        objects[key] = next
        return true
    }

    override suspend fun objectsForLineage(lineageId: CloudLineageId): List<RemoteBackupObject> =
        objects.values.filter { it.lineageId == lineageId }

    override suspend fun removeObjectState(
        lineageId: CloudLineageId,
        logicalObjectId: RemoteLogicalObjectId,
    ): Boolean = objects.remove(lineageId.value to logicalObjectId.value) != null
}
