package app.opentasks.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.opentasks.MainActivity
import app.opentasks.R
import app.opentasks.core.designsystem.OpenTasksColors
import app.opentasks.core.domain.VaultRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

/** Registers [TodayWidget] to receive `APPWIDGET_UPDATE` broadcasts. */
class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
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
            style = TextStyle(color = WidgetInk, fontWeight = FontWeight.Bold, fontSize = 16.sp),
        )
        if (titlesPermitted) {
            titles.forEach { title ->
                Text(
                    text = title,
                    maxLines = 1,
                    style = TextStyle(color = WidgetMutedInk, fontSize = 14.sp),
                    modifier = GlanceModifier.padding(top = 4.dp),
                )
            }
        } else {
            Text(
                text = context.getString(R.string.today_widget_titles_hidden),
                style = TextStyle(color = WidgetMutedInk, fontSize = 14.sp),
                modifier = GlanceModifier.padding(top = 4.dp),
            )
        }
        Text(
            text = context.getString(R.string.quick_add),
            style = TextStyle(color = WidgetAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp),
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
 * Publishes [TodayWidgetProjection] into Glance state for every placed
 * [TodayWidget].
 *
 * One instance is built fresh per active vault slot, mirroring
 * [app.opentasks.core.data.backup.AttachmentRuntime]: [start] launches an
 * `observeWorkspace()` collection on this publisher's own scope, and [stop]
 * cancels only that collection -- never the scope itself -- so the titles
 * it then clears always finish writing even though the slot that asked for
 * the clear is already gone. Glance state lives under `filesDir`, outside
 * the Auto Backup allow-list, so it is never backed up.
 */
class TodayWidgetPublisher(
    private val context: Context,
    private val repository: VaultRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collection: Job? = null

    /**
     * Starts collecting the slot's workspace and republishing the
     * projection on every change. [titlesPermitted] is unconditionally
     * `true` until Task 10 wires real lock/privacy state through here.
     */
    fun start(titlesPermitted: Boolean = true) {
        collection?.cancel()
        collection = scope.launch {
            repository.observeWorkspace().collect { snapshot ->
                val projection = computeTodayProjection(
                    snapshot = snapshot,
                    today = LocalDate.now(zone),
                    zone = zone,
                    titlesPermitted = titlesPermitted,
                )
                publish(projection, titlesPermitted)
            }
        }
    }

    /** Stops collection and clears titles -- but not counts -- from Glance state. */
    fun stop() {
        collection?.cancel()
        collection = null
        scope.launch { clearTitles() }
    }

    private suspend fun publish(projection: TodayWidgetProjection, titlesPermitted: Boolean) {
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
}
