package app.opentasks.lock

import android.content.SharedPreferences
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}

/**
 * A hand-rolled, in-memory [SharedPreferences] double -- no mocking library
 * is available, and the Android unit-test jar stubs every concrete
 * framework method body, but [SharedPreferences] and its [Editor] are pure
 * interfaces with nothing to stub, so implementing them directly runs as
 * real code under plain JVM unit tests.
 */
private class FakeSharedPreferences : SharedPreferences {
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
