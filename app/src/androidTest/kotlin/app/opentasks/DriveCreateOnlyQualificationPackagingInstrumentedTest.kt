package app.opentasks

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.opentasks.backup.drive.DriveCreateOnlyQualificationActivity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DriveCreateOnlyQualificationPackagingInstrumentedTest {
    @Test
    fun authorizationDiagnosticLabelsDeveloperConfigurationStatusWithoutExceptionText() {
        val diagnostic = DriveCreateOnlyQualificationActivity.authorizationDiagnostic(
            stage = "AUTH_START",
            throwable = ApiException(Status(CommonStatusCodes.DEVELOPER_ERROR)),
        )

        assertEquals("AUTH_START_ApiException_DEVELOPER_ERROR_10", diagnostic)
    }

    @Test
    fun debugPackageContainsOnlyInternalDriveCreateOnlyQualificationActivity() {
        val context = ApplicationProvider.getApplicationContext<OpenTasksApplication>()
        val component = ComponentName(
            context,
            "app.opentasks.backup.drive.DriveCreateOnlyQualificationActivity",
        )
        val activity = context.packageManager.getActivityInfo(component, 0)

        assertTrue(!activity.exported)
    }

    @Test
    fun installedPackageDeclaresInternetForDriveQualification() {
        val context = ApplicationProvider.getApplicationContext<OpenTasksApplication>()
        val packageInfo = context.packageManager.getPackageInfo(
            "app.opentasks",
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )

        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.INTERNET))
    }

    @Test
    fun explicitCredentialedArgumentLaunchesInternalQualificationAndRequiresBoundedPass() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString(QUALIFICATION_ARGUMENT) ==
                QUALIFICATION_ARGUMENT_VALUE,
        )
        val context = ApplicationProvider.getApplicationContext<OpenTasksApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            DriveCreateOnlyQualificationActivity.qualificationIntent(context)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as DriveCreateOnlyQualificationActivity
        try {
            var result: String? = null
            for (attempt in 0 until MAX_RESULT_POLLS) {
                instrumentation.runOnMainSync {
                    result = activity.qualificationResultForInstrumentation()
                }
                if (result != null) break
                Thread.sleep(RESULT_POLL_MILLIS)
            }
            assertEquals("PASS", result)
        } finally {
            instrumentation.runOnMainSync {
                if (!activity.isFinishing) activity.finish()
            }
        }
    }

    private companion object {
        const val QUALIFICATION_ARGUMENT = "driveQualification"
        const val QUALIFICATION_ARGUMENT_VALUE = "run"
        const val MAX_RESULT_POLLS = 4_800
        const val RESULT_POLL_MILLIS = 250L
    }
}
