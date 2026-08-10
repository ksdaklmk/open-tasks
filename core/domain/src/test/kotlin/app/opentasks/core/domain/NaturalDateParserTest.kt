package app.opentasks.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class NaturalDateParserTest {

    private val zone = ZoneId.of("Asia/Bangkok")
    // Wednesday 5 August 2026, 10:00 in Asia/Bangkok
    private val now = ZonedDateTime.of(2026, 8, 5, 10, 0, 0, 0, zone).toInstant()

    private fun dueOf(match: NaturalDateMatch?): ZonedDateTime =
        ZonedDateTime.ofInstant(match!!.due.instant, zone)

    @Test
    fun tomorrowWithTimeResolvesAndReportsTheMatchedSpan() {
        val match = parseNaturalDate("Pay invoices tomorrow 4pm", now, zone)
        assertEquals(ZonedDateTime.of(2026, 8, 6, 16, 0, 0, 0, zone), dueOf(match))
        assertEquals("tomorrow 4pm", "Pay invoices tomorrow 4pm"
            .substring(match!!.startIndex, match.endIndex))
    }

    @Test
    fun dateOnlyDefaultsToFivePm() {
        val match = parseNaturalDate("call plumber fri", now, zone)
        assertEquals(ZonedDateTime.of(2026, 8, 7, 17, 0, 0, 0, zone), dueOf(match))
    }

    @Test
    fun nextWeekdayAddsSevenDaysToTheSoonestOccurrence() {
        val match = parseNaturalDate("review next fri", now, zone)
        assertEquals(ZonedDateTime.of(2026, 8, 14, 17, 0, 0, 0, zone), dueOf(match))
    }

    @Test
    fun timeOnlyPastRollsToTomorrow() {
        val match = parseNaturalDate("standup 9am", now, zone)
        assertEquals(ZonedDateTime.of(2026, 8, 6, 9, 0, 0, 0, zone), dueOf(match))
    }

    @Test
    fun relativeDaysResolveAtFivePm() {
        val match = parseNaturalDate("send report in 3 days", now, zone)
        assertEquals(ZonedDateTime.of(2026, 8, 8, 17, 0, 0, 0, zone), dueOf(match))
    }

    @Test
    fun uppercaseGrammarIsIndependentOfTheDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(
                ZonedDateTime.of(2026, 8, 7, 17, 0, 0, 0, zone),
                dueOf(parseNaturalDate("PLAN FRIDAY", now, zone)),
            )
            assertEquals(
                ZonedDateTime.of(2026, 8, 19, 17, 0, 0, 0, zone),
                dueOf(parseNaturalDate("REPORT IN 2 WEEKS", now, zone)),
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun twelveHourTimesRejectHoursOutsideOneThroughTwelve() {
        listOf("0am", "0pm", "13am", "13pm").forEach { time ->
            assertNull(time, parseNaturalDate("meet $time", now, zone))
        }
    }

    @Test
    fun pinnedGrammarCasesResolveDeterministically() {
        assertEquals(
            ZonedDateTime.of(2026, 8, 7, 17, 0, 0, 0, zone),
            dueOf(parseNaturalDate("tomorrow then friday", now, zone)),
        )
        assertEquals(
            ZonedDateTime.of(2026, 8, 5, 11, 0, 0, 0, zone),
            dueOf(parseNaturalDate("call at 11am", now, zone)),
        )
        val afterFive = ZonedDateTime.of(2026, 8, 5, 18, 0, 0, 0, zone).toInstant()
        assertEquals(
            ZonedDateTime.of(2026, 8, 5, 17, 0, 0, 0, zone),
            dueOf(parseNaturalDate("finish today", afterFive, zone)),
        )
        assertEquals(
            ZonedDateTime.of(2026, 8, 19, 17, 0, 0, 0, zone),
            dueOf(parseNaturalDate("report in 2 weeks", now, zone)),
        )
    }

    @Test
    fun publicParserStillReturnsTheRightmostDate() {
        val text = "today then tomorrow"
        val match = parseNaturalDate(text, now, zone)!!
        assertEquals("tomorrow", text.substring(match.startIndex, match.endIndex))
    }

    @Test
    fun embeddedTokensPlainTextAndInvalidValuesDoNotMatch() {
        assertNull(parseNaturalDate("buy milk friday-market", now, zone))
        assertNull(parseNaturalDate("no dates here", now, zone))
        assertNull(parseNaturalDate("meet at 25:99", now, zone))
        assertNull(parseNaturalDate("later in 999999999999999999999 weeks", now, zone))
    }
}
