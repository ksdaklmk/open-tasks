package app.opentasks.core.model

import java.time.Instant

@JvmInline
value class BackupGeneration(val value: Long) {
    init {
        require(value >= 0)
    }
}

data class BackupPackageInfo(
    val packageGeneration: BackupGeneration,
    val currentGeneration: BackupGeneration,
    val byteCount: Long,
    val producedAt: Instant,
)

enum class BackupUnavailableReason {
    PACKAGE_TOO_LARGE,
    RECOVERY_ENVELOPE_UNAVAILABLE,
    ENCODING_OR_CRYPTO,
    VERIFICATION_FAILED,
    FILE_IO,
}

enum class RestoredPackageCondition {
    PRESERVED,
    INCOMPATIBLE_OR_CORRUPT,
}

sealed interface AndroidBackupStatus {
    data object NotPrepared : AndroidBackupStatus
    data object Preparing : AndroidBackupStatus
    data class Ready(val packageInfo: BackupPackageInfo) : AndroidBackupStatus
    data class UpdatePending(val packageInfo: BackupPackageInfo) : AndroidBackupStatus
    data class Unavailable(
        val reason: BackupUnavailableReason,
    ) : AndroidBackupStatus

    data class RestoredPackageDetected(
        val condition: RestoredPackageCondition,
    ) : AndroidBackupStatus
}

sealed interface RecoveryPassphraseValidation {
    data object Valid : RecoveryPassphraseValidation
    data object TooShort : RecoveryPassphraseValidation
    data object TooLong : RecoveryPassphraseValidation
    data object ConfirmationMismatch : RecoveryPassphraseValidation
}
