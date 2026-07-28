package app.opentasks.core.data

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.sync.CloudBounds
import app.opentasks.core.sync.CloudFormatException
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import app.opentasks.core.sync.CloudObjectFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class AuthenticatedCloudObjectCodecTest {
    private val crypto = TinkVaultCrypto()
    private val codec = DefaultAuthenticatedCloudObjectCodec(crypto)

    @Test
    fun everyObjectFamilyRoundTripsThroughAuthenticatedFrame() {
        val key = crypto.createKey()
        try {
            identities().forEach { identity ->
                val plaintext = "payload:${identity.family}".toByteArray()
                val frame = codec.encrypt(identity, plaintext, key)

                val result = codec.decrypt(
                    ByteArrayInputStream(frame),
                    frame.size.toLong(),
                    key,
                )

                val decoded = (result as CloudDecodeResult.Success).value
                assertEquals(identity, decoded.identity)
                val taken = decoded.takePlaintext()
                assertArrayEquals(plaintext, taken)
                plaintext.fill(0)
                taken.fill(0)
                frame.fill(0)
            }
        } finally {
            key.close()
        }
    }

    @Test
    fun encryptingSamePlaintextTwiceUsesDifferentCiphertext() {
        val key = crypto.createKey()
        val identity = manifestIdentity()
        val plaintext = "same payload".toByteArray()

        val first = codec.encrypt(identity, plaintext, key)
        val second = codec.encrypt(identity, plaintext, key)

        assertFalse(first.contentEquals(second))
        plaintext.fill(0)
        first.fill(0)
        second.fill(0)
        key.close()
    }

    @Test
    fun encryptPreservesCallerPlaintext() {
        val key = crypto.createKey()
        val plaintext = "caller owned".toByteArray()
        val expected = plaintext.copyOf()

        val frame = codec.encrypt(manifestIdentity(), plaintext, key)

        assertArrayEquals(expected, plaintext)
        plaintext.fill(0)
        expected.fill(0)
        frame.fill(0)
        key.close()
    }

    @Test
    fun decryptedObjectReturnsDefensiveCopiesWhileRetainingOwnership() {
        val owned = byteArrayOf(1, 2, 3)
        val decoded = DecryptedCloudObject(manifestIdentity(), owned)

        val first = decoded.copyPlaintext()
        assertNotSame(owned, first)
        first.fill(0)

        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.copyPlaintext())
        decoded.close()
        assertArrayEquals(byteArrayOf(0, 0, 0), owned)
    }

    @Test
    fun decryptedObjectTransfersExactPlaintextOnlyOnce() {
        val owned = byteArrayOf(1, 2, 3)
        val decoded = DecryptedCloudObject(manifestIdentity(), owned)

        val transferred = decoded.takePlaintext()

        assertSame(owned, transferred)
        assertThrows(IllegalStateException::class.java) {
            decoded.takePlaintext()
        }
        assertThrows(IllegalStateException::class.java) {
            decoded.copyPlaintext()
        }
        decoded.close()
        assertArrayEquals(byteArrayOf(1, 2, 3), transferred)
        transferred.fill(0)
    }

    @Test
    fun closingBeforeTransferZeroesRetainedPlaintextAndEndsOwnership() {
        val owned = byteArrayOf(4, 5, 6)
        val decoded = DecryptedCloudObject(manifestIdentity(), owned)

        decoded.close()

        assertArrayEquals(byteArrayOf(0, 0, 0), owned)
        assertThrows(IllegalStateException::class.java) {
            decoded.copyPlaintext()
        }
        assertThrows(IllegalStateException::class.java) {
            decoded.takePlaintext()
        }
        decoded.close()
    }

    @Test
    fun closedKeyRemainsALocalLifecycleFailureForEncryptAndDecrypt() {
        val key = crypto.createKey()
        val frame = codec.encrypt(manifestIdentity(), "payload".toByteArray(), key)
        key.close()

        assertThrows(IllegalStateException::class.java) {
            codec.encrypt(manifestIdentity(), "payload".toByteArray(), key)
        }
        assertThrows(IllegalStateException::class.java) {
            codec.decrypt(ByteArrayInputStream(frame), frame.size.toLong(), key)
        }
        frame.fill(0)
    }

    @Test
    fun encryptValidatesIdentityBeforeTouchingClosedKey() {
        val key = crypto.createKey()
        key.close()

        assertThrows(CloudFormatException::class.java) {
            codec.encrypt(
                manifestIdentity().copy(schemaVersion = 2),
                "payload".toByteArray(),
                key,
            )
        }
    }

    @Test
    fun reframingCiphertextUnderDifferentValidIdentityFailsAuthentication() {
        val key = crypto.createKey()
        try {
            val cases = listOf(
                manifestIdentity() to manifestIdentity().copy(
                    family = CloudObjectFamily.SNAPSHOT,
                ),
                manifestIdentity() to manifestIdentity().copy(vaultId = "vault-beta"),
                manifestIdentity() to manifestIdentity().copy(objectId = "object-beta"),
                attachmentIdentity() to attachmentIdentity().copy(chunkIndex = 1),
                attachmentIdentity() to attachmentIdentity().copy(chunkCount = 3),
            )

            cases.forEach { (originalIdentity, reframedIdentity) ->
                val encrypted = codec.encrypt(originalIdentity, "payload".toByteArray(), key)
                val decodedFrame = CloudObjectFormat.decode(
                    ByteArrayInputStream(encrypted),
                    encrypted.size.toLong(),
                )
                val ciphertext = decodedFrame.takeCiphertext()
                val reframed = CloudObjectFormat.encode(reframedIdentity, ciphertext)

                assertFailure(
                    CloudDecodeFailure.AUTHENTICATION_FAILED,
                    codec.decrypt(
                        ByteArrayInputStream(reframed),
                        reframed.size.toLong(),
                        key,
                    ),
                )

                ciphertext.fill(0)
                encrypted.fill(0)
                reframed.fill(0)
            }
        } finally {
            key.close()
        }
    }

    @Test
    fun incompatibleVersionIsRejectedBeforeAead() {
        val key = crypto.createKey()
        val frame = codec.encrypt(manifestIdentity(), "payload".toByteArray(), key)
        val futureVersion = mutateHeader(frame, """"cryptoVersion":1""", """"cryptoVersion":2""")
        key.close()

        assertFailure(
            CloudDecodeFailure.UNSUPPORTED_FORMAT,
            codec.decrypt(
                ByteArrayInputStream(futureVersion),
                futureVersion.size.toLong(),
                key,
            ),
        )
        frame.fill(0)
        futureVersion.fill(0)
    }

    @Test
    fun independentVaultKeyFailsAuthentication() {
        val encryptionKey = crypto.createKey()
        val independentKey = crypto.createKey()
        val frame = codec.encrypt(
            manifestIdentity(),
            "private payload".toByteArray(),
            encryptionKey,
        )

        assertFailure(
            CloudDecodeFailure.AUTHENTICATION_FAILED,
            codec.decrypt(
                ByteArrayInputStream(frame),
                frame.size.toLong(),
                independentKey,
            ),
        )

        frame.fill(0)
        encryptionKey.close()
        independentKey.close()
    }

    @Test
    fun ciphertextChecksumIsVerifiedBeforeAuthentication() {
        val key = crypto.createKey()
        val frame = codec.encrypt(manifestIdentity(), "private payload".toByteArray(), key)
        frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()

        assertFailure(
            CloudDecodeFailure.CHECKSUM_MISMATCH,
            codec.decrypt(ByteArrayInputStream(frame), frame.size.toLong(), key),
        )

        frame.fill(0)
        key.close()
    }

    @Test
    fun truncationInEveryFrameRegionIsTyped() {
        val key = crypto.createKey()
        val frame = codec.encrypt(manifestIdentity(), "private payload".toByteArray(), key)
        val headerEnd = Integer.BYTES + ByteBuffer.wrap(frame, 0, Integer.BYTES).int
        val truncatedFrames = listOf(
            frame.copyOf(Integer.BYTES - 1),
            frame.copyOf(headerEnd - 1),
            frame.copyOf(frame.size - 1),
        )

        truncatedFrames.forEach { truncated ->
            assertFailure(
                CloudDecodeFailure.TRUNCATED,
                codec.decrypt(
                    ByteArrayInputStream(truncated),
                    frame.size.toLong(),
                    key,
                ),
            )
            truncated.fill(0)
        }

        frame.fill(0)
        key.close()
    }

    @Test
    fun everyFutureVersionIsTypedAsUnsupportedFormat() {
        val key = crypto.createKey()
        val frame = codec.encrypt(manifestIdentity(), "payload".toByteArray(), key)
        val futureFrames = listOf(
            mutateHeader(frame, """"schemaVersion":1""", """"schemaVersion":2"""),
            mutateHeader(frame, """"cryptoVersion":1""", """"cryptoVersion":2"""),
            mutateHeader(
                frame,
                """"minimumReaderVersion":1""",
                """"minimumReaderVersion":2""",
            ),
        )

        futureFrames.forEach { future ->
            assertFailure(
                CloudDecodeFailure.UNSUPPORTED_FORMAT,
                codec.decrypt(
                    ByteArrayInputStream(future),
                    future.size.toLong(),
                    key,
                ),
            )
            future.fill(0)
        }

        frame.fill(0)
        key.close()
    }

    @Test
    fun oversizedFamilyPayloadIsTypedBeforeCiphertextRead() {
        val declaredLength = CloudBounds.MAX_MANIFEST_CIPHERTEXT_BYTES + 1
        val header = canonicalManifestHeader(
            ciphertextLength = declaredLength,
            checksum = "0".repeat(64),
        )
        val source = headerOnlyFrame(header)
        val closedKey = crypto.createKey()
        closedKey.close()

        assertFailure(
            CloudDecodeFailure.LIMIT_EXCEEDED,
            codec.decrypt(
                ByteArrayInputStream(source),
                Integer.BYTES + header.size.toLong() + declaredLength,
                closedKey,
            ),
        )
        source.fill(0)
        header.fill(0)
    }

    @Test
    fun mismatchedTotalLengthIsTyped() {
        val key = crypto.createKey()
        val frame = codec.encrypt(manifestIdentity(), "payload".toByteArray(), key)

        assertFailure(
            CloudDecodeFailure.LENGTH_MISMATCH,
            codec.decrypt(
                ByteArrayInputStream(frame),
                frame.size.toLong() + 1,
                key,
            ),
        )

        frame.fill(0)
        key.close()
    }

    @Test
    fun malformedFrameIsTyped() {
        val key = crypto.createKey()
        val malformedPrefix = byteArrayOf(0, 0, 0, 0)

        assertFailure(
            CloudDecodeFailure.MALFORMED_FRAME,
            codec.decrypt(
                ByteArrayInputStream(malformedPrefix),
                malformedPrefix.size.toLong(),
                key,
            ),
        )

        malformedPrefix.fill(0)
        key.close()
    }

    private fun assertFailure(
        expected: CloudDecodeFailure,
        result: CloudDecodeResult,
    ) {
        assertEquals(expected, (result as CloudDecodeResult.Failure).reason)
    }

    private fun identities(): List<CloudHeaderIdentity> = listOf(
        manifestIdentity(),
        manifestIdentity().copy(
            family = CloudObjectFamily.SNAPSHOT,
            objectId = "snapshot-0001",
        ),
        manifestIdentity().copy(
            family = CloudObjectFamily.OPERATION_SEGMENT,
            objectId = "operations-0001",
        ),
        attachmentIdentity(),
    )

    private fun manifestIdentity(): CloudHeaderIdentity = CloudHeaderIdentity(
        family = CloudObjectFamily.MANIFEST,
        schemaVersion = 1,
        cryptoVersion = 1,
        minimumReaderVersion = 1,
        vaultId = "vault-alpha",
        objectId = "manifest-0001",
    )

    private fun attachmentIdentity(): CloudHeaderIdentity = CloudHeaderIdentity(
        family = CloudObjectFamily.ATTACHMENT_CHUNK,
        schemaVersion = 1,
        cryptoVersion = 1,
        minimumReaderVersion = 1,
        vaultId = "vault-alpha",
        objectId = "attachment-0001",
        chunkIndex = 0,
        chunkCount = 2,
    )

    private fun mutateHeader(
        frame: ByteArray,
        oldValue: String,
        newValue: String,
    ): ByteArray {
        val headerLength = ByteBuffer.wrap(frame, 0, Integer.BYTES).int
        val header = frame
            .copyOfRange(Integer.BYTES, Integer.BYTES + headerLength)
            .toString(Charsets.UTF_8)
        val mutated = header.replace(oldValue, newValue)
        assertEquals(header.length, mutated.length)
        return frame(
            mutated.toByteArray(),
            frame.copyOfRange(Integer.BYTES + headerLength, frame.size),
        )
    }

    private fun canonicalManifestHeader(
        ciphertextLength: Long,
        checksum: String,
    ): ByteArray =
        """{"magic":"OPEN_TASKS","family":"MANIFEST","schemaVersion":1,"cryptoVersion":1,"minimumReaderVersion":1,"vaultId":"vault-alpha","objectId":"manifest-0001","ciphertextLength":$ciphertextLength,"ciphertextSha256":"$checksum","chunkIndex":null,"chunkCount":null}"""
            .toByteArray()

    private fun headerOnlyFrame(header: ByteArray): ByteArray =
        ByteBuffer.allocate(Integer.BYTES + header.size)
            .putInt(header.size)
            .put(header)
            .array()

    private fun frame(header: ByteArray, ciphertext: ByteArray): ByteArray =
        ByteBuffer.allocate(Integer.BYTES + header.size + ciphertext.size)
            .putInt(header.size)
            .put(header)
            .put(ciphertext)
            .array()
}
