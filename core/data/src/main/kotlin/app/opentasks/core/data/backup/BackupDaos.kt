package app.opentasks.core.data.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.opentasks.core.data.db.ActivityEntryEntity
import app.opentasks.core.data.db.AttachmentEntity
import app.opentasks.core.data.db.ChecklistItemEntity
import app.opentasks.core.data.db.MemberEntity
import app.opentasks.core.data.db.MilestoneEntity
import app.opentasks.core.data.db.ProjectEntity
import app.opentasks.core.data.db.ReminderEntity
import app.opentasks.core.data.db.SavedViewEntity
import app.opentasks.core.data.db.SyncOperationEntity
import app.opentasks.core.data.db.TagEntity
import app.opentasks.core.data.db.TaskDependencyEntity
import app.opentasks.core.data.db.TaskEntity
import app.opentasks.core.data.db.TaskTagEntity
import app.opentasks.core.data.db.TemplateEntity
import app.opentasks.core.data.db.TimeEntryEntity
import app.opentasks.core.data.db.TombstoneEntity
import app.opentasks.core.data.db.VaultEntity
import app.opentasks.core.data.db.WorkflowStatusEntity
import app.opentasks.core.data.db.WorkspaceEntity
import app.opentasks.core.model.VaultId
import kotlinx.coroutines.flow.Flow

@Dao
interface LegacySyncOperationDao {
    @Query(
        """
        SELECT * FROM sync_operations
        ORDER BY revisionWallMillis, revisionLogical, deviceId, id
        """,
    )
    suspend fun allForAudit(): List<SyncOperationEntity>

    @Query("SELECT COUNT(*) FROM sync_operations")
    suspend fun count(): Int
}

@Dao
interface BackupJournalDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: BackupJournalEntity)

    @Query(
        """
        SELECT * FROM backup_journal
        WHERE vaultId = :vaultId AND generation > :generation
        ORDER BY generation, sequence
        LIMIT :limit
        """,
    )
    suspend fun after(
        vaultId: String,
        generation: Long,
        limit: Int,
    ): List<BackupJournalEntity>

    @Query(
        """
        SELECT * FROM backup_journal
        WHERE vaultId = :vaultId
            AND generation BETWEEN :firstGeneration AND :lastGeneration
        ORDER BY generation, sequence
        """,
    )
    suspend fun between(
        vaultId: String,
        firstGeneration: Long,
        lastGeneration: Long,
    ): List<BackupJournalEntity>

    @Query(
        """
        SELECT COUNT(*) FROM backup_journal
        WHERE vaultId = :vaultId AND generation > :generation
        """,
    )
    suspend fun countAfter(vaultId: String, generation: Long): Int
}

@Dao
interface BackupStateDao {
    @Query("SELECT * FROM backup_state WHERE vaultId = :vaultId LIMIT 1")
    suspend fun get(vaultId: String): BackupStateEntity?

    @Query("SELECT * FROM backup_state WHERE vaultId = :vaultId LIMIT 1")
    suspend fun require(vaultId: String): BackupStateEntity

    @Query("SELECT * FROM backup_state WHERE vaultId = :vaultId LIMIT 1")
    fun observe(vaultId: String): Flow<BackupStateEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: BackupStateEntity)

    @Query(
        """
        UPDATE backup_state SET
            currentGeneration = :currentGeneration,
            lastVerifiedSnapshotGeneration = :lastVerifiedSnapshotGeneration,
            currentBaseObjectId = :currentBaseObjectId,
            previousBaseObjectId = :previousBaseObjectId,
            latestVerifiedSegmentGeneration = :latestVerifiedSegmentGeneration,
            portablePackageGeneration = :portablePackageGeneration,
            portablePackageBytes = :portablePackageBytes,
            portablePackageProducedAtEpochMillis = :portablePackageProducedAtEpochMillis,
            packageState = :packageState,
            failureCategory = :failureCategory,
            recoveryEnvelopeReady = :recoveryEnvelopeReady,
            legacyOutboxCoveredAtGeneration = :legacyOutboxCoveredAtGeneration,
            snapshotCreatedAtEpochMillis = :snapshotCreatedAtEpochMillis
        WHERE vaultId = :vaultId
            AND currentGeneration = :expectedCurrentGeneration
        """,
    )
    suspend fun compareAndUpdate(
        vaultId: String,
        expectedCurrentGeneration: Long,
        currentGeneration: Long,
        lastVerifiedSnapshotGeneration: Long?,
        currentBaseObjectId: String?,
        previousBaseObjectId: String?,
        latestVerifiedSegmentGeneration: Long?,
        portablePackageGeneration: Long?,
        portablePackageBytes: Long?,
        portablePackageProducedAtEpochMillis: Long?,
        packageState: String,
        failureCategory: String?,
        recoveryEnvelopeReady: Boolean,
        legacyOutboxCoveredAtGeneration: Long?,
        snapshotCreatedAtEpochMillis: Long?,
    ): Int
}

@Dao
interface VaultRecoveryEnvelopeDao {
    @Query("SELECT * FROM vault_recovery_envelope WHERE vaultId = :vaultId LIMIT 1")
    suspend fun get(vaultId: String): VaultRecoveryEnvelopeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: VaultRecoveryEnvelopeEntity)

    @Query("DELETE FROM vault_recovery_envelope WHERE vaultId = :vaultId")
    suspend fun delete(vaultId: String): Int
}

@Dao
interface BackupCaptureDao {
    @Query("SELECT * FROM vaults WHERE id = :vaultId ORDER BY id")
    suspend fun vaults(vaultId: String): List<VaultEntity>

    @Query("SELECT * FROM workspaces WHERE vaultId = :vaultId ORDER BY id")
    suspend fun workspaces(vaultId: String): List<WorkspaceEntity>

    @Query("SELECT * FROM members ORDER BY id")
    suspend fun members(): List<MemberEntity>

    @Query("SELECT * FROM projects ORDER BY id")
    suspend fun projects(): List<ProjectEntity>

    @Query("SELECT * FROM workflow_statuses ORDER BY id")
    suspend fun workflowStatuses(): List<WorkflowStatusEntity>

    @Query("SELECT * FROM milestones ORDER BY id")
    suspend fun milestones(): List<MilestoneEntity>

    @Query("SELECT * FROM tasks ORDER BY id")
    suspend fun tasks(): List<TaskEntity>

    @Query("SELECT * FROM checklist_items ORDER BY id")
    suspend fun checklistItems(): List<ChecklistItemEntity>

    @Query("SELECT * FROM task_dependencies ORDER BY taskId, dependsOnTaskId")
    suspend fun taskDependencies(): List<TaskDependencyEntity>

    @Query("SELECT * FROM tags ORDER BY id")
    suspend fun tags(): List<TagEntity>

    @Query("SELECT * FROM task_tags ORDER BY taskId, tagId")
    suspend fun taskTags(): List<TaskTagEntity>

    @Query("SELECT * FROM reminders ORDER BY id")
    suspend fun reminders(): List<ReminderEntity>

    @Query("SELECT * FROM attachments ORDER BY id")
    suspend fun attachments(): List<AttachmentEntity>

    @Query("SELECT * FROM activity_entries ORDER BY id")
    suspend fun activityEntries(): List<ActivityEntryEntity>

    @Query("SELECT * FROM time_entries ORDER BY id")
    suspend fun timeEntries(): List<TimeEntryEntity>

    @Query("SELECT * FROM templates ORDER BY id")
    suspend fun templates(): List<TemplateEntity>

    @Query("SELECT * FROM saved_views ORDER BY id")
    suspend fun savedViews(): List<SavedViewEntity>

    @Query("SELECT * FROM tombstones ORDER BY objectId, objectType")
    suspend fun tombstones(): List<TombstoneEntity>
}

internal suspend fun BackupCaptureDao.allRecords(vaultId: String): List<BackupRecordV1> =
    buildList {
        vaults(vaultId).mapTo(this) { it.toBackupRecordV1() }
        workspaces(vaultId).mapTo(this) { it.toBackupRecordV1() }
        members().mapTo(this) { it.toBackupRecordV1() }
        projects().mapTo(this) { it.toBackupRecordV1() }
        workflowStatuses().mapTo(this) { it.toBackupRecordV1() }
        milestones().mapTo(this) { it.toBackupRecordV1() }
        tasks().mapTo(this) { it.toBackupRecordV1() }
        checklistItems().mapTo(this) { it.toBackupRecordV1() }
        taskDependencies().mapTo(this) { it.toBackupRecordV1() }
        tags().mapTo(this) { it.toBackupRecordV1() }
        taskTags().mapTo(this) { it.toBackupRecordV1() }
        reminders().mapTo(this) { it.toBackupRecordV1() }
        attachments().mapTo(this) { it.toBackupRecordV1() }
        activityEntries().mapTo(this) { it.toBackupRecordV1() }
        timeEntries().mapTo(this) { it.toBackupRecordV1() }
        templates().mapTo(this) { it.toBackupRecordV1() }
        savedViews().mapTo(this) { it.toBackupRecordV1() }
        tombstones().mapTo(this) { it.toBackupRecordV1() }
    }

interface BackupStateStore {
    fun observe(vaultId: VaultId): Flow<BackupStateEntity>
    suspend fun get(vaultId: VaultId): BackupStateEntity?
    suspend fun compareAndUpdate(
        entity: BackupStateEntity,
        expectedCurrentGeneration: Long,
    ): Int
}

interface RecoveryEnvelopeStore {
    suspend fun get(vaultId: VaultId): VaultRecoveryEnvelopeEntity?
    suspend fun upsert(entity: VaultRecoveryEnvelopeEntity)
    suspend fun delete(vaultId: VaultId)
}

class RoomBackupStateStore(
    private val dao: BackupStateDao,
) : BackupStateStore {
    override fun observe(vaultId: VaultId): Flow<BackupStateEntity> = dao.observe(vaultId.value)

    override suspend fun get(vaultId: VaultId): BackupStateEntity? = dao.get(vaultId.value)

    override suspend fun compareAndUpdate(
        entity: BackupStateEntity,
        expectedCurrentGeneration: Long,
    ): Int = dao.compareAndUpdate(
        vaultId = entity.vaultId,
        expectedCurrentGeneration = expectedCurrentGeneration,
        currentGeneration = entity.currentGeneration,
        lastVerifiedSnapshotGeneration = entity.lastVerifiedSnapshotGeneration,
        currentBaseObjectId = entity.currentBaseObjectId,
        previousBaseObjectId = entity.previousBaseObjectId,
        latestVerifiedSegmentGeneration = entity.latestVerifiedSegmentGeneration,
        portablePackageGeneration = entity.portablePackageGeneration,
        portablePackageBytes = entity.portablePackageBytes,
        portablePackageProducedAtEpochMillis = entity.portablePackageProducedAtEpochMillis,
        packageState = entity.packageState,
        failureCategory = entity.failureCategory,
        recoveryEnvelopeReady = entity.recoveryEnvelopeReady,
        legacyOutboxCoveredAtGeneration = entity.legacyOutboxCoveredAtGeneration,
        snapshotCreatedAtEpochMillis = entity.snapshotCreatedAtEpochMillis,
    )
}

class RoomRecoveryEnvelopeStore(
    private val dao: VaultRecoveryEnvelopeDao,
) : RecoveryEnvelopeStore {
    override suspend fun get(vaultId: VaultId): VaultRecoveryEnvelopeEntity? =
        dao.get(vaultId.value)

    override suspend fun upsert(entity: VaultRecoveryEnvelopeEntity) {
        dao.upsert(entity)
    }

    override suspend fun delete(vaultId: VaultId) {
        dao.delete(vaultId.value)
    }
}
