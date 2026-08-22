package app.opentasks.lock

import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalLockContentConcealerTest {
    private val baseElapsedRealtime = Duration.ofHours(12).toMillis()

    @Test
    fun staleEarlyDeliveryLeavesExternalContentAlone() = runBlocking {
        var elapsedRealtime = baseElapsedRealtime
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, elapsedRealtime = { elapsedRealtime })
        controller.onUnlocked()
        controller.onAppBackgrounded()
        elapsedRealtime += Duration.ofMinutes(4).toMillis()
        val effects = mutableListOf<String>()
        val concealer = ExternalLockContentConcealer(
            appLockController = controller,
            cancelActiveReminders = { effects += "reminders" },
            clearTodayWidgetTitles = { effects += "widget" },
        )

        concealer.concealIfUnauthorized()

        assertEquals(emptyList<String>(), effects)
    }

    @Test
    fun expiredDeliveryConcealsRemindersAndWidgetTitles() = runBlocking {
        var elapsedRealtime = baseElapsedRealtime
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, elapsedRealtime = { elapsedRealtime })
        controller.onUnlocked()
        controller.onAppBackgrounded()
        elapsedRealtime += Duration.ofMinutes(5).toMillis()
        val effects = mutableListOf<String>()
        val concealer = ExternalLockContentConcealer(
            appLockController = controller,
            cancelActiveReminders = { effects += "reminders" },
            clearTodayWidgetTitles = { effects += "widget" },
        )

        concealer.concealIfUnauthorized()

        assertEquals(setOf("reminders", "widget"), effects.toSet())
        assertEquals(2, effects.size)
    }

    @Test
    fun lockedColdStartConcealsWithoutVaultState() = runBlocking {
        val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = true }
        val controller = AppLockController(settings, elapsedRealtime = { baseElapsedRealtime })
        val effects = mutableListOf<String>()
        val concealer = ExternalLockContentConcealer(
            appLockController = controller,
            cancelActiveReminders = { effects += "reminders" },
            clearTodayWidgetTitles = { effects += "widget" },
        )

        concealer.concealIfUnauthorized()

        assertEquals(setOf("reminders", "widget"), effects.toSet())
        assertEquals(2, effects.size)
    }
}
