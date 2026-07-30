package app.opentasks.backup.drive

import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.data.backup.drive.DriveChunkResult
import app.opentasks.core.data.backup.drive.DriveCreateRequest
import app.opentasks.core.data.backup.drive.DriveCreateResult
import app.opentasks.core.data.backup.drive.DriveDownloadReceipt
import app.opentasks.core.data.backup.drive.DriveFileMetadata
import app.opentasks.core.data.backup.drive.DriveListPage
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
    ) : CreateOnlyDriveTransport {
        private val nextId = AtomicInteger()
        private val content = ConcurrentHashMap<String, ByteArray>()
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

        override suspend fun readCurrentUserPermissionId(): String = "permission-for-test"

        override suspend fun generateAppDataFileIds(count: Int): List<String> =
            List(count) { "generated-${nextId.incrementAndGet()}" }.also { ids ->
                synchronized(generatedIds) { generatedIds += ids }
            }

        override suspend fun listAppDataFiles(
            query: String,
            pageToken: String?,
            pageSize: Int,
        ): DriveListPage = DriveListPage(emptyList(), null)

        override suspend fun createFileIfAbsent(request: DriveCreateRequest): DriveCreateResult {
            val copy = request.content.copyOf()
            val previous = content.putIfAbsent(request.metadata.providerFileId, copy)
            val isRaceSuccessor =
                request.metadata.role == "successor" &&
                    request.metadata.appProperties["claimId"] != "discarded-success"
            if (previous == null) {
                if (isRaceSuccessor) {
                    synchronized(this) { raceSuccessorCreates++ }
                }
                return DriveCreateResult.Created
            }
            copy.fill(0)
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
            return content.remove(providerFileId)?.also { it.fill(0) } != null
        }

        override fun close() = Unit

        fun contentAt(id: String): ByteArray? = content[id]?.copyOf()
        fun remainingIds(): Set<String> = content.keys.toSet()
    }
}
