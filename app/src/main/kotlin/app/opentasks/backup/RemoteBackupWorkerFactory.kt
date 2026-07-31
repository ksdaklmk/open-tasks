package app.opentasks.backup

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import app.opentasks.core.domain.RemoteBackupRunner

/**
 * Constructs [RemoteBackupWorker] with the runner of the vault that is active
 * when the work actually starts.
 *
 * The runner is resolved per creation rather than captured, because a vault
 * slot may have been replaced — or closed altogether — since the work was
 * scheduled, and a worker must never hold a service belonging to a database
 * that is no longer open. Any other worker class is left to WorkManager's
 * default reflective factory.
 */
class RemoteBackupWorkerFactory(
    private val runner: () -> RemoteBackupRunner?,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == REMOTE_BACKUP_WORKER_CLASS_NAME) {
        RemoteBackupWorker(appContext, workerParameters, runner)
    } else {
        null
    }

    private companion object {
        val REMOTE_BACKUP_WORKER_CLASS_NAME: String = RemoteBackupWorker::class.java.name
    }
}
