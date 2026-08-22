package app.opentasks.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle as ComposeTextStyle
import androidx.compose.ui.text.font.FontWeight as ComposeFontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as GlanceColorProvider
import app.opentasks.MainActivity
import app.opentasks.R
import app.opentasks.core.designsystem.OpenTasksColors
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.lock.AppLockController
import app.opentasks.lock.AppLockSettings
import app.opentasks.reminders.ReminderIntents
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val OpenTodayCountKey = intPreferencesKey("today_open_count")
private val OverdueCountKey = intPreferencesKey("today_overdue_count")
private val TitlesPermittedKey = booleanPreferencesKey("today_titles_permitted")
private val FocusTitleKeys =
    List(3) { index -> stringPreferencesKey("today_focus_title_$index") }
private val FocusIdKeys =
    List(3) { index -> stringPreferencesKey("today_focus_id_$index") }
private val FocusCompletableKeys =
    List(3) { index -> booleanPreferencesKey("today_focus_completable_$index") }

/** Carries the tapped row's task id into [CompleteFocusTaskAction]. */
private val TaskIdKey = ActionParameters.Key<String>("today_focus_task_id")

private val WidgetBackground =
    ColorProvider(day = OpenTasksColors.LightSurface, night = OpenTasksColors.LightSurface)
private val WidgetInk =
    ColorProvider(day = OpenTasksColors.LightInk, night = OpenTasksColors.LightInk)
private val WidgetMutedInk =
    ColorProvider(day = OpenTasksColors.LightMutedInk, night = OpenTasksColors.LightMutedInk)
private val WidgetAccent =
    ColorProvider(day = OpenTasksColors.LightEmber, night = OpenTasksColors.LightEmber)
private val CompactNarrowWidgetSize = DpSize(130.dp, 48.dp)
private val CompactWideWidgetSize = DpSize(200.dp, 48.dp)
private val ExpandedNarrowWidgetSize = DpSize(130.dp, 288.dp)
private val ExpandedWideWidgetSize = DpSize(200.dp, 288.dp)
private val TodayWidgetSizes = setOf(
    CompactNarrowWidgetSize,
    CompactWideWidgetSize,
    ExpandedNarrowWidgetSize,
    ExpandedWideWidgetSize,
)

internal fun todayWidgetShowsDetails(height: Dp): Boolean =
    height >= ExpandedNarrowWidgetSize.height

internal fun todayWidgetUsesWideCounts(width: Dp): Boolean =
    width >= CompactWideWidgetSize.width

internal fun todayWidgetUsesVerboseCounts(width: Dp, fontScale: Float): Boolean =
    todayWidgetUsesWideCounts(width) && fontScale <= 1.3f

internal fun widgetTitlesAuthorized(
    requested: Boolean,
    appLockController: AppLockController,
    appLockSettings: AppLockSettings,
): Boolean = requested &&
    !appLockSettings.titlePrivacy &&
    appLockController.isExternalActionAuthorized()

// Glance (1.1.1) has no typography-role system of its own -- `glance-material3`
// only converts a Compose Material3 `ColorScheme` to Glance `ColorProviders`,
// nothing about type. Real Material role *values* (size, weight) come from a
// plain, uncomposed `androidx.compose.material3.Typography()` baseline instead;
// `glanceTextStyle` below carries a role's size and weight into Glance's own
// `TextStyle`, replacing this file's previous ad hoc `fontSize` literals.
private val MaterialTypography = Typography()

private fun ComposeFontWeight?.toGlanceFontWeight(): FontWeight = when {
    this == null -> FontWeight.Normal
    weight >= 700 -> FontWeight.Bold
    weight >= 500 -> FontWeight.Medium
    else -> FontWeight.Normal
}

private fun glanceTextStyle(
    role: ComposeTextStyle,
    color: GlanceColorProvider,
): TextStyle = TextStyle(
    color = color,
    fontSize = role.fontSize,
    fontWeight = role.fontWeight.toGlanceFontWeight(),
)

/**
 * The Today Glance widget: today's open and overdue counts, up to three
 * focus-task titles (or a generic hidden label), and Open/Quick Add taps.
 *
 * Content is driven entirely by Glance state that [TodayWidgetPublisher]
 * writes -- this class never opens the vault itself.
 */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(TodayWidgetSizes)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            TodayWidgetContent()
        }
    }
}

/**
 * Registers [TodayWidget] to receive `APPWIDGET_UPDATE` broadcasts.
 *
 * [onUpdate] and [onEnabled] both ask whichever [TodayWidgetPublisher] is
 * currently active to republish immediately, so a widget instance newly
 * placed on the home screen shows real data instead of the "0 open today /
 * 0 overdue" defaults every fresh Glance state starts with, and without
 * waiting for an unrelated workspace change to trigger the next emission.
 */
class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        TodayWidgetPublisher.republishActive()
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        TodayWidgetPublisher.republishActive()
    }
}

/** Reconstructs the focus rows this composable can act on from Glance state. */
private fun Preferences.readFocusEntries(): List<FocusEntry> =
    FocusTitleKeys.indices.mapNotNull { index ->
        val title = this[FocusTitleKeys[index]] ?: return@mapNotNull null
        val taskId = this[FocusIdKeys[index]] ?: return@mapNotNull null
        val completable = this[FocusCompletableKeys[index]] ?: false
        FocusEntry(taskId = taskId, title = title, completable = completable)
    }

private fun writeFocusTitles(state: MutablePreferences, entries: List<FocusEntry>) {
    FocusTitleKeys.forEachIndexed { index, key ->
        val entry = entries.getOrNull(index)
        if (entry != null) state[key] = entry.title else state.remove(key)
    }
    FocusIdKeys.forEachIndexed { index, key ->
        val entry = entries.getOrNull(index)
        if (entry != null) state[key] = entry.taskId else state.remove(key)
    }
    FocusCompletableKeys.forEachIndexed { index, key ->
        val entry = entries.getOrNull(index)
        if (entry != null) state[key] = entry.completable else state.remove(key)
    }
}

@Composable
private fun TodayWidgetContent() {
    val prefs = currentState<Preferences>()
    val context = LocalContext.current
    val openToday = prefs[OpenTodayCountKey] ?: 0
    val overdue = prefs[OverdueCountKey] ?: 0
    val titlesPermitted = prefs[TitlesPermittedKey] ?: false
    val size = LocalSize.current
    val showsDetails = todayWidgetShowsDetails(size.height)
    val usesVerboseCounts = todayWidgetUsesVerboseCounts(
        width = size.width,
        fontScale = context.resources.configuration.fontScale,
    )
    val counts = context.getString(R.string.today_widget_counts, openToday, overdue)
    val quickAdd = context.getString(R.string.quick_add)
    val entries = if (showsDetails && titlesPermitted) prefs.readFocusEntries() else emptyList()

    if (!showsDetails) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetBackground)
                .cornerRadius(16.dp)
                .padding(start = 4.dp, end = 4.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (usesVerboseCounts) {
                    counts
                } else {
                    context.getString(
                        R.string.today_widget_compact_numbers,
                        openToday,
                        overdue,
                    )
                },
                maxLines = 1,
                style = glanceTextStyle(MaterialTypography.labelSmall, WidgetInk),
                modifier = GlanceModifier
                    .defaultWeight()
                    .semantics { contentDescription = counts },
            )
            Image(
                provider = ImageProvider(R.drawable.ic_quick_add),
                contentDescription = quickAdd,
                modifier = GlanceModifier
                    .size(48.dp)
                    .clickable(quickAddAction(context)),
            )
        }
        return
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBackground)
            .cornerRadius(16.dp)
            .padding(8.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            text = if (usesVerboseCounts) {
                counts
            } else {
                context.getString(R.string.today_widget_compact_numbers, openToday, overdue)
            },
            maxLines = 1,
            style = glanceTextStyle(
                if (usesVerboseCounts) {
                    MaterialTypography.titleMedium
                } else {
                    MaterialTypography.bodySmall
                },
                WidgetInk,
            ),
            modifier = GlanceModifier.semantics { contentDescription = counts },
        )
        if (titlesPermitted) {
            entries.forEach { entry ->
                FocusRow(entry, context)
            }
        } else {
            Text(
                text = context.getString(R.string.today_widget_titles_hidden),
                style = glanceTextStyle(MaterialTypography.bodyMedium, WidgetMutedInk),
                modifier = GlanceModifier.padding(top = 4.dp),
            )
        }
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable(quickAddAction(context)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = quickAdd,
                maxLines = 1,
                style = glanceTextStyle(MaterialTypography.labelLarge, WidgetAccent),
            )
        }
    }
}

private fun quickAddAction(context: Context) =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.QUICK_ADD_ACTION),
    )

/**
 * One focus-task row: the title tap-opens [entry]'s task through the
 * existing reminder open-task contract (`ReminderIntents.ACTION_OPEN_TASK`
 * + `EXTRA_TASK_ID`, the same extra `MainActivity` already reads -- no new
 * extra, no new `MainActivity` branch), and, only when [entry] is
 * [FocusEntry.completable], a trailing complete glyph runs
 * [CompleteFocusTaskAction]. That callback never writes Glance state itself
 * -- it only asks [TodayWidgetPublisher.completeActiveTask] to re-verify and
 * act, so nothing here can race the stop-time title clear.
 */
@Composable
private fun FocusRow(entry: FocusEntry, context: Context) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Text(
            text = entry.title,
            maxLines = 1,
            style = glanceTextStyle(MaterialTypography.bodyMedium, WidgetMutedInk),
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(
                    actionStartActivity(
                        Intent(context, MainActivity::class.java)
                            .setAction(ReminderIntents.ACTION_OPEN_TASK)
                            .putExtra(ReminderIntents.EXTRA_TASK_ID, entry.taskId),
                    ),
                ),
        )
        if (entry.completable) {
            Text(
                text = context.getString(R.string.today_widget_complete_glyph),
                style = glanceTextStyle(MaterialTypography.labelLarge, WidgetAccent),
                modifier = GlanceModifier
                    .padding(start = 8.dp)
                    .semantics {
                        contentDescription =
                            context.getString(R.string.today_widget_complete_task_description)
                    }
                    .clickable(
                        actionRunCallback<CompleteFocusTaskAction>(
                            actionParametersOf(TaskIdKey to entry.taskId),
                        ),
                    ),
            )
        }
    }
}

/**
 * Runs when the widget's complete glyph is tapped. Writes nothing itself --
 * every Glance state write happens inside [TodayWidgetPublisher], reached
 * only through [TodayWidgetPublisher.completeActiveTask], so a tap can never
 * bypass that publisher's [StopGatedWriter] gate.
 */
class CompleteFocusTaskAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val taskId = parameters[TaskIdKey] ?: return
        TodayWidgetPublisher.completeActiveTask(taskId)
    }
}

/**
 * Serializes every write a caller performs through [write], and guarantees
 * that once [stop] has run its own action, no [write] -- already in
 * flight, queued behind the gate, or requested afterwards -- can run its
 * action later. `stopped` is only ever read or written while holding
 * [gate], so the check in [write] and the flip in [stop] can never
 * interleave: a [write] that is inside the gate when [stop] is requested
 * finishes first (ordering is preserved, not corrupted), and every [write]
 * that reaches the gate after [stop]'s own action has run sees `stopped`
 * already `true` and is a no-op. [stop] itself only ever runs [action]
 * once, even if called more than once.
 *
 * This exists so [TodayWidgetPublisher] has one mechanism -- not two -- for
 * "no title write lands after the clear": both its continuous
 * `observeWorkspace()` collection and its one-off [TodayWidgetPublisher.republish]
 * (triggered by an arbitrary widget-host broadcast, so it can race a
 * closing slot) route through the same [StopGatedWriter] instance.
 */
internal class StopGatedWriter {
    private val gate = Mutex()
    private var stopped = false

    /** Runs [action] unless [stop] has already run; a no-op afterwards. */
    suspend fun write(action: suspend () -> Unit) {
        gate.withLock {
            if (!stopped) action()
        }
    }

    /** Marks this writer stopped and runs [action], exactly once. */
    suspend fun stop(action: suspend () -> Unit) {
        gate.withLock {
            if (stopped) return@withLock
            stopped = true
            action()
        }
    }
}

/** Invalidates captured widget actions without delaying the invalidating caller. */
internal class WidgetActionGate {
    private val generation = AtomicLong()
    private val dispatchGate = Mutex()

    fun capture(): Long = generation.get()

    fun invalidate() {
        generation.incrementAndGet()
    }

    suspend fun dispatch(
        capturedGeneration: Long,
        isAuthorized: () -> Boolean,
        action: suspend () -> Unit,
    ) {
        dispatchGate.withLock {
            if (isAuthorized() && capturedGeneration == generation.get()) action()
        }
    }
}

internal suspend fun WidgetActionGate.dispatchCompletion(
    capturedGeneration: Long,
    snapshot: WorkspaceSnapshot,
    taskId: String,
    today: LocalDate,
    zone: ZoneId,
    now: Instant,
    appLockController: AppLockController,
    appLockSettings: AppLockSettings,
    execute: suspend (DomainCommand) -> Unit,
) {
    val completable = computeTodayProjection(
        snapshot = snapshot,
        today = today,
        zone = zone,
        now = now,
        titlesPermitted = true,
    ).focusEntries.any { it.taskId == taskId && it.completable }
    if (completable) {
        dispatch(capturedGeneration, {
            appLockController.isExternalActionAuthorized() && !appLockSettings.titlePrivacy
        }) {
            execute(DomainCommand.CompleteTask(TaskId(taskId)))
        }
    }
}

/** Clears private widget rows while retaining the last aggregate counts. */
internal suspend fun clearTodayWidgetTitles(context: Context) {
    val ids = GlanceAppWidgetManager(context).getGlanceIds(TodayWidget::class.java)
    if (ids.isEmpty()) return
    for (id in ids) {
        updateAppWidgetState(context, id) { state ->
            state[TitlesPermittedKey] = false
            writeFocusTitles(state, emptyList())
        }
    }
    TodayWidget().updateAll(context)
}

/**
 * Publishes [TodayWidgetProjection] into Glance state for every placed
 * [TodayWidget].
 *
 * One instance is built fresh per active vault slot, mirroring
 * [app.opentasks.core.data.backup.AttachmentRuntime]: [start] launches an
 * `observeWorkspace()` collection on this publisher's own scope, and [stop]
 * cancels that collection and, through [writer], guarantees the clearing
 * write it performs can never be followed by a later title write -- from
 * that collection, which is merely cancelled cooperatively and so cannot
 * be relied on to have already stopped writing, or from [republish], which
 * a widget-host broadcast can trigger at any time, including the instant
 * [stop] itself runs. Glance state lives under `filesDir`, outside the
 * Auto Backup allow-list, so it is never backed up.
 */
class TodayWidgetPublisher(
    private val context: Context,
    private val repository: VaultRepository,
    private val appLockController: AppLockController,
    private val appLockSettings: AppLockSettings,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writer = StopGatedWriter()
    private val actionGate = WidgetActionGate()
    private var collection: Job? = null

    // Read from the internal snapshot collection and written from whatever
    // external caller reacts to lock/privacy-setting changes (AppModule's
    // `provideActiveVaultSession`) -- both can run on different `Dispatchers
    // .Default` threads, so this needs a visibility guarantee a plain `var`
    // does not give.
    @Volatile
    private var titlesPermitted: Boolean = true

    private fun actionAuthorized(): Boolean =
        appLockController.isExternalActionAuthorized() && !appLockSettings.titlePrivacy

    /**
     * Starts collecting the slot's workspace and republishing the
     * projection on every change, using [titlesPermitted] for every write
     * until [setTitlesPermitted] changes it.
     */
    fun start(titlesPermitted: Boolean = true) {
        this.titlesPermitted = titlesPermitted
        collection?.cancel()
        collection = scope.launch {
            repository.observeWorkspace().collect { snapshot ->
                writer.write { writeProjection(snapshot) }
            }
        }
        synchronized(activeLock) { active = this }
    }

    /** Stops collection and clears titles -- but not counts -- from Glance state. */
    fun stop() {
        synchronized(activeLock) {
            actionGate.invalidate()
            if (active === this) active = null
        }
        collection?.cancel()
        collection = null
        scope.launch { writer.stop { clearTodayWidgetTitles(context) } }
    }

    /**
     * Re-publishes the latest known workspace snapshot without waiting for
     * the next emission. `observeWorkspace()` is a `StateFlow`, so `.value`
     * always holds the current snapshot even when nothing has changed
     * since [start].
     */
    fun republish() {
        val snapshot = repository.observeWorkspace().value
        scope.launch { writer.write { writeProjection(snapshot) } }
    }

    /**
     * Updates whether titles may be shown and, if that changed, republishes
     * immediately through [republish]'s existing gated write -- a lock,
     * unlock, or title-privacy flip is reflected without waiting for the
     * next unrelated workspace change.
     */
    fun setTitlesPermitted(titlesPermitted: Boolean) {
        if (!titlesPermitted) actionGate.invalidate()
        if (this.titlesPermitted == titlesPermitted) return
        this.titlesPermitted = titlesPermitted
        republish()
    }

    /**
     * Re-verifies [taskId] against a freshly read workspace before acting,
     * so a stale, concealed, missing, blocked, or no-longer-today tap from
     * the widget surface can never complete a task. The captured action
     * generation must still be current, the live [actionAuthorized]
     * predicate must permit the action, and [taskId] must still appear in
     * the recomputed projection as a completable row. A repository rejection
     * is not distinguished from "not authorised"; either way the latest
     * truth is republished through [writer].
     */
    private fun captureCompletion(taskId: String): (() -> Unit)? {
        if (!actionAuthorized()) return null
        val capturedGeneration = actionGate.capture()
        return { completeTask(taskId, capturedGeneration) }
    }

    private fun completeTask(taskId: String, capturedGeneration: Long) {
        scope.launch {
            val snapshot = repository.observeWorkspace().value
            actionGate.dispatchCompletion(
                capturedGeneration = capturedGeneration,
                snapshot = snapshot,
                taskId = taskId,
                today = LocalDate.now(zone),
                zone = zone,
                now = Instant.now(),
                appLockController = appLockController,
                appLockSettings = appLockSettings,
                execute = repository::execute,
            )
            val latest = repository.observeWorkspace().value
            writer.write { writeProjection(latest) }
        }
    }

    private suspend fun writeProjection(snapshot: WorkspaceSnapshot) {
        val projection = computeTodayProjection(
            snapshot = snapshot,
            today = LocalDate.now(zone),
            zone = zone,
            now = Instant.now(),
            titlesPermitted = titlesPermitted,
        )
        val ids = GlanceAppWidgetManager(context).getGlanceIds(TodayWidget::class.java)
        if (ids.isEmpty()) return
        for (id in ids) {
            updateAppWidgetState(context, id) { state ->
                val titlesAuthorized = widgetTitlesAuthorized(
                    requested = titlesPermitted,
                    appLockController = appLockController,
                    appLockSettings = appLockSettings,
                )
                state[OpenTodayCountKey] = projection.openTodayCount
                state[OverdueCountKey] = projection.overdueCount
                state[TitlesPermittedKey] = titlesAuthorized
                writeFocusTitles(
                    state,
                    if (titlesAuthorized) projection.focusEntries else emptyList(),
                )
            }
        }
        TodayWidget().updateAll(context)
    }

    companion object {
        private val activeLock = Any()

        // The context this instance holds is always the process-lifetime
        // Application context (see AppModule's `@ApplicationContext`
        // constructor parameter), never an Activity, so this static
        // reference cannot leak a shorter-lived context.
        @Volatile
        @SuppressLint("StaticFieldLeak")
        private var active: TodayWidgetPublisher? = null

        /** Asks whichever publisher is active, if any, to [republish] now. */
        fun republishActive() {
            val publisher = synchronized(activeLock) { active }
            publisher?.republish()
        }

        /**
         * Asks whichever publisher is active, if any, to [completeTask] for
         * [taskId] -- the sole entry point [CompleteFocusTaskAction] uses, so
         * a tap can only ever reach the currently active slot's own instance
         * (and its own [StopGatedWriter] gate), never a replaced or closed
         * one. A no-op when no publisher is active.
         */
        fun completeActiveTask(taskId: String) {
            val action = synchronized(activeLock) { active?.captureCompletion(taskId) }
            action?.invoke()
        }
    }
}
