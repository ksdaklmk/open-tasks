package app.opentasks.digest

import android.content.SharedPreferences
import androidx.core.content.edit
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.widget.computeTodayProjection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
