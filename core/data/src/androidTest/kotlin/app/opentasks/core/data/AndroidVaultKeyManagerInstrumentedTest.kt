package app.opentasks.core.data

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class AndroidVaultKeyManagerInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearTestKeyState()
    }

    @After
    fun tearDown() {
        clearTestKeyState()
    }

    @Test
    fun databaseKeyAndDeviceIdSurviveManagerRecreation() {
        val first = AndroidVaultKeyManager(context, FixedSecureRandom())
        val databaseKey = first.getOrCreateDatabaseKey()
        val deviceId = first.getOrCreateDeviceId()

        val reopened = AndroidVaultKeyManager(context)

        assertArrayEquals(databaseKey, reopened.getOrCreateDatabaseKey())
        assertEquals(deviceId, reopened.getOrCreateDeviceId())
        databaseKey.fill(0)
    }

    @Test
    fun missingKeystoreKeyFailsClosedInsteadOfReplacingTheVaultKey() {
        AndroidVaultKeyManager(context, FixedSecureRandom()).getOrCreateDatabaseKey().fill(0)
        keyStore().deleteEntry(KEYSTORE_ALIAS)

        val failure = runCatching {
            AndroidVaultKeyManager(context).getOrCreateDatabaseKey()
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("unavailable") == true)
    }

    @Test
    fun tamperedStoredEnvelopeCannotBeUnwrapped() {
        AndroidVaultKeyManager(context, FixedSecureRandom()).getOrCreateDatabaseKey().fill(0)
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val ciphertext = checkNotNull(preferences.getString(DATABASE_KEY_CIPHERTEXT, null))
        val bytes = Base64.decode(ciphertext, Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        assertTrue(
            preferences.edit()
                .putString(
                    DATABASE_KEY_CIPHERTEXT,
                    Base64.encodeToString(bytes, Base64.NO_WRAP),
                )
                .commit(),
        )

        val failure = runCatching {
            AndroidVaultKeyManager(context).getOrCreateDatabaseKey()
        }.exceptionOrNull()

        assertTrue(failure != null)
    }

    private fun clearTestKeyState() {
        context.deleteSharedPreferences(PREFERENCES_NAME)
        keyStore().deleteEntry(KEYSTORE_ALIAS)
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private class FixedSecureRandom : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { index -> bytes[index] = (index + 1).toByte() }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "vault_keys"
        const val DATABASE_KEY_CIPHERTEXT = "database_key_ciphertext_v1"
        const val KEYSTORE_ALIAS = "open_tasks_local_vault_wrapper_v1"
    }
}
