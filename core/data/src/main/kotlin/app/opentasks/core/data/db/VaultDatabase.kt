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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    @Query("SELECT * FROM milestones ORDER BY dueDate IS NULL, dueDate, name COLLATE NOCASE")
    fun observeMilestones(): Flow<List<MilestoneEntity>>

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

    @Query("SELECT * FROM task_tags WHERE present = 1 ORDER BY taskId, tagId")
    fun observeTaskTags(): Flow<List<TaskTagEntity>>

    @Query("SELECT * FROM checklist_items ORDER BY taskId, rank")
    fun observeChecklistItems(): Flow<List<ChecklistItemEntity>>

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMilestones(values: List<MilestoneEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTasks(values: List<TaskEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDependencies(values: List<TaskDependencyEntity>)

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

    @Query(
        """
        UPDATE time_entries
        SET stoppedAtEpochMillis = :stoppedAtEpochMillis
        WHERE id = :id AND stoppedAtEpochMillis IS NULL
        """,
    )
    suspend fun stop(id: String, stoppedAtEpochMillis: Long): Int
}

@Dao
interface SyncOperationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun append(operation: SyncOperationEntity)

    @Query(
        """
        SELECT * FROM sync_operations
        WHERE uploadedAtEpochMillis IS NULL
        ORDER BY revisionWallMillis, revisionLogical, deviceId
        LIMIT :limit
        """,
    )
    suspend fun pending(limit: Int): List<SyncOperationEntity>

    @Query(
        """
        UPDATE sync_operations
        SET uploadedAtEpochMillis = :uploadedAtEpochMillis
        WHERE id IN (:operationIds)
        """,
    )
    suspend fun markUploaded(operationIds: List<String>, uploadedAtEpochMillis: Long)

    @Query("SELECT COUNT(*) FROM sync_operations WHERE uploadedAtEpochMillis IS NULL")
    suspend fun pendingCount(): Int
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
    ],
    version = 2,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun timeEntryDao(): TimeEntryDao
    abstract fun syncOperationDao(): SyncOperationDao

    @Transaction
    open suspend fun upsertTaskAndAppendOperation(
        task: TaskEntity,
        operation: SyncOperationEntity,
    ) {
        taskDao().upsert(task)
        syncOperationDao().append(operation)
    }

    @Transaction
    open suspend fun upsertProjectAndAppendOperation(
        project: ProjectEntity,
        operation: SyncOperationEntity,
    ) {
        workspaceDao().upsertProject(project)
        syncOperationDao().append(operation)
    }

    @Transaction
    open suspend fun purgeTaskAndAppendOperation(
        taskId: String,
        tombstone: TombstoneEntity,
        operation: SyncOperationEntity,
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
        syncOperationDao().append(operation)
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
                .addMigrations(MIGRATION_1_2)
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
