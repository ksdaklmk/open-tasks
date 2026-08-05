package app.opentasks.lock

import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.Duration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class LockDelay(val duration: Duration) {
    IMMEDIATE(Duration.ZERO),
    ONE_MINUTE(Duration.ofMinutes(1)),
    FIVE_MINUTES(Duration.ofMinutes(5)),
    FIFTEEN_MINUTES(Duration.ofMinutes(15)),
}

/**
 * Wraps the plain [SharedPreferences] file holding every app-lock and
 * title-privacy choice.
 *
 * Nothing here is vault-scoped: these settings apply to the app process as
 * a whole, independent of which vault slot -- if any -- is currently
 * active, and they change no key material.
 */
class AppLockSettings(private val prefs: SharedPreferences) {
    var lockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCK_ENABLED, value) }

    var lockDelay: LockDelay
        get() = prefs.getString(KEY_LOCK_DELAY, null)
            ?.let { stored -> LockDelay.entries.firstOrNull { it.name == stored } }
            ?: LockDelay.ONE_MINUTE
        set(value) = prefs.edit { putString(KEY_LOCK_DELAY, value.name) }

    var titlePrivacy: Boolean
        get() = prefs.getBoolean(KEY_TITLE_PRIVACY, false)
        set(value) = prefs.edit { putBoolean(KEY_TITLE_PRIVACY, value) }

    var screenshotBlocking: Boolean
        get() = prefs.getBoolean(KEY_SCREENSHOT_BLOCKING, false)
        set(value) = prefs.edit { putBoolean(KEY_SCREENSHOT_BLOCKING, value) }

    /**
     * Emits whenever any preference in this file changes.
     *
     * Built on the plain [flow] builder rather than `callbackFlow`:
     * `callbackFlow` always runs its producer in a separately dispatched
     * child coroutine (even with an atomic start), so a caller has no way
     * to know the listener below is actually registered yet. This body
     * runs inline on the collecting coroutine up to its first suspension
     * point, so [AppLockController] can force that registration to finish
     * synchronously before it does anything else.
     */
    fun observe(): Flow<Unit> = flow {
        val changes = Channel<Unit>(Channel.CONFLATED)
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changes.trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            for (change in changes) emit(change)
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    private companion object {
        const val KEY_LOCK_ENABLED = "lock_enabled"
        const val KEY_LOCK_DELAY = "lock_delay"
        const val KEY_TITLE_PRIVACY = "title_privacy"
        const val KEY_SCREENSHOT_BLOCKING = "screenshot_blocking"
    }
}
