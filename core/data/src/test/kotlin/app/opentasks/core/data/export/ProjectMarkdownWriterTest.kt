package app.opentasks.core.data.export

import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.Milestone
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.WorkspaceId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectMarkdownWriterTest {
    private val writer = ProjectMarkdownWriter()
    private val workspaceId = WorkspaceId("workspace-1")
    private val projectId = ProjectId("project-1")
    private val revision = Revision(DeviceId("device-1"), 1_722_000_000_000, 0)

    @Test
    fun writesProjectDetailsStatusesTasksAndChecklist() {
        val backlog = status("backlog", "Backlog", "a0", SemanticStatus.BACKLOG)
        val done = status("done", "Done", "a1", SemanticStatus.COMPLETED)
        val admin = Tag(TagId("admin"), workspaceId, "admin")
        val task = task(
            id = "task-open",
            status = backlog,
            title = "Draft plan",
            due = moment(2026, 8, 12, 14, 30, "Asia/Bangkok"),
            tagIds = setOf(admin.id),
            checklist = listOf(
                ChecklistItem("check-1", "Ask team", completed = true, rank = "a0"),
                ChecklistItem("check-2", "Share draft", completed = false, rank = "a1"),
            ),
        )
        val completed = task(
            id = "task-done",
            status = done,
            title = "Choose date",
            semanticStatus = SemanticStatus.COMPLETED,
        )
        val out = StringBuilder()

        writer.write(
            projectId,
            snapshot(
                tasks = listOf(task, completed),
                workflowStatuses = listOf(done, backlog),
                milestones = listOf(
                    Milestone(
                        MilestoneId("milestone-1"),
                        projectId,
                        "Launch",
                        LocalDate.of(2026, 8, 20),
                    ),
                ),
                tags = listOf(admin),
            ),
            out,
        )

        assertEquals(
            "# Studio refresh\n\n" +
                "Refresh the design system\n\n" +
                "Due 14 August 2026\n\n" +
                "## Milestones\n\n" +
                "- [ ] Launch — 20 August 2026\n\n" +
                "## Backlog\n\n" +
                "- [ ] Draft plan — due 12 August 2026 14:30 \\#admin\n" +
                "  - [x] Ask team\n" +
                "  - [ ] Share draft\n\n" +
                "## Done\n\n" +
                "- [x] Choose date\n",
            out.toString(),
        )
    }

    @Test
    fun excludesBinnedTasks() {
        val status = status("backlog", "Backlog", "a0", SemanticStatus.BACKLOG)
        val out = StringBuilder()

        writer.write(
            projectId,
            snapshot(
                tasks = listOf(
                    task("active", status, "Active"),
                    task(
                        "binned",
                        status,
                        "Binned",
                        deletedAt = Instant.parse("2026-08-01T00:00:00Z"),
                    ),
                ),
                workflowStatuses = listOf(status),
            ),
            out,
        )

        assertEquals("# Studio refresh\n\nRefresh the design system\n\nDue 14 August 2026\n\n## Backlog\n\n- [ ] Active\n", out.toString())
    }

    @Test
    fun omitsStatusesWithNoTasks() {
        val empty = status("empty", "Empty", "a0", SemanticStatus.BACKLOG)
        val active = status("active", "Active", "a1", SemanticStatus.STARTED)
        val out = StringBuilder()

        writer.write(
            projectId,
            snapshot(
                tasks = listOf(task("task-1", active, "Do this", SemanticStatus.STARTED)),
                workflowStatuses = listOf(empty, active),
            ),
            out,
        )

        assertEquals("# Studio refresh\n\nRefresh the design system\n\nDue 14 August 2026\n\n## Active\n\n- [ ] Do this\n", out.toString())
    }

    @Test
    fun escapesUserTextAndNormalisesLineBreaks() {
        val status = status("status", "# status\nnext", "a0", SemanticStatus.BACKLOG)
        val tag = Tag(TagId("tag-1"), workspaceId, "tag|name")
        val out = StringBuilder()

        writer.write(
            projectId,
            snapshot(
                project = project("# heading\rsummary"),
                tasks = listOf(
                    task(
                        "task-1",
                        status,
                        "* task `code`\r\nnext",
                        checklist = listOf(
                            ChecklistItem("check-1", "item # one\nnext", false, "a0"),
                        ),
                        tagIds = setOf(tag.id),
                    ),
                ),
                workflowStatuses = listOf(status),
                tags = listOf(tag),
            ),
            out,
        )

        assertEquals(
            "# \\# heading summary\n\n" +
                "Refresh the design system\n\n" +
                "Due 14 August 2026\n\n" +
                "## \\# status next\n\n" +
                "- [ ] \\* task \\`code\\` next \\#tag\\|name\n" +
                "  - [ ] item \\# one next\n",
            out.toString(),
        )
        assertFalse(out.contains('\r'))
        assertTrue(out.endsWith("\n"))
    }

    private fun project(name: String = "Studio refresh") = Project(
        id = projectId,
        workspaceId = workspaceId,
        name = name,
        summary = "Refresh the design system",
        status = ProjectHealth.ON_TRACK,
        dueDate = LocalDate.of(2026, 8, 14),
        completedTasks = 0,
        totalTasks = 0,
    )

    private fun status(
        id: String,
        name: String,
        rank: String,
        semanticStatus: SemanticStatus,
    ) = WorkflowStatus(WorkflowStatusId(id), projectId, name, semanticStatus, rank)

    private fun task(
        id: String,
        status: WorkflowStatus,
        title: String,
        semanticStatus: SemanticStatus = status.semanticStatus,
        due: ZonedMoment? = null,
        checklist: List<ChecklistItem> = emptyList(),
        tagIds: Set<TagId> = emptySet(),
        deletedAt: Instant? = null,
    ) = Task(
        id = TaskId(id),
        workspaceId = workspaceId,
        projectId = projectId,
        statusId = status.id,
        semanticStatus = semanticStatus,
        title = title,
        due = due,
        checklist = checklist,
        tagIds = tagIds,
        deletedAt = deletedAt,
        revision = revision,
    )

    private fun moment(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: String): ZonedMoment {
        val dateTime = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.of(zone))
        return ZonedMoment(dateTime.toInstant(), zone)
    }

    private fun snapshot(
        project: Project = project(),
        tasks: List<Task> = emptyList(),
        workflowStatuses: List<WorkflowStatus> = emptyList(),
        milestones: List<Milestone> = emptyList(),
        tags: List<Tag> = emptyList(),
    ) = WorkspaceSnapshot(
        home = HomeSnapshot(
            today = LocalDate.of(2026, 8, 4),
            focusTasks = emptyList(),
            upcomingTasks = emptyList(),
            projects = emptyList(),
            activeTimer = null,
            overdueCount = 0,
        ),
        tasks = tasks,
        projects = listOf(project),
        workflowStatuses = workflowStatuses,
        milestones = milestones,
        tags = tags,
    )
}
