package app.opentasks.core.domain

import app.opentasks.core.model.AndroidBackupStatus
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.RecoveryPassphraseValidation
import app.opentasks.core.model.Revision
import app.opentasks.core.model.VaultId
import app.opentasks.core.sync.CloudBounds
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

enum class BackupMutationKind {
    LEGACY,
    UPSERT,
    DELETE,
}

data class BackupJournalEntry(
    val operationId: String,
    val vaultId: VaultId,
    val generation: BackupGeneration,
    val sequence: Int,
    val payloadFormatVersion: Int,
    val mutationKind: BackupMutationKind,
    val objectId: String,
    val objectType: String,
    val payload: ByteArray,
    val revision: Revision,
)

interface BackupJournalReader {
    suspend fun currentGeneration(vaultId: VaultId): BackupGeneration

    suspend fun entriesAfter(
        vaultId: VaultId,
        generation: BackupGeneration,
        limit: Int,
    ): List<BackupJournalEntry>
}

interface BackupCoordinator {
    suspend fun request()
}

fun interface BackupCaptureSource<T> {
    suspend fun capture(): T
}

interface AndroidBackupStatusSource {
    val status: StateFlow<AndroidBackupStatus>
}

object BackupPolicy {
    const val SNAPSHOT_OPERATION_INTERVAL = 5_000
    val SNAPSHOT_TIME_INTERVAL: Duration = Duration.ofDays(7)
    const val MAX_PORTABLE_PACKAGE_BYTES = 24L * 1024 * 1024
    const val MAX_SEGMENT_PLAINTEXT_BYTES =
        16 * 1024 * 1024 - 33

    fun requiresSnapshot(
        operationsSinceBase: Int,
        baseProducedAt: Instant,
        now: Instant,
    ): Boolean =
        operationsSinceBase >= SNAPSHOT_OPERATION_INTERVAL ||
            !now.isBefore(baseProducedAt.plus(SNAPSHOT_TIME_INTERVAL))

    fun splitIntoSegments(
        entries: List<BackupJournalEntry>,
        plaintextBytes: (BackupJournalEntry) -> Int,
    ): List<List<BackupJournalEntry>> {
        val segments = mutableListOf<List<BackupJournalEntry>>()
        var currentSegment = mutableListOf<BackupJournalEntry>()
        var currentBytes = 0

        entries
            .sortedWith(
                compareBy<BackupJournalEntry> { it.generation.value }
                    .thenBy(BackupJournalEntry::sequence),
            )
            .forEach { entry ->
                val entryBytes = plaintextBytes(entry)
                require(entryBytes in 0..MAX_SEGMENT_PLAINTEXT_BYTES)
                val reachesOperationLimit =
                    currentSegment.size == CloudBounds.MAX_OPERATIONS_PER_SEGMENT
                val exceedsByteLimit = currentBytes > MAX_SEGMENT_PLAINTEXT_BYTES - entryBytes
                if (currentSegment.isNotEmpty() && (reachesOperationLimit || exceedsByteLimit)) {
                    segments += currentSegment
                    currentSegment = mutableListOf()
                    currentBytes = 0
                }
                currentSegment += entry
                currentBytes += entryBytes
            }

        if (currentSegment.isNotEmpty()) {
            segments += currentSegment
        }
        return segments
    }

    fun recoveryBaseGenerationsToRetain(
        currentGeneration: BackupGeneration,
        previousGeneration: BackupGeneration?,
    ): List<BackupGeneration> =
        listOfNotNull(currentGeneration, previousGeneration)
            .distinct()
}

object RecoveryPassphrasePolicy {
    const val MIN_CODE_POINTS = 12
    const val MAX_CODE_POINTS = 128

    fun validate(
        value: String,
        confirmation: String,
    ): RecoveryPassphraseValidation {
        val codePoints = value.codePointCount(0, value.length)
        return when {
            codePoints < MIN_CODE_POINTS -> RecoveryPassphraseValidation.TooShort
            codePoints > MAX_CODE_POINTS -> RecoveryPassphraseValidation.TooLong
            value != confirmation -> RecoveryPassphraseValidation.ConfirmationMismatch
            else -> RecoveryPassphraseValidation.Valid
        }
    }
}
