package app.opentasks.core.data

import android.content.Context
import android.database.Cursor
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.data.backup.RECOVERED_SCHEMA_VERSION
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

    @Test
    fun migrate6To7PreservesStage2AndLegacyBytes() {
        val databaseName = databaseNameV6("preserved")
        lateinit var beforeTableNames: List<String>
        lateinit var before: Map<String, List<List<Any?>>>
        createV6(databaseName).use { database ->
            seedVersion6Fixture(database)
            beforeTableNames = database.tableNames()
            before = database.captureVersion6Bytes()
        }

        val migrated = migrateTo7(databaseName)

        assertEquals(before, migrated.capturePreservedBytes())
        assertEquals(0, migrated.longValue("SELECT COUNT(*) FROM remote_backup_config"))
        assertEquals(0, migrated.longValue("SELECT COUNT(*) FROM remote_backup_object"))
        assertEquals(0, migrated.longValue("SELECT COUNT(*) FROM remote_backup_operation"))
        assertEquals(
            (
                beforeTableNames + listOf(
                    "remote_backup_config",
                    "remote_backup_object",
                    "remote_backup_operation",
                )
                ).sorted(),
            migrated.tableNames(),
        )
        assertEquals(
            7L,
            migrated.longValue("SELECT schemaVersion FROM vaults WHERE id = 'vault-a'"),
        )
        migrated.close()
    }

    @Test
    fun migrate6To7CreatesRemoteTablesMatchingExportedSchema() {
        val databaseName = databaseNameV6("remote-schema")
        createV6(databaseName).use { database ->
            insertVaultV6(database, id = "vault-a")
        }

        migrateTo7(databaseName).use { migrated ->
            migrated.query(
                """
                SELECT name FROM sqlite_master
                WHERE type = 'table'
                    AND name IN (
                        'remote_backup_config',
                        'remote_backup_object',
                        'remote_backup_operation'
                    )
                ORDER BY name
                """.trimIndent(),
            ).use { cursor ->
                val tableNames = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
                assertEquals(
                    listOf(
                        "remote_backup_config",
                        "remote_backup_object",
                        "remote_backup_operation",
                    ),
                    tableNames,
                )
            }
        }
    }

    @Test
    fun migrate7To8PreservesRowsAndRebuildsAttachments() {
        val databaseName = databaseNameV7("preserved")
        lateinit var before: Map<String, List<List<Any?>>>
        createV7(databaseName).use { database ->
            seedVersion7Fixture(database)
            database.execSQL(
                "INSERT INTO attachments (id, taskId, displayNameCiphertext, mimeType," +
                    " byteCount, contentHash, keepOffline) VALUES ('att-1', 'task-a'," +
                    " x'6e616d65', 'image/png', 42, 'hash-a', 1)",
            )
            before = database.captureVersion7Bytes()
        }

        val migrated = migrateTo8(databaseName)

        assertEquals(before, migrated.captureVersion7Bytes())
        assertEquals(0, migrated.longValue("SELECT COUNT(*) FROM notes"))
        assertEquals(0, migrated.longValue("SELECT COUNT(*) FROM attachment_transfer"))
        assertEquals(
            listOf<Any?>("att-1", "task-a", "image/png", 42L, "hash-a", null, 0L, null),
            migrated.rowValues(
                "SELECT id, taskId, mimeType, byteCount, contentHash, blobSetId," +
                    " chunkCount, deletedAtEpochMillis FROM attachments",
            ),
        )
        assertEquals(
            8L,
            migrated.longValue("SELECT schemaVersion FROM vaults WHERE id = 'vault-a'"),
        )
        migrated.close()
    }

    @Test
    fun migrate8To9PreservesRowsAndAddsEmptyRetiredBlobSets() {
        val databaseName = databaseNameV8("preserved")
        lateinit var before: Map<String, List<List<Any?>>>
        createV8(databaseName).use { database ->
            seedVersion8Fixture(database)
            before = database.captureVersion8Bytes()
        }

        val migrated = migrateTo9(databaseName)

        assertEquals(before, migrated.captureVersion8Bytes())
        assertEquals(0L, migrated.longValue("SELECT COUNT(*) FROM retired_blob_sets"))
        // MIGRATION_8_9 does not bump the vault row marker, so a device
        // migrated through v7->v8 still carries marker 8 here -- this proves
        // that for the one concrete migration under test, and only on the
        // connected API 36/37 matrix. The bound itself is checked against
        // RECOVERED_SCHEMA_VERSION (== VAULT_DATABASE_VERSION), which any
        // migration necessarily satisfies, so this assertion cannot by
        // itself catch a future migration author who bumps the row marker
        // without widening the recovery import gate. BackupRecordImporterTest's
        // row-marker bound tests are the real, deterministic guard for that
        // class of bug: they exercise normalizeForRecovery directly on the
        // JVM and run on every testDebugUnitTest.
        assertTrue(
            "A migrated row marker must never exceed RECOVERED_SCHEMA_VERSION",
            migrated.longValue("SELECT schemaVersion FROM vaults WHERE id = 'vault-a'") <=
                RECOVERED_SCHEMA_VERSION,
        )
        migrated.close()
    }

    @Test
    fun migrate9To10PreservesRowsAddsEmptyTablesAndStampsMarker() {
        val databaseName = databaseNameV9("preserved")
        lateinit var before: Map<String, List<List<Any?>>>
        createV9(databaseName).use { database ->
            seedVersion9Fixture(database)
            before = database.captureVersion9Bytes()
        }

        val migrated = migrateTo10(databaseName)

        // Existing bytes byte-identical. The capture deliberately excludes
        // `vaults` (the row marker is stamped by design, asserted below)
        // and reads `workflow_statuses` with EXPLICIT v9 columns, because a
        // post-migration `SELECT *` would include the new `wipLimit` column
        // and could never byte-match the pre-migration capture.
        assertEquals(before, migrated.captureVersion9Bytes())
        assertEquals(0L, migrated.longValue("SELECT COUNT(*) FROM automation_rules"))
        assertEquals(0L, migrated.longValue("SELECT COUNT(*) FROM my_day_entries"))
        assertEquals(
            0L,
            migrated.longValue(
                "SELECT COUNT(*) FROM workflow_statuses WHERE wipLimit IS NOT NULL",
            ),
        )
        // MIGRATION_9_10 stamps the marker (the 7→8 precedent): a v9 app can
        // never read v10 data anyway, so the recovery refusal becomes a
        // legible upfront gate instead of a mid-decode field-count error.
        assertEquals(
            10L,
            migrated.longValue("SELECT schemaVersion FROM vaults WHERE id = 'vault-a'"),
        )
        assertTrue(
            migrated.longValue("SELECT schemaVersion FROM vaults WHERE id = 'vault-a'") <=
                RECOVERED_SCHEMA_VERSION,
        )
        migrated.close()
    }

    private fun databaseNameV9(suffix: String): String =
        "vault-v9-v10-$suffix.db".also(databaseNames::add)

    private fun createV9(databaseName: String): SupportSQLiteDatabase =
        migrationTestHelper.createDatabase(databaseName, 9)

    private fun migrateTo10(databaseName: String): SupportSQLiteDatabase =
        migrationTestHelper.runMigrationsAndValidate(
            databaseName,
            10,
            true,
            VaultDatabase.MIGRATION_9_10,
        )

    private fun insertVaultV9(database: SupportSQLiteDatabase, id: String) {
        database.execSQL(
            """
            INSERT INTO vaults (
                id, storageMode, createdAtEpochMillis, schemaVersion,
                cryptoVersion, minimumReaderVersion
            ) VALUES (?, 'LOCAL', 1000, 9, 1, 1)
            """.trimIndent(),
            arrayOf(id),
        )
    }

    /**
     * Populates a task, two attachments (one with a `blobSetId`, one
     * without), one `attachment_transfer` row, one `retired_blob_sets` row,
     * and one `workflow_statuses` row (9-column insert, no `wipLimit`) so
     * [migrate9To10PreservesRowsAddsEmptyTablesAndStampsMarker] can prove
     * the additive 9→10 migration changes no existing byte while starting
     * the new `automation_rules` and `my_day_entries` tables empty and the
     * new `wipLimit` column null.
     */
    private fun seedVersion9Fixture(database: SupportSQLiteDatabase) {
        insertVaultV9(database, id = "vault-a")
        database.execSQL(
            """
            INSERT INTO workspaces (id, vaultId, ownerId, name)
            VALUES ('workspace-a', 'vault-a', 'member-a', 'Workspace A')
            """.trimIndent(),
        )
        database.execSQL(
            "INSERT INTO members (id, displayName) VALUES ('member-a', 'Member A')",
        )
        database.execSQL(
            """
            INSERT INTO tasks (
                id, workspaceId, projectId, parentTaskId, statusId, semanticStatus,
                title, descriptionCiphertext, priority, startEpochMillis, startZoneId,
                dueEpochMillis, dueZoneId, recurrenceFrequency, recurrenceInterval,
                recurrenceWeekdays, recurrenceCount, recurrenceEndDate,
                recurrenceSeriesId, recurrenceAnchorEpochMillis, recurrenceAnchorZoneId,
                recurrenceOccurrenceIndex, estimateSeconds, milestoneId,
                completedAtEpochMillis, deletedAtEpochMillis, revisionWallMillis,
                revisionLogical, revisionDeviceId
            ) VALUES (
                'task-a', 'workspace-a', NULL, NULL, 'workflow-a', 'TODO',
                'Task A', ?, 'MEDIUM', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 10, 1, 'device-a'
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x01, 0x02, 0x03)),
        )
        database.execSQL(
            """
            INSERT INTO attachments (
                id, taskId, displayNameCiphertext, mimeType, byteCount, contentHash,
                blobSetId, chunkCount, deletedAtEpochMillis, revisionWallMillis,
                revisionLogical, revisionDeviceId
            ) VALUES (
                'attachment-blob', 'task-a', ?, 'application/pdf', 9000000, 'hash-a',
                'blob-set-a', 3, NULL, 10, 1, 'device-a'
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x04, 0x05)),
        )
        database.execSQL(
            """
            INSERT INTO attachments (
                id, taskId, displayNameCiphertext, mimeType, byteCount, contentHash,
                blobSetId, chunkCount, deletedAtEpochMillis, revisionWallMillis,
                revisionLogical, revisionDeviceId
            ) VALUES (
                'attachment-blobless', 'task-a', ?, 'application/octet-stream', 10,
                'hash-b', NULL, 0, NULL, 11, 1, 'device-a'
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x06)),
        )
        database.execSQL(
            """
            INSERT INTO attachment_transfer (
                blobSetId, attachmentId, taskId, phase, displayNameCiphertext, mimeType,
                declaredByteCount, contentHash, chunkCount, chunkStateEncoded,
                manifestProviderFileId, createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES (
                'blob-set-pending', 'attachment-pending', 'task-a', 'UPLOADING', ?,
                'application/zip', 4000000, NULL, NULL, '000', NULL, 10, 20
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x07)),
        )
        database.execSQL(
            """
            INSERT INTO retired_blob_sets (
                blobSetId, chunkCount, retiredAtEpochMillis, revisionWallMillis,
                revisionLogical, revisionDeviceId
            ) VALUES ('blob-set-retired', 2, 12, 10, 1, 'device-a')
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO workflow_statuses (
                id, projectId, name, semanticStatus, rank, archivedAtEpochMillis,
                revisionWallMillis, revisionLogical, revisionDeviceId
            ) VALUES (
                'workflow-a', NULL, 'Todo', 'TODO', 'a0', NULL, 10, 1, 'device-a'
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.captureVersion9Bytes(): Map<String, List<List<Any?>>> =
        buildMap {
            listOf("tasks", "attachments", "attachment_transfer", "retired_blob_sets")
                .forEach { table ->
                    put(table, captureRows("SELECT * FROM $table ORDER BY rowid"))
                }
            // Explicit v9 columns: byte-comparable across the ADD COLUMN.
            put(
                "workflow_statuses",
                captureRows(
                    "SELECT id, projectId, name, semanticStatus, rank, " +
                        "archivedAtEpochMillis, revisionWallMillis, " +
                        "revisionLogical, revisionDeviceId " +
                        "FROM workflow_statuses ORDER BY rowid",
                ),
            )
        }

    private fun databaseNameV8(suffix: String): String =
        "vault-v8-v9-$suffix.db".also(databaseNames::add)

    private fun createV8(databaseName: String): SupportSQLiteDatabase =
        migrationTestHelper.createDatabase(databaseName, 8)

    private fun migrateTo9(databaseName: String): SupportSQLiteDatabase =
        migrationTestHelper.runMigrationsAndValidate(
            databaseName,
            9,
            true,
            VaultDatabase.MIGRATION_8_9,
        )

    private fun insertVaultV8(database: SupportSQLiteDatabase, id: String) {
        database.execSQL(
            """
            INSERT INTO vaults (
                id, storageMode, createdAtEpochMillis, schemaVersion,
                cryptoVersion, minimumReaderVersion
            ) VALUES (?, 'LOCAL', 1000, 8, 1, 1)
            """.trimIndent(),
            arrayOf(id),
        )
    }

    /**
     * Populates a task, two attachments (one with a `blobSetId`, one
     * without), and one `attachment_transfer` row so
     * [migrate8To9PreservesRowsAndAddsEmptyRetiredBlobSets] can prove the
     * additive 8→9 migration changes no existing byte while starting the new
     * `retired_blob_sets` table empty.
     */
    private fun seedVersion8Fixture(database: SupportSQLiteDatabase) {
        insertVaultV8(database, id = "vault-a")
        database.execSQL(
            """
            INSERT INTO workspaces (id, vaultId, ownerId, name)
            VALUES ('workspace-a', 'vault-a', 'member-a', 'Workspace A')
            """.trimIndent(),
        )
        database.execSQL(
            "INSERT INTO members (id, displayName) VALUES ('member-a', 'Member A')",
        )
        database.execSQL(
            """
            INSERT INTO tasks (
                id, workspaceId, projectId, parentTaskId, statusId, semanticStatus,
                title, descriptionCiphertext, priority, startEpochMillis, startZoneId,
                dueEpochMillis, dueZoneId, recurrenceFrequency, recurrenceInterval,
                recurrenceWeekdays, recurrenceCount, recurrenceEndDate,
                recurrenceSeriesId, recurrenceAnchorEpochMillis, recurrenceAnchorZoneId,
                recurrenceOccurrenceIndex, estimateSeconds, milestoneId,
                completedAtEpochMillis, deletedAtEpochMillis, revisionWallMillis,
                revisionLogical, revisionDeviceId
            ) VALUES (
                'task-a', 'workspace-a', NULL, NULL, 'workflow-a', 'TODO',
                'Task A', ?, 'MEDIUM', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 10, 1, 'device-a'
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x01, 0x02, 0x03)),
        )
        database.execSQL(
            """
            INSERT INTO attachments (
                id, taskId, displayNameCiphertext, mimeType, byteCount, contentHash,
                blobSetId, chunkCount, deletedAtEpochMillis, revisionWallMillis,
                revisionLogical, revisionDeviceId
            ) VALUES (
                'attachment-blob', 'task-a', ?, 'application/pdf', 9000000, 'hash-a',
                'blob-set-a', 3, NULL, 10, 1, 'device-a'
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x04, 0x05)),
        )
        database.execSQL(
            """
            INSERT INTO attachments (
                id, taskId, displayNameCiphertext, mimeType, byteCount, contentHash,
                blobSetId, chunkCount, deletedAtEpochMillis, revisionWallMillis,
                revisionLogical, revisionDeviceId
            ) VALUES (
                'attachment-blobless', 'task-a', ?, 'application/octet-stream', 10,
                'hash-b', NULL, 0, NULL, 11, 1, 'device-a'
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x06)),
        )
        database.execSQL(
            """
            INSERT INTO attachment_transfer (
                blobSetId, attachmentId, taskId, phase, displayNameCiphertext, mimeType,
                declaredByteCount, contentHash, chunkCount, chunkStateEncoded,
                manifestProviderFileId, createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES (
                'blob-set-pending', 'attachment-pending', 'task-a', 'UPLOADING', ?,
                'application/zip', 4000000, NULL, NULL, '000', NULL, 10, 20
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x07)),
        )
    }

    private fun SupportSQLiteDatabase.captureVersion8Bytes(): Map<String, List<List<Any?>>> =
        buildMap {
            listOf("tasks", "attachments", "attachment_transfer").forEach { table ->
                put(table, captureRows("SELECT * FROM $table ORDER BY rowid"))
            }
        }

    private fun databaseNameV6(suffix: String): String =
        "vault-v6-v7-$suffix.db".also(databaseNames::add)

    private fun createV6(databaseName: String): SupportSQLiteDatabase =
        migrationTestHelper.createDatabase(databaseName, 6)

    private fun migrateTo7(databaseName: String): SupportSQLiteDatabase =
        migrationTestHelper.runMigrationsAndValidate(
            databaseName,
            7,
            true,
            VaultDatabase.MIGRATION_6_7,
        )

    private fun insertVaultV6(database: SupportSQLiteDatabase, id: String) {
        database.execSQL(
            """
            INSERT INTO vaults (
                id, storageMode, createdAtEpochMillis, schemaVersion,
                cryptoVersion, minimumReaderVersion
            ) VALUES (?, 'LOCAL', 1000, 6, 1, 1)
            """.trimIndent(),
            arrayOf(id),
        )
    }

    /**
     * Populates one row in every v6 user table, every Stage 2 backup table,
     * the recovery envelope, and legacy `sync_operations` so
     * [migrate6To7PreservesStage2AndLegacyBytes] can prove the migration
     * changes no existing column or byte.
     */
    private fun seedVersion6Fixture(database: SupportSQLiteDatabase) {
        insertVaultV6(database, id = "vault-a")
        database.execSQL(
            """
            INSERT INTO workspaces (id, vaultId, ownerId, name)
            VALUES ('workspace-a', 'vault-a', 'member-a', 'Workspace A')
            """.trimIndent(),
        )
        database.execSQL(
            "INSERT INTO members (id, displayName) VALUES ('member-a', 'Member A')",
        )
        database.execSQL(
            """
            INSERT INTO projects (
                id, workspaceId, name, summary, health, dueDate, completedTasks,
                totalTasks, archivedAtEpochMillis, revisionWallMillis, revisionLogical,
                revisionDeviceId
            ) VALUES (
                'project-a', 'workspace-a', 'Project A', 'Summary', 'ON_TRACK', NULL, 1,
                2, NULL, 10, 1, 'device-a'
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO workflow_statuses (
                id, projectId, name, semanticStatus, rank, archivedAtEpochMillis,
                revisionWallMillis, revisionLogical, revisionDeviceId
            ) VALUES (
                'workflow-a', 'project-a', 'Todo', 'TODO', 'a0', NULL, 10, 1, 'device-a'
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO milestones (
                id, projectId, name, dueDate, completedAtEpochMillis,
                revisionWallMillis, revisionLogical, revisionDeviceId
            ) VALUES ('milestone-a', 'project-a', 'Milestone A', NULL, NULL, 10, 1, 'device-a')
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO tasks (
                id, workspaceId, projectId, parentTaskId, statusId, semanticStatus,
                title, descriptionCiphertext, priority, startEpochMillis, startZoneId,
                dueEpochMillis, dueZoneId, recurrenceFrequency, recurrenceInterval,
                recurrenceWeekdays, recurrenceCount, recurrenceEndDate,
                recurrenceSeriesId, recurrenceAnchorEpochMillis, recurrenceAnchorZoneId,
                recurrenceOccurrenceIndex, estimateSeconds, milestoneId,
                completedAtEpochMillis, deletedAtEpochMillis, revisionWallMillis,
                revisionLogical, revisionDeviceId
            ) VALUES (
                'task-a', 'workspace-a', 'project-a', NULL, 'workflow-a', 'TODO',
                'Task A', ?, 'MEDIUM', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                NULL, NULL, NULL, NULL, NULL, NULL, 'milestone-a', NULL, NULL, 10, 1,
                'device-a'
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x01, 0x02, 0x03)),
        )
        database.execSQL(
            """
            INSERT INTO tasks (
                id, workspaceId, projectId, parentTaskId, statusId, semanticStatus,
                title, descriptionCiphertext, priority, startEpochMillis, startZoneId,
                dueEpochMillis, dueZoneId, recurrenceFrequency, recurrenceInterval,
                recurrenceWeekdays, recurrenceCount, recurrenceEndDate,
                recurrenceSeriesId, recurrenceAnchorEpochMillis, recurrenceAnchorZoneId,
                recurrenceOccurrenceIndex, estimateSeconds, milestoneId,
                completedAtEpochMillis, deletedAtEpochMillis, revisionWallMillis,
                revisionLogical, revisionDeviceId
            ) VALUES (
                'task-b', 'workspace-a', 'project-a', NULL, 'workflow-a', 'TODO',
                'Task B', ?, 'LOW', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 11, 1, 'device-a'
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x0a)),
        )
        database.execSQL(
            """
            INSERT INTO checklist_items (id, taskId, text, completed, rank)
            VALUES ('checklist-a', 'task-a', 'Step 1', 0, 'a0')
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO task_dependencies (
                taskId, dependsOnTaskId, revisionWallMillis, revisionLogical, revisionDeviceId
            ) VALUES ('task-a', 'task-b', 10, 1, 'device-a')
            """.trimIndent(),
        )
        database.execSQL(
            "INSERT INTO tags (id, workspaceId, name) VALUES ('tag-a', 'workspace-a', 'Urgent')",
        )
        database.execSQL(
            """
            INSERT INTO task_tags (
                taskId, tagId, present, revisionWallMillis, revisionLogical, revisionDeviceId
            ) VALUES ('task-a', 'tag-a', 1, 10, 1, 'device-a')
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO reminders (id, taskId, triggerAtEpochMillis, zoneId, precise)
            VALUES ('reminder-a', 'task-a', 5000, 'UTC', 1)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO attachments (
                id, taskId, displayNameCiphertext, mimeType, byteCount, contentHash,
                keepOffline
            ) VALUES ('attachment-a', 'task-a', ?, 'image/png', 10, 'hash-a', 0)
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x04, 0x05)),
        )
        database.execSQL(
            """
            INSERT INTO activity_entries (
                id, taskId, projectId, kind, bodyCiphertext, createdAtEpochMillis
            ) VALUES ('activity-a', 'task-a', NULL, 'CREATED', ?, 10)
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x06)),
        )
        database.execSQL(
            """
            INSERT INTO time_entries (
                id, taskId, deviceId, startedAtEpochMillis, stoppedAtEpochMillis,
                noteCiphertext
            ) VALUES ('time-a', 'task-a', 'device-a', 10, 20, ?)
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x07)),
        )
        database.execSQL(
            """
            INSERT INTO templates (
                id, workspaceId, name, encryptedPayload, revisionWallMillis,
                revisionLogical, revisionDeviceId
            ) VALUES ('template-a', 'workspace-a', 'Template A', ?, 10, 1, 'device-a')
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x08)),
        )
        database.execSQL(
            """
            INSERT INTO saved_views (id, workspaceId, name, encryptedQuery)
            VALUES ('saved-view-a', 'workspace-a', 'My View', ?)
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x09)),
        )
        database.execSQL(
            """
            INSERT INTO tombstones (
                objectId, objectType, deletedAtEpochMillis, purgeAfterEpochMillis,
                revisionWallMillis, revisionLogical, revisionDeviceId
            ) VALUES ('task-c', 'TASK', 10, 20, 10, 1, 'device-a')
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO sync_operations (
                id, deviceId, objectId, objectType, encryptedPayload,
                revisionWallMillis, revisionLogical, uploadedAtEpochMillis
            ) VALUES ('sync-a', 'device-a', 'task-a', 'TASK', ?, 10, 1, NULL)
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x0b)),
        )
        database.execSQL(
            """
            INSERT INTO backup_journal (
                operationId, vaultId, generation, sequence, payloadFormatVersion,
                mutationKind, objectId, objectType, payload, revisionWallMillis,
                revisionLogical, sourceDeviceId
            ) VALUES (
                'journal-a', 'vault-a', 1, 0, 0, 'UPSERT', 'task-a', 'TASK', ?, 10, 1,
                'device-a'
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x0c)),
        )
        database.execSQL(
            """
            INSERT INTO backup_state (
                vaultId, currentGeneration, lastVerifiedSnapshotGeneration,
                currentBaseObjectId, previousBaseObjectId, latestVerifiedSegmentGeneration,
                portablePackageGeneration, portablePackageBytes,
                portablePackageProducedAtEpochMillis, packageState, failureCategory,
                recoveryEnvelopeReady, legacyOutboxCoveredAtGeneration,
                snapshotCreatedAtEpochMillis
            ) VALUES (
                'vault-a', 1, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'NOT_PREPARED',
                NULL, 0, NULL, NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO vault_recovery_envelope (
                vaultId, formatVersion, kdfAlgorithm, memoryKiB, iterations,
                parallelism, salt, nonce, wrappedKeyset
            ) VALUES ('vault-a', 1, 'ARGON2ID', 65536, 3, 1, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                ByteArray(16) { it.toByte() },
                ByteArray(12) { (it + 1).toByte() },
                ByteArray(32) { (it + 2).toByte() },
            ),
        )
    }

    private fun databaseNameV7(suffix: String): String =
        "vault-v7-v8-$suffix.db".also(databaseNames::add)

    private fun createV7(databaseName: String): SupportSQLiteDatabase =
        migrationTestHelper.createDatabase(databaseName, 7)

    private fun migrateTo8(databaseName: String): SupportSQLiteDatabase =
        migrationTestHelper.runMigrationsAndValidate(
            databaseName,
            8,
            true,
            VaultDatabase.MIGRATION_7_8,
        )

    private fun insertVaultV7(database: SupportSQLiteDatabase, id: String) {
        database.execSQL(
            """
            INSERT INTO vaults (
                id, storageMode, createdAtEpochMillis, schemaVersion,
                cryptoVersion, minimumReaderVersion
            ) VALUES (?, 'LOCAL', 1000, 7, 1, 1)
            """.trimIndent(),
            arrayOf(id),
        )
    }

    /**
     * Populates one row in every v7 user table except `attachments`, plus one
     * row in each Stage 3 remote-backup table, so
     * [migrate7To8PreservesRowsAndRebuildsAttachments] can prove the 7→8
     * migration leaves every other table byte-identical. `attachments` is
     * seeded directly by that test instead, since its rebuild is the point
     * under test.
     */
    private fun seedVersion7Fixture(database: SupportSQLiteDatabase) {
        insertVaultV7(database, id = "vault-a")
        database.execSQL(
            """
            INSERT INTO workspaces (id, vaultId, ownerId, name)
            VALUES ('workspace-a', 'vault-a', 'member-a', 'Workspace A')
            """.trimIndent(),
        )
        database.execSQL(
            "INSERT INTO members (id, displayName) VALUES ('member-a', 'Member A')",
        )
        database.execSQL(
            """
            INSERT INTO projects (
                id, workspaceId, name, summary, health, dueDate, completedTasks,
                totalTasks, archivedAtEpochMillis, revisionWallMillis, revisionLogical,
                revisionDeviceId
            ) VALUES (
                'project-a', 'workspace-a', 'Project A', 'Summary', 'ON_TRACK', NULL, 1,
                2, NULL, 10, 1, 'device-a'
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO workflow_statuses (
                id, projectId, name, semanticStatus, rank, archivedAtEpochMillis,
                revisionWallMillis, revisionLogical, revisionDeviceId
            ) VALUES (
                'workflow-a', 'project-a', 'Todo', 'TODO', 'a0', NULL, 10, 1, 'device-a'
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO milestones (
                id, projectId, name, dueDate, completedAtEpochMillis,
                revisionWallMillis, revisionLogical, revisionDeviceId
            ) VALUES ('milestone-a', 'project-a', 'Milestone A', NULL, NULL, 10, 1, 'device-a')
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO tasks (
                id, workspaceId, projectId, parentTaskId, statusId, semanticStatus,
                title, descriptionCiphertext, priority, startEpochMillis, startZoneId,
                dueEpochMillis, dueZoneId, recurrenceFrequency, recurrenceInterval,
                recurrenceWeekdays, recurrenceCount, recurrenceEndDate,
                recurrenceSeriesId, recurrenceAnchorEpochMillis, recurrenceAnchorZoneId,
                recurrenceOccurrenceIndex, estimateSeconds, milestoneId,
                completedAtEpochMillis, deletedAtEpochMillis, revisionWallMillis,
                revisionLogical, revisionDeviceId
            ) VALUES (
                'task-a', 'workspace-a', 'project-a', NULL, 'workflow-a', 'TODO',
                'Task A', ?, 'MEDIUM', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                NULL, NULL, NULL, NULL, NULL, NULL, 'milestone-a', NULL, NULL, 10, 1,
                'device-a'
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x01, 0x02, 0x03)),
        )
        database.execSQL(
            """
            INSERT INTO tasks (
                id, workspaceId, projectId, parentTaskId, statusId, semanticStatus,
                title, descriptionCiphertext, priority, startEpochMillis, startZoneId,
                dueEpochMillis, dueZoneId, recurrenceFrequency, recurrenceInterval,
                recurrenceWeekdays, recurrenceCount, recurrenceEndDate,
                recurrenceSeriesId, recurrenceAnchorEpochMillis, recurrenceAnchorZoneId,
                recurrenceOccurrenceIndex, estimateSeconds, milestoneId,
                completedAtEpochMillis, deletedAtEpochMillis, revisionWallMillis,
                revisionLogical, revisionDeviceId
            ) VALUES (
                'task-b', 'workspace-a', 'project-a', NULL, 'workflow-a', 'TODO',
                'Task B', ?, 'LOW', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 11, 1, 'device-a'
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x0a)),
        )
        database.execSQL(
            """
            INSERT INTO checklist_items (id, taskId, text, completed, rank)
            VALUES ('checklist-a', 'task-a', 'Step 1', 0, 'a0')
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO task_dependencies (
                taskId, dependsOnTaskId, revisionWallMillis, revisionLogical, revisionDeviceId
            ) VALUES ('task-a', 'task-b', 10, 1, 'device-a')
            """.trimIndent(),
        )
        database.execSQL(
            "INSERT INTO tags (id, workspaceId, name) VALUES ('tag-a', 'workspace-a', 'Urgent')",
        )
        database.execSQL(
            """
            INSERT INTO task_tags (
                taskId, tagId, present, revisionWallMillis, revisionLogical, revisionDeviceId
            ) VALUES ('task-a', 'tag-a', 1, 10, 1, 'device-a')
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO reminders (id, taskId, triggerAtEpochMillis, zoneId, precise)
            VALUES ('reminder-a', 'task-a', 5000, 'UTC', 1)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO activity_entries (
                id, taskId, projectId, kind, bodyCiphertext, createdAtEpochMillis
            ) VALUES ('activity-a', 'task-a', NULL, 'CREATED', ?, 10)
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x06)),
        )
        database.execSQL(
            """
            INSERT INTO time_entries (
                id, taskId, deviceId, startedAtEpochMillis, stoppedAtEpochMillis,
                noteCiphertext
            ) VALUES ('time-a', 'task-a', 'device-a', 10, 20, ?)
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x07)),
        )
        database.execSQL(
            """
            INSERT INTO templates (
                id, workspaceId, name, encryptedPayload, revisionWallMillis,
                revisionLogical, revisionDeviceId
            ) VALUES ('template-a', 'workspace-a', 'Template A', ?, 10, 1, 'device-a')
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x08)),
        )
        database.execSQL(
            """
            INSERT INTO saved_views (id, workspaceId, name, encryptedQuery)
            VALUES ('saved-view-a', 'workspace-a', 'My View', ?)
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x09)),
        )
        database.execSQL(
            """
            INSERT INTO tombstones (
                objectId, objectType, deletedAtEpochMillis, purgeAfterEpochMillis,
                revisionWallMillis, revisionLogical, revisionDeviceId
            ) VALUES ('task-c', 'TASK', 10, 20, 10, 1, 'device-a')
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO sync_operations (
                id, deviceId, objectId, objectType, encryptedPayload,
                revisionWallMillis, revisionLogical, uploadedAtEpochMillis
            ) VALUES ('sync-a', 'device-a', 'task-a', 'TASK', ?, 10, 1, NULL)
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x0b)),
        )
        database.execSQL(
            """
            INSERT INTO backup_journal (
                operationId, vaultId, generation, sequence, payloadFormatVersion,
                mutationKind, objectId, objectType, payload, revisionWallMillis,
                revisionLogical, sourceDeviceId
            ) VALUES (
                'journal-a', 'vault-a', 1, 0, 0, 'UPSERT', 'task-a', 'TASK', ?, 10, 1,
                'device-a'
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x0c)),
        )
        database.execSQL(
            """
            INSERT INTO backup_state (
                vaultId, currentGeneration, lastVerifiedSnapshotGeneration,
                currentBaseObjectId, previousBaseObjectId, latestVerifiedSegmentGeneration,
                portablePackageGeneration, portablePackageBytes,
                portablePackageProducedAtEpochMillis, packageState, failureCategory,
                recoveryEnvelopeReady, legacyOutboxCoveredAtGeneration,
                snapshotCreatedAtEpochMillis
            ) VALUES (
                'vault-a', 1, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'NOT_PREPARED',
                NULL, 0, NULL, NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO vault_recovery_envelope (
                vaultId, formatVersion, kdfAlgorithm, memoryKiB, iterations,
                parallelism, salt, nonce, wrappedKeyset
            ) VALUES ('vault-a', 1, 'ARGON2ID', 65536, 3, 1, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                ByteArray(16) { it.toByte() },
                ByteArray(12) { (it + 1).toByte() },
                ByteArray(32) { (it + 2).toByte() },
            ),
        )
        database.execSQL(
            """
            INSERT INTO remote_backup_config (
                lineageId, vaultId, rootClaimProviderFileId, accountBindingDigest,
                lifecycle, activeDeviceId, writerEpoch, ownershipClaimProviderFileId,
                ownershipClaimId, ownershipClaimSha256, nextSuccessorProviderFileId,
                currentPublicationProviderFileId, currentPublicationId,
                currentPublicationSha256, previousPublicationProviderFileId,
                previousPublicationId, previousPublicationSha256,
                previousPublicationGeneration, publicationSequence, lastVerifiedGeneration,
                lastVerifiedAtEpochMillis, recoveryCredentialGeneration, failureCategory,
                stateVersion, createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES (
                'lineage-a', 'vault-a', 'root-claim-a', ?, 'ACTIVE', 'device-a', 1, NULL,
                NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                NULL, 0, NULL, 1, 10, 10
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x0d)),
        )
        database.execSQL(
            """
            INSERT INTO remote_backup_object (
                lineageId, logicalObjectId, providerFileId, role, writerEpoch,
                ownerDeviceId, operationId, firstGeneration, lastGeneration, frameLength,
                frameSha256, lifecycle, resumableSessionUri, uploadedBytes,
                createdAtEpochMillis, verifiedAtEpochMillis
            ) VALUES (
                'lineage-a', 'object-a', 'provider-file-a', 'SNAPSHOT', 1, 'device-a',
                'remote-op-a', 1, 1, 10, 'frame-sha-a', 'PENDING', NULL, 0, 10, NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO remote_backup_operation (
                operationId, lineageId, kind, phase, targetEpoch, targetGeneration,
                candidateClaimProviderFileId, candidatePublicationProviderFileId,
                stateBytes, startedAtEpochMillis, updatedAtEpochMillis
            ) VALUES (
                'remote-op-a', 'lineage-a', 'PUBLISH', 'STARTED', NULL, NULL, NULL, NULL,
                ?, 10, 10
            )
            """.trimIndent(),
            arrayOf<Any?>(byteArrayOf(0x0e)),
        )
    }

    private val preservedTablesV7 = listOf(
        "workspaces",
        "members",
        "projects",
        "workflow_statuses",
        "milestones",
        "tasks",
        "checklist_items",
        "task_dependencies",
        "tags",
        "task_tags",
        "reminders",
        "activity_entries",
        "time_entries",
        "templates",
        "saved_views",
        "tombstones",
        "sync_operations",
        "backup_journal",
        "backup_state",
        "vault_recovery_envelope",
        "remote_backup_config",
        "remote_backup_object",
        "remote_backup_operation",
    )

    /**
     * Captures every v7 table except `attachments` (whose rebuild
     * [migrate7To8PreservesRowsAndRebuildsAttachments] asserts column-by-
     * column instead) and the two tables the 7→8 migration adds (`notes`,
     * `attachment_transfer`), which start and stay empty in that test. Safe
     * to call on either the pre- or post-migration database handle since
     * none of these tables change shape across the 7→8 migration.
     */
    private fun SupportSQLiteDatabase.captureVersion7Bytes(): Map<String, List<List<Any?>>> =
        buildMap {
            put(
                "vaults",
                captureRows(
                    """
                    SELECT id, storageMode, createdAtEpochMillis, cryptoVersion,
                           minimumReaderVersion
                    FROM vaults ORDER BY id
                    """.trimIndent(),
                ),
            )
            preservedTablesV7.forEach { table ->
                put(table, captureRows("SELECT * FROM $table ORDER BY rowid"))
            }
        }

    private fun SupportSQLiteDatabase.rowValues(sql: String): List<Any?> =
        query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "Expected exactly one row: $sql" }
            val values = (0 until cursor.columnCount).map { index ->
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
                    else -> error("Unsupported cursor column type")
                }
            }
            check(!cursor.moveToNext()) { "Expected exactly one row: $sql" }
            values
        }

    private val preservedTables = listOf(
        "workspaces",
        "members",
        "projects",
        "workflow_statuses",
        "milestones",
        "tasks",
        "checklist_items",
        "task_dependencies",
        "tags",
        "task_tags",
        "reminders",
        "attachments",
        "activity_entries",
        "time_entries",
        "templates",
        "saved_views",
        "tombstones",
        "sync_operations",
        "backup_journal",
        "backup_state",
        "vault_recovery_envelope",
    )

    private fun SupportSQLiteDatabase.captureVersion6Bytes(): Map<String, List<List<Any?>>> =
        buildMap {
            put(
                "vaults",
                captureRows(
                    """
                    SELECT id, storageMode, createdAtEpochMillis, cryptoVersion,
                           minimumReaderVersion
                    FROM vaults ORDER BY id
                    """.trimIndent(),
                ),
            )
            preservedTables.forEach { table ->
                put(table, captureRows("SELECT * FROM $table ORDER BY rowid"))
            }
        }

    private fun SupportSQLiteDatabase.capturePreservedBytes(): Map<String, List<List<Any?>>> =
        captureVersion6Bytes()

    private fun SupportSQLiteDatabase.captureRows(sql: String): List<List<Any?>> =
        query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        (0 until cursor.columnCount).map { index ->
                            when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
                                else -> error("Unsupported cursor column type")
                            }
                        },
                    )
                }
            }
        }

    private fun SupportSQLiteDatabase.tableNames(): List<String> =
        query(
            """
            SELECT name FROM sqlite_master
            WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'
            ORDER BY name
            """.trimIndent(),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
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
