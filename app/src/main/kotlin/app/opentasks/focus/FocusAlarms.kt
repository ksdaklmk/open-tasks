package app.opentasks.focus

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
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.opentasks.R
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.TaskId
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

object FocusIntents {
    const val ACTION_BOUNDARY = "app.opentasks.action.FOCUS_PHASE_BOUNDARY"

    fun boundaryData(): Uri = Uri.Builder()
        .scheme("opentasks")
        .authority("focus")
        .appendPath("boundary")
        .build()
}

/**
 * Schedules the single alarm that ends the current focus phase.
 *
 * One session means one pending alarm, so the delivery intent's [Uri] data is
 * constant and `FLAG_UPDATE_CURRENT` re-targets that same alarm on every
 * re-arm, exactly as [app.opentasks.reminders.ReminderScheduler] does per
 * reminder.
 */
@Singleton
class FocusAlarms @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(session: FocusSession) {
        val pendingIntent = boundaryIntent()
        val triggerAtMillis = session.phaseEndsAt.toEpochMilli()
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        }
    }

    fun cancel() {
        alarmManager.cancel(boundaryIntent())
    }

    private fun boundaryIntent(): PendingIntent {
        val intent = Intent(context, FocusAlarmReceiver::class.java)
            .setAction(FocusIntents.ACTION_BOUNDARY)
            .setData(FocusIntents.boundaryData())
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

object FocusNotifications {
    const val CHANNEL_ID = "focus_sessions"

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
            context.getString(R.string.focus_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.focus_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}

/**
 * Posts the one generic alert a phase boundary is allowed to show.
 *
 * The copy is fixed and carries no task text, project name, or timing, so the
 * notification is safe at any lock-screen visibility and needs no privacy
 * conditioning. A missing permission, a disabled channel, or a
 * [SecurityException] skips only the alert -- never the phase transition
 * itself.
 */
@Singleton
class FocusNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun canPostNotifications(): Boolean = FocusNotifications.areEnabled(context)

    @SuppressLint("MissingPermission")
    fun notifyPhaseStarted(phase: FocusPhaseKind) {
        if (!canPostNotifications()) return
        FocusNotifications.createChannel(context)
        val message = when (phase) {
            FocusPhaseKind.REST -> R.string.focus_phase_ended_focus
            FocusPhaseKind.FOCUS -> R.string.focus_phase_ended_rest
        }
        val notification = NotificationCompat.Builder(context, FocusNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(message))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Alert only: the phase has already advanced and re-armed.
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 0x0F0C05
    }
}

/**
 * Applies a focus phase to the world: persistence, alarm, alert, and -- only
 * ever through [focusTimerAction] -- the vault's own timer.
 *
 * The boundary receiver and the foreground reconciler both come through here,
 * so neither restates the ownership rule. The vault repository arrives as a
 * supplier rather than an injected instance because a boundary can be
 * delivered while no vault runtime is active; resolving it throws
 * [IllegalStateException] there, which callers treat as "the phase advanced,
 * the timer was left alone".
 *
 * This is a process singleton reachable from several threads at once -- a
 * delivered boundary on `Dispatchers.IO`, a foregrounding on the view-model
 * scope, a person pressing Stop -- and every entry point below both reads the
 * store and may go on to dispatch a timer command across a suspension. Two
 * guards keep that safe, and neither adds any decision of its own:
 *
 *  - [gate] serialises whole operations, so two advances cannot each conclude
 *    "start the timer" from the same empty-timer reading and then have the
 *    loser's rejection tear down a perfectly live cycle.
 *  - [focusSessionStillCurrent] is re-checked against the store immediately
 *    before any dispatch, so a conclusion drawn before a Stop or a replacement
 *    landed is abandoned rather than applied. Without it, a Stop pressed inside
 *    an in-flight advance could be followed by that advance starting the very
 *    timer the person just asked to end.
 */
@Singleton
class FocusCoordinator @Inject constructor(
    private val store: FocusSessionStore,
    private val alarms: FocusAlarms,
    private val controller: FocusSessionController,
    private val notifier: FocusNotifier,
) {
    private val gate = Mutex()

    /** Persists a fresh cycle and arms its first boundary. */
    suspend fun start(taskId: String, preset: FocusPreset) = gate.withLock {
        val session = controller.start(taskId, preset)
        store.save(session)
        alarms.schedule(session)
    }

    /**
     * Ends the cycle and returns the session that was running, or `null` when
     * none was. Reading and clearing share the lock, so the caller's own
     * follow-up decision about the vault's timer is made against a session no
     * concurrent advance can still be acting on.
     */
    suspend fun stop(): FocusSession? = gate.withLock {
        val stopped = store.load()
        clearFocus()
        stopped
    }

    suspend fun onBoundary(repository: () -> VaultRepository) = gate.withLock {
        advance(repository, alert = true)
    }

    suspend fun reconcile(repository: () -> VaultRepository) = gate.withLock {
        advance(repository, alert = false)
    }

    // Callers hold `gate` for the whole operation, dispatch included.
    private suspend fun advance(repository: () -> VaultRepository, alert: Boolean) {
        val stored = store.load()
        if (stored == null) {
            alarms.cancel()
            return
        }
        val advanced = controller.reconcile(stored)
        if (advanced == null) {
            clearFocus()
            return
        }
        if (advanced != stored) {
            store.save(advanced)
        }
        alarms.schedule(advanced)
        if (alert && advanced.phase != stored.phase) {
            notifier.notifyPhaseStarted(advanced.phase)
        }
        applyTimerOwnership(advanced, repository())
    }

    private suspend fun applyTimerOwnership(
        session: FocusSession,
        repository: VaultRepository,
    ) {
        val snapshot = repository.currentWorkspace()
        val task = snapshot.tasks.firstOrNull { it.id.value == session.taskId }
        val action = focusTimerAction(
            session = session,
            activeTimerTaskId = snapshot.home.activeTimer?.taskId?.value,
            sessionTaskAvailable = task != null && task.deletedAt == null,
        )
        when (action) {
            FocusTimerAction.NONE -> Unit
            FocusTimerAction.CLEAR_SESSION -> clearFocus()
            FocusTimerAction.START -> dispatchIfStillCurrent(session) {
                repository.execute(DomainCommand.StartTimer(TaskId(session.taskId)))
            }
            FocusTimerAction.STOP -> dispatchIfStillCurrent(session) {
                repository.execute(DomainCommand.StopTimerIfOwned(TaskId(session.taskId)))
            }
        }
    }

    /**
     * Applies a decision only while the session it was drawn from is still the
     * persisted one.
     *
     * An aborted dispatch clears nothing: the session that superseded this one
     * is live and owns its own alarm. A dispatch that genuinely runs and is
     * refused does clear, because the refusal means the world no longer matches
     * the session -- the cycle is abandoned rather than retried, and the timer
     * stays exactly as the repository left it.
     */
    private suspend fun dispatchIfStillCurrent(
        session: FocusSession,
        dispatch: suspend () -> CommandResult,
    ) {
        if (!focusSessionStillCurrent(store.load(), session)) return
        if (dispatch() !is CommandResult.Success) clearFocus()
    }

    private fun clearFocus() {
        store.clear()
        alarms.cancel()
    }
}

@AndroidEntryPoint
class FocusAlarmReceiver : BroadcastReceiver() {
    // Lazy: a boundary can arrive while no vault runtime is active, and no
    // repository may be constructed outside an active one.
    @Inject
    lateinit var vaultRepository: Lazy<VaultRepository>

    @Inject
    lateinit var coordinator: FocusCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FocusIntents.ACTION_BOUNDARY) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                coordinator.onBoundary(vaultRepository::get)
            } catch (_: IllegalStateException) {
                // No active vault runtime: the phase advanced and re-armed,
                // and no timer was touched.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
