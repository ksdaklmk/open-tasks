package app.opentasks.core.data.db

import app.opentasks.core.data.SavedViewPayloadCodec
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.ActivityEntry
import app.opentasks.core.model.ActivityKind
import app.opentasks.core.model.AutomationRule
import app.opentasks.core.model.AutomationRuleId
import app.opentasks.core.model.AutomationRuleType
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Milestone
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.MyDayEntry
import app.opentasks.core.model.Note
import app.opentasks.core.model.NoteId
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.RetiredBlobSet
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SavedView
import app.opentasks.core.model.SavedViewId
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TimeEntry
import app.opentasks.core.model.TimeEntryId
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkspaceId
import app.opentasks.core.model.ZonedMoment
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

internal fun TaskEntity.toModel(
    tagIds: Set<TagId>,
    checklist: List<ChecklistItem>,
    dependencyIds: Set<TaskId>,
    blockedBy: Set<TaskId>,
): Task = Task(
    id = TaskId(id),
    workspaceId = WorkspaceId(workspaceId),
    projectId = projectId?.let(::ProjectId),
    parentTaskId = parentTaskId?.let(::TaskId),
    statusId = WorkflowStatusId(statusId),
    semanticStatus = enumValueOf(semanticStatus),
    title = title,
    description = descriptionCiphertext.toString(Charsets.UTF_8),
    priority = enumValueOf(priority),
    start = startEpochMillis?.let { epoch ->
        ZonedMoment(Instant.ofEpochMilli(epoch), requireNotNull(startZoneId))
    },
    due = dueEpochMillis?.let { epoch ->
        ZonedMoment(Instant.ofEpochMilli(epoch), requireNotNull(dueZoneId))
    },
    recurrence = recurrenceFrequency?.let { frequency ->
        RecurrenceRule(
            frequency = RecurrenceFrequency.valueOf(frequency),
            interval = recurrenceInterval ?: 1,
            weekdays = recurrenceWeekdays
                .orEmpty()
                .split(',')
                .filter(String::isNotBlank)
                .mapTo(linkedSetOf(), DayOfWeek::valueOf),
            count = recurrenceCount,
            endDate = recurrenceEndDate?.let(LocalDate::parse),
        )
    },
    recurrenceSeriesId = recurrenceSeriesId?.let(::TaskId),
    recurrenceAnchor = recurrenceAnchorEpochMillis?.let { epoch ->
        ZonedMoment(Instant.ofEpochMilli(epoch), requireNotNull(recurrenceAnchorZoneId))
    },
    recurrenceOccurrenceIndex = recurrenceOccurrenceIndex,
    estimate = estimateSeconds?.let(Duration::ofSeconds),
    milestoneId = milestoneId?.let(::MilestoneId),
    tagIds = tagIds,
    checklist = checklist,
    dependencyIds = dependencyIds,
    blockedBy = blockedBy,
    completedAt = completedAtEpochMillis?.let(Instant::ofEpochMilli),
    deletedAt = deletedAtEpochMillis?.let(Instant::ofEpochMilli),
    revision = Revision(
        deviceId = DeviceId(revisionDeviceId),
        wallTimeMillis = revisionWallMillis,
        logicalCounter = revisionLogical,
    ),
)

internal fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id.value,
    workspaceId = workspaceId.value,
    projectId = projectId?.value,
    parentTaskId = parentTaskId?.value,
    statusId = statusId.value,
    semanticStatus = semanticStatus.name,
    title = title,
    descriptionCiphertext = description.toByteArray(Charsets.UTF_8),
    priority = priority.name,
    startEpochMillis = start?.instant?.toEpochMilli(),
    startZoneId = start?.zoneId,
    dueEpochMillis = due?.instant?.toEpochMilli(),
    dueZoneId = due?.zoneId,
    recurrenceFrequency = recurrence?.frequency?.name,
    recurrenceInterval = recurrence?.interval,
    recurrenceWeekdays = recurrence?.weekdays
        ?.sortedBy { it.value }
        ?.joinToString(",") { it.name },
    recurrenceCount = recurrence?.count,
    recurrenceEndDate = recurrence?.endDate?.toString(),
    recurrenceSeriesId = recurrenceSeriesId?.value,
    recurrenceAnchorEpochMillis = recurrenceAnchor?.instant?.toEpochMilli(),
    recurrenceAnchorZoneId = recurrenceAnchor?.zoneId,
    recurrenceOccurrenceIndex = recurrenceOccurrenceIndex,
    estimateSeconds = estimate?.seconds,
    milestoneId = milestoneId?.value,
    completedAtEpochMillis = completedAt?.toEpochMilli(),
    deletedAtEpochMillis = deletedAt?.toEpochMilli(),
    revisionWallMillis = revision.wallTimeMillis,
    revisionLogical = revision.logicalCounter,
    revisionDeviceId = revision.deviceId.value,
)

internal fun ProjectEntity.toModel(): Project = Project(
    id = ProjectId(id),
    workspaceId = WorkspaceId(workspaceId),
    name = name,
    summary = summary,
    status = ProjectHealth.valueOf(health),
    dueDate = dueDate?.let(LocalDate::parse),
    completedTasks = completedTasks,
    totalTasks = totalTasks,
    archivedAt = archivedAtEpochMillis?.let(Instant::ofEpochMilli),
)

internal fun Project.toEntity(revision: Revision): ProjectEntity = ProjectEntity(
    id = id.value,
    workspaceId = workspaceId.value,
    name = name,
    summary = summary,
    health = status.name,
    dueDate = dueDate?.toString(),
    completedTasks = completedTasks,
    totalTasks = totalTasks,
    archivedAtEpochMillis = archivedAt?.toEpochMilli(),
    revisionWallMillis = revision.wallTimeMillis,
    revisionLogical = revision.logicalCounter,
    revisionDeviceId = revision.deviceId.value,
)

internal fun WorkflowStatusEntity.toModel(): WorkflowStatus = WorkflowStatus(
    id = WorkflowStatusId(id),
    projectId = projectId?.let(::ProjectId),
    name = name,
    semanticStatus = SemanticStatus.valueOf(semanticStatus),
    rank = rank,
    archivedAt = archivedAtEpochMillis?.let(Instant::ofEpochMilli),
    wipLimit = wipLimit,
)

internal fun WorkflowStatus.toEntity(revision: Revision): WorkflowStatusEntity = WorkflowStatusEntity(
    id = id.value,
    projectId = projectId?.value,
    name = name,
    semanticStatus = semanticStatus.name,
    rank = rank,
    archivedAtEpochMillis = archivedAt?.toEpochMilli(),
    revisionWallMillis = revision.wallTimeMillis,
    revisionLogical = revision.logicalCounter,
    revisionDeviceId = revision.deviceId.value,
    wipLimit = wipLimit,
)

internal fun MilestoneEntity.toModel(): Milestone = Milestone(
    id = MilestoneId(id),
    projectId = ProjectId(projectId),
    name = name,
    dueDate = dueDate?.let(LocalDate::parse),
    completedAt = completedAtEpochMillis?.let(Instant::ofEpochMilli),
)

internal fun Milestone.toEntity(revision: Revision): MilestoneEntity = MilestoneEntity(
    id = id.value,
    projectId = projectId.value,
    name = name,
    dueDate = dueDate?.toString(),
    completedAtEpochMillis = completedAt?.toEpochMilli(),
    revisionWallMillis = revision.wallTimeMillis,
    revisionLogical = revision.logicalCounter,
    revisionDeviceId = revision.deviceId.value,
)

internal fun TagEntity.toModel(): Tag = Tag(
    id = TagId(id),
    workspaceId = WorkspaceId(workspaceId),
    name = name,
)

internal fun Tag.toEntity(): TagEntity = TagEntity(
    id = id.value,
    workspaceId = workspaceId.value,
    name = name,
)

internal fun ReminderEntity.toModel(): Reminder = Reminder(
    id = id,
    taskId = TaskId(taskId),
    triggerAt = ZonedMoment(
        instant = Instant.ofEpochMilli(triggerAtEpochMillis),
        zoneId = zoneId,
    ),
    precise = precise,
)

internal fun Reminder.toEntity(): ReminderEntity = ReminderEntity(
    id = id,
    taskId = taskId.value,
    triggerAtEpochMillis = triggerAt.instant.toEpochMilli(),
    zoneId = triggerAt.zoneId,
    precise = precise,
)

internal fun TimeEntryEntity.toModel(): TimeEntry = TimeEntry(
    id = TimeEntryId(id),
    taskId = TaskId(taskId),
    deviceId = DeviceId(deviceId),
    startedAt = Instant.ofEpochMilli(startedAtEpochMillis),
    stoppedAt = stoppedAtEpochMillis?.let(Instant::ofEpochMilli),
    note = noteCiphertext.toString(Charsets.UTF_8),
)

internal fun TimeEntry.toEntity(): TimeEntryEntity = TimeEntryEntity(
    id = id.value,
    taskId = taskId.value,
    deviceId = deviceId.value,
    startedAtEpochMillis = startedAt.toEpochMilli(),
    stoppedAtEpochMillis = stoppedAt?.toEpochMilli(),
    noteCiphertext = note.toByteArray(Charsets.UTF_8),
)

internal fun ChecklistItemEntity.toModel(): ChecklistItem = ChecklistItem(
    id = id,
    text = text,
    completed = completed,
    rank = rank,
)

internal fun Task.checklistEntities(): List<ChecklistItemEntity> = checklist.map { item ->
    ChecklistItemEntity(
        id = item.id,
        taskId = id.value,
        text = item.text,
        completed = item.completed,
        rank = item.rank,
    )
}

internal fun Task.tagEntities(): List<TaskTagEntity> = tagIds.map { tagId ->
    TaskTagEntity(
        taskId = id.value,
        tagId = tagId.value,
        present = true,
        revisionWallMillis = revision.wallTimeMillis,
        revisionLogical = revision.logicalCounter,
        revisionDeviceId = revision.deviceId.value,
    )
}

internal fun Task.dependencyEntities(): List<TaskDependencyEntity> = dependencyIds.map { dependencyId ->
    TaskDependencyEntity(
        taskId = id.value,
        dependsOnTaskId = dependencyId.value,
        revisionWallMillis = revision.wallTimeMillis,
        revisionLogical = revision.logicalCounter,
        revisionDeviceId = revision.deviceId.value,
    )
}

internal fun NoteEntity.toModel(): Note = Note(
    id = NoteId(id),
    taskId = taskId?.let(::TaskId),
    projectId = projectId?.let(::ProjectId),
    body = bodyCiphertext.toString(Charsets.UTF_8),
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    editedAt = editedAtEpochMillis?.let(Instant::ofEpochMilli),
    revision = Revision(
        deviceId = DeviceId(revisionDeviceId),
        wallTimeMillis = revisionWallMillis,
        logicalCounter = revisionLogical,
    ),
)

internal fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id.value,
    taskId = taskId?.value,
    projectId = projectId?.value,
    bodyCiphertext = body.toByteArray(Charsets.UTF_8),
    createdAtEpochMillis = createdAt.toEpochMilli(),
    editedAtEpochMillis = editedAt?.toEpochMilli(),
    revisionWallMillis = revision.wallTimeMillis,
    revisionLogical = revision.logicalCounter,
    revisionDeviceId = revision.deviceId.value,
)

internal fun AttachmentEntity.toModel(): Attachment = Attachment(
    id = AttachmentId(id),
    taskId = TaskId(taskId),
    displayName = displayNameCiphertext.toString(Charsets.UTF_8),
    mimeType = mimeType,
    byteCount = byteCount,
    contentHash = contentHash,
    blobSetId = blobSetId?.let(::BlobSetId),
    chunkCount = chunkCount,
    deletedAt = deletedAtEpochMillis?.let(Instant::ofEpochMilli),
    revision = Revision(
        deviceId = DeviceId(revisionDeviceId),
        wallTimeMillis = revisionWallMillis,
        logicalCounter = revisionLogical,
    ),
)

internal fun Attachment.toEntity(): AttachmentEntity = AttachmentEntity(
    id = id.value,
    taskId = taskId.value,
    displayNameCiphertext = displayName.toByteArray(Charsets.UTF_8),
    mimeType = mimeType,
    byteCount = byteCount,
    contentHash = contentHash,
    blobSetId = blobSetId?.value,
    chunkCount = chunkCount,
    deletedAtEpochMillis = deletedAt?.toEpochMilli(),
    revisionWallMillis = revision.wallTimeMillis,
    revisionLogical = revision.logicalCounter,
    revisionDeviceId = revision.deviceId.value,
)

/**
 * Strict: throws when the stored payload is malformed or from an unsupported
 * format version. Snapshot mapping wraps this dormant-family decode and omits
 * the row without deleting or rewriting it.
 */
internal fun SavedViewEntity.toModel(): SavedView = SavedView(
    id = SavedViewId(id),
    workspaceId = WorkspaceId(workspaceId),
    name = name,
    query = SavedViewPayloadCodec.decode(encryptedQuery),
)

internal fun SavedView.toEntity(encodedQuery: ByteArray): SavedViewEntity = SavedViewEntity(
    id = id.value,
    workspaceId = workspaceId.value,
    name = name,
    encryptedQuery = encodedQuery,
)

internal fun RetiredBlobSetEntity.toModel(): RetiredBlobSet = RetiredBlobSet(
    blobSetId = BlobSetId(blobSetId),
    chunkCount = chunkCount,
    retiredAt = Instant.ofEpochMilli(retiredAtEpochMillis),
    revision = Revision(
        deviceId = DeviceId(revisionDeviceId),
        wallTimeMillis = revisionWallMillis,
        logicalCounter = revisionLogical,
    ),
)

internal fun ActivityEntryEntity.toModel(): ActivityEntry? {
    val activityKind = ActivityKind.entries.firstOrNull { it.name == kind } ?: return null
    return ActivityEntry(
        id = id,
        taskId = taskId?.let(::TaskId),
        projectId = projectId?.let(::ProjectId),
        kind = activityKind,
        body = bodyCiphertext.toString(Charsets.UTF_8),
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    )
}

internal fun ActivityEntry.toEntity(): ActivityEntryEntity = ActivityEntryEntity(
    id = id,
    taskId = taskId?.value,
    projectId = projectId?.value,
    kind = kind.name,
    bodyCiphertext = body.toByteArray(Charsets.UTF_8),
    createdAtEpochMillis = createdAt.toEpochMilli(),
)

internal fun AutomationRuleEntity.toModel(): AutomationRule = AutomationRule(
    id = AutomationRuleId(id),
    workspaceId = WorkspaceId(workspaceId),
    type = AutomationRuleType.valueOf(type),
    enabled = enabled,
    projectId = projectId?.let(::ProjectId),
    statusId = statusId?.let(::WorkflowStatusId),
    tagId = tagId?.let(::TagId),
    dueInDays = dueInDays,
    thresholdDays = thresholdDays,
)

internal fun AutomationRule.toEntity(): AutomationRuleEntity = AutomationRuleEntity(
    id = id.value,
    workspaceId = workspaceId.value,
    type = type.name,
    enabled = enabled,
    projectId = projectId?.value,
    statusId = statusId?.value,
    tagId = tagId?.value,
    dueInDays = dueInDays,
    thresholdDays = thresholdDays,
)

internal fun MyDayEntryEntity.toModel(): MyDayEntry =
    MyDayEntry(TaskId(taskId), rank)

internal fun MyDayEntry.toEntity(): MyDayEntryEntity =
    MyDayEntryEntity(taskId.value, rank)
