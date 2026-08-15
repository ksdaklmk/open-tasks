package app.opentasks.digest

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.opentasks.MainActivity
import app.opentasks.R
import app.opentasks.reminders.ReminderNotifications
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the daily digest's Android-side declarations: the literal preference
 * vocabulary the schedule persists under, the receiver's manifest scope, the
 * channel's independence from the reminder channel, and the strict split
 * between the private notification's counts and its generic public version.
 *
 * These are exactly the properties a host test cannot see -- real
 * `SharedPreferences`, the merged manifest, and a real built `Notification`.
 *
 * A dedicated preference file name keeps the real "daily_digest" file the
 * product reads from untouched.
 *
 * Compile-verified only here; runs on CI's connected API 36/37 matrix.
 */
@RunWith(AndroidJUnit4::class)
class DailyDigestSystemInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        context.deleteSharedPreferences(PREFS_NAME)
    }

    @Test
    fun preferenceStoreUsesOnlyTheThreeCanonicalKeys() {
        val store = DailyDigestSettingsStore(prefs)
        val handledDay = LocalDate.of(2026, 8, 15).toEpochDay()

        store.setEnabled(true)
        store.setMinuteOfDay(9 * 60)
        store.markHandled(handledDay)

        // The literal keys DailyDigestSystem.kt persists under.
        assertEquals(true, prefs.all["enabled"])
        assertEquals(9 * 60, prefs.all["minute_of_day"])
        assertEquals(handledDay, prefs.all["last_handled_epoch_day"])
        assertEquals(CANONICAL_KEYS, prefs.all.keys)

        // A stray key makes the whole schedule untrustworthy, so a fresh
        // store clears it and falls back to disabled/08:00.
        prefs.edit().putString("mystery", "value").apply()

        assertEquals(
            DailyDigestSettings(lastHandledEpochDay = handledDay),
            DailyDigestSettingsStore(prefs).load(),
        )
        assertEquals(CANONICAL_KEYS, prefs.all.keys)
    }

    @Test
    fun dailyDigestReceiverIsNotExportedAndHasNoIntentFilter() {
        val component = ComponentName(context, DailyDigestReceiver::class.java)

        val receiver = context.packageManager.getReceiverInfo(component, 0)

        assertFalse(receiver.exported)

        // No intent filter at all: the one delivery intent is explicit, so
        // the same action expressed implicitly must resolve to nothing here.
        val implicit = Intent(DailyDigestIntents.ACTION_DELIVER)
            .setData(DailyDigestIntents.deliveryData())
        val matches = context.packageManager.queryBroadcastReceivers(
            implicit,
            PackageManager.ResolveInfoFlags.of(0L),
        )

        assertTrue(matches.none { it.activityInfo.name == component.className })
    }

    @Test
    fun dailyDigestChannelIsIndependentAndDefaultImportance() {
        DailyDigestNotifications.createChannel(context)

        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(DailyDigestNotifications.CHANNEL_ID)

        assertEquals("daily_digest", DailyDigestNotifications.CHANNEL_ID)
        assertNotEquals(ReminderNotifications.CHANNEL_ID, DailyDigestNotifications.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun privateNotificationContainsCountsAndPublicVersionIsGeneric() {
        val notification = DailyDigestNotifier(context).build(
            DailyDigestNotificationPlan(openTodayCount = 3, overdueCount = 1),
        )

        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(
            context.getString(R.string.today_widget_label),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            context.getString(R.string.today_widget_counts, 3, 1),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )

        val public = notification.publicVersion
        val publicTitle = public.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val publicText = public.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        assertEquals(context.getString(R.string.daily_digest_public_title), publicTitle)
        assertEquals(context.getString(R.string.reminder_public_text), publicText)
        assertTrue(publicTitle!!.none(Char::isDigit))
        assertTrue(publicText!!.none(Char::isDigit))
    }

    @Test
    fun digestContentIntentRequestsHomeWithoutWorkspacePayload() {
        val intent = DailyDigestIntents.homeIntent(context)

        assertEquals("app.opentasks.action.OPEN_DAILY_DIGEST_HOME", intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        // No task id, count, project, or vault payload rides along.
        assertNull(intent.extras)
    }

    private companion object {
        const val PREFS_NAME = "daily_digest_instrumented_test"
        val CANONICAL_KEYS = setOf("enabled", "minute_of_day", "last_handled_epoch_day")
    }
}
