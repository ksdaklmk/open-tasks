package app.opentasks.core.crypto

import app.opentasks.core.model.VaultId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun genericAeadRoundTripsWithCallerAssociatedData() {
        val key = crypto.createKey()
        val associatedData = "independent-context".toByteArray()
        val originalAssociatedData = associatedData.copyOf()
        val plaintext = "private payload".toByteArray()
        val originalPlaintext = plaintext.copyOf()

        val ciphertext = crypto.encryptBytes(key, plaintext, associatedData)

        assertArrayEquals(originalPlaintext, plaintext)
        assertArrayEquals(originalAssociatedData, associatedData)

        val decoded = crypto.decryptBytes(key, ciphertext, associatedData)

        assertArrayEquals(originalPlaintext, decoded)
        plaintext.fill(0)
        originalPlaintext.fill(0)
        decoded.fill(0)
        associatedData.fill(0)
        originalAssociatedData.fill(0)
        ciphertext.fill(0)
        key.close()
    }

    @Test
    fun genericAeadRejectsChangedAssociatedData() {
        val key = crypto.createKey()
        val ciphertext = crypto.encryptBytes(
            key,
            "private payload".toByteArray(),
            "context-a".toByteArray(),
        )

        assertThrows(GeneralSecurityException::class.java) {
            crypto.decryptBytes(
                key,
                ciphertext,
                "context-b".toByteArray(),
            )
        }
        ciphertext.fill(0)
        key.close()
    }

    @Test
    fun closedVaultKeyCannotEncryptBytes() {
        val key = crypto.createKey()
        key.close()

        assertThrows(IllegalStateException::class.java) {
            crypto.encryptBytes(
                key,
                "private payload".toByteArray(),
                "independent-context".toByteArray(),
            )
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

    @Suppress("DEPRECATION")
    @Test
    fun deprecatedCreateVaultClosesItsTemporaryKeyAfterSuccess() {
        val trackingCrypto = TrackingVaultCrypto()

        trackingCrypto.createVault("recovery phrase".toCharArray())

        assertTrue(trackingCrypto.createdKey.serializedKeyset.all { it == 0.toByte() })
    }

    @Suppress("DEPRECATION")
    @Test
    fun deprecatedCreateVaultClosesItsTemporaryKeyAfterWrapFailure() {
        val expected = ExpectedWrapFailure()
        val trackingCrypto = TrackingVaultCrypto(expected)

        val failure = assertThrows(ExpectedWrapFailure::class.java) {
            trackingCrypto.createVault("recovery phrase".toCharArray())
        }

        assertSame(expected, failure)
        assertTrue(trackingCrypto.createdKey.serializedKeyset.all { it == 0.toByte() })
    }

    @Test
    fun localVaultIdentityEncodingPreservesExactUtf16CodeUnits() {
        val malformedHigh = VaultId("\uD800")
        val malformedLow = VaultId("\uDC00")

        assertArrayEquals(
            byteArrayOf(0xd8.toByte(), 0x00),
            LocalVaultIdentityEncoding.identityBytes(malformedHigh),
        )
        assertArrayEquals(
            byteArrayOf(0xdc.toByte(), 0x00),
            LocalVaultIdentityEncoding.identityBytes(malformedLow),
        )
        assertEquals(
            "6e6535d29be7bfac2971dc0853620d739dd43a62c41409d21d39ccb9b29e224b",
            LocalVaultIdentityEncoding.digest(malformedHigh),
        )
        assertEquals(
            "34739425d55f591d570b36e6354822dbccd6453a78cbb9a61c05521248206762",
            LocalVaultIdentityEncoding.digest(malformedLow),
        )
        assertFalse(
            LocalVaultIdentityEncoding.digest(malformedHigh) ==
                LocalVaultIdentityEncoding.digest(malformedLow),
        )
    }

    @Test
    fun localVaultAssociatedDataLengthPrefixesExactIdentityBytes() {
        val vaultId = VaultId("A\uD83D\uDD10")
        val prefix = "open-tasks:local-vault-content-key:v1".toByteArray(Charsets.UTF_8)
        val expected = prefix + byteArrayOf(
            0x00,
            0x00,
            0x00,
            0x06,
            0x00,
            0x41,
            0xd8.toByte(),
            0x3d,
            0xdd.toByte(),
            0x10,
        )

        assertArrayEquals(expected, LocalVaultIdentityEncoding.associatedData(vaultId))
        assertArrayEquals(
            LocalVaultIdentityEncoding.associatedData(vaultId),
            LocalVaultIdentityEncoding.associatedData(VaultId("A\uD83D\uDD10")),
        )
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

    private class TrackingVaultCrypto(
        private val wrapFailure: RuntimeException? = null,
    ) : VaultCrypto {
        lateinit var createdKey: VaultKey

        override fun createKey(): VaultKey =
            VaultKey(byteArrayOf(1, 2, 3, 4)).also { createdKey = it }

        override fun wrapForRecovery(
            unlockedKey: VaultKey,
            passphrase: CharArray,
        ): VaultKeyEnvelope {
            wrapFailure?.let { throw it }
            return VaultKeyEnvelope(
                formatVersion = 1,
                kdf = Argon2Metadata(salt = ByteArray(16)),
                nonce = ByteArray(12),
                wrappedKeyset = byteArrayOf(1),
            )
        }

        override fun unlock(
            passphrase: CharArray,
            envelope: VaultKeyEnvelope,
        ): VaultKey = error("Not used")

        override fun changePassphrase(
            unlockedKey: VaultKey,
            newPassphrase: CharArray,
        ): VaultKeyEnvelope = error("Not used")

        override fun encryptBytes(
            key: VaultKey,
            plaintext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray = error("Not used")

        override fun decryptBytes(
            key: VaultKey,
            ciphertext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray = error("Not used")
    }

    private class ExpectedWrapFailure : RuntimeException()
}
