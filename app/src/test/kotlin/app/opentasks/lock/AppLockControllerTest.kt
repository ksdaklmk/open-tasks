package app.opentasks.lock

import android.content.SharedPreferences
import java.time.Duration
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

internal fun AppLockController.onUnlocked() =
    onUnlocked(beginUnlockAttempt())

class AppLockControllerTest {
    private val baseElapsedRealtime = Duration.ofHours(12).toMillis()

    @Test
    fun coldStartLocksWhenEnabled() {
        val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = true }

        val controller = AppLockController(settings, elapsedRealtime = { baseElapsedRealtime })

        assertTrue(controller.locked.value)
    }

    @Test
    fun coldStartDoesNotLockWhenDisabled() {
        val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = false }

        val controller = AppLockController(settings, elapsedRealtime = { baseElapsedRealtime })

        assertFalse(controller.locked.value)
    }

    @Test
    fun backgroundBelowDelayDoesNotLock() {
        var elapsedRealtime = baseElapsedRealtime
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, elapsedRealtime = { elapsedRealtime })
        controller.onUnlocked()

        controller.onAppBackgrounded()
        elapsedRealtime += Duration.ofMinutes(4).toMillis()
        val mustLock = controller.onAppForegrounded()

        assertFalse(mustLock)
        assertFalse(controller.locked.value)
    }

    @Test
    fun backgroundExactlyAtDelayLocks() {
        var elapsedRealtime = baseElapsedRealtime
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, elapsedRealtime = { elapsedRealtime })
        controller.onUnlocked()

        controller.onAppBackgrounded()
        elapsedRealtime += Duration.ofMinutes(5).toMillis()
        val mustLock = controller.onAppForegrounded()

        assertTrue(mustLock)
        assertTrue(controller.locked.value)
    }

    @Test
    fun backgroundAtOrBeyondDelayLocks() {
        var elapsedRealtime = baseElapsedRealtime
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, elapsedRealtime = { elapsedRealtime })
        controller.onUnlocked()

        controller.onAppBackgrounded()
        elapsedRealtime += Duration.ofMinutes(6).toMillis()
        val mustLock = controller.onAppForegrounded()

        assertTrue(mustLock)
        assertTrue(controller.locked.value)
    }

    @Test
    fun immediateDelayLocksOnAnyForeground() {
        val elapsedRealtime = baseElapsedRealtime
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.IMMEDIATE
        }
        val controller = AppLockController(settings, elapsedRealtime = { elapsedRealtime })
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
        val controller = AppLockController(settings, elapsedRealtime = { baseElapsedRealtime })
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
        var elapsedRealtime = baseElapsedRealtime
        val waits = ControlledWait()
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = controlledController(
            settings,
            waits,
            elapsedRealtime = { elapsedRealtime },
        )
        controller.onUnlocked()
        controller.onAppBackgrounded()

        elapsedRealtime += Duration.ofMinutes(5).toMillis()

        assertFalse(controller.isExternalActionAuthorized())
        assertTrue(controller.locked.value)
    }

    @Test
    fun externalActionAtMonotonicDeadlineCannotBeAuthorizedByWallClockRollback() {
        var elapsedRealtime = baseElapsedRealtime
        val waits = ControlledWait()
        val durableExpiry = DurableExpiryRecorder()
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = controlledController(
            settings = settings,
            waits = waits,
            elapsedRealtime = { elapsedRealtime },
            durableExpiry = durableExpiry,
        )
        controller.onUnlocked()
        durableExpiry.reset()

        controller.onAppBackgrounded()
        elapsedRealtime += Duration.ofMinutes(1).toMillis()
        settings.lockDelay = LockDelay.FIFTEEN_MINUTES
        elapsedRealtime += Duration.ofMinutes(14).toMillis()

        assertEquals(
            listOf(Duration.ofMinutes(5), Duration.ofMinutes(14)),
            durableExpiry.scheduled,
        )
        assertFalse(controller.isExternalActionAuthorized())
        assertTrue(controller.locked.value)
    }

    @Test
    fun externalActionBeforeDeadlineRemainsAuthorized() {
        var elapsedRealtime = baseElapsedRealtime
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, elapsedRealtime = { elapsedRealtime })
        controller.onUnlocked()
        controller.onAppBackgrounded()

        elapsedRealtime += Duration.ofMinutes(4).toMillis()

        assertTrue(controller.isExternalActionAuthorized())
        assertFalse(controller.locked.value)
    }

    @Test
    fun disabledLockAuthorizesDespiteDelayedCachedStateUpdate() {
        val dispatcher = QueuedDispatcher()
        val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = true }
        val controller = AppLockController(
            settings = settings,
            elapsedRealtime = { baseElapsedRealtime },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        assertTrue(controller.locked.value)

        settings.lockEnabled = false

        assertTrue(controller.isExternalActionAuthorized())
        assertFalse(controller.onAppForegrounded())
        assertFalse(controller.externalContentConcealed.value)
    }

    @Test
    fun foregroundCancelsBackgroundExpiry() {
        var elapsedRealtime = baseElapsedRealtime
        val waits = ControlledWait()
        val durableExpiry = DurableExpiryRecorder()
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = controlledController(
            settings = settings,
            waits = waits,
            elapsedRealtime = { elapsedRealtime },
            durableExpiry = durableExpiry,
        )
        controller.onUnlocked()
        durableExpiry.reset()
        controller.onAppBackgrounded()

        elapsedRealtime += Duration.ofMinutes(4).toMillis()
        assertFalse(controller.onAppForegrounded())
        waits.expire(0)

        assertEquals(1, durableExpiry.cancellations)
        assertFalse(controller.locked.value)
    }

    @Test
    fun lateUnlockWhileBackgroundedKeepsExpiry() {
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

        assertEquals(0, durableExpiry.cancellations)
        assertFalse(controller.locked.value)
        waits.expire(0)

        assertTrue(controller.locked.value)
    }

    @Test
    fun lateUnlockFromPreviousForegroundCannotUnlockAfterResume() {
        var elapsedRealtime = baseElapsedRealtime
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, elapsedRealtime = { elapsedRealtime })
        val staleAttempt = controller.beginUnlockAttempt()
        controller.onUnlocked(staleAttempt)
        controller.onAppBackgrounded()
        elapsedRealtime += Duration.ofMinutes(5).toMillis()
        assertTrue(controller.onAppForegrounded())

        controller.onUnlocked(staleAttempt)

        assertTrue(controller.locked.value)
    }

    @Test
    fun replacementUnlockAttemptInvalidatesThePreviousAttempt() {
        val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = true }
        val controller = AppLockController(settings, elapsedRealtime = { baseElapsedRealtime })
        val staleAttempt = controller.beginUnlockAttempt()
        val currentAttempt = controller.beginUnlockAttempt()

        controller.onUnlocked(staleAttempt)
        assertTrue(controller.locked.value)

        controller.onUnlocked(currentAttempt)
        assertFalse(controller.locked.value)
    }

    @Test
    fun backgroundImmediatelyConcealsPassiveContentDuringInAppGrace() {
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, elapsedRealtime = { baseElapsedRealtime })
        controller.onUnlocked()
        assertFalse(controller.externalContentConcealed.value)

        controller.onAppBackgrounded()

        assertTrue(controller.externalContentConcealed.value)
        assertFalse(controller.locked.value)
        assertFalse(controller.onAppForegrounded())
        assertFalse(controller.externalContentConcealed.value)
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
        var elapsedRealtime = baseElapsedRealtime
        val waits = ControlledWait()
        val durableExpiry = DurableExpiryRecorder()
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = controlledController(
            settings = settings,
            waits = waits,
            elapsedRealtime = { elapsedRealtime },
            durableExpiry = durableExpiry,
        )
        controller.onUnlocked()
        durableExpiry.reset()
        controller.onAppBackgrounded()

        elapsedRealtime += Duration.ofMinutes(1).toMillis()
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
        val controller = AppLockController(settings, elapsedRealtime = { baseElapsedRealtime })
        assertTrue(controller.locked.value)

        controller.onUnlocked()

        assertFalse(controller.locked.value)
    }

    @Test
    fun settingsChangeToDisabledUnlocks() = runBlocking {
        val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = true }
        val controller = AppLockController(settings, elapsedRealtime = { baseElapsedRealtime })
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
        elapsedRealtime: () -> Long = { baseElapsedRealtime },
        durableExpiry: DurableExpiryRecorder? = null,
    ) = AppLockController(
        settings = settings,
        elapsedRealtime = elapsedRealtime,
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
