package app.opentasks

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import app.opentasks.backup.RemoteBackupWorker
import app.opentasks.backup.UniqueWorkQueue
import app.opentasks.backup.WorkManagerRemoteBackupScheduler
import app.opentasks.backup.drive.DefaultGoogleDriveAuthorizationManager
import app.opentasks.backup.drive.DriveAccountBinding
import app.opentasks.backup.drive.DriveAccountBindingKeyBoundary
import app.opentasks.backup.drive.DriveAuthorizationMode
import app.opentasks.backup.drive.DriveAuthorizationOutcome
import app.opentasks.backup.drive.DriveAuthorizationRequestSpec
import app.opentasks.backup.drive.DriveIdentityBoundary
import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import java.util.Locale
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBackupBoundaryInstrumentedTest {
    @Test
    fun productionAuthorizationRequestsOnlyDriveAppDataWithoutServerCredentials() = runBlocking {
        withTimeout(5_000) {
            val identity = RecordingIdentityBoundary()
            val manager = DefaultGoogleDriveAuthorizationManager(
                identity = identity,
                accountBinding = DriveAccountBinding(
                    DriveAccountBindingKeyBoundary {
                        SecretKeySpec(ByteArray(32) { 0x41 }, "HmacSHA256")
                    },
                ),
                transportFactory = { error("An unavailable result must not create a transport") },
            )

            manager.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, null)

            assertEquals(
                listOf(DefaultGoogleDriveAuthorizationManager.DRIVE_APPDATA_SCOPE),
                identity.requests.single().scopes,
            )
            assertTrue(identity.requests.single().promptSelectAccount)
            val context = ApplicationProvider.getApplicationContext<OpenTasksApplication>()
            listOf("default_web_client_id", "client_secret", "server_client_id").forEach { name ->
                assertEquals(0, context.resources.getIdentifier(name, "string", context.packageName))
            }
        }
    }

    @Test
    fun packagedRemoteAuthorityExposesCreateOnlyTransportAndAnInternalDebugGate() {
        val forbidden = listOf("update", "patch", "replace", "compareandswap")
        val methods = CreateOnlyDriveTransport::class.java.methods
            .map { it.name.lowercase(Locale.ROOT) }
        forbidden.forEach { fragment ->
            assertFalse(methods.any { fragment in it })
        }

        val context = ApplicationProvider.getApplicationContext<OpenTasksApplication>()
        val component = ComponentName(
            context,
            "app.opentasks.backup.drive.DriveCreateOnlyQualificationActivity",
        )
        val activity = context.packageManager.getActivityInfo(component, 0)
        assertFalse(activity.exported)
    }

    @Test
    fun scheduledRemoteBackupUsesConstantNamesAndNoInputData() {
        val queue = RecordingUniqueWorkQueue()
        val scheduler = WorkManagerRemoteBackupScheduler(queue)

        scheduler.onPendingGeneration()
        scheduler.ensurePeriodic()

        assertEquals(
            listOf(
                "open-tasks-remote-backup-once-v1",
                "open-tasks-remote-backup-periodic-v1",
            ),
            queue.names,
        )
        assertEquals(listOf(0, 0), queue.inputSizes)
        assertEquals(
            listOf(RemoteBackupWorker::class.java.name, RemoteBackupWorker::class.java.name),
            queue.workerClassNames,
        )
        queue.names.forEach { name ->
            assertFalse(name.contains("vault", ignoreCase = true))
            assertFalse(name.contains("lineage", ignoreCase = true))
            assertFalse(name.contains("account", ignoreCase = true))
            assertFalse(name.contains("provider", ignoreCase = true))
        }
    }

    private class RecordingIdentityBoundary : DriveIdentityBoundary {
        val requests = mutableListOf<DriveAuthorizationRequestSpec>()

        override suspend fun authorize(
            spec: DriveAuthorizationRequestSpec,
        ): DriveAuthorizationOutcome {
            requests += spec
            return DriveAuthorizationOutcome(
                accessToken = null,
                account = null,
                hasResolution = false,
                pendingIntent = null,
            )
        }

        override fun resultFromIntent(data: android.content.Intent): DriveAuthorizationOutcome =
            error("Unused")

        override suspend fun clearToken(accessToken: String) = Unit

        override suspend fun revokeAccess(account: android.accounts.Account, scope: String) = Unit
    }

    private class RecordingUniqueWorkQueue : UniqueWorkQueue {
        val names = mutableListOf<String>()
        val inputSizes = mutableListOf<Int>()
        val workerClassNames = mutableListOf<String>()

        override fun enqueueUniqueOneTime(
            uniqueWorkName: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            assertEquals(ExistingWorkPolicy.REPLACE, policy)
            names += uniqueWorkName
            inputSizes += request.workSpec.input.size()
            workerClassNames += request.workSpec.workerClassName
        }

        override fun enqueueUniquePeriodic(
            uniqueWorkName: String,
            policy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest,
        ) {
            assertEquals(ExistingPeriodicWorkPolicy.KEEP, policy)
            names += uniqueWorkName
            inputSizes += request.workSpec.input.size()
            workerClassNames += request.workSpec.workerClassName
        }

        override fun cancelUniqueWork(uniqueWorkName: String) = Unit
    }
}
