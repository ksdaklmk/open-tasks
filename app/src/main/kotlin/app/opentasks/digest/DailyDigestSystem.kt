package app.opentasks.digest

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.opentasks.MainActivity
import app.opentasks.R
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.widget.computeTodayProjection
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DEFAULT_DIGEST_MINUTE_OF_DAY = 8 * 60

/**
 * One daily digest schedule: whether it is on, the wall-clock minute of day
 * it fires at, and the epoch day (`LocalDate.toEpochDay()`) it last fired
 * for, if any.
 *
 * Device-local timing state only -- never task text, ids, or any
 * vault/backup content.
 */
data class DailyDigestSettings(
    val enabled: Boolean = false,
    val minuteOfDay: Int = DEFAULT_DIGEST_MINUTE_OF_DAY,
    val lastHandledEpochDay: Long? = null,
)

/**
 * Wraps the plain [SharedPreferences] file holding the daily digest
 * schedule, following the same shape as
 * [app.opentasks.lock.AppLockSettings] and
 * [app.opentasks.focus.FocusSessionStore].
 *
 * Every read validates the raw stored types through [SharedPreferences.getAll]
 * rather than the typed getters: a typed getter can silently hand back its
 * own default on a type mismatch, which is exactly the failure this store
 * must never paper over. An unknown key, a wrong-typed value, or an
 * out-of-range value makes the whole schedule untrustworthy, so it is
 * cleared and rewritten to disabled/08:00, keeping only a handled day that
 * is itself valid -- this path never throws. The one exception is
 * [setMinuteOfDay], which rejects an out-of-range argument from its own
 * caller instead of ever persisting it.
 */
class DailyDigestSettingsStore(private val prefs: SharedPreferences) {
    private val mutableState = MutableStateFlow(read())

    val state: StateFlow<DailyDigestSettings> = mutableState.asStateFlow()

    fun load(): DailyDigestSettings = read().also { mutableState.value = it }

    /**
     * Writes only the enabled flag, leaving the minute of day and last
     * handled day untouched -- disabling therefore retains the handled day.
     */
    fun setEnabled(enabled: Boolean): DailyDigestSettings {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
        return load()
    }

    fun setMinuteOfDay(minuteOfDay: Int): DailyDigestSettings {
        require(minuteOfDay in 0..1439)
        prefs.edit { putInt(KEY_MINUTE_OF_DAY, minuteOfDay) }
        return load()
    }

    fun markHandled(epochDay: Long): DailyDigestSettings {
        prefs.edit { putLong(KEY_LAST_HANDLED_EPOCH_DAY, epochDay) }
        return load()
    }

    private fun read(): DailyDigestSettings {
        val raw = prefs.all
        val hasUnknownKey = raw.keys.any { it !in CANONICAL_KEYS }

        val enabledRaw = raw[KEY_ENABLED]
        val minuteRaw = raw[KEY_MINUTE_OF_DAY]
        val handledRaw = raw[KEY_LAST_HANDLED_EPOCH_DAY]

        val enabledValid = enabledRaw == null || enabledRaw is Boolean
        val minuteValid = minuteRaw == null ||
            (minuteRaw is Int && minuteRaw in 0..1439)
        val validHandledDay = (handledRaw as? Long)?.takeIf { it in HANDLED_DAY_RANGE }
        val handledValid = handledRaw == null || validHandledDay != null

        if (hasUnknownKey || !enabledValid || !minuteValid || !handledValid) {
            prefs.edit {
                clear()
                putBoolean(KEY_ENABLED, false)
                putInt(KEY_MINUTE_OF_DAY, DEFAULT_DIGEST_MINUTE_OF_DAY)
                if (validHandledDay != null) {
                    putLong(KEY_LAST_HANDLED_EPOCH_DAY, validHandledDay)
                }
            }
            return DailyDigestSettings(lastHandledEpochDay = validHandledDay)
        }

        return DailyDigestSettings(
            enabled = enabledRaw ?: false,
            minuteOfDay = minuteRaw ?: DEFAULT_DIGEST_MINUTE_OF_DAY,
            lastHandledEpochDay = handledRaw,
        )
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_MINUTE_OF_DAY = "minute_of_day"
        const val KEY_LAST_HANDLED_EPOCH_DAY = "last_handled_epoch_day"
        val CANONICAL_KEYS = setOf(KEY_ENABLED, KEY_MINUTE_OF_DAY, KEY_LAST_HANDLED_EPOCH_DAY)

        // A following occurrence (last handled day + 1) must stay a
        // representable LocalDate, so the day before LocalDate.MAX is the
        // upper bound.
        val HANDLED_DAY_RANGE = LocalDate.MIN.toEpochDay()..(LocalDate.MAX.toEpochDay() - 1)
    }
}

/**
 * The next one-shot instant the daily digest should fire at, given
 * [minuteOfDay] wall-clock minutes past midnight in [zone], as of [now].
 *
 * Computed as a wall-clock time on a date, not as a duration added to
 * start-of-day: adding minutes to a UTC start-of-day instant would fire at
 * the wrong wall time across a DST transition. [lastHandledEpochDay] bounds
 * the candidate date forward past any day already handled, so a rewound
 * device clock cannot re-fire a digest already delivered for that day.
 */
internal fun nextDailyDigestOccurrence(
    minuteOfDay: Int,
    now: Instant,
    zone: ZoneId,
    lastHandledEpochDay: Long? = null,
): Instant {
    require(minuteOfDay in 0..1439)
    val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    var date = LocalDate.ofInstant(now, zone)
    if (lastHandledEpochDay != null && date.toEpochDay() <= lastHandledEpochDay) {
        date = LocalDate.ofEpochDay(Math.addExact(lastHandledEpochDay, 1L))
    }
    var candidate = date.atTime(time).atZone(zone).toInstant()
    if (!candidate.isAfter(now)) {
        date = date.plusDays(1)
        candidate = date.atTime(time).atZone(zone).toInstant()
    }
    return candidate
}

/**
 * The two title-free counts the daily digest notification would show.
 *
 * Carries only [openTodayCount] and [overdueCount] -- no title, task, or
 * project field belongs here, because the digest notification itself is
 * never permitted to carry titles.
 */
data class DailyDigestNotificationPlan(
    val openTodayCount: Int,
    val overdueCount: Int,
)

/**
 * Plans the daily digest notification for [snapshot] as of [now] in [zone],
 * or `null` when there is nothing to say.
 *
 * Delegates to [computeTodayProjection] exactly once with
 * `titlesPermitted = false` and copies only its two counts -- the digest is
 * a background notification, never a surface that has earned title
 * privacy's unlock/foreground signal.
 */
internal fun dailyDigestNotificationPlan(
    snapshot: WorkspaceSnapshot,
    now: Instant,
    zone: ZoneId,
): DailyDigestNotificationPlan? {
    val projection = computeTodayProjection(
        snapshot = snapshot,
        today = LocalDate.ofInstant(now, zone),
        zone = zone,
        now = now,
        titlesPermitted = false,
    )
    if (projection.openTodayCount == 0 && projection.overdueCount == 0) return null
    return DailyDigestNotificationPlan(
        openTodayCount = projection.openTodayCount,
        overdueCount = projection.overdueCount,
    )
}

/**
 * The daily digest's two intent identities.
 *
 * The delivery intent is always explicit -- it names [DailyDigestReceiver]
 * itself -- and carries the one stable `opentasks://digest/deliver` data, so
 * a single [PendingIntent] identity covers arming and cancelling. Neither
 * intent carries a task, project, count, or vault extra: the digest is a
 * private surface, and its intents are timing and navigation only.
 */
object DailyDigestIntents {
    const val ACTION_DELIVER = "app.opentasks.action.DELIVER_DAILY_DIGEST"
    const val ACTION_OPEN_HOME = "app.opentasks.action.OPEN_DAILY_DIGEST_HOME"

    fun deliveryData(): Uri = data("deliver")

    fun deliveryIntent(context: Context): Intent =
        Intent(context, DailyDigestReceiver::class.java)
            .setAction(ACTION_DELIVER)
            .setData(deliveryData())

    fun homeIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_HOME)
            .setData(data("open"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun data(verb: String): Uri =
        Uri.Builder()
            .scheme("opentasks")
            .authority("digest")
            .appendPath(verb)
            .build()
}

/**
 * Keeps at most one pending daily digest alarm armed.
 *
 * The digest is a convenience, never a deadline, so it is scheduled only
 * with [AlarmManager.setAndAllowWhileIdle] -- there is no exact-alarm mode,
 * no exact-alarm permission check, no repeating alarm, no worker, and no
 * service here. One stable immutable broadcast [PendingIntent] identity
 * means arming replaces the previous alarm rather than adding to it, and
 * cancelling addresses exactly the same alarm.
 */
@Singleton
class DailyDigestScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /**
     * Arms the next occurrence for [settings], or cancels when the digest is
     * off or its stored minute of day is not a wall-clock minute -- an
     * unusable schedule leaves no alarm behind rather than a guessed one.
     */
    fun reconcile(
        settings: DailyDigestSettings,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (!settings.enabled || settings.minuteOfDay !in VALID_MINUTES_OF_DAY) {
            cancel()
            return
        }
        val next = nextDailyDigestOccurrence(
            minuteOfDay = settings.minuteOfDay,
            now = now,
            zone = zone,
            lastHandledEpochDay = settings.lastHandledEpochDay,
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            next.toEpochMilli(),
            deliveryPendingIntent(),
        )
    }

    fun cancel() {
        alarmManager.cancel(deliveryPendingIntent())
    }

    private fun deliveryPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        DailyDigestIntents.deliveryIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        val VALID_MINUTES_OF_DAY = 0..1439
    }
}

/**
 * The digest's own notification channel, separate from the reminder channel
 * so muting one never silently mutes the other.
 */
object DailyDigestNotifications {
    const val CHANNEL_ID = "daily_digest"

    fun areEnabled(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)
                ?.importance != NotificationManager.IMPORTANCE_NONE

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.daily_digest_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.daily_digest_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}

/**
 * Posts the one daily digest notification.
 *
 * The notification never carries a task title, id, or project: its private
 * content is the same two resource-formatted counts the Today widget shows,
 * and its lock-screen-public version is entirely generic, with no counts at
 * all. Tapping it asks only to open Home.
 */
@Singleton
class DailyDigestNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    @SuppressLint("MissingPermission")
    fun show(plan: DailyDigestNotificationPlan) {
        if (!DailyDigestNotifications.areEnabled(context)) return
        DailyDigestNotifications.createChannel(context)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(plan))
    }

    internal fun build(plan: DailyDigestNotificationPlan): Notification {
        val publicNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.daily_digest_public_title))
            .setContentText(context.getString(R.string.reminder_public_text))
            .build()

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.today_widget_label))
            .setContentText(
                context.getString(
                    R.string.today_widget_counts,
                    plan.openTodayCount,
                    plan.overdueCount,
                ),
            )
            .setContentIntent(homePendingIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .build()
    }

    private fun homePendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        DailyDigestIntents.homeIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val CHANNEL_ID = DailyDigestNotifications.CHANNEL_ID

        // There is only ever one digest notification, and it replaces itself.
        val NOTIFICATION_ID = CHANNEL_ID.hashCode() and Int.MAX_VALUE
    }
}

/**
 * Serialises every daily digest schedule change and the one delivery, so a
 * delivery's re-arm can never race a settings change into a lost or
 * duplicated alarm.
 *
 * Delivery order inside the lock is deliberate and load-bearing: the day is
 * marked handled, then the next alarm is armed, and only then is a vault
 * repository resolved. Everything after the mark is best effort -- a missing
 * vault runtime, a revoked notification permission, and a day with nothing
 * to say all leave today handled and tomorrow armed rather than retrying,
 * because a retry would be a second digest for the same day.
 *
 * The repository is a [Provider], never a `Lazy`: the Hilt binding for
 * [VaultRepository] is deliberately unscoped so every resolution hands back
 * the currently active vault runtime, and a singleton-held `Lazy` would
 * instead cache the first vault across an in-process slot replacement.
 */
@Singleton
class DailyDigestCoordinator internal constructor(
    private val store: DailyDigestSettingsStore,
    private val repository: Provider<VaultRepository>,
    private val reconcileAlarm: (DailyDigestSettings, Instant, ZoneId) -> Unit,
    private val cancelAlarm: () -> Unit,
    private val post: (DailyDigestNotificationPlan) -> Unit,
    private val now: () -> Instant,
    private val zone: () -> ZoneId,
) {
    @Inject
    constructor(
        store: DailyDigestSettingsStore,
        scheduler: DailyDigestScheduler,
        notifier: DailyDigestNotifier,
        repository: Provider<VaultRepository>,
    ) : this(
        store = store,
        repository = repository,
        reconcileAlarm = scheduler::reconcile,
        cancelAlarm = scheduler::cancel,
        post = notifier::show,
        now = Instant::now,
        zone = ZoneId::systemDefault,
    )

    private val mutex = Mutex()

    val settings: StateFlow<DailyDigestSettings> = store.state

    suspend fun setEnabled(enabled: Boolean) {
        mutex.withLock { rearm(store.setEnabled(enabled)) }
    }

    suspend fun setMinuteOfDay(minuteOfDay: Int) {
        mutex.withLock { rearm(store.setMinuteOfDay(minuteOfDay)) }
    }

    suspend fun reconcile() {
        mutex.withLock { rearm(store.load()) }
    }

    suspend fun handleDelivery() {
        mutex.withLock {
            val current = store.load()
            if (!current.enabled) {
                cancelAlarm()
                return@withLock
            }
            val instant = now()
            val timeZone = zone()
            val today = LocalDate.ofInstant(instant, timeZone).toEpochDay()
            val handled = current.lastHandledEpochDay
            if (handled != null && today <= handled) {
                // Already delivered for this day -- or the device clock was
                // rewound into one. Re-arm only; a second digest for a day
                // the user has already seen is worse than none.
                rearm(current, instant, timeZone)
                return@withLock
            }

            // Mark first, then re-arm, and only then look at the vault: the
            // schedule is device-local and must survive whatever the vault
            // does next, so neither a missing runtime nor a failed post can
            // leave tomorrow unarmed or today eligible to fire again.
            val marked = store.markHandled(today)
            rearm(marked, instant, timeZone)

            val snapshot = try {
                repository.get().currentWorkspace()
            } catch (_: IllegalStateException) {
                // No active vault runtime: today is still handled.
                return@withLock
            }
            val plan = dailyDigestNotificationPlan(snapshot, instant, timeZone)
                ?: return@withLock
            try {
                post(plan)
            } catch (_: SecurityException) {
                // Notification access withdrawn between the check and the
                // post: today is still handled.
            }
        }
    }

    private fun rearm(
        settings: DailyDigestSettings,
        instant: Instant = now(),
        timeZone: ZoneId = zone(),
    ) {
        if (settings.enabled) reconcileAlarm(settings, instant, timeZone) else cancelAlarm()
    }
}

/**
 * Receives the one daily digest alarm.
 *
 * Non-exported and filter-free in the manifest, and validated here as well:
 * anything but the exact canonical delivery action and data is ignored
 * outright.
 */
@AndroidEntryPoint
class DailyDigestReceiver : BroadcastReceiver() {
    @Inject
    lateinit var coordinator: DailyDigestCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DailyDigestIntents.ACTION_DELIVER ||
            intent.data != DailyDigestIntents.deliveryData()
        ) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                coordinator.handleDelivery()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
