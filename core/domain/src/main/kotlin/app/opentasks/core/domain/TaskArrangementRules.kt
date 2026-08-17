package app.opentasks.core.domain

import app.opentasks.core.model.BoardColumn
import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskArrangement
import app.opentasks.core.model.TaskGroup
import app.opentasks.core.model.TaskGroupKey
import app.opentasks.core.model.TaskGroupValue
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TaskSortKey
import app.opentasks.core.model.WorkflowStatus
import java.time.Clock

fun taskComparator(sort: TaskSortKey): Comparator<Task> =
    Comparator<Task> { first, second ->
        when (sort) {
            TaskSortKey.DUE -> compareDue(first, second)
            TaskSortKey.PRIORITY -> second.priority.ordinal.compareTo(first.priority.ordinal)
            TaskSortKey.TITLE -> String.CASE_INSENSITIVE_ORDER.compare(first.title, second.title)
            TaskSortKey.UPDATED -> second.revision.wallTimeMillis.compareTo(first.revision.wallTimeMillis)
        }
    }.thenComparator { first: Task, second: Task ->
        String.CASE_INSENSITIVE_ORDER.compare(first.title, second.title)
    }.thenComparator { first: Task, second: Task ->
        first.id.value.compareTo(second.id.value)
    }

fun arrangeTasks(
    tasks: List<Task>,
    arrangement: TaskArrangement,
    projectNames: Map<ProjectId, String>,
    clock: Clock,
): List<TaskGroup> {
    val sorted = tasks.sortedWith(taskComparator(arrangement.sort))
    return when (arrangement.groupBy) {
        null -> listOf(TaskGroup(null, nestWithinGroup(sorted)))
        TaskGroupKey.DUE_BUCKET -> sorted.groupBy { classifyDueBucket(it.due, clock) }
            .let { groups ->
                DueBucket.entries.mapNotNull { bucket ->
                    groups[bucket]?.let { TaskGroup(TaskGroupValue.Due(bucket), nestWithinGroup(it)) }
                }
            }
        TaskGroupKey.PROJECT -> sorted.groupBy(Task::projectId)
            .let { groups ->
                groups.keys.sortedWith(projectGroupComparator(projectNames)).map { projectId ->
                    TaskGroup(
                        TaskGroupValue.Project(projectId),
                        nestWithinGroup(checkNotNull(groups[projectId])),
                    )
                }
            }
        TaskGroupKey.PRIORITY -> sorted.groupBy(Task::priority)
            .let { groups ->
                Priority.entries.reversed().mapNotNull { priority ->
                    groups[priority]?.let {
                        TaskGroup(TaskGroupValue.PriorityValue(priority), nestWithinGroup(it))
                    }
                }
            }
    }
}

/**
 * Reorders one already-sorted group so every child sits directly after its
 * parent, depth-first. Only children whose parent is present in this same
 * group are relocated; a child whose parent landed elsewhere (a different
 * group, or filtered out) keeps its comparator position. Recovered foreign
 * data can violate the one-level depth invariant (grandparent-parent-child
 * chains); acyclicity is guaranteed by that data's own construction, so the
 * recursion here always terminates, and [indentedTaskIds] clamps the
 * rendered result to a single visual indent level regardless of chain depth.
 */
private fun nestWithinGroup(sorted: List<Task>): List<Task> {
    val presentIds = sorted.mapTo(hashSetOf(), Task::id)
    val childrenByParent = sorted
        .filter { it.parentTaskId != null && it.parentTaskId in presentIds }
        .groupBy { requireNotNull(it.parentTaskId) }
    val nestedIds = childrenByParent.values.flatten().mapTo(hashSetOf(), Task::id)
    return buildList {
        fun addWithDescendants(task: Task) {
            add(task)
            childrenByParent[task.id].orEmpty().forEach(::addWithDescendants)
        }
        sorted.forEach { task ->
            if (task.id !in nestedIds) addWithDescendants(task)
        }
    }
}

/** Every task id nested under its parent by [arrangeTasks], one level deep. */
fun indentedTaskIds(groups: List<TaskGroup>): Set<TaskId> =
    groups.flatMapTo(hashSetOf()) { group ->
        val present = group.tasks.mapTo(hashSetOf(), Task::id)
        group.tasks
            .filter { it.parentTaskId != null && it.parentTaskId in present }
            .map(Task::id)
    }

fun boardColumns(
    project: Project,
    statuses: List<WorkflowStatus>,
    tasks: List<Task>,
    sort: TaskSortKey = TaskSortKey.PRIORITY,
): List<BoardColumn> = statuses
    .filter { it.projectId == project.id && it.archivedAt == null }
    .sortedBy(WorkflowStatus::rank)
    .map { status ->
        BoardColumn(
            status = status,
            tasks = tasks
                .filter {
                    it.projectId == project.id &&
                        it.statusId == status.id &&
                        it.deletedAt == null &&
                        !it.isCompleted
                }
                .sortedWith(taskComparator(sort)),
        )
    }

private fun compareDue(first: Task, second: Task): Int {
    val firstDue = first.due
    val secondDue = second.due
    return when {
        firstDue == null && secondDue == null -> 0
        firstDue == null -> 1
        secondDue == null -> -1
        else -> firstDue.instant.compareTo(secondDue.instant)
    }
}

private fun projectGroupComparator(projectNames: Map<ProjectId, String>): Comparator<ProjectId?> =
    Comparator { first, second ->
        when {
            first == null && second == null -> 0
            first == null -> -1
            second == null -> 1
            projectNames[first] != null && projectNames[second] != null ->
                String.CASE_INSENSITIVE_ORDER.compare(projectNames.getValue(first), projectNames.getValue(second))
                    .takeIf { it != 0 } ?: first.value.compareTo(second.value)
            projectNames[first] != null -> -1
            projectNames[second] != null -> 1
            else -> first.value.compareTo(second.value)
        }
    }
