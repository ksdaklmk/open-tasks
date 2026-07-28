package app.opentasks.core.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.model.VaultId
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class AndroidVaultContentKeyStoreInstrumentedTest {
    private lateinit var context: Context
    private val firstVault = VaultId("vault/one? \u0000 \u0e44\u0e17\u0e22")
    private val secondVault = VaultId("vault-two")
    private val malformedHighVault = VaultId("\uD800")
    private val malformedLowVault = VaultId("\uDC00")

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

    @Test
    fun malformedUtf16VaultIdsUseDistinctAliasesAndContentKeys() {
        val store = AndroidVaultContentKeyStore(context)

        val high = store.getOrCreate(malformedHighVault)
        val low = store.getOrCreate(malformedLowVault)

        assertFalse(aliasFor(malformedHighVault) == aliasFor(malformedLowVault))
        assertNotNull(keyStore().getKey(aliasFor(malformedHighVault), null))
        assertNotNull(keyStore().getKey(aliasFor(malformedLowVault), null))
        assertFalse(high.serializedKeyset.contentEquals(low.serializedKeyset))
        high.close()
        low.close()
    }

    @Test
    fun failedReplaceRestoresPriorEnvelopeForCurrentAndNewManagers() {
        val original = AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        val originalBytes = original.serializedKeyset.copyOf()
        val replacement = TinkVaultCrypto().createKey()
        val failingStore = storeWith(
            commitBoundary = FailedCommitAfterMemoryMutation(),
            wrappingKeyBoundary = AndroidKeystoreWrappingKeyBoundary(),
        )

        assertThrows(IllegalStateException::class.java) {
            failingStore.replace(firstVault, replacement)
        }

        val currentManagerKey = failingStore.getOrCreate(firstVault)
        val newManagerKey = AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        assertArrayEquals(originalBytes, currentManagerKey.serializedKeyset)
        assertArrayEquals(originalBytes, newManagerKey.serializedKeyset)
        assertNotNull(keyStore().getKey(aliasFor(firstVault), null))
        originalBytes.fill(0)
        original.close()
        replacement.close()
        currentManagerKey.close()
        newManagerKey.close()
    }

    @Test
    fun failedDeleteKeepsPriorEnvelopeKeyAndAlias() {
        val original = AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        val originalBytes = original.serializedKeyset.copyOf()
        val failingStore = storeWith(
            commitBoundary = FailedCommitAfterMemoryMutation(),
            wrappingKeyBoundary = AndroidKeystoreWrappingKeyBoundary(),
        )

        assertThrows(IllegalStateException::class.java) {
            failingStore.delete(firstVault)
        }

        assertTrue(preferences().contains(nonceKey(firstVault)))
        assertTrue(preferences().contains(ciphertextKey(firstVault)))
        assertNotNull(keyStore().getKey(aliasFor(firstVault), null))
        val reopened = AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        assertArrayEquals(originalBytes, reopened.serializedKeyset)
        originalBytes.fill(0)
        original.close()
        reopened.close()
    }

    @Test
    fun failedCreateLeavesNoEnvelopeOrNewAliasAndReportsFailure() {
        val failingStore = storeWith(
            commitBoundary = FailedCommitAfterMemoryMutation(),
            wrappingKeyBoundary = AndroidKeystoreWrappingKeyBoundary(),
        )

        assertThrows(IllegalStateException::class.java) {
            failingStore.getOrCreate(firstVault)
        }

        assertFalse(preferences().contains(nonceKey(firstVault)))
        assertFalse(preferences().contains(ciphertextKey(firstVault)))
        assertNull(keyStore().getKey(aliasFor(firstVault), null))
    }

    @Test
    fun rollbackPreservesOriginalThrownCommitFailure() {
        val original = AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        val originalBytes = original.serializedKeyset.copyOf()
        val replacement = TinkVaultCrypto().createKey()
        val expected = ExpectedCommitFailure()
        val failingStore = storeWith(
            commitBoundary = FailedCommitAfterMemoryMutation(expected),
            wrappingKeyBoundary = AndroidKeystoreWrappingKeyBoundary(),
        )

        val failure = assertThrows(ExpectedCommitFailure::class.java) {
            failingStore.replace(firstVault, replacement)
        }

        assertSame(expected, failure)
        val reopened = AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        assertArrayEquals(originalBytes, reopened.serializedKeyset)
        originalBytes.fill(0)
        original.close()
        replacement.close()
        reopened.close()
    }

    @Test
    fun invalidBase64FailsClosed() {
        AndroidVaultContentKeyStore(context).getOrCreate(firstVault).close()
        assertTrue(
            preferences().edit()
                .putString(nonceKey(firstVault), "%")
                .commit(),
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        }

        assertTrue(failure.message?.contains("envelope is invalid") == true)
    }

    @Test
    fun bothPartialEnvelopeHalvesFailClosed() {
        AndroidVaultContentKeyStore(context).getOrCreate(firstVault).close()
        assertTrue(preferences().edit().remove(nonceKey(firstVault)).commit())

        assertThrows(IllegalStateException::class.java) {
            AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        }

        clearTestState()
        AndroidVaultContentKeyStore(context).getOrCreate(firstVault).close()
        assertTrue(preferences().edit().remove(ciphertextKey(firstVault)).commit())

        assertThrows(IllegalStateException::class.java) {
            AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        }
    }

    @Test
    fun emptyAndTruncatedCiphertextFailClosed() {
        AndroidVaultContentKeyStore(context).getOrCreate(firstVault).close()
        assertTrue(
            preferences().edit()
                .putString(
                    ciphertextKey(firstVault),
                    Base64.encodeToString(ByteArray(0), Base64.NO_WRAP),
                )
                .commit(),
        )
        assertThrows(GeneralSecurityException::class.java) {
            AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        }

        clearTestState()
        AndroidVaultContentKeyStore(context).getOrCreate(firstVault).close()
        assertTrue(
            preferences().edit()
                .putString(
                    ciphertextKey(firstVault),
                    Base64.encodeToString(ByteArray(15), Base64.NO_WRAP),
                )
                .commit(),
        )
        assertThrows(GeneralSecurityException::class.java) {
            AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
        }
    }

    @Test
    fun replacePersistsSuppliedKeyForAbsentVault() {
        val crypto = TinkVaultCrypto()
        val supplied = crypto.createKey()
        val ciphertext = crypto.encryptRecord(
            supplied,
            CryptoContext(firstVault, "record", 1),
            "absent replacement".toByteArray(),
        )

        AndroidVaultContentKeyStore(context).replace(firstVault, supplied)
        val reopened = AndroidVaultContentKeyStore(context).getOrCreate(firstVault)

        assertArrayEquals(
            "absent replacement".toByteArray(),
            crypto.decryptRecord(
                reopened,
                CryptoContext(firstVault, "record", 1),
                ciphertext,
            ),
        )
        supplied.close()
        reopened.close()
    }

    @Test
    fun crossVaultEnvelopeSwapIsRejectedWhenWrappingKeyIsShared() {
        val wrappingKeys = SharedWrappingKeyBoundary()
        val store = storeWith(
            commitBoundary = SharedPreferencesCommitBoundary,
            wrappingKeyBoundary = wrappingKeys,
        )
        store.getOrCreate(firstVault).close()
        store.getOrCreate(secondVault).close()
        val firstNonce = checkNotNull(preferences().getString(nonceKey(firstVault), null))
        val firstCiphertext =
            checkNotNull(preferences().getString(ciphertextKey(firstVault), null))
        assertTrue(
            preferences().edit()
                .putString(nonceKey(secondVault), firstNonce)
                .putString(ciphertextKey(secondVault), firstCiphertext)
                .commit(),
        )

        assertThrows(GeneralSecurityException::class.java) {
            store.getOrCreate(secondVault)
        }
    }

    @Test
    fun permanentlyInvalidatedWrappingKeyFailsClosed() {
        val wrappingKeys = SharedWrappingKeyBoundary()
        storeWith(
            commitBoundary = SharedPreferencesCommitBoundary,
            wrappingKeyBoundary = wrappingKeys,
        ).getOrCreate(firstVault).close()
        val invalidatedStore = storeWith(
            commitBoundary = SharedPreferencesCommitBoundary,
            wrappingKeyBoundary = PermanentlyInvalidatedWrappingKeyBoundary(),
        )

        assertThrows(KeyPermanentlyInvalidatedException::class.java) {
            invalidatedStore.getOrCreate(firstVault)
        }
    }

    @Test
    fun concurrentManagersReturnTheSameContentKey() {
        val start = CountDownLatch(1)
        val complete = CountDownLatch(CONCURRENT_MANAGER_COUNT)
        val keysets = Collections.synchronizedList(mutableListOf<ByteArray>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        repeat(CONCURRENT_MANAGER_COUNT) {
            Thread {
                try {
                    start.await()
                    val key = AndroidVaultContentKeyStore(context).getOrCreate(firstVault)
                    keysets += key.serializedKeyset.copyOf()
                    key.close()
                } catch (failure: Throwable) {
                    failures += failure
                } finally {
                    complete.countDown()
                }
            }.start()
        }

        start.countDown()

        assertTrue(complete.await(10, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty())
        assertEquals(CONCURRENT_MANAGER_COUNT, keysets.size)
        keysets.drop(1).forEach { keyset ->
            assertArrayEquals(keysets.first(), keyset)
        }
        keysets.forEach { it.fill(0) }
    }

    private fun clearTestState() {
        context.deleteSharedPreferences(PREFERENCES_NAME)
        keyStore().deleteEntry(aliasFor(firstVault))
        keyStore().deleteEntry(aliasFor(secondVault))
        keyStore().deleteEntry(aliasFor(malformedHighVault))
        keyStore().deleteEntry(aliasFor(malformedLowVault))
    }

    private fun storeWith(
        commitBoundary: PreferenceCommitBoundary,
        wrappingKeyBoundary: WrappingKeyBoundary,
    ): AndroidVaultContentKeyStore =
        AndroidVaultContentKeyStore(
            context,
            TinkVaultCrypto(),
            commitBoundary,
            wrappingKeyBoundary,
        )

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
            .digest(exactIdentityBytes(vaultId))
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }

    private fun exactIdentityBytes(vaultId: VaultId): ByteArray =
        ByteArray(vaultId.value.length * 2).also { encoded ->
            vaultId.value.forEachIndexed { index, codeUnit ->
                encoded[index * 2] = (codeUnit.code ushr 8).toByte()
                encoded[index * 2 + 1] = codeUnit.code.toByte()
            }
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

    private class FailedCommitAfterMemoryMutation(
        private val failure: RuntimeException? = null,
    ) : PreferenceCommitBoundary {
        override fun commit(editor: SharedPreferences.Editor): Boolean {
            editor.commit()
            failure?.let { throw it }
            return false
        }
    }

    private class SharedWrappingKeyBoundary : WrappingKeyBoundary {
        private val aliases = mutableSetOf<String>()
        private val key = SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")

        override fun containsAlias(alias: String): Boolean = alias in aliases

        override fun getOrCreate(
            alias: String,
            requireExisting: Boolean,
        ): SecretKey {
            check(!requireExisting || alias in aliases)
            aliases += alias
            return key
        }

        override fun deleteEntry(alias: String) {
            aliases -= alias
        }
    }

    private class PermanentlyInvalidatedWrappingKeyBoundary : WrappingKeyBoundary {
        override fun containsAlias(alias: String): Boolean = true

        override fun getOrCreate(
            alias: String,
            requireExisting: Boolean,
        ): SecretKey = throw KeyPermanentlyInvalidatedException()

        override fun deleteEntry(alias: String) = Unit
    }

    private class ExpectedCommitFailure : RuntimeException()

    private companion object {
        const val CONCURRENT_MANAGER_COUNT = 8
        const val PREFERENCES_NAME = "vault_content_keys_v1"
        const val ALIAS_PREFIX = "open_tasks_vault_content_wrapper_v1_"
        const val NONCE_PREFIX = "nonce_v1_"
        const val CIPHERTEXT_PREFIX = "ciphertext_v1_"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
