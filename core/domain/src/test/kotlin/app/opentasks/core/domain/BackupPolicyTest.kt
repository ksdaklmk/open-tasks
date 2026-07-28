package app.opentasks.core.domain

import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.RecoveryPassphraseValidation
import app.opentasks.core.model.Revision
import app.opentasks.core.model.VaultId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPolicyTest {
    @Test
    fun snapshotBecomesDueAtFiveThousandOperations() {
        assertTrue(
            BackupPolicy.requiresSnapshot(
                operationsSinceBase = 5_000,
                baseProducedAt = Instant.parse("2026-07-20T00:00:00Z"),
                now = Instant.parse("2026-07-20T01:00:00Z"),
            ),
        )
    }

    @Test
    fun snapshotBecomesDueAtSevenDays() {
        assertTrue(
            BackupPolicy.requiresSnapshot(
                operationsSinceBase = 1,
                baseProducedAt = Instant.parse("2026-07-20T00:00:00Z"),
                now = Instant.parse("2026-07-27T00:00:00Z"),
            ),
        )
    }

    @Test
    fun passphraseUsesCodePointsWithoutTrimmingOrNormalising() {
        val value = "1234567890😀x"

        assertEquals(
            RecoveryPassphraseValidation.Valid,
            RecoveryPassphrasePolicy.validate(value, value),
        )
        assertEquals(
            RecoveryPassphraseValidation.TooShort,
            RecoveryPassphrasePolicy.validate(" 123456789 ", " 123456789 "),
        )
        assertEquals(
            RecoveryPassphraseValidation.ConfirmationMismatch,
            RecoveryPassphrasePolicy.validate("12345678901é", "12345678901e\u0301"),
        )
    }

    @Test
    fun passphraseRejectsMoreThanOneHundredTwentyEightCodePoints() {
        val value = "a".repeat(129)

        assertEquals(
            RecoveryPassphraseValidation.TooLong,
            RecoveryPassphrasePolicy.validate(value, value),
        )
    }

    @Test
    fun backupGenerationRejectsNegativeValues() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupGeneration(-1)
        }
    }

    @Test
    fun backupBoundsMatchPortablePackageAndEncryptedSegmentLimits() {
        assertEquals(25_165_824L, BackupPolicy.MAX_PORTABLE_PACKAGE_BYTES)
        assertEquals(16_777_183, BackupPolicy.MAX_SEGMENT_PLAINTEXT_BYTES)
    }

    @Test
    fun segmentSplittingSortsByGenerationAndSequenceBeforeApplyingByteLimit() {
        val later = journalEntry("later", generation = 2, sequence = 0)
        val second = journalEntry("second", generation = 1, sequence = 1)
        val first = journalEntry("first", generation = 1, sequence = 0)

        val segments = BackupPolicy.splitIntoSegments(
            entries = listOf(later, second, first),
            plaintextBytes = { entry ->
                when (entry.operationId) {
                    "first" -> 1
                    "second" -> BackupPolicy.MAX_SEGMENT_PLAINTEXT_BYTES - 1
                    else -> BackupPolicy.MAX_SEGMENT_PLAINTEXT_BYTES
                }
            },
        )

        assertEquals(
            listOf(
                listOf("first", "second"),
                listOf("later"),
            ),
            segments.map { segment -> segment.map(BackupJournalEntry::operationId) },
        )
    }

    @Test
    fun recoveryBaseRetentionKeepsOnlyCurrentAndPreviousGenerations() {
        assertEquals(
            listOf(BackupGeneration(12), BackupGeneration(11)),
            BackupPolicy.recoveryBaseGenerationsToRetain(
                currentGeneration = BackupGeneration(12),
                previousGeneration = BackupGeneration(11),
            ),
        )
    }

    private fun journalEntry(
        operationId: String,
        generation: Long,
        sequence: Int,
    ): BackupJournalEntry = BackupJournalEntry(
        operationId = operationId,
        vaultId = VaultId("vault"),
        generation = BackupGeneration(generation),
        sequence = sequence,
        payloadFormatVersion = 1,
        mutationKind = BackupMutationKind.UPSERT,
        objectId = "task:$operationId",
        objectType = "TASK",
        payload = byteArrayOf(),
        revision = Revision(DeviceId("device"), 0, 0),
    )
}
