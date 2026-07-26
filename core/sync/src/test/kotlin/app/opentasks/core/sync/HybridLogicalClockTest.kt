package app.opentasks.core.sync

import app.opentasks.core.model.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridLogicalClockTest {
    @Test
    fun ordersEventsWhenWallClockDoesNotAdvance() {
        val clock = HybridLogicalClock(DeviceId("phone")) { 1_000 }
        val first = clock.tick()
        val second = clock.tick()

        assertEquals(0, first.logicalCounter)
        assertEquals(1, second.logicalCounter)
        assertTrue(second > first)
    }

    @Test
    fun receivingFutureEventAdvancesLogicalCounter() {
        val clock = HybridLogicalClock(DeviceId("phone")) { 1_000 }
        val remote = HlcTimestamp(2_000, 4, DeviceId("tablet"))
        val local = clock.receive(remote)

        assertEquals(2_000, local.wallTimeMillis)
        assertEquals(5, local.logicalCounter)
        assertTrue(local > remote)
    }

    @Test
    fun deviceIdBreaksExactTimestampTie() {
        val phone = HlcTimestamp(1_000, 1, DeviceId("phone"))
        val tablet = HlcTimestamp(1_000, 1, DeviceId("tablet"))

        assertTrue(tablet > phone)
    }
}
