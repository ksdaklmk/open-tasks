package app.opentasks.core.data.backup

import app.opentasks.core.model.BackupGeneration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class BackupPayloadGoldenTest {
    @Test
    fun independentSnapshotFixtureMatchesExactCanonicalBytesCountsAndDigest() {
        val fixture = loadFixture("snapshot")
        val plaintext = fixture.plaintextUtf8Hex.hexToBytes()
        val expectedCounts = linkedMapOf(
            BackupRecordFamily.VAULT to 1,
            BackupRecordFamily.WORKSPACE to 1,
            BackupRecordFamily.MEMBER to 1,
            BackupRecordFamily.PROJECT to 1,
            BackupRecordFamily.WORKFLOW_STATUS to 10,
            BackupRecordFamily.MILESTONE to 1,
            BackupRecordFamily.TASK to 2,
            BackupRecordFamily.CHECKLIST_ITEM to 1,
            BackupRecordFamily.TASK_DEPENDENCY to 1,
            BackupRecordFamily.TAG to 1,
            BackupRecordFamily.TASK_TAG to 1,
            BackupRecordFamily.REMINDER to 1,
            BackupRecordFamily.ATTACHMENT to 1,
            BackupRecordFamily.ACTIVITY_ENTRY to 1,
            BackupRecordFamily.TIME_ENTRY to 1,
            BackupRecordFamily.TEMPLATE to 1,
            BackupRecordFamily.SAVED_VIEW to 1,
            BackupRecordFamily.NOTE to 2,
            BackupRecordFamily.TOMBSTONE to 1,
        )

        try {
            assertEquals(
                "ce45327286bc1d55a4fa3937fe1c05640a4016b497ff0b7c62d50e772c1a374b",
                fixture.plaintextSha256,
            )
            assertEquals(fixture.plaintextSha256, plaintext.sha256())
            assertEquals(fixture.plaintextUtf8Hex, plaintext.toHex())
            assertTrue(
                fixture.plaintextUtf8Hex.contains(
                    "52c3a973756dc3a9207461736b20f09f9a80",
                ),
            )

            val decoded = BackupSnapshotCodec.decode(plaintext)
            assertEquals("vault-alpha", decoded.vaultId)
            assertEquals(53, decoded.coveredGeneration)
            assertEquals(expectedCounts, decoded.records.groupingBy(BackupRecordV1::family).eachCount())
            val canonical = BackupSnapshotCodec.encode(decoded)
            try {
                assertArrayEquals(plaintext, canonical)
            } finally {
                canonical.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    @Test
    fun independentSegmentFixtureMatchesExactCanonicalBytesIdentityAndDigest() {
        val fixture = loadFixture("operation-segment")
        val plaintext = fixture.plaintextUtf8Hex.hexToBytes()

        try {
            assertEquals(
                "21ef8dd2e577f6ae9bc2771a6843c773e12bc058565b5108ece1cd7668491cef",
                fixture.plaintextSha256,
            )
            assertEquals(fixture.plaintextSha256, plaintext.sha256())
            assertEquals(fixture.plaintextUtf8Hex, plaintext.toHex())

            val decoded = BackupOperationSegmentCodec.decode(plaintext)
            assertEquals("vault-alpha", decoded.vaultId)
            assertEquals(41, decoded.firstGeneration)
            assertEquals(53, decoded.lastGeneration)
            assertEquals(3, decoded.entryCount)
            assertEquals(
                listOf("operation-1", "operation-2", "operation-3"),
                decoded.entries.map(BackupSegmentEntryV1::operationId),
            )
            assertEquals(
                "segment:41:53",
                BackupPayloadIdentities.segmentObjectId(
                    BackupGeneration(decoded.firstGeneration),
                    BackupGeneration(decoded.lastGeneration),
                ),
            )
            val canonical = BackupOperationSegmentCodec.encode(decoded)
            try {
                assertArrayEquals(plaintext, canonical)
            } finally {
                canonical.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun loadFixture(name: String): BackupPayloadGoldenFixture {
        val classLoader = requireNotNull(javaClass.classLoader)
        val resource = requireNotNull(
            classLoader.getResourceAsStream("backup-format/v1/$name.json"),
        )
        return resource.bufferedReader(Charsets.UTF_8).use { reader ->
            Json.decodeFromString<BackupPayloadGoldenFixture>(reader.readText())
        }
    }
}

@Serializable
private data class BackupPayloadGoldenFixture(
    val plaintextUtf8Hex: String,
    val plaintextSha256: String,
)

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).let { digest ->
        try {
            digest.toHex()
        } finally {
            digest.fill(0)
        }
    }
