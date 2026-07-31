package app.opentasks.backup

import app.opentasks.backup.drive.AuthorizedDriveSession
import app.opentasks.backup.drive.DriveAuthorizationResult
import app.opentasks.backup.drive.DriveAuthorizationUnavailableReason
import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.data.backup.drive.DriveChunkResult
import app.opentasks.core.data.backup.drive.DriveCreateRequest
import app.opentasks.core.data.backup.drive.DriveCreateResult
import app.opentasks.core.data.backup.drive.DriveDownloadReceipt
import app.opentasks.core.data.backup.drive.DriveFileMetadata
import app.opentasks.core.data.backup.drive.DriveListPage
import app.opentasks.core.data.backup.drive.DriveResumableSession
import app.opentasks.core.data.backup.RemoteBackupStateStore
import app.opentasks.core.domain.BackupWorkScheduler
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.CreateSmallResult
import app.opentasks.core.domain.DeleteObjectResult
import app.opentasks.core.domain.ImmutableDownloadResult
import app.opentasks.core.domain.ImmutableUploadRequest
import app.opentasks.core.domain.ImmutableUploadResult
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.ReadSmallResult
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupCoordinator
import app.opentasks.core.domain.RemoteBackupOperation
import app.opentasks.core.domain.RemoteBackupRunResult
import app.opentasks.core.domain.RemoteBackupRunner
import app.opentasks.core.domain.RemoteListPage
import app.opentasks.core.domain.RemoteListRequest
import app.opentasks.core.domain.RemoteListedObject
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OwnershipClaimId
import app.opentasks.core.model.OwnershipClaimRef
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationId
import app.opentasks.core.model.PublicationRef
import app.opentasks.core.model.PublicationSequence
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WriterEpoch
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBackupRuntimeTest {

    // -- Runner: one process-scoped run --------------------------------------------------

    @Test
    fun manualAndAutomaticRunsNeverExecuteTwoCoordinatorPassesConcurrently() {
        val fixture = runnerFixture(outcome = RemoteBackupRunResult.NoChanges)
        fixture.coordinator.passDelayMillis = 25

        runBlocking {
            withTimeout(5_000) {
                (0 until 6)
                    .map { async(Dispatchers.Default) { fixture.runner.run() } }
                    .awaitAll()
            }
        }

        assertEquals(6, fixture.coordinator.passes.get())
        assertEquals(1, fixture.coordinator.maximumConcurrentPasses.get())
        assertEquals(6, fixture.openedObjectStores.get())
        assertEquals(6, fixture.transport.closes.get())
    }

    // -- Runner: authorization ------------------------------------------------------------

    @Test
    fun accountMismatchPersistsItsCategoryWithoutAnyLineageCall() {
        val fixture = runnerFixture(
            authorization = { DriveAuthorizationResult.AccountMismatch },
        )

        val result = runBlocking { withTimeout(5_000) { fixture.runner.run() } }

        assertEquals(RemoteBackupRunResult.AccountMismatch, result)
        assertEquals(0, fixture.coordinator.passes.get())
        assertEquals(0, fixture.openedObjectStores.get())
        assertEquals(
            RemoteBackupFailureCategory.ACCOUNT_MISMATCH,
            fixture.stateStore.stored().failureCategory,
        )
    }

    @Test
    fun resolutionRequirementPersistsActionRequiredWithoutStartingAuthorizationUi() {
        val fixture = runnerFixture(
            authorization = {
                DriveAuthorizationResult.Unavailable(
                    DriveAuthorizationUnavailableReason.AUTHORIZATION_REQUIRED,
                )
            },
        )

        val result = runBlocking { withTimeout(5_000) { fixture.runner.run() } }

        assertEquals(RemoteBackupRunResult.AuthorizationRequired, result)
        assertEquals(0, fixture.coordinator.passes.get())
        assertEquals(
            RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED,
            fixture.stateStore.stored().failureCategory,
        )
    }

    @Test
    fun aRejectedGrantBecomesTheSameActionRequiredStateAndARetryableOneRetries() {
        val rejected = runnerFixture(
            authorization = {
                DriveAuthorizationResult.Unavailable(
                    DriveAuthorizationUnavailableReason.REJECTED,
                )
            },
        )
        val retryable = runnerFixture(
            authorization = {
                DriveAuthorizationResult.Unavailable(
                    DriveAuthorizationUnavailableReason.RETRYABLE,
                )
            },
        )

        val rejectedResult = runBlocking { withTimeout(5_000) { rejected.runner.run() } }
        val retryableResult = runBlocking { withTimeout(5_000) { retryable.runner.run() } }

        assertEquals(RemoteBackupRunResult.AuthorizationRequired, rejectedResult)
        assertEquals(
            RemoteBackupRunResult.Retryable(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
            retryableResult,
        )
        assertEquals(
            RemoteBackupFailureCategory.RETRYABLE_PROVIDER,
            retryable.stateStore.stored().failureCategory,
        )
    }

    @Test
    fun authorizationSeesTheStoredDigestAndTheRunnerLeavesNoReadableCopy() {
        val fixture = runnerFixture(outcome = RemoteBackupRunResult.NoChanges)

        runBlocking { withTimeout(5_000) { fixture.runner.run() } }

        assertArrayEquals(ACCOUNT_DIGEST, fixture.authorizedDigest)
        assertArrayEquals(ByteArray(ACCOUNT_DIGEST.size), checkNotNull(fixture.authorizedBuffer))
    }

    @Test
    fun providerAuthorizationFailureClearsTheTokenBeforeTheSessionCloses() {
        val fixture = runnerFixture(outcome = RemoteBackupRunResult.AuthorizationRequired)

        val result = runBlocking { withTimeout(5_000) { fixture.runner.run() } }

        assertEquals(RemoteBackupRunResult.AuthorizationRequired, result)
        assertEquals(listOf("clear-token", "transport-close"), fixture.events)
        assertEquals(
            RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED,
            fixture.stateStore.stored().failureCategory,
        )
    }

    @Test
    fun anOrdinaryOutcomeClosesTheSessionWithoutClearingTheToken() {
        val fixture = runnerFixture(outcome = RemoteBackupRunResult.Verified(BackupGeneration(9)))

        runBlocking { withTimeout(5_000) { fixture.runner.run() } }

        assertEquals(listOf("transport-close"), fixture.events)
    }

    // -- Runner: bounded persisted state --------------------------------------------------

    @Test
    fun everyBoundedOutcomePersistsItsOwnCategoryAndSuccessClearsIt() {
        val expected = mapOf(
            RemoteBackupRunResult.Verified(BackupGeneration(4)) to null,
            RemoteBackupRunResult.NoChanges to null,
            RemoteBackupRunResult.AuthorizationRequired to
                RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED,
            RemoteBackupRunResult.AccountMismatch to RemoteBackupFailureCategory.ACCOUNT_MISMATCH,
            RemoteBackupRunResult.OwnershipLost to RemoteBackupFailureCategory.OWNERSHIP_LOST,
            RemoteBackupRunResult.Terminated to RemoteBackupFailureCategory.TERMINATED,
            RemoteBackupRunResult.AmbiguousRemoteState to
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            RemoteBackupRunResult.Retryable(RemoteBackupFailureCategory.RETRYABLE_PROVIDER) to
                RemoteBackupFailureCategory.RETRYABLE_PROVIDER,
            RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.PROVIDER_STORAGE) to
                RemoteBackupFailureCategory.PROVIDER_STORAGE,
            RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.LOCAL_STORAGE) to
                RemoteBackupFailureCategory.LOCAL_STORAGE,
        )

        expected.forEach { (outcome, category) ->
            val fixture = runnerFixture(
                outcome = outcome,
                storedCategory = RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )

            val result = runBlocking { withTimeout(5_000) { fixture.runner.run() } }

            assertEquals(outcome, result)
            assertEquals(
                outcome.javaClass.simpleName,
                category,
                fixture.stateStore.stored().failureCategory,
            )
        }
    }

    @Test
    fun aFailingCoordinatorStillClosesItsSessionAndBecomesBoundedLocalStorage() {
        val fixture = runnerFixture(outcome = RemoteBackupRunResult.NoChanges)
        fixture.coordinator.failure = IllegalStateException("provider blew up")

        val result = runBlocking { withTimeout(5_000) { fixture.runner.run() } }

        assertEquals(
            RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.LOCAL_STORAGE),
            result,
        )
        assertEquals(listOf("transport-close"), fixture.events)
    }

    @Test
    fun aVaultWithoutAnActiveLineageNeverAuthorizes() {
        val dormant = runnerFixture(lifecycle = RemoteBackupLifecycle.DORMANT)
        val absent = runnerFixture(configured = false)

        val dormantResult = runBlocking { withTimeout(5_000) { dormant.runner.run() } }
        val absentResult = runBlocking { withTimeout(5_000) { absent.runner.run() } }

        assertEquals(RemoteBackupRunResult.NoChanges, dormantResult)
        assertEquals(RemoteBackupRunResult.NoChanges, absentResult)
        assertEquals(0, dormant.authorizations.get())
        assertEquals(0, absent.authorizations.get())
    }

    // -- Runtime: scheduling ---------------------------------------------------------------

    @Test
    fun pendingGenerationsEnqueueOnceAndEnsurePeriodicOnlyWhileActive() {
        val fixture = runtimeFixture(verifiedGeneration = 4, localGeneration = 4)

        fixture.runtime.start()
        assertTrue(fixture.scheduler.awaitEvents(1))
        assertEquals(listOf("periodic"), fixture.scheduler.events())

        fixture.localGeneration.value = 5
        assertTrue(fixture.scheduler.awaitEvents(2))
        fixture.localGeneration.value = 6
        assertTrue(fixture.scheduler.awaitEvents(3))

        assertEquals(listOf("periodic", "pending", "pending"), fixture.scheduler.events())
        fixture.close()
    }

    @Test
    fun aLocalGenerationNoNewerThanTheVerifiedRemoteOneEnqueuesNothing() {
        val fixture = runtimeFixture(verifiedGeneration = 9, localGeneration = 9)

        fixture.runtime.start()
        assertTrue(fixture.scheduler.awaitEvents(1))
        fixture.localGeneration.value = 8

        Thread.sleep(SETTLE_MILLIS)
        assertEquals(listOf("periodic"), fixture.scheduler.events())
        fixture.close()
    }

    @Test
    fun dormancyOwnershipLossTerminationAndDisconnectionCancelOrdinaryWork() {
        listOf(
            RemoteBackupLifecycle.DORMANT,
            RemoteBackupLifecycle.OWNERSHIP_LOST,
            RemoteBackupLifecycle.TERMINATED,
            RemoteBackupLifecycle.DELETING,
            RemoteBackupLifecycle.BLOCKED,
        ).forEach { lifecycle ->
            val fixture = runtimeFixture(verifiedGeneration = 1, localGeneration = 2)

            fixture.runtime.start()
            assertTrue(lifecycle.name, fixture.scheduler.awaitEvents(2))
            fixture.configuration.value = configuration(
                lifecycle = lifecycle,
                verifiedGeneration = 1,
            )
            assertTrue(lifecycle.name, fixture.scheduler.awaitEvents(3))

            assertEquals(
                lifecycle.name,
                listOf("periodic", "pending", "cancel"),
                fixture.scheduler.events(),
            )
            fixture.close()
        }
    }

    @Test
    fun anUnconfiguredLineageCancelsStaleWorkBeforeAnythingIsEverEnqueued() {
        val fixture = runtimeFixture(verifiedGeneration = 1, localGeneration = 2, configured = false)

        fixture.runtime.start()
        assertTrue(fixture.scheduler.awaitEvents(1))

        Thread.sleep(SETTLE_MILLIS)
        assertEquals(listOf("cancel"), fixture.scheduler.events())
        fixture.close()
    }

    @Test
    fun manualRequestsRunThroughTheSameSingleRunnerAndStopEndsObservation() {
        val fixture = runtimeFixture(verifiedGeneration = 4, localGeneration = 4)

        fixture.runtime.start()
        assertTrue(fixture.scheduler.awaitEvents(1))
        fixture.runtime.requestNow()
        assertTrue(fixture.runner.awaitRuns(1))

        fixture.runtime.stop()
        assertTrue(fixture.scheduler.awaitEvents(2))
        fixture.localGeneration.value = 99

        Thread.sleep(SETTLE_MILLIS)
        assertEquals(listOf("periodic", "cancel"), fixture.scheduler.events())
        assertEquals(1, fixture.runner.runs.get())
        fixture.close()
    }

    // -- Fixtures ---------------------------------------------------------------------------

    private fun runnerFixture(
        outcome: RemoteBackupRunResult = RemoteBackupRunResult.NoChanges,
        authorization: (suspend () -> DriveAuthorizationResult)? = null,
        lifecycle: RemoteBackupLifecycle = RemoteBackupLifecycle.ACTIVE,
        configured: Boolean = true,
        storedCategory: RemoteBackupFailureCategory? = null,
    ): RunnerFixture {
        val events = mutableListOf<String>()
        val transport = RecordingDriveTransport { events += "transport-close" }
        val stateStore = FakeRemoteBackupStateStore(
            if (configured) {
                configuration(lifecycle = lifecycle, failureCategory = storedCategory)
            } else {
                null
            },
        )
        val coordinator = FakeRemoteBackupCoordinator(outcome)
        val fixture = RunnerFixture(
            events = events,
            transport = transport,
            stateStore = stateStore,
            coordinator = coordinator,
        )
        fixture.runner = DefaultRemoteBackupRunner(
            vaultId = VAULT_ID,
            remoteStateStore = stateStore,
            coordinator = coordinator,
            authorize = { digest ->
                fixture.authorizations.incrementAndGet()
                fixture.authorizedDigest = digest.copyOf()
                fixture.authorizedBuffer = digest
                authorization?.invoke() ?: DriveAuthorizationResult.Authorized(
                    AuthorizedDriveSession(
                        transport = transport,
                        accountBindingDigest = ACCOUNT_DIGEST,
                        accessToken = "opaque",
                        account = null,
                    ),
                )
            },
            clearToken = { session ->
                events += "clear-token"
                session.close()
            },
            openObjectStore = {
                fixture.openedObjectStores.incrementAndGet()
                UnusedObjectStore
            },
        )
        return fixture
    }

    private class RunnerFixture(
        val events: MutableList<String>,
        val transport: RecordingDriveTransport,
        val stateStore: FakeRemoteBackupStateStore,
        val coordinator: FakeRemoteBackupCoordinator,
    ) {
        lateinit var runner: DefaultRemoteBackupRunner
        val authorizations = AtomicInteger()
        val openedObjectStores = AtomicInteger()

        @Volatile
        var authorizedDigest: ByteArray? = null

        @Volatile
        var authorizedBuffer: ByteArray? = null
    }

    private fun runtimeFixture(
        verifiedGeneration: Long,
        localGeneration: Long,
        configured: Boolean = true,
    ): RuntimeFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val configurationFlow = MutableStateFlow(
            if (configured) configuration(verifiedGeneration = verifiedGeneration) else null,
        )
        val generationFlow = MutableStateFlow(localGeneration)
        val scheduler = RecordingBackupWorkScheduler()
        val runner = CountingRemoteBackupRunner()
        return RuntimeFixture(
            scope = scope,
            configuration = configurationFlow,
            localGeneration = generationFlow,
            scheduler = scheduler,
            runner = runner,
            runtime = DefaultRemoteBackupRuntime(
                scope = scope,
                runner = runner,
                scheduler = scheduler,
                observeConfiguration = { configurationFlow },
                observeLocalGeneration = { generationFlow },
            ),
        )
    }

    private class RuntimeFixture(
        private val scope: CoroutineScope,
        val configuration: MutableStateFlow<RemoteBackupConfiguration?>,
        val localGeneration: MutableStateFlow<Long>,
        val scheduler: RecordingBackupWorkScheduler,
        val runner: CountingRemoteBackupRunner,
        val runtime: DefaultRemoteBackupRuntime,
    ) {
        fun close() {
            scope.cancel()
        }
    }

    // -- Doubles -----------------------------------------------------------------------------

    private class RecordingBackupWorkScheduler : BackupWorkScheduler {
        private val recorded = mutableListOf<String>()
        private val lock = Any()

        @Volatile
        private var arrivals = CountDownLatch(1)

        override fun onPendingGeneration() = record("pending")

        override fun ensurePeriodic() = record("periodic")

        override fun cancelAll() = record("cancel")

        fun events(): List<String> = synchronized(lock) { recorded.toList() }

        fun awaitEvents(count: Int): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (synchronized(lock) { recorded.size } >= count) return true
                arrivals.await(50, TimeUnit.MILLISECONDS)
            }
            return synchronized(lock) { recorded.size } >= count
        }

        private fun record(event: String) {
            synchronized(lock) { recorded += event }
            arrivals.countDown()
            arrivals = CountDownLatch(1)
        }
    }

    private class CountingRemoteBackupRunner : RemoteBackupRunner {
        val runs = AtomicInteger()

        override suspend fun run(): RemoteBackupRunResult {
            runs.incrementAndGet()
            return RemoteBackupRunResult.NoChanges
        }

        fun awaitRuns(count: Int): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (runs.get() >= count) return true
                Thread.sleep(10)
            }
            return runs.get() >= count
        }
    }

    private class FakeRemoteBackupCoordinator(
        private val outcome: RemoteBackupRunResult,
    ) : RemoteBackupCoordinator {
        val passes = AtomicInteger()
        val active = AtomicInteger()
        val maximumConcurrentPasses = AtomicInteger()

        @Volatile
        var passDelayMillis: Long = 0

        @Volatile
        var failure: Throwable? = null

        override suspend fun run(objectStore: CreateOnlyBackupObjectStore): RemoteBackupRunResult {
            val concurrent = active.incrementAndGet()
            maximumConcurrentPasses.updateAndGet { maxOf(it, concurrent) }
            try {
                passes.incrementAndGet()
                if (passDelayMillis > 0) delay(passDelayMillis)
                failure?.let { throw it }
                return outcome
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private class FakeRemoteBackupStateStore(
        initial: RemoteBackupConfiguration?,
    ) : RemoteBackupStateStore {
        private var current: RemoteBackupConfiguration? = initial

        fun stored(): RemoteBackupConfiguration = checkNotNull(current)

        override suspend fun active(vaultId: VaultId): RemoteBackupConfiguration? =
            current?.takeIf { it.vaultId == vaultId }

        override suspend fun known(lineageId: CloudLineageId): RemoteBackupConfiguration? =
            current?.takeIf { it.lineageId == lineageId }

        override fun observeActive(vaultId: VaultId): Flow<RemoteBackupConfiguration?> =
            MutableStateFlow(current)

        override suspend fun insertConnecting(configuration: RemoteBackupConfiguration) {
            current = configuration
        }

        override suspend fun compareAndSet(
            lineageId: CloudLineageId,
            expected: RemoteBackupStateVersion,
            next: RemoteBackupConfiguration,
        ): Boolean {
            val stored = current ?: return false
            if (stored.lineageId != lineageId || stored.stateVersion != expected) return false
            current = next
            return true
        }

        override suspend fun operation(operationId: String): RemoteBackupOperation? = null

        override suspend fun putOperation(operation: RemoteBackupOperation) = Unit

        override suspend fun transitionOperation(
            operationId: String,
            expectedPhase: String,
            next: RemoteBackupOperation,
        ): Boolean = false
    }

    private class RecordingDriveTransport(
        private val onClose: () -> Unit,
    ) : CreateOnlyDriveTransport {
        val closes = AtomicInteger()

        override suspend fun readCurrentUserPermissionId(): String = unsupported()

        override suspend fun generateAppDataFileIds(count: Int): List<String> = unsupported()

        override suspend fun listAppDataFiles(
            query: String,
            pageToken: String?,
            pageSize: Int,
        ): DriveListPage = unsupported()

        override suspend fun createFileIfAbsent(request: DriveCreateRequest): DriveCreateResult =
            unsupported()

        override suspend fun downloadFile(
            providerFileId: String,
            destination: File,
            maximumBytes: Long,
        ): DriveDownloadReceipt = unsupported()

        override suspend fun startResumableCreate(
            metadata: DriveFileMetadata,
            totalBytes: Long,
        ): DriveResumableSession = unsupported()

        override suspend fun queryResumableUpload(
            sessionUri: String,
            totalBytes: Long,
        ): DriveChunkResult = unsupported()

        override suspend fun uploadChunk(
            sessionUri: String,
            firstByte: Long,
            totalBytes: Long,
            content: ByteArray,
        ): DriveChunkResult = unsupported()

        override suspend fun deleteFile(providerFileId: String): Boolean = unsupported()

        override fun close() {
            closes.incrementAndGet()
            onClose()
        }

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("The test transport performs no provider call")
    }

    private object UnusedObjectStore : CreateOnlyBackupObjectStore {
        override suspend fun generateProviderIds(
            count: Int,
            role: RemoteObjectRoleV1,
        ): List<ProviderObjectId> = unsupported()

        override suspend fun createSmallIfAbsent(
            providerObjectId: ProviderObjectId,
            lineageId: CloudLineageId,
            metadata: RemoteListedObject,
            bytes: OwnedRemoteBytes,
        ): CreateSmallResult = unsupported()

        override suspend fun readSmall(
            providerObjectId: ProviderObjectId,
            maximumBytes: Long,
        ): ReadSmallResult = unsupported()

        override suspend fun list(request: RemoteListRequest): RemoteListPage = unsupported()

        override suspend fun uploadImmutable(
            request: ImmutableUploadRequest,
        ): ImmutableUploadResult = unsupported()

        override suspend fun downloadImmutable(
            providerObjectId: ProviderObjectId,
            maximumBytes: Long,
            expectedSha256: Sha256Digest,
        ): ImmutableDownloadResult = unsupported()

        override suspend fun delete(providerObjectId: ProviderObjectId): DeleteObjectResult =
            unsupported()

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("The coordinator double reads no object store")
    }

    private companion object {
        const val SETTLE_MILLIS = 250L
        val VAULT_ID = VaultId("11111111-1111-4111-8111-111111111111")
        val LINEAGE_ID = CloudLineageId.parse("22222222-2222-4222-8222-222222222222")
        val ACCOUNT_DIGEST = ByteArray(32) { index -> (index + 1).toByte() }

        fun configuration(
            lifecycle: RemoteBackupLifecycle = RemoteBackupLifecycle.ACTIVE,
            verifiedGeneration: Long = 1,
            failureCategory: RemoteBackupFailureCategory? = null,
        ): RemoteBackupConfiguration = RemoteBackupConfiguration(
            lineageId = LINEAGE_ID,
            vaultId = VAULT_ID,
            rootClaimProviderId = ProviderObjectId.of("root-claim"),
            accountBindingDigest = ACCOUNT_DIGEST,
            lifecycle = lifecycle,
            activeDeviceId = CloudDeviceId.parse("33333333-3333-4333-8333-333333333333"),
            writerEpoch = WriterEpoch(1),
            ownershipClaim = OwnershipClaimRef(
                providerId = ProviderObjectId.of("claim"),
                logicalId = OwnershipClaimId.parse("44444444-4444-4444-8444-444444444444"),
                sha256 = Sha256Digest.of("a".repeat(64)),
                writerEpoch = WriterEpoch(1),
            ),
            nextSuccessorProviderId = null,
            currentPublication = PublicationRef(
                providerId = ProviderObjectId.of("publication"),
                logicalId = PublicationId.parse("55555555-5555-4555-8555-555555555555"),
                sha256 = Sha256Digest.of("b".repeat(64)),
                sequence = PublicationSequence(1),
                generation = BackupGeneration(verifiedGeneration),
            ),
            previousPublication = null,
            lastVerifiedGeneration = BackupGeneration(verifiedGeneration),
            lastVerifiedAt = Instant.EPOCH,
            recoveryCredentialGeneration = 0,
            failureCategory = failureCategory,
            stateVersion = RemoteBackupStateVersion(1),
        )
    }
}
