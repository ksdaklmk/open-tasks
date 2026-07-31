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
 */
class DefaultRemoteBackupRunner(
    private val vaultId: VaultId,
    private val remoteStateStore: RemoteBackupStateStore,
    private val coordinator: RemoteBackupCoordinator,
    private val authorize: suspend (ByteArray) -> DriveAuthorizationResult,
    private val clearToken: suspend (AuthorizedDriveSession) -> Unit,
    private val openObjectStore: (CreateOnlyDriveTransport) -> CreateOnlyBackupObjectStore,
) : RemoteBackupRunner {
    private val mutex = Mutex()

    override suspend fun run(): RemoteBackupRunResult = mutex.withLock { runExclusively() }

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
        return when (val authorization = authorizeFor(configuration)) {
            is DriveAuthorizationResult.Authorized ->
                publish(configuration, authorization.session)

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
        persistCategory(configuration.lineageId, outcome.failureCategory())
        return outcome
    }

    private suspend fun persistCategory(
        lineageId: CloudLineageId,
        category: RemoteBackupFailureCategory?,
    ) {
        try {
            repeat(PERSIST_ATTEMPTS) {
                // Reread rather than reuse: a verified run has already advanced
                // the checkpoint this state version guards.
                val stored = remoteStateStore.known(lineageId) ?: return
                if (stored.failureCategory == category) return
                val applied = remoteStateStore.compareAndSet(
                    lineageId = lineageId,
                    expected = stored.stateVersion,
                    next = stored.copy(
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
 */
class DefaultRemoteBackupRuntime(
    private val scope: CoroutineScope,
    private val runner: RemoteBackupRunner,
    private val scheduler: BackupWorkScheduler,
    private val observeConfiguration: () -> Flow<RemoteBackupConfiguration?>,
    private val observeLocalGeneration: () -> Flow<Long>,
) : RemoteBackupRuntime {
    private val started = AtomicBoolean()
    private val stopped = AtomicBoolean()
    private val manualRequests = Channel<Unit>(Channel.CONFLATED)
    private var observation: Job? = null

    /** Only ever read and written by the single observing coroutine. */
    private var lastActive: Boolean? = null

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        observation = scope.launch {
            combine(
                observeConfiguration(),
                observeLocalGeneration().distinctUntilChanged(),
            ) { configuration, generation -> Observation(configuration, generation) }
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
    }

    override fun requestNow() {
        if (started.get() && !stopped.get()) manualRequests.trySend(Unit)
    }

    override fun stop() {
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
            if (active) scheduler.ensurePeriodic() else scheduler.cancelAll()
        }
        if (configuration == null || !active) return
        val verified = configuration.lastVerifiedGeneration?.value ?: NOTHING_VERIFIED
        if (observation.generation > verified) scheduler.onPendingGeneration()
    }

    private data class Observation(
        val configuration: RemoteBackupConfiguration?,
        val generation: Long,
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
 */
private fun DriveAuthorizationUnavailableReason.toRunResult(): RemoteBackupRunResult = when (this) {
    DriveAuthorizationUnavailableReason.AUTHORIZATION_REQUIRED,
    DriveAuthorizationUnavailableReason.REJECTED,
    -> RemoteBackupRunResult.AuthorizationRequired

    DriveAuthorizationUnavailableReason.RETRYABLE ->
        RemoteBackupRunResult.Retryable(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
}
