package app.opentasks.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

class CloudObjectFormatTest {
    @Test
    fun goldenFramesAreCanonicalUnderNonDefaultLocale() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            goldenCases().forEach { golden ->
                assertGoldenHeaderMatchesFrame(golden, golden.frame)
                val encoded = CloudObjectFormat.encode(golden.header, golden.ciphertext)

                assertArrayEquals(golden.frame, encoded)
                val decoded = CloudObjectFormat.decode(
                    ByteArrayInputStream(golden.frame),
                    golden.frame.size.toLong(),
                )
                assertEquals(golden.header, decoded.header)
                assertArrayEquals(golden.ciphertext, decoded.ciphertext)
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun goldenIdentityMutationCannotMatchIndependentExpectedMetadata() {
        val golden = golden("manifest")
        val mutatedHeader = golden.expectedHeaderJson.replace(
            """"objectId":"manifest-0001"""",
            """"objectId":"manifest-9999"""",
        )
        val mutatedFrame = frame(mutatedHeader.toByteArray(), golden.ciphertext)

        val decoded = CloudObjectFormat.decode(
            ByteArrayInputStream(mutatedFrame),
            mutatedFrame.size.toLong(),
        )

        assertNotEquals(golden.header.identity, decoded.header.identity)
        assertThrows(AssertionError::class.java) {
            assertGoldenHeaderMatchesFrame(golden, mutatedFrame)
        }
    }

    @Test
    fun canonicalHeaderIncludesDefaultsAndExplicitNullChunksInDeclarationOrder() {
        val frame = golden("manifest").frame
        val header = frame.copyOfRange(4, 4 + frame.headerLength())
            .toString(Charsets.UTF_8)

        assertEquals(
            """{"magic":"OPEN_TASKS","family":"MANIFEST","schemaVersion":1,"cryptoVersion":1,"minimumReaderVersion":1,"vaultId":"vault-alpha","objectId":"manifest-0001","ciphertextLength":8,"ciphertextSha256":"05b3abf2579a5eb66403cd78be557fd860633a1fe2103c7642030defe32c657f","chunkIndex":null,"chunkCount":null}""",
            header,
        )
    }

    @Test
    fun boundsExposeTheFixedCloudFormatCaps() {
        assertEquals(16 * 1024, CloudBounds.MAX_HEADER_BYTES)
        assertEquals(1L * 1024 * 1024, CloudBounds.MAX_MANIFEST_CIPHERTEXT_BYTES)
        assertEquals(64L * 1024 * 1024, CloudBounds.MAX_SNAPSHOT_CIPHERTEXT_BYTES)
        assertEquals(16L * 1024 * 1024, CloudBounds.MAX_OPERATION_SEGMENT_CIPHERTEXT_BYTES)
        assertEquals(4L * 1024 * 1024, CloudBounds.MAX_ATTACHMENT_CHUNK_PLAINTEXT_BYTES)
        assertEquals(
            4L * 1024 * 1024 + 33,
            CloudBounds.MAX_ATTACHMENT_CHUNK_CIPHERTEXT_BYTES_V1,
        )
        assertEquals(26, CloudBounds.MAX_ATTACHMENT_CHUNKS)
        assertEquals(10_000, CloudBounds.MAX_OPERATIONS_PER_SEGMENT)
        assertEquals(100_000, CloudBounds.MAX_RECORDS_PER_SNAPSHOT)
        assertEquals(10_000, CloudBounds.MAX_MANIFEST_INVENTORY_ENTRIES)
    }

    @Test
    fun encodeAcceptsEveryFamilyAtItsExactCiphertextBoundary() {
        boundaryCases().forEach { (family, length) ->
            val ciphertext = ByteArray(length.toInt())
            val header = header(
                family = family,
                ciphertext = ciphertext,
                chunkIndex = if (family == CloudObjectFamily.ATTACHMENT_CHUNK) 25 else null,
                chunkCount = if (family == CloudObjectFamily.ATTACHMENT_CHUNK) 26 else null,
            )

            val encoded = CloudObjectFormat.encode(header, ciphertext)

            assertEquals(4L + encoded.headerLength() + length, encoded.size.toLong())
        }
    }

    @Test
    fun encodeAcceptsExactHeaderBoundaryAndRejectsOneByteOver() {
        val ciphertext = byteArrayOf(1)
        val base = header(CloudObjectFamily.MANIFEST, ciphertext)
        val baseLength = CloudObjectFormat.encode(base, ciphertext).headerLength()
        val exactObjectId = "x".repeat(
            CloudBounds.MAX_HEADER_BYTES - baseLength + base.objectId.length,
        )
        val exact = base.copy(objectId = exactObjectId)

        val encoded = CloudObjectFormat.encode(exact, ciphertext)

        assertEquals(CloudBounds.MAX_HEADER_BYTES, encoded.headerLength())
        assertThrows(IllegalArgumentException::class.java) {
            CloudObjectFormat.encode(
                exact.copy(objectId = "$exactObjectId!"),
                ciphertext,
            )
        }
    }

    @Test
    fun decodeRejectsEveryFamilyOneByteOverBeforeCiphertextRead() {
        boundaryCases().forEach { (family, limit) ->
            val declaredLength = limit + 1
            val headerBytes = canonicalHeader(
                family = family,
                ciphertextLength = declaredLength,
                ciphertextSha256 = ZERO_SHA256,
                chunkIndex = if (family == CloudObjectFamily.ATTACHMENT_CHUNK) 0 else null,
                chunkCount = if (family == CloudObjectFamily.ATTACHMENT_CHUNK) 1 else null,
            )
            val source = HeaderOnlyTrackingInputStream(headerBytes)

            val failure = assertThrows(CloudFormatException::class.java) {
                CloudObjectFormat.decode(
                    source,
                    checkedFrameLength(headerBytes.size, declaredLength),
                )
            }
            assertEquals(CloudFormatFailure.LIMIT_EXCEEDED, failure.failure)
            assertFalse(source.ciphertextRead)
        }
    }

    @Test
    fun decodeRejectsNegativeZeroAndOverflowingCiphertextLengthsBeforeRead() {
        listOf(
            -1L to CloudFormatFailure.LENGTH_MISMATCH,
            0L to CloudFormatFailure.LENGTH_MISMATCH,
            Long.MAX_VALUE to CloudFormatFailure.LENGTH_MISMATCH,
        ).forEach { (declaredLength, expectedFailure) ->
            val headerBytes = canonicalHeader(
                ciphertextLength = declaredLength,
                ciphertextSha256 = ZERO_SHA256,
            )
            val source = HeaderOnlyTrackingInputStream(headerBytes)

            val failure = assertThrows(CloudFormatException::class.java) {
                CloudObjectFormat.decode(source, Long.MAX_VALUE)
            }
            assertEquals(expectedFailure, failure.failure)
            assertFalse(source.ciphertextRead)
        }
    }

    @Test
    fun decodeRejectsNegativeZeroOversizedAndTruncatedHeaderPrefixes() {
        val invalidPrefixes = listOf(
            byteArrayOf(-1, -1, -1, -1) to CloudFormatFailure.MALFORMED,
            byteArrayOf(0, 0, 0, 0) to CloudFormatFailure.MALFORMED,
            ByteBuffer.allocate(4)
                .putInt(CloudBounds.MAX_HEADER_BYTES + 1)
                .array() to CloudFormatFailure.LIMIT_EXCEEDED,
            byteArrayOf(0, 0, 1) to CloudFormatFailure.TRUNCATED,
        )

        invalidPrefixes.forEach { (prefix, expectedFailure) ->
            val failure = assertThrows(CloudFormatException::class.java) {
                CloudObjectFormat.decode(
                    ByteArrayInputStream(prefix),
                    prefix.size.toLong(),
                )
            }
            assertEquals(expectedFailure, failure.failure)
        }
    }

    @Test
    fun decodeRejectsTruncatedHeaderAndCiphertext() {
        val golden = golden("manifest")
        val headerEnd = 4 + golden.frame.headerLength()
        val truncatedHeader = golden.frame.copyOfRange(0, headerEnd - 1)
        val truncatedCiphertext = golden.frame.copyOf(golden.frame.size - 1)

        val headerFailure = assertThrows(CloudFormatException::class.java) {
            CloudObjectFormat.decode(
                ByteArrayInputStream(truncatedHeader),
                golden.frame.size.toLong(),
            )
        }
        assertEquals(CloudFormatFailure.TRUNCATED, headerFailure.failure)
        val ciphertextFailure = assertThrows(CloudFormatException::class.java) {
            CloudObjectFormat.decode(
                ByteArrayInputStream(truncatedCiphertext),
                golden.frame.size.toLong(),
            )
        }
        assertEquals(CloudFormatFailure.TRUNCATED, ciphertextFailure.failure)
    }

    @Test
    fun decodeRejectsTrailingByteDeclarationsBeforeCiphertextRead() {
        val golden = golden("manifest")
        val headerBytes = golden.frame.copyOfRange(4, 4 + golden.frame.headerLength())
        val source = HeaderOnlyTrackingInputStream(headerBytes)

        val failure = assertThrows(CloudFormatException::class.java) {
            CloudObjectFormat.decode(source, golden.frame.size.toLong() + 1)
        }
        assertEquals(CloudFormatFailure.LENGTH_MISMATCH, failure.failure)
        assertFalse(source.ciphertextRead)
    }

    @Test
    fun decodeRejectsNegativeTotalLengthBeforeCiphertextRead() {
        val golden = golden("manifest")
        val headerBytes = golden.frame.copyOfRange(4, 4 + golden.frame.headerLength())
        val source = HeaderOnlyTrackingInputStream(headerBytes)

        val failure = assertThrows(CloudFormatException::class.java) {
            CloudObjectFormat.decode(source, -1)
        }
        assertEquals(CloudFormatFailure.LENGTH_MISMATCH, failure.failure)
        assertFalse(source.ciphertextRead)
    }

    @Test
    fun decodeRejectsInvalidUtf8BeforeCiphertextRead() {
        val source = HeaderOnlyTrackingInputStream(byteArrayOf(0xC3.toByte(), 0x28))

        val failure = assertThrows(CloudFormatException::class.java) {
            CloudObjectFormat.decode(source, 6)
        }
        assertEquals(CloudFormatFailure.MALFORMED, failure.failure)
        assertFalse(source.ciphertextRead)
    }

    @Test
    fun encodeRejectsEveryUnpairedSurrogateIdentityWithoutCollapsingValues() {
        val ciphertext = byteArrayOf(1)
        val valid = header(CloudObjectFamily.MANIFEST, ciphertext)
        val malformedValues = listOf(
            "\uD800",
            "\uDC00",
            "vault-\uD800",
            "vault-\uDC00",
            "\uD800-first",
            "\uD801-second",
        )

        malformedValues.forEach { malformed ->
            assertThrows(IllegalArgumentException::class.java) {
                CloudObjectFormat.encode(valid.copy(vaultId = malformed), ciphertext)
            }
            assertThrows(IllegalArgumentException::class.java) {
                CloudObjectFormat.encode(valid.copy(objectId = malformed), ciphertext)
            }
        }
    }

    @Test
    fun decodeRejectsEscapedUnpairedSurrogateIdentitiesBeforeCiphertextRead() {
        val canonical = golden("manifest").expectedHeaderJson
        val variants = listOf(
            canonical.replace(
                """"vaultId":"vault-alpha"""",
                """"vaultId":"\uD800"""",
            ),
            canonical.replace(
                """"vaultId":"vault-alpha"""",
                """"vaultId":"\uDC00"""",
            ),
            canonical.replace(
                """"objectId":"manifest-0001"""",
                """"objectId":"\uD800"""",
            ),
            canonical.replace(
                """"objectId":"manifest-0001"""",
                """"objectId":"\uDC00"""",
            ),
        )

        variants.forEach { json ->
            assertMalformedHeaderRejectedBeforeCiphertext(json.toByteArray())
        }
    }

    @Test
    fun arbitraryValidUnicodeIdentityRoundTripsExactly() {
        val ciphertext = "unicode".toByteArray()
        val expected = header(CloudObjectFamily.MANIFEST, ciphertext).copy(
            vaultId = "vault-\uD83D\uDD10-e\u0301-日本",
            objectId = "object-\uD83E\uDDEA-ไทย-\uD834\uDD1E",
        )

        val encoded = CloudObjectFormat.encode(expected, ciphertext)
        val decoded = CloudObjectFormat.decode(
            ByteArrayInputStream(encoded),
            encoded.size.toLong(),
        )

        assertEquals(expected, decoded.header)
        assertArrayEquals(ciphertext, decoded.ciphertext)
    }

    @Test
    fun decodeRejectsUnknownDuplicateMissingReorderedAndWhitespaceJson() {
        val canonical = goldenHeader("manifest")
        val variants = listOf(
            canonical.replaceFirst("{", """{"unknown":1,"""),
            canonical.replaceFirst(
                "{",
                """{"magic":"OPEN_TASKS",""",
            ),
            canonical.replaceFirst(""""schemaVersion":1,""", ""),
            canonical.replaceFirst(
                """"magic":"OPEN_TASKS","family":"MANIFEST"""",
                """"family":"MANIFEST","magic":"OPEN_TASKS"""",
            ),
            canonical.replaceFirst("{", "{ "),
        )

        variants.forEach { json ->
            assertMalformedHeaderRejectedBeforeCiphertext(json.toByteArray())
        }
    }

    @Test
    fun decodeRejectsUnsupportedMagicAndVersions() {
        val canonical = goldenHeader("manifest")
        val variants = listOf(
            canonical.replace("OPEN_TASKS", "OTHER_TASKS"),
            canonical.replace(""""schemaVersion":1""", """"schemaVersion":2"""),
            canonical.replace(""""cryptoVersion":1""", """"cryptoVersion":2"""),
            canonical.replace(
                """"minimumReaderVersion":1""",
                """"minimumReaderVersion":2""",
            ),
        )

        variants.forEach { json ->
            assertHeaderFailureBeforeCiphertext(
                json.toByteArray(),
                CloudFormatFailure.UNSUPPORTED_FORMAT,
            )
        }
    }

    @Test
    fun decodeRejectsBlankIdentifiersAsMalformed() {
        val canonical = goldenHeader("manifest")
        val variants = listOf(
            canonical.replace(""""vaultId":"vault-alpha"""", """"vaultId":" """"),
            canonical.replace(
                """"objectId":"manifest-0001"""",
                """"objectId":""""",
            ),
        )

        variants.forEach { json ->
            assertMalformedHeaderRejectedBeforeCiphertext(json.toByteArray())
        }
    }

    @Test
    fun decodeRejectsInvalidChecksumSyntaxBeforeCiphertextRead() {
        val canonical = goldenHeader("manifest")
        val validChecksum = golden("manifest").header.ciphertextSha256
        val variants = listOf(
            canonical.replace(validChecksum, validChecksum.uppercase(Locale.ROOT)),
            canonical.replace(validChecksum, validChecksum.dropLast(1)),
            canonical.replace(validChecksum, "g".repeat(64)),
        )

        variants.forEach { json ->
            assertMalformedHeaderRejectedBeforeCiphertext(json.toByteArray())
        }
    }

    @Test
    fun decodeRejectsChecksumMismatch() {
        val golden = golden("manifest")
        val tampered = golden.frame.copyOf()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()

        val failure = assertThrows(CloudFormatException::class.java) {
            CloudObjectFormat.decode(
                ByteArrayInputStream(tampered),
                tampered.size.toLong(),
            )
        }
        assertEquals(CloudFormatFailure.CHECKSUM_MISMATCH, failure.failure)
    }

    @Test
    fun decodeRejectsEveryInvalidAttachmentChunkTupleBeforeCiphertextRead() {
        val invalidTuples = listOf(
            null to null,
            0 to null,
            null to 1,
            0 to 0,
            0 to 27,
            -1 to 1,
            1 to 1,
            26 to 26,
        )
        invalidTuples.forEach { (index, count) ->
            val bytes = canonicalHeader(
                family = CloudObjectFamily.ATTACHMENT_CHUNK,
                chunkIndex = index,
                chunkCount = count,
            )
            val source = HeaderOnlyTrackingInputStream(bytes)

            val failure = assertThrows(CloudFormatException::class.java) {
                CloudObjectFormat.decode(
                    source,
                    checkedFrameLength(bytes.size, 1),
                )
            }
            assertEquals(CloudFormatFailure.MALFORMED, failure.failure)
            assertFalse(source.ciphertextRead)
        }
    }

    @Test
    fun decodeRejectsChunkFieldsForEveryNonAttachmentFamilyBeforeRead() {
        CloudObjectFamily.entries
            .filterNot { it == CloudObjectFamily.ATTACHMENT_CHUNK }
            .forEach { family ->
                listOf(0 to null, null to 1, 0 to 1).forEach { (index, count) ->
                    val bytes = canonicalHeader(
                        family = family,
                        chunkIndex = index,
                        chunkCount = count,
                    )
                    val source = HeaderOnlyTrackingInputStream(bytes)

                    val failure = assertThrows(CloudFormatException::class.java) {
                        CloudObjectFormat.decode(
                            source,
                            checkedFrameLength(bytes.size, 1),
                        )
                    }
                    assertEquals(CloudFormatFailure.MALFORMED, failure.failure)
                    assertFalse(source.ciphertextRead)
                }
            }
    }

    @Test
    fun encodeIdentityDerivesHeaderMetadataAndRoundTrips() {
        val identity = CloudHeaderIdentity(
            family = CloudObjectFamily.ATTACHMENT_CHUNK,
            schemaVersion = 1,
            cryptoVersion = 1,
            minimumReaderVersion = 1,
            vaultId = "vault-alpha",
            objectId = "attachment-0001-chunk-00",
            chunkIndex = 0,
            chunkCount = 26,
        )
        val ciphertext = "attachment".toByteArray()

        val frame = CloudObjectFormat.encode(identity, ciphertext)
        val decoded = CloudObjectFormat.decode(
            ByteArrayInputStream(frame),
            frame.size.toLong(),
        )

        assertEquals(identity, decoded.header.identity)
        assertEquals(ciphertext.size.toLong(), decoded.header.ciphertextLength)
        assertEquals(sha256(ciphertext), decoded.header.ciphertextSha256)
        assertArrayEquals(ciphertext, decoded.takeCiphertext())
    }

    @Test
    fun encodeRejectsLengthAndChecksumMetadataMismatch() {
        val ciphertext = "manifest".toByteArray()
        val valid = header(CloudObjectFamily.MANIFEST, ciphertext)

        assertThrows(IllegalArgumentException::class.java) {
            CloudObjectFormat.encode(
                valid.copy(ciphertextLength = ciphertext.size + 1L),
                ciphertext,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CloudObjectFormat.encode(
                valid.copy(ciphertextSha256 = ZERO_SHA256),
                ciphertext,
            )
        }
    }

    @Test
    fun encodeRejectsInvalidHeaderMetadata() {
        val ciphertext = byteArrayOf(1)
        val valid = header(CloudObjectFamily.MANIFEST, ciphertext)
        val invalid = listOf(
            valid.copy(magic = "OTHER_TASKS"),
            valid.copy(schemaVersion = 2),
            valid.copy(cryptoVersion = 2),
            valid.copy(minimumReaderVersion = 2),
            valid.copy(vaultId = " "),
            valid.copy(objectId = ""),
            valid.copy(chunkIndex = 0),
            valid.copy(ciphertextSha256 = valid.ciphertextSha256.uppercase(Locale.ROOT)),
        )

        invalid.forEach { header ->
            assertThrows(IllegalArgumentException::class.java) {
                CloudObjectFormat.encode(header, ciphertext)
            }
        }
    }

    @Test
    fun decodedFrameAndIdentityDefensivelyPreserveExactMetadata() {
        val golden = golden("attachment-chunk")
        val decoded = CloudObjectFormat.decode(
            ByteArrayInputStream(golden.frame),
            golden.frame.size.toLong(),
        )
        val firstCopy = decoded.ciphertext
        firstCopy.fill(0)

        assertArrayEquals(golden.ciphertext, decoded.ciphertext)
        assertEquals(
            CloudHeaderIdentity(
                family = CloudObjectFamily.ATTACHMENT_CHUNK,
                schemaVersion = 1,
                cryptoVersion = 1,
                minimumReaderVersion = 1,
                vaultId = "vault-alpha",
                objectId = "attachment-0001-chunk-00",
                chunkIndex = 0,
                chunkCount = 26,
            ),
            decoded.header.identity,
        )
    }

    @Test
    fun frameTakesOwnershipAndTransfersExactBufferOnlyOnce() {
        val owned = byteArrayOf(1, 2, 3)
        val frame = CloudObjectFrame(
            header(CloudObjectFamily.MANIFEST, owned),
            owned,
        )
        val defensiveCopy = frame.ciphertext

        assertNotSame(owned, defensiveCopy)
        defensiveCopy.fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.ciphertext)
        assertSame(owned, frame.takeCiphertext())
        assertThrows(IllegalStateException::class.java) {
            frame.takeCiphertext()
        }
        assertThrows(IllegalStateException::class.java) {
            frame.ciphertext
        }
    }

    @Test
    fun decodeNeverTransfersAnySourceRetainedReadTarget() {
        val golden = golden("manifest")
        val source = CiphertextBufferTrackingInputStream(golden.frame)
        val decoded = CloudObjectFormat.decode(source, golden.frame.size.toLong())
        val transferred = decoded.takeCiphertext()

        assertTrue(source.ciphertextTargets.isNotEmpty())
        source.ciphertextTargets.forEach { retained ->
            assertNotSame(retained, transferred)
        }
    }

    @Test
    fun mutatingSourceRetainedReadTargetsCannotAlterTransferredCiphertext() {
        val golden = golden("manifest")
        val source = CiphertextBufferTrackingInputStream(golden.frame)
        val decoded = CloudObjectFormat.decode(source, golden.frame.size.toLong())

        assertTrue(source.ciphertextTargets.isNotEmpty())
        source.ciphertextTargets.forEach { retained ->
            retained.fill(0)
        }

        assertArrayEquals(golden.ciphertext, decoded.takeCiphertext())
    }

    private fun assertMalformedHeaderRejectedBeforeCiphertext(headerBytes: ByteArray) {
        assertHeaderFailureBeforeCiphertext(
            headerBytes,
            CloudFormatFailure.MALFORMED,
        )
    }

    private fun assertHeaderFailureBeforeCiphertext(
        headerBytes: ByteArray,
        expectedFailure: CloudFormatFailure,
    ) {
        val source = HeaderOnlyTrackingInputStream(headerBytes)

        val failure = assertThrows(CloudFormatException::class.java) {
            CloudObjectFormat.decode(
                source,
                checkedFrameLength(headerBytes.size, 8),
            )
        }
        assertEquals(expectedFailure, failure.failure)
        assertFalse(source.ciphertextRead)
    }

    private fun boundaryCases(): List<Pair<CloudObjectFamily, Long>> = listOf(
        CloudObjectFamily.MANIFEST to CloudBounds.MAX_MANIFEST_CIPHERTEXT_BYTES,
        CloudObjectFamily.SNAPSHOT to CloudBounds.MAX_SNAPSHOT_CIPHERTEXT_BYTES,
        CloudObjectFamily.OPERATION_SEGMENT to
            CloudBounds.MAX_OPERATION_SEGMENT_CIPHERTEXT_BYTES,
        CloudObjectFamily.ATTACHMENT_CHUNK to
            CloudBounds.MAX_ATTACHMENT_CHUNK_CIPHERTEXT_BYTES_V1,
    )

    private fun goldenCases(): List<GoldenCase> = listOf(
        golden("manifest"),
        golden("snapshot"),
        golden("operation-segment"),
        golden("attachment-chunk"),
    )

    private fun golden(name: String): GoldenCase {
        val resource = javaClass.getResource("/cloud-format/v1/$name.json")
            ?: error("Missing golden fixture $name")
        val fixture = Json.parseToJsonElement(resource.readText()).jsonObject
        val expectedHeaderJson = fixture.getValue("headerJson").jsonPrimitive.content
        val frame = fixture.getValue("frameHex").jsonPrimitive.content.hexToByteArray()
        val ciphertext = fixture.getValue("ciphertextHex")
            .jsonPrimitive
            .content
            .hexToByteArray()
        val family = CloudObjectFamily.valueOf(
            expectedHeaderJson.stringField("family"),
        )
        val chunkIndex = expectedHeaderJson.nullableIntField("chunkIndex")
        val chunkCount = expectedHeaderJson.nullableIntField("chunkCount")
        val expectedHeader = CloudObjectHeader(
            magic = expectedHeaderJson.stringField("magic"),
            family = family,
            schemaVersion = expectedHeaderJson.intField("schemaVersion"),
            cryptoVersion = expectedHeaderJson.intField("cryptoVersion"),
            minimumReaderVersion = expectedHeaderJson.intField("minimumReaderVersion"),
            vaultId = expectedHeaderJson.stringField("vaultId"),
            objectId = expectedHeaderJson.stringField("objectId"),
            ciphertextLength = expectedHeaderJson.longField("ciphertextLength"),
            ciphertextSha256 = expectedHeaderJson.stringField("ciphertextSha256"),
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
        )
        assertEquals(expectedHeader.ciphertextLength, ciphertext.size.toLong())
        assertEquals(expectedHeader.ciphertextSha256, sha256(ciphertext))
        return GoldenCase(
            expectedHeaderJson = expectedHeaderJson,
            header = expectedHeader,
            ciphertext = ciphertext,
            frame = frame,
        )
    }

    private fun goldenHeader(name: String): String = golden(name).expectedHeaderJson

    private fun assertGoldenHeaderMatchesFrame(golden: GoldenCase, frame: ByteArray) {
        val headerLength = frame.headerLength()
        assertArrayEquals(
            golden.expectedHeaderJson.toByteArray(),
            frame.copyOfRange(4, 4 + headerLength),
        )
    }

    private fun header(
        family: CloudObjectFamily,
        ciphertext: ByteArray,
        chunkIndex: Int? = null,
        chunkCount: Int? = null,
    ): CloudObjectHeader = CloudObjectHeader(
        family = family,
        vaultId = "vault-alpha",
        objectId = "object-alpha",
        ciphertextLength = ciphertext.size.toLong(),
        ciphertextSha256 = sha256(ciphertext),
        chunkIndex = chunkIndex,
        chunkCount = chunkCount,
    )

    private fun canonicalHeader(
        family: CloudObjectFamily = CloudObjectFamily.MANIFEST,
        ciphertextLength: Long = 1,
        ciphertextSha256: String = ZERO_SHA256,
        chunkIndex: Int? = null,
        chunkCount: Int? = null,
    ): ByteArray {
        val index = chunkIndex?.toString() ?: "null"
        val count = chunkCount?.toString() ?: "null"
        return """{"magic":"OPEN_TASKS","family":"${family.name}","schemaVersion":1,"cryptoVersion":1,"minimumReaderVersion":1,"vaultId":"vault-alpha","objectId":"object-alpha","ciphertextLength":$ciphertextLength,"ciphertextSha256":"$ciphertextSha256","chunkIndex":$index,"chunkCount":$count}"""
            .toByteArray()
    }

    private fun checkedFrameLength(headerLength: Int, ciphertextLength: Long): Long =
        Math.addExact(Math.addExact(4L, headerLength.toLong()), ciphertextLength)

    private fun frame(headerBytes: ByteArray, ciphertext: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + headerBytes.size + ciphertext.size)
            .putInt(headerBytes.size)
            .put(headerBytes)
            .put(ciphertext)
            .array()

    private fun String.stringField(name: String): String =
        Regex(""""$name":"([^"]*)"""").find(this)!!.groupValues[1]

    private fun String.intField(name: String): Int =
        Regex(""""$name":(-?\d+)""").find(this)!!.groupValues[1].toInt()

    private fun String.longField(name: String): Long =
        Regex(""""$name":(-?\d+)""").find(this)!!.groupValues[1].toLong()

    private fun String.nullableIntField(name: String): Int? =
        Regex(""""$name":(null|-?\d+)""")
            .find(this)!!
            .groupValues[1]
            .takeUnless { it == "null" }
            ?.toInt()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    private fun ByteArray.headerLength(): Int = ByteBuffer.wrap(this, 0, 4).int

    private data class GoldenCase(
        val expectedHeaderJson: String,
        val header: CloudObjectHeader,
        val ciphertext: ByteArray,
        val frame: ByteArray,
    )

    private class CiphertextBufferTrackingInputStream(
        frame: ByteArray,
    ) : ByteArrayInputStream(frame) {
        private val ciphertextOffset = 4 + ByteBuffer.wrap(frame, 0, 4).int
        val ciphertextTargets = mutableListOf<ByteArray>()

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (pos >= ciphertextOffset && ciphertextTargets.none { it === target }) {
                ciphertextTargets += target
            }
            return super.read(target, offset, length)
        }
    }

    private class HeaderOnlyTrackingInputStream(
        headerBytes: ByteArray,
    ) : InputStream() {
        private val prefixAndHeader =
            ByteBuffer.allocate(4 + headerBytes.size)
                .putInt(headerBytes.size)
                .put(headerBytes)
                .array()
        private var position = 0
        var ciphertextRead: Boolean = false
            private set

        override fun read(): Int {
            if (position >= prefixAndHeader.size) {
                ciphertextRead = true
                return -1
            }
            return prefixAndHeader[position++].toInt() and 0xff
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (position >= prefixAndHeader.size) {
                ciphertextRead = true
                return -1
            }
            val count = minOf(length, prefixAndHeader.size - position)
            prefixAndHeader.copyInto(target, offset, position, position + count)
            position += count
            return count
        }
    }

    private companion object {
        const val ZERO_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
