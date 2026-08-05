package app.opentasks.core.data.backup

import app.opentasks.core.data.db.VAULT_DATABASE_VERSION
import app.opentasks.core.data.db.VaultEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `RecoveryImportPlan.normalizeForRecovery` is the sole gate deciding which
 * captured VAULT row markers a recovery or `.otvault` import accepts. It
 * must accept every marker a real device can carry, including one written by
 * a migration that ran after the vault was seeded, and reject only markers
 * this reader cannot represent at all.
 */
class BackupRecordImporterTest {
    @Test
    fun aMigratedRowMarkerIsAcceptedAndNormalizedToTheDatabaseVersion() {
        val migrated = vaultRecord(schemaVersion = 8)

        val normalized = RecoveryImportPlan.normalizeForRecovery(migrated)

        assertEquals("8", schemaVersionValue(migrated))
        assertEquals(VAULT_DATABASE_VERSION.toString(), schemaVersionValue(normalized))
    }

    @Test
    fun aRowMarkerAtTheDatabaseVersionIsAcceptedUnchanged() {
        val normalized = RecoveryImportPlan.normalizeForRecovery(
            vaultRecord(schemaVersion = VAULT_DATABASE_VERSION),
        )

        assertEquals(VAULT_DATABASE_VERSION.toString(), schemaVersionValue(normalized))
    }

    @Test
    fun aRowMarkerPastTheDatabaseVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryImportPlan.normalizeForRecovery(
                vaultRecord(schemaVersion = VAULT_DATABASE_VERSION + 1),
            )
        }
    }

    @Test
    fun aLowRowMarkerIsNormalizedToTheDatabaseVersion() {
        val normalized = RecoveryImportPlan.normalizeForRecovery(vaultRecord(schemaVersion = 6))

        assertEquals(VAULT_DATABASE_VERSION.toString(), schemaVersionValue(normalized))
    }

    private fun vaultRecord(schemaVersion: Int): BackupRecordV1 = VaultEntity(
        id = "vault-1",
        storageMode = "LOCAL",
        createdAtEpochMillis = 1_700_000_000_000,
        schemaVersion = schemaVersion,
        cryptoVersion = 1,
        minimumReaderVersion = 1,
    ).toBackupRecordV1()

    private fun schemaVersionValue(record: BackupRecordV1): String? =
        record.fields.single { it.name == "schemaVersion" }.value
}
