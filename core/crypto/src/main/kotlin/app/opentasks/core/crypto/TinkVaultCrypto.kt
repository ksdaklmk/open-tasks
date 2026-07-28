package app.opentasks.core.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import org.bouncycastle.crypto.PBEParametersGenerator
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TinkVaultCrypto(
    private val secureRandom: SecureRandom = SecureRandom(),
) : VaultCrypto {
    init {
        AeadConfig.register()
    }

    override fun createKey(): VaultKey {
        val handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        val serialized = TinkJsonProtoKeysetFormat
            .serializeKeyset(handle, InsecureSecretKeyAccess.get())
            .toByteArray(Charsets.UTF_8)
        return VaultKey(serialized)
    }

    override fun wrapForRecovery(
        unlockedKey: VaultKey,
        passphrase: CharArray,
    ): VaultKeyEnvelope {
        val serialized = unlockedKey.copySerializedKeyset()
        return try {
            wrap(serialized, passphrase)
        } finally {
            serialized.fill(0)
        }
    }

    override fun unlock(
        passphrase: CharArray,
        envelope: VaultKeyEnvelope,
    ): VaultKey {
        require(envelope.formatVersion == ENVELOPE_VERSION) {
            "Unsupported key envelope version ${envelope.formatVersion}"
        }
        val wrappingKey = deriveWrappingKey(passphrase, envelope.kdf)
        val plaintext = try {
            aesGcmDecrypt(
                wrappingKey,
                envelope.nonce,
                envelope.wrappedKeyset,
                ENVELOPE_ASSOCIATED_DATA,
            )
        } catch (failure: AEADBadTagException) {
            throw InvalidRecoveryPassphraseException(failure)
        } finally {
            wrappingKey.fill(0)
        }

        try {
            parseHandle(plaintext)
        } catch (failure: GeneralSecurityException) {
            plaintext.fill(0)
            throw CorruptVaultKeyEnvelopeException(failure)
        }
        return VaultKey(plaintext)
    }

    override fun changePassphrase(
        unlockedKey: VaultKey,
        newPassphrase: CharArray,
    ): VaultKeyEnvelope = wrapForRecovery(unlockedKey, newPassphrase)

    override fun encryptRecord(
        key: VaultKey,
        context: CryptoContext,
        plaintext: ByteArray,
    ): ByteArray = primitive(key).encrypt(plaintext, context.associatedData())

    override fun decryptRecord(
        key: VaultKey,
        context: CryptoContext,
        ciphertext: ByteArray,
    ): ByteArray = primitive(key).decrypt(ciphertext, context.associatedData())

    private fun wrap(
        serializedKeyset: ByteArray,
        passphrase: CharArray,
    ): VaultKeyEnvelope {
        require(passphrase.isNotEmpty()) { "Recovery passphrase cannot be empty" }
        val metadata = Argon2Metadata(ByteArray(SALT_BYTES).also(secureRandom::nextBytes))
        val wrappingKey = deriveWrappingKey(passphrase, metadata)
        val nonce = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)
        val wrapped = try {
            aesGcmEncrypt(
                wrappingKey,
                nonce,
                serializedKeyset,
                ENVELOPE_ASSOCIATED_DATA,
            )
        } finally {
            wrappingKey.fill(0)
        }
        return VaultKeyEnvelope(
            formatVersion = ENVELOPE_VERSION,
            kdf = metadata,
            nonce = nonce,
            wrappedKeyset = wrapped,
        )
    }

    private fun primitive(key: VaultKey): Aead {
        val serialized = key.copySerializedKeyset()
        return try {
            parseHandle(serialized).getPrimitive(
                RegistryConfiguration.get(),
                Aead::class.java,
            )
        } finally {
            serialized.fill(0)
        }
    }

    private fun parseHandle(serialized: ByteArray): KeysetHandle =
        TinkJsonProtoKeysetFormat.parseKeyset(
            serialized.toString(Charsets.UTF_8),
            InsecureSecretKeyAccess.get(),
        )

    private fun deriveWrappingKey(
        passphrase: CharArray,
        metadata: Argon2Metadata,
    ): ByteArray = Argon2idKdf.derive(passphrase, metadata)

    private fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = Cipher.getInstance(AES_GCM).run {
        init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, AES),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        updateAAD(associatedData)
        doFinal(plaintext)
    }

    private fun aesGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = Cipher.getInstance(AES_GCM).run {
        init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, AES),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        updateAAD(associatedData)
        doFinal(ciphertext)
    }

    private companion object {
        const val ENVELOPE_VERSION = 1
        const val SALT_BYTES = 16
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val AES = "AES"
        const val AES_GCM = "AES/GCM/NoPadding"
        val ENVELOPE_ASSOCIATED_DATA =
            "open-tasks:recovery-envelope:v1".toByteArray(Charsets.UTF_8)
    }
}

internal object Argon2idKdf {
    fun derive(
        passphrase: CharArray,
        metadata: Argon2Metadata,
    ): ByteArray {
        require(metadata.salt.size == SALT_BYTES)
        require(metadata.memoryKiB >= 65_536)
        require(metadata.iterations >= 3)
        require(metadata.parallelism == 1)

        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(metadata.salt)
            .withMemoryAsKB(metadata.memoryKiB)
            .withIterations(metadata.iterations)
            .withParallelism(metadata.parallelism)
            .build()
        val generator = Argon2BytesGenerator().apply { init(parameters) }
        val passwordBytes = PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(passphrase)
        return ByteArray(AES_KEY_BYTES).also { output ->
            try {
                generator.generateBytes(passwordBytes, output)
            } finally {
                passwordBytes.fill(0)
            }
        }
    }

    private const val SALT_BYTES = 16
    private const val AES_KEY_BYTES = 32
}

class InvalidRecoveryPassphraseException(cause: Throwable) :
    GeneralSecurityException("The recovery passphrase is incorrect", cause)

class CorruptVaultKeyEnvelopeException(cause: Throwable) :
    GeneralSecurityException("The vault key envelope is corrupt", cause)
