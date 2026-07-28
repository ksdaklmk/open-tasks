package app.opentasks.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.opentasks.core.model.VaultId
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidVaultContentKeyStore(
    context: Context,
    private val crypto: VaultCrypto = TinkVaultCrypto(),
) : VaultContentKeyStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun getOrCreate(vaultId: VaultId): VaultKey = synchronized(PROCESS_LOCK) {
        when (val stored = readStoredEnvelope(vaultId)) {
            StoredEnvelope.Absent -> createAndPersist(vaultId)
            is StoredEnvelope.Complete -> unwrap(vaultId, stored)
        }
    }

    override fun replace(vaultId: VaultId, key: VaultKey) = synchronized(PROCESS_LOCK) {
        val storedEnvelope = readStoredEnvelope(vaultId)
        val requireExistingAlias = storedEnvelope is StoredEnvelope.Complete
        val alias = aliasFor(vaultId)
        val aliasExisted = keyStore().containsAlias(alias)
        try {
            persist(vaultId, wrap(vaultId, key, requireExistingAlias))
        } catch (failure: Throwable) {
            if (!aliasExisted && !requireExistingAlias) {
                keyStore().deleteEntry(alias)
            }
            throw failure
        }
    }

    override fun delete(vaultId: VaultId) = synchronized(PROCESS_LOCK) {
        check(
            preferences.edit()
                .remove(noncePreferenceKey(vaultId))
                .remove(ciphertextPreferenceKey(vaultId))
                .commit(),
        ) {
            "Unable to remove the local vault-content key envelope"
        }
        keyStore().deleteEntry(aliasFor(vaultId))
    }

    private fun createAndPersist(vaultId: VaultId): VaultKey {
        val alias = aliasFor(vaultId)
        val aliasExisted = keyStore().containsAlias(alias)
        val key = crypto.createKey()
        try {
            persist(vaultId, wrap(vaultId, key, requireExistingAlias = false))
            return key
        } catch (failure: Throwable) {
            key.close()
            if (!aliasExisted) {
                keyStore().deleteEntry(alias)
            }
            throw failure
        }
    }

    private fun readStoredEnvelope(vaultId: VaultId): StoredEnvelope {
        val nonce = preferences.getString(noncePreferenceKey(vaultId), null)
        val ciphertext = preferences.getString(ciphertextPreferenceKey(vaultId), null)
        if (nonce == null && ciphertext == null) return StoredEnvelope.Absent
        check(nonce != null && ciphertext != null) {
            "The local vault-content key envelope is incomplete"
        }
        return StoredEnvelope.Complete(
            nonce = nonce.decodeBase64(),
            ciphertext = ciphertext.decodeBase64(),
        )
    }

    private fun wrap(
        vaultId: VaultId,
        key: VaultKey,
        requireExistingAlias: Boolean,
    ): WrappedKey {
        val plaintext = key.copySerializedKeyset()
        return try {
            val cipher = Cipher.getInstance(AES_GCM).apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    wrappingKey(vaultId, requireExistingAlias),
                )
                updateAAD(associatedData(vaultId))
            }
            WrappedKey(
                nonce = cipher.iv.copyOf(),
                ciphertext = cipher.doFinal(plaintext),
            )
        } finally {
            plaintext.fill(0)
        }
    }

    private fun unwrap(
        vaultId: VaultId,
        stored: StoredEnvelope.Complete,
    ): VaultKey {
        check(stored.nonce.size == GCM_NONCE_BYTES) {
            "The local vault-content key nonce is invalid"
        }
        val plaintext = Cipher.getInstance(AES_GCM).run {
            init(
                Cipher.DECRYPT_MODE,
                wrappingKey(vaultId, requireExisting = true),
                GCMParameterSpec(GCM_TAG_BITS, stored.nonce),
            )
            updateAAD(associatedData(vaultId))
            doFinal(stored.ciphertext)
        }
        return VaultKey(plaintext)
    }

    private fun persist(vaultId: VaultId, wrapped: WrappedKey) {
        check(
            preferences.edit()
                .putString(noncePreferenceKey(vaultId), wrapped.nonce.encodeBase64())
                .putString(
                    ciphertextPreferenceKey(vaultId),
                    wrapped.ciphertext.encodeBase64(),
                )
                .commit(),
        ) {
            "Unable to persist the local vault-content key envelope"
        }
    }

    private fun wrappingKey(
        vaultId: VaultId,
        requireExisting: Boolean,
    ): SecretKey {
        val alias = aliasFor(vaultId)
        val keyStore = keyStore()
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        check(!requireExisting) {
            "The Android Keystore key for this vault-content key is unavailable"
        }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(WRAPPING_KEY_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .setUnlockedDeviceRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun aliasFor(vaultId: VaultId): String =
        "$KEYSTORE_ALIAS_PREFIX${vaultDigest(vaultId)}"

    private fun noncePreferenceKey(vaultId: VaultId): String =
        "$NONCE_PREFERENCE_PREFIX${vaultDigest(vaultId)}"

    private fun ciphertextPreferenceKey(vaultId: VaultId): String =
        "$CIPHERTEXT_PREFERENCE_PREFIX${vaultDigest(vaultId)}"

    private fun vaultDigest(vaultId: VaultId): String =
        MessageDigest.getInstance(DIGEST_ALGORITHM)
            .digest(vaultId.value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }

    private fun associatedData(vaultId: VaultId): ByteArray =
        "$LOCAL_ENVELOPE_ASSOCIATED_DATA_PREFIX\u0000${vaultId.value}"
            .toByteArray(Charsets.UTF_8)

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun ByteArray.encodeBase64(): String =
        Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decodeBase64(): ByteArray =
        try {
            Base64.decode(this, Base64.NO_WRAP)
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException(
                "The local vault-content key envelope is invalid",
                failure,
            )
        }

    private sealed interface StoredEnvelope {
        data object Absent : StoredEnvelope

        data class Complete(
            val nonce: ByteArray,
            val ciphertext: ByteArray,
        ) : StoredEnvelope
    }

    private data class WrappedKey(
        val nonce: ByteArray,
        val ciphertext: ByteArray,
    )

    private companion object {
        const val PREFERENCES_NAME = "vault_content_keys_v1"
        const val KEYSTORE_ALIAS_PREFIX = "open_tasks_vault_content_wrapper_v1_"
        const val NONCE_PREFERENCE_PREFIX = "nonce_v1_"
        const val CIPHERTEXT_PREFERENCE_PREFIX = "ciphertext_v1_"
        const val LOCAL_ENVELOPE_ASSOCIATED_DATA_PREFIX =
            "open-tasks:local-vault-content-key:v1"
        const val DIGEST_ALGORITHM = "SHA-256"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val WRAPPING_KEY_BITS = 256
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        val PROCESS_LOCK = Any()
    }
}
