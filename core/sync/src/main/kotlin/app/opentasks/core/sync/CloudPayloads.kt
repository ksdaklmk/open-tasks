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

class CloudObjectFrame internal constructor(
    val header: CloudObjectHeader,
    ciphertext: ByteArray,
) {
    private val ciphertextBytes = ciphertext.copyOf()

    val ciphertext: ByteArray
        get() = ciphertextBytes.copyOf()
}
