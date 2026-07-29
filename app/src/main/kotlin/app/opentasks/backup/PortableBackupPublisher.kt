package app.opentasks.backup

import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.backup.BackupSnapshotCodec
import app.opentasks.core.data.backup.BackupStateEntity
import app.opentasks.core.data.backup.BackupStateStore
import app.opentasks.core.data.backup.PortablePackageCodec
import app.opentasks.core.data.backup.PortablePackageTooLargeException
import app.opentasks.core.data.backup.RecoveryEnvelopeCodec
import app.opentasks.core.data.backup.RecoveryEnvelopeStore
import app.opentasks.core.data.backup.StructuredBackupCapture
import app.opentasks.core.data.backup.VerifiedPortableBackup
import app.opentasks.core.domain.BackupCaptureSource
import app.opentasks.core.domain.BackupPolicy
import app.opentasks.core.model.AndroidBackupStatus
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.BackupPackageInfo
import app.opentasks.core.model.BackupUnavailableReason
import app.opentasks.core.model.VaultId
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PortableBackupPublisher(
    private val vaultId: VaultId,
    private val captureSource: BackupCaptureSource<StructuredBackupCapture>,
    private val stateStore: BackupStateStore,
    private val envelopeStore: RecoveryEnvelopeStore,
    private val contentKeyStore: VaultContentKeyStore,
    private val packageFile: AtomicPackageFile,
    private val codec: PortablePackageCodec,
    private val prepareEnvelope: (CharArray) -> PreparedRecoveryEnvelope,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()

    suspend fun prepare(passphrase: CharArray): AndroidBackupStatus = mutex.withLock {
        val initialState = checkNotNull(stateStore.get(vaultId)) {
            "Backup state is unavailable"
        }
        val existingEnvelope = envelopeStore.get(vaultId)
        if (existingEnvelope != null || initialState.recoveryEnvelopeReady) {
            existingEnvelope?.clear()
            return@withLock AndroidBackupStatus.Unavailable(
                BackupUnavailableReason.RECOVERY_ENVELOPE_UNAVAILABLE,
            )
        }
        val prepared = try {
            prepareEnvelope(passphrase)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return@withLock recordFailure(
                initialState,
                BackupUnavailableReason.RECOVERY_ENVELOPE_UNAVAILABLE,
                withdraw = false,
            )
        }
        try {
            val published = produce(prepared.envelope)
            var databaseCommitComplete = false
            try {
                val latest = checkNotNull(stateStore.get(vaultId)) {
                    "Backup state is unavailable"
                }
                val readyState = packageState(latest, published).copy(
                    recoveryEnvelopeReady = true,
                )
                val committed = envelopeStore.commitInitial(
                    vaultId = vaultId,
                    envelope = prepared.envelope,
                    state = readyState,
                    expectedCurrentGeneration = latest.currentGeneration,
                )
                if (!committed) {
                    return@withLock AndroidBackupStatus.Unavailable(
                        BackupUnavailableReason.FILE_IO,
                    )
                }
                databaseCommitComplete = true
                status(readyState)
            } catch (failure: Throwable) {
                if (failure is CancellationException) {
                    databaseCommitComplete = initialCommitMatches(published)
                    throw failure
                }
                AndroidBackupStatus.Unavailable(BackupUnavailableReason.FILE_IO)
            } finally {
                if (!databaseCommitComplete) packageFile.delete()
            }
        } catch (failure: PublicationFailure) {
            recordFailure(initialState, failure.reason, failure.withdraw)
        } finally {
            prepared.close()
        }
    }

    private suspend fun initialCommitMatches(
        published: VerifiedPortableBackup,
    ): Boolean = withContext(NonCancellable) {
        val state = try {
            stateStore.get(vaultId)
        } catch (_: Throwable) {
            null
        } ?: return@withContext false
        val envelope = try {
            envelopeStore.get(vaultId)
        } catch (_: Throwable) {
            null
        } ?: return@withContext false
        val digestMatches = try {
            envelopeDigest(envelope) == published.recoveryEnvelopeSha256
        } finally {
            envelope.clear()
        }
        digestMatches &&
            state.recoveryEnvelopeReady &&
            state.portablePackageGeneration == published.generation &&
            state.portablePackageBytes == published.totalPackageLength &&
            state.portablePackageProducedAtEpochMillis == published.producedAtEpochMillis &&
            state.packageState == if (published.generation == state.currentGeneration) {
                PACKAGE_READY
            } else {
                PACKAGE_UPDATE_PENDING
            }
    }

    suspend fun refresh(): AndroidBackupStatus = mutex.withLock {
        val envelope = envelopeStore.get(vaultId)
            ?: return@withLock recordFailure(
                checkNotNull(stateStore.get(vaultId)) { "Backup state is unavailable" },
                BackupUnavailableReason.RECOVERY_ENVELOPE_UNAVAILABLE,
                withdraw = false,
            )
        try {
            val initialState = checkNotNull(stateStore.get(vaultId)) {
                "Backup state is unavailable"
            }
            val shouldReconcile = initialState.packageState == PACKAGE_PREPARING ||
                (
                    initialState.packageState == PACKAGE_READY &&
                        initialState.portablePackageGeneration == initialState.currentGeneration
                    )
            val reconciled = if (shouldReconcile) reconcile(initialState, envelope) else null
            if (
                reconciled != null &&
                reconciled.portablePackageGeneration == reconciled.currentGeneration
            ) {
                return@withLock status(reconciled)
            }
            val checkpoint = reconciled ?: initialState
            val preparing = checkpoint.copy(
                packageState = PACKAGE_PREPARING,
                failureCategory = null,
            )
            check(
                stateStore.compareAndUpdate(
                    preparing,
                    checkpoint.currentGeneration,
                ) == 1,
            ) {
                "Backup state changed before portable package capture"
            }
            try {
                val published = produce(envelope)
                val latest = checkNotNull(stateStore.get(vaultId)) {
                    "Backup state is unavailable"
                }
                val updated = packageState(latest, published).copy(
                    recoveryEnvelopeReady = true,
                )
                check(stateStore.compareAndUpdate(updated, latest.currentGeneration) == 1) {
                    "Backup state changed during portable package checkpoint"
                }
                status(updated)
            } catch (failure: PublicationFailure) {
                val latest = checkNotNull(stateStore.get(vaultId)) {
                    "Backup state is unavailable"
                }
                recordFailure(latest, failure.reason, failure.withdraw)
            }
        } finally {
            envelope.clear()
        }
    }

    private suspend fun reconcile(
        state: BackupStateEntity,
        envelope: VaultKeyEnvelope,
    ): BackupStateEntity? {
        if (
            packageFile.length() <= 0 ||
            (
                state.packageState != PACKAGE_PREPARING &&
                    state.portablePackageGeneration == null
                )
        ) {
            return null
        }
        val key = try {
            contentKeyStore.openExisting(vaultId)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return null
        }
        val verified = try {
            packageFile.openRead().use { source ->
                codec.verifyComplete(source, packageFile.length(), key)
            }
        } catch (_: IOException) {
            return null
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            packageFile.delete()
            return null
        } finally {
            key.close()
        }
        val envelopeDigest = envelopeDigest(envelope)
        if (
            verified.vaultId != vaultId.value ||
            (
                state.packageState != PACKAGE_PREPARING &&
                    verified.generation != state.portablePackageGeneration
                ) ||
            verified.generation > state.currentGeneration ||
            verified.recoveryEnvelopeSha256 != envelopeDigest
        ) {
            packageFile.delete()
            return null
        }
        val reconciled = packageState(state, verified).copy(recoveryEnvelopeReady = true)
        check(stateStore.compareAndUpdate(reconciled, state.currentGeneration) == 1) {
            "Backup state changed during portable package reconciliation"
        }
        return reconciled
    }

    private suspend fun produce(envelope: VaultKeyEnvelope): VerifiedPortableBackup {
        val capture = try {
            captureSource.capture()
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            throw PublicationFailure(BackupUnavailableReason.ENCODING_OR_CRYPTO)
        }
        if (capture.vaultId != vaultId) {
            throw PublicationFailure(BackupUnavailableReason.ENCODING_OR_CRYPTO)
        }
        val producedAt = now()
        if (producedAt.toEpochMilli() < 0) {
            throw PublicationFailure(BackupUnavailableReason.ENCODING_OR_CRYPTO)
        }
        val key = try {
            contentKeyStore.openExisting(vaultId)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            throw PublicationFailure(BackupUnavailableReason.ENCODING_OR_CRYPTO)
        }
        var packageBytes: ByteArray? = null
        try {
            packageBytes = try {
                codec.encode(
                    recoveryEnvelope = envelope,
                    snapshot = BackupSnapshotCodec.fromCapture(capture),
                    producedAtEpochMillis = producedAt.toEpochMilli(),
                    key = key,
                )
            } catch (_: PortablePackageTooLargeException) {
                packageFile.delete()
                throw PublicationFailure(
                    BackupUnavailableReason.PACKAGE_TOO_LARGE,
                    withdraw = true,
                )
            } catch (failure: Throwable) {
                failure.rethrowCancellation()
                throw PublicationFailure(BackupUnavailableReason.ENCODING_OR_CRYPTO)
            }
            if (packageBytes.size.toLong() > BackupPolicy.MAX_PORTABLE_PACKAGE_BYTES) {
                packageFile.delete()
                throw PublicationFailure(
                    BackupUnavailableReason.PACKAGE_TOO_LARGE,
                    withdraw = true,
                )
            }
            return writeVerifyAndCommit(
                packageBytes = packageBytes,
                expectedGeneration = capture.generation.value,
                expectedProducedAt = producedAt.toEpochMilli(),
                expectedEnvelopeDigest = envelopeDigest(envelope),
                key = key,
            )
        } finally {
            packageBytes?.fill(0)
            key.close()
        }
    }

    private fun writeVerifyAndCommit(
        packageBytes: ByteArray,
        expectedGeneration: Long,
        expectedProducedAt: Long,
        expectedEnvelopeDigest: String,
        key: VaultKey,
    ): VerifiedPortableBackup {
        var stream: OutputStream? = null
        var finished = false
        try {
            stream = packageFile.startWrite()
            try {
                stream.write(packageBytes)
                stream.flush()
            } catch (failure: Throwable) {
                failure.rethrowCancellation()
                throw PublicationFailure(BackupUnavailableReason.FILE_IO)
            }
            val verified = try {
                packageFile.openRead().use { source ->
                    codec.verifyComplete(source, packageFile.length(), key)
                }
            } catch (failure: Throwable) {
                failure.rethrowCancellation()
                throw PublicationFailure(BackupUnavailableReason.VERIFICATION_FAILED)
            }
            if (
                verified.vaultId != vaultId.value ||
                verified.generation != expectedGeneration ||
                verified.producedAtEpochMillis != expectedProducedAt ||
                verified.recoveryEnvelopeSha256 != expectedEnvelopeDigest ||
                verified.totalPackageLength != packageBytes.size.toLong()
            ) {
                throw PublicationFailure(BackupUnavailableReason.VERIFICATION_FAILED)
            }
            try {
                packageFile.finishWrite(stream)
                finished = true
            } catch (failure: Throwable) {
                failure.rethrowCancellation()
                throw PublicationFailure(BackupUnavailableReason.FILE_IO)
            }
            return verified
        } catch (failure: PublicationFailure) {
            throw failure
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            throw PublicationFailure(BackupUnavailableReason.FILE_IO)
        } finally {
            if (!finished && stream != null) {
                try {
                    packageFile.failWrite(stream)
                } catch (_: Throwable) {
                    // The bounded persistent reason remains FILE_IO.
                }
            }
        }
    }

    private suspend fun recordFailure(
        state: BackupStateEntity,
        reason: BackupUnavailableReason,
        withdraw: Boolean,
    ): AndroidBackupStatus {
        val hasPrior = !withdraw &&
            state.portablePackageGeneration != null &&
            state.portablePackageBytes != null &&
            state.portablePackageProducedAtEpochMillis != null &&
            packageFile.length() > 0
        val updated = if (hasPrior) {
            state.copy(
                packageState = PACKAGE_UPDATE_PENDING,
                failureCategory = reason.name,
            )
        } else if (!state.recoveryEnvelopeReady && reason == BackupUnavailableReason.FILE_IO) {
            state
        } else {
            if (withdraw) packageFile.delete()
            state.copy(
                portablePackageGeneration = null,
                portablePackageBytes = null,
                portablePackageProducedAtEpochMillis = null,
                packageState = if (state.recoveryEnvelopeReady) {
                    PACKAGE_UNAVAILABLE
                } else {
                    PACKAGE_NOT_PREPARED
                },
                failureCategory = if (state.recoveryEnvelopeReady) reason.name else null,
            )
        }
        if (updated != state) {
            stateStore.compareAndUpdate(updated, state.currentGeneration)
        }
        return if (hasPrior) {
            AndroidBackupStatus.UpdatePending(packageInfo(updated))
        } else {
            AndroidBackupStatus.Unavailable(reason)
        }
    }

    private fun packageState(
        state: BackupStateEntity,
        verified: VerifiedPortableBackup,
    ): BackupStateEntity = state.copy(
        portablePackageGeneration = verified.generation,
        portablePackageBytes = verified.totalPackageLength,
        portablePackageProducedAtEpochMillis = verified.producedAtEpochMillis,
        packageState = if (verified.generation == state.currentGeneration) {
            PACKAGE_READY
        } else {
            PACKAGE_UPDATE_PENDING
        },
        failureCategory = null,
    )

    private fun status(state: BackupStateEntity): AndroidBackupStatus =
        if (state.packageState == PACKAGE_READY) {
            AndroidBackupStatus.Ready(packageInfo(state))
        } else {
            AndroidBackupStatus.UpdatePending(packageInfo(state))
        }

    private fun packageInfo(state: BackupStateEntity): BackupPackageInfo = BackupPackageInfo(
        packageGeneration = BackupGeneration(checkNotNull(state.portablePackageGeneration)),
        currentGeneration = BackupGeneration(state.currentGeneration),
        byteCount = checkNotNull(state.portablePackageBytes),
        producedAt = Instant.ofEpochMilli(
            checkNotNull(state.portablePackageProducedAtEpochMillis),
        ),
    )

    private fun envelopeDigest(envelope: VaultKeyEnvelope): String {
        val canonical = RecoveryEnvelopeCodec.encode(envelope)
        val digest = try {
            MessageDigest.getInstance("SHA-256").digest(canonical)
        } finally {
            canonical.fill(0)
        }
        return try {
            buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }
        } finally {
            digest.fill(0)
        }
    }

    private class PublicationFailure(
        val reason: BackupUnavailableReason,
        val withdraw: Boolean = false,
    ) : RuntimeException()

    private companion object {
        const val PACKAGE_NOT_PREPARED = "NOT_PREPARED"
        const val PACKAGE_PREPARING = "PREPARING"
        const val PACKAGE_READY = "READY"
        const val PACKAGE_UPDATE_PENDING = "UPDATE_PENDING"
        const val PACKAGE_UNAVAILABLE = "UNAVAILABLE"
        const val HEX = "0123456789abcdef"
    }
}

private fun VaultKeyEnvelope.clear() {
    kdf.salt.fill(0)
    nonce.fill(0)
    wrappedKeyset.fill(0)
}

private fun Throwable.rethrowCancellation() {
    if (this is CancellationException) throw this
}
