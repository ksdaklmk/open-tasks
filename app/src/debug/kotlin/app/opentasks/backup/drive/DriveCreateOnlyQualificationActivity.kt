package app.opentasks.backup.drive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Debug-only, non-exported qualification entry point. Its UI reveals no provider data. */
class DriveCreateOnlyQualificationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var resultView: TextView
    private var qualificationResult: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resultView = TextView(this).also { setContentView(it) }
        if (intent.action != ACTION_RUN_QUALIFICATION) {
            render("UNAUTHORIZED_LAUNCH")
            return
        }
        requestAuthorization()
    }

    /** Exposes only the activity's bounded result to the explicit instrumentation gate. */
    fun qualificationResultForInstrumentation(): String? = qualificationResult

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AUTHORIZATION_REQUEST || resultCode != RESULT_OK || data == null) {
            render("AUTH_RESOLUTION_NOT_COMPLETED")
            return
        }
        try {
            consumeAuthorization(
                Identity.getAuthorizationClient(this)
                    .getAuthorizationResultFromIntent(data)
                    .accessToken,
            )
        } catch (exception: Exception) {
            render(authorizationDiagnostic("AUTH_RESOLUTION", exception))
        }
    }

    private fun requestAuthorization() {
        val request = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    try {
                        startIntentSenderForResult(
                            checkNotNull(result.pendingIntent).intentSender,
                            AUTHORIZATION_REQUEST,
                            null,
                            0,
                            0,
                            0,
                        )
                    } catch (exception: Exception) {
                        render(authorizationDiagnostic("AUTH_RESOLUTION_START", exception))
                    }
                } else {
                    consumeAuthorization(result.accessToken)
                }
            }
            .addOnFailureListener { exception ->
                render(authorizationDiagnostic("AUTH_START", exception))
            }
    }

    private fun consumeAuthorization(token: String?) {
        if (token.isNullOrEmpty()) {
            render("AUTH_TOKEN_MISSING")
            return
        }
        scope.launch {
            val results = DriveCreateOnlyQualification(
                transport = HttpCreateOnlyDriveTransport(token),
                directory = applicationContext.noBackupFilesDir,
            ).run()
            val failure = results.firstOrNull { !it.passed }
            render(failure?.property ?: "PASS")
        }
    }

    private fun render(value: String) {
        qualificationResult = value
        resultView.text = value
    }

    companion object {
        /** Produces only a bounded authorization stage, exception class, and public status code. */
        internal fun authorizationDiagnostic(stage: String, throwable: Throwable): String {
            val exceptionClass = throwable::class.java.simpleName
            val apiException = throwable as? ApiException
                ?: return "${stage}_${exceptionClass}".take(80)
            val statusCode = apiException.statusCode
            return (
                "${stage}_${exceptionClass}_" +
                    "${CommonStatusCodes.getStatusCodeString(statusCode)}_$statusCode"
                ).take(80)
        }

        fun qualificationIntent(context: Context): Intent =
            Intent(context, DriveCreateOnlyQualificationActivity::class.java)
                .setAction(ACTION_RUN_QUALIFICATION)

        const val AUTHORIZATION_REQUEST = 733
        private const val ACTION_RUN_QUALIFICATION =
            "app.opentasks.debug.RUN_DRIVE_CREATE_ONLY_QUALIFICATION"
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}
