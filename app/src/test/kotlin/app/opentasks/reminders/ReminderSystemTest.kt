package app.opentasks.reminders

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun pendingMutationCreatedBeforeLockCannotRunAfterLock() = runBlocking {
        var mutations = 0
        var locked = false
        val pendingSnooze: suspend () -> Unit = { mutations += 1 }
        val pendingComplete: suspend () -> Unit = { mutations += 1 }
        assertFalse(locked)
        locked = true

        val snoozeResult = performReminderMutation(
            lockEnabled = true,
            locked = locked,
            mutation = pendingSnooze,
        )
        val completeResult = performReminderMutation(
            lockEnabled = true,
            locked = locked,
            mutation = pendingComplete,
        )

        assertNull(snoozeResult)
        assertNull(completeResult)
        assertEquals(0, mutations)
    }

    @Test
    fun authorizedPendingMutationStillRuns() = runBlocking {
        var mutations = 0

        val result = performReminderMutation(
            lockEnabled = true,
            locked = false,
        ) {
            mutations += 1
            "updated"
        }

        assertEquals("updated", result)
        assertEquals(1, mutations)
    }

    @Test
    fun disabledLockDoesNotRejectAStaleLockedState() = runBlocking {
        var mutations = 0

        performReminderMutation(lockEnabled = false, locked = true) {
            mutations += 1
        }

        assertEquals(1, mutations)
    }

    @Test
    fun concealedNotificationExposesNoMutationActions() {
        assertFalse(reminderMutationActionsEnabled(concealed = true))
        assertTrue(reminderMutationActionsEnabled(concealed = false))
    }

    @Test
    fun activeCancellationMatchesOnlyReminderChannelNotifications() {
        assertTrue(isReminderNotification(ReminderNotifications.CHANNEL_ID))
        assertFalse(isReminderNotification("daily_digest"))
        assertFalse(isReminderNotification(null))
    }
}
