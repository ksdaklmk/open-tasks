package app.opentasks.core.data.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import app.opentasks.core.crypto.VaultKeyEnvelope
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
import app.opentasks.core.data.db.VaultDatabase
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
        SELECT COUNT(*) FROM (
            SELECT operationId FROM backup_journal
            WHERE vaultId = :vaultId
                AND generation > :afterGeneration
                AND generation <= :throughGeneration
            ORDER BY generation, sequence
            LIMIT :limit
        )
        """,
    )
    suspend fun countThrough(
        vaultId: String,
        afterGeneration: Long,
        throughGeneration: Long,
        limit: Int,
    ): Int
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

    @Query(
        """
        UPDATE backup_state SET
            currentGeneration = :nextGeneration,
            packageState = CASE
                WHEN packageState = 'READY'
                    AND (
                        portablePackageGeneration IS NULL
                        OR portablePackageGeneration < :nextGeneration
                    )
                THEN 'UPDATE_PENDING'
                ELSE packageState
            END
        WHERE vaultId = :vaultId
            AND currentGeneration = :expectedCurrentGeneration
        """,
    )
    suspend fun advanceGeneration(
        vaultId: String,
        expectedCurrentGeneration: Long,
        nextGeneration: Long,
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

    @Query(
        """
        SELECT member.* FROM members AS member
        WHERE EXISTS (
            SELECT 1 FROM workspaces AS workspace
            WHERE workspace.ownerId = member.id AND workspace.vaultId = :vaultId
        )
        ORDER BY member.id
        """,
    )
    suspend fun members(vaultId: String): List<MemberEntity>

    @Query(
        """
        SELECT project.* FROM projects AS project
        INNER JOIN workspaces AS workspace ON workspace.id = project.workspaceId
        WHERE workspace.vaultId = :vaultId
        ORDER BY project.id
        """,
    )
    suspend fun projects(vaultId: String): List<ProjectEntity>

    @Query(
        """
        SELECT status.* FROM workflow_statuses AS status
        WHERE EXISTS (
            SELECT 1 FROM projects AS project
            INNER JOIN workspaces AS workspace ON workspace.id = project.workspaceId
            WHERE project.id = status.projectId AND workspace.vaultId = :vaultId
        ) OR (
            status.projectId IS NULL
            AND (SELECT COUNT(*) FROM workspaces) = 1
            AND EXISTS (
                SELECT 1 FROM workspaces AS workspace
                WHERE workspace.vaultId = :vaultId
            )
        )
        ORDER BY status.id
        """,
    )
    suspend fun workflowStatuses(vaultId: String): List<WorkflowStatusEntity>

    @Query(
        """
        SELECT COUNT(*) FROM workflow_statuses AS status
        WHERE status.projectId IS NULL
            AND NOT (
                (SELECT COUNT(*) FROM workspaces) = 1
                AND EXISTS (
                    SELECT 1 FROM workspaces AS workspace
                    WHERE workspace.vaultId = :vaultId
                )
            )
        """,
    )
    suspend fun ambiguousInboxWorkflowStatusCount(vaultId: String): Int

    @Query(
        """
        SELECT milestone.* FROM milestones AS milestone
        INNER JOIN projects AS project ON project.id = milestone.projectId
        INNER JOIN workspaces AS workspace ON workspace.id = project.workspaceId
        WHERE workspace.vaultId = :vaultId
        ORDER BY milestone.id
        """,
    )
    suspend fun milestones(vaultId: String): List<MilestoneEntity>

    @Query(
        """
        SELECT task.* FROM tasks AS task
        INNER JOIN workspaces AS workspace ON workspace.id = task.workspaceId
        WHERE workspace.vaultId = :vaultId
        ORDER BY task.id
        """,
    )
    suspend fun tasks(vaultId: String): List<TaskEntity>

    @Query(
        """
        SELECT item.* FROM checklist_items AS item
        INNER JOIN tasks AS task ON task.id = item.taskId
        INNER JOIN workspaces AS workspace ON workspace.id = task.workspaceId
        WHERE workspace.vaultId = :vaultId
        ORDER BY item.id
        """,
    )
    suspend fun checklistItems(vaultId: String): List<ChecklistItemEntity>

    @Query(
        """
        SELECT dependency.* FROM task_dependencies AS dependency
        INNER JOIN tasks AS task ON task.id = dependency.taskId
        INNER JOIN workspaces AS taskWorkspace ON taskWorkspace.id = task.workspaceId
        INNER JOIN tasks AS prerequisite ON prerequisite.id = dependency.dependsOnTaskId
        INNER JOIN workspaces AS prerequisiteWorkspace
            ON prerequisiteWorkspace.id = prerequisite.workspaceId
        WHERE taskWorkspace.vaultId = :vaultId
            AND prerequisiteWorkspace.vaultId = :vaultId
        ORDER BY dependency.taskId, dependency.dependsOnTaskId
        """,
    )
    suspend fun taskDependencies(vaultId: String): List<TaskDependencyEntity>

    @Query(
        """
        SELECT COUNT(*) FROM task_dependencies AS dependency
        INNER JOIN tasks AS task ON task.id = dependency.taskId
        INNER JOIN workspaces AS taskWorkspace ON taskWorkspace.id = task.workspaceId
        INNER JOIN tasks AS prerequisite ON prerequisite.id = dependency.dependsOnTaskId
        INNER JOIN workspaces AS prerequisiteWorkspace
            ON prerequisiteWorkspace.id = prerequisite.workspaceId
        WHERE taskWorkspace.vaultId != prerequisiteWorkspace.vaultId
            AND (
                taskWorkspace.vaultId = :vaultId
                OR prerequisiteWorkspace.vaultId = :vaultId
            )
        """,
    )
    suspend fun crossVaultTaskDependencyCount(vaultId: String): Int

    @Query(
        """
        SELECT tag.* FROM tags AS tag
        INNER JOIN workspaces AS workspace ON workspace.id = tag.workspaceId
        WHERE workspace.vaultId = :vaultId
        ORDER BY tag.id
        """,
    )
    suspend fun tags(vaultId: String): List<TagEntity>

    @Query(
        """
        SELECT relation.* FROM task_tags AS relation
        INNER JOIN tasks AS task ON task.id = relation.taskId
        INNER JOIN workspaces AS taskWorkspace ON taskWorkspace.id = task.workspaceId
        INNER JOIN tags AS tag ON tag.id = relation.tagId
        INNER JOIN workspaces AS tagWorkspace ON tagWorkspace.id = tag.workspaceId
        WHERE taskWorkspace.vaultId = :vaultId AND tagWorkspace.vaultId = :vaultId
        ORDER BY relation.taskId, relation.tagId
        """,
    )
    suspend fun taskTags(vaultId: String): List<TaskTagEntity>

    @Query(
        """
        SELECT COUNT(*) FROM task_tags AS relation
        INNER JOIN tasks AS task ON task.id = relation.taskId
        INNER JOIN workspaces AS taskWorkspace ON taskWorkspace.id = task.workspaceId
        INNER JOIN tags AS tag ON tag.id = relation.tagId
        INNER JOIN workspaces AS tagWorkspace ON tagWorkspace.id = tag.workspaceId
        WHERE taskWorkspace.vaultId != tagWorkspace.vaultId
            AND (
                taskWorkspace.vaultId = :vaultId
                OR tagWorkspace.vaultId = :vaultId
            )
        """,
    )
    suspend fun crossVaultTaskTagCount(vaultId: String): Int

    @Query(
        """
        SELECT reminder.* FROM reminders AS reminder
        INNER JOIN tasks AS task ON task.id = reminder.taskId
        INNER JOIN workspaces AS workspace ON workspace.id = task.workspaceId
        WHERE workspace.vaultId = :vaultId
        ORDER BY reminder.id
        """,
    )
    suspend fun reminders(vaultId: String): List<ReminderEntity>

    @Query(
        """
        SELECT attachment.* FROM attachments AS attachment
        INNER JOIN tasks AS task ON task.id = attachment.taskId
        INNER JOIN workspaces AS workspace ON workspace.id = task.workspaceId
        WHERE workspace.vaultId = :vaultId
        ORDER BY attachment.id
        """,
    )
    suspend fun attachments(vaultId: String): List<AttachmentEntity>

    @Query(
        """
        SELECT activity.* FROM activity_entries AS activity
        WHERE (
            activity.taskId IS NOT NULL
            AND EXISTS (
                SELECT 1 FROM tasks AS task
                INNER JOIN workspaces AS workspace ON workspace.id = task.workspaceId
                WHERE task.id = activity.taskId AND workspace.vaultId = :vaultId
            )
            AND (
                activity.projectId IS NULL OR EXISTS (
                    SELECT 1 FROM projects AS project
                    INNER JOIN workspaces AS workspace ON workspace.id = project.workspaceId
                    WHERE project.id = activity.projectId AND workspace.vaultId = :vaultId
                )
            )
        ) OR (
            activity.taskId IS NULL
            AND activity.projectId IS NOT NULL
            AND EXISTS (
                SELECT 1 FROM projects AS project
                INNER JOIN workspaces AS workspace ON workspace.id = project.workspaceId
                WHERE project.id = activity.projectId AND workspace.vaultId = :vaultId
            )
        ) OR (
            activity.taskId IS NULL
            AND activity.projectId IS NULL
            AND (
                (
                    (SELECT COUNT(*) FROM vaults) = 1
                    AND EXISTS (SELECT 1 FROM vaults AS sole WHERE sole.id = :vaultId)
                ) OR (
                    (
                        SELECT COUNT(DISTINCT owner.vaultId)
                        FROM backup_journal AS owner
                        WHERE owner.objectId = activity.id
                            AND owner.objectType = 'ACTIVITY_ENTRY'
                    ) = 1
                    AND EXISTS (
                        SELECT 1 FROM backup_journal AS journal
                        WHERE journal.vaultId = :vaultId
                            AND journal.objectId = activity.id
                            AND journal.objectType = 'ACTIVITY_ENTRY'
                    )
                )
            )
        )
        ORDER BY activity.id
        """,
    )
    suspend fun activityEntries(vaultId: String): List<ActivityEntryEntity>

    @Query(
        """
        SELECT entry.* FROM time_entries AS entry
        INNER JOIN tasks AS task ON task.id = entry.taskId
        INNER JOIN workspaces AS workspace ON workspace.id = task.workspaceId
        WHERE workspace.vaultId = :vaultId
        ORDER BY entry.id
        """,
    )
    suspend fun timeEntries(vaultId: String): List<TimeEntryEntity>

    @Query(
        """
        SELECT template.* FROM templates AS template
        INNER JOIN workspaces AS workspace ON workspace.id = template.workspaceId
        WHERE workspace.vaultId = :vaultId
        ORDER BY template.id
        """,
    )
    suspend fun templates(vaultId: String): List<TemplateEntity>

    @Query(
        """
        SELECT savedView.* FROM saved_views AS savedView
        INNER JOIN workspaces AS workspace ON workspace.id = savedView.workspaceId
        WHERE workspace.vaultId = :vaultId
        ORDER BY savedView.id
        """,
    )
    suspend fun savedViews(vaultId: String): List<SavedViewEntity>

    /**
     * Tombstones and relationless activity entries carry no vault column, so a
     * database holding several vaults attributes them through `backup_journal`
     * evidence. A database holding exactly one vault owns every row in it, and
     * needs no evidence: a recovered vault is imported with an empty journal by
     * design, and would otherwise be unable to capture the fresh complete base
     * its next remote publication requires.
     */
    @Query(
        """
        SELECT tombstone.* FROM tombstones AS tombstone
        WHERE (
            (SELECT COUNT(*) FROM vaults) = 1
            AND EXISTS (SELECT 1 FROM vaults AS sole WHERE sole.id = :vaultId)
        ) OR (
            (
                SELECT COUNT(DISTINCT owner.vaultId)
                FROM backup_journal AS owner
                WHERE owner.objectId = tombstone.objectId
                    AND (
                        UPPER(owner.objectType) = UPPER(tombstone.objectType)
                        OR LOWER(owner.objectType) = LOWER(tombstone.objectType) || '.purge'
                    )
            ) = 1
            AND EXISTS (
                SELECT 1 FROM backup_journal AS journal
                WHERE journal.vaultId = :vaultId
                    AND journal.objectId = tombstone.objectId
                    AND (
                        UPPER(journal.objectType) = UPPER(tombstone.objectType)
                        OR LOWER(journal.objectType) = LOWER(tombstone.objectType) || '.purge'
                    )
            )
        )
        ORDER BY tombstone.objectId, tombstone.objectType
        """,
    )
    suspend fun tombstones(vaultId: String): List<TombstoneEntity>

    @Query(
        """
        SELECT COUNT(*) FROM activity_entries AS activity
        WHERE (
            activity.taskId IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM tasks AS task WHERE task.id = activity.taskId)
        ) OR (
            activity.projectId IS NOT NULL
            AND NOT EXISTS (
                SELECT 1 FROM projects AS project WHERE project.id = activity.projectId
            )
        ) OR (
            activity.taskId IS NOT NULL
            AND activity.projectId IS NOT NULL
            AND NOT EXISTS (
                SELECT 1 FROM tasks AS task
                INNER JOIN workspaces AS taskWorkspace
                    ON taskWorkspace.id = task.workspaceId
                INNER JOIN projects AS project ON project.id = activity.projectId
                INNER JOIN workspaces AS projectWorkspace
                    ON projectWorkspace.id = project.workspaceId
                WHERE task.id = activity.taskId
                    AND taskWorkspace.vaultId = projectWorkspace.vaultId
            )
        ) OR (
            activity.taskId IS NULL
            AND activity.projectId IS NULL
            AND (SELECT COUNT(*) FROM vaults) != 1
            AND (
                SELECT COUNT(DISTINCT journal.vaultId)
                FROM backup_journal AS journal
                WHERE journal.objectId = activity.id
                    AND journal.objectType = 'ACTIVITY_ENTRY'
            ) != 1
        )
        """,
    )
    suspend fun unassignableActivityEntryCount(): Int

    @Query(
        """
        SELECT COUNT(*) FROM tombstones AS tombstone
        WHERE (SELECT COUNT(*) FROM vaults) != 1
        AND (
            SELECT COUNT(DISTINCT journal.vaultId)
            FROM backup_journal AS journal
            WHERE journal.objectId = tombstone.objectId
                AND (
                    UPPER(journal.objectType) = UPPER(tombstone.objectType)
                    OR LOWER(journal.objectType) = LOWER(tombstone.objectType) || '.purge'
                )
        ) != 1
        """,
    )
    suspend fun unassignableTombstoneCount(): Int
}

internal suspend fun BackupCaptureDao.allRecords(vaultId: String): List<BackupRecordV1> =
    buildList {
        require(crossVaultTaskDependencyCount(vaultId) == 0) {
            "Task dependency crosses vaults"
        }
        require(crossVaultTaskTagCount(vaultId) == 0) {
            "Task tag crosses vaults"
        }
        require(unassignableActivityEntryCount() == 0) {
            "Activity entry cannot be assigned to a vault"
        }
        require(unassignableTombstoneCount() == 0) {
            "Tombstone cannot be assigned to a vault"
        }
        require(ambiguousInboxWorkflowStatusCount(vaultId) == 0) {
            "Inbox workflow cannot be assigned to one workspace"
        }
        vaults(vaultId).mapTo(this) { it.toBackupRecordV1() }
        workspaces(vaultId).mapTo(this) { it.toBackupRecordV1() }
        members(vaultId).mapTo(this) { it.toBackupRecordV1() }
        projects(vaultId).mapTo(this) { it.toBackupRecordV1() }
        workflowStatuses(vaultId).mapTo(this) { it.toBackupRecordV1() }
        milestones(vaultId).mapTo(this) { it.toBackupRecordV1() }
        tasks(vaultId).mapTo(this) { it.toBackupRecordV1() }
        checklistItems(vaultId).mapTo(this) { it.toBackupRecordV1() }
        taskDependencies(vaultId).mapTo(this) { it.toBackupRecordV1() }
        tags(vaultId).mapTo(this) { it.toBackupRecordV1() }
        taskTags(vaultId).mapTo(this) { it.toBackupRecordV1() }
        reminders(vaultId).mapTo(this) { it.toBackupRecordV1() }
        attachments(vaultId).mapTo(this) { it.toBackupRecordV1() }
        activityEntries(vaultId).mapTo(this) { it.toBackupRecordV1() }
        timeEntries(vaultId).mapTo(this) { it.toBackupRecordV1() }
        templates(vaultId).mapTo(this) { it.toBackupRecordV1() }
        savedViews(vaultId).mapTo(this) { it.toBackupRecordV1() }
        tombstones(vaultId).mapTo(this) { it.toBackupRecordV1() }
    }

fun interface BackupStateMutation {
    /**
     * Runs against the latest row while the store owns its atomic transaction.
     * Returning null rejects the transition without changing durable state.
     */
    fun apply(current: BackupStateEntity): BackupStateEntity?
}

interface BackupStateStore {
    fun observe(vaultId: VaultId): Flow<BackupStateEntity>
    suspend fun get(vaultId: VaultId): BackupStateEntity?
    suspend fun mutate(
        vaultId: VaultId,
        mutation: BackupStateMutation,
    ): BackupStateEntity?
}

interface BackupJournalStore {
    suspend fun after(
        vaultId: VaultId,
        generation: Long,
        limit: Int,
    ): List<BackupJournalEntity>

    suspend fun countThrough(
        vaultId: VaultId,
        afterGeneration: Long,
        throughGeneration: Long,
        limit: Int,
    ): Int

    suspend fun between(
        vaultId: VaultId,
        afterGeneration: Long,
        throughGeneration: Long,
    ): List<BackupJournalEntity>
}

interface RecoveryEnvelopeStore {
    suspend fun get(vaultId: VaultId): VaultKeyEnvelope?
    suspend fun upsert(vaultId: VaultId, envelope: VaultKeyEnvelope)
    suspend fun delete(vaultId: VaultId)
    suspend fun commitInitial(
        vaultId: VaultId,
        envelope: VaultKeyEnvelope,
        published: VerifiedPortableBackup,
    ): BackupStateEntity?
}

class RoomBackupStateStore(
    private val database: VaultDatabase,
    private val beforeMutationTransaction: suspend () -> Unit = {},
) : BackupStateStore {
    override fun observe(vaultId: VaultId): Flow<BackupStateEntity> =
        database.backupStateDao().observe(vaultId.value)

    override suspend fun get(vaultId: VaultId): BackupStateEntity? =
        database.backupStateDao().get(vaultId.value)

    override suspend fun mutate(
        vaultId: VaultId,
        mutation: BackupStateMutation,
    ): BackupStateEntity? {
        beforeMutationTransaction()
        return database.withTransaction {
            database.backupStateDao().mutateLatest(vaultId, mutation)
        }
    }
}

class RoomBackupJournalStore(
    private val dao: BackupJournalDao,
) : BackupJournalStore {
    override suspend fun after(
        vaultId: VaultId,
        generation: Long,
        limit: Int,
    ): List<BackupJournalEntity> = dao.after(vaultId.value, generation, limit)

    override suspend fun countThrough(
        vaultId: VaultId,
        afterGeneration: Long,
        throughGeneration: Long,
        limit: Int,
    ): Int {
        if (throughGeneration <= afterGeneration || limit <= 0) return 0
        return dao.countThrough(
            vaultId = vaultId.value,
            afterGeneration = afterGeneration,
            throughGeneration = throughGeneration,
            limit = limit,
        )
    }

    override suspend fun between(
        vaultId: VaultId,
        afterGeneration: Long,
        throughGeneration: Long,
    ): List<BackupJournalEntity> {
        if (throughGeneration <= afterGeneration) return emptyList()
        return dao.between(vaultId.value, afterGeneration + 1, throughGeneration)
    }
}

class RoomRecoveryEnvelopeStore(
    private val database: VaultDatabase,
) : RecoveryEnvelopeStore {
    override suspend fun get(vaultId: VaultId): VaultKeyEnvelope? {
        val entity = database.vaultRecoveryEnvelopeDao().get(vaultId.value) ?: return null
        return try {
            RecoveryEnvelopeCodec.fromEntity(entity)
        } finally {
            entity.salt.fill(0)
            entity.nonce.fill(0)
            entity.wrappedKeyset.fill(0)
        }
    }

    override suspend fun upsert(
        vaultId: VaultId,
        envelope: VaultKeyEnvelope,
    ) {
        val entity = RecoveryEnvelopeCodec.toEntity(vaultId, envelope)
        try {
            database.vaultRecoveryEnvelopeDao().upsert(entity)
        } finally {
            entity.salt.fill(0)
            entity.nonce.fill(0)
            entity.wrappedKeyset.fill(0)
        }
    }

    override suspend fun delete(vaultId: VaultId) {
        database.vaultRecoveryEnvelopeDao().delete(vaultId.value)
    }

    override suspend fun commitInitial(
        vaultId: VaultId,
        envelope: VaultKeyEnvelope,
        published: VerifiedPortableBackup,
    ): BackupStateEntity? = database.withTransaction {
        require(published.vaultId == vaultId.value) {
            "Portable package belongs to another vault"
        }
        val envelopeDao = database.vaultRecoveryEnvelopeDao()
        val stateDao = database.backupStateDao()
        if (!acceptInitialEnvelopeWhenAbsent(envelopeDao.get(vaultId.value))) {
            return@withTransaction null
        }
        val updated = stateDao.mutateLatest(
            vaultId,
            BackupStateMutation { current ->
                if (
                    current.recoveryEnvelopeReady ||
                    current.packageState == PACKAGE_RESTORED_DETECTED ||
                    published.generation > current.currentGeneration
                ) {
                    null
                } else {
                    current.copy(
                        portablePackageGeneration = published.generation,
                        portablePackageBytes = published.totalPackageLength,
                        portablePackageProducedAtEpochMillis =
                            published.producedAtEpochMillis,
                        packageState = if (published.generation == current.currentGeneration) {
                            PACKAGE_READY
                        } else {
                            PACKAGE_UPDATE_PENDING
                        },
                        failureCategory = null,
                        recoveryEnvelopeReady = true,
                    )
                }
            },
        ) ?: return@withTransaction null
        val entity = RecoveryEnvelopeCodec.toEntity(vaultId, envelope)
        try {
            envelopeDao.upsert(entity)
            updated
        } finally {
            entity.salt.fill(0)
            entity.nonce.fill(0)
            entity.wrappedKeyset.fill(0)
        }
    }
}

internal fun acceptInitialEnvelopeWhenAbsent(
    existing: VaultRecoveryEnvelopeEntity?,
): Boolean {
    if (existing == null) return true
    existing.salt.fill(0)
    existing.nonce.fill(0)
    existing.wrappedKeyset.fill(0)
    return false
}

private suspend fun BackupStateDao.mutateLatest(
    vaultId: VaultId,
    mutation: BackupStateMutation,
): BackupStateEntity? {
    val current = get(vaultId.value) ?: return null
    val updated = mutation.apply(current) ?: return null
    require(updated.vaultId == current.vaultId) {
        "Backup state mutation changed vault ownership"
    }
    require(updated.currentGeneration == current.currentGeneration) {
        "Backup state mutation cannot allocate a generation"
    }
    if (updated == current) return current
    check(
        compareAndUpdate(
            vaultId = updated.vaultId,
            expectedCurrentGeneration = current.currentGeneration,
            currentGeneration = updated.currentGeneration,
            lastVerifiedSnapshotGeneration = updated.lastVerifiedSnapshotGeneration,
            currentBaseObjectId = updated.currentBaseObjectId,
            previousBaseObjectId = updated.previousBaseObjectId,
            latestVerifiedSegmentGeneration = updated.latestVerifiedSegmentGeneration,
            portablePackageGeneration = updated.portablePackageGeneration,
            portablePackageBytes = updated.portablePackageBytes,
            portablePackageProducedAtEpochMillis =
                updated.portablePackageProducedAtEpochMillis,
            packageState = updated.packageState,
            failureCategory = updated.failureCategory,
            recoveryEnvelopeReady = updated.recoveryEnvelopeReady,
            legacyOutboxCoveredAtGeneration = updated.legacyOutboxCoveredAtGeneration,
            snapshotCreatedAtEpochMillis = updated.snapshotCreatedAtEpochMillis,
        ) == 1,
    ) {
        "Backup state changed inside its mutation transaction"
    }
    return updated
}

private const val PACKAGE_READY = "READY"
private const val PACKAGE_UPDATE_PENDING = "UPDATE_PENDING"
private const val PACKAGE_RESTORED_DETECTED = "RESTORED_PACKAGE_DETECTED"
