package app.opentasks.lock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import app.opentasks.reminders.ReminderNotifier
import app.opentasks.widget.clearTodayWidgetTitles
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AppLockExpiryIntents {
    const val ACTION_DELIVER = "app.opentasks.action.DELIVER_APP_LOCK_EXPIRY"

    fun deliveryData(): Uri = Uri.Builder()
        .scheme("opentasks")
        .authority("app-lock")
        .appendPath("expire")
        .build()

    fun deliveryIntent(context: Context): Intent =
        Intent(context, AppLockExpiryReceiver::class.java)
            .setAction(ACTION_DELIVER)
            .setData(deliveryData())
}

@Singleton
class AppLockExpiryScheduler @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val pendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        AppLockExpiryIntents.deliveryIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(delay: Duration) {
        val triggerAt = SystemClock.elapsedRealtime() + delay.toMillis()
        if (alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
                return
            } catch (_: SecurityException) {
                // Permission changed between the check and the call.
            }
        }
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            pendingIntent,
        )
    }

    fun cancel() {
        alarmManager.cancel(pendingIntent)
    }
}

@Singleton
class ExternalLockContentConcealer internal constructor(
    private val appLockController: AppLockController,
    private val cancelActiveReminders: () -> Unit,
    private val clearTodayWidgetTitles: suspend () -> Unit,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        appLockController: AppLockController,
        reminderNotifier: ReminderNotifier,
    ) : this(
        appLockController = appLockController,
        cancelActiveReminders = reminderNotifier::cancelActiveReminders,
        clearTodayWidgetTitles = { clearTodayWidgetTitles(context) },
    )

    suspend fun concealIfUnauthorized() {
        if (appLockController.isExternalActionAuthorized()) return
        cancelActiveReminders()
        clearTodayWidgetTitles()
    }
}

internal fun isAppLockExpiryDelivery(intent: Intent): Boolean =
    intent.action == AppLockExpiryIntents.ACTION_DELIVER &&
        intent.data == AppLockExpiryIntents.deliveryData()

@AndroidEntryPoint
class AppLockExpiryReceiver : BroadcastReceiver() {
    @Inject
    lateinit var concealer: ExternalLockContentConcealer

    override fun onReceive(context: Context, intent: Intent) {
        if (!isAppLockExpiryDelivery(intent)) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                concealer.concealIfUnauthorized()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
