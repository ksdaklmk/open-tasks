package app.opentasks.core.data.backup

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.domain.AttachmentBlobSetManifest
import app.opentasks.core.domain.AttachmentChunkRef
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.Sha256Digest
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentGoldenTest {
    @Test
    fun independentNodeFixtureMatchesCanonicalManifestFrameAndDigest() {
        val fixture = loadFixture()
        val crypto = FixtureAttachmentCrypto(fixture)
        val key = crypto.createKey()
        val codec = AttachmentBlobSetManifestCodec(DefaultAuthenticatedCloudObjectCodec(crypto))
        val manifest = fixture.manifest.toDomain()
        try {
            val encoded = codec.encode(manifest, CloudLineageId.parse(fixture.lineageId), key)

            assertEquals(fixture.frameHex, encoded.toHex())
            assertEquals(fixture.frameSha256, sha256(encoded))
            assertArrayEquals(
                fixture.payloadJson.toByteArray(Charsets.UTF_8),
                fixture.plaintextHex.hexToByteArray(),
            )
            assertEquals(
                manifest,
                codec.decode(
                    fixture.frameHex.hexToByteArray(),
                    CloudLineageId.parse(fixture.lineageId),
                    BlobSetId(fixture.manifest.blobSetId),
                    key,
                ),
            )
        } finally {
            key.close()
        }
    }

    private fun loadFixture(): AttachmentFixture {
        val path = "/backup-format/attachment-v1/blob-set-manifest.json"
        val text = requireNotNull(javaClass.getResourceAsStream(path)) {
            "Missing attachment fixture $path"
        }.bufferedReader().use { it.readText() }
        return Json.decodeFromString(text)
    }
}

@Serializable
private data class AttachmentFixture(
    val lineageId: String,
    val keyHex: String,
    val nonceHex: String,
    val associatedDataHex: String,
    val payloadJson: String,
    val plaintextHex: String,
    val ciphertextHex: String,
    val headerJson: String,
    val frameHex: String,
    val frameSha256: String,
    val manifest: AttachmentFixtureManifest,
)

@Serializable
private data class AttachmentFixtureManifest(
    val blobSetId: String,
    val contentSha256: String,
    val totalByteCount: Long,
    val chunks: List<AttachmentFixtureChunk>,
) {
    fun toDomain() = AttachmentBlobSetManifest(
        blobSetId = BlobSetId(blobSetId),
        contentSha256 = Sha256Digest.of(contentSha256),
        totalByteCount = totalByteCount,
        chunks = chunks.map { chunk ->
            AttachmentChunkRef(
                index = chunk.index,
                providerObjectId = ProviderObjectId.of(chunk.providerObjectId),
                ciphertextSha256 = Sha256Digest.of(chunk.ciphertextSha256),
                plaintextByteCount = chunk.plaintextByteCount,
            )
        },
    )
}

@Serializable
private data class AttachmentFixtureChunk(
    val index: Int,
    val providerObjectId: String,
    val ciphertextSha256: String,
    val plaintextByteCount: Int,
)

private class FixtureAttachmentCrypto(
    private val fixture: AttachmentFixture,
    private val delegate: VaultCrypto = TinkVaultCrypto(),
) : VaultCrypto by delegate {
    override fun encryptBytes(
        key: VaultKey,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        verifyFixtureInputs(plaintext, associatedData)
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
        val expectedAssociatedData = fixture.associatedDataHex.hexToByteArray()
        if (!MessageDigest.isEqual(expectedAssociatedData, associatedData)) {
            throw GeneralSecurityException("Fixture associated data mismatch")
        }
        val rawKey = fixture.keyHex.hexToByteArray()
        val nonce = fixture.nonceHex.hexToByteArray()
        val prefixAndNonce = TINK_PREFIX + nonce
        if (ciphertext.size < prefixAndNonce.size ||
            !ciphertext.copyOfRange(0, prefixAndNonce.size).contentEquals(prefixAndNonce)
        ) {
            throw GeneralSecurityException("Fixture ciphertext prefix mismatch")
        }
        val encrypted = ciphertext.copyOfRange(prefixAndNonce.size, ciphertext.size)
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
            expectedAssociatedData.fill(0)
            rawKey.fill(0)
            nonce.fill(0)
            prefixAndNonce.fill(0)
            encrypted.fill(0)
        }
    }

    private fun verifyFixtureInputs(plaintext: ByteArray, associatedData: ByteArray) {
        val expectedPlaintext = fixture.plaintextHex.hexToByteArray()
        val expectedAssociatedData = fixture.associatedDataHex.hexToByteArray()
        try {
            if (!MessageDigest.isEqual(expectedPlaintext, plaintext) ||
                !MessageDigest.isEqual(expectedAssociatedData, associatedData)
            ) {
                throw GeneralSecurityException("Fixture input mismatch")
            }
        } finally {
            expectedPlaintext.fill(0)
            expectedAssociatedData.fill(0)
        }
    }

    private companion object {
        const val AES = "AES"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        val TINK_PREFIX = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x2a)
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { "%02x".format(it) }

private fun String.hexToByteArray(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
