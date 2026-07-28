package app.opentasks.core.data.backup

import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.VaultId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class BackupOperationSegmentCodecTest {
    @Test
    fun canonicalJournalEncodingMatchesHandDerivedBytesAndIdentity() {
        val journal = journalEntry(
            operationId = "operation-1",
            generation = 41,
            sequence = 0,
            tagId = "tag-1",
            revisionWallMillis = 9,
            revisionLogical = 2,
        )
        val expectedPayloadBase64 =
            "eyJmb3JtYXRWZXJzaW9uIjoxLCJtaW5pbXVtUmVhZGVyVmVyc2lvbiI6MSwibXV0YXRpb25LaW5kIjoi" +
                "VVBTRVJUIiwicmVjb3JkIjp7ImZhbWlseSI6IlRBRyIsImlkZW50aXR5IjpbInRhZy0xIl0sImZpZWxk" +
                "cyI6W3sibmFtZSI6ImlkIiwidHlwZSI6IlNUUklORyIsInZhbHVlIjoidGFnLTEifSx7Im5hbWUiOiJ3" +
                "b3Jrc3BhY2VJZCIsInR5cGUiOiJTVFJJTkciLCJ2YWx1ZSI6IndvcmtzcGFjZS0xIn0seyJuYW1lIjoi" +
                "bmFtZSIsInR5cGUiOiJTVFJJTkciLCJ2YWx1ZSI6IlVyZ2VudCJ9XX0sImRlbGV0ZWRGYW1pbHkiOm51" +
                "bGwsImRlbGV0ZWRJZGVudGl0eSI6bnVsbH0"
        val expected = (
            """{"formatVersion":1,"minimumReaderVersion":1,"vaultId":"vault-alpha",""" +
                """"firstGeneration":41,"lastGeneration":41,"entries":[""" +
                """{"operationId":"operation-1","generation":41,"sequence":0,""" +
                """"objectId":"tag-1","objectType":"TAG","revisionWallMillis":9,""" +
                """"revisionLogical":2,"sourceDeviceId":"device-alpha",""" +
                """"payloadBase64":"$expectedPayloadBase64"}],"entryCount":1}"""
            ).toByteArray()
        val journalPayload = journal.payload.copyOf()

        val payload = BackupOperationSegmentCodec.fromJournalEntries(
            VaultId("vault-alpha"),
            listOf(journal),
        )
        val encoded = BackupOperationSegmentCodec.encode(payload)

        assertArrayEquals(expected, encoded)
        assertArrayEquals(journalPayload, journal.payload)
        assertEquals(payload, BackupOperationSegmentCodec.decode(encoded))
        assertEquals(
            "segment:41:53",
            BackupPayloadIdentities.segmentObjectId(
                BackupGeneration(41),
                BackupGeneration(53),
            ),
        )
    }

    @Test
    fun generationSequenceOrderAndInclusiveRangeMustAgree() {
        val canonical = BackupOperationSegmentCodec.fromJournalEntries(
            VaultId("vault-alpha"),
            listOf(
                journalEntry("operation-1", 41, 0, "tag-1"),
                journalEntry("operation-2", 41, 1, "tag-2"),
                journalEntry("operation-3", 53, 0, "tag-3"),
            ),
        )
        val invalid = listOf(
            canonical.copy(entries = canonical.entries.reversed()),
            canonical.copy(entries = listOf(canonical.entries[1], canonical.entries[0], canonical.entries[2])),
            canonical.copy(firstGeneration = 40),
            canonical.copy(lastGeneration = 54),
            canonical.copy(firstGeneration = 54, lastGeneration = 53),
            canonical.copy(entries = canonical.entries.toMutableList().also {
                it[0] = it[0].copy(sequence = -1)
            }),
        )

        invalid.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupOperationSegmentCodec.encode(payload)
            }
        }
    }

    @Test
    fun operationIdsMustBeUniqueAndAllEntryValuesBounded() {
        val canonical = BackupOperationSegmentCodec.fromJournalEntries(
            VaultId("vault-alpha"),
            listOf(
                journalEntry("operation-1", 41, 0, "tag-1"),
                journalEntry("operation-2", 41, 1, "tag-2"),
            ),
        )
        val invalid = listOf(
            canonical.copy(
                entries = listOf(
                    canonical.entries[0],
                    canonical.entries[1].copy(operationId = "operation-1"),
                ),
            ),
            canonical.copy(entries = canonical.entries.toMutableList().also {
                it[0] = it[0].copy(revisionWallMillis = -1)
            }),
            canonical.copy(entries = canonical.entries.toMutableList().also {
                it[0] = it[0].copy(revisionLogical = -1)
            }),
            canonical.copy(entries = canonical.entries.toMutableList().also {
                it[0] = it[0].copy(sourceDeviceId = "")
            }),
            canonical.copy(entryCount = 3),
        )

        invalid.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupOperationSegmentCodec.encode(payload)
            }
        }
    }

    @Test
    fun segmentAcceptsTenThousandEntriesAndRejectsTenThousandOne() {
        val journal = (0..10_000).map { index ->
            journalEntry(
                operationId = "operation-${index.toString().padStart(5, '0')}",
                generation = 41,
                sequence = index,
                tagId = "tag-${index.toString().padStart(5, '0')}",
            )
        }

        val accepted = BackupOperationSegmentCodec.fromJournalEntries(
            VaultId("vault-alpha"),
            journal.dropLast(1),
        )
        assertEquals(
            10_000,
            BackupOperationSegmentCodec.decode(
                BackupOperationSegmentCodec.encode(accepted),
            ).entries.size,
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupOperationSegmentCodec.fromJournalEntries(
                VaultId("vault-alpha"),
                journal,
            )
        }
    }

    @Test
    fun segmentAcceptsExactPlaintextMaximumAndRejectsOneByteOver() {
        val source = exactSegmentBytes(BackupOperationSegmentCodec.MAX_PLAINTEXT_BYTES)
        val over = source.copyOf(source.size + 1).also { it[it.lastIndex] = ' '.code.toByte() }

        assertEquals(BackupOperationSegmentCodec.MAX_PLAINTEXT_BYTES, source.size)
        assertEquals(
            "vault-alpha",
            BackupOperationSegmentCodec.decode(source).vaultId,
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupOperationSegmentCodec.decode(over)
        }
    }

    @Test
    fun embeddedMutationMustBeCanonicalUnpaddedAndMatchEnvelopeIdentity() {
        val canonical = BackupOperationSegmentCodec.fromJournalEntries(
            VaultId("vault-alpha"),
            listOf(journalEntry("operation-1", 41, 0, "tag-1")),
        )
        val entry = canonical.entries.single()
        val decodedMutation = Base64.getDecoder().decode(entry.payloadBase64)
        val nonCanonicalMutation = decodedMutation.toString(Charsets.UTF_8)
            .replace(""""formatVersion":1""", """ "formatVersion":1""")
            .toByteArray()
        val invalid = listOf(
            canonical.copy(
                entries = listOf(
                    entry.copy(
                        payloadBase64 = Base64.getEncoder()
                            .withoutPadding()
                            .encodeToString(nonCanonicalMutation),
                    ),
                ),
            ),
            canonical.copy(entries = listOf(entry.copy(payloadBase64 = "${entry.payloadBase64}="))),
            canonical.copy(entries = listOf(entry.copy(objectId = "tag-other"))),
            canonical.copy(entries = listOf(entry.copy(objectType = "TASK"))),
        )

        try {
            invalid.forEach { payload ->
                assertThrows(IllegalArgumentException::class.java) {
                    BackupOperationSegmentCodec.encode(payload)
                }
            }
        } finally {
            decodedMutation.fill(0)
            nonCanonicalMutation.fill(0)
        }
    }

    @Test
    fun deletionMutationIdentityMustMatchEnvelopeIdentity() {
        val payload = BackupMutationCodec.encode(
            BackupMutationPayloadV1(
                mutationKind = BackupMutationKind.DELETE,
                record = null,
                deletedFamily = BackupRecordFamily.TASK_DEPENDENCY,
                deletedIdentity = listOf("task-2", "task-1"),
            ),
        )
        val journal = BackupJournalEntity(
            operationId = "operation-delete",
            vaultId = "vault-alpha",
            generation = 41,
            sequence = 0,
            payloadFormatVersion = 1,
            mutationKind = "DELETE",
            objectId = "6:task-2|6:task-1",
            objectType = "TASK_DEPENDENCY",
            payload = payload,
            revisionWallMillis = 1,
            revisionLogical = 0,
            sourceDeviceId = "device-alpha",
        )

        val segment = BackupOperationSegmentCodec.fromJournalEntries(
            VaultId("vault-alpha"),
            listOf(journal),
        )
        assertEquals(
            listOf("task-2", "task-1"),
            BackupMutationCodec.decode(
                Base64.getDecoder().decode(segment.entries.single().payloadBase64),
            ).deletedIdentity,
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupOperationSegmentCodec.fromJournalEntries(
                VaultId("vault-alpha"),
                listOf(journal.copy(objectId = "task-2|task-1")),
            )
        }
    }

    @Test
    fun maximumLengthCompositeMutationIdentityRemainsRepresentable() {
        val taskId = "a".repeat(200)
        val prerequisiteId = "b".repeat(200)
        val payload = BackupMutationCodec.encode(
            BackupMutationPayloadV1(
                mutationKind = BackupMutationKind.DELETE,
                record = null,
                deletedFamily = BackupRecordFamily.TASK_DEPENDENCY,
                deletedIdentity = listOf(taskId, prerequisiteId),
            ),
        )
        val journal = BackupJournalEntity(
            operationId = "operation-maximum-composite",
            vaultId = "vault-alpha",
            generation = 41,
            sequence = 0,
            payloadFormatVersion = 1,
            mutationKind = "DELETE",
            objectId = "200:$taskId|200:$prerequisiteId",
            objectType = "TASK_DEPENDENCY",
            payload = payload,
            revisionWallMillis = 1,
            revisionLogical = 0,
            sourceDeviceId = "device-alpha",
        )

        val segment = BackupOperationSegmentCodec.fromJournalEntries(
            VaultId("vault-alpha"),
            listOf(journal),
        )

        assertEquals(409, segment.entries.single().objectId.length)
    }

    @Test
    fun legacyJournalPayloadsNeverBecomePostBaseSegments() {
        val legacy = journalEntry("legacy-operation", 41, 0, "tag-1").copy(
            payloadFormatVersion = 0,
            mutationKind = "LEGACY",
        )

        assertThrows(IllegalArgumentException::class.java) {
            BackupOperationSegmentCodec.fromJournalEntries(
                VaultId("vault-alpha"),
                listOf(legacy),
            )
        }
    }

    @Test
    fun journalVaultMutationKindAndCanonicalPayloadAreValidatedBeforeCapture() {
        val canonical = journalEntry("operation-1", 41, 0, "tag-1")
        val nonCanonical = canonical.payload.toString(Charsets.UTF_8)
            .replace(""""formatVersion":1""", """"formatVersion":1 """)
            .toByteArray()
        val invalid = listOf(
            canonical.copy(vaultId = "vault-other"),
            canonical.copy(mutationKind = "DELETE"),
            canonical.copy(payload = nonCanonical),
        )

        try {
            invalid.forEach { journal ->
                assertThrows(IllegalArgumentException::class.java) {
                    BackupOperationSegmentCodec.fromJournalEntries(
                        VaultId("vault-alpha"),
                        listOf(journal),
                    )
                }
            }
        } finally {
            nonCanonical.fill(0)
        }
    }

    @Test
    fun invalidUtf8FutureVersionsAndNonCanonicalOuterJsonAreRejected() {
        val canonical = BackupOperationSegmentCodec.encode(
            BackupOperationSegmentCodec.fromJournalEntries(
                VaultId("vault-alpha"),
                listOf(journalEntry("operation-1", 41, 0, "tag-1")),
            ),
        )
        val invalidUtf8 = canonical.copyOf().also {
            val index = it.indexOfSubsequence("device-alpha".toByteArray())
            it[index] = 0xc3.toByte()
            it[index + 1] = 0x28
        }
        val future = canonical.toString(Charsets.UTF_8)
            .replace(""""formatVersion":1""", """"formatVersion":2""")
            .toByteArray()
        val nonCanonical = canonical.toString(Charsets.UTF_8)
            .replace(""""entryCount":1}""", """"entryCount":1 }""")
            .toByteArray()

        listOf(invalidUtf8, future, nonCanonical).forEach { source ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupOperationSegmentCodec.decode(source)
            }
        }
    }

    @Test
    fun decoderPreservesCallerInputAndClearsTransferredBuffers() {
        val source = BackupOperationSegmentCodec.encode(
            BackupOperationSegmentCodec.fromJournalEntries(
                VaultId("vault-alpha"),
                listOf(journalEntry("operation-1", 41, 0, "tag-1")),
            ),
        )
        val expected = source.copyOf()

        BackupOperationSegmentCodec.decode(source)
        assertArrayEquals(expected, source)

        val owned = source.copyOf()
        BackupOperationSegmentCodec.decodeOwned(owned)
        assertArrayEquals(ByteArray(owned.size), owned)

        val invalidOwned = byteArrayOf(0xc3.toByte())
        assertThrows(IllegalArgumentException::class.java) {
            BackupOperationSegmentCodec.decodeOwned(invalidOwned)
        }
        assertArrayEquals(ByteArray(invalidOwned.size), invalidOwned)
    }

    private fun journalEntry(
        operationId: String,
        generation: Long,
        sequence: Int,
        tagId: String,
        revisionWallMillis: Long = 1,
        revisionLogical: Int = 0,
    ): BackupJournalEntity = BackupJournalEntity(
        operationId = operationId,
        vaultId = "vault-alpha",
        generation = generation,
        sequence = sequence,
        payloadFormatVersion = 1,
        mutationKind = "UPSERT",
        objectId = tagId,
        objectType = "TAG",
        payload = BackupPayloadTestFixtures.tagMutation(tagId),
        revisionWallMillis = revisionWallMillis,
        revisionLogical = revisionLogical,
        sourceDeviceId = "device-alpha",
    )

    private fun exactSegmentBytes(target: Int): ByteArray {
        val queryByteCounts = MutableList(4) { 2 * 1024 * 1024 }
        val fixedEntries = queryByteCounts.mapIndexed { index, byteCount ->
            largeSavedViewEntry(
                index = index,
                generation = 41,
                sequence = index,
                queryByteCount = byteCount,
                sourceDeviceId = "d",
            )
        }
        var low = 0
        var high = 2 * 1024 * 1024
        while (low < high) {
            val middle = (low + high + 1) / 2
            val size = segmentTextSize(
                fixedEntries + largeSavedViewEntry(4, 53, 0, middle, "d"),
            )
            if (size <= target) low = middle else high = middle - 1
        }
        var finalEntry = largeSavedViewEntry(4, 53, 0, low, "d")
        val difference = target - segmentTextSize(fixedEntries + finalEntry)
        require(difference in 0..199)
        finalEntry = finalEntry.copy(sourceDeviceId = "d".repeat(difference + 1))
        val payload = BackupOperationSegmentPayloadV1(
            vaultId = "vault-alpha",
            firstGeneration = 41,
            lastGeneration = 53,
            entries = fixedEntries + finalEntry,
            entryCount = 5,
        )
        return manualSegmentJson(payload).toByteArray().also {
            check(it.size == target)
        }
    }

    private fun largeSavedViewEntry(
        index: Int,
        generation: Long,
        sequence: Int,
        queryByteCount: Int,
        sourceDeviceId: String,
    ): BackupSegmentEntryV1 {
        val id = "view-large-$index"
        val innerBase64Length = unpaddedBase64Length(queryByteCount)
        val mutation = (
            """{"formatVersion":1,"minimumReaderVersion":1,"mutationKind":"UPSERT",""" +
                """"record":{"family":"SAVED_VIEW","identity":["$id"],"fields":[""" +
                """{"name":"id","type":"STRING","value":"$id"},""" +
                """{"name":"workspaceId","type":"STRING","value":"workspace-1"},""" +
                """{"name":"name","type":"STRING","value":"Large"},""" +
                """{"name":"encryptedQuery","type":"BYTES","value":"""" +
                "A".repeat(innerBase64Length) +
                """"}]},"deletedFamily":null,"deletedIdentity":null}"""
            )
        return BackupSegmentEntryV1(
            operationId = "operation-large-$index",
            generation = generation,
            sequence = sequence,
            objectId = id,
            objectType = "SAVED_VIEW",
            revisionWallMillis = 1,
            revisionLogical = 0,
            sourceDeviceId = sourceDeviceId,
            payloadBase64 = Base64.getEncoder()
                .withoutPadding()
                .encodeToString(mutation.toByteArray()),
        )
    }

    private fun segmentTextSize(entries: List<BackupSegmentEntryV1>): Int =
        manualSegmentJson(
            BackupOperationSegmentPayloadV1(
                vaultId = "vault-alpha",
                firstGeneration = 41,
                lastGeneration = 53,
                entries = entries,
                entryCount = entries.size,
            ),
        ).toByteArray().size

    private fun manualSegmentJson(payload: BackupOperationSegmentPayloadV1): String =
        """{"formatVersion":1,"minimumReaderVersion":1,"vaultId":"${payload.vaultId}",""" +
            """"firstGeneration":${payload.firstGeneration},"lastGeneration":${payload.lastGeneration},""" +
            """"entries":[""" +
            payload.entries.joinToString(",") { entry ->
                """{"operationId":"${entry.operationId}","generation":${entry.generation},""" +
                    """"sequence":${entry.sequence},"objectId":"${entry.objectId}",""" +
                    """"objectType":"${entry.objectType}","revisionWallMillis":${entry.revisionWallMillis},""" +
                    """"revisionLogical":${entry.revisionLogical},"sourceDeviceId":"${entry.sourceDeviceId}",""" +
                    """"payloadBase64":"${entry.payloadBase64}"}"""
            } +
            """],"entryCount":${payload.entryCount}}"""

    private fun unpaddedBase64Length(byteCount: Int): Int =
        (byteCount / 3) * 4 + when (byteCount % 3) {
            0 -> 0
            1 -> 2
            else -> 3
        }
}
