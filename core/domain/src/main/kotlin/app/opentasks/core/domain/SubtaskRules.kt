package app.opentasks.core.domain

import app.opentasks.core.model.SubtaskRollup
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId

enum class SubtaskViolation {
    SELF,
    PARENT_MISSING_OR_BINNED,
    CROSS_PROJECT,
    PARENT_IS_A_SUBTASK,
    TASK_HAS_SUBTASKS,
}

/**
 * Pure one-level subtask nesting authority shared by both vault repository
 * engines' `SetTaskParent` and `CreateTask(parentTaskId:)` handlers, and by
 * later list/board consumers, so the rule stays in exactly one place.
 */
object SubtaskRules {
    /** One-level guard: null when [parentTaskId] may become the parent. */
    fun parentViolation(
        tasks: List<Task>,
        taskId: TaskId,
        parentTaskId: TaskId,
    ): SubtaskViolation? {
        if (taskId == parentTaskId) return SubtaskViolation.SELF
        val parent = tasks.firstOrNull { it.id == parentTaskId && it.deletedAt == null }
            ?: return SubtaskViolation.PARENT_MISSING_OR_BINNED
        val task = tasks.firstOrNull { it.id == taskId }
        if (task != null && task.projectId != parent.projectId) {
            return SubtaskViolation.CROSS_PROJECT
        }
        if (parent.parentTaskId != null) return SubtaskViolation.PARENT_IS_A_SUBTASK
        if (tasks.any { it.parentTaskId == taskId }) {
            return SubtaskViolation.TASK_HAS_SUBTASKS
        }
        return null
    }

    /** done/total over live (non-binned) children per parent. */
    fun subtaskRollups(tasks: List<Task>): Map<TaskId, SubtaskRollup> =
        tasks.asSequence()
            .filter { it.parentTaskId != null && it.deletedAt == null }
            .groupBy { requireNotNull(it.parentTaskId) }
            .mapValues { (_, children) ->
                SubtaskRollup(
                    completed = children.count(Task::isCompleted),
                    total = children.size,
                )
            }

    /** Tasks eligible to become [parent]'s children right now. */
    fun attachableSubtasks(tasks: List<Task>, parent: Task): List<Task> =
        tasks.filter { candidate ->
            candidate.deletedAt == null &&
                candidate.parentTaskId == null &&
                parentViolation(tasks, candidate.id, parent.id) == null
        }
}

/** Shared inline-feedback text for a [SubtaskViolation], identical in both engines. */
fun subtaskViolationMessage(violation: SubtaskViolation): String = when (violation) {
    SubtaskViolation.SELF -> "A task cannot be its own subtask."
    SubtaskViolation.PARENT_MISSING_OR_BINNED ->
        "That parent task is no longer available."
    SubtaskViolation.CROSS_PROJECT ->
        "Subtasks live in the same project as their parent."
    SubtaskViolation.PARENT_IS_A_SUBTASK ->
        "Subtasks go one level deep — that task is already a subtask."
    SubtaskViolation.TASK_HAS_SUBTASKS ->
        "That task has subtasks of its own, so it cannot become one."
}
