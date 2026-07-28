package app.opentasks.core.sync

import kotlinx.serialization.Serializable

@Serializable
enum class CloudObjectFamily {
    MANIFEST,
    SNAPSHOT,
    OPERATION_SEGMENT,
    ATTACHMENT_CHUNK,
}

@Serializable
data class CloudObjectHeader(
    val magic: String = "OPEN_TASKS",
    val family: CloudObjectFamily,
    val schemaVersion: Int = 1,
    val cryptoVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val vaultId: String,
    val objectId: String,
    val ciphertextLength: Long,
    val ciphertextSha256: String,
    val chunkIndex: Int? = null,
    val chunkCount: Int? = null,
) {
    val identity: CloudHeaderIdentity
        get() = CloudHeaderIdentity(
            family = family,
            schemaVersion = schemaVersion,
            cryptoVersion = cryptoVersion,
            minimumReaderVersion = minimumReaderVersion,
            vaultId = vaultId,
            objectId = objectId,
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
        )
}

data class CloudHeaderIdentity(
    val family: CloudObjectFamily,
    val schemaVersion: Int,
    val cryptoVersion: Int,
    val minimumReaderVersion: Int,
    val vaultId: String,
    val objectId: String,
    val chunkIndex: Int? = null,
    val chunkCount: Int? = null,
)

/**
 * Owns one verified ciphertext buffer.
 *
 * [ciphertext] returns defensive copies while ownership is retained.
 * [takeCiphertext] transfers the exact buffer once; both access paths fail
 * after transfer.
 */
class CloudObjectFrame internal constructor(
    val header: CloudObjectHeader,
    ciphertext: ByteArray,
) {
    private var ciphertextBytes: ByteArray? = ciphertext

    val ciphertext: ByteArray
        get() = synchronized(this) {
            checkNotNull(ciphertextBytes) {
                "Ciphertext ownership has already been transferred"
            }.copyOf()
        }

    @Synchronized
    fun takeCiphertext(): ByteArray = checkNotNull(ciphertextBytes) {
        "Ciphertext ownership has already been transferred"
    }.also {
        ciphertextBytes = null
    }
}
