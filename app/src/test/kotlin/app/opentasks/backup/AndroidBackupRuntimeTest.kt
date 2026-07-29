package app.opentasks.backup

import app.opentasks.core.data.backup.BackupStateEntity
import app.opentasks.core.model.AndroidBackupStatus
import app.opentasks.core.model.RestoredPackageCondition
import app.opentasks.core.model.VaultId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            bootstrapKey = {
                events += "key"
                true
            },
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
            bootstrapKey = {
                keyBootstraps += 1
                true
            },
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
                true
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
        var coordinatorRequests = 0
        val completed = CountDownLatch(1)
        val states = MutableSharedFlow<BackupStateEntity>(replay = 1)
        states.tryEmit(state())
        val fixture = runtime(
            requestCoordinator = {
                coordinatorRequests += 1
                if (coordinatorRequests == 1) {
                    throw IllegalStateException("interrupted coordinator")
                }
                completed.countDown()
            },
            observeStates = { states },
        )

        fixture.runtime.start()
        Thread.sleep(50)
        fixture.runtime.retry()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(2, coordinatorRequests)
        fixture.close()
    }

    @Test
    fun preservationBlockRunsLocalStartupInOrderButPreventsPublisherReplacement() {
        val events = mutableListOf<String>()
        var publisherCalls = 0
        val observed = CountDownLatch(1)
        val fixture = runtime(
            intake = {
                events += "intake"
                RestoredPackageIntakeResult.PreservationBlocked
            },
            bootstrapKey = {
                events += "key"
                true
            },
            requestCoordinator = { events += "coordinator" },
            observeStates = {
                events += "observe"
                observed.countDown()
                flowOf(state(envelopeReady = true))
            },
            refreshPublisher = { publisherCalls += 1 },
        )

        fixture.runtime.start()
        assertTrue(observed.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)

        assertEquals(listOf("intake", "key", "coordinator", "observe"), events)
        assertEquals(0, publisherCalls)
        fixture.close()
    }

    @Test
    fun preservedUnknownPackageRecordsBoundedStatusAndBlocksPublisherOnly() {
        val recorded = mutableListOf<AndroidBackupStatus>()
        val coordinator = CountDownLatch(1)
        var publisherCalls = 0
        val fixture = runtime(
            intake = {
                RestoredPackageIntakeResult.Preserved(RestoredPackageCondition.PRESERVED)
            },
            recordStatus = { recorded.add(it) },
            requestCoordinator = { coordinator.countDown() },
            refreshPublisher = { publisherCalls += 1 },
            observeStates = {
                flowOf(state(envelopeReady = true))
            },
        )

        fixture.runtime.start()
        assertTrue(coordinator.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)

        assertEquals(
            listOf(
                AndroidBackupStatus.RestoredPackageDetected(
                    RestoredPackageCondition.PRESERVED,
                ),
            ),
            recorded,
        )
        assertEquals(0, publisherCalls)
        fixture.close()
    }

    @Test
    fun persistedRestoredStatusBlocksPublisherAcrossRestartAndIsExposed() = runBlocking {
        val store = FakeStateStore(
            state(
                envelopeReady = true,
                packageState = "RESTORED_PACKAGE_DETECTED",
                failureCategory = "INCOMPATIBLE_OR_CORRUPT",
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = PersistedAndroidBackupStatusSource(
            scope = scope,
            observeBackupState = { store.observe(VaultId("vault-primary")) },
        )
        val exposed = withTimeout(2_000) {
            source.status.first { it is AndroidBackupStatus.RestoredPackageDetected }
        }
        var publisherCalls = 0
        val observed = CountDownLatch(1)
        val fixture = runtime(
            observeStates = {
                observed.countDown()
                store.observe(VaultId("vault-primary"))
            },
            restoredPublicationBlocked = { true },
            refreshPublisher = { publisherCalls += 1 },
        )

        fixture.runtime.start()
        assertTrue(observed.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)

        assertEquals(
            AndroidBackupStatus.RestoredPackageDetected(
                RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT,
            ),
            exposed,
        )
        assertEquals(0, publisherCalls)
        fixture.close()
        scope.cancel()
    }

    @Test
    fun restoredStatusWriterPersistsOnlyBoundedPrivateClassification() = runBlocking {
        val store = FakeStateStore(state(envelopeReady = true))

        recordRestoredPackageStatus(
            stateStore = store,
            vaultId = VaultId("vault-primary"),
            status = AndroidBackupStatus.RestoredPackageDetected(
                RestoredPackageCondition.PRESERVED,
            ),
        ).also(::assertTrue)

        assertEquals("RESTORED_PACKAGE_DETECTED", store.value.packageState)
        assertEquals("PRESERVED", store.value.failureCategory)
        assertEquals(7L, store.value.currentGeneration)
        assertEquals(null, store.value.portablePackageGeneration)
    }

    @Test
    fun preservedInputPersistsAfterMissingStateIsEstablishedAndBlocksRestart() = runBlocking {
        val store = DeferredStateStore()
        val startupEvents = mutableListOf<String>()
        var publisherCalls = 0
        val firstRuntime = runtime(
            intake = {
                startupEvents += "intake"
                RestoredPackageIntakeResult.Preserved(RestoredPackageCondition.PRESERVED)
            },
            bootstrapKey = {
                startupEvents += "key"
                true
            },
            requestCoordinator = {
                startupEvents += "coordinator"
                store.establish(
                    state(
                        generation = 11,
                        envelopeReady = true,
                    ),
                )
            },
            observeStates = {
                startupEvents += "observe"
                store.observe(VaultId("vault-primary"))
            },
            recordStatus = {
                recordRestoredPackageStatus(
                    stateStore = store,
                    vaultId = VaultId("vault-primary"),
                    status = it,
                )
            },
            restoredPublicationBlocked = {
                restoredPackagePublicationBlocked(
                    stateStore = store,
                    vaultId = VaultId("vault-primary"),
                )
            },
            refreshPublisher = { publisherCalls += 1 },
        )

        firstRuntime.runtime.start()
        assertTrue(store.persisted.await(2, TimeUnit.SECONDS))

        assertEquals(
            listOf("intake", "key", "coordinator", "observe"),
            startupEvents,
        )
        assertEquals(1, store.statusWrites)
        assertEquals(11L, store.value.currentGeneration)
        assertEquals("snapshot:11", store.value.currentBaseObjectId)
        assertEquals("RESTORED_PACKAGE_DETECTED", store.value.packageState)
        assertEquals("PRESERVED", store.value.failureCategory)
        assertEquals(0, publisherCalls)
        firstRuntime.close()

        store.applyCoordinatorUpdate()
        val statusScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = PersistedAndroidBackupStatusSource(
            scope = statusScope,
            observeBackupState = { store.observe(VaultId("vault-primary")) },
        )
        val exposed = withTimeout(2_000) {
            source.status.first {
                it == AndroidBackupStatus.RestoredPackageDetected(
                    RestoredPackageCondition.PRESERVED,
                )
            }
        }
        val restartObserved = CountDownLatch(1)
        val restartedRuntime = runtime(
            observeStates = {
                restartObserved.countDown()
                store.observe(VaultId("vault-primary"))
            },
            restoredPublicationBlocked = {
                restoredPackagePublicationBlocked(
                    stateStore = store,
                    vaultId = VaultId("vault-primary"),
                )
            },
            refreshPublisher = { publisherCalls += 1 },
        )

        restartedRuntime.runtime.start()
        assertTrue(restartObserved.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)

        assertEquals(
            AndroidBackupStatus.RestoredPackageDetected(
                RestoredPackageCondition.PRESERVED,
            ),
            exposed,
        )
        assertEquals(12L, store.value.currentGeneration)
        assertEquals(1, store.statusWrites)
        assertEquals(0, publisherCalls)
        restartedRuntime.close()
        statusScope.cancel()
    }

    @Test
    fun guardedContentKeyFailureStopsBackupWithoutCreatingReplacement() {
        var coordinatorCalls = 0
        var observationCalls = 0
        var publisherCalls = 0
        val fixture = runtime(
            bootstrapKey = { false },
            requestCoordinator = { coordinatorCalls += 1 },
            observeStates = {
                observationCalls += 1
                flowOf(state(envelopeReady = true))
            },
            refreshPublisher = { publisherCalls += 1 },
        )

        fixture.runtime.start()
        Thread.sleep(50)

        assertEquals(0, coordinatorCalls)
        assertEquals(0, observationCalls)
        assertEquals(0, publisherCalls)
        fixture.close()
    }

    @Test
    fun intakeFailureStillStartsLocalCoordinatorAndBlocksPublisher() {
        val coordinator = CountDownLatch(1)
        var publisherCalls = 0
        val fixture = runtime(
            intake = { throw IllegalStateException("intake failed") },
            requestCoordinator = { coordinator.countDown() },
            observeStates = { flowOf(state(envelopeReady = true)) },
            refreshPublisher = { publisherCalls += 1 },
        )

        fixture.runtime.start()
        assertTrue(coordinator.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)

        assertEquals(0, publisherCalls)
        fixture.close()
    }

    private fun runtime(
        intake: suspend () -> RestoredPackageIntakeResult = {
            RestoredPackageIntakeResult.NoPackage
        },
        bootstrapKey: suspend () -> Boolean = { true },
        requestCoordinator: suspend () -> Unit = {},
        observeStates: () -> kotlinx.coroutines.flow.Flow<BackupStateEntity> = {
            MutableSharedFlow(replay = 1)
        },
        envelopeAvailable: suspend () -> Boolean = { true },
        recordStatus:
            suspend (AndroidBackupStatus.RestoredPackageDetected) -> Boolean = { true },
        restoredPublicationBlocked: suspend () -> Boolean = { false },
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
                recordStatus = recordStatus,
                restoredPublicationBlocked = restoredPublicationBlocked,
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
            packageState: String = "NOT_PREPARED",
            failureCategory: String? = null,
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
            packageState = packageState,
            failureCategory = failureCategory,
            recoveryEnvelopeReady = envelopeReady,
            legacyOutboxCoveredAtGeneration = generation,
            snapshotCreatedAtEpochMillis = 1234,
        )
    }

    private class FakeStateStore(initial: BackupStateEntity) :
        app.opentasks.core.data.backup.BackupStateStore {
        private val flow = kotlinx.coroutines.flow.MutableStateFlow(initial)
        val value: BackupStateEntity
            get() = flow.value

        override fun observe(vaultId: VaultId) = flow
        override suspend fun get(vaultId: VaultId) = flow.value

        override suspend fun compareAndUpdate(
            entity: BackupStateEntity,
            expectedCurrentGeneration: Long,
        ): Int {
            if (flow.value.currentGeneration != expectedCurrentGeneration) return 0
            flow.value = entity
            return 1
        }
    }

    private class DeferredStateStore :
        app.opentasks.core.data.backup.BackupStateStore {
        private val flow = MutableStateFlow<BackupStateEntity?>(null)
        val persisted = CountDownLatch(1)
        var statusWrites = 0
            private set
        val value: BackupStateEntity
            get() = checkNotNull(flow.value)

        override fun observe(vaultId: VaultId) = flow.filterNotNull()
        override suspend fun get(vaultId: VaultId) = flow.value

        override suspend fun compareAndUpdate(
            entity: BackupStateEntity,
            expectedCurrentGeneration: Long,
        ): Int {
            val current = flow.value ?: return 0
            if (current.currentGeneration != expectedCurrentGeneration) return 0
            flow.value = entity
            if (entity.packageState == "RESTORED_PACKAGE_DETECTED") {
                statusWrites += 1
                persisted.countDown()
            }
            return 1
        }

        fun establish(entity: BackupStateEntity) {
            check(flow.value == null)
            flow.value = entity
        }

        fun applyCoordinatorUpdate() {
            flow.value = value.copy(
                currentGeneration = 12,
                lastVerifiedSnapshotGeneration = 12,
                currentBaseObjectId = "snapshot:12",
                latestVerifiedSegmentGeneration = 12,
                legacyOutboxCoveredAtGeneration = 12,
            )
        }
    }
}
