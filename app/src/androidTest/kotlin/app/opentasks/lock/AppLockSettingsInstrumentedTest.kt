package app.opentasks.lock

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the literal SharedPreferences keys [AppLockSettings] persists under.
 *
 * A typo in one of those key constants would not crash or fail loudly -- the
 * getter would just silently fall back to its default on the next read. A
 * round trip through [AppLockSettings] alone can never catch that: the same
 * typo'd constant is used to both write and read a given setting, so the
 * mismatch is symmetric and invisible to the wrapper's own API. This test
 * instead asserts the literal key strings directly against the raw
 * [SharedPreferences] file [AppLockSettings] writes into. A second,
 * independently constructed [AppLockSettings] is checked too, but that is a
 * weaker, secondary proof: `Context.getSharedPreferences` returns the same
 * process-cached [SharedPreferences] instance on every call within one
 * process, so this does not exercise a real fresh-process read -- only the
 * direct-key assertions above do that. A dedicated file name, cleaned up in
 * [tearDown], keeps this from leaving state behind in the real "app_lock"
 * file other instrumented tests rely on for a clean cold-start read -- the
 * same contamination class `MainActivityRecoveryRestorationInstrumentedTest`
 * defends against.
 *
 * Compile-verified only here; runs on CI's connected API 36/37 matrix.
 */
@RunWith(AndroidJUnit4::class)
class AppLockSettingsInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun freshSettings(): AppLockSettings = AppLockSettings(prefs)

    @After
    fun tearDown() {
        context.deleteSharedPreferences(PREFS_NAME)
    }

    @Test
    fun everyPersistedSettingSurvivesAFreshInstance() {
        val written = freshSettings()
        written.lockEnabled = true
        written.lockDelay = LockDelay.FIFTEEN_MINUTES
        written.titlePrivacy = true
        written.screenshotBlocking = true

        // The literal keys AppLockSettings.kt persists under -- a typo here
        // would silently reset the matching setting to its default on the
        // next read, with no crash to surface it.
        assertTrue(prefs.getBoolean("lock_enabled", false))
        assertEquals("FIFTEEN_MINUTES", prefs.getString("lock_delay", null))
        assertTrue(prefs.getBoolean("title_privacy", false))
        assertTrue(prefs.getBoolean("screenshot_blocking", false))

        val read = freshSettings()

        assertTrue(read.lockEnabled)
        assertEquals(LockDelay.FIFTEEN_MINUTES, read.lockDelay)
        assertTrue(read.titlePrivacy)
        assertTrue(read.screenshotBlocking)
    }

    private companion object {
        const val PREFS_NAME = "app_lock_settings_instrumented_test"
    }
}
