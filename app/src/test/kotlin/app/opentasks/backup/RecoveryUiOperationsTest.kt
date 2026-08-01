package app.opentasks.backup

import android.content.Intent
import app.opentasks.backup.drive.AuthorizedDriveSession
import app.opentasks.backup.drive.DriveAuthorizationMode
import app.opentasks.backup.drive.DriveAuthorizationResult
import app.opentasks.backup.drive.GoogleDriveAuthorizationManager
import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.data.backup.drive.DriveChunkResult
import app.opentasks.core.data.backup.drive.DriveCreateRequest
import app.opentasks.core.data.backup.drive.DriveCreateResult
import app.opentasks.core.data.backup.drive.DriveDownloadReceipt
import app.opentasks.core.data.backup.drive.DriveFileMetadata
import app.opentasks.core.data.backup.drive.DriveListPage
import app.opentasks.core.data.backup.drive.DriveResumableSession
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.RecoveryCandidate
import app.opentasks.core.domain.RecoveryCoordinator
import app.opentasks.core.domain.RecoveryResult
import app.opentasks.core.domain.RecoverySource
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.RecoveryFailureCategory
import app.opentasks.core.model.WriterEpoch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryUiOperationsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun activeKnownAccountMismatchStopsBeforeRecoveryDiscovery() = runBlocking {
        val authorization = TestAuthorizationManager(DriveAuthorizationResult.AccountMismatch)
        val factoryCalls = AtomicInteger()
        val coordinator = TestRecoveryCoordinator()
        val operations = operations(
            authorization = authorization,
            coordinatorFactory = {
                factoryCalls.incrementAndGet()
                coordinator
            },
            knownDigest = { EXPECTED_DIGEST.copyOf() },
        )

        val result = operations.discoverDrive(null)

        assertEquals(
            RecoveryDiscoveryResult.Failed(RecoveryFailureCategory.ACCOUNT_MISMATCH),
            result,
        )
        assertArrayEquals(EXPECTED_DIGEST, authorization.expectedDigests.single())
        assertEquals(0, factoryCalls.get())
        assertEquals(0, coordinator.discoveryCalls)
    }

    @Test
    fun knownDigestReachesCoordinatorAndEveryBoundedSessionCloses() = runBlocking {
        val discoverySession = session()
        val prepareSession = session()
        val confirmSession = session()
        val authorization = TestAuthorizationManager(
            DriveAuthorizationResult.Authorized(discoverySession.session),
            DriveAuthorizationResult.Authorized(prepareSession.session),
            DriveAuthorizationResult.Authorized(confirmSession.session),
        )
        val factoryDigests = mutableListOf<ByteArray?>()
        val coordinator = TestRecoveryCoordinator()
        val operations = operations(
            authorization = authorization,
            coordinatorFactory = { digest ->
                factoryDigests += digest?.copyOf()
                coordinator
            },
            knownDigest = { EXPECTED_DIGEST.copyOf() },
        )

        operations.discoverDrive(null)
        assertTrue(discoverySession.transport.closed)
        operations.prepare("drive", "passphrase".toCharArray())
        assertTrue(prepareSession.transport.closed)
        operations.confirm("operation")
        assertTrue(confirmSession.transport.closed)

        assertArrayEquals(EXPECTED_DIGEST, factoryDigests.single())
        authorization.expectedDigests.forEach { assertArrayEquals(EXPECTED_DIGEST, it) }
        assertArrayEquals(EXPECTED_DIGEST, coordinator.prepareDigest)
        assertEquals(
            listOf(
                DriveAuthorizationMode.EXPLICIT_ACCOUNT,
                DriveAuthorizationMode.NON_INTERACTIVE,
                DriveAuthorizationMode.NON_INTERACTIVE,
            ),
            authorization.modes,
        )
    }

    private fun operations(
        authorization: GoogleDriveAuthorizationManager,
        coordinatorFactory: (ByteArray?) -> RecoveryCoordinator,
        knownDigest: suspend () -> ByteArray?,
    ) = RecoveryUiOperations(
        coordinatorFactory = coordinatorFactory,
        authorizationManager = authorization,
        recoveryInbox = File(temporaryFolder.root, "inbox.otb"),
        eligiblePackage = File(temporaryFolder.root, "eligible.otb"),
        recoveryRoot = File(temporaryFolder.root, "staging"),
        knownAccountBindingDigest = knownDigest,
    )

    private fun session(): TestSession {
        val transport = TestTransport()
        return TestSession(
            AuthorizedDriveSession(transport, EXPECTED_DIGEST, "token", null),
            transport,
        )
    }

    private data class TestSession(
        val session: AuthorizedDriveSession,
        val transport: TestTransport,
    )

    private class TestAuthorizationManager(
        vararg results: DriveAuthorizationResult,
    ) : GoogleDriveAuthorizationManager {
        private val results = ArrayDeque(results.toList())
        val expectedDigests = mutableListOf<ByteArray>()
        val modes = mutableListOf<DriveAuthorizationMode>()

        override suspend fun authorize(
            mode: DriveAuthorizationMode,
            expectedAccountDigest: ByteArray?,
        ): DriveAuthorizationResult {
            modes += mode
            expectedDigests += checkNotNull(expectedAccountDigest).copyOf()
            return results.removeFirst()
        }

        override suspend fun acceptResolution(
            data: Intent,
            expectedAccountDigest: ByteArray?,
        ): DriveAuthorizationResult = error("not used")

        override suspend fun clearToken(session: AuthorizedDriveSession) = Unit
        override suspend fun revokeAccess(session: AuthorizedDriveSession) = Unit
    }

    private class TestRecoveryCoordinator : RecoveryCoordinator {
        var discoveryCalls = 0
        var prepareDigest: ByteArray? = null

        override suspend fun discover(
            objectStore: CreateOnlyBackupObjectStore?,
            portablePackage: File?,
        ): List<RecoveryCandidate> {
            discoveryCalls++
            return listOf(RecoveryCandidate("drive", RecoverySource.GOOGLE_DRIVE))
        }

        override suspend fun prepare(
            candidate: RecoveryCandidate,
            passphrase: CharArray,
            objectStore: CreateOnlyBackupObjectStore?,
            accountBindingDigest: ByteArray?,
        ): RecoveryResult {
            prepareDigest = accountBindingDigest?.copyOf()
            return RecoveryResult.TakeoverConfirmationRequired(
                operationId = "operation",
                generation = BackupGeneration(3),
                nextWriterEpoch = WriterEpoch(2),
            )
        }

        override suspend fun confirmTakeover(
            operationId: String,
            objectStore: CreateOnlyBackupObjectStore,
        ): RecoveryResult = RecoveryResult.Activated(BackupGeneration(3), null)
    }

    private class TestTransport : CreateOnlyDriveTransport {
        var closed = false

        override suspend fun readCurrentUserPermissionId() = error("not used")
        override suspend fun generateAppDataFileIds(count: Int) = error("not used")
        override suspend fun listAppDataFiles(query: String, pageToken: String?, pageSize: Int): DriveListPage = error("not used")
        override suspend fun createFileIfAbsent(request: DriveCreateRequest): DriveCreateResult = error("not used")
        override suspend fun downloadFile(providerFileId: String, destination: File, maximumBytes: Long): DriveDownloadReceipt = error("not used")
        override suspend fun startResumableCreate(metadata: DriveFileMetadata, totalBytes: Long): DriveResumableSession = error("not used")
        override suspend fun queryResumableUpload(sessionUri: String, totalBytes: Long): DriveChunkResult = error("not used")
        override suspend fun uploadChunk(sessionUri: String, firstByte: Long, totalBytes: Long, content: ByteArray): DriveChunkResult = error("not used")
        override suspend fun deleteFile(providerFileId: String) = error("not used")
        override fun close() { closed = true }
    }

    private companion object {
        val EXPECTED_DIGEST = ByteArray(32) { (it + 1).toByte() }
    }
}
