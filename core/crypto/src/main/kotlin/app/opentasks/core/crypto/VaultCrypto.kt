package app.opentasks.core.crypto

import app.opentasks.core.model.VaultId

data class CryptoContext(
    val vaultId: VaultId,
    val objectId: String,
    val formatVersion: Int,
    val chunkIndex: Long? = null,
) {
    fun associatedData(): ByteArray = buildString {
        append("open-tasks")
        append('\u0000')
        append(vaultId.value)
        append('\u0000')
        append(objectId)
        append('\u0000')
        append(formatVersion)
        append('\u0000')
        append(chunkIndex ?: -1)
    }.toByteArray(Charsets.UTF_8)
}

data class Argon2Metadata(
    val salt: ByteArray,
    val memoryKiB: Int = 65_536,
    val iterations: Int = 3,
    val parallelism: Int = 1,
)

data class VaultKeyEnvelope(
    val formatVersion: Int,
    val kdf: Argon2Metadata,
    val nonce: ByteArray,
    val wrappedKeyset: ByteArray,
)

class VaultKey internal constructor(
    internal val serializedKeyset: ByteArray,
) : AutoCloseable {
    override fun close() {
        serializedKeyset.fill(0)
    }
}

interface VaultCrypto {
    fun createVault(passphrase: CharArray): VaultKeyEnvelope
    fun unlock(passphrase: CharArray, envelope: VaultKeyEnvelope): VaultKey
    fun changePassphrase(
        unlockedKey: VaultKey,
        newPassphrase: CharArray,
    ): VaultKeyEnvelope

    fun encryptRecord(
        key: VaultKey,
        context: CryptoContext,
        plaintext: ByteArray,
    ): ByteArray

    fun decryptRecord(
        key: VaultKey,
        context: CryptoContext,
        ciphertext: ByteArray,
    ): ByteArray
}
