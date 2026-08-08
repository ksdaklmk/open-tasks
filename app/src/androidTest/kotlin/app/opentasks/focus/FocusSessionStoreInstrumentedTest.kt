package app.opentasks.focus

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Pins the literal SharedPreferences keys [FocusSessionStore] persists under,
 * and its fail-closed reaction to values it cannot interpret.
 *
 * The direct-key assertions matter for the same reason
 * `AppLockSettingsInstrumentedTest` makes them: a typo in one key constant is
 * symmetric across the wrapper's own write and read, so only raw preference
 * reads can catch it. The corruption cases matter because a half-understood
 * session would otherwise drive real timer transitions on a task, phase, or
 * boundary the store never actually stored.
 *
 * A dedicated file name keeps the real "focus_session" file other tests and
 * the product read from untouched.
 *
 * Compile-verified only here; runs on CI's connected API 36/37 matrix.
 */
@RunWith(AndroidJUnit4::class)
class FocusSessionStoreInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun freshStore(): FocusSessionStore = FocusSessionStore(prefs)

    @After
    fun tearDown() {
        context.deleteSharedPreferences(PREFS_NAME)
    }

    @Test
    fun everyPersistedFieldSurvivesAFreshStore() {
        val session = FocusSession(
            taskId = "task-42",
            preset = FocusPreset.FIFTY_TEN,
            phase = FocusPhaseKind.REST,
            phaseEndsAt = Instant.parse("2026-08-08T09:35:00Z"),
        )

        freshStore().save(session)

        // The literal keys FocusSessionStore.kt persists under.
        assertEquals("task-42", prefs.getString("task_id", null))
        assertEquals("FIFTY_TEN", prefs.getString("preset", null))
        assertEquals("REST", prefs.getString("phase", null))
        assertEquals(
            session.phaseEndsAt.toEpochMilli(),
            prefs.getLong("phase_end", 0L),
        )

        val reloaded = freshStore()

        assertEquals(session, reloaded.load())
        assertEquals(session, reloaded.session.value)
    }

    @Test
    fun anUnknownPresetClearsEveryStoredKey() {
        assertClearedBy { putString("preset", "NINETY_TWENTY") }
    }

    @Test
    fun anUnknownPhaseClearsEveryStoredKey() {
        assertClearedBy { putString("phase", "SKIPPED") }
    }

    @Test
    fun aMalformedEndInstantClearsEveryStoredKey() {
        assertClearedBy { putString("phase_end", "not-an-instant") }
    }

    private fun assertClearedBy(corrupt: SharedPreferences.Editor.() -> Unit) {
        freshStore().save(
            FocusSession(
                taskId = "task-42",
                preset = FocusPreset.TWENTY_FIVE_FIVE,
                phase = FocusPhaseKind.FOCUS,
                phaseEndsAt = Instant.parse("2026-08-08T09:25:00Z"),
            ),
        )
        prefs.edit(action = corrupt)

        val store = freshStore()

        assertNull(store.load())
        assertNull(store.session.value)
        assertFalse(prefs.contains("task_id"))
        assertFalse(prefs.contains("preset"))
        assertFalse(prefs.contains("phase"))
        assertFalse(prefs.contains("phase_end"))
    }

    private companion object {
        const val PREFS_NAME = "focus_session_instrumented_test"
    }
}
