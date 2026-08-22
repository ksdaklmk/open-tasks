package app.opentasks.lock

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalLockContentConcealerTest {
    private val baseInstant = Instant.parse("2026-08-05T09:00:00Z")

    @Test
    fun staleEarlyDeliveryLeavesExternalContentAlone() = runBlocking {
        var clock = baseInstant
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, now = { clock })
        controller.onUnlocked()
        controller.onAppBackgrounded()
        clock = clock.plus(Duration.ofMinutes(4))
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
        var clock = baseInstant
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, now = { clock })
        controller.onUnlocked()
        controller.onAppBackgrounded()
        clock = clock.plus(Duration.ofMinutes(5))
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
        val controller = AppLockController(settings, now = { baseInstant })
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
