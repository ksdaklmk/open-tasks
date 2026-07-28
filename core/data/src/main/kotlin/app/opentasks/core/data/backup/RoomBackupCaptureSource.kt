package app.opentasks.core.data.backup

import androidx.room.withTransaction
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.domain.BackupCaptureSource
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.VaultId

data class StructuredBackupCapture(
    val vaultId: VaultId,
    val generation: BackupGeneration,
    val records: List<BackupRecordV1>,
)

class RoomBackupCaptureSource(
    private val database: VaultDatabase,
    private val vaultId: VaultId,
) : BackupCaptureSource<StructuredBackupCapture> {
    override suspend fun capture(): StructuredBackupCapture =
        database.withTransaction {
            val state = database.backupStateDao().require(vaultId.value)
            StructuredBackupCapture(
                vaultId = vaultId,
                generation = BackupGeneration(state.currentGeneration),
                records = database.backupCaptureDao().allRecords(vaultId.value),
            )
        }
}
