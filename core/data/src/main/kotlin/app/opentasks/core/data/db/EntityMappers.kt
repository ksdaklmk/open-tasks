package app.opentasks.core.data.db

import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Milestone
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
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
    projectId = ProjectId(projectId),
    name = name,
    semanticStatus = SemanticStatus.valueOf(semanticStatus),
    rank = rank,
    archivedAt = archivedAtEpochMillis?.let(Instant::ofEpochMilli),
)

internal fun MilestoneEntity.toModel(): Milestone = Milestone(
    id = MilestoneId(id),
    projectId = ProjectId(projectId),
    name = name,
    dueDate = dueDate?.let(LocalDate::parse),
    completedAt = completedAtEpochMillis?.let(Instant::ofEpochMilli),
)

internal fun Milestone.toEntity(): MilestoneEntity = MilestoneEntity(
    id = id.value,
    projectId = projectId.value,
    name = name,
    dueDate = dueDate?.toString(),
    completedAtEpochMillis = completedAt?.toEpochMilli(),
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

internal fun Task.dependencyEntities(): List<TaskDependencyEntity> = blockedBy.map { dependencyId ->
    TaskDependencyEntity(
        taskId = id.value,
        dependsOnTaskId = dependencyId.value,
        revisionWallMillis = revision.wallTimeMillis,
        revisionLogical = revision.logicalCounter,
        revisionDeviceId = revision.deviceId.value,
    )
}
