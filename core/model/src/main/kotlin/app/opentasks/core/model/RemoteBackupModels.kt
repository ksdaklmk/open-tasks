package app.opentasks.core.model

import java.time.Instant
import java.util.UUID

/**
 * Opaque create-only remote-backup identities.
 *
 * Every wrapper compares by value, validates its canonical shape on creation,
 * and never reveals that value through [toString] so identifiers cannot reach
 * logs, telemetry, or exception text.
 */
class CloudLineageId private constructor(val value: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is CloudLineageId && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "CloudLineageId(redacted)"

    companion object {
        fun new(): CloudLineageId = CloudLineageId(UUID.randomUUID().toString())

        fun parse(value: String): CloudLineageId =
            CloudLineageId(canonicalUuid(value, "Cloud lineage identifier"))
    }
}

class CloudDeviceId private constructor(val value: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is CloudDeviceId && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "CloudDeviceId(redacted)"

    companion object {
        fun new(): CloudDeviceId = CloudDeviceId(UUID.randomUUID().toString())

        fun parse(value: String): CloudDeviceId =
            CloudDeviceId(canonicalUuid(value, "Cloud device identifier"))
    }
}

class OwnershipClaimId private constructor(val value: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is OwnershipClaimId && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "OwnershipClaimId(redacted)"

    companion object {
        fun new(): OwnershipClaimId = OwnershipClaimId(UUID.randomUUID().toString())

        fun parse(value: String): OwnershipClaimId =
            OwnershipClaimId(canonicalUuid(value, "Ownership claim identifier"))
    }
}

class PublicationId private constructor(val value: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PublicationId && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "PublicationId(redacted)"

    companion object {
        fun new(): PublicationId = PublicationId(UUID.randomUUID().toString())

        fun parse(value: String): PublicationId =
            PublicationId(canonicalUuid(value, "Publication identifier"))
    }
}

class RemoteLogicalObjectId private constructor(val value: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is RemoteLogicalObjectId && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "RemoteLogicalObjectId(redacted)"

    companion object {
        fun new(): RemoteLogicalObjectId =
            RemoteLogicalObjectId(UUID.randomUUID().toString())

        fun of(value: String): RemoteLogicalObjectId =
            RemoteLogicalObjectId(
                opaqueIdentifier(value, "Remote logical object identifier"),
            )
    }
}

class ProviderObjectId private constructor(val value: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ProviderObjectId && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "ProviderObjectId(redacted)"

    companion object {
        fun of(value: String): ProviderObjectId =
            ProviderObjectId(opaqueIdentifier(value, "Provider object identifier"))
    }
}

class Sha256Digest private constructor(val value: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Sha256Digest && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "Sha256Digest(redacted)"

    companion object {
        fun of(value: String): Sha256Digest =
            Sha256Digest(lowercaseSha256(value, "Object digest"))
    }
}

@JvmInline
value class WriterEpoch(val value: Long) {
    init {
        require(value >= 1) { "Writer epoch is outside its bound" }
    }
}

@JvmInline
value class PublicationSequence(val value: Long) {
    init {
        require(value >= 0) { "Publication sequence is negative" }
    }
}

enum class RemoteBackupFailureCategory {
    AUTHORIZATION_REQUIRED,
    ACCOUNT_MISMATCH,
    OWNERSHIP_LOST,
    TERMINATED,
    AMBIGUOUS_REMOTE_STATE,
    PROVIDER_STORAGE,
    RETRYABLE_PROVIDER,
    CORRUPT_OR_INCOMPATIBLE,
    LOCAL_STORAGE,
}

enum class RecoveryFailureCategory {
    AUTHORIZATION_REQUIRED,
    RETRYABLE_PROVIDER,
    ACCOUNT_MISMATCH,
    WRONG_PASSPHRASE,
    UNSAFE_KDF,
    CORRUPT_OR_INCOMPATIBLE,
    MISSING_REQUIRED_OBJECT,
    INSUFFICIENT_STORAGE,
    STAGING_INVARIANT,
    OWNERSHIP_CHANGED,
    OWNERSHIP_LOST,
    TERMINATED,
    AMBIGUOUS_REMOTE_STATE,
    LOCAL_KEY_UNAVAILABLE,
}

enum class OwnershipStateV1 {
    ACTIVE,
    TERMINATED,
}

enum class RemoteObjectRoleV1 {
    OWNERSHIP_ROOT,
    OWNERSHIP_CLAIM,
    OWNERSHIP_TOMBSTONE,
    PUBLICATION,
    SNAPSHOT,
    SEGMENT,
}

enum class RemoteObjectLifecycle {
    PLANNED,
    UPLOADING,
    VERIFIED,
    ABANDONED,
    DELETED,
}

enum class RemoteBackupLifecycle {
    CONNECTING,
    ACTIVE,
    DORMANT,
    OWNERSHIP_LOST,
    DELETING,
    TERMINATED,
    BLOCKED,
}

@JvmInline
value class RemoteBackupStateVersion(val value: Long) {
    init {
        require(value >= 0) { "Remote backup state version is negative" }
    }
}

data class OwnershipClaimRef(
    val providerId: ProviderObjectId,
    val logicalId: OwnershipClaimId,
    val sha256: Sha256Digest,
    val writerEpoch: WriterEpoch,
)

data class PublicationRef(
    val providerId: ProviderObjectId,
    val logicalId: PublicationId,
    val sha256: Sha256Digest,
    val sequence: PublicationSequence,
    val generation: BackupGeneration,
)

data class RemoteBackupVerifiedInfo(
    val generation: BackupGeneration,
    val verifiedAt: Instant,
)

sealed interface RemoteBackupStatus {
    data object Disabled : RemoteBackupStatus
    data object Preparing : RemoteBackupStatus
    data class BackingUp(val generation: BackupGeneration) :
        RemoteBackupStatus

    data class Verified(val info: RemoteBackupVerifiedInfo) :
        RemoteBackupStatus

    data class RetryScheduled(
        val generation: BackupGeneration,
        val reason: RemoteBackupFailureCategory,
    ) : RemoteBackupStatus

    data class ActionRequired(val reason: RemoteBackupFailureCategory) :
        RemoteBackupStatus

    data object OwnershipLost : RemoteBackupStatus
    data object AmbiguousRemoteState : RemoteBackupStatus
    data object Deleting : RemoteBackupStatus
    data object Terminated : RemoteBackupStatus
}

private const val MAX_OPAQUE_IDENTIFIER_LENGTH = 200

private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")

private fun canonicalUuid(value: String, label: String): String {
    val parsed = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("$label is not a canonical UUID")
    }
    require(parsed.toString() == value) { "$label is not a canonical UUID" }
    return value
}

private fun opaqueIdentifier(value: String, label: String): String {
    require(value.isNotEmpty() && value.length <= MAX_OPAQUE_IDENTIFIER_LENGTH) {
        "$label is outside its length bound"
    }
    require(value.none { it.isISOControl() || it.isWhitespace() }) {
        "$label contains control or space characters"
    }
    return value
}

private fun lowercaseSha256(value: String, label: String): String {
    require(LOWERCASE_SHA256.matches(value)) {
        "$label is not a lowercase SHA-256 digest"
    }
    return value
}
