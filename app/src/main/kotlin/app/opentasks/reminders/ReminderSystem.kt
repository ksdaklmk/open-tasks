package app.opentasks.reminders

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
import androidx.core.content.edit
import app.opentasks.MainActivity
import app.opentasks.R
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import app.opentasks.lock.AppLockController
import app.opentasks.lock.AppLockSettings
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class ReminderScheduleMode {
    EXACT,
    INEXACT,
}

internal fun reminderScheduleMode(
    preciseRequested: Boolean,
    preciseAccessGranted: Boolean,
): ReminderScheduleMode =
    if (preciseRequested && preciseAccessGranted) {
        ReminderScheduleMode.EXACT
    } else {
        ReminderScheduleMode.INEXACT
    }

@Singleton
class ReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val preferences = context.getSharedPreferences(
        SCHEDULE_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun canSchedulePrecise(): Boolean = alarmManager.canScheduleExactAlarms()

    fun reconcile(
        snapshot: WorkspaceSnapshot,
        now: Instant = Instant.now(),
    ) {
        val activeTaskIds = snapshot.tasks
            .asSequence()
            .filter { !it.isCompleted && it.deletedAt == null }
            .mapTo(hashSetOf(), Task::id)
        val desired = snapshot.reminders
            .filter { it.taskId in activeTaskIds && it.triggerAt.instant.isAfter(now) }
            .associateBy(Reminder::id)
        val previouslyScheduled = preferences
            .getStringSet(SCHEDULED_IDS, emptySet())
            .orEmpty()
            .toSet()

        (previouslyScheduled - desired.keys).forEach(::cancel)
        desired.values.forEach(::schedule)
        preferences.edit { putStringSet(SCHEDULED_IDS, desired.keys) }
    }

    fun cancel(reminderId: String) {
        alarmManager.cancel(deliveryIntent(reminderId))
    }

    private fun schedule(reminder: Reminder) {
        val pendingIntent = deliveryIntent(reminder.id)
        when (reminderScheduleMode(reminder.precise, canSchedulePrecise())) {
            ReminderScheduleMode.EXACT -> {
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        reminder.triggerAt.instant.toEpochMilli(),
                        pendingIntent,
                    )
                } catch (_: SecurityException) {
                    scheduleInexact(reminder, pendingIntent)
                }
            }
            ReminderScheduleMode.INEXACT -> scheduleInexact(reminder, pendingIntent)
        }
    }

    private fun scheduleInexact(
        reminder: Reminder,
        pendingIntent: PendingIntent,
    ) {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            reminder.triggerAt.instant.toEpochMilli(),
            pendingIntent,
        )
    }

    private fun deliveryIntent(reminderId: String): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(ReminderIntents.ACTION_DELIVER)
            .setData(ReminderIntents.data("deliver", reminderId))
            .putExtra(ReminderIntents.EXTRA_REMINDER_ID, reminderId)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val SCHEDULE_PREFERENCES = "reminder_schedules"
        const val SCHEDULED_IDS = "scheduled_ids"
    }
}

object ReminderNotifications {
    const val CHANNEL_ID = "task_reminders"

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
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}

@Singleton
class ReminderNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appLockSettings: AppLockSettings,
    private val appLockController: AppLockController,
) {
    fun canPostNotifications(): Boolean = ReminderNotifications.areEnabled(context)

    @SuppressLint("MissingPermission")
    fun show(
        task: Task,
        reminder: Reminder,
        projectName: String?,
    ) {
        if (!canPostNotifications()) return
        ReminderNotifications.createChannel(context)

        // Title privacy and an active lock both conceal task text from the
        // notification's main content, not only from its lock-screen-public
        // version below.
        val concealed = appLockSettings.titlePrivacy || appLockController.locked.value

        val publicNotification = NotificationCompat.Builder(
            context,
            ReminderNotifications.CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_public_title))
            .setContentText(context.getString(R.string.reminder_public_text))
            .build()

        val builder = NotificationCompat.Builder(context, ReminderNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (concealed) context.getString(R.string.reminder_public_title) else task.title,
            )
            .setContentText(
                if (concealed) {
                    context.getString(R.string.reminder_public_text)
                } else {
                    reminderContext(task, projectName)
                },
            )
            .setContentIntent(openTaskIntent(task.id, reminder.id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .addAction(
                R.drawable.ic_notification,
                context.getString(R.string.reminder_action_snooze),
                actionIntent(
                    action = ReminderIntents.ACTION_SNOOZE,
                    verb = "snooze",
                    task = task,
                    reminder = reminder,
                ),
            )

        if (!task.isBlocked) {
            builder.addAction(
                R.drawable.ic_notification,
                context.getString(R.string.reminder_action_complete),
                actionIntent(
                    action = ReminderIntents.ACTION_COMPLETE,
                    verb = "complete",
                    task = task,
                    reminder = reminder,
                ),
            )
        }

        NotificationManagerCompat.from(context).notify(
            notificationId(reminder.id),
            builder.build(),
        )
    }

    fun cancel(reminderId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(reminderId))
    }

    private fun reminderContext(task: Task, projectName: String?): String {
        val due = task.due?.let { moment ->
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(Locale.UK)
                .format(moment.instant.atZone(ZoneId.of(moment.zoneId)))
        }
        return listOfNotNull(due, projectName).joinToString(" • ")
            .ifBlank { context.getString(R.string.reminder_notification_fallback) }
    }

    private fun openTaskIntent(taskId: TaskId, reminderId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ReminderIntents.ACTION_OPEN_TASK)
            .setData(ReminderIntents.data("open", reminderId))
            .putExtra(ReminderIntents.EXTRA_TASK_ID, taskId.value)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(
        action: String,
        verb: String,
        task: Task,
        reminder: Reminder,
    ): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(action)
            .setData(ReminderIntents.data(verb, reminder.id))
            .putExtra(ReminderIntents.EXTRA_TASK_ID, task.id.value)
            .putExtra(ReminderIntents.EXTRA_REMINDER_ID, reminder.id)
            .putExtra(ReminderIntents.EXTRA_PRECISE, reminder.precise)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationId(reminderId: String): Int =
        reminderId.hashCode() and Int.MAX_VALUE
}

@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {
    // Lazy: a broadcast can arrive while no vault runtime is active, and no
    // repository may be constructed outside an active one.
    @Inject
    lateinit var vaultRepository: Lazy<VaultRepository>

    @Inject
    lateinit var scheduler: ReminderScheduler

    @Inject
    lateinit var notifier: ReminderNotifier

    private val repository: VaultRepository
        get() = vaultRepository.get()

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ReminderIntents.ACTION_DELIVER -> deliver(intent)
                    ReminderIntents.ACTION_SNOOZE -> snooze(intent)
                    ReminderIntents.ACTION_COMPLETE -> complete(intent)
                }
            } catch (_: IllegalStateException) {
                // No active vault runtime: the reminder is dropped, not repaired.
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun deliver(intent: Intent) {
        val reminderId = intent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID) ?: return
        val snapshot = repository.currentWorkspace()
        val reminder = snapshot.reminders.firstOrNull { it.id == reminderId } ?: return
        val task = snapshot.tasks.firstOrNull { it.id == reminder.taskId } ?: return
        if (task.isCompleted || task.deletedAt != null) return
        val projectName = task.projectId?.let { projectId ->
            snapshot.projects.firstOrNull { it.id == projectId }?.name
        }
        notifier.show(task, reminder, projectName)
    }

    private suspend fun snooze(intent: Intent) {
        val taskId = intent.getStringExtra(ReminderIntents.EXTRA_TASK_ID)?.let(::TaskId) ?: return
        val reminderId = intent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID) ?: return
        val precise = intent.getBooleanExtra(ReminderIntents.EXTRA_PRECISE, false)
        val snapshot = repository.currentWorkspace()
        val task = snapshot.tasks.firstOrNull { it.id == taskId } ?: return
        val zoneId = task.due?.zoneId ?: ZoneId.systemDefault().id
        val scheduledAt = Instant.now().plus(SNOOZE_DURATION)
        val result = repository.execute(
            DomainCommand.SetTaskReminder(
                taskId = taskId,
                triggerAt = ZonedMoment(
                    instant = scheduledAt,
                    zoneId = zoneId,
                ),
                precise = precise,
            ),
        )
        notifier.cancel(reminderId)
        val refreshed = if (result is CommandResult.Success) {
            awaitWorkspace { workspace ->
                workspace.reminders.any {
                    it.taskId == taskId && it.triggerAt.instant == scheduledAt
                }
            }
        } else {
            repository.currentWorkspace()
        }
        scheduler.reconcile(refreshed)
    }

    private suspend fun complete(intent: Intent) {
        val taskId = intent.getStringExtra(ReminderIntents.EXTRA_TASK_ID)?.let(::TaskId) ?: return
        val reminderId = intent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID) ?: return
        val result = repository.execute(DomainCommand.CompleteTask(taskId))
        val refreshed = when (result) {
            is CommandResult.Success -> {
                notifier.cancel(reminderId)
                awaitWorkspace { workspace ->
                    workspace.tasks.firstOrNull { it.id == taskId }?.isCompleted == true
                }
            }
            is CommandResult.Rejected -> repository.currentWorkspace()
        }
        scheduler.reconcile(refreshed)
    }

    private suspend fun awaitWorkspace(
        predicate: (WorkspaceSnapshot) -> Boolean,
    ): WorkspaceSnapshot =
        withTimeoutOrNull(WORKSPACE_REFRESH_TIMEOUT_MILLIS) {
            repository.observeWorkspace().first(predicate)
        } ?: repository.currentWorkspace()

    private companion object {
        val SNOOZE_DURATION: Duration = Duration.ofMinutes(15)
        const val WORKSPACE_REFRESH_TIMEOUT_MILLIS = 5_000L
    }
}

@AndroidEntryPoint
class ReminderSystemEventReceiver : BroadcastReceiver() {
    // Lazy: boot and package-replacement broadcasts arrive before, and without,
    // an active vault runtime.
    @Inject
    lateinit var vaultRepository: Lazy<VaultRepository>

    @Inject
    lateinit var scheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                scheduler.reconcile(vaultRepository.get().currentWorkspace())
            } catch (_: IllegalStateException) {
                // No active vault runtime: nothing can be reconciled yet.
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}

object ReminderIntents {
    const val ACTION_OPEN_TASK = "app.opentasks.action.OPEN_REMINDER_TASK"
    const val ACTION_DELIVER = "app.opentasks.action.DELIVER_REMINDER"
    const val ACTION_SNOOZE = "app.opentasks.action.SNOOZE_REMINDER"
    const val ACTION_COMPLETE = "app.opentasks.action.COMPLETE_REMINDER_TASK"
    const val EXTRA_TASK_ID = "app.opentasks.extra.TASK_ID"
    const val EXTRA_REMINDER_ID = "app.opentasks.extra.REMINDER_ID"
    const val EXTRA_PRECISE = "app.opentasks.extra.PRECISE"

    fun data(verb: String, reminderId: String): Uri =
        Uri.Builder()
            .scheme("opentasks")
            .authority("reminder")
            .appendPath(verb)
            .appendPath(reminderId)
            .build()
}
