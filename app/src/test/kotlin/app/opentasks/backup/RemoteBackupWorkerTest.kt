package app.opentasks.backup

import androidx.work.ListenableWorker
import app.opentasks.core.domain.RemoteBackupRunResult
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.RemoteBackupFailureCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteBackupWorkerTest {
    @Test
    fun onlyRetryableOutcomesAskWorkManagerToRetry() {
        RemoteBackupFailureCategory.entries.forEach { reason ->
            assertEquals(
                reason.name,
                ListenableWorker.Result.retry(),
                remoteBackupWorkerResult(RemoteBackupRunResult.Retryable(reason)),
            )
        }
    }

    @Test
    fun everyOtherBoundedOutcomeCompletesWithoutARetryLoop() {
        val settled = buildList {
            add(RemoteBackupRunResult.Verified(BackupGeneration(7)))
            add(RemoteBackupRunResult.NoChanges)
            add(RemoteBackupRunResult.AuthorizationRequired)
            add(RemoteBackupRunResult.AccountMismatch)
            add(RemoteBackupRunResult.OwnershipLost)
            add(RemoteBackupRunResult.Terminated)
            add(RemoteBackupRunResult.AmbiguousRemoteState)
            RemoteBackupFailureCategory.entries.forEach {
                add(RemoteBackupRunResult.Blocked(it))
            }
        }

        settled.forEach { outcome ->
            assertEquals(
                outcome.javaClass.simpleName,
                ListenableWorker.Result.success(),
                remoteBackupWorkerResult(outcome),
            )
        }
    }

    @Test
    fun aRunWithoutAnActiveVaultCompletesInsteadOfRetrying() {
        assertEquals(ListenableWorker.Result.success(), remoteBackupWorkerResult(null))
    }
}
