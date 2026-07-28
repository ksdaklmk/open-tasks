package app.opentasks.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.model.VaultId
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class AndroidVaultContentKeyStoreInstrumentedTest {
    private lateinit var context: Context
    private val firstVault = VaultId("vault/one? \u0000 \u0e44\u0e17\u0e22")
    private val secondVault = VaultId("vault-two")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearTestState()
    }

    @After
    fun tearDown() {
        clearTestState()
    }

    @Test
    fun vaultsUseIsolatedAliasesAndDeletingOneDoesNotAffectTheOther() {
        val store = AndroidVaultContentKeyStore(context)
        val first = store.getOrCreate(firstVault)
        val second = store.getOrCreate(secondVault)

        assertFalse(first.serializedKeyset.contentEquals(second.serializedKeyset))
        assertNotNull(keyStore().getKey(aliasFor(firstVault), null))
        assertNotNull(keyStore().getKey(aliasFor(secondVault), null))

        store.delete(firstVault)

        assertNull(keyStore().getKey(aliasFor(firstVault), null))
        val reopened = AndroidVaultContentKeyStore(context).getOrCreate(secondVault)
        assertArrayEquals(
            second.serializedKeyset,
            reopened.serializedKeyset,
        )
        first.close()
        second.close()
        reopened.close()
    }

    @Test
    fun managerRecreationRecoversTheSameContentKey() {
        val first = AndroidVaultContentKeyStore(context).getOrCreate(firstVault)

        val reopened = AndroidVaultContentKeyStore(context).getOrCreate(firstVault)

        assertArrayEquals(first.serializedKeyset, reopened.serializedKeyset)
        first.close()
        reopened.close()
    }

    @Test
    fun replacePersistsTheSuppliedKeyWithoutClosingIt() {
        val crypto = TinkVaultCrypto()
        AndroidVaultContentKeyStore(context).getOrCreate(firstVault).close()
        val supplied = crypto.createKey()
        val ciphertext = crypto.encryptRecord(
            supplied,
            CryptoContext(firstVault, "record", 1),
            "secret".toByteArray(),
        )

        AndroidVaultContentKeyStore(context).replace(firstVault, supplied)
        val reopened = AndroidVaultContentKeyStore(context).getOrCreate(firstVault)

        assertArrayEquals(
            "secret".toByteArray(),
            crypto.decryptRecord(
                reopened,
                CryptoContext(firstVault, "record", 1),
                ciphertext,
            ),
        )
        assertTrue(supplied.serializedKeyset.any { byte -> byte != 0.toByte() })
        supplied.close()
        reopened.close()
    }

    @Test
    fun deleteRemovesEnvelopeAndAliasBeforeCreatingADistinctKey() {
        val store = AndroidVaultContentKeyStore(context)
        val original = store.getOrCreate(firstVault)
        val originalBytes = original.serializedKeyset.copyOf()

        store.delete(firstVault)

        val preferences = preferences()
        assertFalse(preferences.contains(nonceKey(firstVault)))
        assertFalse(preferences.contains(ciphertextKey(firstVault)))
        assertNull(keyStore().getKey(aliasFor(firstVault), null))

        val replacement = store.getOrCreate(firstVault)
        assertFalse(originalBytes.contentEquals(replacement.serializedKeyset))
        originalBytes.fill(0)
        original.close()
        replacement.close()
    }

    @Test
    fun partialStoredEnvelopeFailsClosed() {
        AndroidVaultContentKeyStore(context).getOrCreate(firstVault).close()
        assertTrue(preferences().edit().remove(nonceKey(firstVault)).commit())

        assertThrows(IllegalStateException::class.java) {
            AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        }
    }

    @Test
    fun invalidNonceAndTamperedCiphertextFailClosed() {
        AndroidVaultContentKeyStore(context).getOrCreate(firstVault).close()
        val preferences = preferences()
        assertTrue(
            preferences.edit()
                .putString(
                    nonceKey(firstVault),
                    Base64.encodeToString(ByteArray(11), Base64.NO_WRAP),
                )
                .commit(),
        )
        assertThrows(IllegalStateException::class.java) {
            AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        }

        clearTestState()
        AndroidVaultContentKeyStore(context).getOrCreate(firstVault).close()
        val ciphertext = checkNotNull(preferences().getString(ciphertextKey(firstVault), null))
        val tampered = Base64.decode(ciphertext, Base64.NO_WRAP).also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }
        assertTrue(
            preferences().edit()
                .putString(
                    ciphertextKey(firstVault),
                    Base64.encodeToString(tampered, Base64.NO_WRAP),
                )
                .commit(),
        )
        assertThrows(GeneralSecurityException::class.java) {
            AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        }
    }

    @Test
    fun lostAliasFailsClosedWithoutAffectingAnotherVault() {
        val store = AndroidVaultContentKeyStore(context)
        store.getOrCreate(firstVault).close()
        val unaffected = store.getOrCreate(secondVault)
        keyStore().deleteEntry(aliasFor(firstVault))

        val failure = assertThrows(IllegalStateException::class.java) {
            AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        }

        assertTrue(failure.message?.contains("unavailable") == true)
        val reopened = AndroidVaultContentKeyStore(context).getOrCreate(secondVault)
        assertArrayEquals(unaffected.serializedKeyset, reopened.serializedKeyset)
        unaffected.close()
        reopened.close()
    }

    @Test
    fun replacedKeystoreAliasFailsAuthenticationWithoutAffectingAnotherVault() {
        val store = AndroidVaultContentKeyStore(context)
        store.getOrCreate(firstVault).close()
        val unaffected = store.getOrCreate(secondVault)
        keyStore().deleteEntry(aliasFor(firstVault))
        createWrappingKey(aliasFor(firstVault))

        assertThrows(GeneralSecurityException::class.java) {
            AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        }
        val reopened = AndroidVaultContentKeyStore(context).getOrCreate(secondVault)
        assertArrayEquals(unaffected.serializedKeyset, reopened.serializedKeyset)
        unaffected.close()
        reopened.close()
    }

    private fun clearTestState() {
        context.deleteSharedPreferences(PREFERENCES_NAME)
        keyStore().deleteEntry(aliasFor(firstVault))
        keyStore().deleteEntry(aliasFor(secondVault))
    }

    private fun preferences() =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun aliasFor(vaultId: VaultId): String =
        "$ALIAS_PREFIX${vaultDigest(vaultId)}"

    private fun nonceKey(vaultId: VaultId): String =
        "$NONCE_PREFIX${vaultDigest(vaultId)}"

    private fun ciphertextKey(vaultId: VaultId): String =
        "$CIPHERTEXT_PREFIX${vaultDigest(vaultId)}"

    private fun vaultDigest(vaultId: VaultId): String =
        MessageDigest.getInstance("SHA-256")
            .digest(vaultId.value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }

    private fun createWrappingKey(alias: String) {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .setUnlockedDeviceRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val PREFERENCES_NAME = "vault_content_keys_v1"
        const val ALIAS_PREFIX = "open_tasks_vault_content_wrapper_v1_"
        const val NONCE_PREFIX = "nonce_v1_"
        const val CIPHERTEXT_PREFIX = "ciphertext_v1_"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
