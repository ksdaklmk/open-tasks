package app.opentasks.backup

import app.opentasks.core.data.backup.BackupStateEntity
import app.opentasks.core.model.RestoredPackageCondition
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBackupRuntimeTest {
    @Test
    fun startupUsesExactIntakeKeyCoordinatorObservationPublisherOrder() {
        val events = mutableListOf<String>()
        val states = MutableSharedFlow<BackupStateEntity>(replay = 1)
        states.tryEmit(state(envelopeReady = true))
        val published = CountDownLatch(1)
        val fixture = runtime(
            intake = {
                events += "intake"
                RestoredPackageIntakeResult.NoPackage
            },
            bootstrapKey = { events += "key" },
            requestCoordinator = { events += "coordinator" },
            observeStates = {
                events += "observe"
                states
            },
            refreshPublisher = {
                events += "publisher"
                published.countDown()
            },
        )

        fixture.runtime.start()

        assertTrue(published.await(2, TimeUnit.SECONDS))
        assertEquals(
            listOf("intake", "key", "coordinator", "observe", "publisher"),
            events,
        )
        fixture.close()
    }

    @Test
    fun processLifetimeStartAndRetryBootstrapContentKeyOnlyOnce() {
        var keyBootstraps = 0
        var coordinatorRequests = 0
        val firstRequest = CountDownLatch(1)
        val retryRequest = CountDownLatch(1)
        val fixture = runtime(
            bootstrapKey = { keyBootstraps += 1 },
            requestCoordinator = {
                coordinatorRequests += 1
                if (coordinatorRequests == 1) firstRequest.countDown() else retryRequest.countDown()
            },
            observeStates = { flowOf(state()) },
        )

        fixture.runtime.start()
        fixture.runtime.start()
        assertTrue(firstRequest.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)
        fixture.runtime.retry()
        assertTrue(retryRequest.await(2, TimeUnit.SECONDS))

        assertEquals(1, keyBootstraps)
        fixture.close()
    }

    @Test
    fun envelopeReadFailureStopsPublisherWithoutReplacingKeyOrRepositoryDependency() {
        var keyBootstraps = 0
        var publisherRefreshes = 0
        val states = MutableSharedFlow<BackupStateEntity>(replay = 1)
        states.tryEmit(state(envelopeReady = true))
        val observed = CountDownLatch(1)
        val fixture = runtime(
            bootstrapKey = {
                keyBootstraps += 1
            },
            requestCoordinator = { Unit },
            observeStates = {
                observed.countDown()
                states
            },
            envelopeAvailable = { throw IllegalStateException("stored envelope failed") },
            refreshPublisher = { publisherRefreshes += 1 },
        )

        fixture.runtime.start()

        assertTrue(observed.await(2, TimeUnit.SECONDS))
        Thread.sleep(80)
        assertEquals(1, keyBootstraps)
        assertEquals(0, publisherRefreshes)
        fixture.close()
    }

    @Test
    fun rapidGenerationChangesDebounceAndCoalesceOneRefresh() {
        val states = MutableSharedFlow<BackupStateEntity>(replay = 1, extraBufferCapacity = 8)
        states.tryEmit(state(generation = 1, envelopeReady = true))
        val initial = CountDownLatch(1)
        val burst = CountDownLatch(1)
        var refreshes = 0
        val fixture = runtime(
            requestCoordinator = { Unit },
            observeStates = { states },
            refreshPublisher = {
                refreshes += 1
                if (refreshes == 1) initial.countDown() else burst.countDown()
            },
            debounceMillis = 60,
        )
        fixture.runtime.start()
        assertTrue(initial.await(2, TimeUnit.SECONDS))

        states.tryEmit(state(generation = 2, envelopeReady = true))
        states.tryEmit(state(generation = 3, envelopeReady = true))
        states.tryEmit(state(generation = 4, envelopeReady = true))

        assertTrue(burst.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(2, refreshes)
        fixture.close()
    }

    @Test
    fun retryResumesIncompleteStateAfterIsolatedStartupFailure() {
        var intakeCalls = 0
        var coordinatorRequests = 0
        val completed = CountDownLatch(1)
        val states = MutableSharedFlow<BackupStateEntity>(replay = 1)
        states.tryEmit(state())
        val fixture = runtime(
            intake = {
                intakeCalls += 1
                if (intakeCalls == 1) throw IllegalStateException("interrupted startup")
                RestoredPackageIntakeResult.NoPackage
            },
            requestCoordinator = {
                coordinatorRequests += 1
                completed.countDown()
            },
            observeStates = { states },
        )

        fixture.runtime.start()
        Thread.sleep(50)
        fixture.runtime.retry()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(2, intakeCalls)
        assertEquals(1, coordinatorRequests)
        fixture.close()
    }

    @Test
    fun preservationBlockPreventsKeyCoordinatorAndPublisherWork() {
        var keyCalls = 0
        var coordinatorCalls = 0
        var publisherCalls = 0
        val fixture = runtime(
            intake = {
                RestoredPackageIntakeResult.PreservationBlocked
            },
            bootstrapKey = { keyCalls += 1 },
            requestCoordinator = { coordinatorCalls += 1 },
            refreshPublisher = { publisherCalls += 1 },
        )

        fixture.runtime.start()
        Thread.sleep(80)

        assertEquals(0, keyCalls)
        assertEquals(0, coordinatorCalls)
        assertEquals(0, publisherCalls)
        fixture.close()
    }

    @Test
    fun preservedUnknownPackageDoesNotActivateItAndAllowsFreshLocalPublication() {
        val published = CountDownLatch(1)
        val fixture = runtime(
            intake = {
                RestoredPackageIntakeResult.Preserved(RestoredPackageCondition.PRESERVED)
            },
            refreshPublisher = { published.countDown() },
            observeStates = {
                MutableSharedFlow<BackupStateEntity>(replay = 1).also {
                    it.tryEmit(state(envelopeReady = true))
                }
            },
        )

        fixture.runtime.start()

        assertTrue(published.await(2, TimeUnit.SECONDS))
        fixture.close()
    }

    private fun runtime(
        intake: suspend () -> RestoredPackageIntakeResult = {
            RestoredPackageIntakeResult.NoPackage
        },
        bootstrapKey: () -> Unit = {},
        requestCoordinator: suspend () -> Unit = {},
        observeStates: () -> kotlinx.coroutines.flow.Flow<BackupStateEntity> = {
            MutableSharedFlow(replay = 1)
        },
        envelopeAvailable: suspend () -> Boolean = { true },
        refreshPublisher: suspend () -> Unit = {},
        debounceMillis: Long = 0,
    ): RuntimeFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return RuntimeFixture(
            runtime = DefaultAndroidBackupRuntime(
                scope = scope,
                restoredPackageIntake = intake,
                bootstrapContentKey = bootstrapKey,
                requestLocalBackup = requestCoordinator,
                observeBackupState = observeStates,
                envelopeAvailable = envelopeAvailable,
                refreshPortablePackage = refreshPublisher,
                debounceMillis = debounceMillis,
            ),
            scope = scope,
        )
    }

    private data class RuntimeFixture(
        val runtime: AndroidBackupRuntime,
        val scope: CoroutineScope,
    ) {
        fun close() = scope.cancel()
    }

    private companion object {
        fun state(
            generation: Long = 7,
            envelopeReady: Boolean = false,
        ) = BackupStateEntity(
            vaultId = "vault-primary",
            currentGeneration = generation,
            lastVerifiedSnapshotGeneration = generation,
            currentBaseObjectId = "snapshot:$generation",
            previousBaseObjectId = null,
            latestVerifiedSegmentGeneration = generation,
            portablePackageGeneration = null,
            portablePackageBytes = null,
            portablePackageProducedAtEpochMillis = null,
            packageState = "NOT_PREPARED",
            failureCategory = null,
            recoveryEnvelopeReady = envelopeReady,
            legacyOutboxCoveredAtGeneration = generation,
            snapshotCreatedAtEpochMillis = 1234,
        )
    }
}
