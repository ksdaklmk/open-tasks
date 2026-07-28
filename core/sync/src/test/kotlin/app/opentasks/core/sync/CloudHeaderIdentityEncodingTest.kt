package app.opentasks.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudHeaderIdentityEncodingTest {
    @Test
    fun manifestIdentityMatchesIndependentGoldenBytes() {
        val identity = CloudHeaderIdentity(
            family = CloudObjectFamily.MANIFEST,
            schemaVersion = 1,
            cryptoVersion = 1,
            minimumReaderVersion = 1,
            vaultId = "vault-alpha",
            objectId = "manifest-0001",
        )

        assertEquals(
            "000000236f70656e2d7461736b733a636c6f75642d6865616465722d6964656e" +
                "746974793a7631000000084d414e494645535400000001310000000131000000" +
                "01310000000b7661756c742d616c7068610000000d6d616e69666573742d3030" +
                "30310000000000000000",
            CloudHeaderIdentityEncoding.associatedData(identity).toHex(),
        )
    }

    @Test
    fun everyValidIdentityMutationChangesAssociatedData() {
        val base = manifestIdentity()
        val mutations = listOf(
            base.copy(family = CloudObjectFamily.SNAPSHOT),
            base.copy(vaultId = "vault-beta"),
            base.copy(objectId = "manifest-0002"),
        )
        val expected = CloudHeaderIdentityEncoding.associatedData(base)

        mutations.forEach { mutation ->
            assertFalse(
                expected.contentEquals(
                    CloudHeaderIdentityEncoding.associatedData(mutation),
                ),
            )
        }

        val chunk = attachmentIdentity()
        val chunkMutations = listOf(
            chunk.copy(chunkIndex = 1),
            chunk.copy(chunkCount = 25),
        )
        val expectedChunk =
            CloudHeaderIdentityEncoding.associatedData(chunk)
        chunkMutations.forEach { mutation ->
            assertFalse(
                expectedChunk.contentEquals(
                    CloudHeaderIdentityEncoding.associatedData(mutation),
                ),
            )
        }
    }

    @Test
    fun blankIdentifiersAreMalformed() {
        listOf(
            manifestIdentity().copy(vaultId = ""),
            manifestIdentity().copy(vaultId = " \t"),
            manifestIdentity().copy(objectId = ""),
            manifestIdentity().copy(objectId = "\n"),
        ).forEach { identity ->
            assertIdentityFailure(CloudFormatFailure.MALFORMED, identity)
        }
    }

    @Test
    fun unpairedSurrogateIdentifiersAreMalformed() {
        val malformedValues = listOf(
            "\uD800",
            "\uDC00",
            "vault-\uD800",
            "object-\uDC00",
        )

        malformedValues.forEach { malformed ->
            assertIdentityFailure(
                CloudFormatFailure.MALFORMED,
                manifestIdentity().copy(vaultId = malformed),
            )
            assertIdentityFailure(
                CloudFormatFailure.MALFORMED,
                manifestIdentity().copy(objectId = malformed),
            )
        }
    }

    @Test
    fun unsupportedIdentityVersionsAreTyped() {
        listOf(
            manifestIdentity().copy(schemaVersion = 2),
            manifestIdentity().copy(cryptoVersion = 2),
            manifestIdentity().copy(minimumReaderVersion = 2),
            manifestIdentity().copy(minimumReaderVersion = 0),
        ).forEach { identity ->
            assertIdentityFailure(CloudFormatFailure.UNSUPPORTED_FORMAT, identity)
        }
    }

    @Test
    fun attachmentIdentityRequiresACompleteBoundedChunkTuple() {
        listOf(
            null to null,
            0 to null,
            null to 1,
            0 to 0,
            0 to 27,
            -1 to 1,
            1 to 1,
            26 to 26,
        ).forEach { (chunkIndex, chunkCount) ->
            assertIdentityFailure(
                CloudFormatFailure.MALFORMED,
                attachmentIdentity().copy(
                    chunkIndex = chunkIndex,
                    chunkCount = chunkCount,
                ),
            )
        }
    }

    @Test
    fun nonAttachmentIdentitiesRejectChunkFields() {
        CloudObjectFamily.entries
            .filterNot { it == CloudObjectFamily.ATTACHMENT_CHUNK }
            .forEach { family ->
                listOf(0 to null, null to 1, 0 to 1).forEach { (index, count) ->
                    assertIdentityFailure(
                        CloudFormatFailure.MALFORMED,
                        manifestIdentity().copy(
                            family = family,
                            chunkIndex = index,
                            chunkCount = count,
                        ),
                    )
                }
            }
    }

    private fun assertIdentityFailure(
        expected: CloudFormatFailure,
        identity: CloudHeaderIdentity,
    ) {
        val failure = assertThrows(CloudFormatException::class.java) {
            CloudHeaderIdentityEncoding.associatedData(identity)
        }

        assertEquals(expected, failure.failure)
    }

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
        objectId = "attachment-0001-chunk-00",
        chunkIndex = 0,
        chunkCount = 26,
    )

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
}
