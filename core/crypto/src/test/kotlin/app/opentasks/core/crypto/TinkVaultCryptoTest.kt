package app.opentasks.core.crypto

import app.opentasks.core.model.VaultId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class TinkVaultCryptoTest {
    private val crypto = TinkVaultCrypto()
    private val context = CryptoContext(
        vaultId = VaultId("vault-test"),
        objectId = "task-123",
        formatVersion = 1,
    )

    @Test
    fun secondDeviceCanUnlockAndDecrypt() {
        val passphrase = "correct horse battery staple".toCharArray()
        val envelope = crypto.createVault(passphrase)
        val firstDevice = crypto.unlock(passphrase, envelope)
        val ciphertext = crypto.encryptRecord(
            firstDevice,
            context,
            "private task body".toByteArray(),
        )

        val secondDevice = TinkVaultCrypto().unlock(passphrase, envelope)
        val plaintext = TinkVaultCrypto().decryptRecord(secondDevice, context, ciphertext)

        assertArrayEquals("private task body".toByteArray(), plaintext)
        passphrase.fill('\u0000')
        firstDevice.close()
        secondDevice.close()
    }

    @Test
    fun wrongPassphraseCannotUnlock() {
        val envelope = crypto.createVault("right passphrase".toCharArray())

        assertThrows(InvalidRecoveryPassphraseException::class.java) {
            crypto.unlock("wrong passphrase".toCharArray(), envelope)
        }
    }

    @Test
    fun swappedAssociatedDataIsRejected() {
        val passphrase = "recovery phrase".toCharArray()
        val envelope = crypto.createVault(passphrase)
        val key = crypto.unlock(passphrase, envelope)
        val ciphertext = crypto.encryptRecord(key, context, "secret".toByteArray())
        val swapped = context.copy(objectId = "task-elsewhere")

        assertThrows(GeneralSecurityException::class.java) {
            crypto.decryptRecord(key, swapped, ciphertext)
        }
    }

    @Test
    fun passphraseChangeRewrapsSameDataKey() {
        val oldPassphrase = "old recovery phrase".toCharArray()
        val oldEnvelope = crypto.createVault(oldPassphrase)
        val key = crypto.unlock(oldPassphrase, oldEnvelope)
        val ciphertext = crypto.encryptRecord(key, context, "stable key".toByteArray())

        val newEnvelope = crypto.changePassphrase(key, "new recovery phrase".toCharArray())
        val reopened = crypto.unlock("new recovery phrase".toCharArray(), newEnvelope)

        assertArrayEquals(
            "stable key".toByteArray(),
            crypto.decryptRecord(reopened, context, ciphertext),
        )
        assertFalse(oldEnvelope.wrappedKeyset.contentEquals(newEnvelope.wrappedKeyset))
        assertThrows(InvalidRecoveryPassphraseException::class.java) {
            crypto.unlock(oldPassphrase, newEnvelope)
        }
    }
}
