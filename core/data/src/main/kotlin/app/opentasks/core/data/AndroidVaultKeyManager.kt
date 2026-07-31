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
 * Keeps the SQLCipher key of every vault slot outside its database.
 *
 * Only an AES-GCM wrapped copy is stored in app-private preferences. The
 * wrapping key itself is non-exportable and remains in Android Keystore.
 *
 * [VaultSlot.LEGACY] keeps the exact preference names, Keystore alias, and
 * associated data this application shipped with; every other slot suffixes them
 * with its SHA-256 digest, so two slots never share a wrapper.
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
    fun getOrCreateDatabaseKey(): ByteArray = getOrCreateDatabaseKey(VaultSlot.LEGACY)

    @Synchronized
    fun getOrCreateDatabaseKey(slot: VaultSlot): ByteArray =
        readEnvelope(slot)?.let { stored -> unwrap(slot, stored) } ?: create(slot)

    /**
     * Opens an established slot key, never creating an envelope or an alias.
     *
     * A missing envelope, a missing Keystore key, or a tampered envelope all
     * fail closed so an unreadable vault is preserved rather than replaced.
     */
    @Synchronized
    fun openExistingDatabaseKey(slot: VaultSlot): ByteArray {
        val stored = readEnvelope(slot)
            ?: error("The local vault key envelope has not been initialised")
        return unwrap(slot, stored)
    }

    @Synchronized
    fun createDatabaseKey(slot: VaultSlot): ByteArray {
        check(readEnvelope(slot) == null) {
            "The local vault key envelope already exists"
        }
        return create(slot)
    }

    /**
     * Removes a slot's envelope before its Keystore alias.
     *
     * The alias is only dropped once the envelope is gone, so an interrupted
     * deletion can never leave an envelope that no key can ever open.
     */
    @Synchronized
    fun deleteDatabaseKey(slot: VaultSlot) {
        check(
            preferences.edit()
                .remove(noncePreferenceKey(slot))
                .remove(ciphertextPreferenceKey(slot))
                .commit(),
        ) {
            "Unable to remove the local vault key envelope"
        }
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(aliasFor(slot))
    }

    @Synchronized
    fun hasDatabaseKey(slot: VaultSlot): Boolean =
        preferences.contains(noncePreferenceKey(slot)) ||
            preferences.contains(ciphertextPreferenceKey(slot))

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

    private fun create(slot: VaultSlot): ByteArray {
        val databaseKey = ByteArray(DATABASE_KEY_BYTES).also(secureRandom::nextBytes)
        val envelope = wrap(slot, databaseKey)
        val stored = preferences.edit()
            .putString(noncePreferenceKey(slot), envelope.nonce.encodeBase64())
            .putString(ciphertextPreferenceKey(slot), envelope.ciphertext.encodeBase64())
            .commit()
        if (!stored) {
            databaseKey.fill(0)
            error("Unable to persist the local vault key envelope")
        }
        return databaseKey
    }

    private fun readEnvelope(slot: VaultSlot): WrappedDatabaseKey? {
        val storedCiphertext = preferences.getString(ciphertextPreferenceKey(slot), null)
        val storedNonce = preferences.getString(noncePreferenceKey(slot), null)
        if (storedCiphertext == null && storedNonce == null) return null
        check(storedCiphertext != null && storedNonce != null) {
            "The local vault key envelope is incomplete"
        }
        return WrappedDatabaseKey(
            nonce = storedNonce.decodeBase64(),
            ciphertext = storedCiphertext.decodeBase64(),
        )
    }

    private fun wrap(slot: VaultSlot, databaseKey: ByteArray): WrappedDatabaseKey {
        val cipher = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey(slot))
            updateAAD(associatedData(slot))
        }
        return WrappedDatabaseKey(
            nonce = cipher.iv.copyOf(),
            ciphertext = cipher.doFinal(databaseKey),
        )
    }

    private fun unwrap(slot: VaultSlot, stored: WrappedDatabaseKey): ByteArray {
        check(stored.nonce.size == GCM_NONCE_BYTES) { "The local vault key nonce is invalid" }
        return Cipher.getInstance(AES_GCM).run {
            init(
                Cipher.DECRYPT_MODE,
                getOrCreateWrappingKey(slot, requireExisting = true),
                GCMParameterSpec(GCM_TAG_BITS, stored.nonce),
            )
            updateAAD(associatedData(slot))
            doFinal(stored.ciphertext)
        }
    }

    private fun getOrCreateWrappingKey(
        slot: VaultSlot,
        requireExisting: Boolean = false,
    ): SecretKey {
        val alias = aliasFor(slot)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        check(!requireExisting) {
            "The Android Keystore key for this local vault is unavailable"
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
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

    private fun noncePreferenceKey(slot: VaultSlot): String =
        DATABASE_KEY_NONCE.withSlotSuffix(slot)

    private fun ciphertextPreferenceKey(slot: VaultSlot): String =
        DATABASE_KEY_CIPHERTEXT.withSlotSuffix(slot)

    private fun aliasFor(slot: VaultSlot): String = KEYSTORE_ALIAS.withSlotSuffix(slot)

    private fun associatedData(slot: VaultSlot): ByteArray =
        if (slot == VaultSlot.LEGACY) {
            DATABASE_KEY_ASSOCIATED_DATA
        } else {
            "$DATABASE_KEY_ASSOCIATED_DATA_PREFIX:${slot.digest}".toByteArray(Charsets.UTF_8)
        }

    private fun String.withSlotSuffix(slot: VaultSlot): String =
        if (slot == VaultSlot.LEGACY) this else "${this}_${slot.digest}"

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
        const val DATABASE_KEY_ASSOCIATED_DATA_PREFIX = "open-tasks:local-database-key:v1"
        val DATABASE_KEY_ASSOCIATED_DATA =
            DATABASE_KEY_ASSOCIATED_DATA_PREFIX.toByteArray(Charsets.UTF_8)
    }
}
