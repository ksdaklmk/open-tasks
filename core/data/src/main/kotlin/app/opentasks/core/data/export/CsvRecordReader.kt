package app.opentasks.core.data.export

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal const val MAX_IMPORT_ROWS = 5_000

internal enum class CsvRecordFailureKind {
    INVALID_UTF8,
    MALFORMED,
    TOO_MANY_ROWS,
    TOO_MANY_COLUMNS,
}

internal sealed interface CsvTextResult {
    data class Ready(val text: String) : CsvTextResult
    data class Failed(val kind: CsvRecordFailureKind) : CsvTextResult
}

internal sealed interface CsvRecordResult {
    data class Ready(val records: List<List<String>>) : CsvRecordResult
    data class Failed(
        val rowNumber: Int,
        val kind: CsvRecordFailureKind,
        val message: String,
    ) : CsvRecordResult
}

internal fun decodeCsvUtf8(bytes: ByteArray): CsvTextResult = try {
    CsvTextResult.Ready(
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString(),
    )
} catch (_: java.nio.charset.CharacterCodingException) {
    CsvTextResult.Failed(CsvRecordFailureKind.INVALID_UTF8)
}

internal fun readCsvRecords(
    text: String,
    maxDataRows: Int,
    maxColumns: Int,
): CsvRecordResult {
    require(maxDataRows > 0 && maxColumns > 0)
    val records = mutableListOf<List<String>>()
    var fields = mutableListOf<String>()
    val field = StringBuilder()
    var state = FieldState.START
    var index = 0

    fun rowNumber() = records.size
    fun failed(kind: CsvRecordFailureKind, message: String) =
        CsvRecordResult.Failed(rowNumber(), kind, message)
    fun finishField() {
        fields += field.toString()
        field.setLength(0)
        state = FieldState.START
    }
    fun finishSeparatedField(): CsvRecordResult.Failed? {
        finishField()
        return if (fields.size >= maxColumns) {
            failed(
                CsvRecordFailureKind.TOO_MANY_COLUMNS,
                "CSV records may contain at most $maxColumns columns.",
            )
        } else {
            null
        }
    }
    fun finishRecord(): CsvRecordResult.Failed? {
        finishField()
        if (records.size > maxDataRows) {
            return CsvRecordResult.Failed(
                maxDataRows + 1,
                CsvRecordFailureKind.TOO_MANY_ROWS,
                "Import at most $maxDataRows tasks",
            )
        }
        records += fields
        fields = mutableListOf()
        return null
    }

    while (index < text.length) {
        val char = text[index]
        when (state) {
            FieldState.START -> when (char) {
                '"' -> state = FieldState.QUOTED
                ',' -> finishSeparatedField()?.let { return it }
                '\n' -> finishRecord()?.let { return it }
                '\r' -> {
                    if (index + 1 >= text.length || text[index + 1] != '\n') {
                        return failed(
                            CsvRecordFailureKind.MALFORMED,
                            "Bare CR outside a quoted field",
                        )
                    }
                    finishRecord()?.let { return it }
                    index++
                }
                else -> {
                    field.append(char)
                    state = FieldState.UNQUOTED
                }
            }
            FieldState.UNQUOTED -> when (char) {
                '"' -> return failed(
                    CsvRecordFailureKind.MALFORMED,
                    "Quote in unquoted field",
                )
                ',' -> finishSeparatedField()?.let { return it }
                '\n' -> finishRecord()?.let { return it }
                '\r' -> {
                    if (index + 1 >= text.length || text[index + 1] != '\n') {
                        return failed(
                            CsvRecordFailureKind.MALFORMED,
                            "Bare CR outside a quoted field",
                        )
                    }
                    finishRecord()?.let { return it }
                    index++
                }
                else -> field.append(char)
            }
            FieldState.QUOTED -> when {
                char != '"' -> field.append(char)
                index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                else -> state = FieldState.AFTER_QUOTE
            }
            FieldState.AFTER_QUOTE -> when (char) {
                ',' -> finishSeparatedField()?.let { return it }
                '\n' -> finishRecord()?.let { return it }
                '\r' -> {
                    if (index + 1 >= text.length || text[index + 1] != '\n') {
                        return failed(
                            CsvRecordFailureKind.MALFORMED,
                            "Bare CR after quoted field",
                        )
                    }
                    finishRecord()?.let { return it }
                    index++
                }
                else -> return failed(
                    CsvRecordFailureKind.MALFORMED,
                    "Text after closing quote",
                )
            }
        }
        index++
    }
    if (state == FieldState.QUOTED) {
        return failed(CsvRecordFailureKind.MALFORMED, "Unclosed quoted field")
    }
    if (
        (state != FieldState.START || field.isNotEmpty() || fields.isNotEmpty())
    ) {
        finishRecord()?.let { return it }
    }
    return CsvRecordResult.Ready(records)
}

private enum class FieldState { START, UNQUOTED, QUOTED, AFTER_QUOTE }
