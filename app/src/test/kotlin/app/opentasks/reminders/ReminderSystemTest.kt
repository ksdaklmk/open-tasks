package app.opentasks.reminders

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderSystemTest {
    @Test
    fun preciseReminderUsesExactAlarmWhenAccessIsGranted() {
        assertEquals(
            ReminderScheduleMode.EXACT,
            reminderScheduleMode(
                preciseRequested = true,
                preciseAccessGranted = true,
            ),
        )
    }

    @Test
    fun preciseReminderFallsBackToInexactAlarmWithoutAccess() {
        assertEquals(
            ReminderScheduleMode.INEXACT,
            reminderScheduleMode(
                preciseRequested = true,
                preciseAccessGranted = false,
            ),
        )
    }

    @Test
    fun flexibleReminderNeverRequiresExactAccess() {
        assertEquals(
            ReminderScheduleMode.INEXACT,
            reminderScheduleMode(
                preciseRequested = false,
                preciseAccessGranted = true,
            ),
        )
    }
}
