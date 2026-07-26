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
    fun clockRollbackCannotMoveLocalEventsBackwards() {
        var wallTime = 2_000L
        val clock = HybridLogicalClock(DeviceId("phone")) { wallTime }
        val beforeRollback = clock.tick()

        wallTime = 1_000L
        val afterRollback = clock.tick()

        assertEquals(2_000, afterRollback.wallTimeMillis)
        assertEquals(1, afterRollback.logicalCounter)
        assertTrue(afterRollback > beforeRollback)
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
    fun receivingSameRemoteEventTwiceStillProducesMonotonicLocalEvents() {
        val clock = HybridLogicalClock(DeviceId("phone")) { 1_000 }
        val remote = HlcTimestamp(2_000, 4, DeviceId("tablet"))

        val firstReceipt = clock.receive(remote)
        val repeatedReceipt = clock.receive(remote)

        assertEquals(5, firstReceipt.logicalCounter)
        assertEquals(6, repeatedReceipt.logicalCounter)
        assertTrue(repeatedReceipt > firstReceipt)
    }

    @Test
    fun deviceIdBreaksExactTimestampTie() {
        val phone = HlcTimestamp(1_000, 1, DeviceId("phone"))
        val tablet = HlcTimestamp(1_000, 1, DeviceId("tablet"))

        assertTrue(tablet > phone)
    }
}
