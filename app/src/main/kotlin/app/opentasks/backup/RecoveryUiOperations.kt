package app.opentasks.backup

import android.content.Intent
import app.opentasks.backup.drive.AuthorizedDriveSession
import app.opentasks.backup.drive.DriveAuthorizationMode
import app.opentasks.backup.drive.DriveAuthorizationResult
import app.opentasks.backup.drive.GoogleDriveAuthorizationManager
import app.opentasks.core.data.backup.CreateOnlyDriveObjectStore
import app.opentasks.core.data.backup.RemoteBackupTransferStore
import app.opentasks.core.domain.RecoveryCandidate
import app.opentasks.core.domain.RecoveryCoordinator
import app.opentasks.core.domain.RecoveryResult
import app.opentasks.core.domain.RemoteBackupObject
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.RecoveryFailureCategory
import app.opentasks.core.model.RemoteLogicalObjectId
import javax.inject.Inject

internal class RecoveryUiOperations @Inject constructor(
    private val coordinator: RecoveryCoordinator,
    private val authorizationManager: GoogleDriveAuthorizationManager,
    private val files: AndroidBackupFiles,
) : AutoCloseable {
    private val transfers = EphemeralRecoveryTransferStore()
    private var session: AuthorizedDriveSession? = null
    private var candidates = emptyMap<String, RecoveryCandidate>()

    suspend fun discoverDrive(resolution: Intent?): RecoveryDiscoveryResult {
        val authorization = if (resolution == null) {
            authorizationManager.authorize(DriveAuthorizationMode.EXPLICIT_ACCOUNT, null)
        } else {
            authorizationManager.acceptResolution(resolution, null)
        }
        return when (authorization) {
            is DriveAuthorizationResult.Authorized -> {
                session?.close()
                session = authorization.session
                discover(store(authorization.session), portable = false)
            }
            is DriveAuthorizationResult.ResolutionRequired ->
                RecoveryDiscoveryResult.ResolutionRequired(authorization.pendingIntent)
            DriveAuthorizationResult.AccountMismatch ->
                RecoveryDiscoveryResult.Failed(RecoveryFailureCategory.ACCOUNT_MISMATCH)
            is DriveAuthorizationResult.Unavailable ->
                RecoveryDiscoveryResult.Failed(RecoveryFailureCategory.AUTHORIZATION_REQUIRED)
        }
    }

    suspend fun discoverPortable(): List<RecoveryCandidate> {
        val portable = when {
            files.recoveryInbox.isFile -> files.recoveryInbox
            files.eligiblePackage.isFile -> files.eligiblePackage
            else -> null
        }
        val discovered = coordinator.discover(null, portable)
        remember(discovered)
        return discovered
    }

    suspend fun prepare(handle: String, passphrase: CharArray): RecoveryResult {
        val candidate = candidates[handle]
            ?: return RecoveryResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        val activeSession = session
        val digest = activeSession?.copyAccountBindingDigest()
        return try {
            coordinator.prepare(
                candidate = candidate,
                passphrase = passphrase,
                objectStore = activeSession?.let(::store),
                accountBindingDigest = digest,
            )
        } finally {
            digest?.fill(0)
        }
    }

    suspend fun confirm(operationId: String): RecoveryResult {
        val activeSession = session
            ?: return RecoveryResult.Failed(RecoveryFailureCategory.AUTHORIZATION_REQUIRED)
        return coordinator.confirmTakeover(operationId, store(activeSession))
    }

    override fun close() {
        session?.close()
        session = null
        candidates = emptyMap()
    }

    private suspend fun discover(
        objectStore: app.opentasks.core.domain.CreateOnlyBackupObjectStore?,
        portable: Boolean,
    ): RecoveryDiscoveryResult {
        val discovered = coordinator.discover(
            objectStore,
            if (portable) files.recoveryInbox else null,
        )
        remember(discovered)
        return RecoveryDiscoveryResult.Candidates(discovered)
    }

    private fun remember(values: List<RecoveryCandidate>) {
        candidates = values.associateBy(RecoveryCandidate::handle)
    }

    private fun store(value: AuthorizedDriveSession) = CreateOnlyDriveObjectStore(
        transport = value.transport,
        transferStore = transfers,
        stagingRoot = files.recoveryRoot,
    )
}

private class EphemeralRecoveryTransferStore : RemoteBackupTransferStore {
    private val values = linkedMapOf<Pair<String, String>, RemoteBackupObject>()

    override suspend fun objectState(
        lineageId: CloudLineageId,
        logicalObjectId: RemoteLogicalObjectId,
    ): RemoteBackupObject? = values[lineageId.value to logicalObjectId.value]

    override suspend fun insertObject(value: RemoteBackupObject) {
        values[value.lineageId.value to value.logicalObjectId.value] = value
    }

    override suspend fun compareAndSetObject(
        expected: RemoteBackupObject,
        next: RemoteBackupObject,
    ): Boolean {
        val key = expected.lineageId.value to expected.logicalObjectId.value
        if (values[key] != expected) return false
        values[key] = next
        return true
    }

    override suspend fun objectsForLineage(lineageId: CloudLineageId): List<RemoteBackupObject> =
        values.values.filter { it.lineageId == lineageId }

    override suspend fun removeObjectState(
        lineageId: CloudLineageId,
        logicalObjectId: RemoteLogicalObjectId,
    ): Boolean = values.remove(lineageId.value to logicalObjectId.value) != null
}
