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

internal interface LocalEnvelopeBase64Boundary {
    fun encode(bytes: ByteArray): String

    fun decode(encoded: String): ByteArray
}

internal data object AndroidLocalEnvelopeBase64Boundary : LocalEnvelopeBase64Boundary {
    override fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    override fun decode(encoded: String): ByteArray =
        Base64.decode(encoded, Base64.NO_WRAP)
}

internal fun decodeCanonicalLocalEnvelopeBase64(
    encoded: String,
    boundary: LocalEnvelopeBase64Boundary,
): ByteArray {
    val decoded = try {
        boundary.decode(encoded)
    } catch (failure: IllegalArgumentException) {
        throw IllegalStateException(
            "The local vault-content key envelope is invalid",
            failure,
        )
    }
    return try {
        check(boundary.encode(decoded) == encoded) {
            "The local vault-content key envelope is invalid"
        }
        decoded
    } catch (failure: Throwable) {
        decoded.fill(0)
        if (failure is IllegalArgumentException) {
            throw IllegalStateException(
                "The local vault-content key envelope is invalid",
                failure,
            )
        }
        throw failure
    }
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
        val identity = identityBytes(vaultId)
        val digest = try {
            MessageDigest.getInstance(DIGEST_ALGORITHM).digest(identity)
        } finally {
            identity.fill(0)
        }
        return try {
            CharArray(digest.size * 2).also { encoded ->
                digest.forEachIndexed { index, byte ->
                    val value = byte.toInt() and 0xff
                    encoded[index * 2] = HEX_DIGITS[value ushr 4]
                    encoded[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
                }
            }.concatToString()
        } finally {
            digest.fill(0)
        }
    }

    fun associatedData(vaultId: VaultId, storageNamespace: String? = null): ByteArray {
        val prefix = when (storageNamespace) {
            null -> ASSOCIATED_DATA_PREFIX
            else -> "$ASSOCIATED_DATA_TEXT:$storageNamespace".toByteArray(Charsets.UTF_8)
        }
        val identity = identityBytes(vaultId)
        val result = ByteArray(
            prefix.size + IDENTITY_LENGTH_BYTES + identity.size,
        )
        prefix.copyInto(result)
        val lengthOffset = prefix.size
        result[lengthOffset] = (identity.size ushr 24).toByte()
        result[lengthOffset + 1] = (identity.size ushr 16).toByte()
        result[lengthOffset + 2] = (identity.size ushr 8).toByte()
        result[lengthOffset + 3] = identity.size.toByte()
        identity.copyInto(result, destinationOffset = lengthOffset + IDENTITY_LENGTH_BYTES)
        identity.fill(0)
        return result
    }

    private const val BYTES_PER_CODE_UNIT = 2
    private const val IDENTITY_LENGTH_BYTES = 4
    private const val DIGEST_ALGORITHM = "SHA-256"
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val ASSOCIATED_DATA_TEXT = "open-tasks:local-vault-content-key:v1"
    private val ASSOCIATED_DATA_PREFIX = ASSOCIATED_DATA_TEXT.toByteArray(Charsets.UTF_8)
}

/**
 * Removes the local storage of one content-key namespace.
 *
 * A staged slot owns its own preference file and Keystore aliases, so
 * discarding it can never touch another slot's wrappers.
 */
object AndroidVaultContentKeyStorage {
    fun deleteNamespace(context: Context, storageNamespace: String) {
        require(storageNamespace.isNotBlank()) {
            "A content-key namespace cannot be blank"
        }
        deleteStorage(
            context = context,
            preferencesName = "${PREFERENCES_NAME}_$storageNamespace",
            aliasPrefix = "$KEYSTORE_ALIAS_PREFIX${storageNamespace}_",
            legacy = false,
        )
    }

    fun deleteLegacyStorage(context: Context) {
        deleteStorage(
            context = context,
            preferencesName = PREFERENCES_NAME,
            aliasPrefix = KEYSTORE_ALIAS_PREFIX,
            legacy = true,
        )
    }

    private fun deleteStorage(
        context: Context,
        preferencesName: String,
        aliasPrefix: String,
        legacy: Boolean,
    ) {
        val applicationContext = context.applicationContext
        applicationContext.deleteSharedPreferences(preferencesName)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.aliases().toList()
            .filter { alias ->
                alias.startsWith(aliasPrefix) &&
                    // The legacy prefix is also the prefix of every namespaced
                    // alias, so only unnamespaced digests belong to it.
                    (!legacy || !alias.removePrefix(aliasPrefix).contains('_'))
            }
            .forEach(keyStore::deleteEntry)
    }

    private const val PREFERENCES_NAME = "vault_content_keys_v1"
    private const val KEYSTORE_ALIAS_PREFIX = "open_tasks_vault_content_wrapper_v1_"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
}

/**
 * Stores one vault-content key per vault inside one storage namespace.
 *
 * A `null` [storageNamespace] is the storage this application shipped with and
 * keeps its preference names, Keystore aliases, and associated data unchanged.
 * A staged slot passes only its SHA-256 digest, so the same logical vault held
 * in two slots keeps two independent wrappers.
 */
class AndroidVaultContentKeyStore internal constructor(
    context: Context,
    private val crypto: VaultCrypto,
    private val commitBoundary: PreferenceCommitBoundary,
    private val wrappingKeyBoundary: WrappingKeyBoundary,
    private val base64Boundary: LocalEnvelopeBase64Boundary =
        AndroidLocalEnvelopeBase64Boundary,
    private val storageNamespace: String? = null,
) : VaultContentKeyStore {
    constructor(
        context: Context,
        crypto: VaultCrypto = TinkVaultCrypto(),
        storageNamespace: String? = null,
    ) : this(
        context = context,
        crypto = crypto,
        commitBoundary = SharedPreferencesCommitBoundary,
        wrappingKeyBoundary = AndroidKeystoreWrappingKeyBoundary(),
        base64Boundary = AndroidLocalEnvelopeBase64Boundary,
        storageNamespace = storageNamespace,
    )

    init {
        require(storageNamespace == null || storageNamespace.isNotBlank()) {
            "A content-key namespace cannot be blank"
        }
    }

    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName(),
        Context.MODE_PRIVATE,
    )

    override fun getOrCreate(vaultId: VaultId): VaultKey = synchronized(PROCESS_LOCK) {
        val prior = readPreferenceState(vaultId)
        when (val stored = prior.toStoredEnvelope()) {
            StoredEnvelope.Absent -> createAndPersist(vaultId, prior)
            is StoredEnvelope.Complete -> unwrapAndClear(vaultId, stored)
        }
    }

    override fun openExisting(vaultId: VaultId): VaultKey =
        synchronized(PROCESS_LOCK) {
            when (val stored = readPreferenceState(vaultId).toStoredEnvelope()) {
                StoredEnvelope.Absent ->
                    error("The local vault-content key has not been initialised")
                is StoredEnvelope.Complete -> unwrapAndClear(vaultId, stored)
            }
        }

    override fun replace(vaultId: VaultId, key: VaultKey) = synchronized(PROCESS_LOCK) {
        val prior = readPreferenceState(vaultId)
        val storedEnvelope = prior.toStoredEnvelope()
        val requireExistingAlias = storedEnvelope is StoredEnvelope.Complete
        storedEnvelope.clear()
        val alias = aliasFor(vaultId)
        val aliasExisted = wrappingKeyBoundary.containsAlias(alias)
        try {
            persist(vaultId, wrap(vaultId, key, requireExistingAlias), prior)
        } catch (failure: Throwable) {
            if (!aliasExisted && !requireExistingAlias) {
                cleanupAliasAfterFailure(alias, failure)
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
        try {
            wrappingKeyBoundary.deleteEntry(aliasFor(vaultId))
        } catch (failure: Throwable) {
            restorePreferenceState(vaultId, prior)
            throw failure
        }
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
                cleanupAliasAfterFailure(alias, failure)
            }
            throw failure
        }
    }

    private fun cleanupAliasAfterFailure(
        alias: String,
        primaryFailure: Throwable,
    ) {
        try {
            wrappingKeyBoundary.deleteEntry(alias)
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure)
            }
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
        val decodedNonce = nonce.decodeBase64()
        return try {
            StoredEnvelope.Complete(
                nonce = decodedNonce,
                ciphertext = ciphertext.decodeBase64(),
            )
        } catch (failure: Throwable) {
            decodedNonce.fill(0)
            throw failure
        }
    }

    private fun wrap(
        vaultId: VaultId,
        key: VaultKey,
        requireExistingAlias: Boolean,
    ): WrappedKey {
        val plaintext = key.copySerializedKeyset()
        val associatedData = LocalVaultIdentityEncoding.associatedData(vaultId, storageNamespace)
        return try {
            val cipher = Cipher.getInstance(AES_GCM).apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    wrappingKeyBoundary.getOrCreate(
                        aliasFor(vaultId),
                        requireExistingAlias,
                    ),
                )
                updateAAD(associatedData)
            }
            WrappedKey(
                nonce = cipher.iv.copyOf(),
                ciphertext = cipher.doFinal(plaintext),
            )
        } finally {
            associatedData.fill(0)
            plaintext.fill(0)
        }
    }

    private fun unwrapAndClear(
        vaultId: VaultId,
        stored: StoredEnvelope.Complete,
    ): VaultKey =
        try {
            unwrap(vaultId, stored)
        } finally {
            stored.clear()
        }

    private fun unwrap(
        vaultId: VaultId,
        stored: StoredEnvelope.Complete,
    ): VaultKey {
        check(stored.nonce.size == GCM_NONCE_BYTES) {
            "The local vault-content key nonce is invalid"
        }
        val associatedData = LocalVaultIdentityEncoding.associatedData(vaultId, storageNamespace)
        val plaintext = try {
            Cipher.getInstance(AES_GCM).run {
                init(
                    Cipher.DECRYPT_MODE,
                    wrappingKeyBoundary.getOrCreate(
                        aliasFor(vaultId),
                        requireExisting = true,
                    ),
                    GCMParameterSpec(GCM_TAG_BITS, stored.nonce),
                )
                updateAAD(associatedData)
                doFinal(stored.ciphertext)
            }
        } finally {
            associatedData.fill(0)
        }
        return VaultKey(plaintext)
    }

    private fun persist(
        vaultId: VaultId,
        wrapped: WrappedKey,
        prior: PreferenceState,
    ) {
        try {
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
        } finally {
            wrapped.nonce.fill(0)
            wrapped.ciphertext.fill(0)
        }
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

    private fun preferencesName(): String = when (storageNamespace) {
        null -> PREFERENCES_NAME
        else -> "${PREFERENCES_NAME}_$storageNamespace"
    }

    private fun aliasFor(vaultId: VaultId): String {
        val prefix = when (storageNamespace) {
            null -> KEYSTORE_ALIAS_PREFIX
            else -> "$KEYSTORE_ALIAS_PREFIX${storageNamespace}_"
        }
        return "$prefix${LocalVaultIdentityEncoding.digest(vaultId)}"
    }

    private fun noncePreferenceKey(vaultId: VaultId): String =
        "$NONCE_PREFERENCE_PREFIX${LocalVaultIdentityEncoding.digest(vaultId)}"

    private fun ciphertextPreferenceKey(vaultId: VaultId): String =
        "$CIPHERTEXT_PREFERENCE_PREFIX${LocalVaultIdentityEncoding.digest(vaultId)}"

    private fun ByteArray.encodeBase64(): String =
        base64Boundary.encode(this)

    private fun String.decodeBase64(): ByteArray =
        decodeCanonicalLocalEnvelopeBase64(this, base64Boundary)

    private sealed interface StoredEnvelope {
        data object Absent : StoredEnvelope

        data class Complete(
            val nonce: ByteArray,
            val ciphertext: ByteArray,
        ) : StoredEnvelope
    }

    private fun StoredEnvelope.clear() {
        if (this is StoredEnvelope.Complete) {
            nonce.fill(0)
            ciphertext.fill(0)
        }
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
