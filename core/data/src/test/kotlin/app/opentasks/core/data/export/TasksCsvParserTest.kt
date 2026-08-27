package app.opentasks.core.data.export

import app.opentasks.core.model.DeviceId
import app.opentasks.core.domain.ImportedTaskRow
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.Priority
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
import app.opentasks.core.model.WorkspaceId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksCsvParserTest {
    private val workspaceId = WorkspaceId("workspace-csv")
    private val revision = Revision(DeviceId("device-csv"), 1_786_262_400_000, 0)

    @Test
    fun quotedMultilineDescriptionParsesAsOneRow() {
        val result = parsed(
            csv(row(title = "Write brief", description = "Line one\nLine two, with comma")),
        )

        assertEquals(1, result.rows.size)
        assertEquals("Line one\nLine two, with comma", result.rows.single().description)
    }

    @Test
    fun formulaNeutralisationRoundTripsDistinctLeadingApostrophes() {
        val titles = listOf("=SUM(A1)", "'=SUM(A1)", "''=SUM(A1)")
        val snapshot = snapshot(tasks = titles.mapIndexed { index, title -> task(index, title) })
        val out = StringBuilder()

        WorkspaceCsvWriter(ZoneId.of("UTC")).write(CsvTable.TASKS, snapshot, out)

        assertEquals(titles, parsed(out.toString()).rows.map { it.title })
    }

    @Test
    fun carriageReturnAndLineFeedFormulaPrefixesRoundTripLiterally() {
        val descriptions = listOf("\r=CMD()", "\n+SUM(A1)")
        val out = StringBuilder()

        WorkspaceCsvWriter(ZoneId.of("UTC")).write(
            CsvTable.TASKS,
            snapshot(
                tasks = descriptions.mapIndexed { index, description ->
                    task(index, "Task $index", description = description)
                },
            ),
            out,
        )

        assertEquals(descriptions, parsed(out.toString()).rows.map { it.description })
    }

    @Test
    fun escapedTagSeparatorsAndBackslashesRoundTripDistinctNames() {
        val tags = listOf(
            Tag(TagId("tag-semicolon"), workspaceId, "ops;urgent"),
            Tag(TagId("tag-backslash"), workspaceId, "path\\name"),
        )
        val source = task(0, "Tagged", tagIds = tags.mapTo(linkedSetOf()) { it.id })
        val out = StringBuilder()

        WorkspaceCsvWriter(ZoneId.of("UTC")).write(
            CsvTable.TASKS,
            snapshot(tasks = listOf(source), tags = tags),
            out,
        )

        assertEquals(listOf("ops;urgent", "path\\name"), parsed(out.toString()).rows.single().tagNames)
    }

    @Test
    fun historicalSecondPrecisionOffsetRoundTripsAndPreviews() {
        val historical = task(
            index = 0,
            title = "Historical offset",
            start = ZonedMoment(Instant.parse("1900-01-01T00:00:00Z"), "Europe/Paris"),
        )
        val out = StringBuilder()

        WorkspaceCsvWriter(ZoneId.of("UTC")).write(
            CsvTable.TASKS,
            snapshot(tasks = listOf(historical)),
            out,
        )

        val parsed = parsed(out.toString())
        assertEquals("+00:09:21", parsed.rows.single().start?.zoneId)
        val preview = previewTasksImport(parsed.rows, snapshot(tasks = emptyList()))
        assertTrue(preview.toString(), preview is CsvImportPreviewResult.Ready)
    }

    @Test
    fun invalidTagEscapeReportsItsDataRow() {
        val result = parseTasksCsv(csv(row(title = "Bad tag", tags = "bad\\x")).toByteArray())

        assertMalformed(result, 1)
    }

    @Test
    fun ownHeaderMustMatchExactly() {
        val result = parseTasksCsv("wrong,header\r\n".toByteArray())

        assertMalformed(result, 0)
    }

    @Test
    fun badDueOnThirdDataRowReportsThreeWithoutPartialRows() {
        val result = parseTasksCsv(
            csv(
                row(title = "One"),
                row(title = "Two"),
                row(title = "Three", dueIso = "not-an-offset-time"),
            ).toByteArray(),
        )

        assertMalformed(result, 3)
    }

    @Test
    fun malformedUtf8FailsClosed() {
        val source = (header + "\r\n").toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)

        assertMalformed(parseTasksCsv(source), 0)
    }

    @Test
    fun byteLimitFailsClosedBeforeDecode() {
        assertMalformed(parseTasksCsv(ByteArray(MAX_TASKS_CSV_BYTES + 1)), 0)
    }

    @Test
    fun rowLimitFailsAtTheFirstExcessDataRow() {
        val source = csv(*Array(5_001) { row(title = "Task") })

        assertMalformed(parseTasksCsv(source.toByteArray()), 5_001)
    }

    @Test
    fun malformedRecordGrammarReportsTheOffendingRow() {
        val malformedRows = listOf(
            "only,thirteen,fields,,,,,,,,,," to 1,
            "id,bad\"quote,,,,,,,,,,,," to 1,
            "id,\"closed\"tail,,,,,,,,,,,," to 1,
            "id,\"unclosed,,,,,,,,,,,," to 1,
        )

        malformedRows.forEach { (badRow, expectedRow) ->
            assertMalformed(parseTasksCsv("$header\r\n$badRow\r\n".toByteArray()), expectedRow)
        }
    }

    @Test
    fun domainBoundsReportTheOffendingRow() {
        val malformedRows = listOf(
            row(title = " "),
            row(title = "t".repeat(241)),
            row(title = "Task", description = "d".repeat(20_001)),
            row(title = "Task", project = "p".repeat(121)),
            row(title = "Task", status = "s".repeat(65)),
            row(title = "Task", tags = "t".repeat(65)),
            row(title = "Task", tags = (1..51).joinToString(";") { "tag-$it" }),
            row(title = "Task", estimate = "0"),
            row(title = "Task", estimate = "9223372036854775807"),
        )

        malformedRows.forEach { malformed ->
            assertMalformed(parseTasksCsv(csv(malformed).toByteArray()), 1)
        }
    }

    @Test
    fun invalidPriorityStartAndCompletionReportTheirRows() {
        listOf(
            row(title = "Task", priority = "LATER"),
            row(title = "Task", startIso = "2026-08-09T10:00:00"),
            row(title = "Task", completedIso = "not-an-instant"),
        ).forEach { malformed ->
            assertMalformed(parseTasksCsv(csv(malformed).toByteArray()), 1)
        }
    }

    @Test
    fun keystoneTasksExportRoundTripsEveryOwnedFieldInOrder() {
        val project = Project(
            id = ProjectId("project-1"),
            workspaceId = workspaceId,
            name = "Launch",
            summary = "",
            status = ProjectHealth.ON_TRACK,
            dueDate = null,
            completedTasks = 0,
            totalTasks = 2,
        )
        val statuses = WorkflowStatus.defaults(project.id)
        val tags = listOf(
            Tag(TagId("tag-1"), workspaceId, "ops;urgent"),
            Tag(TagId("tag-2"), workspaceId, "path\\name"),
        )
        val first = task(
            index = 1,
            title = "=Prepare, launch",
            projectId = project.id,
            status = statuses[2],
            priority = Priority.URGENT,
            start = ZonedMoment(Instant.parse("2026-08-09T03:00:00Z"), "Asia/Bangkok"),
            due = ZonedMoment(Instant.parse("2026-08-10T16:30:00Z"), "America/New_York"),
            estimate = Duration.ofMinutes(95),
            tagIds = tags.mapTo(linkedSetOf()) { it.id },
            description = "Line one\nLine two",
        )
        val second = task(
            index = 2,
            title = "Finished",
            projectId = project.id,
            status = statuses.last(),
            completedAt = Instant.parse("2026-08-08T12:34:56Z"),
        )
        val binned = task(3, "Binned").copy(deletedAt = Instant.parse("2026-08-01T00:00:00Z"))
        val out = StringBuilder()

        WorkspaceCsvWriter(ZoneId.of("Europe/London")).write(
            CsvTable.TASKS,
            snapshot(
                tasks = listOf(first, second, binned),
                projects = listOf(project),
                statuses = statuses,
                tags = tags,
            ),
            out,
        )

        val rows = parsed(out.toString()).rows
        assertEquals(2, rows.size)
        assertEquals(first.title, rows[0].title)
        assertEquals(first.description, rows[0].description)
        assertEquals(project.name, rows[0].projectName)
        assertEquals(statuses[2].name, rows[0].statusName)
        assertEquals(first.priority, rows[0].priority)
        assertEquals(first.start?.instant, rows[0].start?.instant)
        assertEquals("+07:00", rows[0].start?.zoneId)
        assertEquals(first.due?.instant, rows[0].due?.instant)
        assertEquals("-04:00", rows[0].due?.zoneId)
        assertEquals(first.completedAt, rows[0].completedAt)
        assertEquals(first.estimate?.toMinutes(), rows[0].estimateMinutes)
        assertEquals(tags.map { it.name }, rows[0].tagNames)
        assertNull(rows[0].statusSemantic)
        assertEquals(second.completedAt, rows[1].completedAt)
        assertEquals(statuses.last().name, rows[1].statusName)
        assertNull(rows[1].statusSemantic)
    }

    @Test
    fun exactlyFiveHundredNewProjectsAreAccepted() {
        val result = previewTasksImport(
            List(500) { index -> importedRow(index + 1, project = "Project $index") },
            snapshot(tasks = emptyList()),
        )

        assertTrue(result.toString(), result is CsvImportPreviewResult.Ready)
        assertEquals(500, (result as CsvImportPreviewResult.Ready).summary.newProjectCount)
    }

    @Test
    fun fiveHundredAndFirstNewProjectIsRejected() {
        val result = previewTasksImport(
            List(501) { index -> importedRow(index + 1, project = "Project $index") },
            snapshot(tasks = emptyList()),
        )

        assertTrue(result.toString(), result is CsvImportPreviewResult.Invalid)
        result as CsvImportPreviewResult.Invalid
        assertEquals(501, result.rowNumber)
        assertEquals(RejectionReason.IMPORT_TOO_LARGE, result.reason)
    }

    @Test
    fun exactlyOneThousandNewTagsAreAccepted() {
        val result = previewTasksImport(
            rowsWithUniqueTags(1_000),
            snapshot(tasks = emptyList()),
        )

        assertTrue(result.toString(), result is CsvImportPreviewResult.Ready)
        assertEquals(1_000, (result as CsvImportPreviewResult.Ready).summary.newTagCount)
    }

    @Test
    fun oneThousandAndFirstNewTagIsRejected() {
        val result = previewTasksImport(
            rowsWithUniqueTags(1_001),
            snapshot(tasks = emptyList()),
        )

        assertTrue(result.toString(), result is CsvImportPreviewResult.Invalid)
        result as CsvImportPreviewResult.Invalid
        assertEquals(21, result.rowNumber)
        assertEquals(RejectionReason.IMPORT_TOO_LARGE, result.reason)
    }

    @Test
    fun fiveThousandUniqueRowsResolveAtTheExistingRowLimit() {
        val result = previewTasksImport(
            List(5_000) { index -> importedRow(index + 1) },
            snapshot(tasks = emptyList()),
        )

        assertTrue(result.toString(), result is CsvImportPreviewResult.Ready)
        assertEquals(5_000, (result as CsvImportPreviewResult.Ready).summary.taskCount)
    }

    @Test
    fun exactProposedNamesReuseWhileCaseOnlyNamesStillCollide() {
        val exact = previewTasksImport(
            listOf(
                importedRow(1, project = "Launch", tags = listOf("Ops")),
                importedRow(2, project = "Launch", tags = listOf("Ops")),
            ),
            snapshot(tasks = emptyList()),
        ) as CsvImportPreviewResult.Ready
        assertEquals(1, exact.summary.newProjectCount)
        assertEquals(1, exact.summary.newTagCount)

        val collision = previewTasksImport(
            listOf(
                importedRow(1, project = "Launch", tags = listOf("Ops")),
                importedRow(2, project = "launch", tags = listOf("ops")),
            ),
            snapshot(tasks = emptyList()),
        )
        assertTrue(collision.toString(), collision is CsvImportPreviewResult.Invalid)
        assertEquals(
            RejectionReason.IMPORT_NAME_COLLISION,
            (collision as CsvImportPreviewResult.Invalid).reason,
        )
    }

    private fun parsed(source: String): CsvParseResult.Parsed {
        val result = parseTasksCsv(source.toByteArray())
        assertTrue(result.toString(), result is CsvParseResult.Parsed)
        return result as CsvParseResult.Parsed
    }

    private fun assertMalformed(result: CsvParseResult, rowNumber: Int) {
        assertTrue(result.toString(), result is CsvParseResult.Malformed)
        assertEquals(rowNumber, (result as CsvParseResult.Malformed).rowNumber)
    }

    private fun csv(vararg rows: List<String>): String = buildString {
        append(header).append("\r\n")
        rows.forEach { fields ->
            append(fields.joinToString(",") { field ->
                if (field.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
                    "\"${field.replace("\"", "\"\"")}\""
                } else {
                    field
                }
            })
            append("\r\n")
        }
    }

    private fun row(
        title: String,
        project: String = "",
        status: String = "",
        priority: String = "",
        startIso: String = "",
        dueIso: String = "",
        completedIso: String = "",
        estimate: String = "",
        tags: String = "",
        description: String = "",
    ): List<String> = listOf(
        "ignored-id", title, project, status, priority, "ignored", startIso, "ignored", dueIso,
        "ignored", completedIso, estimate, tags, description,
    )

    private fun task(
        index: Int,
        title: String,
        projectId: ProjectId? = null,
        status: WorkflowStatus = WorkflowStatus.defaults(projectId).first(),
        priority: Priority = Priority.NONE,
        start: ZonedMoment? = null,
        due: ZonedMoment? = null,
        estimate: Duration? = null,
        tagIds: Set<TagId> = emptySet(),
        description: String = "",
        completedAt: Instant? = null,
    ) = Task(
        id = TaskId("task-$index"),
        workspaceId = workspaceId,
        projectId = projectId,
        statusId = status.id,
        semanticStatus = status.semanticStatus,
        title = title,
        description = description,
        priority = priority,
        start = start,
        due = due,
        estimate = estimate,
        tagIds = tagIds,
        completedAt = completedAt,
        revision = revision,
    )

    private fun importedRow(
        rowNumber: Int,
        project: String? = null,
        tags: List<String> = emptyList(),
    ) = ImportedTaskRow(
        sourceRowNumber = rowNumber,
        title = "Task $rowNumber",
        projectName = project,
        statusName = null,
        priority = Priority.NONE,
        start = null,
        due = null,
        completedAt = null,
        estimateMinutes = null,
        tagNames = tags,
        description = "",
    )

    private fun rowsWithUniqueTags(count: Int): List<ImportedTaskRow> =
        (0 until count).chunked(50).mapIndexed { rowIndex, tags ->
            importedRow(
                rowNumber = rowIndex + 1,
                tags = tags.map { "Tag $it" },
            )
        }

    private fun snapshot(
        tasks: List<Task>,
        projects: List<Project> = emptyList(),
        statuses: List<WorkflowStatus> = WorkflowStatus.defaults(null),
        tags: List<Tag> = emptyList(),
    ) = WorkspaceSnapshot(
        home = HomeSnapshot(
            today = LocalDate.of(2026, 8, 9),
            focusTasks = emptyList(),
            upcomingTasks = emptyList(),
            projects = projects,
            activeTimer = null,
            overdueCount = 0,
        ),
        tasks = tasks,
        projects = projects,
        workflowStatuses = statuses,
        milestones = emptyList(),
        tags = tags,
    )

    private companion object {
        const val header = "id,title,project,status,priority,start_display,start_iso,due_display," +
            "due_iso,completed_display,completed_iso,estimate_minutes,tags,description"
    }
}
