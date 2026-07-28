package app.opentasks.core.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.data.db.VaultDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultDatabaseMigrationInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseNames = mutableListOf<String>()

    @get:Rule
    val migrationTestHelper = MigrationTestHelper(
        instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        databaseClass = VaultDatabase::class.java,
    )

    @After
    fun deleteDatabases() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun migrationCopiesEveryLegacyRowInDeterministicOrder() {
        val databaseName = databaseName("legacy")
        val legacyRows = listOf(
            LegacyFixture(
                operationId = "op-c",
                deviceId = "device-b",
                objectId = "task-c",
                objectType = "TASK",
                payload = byteArrayOf(0x43, 0x00, 0x44),
                revisionWallMillis = 200,
                revisionLogical = 4,
                uploadedAtEpochMillis = null,
            ),
            LegacyFixture(
                operationId = "op-b",
                deviceId = "device-a",
                objectId = "project-b",
                objectType = "PROJECT",
                payload = byteArrayOf(0x00),
                revisionWallMillis = 200,
                revisionLogical = 4,
                uploadedAtEpochMillis = 1_234,
            ),
            LegacyFixture(
                operationId = "op-a",
                deviceId = "device-a",
                objectId = "tag-a",
                objectType = "TAG",
                payload = byteArrayOf(0x41, 0x42),
                revisionWallMillis = 200,
                revisionLogical = 4,
                uploadedAtEpochMillis = null,
            ),
        )
        createV5(databaseName).use { database ->
            insertVault(database, id = "vault-a", storageMode = "DRIVE_PRIMARY")
            legacyRows.forEach { insertLegacyOperation(database, it) }
        }

        val migrated = migrate(databaseName)
        val journalRows = migrated.readJournalRows()
        val expectedById = legacyRows.associateBy(LegacyFixture::operationId)

        assertEquals(
            listOf("op-a", "op-b", "op-c"),
            journalRows.map(JournalFixture::operationId),
        )
        assertEquals(listOf(1L, 2L, 3L), journalRows.map(JournalFixture::generation))
        assertTrue(journalRows.all { it.sequence == 0 })
        assertTrue(journalRows.all { it.payloadFormatVersion == 0 })
        assertTrue(journalRows.all { it.mutationKind == "LEGACY" })
        journalRows.forEach { actual ->
            val expected = requireNotNull(expectedById[actual.operationId])
            assertEquals(expected.objectId, actual.objectId)
            assertEquals(expected.objectType, actual.objectType)
            assertArrayEquals(expected.payload, actual.payload)
            assertEquals(expected.revisionWallMillis, actual.revisionWallMillis)
            assertEquals(expected.revisionLogical, actual.revisionLogical)
            assertEquals(expected.deviceId, actual.sourceDeviceId)
        }
        migrated.query("SELECT * FROM backup_state WHERE vaultId = 'vault-a'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3L, cursor.getLong(cursor.getColumnIndexOrThrow("currentGeneration")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("legacyOutboxCoveredAtGeneration")))
            assertNull(
                cursor.getString(cursor.getColumnIndexOrThrow("legacyOutboxCoveredAtGeneration")),
            )
        }
        assertEquals("LOCAL", migrated.stringValue("SELECT storageMode FROM vaults"))
        assertEquals(6L, migrated.longValue("SELECT schemaVersion FROM vaults"))
        assertEquals(3L, migrated.longValue("SELECT COUNT(*) FROM sync_operations"))
        migrated.close()
    }

    @Test
    fun migrationWithoutLegacyRowsCreatesGenerationZeroState() {
        val databaseName = databaseName("empty")
        createV5(databaseName).use { database ->
            insertVault(database, id = "vault-empty", storageMode = "LOCAL")
        }

        migrate(databaseName).use { migrated ->
            assertEquals(
                0L,
                migrated.longValue(
                    "SELECT currentGeneration FROM backup_state WHERE vaultId = 'vault-empty'",
                ),
            )
            assertEquals(0L, migrated.longValue("SELECT COUNT(*) FROM backup_journal"))
        }
    }

    @Test
    fun migrationWithLegacyRowsAndMultipleVaultsFailsClosed() {
        val databaseName = databaseName("ambiguous")
        createV5(databaseName).use { database ->
            insertVault(database, id = "vault-a", storageMode = "LOCAL")
            insertVault(database, id = "vault-b", storageMode = "DRIVE_PRIMARY")
            insertLegacyOperation(
                database,
                LegacyFixture(
                    operationId = "op-a",
                    deviceId = "device-a",
                    objectId = "task-a",
                    objectType = "TASK",
                    payload = byteArrayOf(0x00),
                    revisionWallMillis = 1,
                    revisionLogical = 0,
                    uploadedAtEpochMillis = null,
                ),
            )
        }

        val error = assertThrows(IllegalStateException::class.java) {
            migrate(databaseName).close()
        }

        assertEquals(
            "Legacy backup operations cannot be assigned to multiple vaults",
            error.message,
        )
    }

    @Test
    fun migrationWithoutLegacyRowsCreatesIndependentStateForEveryVault() {
        val databaseName = databaseName("multiple-empty")
        createV5(databaseName).use { database ->
            insertVault(database, id = "vault-b", storageMode = "DRIVE_PRIMARY")
            insertVault(database, id = "vault-a", storageMode = "LOCAL")
        }

        migrate(databaseName).use { migrated ->
            migrated.query(
                "SELECT vaultId, currentGeneration FROM backup_state ORDER BY vaultId",
            ).use { cursor ->
                val states = buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0) to cursor.getLong(1))
                    }
                }
                assertEquals(listOf("vault-a" to 0L, "vault-b" to 0L), states)
            }
            assertEquals(
                2L,
                migrated.longValue(
                    "SELECT COUNT(*) FROM vaults WHERE storageMode = 'LOCAL' AND schemaVersion = 6",
                ),
            )
        }
    }

    @Test
    fun migrationCreatesAllV6TablesMatchingExportedSchema() {
        val databaseName = databaseName("schema")
        createV5(databaseName).use { database ->
            insertVault(database, id = "vault-a", storageMode = "LOCAL")
        }

        migrate(databaseName).use { migrated ->
            migrated.query(
                """
                SELECT name FROM sqlite_master
                WHERE type = 'table'
                    AND name IN (
                        'backup_journal',
                        'backup_state',
                        'vault_recovery_envelope'
                    )
                ORDER BY name
                """.trimIndent(),
            ).use { cursor ->
                val tableNames = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
                assertEquals(
                    listOf("backup_journal", "backup_state", "vault_recovery_envelope"),
                    tableNames,
                )
            }
        }
    }

    private fun databaseName(suffix: String): String =
        "vault-v5-v6-$suffix.db".also(databaseNames::add)

    private fun createV5(databaseName: String): SupportSQLiteDatabase =
        migrationTestHelper.createDatabase(databaseName, 5)

    private fun migrate(databaseName: String): SupportSQLiteDatabase =
        migrationTestHelper.runMigrationsAndValidate(
            databaseName,
            6,
            true,
            VaultDatabase.MIGRATION_5_6,
        )

    private fun insertVault(
        database: SupportSQLiteDatabase,
        id: String,
        storageMode: String,
    ) {
        database.execSQL(
            """
            INSERT INTO vaults (
                id, storageMode, createdAtEpochMillis, schemaVersion,
                cryptoVersion, minimumReaderVersion
            ) VALUES (?, ?, 1000, 5, 1, 1)
            """.trimIndent(),
            arrayOf(id, storageMode),
        )
    }

    private fun insertLegacyOperation(
        database: SupportSQLiteDatabase,
        operation: LegacyFixture,
    ) {
        database.execSQL(
            """
            INSERT INTO sync_operations (
                id, deviceId, objectId, objectType, encryptedPayload,
                revisionWallMillis, revisionLogical, uploadedAtEpochMillis
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                operation.operationId,
                operation.deviceId,
                operation.objectId,
                operation.objectType,
                operation.payload,
                operation.revisionWallMillis,
                operation.revisionLogical,
                operation.uploadedAtEpochMillis,
            ),
        )
    }

    private fun SupportSQLiteDatabase.readJournalRows(): List<JournalFixture> =
        query(
            """
            SELECT operationId, generation, sequence, payloadFormatVersion,
                   mutationKind, objectId, objectType, payload,
                   revisionWallMillis, revisionLogical, sourceDeviceId
            FROM backup_journal
            ORDER BY generation, sequence
            """.trimIndent(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        JournalFixture(
                            operationId = cursor.getString(0),
                            generation = cursor.getLong(1),
                            sequence = cursor.getInt(2),
                            payloadFormatVersion = cursor.getInt(3),
                            mutationKind = cursor.getString(4),
                            objectId = cursor.getString(5),
                            objectType = cursor.getString(6),
                            payload = cursor.getBlob(7),
                            revisionWallMillis = cursor.getLong(8),
                            revisionLogical = cursor.getInt(9),
                            sourceDeviceId = cursor.getString(10),
                        ),
                    )
                }
            }
        }

    private fun SupportSQLiteDatabase.longValue(query: String): Long =
        query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.stringValue(query: String): String =
        query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private data class LegacyFixture(
        val operationId: String,
        val deviceId: String,
        val objectId: String,
        val objectType: String,
        val payload: ByteArray,
        val revisionWallMillis: Long,
        val revisionLogical: Int,
        val uploadedAtEpochMillis: Long?,
    )

    private data class JournalFixture(
        val operationId: String,
        val generation: Long,
        val sequence: Int,
        val payloadFormatVersion: Int,
        val mutationKind: String,
        val objectId: String,
        val objectType: String,
        val payload: ByteArray,
        val revisionWallMillis: Long,
        val revisionLogical: Int,
        val sourceDeviceId: String,
    )
}
