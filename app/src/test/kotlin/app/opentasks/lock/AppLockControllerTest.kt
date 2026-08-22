package app.opentasks.lock

import android.content.SharedPreferences
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class AppLockControllerTest {
    private val baseInstant: Instant = Instant.parse("2026-08-05T09:00:00Z")

    @Test
    fun coldStartLocksWhenEnabled() {
        val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = true }

        val controller = AppLockController(settings, now = { baseInstant })

        assertTrue(controller.locked.value)
    }

    @Test
    fun coldStartDoesNotLockWhenDisabled() {
        val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = false }

        val controller = AppLockController(settings, now = { baseInstant })

        assertFalse(controller.locked.value)
    }

    @Test
    fun backgroundBelowDelayDoesNotLock() {
        var clock = baseInstant
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, now = { clock })
        controller.onUnlocked()

        controller.onAppBackgrounded()
        clock = clock.plus(Duration.ofMinutes(4))
        val mustLock = controller.onAppForegrounded()

        assertFalse(mustLock)
        assertFalse(controller.locked.value)
    }

    @Test
    fun backgroundExactlyAtDelayLocks() {
        var clock = baseInstant
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, now = { clock })
        controller.onUnlocked()

        controller.onAppBackgrounded()
        clock = clock.plus(Duration.ofMinutes(5))
        val mustLock = controller.onAppForegrounded()

        assertTrue(mustLock)
        assertTrue(controller.locked.value)
    }

    @Test
    fun backgroundAtOrBeyondDelayLocks() {
        var clock = baseInstant
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, now = { clock })
        controller.onUnlocked()

        controller.onAppBackgrounded()
        clock = clock.plus(Duration.ofMinutes(6))
        val mustLock = controller.onAppForegrounded()

        assertTrue(mustLock)
        assertTrue(controller.locked.value)
    }

    @Test
    fun immediateDelayLocksOnAnyForeground() {
        val clock = baseInstant
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.IMMEDIATE
        }
        val controller = AppLockController(settings, now = { clock })
        controller.onUnlocked()

        controller.onAppBackgrounded()
        val mustLock = controller.onAppForegrounded()

        assertTrue(mustLock)
        assertTrue(controller.locked.value)
    }

    @Test
    fun immediateDelayExpiresWhileAppRemainsBackgrounded() = runBlocking {
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.IMMEDIATE
        }
        val controller = AppLockController(settings, now = { baseInstant })
        controller.onUnlocked()

        controller.onAppBackgrounded()

        assertTrue(withTimeout(5_000) {
            controller.locked.first { it }
        })
    }

    @Test
    fun backgroundExpiryLocksWithoutForegrounding() {
        val waits = ControlledWait()
        val durableExpiry = DurableExpiryRecorder()
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = controlledController(settings, waits, durableExpiry = durableExpiry)
        controller.onUnlocked()
        durableExpiry.reset()

        controller.onAppBackgrounded()

        assertEquals(listOf(Duration.ofMinutes(5)), waits.durations)
        assertEquals(listOf(Duration.ofMinutes(5)), durableExpiry.scheduled)
        waits.expire(0)

        assertTrue(controller.locked.value)
    }

    @Test
    fun externalActionAtExactDeadlineRefreshesAuthorityBeforeWaitCompletes() {
        var clock = baseInstant
        val waits = ControlledWait()
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = controlledController(settings, waits, now = { clock })
        controller.onUnlocked()
        controller.onAppBackgrounded()

        clock = clock.plus(Duration.ofMinutes(5))

        assertFalse(controller.isExternalActionAuthorized())
        assertTrue(controller.locked.value)
    }

    @Test
    fun externalActionBeforeDeadlineRemainsAuthorized() {
        var clock = baseInstant
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, now = { clock })
        controller.onUnlocked()
        controller.onAppBackgrounded()

        clock = clock.plus(Duration.ofMinutes(4))

        assertTrue(controller.isExternalActionAuthorized())
        assertFalse(controller.locked.value)
    }

    @Test
    fun disabledLockAuthorizesDespiteDelayedCachedStateUpdate() {
        val dispatcher = QueuedDispatcher()
        val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = true }
        val controller = AppLockController(
            settings = settings,
            now = { baseInstant },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        assertTrue(controller.locked.value)

        settings.lockEnabled = false

        assertTrue(controller.isExternalActionAuthorized())
    }

    @Test
    fun foregroundCancelsBackgroundExpiry() {
        var clock = baseInstant
        val waits = ControlledWait()
        val durableExpiry = DurableExpiryRecorder()
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = controlledController(
            settings = settings,
            waits = waits,
            now = { clock },
            durableExpiry = durableExpiry,
        )
        controller.onUnlocked()
        durableExpiry.reset()
        controller.onAppBackgrounded()

        clock = clock.plus(Duration.ofMinutes(4))
        assertFalse(controller.onAppForegrounded())
        waits.expire(0)

        assertEquals(1, durableExpiry.cancellations)
        assertFalse(controller.locked.value)
    }

    @Test
    fun unlockCancelsBackgroundExpiry() {
        val waits = ControlledWait()
        val durableExpiry = DurableExpiryRecorder()
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = controlledController(settings, waits, durableExpiry = durableExpiry)
        controller.onUnlocked()
        durableExpiry.reset()
        controller.onAppBackgrounded()

        controller.onUnlocked()
        waits.expire(0)

        assertEquals(1, durableExpiry.cancellations)
        assertFalse(controller.locked.value)
    }

    @Test
    fun disablingLockCancelsBackgroundExpiry() {
        val waits = ControlledWait()
        val durableExpiry = DurableExpiryRecorder()
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = controlledController(settings, waits, durableExpiry = durableExpiry)
        controller.onUnlocked()
        durableExpiry.reset()
        controller.onAppBackgrounded()

        settings.lockEnabled = false
        waits.expire(0)

        assertEquals(1, durableExpiry.cancellations)
        assertFalse(controller.locked.value)
    }

    @Test
    fun delayChangeReschedulesFromOriginalBackgroundTime() {
        var clock = baseInstant
        val waits = ControlledWait()
        val durableExpiry = DurableExpiryRecorder()
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = controlledController(
            settings = settings,
            waits = waits,
            now = { clock },
            durableExpiry = durableExpiry,
        )
        controller.onUnlocked()
        durableExpiry.reset()
        controller.onAppBackgrounded()

        clock = clock.plus(Duration.ofMinutes(1))
        settings.lockDelay = LockDelay.FIFTEEN_MINUTES

        assertEquals(
            listOf(Duration.ofMinutes(5), Duration.ofMinutes(14)),
            waits.durations,
        )
        assertEquals(
            listOf(Duration.ofMinutes(5), Duration.ofMinutes(14)),
            durableExpiry.scheduled,
        )
        waits.expire(0)
        assertFalse(controller.locked.value)
        waits.expire(1)
        assertTrue(controller.locked.value)
    }

    @Test
    fun onUnlockedClearsLocked() {
        val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = true }
        val controller = AppLockController(settings, now = { baseInstant })
        assertTrue(controller.locked.value)

        controller.onUnlocked()

        assertFalse(controller.locked.value)
    }

    @Test
    fun settingsChangeToDisabledUnlocks() = runBlocking {
        val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = true }
        val controller = AppLockController(settings, now = { baseInstant })
        assertTrue(controller.locked.value)

        settings.lockEnabled = false

        withTimeout(5_000) {
            controller.locked.first { locked -> !locked }
        }
        assertFalse(controller.locked.value)
    }

    private fun controlledController(
        settings: AppLockSettings,
        waits: ControlledWait,
        now: () -> Instant = { baseInstant },
        durableExpiry: DurableExpiryRecorder? = null,
    ) = AppLockController(
        settings = settings,
        now = now,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        wait = waits::await,
        scheduleDurableExpiry = durableExpiry?.let { recorder -> recorder::schedule } ?: {},
        cancelDurableExpiry = durableExpiry?.let { recorder -> recorder::cancel } ?: {},
    )

    private class DurableExpiryRecorder {
        val scheduled = mutableListOf<Duration>()
        var cancellations = 0
            private set

        fun schedule(duration: Duration) {
            scheduled += duration
        }

        fun cancel() {
            cancellations += 1
        }

        fun reset() {
            scheduled.clear()
            cancellations = 0
        }
    }

    private class ControlledWait {
        private data class Invocation(
            val duration: Duration,
            val completion: CompletableDeferred<Unit>,
        )

        private val invocations = mutableListOf<Invocation>()

        val durations: List<Duration>
            get() = invocations.map(Invocation::duration)

        suspend fun await(duration: Duration) {
            val completion = CompletableDeferred<Unit>()
            invocations += Invocation(duration, completion)
            completion.await()
        }

        fun expire(index: Int) {
            invocations[index].completion.complete(Unit)
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }
}

/**
 * A hand-rolled, in-memory [SharedPreferences] double -- no mocking library
 * is available, and the Android unit-test jar stubs every concrete
 * framework method body, but [SharedPreferences] and its [Editor] are pure
 * interfaces with nothing to stub, so implementing them directly runs as
 * real code under plain JVM unit tests.
 */
internal class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float =
        values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listener?.let(listeners::add)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listeners.remove(listener)
    }

    private inner class FakeEditor : SharedPreferences.Editor {
        private val touchedKeys = mutableSetOf<String>()

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) {
                values.remove(key)
                touchedKeys += key
            }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            touchedKeys += values.keys
            values.clear()
            return this
        }

        override fun commit(): Boolean {
            notifyListeners()
            return true
        }

        override fun apply() {
            notifyListeners()
        }

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            if (key != null) {
                values[key] = value
                touchedKeys += key
            }
            return this
        }

        private fun notifyListeners() {
            val keys = touchedKeys.toList()
            touchedKeys.clear()
            listeners.toList().forEach { listener ->
                keys.forEach { key ->
                    listener.onSharedPreferenceChanged(this@FakeSharedPreferences, key)
                }
            }
        }
    }
}
