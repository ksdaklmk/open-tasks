package app.opentasks.backup

import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkManagerRemoteBackupSchedulerTest {
    @Test
    fun requestsUseExactPolicyAndNoPrivateInput() {
        val scheduler = WorkManagerRemoteBackupScheduler(RecordingUniqueWorkQueue())

        val once = scheduler.buildOneTimeRequest().workSpec
        val periodic = scheduler.buildPeriodicRequest().workSpec

        assertEquals(Duration.ofMinutes(15), Duration.ofMillis(once.initialDelay))
        assertEquals(Duration.ofHours(24), Duration.ofMillis(periodic.intervalDuration))
        assertEquals(Duration.ofHours(6), Duration.ofMillis(periodic.flexDuration))
        assertEquals(0, once.input.size())
        assertEquals(0, periodic.input.size())
        assertEquals(NetworkType.CONNECTED, once.constraints.requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)
        assertTrue(once.constraints.requiresBatteryNotLow())
        assertTrue(once.constraints.requiresStorageNotLow())
        assertTrue(periodic.constraints.requiresBatteryNotLow())
        assertTrue(periodic.constraints.requiresStorageNotLow())
    }

    @Test
    fun requestsUseThirtySecondExponentialBackoffAndNoIdleOrChargingRequirement() {
        val scheduler = WorkManagerRemoteBackupScheduler(RecordingUniqueWorkQueue())

        listOf(scheduler.buildOneTimeRequest().workSpec, scheduler.buildPeriodicRequest().workSpec)
            .forEach { spec ->
                assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
                assertEquals(Duration.ofSeconds(30), Duration.ofMillis(spec.backoffDelayDuration))
                assertFalse(spec.constraints.requiresCharging())
                assertFalse(spec.constraints.requiresDeviceIdle())
                assertEquals(RemoteBackupWorker::class.java.name, spec.workerClassName)
            }
    }

    @Test
    fun uniqueNamesAndExistingWorkPoliciesStayConstantAcrossScheduleAndCancel() {
        val queue = RecordingUniqueWorkQueue()
        val scheduler = WorkManagerRemoteBackupScheduler(queue)

        scheduler.onPendingGeneration()
        scheduler.onPendingGeneration()
        scheduler.ensurePeriodic()
        scheduler.cancelAll()

        assertEquals(
            listOf(
                "once:open-tasks-remote-backup-once-v1:REPLACE",
                "once:open-tasks-remote-backup-once-v1:REPLACE",
                "periodic:open-tasks-remote-backup-periodic-v1:KEEP",
                "cancel:open-tasks-remote-backup-once-v1",
                "cancel:open-tasks-remote-backup-periodic-v1",
            ),
            queue.events,
        )
    }

    @Test
    fun enqueuedRequestsCarryNoWorkerInputAtAll() {
        val queue = RecordingUniqueWorkQueue()
        val scheduler = WorkManagerRemoteBackupScheduler(queue)

        scheduler.onPendingGeneration()
        scheduler.ensurePeriodic()

        assertEquals(listOf(0, 0), queue.enqueuedInputSizes)
    }

    private class RecordingUniqueWorkQueue : UniqueWorkQueue {
        val events = mutableListOf<String>()
        val enqueuedInputSizes = mutableListOf<Int>()

        override fun enqueueUniqueOneTime(
            uniqueWorkName: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            events += "once:$uniqueWorkName:${policy.name}"
            enqueuedInputSizes += request.workSpec.input.size()
        }

        override fun enqueueUniquePeriodic(
            uniqueWorkName: String,
            policy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest,
        ) {
            events += "periodic:$uniqueWorkName:${policy.name}"
            enqueuedInputSizes += request.workSpec.input.size()
        }

        override fun cancelUniqueWork(uniqueWorkName: String) {
            events += "cancel:$uniqueWorkName"
        }
    }
}
