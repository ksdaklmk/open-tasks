package app.opentasks.core.model

import java.time.DayOfWeek
import java.time.LocalDate

enum class ProjectPresentation { LIST, BOARD, TIMELINE }

const val PROJECT_TIMELINE_DAY_COUNT = 84

data class ProjectTimelineWindow(val firstDate: LocalDate) {
    init {
        require(firstDate.dayOfWeek == DayOfWeek.MONDAY)
    }

    val lastDate: LocalDate = firstDate.plusDays(PROJECT_TIMELINE_DAY_COUNT - 1L)
}

enum class ProjectTimelineWindowSide { BEFORE, AFTER }

enum class ProjectTimelineMarkerKind { START, DUE }

enum class ProjectTimelineDependencyRole {
    NONE,
    SELECTED,
    PREREQUISITE,
    DEPENDANT,
    PREREQUISITE_AND_DEPENDANT,
}

sealed interface ProjectTimelineTaskPlacement {
    data class Span(
        val firstVisibleDayIndex: Int,
        val lastVisibleDayIndex: Int,
        val totalDayCount: Long,
        val continuesBefore: Boolean,
        val continuesAfter: Boolean,
    ) : ProjectTimelineTaskPlacement

    data class Marker(
        val dayIndex: Int,
        val kind: ProjectTimelineMarkerKind,
    ) : ProjectTimelineTaskPlacement

    data class Outside(val side: ProjectTimelineWindowSide) : ProjectTimelineTaskPlacement

    data object InvalidRange : ProjectTimelineTaskPlacement

    data object Unscheduled : ProjectTimelineTaskPlacement
}

data class ProjectTimelineTaskRow(
    val task: Task,
    val startDate: LocalDate?,
    val dueDate: LocalDate?,
    val placement: ProjectTimelineTaskPlacement,
    val dependencyRole: ProjectTimelineDependencyRole,
)

data class ProjectTimelineMilestoneMarker(
    val milestone: Milestone,
    val dayIndex: Int,
)

data class ProjectTimelineProjection(
    val projectId: ProjectId,
    val window: ProjectTimelineWindow,
    val taskRows: List<ProjectTimelineTaskRow>,
    val milestoneMarkers: List<ProjectTimelineMilestoneMarker>,
    val milestonesBeforeWindow: Int,
    val milestonesAfterWindow: Int,
    val selectedTaskId: TaskId?,
    val outOfProjectDependencyTaskCount: Int,
)
