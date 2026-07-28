package app.opentasks.core.crypto

import app.opentasks.core.model.VaultId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun createKeyGeneratesIndependentVaultContentKeys() {
        val first = crypto.createKey()
        val second = crypto.createKey()

        assertFalse(first.serializedKeyset.contentEquals(second.serializedKeyset))
        first.close()
        second.close()
    }

    @Test
    fun vaultContentKeyDiffersFromSqlCipherDatabaseKeyFixture() {
        val sqlCipherKeyFixture = ByteArray(32) { index -> (index + 1).toByte() }
        val contentKey = crypto.createKey()

        assertFalse(contentKey.serializedKeyset.contentEquals(sqlCipherKeyFixture))
        sqlCipherKeyFixture.fill(0)
        contentKey.close()
    }

    @Test
    fun recoveryEnvelopeWrapsTheCreatedContentKey() {
        val passphrase = "correct horse battery staple".toCharArray()
        val contentKey = crypto.createKey()
        val envelope = crypto.wrapForRecovery(contentKey, passphrase)
        val ciphertext = crypto.encryptRecord(
            contentKey,
            context,
            "private task body".toByteArray(),
        )

        val recovered = crypto.unlock(passphrase, envelope)

        assertArrayEquals(
            "private task body".toByteArray(),
            crypto.decryptRecord(recovered, context, ciphertext),
        )
        passphrase.fill('\u0000')
        contentKey.close()
        recovered.close()
    }

    @Test
    fun closedVaultKeyCannotEncryptRecords() {
        val contentKey = crypto.createKey()
        contentKey.close()

        assertThrows(IllegalStateException::class.java) {
            crypto.encryptRecord(contentKey, context, "secret".toByteArray())
        }
    }

    @Test
    fun closedVaultKeyCannotBeWrappedForRecovery() {
        val contentKey = crypto.createKey()
        contentKey.close()

        assertThrows(IllegalStateException::class.java) {
            crypto.wrapForRecovery(contentKey, "recovery phrase".toCharArray())
        }
    }

    @Test
    fun secondDeviceCanUnlockAndDecrypt() {
        val passphrase = "correct horse battery staple".toCharArray()
        val envelope = createEnvelope(passphrase)
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
        val key = crypto.createKey()
        val envelope = crypto.wrapForRecovery(key, "right passphrase".toCharArray())

        assertThrows(InvalidRecoveryPassphraseException::class.java) {
            crypto.unlock("wrong passphrase".toCharArray(), envelope)
        }
        key.close()
    }

    @Test
    fun swappedAssociatedDataIsRejected() {
        val passphrase = "recovery phrase".toCharArray()
        val envelope = createEnvelope(passphrase)
        val key = crypto.unlock(passphrase, envelope)
        val ciphertext = crypto.encryptRecord(key, context, "secret".toByteArray())
        val swapped = context.copy(objectId = "task-elsewhere")

        assertThrows(GeneralSecurityException::class.java) {
            crypto.decryptRecord(key, swapped, ciphertext)
        }
    }

    @Test
    fun associatedDataEncodingMatchesGoldenVector() {
        assertArrayEquals(
            "open-tasks\u0000vault-test\u0000task-123\u00001\u0000-1"
                .toByteArray(Charsets.UTF_8),
            context.associatedData(),
        )
        assertArrayEquals(
            "open-tasks\u0000vault-test\u0000task-123\u00001\u00007"
                .toByteArray(Charsets.UTF_8),
            context.copy(chunkIndex = 7).associatedData(),
        )
    }

    @Test
    fun argon2idDerivationMatchesGoldenVector() {
        val derived = Argon2idKdf.derive(
            passphrase = "correct horse battery staple".toCharArray(),
            metadata = Argon2Metadata(
                salt = ByteArray(16) { it.toByte() },
            ),
        )

        assertEquals(
            "0d1a3c6523c8f06e4e0af9c515aa5b5448cfebd6838f2d52c3d8b6ef8ddc3c2e",
            derived.toHex(),
        )
        derived.fill(0)
    }

    @Test
    fun tamperedRecoveryEnvelopeIsRejected() {
        val passphrase = "recovery phrase".toCharArray()
        val envelope = createEnvelope(passphrase)
        val tampered = envelope.copy(
            wrappedKeyset = envelope.wrappedKeyset.copyOf().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            },
        )

        assertThrows(InvalidRecoveryPassphraseException::class.java) {
            crypto.unlock(passphrase, tampered)
        }
    }

    @Test
    fun tamperedRecordCiphertextIsRejected() {
        val passphrase = "recovery phrase".toCharArray()
        val envelope = createEnvelope(passphrase)
        val key = crypto.unlock(passphrase, envelope)
        val ciphertext = crypto.encryptRecord(key, context, "secret".toByteArray())
        ciphertext[ciphertext.lastIndex] =
            (ciphertext.last().toInt() xor 0x01).toByte()

        assertThrows(GeneralSecurityException::class.java) {
            crypto.decryptRecord(key, context, ciphertext)
        }
        key.close()
    }

    @Test
    fun weakenedKdfMetadataIsRejected() {
        val envelope = createEnvelope("recovery phrase".toCharArray())
        val weakened = envelope.copy(
            kdf = envelope.kdf.copy(memoryKiB = 32_768),
        )

        assertThrows(IllegalArgumentException::class.java) {
            crypto.unlock("recovery phrase".toCharArray(), weakened)
        }
    }

    @Test
    fun closingVaultKeyErasesSerializedKeyMaterial() {
        val passphrase = "recovery phrase".toCharArray()
        val key = crypto.unlock(passphrase, createEnvelope(passphrase))
        assertTrue(key.serializedKeyset.any { it != 0.toByte() })

        key.close()

        assertTrue(key.serializedKeyset.all { it == 0.toByte() })
    }

    @Test
    fun passphraseChangeRewrapsSameDataKey() {
        val oldPassphrase = "old recovery phrase".toCharArray()
        val key = crypto.createKey()
        val oldEnvelope = crypto.wrapForRecovery(key, oldPassphrase)
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
        key.close()
        reopened.close()
    }

    private fun createEnvelope(passphrase: CharArray): VaultKeyEnvelope {
        val key = crypto.createKey()
        return try {
            crypto.wrapForRecovery(key, passphrase)
        } finally {
            key.close()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
