package app.opentasks.core.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class ActiveTimerSnapshot(
    val entryId: TimeEntryId,
    val taskId: TaskId,
    val taskTitle: String,
    val projectName: String?,
    val startedAt: Instant,
    val elapsed: Duration,
)

data class HomeSnapshot(
    val today: LocalDate,
    val focusTasks: List<Task>,
    val upcomingTasks: List<Task>,
    val projects: List<Project>,
    val activeTimer: ActiveTimerSnapshot?,
    val overdueCount: Int,
)

data class WorkspaceSnapshot(
    val home: HomeSnapshot,
    val tasks: List<Task>,
    val projects: List<Project>,
    val workflowStatuses: List<WorkflowStatus>,
    val milestones: List<Milestone>,
    val tags: List<Tag>,
    val reminders: List<Reminder> = emptyList(),
    val templates: List<Template> = emptyList(),
    val timeEntries: List<TimeEntry> = emptyList(),
    val timeEntryConflicts: List<TimeEntryConflict> = emptyList(),
    val notes: List<Note> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val activityEntries: List<ActivityEntry> = emptyList(),
    val retiredBlobSets: List<RetiredBlobSet> = emptyList(),
    val savedViews: List<SavedView> = emptyList(),
)

data class TimeEntryConflict(
    val firstEntryId: TimeEntryId,
    val secondEntryId: TimeEntryId,
    val overlap: Duration,
)

data class SearchQuery(
    val text: String,
    val projectIds: Set<ProjectId> = emptySet(),
    val tagIds: Set<TagId> = emptySet(),
    val includeCompleted: Boolean = true,
    val includeTrash: Boolean = false,
)

sealed interface SearchResult {
    val title: String
    val context: String

    data class TaskResult(
        val task: Task,
        override val context: String,
    ) : SearchResult {
        override val title: String = task.title
    }

    data class ProjectResult(
        val project: Project,
        override val context: String,
    ) : SearchResult {
        override val title: String = project.name
    }
}
