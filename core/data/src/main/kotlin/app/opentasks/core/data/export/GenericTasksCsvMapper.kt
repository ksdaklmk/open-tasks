package app.opentasks.core.data.export

import app.opentasks.core.domain.ImportedTaskRow
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TaskCsvEstimateUnit
import app.opentasks.core.model.TaskCsvBlockingIssue
import app.opentasks.core.model.TaskCsvDateOrder
import app.opentasks.core.model.TaskCsvField
import app.opentasks.core.model.TaskCsvMapping
import app.opentasks.core.model.TaskCsvPriorityChoice
import app.opentasks.core.model.TaskCsvStatusChoice
import app.opentasks.core.model.TaskCsvTagMode
import app.opentasks.core.model.TaskCsvWarning
import app.opentasks.core.model.TaskCsvWarningReason
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

data class GenericTasksCsvRow(
    val sourceRowNumber: Int,
    val cells: List<String>,
)

data class GenericTasksCsvDocument(
    val headers: List<String>,
    val rows: List<GenericTasksCsvRow>,
)

enum class GenericTasksCsvFailure {
    TOO_LARGE,
    INVALID_UTF8,
    MALFORMED,
    TOO_MANY_ROWS,
    TOO_MANY_COLUMNS,
    MISSING_HEADER,
    ROW_WIDER_THAN_HEADER,
}

sealed interface GenericTasksCsvParseResult {
    data class Parsed(val document: GenericTasksCsvDocument) : GenericTasksCsvParseResult
    data class Failed(
        val rowNumber: Int?,
        val reason: GenericTasksCsvFailure,
    ) : GenericTasksCsvParseResult
}

fun parseGenericTasksCsv(bytes: ByteArray): GenericTasksCsvParseResult {
    if (bytes.size > MAX_TASKS_CSV_BYTES) {
        return GenericTasksCsvParseResult.Failed(null, GenericTasksCsvFailure.TOO_LARGE)
    }
    val decoded = when (val result = decodeCsvUtf8(bytes)) {
        is CsvTextResult.Ready -> result.text.removePrefix("\uFEFF")
        is CsvTextResult.Failed -> return GenericTasksCsvParseResult.Failed(
            null,
            GenericTasksCsvFailure.INVALID_UTF8,
        )
    }
    val records = when (
        val result = readCsvRecords(
            decoded,
            maxDataRows = MAX_IMPORT_ROWS,
            maxColumns = 100,
        )
    ) {
        is CsvRecordResult.Ready -> result.records
        is CsvRecordResult.Failed -> return GenericTasksCsvParseResult.Failed(
            result.rowNumber.takeIf { it > 0 },
            when (result.kind) {
                CsvRecordFailureKind.INVALID_UTF8 -> GenericTasksCsvFailure.INVALID_UTF8
                CsvRecordFailureKind.MALFORMED -> GenericTasksCsvFailure.MALFORMED
                CsvRecordFailureKind.TOO_MANY_ROWS -> GenericTasksCsvFailure.TOO_MANY_ROWS
                CsvRecordFailureKind.TOO_MANY_COLUMNS -> GenericTasksCsvFailure.TOO_MANY_COLUMNS
            },
        )
    }
    val headers = records.firstOrNull()
        ?.takeIf { row -> row.isNotEmpty() && row.any(String::isNotBlank) }
        ?: return GenericTasksCsvParseResult.Failed(
            null,
            GenericTasksCsvFailure.MISSING_HEADER,
        )
    val rows = records.drop(1).mapIndexed { index, cells ->
        if (cells.size > headers.size) {
            return GenericTasksCsvParseResult.Failed(
                index + 1,
                GenericTasksCsvFailure.ROW_WIDER_THAN_HEADER,
            )
        }
        GenericTasksCsvRow(
            sourceRowNumber = index + 1,
            cells = cells + List(headers.size - cells.size) { "" },
        )
    }
    return GenericTasksCsvParseResult.Parsed(GenericTasksCsvDocument(headers, rows))
}

data class GenericTasksCsvColumn(
    val index: Int,
    val header: String,
    val samples: List<String>,
)

fun describeGenericTasksCsv(document: GenericTasksCsvDocument): List<GenericTasksCsvColumn> =
    document.headers.mapIndexed { index, header ->
        GenericTasksCsvColumn(
            index = index,
            header = header,
            samples = document.rows.asSequence()
                .map { it.cells[index].trim() }
                .filter(String::isNotEmpty)
                .distinct()
                .take(3)
                .map(::displaySample)
                .toList(),
        )
    }

fun suggestTaskCsvMapping(
    document: GenericTasksCsvDocument,
    columns: Map<TaskCsvField, Int>? = null,
): TaskCsvMapping {
    val selected = columns ?: suggestColumns(document.headers)
    val statusValues = distinctValues(document, selected[TaskCsvField.STATUS])
    val priorityValues = distinctValues(document, selected[TaskCsvField.PRIORITY])
    return TaskCsvMapping(
        columns = selected,
        statusChoices = statusValues.mapNotNull { value ->
            suggestStatus(value)?.let { value to it }
        }.toMap(),
        priorityChoices = priorityValues.mapNotNull { value ->
            suggestPriority(value)?.let { value to it }
        }.toMap(),
        estimateUnit = selected[TaskCsvField.ESTIMATE]
            ?.let(document.headers::get)
            ?.let(::suggestEstimateUnit),
        tagMode = selected[TaskCsvField.TAGS]
            ?.let { index -> suggestTagMode(distinctValues(document, index)) },
    )
}

data class TaskCsvTarget(
    val projects: List<Project>,
    val workflowStatuses: List<WorkflowStatus>,
    val tags: List<Tag>,
)

data class GenericTasksCsvReview(
    val rows: List<ImportedTaskRow>,
    val warnings: List<TaskCsvWarning>,
    val blockingIssues: Set<TaskCsvBlockingIssue>,
    val blockingMessage: String?,
    val statusValues: List<String>,
    val priorityValues: List<String>,
    val ambiguousDatesPresent: Boolean,
    val estimateValuesPresent: Boolean,
    val tagValuesPresent: Boolean,
    val tagSamples: List<String>,
    val capturedZoneId: String,
    val skippedTaskCount: Int,
    val omittedValueCount: Int,
    val ignoredColumnIndexes: List<Int>,
    val newProjectCount: Int,
    val newTagCount: Int,
) {
    val canImport: Boolean
        get() = rows.isNotEmpty() && blockingIssues.isEmpty()
}

fun WorkspaceSnapshot.toTaskCsvTarget() = TaskCsvTarget(
    projects = projects.filter { it.archivedAt == null },
    workflowStatuses = workflowStatuses.filter { it.archivedAt == null },
    tags = tags,
)

fun emptyTaskCsvTarget() = TaskCsvTarget(
    projects = emptyList(),
    workflowStatuses = WorkflowStatus.defaults(null),
    tags = emptyList(),
)

fun reviewGenericTasksCsv(
    document: GenericTasksCsvDocument,
    mapping: TaskCsvMapping,
    target: TaskCsvTarget,
    zone: ZoneId,
    completionFallback: Instant,
): GenericTasksCsvReview {
    val mappedIndexes = mapping.columns.values
    if (
        mappedIndexes.any { it !in document.headers.indices } ||
        mappedIndexes.size != mappedIndexes.toSet().size
    ) {
        return blockedReview(
            document,
            mapping,
            zone,
            TaskCsvBlockingIssue.COLUMN_MAPPING_INVALID,
        )
    }
    if (TaskCsvField.TITLE !in mapping.columns) {
        return blockedReview(
            document,
            mapping,
            zone,
            TaskCsvBlockingIssue.TITLE_MAPPING_REQUIRED,
        )
    }

    val projectsByExact = linkedMapOf<String, Project>()
    val projectsByFolded = linkedMapOf<String, Project>()
    target.projects.forEach { project ->
        projectsByExact.putIfAbsent(project.name, project)
        projectsByFolded.putIfAbsent(project.name.folded(), project)
    }
    val tagsByExact = linkedMapOf<String, String>()
    val tagsByFolded = linkedMapOf<String, String>()
    target.tags.forEach { tag ->
        tagsByExact.putIfAbsent(tag.name, tag.name)
        tagsByFolded.putIfAbsent(tag.name.folded(), tag.name)
    }
    val proposedProjects = linkedMapOf<String, String>()
    val proposedTags = linkedMapOf<String, String>()
    val rows = mutableListOf<ImportedTaskRow>()
    val warnings = mutableListOf<TaskCsvWarning>()
    val blockers = linkedSetOf<TaskCsvBlockingIssue>()
    val statusValues = linkedSetOf<String>()
    val priorityValues = linkedSetOf<String>()
    var ambiguousDatesPresent = false
    var estimateValuesPresent = false
    var tagValuesPresent = false
    var skippedTaskCount = 0
    var omittedValueCount = 0
    var blockingMessage: String? = null

    document.rows.forEach { source ->
        if (source.cells.all(String::isBlank)) {
            skippedTaskCount++
            warnings += TaskCsvWarning(
                source.sourceRowNumber,
                null,
                TaskCsvWarningReason.EMPTY_ROW,
            )
            return@forEach
        }

        fun cell(field: TaskCsvField): String = mapping.columns[field]
            ?.let(source.cells::get)
            .orEmpty()

        val rawTitle = cell(TaskCsvField.TITLE)
        val rawProject = cell(TaskCsvField.PROJECT)
        val rawStatus = cell(TaskCsvField.STATUS).trim()
        val rawPriority = cell(TaskCsvField.PRIORITY).trim()
        val rawStart = cell(TaskCsvField.START).trim()
        val rawDue = cell(TaskCsvField.DUE).trim()
        val rawCompletion = cell(TaskCsvField.COMPLETION).trim()
        val rawEstimate = cell(TaskCsvField.ESTIMATE).trim()
        val rawTags = cell(TaskCsvField.TAGS)
        val rawDescription = cell(TaskCsvField.DESCRIPTION)

        if (rawStatus.isNotEmpty()) statusValues += rawStatus
        if (rawPriority.isNotEmpty()) priorityValues += rawPriority
        ambiguousDatesPresent = ambiguousDatesPresent ||
            listOf(rawStart, rawDue, rawCompletion).any(::isAmbiguousNumericDate)
        estimateValuesPresent = estimateValuesPresent || rawEstimate.isNotEmpty()
        tagValuesPresent = tagValuesPresent || rawTags.isNotEmpty()

        val title = rawTitle.trim()
        if (title.isBlank() || title.length > 240) {
            skippedTaskCount++
            warnings += TaskCsvWarning(
                source.sourceRowNumber,
                TaskCsvField.TITLE,
                if (title.isBlank()) {
                    TaskCsvWarningReason.TITLE_BLANK
                } else {
                    TaskCsvWarningReason.TITLE_TOO_LONG
                },
            )
            return@forEach
        }

        fun warn(field: TaskCsvField, reason: TaskCsvWarningReason) {
            warnings += TaskCsvWarning(source.sourceRowNumber, field, reason)
        }

        fun omit(field: TaskCsvField, reason: TaskCsvWarningReason) {
            omittedValueCount++
            warn(field, reason)
        }

        val projectText = rawProject.trim()
        val project = when {
            projectText.isEmpty() -> null
            projectText.length > 120 -> {
                omit(TaskCsvField.PROJECT, TaskCsvWarningReason.PROJECT_OMITTED)
                null
            }
            else -> {
                val folded = projectText.folded()
                val existing = projectsByExact[projectText] ?: projectsByFolded[folded]
                if (existing != null) {
                    if (existing.name != projectText) {
                        warn(TaskCsvField.PROJECT, TaskCsvWarningReason.PROJECT_CASE_MERGED)
                    }
                    ResolvedProject(existing.name, existing.id)
                } else {
                    val canonical = proposedProjects.getOrPut(folded) { projectText }
                    if (canonical != projectText) {
                        warn(TaskCsvField.PROJECT, TaskCsvWarningReason.PROJECT_CASE_MERGED)
                    }
                    ResolvedProject(canonical, null)
                }
            }
        }
        val availableStatuses = when {
            project?.existingId != null -> target.workflowStatuses.filter {
                it.projectId == project.existingId && it.archivedAt == null
            }
            project != null -> WorkflowStatus.defaults(ProjectId("preview:${project.name}"))
            else -> target.workflowStatuses.filter {
                it.projectId == null && it.archivedAt == null
            }
        }
        fun firstStatus(semantic: SemanticStatus): WorkflowStatus? = availableStatuses
            .filter { it.semanticStatus == semantic }
            .minByOrNull(WorkflowStatus::rank)

        val statusChoice = rawStatus.takeIf(String::isNotEmpty)
            ?.let(mapping.statusChoices::get)
        val requestedSemantic = when (statusChoice) {
            TaskCsvStatusChoice.IN_PROGRESS -> SemanticStatus.STARTED
            TaskCsvStatusChoice.DONE -> SemanticStatus.COMPLETED
            TaskCsvStatusChoice.BACKLOG,
            TaskCsvStatusChoice.IGNORE,
            null,
            -> SemanticStatus.BACKLOG
        }
        var statusLossCounted = false
        if (statusChoice == TaskCsvStatusChoice.IGNORE) {
            omit(TaskCsvField.STATUS, TaskCsvWarningReason.STATUS_OMITTED)
            statusLossCounted = true
        }
        var selectedStatus = firstStatus(requestedSemantic)
        var doneFellBack = false
        if (selectedStatus == null) {
            val backlog = firstStatus(SemanticStatus.BACKLOG)
            if (backlog == null) {
                blockers += TaskCsvBlockingIssue.TARGET_REJECTED
                if (blockingMessage == null) {
                    blockingMessage = "The target has no active Backlog status."
                }
            } else {
                selectedStatus = backlog
                if (requestedSemantic != SemanticStatus.BACKLOG) {
                    omit(TaskCsvField.STATUS, TaskCsvWarningReason.STATUS_FALLBACK)
                    statusLossCounted = true
                    doneFellBack = requestedSemantic == SemanticStatus.COMPLETED
                }
            }
        }
        var statusSemantic = selectedStatus?.semanticStatus ?: requestedSemantic

        val priority = when (
            rawPriority.takeIf(String::isNotEmpty)?.let(mapping.priorityChoices::get)
        ) {
            TaskCsvPriorityChoice.LOW -> Priority.LOW
            TaskCsvPriorityChoice.MEDIUM -> Priority.MEDIUM
            TaskCsvPriorityChoice.HIGH -> Priority.HIGH
            TaskCsvPriorityChoice.URGENT -> Priority.URGENT
            TaskCsvPriorityChoice.IGNORE -> {
                omit(TaskCsvField.PRIORITY, TaskCsvWarningReason.PRIORITY_OMITTED)
                Priority.NONE
            }
            TaskCsvPriorityChoice.NONE,
            null,
            -> Priority.NONE
        }

        fun resolveMoment(
            raw: String,
            field: TaskCsvField,
            dateOnlyTime: LocalTime,
            omittedReason: TaskCsvWarningReason,
            timeReason: TaskCsvWarningReason,
            zoneReason: TaskCsvWarningReason,
        ): ZonedMoment? {
            if (raw.isEmpty()) return null
            return when (val parsed = parseDateValue(raw, mapping.dateOrder)) {
                is ParsedDateValue.Offset -> parsed.value.toImportedMoment()
                is ParsedDateValue.LocalDateTimeValue -> {
                    warn(field, zoneReason)
                    parsed.value.toImportedMoment(zone)
                }
                is ParsedDateValue.Date -> {
                    warn(field, timeReason)
                    warn(field, zoneReason)
                    parsed.value.atTime(dateOnlyTime).toImportedMoment(zone)
                }
                ParsedDateValue.Ambiguous -> null
                ParsedDateValue.Invalid -> {
                    omit(field, omittedReason)
                    null
                }
            }
        }

        val start = resolveMoment(
            rawStart,
            TaskCsvField.START,
            START_DATE_ONLY_TIME,
            TaskCsvWarningReason.START_OMITTED,
            TaskCsvWarningReason.START_TIME_INFERRED,
            TaskCsvWarningReason.START_ZONE_INFERRED,
        )
        val due = resolveMoment(
            rawDue,
            TaskCsvField.DUE,
            DUE_DATE_ONLY_TIME,
            TaskCsvWarningReason.DUE_OMITTED,
            TaskCsvWarningReason.DUE_TIME_INFERRED,
            TaskCsvWarningReason.DUE_ZONE_INFERRED,
        )

        val completionToken = rawCompletion.lowercase(Locale.ROOT)
        val explicitOpen = completionToken in OPEN_COMPLETION_VALUES
        var completionAmbiguous = false
        var completionLossCounted = false
        fun omitCompletion() {
            if (!completionLossCounted) {
                omit(TaskCsvField.COMPLETION, TaskCsvWarningReason.COMPLETION_OMITTED)
                completionLossCounted = true
            }
        }
        var completedAt = when {
            completionToken in COMPLETED_VALUES -> {
                warn(TaskCsvField.COMPLETION, TaskCsvWarningReason.COMPLETION_INFERRED)
                completionFallback
            }
            explicitOpen || rawCompletion.isEmpty() -> null
            else -> when (val parsed = parseDateValue(rawCompletion, mapping.dateOrder)) {
                is ParsedDateValue.Offset -> parsed.value.toInstant()
                is ParsedDateValue.LocalDateTimeValue -> {
                    warn(
                        TaskCsvField.COMPLETION,
                        TaskCsvWarningReason.COMPLETION_ZONE_INFERRED,
                    )
                    parsed.value.atZone(zone).toInstant()
                }
                is ParsedDateValue.Date -> {
                    warn(
                        TaskCsvField.COMPLETION,
                        TaskCsvWarningReason.COMPLETION_TIME_INFERRED,
                    )
                    warn(
                        TaskCsvField.COMPLETION,
                        TaskCsvWarningReason.COMPLETION_ZONE_INFERRED,
                    )
                    parsed.value.atTime(COMPLETION_DATE_ONLY_TIME).atZone(zone).toInstant()
                }
                ParsedDateValue.Ambiguous -> {
                    completionAmbiguous = true
                    null
                }
                ParsedDateValue.Invalid -> {
                    omitCompletion()
                    null
                }
            }
        }

        if (doneFellBack) {
            omitCompletion()
            completedAt = null
            statusSemantic = SemanticStatus.BACKLOG
        } else if (explicitOpen && statusSemantic == SemanticStatus.COMPLETED) {
            warn(
                TaskCsvField.COMPLETION,
                TaskCsvWarningReason.COMPLETION_OVERRIDES_STATUS,
            )
            if (!statusLossCounted && rawStatus.isNotEmpty()) {
                omittedValueCount++
                statusLossCounted = true
            }
            selectedStatus = firstStatus(SemanticStatus.BACKLOG)
            if (selectedStatus == null) {
                blockers += TaskCsvBlockingIssue.TARGET_REJECTED
                if (blockingMessage == null) {
                    blockingMessage = "The target has no active Backlog status."
                }
            }
            statusSemantic = SemanticStatus.BACKLOG
        } else if (completedAt != null) {
            if (statusSemantic != SemanticStatus.COMPLETED && rawStatus.isNotEmpty()) {
                warn(
                    TaskCsvField.COMPLETION,
                    TaskCsvWarningReason.COMPLETION_OVERRIDES_STATUS,
                )
                if (!statusLossCounted) {
                    omittedValueCount++
                    statusLossCounted = true
                }
            }
            val completedStatus = firstStatus(SemanticStatus.COMPLETED)
            if (completedStatus == null) {
                val backlog = firstStatus(SemanticStatus.BACKLOG)
                if (!statusLossCounted) {
                    omittedValueCount++
                    statusLossCounted = true
                }
                warn(TaskCsvField.STATUS, TaskCsvWarningReason.STATUS_FALLBACK)
                omitCompletion()
                completedAt = null
                selectedStatus = backlog
                statusSemantic = SemanticStatus.BACKLOG
                if (backlog == null) {
                    blockers += TaskCsvBlockingIssue.TARGET_REJECTED
                    if (blockingMessage == null) {
                        blockingMessage = "The target has no active Backlog status."
                    }
                }
            } else {
                selectedStatus = completedStatus
                statusSemantic = SemanticStatus.COMPLETED
            }
        } else if (statusSemantic == SemanticStatus.COMPLETED && !completionAmbiguous) {
            completedAt = completionFallback
            warn(TaskCsvField.COMPLETION, TaskCsvWarningReason.COMPLETION_INFERRED)
        }

        val estimateMinutes = if (rawEstimate.isEmpty() || mapping.estimateUnit == null) {
            null
        } else {
            val value = rawEstimate.toLongOrNull()
            val minutes = if (value == null || value <= 0) {
                null
            } else {
                try {
                    if (mapping.estimateUnit == TaskCsvEstimateUnit.HOURS) {
                        Math.multiplyExact(value, 60L)
                    } else {
                        value
                    }
                } catch (_: ArithmeticException) {
                    null
                }
            }
            if (minutes == null || minutes !in 1..MAX_GENERIC_ESTIMATE_MINUTES) {
                omit(TaskCsvField.ESTIMATE, TaskCsvWarningReason.ESTIMATE_OMITTED)
                null
            } else {
                minutes
            }
        }

        val tagNames = mutableListOf<String>()
        val tagMode = mapping.tagMode
        if (rawTags.isNotEmpty() && tagMode != null) {
            val seen = linkedSetOf<String>()
            val tokens = tagMode.separator?.let { rawTags.split(it) } ?: listOf(rawTags)
            tokens.forEach { token ->
                val name = token.trim()
                val folded = name.folded()
                when {
                    name.isEmpty() -> omit(
                        TaskCsvField.TAGS,
                        TaskCsvWarningReason.TAG_BLANK_OMITTED,
                    )
                    name.length > 64 -> omit(
                        TaskCsvField.TAGS,
                        TaskCsvWarningReason.TAG_TOO_LONG_OMITTED,
                    )
                    folded in seen -> omit(
                        TaskCsvField.TAGS,
                        TaskCsvWarningReason.TAG_DUPLICATE_OMITTED,
                    )
                    tagNames.size >= 50 -> omit(
                        TaskCsvField.TAGS,
                        TaskCsvWarningReason.TAG_LIMIT_OMITTED,
                    )
                    else -> {
                        seen += folded
                        val canonical = tagsByExact[name] ?: tagsByFolded[folded]
                            ?: proposedTags.getOrPut(folded) { name }
                        if (canonical != name) {
                            warn(TaskCsvField.TAGS, TaskCsvWarningReason.TAG_CASE_MERGED)
                        }
                        tagNames += canonical
                    }
                }
            }
        }

        val description = if (rawDescription.length <= 20_000) {
            rawDescription
        } else {
            omit(TaskCsvField.DESCRIPTION, TaskCsvWarningReason.DESCRIPTION_OMITTED)
            ""
        }

        rows += ImportedTaskRow(
            sourceRowNumber = source.sourceRowNumber,
            title = title,
            projectName = project?.name,
            statusName = selectedStatus?.name,
            priority = priority,
            start = start,
            due = due,
            completedAt = completedAt,
            estimateMinutes = estimateMinutes,
            tagNames = tagNames,
            description = description,
            statusSemantic = statusSemantic,
        )
    }

    if (statusValues.any { it !in mapping.statusChoices }) {
        blockers += TaskCsvBlockingIssue.STATUS_CHOICES_REQUIRED
    }
    if (priorityValues.any { it !in mapping.priorityChoices }) {
        blockers += TaskCsvBlockingIssue.PRIORITY_CHOICES_REQUIRED
    }
    if (ambiguousDatesPresent && mapping.dateOrder == null) {
        blockers += TaskCsvBlockingIssue.DATE_ORDER_REQUIRED
    }
    if (estimateValuesPresent && mapping.estimateUnit == null) {
        blockers += TaskCsvBlockingIssue.ESTIMATE_UNIT_REQUIRED
    }
    if (tagValuesPresent && mapping.tagMode == null) {
        blockers += TaskCsvBlockingIssue.TAG_MODE_REQUIRED
    }
    if (rows.isEmpty()) blockers += TaskCsvBlockingIssue.NO_VALID_TASKS
    if (proposedProjects.size > MAX_GENERIC_NEW_PROJECTS) {
        blockers += TaskCsvBlockingIssue.TOO_MANY_NEW_PROJECTS
    }
    if (proposedTags.size > MAX_GENERIC_NEW_TAGS) {
        blockers += TaskCsvBlockingIssue.TOO_MANY_NEW_TAGS
    }

    if (rows.isNotEmpty() && blockers.isEmpty()) {
        when (val preview = previewTasksImport(rows, target.toPreviewSnapshot())) {
            is CsvImportPreviewResult.Ready -> check(
                preview.summary.taskCount == rows.size &&
                    preview.summary.newProjectCount == proposedProjects.size &&
                    preview.summary.newTagCount == proposedTags.size,
            )
            is CsvImportPreviewResult.Invalid -> {
                blockers += TaskCsvBlockingIssue.TARGET_REJECTED
                blockingMessage = preview.message
            }
        }
    }

    return GenericTasksCsvReview(
        rows = rows,
        warnings = warnings,
        blockingIssues = blockers,
        blockingMessage = blockingMessage,
        statusValues = statusValues.toList(),
        priorityValues = priorityValues.toList(),
        ambiguousDatesPresent = ambiguousDatesPresent,
        estimateValuesPresent = estimateValuesPresent,
        tagValuesPresent = tagValuesPresent,
        tagSamples = rows.asSequence()
            .flatMap { it.tagNames.asSequence() }
            .distinct()
            .take(10)
            .toList(),
        capturedZoneId = zone.id,
        skippedTaskCount = skippedTaskCount,
        omittedValueCount = omittedValueCount,
        ignoredColumnIndexes = document.headers.indices.filter {
            it !in mapping.columns.values
        },
        newProjectCount = proposedProjects.size,
        newTagCount = proposedTags.size,
    )
}

private val FIELD_ALIASES = mapOf(
    TaskCsvField.TITLE to setOf("title", "task", "taskname", "content"),
    TaskCsvField.PROJECT to setOf("project", "projectname", "list", "listname"),
    TaskCsvField.STATUS to setOf("status", "state", "workflowstatus"),
    TaskCsvField.PRIORITY to setOf("priority", "taskpriority"),
    TaskCsvField.START to setOf("start", "startdate", "starttime", "scheduled"),
    TaskCsvField.DUE to setOf("due", "duedate", "duetime", "deadline"),
    TaskCsvField.COMPLETION to setOf("completed", "completedat", "completion", "closedat"),
    TaskCsvField.ESTIMATE to setOf("estimate", "estimateminutes", "estimatehours", "duration"),
    TaskCsvField.TAGS to setOf("tags", "tag", "labels", "label"),
    TaskCsvField.DESCRIPTION to setOf("description", "notes", "note", "details"),
)

private const val MAX_DISPLAY_SAMPLE_CHARS = 120

private fun displaySample(value: String): String =
    if (value.length <= MAX_DISPLAY_SAMPLE_CHARS) value
    else value.take(MAX_DISPLAY_SAMPLE_CHARS - 1) + "…"

private fun normaliseHeader(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .filterNot { it.isWhitespace() || it == '-' || it == '_' }

private fun suggestColumns(headers: List<String>): Map<TaskCsvField, Int> {
    val claims = headers.mapIndexed { index, header ->
        index to TaskCsvField.entries.filter { field ->
            normaliseHeader(header) in FIELD_ALIASES.getValue(field)
        }
    }
    return TaskCsvField.entries.mapNotNull { field ->
        val candidates = claims.filter { (_, fields) -> fields == listOf(field) }
        candidates.singleOrNull()?.first?.let { field to it }
    }.toMap()
}

private fun distinctValues(
    document: GenericTasksCsvDocument,
    columnIndex: Int?,
): List<String> = columnIndex?.let { index ->
    document.rows.asSequence()
        .map { it.cells[index].trim() }
        .filter(String::isNotEmpty)
        .distinct()
        .toList()
}.orEmpty()

private fun suggestStatus(value: String): TaskCsvStatusChoice? =
    when (normaliseHeader(value)) {
        "backlog", "open", "todo" -> TaskCsvStatusChoice.BACKLOG
        "inprogress", "doing", "started" -> TaskCsvStatusChoice.IN_PROGRESS
        "done", "completed", "closed" -> TaskCsvStatusChoice.DONE
        else -> null
    }

private fun suggestPriority(value: String): TaskCsvPriorityChoice? {
    if (value.trim().toLongOrNull() != null) return null
    return when (normaliseHeader(value)) {
        "none", "nopriority" -> TaskCsvPriorityChoice.NONE
        "low" -> TaskCsvPriorityChoice.LOW
        "medium", "normal" -> TaskCsvPriorityChoice.MEDIUM
        "high" -> TaskCsvPriorityChoice.HIGH
        "urgent", "critical" -> TaskCsvPriorityChoice.URGENT
        else -> null
    }
}

private fun suggestEstimateUnit(header: String): TaskCsvEstimateUnit? =
    when {
        normaliseHeader(header).let { value ->
            value.endsWith("minutes") || value.endsWith("minute") ||
                value.endsWith("mins") || value.endsWith("min")
        } -> TaskCsvEstimateUnit.MINUTES
        normaliseHeader(header).let { value ->
            value.endsWith("hours") || value.endsWith("hour") ||
                value.endsWith("hrs") || value.endsWith("hr")
        } -> TaskCsvEstimateUnit.HOURS
        else -> null
    }

private fun suggestTagMode(values: List<String>): TaskCsvTagMode? {
    val separators = values.asSequence()
        .flatMap(String::asSequence)
        .filter { it == ',' || it == ';' || it == '|' }
        .distinct()
        .toList()
    return when (separators.singleOrNull()) {
        ',' -> TaskCsvTagMode.COMMA
        ';' -> TaskCsvTagMode.SEMICOLON
        '|' -> TaskCsvTagMode.PIPE
        null -> TaskCsvTagMode.SINGLE.takeIf { separators.isEmpty() }
        else -> null
    }
}

private data class ResolvedProject(
    val name: String,
    val existingId: ProjectId?,
)

private sealed interface ParsedDateValue {
    data class Offset(val value: OffsetDateTime) : ParsedDateValue
    data class LocalDateTimeValue(val value: LocalDateTime) : ParsedDateValue
    data class Date(val value: LocalDate) : ParsedDateValue
    data object Ambiguous : ParsedDateValue
    data object Invalid : ParsedDateValue
}

private fun blockedReview(
    document: GenericTasksCsvDocument,
    mapping: TaskCsvMapping,
    zone: ZoneId,
    issue: TaskCsvBlockingIssue,
) = GenericTasksCsvReview(
    rows = emptyList(),
    warnings = emptyList(),
    blockingIssues = setOf(issue),
    blockingMessage = null,
    statusValues = emptyList(),
    priorityValues = emptyList(),
    ambiguousDatesPresent = false,
    estimateValuesPresent = false,
    tagValuesPresent = false,
    tagSamples = emptyList(),
    capturedZoneId = zone.id,
    skippedTaskCount = 0,
    omittedValueCount = 0,
    ignoredColumnIndexes = document.headers.indices.filter { it !in mapping.columns.values },
    newProjectCount = 0,
    newTagCount = 0,
)

private fun TaskCsvTarget.toPreviewSnapshot() = WorkspaceSnapshot(
    home = HomeSnapshot(
        LocalDate.EPOCH,
        emptyList(),
        emptyList(),
        projects,
        null,
        0,
    ),
    tasks = emptyList(),
    projects = projects,
    workflowStatuses = workflowStatuses,
    milestones = emptyList(),
    tags = tags,
)

private fun parseDateValue(
    value: String,
    dateOrder: TaskCsvDateOrder?,
): ParsedDateValue {
    parseTime { Instant.parse(value).atOffset(ZoneOffset.UTC) }
        ?.let { return ParsedDateValue.Offset(it) }
    parseTime { OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }
        ?.let { return ParsedDateValue.Offset(it) }
    parseTime { LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
        ?.let { return ParsedDateValue.LocalDateTimeValue(it) }
    parseTime { LocalDateTime.parse(value, ISO_SPACE_LOCAL_DATE_TIME) }
        ?.let { return ParsedDateValue.LocalDateTimeValue(it) }
    parseTime { LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE) }
        ?.let { return ParsedDateValue.Date(it) }

    NUMERIC_DATE.matchEntire(value)?.let { match ->
        val first = match.groupValues[1].toInt()
        val second = match.groupValues[3].toInt()
        val order = when {
            first > 12 && second > 12 -> return ParsedDateValue.Invalid
            first > 12 -> TaskCsvDateOrder.DAY_MONTH_YEAR
            second > 12 -> TaskCsvDateOrder.MONTH_DAY_YEAR
            first !in 1..12 || second !in 1..12 -> return ParsedDateValue.Invalid
            dateOrder == null -> {
                val valid = parseNumeric(match, TaskCsvDateOrder.DAY_MONTH_YEAR)
                return if (valid == null) ParsedDateValue.Invalid else ParsedDateValue.Ambiguous
            }
            else -> dateOrder
        }
        return parseNumeric(match, order) ?: ParsedDateValue.Invalid
    }

    parseTime { LocalDate.parse(value, ENGLISH_DMY) }
        ?.let { return ParsedDateValue.Date(it) }
    parseTime { LocalDate.parse(value, ENGLISH_MDY) }
        ?.let { return ParsedDateValue.Date(it) }
    return ParsedDateValue.Invalid
}

private fun parseNumeric(
    match: MatchResult,
    order: TaskCsvDateOrder,
): ParsedDateValue? {
    val value = match.value.replace('T', ' ')
    val formatter = numericFormatter(order, match.groupValues[2].single())
    return if (match.groupValues[5].isNotEmpty()) {
        parseTime { LocalDateTime.parse(value, formatter) }
            ?.let(ParsedDateValue::LocalDateTimeValue)
    } else {
        parseTime { LocalDate.parse(value, formatter) }
            ?.let(ParsedDateValue::Date)
    }
}

private fun isAmbiguousNumericDate(value: String): Boolean {
    val match = NUMERIC_DATE.matchEntire(value) ?: return false
    val first = match.groupValues[1].toInt()
    val second = match.groupValues[3].toInt()
    return first in 1..12 && second in 1..12 &&
        parseNumeric(match, TaskCsvDateOrder.DAY_MONTH_YEAR) != null
}

private inline fun <T> parseTime(block: () -> T): T? = try {
    block()
} catch (_: DateTimeException) {
    null
}

private fun numericFormatter(
    order: TaskCsvDateOrder,
    separator: Char,
): DateTimeFormatter = DateTimeFormatterBuilder()
    .parseCaseInsensitive()
    .appendPattern(
        if (order == TaskCsvDateOrder.DAY_MONTH_YEAR) {
            "d${separator}M${separator}uuuu"
        } else {
            "M${separator}d${separator}uuuu"
        },
    )
    .optionalStart()
    .appendLiteral(' ')
    .appendPattern("H:mm")
    .optionalStart()
    .appendPattern(":ss")
    .optionalEnd()
    .optionalEnd()
    .toFormatter(Locale.UK)
    .withResolverStyle(java.time.format.ResolverStyle.STRICT)

private fun englishFormatter(pattern: String) = DateTimeFormatterBuilder()
    .parseCaseInsensitive()
    .appendPattern(pattern)
    .toFormatter(Locale.UK)
    .withResolverStyle(java.time.format.ResolverStyle.STRICT)

private fun LocalDateTime.toImportedMoment(zone: ZoneId): ZonedMoment {
    val resolved = atZone(zone)
    return ZonedMoment(resolved.toInstant(), resolved.offset.id)
}

private fun OffsetDateTime.toImportedMoment() = ZonedMoment(toInstant(), offset.id)

private fun String.folded(): String = lowercase(Locale.ROOT)

private val NUMERIC_DATE = Regex(
    """^(\d{1,2})([/.-])(\d{1,2})\2(\d{4})""" +
        """(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?$""",
)
private val ENGLISH_DMY = englishFormatter("d MMM uuuu")
private val ENGLISH_MDY = englishFormatter("MMM d uuuu")
private val ISO_SPACE_LOCAL_DATE_TIME =
    DateTimeFormatter.ofPattern("uuuu-MM-dd H:mm[:ss]", Locale.UK)
        .withResolverStyle(java.time.format.ResolverStyle.STRICT)
private val START_DATE_ONLY_TIME = LocalTime.of(9, 0)
private val DUE_DATE_ONLY_TIME = LocalTime.of(17, 0)
private val COMPLETION_DATE_ONLY_TIME = LocalTime.of(17, 0)
private val COMPLETED_VALUES = setOf("yes", "true", "1", "done", "completed", "closed")
private val OPEN_COMPLETION_VALUES = setOf("no", "false", "0", "open")
private const val MAX_GENERIC_ESTIMATE_MINUTES = Long.MAX_VALUE / 60
private const val MAX_GENERIC_NEW_PROJECTS = 500
private const val MAX_GENERIC_NEW_TAGS = 1_000
