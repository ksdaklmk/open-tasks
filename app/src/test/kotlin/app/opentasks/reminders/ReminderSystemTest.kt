package app.opentasks.reminders

import app.opentasks.lock.AppLockController
import app.opentasks.lock.AppLockSettings
import app.opentasks.lock.FakeSharedPreferences
import app.opentasks.lock.LockDelay
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.IMMEDIATE
        }
        val controller = AppLockController(settings, elapsedRealtime = { 0L })
        controller.onUnlocked()
        val pendingSnooze: suspend () -> Unit? = {
            performReminderMutation(controller) { mutations += 1 }
        }
        val pendingComplete: suspend () -> Unit? = {
            performReminderMutation(controller) { mutations += 1 }
        }
        controller.onAppBackgrounded()

        val snoozeResult = pendingSnooze()
        val completeResult = pendingComplete()

        assertNull(snoozeResult)
        assertNull(completeResult)
        assertEquals(0, mutations)
    }

    @Test
    fun authorizedPendingMutationStillRuns() = runBlocking {
        var mutations = 0
        val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = true }
        val controller = AppLockController(settings, elapsedRealtime = { 0L })
        controller.onUnlocked()

        val result = performReminderMutation(controller) {
            mutations += 1
            "updated"
        }

        assertEquals("updated", result)
        assertEquals(1, mutations)
    }

    @Test
    fun disabledLockAllowsMutation() = runBlocking {
        var mutations = 0
        val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = false }
        val controller = AppLockController(settings, elapsedRealtime = { 0L })

        performReminderMutation(controller) {
            mutations += 1
        }

        assertEquals(1, mutations)
    }

    @Test
    fun reminderMutationRechecksControllerAuthorityAtExecution() = runBlocking {
        var elapsedRealtime = Duration.ofHours(12).toMillis()
        val waitForever = CompletableDeferred<Unit>()
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(
            settings = settings,
            elapsedRealtime = { elapsedRealtime },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            wait = { waitForever.await() },
        )
        controller.onUnlocked()
        controller.onAppBackgrounded()
        assertTrue(controller.isExternalActionAuthorized())
        var mutations = 0
        val pendingMutation: suspend () -> String? = {
            performReminderMutation(controller) {
                mutations += 1
                "updated"
            }
        }
        elapsedRealtime += Duration.ofMinutes(5).toMillis()

        val result = pendingMutation()

        assertNull(result)
        assertEquals(0, mutations)
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

    @Test
    fun elapsedLockAuthorityConcealsReminderPublication() {
        var elapsedRealtime = 0L
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, elapsedRealtime = { elapsedRealtime })
        controller.onUnlocked()
        controller.onAppBackgrounded()

        assertFalse(reminderContentConcealed(settings, controller))

        elapsedRealtime += Duration.ofMinutes(5).toMillis()

        assertTrue(reminderContentConcealed(settings, controller))
    }
}
