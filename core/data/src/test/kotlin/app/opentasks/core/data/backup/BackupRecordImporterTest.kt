package app.opentasks.core.data.backup

import app.opentasks.core.data.db.TagEntity
import app.opentasks.core.data.db.VAULT_DATABASE_VERSION
import app.opentasks.core.data.db.VaultEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
        val failure = assertThrows(IllegalArgumentException::class.java) {
            RecoveryImportPlan.normalizeForRecovery(
                vaultRecord(schemaVersion = VAULT_DATABASE_VERSION + 1),
            )
        }

        // Pins which of the two adjacent requires fired: the schema-version
        // gate, not the reader-version gate one line below it in
        // normalizeForRecovery -- both throw the same exception type, so
        // only the message actually distinguishes them.
        assertEquals("The backup vault schema is not readable", failure.message)
    }

    @Test
    fun aZeroRowMarkerIsRejected() {
        // rawVaultRecord bypasses VaultEntity.toBackupRecordV1()'s own
        // construction-time validation so this exercises what
        // normalizeForRecovery itself does with an out-of-range marker --
        // the path an untrusted recovered record actually takes.
        // BackupRecordFields.of(record), the first call inside
        // normalizeForRecovery, runs BackupMutationCodec.validateRecord
        // first, and that family-wide positivity rule rejects a
        // non-positive schemaVersion before normalizeForRecovery's own
        // `in 1..RECOVERED_SCHEMA_VERSION` require is ever reached -- so
        // this is the true lower-bound guard, not that later require.
        val failure = assertThrows(IllegalArgumentException::class.java) {
            RecoveryImportPlan.normalizeForRecovery(rawVaultRecord(schemaVersion = "0"))
        }

        assertEquals("schemaVersion must be positive", failure.message)
    }

    @Test
    fun aNegativeRowMarkerIsRejected() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            RecoveryImportPlan.normalizeForRecovery(rawVaultRecord(schemaVersion = "-1"))
        }

        assertEquals("schemaVersion must be positive", failure.message)
    }

    @Test
    fun aLowRowMarkerIsNormalizedToTheDatabaseVersion() {
        val normalized = RecoveryImportPlan.normalizeForRecovery(vaultRecord(schemaVersion = 6))

        assertEquals(VAULT_DATABASE_VERSION.toString(), schemaVersionValue(normalized))
    }

    @Test
    fun aNonVaultRecordPassesThroughUnchanged() {
        val record = TagEntity(
            id = "tag-1",
            workspaceId = "workspace-1",
            name = "Urgent",
        ).toBackupRecordV1()

        val normalized = RecoveryImportPlan.normalizeForRecovery(record)

        assertSame(record, normalized)
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

    /**
     * A VAULT record built from raw fields rather than [VaultEntity], so an
     * out-of-range [schemaVersion] string reaches
     * [RecoveryImportPlan.normalizeForRecovery] unfiltered by the entity's
     * own field validation.
     */
    private fun rawVaultRecord(schemaVersion: String): BackupRecordV1 = BackupRecordV1(
        family = BackupRecordFamily.VAULT,
        identity = listOf("vault-1"),
        fields = listOf(
            BackupFieldV1("id", BackupFieldType.STRING, "vault-1"),
            BackupFieldV1("createdAtEpochMillis", BackupFieldType.LONG, "1700000000000"),
            BackupFieldV1("schemaVersion", BackupFieldType.INT, schemaVersion),
            BackupFieldV1("cryptoVersion", BackupFieldType.INT, "1"),
            BackupFieldV1("minimumReaderVersion", BackupFieldType.INT, "1"),
        ),
    )
}
