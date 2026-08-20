package app.opentasks.backup.drive

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.data.backup.drive.DriveTransportException
import app.opentasks.core.data.backup.drive.DriveTransportFailureCategory
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Whether authorization may show account-selection UI or must stay silent. */
enum class DriveAuthorizationMode {
    EXPLICIT_ACCOUNT,
    NON_INTERACTIVE,
}

/**
 * Why authorization could not produce a session, without leaking why in detail.
 *
 * Deliberately bounded, and deliberately *not* collapsed to "refused": silent
 * authorization performs a live `about.get` probe, so a provider storage
 * failure or a mangled response surfaces here too. Folding those into
 * [REJECTED] would tell a person to reconnect an account that is perfectly
 * fine, so each keeps the category that describes it. No provider message,
 * status code, or identifier is carried — only which of these five a caller
 * may act on.
 */
enum class DriveAuthorizationUnavailableReason {
    AUTHORIZATION_REQUIRED,
    RETRYABLE,

    /** The grant itself was refused; only a person can resolve it. */
    REJECTED,
    PROVIDER_STORAGE,
    CORRUPT_OR_INCOMPATIBLE,
}

sealed interface DriveAuthorizationResult {
    data class Authorized(
        val session: AuthorizedDriveSession,
    ) : DriveAuthorizationResult

    data class ResolutionRequired(
        val pendingIntent: PendingIntent,
    ) : DriveAuthorizationResult

    data object AccountMismatch : DriveAuthorizationResult

    data class Unavailable(
        val reason: DriveAuthorizationUnavailableReason,
    ) : DriveAuthorizationResult
}

/**
 * A live, single-use Drive authorization grant.
 *
 * The session privately owns the access token and Google account references
 * needed for [GoogleDriveAuthorizationManager.clearToken] and
 * [GoogleDriveAuthorizationManager.revokeAccess]; both are dropped on
 * [close], alongside the wrapped [transport]. [toString] never reveals the
 * token, account, or digest.
 *
 * [account] may be absent: the account handle is only ever needed by
 * [GoogleDriveAuthorizationManager.revokeAccess], never by authorization
 * itself, so a missing handle must not fail an otherwise valid token grant.
 */
class AuthorizedDriveSession internal constructor(
    val transport: CreateOnlyDriveTransport,
    accountBindingDigest: ByteArray,
    accessToken: String,
    account: Account?,
) : AutoCloseable {
    private val ownedAccountBindingDigest: ByteArray = accountBindingDigest.copyOf()
    private var ownedAccessToken: String? = accessToken
    private var ownedAccount: Account? = account
    private var closed = false

    fun copyAccountBindingDigest(): ByteArray {
        check(!closed) { "The Drive authorization session is closed" }
        return ownedAccountBindingDigest.copyOf()
    }

    internal fun accessTokenOrNull(): String? = ownedAccessToken

    internal fun accountOrNull(): Account? = ownedAccount

    override fun close() {
        if (closed) return
        closed = true
        ownedAccountBindingDigest.fill(0)
        ownedAccessToken = null
        ownedAccount = null
        transport.close()
    }

    override fun toString(): String = "AuthorizedDriveSession()"
}

interface GoogleDriveAuthorizationManager {
    suspend fun authorize(
        mode: DriveAuthorizationMode,
        expectedAccountDigest: ByteArray?,
    ): DriveAuthorizationResult

    suspend fun acceptResolution(
        data: Intent,
        expectedAccountDigest: ByteArray?,
    ): DriveAuthorizationResult

    suspend fun clearToken(session: AuthorizedDriveSession)

    suspend fun revokeAccess(session: AuthorizedDriveSession)
}

/** A scope request plus whether the account picker may be shown. */
internal data class DriveAuthorizationRequestSpec(
    val scopes: List<String>,
    val promptSelectAccount: Boolean,
)

/** The bounded shape this file needs out of a Google authorization result. */
internal class DriveAuthorizationOutcome(
    val accessToken: String?,
    val account: Account?,
    val hasResolution: Boolean,
    val pendingIntent: PendingIntent?,
)

/**
 * Isolates every direct call into Google Identity Services so the manager
 * logic below is testable without Play Services or an Android device.
 */
internal interface DriveIdentityBoundary {
    suspend fun authorize(spec: DriveAuthorizationRequestSpec): DriveAuthorizationOutcome

    fun resultFromIntent(data: Intent): DriveAuthorizationOutcome

    suspend fun clearToken(accessToken: String)

    suspend fun revokeAccess(account: Account, scope: String)
}

internal class PlayServicesDriveIdentityBoundary(context: Context) : DriveIdentityBoundary {
    private val client: AuthorizationClient =
        Identity.getAuthorizationClient(context.applicationContext)

    override suspend fun authorize(spec: DriveAuthorizationRequestSpec): DriveAuthorizationOutcome {
        val builder = AuthorizationRequest.Builder()
            .setRequestedScopes(spec.scopes.map(::Scope))
        if (spec.promptSelectAccount) {
            builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        }
        return client.authorize(builder.build()).awaitDriveTask().toOutcome()
    }

    override fun resultFromIntent(data: Intent): DriveAuthorizationOutcome =
        client.getAuthorizationResultFromIntent(data).toOutcome()

    override suspend fun clearToken(accessToken: String) {
        client.clearToken(
            ClearTokenRequest.builder().setToken(accessToken).build(),
        ).awaitDriveTask()
    }

    override suspend fun revokeAccess(account: Account, scope: String) {
        client.revokeAccess(
            RevokeAccessRequest.builder()
                .setAccount(account)
                .setScopes(listOf(Scope(scope)))
                .build(),
        ).awaitDriveTask()
    }

    private fun AuthorizationResult.toOutcome(): DriveAuthorizationOutcome = DriveAuthorizationOutcome(
        accessToken = accessToken,
        account = toGoogleSignInAccount()?.account,
        hasResolution = hasResolution(),
        pendingIntent = pendingIntent,
    )

    private suspend fun <T> Task<T>.awaitDriveTask(): T =
        suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { completed ->
                val failure = completed.exception
                when {
                    completed.isCanceled -> continuation.cancel()
                    failure != null -> continuation.resumeWithException(failure)
                    else -> continuation.resume(completed.result)
                }
            }
        }
}

/**
 * Production [GoogleDriveAuthorizationManager]: explicit or silent
 * authorization, `about.get` account binding, and token/account lifecycle.
 *
 * No OAuth token, account, or profile field is ever persisted or logged by
 * this class; only [DriveAccountBinding.digest] output leaves an
 * authorization call, inside an [AuthorizedDriveSession].
 */
class DefaultGoogleDriveAuthorizationManager internal constructor(
    private val identity: DriveIdentityBoundary,
    private val accountBinding: DriveAccountBinding,
    private val transportFactory: (String) -> CreateOnlyDriveTransport,
) : GoogleDriveAuthorizationManager {
    constructor(context: Context) : this(
        identity = PlayServicesDriveIdentityBoundary(context),
        accountBinding = DriveAccountBinding(),
        transportFactory = { accessToken -> HttpCreateOnlyDriveTransport(accessToken) },
    )

    override suspend fun authorize(
        mode: DriveAuthorizationMode,
        expectedAccountDigest: ByteArray?,
    ): DriveAuthorizationResult {
        val outcome = try {
            identity.authorize(
                DriveAuthorizationRequestSpec(
                    scopes = listOf(DRIVE_APPDATA_SCOPE),
                    promptSelectAccount = mode == DriveAuthorizationMode.EXPLICIT_ACCOUNT,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return unavailable(DriveAuthorizationUnavailableReason.REJECTED)
        }
        return resolveOutcome(mode, outcome, expectedAccountDigest)
    }

    override suspend fun acceptResolution(
        data: Intent,
        expectedAccountDigest: ByteArray?,
    ): DriveAuthorizationResult {
        val outcome = try {
            identity.resultFromIntent(data)
        } catch (_: Exception) {
            return DriveAuthorizationResult.Unavailable(DriveAuthorizationUnavailableReason.REJECTED)
        }
        return resolveOutcome(DriveAuthorizationMode.EXPLICIT_ACCOUNT, outcome, expectedAccountDigest)
    }

    override suspend fun clearToken(session: AuthorizedDriveSession) {
        val token = session.accessTokenOrNull()
        try {
            token?.let { identity.clearToken(it) }
        } finally {
            session.close()
        }
    }

    override suspend fun revokeAccess(session: AuthorizedDriveSession) {
        val account = session.accountOrNull()
        val token = session.accessTokenOrNull()
        var firstFailure: Throwable? = null
        try {
            if (account != null) {
                try {
                    identity.revokeAccess(account, DRIVE_APPDATA_SCOPE)
                } catch (failure: Throwable) {
                    firstFailure = failure
                }
            }
            try {
                token?.let { identity.clearToken(it) }
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        } finally {
            session.close()
        }
        firstFailure?.let { throw it }
    }

    private suspend fun resolveOutcome(
        mode: DriveAuthorizationMode,
        outcome: DriveAuthorizationOutcome,
        expectedAccountDigest: ByteArray?,
    ): DriveAuthorizationResult {
        if (outcome.hasResolution) {
            val pendingIntent = outcome.pendingIntent
            return when {
                mode == DriveAuthorizationMode.NON_INTERACTIVE ->
                    unavailable(DriveAuthorizationUnavailableReason.AUTHORIZATION_REQUIRED)
                pendingIntent != null -> DriveAuthorizationResult.ResolutionRequired(pendingIntent)
                else -> unavailable(DriveAuthorizationUnavailableReason.REJECTED)
            }
        }
        val accessToken = outcome.accessToken
        val account = outcome.account
        if (accessToken.isNullOrEmpty()) {
            return unavailable(DriveAuthorizationUnavailableReason.REJECTED)
        }
        val transport = transportFactory(accessToken)
        val permissionId = try {
            transport.readCurrentUserPermissionId()
        } catch (exception: DriveTransportException) {
            if (exception.category == DriveTransportFailureCategory.AUTHORIZATION) {
                identity.clearToken(accessToken)
            }
            transport.close()
            return unavailable(exception.category.toUnavailableReason())
        }
        val digest = accountBinding.digest(permissionId)
        try {
            if (expectedAccountDigest != null && !MessageDigest.isEqual(digest, expectedAccountDigest)) {
                transport.close()
                return DriveAuthorizationResult.AccountMismatch
            }
            return DriveAuthorizationResult.Authorized(
                AuthorizedDriveSession(
                    transport = transport,
                    accountBindingDigest = digest,
                    accessToken = accessToken,
                    account = account,
                ),
            )
        } finally {
            digest.fill(0)
        }
    }

    private fun unavailable(
        reason: DriveAuthorizationUnavailableReason,
    ): DriveAuthorizationResult.Unavailable = DriveAuthorizationResult.Unavailable(reason)

    /**
     * Maps a failure of the `about.get` account-binding probe.
     *
     * Only a genuinely refused grant becomes [DriveAuthorizationUnavailableReason.REJECTED].
     * Storage and malformed-response failures say nothing about the grant, so
     * they keep their own categories rather than being reported as an account
     * problem.
     */
    private fun DriveTransportFailureCategory.toUnavailableReason(): DriveAuthorizationUnavailableReason =
        when (this) {
            DriveTransportFailureCategory.AUTHORIZATION ->
                DriveAuthorizationUnavailableReason.AUTHORIZATION_REQUIRED
            DriveTransportFailureCategory.RETRYABLE -> DriveAuthorizationUnavailableReason.RETRYABLE
            DriveTransportFailureCategory.STORAGE_QUOTA ->
                DriveAuthorizationUnavailableReason.PROVIDER_STORAGE
            DriveTransportFailureCategory.MISSING,
            DriveTransportFailureCategory.CORRUPT_RESPONSE,
            -> DriveAuthorizationUnavailableReason.CORRUPT_OR_INCOMPATIBLE
            DriveTransportFailureCategory.PROVIDER_REJECTED ->
                DriveAuthorizationUnavailableReason.REJECTED
        }

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}
