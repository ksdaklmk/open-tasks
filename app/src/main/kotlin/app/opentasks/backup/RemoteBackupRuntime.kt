package app.opentasks.backup

import app.opentasks.backup.drive.AuthorizedDriveSession
import app.opentasks.backup.drive.DriveAuthorizationResult
import app.opentasks.backup.drive.DriveAuthorizationUnavailableReason
import app.opentasks.core.data.backup.RemoteBackupStateStore
import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.domain.BackupWorkScheduler
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupCoordinator
import app.opentasks.core.domain.RemoteBackupRunResult
import app.opentasks.core.domain.RemoteBackupRunner
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.VaultId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns routine remote backup for exactly one active vault.
 *
 * The runtime is the only thing that constructs a [RemoteBackupRunner] for a
 * vault, and there is one runtime per open slot, so a background worker and a
 * manual request drive the same single runner rather than two coordinators
 * racing each other's durable publication state.
 */
interface RemoteBackupRuntime {
    fun start()

    /** Asks for one run now, coalescing with a request that has not started yet. */
    fun requestNow()

    fun stop()
}

internal data class RemoteBackupExecution(
    val sequence: Long,
    val startedGeneration: Long?,
    val running: Boolean,
)

/**
 * One routine remote-backup attempt: authorize silently, publish, record.
 *
 * A run is serialized by a mutex so no two passes of the same
 * [RemoteBackupCoordinator] can ever overlap — the invariant retention
 * depends on, not only durable-state consistency. Authorization is always
 * non-interactive: the caller supplies an [authorize] that may never show a
 * consent screen, and a resolution this process cannot satisfy is recorded as
 * an action-required state instead of retried.
 *
 * The object store is constructed per run around the session's transport and
 * dies with it: closing the session in `finally` closes the transport, so no
 * provider handle outlives the authorization that produced it. A run that the
 * provider itself refused clears Google's cached token first, so the next
 * silent authorization cannot reuse a token that is already rejected.
 *
 * No account digest, token, provider identity, or lineage leaves this class:
 * the digest copy this run reads is zeroed before it returns, and only a
 * bounded [RemoteBackupFailureCategory] is ever persisted.
 *
 * The runner belongs to one vault slot and dies with it. [stop] is what makes
 * that binding real: background work resolves a runner when the worker is
 * *created*, which can be before the slot it belonged to was replaced, so a
 * stopped runner refuses to run rather than driving a coordinator whose
 * database is being torn down beside a freshly constructed replacement.
 *
 * A verified publication is also the only moment attachment collection may
 * happen, because only then is the newest retained base known to contain every
 * retirement this vault has recorded.
 */
class DefaultRemoteBackupRunner(
    private val vaultId: VaultId,
    private val remoteStateStore: RemoteBackupStateStore,
    private val coordinator: RemoteBackupCoordinator,
    private val authorize: suspend (ByteArray) -> DriveAuthorizationResult,
    private val clearToken: suspend (AuthorizedDriveSession) -> Unit,
    private val openObjectStore: (CreateOnlyDriveTransport) -> CreateOnlyBackupObjectStore,
    private val readLocalGeneration: suspend () -> Long?,
    private val publicationGate: Mutex = Mutex(),
    private val collectAttachments: suspend () -> Unit = {},
) : RemoteBackupRunner {
    private val stopped = AtomicBoolean()
    private var runSequence = 0L
    private val runInFlight = MutableStateFlow(false)
    private val currentExecution = MutableStateFlow<RemoteBackupExecution?>(null)

    /**
     * Whether a run currently holds the single-run lock.
     *
     * Product status reads this directly; scheduling uses [execution] so it
     * also retains the exact generation after a run completes.
     */
    internal val running: StateFlow<Boolean> = runInFlight.asStateFlow()
    internal val execution: StateFlow<RemoteBackupExecution?> = currentExecution.asStateFlow()

    /** Refuses every later run; the slot this runner was built for is gone. */
    fun stop() {
        stopped.set(true)
    }

    override suspend fun run(): RemoteBackupRunResult {
        if (stopped.get()) return RemoteBackupRunResult.NoChanges
        return publicationGate.withLock {
            // Rechecked under the lock: a run can queue behind another while
            // the slot is being replaced.
            if (stopped.get()) {
                RemoteBackupRunResult.NoChanges
            } else {
                val startedGeneration = try {
                    readLocalGeneration()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
                runSequence += 1
                val execution = RemoteBackupExecution(
                    sequence = runSequence,
                    startedGeneration = startedGeneration,
                    running = true,
                )
                currentExecution.value = execution
                runInFlight.value = true
                try {
                    runExclusively()
                } finally {
                    runInFlight.value = false
                    currentExecution.value = execution.copy(running = false)
                }
            }
        }
    }

    private suspend fun runExclusively(): RemoteBackupRunResult {
        val configuration = try {
            remoteStateStore.active(vaultId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.LOCAL_STORAGE)
        }
        // A vault with no lineage, or one that is dormant, lost, or terminated,
        // has nothing routine to publish and must not touch an account at all.
        if (configuration == null || configuration.lifecycle != RemoteBackupLifecycle.ACTIVE) {
            return RemoteBackupRunResult.NoChanges
        }
        val rotationInProgress = try {
            hasUnfinishedRecoveryPassphraseChange(remoteStateStore, configuration)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.LOCAL_STORAGE)
        }
        if (rotationInProgress) return RemoteBackupRunResult.NoChanges
        return when (val authorization = authorizeFor(configuration)) {
            is DriveAuthorizationResult.Authorized ->
                publish(configuration, authorization.session).also { outcome ->
                    if (outcome is RemoteBackupRunResult.Verified) collectRetiredAttachments()
                }

            // The bound account is not the one this device can reach, so the
            // lineage is never read, listed, or written.
            DriveAuthorizationResult.AccountMismatch ->
                settle(configuration, RemoteBackupRunResult.AccountMismatch)

            // Background work never starts a pending intent; the person is
            // asked through persisted product state instead.
            is DriveAuthorizationResult.ResolutionRequired ->
                settle(configuration, RemoteBackupRunResult.AuthorizationRequired)

            is DriveAuthorizationResult.Unavailable ->
                settle(configuration, authorization.reason.toRunResult())
        }
    }

    private suspend fun authorizeFor(
        configuration: RemoteBackupConfiguration,
    ): DriveAuthorizationResult {
        val expectedDigest = configuration.accountBindingDigest
        return try {
            authorize(expectedDigest)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            DriveAuthorizationResult.Unavailable(DriveAuthorizationUnavailableReason.RETRYABLE)
        } finally {
            expectedDigest.fill(0)
        }
    }

    private suspend fun publish(
        configuration: RemoteBackupConfiguration,
        session: AuthorizedDriveSession,
    ): RemoteBackupRunResult {
        var outcome: RemoteBackupRunResult =
            RemoteBackupRunResult.Retryable(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
        try {
            outcome = try {
                coordinator.run(openObjectStore(session.transport))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }
        } finally {
            closeSession(
                session = session,
                clearsToken = outcome == RemoteBackupRunResult.AuthorizationRequired,
            )
        }
        return settle(configuration, outcome)
    }

    /**
     * Attachment collection is upkeep, never part of a publication's outcome:
     * a lineage that verified stays verified even when the retired bytes
     * behind it could not be collected this pass, and the next verified
     * publication tries again.
     */
    private suspend fun collectRetiredAttachments() {
        try {
            collectAttachments()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Local editing and structured backup are unaffected.
        }
    }

    private suspend fun closeSession(session: AuthorizedDriveSession, clearsToken: Boolean) {
        withContext(NonCancellable) {
            if (clearsToken) {
                try {
                    clearToken(session)
                } catch (_: Exception) {
                    // A token cache the provider keeps is no reason to leak
                    // this session's transport; the close below still runs.
                }
            }
            session.close()
        }
    }

    /** Records the bounded outcome without ever changing the remote checkpoint. */
    private suspend fun settle(
        configuration: RemoteBackupConfiguration,
        outcome: RemoteBackupRunResult,
    ): RemoteBackupRunResult {
        persistOutcome(configuration.lineageId, outcome)
        return outcome
    }

    private suspend fun persistOutcome(
        lineageId: CloudLineageId,
        outcome: RemoteBackupRunResult,
    ) {
        val category = outcome.failureCategory()
        try {
            repeat(PERSIST_ATTEMPTS) {
                // Reread rather than reuse: a verified run has already advanced
                // the checkpoint this state version guards.
                val stored = remoteStateStore.known(lineageId) ?: return
                val lifecycle = if (stored.lifecycle == RemoteBackupLifecycle.ACTIVE) {
                    when (outcome) {
                        RemoteBackupRunResult.OwnershipLost ->
                            RemoteBackupLifecycle.OWNERSHIP_LOST
                        RemoteBackupRunResult.Terminated -> RemoteBackupLifecycle.TERMINATED
                        else -> stored.lifecycle
                    }
                } else {
                    stored.lifecycle
                }
                if (stored.failureCategory == category && stored.lifecycle == lifecycle) return
                val applied = remoteStateStore.compareAndSet(
                    lineageId = lineageId,
                    expected = stored.stateVersion,
                    next = stored.copy(
                        lifecycle = lifecycle,
                        failureCategory = category,
                        stateVersion = RemoteBackupStateVersion(stored.stateVersion.value + 1),
                    ),
                )
                if (applied) return
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Local editing and Stage 2 package production stay available
            // through every remote failure; the next run records this again.
        }
    }

    private companion object {
        const val PERSIST_ATTEMPTS = 3
    }
}

/**
 * Schedules remote backup only while both the vault and its lineage are live.
 *
 * Work is enqueued when the local generation is ahead of the generation the
 * lineage has verified, and the recurring safety net is ensured once per
 * activation. Every state that is not an active lineage — no lineage at all,
 * dormancy, ownership loss, deletion, termination, or a blocked lineage —
 * cancels the ordinary work instead, as does stopping with the vault. A
 * durable terminal-deletion operation is resumed from its own persisted
 * record rather than from this ordinary publication work, so cancelling here
 * never abandons one.
 *
 * Nothing is enqueued while a run is in flight. The debounced unit is
 * enqueued with `REPLACE`, which cancels work that is already *running*, so
 * re-enqueueing on every edit would let a vault edited more often than an
 * upload takes never finish the debounced path at all. The runner's in-flight
 * state is therefore part of what this observes, and a run finishing is its
 * own event.
 *
 * That completion enqueues only for work the finished run did not already
 * attempt — a generation strictly newer than the one it started from. A run
 * that fails with nothing new behind it must not re-arm the debounce: doing
 * so would poll an outcome that cannot change on its own every fifteen
 * minutes forever, and would replace the still-returning worker, discarding
 * the `Result.retry()` whose 30-second exponential backoff is what actually
 * owns transient retry. An action-required state waits for the person or for
 * the periodic pass instead.
 *
 * The runner is held as its concrete type on purpose. This runtime owns the
 * single runner for its slot, and that ownership is what both the in-flight
 * suppression above and [stop]'s teardown depend on.
 */
class DefaultRemoteBackupRuntime(
    private val scope: CoroutineScope,
    private val runner: DefaultRemoteBackupRunner,
    private val scheduler: BackupWorkScheduler,
    private val observeConfiguration: () -> Flow<RemoteBackupConfiguration?>,
    private val observeLocalGeneration: () -> Flow<Long>,
    private val expireAttachmentSessions: suspend () -> Unit = {},
    private val resumeAttachmentSessions: suspend () -> Unit = {},
) : RemoteBackupRuntime {
    private val started = AtomicBoolean()
    private val stopped = AtomicBoolean()
    private val manualRequests = Channel<Unit>(Channel.CONFLATED)
    private var observation: Job? = null

    // All four are only ever read and written by the single observing
    // coroutine, so they need no synchronisation.
    private var lastActive: Boolean? = null

    /** The generation the debounced work was last enqueued for. */
    private var requestedGeneration: Long? = null

    /** The newest run-start generation already handled at completion. */
    private var lastAttemptedGeneration: Long? = null

    private var completedExecution: Long? = null

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        observation = scope.launch {
            combine(
                observeConfiguration(),
                observeLocalGeneration().distinctUntilChanged(),
                runner.execution,
            ) { configuration, generation, execution ->
                Observation(configuration, generation, execution)
            }
                .collect(::apply)
        }
        scope.launch {
            for (request in manualRequests) {
                try {
                    runner.run()
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    // Structured editing is unaffected; a later request retries.
                }
            }
        }
        // An intake this slot never finished holds provider objects no record
        // names. Activation is when that residue is abandoned; the attachment
        // runtime refuses the pass itself unless its lineage is still active.
        // Resuming runs right after, in the same coroutine, so a session old
        // enough to expire is never also offered to resume.
        scope.launch {
            try {
                expireAttachmentSessions()
                resumeAttachmentSessions()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                // The next activation expires and resumes the same sessions again.
            }
        }
    }

    override fun requestNow() {
        if (started.get() && !stopped.get()) manualRequests.trySend(Unit)
    }

    /**
     * Stops the runner first, so a worker that resolved this slot's runner
     * before the slot was replaced refuses to run rather than driving a
     * coordinator alongside its own replacement.
     */
    override fun stop() {
        runner.stop()
        if (!started.get() || !stopped.compareAndSet(false, true)) return
        observation?.cancel()
        manualRequests.close()
        scheduler.cancelAll()
    }

    private fun apply(observation: Observation) {
        if (stopped.get()) return
        val configuration = observation.configuration
        val active = configuration != null &&
            configuration.lifecycle == RemoteBackupLifecycle.ACTIVE
        if (lastActive != active) {
            lastActive = active
            if (active) {
                scheduler.ensurePeriodic()
            } else {
                // A lineage that stops being active abandons what it asked
                // for, so a later activation arms the debounce again.
                requestedGeneration = null
                lastAttemptedGeneration = null
                scheduler.cancelAll()
            }
        }
        if (configuration == null || !active) {
            completedExecution = observation.execution?.sequence
            return
        }

        val execution = observation.execution
        if (execution?.running == true) return
        if (execution != null && execution.sequence != completedExecution) {
            completedExecution = execution.sequence
            execution.startedGeneration?.let { started ->
                lastAttemptedGeneration = maxOf(lastAttemptedGeneration ?: started, started)
            }
            // A publication run just finished; a session interrupted during
            // or before that run may now be resumable under whatever
            // ownership state the run settled. This does not depend on
            // whether the run's outcome re-arms the debounce below, so a
            // long-lived process keeps retrying even when nothing new is
            // scheduled.
            scope.launch {
                try {
                    resumeAttachmentSessions()
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    // The next completed run resumes the same sessions again.
                }
            }
        }
        val verified = configuration.lastVerifiedGeneration?.value ?: NOTHING_VERIFIED
        if (observation.generation <= verified) return
        // The run that just finished already attempted exactly this
        // generation, so re-arming the debounce would poll a result that
        // cannot change on its own — every 15 minutes, forever. What happens
        // next belongs to that run's own outcome instead: WorkManager's
        // 30-second exponential backoff owns a transient failure, and an
        // action-required state waits for the person or the periodic pass.
        // Re-arming here would also replace the still-returning worker and
        // discard the `Result.retry()` it is in the middle of reporting.
        val attempted = lastAttemptedGeneration
        if (attempted != null && observation.generation <= attempted) return
        // Work is already scheduled for this generation; a configuration
        // change alone is no reason to restart its debounce.
        val requested = requestedGeneration
        if (requested != null && observation.generation <= requested) return

        requestedGeneration = observation.generation
        scheduler.onPendingGeneration()
    }

    private data class Observation(
        val configuration: RemoteBackupConfiguration?,
        val generation: Long,
        val execution: RemoteBackupExecution?,
    )

    private companion object {
        /** A lineage that has verified nothing is behind every local generation. */
        const val NOTHING_VERIFIED = -1L
    }
}

private fun RemoteBackupRunResult.failureCategory(): RemoteBackupFailureCategory? = when (this) {
    is RemoteBackupRunResult.Verified, RemoteBackupRunResult.NoChanges -> null
    RemoteBackupRunResult.AuthorizationRequired ->
        RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED

    RemoteBackupRunResult.AccountMismatch -> RemoteBackupFailureCategory.ACCOUNT_MISMATCH
    RemoteBackupRunResult.OwnershipLost -> RemoteBackupFailureCategory.OWNERSHIP_LOST
    RemoteBackupRunResult.Terminated -> RemoteBackupFailureCategory.TERMINATED
    RemoteBackupRunResult.AmbiguousRemoteState ->
        RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE

    is RemoteBackupRunResult.Retryable -> reason
    is RemoteBackupRunResult.Blocked -> reason
}

/**
 * A grant that was refused and a grant that was never given both need the
 * same thing from a person — reconnect — and neither may be retried silently.
 *
 * Nothing else may claim that, though. Silent authorization probes the
 * provider to bind the account, so a storage or malformed-response failure
 * arrives here too; reporting either as "reconnect your account" would send a
 * person after an account that is working. Each keeps its own bounded
 * category instead.
 */
private fun DriveAuthorizationUnavailableReason.toRunResult(): RemoteBackupRunResult = when (this) {
    DriveAuthorizationUnavailableReason.AUTHORIZATION_REQUIRED,
    DriveAuthorizationUnavailableReason.REJECTED,
    -> RemoteBackupRunResult.AuthorizationRequired

    DriveAuthorizationUnavailableReason.RETRYABLE ->
        RemoteBackupRunResult.Retryable(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)

    DriveAuthorizationUnavailableReason.PROVIDER_STORAGE ->
        RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.PROVIDER_STORAGE)

    DriveAuthorizationUnavailableReason.CORRUPT_OR_INCOMPATIBLE ->
        RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
}
