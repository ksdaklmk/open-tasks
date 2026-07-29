package app.opentasks.core.data.backup

import app.opentasks.core.crypto.Argon2Metadata
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.model.VaultId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RecoveryEnvelopeCodecTest {
    @Test
    fun canonicalEncodingMatchesHandDerivedBytes() {
        val envelope = fixtureEnvelope()
        val expected = (
            """{"formatVersion":1,"kdfAlgorithm":"ARGON2ID","memoryKiB":65536,""" +
                """"iterations":3,"parallelism":1,""" +
                """"saltBase64":"AAECAwQFBgcICQoLDA0ODw",""" +
                """"nonceBase64":"EBESExQVFhcYGRob",""" +
                """"wrappedKeysetBase64":"HB0eHyAhIiM"}"""
            ).toByteArray()

        val encoded = RecoveryEnvelopeCodec.encode(envelope)

        assertArrayEquals(expected, encoded)
        val decoded = RecoveryEnvelopeCodec.decode(encoded)
        assertEnvelopeEquals(envelope, decoded)
        clearEnvelope(envelope)
        clearEnvelope(decoded)
        encoded.fill(0)
        expected.fill(0)
    }

    @Test
    fun payloadUsesFixedRecoveryMetadataAndUnpaddedBase64() {
        val envelope = fixtureEnvelope()

        val payload = RecoveryEnvelopeCodec.toPayload(envelope)

        assertEquals(1, payload.formatVersion)
        assertEquals("ARGON2ID", payload.kdfAlgorithm)
        assertEquals(65_536, payload.memoryKiB)
        assertEquals(3, payload.iterations)
        assertEquals(1, payload.parallelism)
        assertFalse(payload.saltBase64.contains('='))
        assertFalse(payload.nonceBase64.contains('='))
        assertFalse(payload.wrappedKeysetBase64.contains('='))
        assertEnvelopeEquals(envelope, RecoveryEnvelopeCodec.fromPayload(payload))
        clearEnvelope(envelope)
    }

    @Test
    fun unknownReorderedAndDuplicateFieldsAreRejected() {
        val canonical = canonicalJson()
        val unknown = canonical.replace(
            """"formatVersion":1""",
            """"formatVersion":1,"unknown":true""",
        )
        val reordered = canonical.replace(
            """"formatVersion":1,"kdfAlgorithm":"ARGON2ID"""",
            """"kdfAlgorithm":"ARGON2ID","formatVersion":1""",
        )
        val duplicate = canonical.replace(
            """"formatVersion":1""",
            """"formatVersion":1,"formatVersion":1""",
        )

        listOf(unknown, reordered, duplicate).forEach { source ->
            assertThrows(IllegalArgumentException::class.java) {
                RecoveryEnvelopeCodec.decode(source.toByteArray())
            }
        }
    }

    @Test
    fun weakenedAndFutureMetadataAreRejected() {
        val replacements = listOf(
            """"formatVersion":1""" to """"formatVersion":2""",
            """"kdfAlgorithm":"ARGON2ID"""" to """"kdfAlgorithm":"ARGON2I"""",
            """"memoryKiB":65536""" to """"memoryKiB":32768""",
            """"iterations":3""" to """"iterations":2""",
            """"parallelism":1""" to """"parallelism":2""",
        )

        replacements.forEach { (current, replacement) ->
            val source = canonicalJson().replace(current, replacement).toByteArray()
            assertThrows(IllegalArgumentException::class.java) {
                RecoveryEnvelopeCodec.decode(source)
            }
        }
    }

    @Test
    fun invalidSizesAndNonCanonicalBase64AreRejected() {
        val invalidPayloads = listOf(
            RecoveryEnvelopeCodec.toPayload(fixtureEnvelope()).copy(saltBase64 = "AA"),
            RecoveryEnvelopeCodec.toPayload(fixtureEnvelope()).copy(nonceBase64 = "AA"),
            RecoveryEnvelopeCodec.toPayload(fixtureEnvelope()).copy(saltBase64 = "AA=="),
            RecoveryEnvelopeCodec.toPayload(fixtureEnvelope())
                .copy(wrappedKeysetBase64 = "%"),
        )

        invalidPayloads.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                RecoveryEnvelopeCodec.fromPayload(payload)
            }
        }
    }

    @Test
    fun entityMappingCopiesArraysAndRejectsInvalidStoredMetadata() {
        val envelope = fixtureEnvelope()
        val entity = RecoveryEnvelopeCodec.toEntity(VaultId("vault-1"), envelope)
        envelope.kdf.salt.fill(99)
        envelope.nonce.fill(99)
        envelope.wrappedKeyset.fill(99)

        val reopened = RecoveryEnvelopeCodec.fromEntity(entity)

        assertArrayEquals(ByteArray(16) { it.toByte() }, reopened.kdf.salt)
        assertArrayEquals(ByteArray(12) { (it + 16).toByte() }, reopened.nonce)
        assertArrayEquals(ByteArray(8) { (it + 28).toByte() }, reopened.wrappedKeyset)
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryEnvelopeCodec.fromEntity(entity.copy(kdfAlgorithm = "argon2id"))
        }
        entity.salt.fill(0)
        entity.nonce.fill(0)
        entity.wrappedKeyset.fill(0)
        clearEnvelope(envelope)
        clearEnvelope(reopened)
    }

    @Test
    fun decodeOwnedClearsTransferredCanonicalBytesOnSuccessAndFailure() {
        val valid = canonicalJson().toByteArray()
        val invalid = canonicalJson()
            .replace(""""iterations":3""", """"iterations":2""")
            .toByteArray()

        clearEnvelope(RecoveryEnvelopeCodec.decodeOwned(valid))
        assertArrayEquals(ByteArray(valid.size), valid)
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryEnvelopeCodec.decodeOwned(invalid)
        }
        assertArrayEquals(ByteArray(invalid.size), invalid)
    }

    private fun fixtureEnvelope(): VaultKeyEnvelope =
        VaultKeyEnvelope(
            formatVersion = 1,
            kdf = Argon2Metadata(
                salt = ByteArray(16) { it.toByte() },
                memoryKiB = 65_536,
                iterations = 3,
                parallelism = 1,
            ),
            nonce = ByteArray(12) { (it + 16).toByte() },
            wrappedKeyset = ByteArray(8) { (it + 28).toByte() },
        )

    private fun canonicalJson(): String =
        """{"formatVersion":1,"kdfAlgorithm":"ARGON2ID","memoryKiB":65536,""" +
            """"iterations":3,"parallelism":1,""" +
            """"saltBase64":"AAECAwQFBgcICQoLDA0ODw",""" +
            """"nonceBase64":"EBESExQVFhcYGRob",""" +
            """"wrappedKeysetBase64":"HB0eHyAhIiM"}"""

    private fun assertEnvelopeEquals(
        expected: VaultKeyEnvelope,
        actual: VaultKeyEnvelope,
    ) {
        assertEquals(expected.formatVersion, actual.formatVersion)
        assertEquals(expected.kdf.memoryKiB, actual.kdf.memoryKiB)
        assertEquals(expected.kdf.iterations, actual.kdf.iterations)
        assertEquals(expected.kdf.parallelism, actual.kdf.parallelism)
        assertArrayEquals(expected.kdf.salt, actual.kdf.salt)
        assertArrayEquals(expected.nonce, actual.nonce)
        assertArrayEquals(expected.wrappedKeyset, actual.wrappedKeyset)
    }

    private fun clearEnvelope(envelope: VaultKeyEnvelope) {
        envelope.kdf.salt.fill(0)
        envelope.nonce.fill(0)
        envelope.wrappedKeyset.fill(0)
    }
}
