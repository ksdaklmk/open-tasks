package app.opentasks.core.sync

import app.opentasks.core.model.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeRulesTest {
    private val phone = DeviceId("phone")
    private val tablet = DeviceId("tablet")

    @Test
    fun scalarMergeIsCommutative() {
        val first = VersionedField("Draft", HlcTimestamp(100, 1, phone))
        val second = VersionedField("Final", HlcTimestamp(101, 0, tablet))

        assertEquals(
            MergeRules.scalar(first, second),
            MergeRules.scalar(second, first),
        )
        assertEquals("Final", MergeRules.scalar(first, second).value)
    }

    @Test
    fun newerRestoreBeatsDeleteButOlderRestoreDoesNot() {
        val deleted = TombstonedValue<String>(
            null,
            HlcTimestamp(200, 0, phone),
            deleted = true,
        )
        val oldRestore = TombstonedValue(
            "Task",
            HlcTimestamp(199, 8, tablet),
            deleted = false,
        )
        val newRestore = TombstonedValue(
            "Task",
            HlcTimestamp(201, 0, tablet),
            deleted = false,
        )

        assertTrue(MergeRules.tombstone(deleted, oldRestore).deleted)
        assertEquals(newRestore, MergeRules.tombstone(deleted, newRestore))
    }

    @Test
    fun latestSetMutationWins() {
        val result = MergeRules.setMembership(
            listOf(
                SetMutation("deep-work", HlcTimestamp(100, 0, phone), true),
                SetMutation("deep-work", HlcTimestamp(102, 0, tablet), false),
                SetMutation("admin", HlcTimestamp(101, 0, phone), true),
            ),
        )

        assertEquals(setOf("admin"), result)
    }
}
