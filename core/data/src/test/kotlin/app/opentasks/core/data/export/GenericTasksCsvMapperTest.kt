package app.opentasks.core.data.export

import app.opentasks.core.model.TaskCsvEstimateUnit
import app.opentasks.core.model.TaskCsvField
import app.opentasks.core.model.TaskCsvStatusChoice
import app.opentasks.core.model.TaskCsvTagMode
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

    private fun parsedGeneric(source: String): GenericTasksCsvDocument =
        (parseGenericTasksCsv(source.toByteArray()) as GenericTasksCsvParseResult.Parsed).document
}
