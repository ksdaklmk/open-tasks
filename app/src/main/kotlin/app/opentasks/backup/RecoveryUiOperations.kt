package app.opentasks.backup

import android.content.Context
import android.content.Intent
import app.opentasks.ActiveVaultServices
import app.opentasks.backup.drive.AuthorizedDriveSession
import app.opentasks.backup.drive.DriveAuthorizationMode
import app.opentasks.backup.drive.DriveAuthorizationResult
import app.opentasks.backup.drive.GoogleDriveAuthorizationManager
import app.opentasks.core.data.backup.CreateOnlyDriveObjectStore
import app.opentasks.core.data.backup.RemoteBackupTransferStore
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.LocalVaultRepositoryFactory
import app.opentasks.core.data.VaultRuntimeManager
import app.opentasks.core.domain.RecoveryCandidate
import app.opentasks.core.domain.RecoveryCoordinator
import app.opentasks.core.domain.RecoveryResult
import app.opentasks.core.domain.RemoteBackupObject
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.RecoveryFailureCategory
import app.opentasks.core.model.RemoteLogicalObjectId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

internal class RecoveryUiOperations internal constructor(
    private val coordinatorFactory: (ByteArray?) -> RecoveryCoordinator,
    private val authorizationManager: GoogleDriveAuthorizationManager,
    private val recoveryInbox: File,
    private val eligiblePackage: File,
    private val recoveryRoot: File,
    private val knownAccountBindingDigest: suspend () -> ByteArray?,
) : AutoCloseable {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        crypto: VaultCrypto,
        runtimeManager: VaultRuntimeManager,
        codec: AuthenticatedCloudObjectCodec,
        files: AndroidBackupFiles,
        activeVaultServices: ActiveVaultServices,
        authorizationManager: GoogleDriveAuthorizationManager,
    ) : this(
        coordinatorFactory = { expectedDigest ->
            LocalVaultRepositoryFactory.createRecoveryCoordinator(
                context = context,
                crypto = crypto,
                runtimeManager = runtimeManager,
                authenticatedCodec = codec,
                recoveryStagingRoot = files.recoveryRoot,
                expectedAccountBindingDigest = expectedDigest,
            )
        },
        authorizationManager = authorizationManager,
        recoveryInbox = files.recoveryInbox,
        eligiblePackage = files.eligiblePackage,
        recoveryRoot = files.recoveryRoot,
        knownAccountBindingDigest = {
            activeVaultServices.sessionOrNull()?.recoveryAccountBindingDigest()
        },
    )

    private val transfers = EphemeralRecoveryTransferStore()
    private var coordinator: RecoveryCoordinator? = null
    private var accountBindingDigest: ByteArray? = null
    private var candidates = emptyMap<String, RecoveryCandidate>()

    suspend fun discoverDrive(resolution: Intent?): RecoveryDiscoveryResult {
        resetRecovery()
        val knownDigest = knownAccountBindingDigest()
        try {
            val authorization = if (resolution == null) {
                authorizationManager.authorize(
                    DriveAuthorizationMode.EXPLICIT_ACCOUNT,
                    knownDigest,
                )
            } else {
                authorizationManager.acceptResolution(resolution, knownDigest)
            }
            return when (authorization) {
                is DriveAuthorizationResult.Authorized -> authorization.session.use { session ->
                    val binding = knownDigest ?: session.copyAccountBindingDigest()
                    accountBindingDigest = binding.copyOf()
                    coordinator = coordinatorFactory(accountBindingDigest)
                    discover(store(session))
                }
                is DriveAuthorizationResult.ResolutionRequired ->
                    RecoveryDiscoveryResult.ResolutionRequired(authorization.pendingIntent)
                DriveAuthorizationResult.AccountMismatch ->
                    RecoveryDiscoveryResult.Failed(RecoveryFailureCategory.ACCOUNT_MISMATCH)
                is DriveAuthorizationResult.Unavailable ->
                    RecoveryDiscoveryResult.Failed(RecoveryFailureCategory.AUTHORIZATION_REQUIRED)
            }
        } finally {
            knownDigest?.fill(0)
        }
    }

    suspend fun discoverPortable(): List<RecoveryCandidate> {
        val portable = when {
            recoveryInbox.isFile -> recoveryInbox
            eligiblePackage.isFile -> eligiblePackage
            else -> null
        }
        val activeCoordinator = coordinator ?: coordinatorFactory(null).also { coordinator = it }
        val discovered = activeCoordinator.discover(null, portable)
        remember(discovered)
        return discovered
    }

    suspend fun prepare(handle: String, passphrase: CharArray): RecoveryResult {
        val candidate = candidates[handle]
            ?: return RecoveryResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        val activeCoordinator = coordinator
            ?: return RecoveryResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        if (candidate.source != app.opentasks.core.domain.RecoverySource.GOOGLE_DRIVE) {
            return activeCoordinator.prepare(candidate, passphrase, null, null)
        }
        val authorization = authorizationManager.authorize(
            DriveAuthorizationMode.NON_INTERACTIVE,
            accountBindingDigest,
        )
        return when (authorization) {
            is DriveAuthorizationResult.Authorized -> authorization.session.use { session ->
                val digest = session.copyAccountBindingDigest()
                try {
                    activeCoordinator.prepare(candidate, passphrase, store(session), digest)
                } finally {
                    digest.fill(0)
                }
            }
            is DriveAuthorizationResult.ResolutionRequired,
            is DriveAuthorizationResult.Unavailable,
            -> RecoveryResult.Failed(RecoveryFailureCategory.AUTHORIZATION_REQUIRED)
            DriveAuthorizationResult.AccountMismatch ->
                RecoveryResult.Failed(RecoveryFailureCategory.ACCOUNT_MISMATCH)
        }
    }

    suspend fun confirm(operationId: String): RecoveryResult {
        val activeCoordinator = coordinator
            ?: return RecoveryResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        val authorization = authorizationManager.authorize(
            DriveAuthorizationMode.NON_INTERACTIVE,
            accountBindingDigest,
        )
        return when (authorization) {
            is DriveAuthorizationResult.Authorized -> authorization.session.use { session ->
                activeCoordinator.confirmTakeover(operationId, store(session))
            }
            is DriveAuthorizationResult.ResolutionRequired,
            is DriveAuthorizationResult.Unavailable,
            -> RecoveryResult.Failed(RecoveryFailureCategory.AUTHORIZATION_REQUIRED)
            DriveAuthorizationResult.AccountMismatch ->
                RecoveryResult.Failed(RecoveryFailureCategory.ACCOUNT_MISMATCH)
        }
    }

    override fun close() {
        resetRecovery()
    }

    private fun resetRecovery() {
        accountBindingDigest?.fill(0)
        accountBindingDigest = null
        coordinator = null
        candidates = emptyMap()
    }

    private suspend fun discover(
        objectStore: app.opentasks.core.domain.CreateOnlyBackupObjectStore?,
    ): RecoveryDiscoveryResult {
        val discovered = checkNotNull(coordinator).discover(
            objectStore,
            null,
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
        stagingRoot = recoveryRoot,
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
