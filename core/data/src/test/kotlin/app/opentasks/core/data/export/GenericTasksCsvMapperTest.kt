package app.opentasks.core.data.export

import app.opentasks.core.model.TaskCsvEstimateUnit
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.TaskCsvDateOrder
import app.opentasks.core.model.TaskCsvBlockingIssue
import app.opentasks.core.model.TaskCsvField
import app.opentasks.core.model.TaskCsvMapping
import app.opentasks.core.model.TaskCsvPriorityChoice
import app.opentasks.core.model.TaskCsvStatusChoice
import app.opentasks.core.model.TaskCsvTagMode
import app.opentasks.core.model.TaskCsvWarning
import app.opentasks.core.model.TaskCsvWarningReason
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkspaceId
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericTasksCsvMapperTest {
    private val workspaceId = WorkspaceId("workspace-generic-csv")

    @Test
    fun genericParserAcceptsBomQuotesAndPadsShortRows() {
        val source = "\uFEFFTask Name,Project,Notes\r\n" +
            "One,Launch,\"Line one\nLine two\"\r\n" +
            "Two\r\n"

        val parsed = parseGenericTasksCsv(source.toByteArray())
            as GenericTasksCsvParseResult.Parsed

        assertEquals(listOf("Task Name", "Project", "Notes"), parsed.document.headers)
        assertEquals(1, parsed.document.rows[0].sourceRowNumber)
        assertEquals(listOf("One", "Launch", "Line one\nLine two"), parsed.document.rows[0].cells)
        assertEquals(listOf("Two", "", ""), parsed.document.rows[1].cells)
        val lf = parseGenericTasksCsv("Title\nThree\n".toByteArray())
            as GenericTasksCsvParseResult.Parsed
        assertEquals("Three", lf.document.rows.single().cells.single())
    }

    @Test
    fun genericParserRejectsARecordWiderThanItsHeader() {
        val result = parseGenericTasksCsv("Title,Project\r\nOne,A,extra\r\n".toByteArray())
            as GenericTasksCsvParseResult.Failed

        assertEquals(1, result.rowNumber)
        assertEquals(GenericTasksCsvFailure.ROW_WIDER_THAN_HEADER, result.reason)
    }

    @Test
    fun genericParserRejectsMissingHeaderBareCrAndUnclosedQuote() {
        assertEquals(
            GenericTasksCsvFailure.MISSING_HEADER,
            (parseGenericTasksCsv("\r\n".toByteArray())
                as GenericTasksCsvParseResult.Failed).reason,
        )
        assertEquals(
            GenericTasksCsvFailure.MALFORMED,
            (parseGenericTasksCsv("Title\rOne".toByteArray())
                as GenericTasksCsvParseResult.Failed).reason,
        )
        val unclosed = parseGenericTasksCsv("Title\n\"One".toByteArray())
            as GenericTasksCsvParseResult.Failed
        assertEquals(GenericTasksCsvFailure.MALFORMED, unclosed.reason)
        assertEquals(1, unclosed.rowNumber)
    }

    @Test
    fun genericParserEnforcesEncodingByteRowAndColumnBounds() {
        val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)
        assertEquals(
            GenericTasksCsvFailure.INVALID_UTF8,
            (parseGenericTasksCsv(invalidUtf8) as GenericTasksCsvParseResult.Failed).reason,
        )
        assertEquals(
            GenericTasksCsvFailure.TOO_LARGE,
            (parseGenericTasksCsv(ByteArray(MAX_TASKS_CSV_BYTES + 1))
                as GenericTasksCsvParseResult.Failed).reason,
        )
        val wideHeader = (1..101).joinToString(",") { "Column $it" } + "\r\n"
        assertEquals(
            GenericTasksCsvFailure.TOO_MANY_COLUMNS,
            (parseGenericTasksCsv(wideHeader.toByteArray())
                as GenericTasksCsvParseResult.Failed).reason,
        )
        val tooManyRows = buildString {
            append("Title\r\n")
            repeat(5_001) { append("Task\r\n") }
        }
        assertEquals(
            GenericTasksCsvFailure.TOO_MANY_ROWS,
            (parseGenericTasksCsv(tooManyRows.toByteArray())
                as GenericTasksCsvParseResult.Failed).reason,
        )
    }

    @Test
    fun suggestionsCoverEveryApprovedFieldWithoutVendorProfiles() {
        val document = parsedGeneric(
            "Task Name,List,State,Priority,Start Date,Due Date,Completed At," +
                "Estimate Hours,Labels,Notes\r\n" +
                "Ship,Launch,Doing,3,24/08/2026,25/08/2026,,2,work|urgent,Text\r\n",
        )

        val mapping = suggestTaskCsvMapping(document)

        assertEquals(0, mapping.columns[TaskCsvField.TITLE])
        assertEquals(1, mapping.columns[TaskCsvField.PROJECT])
        assertEquals(2, mapping.columns[TaskCsvField.STATUS])
        assertEquals(3, mapping.columns[TaskCsvField.PRIORITY])
        assertEquals(4, mapping.columns[TaskCsvField.START])
        assertEquals(5, mapping.columns[TaskCsvField.DUE])
        assertEquals(6, mapping.columns[TaskCsvField.COMPLETION])
        assertEquals(7, mapping.columns[TaskCsvField.ESTIMATE])
        assertEquals(8, mapping.columns[TaskCsvField.TAGS])
        assertEquals(9, mapping.columns[TaskCsvField.DESCRIPTION])
        assertEquals(TaskCsvEstimateUnit.HOURS, mapping.estimateUnit)
        assertEquals(TaskCsvTagMode.PIPE, mapping.tagMode)
        assertEquals(TaskCsvStatusChoice.IN_PROGRESS, mapping.statusChoices["Doing"])
        assertEquals(null, mapping.priorityChoices["3"])
    }

    @Test
    fun ambiguousHeadersRemainUnmappedAndSamplesStayBounded() {
        val document = parsedGeneric(
            "Task Name,Task-Name,Unknown\r\n" +
                "One,Project A,${"x".repeat(121)}\r\n" +
                "Two,Project B,second\r\n" +
                "Three,Project C,third\r\n" +
                "Four,Project D,fourth\r\n",
        )

        val columns = describeGenericTasksCsv(document)
        val mapping = suggestTaskCsvMapping(document)

        assertTrue(mapping.columns.isEmpty())
        assertEquals(listOf("One", "Two", "Three"), columns[0].samples)
        assertEquals(3, columns[2].samples.size)
        assertEquals(120, columns[2].samples.first().length)
        assertTrue(columns[2].samples.first().endsWith("…"))
    }

    @Test
    fun reviewConvertsTheFullApprovedFieldSet() {
        val document = parsedGeneric(
            "Title,Project,Status,Priority,Start,Due,Completed,Estimate,Tags,Notes\r\n" +
                "Ship release,Launch,Doing,High,24/08/2026," +
                "25/08/2026 18:30,,2,work|urgent,Migration note\r\n",
        )
        val mapping = TaskCsvMapping(
            columns = TaskCsvField.entries.associateWith { it.ordinal },
            statusChoices = mapOf("Doing" to TaskCsvStatusChoice.IN_PROGRESS),
            priorityChoices = mapOf("High" to TaskCsvPriorityChoice.HIGH),
            dateOrder = TaskCsvDateOrder.DAY_MONTH_YEAR,
            estimateUnit = TaskCsvEstimateUnit.HOURS,
            tagMode = TaskCsvTagMode.PIPE,
        )

        val review = reviewGenericTasksCsv(
            document = document,
            mapping = mapping,
            target = emptyTaskCsvTarget(),
            zone = ZoneId.of("Asia/Bangkok"),
            completionFallback = Instant.parse("2026-08-24T12:00:00Z"),
        )

        assertTrue(review.blockingIssues.isEmpty())
        val row = review.rows.single()
        assertEquals("Ship release", row.title)
        assertEquals("Launch", row.projectName)
        assertEquals("In progress", row.statusName)
        assertEquals(SemanticStatus.STARTED, row.statusSemantic)
        assertEquals(Priority.HIGH, row.priority)
        assertEquals(Instant.parse("2026-08-24T02:00:00Z"), row.start?.instant)
        assertEquals("+07:00", row.start?.zoneId)
        assertEquals(Instant.parse("2026-08-25T11:30:00Z"), row.due?.instant)
        assertEquals(120L, row.estimateMinutes)
        assertEquals(listOf("work", "urgent"), row.tagNames)
        assertEquals(listOf("work", "urgent"), review.tagSamples)
        assertEquals("Migration note", row.description)
        assertEquals(1, review.newProjectCount)
        assertEquals(2, review.newTagCount)
    }

    @Test
    fun invalidTitlesSkipRowsAndInvalidOptionalValuesAreCounted() {
        val document = parsedGeneric(
            "Title,Project,Due,Estimate,Tags,Notes\r\n" +
                ",Launch,not-a-date,-1,valid|${"x".repeat(65)},Text\r\n" +
                "Kept,${"p".repeat(121)},not-a-date,-1," +
                "valid|${"x".repeat(65)},${"d".repeat(20_001)}\r\n",
        )
        val mapping = TaskCsvMapping(
            columns = mapOf(
                TaskCsvField.TITLE to 0,
                TaskCsvField.PROJECT to 1,
                TaskCsvField.DUE to 2,
                TaskCsvField.ESTIMATE to 3,
                TaskCsvField.TAGS to 4,
                TaskCsvField.DESCRIPTION to 5,
            ),
            estimateUnit = TaskCsvEstimateUnit.MINUTES,
            tagMode = TaskCsvTagMode.PIPE,
        )

        val review = reviewGenericTasksCsv(
            document,
            mapping,
            emptyTaskCsvTarget(),
            ZoneId.of("UTC"),
            Instant.parse("2026-08-24T12:00:00Z"),
        )

        assertEquals(1, review.rows.size)
        assertEquals(1, review.skippedTaskCount)
        assertEquals(5, review.omittedValueCount)
        assertTrue(review.warnings.any { it.reason == TaskCsvWarningReason.TITLE_BLANK })
        assertEquals(null, review.rows.single().projectName)
        assertEquals(null, review.rows.single().due)
        assertEquals(null, review.rows.single().estimateMinutes)
        assertEquals(listOf("valid"), review.rows.single().tagNames)
        assertEquals("", review.rows.single().description)
    }

    @Test
    fun doneWithoutTimeUsesFallbackAndExplicitCompletionOverridesOpenStatus() {
        val document = parsedGeneric(
            "Title,Status,Completed\r\n" +
                "Inferred,Done,\r\n" +
                "Override,Open,yes\r\n",
        )
        val mapping = TaskCsvMapping(
            columns = mapOf(
                TaskCsvField.TITLE to 0,
                TaskCsvField.STATUS to 1,
                TaskCsvField.COMPLETION to 2,
            ),
            statusChoices = mapOf(
                "Done" to TaskCsvStatusChoice.DONE,
                "Open" to TaskCsvStatusChoice.BACKLOG,
            ),
        )
        val fallback = Instant.parse("2026-08-24T12:34:56Z")

        val review = reviewGenericTasksCsv(
            document,
            mapping,
            emptyTaskCsvTarget(),
            ZoneId.of("UTC"),
            fallback,
        )

        assertTrue(review.rows.all { it.completedAt == fallback })
        assertTrue(review.rows.all { it.statusSemantic == SemanticStatus.COMPLETED })
        assertTrue(review.warnings.any { it.reason == TaskCsvWarningReason.COMPLETION_INFERRED })
        assertTrue(review.warnings.any {
            it.reason == TaskCsvWarningReason.COMPLETION_OVERRIDES_STATUS
        })
    }

    @Test
    fun explicitCompletionWithoutSourceStatusDoesNotFabricateAnOverrideWarning() {
        val review = reviewGenericTasksCsv(
            parsedGeneric(
                "Title,Completed\r\n" +
                    "Finished,2026-08-24T12:34:56Z\r\n",
            ),
            TaskCsvMapping(
                columns = mapOf(
                    TaskCsvField.TITLE to 0,
                    TaskCsvField.COMPLETION to 1,
                ),
            ),
            emptyTaskCsvTarget(),
            ZoneId.of("UTC"),
            Instant.EPOCH,
        )

        assertEquals(1, review.rows.size)
        assertEquals(SemanticStatus.COMPLETED, review.rows.single().statusSemantic)
        assertTrue(review.warnings.isEmpty())
    }

    @Test
    fun doneFallbackCountsAnInvalidCompletionCellOnlyOnce() {
        val target = emptyTaskCsvTarget().copy(
            workflowStatuses = WorkflowStatus.defaults(null).filter {
                it.semanticStatus != SemanticStatus.COMPLETED
            },
        )
        val review = reviewGenericTasksCsv(
            parsedGeneric("Title,Status,Completed\r\nOne,Done,invalid\r\n"),
            TaskCsvMapping(
                columns = mapOf(
                    TaskCsvField.TITLE to 0,
                    TaskCsvField.STATUS to 1,
                    TaskCsvField.COMPLETION to 2,
                ),
                statusChoices = mapOf("Done" to TaskCsvStatusChoice.DONE),
            ),
            target,
            ZoneId.of("UTC"),
            Instant.EPOCH,
        )

        assertEquals(2, review.omittedValueCount)
        assertEquals(
            1,
            review.warnings.count {
                it.reason == TaskCsvWarningReason.COMPLETION_OMITTED
            },
        )
    }

    @Test
    fun completedFallbackStillReportsTheStatusFallback() {
        val target = emptyTaskCsvTarget().copy(
            workflowStatuses = WorkflowStatus.defaults(null).filter {
                it.semanticStatus != SemanticStatus.COMPLETED
            },
        )
        val review = reviewGenericTasksCsv(
            parsedGeneric("Title,Status,Completed\r\nOne,Open,yes\r\n"),
            TaskCsvMapping(
                columns = mapOf(
                    TaskCsvField.TITLE to 0,
                    TaskCsvField.STATUS to 1,
                    TaskCsvField.COMPLETION to 2,
                ),
                statusChoices = mapOf("Open" to TaskCsvStatusChoice.BACKLOG),
            ),
            target,
            ZoneId.of("UTC"),
            Instant.EPOCH,
        )

        assertEquals(2, review.omittedValueCount)
        assertTrue(review.warnings.any {
            it.reason == TaskCsvWarningReason.STATUS_FALLBACK
        })
    }

    @Test
    fun dateOnlyValuesReportTheirVisibleDefaultTimes() {
        val document = parsedGeneric(
            "Title,Start,Due,Completed\r\n" +
                "Defaults,24/08/2026,25/08/2026,26/08/2026\r\n",
        )
        val mapping = TaskCsvMapping(
            columns = mapOf(
                TaskCsvField.TITLE to 0,
                TaskCsvField.START to 1,
                TaskCsvField.DUE to 2,
                TaskCsvField.COMPLETION to 3,
            ),
            dateOrder = TaskCsvDateOrder.DAY_MONTH_YEAR,
        )

        val review = reviewGenericTasksCsv(
            document,
            mapping,
            emptyTaskCsvTarget(),
            ZoneId.of("Asia/Bangkok"),
            Instant.EPOCH,
        )

        assertTrue(review.warnings.any {
            it.reason == TaskCsvWarningReason.START_TIME_INFERRED
        })
        assertTrue(review.warnings.any {
            it.reason == TaskCsvWarningReason.START_ZONE_INFERRED
        })
        assertTrue(review.warnings.any {
            it.reason == TaskCsvWarningReason.DUE_TIME_INFERRED
        })
        assertTrue(review.warnings.any {
            it.reason == TaskCsvWarningReason.DUE_ZONE_INFERRED
        })
        assertTrue(review.warnings.any {
            it.reason == TaskCsvWarningReason.COMPLETION_TIME_INFERRED
        })
        assertTrue(review.warnings.any {
            it.reason == TaskCsvWarningReason.COMPLETION_ZONE_INFERRED
        })
        assertEquals(0, review.omittedValueCount)
    }

    @Test
    fun projectAndTagCaseVariantsReuseCanonicalTargetNames() {
        val target = TaskCsvTarget(
            projects = listOf(project("Existing Project")),
            workflowStatuses = WorkflowStatus.defaults(null) +
                WorkflowStatus.defaults(ProjectId("existing-project")),
            tags = listOf(Tag(TagId("tag-work"), workspaceId, "Work")),
        )
        val document = parsedGeneric(
            "Title,Project,Tags\r\nOne,existing project,work|New\r\n" +
                "Two,EXISTING PROJECT,WORK|new\r\n",
        )
        val mapping = TaskCsvMapping(
            columns = mapOf(
                TaskCsvField.TITLE to 0,
                TaskCsvField.PROJECT to 1,
                TaskCsvField.TAGS to 2,
            ),
            tagMode = TaskCsvTagMode.PIPE,
        )

        val review = reviewGenericTasksCsv(
            document,
            mapping,
            target,
            ZoneId.of("UTC"),
            Instant.EPOCH,
        )

        assertEquals(listOf("Existing Project", "Existing Project"), review.rows.map {
            it.projectName
        })
        assertTrue(review.rows.all { it.tagNames == listOf("Work", "New") })
        assertEquals(0, review.newProjectCount)
        assertEquals(1, review.newTagCount)
        assertTrue(review.warnings.any { it.reason == TaskCsvWarningReason.PROJECT_CASE_MERGED })
        assertTrue(review.warnings.any { it.reason == TaskCsvWarningReason.TAG_CASE_MERGED })
    }

    @Test
    fun unresolvedChoicesAndZeroValidRowsBlockConfirmation() {
        val document = parsedGeneric(
            "Title,Status,Priority,Due,Estimate,Tags\r\n" +
                ",3,2,03/04/2026,10,a;b\r\n",
        )
        val mapping = TaskCsvMapping(
            columns = mapOf(
                TaskCsvField.TITLE to 0,
                TaskCsvField.STATUS to 1,
                TaskCsvField.PRIORITY to 2,
                TaskCsvField.DUE to 3,
                TaskCsvField.ESTIMATE to 4,
                TaskCsvField.TAGS to 5,
            ),
        )

        val review = reviewGenericTasksCsv(
            document,
            mapping,
            emptyTaskCsvTarget(),
            ZoneId.of("UTC"),
            Instant.EPOCH,
        )

        assertEquals(
            setOf(
                TaskCsvBlockingIssue.STATUS_CHOICES_REQUIRED,
                TaskCsvBlockingIssue.PRIORITY_CHOICES_REQUIRED,
                TaskCsvBlockingIssue.DATE_ORDER_REQUIRED,
                TaskCsvBlockingIssue.ESTIMATE_UNIT_REQUIRED,
                TaskCsvBlockingIssue.TAG_MODE_REQUIRED,
                TaskCsvBlockingIssue.NO_VALID_TASKS,
            ),
            review.blockingIssues,
        )
    }

    @Test
    fun invalidOrDuplicateColumnMappingsBlockWithoutReadingOutsideTheTable() {
        val document = parsedGeneric("Title,Project\r\nOne,Launch\r\n")
        val mappings = listOf(
            TaskCsvMapping(
                columns = mapOf(
                    TaskCsvField.TITLE to 0,
                    TaskCsvField.PROJECT to 0,
                ),
            ),
            TaskCsvMapping(columns = mapOf(TaskCsvField.TITLE to 99)),
        )

        mappings.forEach { mapping ->
            val review = reviewGenericTasksCsv(
                document,
                mapping,
                emptyTaskCsvTarget(),
                ZoneId.of("UTC"),
                Instant.EPOCH,
            )
            assertTrue(TaskCsvBlockingIssue.COLUMN_MAPPING_INVALID in review.blockingIssues)
            assertTrue(review.rows.isEmpty())
        }
    }

    @Test
    fun projectAndTagCreationLimitsBlockOnlyAboveTheApprovedCeilings() {
        fun projectReview(count: Int): GenericTasksCsvReview {
            val source = buildString {
                append("Title,Project\r\n")
                repeat(count) { index -> append("Task $index,Project $index\r\n") }
            }
            return reviewGenericTasksCsv(
                parsedGeneric(source),
                TaskCsvMapping(
                    columns = mapOf(
                        TaskCsvField.TITLE to 0,
                        TaskCsvField.PROJECT to 1,
                    ),
                ),
                emptyTaskCsvTarget(),
                ZoneId.of("UTC"),
                Instant.EPOCH,
            )
        }
        fun tagReview(count: Int): GenericTasksCsvReview {
            val source = buildString {
                append("Title,Tags\r\n")
                (0 until count).chunked(50).forEachIndexed { row, tags ->
                    append("Task $row,")
                    append(tags.joinToString("|") { "Tag $it" })
                    append("\r\n")
                }
            }
            return reviewGenericTasksCsv(
                parsedGeneric(source),
                TaskCsvMapping(
                    columns = mapOf(
                        TaskCsvField.TITLE to 0,
                        TaskCsvField.TAGS to 1,
                    ),
                    tagMode = TaskCsvTagMode.PIPE,
                ),
                emptyTaskCsvTarget(),
                ZoneId.of("UTC"),
                Instant.EPOCH,
            )
        }

        assertEquals(500, projectReview(500).newProjectCount)
        assertTrue(
            TaskCsvBlockingIssue.TOO_MANY_NEW_PROJECTS in
                projectReview(501).blockingIssues,
        )
        assertEquals(1_000, tagReview(1_000).newTagCount)
        assertTrue(
            TaskCsvBlockingIssue.TOO_MANY_NEW_TAGS in
                tagReview(1_001).blockingIssues,
        )
    }

    @Test
    fun tagOmissionsReportTheSpecificTokenLoss() {
        val values = buildList {
            add("Work")
            add("")
            add("work")
            add("x".repeat(65))
            repeat(51) { add("Tag $it") }
        }
        val document = parsedGeneric(
            "Title,Tags\r\nOne,${values.joinToString("|")}\r\n",
        )
        val review = reviewGenericTasksCsv(
            document,
            TaskCsvMapping(
                columns = mapOf(
                    TaskCsvField.TITLE to 0,
                    TaskCsvField.TAGS to 1,
                ),
                tagMode = TaskCsvTagMode.PIPE,
            ),
            emptyTaskCsvTarget(),
            ZoneId.of("UTC"),
            Instant.EPOCH,
        )

        val reasons = review.warnings.map(TaskCsvWarning::reason).toSet()
        assertTrue(TaskCsvWarningReason.TAG_BLANK_OMITTED in reasons)
        assertTrue(TaskCsvWarningReason.TAG_DUPLICATE_OMITTED in reasons)
        assertTrue(TaskCsvWarningReason.TAG_TOO_LONG_OMITTED in reasons)
        assertTrue(TaskCsvWarningReason.TAG_LIMIT_OMITTED in reasons)
    }

    private fun parsedGeneric(source: String): GenericTasksCsvDocument =
        (parseGenericTasksCsv(source.toByteArray()) as GenericTasksCsvParseResult.Parsed).document

    private fun project(name: String) = Project(
        id = ProjectId("existing-project"),
        workspaceId = workspaceId,
        name = name,
        summary = "",
        status = ProjectHealth.ON_TRACK,
        dueDate = null,
        completedTasks = 0,
        totalTasks = 0,
    )
}
