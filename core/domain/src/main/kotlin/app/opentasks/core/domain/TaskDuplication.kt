package app.opentasks.core.domain

import app.opentasks.core.model.Revision
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatus

private const val COPY_SUFFIX = " (copy)"
private const val MAX_DUPLICATE_TITLE_LENGTH = 240

fun planTaskDuplicate(
    source: Task,
    targetStatus: WorkflowStatus,
    duplicateId: TaskId,
    checklistItemIds: List<String>,
    revision: Revision,
): Task {
    require(targetStatus.projectId == source.projectId)
    require(checklistItemIds.size == source.checklist.size)
    return source.copy(
        id = duplicateId,
        statusId = targetStatus.id,
        semanticStatus = targetStatus.semanticStatus,
        title = source.title.take(MAX_DUPLICATE_TITLE_LENGTH - COPY_SUFFIX.length) + COPY_SUFFIX,
        checklist = source.checklist.zip(checklistItemIds) { item, id ->
            item.copy(id = id, completed = false)
        },
        blockedBy = emptySet(),
        completedAt = null,
        deletedAt = null,
        recurrence = null,
        recurrenceSeriesId = null,
        recurrenceAnchor = null,
        recurrenceOccurrenceIndex = null,
        revision = revision,
    )
}
