package app.opentasks.core.data.backup

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
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.VaultId
import app.opentasks.core.sync.CloudBounds
import java.util.Base64

/**
 * One authenticated recovery, ready to be rebuilt into a staging database.
 *
 * The snapshot and the ordered segments have already been decoded through the
 * strict Stage 2 codecs, so this request carries no provider, account, or
 * transport state: a recovered vault is reconstructed from records alone.
 */
internal data class RecoveryImportRequest(
    val snapshot: BackupSnapshotPayloadV1,
    val segments: List<BackupOperationSegmentPayloadV1>,
    val recoveryEnvelope: VaultKeyEnvelope,
    val expectedGeneration: BackupGeneration,
)

internal interface BackupRecordImporter {
    suspend fun importInto(
        database: VaultDatabase,
        request: RecoveryImportRequest,
    )
}

/**
 * The record set a faithful import must leave behind.
 *
 * This replays the request in memory, independently of any SQL, so the
 * staged-vault verifier compares two computations of the same recovery rather
 * than reading back whatever the importer happened to write.
 */
internal fun RecoveryImportRequest.expectedCapture(): StructuredBackupCapture {
    val plan = RecoveryImportPlan.of(this)
    return StructuredBackupCapture(
        vaultId = plan.vaultId,
        generation = plan.generation,
        records = plan.finalRecords,
    )
}

/**
 * Rebuilds one vault inside a new inactive staging database.
 *
 * Snapshot records insert with `ABORT` and segment operations replay in
 * generation and sequence order inside a single transaction, so a rejected
 * recovery leaves the staging database exactly as empty as it was found.
 */
internal class RoomBackupRecordImporter(
    private val database: VaultDatabase,
    private val importDao: RecoveryImportDao,
) : BackupRecordImporter {
    override suspend fun importInto(
        database: VaultDatabase,
        request: RecoveryImportRequest,
    ) {
        require(database === this.database) {
            "The importer is bound to another staging database"
        }
        val plan = RecoveryImportPlan.of(request)
        database.withTransaction {
            check(importDao.structuredRecordCount() == 0) {
                "The staging database already holds records"
            }
            check(importDao.operationalRecordCount() == 0) {
                "The staging database already holds operational rows"
            }
            check(importDao.localBackupStateCount() == 0) {
                "The staging database already holds local backup state"
            }

            val live = plan.snapshotRecords.mapTo(mutableSetOf()) { BackupRecordKey(it) }
            plan.snapshotRecords.forEach { record -> write(record, replace = false) }
            plan.operations.forEach { operation ->
                when (operation) {
                    is RecoveryImportOperation.Upsert -> {
                        write(operation.record, replace = true)
                        live += operation.key
                    }
                    is RecoveryImportOperation.Delete -> {
                        val expected = if (live.remove(operation.key)) 1 else 0
                        check(delete(operation.key) == expected) {
                            "A ${operation.key.family} deletion did not match its identity"
                        }
                    }
                }
            }
            initializeFreshLocalState(plan, request.recoveryEnvelope)
        }
    }

    /**
     * Leaves the journal, the legacy outbox, and every remote table empty.
     *
     * A recovered vault inherits no verified base, no published segment, and no
     * portable package, so the next remote publication must start from a fresh
     * complete Stage 2 baseline rather than a checkpoint another device owns.
     */
    private suspend fun initializeFreshLocalState(
        plan: RecoveryImportPlan,
        recoveryEnvelope: VaultKeyEnvelope,
    ) {
        database.backupStateDao().insert(
            defaultBackupState(plan.vaultId.value).copy(
                currentGeneration = plan.generation.value,
                recoveryEnvelopeReady = true,
            ),
        )
        RoomRecoveryEnvelopeStore(database).upsert(plan.vaultId, recoveryEnvelope)
    }

    private suspend fun write(
        record: BackupRecordV1,
        replace: Boolean,
    ) {
        val fields = BackupRecordFields.of(record)
        when (record.family) {
            BackupRecordFamily.VAULT -> fields.toVaultEntity().let { entity ->
                if (replace) importDao.upsertVault(entity) else importDao.insertVault(entity)
            }
            BackupRecordFamily.WORKSPACE -> fields.toWorkspaceEntity().let { entity ->
                if (replace) importDao.upsertWorkspace(entity) else importDao.insertWorkspace(entity)
            }
            BackupRecordFamily.MEMBER -> fields.toMemberEntity().let { entity ->
                if (replace) importDao.upsertMember(entity) else importDao.insertMember(entity)
            }
            BackupRecordFamily.PROJECT -> fields.toProjectEntity().let { entity ->
                if (replace) importDao.upsertProject(entity) else importDao.insertProject(entity)
            }
            BackupRecordFamily.WORKFLOW_STATUS -> fields.toWorkflowStatusEntity().let { entity ->
                if (replace) {
                    importDao.upsertWorkflowStatus(entity)
                } else {
                    importDao.insertWorkflowStatus(entity)
                }
            }
            BackupRecordFamily.MILESTONE -> fields.toMilestoneEntity().let { entity ->
                if (replace) importDao.upsertMilestone(entity) else importDao.insertMilestone(entity)
            }
            BackupRecordFamily.TASK -> {
                val entity = fields.toTaskEntity()
                try {
                    if (replace) importDao.upsertTask(entity) else importDao.insertTask(entity)
                } finally {
                    entity.descriptionCiphertext.fill(0)
                }
            }
            BackupRecordFamily.CHECKLIST_ITEM -> fields.toChecklistItemEntity().let { entity ->
                if (replace) {
                    importDao.upsertChecklistItem(entity)
                } else {
                    importDao.insertChecklistItem(entity)
                }
            }
            BackupRecordFamily.TASK_DEPENDENCY -> fields.toTaskDependencyEntity().let { entity ->
                if (replace) {
                    importDao.upsertTaskDependency(entity)
                } else {
                    importDao.insertTaskDependency(entity)
                }
            }
            BackupRecordFamily.TAG -> fields.toTagEntity().let { entity ->
                if (replace) importDao.upsertTag(entity) else importDao.insertTag(entity)
            }
            BackupRecordFamily.TASK_TAG -> fields.toTaskTagEntity().let { entity ->
                if (replace) importDao.upsertTaskTag(entity) else importDao.insertTaskTag(entity)
            }
            BackupRecordFamily.REMINDER -> fields.toReminderEntity().let { entity ->
                if (replace) importDao.upsertReminder(entity) else importDao.insertReminder(entity)
            }
            BackupRecordFamily.ATTACHMENT -> {
                val entity = fields.toAttachmentEntity()
                try {
                    if (replace) {
                        importDao.upsertAttachment(entity)
                    } else {
                        importDao.insertAttachment(entity)
                    }
                } finally {
                    entity.displayNameCiphertext.fill(0)
                }
            }
            BackupRecordFamily.ACTIVITY_ENTRY -> {
                val entity = fields.toActivityEntryEntity()
                try {
                    if (replace) {
                        importDao.upsertActivityEntry(entity)
                    } else {
                        importDao.insertActivityEntry(entity)
                    }
                } finally {
                    entity.bodyCiphertext.fill(0)
                }
            }
            BackupRecordFamily.TIME_ENTRY -> {
                val entity = fields.toTimeEntryEntity()
                try {
                    if (replace) {
                        importDao.upsertTimeEntry(entity)
                    } else {
                        importDao.insertTimeEntry(entity)
                    }
                } finally {
                    entity.noteCiphertext.fill(0)
                }
            }
            BackupRecordFamily.TEMPLATE -> {
                val entity = fields.toTemplateEntity()
                try {
                    if (replace) {
                        importDao.upsertTemplate(entity)
                    } else {
                        importDao.insertTemplate(entity)
                    }
                } finally {
                    entity.encryptedPayload.fill(0)
                }
            }
            BackupRecordFamily.SAVED_VIEW -> {
                val entity = fields.toSavedViewEntity()
                try {
                    if (replace) {
                        importDao.upsertSavedView(entity)
                    } else {
                        importDao.insertSavedView(entity)
                    }
                } finally {
                    entity.encryptedQuery.fill(0)
                }
            }
            BackupRecordFamily.TOMBSTONE -> fields.toTombstoneEntity().let { entity ->
                if (replace) importDao.upsertTombstone(entity) else importDao.insertTombstone(entity)
            }
        }
    }

    private suspend fun delete(key: BackupRecordKey): Int = when (key.family) {
        BackupRecordFamily.VAULT -> importDao.deleteVault(key.single())
        BackupRecordFamily.WORKSPACE -> importDao.deleteWorkspace(key.single())
        BackupRecordFamily.MEMBER -> importDao.deleteMember(key.single())
        BackupRecordFamily.PROJECT -> importDao.deleteProject(key.single())
        BackupRecordFamily.WORKFLOW_STATUS -> importDao.deleteWorkflowStatus(key.single())
        BackupRecordFamily.MILESTONE -> importDao.deleteMilestone(key.single())
        BackupRecordFamily.TASK -> importDao.deleteTask(key.single())
        BackupRecordFamily.CHECKLIST_ITEM -> importDao.deleteChecklistItem(key.single())
        BackupRecordFamily.TASK_DEPENDENCY ->
            importDao.deleteTaskDependency(key.first(), key.second())
        BackupRecordFamily.TAG -> importDao.deleteTag(key.single())
        BackupRecordFamily.TASK_TAG -> importDao.deleteTaskTag(key.first(), key.second())
        BackupRecordFamily.REMINDER -> importDao.deleteReminder(key.single())
        BackupRecordFamily.ATTACHMENT -> importDao.deleteAttachment(key.single())
        BackupRecordFamily.ACTIVITY_ENTRY -> importDao.deleteActivityEntry(key.single())
        BackupRecordFamily.TIME_ENTRY -> importDao.deleteTimeEntry(key.single())
        BackupRecordFamily.TEMPLATE -> importDao.deleteTemplate(key.single())
        BackupRecordFamily.SAVED_VIEW -> importDao.deleteSavedView(key.single())
        BackupRecordFamily.TOMBSTONE -> importDao.deleteTombstone(key.first(), key.second())
    }
}

/** A validated recovery, reduced to the exact writes it authorises. */
internal class RecoveryImportPlan private constructor(
    val vaultId: VaultId,
    val generation: BackupGeneration,
    val snapshotRecords: List<BackupRecordV1>,
    val operations: List<RecoveryImportOperation>,
) {
    /** The canonical record set a faithful import must leave behind. */
    val finalRecords: List<BackupRecordV1> = buildFinalRecords()

    private fun buildFinalRecords(): List<BackupRecordV1> {
        val live = LinkedHashMap<BackupRecordKey, BackupRecordV1>()
        snapshotRecords.forEach { record -> live[BackupRecordKey(record)] = record }
        operations.forEach { operation ->
            when (operation) {
                is RecoveryImportOperation.Upsert -> live[operation.key] = operation.record
                is RecoveryImportOperation.Delete -> live.remove(operation.key)
            }
        }
        return live.entries.sortedBy { it.key }.map { it.value }
    }

    companion object {
        fun of(request: RecoveryImportRequest): RecoveryImportPlan {
            val snapshot = request.snapshot
            require(snapshot.formatVersion == PAYLOAD_FORMAT_VERSION) {
                "Unsupported backup snapshot format"
            }
            require(snapshot.minimumReaderVersion <= SUPPORTED_READER_VERSION) {
                "The backup snapshot requires a newer reader"
            }
            require(snapshot.coveredGeneration >= 0) {
                "Covered generation cannot be negative"
            }
            require(request.expectedGeneration.value >= snapshot.coveredGeneration) {
                "The recovered generation is behind its snapshot"
            }
            require(snapshot.records.size <= CloudBounds.MAX_RECORDS_PER_SNAPSHOT) {
                "Backup snapshot exceeds ${CloudBounds.MAX_RECORDS_PER_SNAPSHOT} records"
            }

            val identities = mutableSetOf<BackupRecordKey>()
            val snapshotRecords = snapshot.records.map { record ->
                BackupMutationCodec.validateRecord(record)
                require(identities.add(BackupRecordKey(record))) {
                    "Duplicate ${record.family} identity in the backup snapshot"
                }
                normalizeForRecovery(record)
            }

            var previousGeneration = snapshot.coveredGeneration
            val operationIds = mutableSetOf<String>()
            val operations = buildList {
                request.segments.forEach { segment ->
                    // Re-proves canonical ordering, bounds, and that every
                    // entry identity agrees with the mutation it carries.
                    BackupOperationSegmentCodec.encode(segment).fill(0)
                    require(segment.vaultId == snapshot.vaultId) {
                        "A backup segment belongs to another vault"
                    }
                    require(segment.firstGeneration == Math.addExact(previousGeneration, 1L)) {
                        "The backup segment inventory is not contiguous"
                    }
                    previousGeneration = segment.lastGeneration
                    segment.entries.forEach { entry ->
                        require(operationIds.add(entry.operationId)) {
                            "Duplicate backup operation across segments"
                        }
                        add(entry.toOperation())
                    }
                }
            }
            require(previousGeneration == request.expectedGeneration.value) {
                "The backup segment inventory does not reach the recovered generation"
            }

            return RecoveryImportPlan(
                vaultId = VaultId(snapshot.vaultId),
                generation = request.expectedGeneration,
                snapshotRecords = snapshotRecords,
                operations = operations,
            ).also { plan ->
                // Checked on the replayed result, so a segment can neither add a
                // second vault nor delete the only one it was meant to recover.
                requireSingleVaultOwnership(plan.finalRecords, snapshot.vaultId)
            }
        }

        private fun requireSingleVaultOwnership(
            records: List<BackupRecordV1>,
            vaultId: String,
        ) {
            val vaults = records.filter { it.family == BackupRecordFamily.VAULT }
            require(vaults.size == 1 && vaults.single().identity == listOf(vaultId)) {
                "The recovered records do not describe exactly one vault"
            }
            val workspaces = records.filter { it.family == BackupRecordFamily.WORKSPACE }
            // A recovered vault holding no workspace would be seeded with the
            // shipped fixture the first time a repository opened it.
            require(workspaces.isNotEmpty()) { "The recovered records hold no workspace" }
            workspaces.forEach { workspace ->
                require(BackupRecordFields.of(workspace).string("vaultId") == vaultId) {
                    "A recovered workspace belongs to another vault"
                }
            }
        }

        private fun BackupSegmentEntryV1.toOperation(): RecoveryImportOperation {
            val mutation = BackupMutationCodec.decodeOwned(
                Base64.getDecoder().decode(payloadBase64),
            )
            return when (mutation.mutationKind) {
                BackupMutationKind.UPSERT -> RecoveryImportOperation.Upsert(
                    normalizeForRecovery(requireNotNull(mutation.record)),
                )
                BackupMutationKind.DELETE -> RecoveryImportOperation.Delete(
                    BackupRecordKey(
                        family = requireNotNull(mutation.deletedFamily),
                        identity = requireNotNull(mutation.deletedIdentity),
                    ),
                )
                BackupMutationKind.LEGACY ->
                    throw IllegalArgumentException("Legacy mutations cannot be recovered")
            }
        }

        /**
         * Rejects a vault this reader cannot represent and marks every readable
         * one as the current logical schema, so a vault captured at v6 is
         * recovered as the v7 database it now lives in.
         */
        private fun normalizeForRecovery(record: BackupRecordV1): BackupRecordV1 {
            if (record.family != BackupRecordFamily.VAULT) return record
            val fields = BackupRecordFields.of(record)
            val schemaVersion = fields.int("schemaVersion")
            require(schemaVersion in 1..RECOVERED_SCHEMA_VERSION) {
                "The backup vault schema is not readable"
            }
            require(fields.int("minimumReaderVersion") <= SUPPORTED_READER_VERSION) {
                "The backup vault requires a newer reader"
            }
            if (schemaVersion == RECOVERED_SCHEMA_VERSION) return record
            return record.copy(
                fields = record.fields.map { field ->
                    if (field.name == SCHEMA_VERSION_FIELD) {
                        field.copy(value = RECOVERED_SCHEMA_VERSION.toString())
                    } else {
                        field
                    }
                },
            )
        }
    }
}

internal sealed interface RecoveryImportOperation {
    val key: BackupRecordKey

    data class Upsert(val record: BackupRecordV1) : RecoveryImportOperation {
        override val key: BackupRecordKey get() = BackupRecordKey(record)
    }

    data class Delete(override val key: BackupRecordKey) : RecoveryImportOperation
}

/**
 * Strict typed access to one validated record.
 *
 * Construction validates the record against its family schema first, so every
 * accessor below reads a field that is present, correctly typed, and canonical.
 */
internal class BackupRecordFields private constructor(
    private val family: BackupRecordFamily,
    private val fields: Map<String, BackupFieldV1>,
) {
    fun string(name: String): String = required(name, BackupFieldType.STRING)

    fun nullableString(name: String): String? = optional(name, BackupFieldType.STRING)

    fun long(name: String): Long = required(name, BackupFieldType.LONG).toLong()

    fun nullableLong(name: String): Long? = optional(name, BackupFieldType.LONG)?.toLong()

    fun int(name: String): Int = required(name, BackupFieldType.INT).toInt()

    fun nullableInt(name: String): Int? = optional(name, BackupFieldType.INT)?.toInt()

    fun boolean(name: String): Boolean =
        required(name, BackupFieldType.BOOLEAN).toBooleanStrict()

    /** Returns a fresh array the caller owns and clears once Room has bound it. */
    fun bytes(name: String): ByteArray =
        Base64.getDecoder().decode(required(name, BackupFieldType.BYTES))

    private fun field(name: String): BackupFieldV1 =
        requireNotNull(fields[name]) { "$family is missing a field" }

    private fun required(name: String, type: BackupFieldType): String {
        val field = field(name)
        require(field.type == type) { "$family holds a field of the wrong type" }
        return requireNotNull(field.value) { "$family holds a field without a value" }
    }

    private fun optional(name: String, type: BackupFieldType): String? {
        val field = field(name)
        if (field.type == BackupFieldType.NULL) {
            require(field.value == null) { "$family holds a null field with a value" }
            return null
        }
        return required(name, type)
    }

    companion object {
        fun of(record: BackupRecordV1): BackupRecordFields {
            BackupMutationCodec.validateRecord(record)
            return BackupRecordFields(
                family = record.family,
                fields = record.fields.associateBy(BackupFieldV1::name),
            )
        }
    }
}

internal fun BackupRecordFields.toVaultEntity(): VaultEntity = VaultEntity(
    id = string("id"),
    storageMode = LOCAL_STORAGE_MODE,
    createdAtEpochMillis = long("createdAtEpochMillis"),
    schemaVersion = int(SCHEMA_VERSION_FIELD),
    cryptoVersion = int("cryptoVersion"),
    minimumReaderVersion = int("minimumReaderVersion"),
)

internal fun BackupRecordFields.toWorkspaceEntity(): WorkspaceEntity = WorkspaceEntity(
    id = string("id"),
    vaultId = string("vaultId"),
    ownerId = string("ownerId"),
    name = string("name"),
)

internal fun BackupRecordFields.toMemberEntity(): MemberEntity = MemberEntity(
    id = string("id"),
    displayName = string("displayName"),
)

internal fun BackupRecordFields.toProjectEntity(): ProjectEntity = ProjectEntity(
    id = string("id"),
    workspaceId = string("workspaceId"),
    name = string("name"),
    summary = string("summary"),
    health = string("health"),
    dueDate = nullableString("dueDate"),
    completedTasks = int("completedTasks"),
    totalTasks = int("totalTasks"),
    archivedAtEpochMillis = nullableLong("archivedAtEpochMillis"),
    revisionWallMillis = long("revisionWallMillis"),
    revisionLogical = int("revisionLogical"),
    revisionDeviceId = string("revisionDeviceId"),
)

internal fun BackupRecordFields.toWorkflowStatusEntity(): WorkflowStatusEntity =
    WorkflowStatusEntity(
        id = string("id"),
        projectId = nullableString("projectId"),
        name = string("name"),
        semanticStatus = string("semanticStatus"),
        rank = string("rank"),
        archivedAtEpochMillis = nullableLong("archivedAtEpochMillis"),
        revisionWallMillis = long("revisionWallMillis"),
        revisionLogical = int("revisionLogical"),
        revisionDeviceId = string("revisionDeviceId"),
    )

internal fun BackupRecordFields.toMilestoneEntity(): MilestoneEntity = MilestoneEntity(
    id = string("id"),
    projectId = string("projectId"),
    name = string("name"),
    dueDate = nullableString("dueDate"),
    completedAtEpochMillis = nullableLong("completedAtEpochMillis"),
    revisionWallMillis = long("revisionWallMillis"),
    revisionLogical = int("revisionLogical"),
    revisionDeviceId = string("revisionDeviceId"),
)

internal fun BackupRecordFields.toTaskEntity(): TaskEntity = TaskEntity(
    id = string("id"),
    workspaceId = string("workspaceId"),
    projectId = nullableString("projectId"),
    parentTaskId = nullableString("parentTaskId"),
    statusId = string("statusId"),
    semanticStatus = string("semanticStatus"),
    title = string("title"),
    descriptionCiphertext = bytes("descriptionCiphertext"),
    priority = string("priority"),
    startEpochMillis = nullableLong("startEpochMillis"),
    startZoneId = nullableString("startZoneId"),
    dueEpochMillis = nullableLong("dueEpochMillis"),
    dueZoneId = nullableString("dueZoneId"),
    recurrenceFrequency = nullableString("recurrenceFrequency"),
    recurrenceInterval = nullableInt("recurrenceInterval"),
    recurrenceWeekdays = nullableString("recurrenceWeekdays"),
    recurrenceCount = nullableInt("recurrenceCount"),
    recurrenceEndDate = nullableString("recurrenceEndDate"),
    recurrenceSeriesId = nullableString("recurrenceSeriesId"),
    recurrenceAnchorEpochMillis = nullableLong("recurrenceAnchorEpochMillis"),
    recurrenceAnchorZoneId = nullableString("recurrenceAnchorZoneId"),
    recurrenceOccurrenceIndex = nullableInt("recurrenceOccurrenceIndex"),
    estimateSeconds = nullableLong("estimateSeconds"),
    milestoneId = nullableString("milestoneId"),
    completedAtEpochMillis = nullableLong("completedAtEpochMillis"),
    deletedAtEpochMillis = nullableLong("deletedAtEpochMillis"),
    revisionWallMillis = long("revisionWallMillis"),
    revisionLogical = int("revisionLogical"),
    revisionDeviceId = string("revisionDeviceId"),
)

internal fun BackupRecordFields.toChecklistItemEntity(): ChecklistItemEntity = ChecklistItemEntity(
    id = string("id"),
    taskId = string("taskId"),
    text = string("text"),
    completed = boolean("completed"),
    rank = string("rank"),
)

internal fun BackupRecordFields.toTaskDependencyEntity(): TaskDependencyEntity =
    TaskDependencyEntity(
        taskId = string("taskId"),
        dependsOnTaskId = string("dependsOnTaskId"),
        revisionWallMillis = long("revisionWallMillis"),
        revisionLogical = int("revisionLogical"),
        revisionDeviceId = string("revisionDeviceId"),
    )

internal fun BackupRecordFields.toTagEntity(): TagEntity = TagEntity(
    id = string("id"),
    workspaceId = string("workspaceId"),
    name = string("name"),
)

internal fun BackupRecordFields.toTaskTagEntity(): TaskTagEntity = TaskTagEntity(
    taskId = string("taskId"),
    tagId = string("tagId"),
    present = boolean("present"),
    revisionWallMillis = long("revisionWallMillis"),
    revisionLogical = int("revisionLogical"),
    revisionDeviceId = string("revisionDeviceId"),
)

internal fun BackupRecordFields.toReminderEntity(): ReminderEntity = ReminderEntity(
    id = string("id"),
    taskId = string("taskId"),
    triggerAtEpochMillis = long("triggerAtEpochMillis"),
    zoneId = string("zoneId"),
    precise = boolean("precise"),
)

internal fun BackupRecordFields.toAttachmentEntity(): AttachmentEntity = AttachmentEntity(
    id = string("id"),
    taskId = string("taskId"),
    displayNameCiphertext = bytes("displayNameCiphertext"),
    mimeType = string("mimeType"),
    byteCount = long("byteCount"),
    contentHash = string("contentHash"),
    blobSetId = nullableString("blobSetId"),
    chunkCount = int("chunkCount"),
    deletedAtEpochMillis = nullableLong("deletedAtEpochMillis"),
    revisionWallMillis = long("revisionWallMillis"),
    revisionLogical = int("revisionLogical"),
    revisionDeviceId = string("revisionDeviceId"),
)

internal fun BackupRecordFields.toActivityEntryEntity(): ActivityEntryEntity = ActivityEntryEntity(
    id = string("id"),
    taskId = nullableString("taskId"),
    projectId = nullableString("projectId"),
    kind = string("kind"),
    bodyCiphertext = bytes("bodyCiphertext"),
    createdAtEpochMillis = long("createdAtEpochMillis"),
)

internal fun BackupRecordFields.toTimeEntryEntity(): TimeEntryEntity = TimeEntryEntity(
    id = string("id"),
    taskId = string("taskId"),
    deviceId = string("deviceId"),
    startedAtEpochMillis = long("startedAtEpochMillis"),
    stoppedAtEpochMillis = nullableLong("stoppedAtEpochMillis"),
    noteCiphertext = bytes("noteCiphertext"),
)

internal fun BackupRecordFields.toTemplateEntity(): TemplateEntity = TemplateEntity(
    id = string("id"),
    workspaceId = string("workspaceId"),
    name = string("name"),
    encryptedPayload = bytes("encryptedPayload"),
    revisionWallMillis = long("revisionWallMillis"),
    revisionLogical = int("revisionLogical"),
    revisionDeviceId = string("revisionDeviceId"),
)

internal fun BackupRecordFields.toSavedViewEntity(): SavedViewEntity = SavedViewEntity(
    id = string("id"),
    workspaceId = string("workspaceId"),
    name = string("name"),
    encryptedQuery = bytes("encryptedQuery"),
)

internal fun BackupRecordFields.toTombstoneEntity(): TombstoneEntity = TombstoneEntity(
    objectId = string("objectId"),
    objectType = string("objectType"),
    deletedAtEpochMillis = long("deletedAtEpochMillis"),
    purgeAfterEpochMillis = long("purgeAfterEpochMillis"),
    revisionWallMillis = long("revisionWallMillis"),
    revisionLogical = int("revisionLogical"),
    revisionDeviceId = string("revisionDeviceId"),
)

private fun BackupRecordKey.single(): String {
    require(identity.size == 1) { "$family does not hold a single identity" }
    return identity.single()
}

private fun BackupRecordKey.first(): String {
    require(identity.size == 2) { "$family does not hold a composite identity" }
    return identity[0]
}

private fun BackupRecordKey.second(): String {
    require(identity.size == 2) { "$family does not hold a composite identity" }
    return identity[1]
}

/** The logical schema marker every recovered vault is normalized to. */
internal const val RECOVERED_SCHEMA_VERSION = 7
internal const val NOT_PREPARED_PACKAGE_STATE = "NOT_PREPARED"
private const val SUPPORTED_READER_VERSION = 1
private const val PAYLOAD_FORMAT_VERSION = 1
private const val LOCAL_STORAGE_MODE = "LOCAL"
private const val SCHEMA_VERSION_FIELD = "schemaVersion"
