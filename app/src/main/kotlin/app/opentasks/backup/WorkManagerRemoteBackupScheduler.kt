package app.opentasks.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import app.opentasks.core.domain.BackupWorkScheduler
import java.time.Duration

/**
 * The exact WorkManager surface unique remote-backup scheduling needs.
 *
 * [WorkManager] cannot be constructed or subclassed outside its own library,
 * so this narrow seam is what lets the request policy above be proven on the
 * JVM instead of only on a device.
 */
internal interface UniqueWorkQueue {
    fun enqueueUniqueOneTime(
        uniqueWorkName: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )

    fun enqueueUniquePeriodic(
        uniqueWorkName: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    )

    fun cancelUniqueWork(uniqueWorkName: String)
}

/**
 * Schedules routine remote backup as two uniquely named units of work.
 *
 * A pending generation replaces the debounced unit so a burst of edits costs
 * one delayed run, and the recurring check is kept rather than restarted so
 * ordinary observation never postpones the safety net. Both requests carry no
 * input data at all: the run resolves the active vault, lineage, and account
 * binding from SQLCipher itself, so no lineage, provider identity, account
 * digest, token, claim, publication, or session URI is ever placed in a work
 * name, a tag, or worker input.
 *
 * [cancelAll] cancels only these two ordinary units. A durable terminal
 * deletion operation is resumed from its own persisted record, never from
 * ordinary publication work, so cancelling here can never abandon one.
 */
class WorkManagerRemoteBackupScheduler internal constructor(
    private val queue: UniqueWorkQueue,
) : BackupWorkScheduler {
    constructor(context: Context) : this(ApplicationUniqueWorkQueue(context))

    internal fun buildOneTimeRequest(): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(RemoteBackupWorker::class.java)
            .setConstraints(REMOTE_BACKUP_CONSTRAINTS)
            .setInitialDelay(DEBOUNCE_DELAY)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY)
            .build()

    internal fun buildPeriodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequest.Builder(
            RemoteBackupWorker::class.java,
            PERIODIC_INTERVAL,
            PERIODIC_FLEX,
        )
            .setConstraints(REMOTE_BACKUP_CONSTRAINTS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY)
            .build()

    override fun onPendingGeneration() {
        queue.enqueueUniqueOneTime(
            ONCE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            buildOneTimeRequest(),
        )
    }

    override fun ensurePeriodic() {
        queue.enqueueUniquePeriodic(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            buildPeriodicRequest(),
        )
    }

    override fun cancelAll() {
        queue.cancelUniqueWork(ONCE_WORK_NAME)
        queue.cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    companion object {
        const val ONCE_WORK_NAME = "open-tasks-remote-backup-once-v1"
        const val PERIODIC_WORK_NAME = "open-tasks-remote-backup-periodic-v1"

        private val DEBOUNCE_DELAY: Duration = Duration.ofMinutes(15)
        private val PERIODIC_INTERVAL: Duration = Duration.ofHours(24)
        private val PERIODIC_FLEX: Duration = Duration.ofHours(6)
        private val BACKOFF_DELAY: Duration = Duration.ofSeconds(30)

        // Version one backs up over any connection: an unmetered requirement
        // would silently stop backing up the devices that never see Wi-Fi.
        private val REMOTE_BACKUP_CONSTRAINTS: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
    }
}

/**
 * Resolves [WorkManager] lazily so constructing a scheduler never forces
 * WorkManager initialization on a device that has no remote backup at all.
 */
private class ApplicationUniqueWorkQueue(context: Context) : UniqueWorkQueue {
    private val context = context.applicationContext

    override fun enqueueUniqueOneTime(
        uniqueWorkName: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueWorkName, policy, request)
    }

    override fun enqueueUniquePeriodic(
        uniqueWorkName: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    ) {
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(uniqueWorkName, policy, request)
    }

    override fun cancelUniqueWork(uniqueWorkName: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName)
    }
}
