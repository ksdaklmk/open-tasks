package app.opentasks.core.domain

import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.ProjectTimelineDependencyRole
import app.opentasks.core.model.ProjectTimelineMarkerKind
import app.opentasks.core.model.ProjectTimelineMilestoneMarker
import app.opentasks.core.model.ProjectTimelineProjection
import app.opentasks.core.model.ProjectTimelineTaskPlacement
import app.opentasks.core.model.ProjectTimelineTaskRow
import app.opentasks.core.model.ProjectTimelineWindow
import app.opentasks.core.model.ProjectTimelineWindowSide
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkspaceSnapshot
import java.time.LocalDate
import java.time.temporal.ChronoUnit

fun computeProjectTimelineProjection(
    snapshot: WorkspaceSnapshot,
    projectId: ProjectId,
    window: ProjectTimelineWindow,
    selectedTaskId: TaskId? = null,
): ProjectTimelineProjection {
    val projectTasks = snapshot.tasks.filter { it.projectId == projectId && it.deletedAt == null }
    val activeTasksById = snapshot.tasks.filter { it.deletedAt == null }.associateBy(Task::id)
    val selectedId = selectedTaskId?.takeIf { id -> projectTasks.any { it.id == id } }
    val dependantsById = activeTasksById.values
        .flatMap { task -> task.dependencyIds.map { dependencyId -> dependencyId to task.id } }
        .groupBy({ it.first }, { it.second })
    val prerequisites = selectedId?.let { selected ->
        traverseDependencies(activeTasksById, activeTasksById.getValue(selected).dependencyIds) { it.dependencyIds }
    }.orEmpty()
    val dependants = selectedId?.let { selected ->
        traverseDependencies(activeTasksById, dependantsById[selected].orEmpty()) { dependantsById[it.id].orEmpty() }
    }.orEmpty()

    val taskRows = projectTasks.map { task ->
        val startDate = task.start?.let { it.instant.atZone(it.zone()).toLocalDate() }
        val dueDate = task.due?.let { it.instant.atZone(it.zone()).toLocalDate() }
        ProjectTimelineTaskRow(
            task = task,
            startDate = startDate,
            dueDate = dueDate,
            placement = taskPlacement(task, startDate, dueDate, window),
            dependencyRole = when {
                task.id == selectedId -> ProjectTimelineDependencyRole.SELECTED
                task.id in prerequisites && task.id in dependants -> ProjectTimelineDependencyRole.PREREQUISITE_AND_DEPENDANT
                task.id in prerequisites -> ProjectTimelineDependencyRole.PREREQUISITE
                task.id in dependants -> ProjectTimelineDependencyRole.DEPENDANT
                else -> ProjectTimelineDependencyRole.NONE
            },
        )
    }
    val datedMilestones = snapshot.milestones
        .filter { it.projectId == projectId }
        .mapNotNull { milestone -> milestone.dueDate?.let { milestone to it } }

    return ProjectTimelineProjection(
        projectId = projectId,
        window = window,
        taskRows = taskRows,
        milestoneMarkers = datedMilestones.mapNotNull { (milestone, date) ->
            date.dayIndexIn(window)?.let { ProjectTimelineMilestoneMarker(milestone, it) }
        },
        milestonesBeforeWindow = datedMilestones.count { (_, date) -> date < window.firstDate },
        milestonesAfterWindow = datedMilestones.count { (_, date) -> date > window.lastDate },
        selectedTaskId = selectedId,
        outOfProjectDependencyTaskCount = if (selectedId == null) {
            0
        } else {
            (prerequisites + dependants)
                .asSequence()
                .filter { it != selectedId && activeTasksById.getValue(it).projectId != projectId }
                .toSet()
                .size
        },
    )
}

private fun taskPlacement(
    task: Task,
    startDate: LocalDate?,
    dueDate: LocalDate?,
    window: ProjectTimelineWindow,
): ProjectTimelineTaskPlacement = when {
    task.start != null && task.due != null -> when {
        task.due!!.instant < task.start!!.instant || dueDate!! < startDate!! -> ProjectTimelineTaskPlacement.InvalidRange
        dueDate < window.firstDate -> ProjectTimelineTaskPlacement.Outside(ProjectTimelineWindowSide.BEFORE)
        startDate > window.lastDate -> ProjectTimelineTaskPlacement.Outside(ProjectTimelineWindowSide.AFTER)
        else -> ProjectTimelineTaskPlacement.Span(
            firstVisibleDayIndex = ChronoUnit.DAYS.between(window.firstDate, maxOf(window.firstDate, startDate)).toInt(),
            lastVisibleDayIndex = ChronoUnit.DAYS.between(window.firstDate, minOf(window.lastDate, dueDate)).toInt(),
            totalDayCount = ChronoUnit.DAYS.between(startDate, dueDate) + 1,
            continuesBefore = startDate < window.firstDate,
            continuesAfter = dueDate > window.lastDate,
        )
    }
    startDate != null -> startDate.markerPlacement(window, ProjectTimelineMarkerKind.START)
    dueDate != null -> dueDate.markerPlacement(window, ProjectTimelineMarkerKind.DUE)
    else -> ProjectTimelineTaskPlacement.Unscheduled
}

private fun LocalDate.markerPlacement(
    window: ProjectTimelineWindow,
    kind: ProjectTimelineMarkerKind,
): ProjectTimelineTaskPlacement = when {
    this < window.firstDate -> ProjectTimelineTaskPlacement.Outside(ProjectTimelineWindowSide.BEFORE)
    this > window.lastDate -> ProjectTimelineTaskPlacement.Outside(ProjectTimelineWindowSide.AFTER)
    else -> ProjectTimelineTaskPlacement.Marker(ChronoUnit.DAYS.between(window.firstDate, this).toInt(), kind)
}

private fun LocalDate.dayIndexIn(window: ProjectTimelineWindow): Int? =
    if (this in window.firstDate..window.lastDate) ChronoUnit.DAYS.between(window.firstDate, this).toInt() else null

private fun traverseDependencies(
    activeTasksById: Map<TaskId, Task>,
    initialIds: Collection<TaskId>,
    nextIds: (Task) -> Collection<TaskId>,
): Set<TaskId> {
    val pending = ArrayDeque(initialIds)
    val visited = mutableSetOf<TaskId>()
    while (pending.isNotEmpty() && visited.size < activeTasksById.size) {
        val task = activeTasksById[pending.removeFirst()] ?: continue
        if (visited.add(task.id)) nextIds(task).forEach(pending::addLast)
    }
    return visited
}
