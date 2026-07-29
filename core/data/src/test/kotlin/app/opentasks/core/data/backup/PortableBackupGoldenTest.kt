package app.opentasks.core.data.backup

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PortableBackupGoldenTest {
    @Test
    fun independentPortableFixtureMatchesExactKotlinBytesAndCompleteVerification() {
        val fixture = loadFixture()
        val packageBytes = fixture.packageHex.hexToBytes()
        val manifestFrame = fixture.manifestFrameHex.hexToBytes()
        val snapshotFrame = fixture.snapshotFrameHex.hexToBytes()
        val snapshotBytes = fixture.snapshotJson.toByteArray()
        val envelopeBytes = fixture.bootstrapJson
            .substringAfter(""""recoveryEnvelope":""")
            .substringBefore(""","manifestFrameLength"""")
            .toByteArray()
        val snapshot = BackupSnapshotCodec.decode(snapshotBytes)
        val envelope = RecoveryEnvelopeCodec.decode(envelopeBytes)
        val crypto = DeterministicPortableFixtureCrypto(fixture)
        val codec = PortableBackupCodec(DefaultAuthenticatedCloudObjectCodec(crypto))
        val key = crypto.createKey()
        try {
            val encoded = codec.encode(
                recoveryEnvelope = envelope,
                snapshot = snapshot,
                producedAtEpochMillis = 1_754_000_000_000L,
                key = key,
            )
            try {
                assertArrayEquals(packageBytes, encoded)
                assertEquals(fixture.packageSha256, sha256(encoded))

                val headerLength = ByteBuffer.wrap(encoded, 0, 4).int
                assertEquals(
                    fixture.bootstrapJson,
                    encoded.copyOfRange(4, 4 + headerLength).toString(Charsets.UTF_8),
                )
                val header = codec.readBootstrap(
                    ByteArrayInputStream(encoded),
                    encoded.size.toLong(),
                )
                val manifestStart = 4 + headerLength
                val snapshotStart = manifestStart + header.manifestFrameLength.toInt()
                assertArrayEquals(
                    manifestFrame,
                    encoded.copyOfRange(manifestStart, snapshotStart),
                )
                assertArrayEquals(
                    snapshotFrame,
                    encoded.copyOfRange(snapshotStart, encoded.size),
                )
                assertEquals(fixture.manifestFrameSha256, sha256(manifestFrame))
                assertEquals(fixture.snapshotFrameSha256, sha256(snapshotFrame))
                assertEquals(fixture.recoveryEnvelopeSha256, sha256(envelopeBytes))

                val verified = codec.verifyComplete(
                    ByteArrayInputStream(encoded),
                    encoded.size.toLong(),
                    key,
                )
                assertEquals("vault-alpha", verified.vaultId)
                assertEquals(53L, verified.generation)
                assertEquals(1_754_000_000_000L, verified.producedAtEpochMillis)
                assertEquals(encoded.size.toLong(), verified.totalPackageLength)
                assertEquals(
                    fixture.recoveryEnvelopeSha256,
                    verified.recoveryEnvelopeSha256,
                )
            } finally {
                encoded.fill(0)
            }
        } finally {
            key.close()
            envelope.kdf.salt.fill(0)
            envelope.nonce.fill(0)
            envelope.wrappedKeyset.fill(0)
            packageBytes.fill(0)
            manifestFrame.fill(0)
            snapshotFrame.fill(0)
            snapshotBytes.fill(0)
            envelopeBytes.fill(0)
        }
    }

    private fun loadFixture(): PortableFixture {
        val path = "/backup-format/v1/portable-package.json"
        val text = requireNotNull(javaClass.getResourceAsStream(path)) {
            "Missing portable fixture $path"
        }.bufferedReader().use { it.readText() }
        return Json.decodeFromString(text)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }
}

@Serializable
private data class PortableFixture(
    val keyHex: String,
    val manifestNonceHex: String,
    val snapshotNonceHex: String,
    val bootstrapJson: String,
    val manifestJson: String,
    val snapshotJson: String,
    val recoveryEnvelopeSha256: String,
    val manifestFrameSha256: String,
    val snapshotFrameSha256: String,
    val packageSha256: String,
    val manifestFrameHex: String,
    val snapshotFrameHex: String,
    val packageHex: String,
)

private class DeterministicPortableFixtureCrypto(
    private val fixture: PortableFixture,
    private val delegate: VaultCrypto = TinkVaultCrypto(),
) : VaultCrypto by delegate {
    override fun encryptBytes(
        key: VaultKey,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val rawKey = fixture.keyHex.hexToBytes()
        val nonce = nonceFor(associatedData)
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
        require(ciphertext.size > TINK_PREFIX.size + NONCE_BYTES + TAG_BYTES)
        require(ciphertext.copyOfRange(0, TINK_PREFIX.size).contentEquals(TINK_PREFIX))
        val rawKey = fixture.keyHex.hexToBytes()
        val nonce = ciphertext.copyOfRange(TINK_PREFIX.size, TINK_PREFIX.size + NONCE_BYTES)
        val expectedNonce = nonceFor(associatedData)
        try {
            require(nonce.contentEquals(expectedNonce))
            return Cipher.getInstance(AES_GCM).run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(rawKey, AES),
                    GCMParameterSpec(GCM_TAG_BITS, nonce),
                )
                updateAAD(associatedData)
                doFinal(ciphertext, TINK_PREFIX.size + NONCE_BYTES, ciphertext.size -
                    TINK_PREFIX.size - NONCE_BYTES)
            }
        } finally {
            expectedNonce.fill(0)
            nonce.fill(0)
            rawKey.fill(0)
        }
    }

    private fun nonceFor(associatedData: ByteArray): ByteArray {
        val text = associatedData.toString(Charsets.ISO_8859_1)
        return if (text.contains("MANIFEST")) {
            fixture.manifestNonceHex.hexToBytes()
        } else {
            fixture.snapshotNonceHex.hexToBytes()
        }
    }

    private companion object {
        const val AES = "AES"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        val TINK_PREFIX = "010000002a".hexToBytes()
    }
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
