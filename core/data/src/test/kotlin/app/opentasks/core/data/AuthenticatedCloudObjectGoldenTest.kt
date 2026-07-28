package app.opentasks.core.data

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudHeaderIdentityEncoding
import app.opentasks.core.sync.CloudObjectFamily
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AuthenticatedCloudObjectGoldenTest {
    @Test
    fun independentFixturesMatchCanonicalAssociatedDataAndAuthenticatedFrames() {
        fixtures().forEach { fixture ->
            assertFixtureHexFields(fixture)
            val associatedData =
                CloudHeaderIdentityEncoding.associatedData(fixture.identity)
            try {
                assertEquals(fixture.associatedDataHex, associatedData.toHex())
            } finally {
                associatedData.fill(0)
            }
            assertCanonicalHeaderAndCiphertext(fixture)

            val crypto = FixtureVaultCrypto(fixture)
            val codec = DefaultAuthenticatedCloudObjectCodec(crypto)
            val key = crypto.createKey()
            val plaintext = fixture.plaintextHex.hexToByteArray()
            try {
                val encrypted = codec.encrypt(
                    fixture.identity,
                    plaintext,
                    key,
                )
                try {
                    assertEquals(fixture.frameHex, encrypted.toHex())
                } finally {
                    encrypted.fill(0)
                }

                val frame = fixture.frameHex.hexToByteArray()
                try {
                    val decoded = codec.decrypt(
                        ByteArrayInputStream(frame),
                        frame.size.toLong(),
                        key,
                    ) as CloudDecodeResult.Success
                    decoded.value.use { value ->
                        assertEquals(fixture.identity, value.identity)
                        val decodedPlaintext = value.copyPlaintext()
                        try {
                            assertEquals(
                                fixture.plaintextHex,
                                decodedPlaintext.toHex(),
                            )
                        } finally {
                            decodedPlaintext.fill(0)
                        }
                    }
                } finally {
                    frame.fill(0)
                }
            } finally {
                plaintext.fill(0)
                key.close()
            }
        }
    }

    @Test
    fun fixtureCryptoRejectsPrefixNonceTagAndAssociatedDataMismatch() {
        val fixture = loadFixture("manifest")
        val crypto = FixtureVaultCrypto(fixture)
        val key = crypto.createKey()
        val plaintext = fixture.plaintextHex.hexToByteArray()
        val associatedData = fixture.associatedDataHex.hexToByteArray()
        val ciphertext = fixture.ciphertextHex.hexToByteArray()
        try {
            val decrypted =
                crypto.decryptBytes(key, ciphertext, associatedData)
            try {
                assertArrayEquals(plaintext, decrypted)
            } finally {
                decrypted.fill(0)
            }

            val wrongAssociatedData = associatedData.copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            try {
                assertThrows(GeneralSecurityException::class.java) {
                    crypto.encryptBytes(key, plaintext, wrongAssociatedData)
                }
                assertThrows(GeneralSecurityException::class.java) {
                    crypto.decryptBytes(key, ciphertext, wrongAssociatedData)
                }
            } finally {
                wrongAssociatedData.fill(0)
            }

            listOf(
                0,
                TINK_PREFIX_BYTES,
                ciphertext.lastIndex,
            ).forEach { mutationIndex ->
                val mutated = ciphertext.copyOf().also {
                    it[mutationIndex] =
                        (it[mutationIndex].toInt() xor 1).toByte()
                }
                try {
                    assertThrows(GeneralSecurityException::class.java) {
                        crypto.decryptBytes(key, mutated, associatedData)
                    }
                } finally {
                    mutated.fill(0)
                }
            }
        } finally {
            plaintext.fill(0)
            associatedData.fill(0)
            ciphertext.fill(0)
            key.close()
        }
    }

    private fun fixtures(): List<AuthenticatedFixture> = listOf(
        "manifest",
        "snapshot",
        "operation-segment",
        "attachment-chunk",
    ).map(::loadFixture)

    private fun loadFixture(name: String): AuthenticatedFixture {
        val path = "/cloud-format/v1-authenticated/$name.json"
        val text = requireNotNull(javaClass.getResourceAsStream(path)) {
            "Missing authenticated cloud fixture $path"
        }.bufferedReader().use { it.readText() }
        return JSON.decodeFromString(text)
    }

    private fun assertCanonicalHeaderAndCiphertext(
        fixture: AuthenticatedFixture,
    ) {
        val frame = fixture.frameHex.hexToByteArray()
        try {
            val headerLength =
                ByteBuffer.wrap(frame, 0, Integer.BYTES).int
            val headerEnd = Integer.BYTES + headerLength
            assertTrue(headerLength > 0)
            assertTrue(headerEnd <= frame.size)
            assertEquals(
                fixture.headerJson,
                frame.copyOfRange(Integer.BYTES, headerEnd)
                    .toString(Charsets.UTF_8),
            )
            assertEquals(
                fixture.ciphertextHex,
                frame.copyOfRange(headerEnd, frame.size).toHex(),
            )
        } finally {
            frame.fill(0)
        }
    }

    private fun assertFixtureHexFields(fixture: AuthenticatedFixture) {
        listOf(
            fixture.keyHex,
            fixture.nonceHex,
            fixture.associatedDataHex,
            fixture.plaintextHex,
            fixture.ciphertextHex,
            fixture.frameHex,
        ).forEach { value ->
            assertTrue(LOWERCASE_HEX.matches(value))
        }
    }

    private companion object {
        const val TINK_PREFIX_BYTES = 5
        val JSON = Json
        val LOWERCASE_HEX = Regex("[0-9a-f]+")
    }
}

@Serializable
private data class AuthenticatedFixture(
    val family: String,
    val schemaVersion: Int,
    val cryptoVersion: Int,
    val minimumReaderVersion: Int,
    val vaultId: String,
    val objectId: String,
    val chunkIndex: Int?,
    val chunkCount: Int?,
    val keyHex: String,
    val nonceHex: String,
    val associatedDataHex: String,
    val plaintextHex: String,
    val ciphertextHex: String,
    val headerJson: String,
    val frameHex: String,
) {
    val identity: CloudHeaderIdentity
        get() = CloudHeaderIdentity(
            family = CloudObjectFamily.valueOf(family),
            schemaVersion = schemaVersion,
            cryptoVersion = cryptoVersion,
            minimumReaderVersion = minimumReaderVersion,
            vaultId = vaultId,
            objectId = objectId,
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
        )
}

private class FixtureVaultCrypto(
    private val fixture: AuthenticatedFixture,
    private val delegate: VaultCrypto = TinkVaultCrypto(),
) : VaultCrypto {
    override fun createKey(): VaultKey = delegate.createKey()

    override fun wrapForRecovery(
        unlockedKey: VaultKey,
        passphrase: CharArray,
    ): VaultKeyEnvelope =
        delegate.wrapForRecovery(unlockedKey, passphrase)

    override fun unlock(
        passphrase: CharArray,
        envelope: VaultKeyEnvelope,
    ): VaultKey = delegate.unlock(passphrase, envelope)

    override fun changePassphrase(
        unlockedKey: VaultKey,
        newPassphrase: CharArray,
    ): VaultKeyEnvelope =
        delegate.changePassphrase(unlockedKey, newPassphrase)

    override fun encryptBytes(
        key: VaultKey,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        verifyAssociatedData(associatedData)
        val rawKey = fixture.keyHex.hexToByteArray()
        val nonce = fixture.nonceHex.hexToByteArray()
        return try {
            val encrypted = Cipher.getInstance(AES_GCM).run {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(rawKey, AES),
                    GCMParameterSpec(GCM_TAG_BITS, nonce),
                )
                updateAAD(associatedData)
                doFinal(plaintext)
            }
            TINK_PREFIX + nonce + encrypted
        } finally {
            rawKey.fill(0)
            nonce.fill(0)
        }
    }

    override fun decryptBytes(
        key: VaultKey,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        verifyAssociatedData(associatedData)
        if (ciphertext.size < TINK_PREFIX.size + GCM_NONCE_BYTES + GCM_TAG_BYTES) {
            throw GeneralSecurityException("Fixture ciphertext is too short")
        }
        if (!ciphertext.matches(TINK_PREFIX, offset = 0)) {
            throw GeneralSecurityException("Fixture Tink prefix mismatch")
        }

        val rawKey = fixture.keyHex.hexToByteArray()
        val nonce = fixture.nonceHex.hexToByteArray()
        val payloadOffset = TINK_PREFIX.size + nonce.size
        if (!ciphertext.matches(nonce, offset = TINK_PREFIX.size)) {
            rawKey.fill(0)
            nonce.fill(0)
            throw GeneralSecurityException("Fixture nonce mismatch")
        }
        val encrypted = ciphertext.copyOfRange(
            payloadOffset,
            ciphertext.size,
        )
        return try {
            Cipher.getInstance(AES_GCM).run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(rawKey, AES),
                    GCMParameterSpec(GCM_TAG_BITS, nonce),
                )
                updateAAD(associatedData)
                doFinal(encrypted)
            }
        } finally {
            rawKey.fill(0)
            nonce.fill(0)
            encrypted.fill(0)
        }
    }

    private fun verifyAssociatedData(associatedData: ByteArray) {
        val expected = fixture.associatedDataHex.hexToByteArray()
        try {
            if (!expected.contentEquals(associatedData)) {
                throw GeneralSecurityException(
                    "Fixture associated data mismatch",
                )
            }
        } finally {
            expected.fill(0)
        }
    }

    private fun ByteArray.matches(
        expected: ByteArray,
        offset: Int,
    ): Boolean =
        expected.indices.all { index ->
            this[offset + index] == expected[index]
        }

    private companion object {
        const val AES = "AES"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / Byte.SIZE_BITS
        const val GCM_NONCE_BYTES = 12
        val TINK_PREFIX = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x2a)
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { "%02x".format(it) }
