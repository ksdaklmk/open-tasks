package app.opentasks.backup

import android.app.PendingIntent
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun anUnfinishedPassphraseRotationPreventsOrdinaryPublication() {
        val fixture = runnerFixture(outcome = RemoteBackupRunResult.NoChanges)
        fixture.stateStore.recordOperation(
            RemoteBackupOperation(
                operationId = "recovery-passphrase-change:${VAULT_ID.value}:0",
                lineageId = LINEAGE_ID,
                kind = "RECOVERY_PASSPHRASE_CHANGE",
                phase = "REMOTE_PUBLICATION_CREATED",
                targetEpoch = WriterEpoch(1),
                targetGeneration = BackupGeneration(1),
                candidateClaimProviderId = ProviderObjectId.of("claim"),
                candidatePublicationProviderId = ProviderObjectId.of("rotation-publication"),
                stateBytes = byteArrayOf(1),
                startedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )

        val result = runBlocking { withTimeout(5_000) { fixture.runner.run() } }

        assertEquals(RemoteBackupRunResult.NoChanges, result)
        assertEquals(0, fixture.authorizations.get())
        assertEquals(0, fixture.coordinator.passes.get())
    }

    @Test
    fun ordinaryPublicationWaitsForTheVaultPublicationGate() = runBlocking {
        val gate = Mutex(locked = true)
        val fixture = runnerFixture(
            outcome = RemoteBackupRunResult.NoChanges,
            publicationGate = gate,
        )

        val result = async(Dispatchers.Default) { fixture.runner.run() }
        delay(25)
        assertEquals(0, fixture.authorizations.get())
        assertEquals(0, fixture.coordinator.passes.get())

        gate.unlock()

        assertEquals(RemoteBackupRunResult.NoChanges, withTimeout(5_000) { result.await() })
        assertEquals(1, fixture.authorizations.get())
        assertEquals(1, fixture.coordinator.passes.get())
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
    fun silentAuthorizationRequirementPersistsActionRequiredWithoutRunningTheLineage() {
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

    /**
     * The pending intent is a live instance whose every member throws, so a
     * runner that reads it at all — to start it, log it, or even print it —
     * fails here rather than opening a consent screen from background work.
     */
    @Test
    fun aResolutionRequirementPersistsActionRequiredWithoutTouchingItsPendingIntent() {
        val fixture = runnerFixture(
            authorization = {
                DriveAuthorizationResult.ResolutionRequired(untouchablePendingIntent())
            },
        )

        val result = runBlocking { withTimeout(5_000) { fixture.runner.run() } }

        assertEquals(RemoteBackupRunResult.AuthorizationRequired, result)
        assertEquals(0, fixture.coordinator.passes.get())
        assertEquals(0, fixture.openedObjectStores.get())
        assertEquals(
            RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED,
            fixture.stateStore.stored().failureCategory,
        )
    }

    /**
     * Silent authorization probes the provider to bind the account, so its
     * storage and malformed-response failures arrive as authorization
     * outcomes. Neither says anything about the account, and reporting either
     * as "reconnect" would send a person after an account that works.
     */
    @Test
    fun providerStorageAndCorruptAuthorizationFailuresNeverAskThePersonToReconnect() {
        val storage = runnerFixture(
            authorization = {
                DriveAuthorizationResult.Unavailable(
                    DriveAuthorizationUnavailableReason.PROVIDER_STORAGE,
                )
            },
        )
        val corrupt = runnerFixture(
            authorization = {
                DriveAuthorizationResult.Unavailable(
                    DriveAuthorizationUnavailableReason.CORRUPT_OR_INCOMPATIBLE,
                )
            },
        )

        val storageResult = runBlocking { withTimeout(5_000) { storage.runner.run() } }
        val corruptResult = runBlocking { withTimeout(5_000) { corrupt.runner.run() } }

        assertEquals(
            RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.PROVIDER_STORAGE),
            storageResult,
        )
        assertEquals(
            RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE),
            corruptResult,
        )
        assertEquals(
            RemoteBackupFailureCategory.PROVIDER_STORAGE,
            storage.stateStore.stored().failureCategory,
        )
        assertEquals(
            RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            corrupt.stateStore.stored().failureCategory,
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
    fun authorityLossPromotesAnActiveLineageOutOfRoutinePublication() {
        listOf(
            RemoteBackupRunResult.OwnershipLost to RemoteBackupLifecycle.OWNERSHIP_LOST,
            RemoteBackupRunResult.Terminated to RemoteBackupLifecycle.TERMINATED,
        ).forEach { (outcome, lifecycle) ->
            val fixture = runnerFixture(outcome = outcome)

            val result = runBlocking { withTimeout(5_000) { fixture.runner.run() } }

            assertEquals(outcome, result)
            assertEquals(lifecycle, fixture.stateStore.stored().lifecycle)
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

    // -- Runner: bound to one slot ---------------------------------------------------------

    /**
     * Background work resolves a runner when the worker is *created*, which
     * can be before the slot it belonged to is replaced. A stopped runner
     * must refuse rather than drive a coordinator beside its replacement.
     */
    @Test
    fun aRunnerStoppedWithItsSlotRefusesWorkThatResolvedItBeforeTheReplacement() {
        val fixture = runnerFixture(outcome = RemoteBackupRunResult.Verified(BackupGeneration(3)))
        val resolvedBeforeReplacement: RemoteBackupRunner = fixture.runner

        fixture.runner.stop()
        val result = runBlocking {
            withTimeout(5_000) { resolvedBeforeReplacement.run() }
        }

        assertEquals(RemoteBackupRunResult.NoChanges, result)
        assertEquals(0, fixture.authorizations.get())
        assertEquals(0, fixture.coordinator.passes.get())
        assertEquals(0, fixture.openedObjectStores.get())
    }

    /**
     * Ordering is established by the coordinator gate, not by racing delays:
     * the first run provably holds the single-run lock before the second is
     * launched, and the lock is only released after the slot has stopped.
     */
    @Test
    fun aRunnerQueuedBehindAnotherRunStillRefusesOnceItsSlotIsStopped() {
        val fixture = runnerFixture(outcome = RemoteBackupRunResult.NoChanges)
        val release = CountDownLatch(1)
        val queuedEntered = CountDownLatch(1)
        fixture.coordinator.gate = release

        val queuedResult = runBlocking {
            withTimeout(5_000) {
                val holder = async(Dispatchers.Default) { fixture.runner.run() }
                // The first run is inside the coordinator, holding the lock.
                assertTrue(fixture.coordinator.awaitPasses(1))
                val queued = async(Dispatchers.Default) {
                    queuedEntered.countDown()
                    fixture.runner.run()
                }
                queuedEntered.await()
                fixture.runner.stop()
                release.countDown()
                holder.await()
                queued.await()
            }
        }

        assertEquals(RemoteBackupRunResult.NoChanges, queuedResult)
        assertEquals(1, fixture.coordinator.passes.get())
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
        assertTrue(fixture.coordinator.awaitPasses(1))

        fixture.runtime.stop()
        assertTrue(fixture.scheduler.awaitEvents(2))
        fixture.localGeneration.value = 99

        Thread.sleep(SETTLE_MILLIS)
        assertEquals(listOf("periodic", "cancel"), fixture.scheduler.events())
        assertEquals(1, fixture.coordinator.passes.get())
        fixture.close()
    }

    /**
     * The debounced unit is enqueued with `REPLACE`, which cancels work that
     * is already running. An edit during an upload must therefore not
     * re-enqueue, or a vault edited more often than an upload takes would
     * never finish the debounced path at all.
     */
    @Test
    fun anEditDuringAnInFlightRunNeverReplacesItAndReEnqueuesOnceItFinishes() {
        val fixture = runtimeFixture(verifiedGeneration = 4, localGeneration = 4)
        val release = CountDownLatch(1)
        fixture.coordinator.gate = release

        fixture.runtime.start()
        assertTrue(fixture.scheduler.awaitEvents(1))
        fixture.runtime.requestNow()
        assertTrue(fixture.coordinator.awaitPasses(1))

        fixture.localGeneration.value = 5
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(listOf("periodic"), fixture.scheduler.events())

        release.countDown()
        assertTrue(fixture.scheduler.awaitEvents(2))

        assertEquals(listOf("periodic", "pending"), fixture.scheduler.events())
        assertEquals(1, fixture.coordinator.passes.get())
        fixture.close()
    }

    /**
     * A run that finishes with nothing new behind it must leave the debounce
     * alone. Re-arming would poll an outcome that cannot change on its own
     * every debounce, forever — a silent authorization and an `about.get`
     * each time — which is exactly what the worker's own contract says not to
     * do.
     */
    @Test
    fun aFailedRunWithNoNewEditsNeverReArmsTheDebouncedWork() {
        val fixture = runtimeFixture(
            verifiedGeneration = 4,
            localGeneration = 5,
            outcome = RemoteBackupRunResult.AuthorizationRequired,
        )

        fixture.runtime.start()
        assertTrue(fixture.scheduler.awaitEvents(2))
        assertEquals(listOf("periodic", "pending"), fixture.scheduler.events())

        fixture.runtime.requestNow()
        assertTrue(fixture.coordinator.awaitPasses(1))
        // The run's bounded action-required state reaches the configuration.
        fixture.configuration.value = configuration(
            verifiedGeneration = 4,
            failureCategory = RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED,
        )

        Thread.sleep(SETTLE_MILLIS)
        assertEquals(listOf("periodic", "pending"), fixture.scheduler.events())
        fixture.close()
    }

    /**
     * The completion emission fires while the worker that produced it is
     * still returning. Enqueueing then would `REPLACE` — and so cancel — that
     * worker, throwing away the `Result.retry()` whose 30-second exponential
     * backoff is what actually owns transient retry.
     */
    @Test
    fun aRetryableRunCompletionNeverReplacesTheWorkerStillReportingIt() {
        val fixture = runtimeFixture(
            verifiedGeneration = 4,
            localGeneration = 5,
            outcome = RemoteBackupRunResult.Retryable(
                RemoteBackupFailureCategory.RETRYABLE_PROVIDER,
            ),
        )
        val release = CountDownLatch(1)
        fixture.coordinator.gate = release

        fixture.runtime.start()
        assertTrue(fixture.scheduler.awaitEvents(2))
        fixture.runtime.requestNow()
        // Held open so the runtime observes the run in flight, and observes
        // its completion as a distinct event rather than a conflated flicker.
        assertTrue(fixture.coordinator.awaitPasses(1))
        assertTrue(fixture.awaitRunning(true))
        release.countDown()
        assertTrue(fixture.awaitRunning(false))

        Thread.sleep(SETTLE_MILLIS)
        assertEquals(listOf("periodic", "pending"), fixture.scheduler.events())
        fixture.close()
    }

    @Test
    fun repeatedRunsWithoutNewLocalWorkEnqueueNothingBeyondTheFirstDebounce() {
        val fixture = runtimeFixture(
            verifiedGeneration = 4,
            localGeneration = 5,
            outcome = RemoteBackupRunResult.AuthorizationRequired,
        )

        fixture.runtime.start()
        assertTrue(fixture.scheduler.awaitEvents(2))
        repeat(3) { attempt ->
            val release = CountDownLatch(1)
            fixture.coordinator.gate = release
            fixture.runtime.requestNow()
            assertTrue(fixture.coordinator.awaitPasses(attempt + 1))
            assertTrue(fixture.awaitRunning(true))
            release.countDown()
            assertTrue(fixture.awaitRunning(false))
        }

        Thread.sleep(SETTLE_MILLIS)
        assertEquals(listOf("periodic", "pending"), fixture.scheduler.events())
        assertEquals(3, fixture.coordinator.passes.get())
        fixture.close()
    }

    @Test
    fun stoppingTheRuntimeStopsItsRunnerSoLateWorkCannotDriveAReplacedSlot() {
        val fixture = runtimeFixture(verifiedGeneration = 4, localGeneration = 4)

        fixture.runtime.start()
        assertTrue(fixture.scheduler.awaitEvents(1))
        fixture.runtime.stop()

        val result = runBlocking { withTimeout(5_000) { fixture.runner.run() } }

        assertEquals(RemoteBackupRunResult.NoChanges, result)
        assertEquals(0, fixture.coordinator.passes.get())
        fixture.close()
    }

    // -- Attachment upkeep ------------------------------------------------------------------

    /**
     * Only a verified publication proves that the newest retained base
     * contains every retirement recorded so far, so only a verified
     * publication may collect the bytes those retirements released.
     */
    @Test
    fun onlyAVerifiedPublicationCollectsRetiredAttachmentBytes() {
        val collections = AtomicInteger()
        val verified = runnerFixture(
            outcome = RemoteBackupRunResult.Verified(BackupGeneration(2)),
            collectAttachments = { collections.incrementAndGet() },
        )
        val retryable = runnerFixture(
            outcome = RemoteBackupRunResult.Retryable(
                RemoteBackupFailureCategory.RETRYABLE_PROVIDER,
            ),
            collectAttachments = { collections.incrementAndGet() },
        )

        runBlocking {
            withTimeout(5_000) {
                assertEquals(
                    RemoteBackupRunResult.Verified(BackupGeneration(2)),
                    verified.runner.run(),
                )
                assertEquals(1, collections.get())
                assertEquals(
                    RemoteBackupRunResult.Retryable(
                        RemoteBackupFailureCategory.RETRYABLE_PROVIDER,
                    ),
                    retryable.runner.run(),
                )
            }
        }

        assertEquals(1, collections.get())
    }

    /** Collection is upkeep behind a publication, never part of its outcome. */
    @Test
    fun aFailedCollectionLeavesTheVerifiedPublicationVerified() {
        val fixture = runnerFixture(
            outcome = RemoteBackupRunResult.Verified(BackupGeneration(3)),
            collectAttachments = { throw IllegalStateException("collection failed") },
        )

        val result = runBlocking { withTimeout(5_000) { fixture.runner.run() } }

        assertEquals(RemoteBackupRunResult.Verified(BackupGeneration(3)), result)
        assertNull(fixture.stateStore.stored().failureCategory)
    }

    /**
     * An intake this slot never finished holds provider objects no record
     * names, so activation is when that residue is abandoned.
     */
    @Test
    fun activatingTheRuntimeAbandonsAttachmentSessionsItNeverFinished() {
        val expirations = AtomicInteger()
        val fixture = runtimeFixture(
            verifiedGeneration = 4,
            localGeneration = 4,
            expireAttachmentSessions = { expirations.incrementAndGet() },
        )

        fixture.runtime.start()

        assertTrue(awaitCount(expirations, 1))
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(1, expirations.get())
        fixture.close()
    }

    // -- Fixtures ---------------------------------------------------------------------------

    private fun runnerFixture(
        outcome: RemoteBackupRunResult = RemoteBackupRunResult.NoChanges,
        authorization: (suspend () -> DriveAuthorizationResult)? = null,
        lifecycle: RemoteBackupLifecycle = RemoteBackupLifecycle.ACTIVE,
        configured: Boolean = true,
        storedCategory: RemoteBackupFailureCategory? = null,
        publicationGate: Mutex = Mutex(),
        collectAttachments: suspend () -> Unit = {},
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
            publicationGate = publicationGate,
            collectAttachments = collectAttachments,
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

    /**
     * Builds the runtime over a *real* runner, so in-flight suppression and
     * stop-with-the-slot are exercised against the same mutex and flags
     * production uses rather than against a stand-in.
     */
    private fun runtimeFixture(
        verifiedGeneration: Long,
        localGeneration: Long,
        configured: Boolean = true,
        outcome: RemoteBackupRunResult = RemoteBackupRunResult.NoChanges,
        expireAttachmentSessions: suspend () -> Unit = {},
    ): RuntimeFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val configurationFlow = MutableStateFlow(
            if (configured) configuration(verifiedGeneration = verifiedGeneration) else null,
        )
        val generationFlow = MutableStateFlow(localGeneration)
        val scheduler = RecordingBackupWorkScheduler()
        val runnerFixture = runnerFixture(
            outcome = outcome,
            configured = configured,
        )
        val runner = runnerFixture.runner
        return RuntimeFixture(
            scope = scope,
            configuration = configurationFlow,
            localGeneration = generationFlow,
            scheduler = scheduler,
            runner = runner,
            coordinator = runnerFixture.coordinator,
            runtime = DefaultRemoteBackupRuntime(
                scope = scope,
                runner = runner,
                scheduler = scheduler,
                observeConfiguration = { configurationFlow },
                observeLocalGeneration = { generationFlow },
                expireAttachmentSessions = expireAttachmentSessions,
            ),
        )
    }

    /** Waits for a counter a background pass increments, or gives up. */
    private fun awaitCount(counter: AtomicInteger, expected: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (counter.get() >= expected) return true
            Thread.sleep(5)
        }
        return counter.get() >= expected
    }

    private class RuntimeFixture(
        private val scope: CoroutineScope,
        val configuration: MutableStateFlow<RemoteBackupConfiguration?>,
        val localGeneration: MutableStateFlow<Long>,
        val scheduler: RecordingBackupWorkScheduler,
        val runner: DefaultRemoteBackupRunner,
        val coordinator: FakeRemoteBackupCoordinator,
        val runtime: DefaultRemoteBackupRuntime,
    ) {
        /** Waits for the single-run lock to be held, or released again. */
        fun awaitRunning(expected: Boolean): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (runner.running.value == expected) return true
                Thread.sleep(5)
            }
            return runner.running.value == expected
        }

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

        /** Holds a pass open so a test can act while a run is in flight. */
        @Volatile
        var gate: CountDownLatch? = null

        override suspend fun run(objectStore: CreateOnlyBackupObjectStore): RemoteBackupRunResult {
            val concurrent = active.incrementAndGet()
            maximumConcurrentPasses.updateAndGet { maxOf(it, concurrent) }
            try {
                passes.incrementAndGet()
                if (passDelayMillis > 0) delay(passDelayMillis)
                gate?.let { latch -> while (latch.count > 0L) delay(5) }
                failure?.let { throw it }
                return outcome
            } finally {
                active.decrementAndGet()
            }
        }

        fun awaitPasses(count: Int): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (passes.get() >= count) return true
                Thread.sleep(10)
            }
            return passes.get() >= count
        }
    }

    private class FakeRemoteBackupStateStore(
        initial: RemoteBackupConfiguration?,
    ) : RemoteBackupStateStore {
        private var current: RemoteBackupConfiguration? = initial
        private val operations = mutableMapOf<String, RemoteBackupOperation>()

        fun stored(): RemoteBackupConfiguration = checkNotNull(current)

        fun recordOperation(operation: RemoteBackupOperation) {
            operations[operation.operationId] = operation
        }

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

        override suspend fun operation(operationId: String): RemoteBackupOperation? =
            operations[operationId]

        override suspend fun putOperation(operation: RemoteBackupOperation) {
            operations[operation.operationId] = operation
        }

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

        /**
         * A live [PendingIntent] every member of which throws, because the
         * unit-test Android stubs are unmocked. Passing one proves the runner
         * never reads the intent it was handed.
         */
        fun untouchablePendingIntent(): PendingIntent =
            PendingIntent::class.java.getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()

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
