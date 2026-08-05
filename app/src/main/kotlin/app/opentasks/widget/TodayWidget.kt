package app.opentasks.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle as ComposeTextStyle
import androidx.compose.ui.text.font.FontWeight as ComposeFontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as GlanceColorProvider
import app.opentasks.MainActivity
import app.opentasks.R
import app.opentasks.core.designsystem.OpenTasksColors
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.WorkspaceSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

/** Matches the extra `MainActivity.handleIntent` reads to open Quick Add. */
private val QuickAddKey = ActionParameters.Key<Boolean>("open_quick_add")

private val WidgetBackground =
    ColorProvider(day = OpenTasksColors.LightSurface, night = OpenTasksColors.DarkSurface)
private val WidgetInk =
    ColorProvider(day = OpenTasksColors.LightInk, night = OpenTasksColors.DarkInk)
private val WidgetMutedInk =
    ColorProvider(day = OpenTasksColors.LightMutedInk, night = OpenTasksColors.DarkMutedInk)
private val WidgetAccent =
    ColorProvider(day = OpenTasksColors.LightEmber, night = OpenTasksColors.DarkEmber)

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

@Composable
private fun TodayWidgetContent() {
    val prefs = currentState<Preferences>()
    val context = LocalContext.current
    val openToday = prefs[OpenTodayCountKey] ?: 0
    val overdue = prefs[OverdueCountKey] ?: 0
    val titlesPermitted = prefs[TitlesPermittedKey] ?: false
    val titles = if (titlesPermitted) FocusTitleKeys.mapNotNull { prefs[it] } else emptyList()

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBackground)
            .cornerRadius(16.dp)
            .padding(16.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            text = context.getString(R.string.today_widget_counts, openToday, overdue),
            style = glanceTextStyle(MaterialTypography.titleMedium, WidgetInk),
        )
        if (titlesPermitted) {
            titles.forEach { title ->
                Text(
                    text = title,
                    maxLines = 1,
                    style = glanceTextStyle(MaterialTypography.bodyMedium, WidgetMutedInk),
                    modifier = GlanceModifier.padding(top = 4.dp),
                )
            }
        } else {
            Text(
                text = context.getString(R.string.today_widget_titles_hidden),
                style = glanceTextStyle(MaterialTypography.bodyMedium, WidgetMutedInk),
                modifier = GlanceModifier.padding(top = 4.dp),
            )
        }
        Text(
            text = context.getString(R.string.quick_add),
            style = glanceTextStyle(MaterialTypography.labelLarge, WidgetAccent),
            modifier = GlanceModifier
                .padding(top = 8.dp)
                .clickable(
                    actionStartActivity<MainActivity>(
                        parameters = actionParametersOf(QuickAddKey to true),
                    ),
                ),
        )
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
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writer = StopGatedWriter()
    private var collection: Job? = null

    // Read from the internal snapshot collection and written from whatever
    // external caller reacts to lock/privacy-setting changes (AppModule's
    // `provideActiveVaultSession`) -- both can run on different `Dispatchers
    // .Default` threads, so this needs a visibility guarantee a plain `var`
    // does not give.
    @Volatile
    private var titlesPermitted: Boolean = true

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
        synchronized(activeLock) { if (active === this) active = null }
        collection?.cancel()
        collection = null
        scope.launch { writer.stop { clearTitles() } }
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
        if (this.titlesPermitted == titlesPermitted) return
        this.titlesPermitted = titlesPermitted
        republish()
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
                state[OpenTodayCountKey] = projection.openTodayCount
                state[OverdueCountKey] = projection.overdueCount
                state[TitlesPermittedKey] = titlesPermitted
                writeFocusTitles(state, projection.focusTitles)
            }
        }
        TodayWidget().updateAll(context)
    }

    private suspend fun clearTitles() {
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

    private fun writeFocusTitles(state: MutablePreferences, titles: List<String>) {
        FocusTitleKeys.forEachIndexed { index, key ->
            val title = titles.getOrNull(index)
            if (title != null) state[key] = title else state.remove(key)
        }
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
    }
}
