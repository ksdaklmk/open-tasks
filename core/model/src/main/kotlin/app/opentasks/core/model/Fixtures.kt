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

    val backlog = WorkflowStatusId("status-backlog")
    val planned = WorkflowStatusId("status-planned")
    val started = WorkflowStatusId("status-started")
    val blocked = WorkflowStatusId("status-blocked")
    val done = WorkflowStatusId("status-done")

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

    val workflowStatuses = listOf(
        WorkflowStatus(
            id = backlog,
            projectId = studioProject.id,
            name = "Backlog",
            semanticStatus = SemanticStatus.BACKLOG,
            rank = "a0",
        ),
        WorkflowStatus(
            id = planned,
            projectId = studioProject.id,
            name = "Planned",
            semanticStatus = SemanticStatus.PLANNED,
            rank = "a1",
        ),
        WorkflowStatus(
            id = started,
            projectId = studioProject.id,
            name = "In progress",
            semanticStatus = SemanticStatus.STARTED,
            rank = "a2",
        ),
        WorkflowStatus(
            id = blocked,
            projectId = studioProject.id,
            name = "Blocked",
            semanticStatus = SemanticStatus.BLOCKED,
            rank = "a3",
        ),
        WorkflowStatus(
            id = done,
            projectId = studioProject.id,
            name = "Done",
            semanticStatus = SemanticStatus.COMPLETED,
            rank = "a4",
        ),
    )

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
            statusId = planned,
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
            statusId = blocked,
            semanticStatus = SemanticStatus.BLOCKED,
            title = "Synthesize interview notes",
            description = "Group evidence by decision and preserve source links.",
            priority = Priority.MEDIUM,
            due = moment(2026, 7, 27, 11),
            estimate = Duration.ofHours(3),
            blockedBy = setOf(TaskId("task-transcript")),
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
            syncState = SyncState.LocalOnly,
            overdueCount = 1,
        ),
        tasks = tasks,
        projects = listOf(studioProject, taxProject, researchProject),
        workflowStatuses = workflowStatuses,
        milestones = milestones,
        tags = tags,
    )
}
