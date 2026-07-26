package app.opentasks.core.sync

import app.opentasks.core.model.DeviceId
import kotlin.math.max

data class HlcTimestamp(
    val wallTimeMillis: Long,
    val logicalCounter: Int,
    val deviceId: DeviceId,
) : Comparable<HlcTimestamp> {
    override fun compareTo(other: HlcTimestamp): Int =
        compareValuesBy(
            this,
            other,
            HlcTimestamp::wallTimeMillis,
            HlcTimestamp::logicalCounter,
            { it.deviceId.value },
        )
}

class HybridLogicalClock(
    private val deviceId: DeviceId,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private var last = HlcTimestamp(0, 0, deviceId)

    @Synchronized
    fun tick(): HlcTimestamp {
        val now = wallClock()
        last = if (now > last.wallTimeMillis) {
            HlcTimestamp(now, 0, deviceId)
        } else {
            HlcTimestamp(last.wallTimeMillis, last.logicalCounter + 1, deviceId)
        }
        return last
    }

    @Synchronized
    fun receive(remote: HlcTimestamp): HlcTimestamp {
        val now = wallClock()
        val maximumWall = max(now, max(last.wallTimeMillis, remote.wallTimeMillis))
        val logical = when {
            maximumWall == last.wallTimeMillis && maximumWall == remote.wallTimeMillis ->
                max(last.logicalCounter, remote.logicalCounter) + 1
            maximumWall == last.wallTimeMillis -> last.logicalCounter + 1
            maximumWall == remote.wallTimeMillis -> remote.logicalCounter + 1
            else -> 0
        }
        last = HlcTimestamp(maximumWall, logical, deviceId)
        return last
    }
}
