package app.opentasks.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.opentasks.core.domain.RemoteBackupRunner
import app.opentasks.core.domain.RemoteBackupRunResult

/**
 * Runs one routine remote-backup attempt in the background.
 *
 * The worker receives nothing but a way to reach the vault that is active
 * right now: its [WorkerParameters] carry no input data, so no lineage,
 * provider identity, account digest, token, claim, publication, session URI,
 * passphrase, or task text ever reaches WorkManager's database. A device
 * whose vault is closed — or which never had remote backup — resolves no
 * runner and simply completes.
 *
 * The worker starts no user interface. Authorization that needs a person is
 * persisted as an action-required state by the run itself and reported here
 * as an ordinary completion, so background work never opens a retry loop
 * around a consent screen.
 */
class RemoteBackupWorker(
    appContext: Context,
    parameters: WorkerParameters,
    private val runner: () -> RemoteBackupRunner?,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = remoteBackupWorkerResult(runner()?.run())
}

/**
 * Maps a bounded run outcome to WorkManager's retry contract.
 *
 * Only a transient provider failure is worth WorkManager's exponential
 * backoff. Every other outcome — including the ones a person has to resolve —
 * has already recorded its bounded state durably, so retrying would repeat
 * an attempt that cannot yet succeed. A null outcome means no vault was
 * active to run against.
 */
internal fun remoteBackupWorkerResult(
    outcome: RemoteBackupRunResult?,
): ListenableWorker.Result = when (outcome) {
    is RemoteBackupRunResult.Retryable -> ListenableWorker.Result.retry()
    is RemoteBackupRunResult.Verified,
    RemoteBackupRunResult.NoChanges,
    RemoteBackupRunResult.AuthorizationRequired,
    RemoteBackupRunResult.AccountMismatch,
    RemoteBackupRunResult.OwnershipLost,
    RemoteBackupRunResult.Terminated,
    RemoteBackupRunResult.AmbiguousRemoteState,
    is RemoteBackupRunResult.Blocked,
    null,
    -> ListenableWorker.Result.success()
}
