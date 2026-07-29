package app.opentasks.backup

import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.backup.BackupStateEntity
import app.opentasks.core.data.backup.BackupStateStore
import app.opentasks.core.data.backup.PortableBootstrapHeaderV1
import app.opentasks.core.data.backup.PortablePackageCodec
import app.opentasks.core.data.backup.RecoveryEnvelopeCodec
import app.opentasks.core.data.backup.RecoveryEnvelopeStore
import app.opentasks.core.data.backup.VerifiedPortableBackup
import app.opentasks.core.model.RestoredPackageCondition
import app.opentasks.core.model.VaultId
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

sealed interface RestoredPackageIntakeResult {
    data object NoPackage : RestoredPackageIntakeResult
    data object CurrentSelfProduced : RestoredPackageIntakeResult
    data object ReconciledSelfProduced : RestoredPackageIntakeResult
    data class Preserved(
        val condition: RestoredPackageCondition,
    ) : RestoredPackageIntakeResult

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
    private val moveAtomicallyNoReplace: (File, File) -> Boolean = ::atomicMoveNoReplace,
) {
    suspend fun inspect(): RestoredPackageIntakeResult {
        val packageLength = packageFile.length()
        if (!eligiblePackage.isFile || packageLength <= 0) {
            return RestoredPackageIntakeResult.NoPackage
        }
        val header = try {
            packageFile.openRead().use { codec.readBootstrap(it, packageLength) }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return preserve(RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT)
        }
        val state = try {
            stateStore.get(vaultId)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            null
        }
        val envelope = try {
            envelopeStore.get(vaultId)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            null
        }
        val linked = try {
            try {
                isLinked(header, state, envelope)
            } catch (failure: Throwable) {
                failure.rethrowCancellation()
                return preserve(RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT)
            }
        } finally {
            envelope?.clear()
        }
        if (!linked || state == null) {
            return preserve(RestoredPackageCondition.PRESERVED)
        }

        val verified = try {
            val key = contentKeyStore.openExisting(vaultId)
            try {
                packageFile.openRead().use {
                    codec.verifyComplete(it, packageLength, key)
                }
            } finally {
                key.close()
            }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return withdraw()
        }
        if (!verified.agreesWith(header, packageLength)) return withdraw()
        if (state.matches(verified)) {
            return RestoredPackageIntakeResult.CurrentSelfProduced
        }

        val reconciled = state.copy(
            portablePackageGeneration = verified.generation,
            portablePackageBytes = verified.totalPackageLength,
            portablePackageProducedAtEpochMillis = verified.producedAtEpochMillis,
            packageState = if (verified.generation == state.currentGeneration) {
                PACKAGE_READY
            } else {
                PACKAGE_UPDATE_PENDING
            },
            failureCategory = null,
            recoveryEnvelopeReady = true,
        )
        return if (
            stateStore.compareAndUpdate(reconciled, state.currentGeneration) == 1
        ) {
            RestoredPackageIntakeResult.ReconciledSelfProduced
        } else {
            RestoredPackageIntakeResult.PreservationBlocked
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
        const val PACKAGE_READY = "READY"
        const val PACKAGE_UPDATE_PENDING = "UPDATE_PENDING"
        const val HEX = "0123456789abcdef"

        fun atomicMoveNoReplace(source: File, target: File): Boolean {
            if (target.exists()) return false
            if (target.parentFile?.mkdirs() == false && target.parentFile?.isDirectory != true) {
                return false
            }
            return try {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
                true
            } catch (_: Throwable) {
                false
            }
        }
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
