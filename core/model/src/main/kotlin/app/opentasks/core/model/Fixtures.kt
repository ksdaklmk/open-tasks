package app.opentasks.core.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

object OpenTasksFixtures {
    val workspaceId = WorkspaceId("workspace-primary")
    private val deviceId = DeviceId("fixture-device")
    private val revision = Revision(deviceId, 1_722_000_000_000, 0)

    val studioProject = Project(
        id = ProjectId("project-studio"),
        workspaceId = workspaceId,
        name = "Studio refresh",
        summary = "Open the new portfolio and booking flow.",
        status = ProjectHealth.ON_TRACK,
        dueDate = LocalDate.of(2026, 8, 14),
        completedTasks = 18,
        totalTasks = 27,
    )

    val taxProject = Project(
        id = ProjectId("project-tax"),
        workspaceId = workspaceId,
        name = "Quarterly accounts",
        summary = "Close books and prepare the filing pack.",
        status = ProjectHealth.AT_RISK,
        dueDate = LocalDate.of(2026, 7, 31),
        completedTasks = 7,
        totalTasks = 12,
    )

    val researchProject = Project(
        id = ProjectId("project-research"),
        workspaceId = workspaceId,
        name = "Client research",
        summary = "Synthesize interviews into a direction brief.",
        status = ProjectHealth.BLOCKED,
        dueDate = LocalDate.of(2026, 8, 5),
        completedTasks = 4,
        totalTasks = 10,
    )

    val backlog = WorkflowStatus.defaultId(studioProject.id, SemanticStatus.BACKLOG)
    val planned = WorkflowStatus.defaultId(studioProject.id, SemanticStatus.PLANNED)
    val started = WorkflowStatus.defaultId(studioProject.id, SemanticStatus.STARTED)
    val blocked = WorkflowStatus.defaultId(studioProject.id, SemanticStatus.BLOCKED)
    val done = WorkflowStatus.defaultId(studioProject.id, SemanticStatus.COMPLETED)

    val workflowStatuses =
        WorkflowStatus.defaults(null) +
            WorkflowStatus.defaults(studioProject.id) +
            WorkflowStatus.defaults(taxProject.id) +
            WorkflowStatus.defaults(researchProject.id)

    fun statusId(
        projectId: ProjectId?,
        semanticStatus: SemanticStatus,
    ): WorkflowStatusId = WorkflowStatus.defaultId(projectId, semanticStatus)

    private fun moment(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
    ): ZonedMoment {
        val zone = ZoneId.of("Asia/Bangkok")
        val value = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
        return ZonedMoment(value.toInstant(), zone.id)
    }

    val tasks = listOf(
        Task(
            id = TaskId("task-proposal"),
            workspaceId = workspaceId,
            projectId = studioProject.id,
            statusId = started,
            semanticStatus = SemanticStatus.STARTED,
            title = "Finish launch proposal",
            description = "Resolve the final scope notes and send the decision-ready proposal.",
            priority = Priority.HIGH,
            due = moment(2026, 7, 26, 16, 30),
            estimate = Duration.ofHours(2),
            tagIds = setOf(TagId("tag-deep-work")),
            checklist = listOf(
                ChecklistItem("check-1", "Update scope table", true, "a0"),
                ChecklistItem("check-2", "Confirm launch assumptions", false, "a1"),
                ChecklistItem("check-3", "Export and send", false, "a2"),
            ),
            revision = revision,
        ),
        Task(
            id = TaskId("task-invoices"),
            workspaceId = workspaceId,
            projectId = taxProject.id,
            statusId = statusId(taxProject.id, SemanticStatus.PLANNED),
            semanticStatus = SemanticStatus.PLANNED,
            title = "Reconcile July invoices",
            priority = Priority.URGENT,
            due = moment(2026, 7, 26, 18),
            estimate = Duration.ofMinutes(45),
            tagIds = setOf(TagId("tag-admin")),
            revision = revision,
        ),
        Task(
            id = TaskId("task-interviews"),
            workspaceId = workspaceId,
            projectId = researchProject.id,
            statusId = statusId(researchProject.id, SemanticStatus.BLOCKED),
            semanticStatus = SemanticStatus.BLOCKED,
            title = "Synthesize interview notes",
            description = "Group evidence by decision and preserve source links.",
            priority = Priority.MEDIUM,
            due = moment(2026, 7, 27, 11),
            estimate = Duration.ofHours(3),
            dependencyIds = setOf(TaskId("task-transcript")),
            blockedBy = setOf(TaskId("task-transcript")),
            revision = revision,
        ),
        Task(
            id = TaskId("task-transcript"),
            workspaceId = workspaceId,
            projectId = researchProject.id,
            statusId = statusId(researchProject.id, SemanticStatus.PLANNED),
            semanticStatus = SemanticStatus.PLANNED,
            title = "Transcribe research interviews",
            description = "Prepare the source material before synthesis begins.",
            priority = Priority.MEDIUM,
            due = moment(2026, 7, 27, 9),
            estimate = Duration.ofHours(2),
            revision = revision,
        ),
        Task(
            id = TaskId("task-domain"),
            workspaceId = workspaceId,
            projectId = studioProject.id,
            statusId = planned,
            semanticStatus = SemanticStatus.PLANNED,
            title = "Point launch domain",
            priority = Priority.LOW,
            start = moment(2026, 7, 28, 9),
            due = moment(2026, 7, 28, 10),
            estimate = Duration.ofMinutes(30),
            revision = revision,
        ),
        Task(
            id = TaskId("task-review"),
            workspaceId = workspaceId,
            projectId = studioProject.id,
            statusId = done,
            semanticStatus = SemanticStatus.COMPLETED,
            title = "Review final case studies",
            priority = Priority.MEDIUM,
            completedAt = Instant.parse("2026-07-25T08:10:00Z"),
            revision = revision,
        ),
    )

    val tags = listOf(
        Tag(TagId("tag-deep-work"), workspaceId, "Deep work"),
        Tag(TagId("tag-admin"), workspaceId, "Admin"),
    )

    // Task-owned fixture note deliberately avoids `tasks.first()` (task-proposal):
    // InMemoryNoteCommandTest seeds InMemoryVaultRepository() from this snapshot and
    // relies on tasks.first() starting with zero notes.
    val notes = listOf(
        Note(
            id = NoteId("note-review-feedback"),
            taskId = TaskId("task-review"),
            projectId = null,
            body = "Client approved both case studies; archive the redline copies.",
            createdAt = Instant.parse("2026-07-20T09:00:00Z"),
            editedAt = null,
            revision = revision,
        ),
        Note(
            id = NoteId("note-studio-kickoff"),
            taskId = null,
            projectId = studioProject.id,
            body = "Kickoff deck approved; keep the brand palette locked for launch.",
            createdAt = Instant.parse("2026-07-15T13:00:00Z"),
            editedAt = null,
            revision = revision,
        ),
    )

    val milestones = listOf(
        Milestone(
            MilestoneId("milestone-launch"),
            studioProject.id,
            "Public launch",
            LocalDate.of(2026, 8, 14),
        ),
        Milestone(
            MilestoneId("milestone-file"),
            taxProject.id,
            "Filing ready",
            LocalDate.of(2026, 7, 31),
        ),
    )

    val snapshot = WorkspaceSnapshot(
        home = HomeSnapshot(
            today = LocalDate.of(2026, 7, 26),
            focusTasks = tasks.filterNot(Task::isCompleted).take(3),
            upcomingTasks = tasks.filter { it.start != null || it.due != null }.takeLast(3),
            projects = listOf(studioProject, taxProject, researchProject),
            activeTimer = ActiveTimerSnapshot(
                entryId = TimeEntryId("timer-active"),
                taskId = tasks.first().id,
                taskTitle = tasks.first().title,
                projectName = studioProject.name,
                startedAt = Instant.parse("2026-07-26T07:37:00Z"),
                elapsed = Duration.ofMinutes(46).plusSeconds(12),
            ),
            overdueCount = 1,
        ),
        tasks = tasks,
        projects = listOf(studioProject, taxProject, researchProject),
        workflowStatuses = workflowStatuses,
        milestones = milestones,
        tags = tags,
        notes = notes,
    )
}
