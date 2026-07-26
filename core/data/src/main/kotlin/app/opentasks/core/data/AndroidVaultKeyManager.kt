package app.opentasks.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.opentasks.core.model.DeviceId
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the SQLCipher key outside the database.
 *
 * Only an AES-GCM wrapped copy is stored in app-private preferences. The
 * wrapping key itself is non-exportable and remains in Android Keystore.
 */
class AndroidVaultKeyManager(
    context: Context,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun getOrCreateDatabaseKey(): ByteArray {
        val storedCiphertext = preferences.getString(DATABASE_KEY_CIPHERTEXT, null)
        val storedNonce = preferences.getString(DATABASE_KEY_NONCE, null)
        if (storedCiphertext != null || storedNonce != null) {
            check(storedCiphertext != null && storedNonce != null) {
                "The local vault key envelope is incomplete"
            }
            return unwrap(
                nonce = storedNonce.decodeBase64(),
                ciphertext = storedCiphertext.decodeBase64(),
            )
        }

        val databaseKey = ByteArray(DATABASE_KEY_BYTES).also(secureRandom::nextBytes)
        val envelope = wrap(databaseKey)
        val stored = preferences.edit()
            .putString(DATABASE_KEY_NONCE, envelope.nonce.encodeBase64())
            .putString(DATABASE_KEY_CIPHERTEXT, envelope.ciphertext.encodeBase64())
            .commit()
        if (!stored) {
            databaseKey.fill(0)
            error("Unable to persist the local vault key envelope")
        }
        return databaseKey
    }

    @Synchronized
    fun getOrCreateDeviceId(): DeviceId {
        val stored = preferences.getString(DEVICE_ID, null)
        if (stored != null) return DeviceId(stored)
        val created = UUID.randomUUID().toString()
        check(preferences.edit().putString(DEVICE_ID, created).commit()) {
            "Unable to persist the local device identifier"
        }
        return DeviceId(created)
    }

    private fun wrap(databaseKey: ByteArray): WrappedDatabaseKey {
        val cipher = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
            updateAAD(DATABASE_KEY_ASSOCIATED_DATA)
        }
        return WrappedDatabaseKey(
            nonce = cipher.iv.copyOf(),
            ciphertext = cipher.doFinal(databaseKey),
        )
    }

    private fun unwrap(
        nonce: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        check(nonce.size == GCM_NONCE_BYTES) { "The local vault key nonce is invalid" }
        return Cipher.getInstance(AES_GCM).run {
            init(
                Cipher.DECRYPT_MODE,
                getOrCreateWrappingKey(requireExisting = true),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            updateAAD(DATABASE_KEY_ASSOCIATED_DATA)
            doFinal(ciphertext)
        }
    }

    private fun getOrCreateWrappingKey(requireExisting: Boolean = false): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        check(!requireExisting) {
            "The Android Keystore key for this local vault is unavailable"
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(DATABASE_KEY_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .setUnlockedDeviceRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private data class WrappedDatabaseKey(
        val nonce: ByteArray,
        val ciphertext: ByteArray,
    )

    private fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val PREFERENCES_NAME = "vault_keys"
        const val DATABASE_KEY_NONCE = "database_key_nonce_v1"
        const val DATABASE_KEY_CIPHERTEXT = "database_key_ciphertext_v1"
        const val DEVICE_ID = "device_id_v1"
        const val KEYSTORE_ALIAS = "open_tasks_local_vault_wrapper_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val DATABASE_KEY_BITS = 256
        const val DATABASE_KEY_BYTES = DATABASE_KEY_BITS / Byte.SIZE_BITS
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        val DATABASE_KEY_ASSOCIATED_DATA =
            "open-tasks:local-database-key:v1".toByteArray(Charsets.UTF_8)
    }
}
