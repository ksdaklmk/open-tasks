package app.opentasks.core.data.export

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
