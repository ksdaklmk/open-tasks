package app.opentasks.core.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericTasksCsvMapperTest {
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
}
