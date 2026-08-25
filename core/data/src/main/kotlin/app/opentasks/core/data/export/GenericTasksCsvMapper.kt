package app.opentasks.core.data.export

import app.opentasks.core.model.TaskCsvEstimateUnit
import app.opentasks.core.model.TaskCsvField
import app.opentasks.core.model.TaskCsvMapping
import app.opentasks.core.model.TaskCsvPriorityChoice
import app.opentasks.core.model.TaskCsvStatusChoice
import app.opentasks.core.model.TaskCsvTagMode
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
