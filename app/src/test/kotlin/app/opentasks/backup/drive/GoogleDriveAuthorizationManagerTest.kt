package app.opentasks.backup.drive

import android.accounts.Account
import android.content.Intent
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
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveAuthorizationManagerTest {
    @Test
    fun explicitModeRequestsAccountSelectionWithExactlyDriveAppdataScope() = runBlocking {
        val manager = manager(permissionId = "account-a")

        manager.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, digestOf("account-a"))

        val spec = manager.identity.authorizeSpecs.single()
        assertEquals(listOf(DefaultGoogleDriveAuthorizationManager.DRIVE_APPDATA_SCOPE), spec.scopes)
        assertTrue(spec.promptSelectAccount)
    }

    @Test
    fun nonInteractiveModeRequestsSilentlyWithoutAccountSelection() = runBlocking {
        val manager = manager(permissionId = "account-a")

        manager.authorize(DriveAuthorizationMode.NON_INTERACTIVE, digestOf("account-a"))

        val spec = manager.identity.authorizeSpecs.single()
        assertEquals(listOf(DefaultGoogleDriveAuthorizationManager.DRIVE_APPDATA_SCOPE), spec.scopes)
        assertFalse(spec.promptSelectAccount)
    }

    @Test
    fun nonInteractiveResolutionRequiredNeverProducesResolutionAndStaysUnavailable() = runBlocking {
        val manager = manager(permissionId = "account-a", hasResolution = true)

        val result = manager.authorize(DriveAuthorizationMode.NON_INTERACTIVE, null)

        assertEquals(
            DriveAuthorizationResult.Unavailable(DriveAuthorizationUnavailableReason.AUTHORIZATION_REQUIRED),
            result,
        )
        assertEquals(0, manager.transportCount)
    }

    @Test
    fun explicitModeResolutionRequiredWithoutAPendingIntentReportsRejected() = runBlocking {
        val manager = manager(permissionId = "account-a", hasResolution = true)

        val result = manager.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, null)

        assertEquals(
            DriveAuthorizationResult.Unavailable(DriveAuthorizationUnavailableReason.REJECTED),
            result,
        )
        assertEquals(0, manager.transportCount)
    }

    @Test
    fun nonInteractiveWrongAccountClosesBeforeLineageAccess() = runBlocking {
        val manager = manager(
            permissionId = "account-b",
            expectedDigest = digestOf("account-a"),
        )

        assertEquals(
            DriveAuthorizationResult.AccountMismatch,
            manager.authorize(
                DriveAuthorizationMode.NON_INTERACTIVE,
                digestOf("account-a"),
            ),
        )
        assertEquals(1, manager.closedTransports)
        assertEquals(0, manager.lineageCalls)
    }

    @Test
    fun matchingAccountDigestReturnsAuthorizedSessionCarryingTheDigest() = runBlocking {
        val expected = digestOf("account-a")
        val manager = manager(permissionId = "account-a")

        val result = manager.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, expected)

        val session = (result as DriveAuthorizationResult.Authorized).session
        assertArrayEquals(expected, session.copyAccountBindingDigest())
        assertEquals(1, manager.transportCount)
        assertEquals(0, manager.lineageCalls)
        session.close()
    }

    @Test
    fun missingExpectedDigestAuthorizesOnFirstBindingWithoutComparison() = runBlocking {
        val manager = manager(permissionId = "account-a")

        val result = manager.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, null)

        assertTrue(result is DriveAuthorizationResult.Authorized)
        (result as DriveAuthorizationResult.Authorized).session.close()
    }

    @Test
    fun aboutGetIsTheOnlyDriveCallRegardlessOfDigestOutcome() = runBlocking {
        val matching = manager(permissionId = "account-a")
        val mismatching = manager(permissionId = "account-b")

        val matchResult = matching.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, digestOf("account-a"))
        val mismatchResult =
            mismatching.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, digestOf("account-a"))

        assertEquals(0, matching.lineageCalls)
        assertEquals(0, mismatching.lineageCalls)
        assertEquals(DriveAuthorizationResult.AccountMismatch, mismatchResult)
        (matchResult as DriveAuthorizationResult.Authorized).session.close()
    }

    @Test
    fun rawPermissionIdIsNeverExposedByTheAuthorizedSession() = runBlocking {
        val manager = manager(permissionId = "raw-permission-id")

        val result = manager.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, null)
        val session = (result as DriveAuthorizationResult.Authorized).session

        assertFalse(session.copyAccountBindingDigest().decodeToString().contains("raw-permission-id"))
        assertFalse(session.toString().contains("raw-permission-id"))
        session.close()
    }

    @Test
    fun sessionToStringNeverExposesTheAccessToken() = runBlocking {
        val manager = manager(permissionId = "account-a", accessToken = "super-secret-token")

        val result = manager.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, null)
        val session = (result as DriveAuthorizationResult.Authorized).session

        assertFalse(session.toString().contains("super-secret-token"))
        session.close()
    }

    @Test
    fun permissionLookupAuthorizationFailureClearsTheTokenAndReportsAuthorizationRequired() = runBlocking {
        val manager = manager(
            permissionId = "unused",
            accessToken = "stale-token",
            permissionIdFailure = DriveTransportException(DriveTransportFailureCategory.AUTHORIZATION),
        )

        val result = manager.authorize(DriveAuthorizationMode.NON_INTERACTIVE, null)

        assertEquals(
            DriveAuthorizationResult.Unavailable(DriveAuthorizationUnavailableReason.AUTHORIZATION_REQUIRED),
            result,
        )
        assertEquals(listOf("stale-token"), manager.identity.clearedTokens)
        assertEquals(1, manager.closedTransports)
    }

    @Test
    fun retryablePermissionLookupFailureNeverClearsTheTokenAndReportsRetryable() = runBlocking {
        val manager = manager(
            permissionId = "unused",
            accessToken = "live-token",
            permissionIdFailure = DriveTransportException(DriveTransportFailureCategory.RETRYABLE),
        )

        val result = manager.authorize(DriveAuthorizationMode.NON_INTERACTIVE, null)

        assertEquals(
            DriveAuthorizationResult.Unavailable(DriveAuthorizationUnavailableReason.RETRYABLE),
            result,
        )
        assertTrue(manager.identity.clearedTokens.isEmpty())
        assertEquals(1, manager.closedTransports)
    }

    @Test
    fun rejectedPermissionLookupFailureReportsRejected() = runBlocking {
        val manager = manager(
            permissionId = "unused",
            accessToken = "live-token",
            permissionIdFailure = DriveTransportException(DriveTransportFailureCategory.PROVIDER_REJECTED),
        )

        val result = manager.authorize(DriveAuthorizationMode.NON_INTERACTIVE, null)

        assertEquals(
            DriveAuthorizationResult.Unavailable(DriveAuthorizationUnavailableReason.REJECTED),
            result,
        )
        assertTrue(manager.identity.clearedTokens.isEmpty())
        assertEquals(1, manager.closedTransports)
    }

    @Test
    fun storageQuotaPermissionLookupFailureReportsProviderStorageNotARefusedGrant() = runBlocking {
        val manager = manager(
            permissionId = "unused",
            accessToken = "live-token",
            permissionIdFailure = DriveTransportException(DriveTransportFailureCategory.STORAGE_QUOTA),
        )

        val result = manager.authorize(DriveAuthorizationMode.NON_INTERACTIVE, null)

        assertEquals(
            DriveAuthorizationResult.Unavailable(
                DriveAuthorizationUnavailableReason.PROVIDER_STORAGE,
            ),
            result,
        )
        assertTrue(manager.identity.clearedTokens.isEmpty())
        assertEquals(1, manager.closedTransports)
    }

    @Test
    fun missingAndCorruptPermissionLookupFailuresReportCorruptNotARefusedGrant() = runBlocking {
        listOf(
            DriveTransportFailureCategory.MISSING,
            DriveTransportFailureCategory.CORRUPT_RESPONSE,
        ).forEach { category ->
            val manager = manager(
                permissionId = "unused",
                accessToken = "live-token",
                permissionIdFailure = DriveTransportException(category),
            )

            val result = manager.authorize(DriveAuthorizationMode.NON_INTERACTIVE, null)

            assertEquals(
                category.name,
                DriveAuthorizationResult.Unavailable(
                    DriveAuthorizationUnavailableReason.CORRUPT_OR_INCOMPATIBLE,
                ),
                result,
            )
            assertTrue(category.name, manager.identity.clearedTokens.isEmpty())
        }
    }

    @Test
    fun everyTransportFailureCategoryMapsToExactlyOneBoundedUnavailableReason() = runBlocking {
        val mapped = DriveTransportFailureCategory.entries.associateWith { category ->
            val manager = manager(
                permissionId = "unused",
                accessToken = "live-token",
                permissionIdFailure = DriveTransportException(category),
            )
            (
                manager.authorize(DriveAuthorizationMode.NON_INTERACTIVE, null)
                    as DriveAuthorizationResult.Unavailable
                ).reason
        }

        assertEquals(
            mapOf(
                DriveTransportFailureCategory.AUTHORIZATION to
                    DriveAuthorizationUnavailableReason.AUTHORIZATION_REQUIRED,
                DriveTransportFailureCategory.RETRYABLE to
                    DriveAuthorizationUnavailableReason.RETRYABLE,
                DriveTransportFailureCategory.STORAGE_QUOTA to
                    DriveAuthorizationUnavailableReason.PROVIDER_STORAGE,
                DriveTransportFailureCategory.MISSING to
                    DriveAuthorizationUnavailableReason.CORRUPT_OR_INCOMPATIBLE,
                DriveTransportFailureCategory.CORRUPT_RESPONSE to
                    DriveAuthorizationUnavailableReason.CORRUPT_OR_INCOMPATIBLE,
                DriveTransportFailureCategory.PROVIDER_REJECTED to
                    DriveAuthorizationUnavailableReason.REJECTED,
            ),
            mapped,
        )
    }

    @Test
    fun authorizeSucceedsWithoutAnAccountHandle() = runBlocking {
        val manager = manager(permissionId = "account-a", account = null)

        val result = manager.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, null)

        assertTrue(result is DriveAuthorizationResult.Authorized)
        assertEquals(1, manager.transportCount)
        (result as DriveAuthorizationResult.Authorized).session.close()
    }

    @Test
    fun revokeAccessWithoutAnAccountHandleClearsTheTokenInsteadAndCloses() = runBlocking {
        val manager = manager(
            permissionId = "account-a",
            account = null,
            accessToken = "no-account-token",
        )
        val session = (
            manager.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, null)
                as DriveAuthorizationResult.Authorized
            ).session

        manager.revokeAccess(session)

        assertTrue(manager.identity.revokedAccounts.isEmpty())
        assertEquals(listOf("no-account-token"), manager.identity.clearedTokens)
        assertThrows(IllegalStateException::class.java) { session.copyAccountBindingDigest() }
        Unit
    }

    @Test
    fun acceptResolutionExtractionFailureReportsRejectedWithoutThrowing() = runBlocking {
        val manager = manager(permissionId = "account-a", resultFromIntentFailure = IllegalStateException())

        val result = manager.acceptResolution(Intent(), null)

        assertEquals(
            DriveAuthorizationResult.Unavailable(DriveAuthorizationUnavailableReason.REJECTED),
            result,
        )
    }

    @Test
    fun authorizeFailureReportsRejectedWithoutThrowing() = runBlocking {
        val manager = manager(
            permissionId = "account-a",
            authorizeFailure = IllegalStateException("identity failed"),
        )

        val result = manager.authorize(
            DriveAuthorizationMode.EXPLICIT_ACCOUNT,
            expectedAccountDigest = null,
        )

        assertEquals(
            DriveAuthorizationResult.Unavailable(
                DriveAuthorizationUnavailableReason.REJECTED,
            ),
            result,
        )
        assertEquals(0, manager.transportCount)
    }

    @Test
    fun authorizeCancellationStillPropagates() {
        val cancellation = CancellationException("identity cancelled")
        val manager = manager(
            permissionId = "account-a",
            authorizeFailure = cancellation,
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                manager.authorize(
                    DriveAuthorizationMode.EXPLICIT_ACCOUNT,
                    expectedAccountDigest = null,
                )
            }
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun revokeAccessUsesTheSessionAccountAndExactlyTheGrantedScopeThenCloses() = runBlocking {
        val account = Account("revoke-account", "com.google")
        val manager = manager(permissionId = "account-a", account = account)
        val session = (
            manager.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, null)
                as DriveAuthorizationResult.Authorized
            ).session

        manager.revokeAccess(session)

        val recorded = manager.identity.revokedAccounts.single()
        assertSame(account, recorded.first)
        assertEquals(DefaultGoogleDriveAuthorizationManager.DRIVE_APPDATA_SCOPE, recorded.second)
        assertEquals(listOf("token"), manager.identity.clearedTokens)
        assertThrows(IllegalStateException::class.java) { session.copyAccountBindingDigest() }
        Unit
    }

    @Test
    fun revokeFailureStillClearsTheCachedTokenAndClosesTheSession() = runBlocking {
        val failure = IllegalStateException("revocation failed")
        val manager = manager(permissionId = "account-a", revokeFailure = failure)
        val session = (
            manager.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, null)
                as DriveAuthorizationResult.Authorized
            ).session

        val thrown = try {
            manager.revokeAccess(session)
            null
        } catch (caught: IllegalStateException) {
            caught
        }

        assertSame(failure, thrown)
        assertEquals(listOf("token"), manager.identity.clearedTokens)
        assertThrows(IllegalStateException::class.java) { session.copyAccountBindingDigest() }
        Unit
    }

    @Test
    fun clearTokenClearsTheGoogleCacheAndDropsTheSessionTokenThenCloses() = runBlocking {
        val manager = manager(permissionId = "account-a", accessToken = "live-token")
        val session = (
            manager.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, null)
                as DriveAuthorizationResult.Authorized
            ).session

        manager.clearToken(session)

        assertEquals(listOf("live-token"), manager.identity.clearedTokens)
        assertThrows(IllegalStateException::class.java) { session.copyAccountBindingDigest() }
        Unit
    }

    @Test
    fun acceptResolutionResolvesThroughTheSameOutcomeHandlingAsAuthorize() = runBlocking {
        val manager = manager(permissionId = "account-a")

        val result = manager.acceptResolution(Intent(), digestOf("account-a"))

        assertTrue(result is DriveAuthorizationResult.Authorized)
        assertEquals(1, manager.identity.resultFromIntentCalls)
        (result as DriveAuthorizationResult.Authorized).session.close()
    }

    @Test
    fun closedSessionRejectsFurtherAccountBindingDigestAccess() = runBlocking {
        val manager = manager(permissionId = "account-a")
        val session = (
            manager.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, null)
                as DriveAuthorizationResult.Authorized
            ).session

        session.close()

        assertThrows(IllegalStateException::class.java) { session.copyAccountBindingDigest() }
        Unit
    }

    private fun testAccountBinding(): DriveAccountBinding =
        DriveAccountBinding(DriveAccountBindingKeyBoundary { FIXED_BINDING_KEY })

    private fun digestOf(permissionId: String): ByteArray = testAccountBinding().digest(permissionId)

    private fun manager(
        permissionId: String,
        expectedDigest: ByteArray? = null,
        accessToken: String = "token",
        account: Account? = Account("account", "type"),
        hasResolution: Boolean = false,
        permissionIdFailure: DriveTransportException? = null,
        authorizeFailure: Exception? = null,
        resultFromIntentFailure: Exception? = null,
        revokeFailure: Exception? = null,
    ): RecordingAuthorizationManager {
        val outcome = DriveAuthorizationOutcome(
            accessToken = accessToken,
            account = account,
            hasResolution = hasResolution,
            pendingIntent = null,
        )
        val identity = FakeDriveIdentityBoundary(
            outcome = outcome,
            authorizeFailure = authorizeFailure,
            resultFromIntentFailure = resultFromIntentFailure,
            revokeFailure = revokeFailure,
        )
        val transports = mutableListOf<TrackingCreateOnlyDriveTransport>()
        val delegate = DefaultGoogleDriveAuthorizationManager(
            identity = identity,
            accountBinding = testAccountBinding(),
            transportFactory = { _ ->
                TrackingCreateOnlyDriveTransport(permissionId, permissionIdFailure).also { transports += it }
            },
        )
        return RecordingAuthorizationManager(delegate, transports, identity)
    }

    private class RecordingAuthorizationManager(
        private val delegate: GoogleDriveAuthorizationManager,
        private val transports: MutableList<TrackingCreateOnlyDriveTransport>,
        val identity: FakeDriveIdentityBoundary,
    ) : GoogleDriveAuthorizationManager by delegate {
        val closedTransports: Int get() = transports.count { it.closed }
        val lineageCalls: Int get() = transports.sumOf { it.lineageCalls }
        val transportCount: Int get() = transports.size
    }

    private class FakeDriveIdentityBoundary(
        private val outcome: DriveAuthorizationOutcome,
        private val authorizeFailure: Exception? = null,
        private val resultFromIntentFailure: Exception? = null,
        private val revokeFailure: Exception? = null,
    ) : DriveIdentityBoundary {
        val authorizeSpecs = mutableListOf<DriveAuthorizationRequestSpec>()
        val clearedTokens = mutableListOf<String>()
        val revokedAccounts = mutableListOf<Pair<Account, String>>()
        var resultFromIntentCalls = 0
            private set

        override suspend fun authorize(spec: DriveAuthorizationRequestSpec): DriveAuthorizationOutcome {
            authorizeSpecs += spec
            authorizeFailure?.let { throw it }
            return outcome
        }

        override fun resultFromIntent(data: Intent): DriveAuthorizationOutcome {
            resultFromIntentCalls++
            resultFromIntentFailure?.let { throw it }
            return outcome
        }

        override suspend fun clearToken(accessToken: String) {
            clearedTokens += accessToken
        }

        override suspend fun revokeAccess(account: Account, scope: String) {
            revokedAccounts += account to scope
            revokeFailure?.let { throw it }
        }
    }

    private class TrackingCreateOnlyDriveTransport(
        private val permissionId: String,
        private val permissionIdFailure: DriveTransportException? = null,
    ) : CreateOnlyDriveTransport {
        var closed = false
            private set
        var lineageCalls = 0
            private set

        override suspend fun readCurrentUserPermissionId(): String {
            permissionIdFailure?.let { throw it }
            return permissionId
        }

        override suspend fun generateAppDataFileIds(count: Int): List<String> {
            lineageCalls++
            return emptyList()
        }

        override suspend fun listAppDataFiles(
            query: String,
            pageToken: String?,
            pageSize: Int,
        ): DriveListPage {
            lineageCalls++
            return DriveListPage(emptyList(), null)
        }

        override suspend fun createFileIfAbsent(request: DriveCreateRequest): DriveCreateResult {
            lineageCalls++
            return DriveCreateResult.Ambiguous
        }

        override suspend fun downloadFile(
            providerFileId: String,
            destination: File,
            maximumBytes: Long,
        ): DriveDownloadReceipt {
            lineageCalls++
            return DriveDownloadReceipt(0)
        }

        override suspend fun startResumableCreate(
            metadata: DriveFileMetadata,
            totalBytes: Long,
        ): DriveResumableSession {
            lineageCalls++
            return DriveResumableSession("unused")
        }

        override suspend fun queryResumableUpload(
            sessionUri: String,
            totalBytes: Long,
        ): DriveChunkResult {
            lineageCalls++
            return DriveChunkResult.Complete
        }

        override suspend fun uploadChunk(
            sessionUri: String,
            firstByte: Long,
            totalBytes: Long,
            content: ByteArray,
        ): DriveChunkResult {
            lineageCalls++
            return DriveChunkResult.Complete
        }

        override suspend fun deleteFile(providerFileId: String): Boolean {
            lineageCalls++
            return false
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val FIXED_BINDING_KEY = SecretKeySpec(ByteArray(32) { 5 }, "HmacSHA256")
    }
}
