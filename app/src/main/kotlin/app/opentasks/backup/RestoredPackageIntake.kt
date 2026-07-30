package app.opentasks.backup

import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.backup.BackupStateEntity
import app.opentasks.core.data.backup.BackupStateMutation
import app.opentasks.core.data.backup.BackupStateStore
import app.opentasks.core.data.backup.PortableBootstrapHeaderV1
import app.opentasks.core.data.backup.PortablePackageCodec
import app.opentasks.core.data.backup.RecoveryEnvelopeCodec
import app.opentasks.core.data.backup.RecoveryEnvelopeStore
import app.opentasks.core.data.backup.VerifiedPortableBackup
import app.opentasks.core.model.RestoredPackageCondition
import app.opentasks.core.model.VaultId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

sealed interface RestoredPackageIntakeResult {
    data object NoPackage : RestoredPackageIntakeResult
    data object CurrentSelfProduced : RestoredPackageIntakeResult
    data object ReconciledSelfProduced : RestoredPackageIntakeResult
    data class Preserved(
        val condition: RestoredPackageCondition,
    ) : RestoredPackageIntakeResult

    data object RetryableFailure : RestoredPackageIntakeResult
    data object PreservationBlocked : RestoredPackageIntakeResult
}

class RestoredPackageIntake(
    private val vaultId: VaultId,
    private val eligiblePackage: File,
    private val recoveryInbox: File,
    private val packageFile: AtomicPackageFile,
    private val stateStore: BackupStateStore,
    private val envelopeStore: RecoveryEnvelopeStore,
    private val contentKeyStore: VaultContentKeyStore,
    private val codec: PortablePackageCodec,
    private val moveAtomicallyNoReplace: (File, File) -> Boolean =
        SameFileSystemNoReplaceMover::move,
) {
    suspend fun inspect(): RestoredPackageIntakeResult {
        if (recoveryInbox.isFile) {
            return RestoredPackageIntakeResult.Preserved(
                RestoredPackageCondition.PRESERVED,
            )
        }
        if (!eligiblePackage.isFile) {
            return reconcileAbandonedInitialPreparation()
        }
        val packageLength = packageFile.length()
        if (packageLength <= 0) return RestoredPackageIntakeResult.RetryableFailure
        val header = try {
            packageFile.openRead().use { codec.readBootstrap(it, packageLength) }
        } catch (_: IOException) {
            return RestoredPackageIntakeResult.RetryableFailure
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return preserve(RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT)
        }
        val state = try {
            stateStore.get(vaultId)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return RestoredPackageIntakeResult.RetryableFailure
        }
        val envelope = try {
            envelopeStore.get(vaultId)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return RestoredPackageIntakeResult.RetryableFailure
        }
        val linkage = try {
            try {
                when {
                    isLinked(header, state, envelope) -> PackageLinkage.ESTABLISHED
                    isInitialCrashCandidate(header, state, envelope) ->
                        PackageLinkage.INITIAL_CRASH_CANDIDATE
                    else -> PackageLinkage.UNKNOWN
                }
            } catch (failure: Throwable) {
                failure.rethrowCancellation()
                return preserve(RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT)
            }
        } finally {
            envelope?.clear()
        }
        if (linkage == PackageLinkage.UNKNOWN || state == null) {
            return preserve(RestoredPackageCondition.PRESERVED)
        }

        val key = try {
            contentKeyStore.openExisting(vaultId)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return if (linkage == PackageLinkage.INITIAL_CRASH_CANDIDATE) {
                preserve(RestoredPackageCondition.PRESERVED)
            } else {
                RestoredPackageIntakeResult.PreservationBlocked
            }
        }
        val verified = try {
            try {
                packageFile.openRead().use {
                    codec.verifyComplete(it, packageLength, key)
                }
            } finally {
                key.close()
            }
        } catch (_: IOException) {
            return RestoredPackageIntakeResult.RetryableFailure
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return if (linkage == PackageLinkage.INITIAL_CRASH_CANDIDATE) {
                preserve(RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT)
            } else {
                withdraw()
            }
        }
        if (!verified.agreesWith(header, packageLength)) {
            return if (linkage == PackageLinkage.INITIAL_CRASH_CANDIDATE) {
                preserve(RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT)
            } else {
                withdraw()
            }
        }
        if (linkage == PackageLinkage.INITIAL_CRASH_CANDIDATE) {
            return reconcileInitialCrashCandidate(verified, header)
        }
        if (state.matches(verified)) {
            return RestoredPackageIntakeResult.CurrentSelfProduced
        }

        val reconciled = stateStore.mutate(
            vaultId,
            BackupStateMutation { latest ->
                if (
                    latest.packageState == PACKAGE_RESTORED_DETECTED ||
                    verified.generation > latest.currentGeneration
                ) {
                    null
                } else {
                    latest.withVerifiedPackage(verified, envelopeReady = true)
                }
            },
        )
        return if (reconciled != null) {
            RestoredPackageIntakeResult.ReconciledSelfProduced
        } else {
            RestoredPackageIntakeResult.PreservationBlocked
        }
    }

    private suspend fun reconcileAbandonedInitialPreparation():
        RestoredPackageIntakeResult {
        val state = try {
            stateStore.get(vaultId)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return RestoredPackageIntakeResult.RetryableFailure
        } ?: return RestoredPackageIntakeResult.NoPackage
        if (
            state.packageState != PACKAGE_PREPARING ||
            state.recoveryEnvelopeReady ||
            state.portablePackageGeneration != null ||
            state.portablePackageBytes != null ||
            state.portablePackageProducedAtEpochMillis != null
        ) {
            return RestoredPackageIntakeResult.NoPackage
        }
        val cleared = try {
            stateStore.mutate(
                vaultId,
                BackupStateMutation { latest ->
                    if (
                        latest.packageState == PACKAGE_PREPARING &&
                        !latest.recoveryEnvelopeReady &&
                        latest.portablePackageGeneration == null &&
                        latest.portablePackageBytes == null &&
                        latest.portablePackageProducedAtEpochMillis == null
                    ) {
                        latest.copy(
                            packageState = PACKAGE_NOT_PREPARED,
                            failureCategory = null,
                        )
                    } else {
                        latest
                    }
                },
            )
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            null
        }
        return if (cleared != null) {
            RestoredPackageIntakeResult.NoPackage
        } else {
            RestoredPackageIntakeResult.RetryableFailure
        }
    }

    private suspend fun reconcileInitialCrashCandidate(
        verified: VerifiedPortableBackup,
        header: PortableBootstrapHeaderV1,
    ): RestoredPackageIntakeResult {
        val recoveredEnvelope = try {
            RecoveryEnvelopeCodec.fromPayload(header.recoveryEnvelope)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return preserve(RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT)
        }
        try {
            if (envelopeDigest(recoveredEnvelope) != verified.recoveryEnvelopeSha256) {
                return preserve(RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT)
            }
            val committed = try {
                envelopeStore.commitInitial(
                    vaultId = vaultId,
                    envelope = recoveredEnvelope,
                    published = verified,
                )
            } catch (failure: Throwable) {
                failure.rethrowCancellation()
                return RestoredPackageIntakeResult.RetryableFailure
            }
            return if (committed != null) {
                RestoredPackageIntakeResult.ReconciledSelfProduced
            } else {
                RestoredPackageIntakeResult.RetryableFailure
            }
        } finally {
            recoveredEnvelope.clear()
        }
    }

    private fun isLinked(
        header: PortableBootstrapHeaderV1,
        state: BackupStateEntity?,
        envelope: VaultKeyEnvelope?,
    ): Boolean {
        if (
            state == null ||
            envelope == null ||
            state.vaultId != vaultId.value ||
            header.vaultId != vaultId.value ||
            header.generation > state.currentGeneration
        ) {
            return false
        }
        return envelopeDigest(envelope) == envelopeDigest(header)
    }

    private fun isInitialCrashCandidate(
        header: PortableBootstrapHeaderV1,
        state: BackupStateEntity?,
        envelope: VaultKeyEnvelope?,
    ): Boolean =
        state != null &&
            envelope == null &&
            state.vaultId == vaultId.value &&
            !state.recoveryEnvelopeReady &&
            (
                state.packageState == PACKAGE_NOT_PREPARED ||
                    state.packageState == PACKAGE_PREPARING
            ) &&
            header.vaultId == vaultId.value &&
            header.generation <= state.currentGeneration

    private fun BackupStateEntity.matches(verified: VerifiedPortableBackup): Boolean {
        val expectedState = if (verified.generation == currentGeneration) {
            PACKAGE_READY
        } else {
            PACKAGE_UPDATE_PENDING
        }
        return recoveryEnvelopeReady &&
            portablePackageGeneration == verified.generation &&
            portablePackageBytes == verified.totalPackageLength &&
            portablePackageProducedAtEpochMillis == verified.producedAtEpochMillis &&
            packageState == expectedState &&
            failureCategory == null
    }

    private fun VerifiedPortableBackup.agreesWith(
        header: PortableBootstrapHeaderV1,
        packageLength: Long,
    ): Boolean =
        vaultId == header.vaultId &&
            generation == header.generation &&
            producedAtEpochMillis == header.producedAtEpochMillis &&
            recoveryEnvelopeSha256 == envelopeDigest(header) &&
            totalPackageLength == packageLength

    private fun BackupStateEntity.withVerifiedPackage(
        verified: VerifiedPortableBackup,
        envelopeReady: Boolean,
    ): BackupStateEntity = copy(
        portablePackageGeneration = verified.generation,
        portablePackageBytes = verified.totalPackageLength,
        portablePackageProducedAtEpochMillis = verified.producedAtEpochMillis,
        packageState = if (verified.generation == currentGeneration) {
            PACKAGE_READY
        } else {
            PACKAGE_UPDATE_PENDING
        },
        failureCategory = null,
        recoveryEnvelopeReady = envelopeReady,
    )

    private fun withdraw(): RestoredPackageIntakeResult =
        if (packageFile.delete()) {
            RestoredPackageIntakeResult.NoPackage
        } else {
            RestoredPackageIntakeResult.PreservationBlocked
        }

    private fun preserve(
        condition: RestoredPackageCondition,
    ): RestoredPackageIntakeResult {
        if (recoveryInbox.exists()) return RestoredPackageIntakeResult.PreservationBlocked
        return if (moveAtomicallyNoReplace(eligiblePackage, recoveryInbox)) {
            RestoredPackageIntakeResult.Preserved(condition)
        } else {
            RestoredPackageIntakeResult.PreservationBlocked
        }
    }

    private fun envelopeDigest(header: PortableBootstrapHeaderV1): String {
        val envelope = RecoveryEnvelopeCodec.fromPayload(header.recoveryEnvelope)
        return try {
            envelopeDigest(envelope)
        } finally {
            envelope.clear()
        }
    }

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

    private companion object {
        const val PACKAGE_NOT_PREPARED = "NOT_PREPARED"
        const val PACKAGE_PREPARING = "PREPARING"
        const val PACKAGE_READY = "READY"
        const val PACKAGE_UPDATE_PENDING = "UPDATE_PENDING"
        const val PACKAGE_RESTORED_DETECTED = "RESTORED_PACKAGE_DETECTED"
        const val HEX = "0123456789abcdef"
    }
}

private enum class PackageLinkage {
    ESTABLISHED,
    INITIAL_CRASH_CANDIDATE,
    UNKNOWN,
}

internal class SameFileSystemNoReplaceMover(
    private val createLink: (Path, Path) -> Unit = { target, source ->
        Files.createLink(target, source)
    },
    private val deleteSource: (Path) -> Unit = Files::delete,
) {
    fun moveNoReplace(source: File, target: File): Boolean {
        if (target.parentFile?.mkdirs() == false && target.parentFile?.isDirectory != true) {
            return false
        }
        return try {
            createLink(target.toPath(), source.toPath())
            try {
                deleteSource(source.toPath())
                true
            } catch (_: Throwable) {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private val default = SameFileSystemNoReplaceMover()

        fun move(source: File, target: File): Boolean =
            default.moveNoReplace(source, target)
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
