package app.opentasks.lock

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the literal SharedPreferences keys [AppLockSettings] persists under.
 *
 * A typo in one of those key constants would not crash or fail loudly -- the
 * getter would just silently fall back to its default on the next read, so
 * this round-trips every persisted setting through a second, independently
 * constructed instance sharing the same real preferences file, the same way
 * a fresh process would see whatever the previous one wrote.
 *
 * Compile-verified only here; runs on CI's connected API 36/37 matrix.
 */
@RunWith(AndroidJUnit4::class)
class AppLockSettingsInstrumentedTest {
    private fun freshSettings(): AppLockSettings = AppLockSettings(
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("app_lock", Context.MODE_PRIVATE),
    )

    @Test
    fun everyPersistedSettingSurvivesAFreshInstance() {
        val written = freshSettings()
        written.lockEnabled = true
        written.lockDelay = LockDelay.FIFTEEN_MINUTES
        written.titlePrivacy = true
        written.screenshotBlocking = true

        val read = freshSettings()

        assertTrue(read.lockEnabled)
        assertEquals(LockDelay.FIFTEEN_MINUTES, read.lockDelay)
        assertTrue(read.titlePrivacy)
        assertTrue(read.screenshotBlocking)
    }
}
