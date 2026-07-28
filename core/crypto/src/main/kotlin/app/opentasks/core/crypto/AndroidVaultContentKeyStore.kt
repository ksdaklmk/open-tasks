package app.opentasks.core.crypto

import android.content.Context
import android.content.SharedPreferences
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

internal fun interface PreferenceCommitBoundary {
    fun commit(editor: SharedPreferences.Editor): Boolean
}

internal data object SharedPreferencesCommitBoundary : PreferenceCommitBoundary {
    override fun commit(editor: SharedPreferences.Editor): Boolean = editor.commit()
}

internal interface WrappingKeyBoundary {
    fun containsAlias(alias: String): Boolean

    fun getOrCreate(
        alias: String,
        requireExisting: Boolean,
    ): SecretKey

    fun deleteEntry(alias: String)
}

internal class AndroidKeystoreWrappingKeyBoundary : WrappingKeyBoundary {
    override fun containsAlias(alias: String): Boolean = keyStore().containsAlias(alias)

    override fun getOrCreate(
        alias: String,
        requireExisting: Boolean,
    ): SecretKey {
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

    override fun deleteEntry(alias: String) {
        keyStore().deleteEntry(alias)
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAPPING_KEY_BITS = 256
    }
}

internal object LocalVaultIdentityEncoding {
    fun identityBytes(vaultId: VaultId): ByteArray {
        val encodedSize = Math.multiplyExact(vaultId.value.length, BYTES_PER_CODE_UNIT)
        return ByteArray(encodedSize).also { encoded ->
            vaultId.value.forEachIndexed { index, codeUnit ->
                encoded[index * BYTES_PER_CODE_UNIT] = (codeUnit.code ushr 8).toByte()
                encoded[index * BYTES_PER_CODE_UNIT + 1] = codeUnit.code.toByte()
            }
        }
    }

    fun digest(vaultId: VaultId): String {
        val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
            .digest(identityBytes(vaultId))
        return CharArray(digest.size * 2).also { encoded ->
            digest.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xff
                encoded[index * 2] = HEX_DIGITS[value ushr 4]
                encoded[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
            }
        }.concatToString()
    }

    fun associatedData(vaultId: VaultId): ByteArray {
        val identity = identityBytes(vaultId)
        val result = ByteArray(
            ASSOCIATED_DATA_PREFIX.size + IDENTITY_LENGTH_BYTES + identity.size,
        )
        ASSOCIATED_DATA_PREFIX.copyInto(result)
        val lengthOffset = ASSOCIATED_DATA_PREFIX.size
        result[lengthOffset] = (identity.size ushr 24).toByte()
        result[lengthOffset + 1] = (identity.size ushr 16).toByte()
        result[lengthOffset + 2] = (identity.size ushr 8).toByte()
        result[lengthOffset + 3] = identity.size.toByte()
        identity.copyInto(result, destinationOffset = lengthOffset + IDENTITY_LENGTH_BYTES)
        return result
    }

    private const val BYTES_PER_CODE_UNIT = 2
    private const val IDENTITY_LENGTH_BYTES = 4
    private const val DIGEST_ALGORITHM = "SHA-256"
    private const val HEX_DIGITS = "0123456789abcdef"
    private val ASSOCIATED_DATA_PREFIX =
        "open-tasks:local-vault-content-key:v1".toByteArray(Charsets.UTF_8)
}

class AndroidVaultContentKeyStore internal constructor(
    context: Context,
    private val crypto: VaultCrypto,
    private val commitBoundary: PreferenceCommitBoundary,
    private val wrappingKeyBoundary: WrappingKeyBoundary,
) : VaultContentKeyStore {
    constructor(
        context: Context,
        crypto: VaultCrypto = TinkVaultCrypto(),
    ) : this(
        context = context,
        crypto = crypto,
        commitBoundary = SharedPreferencesCommitBoundary,
        wrappingKeyBoundary = AndroidKeystoreWrappingKeyBoundary(),
    )

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun getOrCreate(vaultId: VaultId): VaultKey = synchronized(PROCESS_LOCK) {
        val prior = readPreferenceState(vaultId)
        when (val stored = prior.toStoredEnvelope()) {
            StoredEnvelope.Absent -> createAndPersist(vaultId, prior)
            is StoredEnvelope.Complete -> unwrap(vaultId, stored)
        }
    }

    override fun replace(vaultId: VaultId, key: VaultKey) = synchronized(PROCESS_LOCK) {
        val prior = readPreferenceState(vaultId)
        val storedEnvelope = prior.toStoredEnvelope()
        val requireExistingAlias = storedEnvelope is StoredEnvelope.Complete
        val alias = aliasFor(vaultId)
        val aliasExisted = wrappingKeyBoundary.containsAlias(alias)
        try {
            persist(vaultId, wrap(vaultId, key, requireExistingAlias), prior)
        } catch (failure: Throwable) {
            if (!aliasExisted && !requireExistingAlias) {
                wrappingKeyBoundary.deleteEntry(alias)
            }
            throw failure
        }
    }

    override fun delete(vaultId: VaultId) = synchronized(PROCESS_LOCK) {
        val prior = readPreferenceState(vaultId)
        commitOrRollback(
            vaultId = vaultId,
            prior = prior,
            editor = preferences.edit()
                .remove(noncePreferenceKey(vaultId))
                .remove(ciphertextPreferenceKey(vaultId)),
            failureMessage = "Unable to remove the local vault-content key envelope",
        )
        wrappingKeyBoundary.deleteEntry(aliasFor(vaultId))
    }

    private fun createAndPersist(
        vaultId: VaultId,
        prior: PreferenceState,
    ): VaultKey {
        val alias = aliasFor(vaultId)
        val aliasExisted = wrappingKeyBoundary.containsAlias(alias)
        val key = crypto.createKey()
        try {
            persist(
                vaultId,
                wrap(vaultId, key, requireExistingAlias = false),
                prior,
            )
            return key
        } catch (failure: Throwable) {
            key.close()
            if (!aliasExisted) {
                wrappingKeyBoundary.deleteEntry(alias)
            }
            throw failure
        }
    }

    private fun readPreferenceState(vaultId: VaultId): PreferenceState {
        val nonceKey = noncePreferenceKey(vaultId)
        val ciphertextKey = ciphertextPreferenceKey(vaultId)
        return PreferenceState(
            noncePresent = preferences.contains(nonceKey),
            nonce = preferences.getString(nonceKey, null),
            ciphertextPresent = preferences.contains(ciphertextKey),
            ciphertext = preferences.getString(ciphertextKey, null),
        )
    }

    private fun PreferenceState.toStoredEnvelope(): StoredEnvelope {
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
                    wrappingKeyBoundary.getOrCreate(
                        aliasFor(vaultId),
                        requireExistingAlias,
                    ),
                )
                updateAAD(LocalVaultIdentityEncoding.associatedData(vaultId))
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
                wrappingKeyBoundary.getOrCreate(
                    aliasFor(vaultId),
                    requireExisting = true,
                ),
                GCMParameterSpec(GCM_TAG_BITS, stored.nonce),
            )
            updateAAD(LocalVaultIdentityEncoding.associatedData(vaultId))
            doFinal(stored.ciphertext)
        }
        return VaultKey(plaintext)
    }

    private fun persist(
        vaultId: VaultId,
        wrapped: WrappedKey,
        prior: PreferenceState,
    ) {
        commitOrRollback(
            vaultId = vaultId,
            prior = prior,
            editor = preferences.edit()
                .putString(noncePreferenceKey(vaultId), wrapped.nonce.encodeBase64())
                .putString(
                    ciphertextPreferenceKey(vaultId),
                    wrapped.ciphertext.encodeBase64(),
                ),
            failureMessage = "Unable to persist the local vault-content key envelope",
        )
    }

    private fun commitOrRollback(
        vaultId: VaultId,
        prior: PreferenceState,
        editor: SharedPreferences.Editor,
        failureMessage: String,
    ) {
        val failure = try {
            if (commitBoundary.commit(editor)) {
                null
            } else {
                IllegalStateException(failureMessage)
            }
        } catch (failure: Throwable) {
            failure
        }
        if (failure != null) {
            restorePreferenceState(vaultId, prior)
            throw failure
        }
    }

    private fun restorePreferenceState(
        vaultId: VaultId,
        prior: PreferenceState,
    ) {
        try {
            restorationEditor(vaultId, prior).apply()
        } catch (_: Throwable) {
            // Preserve the original failed mutation.
        }
        try {
            commitBoundary.commit(restorationEditor(vaultId, prior))
        } catch (_: Throwable) {
            // The synchronous retry may fail, but apply repaired the live map.
        }
    }

    private fun restorationEditor(
        vaultId: VaultId,
        prior: PreferenceState,
    ): SharedPreferences.Editor =
        preferences.edit().also { editor ->
            if (prior.noncePresent) {
                editor.putString(noncePreferenceKey(vaultId), prior.nonce)
            } else {
                editor.remove(noncePreferenceKey(vaultId))
            }
            if (prior.ciphertextPresent) {
                editor.putString(ciphertextPreferenceKey(vaultId), prior.ciphertext)
            } else {
                editor.remove(ciphertextPreferenceKey(vaultId))
            }
        }

    private fun aliasFor(vaultId: VaultId): String =
        "$KEYSTORE_ALIAS_PREFIX${LocalVaultIdentityEncoding.digest(vaultId)}"

    private fun noncePreferenceKey(vaultId: VaultId): String =
        "$NONCE_PREFERENCE_PREFIX${LocalVaultIdentityEncoding.digest(vaultId)}"

    private fun ciphertextPreferenceKey(vaultId: VaultId): String =
        "$CIPHERTEXT_PREFERENCE_PREFIX${LocalVaultIdentityEncoding.digest(vaultId)}"

    private fun ByteArray.encodeBase64(): String =
        Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decodeBase64(): ByteArray =
        try {
            Base64.decode(this, Base64.NO_WRAP).also { decoded ->
                check(Base64.encodeToString(decoded, Base64.NO_WRAP) == this) {
                    "The local vault-content key envelope is invalid"
                }
            }
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

    private data class PreferenceState(
        val noncePresent: Boolean,
        val nonce: String?,
        val ciphertextPresent: Boolean,
        val ciphertext: String?,
    )

    private companion object {
        const val PREFERENCES_NAME = "vault_content_keys_v1"
        const val KEYSTORE_ALIAS_PREFIX = "open_tasks_vault_content_wrapper_v1_"
        const val NONCE_PREFERENCE_PREFIX = "nonce_v1_"
        const val CIPHERTEXT_PREFERENCE_PREFIX = "ciphertext_v1_"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        val PROCESS_LOCK = Any()
    }
}
