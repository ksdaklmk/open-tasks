package app.opentasks.core.data.backup

import androidx.room.Dao
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
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.model.VaultId

fun interface BackupJournalAppendBoundary {
    suspend fun insert(
        dao: BackupJournalDao,
        entity: BackupJournalEntity,
    )
}

internal class RoomBackupJournalSession(
    private val vaultId: VaultId,
    private val stateDao: BackupStateDao,
    private val journalDao: BackupJournalDao,
    private val mutationDao: BackupMutationDao,
    private val mutationCodec: BackupMutationCodec,
    private val operationId: () -> String,
    private val sourceDeviceId: String,
    private val appendBoundary: BackupJournalAppendBoundary =
        BackupJournalAppendBoundary { dao, entity -> dao.insert(entity) },
) {
    private var generation: Long? = null
    private var nextSequence = 0

    suspend fun appendChanges(
        before: List<BackupRecordSnapshot>,
        after: List<BackupRecordSnapshot>,
    ) {
        diffBackupSnapshots(before, after).forEach { change ->
            when (change) {
                is BackupSnapshotChange.Upsert ->
                    appendUpsert(mutationDao.requireRecord(change.family, change.identity))
                is BackupSnapshotChange.Delete -> appendDelete(
                    family = change.family,
                    identity = change.identity,
                    previousRevision = change.previous.revision
                        ?: RecordRevision(0, 0, sourceDeviceId),
                )
            }
        }
    }

    private suspend fun appendUpsert(record: BackupRecordV1) {
        val revision = record.revision(sourceDeviceId)
        append(
            payload = BackupMutationPayloadV1(
                mutationKind = BackupMutationKind.UPSERT,
                record = record,
                deletedFamily = null,
                deletedIdentity = null,
            ),
            family = record.family,
            identity = record.identity,
            revision = revision,
        )
    }

    private suspend fun appendDelete(
        family: BackupRecordFamily,
        identity: List<String>,
        previousRevision: RecordRevision,
    ) {
        append(
            payload = BackupMutationPayloadV1(
                mutationKind = BackupMutationKind.DELETE,
                record = null,
                deletedFamily = family,
                deletedIdentity = identity,
            ),
            family = family,
            identity = identity,
            revision = previousRevision,
        )
    }

    private suspend fun append(
        payload: BackupMutationPayloadV1,
        family: BackupRecordFamily,
        identity: List<String>,
        revision: RecordRevision,
    ) {
        val allocatedGeneration = generation ?: allocateGeneration().also {
            generation = it
        }
        val encoded = mutationCodec.encode(payload)
        appendBoundary.insert(
            journalDao,
            BackupJournalEntity(
                operationId = operationId(),
                vaultId = vaultId.value,
                generation = allocatedGeneration,
                sequence = nextSequence,
                payloadFormatVersion = 1,
                mutationKind = payload.mutationKind.name,
                objectId = identity.toJournalObjectId(),
                objectType = family.name,
                payload = encoded,
                revisionWallMillis = revision.wallMillis,
                revisionLogical = revision.logical,
                sourceDeviceId = revision.deviceId,
            ),
        )
        nextSequence += 1
    }

    private suspend fun allocateGeneration(): Long {
        val current = stateDao.get(vaultId.value) ?: defaultBackupState(vaultId.value).also {
            stateDao.insert(it)
        }
        val next = Math.addExact(current.currentGeneration, 1)
        check(
            stateDao.advanceGeneration(
                vaultId = current.vaultId,
                expectedCurrentGeneration = current.currentGeneration,
                nextGeneration = next,
            ) == 1,
        ) {
            "Backup generation changed during a serialised command"
        }
        return next
    }
}

internal fun defaultBackupState(vaultId: String): BackupStateEntity = BackupStateEntity(
    vaultId = vaultId,
    currentGeneration = 0,
    lastVerifiedSnapshotGeneration = null,
    currentBaseObjectId = null,
    previousBaseObjectId = null,
    latestVerifiedSegmentGeneration = null,
    portablePackageGeneration = null,
    portablePackageBytes = null,
    portablePackageProducedAtEpochMillis = null,
    packageState = "NOT_PREPARED",
    failureCategory = null,
    recoveryEnvelopeReady = false,
    legacyOutboxCoveredAtGeneration = null,
    snapshotCreatedAtEpochMillis = null,
)

internal data class BackupRecordSnapshot(
    val family: BackupRecordFamily,
    val identity: List<String>,
    val fingerprint: Any,
    val revision: RecordRevision?,
)

internal sealed interface BackupSnapshotChange {
    val family: BackupRecordFamily
    val identity: List<String>

    data class Upsert(
        override val family: BackupRecordFamily,
        override val identity: List<String>,
    ) : BackupSnapshotChange

    data class Delete(
        val previous: BackupRecordSnapshot,
    ) : BackupSnapshotChange {
        override val family: BackupRecordFamily = previous.family
        override val identity: List<String> = previous.identity
    }
}

internal fun diffBackupSnapshots(
    before: List<BackupRecordSnapshot>,
    after: List<BackupRecordSnapshot>,
): List<BackupSnapshotChange> {
    val beforeByKey = before.associateBy { BackupRecordKey(it.family, it.identity) }
    val afterByKey = after.associateBy { BackupRecordKey(it.family, it.identity) }
    require(beforeByKey.size == before.size) { "Duplicate pre-command backup identity" }
    require(afterByKey.size == after.size) { "Duplicate post-command backup identity" }
    return (beforeByKey.keys + afterByKey.keys)
        .distinct()
        .sorted()
        .mapNotNull { key ->
            val previous = beforeByKey[key]
            val current = afterByKey[key]
            when {
                current == null && previous != null -> BackupSnapshotChange.Delete(previous)
                current != null && current.fingerprint != previous?.fingerprint ->
                    BackupSnapshotChange.Upsert(key.family, key.identity)
                else -> null
            }
        }
}

internal sealed interface BackupRecordChange {
    val family: BackupRecordFamily
    val identity: List<String>

    data class Upsert(
        val record: BackupRecordV1,
    ) : BackupRecordChange {
        override val family: BackupRecordFamily = record.family
        override val identity: List<String> = record.identity
    }

    data class Delete(
        override val family: BackupRecordFamily,
        override val identity: List<String>,
        val previous: BackupRecordV1,
    ) : BackupRecordChange
}

internal fun diffBackupRecords(
    before: List<BackupRecordV1>,
    after: List<BackupRecordV1>,
): List<BackupRecordChange> {
    val beforeByKey = before.associateBy(::BackupRecordKey)
    val afterByKey = after.associateBy(::BackupRecordKey)
    require(beforeByKey.size == before.size) { "Duplicate pre-command backup identity" }
    require(afterByKey.size == after.size) { "Duplicate post-command backup identity" }
    return (beforeByKey.keys + afterByKey.keys)
        .distinct()
        .sorted()
        .mapNotNull { key ->
            val previous = beforeByKey[key]
            val current = afterByKey[key]
            when {
                current == null && previous != null ->
                    BackupRecordChange.Delete(key.family, key.identity, previous)
                current != null && current != previous ->
                    BackupRecordChange.Upsert(current)
                else -> null
            }
        }
}

internal data class BackupRecordKey(
    val family: BackupRecordFamily,
    val identity: List<String>,
) : Comparable<BackupRecordKey> {
    constructor(record: BackupRecordV1) : this(record.family, record.identity)

    override fun compareTo(other: BackupRecordKey): Int {
        val familyResult = family.ordinal.compareTo(other.family.ordinal)
        if (familyResult != 0) return familyResult
        identity.zip(other.identity).forEach { (left, right) ->
            val identityResult = left.compareTo(right)
            if (identityResult != 0) return identityResult
        }
        return identity.size.compareTo(other.identity.size)
    }
}

internal data class RecordRevision(
    val wallMillis: Long,
    val logical: Int,
    val deviceId: String,
)

private fun BackupRecordV1.revision(fallbackDeviceId: String): RecordRevision {
    val values = fields.associate { it.name to it.value }
    return RecordRevision(
        wallMillis = values["revisionWallMillis"]?.toLong() ?: 0,
        logical = values["revisionLogical"]?.toInt() ?: 0,
        deviceId = values["revisionDeviceId"] ?: fallbackDeviceId,
    )
}

private fun List<String>.toJournalObjectId(): String =
    if (size == 1) {
        single()
    } else {
        joinToString(separator = "|") { component -> "${component.length}:$component" }
    }

@Dao
internal interface BackupMutationDao {
    @Query("SELECT id FROM vaults ORDER BY id")
    suspend fun vaultIds(): List<String>

    @Query("SELECT id FROM workspaces ORDER BY id")
    suspend fun workspaceIds(): List<String>

    @Query("SELECT id FROM members ORDER BY id")
    suspend fun memberIds(): List<String>

    @Query(
        """
        SELECT id, revisionWallMillis, revisionLogical, revisionDeviceId
        FROM projects ORDER BY id
        """,
    )
    suspend fun projectRevisions(): List<RevisionedIdRow>

    @Query(
        """
        SELECT id, revisionWallMillis, revisionLogical, revisionDeviceId
        FROM workflow_statuses ORDER BY id
        """,
    )
    suspend fun workflowStatusRevisions(): List<RevisionedIdRow>

    @Query(
        """
        SELECT id, revisionWallMillis, revisionLogical, revisionDeviceId
        FROM milestones ORDER BY id
        """,
    )
    suspend fun milestoneRevisions(): List<RevisionedIdRow>

    @Query(
        """
        SELECT id, revisionWallMillis, revisionLogical, revisionDeviceId
        FROM tasks ORDER BY id
        """,
    )
    suspend fun taskRevisions(): List<RevisionedIdRow>

    @Query("SELECT * FROM checklist_items ORDER BY id")
    suspend fun checklistItems(): List<ChecklistItemEntity>

    @Query(
        """
        SELECT taskId AS firstId, dependsOnTaskId AS secondId,
            revisionWallMillis, revisionLogical, revisionDeviceId
        FROM task_dependencies ORDER BY taskId, dependsOnTaskId
        """,
    )
    suspend fun taskDependencyRevisions(): List<CompositeRevisionRow>

    @Query("SELECT * FROM tags ORDER BY id")
    suspend fun tags(): List<TagEntity>

    @Query(
        """
        SELECT taskId AS firstId, tagId AS secondId,
            revisionWallMillis, revisionLogical, revisionDeviceId
        FROM task_tags ORDER BY taskId, tagId
        """,
    )
    suspend fun taskTagRevisions(): List<CompositeRevisionRow>

    @Query("SELECT * FROM reminders ORDER BY id")
    suspend fun reminders(): List<ReminderEntity>

    @Query(
        """
        SELECT id, revisionWallMillis, revisionLogical, revisionDeviceId
        FROM attachments ORDER BY id
        """,
    )
    suspend fun attachmentRevisions(): List<RevisionedIdRow>

    @Query("SELECT id FROM activity_entries ORDER BY id")
    suspend fun activityEntryIds(): List<String>

    @Query(
        """
        SELECT id, revisionWallMillis, revisionLogical, revisionDeviceId
        FROM notes ORDER BY id
        """,
    )
    suspend fun noteRevisions(): List<RevisionedIdRow>

    @Query(
        """
        SELECT blobSetId AS id, revisionWallMillis, revisionLogical, revisionDeviceId
        FROM retired_blob_sets ORDER BY blobSetId
        """,
    )
    suspend fun retiredBlobSetRevisions(): List<RevisionedIdRow>

    @Query("SELECT * FROM time_entries ORDER BY id")
    suspend fun timeEntries(): List<TimeEntryEntity>

    @Query(
        """
        SELECT id, revisionWallMillis, revisionLogical, revisionDeviceId
        FROM templates ORDER BY id
        """,
    )
    suspend fun templateRevisions(): List<RevisionedIdRow>

    @Query("SELECT * FROM saved_views ORDER BY id")
    suspend fun savedViews(): List<SavedViewEntity>

    @Query(
        """
        SELECT objectId AS firstId, objectType AS secondId,
            revisionWallMillis, revisionLogical, revisionDeviceId
        FROM tombstones ORDER BY objectId, objectType
        """,
    )
    suspend fun tombstoneRevisions(): List<CompositeRevisionRow>

    @Query("SELECT * FROM vaults WHERE id = :id LIMIT 1")
    suspend fun vault(id: String): VaultEntity?

    @Query("SELECT * FROM workspaces WHERE id = :id LIMIT 1")
    suspend fun workspace(id: String): WorkspaceEntity?

    @Query("SELECT * FROM members WHERE id = :id LIMIT 1")
    suspend fun member(id: String): MemberEntity?

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun project(id: String): ProjectEntity?

    @Query("SELECT * FROM workflow_statuses WHERE id = :id LIMIT 1")
    suspend fun workflowStatus(id: String): WorkflowStatusEntity?

    @Query("SELECT * FROM milestones WHERE id = :id LIMIT 1")
    suspend fun milestone(id: String): MilestoneEntity?

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun task(id: String): TaskEntity?

    @Query("SELECT * FROM checklist_items WHERE id = :id LIMIT 1")
    suspend fun checklistItem(id: String): ChecklistItemEntity?

    @Query(
        """
        SELECT * FROM task_dependencies
        WHERE taskId = :taskId AND dependsOnTaskId = :dependsOnTaskId
        LIMIT 1
        """,
    )
    suspend fun taskDependency(
        taskId: String,
        dependsOnTaskId: String,
    ): TaskDependencyEntity?

    @Query("SELECT * FROM tags WHERE id = :id LIMIT 1")
    suspend fun tag(id: String): TagEntity?

    @Query(
        """
        SELECT * FROM task_tags
        WHERE taskId = :taskId AND tagId = :tagId
        LIMIT 1
        """,
    )
    suspend fun taskTag(
        taskId: String,
        tagId: String,
    ): TaskTagEntity?

    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    suspend fun reminder(id: String): ReminderEntity?

    @Query("SELECT * FROM attachments WHERE id = :id LIMIT 1")
    suspend fun attachment(id: String): AttachmentEntity?

    @Query("SELECT * FROM activity_entries WHERE id = :id LIMIT 1")
    suspend fun activityEntry(id: String): ActivityEntryEntity?

    @Query("SELECT * FROM time_entries WHERE id = :id LIMIT 1")
    suspend fun timeEntry(id: String): TimeEntryEntity?

    @Query("SELECT * FROM templates WHERE id = :id LIMIT 1")
    suspend fun template(id: String): TemplateEntity?

    @Query("SELECT * FROM saved_views WHERE id = :id LIMIT 1")
    suspend fun savedView(id: String): SavedViewEntity?

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun note(id: String): NoteEntity?

    @Query("SELECT * FROM retired_blob_sets WHERE blobSetId = :blobSetId LIMIT 1")
    suspend fun retiredBlobSet(blobSetId: String): RetiredBlobSetEntity?

    @Query(
        """
        SELECT * FROM tombstones
        WHERE objectId = :objectId AND objectType = :objectType
        LIMIT 1
        """,
    )
    suspend fun tombstone(
        objectId: String,
        objectType: String,
    ): TombstoneEntity?

    @Query("SELECT * FROM automation_rules ORDER BY id")
    suspend fun automationRules(): List<AutomationRuleEntity>

    @Query("SELECT * FROM automation_rules WHERE id = :id LIMIT 1")
    suspend fun automationRule(id: String): AutomationRuleEntity?

    @Query("SELECT * FROM my_day_entries ORDER BY taskId")
    suspend fun myDayEntries(): List<MyDayEntryEntity>

    @Query("SELECT * FROM my_day_entries WHERE taskId = :taskId LIMIT 1")
    suspend fun myDayEntry(taskId: String): MyDayEntryEntity?
}

internal data class RevisionedIdRow(
    val id: String,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val revisionDeviceId: String,
)

internal data class CompositeRevisionRow(
    val firstId: String,
    val secondId: String,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val revisionDeviceId: String,
)

internal suspend fun BackupMutationDao.snapshots(): List<BackupRecordSnapshot> = buildList {
    vaultIds().mapTo(this) { identitySnapshot(BackupRecordFamily.VAULT, it) }
    workspaceIds().mapTo(this) { identitySnapshot(BackupRecordFamily.WORKSPACE, it) }
    memberIds().mapTo(this) { identitySnapshot(BackupRecordFamily.MEMBER, it) }
    projectRevisions().mapTo(this) { it.snapshot(BackupRecordFamily.PROJECT) }
    workflowStatusRevisions().mapTo(this) {
        it.snapshot(BackupRecordFamily.WORKFLOW_STATUS)
    }
    milestoneRevisions().mapTo(this) { it.snapshot(BackupRecordFamily.MILESTONE) }
    taskRevisions().mapTo(this) { it.snapshot(BackupRecordFamily.TASK) }
    checklistItems().mapTo(this) { it.toBackupRecordV1().contentSnapshot() }
    taskDependencyRevisions().mapTo(this) {
        it.snapshot(BackupRecordFamily.TASK_DEPENDENCY)
    }
    tags().mapTo(this) { it.toBackupRecordV1().contentSnapshot() }
    taskTagRevisions().mapTo(this) { it.snapshot(BackupRecordFamily.TASK_TAG) }
    reminders().mapTo(this) { it.toBackupRecordV1().contentSnapshot() }
    attachmentRevisions().mapTo(this) { it.snapshot(BackupRecordFamily.ATTACHMENT) }
    activityEntryIds().mapTo(this) {
        identitySnapshot(BackupRecordFamily.ACTIVITY_ENTRY, it)
    }
    timeEntries().mapTo(this) { it.toBackupRecordV1().contentSnapshot() }
    templateRevisions().mapTo(this) { it.snapshot(BackupRecordFamily.TEMPLATE) }
    // Content-based like the other unrevisioned families: identity-only
    // would never journal a saved-view rename or query update.
    savedViews().mapTo(this) { it.toBackupRecordV1().contentSnapshot() }
    noteRevisions().mapTo(this) { it.snapshot(BackupRecordFamily.NOTE) }
    retiredBlobSetRevisions().mapTo(this) {
        it.snapshot(BackupRecordFamily.RETIRED_BLOB_SET)
    }
    tombstoneRevisions().mapTo(this) { it.snapshot(BackupRecordFamily.TOMBSTONE) }
    automationRules().mapTo(this) { it.toBackupRecordV1().contentSnapshot() }
    myDayEntries().mapTo(this) { it.toBackupRecordV1().contentSnapshot() }
}

internal suspend fun BackupMutationDao.requireRecord(
    family: BackupRecordFamily,
    identity: List<String>,
): BackupRecordV1 {
    fun singleId(): String = identity.single()
    fun compositeIds(): Pair<String, String> {
        require(identity.size == 2) { "$family requires a composite identity" }
        return identity[0] to identity[1]
    }
    return when (family) {
        BackupRecordFamily.VAULT ->
            requireNotNull(vault(singleId())).toBackupRecordV1()
        BackupRecordFamily.WORKSPACE ->
            requireNotNull(workspace(singleId())).toBackupRecordV1()
        BackupRecordFamily.MEMBER ->
            requireNotNull(member(singleId())).toBackupRecordV1()
        BackupRecordFamily.PROJECT ->
            requireNotNull(project(singleId())).toBackupRecordV1()
        BackupRecordFamily.WORKFLOW_STATUS ->
            requireNotNull(workflowStatus(singleId())).toBackupRecordV1()
        BackupRecordFamily.MILESTONE ->
            requireNotNull(milestone(singleId())).toBackupRecordV1()
        BackupRecordFamily.TASK ->
            requireNotNull(task(singleId())).toBackupRecordV1()
        BackupRecordFamily.CHECKLIST_ITEM ->
            requireNotNull(checklistItem(singleId())).toBackupRecordV1()
        BackupRecordFamily.TASK_DEPENDENCY -> compositeIds().let { (taskId, dependsOn) ->
            requireNotNull(taskDependency(taskId, dependsOn)).toBackupRecordV1()
        }
        BackupRecordFamily.TAG ->
            requireNotNull(tag(singleId())).toBackupRecordV1()
        BackupRecordFamily.TASK_TAG -> compositeIds().let { (taskId, tagId) ->
            requireNotNull(taskTag(taskId, tagId)).toBackupRecordV1()
        }
        BackupRecordFamily.REMINDER ->
            requireNotNull(reminder(singleId())).toBackupRecordV1()
        BackupRecordFamily.ATTACHMENT ->
            requireNotNull(attachment(singleId())).toBackupRecordV1()
        BackupRecordFamily.ACTIVITY_ENTRY ->
            requireNotNull(activityEntry(singleId())).toBackupRecordV1()
        BackupRecordFamily.TIME_ENTRY ->
            requireNotNull(timeEntry(singleId())).toBackupRecordV1()
        BackupRecordFamily.TEMPLATE ->
            requireNotNull(template(singleId())).toBackupRecordV1()
        BackupRecordFamily.SAVED_VIEW ->
            requireNotNull(savedView(singleId())).toBackupRecordV1()
        BackupRecordFamily.NOTE ->
            requireNotNull(note(singleId())).toBackupRecordV1()
        BackupRecordFamily.RETIRED_BLOB_SET ->
            requireNotNull(retiredBlobSet(singleId())).toBackupRecordV1()
        BackupRecordFamily.TOMBSTONE -> compositeIds().let { (objectId, objectType) ->
            requireNotNull(tombstone(objectId, objectType)).toBackupRecordV1()
        }
        BackupRecordFamily.AUTOMATION_RULE ->
            requireNotNull(automationRule(singleId())).toBackupRecordV1()
        BackupRecordFamily.MY_DAY ->
            requireNotNull(myDayEntry(singleId())).toBackupRecordV1()
    }
}

private fun identitySnapshot(
    family: BackupRecordFamily,
    id: String,
): BackupRecordSnapshot = BackupRecordSnapshot(
    family = family,
    identity = listOf(id),
    fingerprint = Unit,
    revision = null,
)

private fun RevisionedIdRow.snapshot(
    family: BackupRecordFamily,
): BackupRecordSnapshot = BackupRecordSnapshot(
    family = family,
    identity = listOf(id),
    fingerprint = this,
    revision = RecordRevision(revisionWallMillis, revisionLogical, revisionDeviceId),
)

private fun CompositeRevisionRow.snapshot(
    family: BackupRecordFamily,
): BackupRecordSnapshot = BackupRecordSnapshot(
    family = family,
    identity = listOf(firstId, secondId),
    fingerprint = this,
    revision = RecordRevision(revisionWallMillis, revisionLogical, revisionDeviceId),
)

private fun BackupRecordV1.contentSnapshot(): BackupRecordSnapshot = BackupRecordSnapshot(
    family = family,
    identity = identity,
    fingerprint = this,
    revision = null,
)
