package app.opentasks.core.data.export

import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.Note
import app.opentasks.core.model.NoteId
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TimeEntry
import app.opentasks.core.model.TimeEntryId
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.WorkspaceId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCsvWriterTest {

    private val zone = ZoneId.of("Europe/London")
    private val writer = WorkspaceCsvWriter(zone)
    private val workspaceId = WorkspaceId("workspace-1")
    private val revision = Revision(DeviceId("device-1"), 1_722_000_000_000, 0)
    private val ukDateTime = DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm", Locale.UK)
    private val ukDate = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)

    @Test
    fun tasksHeaderIsExact() {
        val out = StringBuilder()
        writer.write(CsvTable.TASKS, snapshot(), out)

        assertEquals(
            "id,title,project,status,priority,start_display,start_iso,due_display,due_iso," +
                "completed_display,completed_iso,estimate_minutes,tags,description",
            headerOf(out),
        )
    }

    @Test
    fun projectsHeaderIsExact() {
        val out = StringBuilder()
        writer.write(CsvTable.PROJECTS, snapshot(), out)

        assertEquals(
            "id,name,summary,health,due_display,due_iso,completed_tasks,total_tasks",
            headerOf(out),
        )
    }

    @Test
    fun timeEntriesHeaderIsExact() {
        val out = StringBuilder()
        writer.write(CsvTable.TIME_ENTRIES, snapshot(), out)

        assertEquals(
            "id,task_id,task_title,started_display,started_iso,stopped_display,stopped_iso," +
                "duration_minutes,note",
            headerOf(out),
        )
    }

    @Test
    fun notesHeaderIsExact() {
        val out = StringBuilder()
        writer.write(CsvTable.NOTES, snapshot(), out)

        assertEquals(
            "id,owner_type,owner_id,owner_title,created_display,created_iso,edited_iso,body",
            headerOf(out),
        )
    }

    @Test
    fun formulaTitleIsNeutralisedAndQuoted() {
        val task = task(
            id = "task-1",
            title = "=HYPERLINK(\"x\"),\"b\"",
        )
        val out = StringBuilder()
        writer.write(CsvTable.TASKS, snapshot(tasks = listOf(task)), out)

        val expectedTitleField = "\"'=HYPERLINK(\"\"x\"\"),\"\"b\"\"\""
        val expectedRow = listOf(
            "task-1", expectedTitleField, "", "BACKLOG", "NONE",
            "", "", "", "", "", "", "", "", "",
        ).joinToString(",")
        assertEquals(expectedRow, dataLine(out, 0))
    }

    @Test
    fun tabPrefixedTitleIsNeutralised() {
        val task = task(
            id = "task-1",
            title = "\t=SUM(A1:A9)",
        )
        val out = StringBuilder()
        writer.write(CsvTable.TASKS, snapshot(tasks = listOf(task)), out)

        assertEquals("'\t=SUM(A1:A9)", dataLine(out, 0).split(",")[1])
    }

    @Test
    fun formulaNeutralisationPreservesLeadingApostropheCount() {
        val out = StringBuilder()
        writer.write(
            CsvTable.TASKS,
            snapshot(
                tasks = listOf(
                    task(id = "task-1", title = "=SUM(A1)"),
                    task(id = "task-2", title = "'=SUM(A1)"),
                    task(id = "task-3", title = "''=SUM(A1)"),
                ),
            ),
            out,
        )

        assertEquals("'=SUM(A1)", dataLine(out, 0).split(",")[1])
        assertEquals("'''=SUM(A1)", dataLine(out, 1).split(",")[1])
        assertEquals("'''''=SUM(A1)", dataLine(out, 2).split(",")[1])
    }

    @Test
    fun rowsEndWithCrlf() {
        val project = Project(
            id = ProjectId("project-1"),
            workspaceId = workspaceId,
            name = "Studio refresh",
            summary = "Refresh",
            status = ProjectHealth.ON_TRACK,
            dueDate = null,
            completedTasks = 1,
            totalTasks = 2,
        )
        val out = StringBuilder()
        writer.write(CsvTable.PROJECTS, snapshot(projects = listOf(project)), out)

        val expected = "id,name,summary,health,due_display,due_iso,completed_tasks,total_tasks\r\n" +
            "project-1,Studio refresh,Refresh,ON_TRACK,,,1,2\r\n"
        assertEquals(expected, out.toString())
    }

    @Test
    fun multiLineNoteBodyStaysOneQuotedField() {
        val project = Project(
            id = ProjectId("project-1"),
            workspaceId = workspaceId,
            name = "Studio refresh",
            summary = "",
            status = ProjectHealth.ON_TRACK,
            dueDate = null,
            completedTasks = 0,
            totalTasks = 0,
        )
        val createdAt = Instant.parse("2026-08-04T09:00:00Z")
        val note = Note(
            id = NoteId("note-1"),
            taskId = null,
            projectId = project.id,
            body = "Line one\nLine two",
            createdAt = createdAt,
            editedAt = null,
            revision = revision,
        )
        val out = StringBuilder()
        writer.write(
            CsvTable.NOTES,
            snapshot(projects = listOf(project), notes = listOf(note)),
            out,
        )

        val lines = out.toString().split("\r\n")
        // The header, one data record that itself embeds a bare \n (so it does
        // not split further on the \r\n separator), and the trailing empty
        // string produced by the final CRLF.
        assertEquals(3, lines.size)
        assertEquals("", lines[2])
        val expectedDisplay = ukDateTime.format(createdAt.atZone(zone))
        val expectedRow = listOf(
            "note-1",
            "project",
            "project-1",
            "Studio refresh",
            expectedDisplay,
            "2026-08-04T09:00:00Z",
            "",
            "\"Line one\nLine two\"",
        ).joinToString(",")
        assertEquals(expectedRow, lines[1])
    }

    @Test
    fun binTaskIsExcludedFromExport() {
        val active = task(id = "task-active", title = "Active task")
        val binned = task(
            id = "task-binned",
            title = "Binned task",
            deletedAt = Instant.parse("2026-08-01T00:00:00Z"),
        )
        val out = StringBuilder()
        writer.write(CsvTable.TASKS, snapshot(tasks = listOf(active, binned)), out)

        val lines = out.toString().trimEnd('\r', '\n').split("\r\n")
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("task-active,"))
    }

    @Test
    fun ukAndIsoStartColumnsAgreeOnSameMoment() {
        val momentZone = ZoneId.of("Asia/Bangkok")
        val zonedDateTime = ZonedDateTime.of(2026, 8, 10, 14, 30, 0, 0, momentZone)
        val moment = ZonedMoment(zonedDateTime.toInstant(), momentZone.id)
        val withMoment = task(id = "task-1", title = "Zoned task", start = moment)
        val out = StringBuilder()
        writer.write(CsvTable.TASKS, snapshot(tasks = listOf(withMoment)), out)

        val fields = dataLine(out, 0).split(",")
        val expectedDisplay = ukDateTime.format(zonedDateTime)
        val expectedIso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zonedDateTime)
        assertEquals(expectedDisplay, fields[5])
        assertEquals(expectedIso, fields[6])
    }

    @Test
    fun projectDueDateFormatsAsDateOnlyInBothColumns() {
        val project = Project(
            id = ProjectId("project-1"),
            workspaceId = workspaceId,
            name = "Studio",
            summary = "",
            status = ProjectHealth.AT_RISK,
            dueDate = LocalDate.of(2026, 8, 14),
            completedTasks = 3,
            totalTasks = 5,
        )
        val out = StringBuilder()
        writer.write(CsvTable.PROJECTS, snapshot(projects = listOf(project)), out)

        val expected = listOf(
            "project-1", "Studio", "", "AT_RISK", "14 August 2026", "2026-08-14", "3", "5",
        ).joinToString(",")
        assertEquals(expected, dataLine(out, 0))
    }

    @Test
    fun activeTimeEntryLeavesStoppedAndDurationEmpty() {
        val entryTask = task(id = "task-1", title = "Write report")
        val entry = TimeEntry(
            id = TimeEntryId("entry-1"),
            taskId = entryTask.id,
            deviceId = DeviceId("device-1"),
            startedAt = Instant.parse("2026-08-04T09:00:00Z"),
            stoppedAt = null,
        )
        val out = StringBuilder()
        writer.write(
            CsvTable.TIME_ENTRIES,
            snapshot(tasks = listOf(entryTask), timeEntries = listOf(entry)),
            out,
        )

        val expectedStartedDisplay = ukDateTime.format(entry.startedAt.atZone(zone))
        val expected = listOf(
            "entry-1", "task-1", "Write report", expectedStartedDisplay,
            "2026-08-04T09:00:00Z", "", "", "", "",
        ).joinToString(",")
        assertEquals(expected, dataLine(out, 0))
    }

    @Test
    fun completedTimeEntryComputesDurationMinutes() {
        val entryTask = task(id = "task-1", title = "Write report")
        val entry = TimeEntry(
            id = TimeEntryId("entry-1"),
            taskId = entryTask.id,
            deviceId = DeviceId("device-1"),
            startedAt = Instant.parse("2026-08-04T09:00:00Z"),
            stoppedAt = Instant.parse("2026-08-04T10:15:00Z"),
        )
        val out = StringBuilder()
        writer.write(
            CsvTable.TIME_ENTRIES,
            snapshot(tasks = listOf(entryTask), timeEntries = listOf(entry)),
            out,
        )

        assertEquals("75", dataLine(out, 0).split(",")[7])
    }

    @Test
    fun taskTagsEscapeSeparatorAndBackslash() {
        val tagA = Tag(TagId("tag-a"), workspaceId, "ops;urgent")
        val tagB = Tag(TagId("tag-b"), workspaceId, "path\\name")
        val tagged = task(
            id = "task-1",
            title = "Tagged",
            tagIds = setOf(tagB.id, tagA.id),
        )
        val out = StringBuilder()
        writer.write(
            CsvTable.TASKS,
            snapshot(tasks = listOf(tagged), tags = listOf(tagA, tagB)),
            out,
        )

        assertEquals("ops\\;urgent;path\\\\name", dataLine(out, 0).split(",")[12])
    }

    @Test
    fun taskProjectAndStatusNamesAreResolved() {
        val project = Project(
            id = ProjectId("project-1"),
            workspaceId = workspaceId,
            name = "Studio refresh",
            summary = "",
            status = ProjectHealth.ON_TRACK,
            dueDate = null,
            completedTasks = 0,
            totalTasks = 0,
        )
        val status = WorkflowStatus(
            id = WorkflowStatusId("status-1"),
            projectId = project.id,
            name = "In progress",
            semanticStatus = SemanticStatus.STARTED,
            rank = "a0",
        )
        val withProject = task(
            id = "task-1",
            title = "Task",
            projectId = project.id,
            statusId = status.id,
            semanticStatus = SemanticStatus.STARTED,
        )
        val out = StringBuilder()
        writer.write(
            CsvTable.TASKS,
            snapshot(
                tasks = listOf(withProject),
                projects = listOf(project),
                workflowStatuses = listOf(status),
            ),
            out,
        )

        val fields = dataLine(out, 0).split(",")
        assertEquals("Studio refresh", fields[2])
        assertEquals("In progress", fields[3])
    }

    private fun headerOf(out: StringBuilder): String = out.toString().substringBefore("\r\n")

    private fun dataLine(out: StringBuilder, index: Int): String =
        out.toString().trimEnd('\r', '\n').split("\r\n")[index + 1]

    private fun task(
        id: String,
        title: String,
        projectId: ProjectId? = null,
        statusId: WorkflowStatusId = WorkflowStatusId("status-1"),
        semanticStatus: SemanticStatus = SemanticStatus.BACKLOG,
        start: ZonedMoment? = null,
        deletedAt: Instant? = null,
        tagIds: Set<TagId> = emptySet(),
    ): Task = Task(
        id = TaskId(id),
        workspaceId = workspaceId,
        projectId = projectId,
        statusId = statusId,
        semanticStatus = semanticStatus,
        title = title,
        start = start,
        deletedAt = deletedAt,
        tagIds = tagIds,
        revision = revision,
    )

    private fun snapshot(
        tasks: List<Task> = emptyList(),
        projects: List<Project> = emptyList(),
        workflowStatuses: List<WorkflowStatus> = emptyList(),
        tags: List<Tag> = emptyList(),
        timeEntries: List<TimeEntry> = emptyList(),
        notes: List<Note> = emptyList(),
    ): WorkspaceSnapshot = WorkspaceSnapshot(
        home = HomeSnapshot(
            today = LocalDate.of(2026, 8, 4),
            focusTasks = emptyList(),
            upcomingTasks = emptyList(),
            projects = emptyList(),
            activeTimer = null,
            overdueCount = 0,
        ),
        tasks = tasks,
        projects = projects,
        workflowStatuses = workflowStatuses,
        milestones = emptyList(),
        tags = tags,
        timeEntries = timeEntries,
        notes = notes,
    )
}
