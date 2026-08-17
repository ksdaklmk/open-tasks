package app.opentasks.core.data.backup

import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.VaultId
import app.opentasks.core.sync.CloudBounds
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

@Serializable
data class BackupSnapshotPayloadV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val vaultId: String,
    val coveredGeneration: Long,
    val records: List<BackupRecordV1>,
)

@Serializable
data class BackupSegmentEntryV1(
    val operationId: String,
    val generation: Long,
    val sequence: Int,
    val objectId: String,
    val objectType: String,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val sourceDeviceId: String,
    val payloadBase64: String,
)

@Serializable
data class BackupOperationSegmentPayloadV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val vaultId: String,
    val firstGeneration: Long,
    val lastGeneration: Long,
    val entries: List<BackupSegmentEntryV1>,
    val entryCount: Int,
)

object BackupPayloadIdentities {
    fun segmentObjectId(
        firstGeneration: BackupGeneration,
        lastGeneration: BackupGeneration,
    ): String {
        require(firstGeneration.value <= lastGeneration.value) {
            "Segment generation range is reversed"
        }
        return "segment:${firstGeneration.value}:${lastGeneration.value}"
    }
}

interface BackupSnapshotCodec {
    fun fromCapture(capture: StructuredBackupCapture): BackupSnapshotPayloadV1
    fun encode(payload: BackupSnapshotPayloadV1): ByteArray
    fun encodeBounded(
        payload: BackupSnapshotPayloadV1,
        maximumBytes: Int,
    ): ByteArray = encode(payload).also { encoded ->
        if (encoded.size > maximumBytes) {
            encoded.fill(0)
            throw BackupPayloadTooLargeException("snapshot", maximumBytes)
        }
    }
    fun decode(source: ByteArray): BackupSnapshotPayloadV1
    fun decodeOwned(source: ByteArray): BackupSnapshotPayloadV1

    companion object : BackupSnapshotCodec {
        const val MAX_PLAINTEXT_BYTES: Int = 64 * 1024 * 1024 - 33

        override fun fromCapture(capture: StructuredBackupCapture): BackupSnapshotPayloadV1 =
            BackupSnapshotPayloadV1(
                vaultId = capture.vaultId.value,
                coveredGeneration = capture.generation.value,
                records = capture.records.toList(),
            )

        override fun encode(payload: BackupSnapshotPayloadV1): ByteArray =
            encodeBounded(payload, MAX_PLAINTEXT_BYTES)

        override fun encodeBounded(
            payload: BackupSnapshotPayloadV1,
            maximumBytes: Int,
        ): ByteArray {
            require(maximumBytes in 1..MAX_PLAINTEXT_BYTES) {
                "Backup snapshot byte bound is invalid"
            }
            require(payload.records.size <= CloudBounds.MAX_RECORDS_PER_SNAPSHOT) {
                "Backup snapshot exceeds ${CloudBounds.MAX_RECORDS_PER_SNAPSHOT} records"
            }
            val canonical = payload.copy(records = payload.records.sortedWith(recordComparator))
            validateSnapshot(canonical)
            return StrictBackupPayloadJson.encode(
                serializer = BackupSnapshotPayloadV1.serializer(),
                value = canonical,
                maximumBytes = maximumBytes,
                label = "snapshot",
            )
        }

        override fun decode(source: ByteArray): BackupSnapshotPayloadV1 =
            decodeCallerPreserving(source) { it.copyOf() }

        internal fun decodeCallerPreserving(
            source: ByteArray,
            ownershipCopy: (ByteArray) -> ByteArray,
        ): BackupSnapshotPayloadV1 {
            require(source.size <= MAX_PLAINTEXT_BYTES) {
                "Backup snapshot exceeds $MAX_PLAINTEXT_BYTES bytes"
            }
            return decodeOwned(ownershipCopy(source))
        }

        override fun decodeOwned(source: ByteArray): BackupSnapshotPayloadV1 {
            try {
                require(source.size <= MAX_PLAINTEXT_BYTES) {
                    "Backup snapshot exceeds $MAX_PLAINTEXT_BYTES bytes"
                }
                require(source.isNotEmpty()) { "Backup snapshot is empty" }
                val decoded = StrictBackupPayloadJson.decodeSnapshot(source)
                validateSnapshot(decoded)
                val canonical = encode(decoded)
                try {
                    require(source.contentEquals(canonical)) {
                        "Backup snapshot is not canonical"
                    }
                } finally {
                    canonical.fill(0)
                }
                return decoded
            } finally {
                source.fill(0)
            }
        }
    }
}

class BackupPayloadTooLargeException(
    label: String,
    maximumBytes: Int,
) : IllegalArgumentException("Backup $label exceeds $maximumBytes bytes")

interface BackupOperationSegmentCodec {
    fun fromJournalEntries(
        vaultId: VaultId,
        entries: List<BackupJournalEntity>,
    ): BackupOperationSegmentPayloadV1

    fun encode(payload: BackupOperationSegmentPayloadV1): ByteArray
    fun decode(source: ByteArray): BackupOperationSegmentPayloadV1
    fun decodeOwned(source: ByteArray): BackupOperationSegmentPayloadV1

    companion object : BackupOperationSegmentCodec {
        const val MAX_PLAINTEXT_BYTES: Int = 16 * 1024 * 1024 - 33

        override fun fromJournalEntries(
            vaultId: VaultId,
            entries: List<BackupJournalEntity>,
        ): BackupOperationSegmentPayloadV1 {
            require(entries.isNotEmpty()) { "Backup segment requires entries" }
            require(entries.size <= CloudBounds.MAX_OPERATIONS_PER_SEGMENT) {
                "Backup segment exceeds ${CloudBounds.MAX_OPERATIONS_PER_SEGMENT} entries"
            }
            val mapped = entries.map { entity ->
                require(entity.vaultId == vaultId.value) {
                    "Journal entry belongs to another vault"
                }
                require(entity.payloadFormatVersion == FORMAT_VERSION) {
                    "Legacy or unsupported journal payload ${entity.payloadFormatVersion}"
                }
                val mutation = BackupMutationCodec.decode(entity.payload)
                require(entity.mutationKind == mutation.mutationKind.name) {
                    "Journal mutation kind does not match its payload"
                }
                val entry = BackupSegmentEntryV1(
                    operationId = entity.operationId,
                    generation = entity.generation,
                    sequence = entity.sequence,
                    objectId = entity.objectId,
                    objectType = entity.objectType,
                    revisionWallMillis = entity.revisionWallMillis,
                    revisionLogical = entity.revisionLogical,
                    sourceDeviceId = entity.sourceDeviceId,
                    payloadBase64 = Base64.getEncoder()
                        .withoutPadding()
                        .encodeToString(entity.payload),
                )
                validateEntryIdentity(entry, mutation)
                entry
            }
            return BackupOperationSegmentPayloadV1(
                vaultId = vaultId.value,
                firstGeneration = mapped.first().generation,
                lastGeneration = mapped.last().generation,
                entries = mapped,
                entryCount = mapped.size,
            ).also(::validateSegment)
        }

        override fun encode(payload: BackupOperationSegmentPayloadV1): ByteArray {
            validateSegment(payload)
            return StrictBackupPayloadJson.encode(
                serializer = BackupOperationSegmentPayloadV1.serializer(),
                value = payload,
                maximumBytes = MAX_PLAINTEXT_BYTES,
                label = "operation segment",
            )
        }

        override fun decode(source: ByteArray): BackupOperationSegmentPayloadV1 =
            decodeCallerPreserving(source) { it.copyOf() }

        internal fun decodeCallerPreserving(
            source: ByteArray,
            ownershipCopy: (ByteArray) -> ByteArray,
        ): BackupOperationSegmentPayloadV1 {
            require(source.size <= MAX_PLAINTEXT_BYTES) {
                "Backup operation segment exceeds $MAX_PLAINTEXT_BYTES bytes"
            }
            return decodeOwned(ownershipCopy(source))
        }

        override fun decodeOwned(source: ByteArray): BackupOperationSegmentPayloadV1 {
            try {
                require(source.size <= MAX_PLAINTEXT_BYTES) {
                    "Backup operation segment exceeds $MAX_PLAINTEXT_BYTES bytes"
                }
                require(source.isNotEmpty()) { "Backup operation segment is empty" }
                val decoded = StrictBackupPayloadJson.decodeSegment(source)
                validateSegment(decoded)
                val canonical = encode(decoded)
                try {
                    require(source.contentEquals(canonical)) {
                        "Backup operation segment is not canonical"
                    }
                } finally {
                    canonical.fill(0)
                }
                return decoded
            } finally {
                source.fill(0)
            }
        }
    }
}

private const val FORMAT_VERSION = 1
private const val MINIMUM_READER_VERSION = 1
private const val MAX_IDENTIFIER_LENGTH = 200
private const val MAX_JOURNAL_OBJECT_ID_LENGTH = 409

private val recordComparator = Comparator<BackupRecordV1> { left, right ->
    val familyResult = left.family.ordinal.compareTo(right.family.ordinal)
    if (familyResult != 0) {
        familyResult
    } else {
        compareIdentity(left.identity, right.identity)
    }
}

private fun validateSnapshot(payload: BackupSnapshotPayloadV1) {
    require(payload.formatVersion == FORMAT_VERSION) {
        "Unsupported backup snapshot format ${payload.formatVersion}"
    }
    require(payload.minimumReaderVersion == MINIMUM_READER_VERSION) {
        "Unsupported backup snapshot minimum reader ${payload.minimumReaderVersion}"
    }
    validateIdentifier(payload.vaultId, "vaultId")
    require(payload.coveredGeneration >= 0) { "Covered generation cannot be negative" }
    require(payload.records.size <= CloudBounds.MAX_RECORDS_PER_SNAPSHOT) {
        "Backup snapshot exceeds ${CloudBounds.MAX_RECORDS_PER_SNAPSHOT} records"
    }
    require(payload.records == payload.records.sortedWith(recordComparator)) {
        "Backup snapshot records are not in canonical order"
    }

    val recordsByFamily = BackupRecordFamily.entries.associateWith { family ->
        payload.records.filter { it.family == family }
    }
    val identities = mutableSetOf<BackupRecordKey>()
    payload.records.forEach { record ->
        BackupMutationCodec.validateRecord(record)
        require(identities.add(BackupRecordKey(record))) {
            "Duplicate ${record.family} identity ${record.identity}"
        }
    }

    val vaults = recordsByFamily.getValue(BackupRecordFamily.VAULT).identityMap()
    require(vaults.size == 1 && vaults.containsKey(payload.vaultId)) {
        "Snapshot vault identity does not match its vault record"
    }
    val members = recordsByFamily.getValue(BackupRecordFamily.MEMBER).identityMap()
    val workspaces = recordsByFamily.getValue(BackupRecordFamily.WORKSPACE).identityMap()
    require(workspaces.isNotEmpty()) { "Snapshot requires a workspace" }
    workspaces.values.forEach { workspace ->
        require(workspace.value("vaultId") == payload.vaultId) {
            "Workspace belongs to another vault"
        }
        require(members.containsKey(workspace.requiredValue("ownerId"))) {
            "Workspace owner does not exist"
        }
    }

    val projects = recordsByFamily.getValue(BackupRecordFamily.PROJECT).identityMap()
    projects.values.forEach { project ->
        require(workspaces.containsKey(project.requiredValue("workspaceId"))) {
            "Project workspace does not exist"
        }
    }

    val statuses = recordsByFamily
        .getValue(BackupRecordFamily.WORKFLOW_STATUS)
        .identityMap()
    statuses.values.forEach { status ->
        status.value("projectId")?.let { projectId ->
            require(projects.containsKey(projectId)) { "Workflow project does not exist" }
        }
    }
    validateWorkflows(projects.keys, statuses.values)

    val milestones = recordsByFamily.getValue(BackupRecordFamily.MILESTONE).identityMap()
    milestones.values.forEach { milestone ->
        require(projects.containsKey(milestone.requiredValue("projectId"))) {
            "Milestone project does not exist"
        }
    }

    val tasks = recordsByFamily.getValue(BackupRecordFamily.TASK).identityMap()
    tasks.values.forEach { task ->
        validateTaskRelations(task, workspaces, projects, statuses, milestones, tasks)
    }
    requireAcyclic(
        nodes = tasks.keys,
        edges = tasks.values.mapNotNull { task ->
            task.value("parentTaskId")?.let { parent -> task.singleIdentity() to parent }
        },
        label = "Task parent",
    )

    validateTaskChildren(recordsByFamily, tasks, projects)
    validateDependencies(
        recordsByFamily.getValue(BackupRecordFamily.TASK_DEPENDENCY),
        tasks,
    )
    validateTagRelations(recordsByFamily, tasks, workspaces)
    validateWorkspaceOwnedRecords(recordsByFamily, workspaces)
}

private fun validateWorkflows(
    projectIds: Set<String>,
    statuses: Collection<BackupRecordV1>,
) {
    val grouped = statuses.groupBy { it.value("projectId") }
    require(grouped.keys.all { it == null || it in projectIds }) {
        "Workflow status has an invalid scope"
    }
    grouped.values.forEach { workflow ->
        require(workflow.map { it.requiredValue("rank") }.distinct().size == workflow.size) {
            "Workflow ranks must be unique"
        }
        val active = workflow.filter { it.value("archivedAtEpochMillis") == null }
        require(active.size <= 20) { "Workflow exceeds 20 active statuses" }
    }
    (projectIds.map { it as String? } + null).forEach { scope ->
        val active = grouped[scope]
            .orEmpty()
            .filter { it.value("archivedAtEpochMillis") == null }
        SemanticStatus.entries.forEach { semantic ->
            require(active.any { it.requiredValue("semanticStatus") == semantic.name }) {
                "Workflow is missing active ${semantic.name} status"
            }
        }
    }
}

private fun validateTaskRelations(
    task: BackupRecordV1,
    workspaces: Map<String, BackupRecordV1>,
    projects: Map<String, BackupRecordV1>,
    statuses: Map<String, BackupRecordV1>,
    milestones: Map<String, BackupRecordV1>,
    tasks: Map<String, BackupRecordV1>,
) {
    val workspaceId = task.requiredValue("workspaceId")
    require(workspaces.containsKey(workspaceId)) { "Task workspace does not exist" }
    val projectId = task.value("projectId")
    projectId?.let { id ->
        val project = projects[id] ?: throw IllegalArgumentException("Task project does not exist")
        require(project.requiredValue("workspaceId") == workspaceId) {
            "Task project belongs to another workspace"
        }
    }
    task.value("parentTaskId")?.let { parentId ->
        val parent = tasks[parentId] ?: throw IllegalArgumentException("Parent task does not exist")
        require(parent.requiredValue("workspaceId") == workspaceId) {
            "Parent task belongs to another workspace"
        }
    }
    val status = statuses[task.requiredValue("statusId")]
        ?: throw IllegalArgumentException("Task status does not exist")
    require(status.value("projectId") == projectId) {
        "Task status belongs to another workflow"
    }
    require(status.requiredValue("semanticStatus") == task.requiredValue("semanticStatus")) {
        "Task semantic status does not match its workflow status"
    }
    val completed = task.requiredValue("semanticStatus") == SemanticStatus.COMPLETED.name
    require(completed == (task.value("completedAtEpochMillis") != null)) {
        "Task completion instant does not match its semantic status"
    }
    task.value("milestoneId")?.let { milestoneId ->
        val milestone = milestones[milestoneId]
            ?: throw IllegalArgumentException("Task milestone does not exist")
        require(projectId != null && milestone.requiredValue("projectId") == projectId) {
            "Task milestone belongs to another project"
        }
    }
}

private fun validateTaskChildren(
    recordsByFamily: Map<BackupRecordFamily, List<BackupRecordV1>>,
    tasks: Map<String, BackupRecordV1>,
    projects: Map<String, BackupRecordV1>,
) {
    val checklist = recordsByFamily.getValue(BackupRecordFamily.CHECKLIST_ITEM)
    checklist.forEach { item ->
        require(tasks.containsKey(item.requiredValue("taskId"))) {
            "Checklist task does not exist"
        }
    }
    checklist.groupBy { it.requiredValue("taskId") }.values.forEach { items ->
        require(items.map { it.requiredValue("rank") }.distinct().size == items.size) {
            "Checklist ranks must be unique per task"
        }
    }

    recordsByFamily.getValue(BackupRecordFamily.REMINDER).forEach { reminder ->
        val taskId = reminder.requiredValue("taskId")
        require(tasks.containsKey(taskId)) { "Reminder task does not exist" }
        require(reminder.singleIdentity() == "reminder:$taskId") {
            "Reminder identity is not derived from its task"
        }
    }
    recordsByFamily.getValue(BackupRecordFamily.REMINDER)
        .groupBy { it.requiredValue("taskId") }
        .values
        .forEach { require(it.size == 1) { "Task has multiple reminders" } }

    recordsByFamily.getValue(BackupRecordFamily.ATTACHMENT).forEach { attachment ->
        require(tasks.containsKey(attachment.requiredValue("taskId"))) {
            "Attachment task does not exist"
        }
    }
    recordsByFamily.getValue(BackupRecordFamily.NOTE).forEach { note ->
        note.value("taskId")?.let { taskId ->
            require(tasks.containsKey(taskId)) { "Note task does not exist" }
        }
        note.value("projectId")?.let { projectId ->
            require(projects.containsKey(projectId)) { "Note project does not exist" }
        }
    }
    recordsByFamily.getValue(BackupRecordFamily.TIME_ENTRY).forEach { entry ->
        require(tasks.containsKey(entry.requiredValue("taskId"))) {
            "Time entry task does not exist"
        }
    }
    recordsByFamily.getValue(BackupRecordFamily.ACTIVITY_ENTRY).forEach { activity ->
        activity.value("taskId")?.let { taskId ->
            require(tasks.containsKey(taskId)) { "Activity task does not exist" }
        }
        activity.value("projectId")?.let { projectId ->
            require(projects.containsKey(projectId)) { "Activity project does not exist" }
        }
    }
    recordsByFamily.getValue(BackupRecordFamily.MY_DAY).forEach { entry ->
        require(tasks.containsKey(entry.requiredValue("taskId"))) {
            "My Day task does not exist"
        }
    }
}

private fun validateDependencies(
    dependencies: List<BackupRecordV1>,
    tasks: Map<String, BackupRecordV1>,
) {
    val edges = dependencies.map { dependency ->
        val taskId = dependency.requiredValue("taskId")
        val dependsOn = dependency.requiredValue("dependsOnTaskId")
        val task = tasks[taskId] ?: throw IllegalArgumentException("Dependency task does not exist")
        val prerequisite = tasks[dependsOn]
            ?: throw IllegalArgumentException("Dependency prerequisite does not exist")
        require(task.requiredValue("workspaceId") == prerequisite.requiredValue("workspaceId")) {
            "Dependency crosses workspaces"
        }
        taskId to dependsOn
    }
    requireAcyclic(tasks.keys, edges, "Task dependency")
}

private fun validateTagRelations(
    recordsByFamily: Map<BackupRecordFamily, List<BackupRecordV1>>,
    tasks: Map<String, BackupRecordV1>,
    workspaces: Map<String, BackupRecordV1>,
) {
    val tags = recordsByFamily.getValue(BackupRecordFamily.TAG).identityMap()
    tags.values.forEach { tag ->
        require(workspaces.containsKey(tag.requiredValue("workspaceId"))) {
            "Tag workspace does not exist"
        }
    }
    recordsByFamily.getValue(BackupRecordFamily.TASK_TAG).forEach { relation ->
        val task = tasks[relation.requiredValue("taskId")]
            ?: throw IllegalArgumentException("Task-tag task does not exist")
        val tag = tags[relation.requiredValue("tagId")]
            ?: throw IllegalArgumentException("Task-tag tag does not exist")
        require(task.requiredValue("workspaceId") == tag.requiredValue("workspaceId")) {
            "Task-tag relation crosses workspaces"
        }
    }
}

private fun validateWorkspaceOwnedRecords(
    recordsByFamily: Map<BackupRecordFamily, List<BackupRecordV1>>,
    workspaces: Map<String, BackupRecordV1>,
) {
    listOf(
        BackupRecordFamily.TEMPLATE,
        BackupRecordFamily.SAVED_VIEW,
        BackupRecordFamily.AUTOMATION_RULE,
    ).forEach { family ->
        recordsByFamily.getValue(family).forEach { record ->
            require(workspaces.containsKey(record.requiredValue("workspaceId"))) {
                "$family workspace does not exist"
            }
        }
    }
}

private fun requireAcyclic(
    nodes: Set<String>,
    edges: List<Pair<String, String>>,
    label: String,
) {
    val outgoing = nodes.associateWith { mutableListOf<String>() }
    val indegree = nodes.associateWith { 0 }.toMutableMap()
    edges.forEach { (from, to) ->
        outgoing.getValue(from).add(to)
        indegree[to] = indegree.getValue(to) + 1
    }
    val ready = ArrayDeque(indegree.filterValues { it == 0 }.keys)
    var visited = 0
    while (ready.isNotEmpty()) {
        val node = ready.removeFirst()
        visited += 1
        outgoing.getValue(node).forEach { destination ->
            val remaining = indegree.getValue(destination) - 1
            indegree[destination] = remaining
            if (remaining == 0) ready.addLast(destination)
        }
    }
    require(visited == nodes.size) { "$label graph contains a cycle" }
}

private fun validateSegment(payload: BackupOperationSegmentPayloadV1) {
    require(payload.formatVersion == FORMAT_VERSION) {
        "Unsupported backup operation segment format ${payload.formatVersion}"
    }
    require(payload.minimumReaderVersion == MINIMUM_READER_VERSION) {
        "Unsupported backup operation segment minimum reader ${payload.minimumReaderVersion}"
    }
    validateIdentifier(payload.vaultId, "vaultId")
    require(payload.firstGeneration >= 0 && payload.lastGeneration >= 0) {
        "Segment generations cannot be negative"
    }
    require(payload.firstGeneration <= payload.lastGeneration) {
        "Segment generation range is reversed"
    }
    require(payload.entries.isNotEmpty()) { "Backup segment requires entries" }
    require(payload.entries.size <= CloudBounds.MAX_OPERATIONS_PER_SEGMENT) {
        "Backup segment exceeds ${CloudBounds.MAX_OPERATIONS_PER_SEGMENT} entries"
    }
    require(payload.entryCount == payload.entries.size) {
        "Segment entry count does not agree with entries"
    }
    require(payload.entries.first().generation == payload.firstGeneration) {
        "Segment first generation does not agree with entries"
    }
    require(payload.entries.last().generation == payload.lastGeneration) {
        "Segment last generation does not agree with entries"
    }

    val operationIds = mutableSetOf<String>()
    var previous: BackupSegmentEntryV1? = null
    payload.entries.forEach { entry ->
        validateIdentifier(entry.operationId, "operationId")
        require(
            entry.objectId.isNotBlank() &&
                entry.objectId.length <= MAX_JOURNAL_OBJECT_ID_LENGTH,
        ) {
            "objectId must contain 1..$MAX_JOURNAL_OBJECT_ID_LENGTH characters"
        }
        validateIdentifier(entry.objectType, "objectType")
        validateIdentifier(entry.sourceDeviceId, "sourceDeviceId")
        require(entry.generation in payload.firstGeneration..payload.lastGeneration) {
            "Entry generation is outside the segment range"
        }
        require(entry.sequence >= 0) { "Entry sequence cannot be negative" }
        require(entry.revisionWallMillis >= 0) { "Entry revision wall time cannot be negative" }
        require(entry.revisionLogical >= 0) { "Entry revision logical value cannot be negative" }
        require(operationIds.add(entry.operationId)) { "Duplicate segment operation ID" }
        previous?.let { prior ->
            require(
                entry.generation > prior.generation ||
                    entry.generation == prior.generation && entry.sequence > prior.sequence,
            ) {
                "Segment entries are not ordered by generation and sequence"
            }
        }
        previous = entry
        validateEmbeddedMutation(entry)
    }
}

private fun validateEmbeddedMutation(entry: BackupSegmentEntryV1) {
    require(!entry.payloadBase64.endsWith('=')) {
        "Segment mutation payload uses padded Base64"
    }
    val decoded = try {
        Base64.getDecoder().decode(entry.payloadBase64)
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("Segment mutation payload is not Base64", failure)
    }
    try {
        val canonicalBase64 = Base64.getEncoder().withoutPadding().encodeToString(decoded)
        require(canonicalBase64 == entry.payloadBase64) {
            "Segment mutation payload is not canonical Base64"
        }
        val mutation = BackupMutationCodec.decodeOwned(decoded)
        validateEntryIdentity(entry, mutation)
    } finally {
        decoded.fill(0)
    }
}

private fun validateEntryIdentity(
    entry: BackupSegmentEntryV1,
    mutation: BackupMutationPayloadV1,
) {
    val (family, identity) = when (mutation.mutationKind) {
        BackupMutationKind.UPSERT -> {
            val record = requireNotNull(mutation.record)
            record.family to record.identity
        }
        BackupMutationKind.DELETE ->
            requireNotNull(mutation.deletedFamily) to requireNotNull(mutation.deletedIdentity)
        BackupMutationKind.LEGACY ->
            throw IllegalArgumentException("Legacy mutation cannot be in a segment")
    }
    require(entry.objectType == family.name) {
        "Segment entry type does not match its mutation"
    }
    require(entry.objectId == identity.toJournalObjectId()) {
        "Segment entry identity does not match its mutation"
    }
}

private fun List<BackupRecordV1>.identityMap(): Map<String, BackupRecordV1> =
    associateBy(BackupRecordV1::singleIdentity)

private fun BackupRecordV1.singleIdentity(): String {
    require(identity.size == 1) { "$family does not have a single identity" }
    return identity.single()
}

private fun BackupRecordV1.value(name: String): String? =
    fields.first { it.name == name }.value

private fun BackupRecordV1.requiredValue(name: String): String =
    requireNotNull(value(name)) { "$family field $name is required" }

private fun compareIdentity(left: List<String>, right: List<String>): Int {
    left.zip(right).forEach { (leftPart, rightPart) ->
        val result = leftPart.compareTo(rightPart)
        if (result != 0) return result
    }
    return left.size.compareTo(right.size)
}

private fun validateIdentifier(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_IDENTIFIER_LENGTH) {
        "$label must contain 1..$MAX_IDENTIFIER_LENGTH characters"
    }
}

private fun List<String>.toJournalObjectId(): String =
    if (size == 1) {
        single()
    } else {
        joinToString(separator = "|") { component -> "${component.length}:$component" }
    }

@OptIn(ExperimentalSerializationApi::class)
private object StrictBackupPayloadJson {
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowTrailingComma = false
    }

    fun <T> encode(
        serializer: SerializationStrategy<T>,
        value: T,
        maximumBytes: Int,
        label: String,
    ): ByteArray {
        val output = BoundedBackupPayloadOutput(maximumBytes, label)
        return try {
            json.encodeToStream(serializer, value, output)
            output.takeBytes()
        } finally {
            output.clear()
        }
    }

    fun decodeSnapshot(source: ByteArray): BackupSnapshotPayloadV1 {
        val text = decodeUtf8(source, "snapshot")
        return try {
            json.decodeFromString<BackupSnapshotPayloadV1>(text)
        } catch (failure: SerializationException) {
            throw IllegalArgumentException("Invalid backup snapshot", failure)
        }
    }

    fun decodeSegment(source: ByteArray): BackupOperationSegmentPayloadV1 {
        val text = decodeUtf8(source, "operation segment")
        return try {
            json.decodeFromString<BackupOperationSegmentPayloadV1>(text)
        } catch (failure: SerializationException) {
            throw IllegalArgumentException("Invalid backup operation segment", failure)
        }
    }

    private fun decodeUtf8(source: ByteArray, label: String): String = try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(source))
            .toString()
    } catch (failure: Exception) {
        throw IllegalArgumentException("Backup $label is not valid UTF-8", failure)
    }
}

private class BoundedBackupPayloadOutput(
    private val maximumBytes: Int,
    private val label: String,
) : OutputStream() {
    private var buffer = ByteArray(minOf(8 * 1024, maximumBytes))
    private var count = 0

    override fun write(value: Int) {
        ensureCapacity(1)
        buffer[count] = value.toByte()
        count += 1
    }

    override fun write(
        source: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0 && length >= 0 && offset <= source.size - length)
        ensureCapacity(length)
        source.copyInto(buffer, count, offset, offset + length)
        count += length
    }

    fun takeBytes(): ByteArray {
        if (count == buffer.size) {
            return buffer.also {
                buffer = ByteArray(0)
                count = 0
            }
        }
        return buffer.copyOf(count).also { buffer.fill(0) }
    }

    fun clear() {
        buffer.fill(0)
        buffer = ByteArray(0)
        count = 0
    }

    private fun ensureCapacity(additionalBytes: Int) {
        if (additionalBytes > maximumBytes - count) {
            throw BackupPayloadTooLargeException(label, maximumBytes)
        }
        val required = count + additionalBytes
        if (required <= buffer.size) return
        var capacity = maxOf(1, buffer.size)
        while (capacity < required) {
            capacity = minOf(maximumBytes, maxOf(required, capacity * 2))
        }
        val replacement = ByteArray(capacity)
        buffer.copyInto(replacement, endIndex = count)
        buffer.fill(0)
        buffer = replacement
    }
}
