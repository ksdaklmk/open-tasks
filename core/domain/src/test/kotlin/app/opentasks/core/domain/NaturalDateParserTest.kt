package app.opentasks.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

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
    fun embeddedTokensPlainTextAndInvalidValuesDoNotMatch() {
        assertNull(parseNaturalDate("buy milk friday-market", now, zone))
        assertNull(parseNaturalDate("no dates here", now, zone))
        assertNull(parseNaturalDate("meet at 25:99", now, zone))
        assertNull(parseNaturalDate("later in 999999999999999999999 weeks", now, zone))
    }
}
