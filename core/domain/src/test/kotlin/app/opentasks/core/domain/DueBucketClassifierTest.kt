package app.opentasks.core.domain

import app.opentasks.core.model.DueBucket.LATER
import app.opentasks.core.model.DueBucket.NO_DATE
import app.opentasks.core.model.DueBucket.OVERDUE
import app.opentasks.core.model.DueBucket.THIS_WEEK
import app.opentasks.core.model.DueBucket.TODAY
import app.opentasks.core.model.ZonedMoment
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DueBucketClassifierTest {
    private val zone = ZoneId.of("Asia/Bangkok")
    private val now = ZonedDateTime.of(2026, 8, 9, 10, 0, 0, 0, zone).toInstant()
    private val clock = Clock.fixed(now, zone)

    @Test
    fun sundayBucketsAreHalfOpenAndThisWeekIsEmpty() {
        assertEquals(OVERDUE, classifyDueBucket(moment(now.minusNanos(1)), clock))
        assertEquals(TODAY, classifyDueBucket(moment(now), clock))
        assertEquals(TODAY, classifyDueBucket(moment("2026-08-09T23:59:59+07:00"), clock))
        assertEquals(LATER, classifyDueBucket(moment("2026-08-10T00:00:00+07:00"), clock))
        assertEquals(NO_DATE, classifyDueBucket(null, clock))
    }

    @Test
    fun thisWeekEndsAtTheNextIsoMonday() {
        val mondayClock = Clock.fixed(
            ZonedDateTime.of(2026, 8, 10, 10, 0, 0, 0, zone).toInstant(),
            zone,
        )
        assertEquals(
            THIS_WEEK,
            classifyDueBucket(moment("2026-08-16T23:59:59+07:00"), mondayClock),
        )
        assertEquals(
            LATER,
            classifyDueBucket(moment("2026-08-17T00:00:00+07:00"), mondayClock),
        )
    }

    @Test
    fun dueZoneDoesNotOverrideClockZoneBoundaries() {
        val sameInstantDifferentZone = ZonedMoment(
            instant = OffsetDateTime.parse("2026-08-09T23:00:00+07:00").toInstant(),
            zoneId = "Pacific/Kiritimati",
        )
        assertEquals(TODAY, classifyDueBucket(sameInstantDifferentZone, clock))
    }

    private fun moment(instant: Instant): ZonedMoment = ZonedMoment(instant, zone.id)

    private fun moment(value: String): ZonedMoment =
        ZonedMoment(OffsetDateTime.parse(value).toInstant(), zone.id)
}
