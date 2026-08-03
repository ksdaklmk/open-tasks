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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveCreateOnlyQualificationTest {
    @Test
    fun tenRacesHaveOneWinnerThirtyRejectedRetriesAndUnchangedAuthenticatedReadbacks() = runBlocking {
        val transport = FakeCreateOnlyDriveTransport()
        val qualification = DriveCreateOnlyQualification(
            transport = transport,
            directory = temporaryDirectory(),
            keySupplier = { ByteArray(32) { 7 } },
        )

        val results = qualification.run()

        assertTrue(results.all(QualificationResult::passed))
        assertEquals(10, transport.raceSuccessorCreates)
        assertEquals(10, transport.raceSuccessorFirstConflicts)
        assertEquals(30, transport.raceSuccessorRetryConflicts)
        assertEquals(30, transport.winnerReadbacks)
        assertTrue(results.any { it.property == "TEN_CREATE_ONLY_RACES" })
        assertTrue(results.any { it.property == "THIRTY_LOSER_RETRIES" })
        assertTrue(results.any { it.property == "UNCHANGED_AUTHENTICATED_WINNERS" })
    }

    @Test
    fun discardedSuccessfulResponseResolvesOnlyByAuthenticatedExactId() = runBlocking {
        val transport = FakeCreateOnlyDriveTransport()
        val request = DriveCreateRequest(
            DriveFileMetadata(
                providerFileId = "exact-generated-id",
                name = "qualification",
                role = "claim",
                appProperties = mapOf("epoch" to "2"),
            ),
            content = byteArrayOf(1, 2, 3),
        )

        assertEquals(
            DriveCreateResult.Ambiguous,
            QualificationCreateFacade(transport).createAndDeliberatelyDiscardCreatedResult(request),
        )
        assertEquals(byteArrayOf(1, 2, 3).toList(), transport.contentAt("exact-generated-id")?.toList())
    }

    @Test
    fun everyGeneratedDisposableIdIsDeletedEvenWhenAuthenticationFails() = runBlocking {
        val transport = FakeCreateOnlyDriveTransport(corruptFirstDownload = true)
        val qualification = DriveCreateOnlyQualification(
            transport = transport,
            directory = temporaryDirectory(),
            keySupplier = { ByteArray(32) { 9 } },
        )

        val results = qualification.run()

        assertTrue(results.any { !it.passed })
        assertEquals(transport.generatedIds.toSet(), transport.deleteAttempts.toSet())
        assertTrue(transport.remainingIds().isEmpty())
    }

    @Test
    fun attachmentChunksAndManifestCreateReadBackAndResolveAtExactIds() = runBlocking {
        val transport = FakeCreateOnlyDriveTransport()
        val qualification = DriveCreateOnlyQualification(
            transport = transport,
            directory = temporaryDirectory(),
            keySupplier = { ByteArray(32) { 3 } },
        )

        val results = qualification.run()

        assertTrue(results.all(QualificationResult::passed))
        assertTrue(results.any { it.property == "ATTACHMENT_EXACT_ID_CHUNK_CREATE" })
        assertTrue(results.any { it.property == "ATTACHMENT_CHUNK_READBACK_IDENTITY" })
        assertTrue(results.any { it.property == "ATTACHMENT_MANIFEST_CREATE_READBACK_LOOKUP" })
        assertEquals(2, transport.attachmentChunkCreates)
        assertEquals(1, transport.attachmentChunkConflicts)
        assertEquals(1, transport.attachmentManifestCreates)
        assertEquals(3, transport.attachmentReadbacks)
        assertTrue(transport.remainingIds().isEmpty())
    }

    @Test
    fun attachmentReadbackThatIsNotByteIdenticalFailsAndStillDeletesEveryObject() = runBlocking {
        val transport = FakeCreateOnlyDriveTransport(corruptAttachmentDownloads = true)

        val results = DriveCreateOnlyQualification(
            transport = transport,
            directory = temporaryDirectory(),
            keySupplier = { ByteArray(32) { 4 } },
        ).run()

        assertTrue(
            results.any {
                !it.passed && it.property == "EXCEPTION_ATTACHMENT_CHUNK_READBACK_IllegalStateException"
            },
        )
        assertEquals(transport.generatedIds.toSet(), transport.deleteAttempts.toSet())
        assertTrue(transport.remainingIds().isEmpty())
    }

    @Test
    fun interruptedAttachmentObjectsInTheReservedLineageAreRemovedBeforeAnotherRun() = runBlocking {
        val transport = FakeCreateOnlyDriveTransport().also {
            it.seedStaleAttachmentChunk("stale-attachment-chunk")
        }

        val results = DriveCreateOnlyQualification(
            transport = transport,
            directory = temporaryDirectory(),
            keySupplier = { ByteArray(32) { 6 } },
        ).run()

        assertTrue(results.all(QualificationResult::passed))
        assertTrue("stale-attachment-chunk" in transport.deleteAttempts)
        assertTrue(transport.remainingIds().isEmpty())
    }

    @Test
    fun interruptedQualificationObjectsAreRemovedBeforeAnotherRun() = runBlocking {
        val transport = FakeCreateOnlyDriveTransport().also {
            it.seedStaleQualification("stale-qualification")
        }

        val results = DriveCreateOnlyQualification(
            transport = transport,
            directory = temporaryDirectory(),
            keySupplier = { ByteArray(32) { 5 } },
        ).run()

        assertTrue(results.all(QualificationResult::passed))
        assertTrue("stale-qualification" in transport.deleteAttempts)
        assertTrue(transport.remainingIds().isEmpty())
    }

    @Test
    fun propertyNamesAndFailureDiagnosticsAreBoundedAndContainNoExceptionMessage() {
        val diagnostic = DriveCreateOnlyQualification.failureDiagnostic(
            stage = "RACE_WINNER_READBACK",
            throwable = DriveTransportException(DriveTransportFailureCategory.AUTHORIZATION),
        )
        val exceptionDiagnostic = DriveCreateOnlyQualification.failureDiagnostic(
            stage = "DISCARDED_SUCCESS",
            throwable = IllegalStateException("private-token-or-provider-id"),
        )

        assertEquals("TRANSPORT_RACE_WINNER_READBACK_AUTHORIZATION", diagnostic)
        assertEquals("EXCEPTION_DISCARDED_SUCCESS_IllegalStateException", exceptionDiagnostic)
        listOf(diagnostic, exceptionDiagnostic).forEach { property ->
            assertTrue(property.length <= 80)
            assertTrue(property.matches(Regex("[A-Za-z0-9_]+")))
            assertFalse(property.contains("private"))
        }
    }

    private fun temporaryDirectory(): File =
        kotlin.io.path.createTempDirectory("drive-create-only-qualification").toFile().apply {
            deleteOnExit()
        }

    private class FakeCreateOnlyDriveTransport(
        private val corruptFirstDownload: Boolean = false,
        private val corruptAttachmentDownloads: Boolean = false,
    ) : CreateOnlyDriveTransport {
        private val nextId = AtomicInteger()
        private val content = ConcurrentHashMap<String, ByteArray>()
        private val metadata = ConcurrentHashMap<String, DriveFileMetadata>()
        private val successorCreateCounts = ConcurrentHashMap<String, AtomicInteger>()
        private val downloadCount = AtomicInteger()

        val generatedIds = mutableListOf<String>()
        val deleteAttempts = mutableListOf<String>()
        var raceSuccessorCreates = 0
            private set
        var raceSuccessorFirstConflicts = 0
            private set
        var raceSuccessorRetryConflicts = 0
            private set
        var winnerReadbacks = 0
            private set
        var attachmentChunkCreates = 0
            private set
        var attachmentChunkConflicts = 0
            private set
        var attachmentManifestCreates = 0
            private set
        var attachmentReadbacks = 0
            private set

        override suspend fun readCurrentUserPermissionId(): String = "permission-for-test"

        override suspend fun generateAppDataFileIds(count: Int): List<String> =
            List(count) { "generated-${nextId.incrementAndGet()}" }.also { ids ->
                synchronized(generatedIds) { generatedIds += ids }
            }

        override suspend fun listAppDataFiles(
            query: String,
            pageToken: String?,
            pageSize: Int,
        ): DriveListPage = DriveListPage(
            metadata.values.filter { it.matches(query) }.map {
                DriveListedFile(it.providerFileId, it.name, it.role, it.appProperties)
            },
            null,
        )

        private fun DriveFileMetadata.matches(query: String): Boolean {
            val nameClause = NAME_CLAUSE.find(query)?.groupValues?.get(1)?.let(::unescape)
            if (nameClause != null && name != nameClause) return false
            return PROPERTY_CLAUSE.findAll(query).all { match ->
                appProperties[unescape(match.groupValues[1])] == unescape(match.groupValues[2])
            }
        }

        private fun unescape(value: String): String = value.replace(Regex("""\\(.)"""), "$1")

        override suspend fun createFileIfAbsent(request: DriveCreateRequest): DriveCreateResult {
            val copy = request.content.copyOf()
            val previous = content.putIfAbsent(request.metadata.providerFileId, copy)
            val isRaceSuccessor =
                request.metadata.role == "successor" &&
                    request.metadata.appProperties["claimId"] != "discarded-success"
            if (previous == null) {
                metadata[request.metadata.providerFileId] = request.metadata
                synchronized(this) {
                    if (isRaceSuccessor) raceSuccessorCreates++
                    if (request.metadata.role == "attachment-chunk") attachmentChunkCreates++
                    if (request.metadata.role == "attachment-manifest") attachmentManifestCreates++
                }
                return DriveCreateResult.Created
            }
            copy.fill(0)
            if (request.metadata.role == "attachment-chunk") {
                synchronized(this) { attachmentChunkConflicts++ }
            }
            if (isRaceSuccessor) {
                val count = successorCreateCounts
                    .computeIfAbsent(request.metadata.providerFileId) { AtomicInteger() }
                    .incrementAndGet()
                synchronized(this) {
                    if (count == 1) raceSuccessorFirstConflicts++ else raceSuccessorRetryConflicts++
                }
            }
            return DriveCreateResult.AlreadyExists
        }

        override suspend fun downloadFile(
            providerFileId: String,
            destination: File,
            maximumBytes: Long,
        ): DriveDownloadReceipt {
            val stored = checkNotNull(content[providerFileId]).copyOf()
            if (corruptFirstDownload && downloadCount.getAndIncrement() == 0) {
                stored[0] = (stored[0].toInt() xor 1).toByte()
            }
            val isAttachment = metadata[providerFileId]?.role?.startsWith("attachment-") == true
            if (isAttachment) {
                synchronized(this) { attachmentReadbacks++ }
                if (corruptAttachmentDownloads) stored[0] = (stored[0].toInt() xor 1).toByte()
            }
            destination.writeBytes(stored)
            stored.fill(0)
            if (successorCreateCounts.containsKey(providerFileId)) {
                synchronized(this) { winnerReadbacks++ }
            }
            return DriveDownloadReceipt(destination.length())
        }

        override suspend fun startResumableCreate(
            metadata: DriveFileMetadata,
            totalBytes: Long,
        ): DriveResumableSession = error("not used")

        override suspend fun queryResumableUpload(
            sessionUri: String,
            totalBytes: Long,
        ): DriveChunkResult = error("not used")

        override suspend fun uploadChunk(
            sessionUri: String,
            firstByte: Long,
            totalBytes: Long,
            content: ByteArray,
        ): DriveChunkResult = error("not used")

        override suspend fun deleteFile(providerFileId: String): Boolean {
            synchronized(deleteAttempts) { deleteAttempts += providerFileId }
            metadata.remove(providerFileId)
            return content.remove(providerFileId)?.also { it.fill(0) } != null
        }

        override fun close() = Unit

        fun contentAt(id: String): ByteArray? = content[id]?.copyOf()
        fun remainingIds(): Set<String> = content.keys.toSet()

        fun seedStaleQualification(id: String) {
            content[id] = byteArrayOf(1)
            metadata[id] = DriveFileMetadata(
                providerFileId = id,
                name = "stage3-drive-create-only-qualification",
                role = "successor",
                appProperties = mapOf(
                    "format" to "open-tasks-create-only-qualification-v1",
                    "role" to "successor",
                ),
            )
        }

        fun seedStaleAttachmentChunk(id: String) {
            content[id] = byteArrayOf(1)
            metadata[id] = DriveFileMetadata(
                providerFileId = id,
                name = "attachment-chunk",
                role = "attachment-chunk",
                appProperties = mapOf(
                    "format" to "v1",
                    "role" to "attachment-chunk",
                    "lineageId" to QUALIFICATION_LINEAGE_ID,
                    "blobSetId" to "1c0ffee0-0000-4000-8000-000000000009",
                    "chunkIndex" to "0",
                ),
            )
        }

        private companion object {
            const val QUALIFICATION_LINEAGE_ID = "0e57a11f-0000-4000-8000-000000000004"
            val NAME_CLAUSE = Regex("""name = '((?:[^'\\]|\\.)*)'""")
            val PROPERTY_CLAUSE =
                Regex("""key='((?:[^'\\]|\\.)*)' and value='((?:[^'\\]|\\.)*)'""")
        }
    }
}
