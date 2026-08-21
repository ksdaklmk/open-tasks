package app.opentasks.core.data.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.opentasks.core.data.db.ActivityEntryEntity
import app.opentasks.core.data.db.AttachmentEntity
import app.opentasks.core.data.db.AutomationRuleEntity
import app.opentasks.core.data.db.ChecklistItemEntity
import app.opentasks.core.data.db.MemberEntity
import app.opentasks.core.data.db.MilestoneEntity
import app.opentasks.core.data.db.MyDayEntryEntity
import app.opentasks.core.data.db.NoteEntity
import app.opentasks.core.data.db.ProjectEntity
import app.opentasks.core.data.db.ReminderEntity
import app.opentasks.core.data.db.RetiredBlobSetEntity
import app.opentasks.core.data.db.SavedViewEntity
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

/**
 * The only structured write path a recovery import may take.
 *
 * Snapshot records insert with `ABORT`, so a duplicate identity fails the whole
 * import instead of silently replacing an authenticated after-image. Segment
 * replay upserts with `REPLACE` and deletes one exact primary or composite
 * identity at a time. Nothing here is reachable from normal operation: only
 * the recovery importer and the staged-vault verifier hold this DAO, and both
 * only ever run against a new inactive staging database.
 */
@Dao
internal interface RecoveryImportDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVault(value: VaultEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkspace(value: WorkspaceEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMember(value: MemberEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProject(value: ProjectEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkflowStatus(value: WorkflowStatusEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMilestone(value: MilestoneEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTask(value: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChecklistItem(value: ChecklistItemEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTaskDependency(value: TaskDependencyEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTag(value: TagEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTaskTag(value: TaskTagEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReminder(value: ReminderEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttachment(value: AttachmentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertActivityEntry(value: ActivityEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTimeEntry(value: TimeEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTemplate(value: TemplateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSavedView(value: SavedViewEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTombstone(value: TombstoneEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNote(value: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRetiredBlobSet(value: RetiredBlobSetEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAutomationRule(value: AutomationRuleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMyDayEntry(value: MyDayEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVault(value: VaultEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkspace(value: WorkspaceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(value: MemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(value: ProjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkflowStatus(value: WorkflowStatusEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMilestone(value: MilestoneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(value: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChecklistItem(value: ChecklistItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTaskDependency(value: TaskDependencyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTag(value: TagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTaskTag(value: TaskTagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReminder(value: ReminderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachment(value: AttachmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActivityEntry(value: ActivityEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTimeEntry(value: TimeEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplate(value: TemplateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSavedView(value: SavedViewEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstone(value: TombstoneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(value: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRetiredBlobSet(value: RetiredBlobSetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAutomationRule(value: AutomationRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMyDayEntry(value: MyDayEntryEntity)

    @Query("DELETE FROM vaults WHERE id = :id")
    suspend fun deleteVault(id: String): Int

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteWorkspace(id: String): Int

    @Query("DELETE FROM members WHERE id = :id")
    suspend fun deleteMember(id: String): Int

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProject(id: String): Int

    @Query("DELETE FROM workflow_statuses WHERE id = :id")
    suspend fun deleteWorkflowStatus(id: String): Int

    @Query("DELETE FROM milestones WHERE id = :id")
    suspend fun deleteMilestone(id: String): Int

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: String): Int

    @Query("DELETE FROM checklist_items WHERE id = :id")
    suspend fun deleteChecklistItem(id: String): Int

    @Query(
        """
        DELETE FROM task_dependencies
        WHERE taskId = :taskId AND dependsOnTaskId = :dependsOnTaskId
        """,
    )
    suspend fun deleteTaskDependency(taskId: String, dependsOnTaskId: String): Int

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: String): Int

    @Query("DELETE FROM task_tags WHERE taskId = :taskId AND tagId = :tagId")
    suspend fun deleteTaskTag(taskId: String, tagId: String): Int

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: String): Int

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteAttachment(id: String): Int

    @Query("DELETE FROM activity_entries WHERE id = :id")
    suspend fun deleteActivityEntry(id: String): Int

    @Query("DELETE FROM time_entries WHERE id = :id")
    suspend fun deleteTimeEntry(id: String): Int

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun deleteTemplate(id: String): Int

    @Query("DELETE FROM saved_views WHERE id = :id")
    suspend fun deleteSavedView(id: String): Int

    @Query("DELETE FROM tombstones WHERE objectId = :objectId AND objectType = :objectType")
    suspend fun deleteTombstone(objectId: String, objectType: String): Int

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String): Int

    @Query("DELETE FROM retired_blob_sets WHERE blobSetId = :blobSetId")
    suspend fun deleteRetiredBlobSet(blobSetId: String): Int

    @Query("DELETE FROM automation_rules WHERE id = :id")
    suspend fun deleteAutomationRule(id: String): Int

    @Query("DELETE FROM my_day_entries WHERE taskId = :taskId")
    suspend fun deleteMyDayEntry(taskId: String): Int

    /**
     * Reads every activity entry, unlike Stage 2 capture, which attributes a
     * relationless entry through `backup_journal` evidence a recovered vault
     * deliberately does not carry.
     */
    @Query("SELECT * FROM activity_entries ORDER BY id")
    suspend fun allActivityEntries(): List<ActivityEntryEntity>

    /** Reads every tombstone, for the same reason as [allActivityEntries]. */
    @Query("SELECT * FROM tombstones ORDER BY objectId, objectType")
    suspend fun allTombstones(): List<TombstoneEntity>

    /**
     * Reads every retired blob set, for the same reason as [allActivityEntries]:
     * a blob set is retired only after its attachment and owning task are both
     * gone, so it carries no reference a vault-scoped join could resolve.
     */
    @Query("SELECT * FROM retired_blob_sets ORDER BY blobSetId")
    suspend fun allRetiredBlobSets(): List<RetiredBlobSetEntity>

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM vaults) +
            (SELECT COUNT(*) FROM workspaces) +
            (SELECT COUNT(*) FROM members) +
            (SELECT COUNT(*) FROM projects) +
            (SELECT COUNT(*) FROM workflow_statuses) +
            (SELECT COUNT(*) FROM milestones) +
            (SELECT COUNT(*) FROM tasks) +
            (SELECT COUNT(*) FROM checklist_items) +
            (SELECT COUNT(*) FROM task_dependencies) +
            (SELECT COUNT(*) FROM tags) +
            (SELECT COUNT(*) FROM task_tags) +
            (SELECT COUNT(*) FROM reminders) +
            (SELECT COUNT(*) FROM attachments) +
            (SELECT COUNT(*) FROM activity_entries) +
            (SELECT COUNT(*) FROM time_entries) +
            (SELECT COUNT(*) FROM templates) +
            (SELECT COUNT(*) FROM saved_views) +
            (SELECT COUNT(*) FROM tombstones) +
            (SELECT COUNT(*) FROM notes) +
            (SELECT COUNT(*) FROM retired_blob_sets) +
            (SELECT COUNT(*) FROM automation_rules) +
            (SELECT COUNT(*) FROM my_day_entries)
        """,
    )
    suspend fun structuredRecordCount(): Int

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM backup_journal) +
            (SELECT COUNT(*) FROM sync_operations) +
            (SELECT COUNT(*) FROM remote_backup_config) +
            (SELECT COUNT(*) FROM remote_backup_object) +
            (SELECT COUNT(*) FROM remote_backup_operation)
        """,
    )
    suspend fun operationalRecordCount(): Int

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM backup_state) +
            (SELECT COUNT(*) FROM vault_recovery_envelope)
        """,
    )
    suspend fun localBackupStateCount(): Int

    /**
     * Counts journal rows alone.
     *
     * A staged vault is imported with an empty journal, but the local retention
     * purge legitimately appends to it the first time a normal repository opens
     * the slot, so that one operational table has to be countable on its own.
     */
    @Query("SELECT COUNT(*) FROM backup_journal")
    suspend fun journalEntryCount(): Int

    /**
     * Counts every structured reference that resolves to no row.
     *
     * Room declares no SQL foreign keys, so `PRAGMA foreign_key_check` cannot
     * see these relations; a recovered vault proves them here instead.
     */
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM workspaces AS w
                WHERE NOT EXISTS (SELECT 1 FROM vaults AS v WHERE v.id = w.vaultId)) +
            (SELECT COUNT(*) FROM workspaces AS w
                WHERE NOT EXISTS (SELECT 1 FROM members AS m WHERE m.id = w.ownerId)) +
            (SELECT COUNT(*) FROM projects AS p
                WHERE NOT EXISTS (SELECT 1 FROM workspaces AS w WHERE w.id = p.workspaceId)) +
            (SELECT COUNT(*) FROM workflow_statuses AS s
                WHERE s.projectId IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM projects AS p WHERE p.id = s.projectId)) +
            (SELECT COUNT(*) FROM milestones AS m
                WHERE NOT EXISTS (SELECT 1 FROM projects AS p WHERE p.id = m.projectId)) +
            (SELECT COUNT(*) FROM tasks AS t
                WHERE NOT EXISTS (SELECT 1 FROM workspaces AS w WHERE w.id = t.workspaceId)) +
            (SELECT COUNT(*) FROM tasks AS t
                WHERE t.projectId IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM projects AS p WHERE p.id = t.projectId)) +
            (SELECT COUNT(*) FROM tasks AS t
                WHERE t.parentTaskId IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM tasks AS p WHERE p.id = t.parentTaskId)) +
            (SELECT COUNT(*) FROM tasks AS t
                WHERE NOT EXISTS (
                    SELECT 1 FROM workflow_statuses AS s WHERE s.id = t.statusId
                )) +
            (SELECT COUNT(*) FROM tasks AS t
                WHERE t.milestoneId IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM milestones AS m WHERE m.id = t.milestoneId)) +
            (SELECT COUNT(*) FROM checklist_items AS c
                WHERE NOT EXISTS (SELECT 1 FROM tasks AS t WHERE t.id = c.taskId)) +
            (SELECT COUNT(*) FROM task_dependencies AS d
                WHERE NOT EXISTS (SELECT 1 FROM tasks AS t WHERE t.id = d.taskId)) +
            (SELECT COUNT(*) FROM task_dependencies AS d
                WHERE NOT EXISTS (SELECT 1 FROM tasks AS t WHERE t.id = d.dependsOnTaskId)) +
            (SELECT COUNT(*) FROM tags AS g
                WHERE NOT EXISTS (SELECT 1 FROM workspaces AS w WHERE w.id = g.workspaceId)) +
            (SELECT COUNT(*) FROM task_tags AS r
                WHERE NOT EXISTS (SELECT 1 FROM tasks AS t WHERE t.id = r.taskId)) +
            (SELECT COUNT(*) FROM task_tags AS r
                WHERE NOT EXISTS (SELECT 1 FROM tags AS g WHERE g.id = r.tagId)) +
            (SELECT COUNT(*) FROM reminders AS m
                WHERE NOT EXISTS (SELECT 1 FROM tasks AS t WHERE t.id = m.taskId)) +
            (SELECT COUNT(*) FROM attachments AS a
                WHERE NOT EXISTS (SELECT 1 FROM tasks AS t WHERE t.id = a.taskId)) +
            (SELECT COUNT(*) FROM activity_entries AS e
                WHERE e.taskId IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM tasks AS t WHERE t.id = e.taskId)) +
            (SELECT COUNT(*) FROM activity_entries AS e
                WHERE e.projectId IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM projects AS p WHERE p.id = e.projectId)) +
            (SELECT COUNT(*) FROM time_entries AS e
                WHERE NOT EXISTS (SELECT 1 FROM tasks AS t WHERE t.id = e.taskId)) +
            (SELECT COUNT(*) FROM templates AS p
                WHERE NOT EXISTS (SELECT 1 FROM workspaces AS w WHERE w.id = p.workspaceId)) +
            (SELECT COUNT(*) FROM saved_views AS v
                WHERE NOT EXISTS (SELECT 1 FROM workspaces AS w WHERE w.id = v.workspaceId)) +
            (SELECT COUNT(*) FROM notes AS n
                WHERE n.taskId IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM tasks AS t WHERE t.id = n.taskId)) +
            (SELECT COUNT(*) FROM notes AS n
                WHERE n.projectId IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM projects AS p WHERE p.id = n.projectId)) +
            (SELECT COUNT(*) FROM automation_rules AS r
                WHERE NOT EXISTS (
                    SELECT 1 FROM workspaces AS w WHERE w.id = r.workspaceId
                )) +
            (SELECT COUNT(*) FROM my_day_entries AS e
                WHERE NOT EXISTS (SELECT 1 FROM tasks AS t WHERE t.id = e.taskId))
        """,
    )
    suspend fun danglingReferenceCount(): Int
}
