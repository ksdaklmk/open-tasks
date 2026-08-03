package app.opentasks.core.data.backup

import app.opentasks.core.crypto.Argon2Metadata
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.domain.AttachmentBlobSetManifest
import app.opentasks.core.domain.AttachmentChunkRef
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OtVaultGoldenTest {
    private val fixture = loadFixture()
    private val crypto = FixtureOtVaultCrypto(fixture)
    private val codec = OtVaultCodec(DefaultAuthenticatedCloudObjectCodec(crypto))

    @Test
    fun independentNodeFixtureMatchesCanonicalArchiveBytesAndDigest() {
        val expected = loadArchive("archive.bin")
        val key = crypto.createKey()
        val destination = ByteArrayOutputStream()
        val header = fixture.header()
        val entries = try {
            codec.writeHeader(destination, header)
            buildList {
                add(codec.writeSnapshot(destination, key, header, fixture.snapshot()))
                add(codec.writeSegment(destination, key, header, fixture.segment()))
                add(codec.writeAttachmentManifest(destination, key, header, fixture.manifest()))
                fixture.chunkPlaintexts().forEachIndexed { index, plaintext ->
                    add(
                        codec.writeAttachmentChunk(
                            destination,
                            key,
                            header,
                            BlobSetId(fixture.manifest.blobSetId),
                            index,
                            plaintext,
                        ),
                    )
                }
            }.also { entries -> codec.writeInventory(destination, key, header, entries) }
        } finally {
            key.close()
        }

        assertEquals(fixture.archiveSha256, sha256(expected))
        assertEquals(fixture.archiveByteCount, expected.size.toLong())
        assertArrayEquals(expected, destination.toByteArray())
        assertEquals(fixture.inventoryEntries, entries)
        assertEquals(
            fixture.headerJson.toByteArray(Charsets.UTF_8).size + MAGIC_AND_LENGTHS,
            headerBlockLength(expected),
        )
    }

    @Test
    fun committedArchiveDecodesToItsDeclaredObjectsAndCounts() {
        val archive = loadArchive("archive.bin")
        val key = crypto.createKey()
        val source = ByteArrayInputStream(archive)
        val header = codec.readHeader(source)
        val events = mutableListOf<OtVaultReadEvent>()
        try {
            codec.readAll(source, key, header) { event ->
                events += when (event) {
                    is OtVaultReadEvent.AttachmentChunk ->
                        event.copy(plaintext = event.plaintext.copyOf())

                    else -> event
                }
            }
        } finally {
            key.close()
        }

        assertEquals(fixture.vaultId, header.vaultId.value)
        assertEquals(fixture.createdAtEpochMillis, header.createdAtEpochMillis)
        assertEquals(fixture.recordCount, header.recordCount)
        assertEquals(fixture.attachmentCount, header.attachmentCount)
        assertEquals(65_536, header.envelope.kdf.memoryKiB)
        assertEquals(3, header.envelope.kdf.iterations)
        assertEquals(1, header.envelope.kdf.parallelism)
        assertEquals(16, header.envelope.kdf.salt.size)

        assertEquals(5, events.size)
        assertEquals(fixture.snapshot(), (events[0] as OtVaultReadEvent.Snapshot).payload)
        assertEquals(fixture.segment(), (events[1] as OtVaultReadEvent.Segment).payload)
        assertEquals(
            fixture.manifest(),
            (events[2] as OtVaultReadEvent.AttachmentManifest).manifest,
        )
        fixture.chunkPlaintexts().forEachIndexed { index, plaintext ->
            val chunk = events[3 + index] as OtVaultReadEvent.AttachmentChunk
            assertEquals(fixture.manifest.blobSetId, chunk.blobSetId.value)
            assertEquals(index, chunk.chunkIndex)
            assertArrayEquals(plaintext, chunk.plaintext)
        }
    }

    @Test
    fun committedRejectionVariantsAreRefused() {
        val expected = mapOf(
            "corrupt-frame.bin" to "Vault archive object failed authentication",
            "truncated.bin" to "Vault archive is truncated",
            "oversized-header.bin" to "Vault archive header length is outside its bound",
            "newer-version.bin" to "Unsupported vault archive format version 2",
            "wrong-magic.bin" to "Unsupported vault archive magic",
        )

        expected.forEach { (name, message) ->
            val key = crypto.createKey()
            val delivered = mutableListOf<OtVaultReadEvent>()
            val failure = try {
                assertThrows(name, OtVaultFormatException::class.java) {
                    val source = ByteArrayInputStream(loadArchive(name))
                    codec.readAll(source, key, codec.readHeader(source)) { delivered += it }
                }
            } finally {
                key.close()
            }
            assertEquals(name, message, failure.message)
            if (name == "corrupt-frame.bin") {
                assertEquals(name, 0, delivered.size)
            }
        }
    }

    private fun headerBlockLength(archive: ByteArray): Int {
        val declared = ByteArrayInputStream(archive).use { source ->
            source.skip((OtVaultCodec.MAGIC.length + 4).toLong())
            source.readNBytes(4)
        }
        return MAGIC_AND_LENGTHS + declared.fold(0) { total, byte ->
            (total shl 8) or (byte.toInt() and 0xff)
        }
    }

    private fun loadArchive(name: String): ByteArray {
        val path = "backup-format/otvault-v1/$name"
        val classLoader = requireNotNull(javaClass.classLoader)
        return requireNotNull(classLoader.getResourceAsStream(path)) {
            "Missing vault archive fixture $path"
        }.use { it.readBytes() }
    }

    private companion object {
        const val MAGIC_AND_LENGTHS = 16 + 4 + 4
    }
}

private fun loadFixture(): OtVaultFixture {
    val path = "backup-format/otvault-v1/archive.json"
    val classLoader = requireNotNull(OtVaultGoldenTest::class.java.classLoader)
    val text = requireNotNull(classLoader.getResourceAsStream(path)) {
        "Missing vault archive fixture $path"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
    return Json.decodeFromString(text)
}

@Serializable
private data class OtVaultFixture(
    val vaultId: String,
    val createdAtEpochMillis: Long,
    val keyHex: String,
    val recordCount: Int,
    val attachmentCount: Int,
    val recoveryEnvelope: RecoveryEnvelopePayloadV1,
    val headerJson: String,
    val headerSha256: String,
    val snapshotJson: String,
    val segmentJson: String,
    val inventoryJson: String,
    val manifest: OtVaultFixtureManifest,
    val chunkPlaintextHex: List<String>,
    val objects: List<OtVaultFixtureObject>,
    val inventoryEntries: List<OtVaultInventoryEntryV1>,
    val archiveByteCount: Long,
    val archiveSha256: String,
) {
    fun header(): OtVaultHeaderV1 = OtVaultHeaderV1(
        formatVersion = OtVaultCodec.FORMAT_VERSION,
        vaultId = VaultId(vaultId),
        createdAtEpochMillis = createdAtEpochMillis,
        envelope = VaultKeyEnvelope(
            formatVersion = recoveryEnvelope.formatVersion,
            kdf = Argon2Metadata(
                salt = decodeBase64(recoveryEnvelope.saltBase64),
                memoryKiB = recoveryEnvelope.memoryKiB,
                iterations = recoveryEnvelope.iterations,
                parallelism = recoveryEnvelope.parallelism,
            ),
            nonce = decodeBase64(recoveryEnvelope.nonceBase64),
            wrappedKeyset = decodeBase64(recoveryEnvelope.wrappedKeysetBase64),
        ),
        recordCount = recordCount,
        attachmentCount = attachmentCount,
    )

    fun snapshot(): BackupSnapshotPayloadV1 =
        BackupSnapshotCodec.decode(snapshotJson.toByteArray(Charsets.UTF_8))

    fun segment(): BackupOperationSegmentPayloadV1 =
        BackupOperationSegmentCodec.decode(segmentJson.toByteArray(Charsets.UTF_8))

    fun manifest(): AttachmentBlobSetManifest = manifest.toDomain()

    fun chunkPlaintexts(): List<ByteArray> = chunkPlaintextHex.map { it.hexToByteArray() }

    private fun decodeBase64(value: String): ByteArray = Base64.getDecoder().decode(value)
}

@Serializable
private data class OtVaultFixtureManifest(
    val blobSetId: String,
    val contentSha256: String,
    val totalByteCount: Long,
    val chunks: List<OtVaultFixtureChunk>,
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
private data class OtVaultFixtureChunk(
    val index: Int,
    val providerObjectId: String,
    val ciphertextSha256: String,
    val plaintextByteCount: Int,
)

@Serializable
private data class OtVaultFixtureObject(
    val objectId: String,
    val family: String,
    val chunkIndex: Int?,
    val chunkCount: Int?,
    val nonceHex: String,
    val associatedDataHex: String,
    val plaintextSha256: String,
    val frameSha256: String,
    val byteCount: Long,
)

/**
 * Encrypts exactly as the fixture generator did: the fixture's raw AES-256 key
 * and the nonce it pinned for the object whose associated data is offered.
 */
private class FixtureOtVaultCrypto(
    private val fixture: OtVaultFixture,
    private val delegate: VaultCrypto = TinkVaultCrypto(),
) : VaultCrypto by delegate {
    override fun encryptBytes(
        key: VaultKey,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val expected = fixture.objects.firstOrNull {
            it.associatedDataHex == associatedData.toHex()
        } ?: throw GeneralSecurityException("Fixture associated data mismatch")
        if (expected.plaintextSha256 != sha256(plaintext)) {
            throw GeneralSecurityException("Fixture plaintext mismatch")
        }
        val rawKey = fixture.keyHex.hexToByteArray()
        val nonce = expected.nonceHex.hexToByteArray()
        return try {
            TINK_PREFIX + nonce + Cipher.getInstance(AES_GCM).run {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(rawKey, AES),
                    GCMParameterSpec(GCM_TAG_BITS, nonce),
                )
                updateAAD(associatedData)
                doFinal(plaintext)
            }
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
        if (ciphertext.size < PREFIX_AND_NONCE_BYTES ||
            !ciphertext.copyOfRange(0, TINK_PREFIX.size).contentEquals(TINK_PREFIX)
        ) {
            throw GeneralSecurityException("Fixture ciphertext prefix mismatch")
        }
        val rawKey = fixture.keyHex.hexToByteArray()
        val nonce = ciphertext.copyOfRange(TINK_PREFIX.size, PREFIX_AND_NONCE_BYTES)
        val encrypted = ciphertext.copyOfRange(PREFIX_AND_NONCE_BYTES, ciphertext.size)
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

    private companion object {
        const val AES = "AES"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PREFIX_AND_NONCE_BYTES = 17
        val TINK_PREFIX = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x2a)
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { "%02x".format(it) }

private fun String.hexToByteArray(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
