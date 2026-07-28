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
    private var closed = false

    internal fun copySerializedKeyset(): ByteArray = synchronized(this) {
        check(!closed) { "The vault key is closed" }
        serializedKeyset.copyOf()
    }

    override fun close() = synchronized(this) {
        serializedKeyset.fill(0)
        closed = true
    }
}

interface VaultCrypto {
    fun createKey(): VaultKey

    fun wrapForRecovery(
        unlockedKey: VaultKey,
        passphrase: CharArray,
    ): VaultKeyEnvelope

    @Deprecated(
        message = "Create the content key and recovery envelope explicitly",
    )
    fun createVault(passphrase: CharArray): VaultKeyEnvelope {
        val key = createKey()
        return try {
            wrapForRecovery(key, passphrase)
        } finally {
            key.close()
        }
    }

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
