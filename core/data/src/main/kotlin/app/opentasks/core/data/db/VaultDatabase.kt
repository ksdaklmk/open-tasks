package app.opentasks.core.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.opentasks.core.data.backup.BackupCaptureDao
import app.opentasks.core.data.backup.BackupJournalDao
import app.opentasks.core.data.backup.BackupJournalEntity
import app.opentasks.core.data.backup.BackupMutationDao
import app.opentasks.core.data.backup.BackupStateDao
import app.opentasks.core.data.backup.BackupStateEntity
import app.opentasks.core.data.backup.LegacySyncOperationDao
import app.opentasks.core.data.backup.RemoteBackupConfigDao
import app.opentasks.core.data.backup.RemoteBackupConfigEntity
import app.opentasks.core.data.backup.RemoteBackupObjectDao
import app.opentasks.core.data.backup.RemoteBackupObjectEntity
import app.opentasks.core.data.backup.RemoteBackupOperationDao
import app.opentasks.core.data.backup.RemoteBackupOperationEntity
import app.opentasks.core.data.backup.VaultRecoveryEnvelopeDao
import app.opentasks.core.data.backup.VaultRecoveryEnvelopeEntity
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Dao
interface TaskDao {
    @Query(
        """
        SELECT * FROM tasks
        WHERE workspaceId = :workspaceId AND deletedAtEpochMillis IS NULL
        ORDER BY dueEpochMillis IS NULL, dueEpochMillis, title COLLATE NOCASE
        """,
    )
    fun observeActive(workspaceId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE workspaceId = :workspaceId ORDER BY id")
    fun observeAll(workspaceId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id IN (:ids) ORDER BY id")
    suspend fun getByIds(ids: List<String>): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE milestoneId = :milestoneId ORDER BY id")
    suspend fun getByMilestoneId(milestoneId: String): List<TaskEntity>

    @Query(
        """
        SELECT * FROM tasks
        WHERE deletedAtEpochMillis IS NOT NULL AND deletedAtEpochMillis <= :cutoffEpochMillis
        ORDER BY deletedAtEpochMillis, id
        """,
    )
    suspend fun getDeletedAtOrBefore(cutoffEpochMillis: Long): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

@Dao
interface WorkspaceDao {
    @Query("SELECT COUNT(*) FROM workspaces")
    suspend fun workspaceCount(): Int

    @Query("SELECT * FROM projects WHERE workspaceId = :workspaceId ORDER BY name COLLATE NOCASE")
    fun observeProjects(workspaceId: String): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: String): ProjectEntity?

    @Query(
        """
        SELECT * FROM projects
        WHERE workspaceId = :workspaceId
            AND archivedAtEpochMillis IS NULL
            AND name = :name COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun findActiveProjectByName(workspaceId: String, name: String): ProjectEntity?

    @Query("SELECT * FROM workflow_statuses ORDER BY rank")
    fun observeWorkflowStatuses(): Flow<List<WorkflowStatusEntity>>

    @Query("SELECT * FROM workflow_statuses WHERE id = :id LIMIT 1")
    suspend fun getWorkflowStatus(id: String): WorkflowStatusEntity?

    @Query(
        """
        SELECT * FROM workflow_statuses
        WHERE projectId IS :projectId
        ORDER BY rank
        """,
    )
    suspend fun getWorkflowStatuses(projectId: String?): List<WorkflowStatusEntity>

    @Query("SELECT * FROM milestones ORDER BY dueDate IS NULL, dueDate, name COLLATE NOCASE")
    fun observeMilestones(): Flow<List<MilestoneEntity>>

    @Query("SELECT * FROM milestones WHERE id = :id LIMIT 1")
    suspend fun getMilestone(id: String): MilestoneEntity?

    @Query("SELECT * FROM milestones WHERE projectId = :projectId ORDER BY dueDate IS NULL, dueDate, name COLLATE NOCASE")
    suspend fun getMilestones(projectId: String): List<MilestoneEntity>

    @Query("SELECT * FROM tags WHERE workspaceId = :workspaceId ORDER BY name COLLATE NOCASE")
    fun observeTags(workspaceId: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id LIMIT 1")
    suspend fun getTagById(id: String): TagEntity?

    @Query(
        """
        SELECT * FROM tags
        WHERE workspaceId = :workspaceId AND name = :name COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun findTagByName(workspaceId: String, name: String): TagEntity?

    @Query("SELECT * FROM task_dependencies ORDER BY taskId, dependsOnTaskId")
    fun observeDependencies(): Flow<List<TaskDependencyEntity>>

    @Query("SELECT * FROM task_dependencies ORDER BY taskId, dependsOnTaskId")
    suspend fun getDependencies(): List<TaskDependencyEntity>

    @Query(
        """
        SELECT dependsOnTaskId FROM task_dependencies
        WHERE taskId = :taskId
        ORDER BY dependsOnTaskId
        """,
    )
    suspend fun getDependencyIds(taskId: String): List<String>

    @Query("SELECT * FROM task_tags WHERE present = 1 ORDER BY taskId, tagId")
    fun observeTaskTags(): Flow<List<TaskTagEntity>>

    @Query("SELECT * FROM checklist_items ORDER BY taskId, rank")
    fun observeChecklistItems(): Flow<List<ChecklistItemEntity>>

    @Query("SELECT * FROM reminders ORDER BY triggerAtEpochMillis, id")
    fun observeReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE taskId = :taskId ORDER BY id LIMIT 1")
    suspend fun getReminderForTask(taskId: String): ReminderEntity?

    @Query("SELECT * FROM templates WHERE workspaceId = :workspaceId ORDER BY name COLLATE NOCASE")
    fun observeTemplates(workspaceId: String): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates WHERE id = :id LIMIT 1")
    suspend fun getTemplate(id: String): TemplateEntity?

    @Query(
        """
        SELECT * FROM templates
        WHERE workspaceId = :workspaceId AND name = :name COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun findTemplateByName(workspaceId: String, name: String): TemplateEntity?

    @Query("SELECT COUNT(*) FROM templates WHERE workspaceId = :workspaceId")
    suspend fun templateCount(workspaceId: String): Int

    @Query("SELECT * FROM checklist_items WHERE taskId = :taskId ORDER BY rank")
    suspend fun getChecklistItems(taskId: String): List<ChecklistItemEntity>

    @Query("SELECT * FROM task_tags WHERE taskId = :taskId AND present = 1 ORDER BY tagId")
    suspend fun getTaskTags(taskId: String): List<TaskTagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVault(value: VaultEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWorkspace(value: WorkspaceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMember(value: MemberEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProjects(values: List<ProjectEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(value: ProjectEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWorkflowStatuses(values: List<WorkflowStatusEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkflowStatus(value: WorkflowStatusEntity)

    @Query("DELETE FROM workflow_statuses WHERE id = :id")
    suspend fun deleteWorkflowStatus(id: String): Int

    @Query("SELECT COUNT(*) FROM tasks WHERE statusId = :statusId")
    suspend fun taskCountForStatus(statusId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMilestones(values: List<MilestoneEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMilestone(value: MilestoneEntity)

    @Query("DELETE FROM milestones WHERE id = :id")
    suspend fun deleteMilestone(id: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTasks(values: List<TaskEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDependencies(values: List<TaskDependencyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDependency(value: TaskDependencyEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(values: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTaskTags(values: List<TaskTagEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChecklistItems(values: List<ChecklistItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChecklistItem(value: ChecklistItemEntity)

    @Query("DELETE FROM checklist_items WHERE taskId = :taskId AND id = :itemId")
    suspend fun deleteChecklistItem(taskId: String, itemId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTag(value: TagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTaskTag(value: TaskTagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReminder(value: ReminderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplate(value: TemplateEntity)

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun deleteTemplate(id: String): Int

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: String): Int

    @Query("DELETE FROM checklist_items WHERE taskId = :taskId")
    suspend fun deleteChecklistForTask(taskId: String)

    @Query("DELETE FROM task_tags WHERE taskId = :taskId")
    suspend fun deleteTagsForTask(taskId: String)

    @Query(
        """
        DELETE FROM task_dependencies
        WHERE taskId = :taskId OR dependsOnTaskId = :taskId
        """,
    )
    suspend fun deleteDependenciesForTask(taskId: String)

    @Query(
        """
        DELETE FROM task_dependencies
        WHERE taskId = :taskId AND dependsOnTaskId = :dependsOnTaskId
        """,
    )
    suspend fun deleteDependency(taskId: String, dependsOnTaskId: String): Int

    @Query("DELETE FROM reminders WHERE taskId = :taskId")
    suspend fun deleteRemindersForTask(taskId: String)

    @Query("DELETE FROM attachments WHERE taskId = :taskId")
    suspend fun deleteAttachmentsForTask(taskId: String)

    @Query("DELETE FROM activity_entries WHERE taskId = :taskId")
    suspend fun deleteActivityForTask(taskId: String)

    @Query("DELETE FROM time_entries WHERE taskId = :taskId")
    suspend fun deleteTimeForTask(taskId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstone(value: TombstoneEntity)

    @Query(
        """
        SELECT * FROM tombstones
        WHERE objectId = :objectId AND objectType = :objectType
        LIMIT 1
        """,
    )
    suspend fun getTombstone(objectId: String, objectType: String): TombstoneEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTimeEntry(value: TimeEntryEntity)
}

@Dao
interface TimeEntryDao {
    @Query("SELECT * FROM time_entries ORDER BY startedAtEpochMillis DESC, id")
    fun observeAll(): Flow<List<TimeEntryEntity>>

    @Query("SELECT * FROM time_entries ORDER BY startedAtEpochMillis DESC, id")
    suspend fun getAll(): List<TimeEntryEntity>

    @Query("SELECT * FROM time_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TimeEntryEntity?

    @Query("SELECT COUNT(*) FROM time_entries WHERE taskId = :taskId")
    suspend fun countForTask(taskId: String): Int

    @Query(
        """
        SELECT * FROM time_entries
        WHERE stoppedAtEpochMillis IS NULL
        ORDER BY startedAtEpochMillis DESC
        LIMIT 1
        """,
    )
    fun observeActive(): Flow<TimeEntryEntity?>

    @Query(
        """
        SELECT * FROM time_entries
        WHERE stoppedAtEpochMillis IS NULL
        ORDER BY startedAtEpochMillis DESC
        LIMIT 1
        """,
    )
    suspend fun getActive(): TimeEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(value: TimeEntryEntity)

    @Update
    suspend fun update(value: TimeEntryEntity): Int

    @Query(
        """
        UPDATE time_entries
        SET stoppedAtEpochMillis = :stoppedAtEpochMillis
        WHERE id = :id AND stoppedAtEpochMillis IS NULL
        """,
    )
    suspend fun stop(id: String, stoppedAtEpochMillis: Long): Int

    @Query("DELETE FROM time_entries WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Database(
    entities = [
        VaultEntity::class,
        WorkspaceEntity::class,
        MemberEntity::class,
        ProjectEntity::class,
        WorkflowStatusEntity::class,
        MilestoneEntity::class,
        TaskEntity::class,
        ChecklistItemEntity::class,
        TaskDependencyEntity::class,
        TagEntity::class,
        TaskTagEntity::class,
        ReminderEntity::class,
        AttachmentEntity::class,
        ActivityEntryEntity::class,
        TimeEntryEntity::class,
        TemplateEntity::class,
        SavedViewEntity::class,
        SyncOperationEntity::class,
        TombstoneEntity::class,
        BackupJournalEntity::class,
        BackupStateEntity::class,
        VaultRecoveryEnvelopeEntity::class,
        RemoteBackupConfigEntity::class,
        RemoteBackupObjectEntity::class,
        RemoteBackupOperationEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun timeEntryDao(): TimeEntryDao
    abstract fun legacySyncOperationDao(): LegacySyncOperationDao
    abstract fun backupJournalDao(): BackupJournalDao
    internal abstract fun backupMutationDao(): BackupMutationDao
    abstract fun backupStateDao(): BackupStateDao
    abstract fun vaultRecoveryEnvelopeDao(): VaultRecoveryEnvelopeDao
    abstract fun backupCaptureDao(): BackupCaptureDao
    abstract fun remoteBackupConfigDao(): RemoteBackupConfigDao
    abstract fun remoteBackupObjectDao(): RemoteBackupObjectDao
    abstract fun remoteBackupOperationDao(): RemoteBackupOperationDao

    @Transaction
    open suspend fun purgeTask(
        taskId: String,
        tombstone: TombstoneEntity,
    ) {
        workspaceDao().deleteChecklistForTask(taskId)
        workspaceDao().deleteTagsForTask(taskId)
        workspaceDao().deleteDependenciesForTask(taskId)
        workspaceDao().deleteRemindersForTask(taskId)
        workspaceDao().deleteAttachmentsForTask(taskId)
        workspaceDao().deleteActivityForTask(taskId)
        workspaceDao().deleteTimeForTask(taskId)
        taskDao().deleteById(taskId)
        workspaceDao().upsertTombstone(tombstone)
    }

    companion object {
        fun create(
            context: Context,
            databaseName: String,
            databaseKey: ByteArray,
        ): VaultDatabase {
            require(databaseKey.size >= 32) { "Database key must contain at least 256 bits" }
            loadSqlCipher()
            val factory = SupportOpenHelperFactory(databaseKey.copyOf())
            return Room.databaseBuilder(
                context.applicationContext,
                VaultDatabase::class.java,
                databaseName,
            )
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
                .build()
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN recurrenceSeriesId TEXT",
                )
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN recurrenceAnchorEpochMillis INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN recurrenceAnchorZoneId TEXT",
                )
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN recurrenceOccurrenceIndex INTEGER",
                )
                db.execSQL(
                    "UPDATE vaults SET schemaVersion = 2 WHERE schemaVersion < 2",
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_workflow_statuses_projectId_rank")
                db.execSQL(
                    "ALTER TABLE workflow_statuses RENAME TO workflow_statuses_v2",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workflow_statuses (
                        id TEXT NOT NULL,
                        projectId TEXT,
                        name TEXT NOT NULL,
                        semanticStatus TEXT NOT NULL,
                        rank TEXT NOT NULL,
                        archivedAtEpochMillis INTEGER,
                        revisionWallMillis INTEGER NOT NULL,
                        revisionLogical INTEGER NOT NULL,
                        revisionDeviceId TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO workflow_statuses (
                        id, projectId, name, semanticStatus, rank, archivedAtEpochMillis,
                        revisionWallMillis, revisionLogical, revisionDeviceId
                    )
                    SELECT
                        'workflow:' || projects.id || ':' ||
                            lower(workflow_statuses_v2.semanticStatus),
                        projects.id,
                        workflow_statuses_v2.name,
                        workflow_statuses_v2.semanticStatus,
                        workflow_statuses_v2.rank,
                        workflow_statuses_v2.archivedAtEpochMillis,
                        0,
                        0,
                        'migration'
                    FROM projects
                    CROSS JOIN workflow_statuses_v2
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO workflow_statuses (
                        id, projectId, name, semanticStatus, rank, archivedAtEpochMillis,
                        revisionWallMillis, revisionLogical, revisionDeviceId
                    )
                    SELECT
                        'workflow:inbox:' || lower(semanticStatus),
                        NULL,
                        name,
                        semanticStatus,
                        rank,
                        archivedAtEpochMillis,
                        0,
                        0,
                        'migration'
                    FROM workflow_statuses_v2
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE tasks
                    SET statusId =
                        'workflow:' || COALESCE(projectId, 'inbox') || ':' ||
                            lower(semanticStatus)
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE workflow_statuses_v2")
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                        index_workflow_statuses_projectId_rank
                    ON workflow_statuses(projectId, rank)
                    """.trimIndent(),
                )
                db.execSQL(
                    "UPDATE vaults SET schemaVersion = 3 WHERE schemaVersion < 3",
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE milestones RENAME TO milestones_v3")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS milestones (
                        id TEXT NOT NULL,
                        projectId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        dueDate TEXT,
                        completedAtEpochMillis INTEGER,
                        revisionWallMillis INTEGER NOT NULL,
                        revisionLogical INTEGER NOT NULL,
                        revisionDeviceId TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO milestones (
                        id, projectId, name, dueDate, completedAtEpochMillis,
                        revisionWallMillis, revisionLogical, revisionDeviceId
                    )
                    SELECT
                        id, projectId, name, dueDate, completedAtEpochMillis,
                        0, 0, 'migration'
                    FROM milestones_v3
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE milestones_v3")
                db.execSQL(
                    "UPDATE vaults SET schemaVersion = 4 WHERE schemaVersion < 4",
                )
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE templates ADD COLUMN revisionWallMillis INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE templates ADD COLUMN revisionLogical INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    ALTER TABLE templates
                    ADD COLUMN revisionDeviceId TEXT NOT NULL DEFAULT 'migration'
                    """.trimIndent(),
                )
                db.execSQL(
                    "UPDATE vaults SET schemaVersion = 5 WHERE schemaVersion < 5",
                )
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val vaultIds = mutableListOf<String>()
                db.query("SELECT id FROM vaults ORDER BY id").use { cursor ->
                    while (cursor.moveToNext()) vaultIds += cursor.getString(0)
                }
                val operationCount = db.query(
                    "SELECT COUNT(*) FROM sync_operations",
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }
                check(operationCount == 0L || vaultIds.size == 1) {
                    "Legacy backup operations cannot be assigned to multiple vaults"
                }

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS backup_journal (
                        operationId TEXT NOT NULL,
                        vaultId TEXT NOT NULL,
                        generation INTEGER NOT NULL,
                        sequence INTEGER NOT NULL,
                        payloadFormatVersion INTEGER NOT NULL,
                        mutationKind TEXT NOT NULL,
                        objectId TEXT NOT NULL,
                        objectType TEXT NOT NULL,
                        payload BLOB NOT NULL,
                        revisionWallMillis INTEGER NOT NULL,
                        revisionLogical INTEGER NOT NULL,
                        sourceDeviceId TEXT NOT NULL,
                        PRIMARY KEY(operationId)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                        index_backup_journal_vaultId_generation_sequence
                    ON backup_journal(vaultId, generation, sequence)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS backup_state (
                        vaultId TEXT NOT NULL,
                        currentGeneration INTEGER NOT NULL,
                        lastVerifiedSnapshotGeneration INTEGER,
                        currentBaseObjectId TEXT,
                        previousBaseObjectId TEXT,
                        latestVerifiedSegmentGeneration INTEGER,
                        portablePackageGeneration INTEGER,
                        portablePackageBytes INTEGER,
                        portablePackageProducedAtEpochMillis INTEGER,
                        packageState TEXT NOT NULL,
                        failureCategory TEXT,
                        recoveryEnvelopeReady INTEGER NOT NULL,
                        legacyOutboxCoveredAtGeneration INTEGER,
                        snapshotCreatedAtEpochMillis INTEGER,
                        PRIMARY KEY(vaultId)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS vault_recovery_envelope (
                        vaultId TEXT NOT NULL,
                        formatVersion INTEGER NOT NULL,
                        kdfAlgorithm TEXT NOT NULL,
                        memoryKiB INTEGER NOT NULL,
                        iterations INTEGER NOT NULL,
                        parallelism INTEGER NOT NULL,
                        salt BLOB NOT NULL,
                        nonce BLOB NOT NULL,
                        wrappedKeyset BLOB NOT NULL,
                        PRIMARY KEY(vaultId)
                    )
                    """.trimIndent(),
                )

                if (operationCount > 0L) {
                    val vaultId = vaultIds.single()
                    val statement = db.compileStatement(
                        """
                        INSERT INTO backup_journal (
                            operationId, vaultId, generation, sequence,
                            payloadFormatVersion, mutationKind, objectId, objectType,
                            payload, revisionWallMillis, revisionLogical, sourceDeviceId
                        ) VALUES (?, ?, ?, 0, 0, 'LEGACY', ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    )
                    db.query(
                        """
                        SELECT id, deviceId, objectId, objectType, encryptedPayload,
                               revisionWallMillis, revisionLogical
                        FROM sync_operations
                        ORDER BY revisionWallMillis, revisionLogical, deviceId, id
                        """.trimIndent(),
                    ).use { cursor ->
                        var generation = 0L
                        while (cursor.moveToNext()) {
                            generation += 1
                            statement.clearBindings()
                            statement.bindString(1, cursor.getString(0))
                            statement.bindString(2, vaultId)
                            statement.bindLong(3, generation)
                            statement.bindString(4, cursor.getString(2))
                            statement.bindString(5, cursor.getString(3))
                            statement.bindBlob(6, cursor.getBlob(4))
                            statement.bindLong(7, cursor.getLong(5))
                            statement.bindLong(8, cursor.getLong(6))
                            statement.bindString(9, cursor.getString(1))
                            statement.executeInsert()
                        }
                    }
                }

                val stateStatement = db.compileStatement(
                    """
                    INSERT INTO backup_state (
                        vaultId, currentGeneration, lastVerifiedSnapshotGeneration,
                        currentBaseObjectId, previousBaseObjectId,
                        latestVerifiedSegmentGeneration, portablePackageGeneration,
                        portablePackageBytes, portablePackageProducedAtEpochMillis,
                        packageState, failureCategory, recoveryEnvelopeReady,
                        legacyOutboxCoveredAtGeneration, snapshotCreatedAtEpochMillis
                    ) VALUES (?, ?, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                              'NOT_PREPARED', NULL, 0, NULL, NULL)
                    """.trimIndent(),
                )
                vaultIds.forEach { vaultId ->
                    stateStatement.clearBindings()
                    stateStatement.bindString(1, vaultId)
                    stateStatement.bindLong(2, operationCount)
                    stateStatement.executeInsert()
                }
                db.execSQL(
                    "UPDATE vaults SET storageMode = 'LOCAL', schemaVersion = 6",
                )
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS remote_backup_config (
                        lineageId TEXT NOT NULL,
                        vaultId TEXT NOT NULL,
                        rootClaimProviderFileId TEXT NOT NULL,
                        accountBindingDigest BLOB NOT NULL,
                        lifecycle TEXT NOT NULL,
                        activeDeviceId TEXT,
                        writerEpoch INTEGER,
                        ownershipClaimProviderFileId TEXT,
                        ownershipClaimId TEXT,
                        ownershipClaimSha256 TEXT,
                        nextSuccessorProviderFileId TEXT,
                        currentPublicationProviderFileId TEXT,
                        currentPublicationId TEXT,
                        currentPublicationSha256 TEXT,
                        previousPublicationProviderFileId TEXT,
                        previousPublicationId TEXT,
                        previousPublicationSha256 TEXT,
                        previousPublicationGeneration INTEGER,
                        publicationSequence INTEGER,
                        lastVerifiedGeneration INTEGER,
                        lastVerifiedAtEpochMillis INTEGER,
                        recoveryCredentialGeneration INTEGER NOT NULL,
                        failureCategory TEXT,
                        stateVersion INTEGER NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(lineageId)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_remote_backup_config_vaultId
                        ON remote_backup_config(vaultId)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_remote_backup_config_lifecycle
                        ON remote_backup_config(lifecycle)
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS remote_backup_object (
                        lineageId TEXT NOT NULL,
                        logicalObjectId TEXT NOT NULL,
                        providerFileId TEXT NOT NULL,
                        role TEXT NOT NULL,
                        writerEpoch INTEGER NOT NULL,
                        ownerDeviceId TEXT NOT NULL,
                        operationId TEXT NOT NULL,
                        firstGeneration INTEGER NOT NULL,
                        lastGeneration INTEGER NOT NULL,
                        frameLength INTEGER NOT NULL,
                        frameSha256 TEXT NOT NULL,
                        lifecycle TEXT NOT NULL,
                        resumableSessionUri TEXT,
                        uploadedBytes INTEGER NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        verifiedAtEpochMillis INTEGER,
                        PRIMARY KEY(lineageId, logicalObjectId)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                        index_remote_backup_object_providerFileId
                    ON remote_backup_object(providerFileId)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_remote_backup_object_operationId
                        ON remote_backup_object(operationId)
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS remote_backup_operation (
                        operationId TEXT NOT NULL,
                        lineageId TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        phase TEXT NOT NULL,
                        targetEpoch INTEGER,
                        targetGeneration INTEGER,
                        candidateClaimProviderFileId TEXT,
                        candidatePublicationProviderFileId TEXT,
                        stateBytes BLOB NOT NULL,
                        startedAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(operationId)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_remote_backup_operation_lineageId
                        ON remote_backup_operation(lineageId)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_remote_backup_operation_kind
                        ON remote_backup_operation(kind)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_remote_backup_operation_phase
                        ON remote_backup_operation(phase)
                    """.trimIndent(),
                )

                db.execSQL(
                    "UPDATE vaults SET schemaVersion = 7 WHERE schemaVersion < 7",
                )
            }
        }

        @Synchronized
        private fun loadSqlCipher() {
            if (sqlCipherLoaded) return
            System.loadLibrary("sqlcipher")
            sqlCipherLoaded = true
        }

        @Volatile
        private var sqlCipherLoaded = false
    }
}
