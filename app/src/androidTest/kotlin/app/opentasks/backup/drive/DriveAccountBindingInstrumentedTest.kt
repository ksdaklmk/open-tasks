package app.opentasks.backup.drive

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriveAccountBindingInstrumentedTest {
    @Before
    fun setUp() {
        deleteAlias()
    }

    @After
    fun tearDown() {
        deleteAlias()
    }

    @Test
    fun digestIsThirtyTwoBytesAndDeterministicAcrossSeparateInstances() {
        val first = DriveAccountBinding().digest("permission-a")
        val second = DriveAccountBinding().digest("permission-a")

        assertEquals(32, first.size)
        assertArrayEquals(first, second)
    }

    @Test
    fun differentPermissionIdsProduceDifferentDigestsUnderTheSameInstalledKey() {
        val binding = DriveAccountBinding()

        val digestA = binding.digest("permission-a")
        val digestB = binding.digest("permission-b")

        assertFalse(digestA.contentEquals(digestB))
    }

    @Test
    fun theInstalledKeystoreKeyIsGeneratedUnderTheDocumentedAliasAndIsNotExportable() {
        DriveAccountBinding().digest("permission-a")

        val key = keyStore().getKey(DriveAccountBinding.KEYSTORE_ALIAS, null) as? SecretKey
        assertNotNull(key)
        assertNull(key?.encoded)
    }

    private fun deleteAlias() {
        keyStore().deleteEntry(DriveAccountBinding.KEYSTORE_ALIAS)
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
